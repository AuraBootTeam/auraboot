/**
 * Replay UI MVP — Admin Agent Runs page Playwright E2E (ACP A.2 follow-up)
 *
 * Backend route:  GET /api/admin/agent-runs (AgentRunController, gated by AdminRoleInterceptor)
 * Frontend page:  web-admin/app/plugins/core-aurabot/pages/admin/agent-runs.tsx
 *                 → registered as resource `aurabot.admin.runs` at /admin/agent-runs
 *                 (web-admin/app/plugins/core-aurabot/resources.ts)
 *
 * Why this spec exists:
 *   `94b97ad6` shipped the page + a vitest covering the React wiring
 *   (web-admin/app/plugins/core-aurabot/__tests__/AgentRunsPage.test.tsx),
 *   but no Playwright covered the sidebar→list→drawer→user-action chain
 *   end-to-end against a live backend. This spec drives the real Spring
 *   Boot REST endpoints on :6443 with rows seeded directly into
 *   ab_agent_run / ab_agent_action / ab_agent_bif via node-postgres.
 *
 * 14-dimension coverage (per docs/standards/core/testing-e2e-web.md):
 *   D1 menu nav (sidebar click, NOT page.goto direct nav)
 *   D2 list rendering (≥3 rows render with correct columns)
 *   D3 status filter changes URL state and filters rows
 *   D6 specific data-value assertions match API ground truth
 *      (status / cost / duration cell text equals API response)
 *   D7 detail drawer surfaces concrete ids/text in 4+ sub-areas
 *      (metadata / actions / interrupts / child runs)
 *   D14 user action (filter change + action expand) → state change
 *      visible in UI (URL + drawer expand)
 *
 *   D4/D5/D8/D9/D10/D11/D12/D13 are not applicable — this surface is
 *   read-only (Replay UI MVP §6 read-only contract; AgentRunController
 *   exposes no write endpoints).
 *
 * Hard red-line audit (per spec §T2 + memory feedback_no_fake_100_percent_claim):
 *   - 0 page.goto('/admin/agent-runs') direct navigation in spec body
 *   - 0 page.request.put|post|delete (one GET for ground-truth fetch only)
 *   - 0 waitForTimeout / afterAll cleanup
 *   - 0 retries:N / test.skip wrapping a product gap
 *   - UI ops (page.click / locator.click) > page.request count
 *   - Specific value assertions, never "toBeVisible"-only on primary data
 *
 * Test data lifecycle: seed via node-postgres in beforeAll; do NOT cleanup in
 * afterAll (memory feedback_save_test_output / testing-e2e-web.md
 * "测试痕迹" rule). Centralised teardown in tests/global-teardown.ts
 * deletes E2EM_* / E2ELR_* / E2ELA_* / E2ELB_* prefixed rows.
 */

import { test, expect, type Page } from '../../fixtures';
import { Client } from 'pg';
import { randomUUID } from 'crypto';
import { ADMIN_TENANT_ID } from './_real-backend-helpers';

// ---------------------------------------------------------------------------
// Constants — pid prefixes are unique per spec so global teardown can target
// these rows and the assertions can find specific runs deterministically.
// ---------------------------------------------------------------------------

const RUN_PREFIX = 'E2ELR'; // ab_agent_run
const ACTION_PREFIX = 'E2ELA'; // ab_agent_action
const BIF_PREFIX = 'E2ELB'; // ab_agent_bif
const INTERRUPT_PREFIX = 'E2ELI'; // ab_agent_interrupt_log
const TASK_PREFIX = 'E2ELT'; // ab_agent_task
const TURN_PREFIX = 'E2ELU'; // conversation turn metadata
const CONVERSATION_PREFIX = 'E2ELC'; // ab_im_conversation.name
const MENU_PREFIX = 'E2EM_AR'; // ab_menu (caught by /^E2EM_/ teardown)
const MENU_PARENT_PREFIX = 'E2EM_ARP';

const RUN_OK = `${RUN_PREFIX}_OK_${Date.now().toString(36).toUpperCase()}`.slice(0, 26);
const RUN_FAILED = `${RUN_PREFIX}_FL_${Date.now().toString(36).toUpperCase()}`.slice(0, 26);
const RUN_RUNNING = `${RUN_PREFIX}_RU_${Date.now().toString(36).toUpperCase()}`.slice(0, 26);
const RUN_CHILD = `${RUN_PREFIX}_CH_${Date.now().toString(36).toUpperCase()}`.slice(0, 26);

const ACTION_PID = `${ACTION_PREFIX}_${Date.now().toString(36).toUpperCase()}`.slice(0, 26);
const BIF_PID = `${BIF_PREFIX}_${Date.now().toString(36).toUpperCase()}`.slice(0, 26);
const INTERRUPT_PID = `${INTERRUPT_PREFIX}_${Date.now().toString(36).toUpperCase()}`.slice(0, 26);
const TRACE_ID = randomUUID();

const TASK_ID = `${TASK_PREFIX}_${Date.now().toString(36).toUpperCase()}`.slice(0, 26);
const TURN_ID = `${TURN_PREFIX}_${Date.now().toString(36).toUpperCase()}`.slice(0, 26);
const CONVERSATION_ID = Date.now() * 1000 + Math.floor(Math.random() * 900);
const INBOUND_MESSAGE_ID = CONVERSATION_ID + 1;
const OUTBOUND_MESSAGE_ID = CONVERSATION_ID + 2;
const RESULT_CONTRACT_ID = `rc-${ACTION_PID}`;
const AGENT_CODE = 'aurabot.replay.e2e';
// ab_agent_bif.intent is VARCHAR(32). Use a short stable code, not free-form text.
const INTENT_TEXT = 'replay_e2e_query';
const ACTION_INTENT_TEXT = 'replay e2e action intent — sales.list';
const TURN_USER_MESSAGE = '统计客户信息';
const TURN_FINAL_RESPONSE = '客户信息统计完成';

// Stable ground-truth values that MUST be reflected in the UI cells.
// NOTE: ab_agent_run.total_cost is NUMERIC(10,2) — values seeded with more
// than 2 decimal places get rounded, so use 2-decimal values to avoid
// "API returned 0.12 but UI rendered $0.12 vs test expected $0.1235" drift.
const RUN_OK_COST = 0.13;
const RUN_FAILED_COST = 0.03;
// Duration coverage exercises both branches: RUN_OK stores duration_ms
// directly, RUN_FAILED derives it from completed_at - created_at.
const RUN_OK_COMPLETED_OFFSET_SEC = 5; // → 5000ms → "5.00s"
const RUN_FAILED_COMPLETED_OFFSET_SEC = 1; // → 1000ms → "1.00s"

// ---------------------------------------------------------------------------
// SQL helpers — keep this spec independent from a shell `psql` binary. The
// isolated frontend E2E image intentionally ships Node dependencies, not CLI
// database clients.
// ---------------------------------------------------------------------------

const PG_CONN = {
  host: process.env.PGHOST ?? process.env.PG_HOST ?? 'localhost',
  port: Number(process.env.PGPORT ?? process.env.PG_PORT ?? '5432'),
  user: process.env.PGUSER ?? process.env.PG_USER ?? process.env.USER ?? 'ghj',
  database: process.env.PGDATABASE ?? process.env.PG_DB ?? 'aura_boot',
  password: process.env.PGPASSWORD ?? process.env.PG_PASSWORD,
};

async function psql(sql: string): Promise<string> {
  return withPg(async (client) => {
    const result = await client.query(sql);
    const rows = Array.isArray(result)
      ? result.flatMap((item) => item.rows ?? [])
      : result.rows ?? [];
    return rows
      .map((row) => Object.values(row).map((value) => String(value)).join('|'))
      .join('\n')
      .trim();
  });
}

async function withPg<T>(fn: (client: Client) => Promise<T>): Promise<T> {
  const client = new Client(PG_CONN);
  await client.connect();
  try {
    return await fn(client);
  } finally {
    await client.end();
  }
}

function sqlString(value: string): string {
  return `'${value.replace(/'/g, "''")}'`;
}

function sqlNullableString(value?: string | null): string {
  return value ? sqlString(value) : 'NULL';
}

function sqlNullableNumber(value: number | null): string {
  return value === null ? 'NULL' : value.toString();
}

function sqlExpression(value?: string | null, fallback = 'NOW()'): string {
  return value === null ? 'NULL' : value ?? fallback;
}

function assertRow(raw: string, description: string): string {
  if (!raw) {
    throw new Error(`Expected SQL row for ${description}`);
  }
  return raw;
}

interface SeededAgentRun {
  pid: string;
  agentCode: string;
  status: string;
  costUsd: number | null;
  durationMs: number | null;
  parentRunId: string | null;
}

async function seedAgentRun(args: {
  pid: string;
  agentCode: string;
  status: string;
  costUsd: number | null;
  parentRunId?: string | null;
  subtaskOrigin?: string | null;
  taskId?: string;
  createdAtSqlExpr?: string;
  completedAtSqlExpr?: string | null;
  durationMs?: number | null;
}): Promise<SeededAgentRun> {
  const cost = sqlNullableNumber(args.costUsd);
  const parent = sqlNullableString(args.parentRunId);
  const subtaskOrigin = sqlNullableString(args.subtaskOrigin);
  const taskId = args.taskId ?? TASK_ID;
  const completedAt = sqlExpression(args.completedAtSqlExpr);
  const createdAt = args.createdAtSqlExpr ?? 'NOW()';
  const duration = sqlNullableNumber(args.durationMs ?? null);
  await psql(`
    INSERT INTO ab_agent_run
      (pid, tenant_id, task_id, agent_id, run_status,
       total_cost, duration_ms, parent_run_id, subtask_origin,
       started_at, completed_at, created_at)
    VALUES
      (${sqlString(args.pid)}, ${ADMIN_TENANT_ID}, ${sqlString(taskId)},
       ${sqlString(args.agentCode)}, ${sqlString(args.status)}, ${cost},
       ${duration}, ${parent}, ${subtaskOrigin},
       ${createdAt}, ${completedAt}, ${createdAt});
  `);
  return {
    pid: args.pid,
    agentCode: args.agentCode,
    status: args.status,
    costUsd: args.costUsd,
    durationMs: args.durationMs ?? null,
    parentRunId: args.parentRunId ?? null,
  };
}

async function seedAgentAction(args: {
  pid: string;
  runId: string;
  actionCode: string;
  intentSummary: string;
}): Promise<void> {
  await psql(`
    INSERT INTO ab_agent_action
      (pid, tenant_id, run_id, action_code, action_type,
       target_model, action_status, intent_summary, step_index,
       cost_usd, token_usage, executed_at)
    VALUES
      (${sqlString(args.pid)}, ${ADMIN_TENANT_ID}, ${sqlString(args.runId)},
       ${sqlString(args.actionCode)}, 'tool_call',
       'replay_e2e', 'success', ${sqlString(args.intentSummary)}, 1,
       0.001000, 100, NOW());
  `);
}

async function seedAgentBif(args: { pid: string; runId: string; intent: string }): Promise<void> {
  await psql(`
    INSERT INTO ab_agent_bif
      (pid, tenant_id, run_id, nl_input, intent, primary_object,
       confidence, risk_level, dispatched_skill, channel, created_at)
    VALUES
      (${sqlString(args.pid)}, ${ADMIN_TENANT_ID}, ${sqlString(args.runId)},
       'replay e2e nl input', ${sqlString(args.intent)}, 'AgentRun',
       '{"score":0.9}'::jsonb, 'L0', 'replay.e2e.skill',
       'chat', NOW());
  `);
}

async function seedInterruptForRun(args: {
  pid: string;
  activeRunId: string;
  sessionId: string;
}): Promise<void> {
  await psql(`
    INSERT INTO ab_agent_interrupt_log
      (pid, tenant_id, session_id, active_run_id, new_message_excerpt,
       sub_policy, classifier_tier, confidence, reason, action_taken, created_at)
    VALUES
      (${sqlString(args.pid)}, ${ADMIN_TENANT_ID}, ${sqlString(args.sessionId)}, ${sqlString(args.activeRunId)},
       'replay e2e interrupt excerpt', 'replace_intent', 'keyword',
       0.91, 'replay e2e seeded', 'active_run_cancelled', NOW());
  `);
}

async function seedAiTraceForRun(args: { traceId: string; runId: string }): Promise<void> {
  await psql(`
    INSERT INTO ab_ai_trace
      (trace_id, tenant_id, session_id, name, input, output, status,
       metadata, start_time, end_time, duration_ms, total_input_tokens,
       total_output_tokens, total_cost)
    VALUES
      (${sqlString(args.traceId)}, ${ADMIN_TENANT_ID}, ${sqlString(args.runId)},
       'chat', 'replay e2e trace input', 'replay e2e trace output', 'success',
       jsonb_build_object('agentCode', ${sqlString(AGENT_CODE)}, 'taskPid', ${sqlString(TASK_ID)}),
       NOW() - INTERVAL '3 seconds', NOW(), 3000, 100, 50, 0.001000);
  `);
}

async function seedAgentTaskForConversationTurn(): Promise<void> {
  const inputData = {
    turnId: TURN_ID,
    conversationId: CONVERSATION_ID,
    inboundMessageId: INBOUND_MESSAGE_ID,
    triageBucket: 'acp_run',
    userMessage: TURN_USER_MESSAGE,
  };
  const outputData = {
    finalResponse: TURN_FINAL_RESPONSE,
  };
  await psql(`
    INSERT INTO ab_agent_task
      (pid, tenant_id, title, description, task_status, task_priority,
       assignee_type, assignee_id, input_data, output_data,
       created_at, updated_at, completed_at)
    VALUES
      (${sqlString(TASK_ID)}, ${ADMIN_TENANT_ID}, 'Replay E2E conversation turn',
       ${sqlString(TURN_USER_MESSAGE)}, 'completed', 'normal',
       'ai', ${sqlString(AGENT_CODE)}, ${sqlString(JSON.stringify(inputData))},
       ${sqlString(JSON.stringify(outputData))},
       NOW() - INTERVAL '10 seconds', NOW(), NOW() - INTERVAL '5 seconds');
  `);
}

async function seedConversationTurnMessages(): Promise<void> {
  await psql(`
    INSERT INTO ab_im_conversation
      (id, tenant_id, type, name, max_seq, created_at, updated_at)
    VALUES
      (${CONVERSATION_ID}, ${ADMIN_TENANT_ID}, 'BOT',
       ${sqlString(`${CONVERSATION_PREFIX}_${RUN_OK}`)}, 2,
       NOW() - INTERVAL '10 seconds', NOW());

    INSERT INTO ab_im_message
      (id, conversation_id, tenant_id, sender_id, sender_type, seq,
       message_type, content, client_msg_id, triage_bucket,
       triage_confidence, triage_reason_codes, created_at)
    VALUES
      (${INBOUND_MESSAGE_ID}, ${CONVERSATION_ID}, ${ADMIN_TENANT_ID},
       1, 'human', 1, 'text', ${sqlString(TURN_USER_MESSAGE)},
       ${sqlString(`in-${TURN_ID}`)}, 'acp_run', 0.92,
       '["agent-runs-e2e"]'::jsonb, NOW() - INTERVAL '9 seconds');

    INSERT INTO ab_im_message
      (id, conversation_id, tenant_id, sender_id, sender_type, seq,
       message_type, content, client_msg_id, thinking_content,
       thinking_signature, created_at)
    VALUES
      (${OUTBOUND_MESSAGE_ID}, ${CONVERSATION_ID}, ${ADMIN_TENANT_ID},
       2, 'agent', 2, 'ai_response', ${sqlString(TURN_FINAL_RESPONSE)},
       ${sqlString(`out-${TURN_ID}`)}, 'e2e thinking trace',
       'e2e-thinking-signature', NOW() - INTERVAL '5 seconds');
  `);
}

// Idempotent menu upsert (mirrors _real-backend-helpers.ts upsertMenu).
async function upsertAdminAgentRunsMenu(): Promise<{ menuId: string; ownedId: string | null }> {
  const rand = Math.random().toString(36).slice(2, 8).toUpperCase();
  const menuPid = `${MENU_PREFIX}_${rand}`.slice(0, 26);
  const path = '/admin/agent-runs';
  return withPg(async (client) => {
    await client.query('BEGIN');
    try {
      await client.query(
        'SELECT pg_advisory_xact_lock(hashtext($1)::bigint)',
        [`ab_menu_upsert:${path}`],
      );
      const existing = await client.query<{ id: string }>(
        `SELECT id
           FROM ab_menu
          WHERE tenant_id = $1 AND path = $2 AND deleted_flag = false
          LIMIT 1`,
        [ADMIN_TENANT_ID, path],
      );
      if (existing.rows[0]) {
        await client.query('COMMIT');
        return { menuId: String(existing.rows[0].id), ownedId: null };
      }
      const parentId = await resolveOrCreateAiCenterMenuId(client);
      const inserted = await client.query<{ id: string }>(
        `INSERT INTO ab_menu
           (id, pid, tenant_id, parent_id, code, name, path, type,
            permission_code, visible, order_no, status)
         VALUES
           ((EXTRACT(EPOCH FROM NOW()) * 1000 + floor(random() * 1000000))::bigint,
            $1, $2, $3, NULL, 'Agent 运行记录', $4, 1,
            NULL, true, 100, 'active')
         RETURNING id`,
        [menuPid, ADMIN_TENANT_ID, parentId, path],
      );
      await client.query('COMMIT');
      const id = String(assertRow(String(inserted.rows[0]?.id ?? ''), 'inserted menu id'));
      return { menuId: id, ownedId: id };
    } catch (error) {
      await client.query('ROLLBACK');
      throw error;
    }
  });
}

async function resolveOrCreateAiCenterMenuId(client: Client): Promise<string> {
  const existing = await client.query<{ id: string }>(
    `SELECT id
       FROM ab_menu
      WHERE tenant_id = $1
        AND deleted_flag = false
        AND (
          code = 'ai_center'
          OR name IN ('AI 中心', 'AI Center')
          OR id IN (
            SELECT parent_id
              FROM ab_menu
             WHERE tenant_id = $1
               AND deleted_flag = false
               AND path LIKE '/aurabot/%'
               AND parent_id IS NOT NULL
          )
        )
      ORDER BY CASE
        WHEN code = 'ai_center' THEN 0
        WHEN name = 'AI 中心' THEN 1
        ELSE 2
      END
      LIMIT 1`,
    [ADMIN_TENANT_ID],
  );
  if (existing.rows[0]) {
    return String(existing.rows[0].id);
  }

  const parentPid = `${MENU_PARENT_PREFIX}_${Math.random()
    .toString(36)
    .slice(2, 8)
    .toUpperCase()}`.slice(0, 26);
  const inserted = await client.query<{ id: string }>(
    `INSERT INTO ab_menu
       (id, pid, tenant_id, parent_id, code, name, path, icon, type,
        permission_code, visible, order_no, status)
     VALUES
       ((EXTRACT(EPOCH FROM NOW()) * 1000 + floor(random() * 1000000))::bigint,
        $1, $2, NULL, NULL, 'AI 中心', NULL, 'brain', 0,
        NULL, true, 800, 'active')
     RETURNING id`,
    [parentPid, ADMIN_TENANT_ID],
  );
  return String(assertRow(String(inserted.rows[0]?.id ?? ''), 'AI Center menu id'));
}

// ---------------------------------------------------------------------------
// Sidebar navigation — D1: must reach the page from a sidebar click,
// not page.goto('/admin/agent-runs') direct nav.
// ---------------------------------------------------------------------------

async function navigateAgentRunsViaSidebar(page: Page): Promise<void> {
  await page.goto('/');
  await page.waitForLoadState('domcontentloaded');

  const nav = page.locator('nav').first();
  await nav.waitFor({ state: 'visible', timeout: 10_000 });

  // Expand the parent group "AI 中心" / "AI Center" only when needed. The
  // sidebar persists expanded groups between tests, so a blind click would
  // collapse an already-open group and unmount the leaf link.
  const leaf = nav.locator('a[href="/admin/agent-runs"]').first();
  if ((await leaf.count()) === 0 || !(await leaf.isVisible().catch(() => false))) {
    const aiCenter = nav.getByRole('button', { name: /AI 中心|AI Center/ }).first();
    await aiCenter.waitFor({ state: 'visible', timeout: 10_000 });
    await aiCenter.evaluate((el: HTMLElement) => el.click());
  }

  // Click the leaf "Agent 运行记录"
  await leaf.waitFor({ state: 'attached', timeout: 8_000 });

  const listResponsePromise = page.waitForResponse(
    (r) => r.url().includes('/api/admin/agent-runs') && !/\/[A-Z0-9]{20,}/.test(r.url()) && r.status() === 200,
    { timeout: 20_000 },
  );
  await leaf.evaluate((el: HTMLElement) => el.click());
  await listResponsePromise;

  await expect(page.locator('[data-testid="agent-runs-page"]')).toBeVisible({
    timeout: 10_000,
  });
  await expect(page.locator('[data-testid="runs-table"]')).toBeVisible({
    timeout: 10_000,
  });
}

// ---------------------------------------------------------------------------
// File-level setup — seed BOTH the sidebar menu (used by both describes
// below) AND the agent run rows. Putting it at file scope means AR-005 in
// the empty-state describe also reaches the seeded leaf via sidebar.
// ---------------------------------------------------------------------------

test.beforeAll(async () => {
  await upsertAdminAgentRunsMenu();
});

// ---------------------------------------------------------------------------
// Test suite (real backend)
// ---------------------------------------------------------------------------

test.describe('Replay UI — Admin Agent Runs (real backend, ACP A.2)', () => {
  // Drawer open + pagination/filter state changes need predictable order.
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60_000);

  test.beforeAll(async () => {
    await seedAgentTaskForConversationTurn();
    await seedConversationTurnMessages();

    // Order matters for "ORDER BY created_at DESC" — give RUN_OK the
    // newest timestamp so it lands on page 0 deterministically. Use
    // staggered NOW() expressions: succeeded > failed > running.
    //
    // Pin duration values so the UI cell assertions are stable. RUN_OK uses
    // stored duration_ms; RUN_FAILED exercises the API fallback derivation.
    //
    // NOTE: not seeding status='running' — the platform reaps orphaned
    // running runs to 'failed' on restart, which would flake assertions.
    // Use 'cancelled' (terminal) with completedAtSqlExpr=null so the
    // duration-derivation falls into the `else { 0L }` branch — UI shows
    // "0ms", exercising the no-completion rendering path.
    await seedAgentRun({
      pid: RUN_RUNNING,
      agentCode: 'aurabot.planner.e2e',
      status: 'cancelled',
      costUsd: null,
      completedAtSqlExpr: null,
      createdAtSqlExpr: `NOW() - INTERVAL '120 seconds'`,
    });
    await seedAgentRun({
      pid: RUN_FAILED,
      agentCode: AGENT_CODE,
      status: 'failed',
      costUsd: RUN_FAILED_COST,
      // Created 60s ago, completed 59s ago → derived 1000ms → "1.00s".
      createdAtSqlExpr: `NOW() - INTERVAL '60 seconds'`,
      completedAtSqlExpr: `NOW() - INTERVAL '${60 - RUN_FAILED_COMPLETED_OFFSET_SEC} seconds'`,
    });
    await seedAgentRun({
      pid: RUN_OK,
      agentCode: AGENT_CODE,
      status: 'succeeded',
      costUsd: RUN_OK_COST,
      durationMs: RUN_OK_COMPLETED_OFFSET_SEC * 1000,
      // Stored duration_ms drives this row; timestamps stay coherent.
      createdAtSqlExpr: `NOW() - INTERVAL '10 seconds'`,
      completedAtSqlExpr: `NOW() - INTERVAL '${10 - RUN_OK_COMPLETED_OFFSET_SEC} seconds'`,
    });

    // Child run for RUN_OK (subtask_origin must satisfy chk_agent_run_subtask_origin).
    await seedAgentRun({
      pid: RUN_CHILD,
      agentCode: 'aurabot.child.e2e',
      status: 'succeeded',
      costUsd: 0.01,
      parentRunId: RUN_OK,
      subtaskOrigin: 'delegate_task',
      createdAtSqlExpr: `NOW() - INTERVAL '5 seconds'`,
      completedAtSqlExpr: `NOW() - INTERVAL '4 seconds'`,
    });

    // One action + BIF + interrupt under RUN_OK so its drawer surfaces all
    // four sub-areas (metadata / actions / interrupts / child runs) with
    // concrete ids that the test can pin against.
    await seedAgentAction({
      pid: ACTION_PID,
      runId: RUN_OK,
      actionCode: 'sales.list',
      intentSummary: ACTION_INTENT_TEXT,
    });
    await seedAgentBif({
      pid: BIF_PID,
      runId: RUN_OK,
      intent: INTENT_TEXT,
    });
    await seedInterruptForRun({
      pid: INTERRUPT_PID,
      activeRunId: RUN_OK,
      sessionId: `e2e_replay_${Date.now()}`,
    });
    await seedAiTraceForRun({
      traceId: TRACE_ID,
      runId: RUN_OK,
    });
  });

  // -------------------------------------------------------------------------
  // [D1 + D2 + D6] Sidebar nav → list renders → cells match API ground truth
  // -------------------------------------------------------------------------
  test('AR-001 — Sidebar nav → list renders rows whose cells match API ground truth', async ({
    page,
  }) => {
    // Ground-truth read first so we can pin specific cell text values.
    // This is the ONE allowed page.request call (read-only GET, NOT a UI-bypass).
    const apiResp = await page.request.get(
      `/api/admin/agent-runs?page=0&size=20&keyword=${encodeURIComponent(RUN_PREFIX)}`,
    );
    expect(apiResp.ok(), 'admin agent-runs list endpoint should respond 200').toBeTruthy();
    const apiBody = await apiResp.json();
    expect(apiBody?.code, 'admin agent-runs list should return code=0 envelope').toBe('0');
    const items: Array<{
      runId: string;
      runStatus: string;
      costUsd: number | null;
      durationMs: number | null;
      agentCode: string | null;
    }> = apiBody?.data?.items ?? [];
    const apiOk = items.find((r) => r.runId === RUN_OK);
    const apiFailed = items.find((r) => r.runId === RUN_FAILED);
    expect(apiOk, `API should return seeded ${RUN_OK}`).toBeTruthy();
    expect(apiFailed, `API should return seeded ${RUN_FAILED}`).toBeTruthy();
    // Tighten contract: API costUsd/durationMs match what we seeded.
    // numeric(10,2) round-trip preserves 2 decimal places, so use closeTo(2).
    // durationMs is derived from completed_at − created_at; both timestamps
    // are computed from the SAME NOW() call inside one INSERT statement, so
    // the delta is exactly the interval we requested (no clock drift).
    expect(Number(apiOk!.costUsd)).toBeCloseTo(RUN_OK_COST, 2);
    expect(Number(apiOk!.durationMs)).toBe(RUN_OK_COMPLETED_OFFSET_SEC * 1000);
    expect(apiOk!.runStatus).toBe('succeeded');
    expect(apiFailed!.runStatus).toBe('failed');

    await navigateAgentRunsViaSidebar(page);

    // [D2] Table header columns render with i18n labels (zh) — the page
    // has 8 thead cells; assert a stable subset.
    const thead = page.locator('[data-testid="runs-table"] thead');
    await expect(thead).toContainText('Run ID');
    await expect(thead).toContainText('Agent');
    await expect(thead).toContainText(/状态|Status/);
    await expect(thead).toContainText(/成本|Cost/);
    await expect(thead).toContainText(/耗时|Duration/);

    const okRow = page.locator(`[data-testid="run-row-${RUN_OK}"]`);
    await expect(okRow).toBeVisible({ timeout: 10_000 });

    // [D6] Run ID column is rendered via shortPid() — first 8 chars + ellipsis.
    const okRowText = await okRow.innerText();
    const expectedShort = `${RUN_OK.slice(0, 8)}…`;
    expect(okRowText).toContain(expectedShort);
    expect(okRowText).toContain(AGENT_CODE);

    // [D6] Status badge text == API ground truth (succeeded).
    const okBadge = page.locator(`[data-testid="status-badge-${RUN_OK}"]`);
    await expect(okBadge).toHaveText('succeeded');
    expect(apiOk!.runStatus).toBe('succeeded'); // sanity: UI matches API

    // [D6] Cost cell == fmtCost(0.13) = "$0.1300" (toFixed(4) on numeric(10,2)).
    expect(okRowText).toContain('$0.1300');
    // [D6] Duration cell == fmtDuration(5_000) = "5.00s".
    expect(okRowText).toContain('5.00s');

    // Failed row shows distinct status + distinct cost / duration cells.
    const failedRow = page.locator(`[data-testid="run-row-${RUN_FAILED}"]`);
    await expect(failedRow).toBeVisible();
    const failedBadge = page.locator(`[data-testid="status-badge-${RUN_FAILED}"]`);
    await expect(failedBadge).toHaveText('failed');
    const failedRowText = await failedRow.innerText();
    expect(failedRowText).toContain('$0.0300');
    // fmtDuration(1_000) → "1.00s" (ms >= 1000 branch).
    expect(failedRowText).toContain('1.00s');

    // Cancelled row: costUsd null → fmtCost = '-'. Controller's RUN_ROW_MAPPER
    // never returns null durationMs (defaults 0L when both stored and derived
    // are absent), so UI shows "0ms" — which is also a valid render branch.
    const runningRow = page.locator(`[data-testid="run-row-${RUN_RUNNING}"]`);
    await expect(runningRow).toBeVisible();
    const runningRowText = await runningRow.innerText();
    expect(runningRowText).toContain('aurabot.planner.e2e');
    // fmtCost(null) → '-'. Verify "$" doesn't appear in this row's cost
    // cell — guards against fallback formatting.
    const runningCostCell = runningRow.locator('td.text-right.tabular-nums').nth(0);
    await expect(runningCostCell).toHaveText('-');
    // Status badge shows the seeded terminal status.
    await expect(
      page.locator(`[data-testid="status-badge-${RUN_RUNNING}"]`),
    ).toHaveText('cancelled');

    // The keyword filter is empty by default — assert pagination block visible.
    await expect(page.locator('[data-testid="pagination"]')).toBeVisible();
  });

  // -------------------------------------------------------------------------
  // [D7] Row click opens drawer; sub-areas show concrete ids/text values
  // -------------------------------------------------------------------------
  test('AR-002 — Row click → drawer opens with concrete metadata / actions / interrupts / child-runs', async ({
    page,
  }) => {
    await navigateAgentRunsViaSidebar(page);

    const okRow = page.locator(`[data-testid="run-row-${RUN_OK}"]`);
    await okRow.waitFor({ state: 'visible', timeout: 10_000 });

    // Wait for the detail GET to fire when the row is clicked.
    const detailResponsePromise = page.waitForResponse(
      (r) =>
        r.url().includes(`/api/admin/agent-runs/${RUN_OK}`) && r.status() === 200,
      { timeout: 15_000 },
    );
    await okRow.click();
    const detailResp = await detailResponsePromise;
    const detailBody = await detailResp.json();
    expect(detailBody?.code).toBe('0');
    expect(detailBody?.data?.run?.runId).toBe(RUN_OK);
    expect(detailBody?.data?.actions?.length).toBeGreaterThanOrEqual(1);
    expect(detailBody?.data?.childRuns?.length).toBeGreaterThanOrEqual(1);
    expect(detailBody?.data?.interruptLog?.length).toBeGreaterThanOrEqual(1);
    expect(detailBody?.data?.bif?.intent).toBe(INTENT_TEXT);
    expect(detailBody?.data?.traceId).toBe(TRACE_ID);
    expect(detailBody?.data?.actions?.[0]?.resultContractId).toBe(RESULT_CONTRACT_ID);
    expect(detailBody?.data?.conversationTurn?.turnId).toBe(TURN_ID);
    expect(detailBody?.data?.conversationTurn?.conversationId).toBe(CONVERSATION_ID);
    expect(detailBody?.data?.conversationTurn?.inboundMessageId).toBe(INBOUND_MESSAGE_ID);
    expect(detailBody?.data?.conversationTurn?.outboundMessageId).toBe(OUTBOUND_MESSAGE_ID);
    expect(detailBody?.data?.conversationTurn?.userMessage).toBe(TURN_USER_MESSAGE);
    expect(detailBody?.data?.conversationTurn?.finalResponse).toBe(TURN_FINAL_RESPONSE);
    expect(detailBody?.data?.conversationTurn?.messages?.length).toBe(2);
    expect(detailBody?.data?.conversationTurn?.resultContractIds).toContain(RESULT_CONTRACT_ID);
    expect(detailBody?.data?.resultContracts?.[0]?.contractId).toBe(RESULT_CONTRACT_ID);
    expect(detailBody?.data?.resultContracts?.[0]?.contract?.textSummary).toBe(ACTION_INTENT_TEXT);

    const drawer = page.locator('[data-testid="agent-run-detail-drawer"]');
    await expect(drawer).toBeVisible({ timeout: 5_000 });

    // [D7] URL state mirrors open runId — confirms openDrawer wrote to URL.
    expect(page.url()).toContain(`runId=${RUN_OK}`);

    // [D7-1] Metadata section: shows the FULL pid (font-mono <dd>), agent code,
    //         status, intent — not just "section visible".
    const metadata = page.locator('[data-testid="drawer-section-metadata"]');
    await expect(metadata).toBeVisible();
    await expect(metadata).toContainText(RUN_OK);
    await expect(metadata).toContainText(AGENT_CODE);
    await expect(metadata).toContainText('succeeded');
    await expect(metadata).toContainText('$0.1300');
    await expect(metadata).toContainText('5.00s');
    await expect(metadata).toContainText(INTENT_TEXT);

    // [D7-2] Actions section: lists the seeded action by pid testid.
    const actionsSection = page.locator('[data-testid="drawer-section-actions"]');
    await expect(actionsSection).toBeVisible();
    await expect(actionsSection).toContainText(/Action Timeline \(\d+\)/);
    const actionRow = page.locator(`[data-testid="action-row-${ACTION_PID}"]`);
    await expect(actionRow).toBeVisible();
    await expect(actionRow).toContainText('sales.list');
    await expect(actionRow).toContainText('success');

    // [D7-3] Interrupts section: seeded interrupt's policy + tier are visible.
    const interruptsSection = page.locator('[data-testid="drawer-section-interrupts"]');
    await expect(interruptsSection).toBeVisible();
    await expect(interruptsSection).toContainText(/Interrupt Log \(\d+\)/);
    await expect(interruptsSection).toContainText('replace_intent');
    await expect(interruptsSection).toContainText('active_run_cancelled');

    // [D7-4] Child runs section: seeded child run is visible AND clickable.
    const childRunsSection = page.locator('[data-testid="drawer-section-child-runs"]');
    await expect(childRunsSection).toBeVisible();
    await expect(childRunsSection).toContainText(/Child Runs \(\d+\)/);
    const childBtn = page.locator(`[data-testid="child-run-${RUN_CHILD}"]`);
    await expect(childBtn).toBeVisible();
    // Agent code and run status render in sibling spans within the same node row;
    // assert at the node-level container, not the button itself.
    const childNode = page.locator(`[data-testid="child-run-node-${RUN_CHILD}"]`);
    await expect(childNode).toContainText('aurabot.child.e2e');
    await expect(childNode).toContainText('succeeded');

    // [D7-5] BIF section: seeded grounding intent + skill name visible.
    const bifSection = page.locator('[data-testid="drawer-section-bif"]');
    await expect(bifSection).toBeVisible();
    await expect(bifSection).toContainText(INTENT_TEXT);
    await expect(bifSection).toContainText('replay.e2e.skill');
    await expect(bifSection).toContainText('AgentRun');

    // [D7-6] ResultContract deep link: action row opens the selected
    // contract derived from ab_agent_action, without a second runtime path.
    await page.locator(`[data-testid="action-toggle-${ACTION_PID}"]`).click();
    const actionDetail = page.locator(`[data-testid="action-detail-${ACTION_PID}"]`);
    await expect(actionDetail).toBeVisible({ timeout: 3_000 });
    await page.locator(`[data-testid="open-result-contract-${ACTION_PID}"]`).click();
    const resultsSection = page.locator('[data-testid="drawer-section-result-contracts"]');
    await expect(resultsSection).toBeVisible({ timeout: 3_000 });
    await expect(resultsSection).toContainText(RESULT_CONTRACT_ID);
    await expect(resultsSection).toContainText(ACTION_INTENT_TEXT);
    await expect(resultsSection).toContainText('sales.list');
    await expect(
      page.locator(`[data-testid="result-contract-item-${RESULT_CONTRACT_ID}"]`),
    ).toHaveClass(/border-indigo-300/);

    // [D7-7] Conversation tab reconstructs the exact turn tape: inbound
    // user message + outbound agent message by turn-scoped ids.
    await page.locator('[data-testid="drawer-tab-conversation"]').click();
    const conversationSection = page.locator('[data-testid="drawer-section-conversation"]');
    await expect(conversationSection).toBeVisible({ timeout: 3_000 });
    await expect(conversationSection).toContainText(TURN_ID);
    await expect(conversationSection).toContainText(String(CONVERSATION_ID));
    await expect(conversationSection).toContainText(TURN_USER_MESSAGE);
    await expect(conversationSection).toContainText(TURN_FINAL_RESPONSE);
    await expect(
      page.locator(`[data-testid="conversation-message-${INBOUND_MESSAGE_ID}"]`),
    ).toContainText('human');
    await expect(
      page.locator(`[data-testid="conversation-message-${OUTBOUND_MESSAGE_ID}"]`),
    ).toContainText('e2e thinking trace');

    // Deep-link bridge: replay drawer -> trace detail -> related run link.
    const openTrace = page.locator('[data-testid="open-trace-link"]');
    await expect(openTrace).toHaveAttribute('href', `/aurabot/traces/${TRACE_ID}`);
    const traceResponsePromise = page.waitForResponse(
      (r) => r.url().includes(`/api/ai/traces/${TRACE_ID}`) && r.status() === 200,
      { timeout: 15_000 },
    );
    await openTrace.click();
    await traceResponsePromise;
    await expect(page).toHaveURL(new RegExp(`/aurabot/traces/${TRACE_ID}$`));
    await expect(page.locator('[data-testid="trace-tab-timeline"]')).toBeVisible({
      timeout: 10_000,
    });
    await expect(page.locator('[data-testid="trace-related-run-link"]')).toHaveAttribute(
      'href',
      `/admin/agent-runs?runId=${RUN_OK}`,
    );
  });

  // -------------------------------------------------------------------------
  // [D14 user action 1] Status filter change → URL updates + only matching
  //                      rows render. This is the closest user-mutation
  //                      action exposed by the read-only Replay UI surface
  //                      (no replay/export/copy-id button exists on the
  //                      page — see resources.ts + agent-runs.tsx).
  // -------------------------------------------------------------------------
  test('AR-003 — Status filter "succeeded" → URL gains ?status, only succeeded rows render', async ({
    page,
  }) => {
    await navigateAgentRunsViaSidebar(page);

    // Sanity: pre-filter the failed row IS visible (it shouldn't be after).
    await expect(
      page.locator(`[data-testid="run-row-${RUN_FAILED}"]`),
    ).toBeVisible({ timeout: 10_000 });

    // Trigger filter via the page's <select> — selectOption is a real UI
    // interaction (not a JS shortcut), it reaches the SelectChange handler
    // that updates URL via setSearchParams.
    const filterListResponse = page.waitForResponse(
      (r) =>
        r.url().includes('/api/admin/agent-runs') &&
        /\bstatus=succeeded\b/.test(r.url()) &&
        r.status() === 200,
      { timeout: 10_000 },
    );
    await page.locator('[data-testid="filter-status"]').selectOption('succeeded');
    await filterListResponse;

    // [D14] URL state reflects the filter — D3 contract.
    await expect(page).toHaveURL(/[?&]status=succeeded\b/);

    // Only succeeded runs render; failed row must be gone.
    await expect(page.locator(`[data-testid="run-row-${RUN_OK}"]`)).toBeVisible();
    await expect(page.locator(`[data-testid="run-row-${RUN_FAILED}"]`)).toBeHidden();
    await expect(page.locator(`[data-testid="run-row-${RUN_RUNNING}"]`)).toBeHidden();

    // Status badge in the visible row still shows "succeeded".
    await expect(
      page.locator(`[data-testid="status-badge-${RUN_OK}"]`),
    ).toHaveText('succeeded');

    // Reset filter via UI to "All" — assert URL drops the param + failed row reappears.
    const resetListResponse = page.waitForResponse(
      (r) =>
        r.url().includes('/api/admin/agent-runs') &&
        !/\bstatus=succeeded\b/.test(r.url()) &&
        r.status() === 200,
      { timeout: 10_000 },
    );
    await page.locator('[data-testid="filter-status"]').selectOption('');
    await resetListResponse;
    await expect(page).not.toHaveURL(/[?&]status=succeeded\b/);
    await expect(
      page.locator(`[data-testid="run-row-${RUN_FAILED}"]`),
    ).toBeVisible({ timeout: 5_000 });
  });

  // -------------------------------------------------------------------------
  // [D14 user action 2] Action-row toggle expands inline detail
  //                      → row state change visible in DOM.
  // -------------------------------------------------------------------------
  test('AR-004 — Action toggle → expanded detail shows seeded intent text', async ({
    page,
  }) => {
    await navigateAgentRunsViaSidebar(page);

    const okRow = page.locator(`[data-testid="run-row-${RUN_OK}"]`);
    await okRow.waitFor({ state: 'visible', timeout: 10_000 });

    const detailResponsePromise = page.waitForResponse(
      (r) =>
        r.url().includes(`/api/admin/agent-runs/${RUN_OK}`) && r.status() === 200,
      { timeout: 15_000 },
    );
    await okRow.click();
    await detailResponsePromise;

    const drawer = page.locator('[data-testid="agent-run-detail-drawer"]');
    await expect(drawer).toBeVisible({ timeout: 5_000 });

    // Pre-toggle: action detail node is NOT in the DOM (open=false branch).
    const detailNode = page.locator(`[data-testid="action-detail-${ACTION_PID}"]`);
    await expect(detailNode).toHaveCount(0);

    // Toggle the action row — this is the user-action under audit.
    await page.locator(`[data-testid="action-toggle-${ACTION_PID}"]`).click();

    // Post-toggle: expanded detail shows the seeded intent_summary text
    // (AGENT_INTENT, NOT a generic placeholder). This is the row state change.
    await expect(detailNode).toBeVisible({ timeout: 3_000 });
    await expect(detailNode).toContainText(ACTION_INTENT_TEXT);

    // Toggle again → detail collapses.
    await page.locator(`[data-testid="action-toggle-${ACTION_PID}"]`).click();
    await expect(detailNode).toHaveCount(0);
  });
});

// ---------------------------------------------------------------------------
// Empty-state describe block — exception: empty-state via page.route mock
// (real-backend test always seeds runs, so the only deterministic way to
// reach the empty branch is to short-circuit the network. This is the
// SAME exception that ai-interrupts.spec.ts uses for IL-01.)
// ---------------------------------------------------------------------------

test.describe('Replay UI — Admin Agent Runs empty state (mocked)', () => {
  // exception: empty-state via page.route mock — real backend always has
  // seeded runs from AR-001 before this describe runs, so we can't reach
  // the empty branch otherwise. Allowed by spec §T2 + testing-e2e-web.md
  // because pages with seeded fixtures have no other way to demo D7-empty.
  test('AR-005 — Empty list returns empty-state placeholder, not error banner', async ({
    page,
  }) => {
    await page.route(/\/api\/admin\/agent-runs(\?[^/]*)?$/, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: '0',
          message: 'OK',
          data: { items: [], total: 0, page: 0, size: 20 },
        }),
      });
    });

    // Sidebar nav still required — empty-state must be reachable from menu.
    await page.goto('/');
    await page.waitForLoadState('domcontentloaded');
    const nav = page.locator('nav').first();
    await nav.waitFor({ state: 'visible', timeout: 10_000 });
    const aiCenter = nav.getByRole('button', { name: /AI 中心|AI Center/ }).first();
    await aiCenter.waitFor({ state: 'visible', timeout: 10_000 });
    await aiCenter.evaluate((el: HTMLElement) => el.click());
    const leaf = nav.locator('a[href="/admin/agent-runs"]').first();
    await leaf.waitFor({ state: 'attached', timeout: 8_000 });
    await leaf.evaluate((el: HTMLElement) => el.click());
    await expect(page.locator('[data-testid="agent-runs-page"]')).toBeVisible({
      timeout: 10_000,
    });

    // Empty placeholder visible with concrete copy text (zh-CN default).
    const empty = page.locator('[data-testid="empty-state"]');
    await expect(empty).toBeVisible();
    await expect(empty).toContainText(/暂无 Agent 运行记录|No agent runs found/);

    // Negative checks — error banner / table must NOT render in empty state.
    await expect(page.locator('[data-testid="error-banner"]')).toHaveCount(0);
    await expect(page.locator('[data-testid="runs-table"]')).toHaveCount(0);
  });
});
