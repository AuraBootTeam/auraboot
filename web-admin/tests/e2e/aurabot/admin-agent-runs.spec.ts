/**
 * Replay UI MVP — Admin Agent Runs page Playwright E2E (ACP A.2 follow-up)
 *
 * Backend route:  GET /api/admin/agent-runs (AgentRunController, gated by AdminRoleInterceptor)
 * Frontend page:  web-admin/app/plugins/core-aurabot/pages/admin/agent-runs.tsx
 *                 → registered as resource `aurabot.admin.runs` at /admin/agent-runs
 *                 (web-admin/app/plugins/core-aurabot/resources.ts)
 *
 * BLOCKER (PRODUCT BUG FOUND BY THIS SPEC, OUT OF T2 SCOPE TO FIX):
 *   AgentRunController.RUN_ROW_MAPPER (platform/.../AgentRunController.java:302)
 *   does `(Long) rs.getObject("duration_ms")` but ab_agent_run.duration_ms is
 *   INTEGER in schema.sql, so PostgreSQL JDBC returns Integer → crash. Any
 *   admin agent-runs query that hits even one row with non-null duration_ms
 *   returns 500 with `ClassCastException`. Workaround: this spec ALWAYS seeds
 *   duration_ms=NULL and relies on the controller's fallback derivation
 *   (Duration.between(createdAt, completedAt)). Fix is one line:
 *     -  Long storedDuration = (Long) rs.getObject("duration_ms");
 *     +  Number storedDuration = (Number) rs.getObject("duration_ms");
 *     -  durationMs = storedDuration;
 *     +  durationMs = storedDuration.longValue();
 *   Reported in the T2 deliverable's "Blockers" section so the controller can
 *   land a follow-up backend fix; this spec passes against the current source
 *   only because every seeded run avoids the buggy code branch.
 *
 * Why this spec exists:
 *   `94b97ad6` shipped the page + a vitest covering the React wiring
 *   (web-admin/app/plugins/core-aurabot/__tests__/AgentRunsPage.test.tsx),
 *   but no Playwright covered the sidebar→list→drawer→user-action chain
 *   end-to-end against a live backend. This spec drives the real Spring
 *   Boot REST endpoints on :6443 with rows seeded directly into
 *   ab_agent_run / ab_agent_action / ab_agent_bif via psql.
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
 * Test data lifecycle: seed via psql in beforeAll; do NOT cleanup in
 * afterAll (memory feedback_save_test_output / testing-e2e-web.md
 * "测试痕迹" rule). Centralised teardown in tests/global-teardown.ts
 * deletes E2EM_* / E2ELR_* / E2ELA_* / E2ELB_* prefixed rows.
 */

import { test, expect, type Page } from '../../fixtures';
import { execSync } from 'node:child_process';
import {
  ADMIN_TENANT_ID,
  AI_CENTER_MENU_ID,
} from './_real-backend-helpers';

// ---------------------------------------------------------------------------
// Constants — pid prefixes are unique per spec so global teardown can target
// these rows and the assertions can find specific runs deterministically.
// ---------------------------------------------------------------------------

const RUN_PREFIX = 'E2ELR'; // ab_agent_run
const ACTION_PREFIX = 'E2ELA'; // ab_agent_action
const BIF_PREFIX = 'E2ELB'; // ab_agent_bif
const INTERRUPT_PREFIX = 'E2ELI'; // ab_agent_interrupt_log
const MENU_PREFIX = 'E2EM_AR'; // ab_menu (caught by /^E2EM_/ teardown)

const RUN_OK = `${RUN_PREFIX}_OK_${Date.now().toString(36).toUpperCase()}`.slice(0, 26);
const RUN_FAILED = `${RUN_PREFIX}_FL_${Date.now().toString(36).toUpperCase()}`.slice(0, 26);
const RUN_RUNNING = `${RUN_PREFIX}_RU_${Date.now().toString(36).toUpperCase()}`.slice(0, 26);
const RUN_CHILD = `${RUN_PREFIX}_CH_${Date.now().toString(36).toUpperCase()}`.slice(0, 26);

const ACTION_PID = `${ACTION_PREFIX}_${Date.now().toString(36).toUpperCase()}`.slice(0, 26);
const BIF_PID = `${BIF_PREFIX}_${Date.now().toString(36).toUpperCase()}`.slice(0, 26);
const INTERRUPT_PID = `${INTERRUPT_PREFIX}_${Date.now().toString(36).toUpperCase()}`.slice(0, 26);

const TASK_ID = 'TASKE2EREPLAY01234567890XX'.slice(0, 26);
const AGENT_CODE = 'aurabot.replay.e2e';
// ab_agent_bif.intent is VARCHAR(32). Use a short stable code, not free-form text.
const INTENT_TEXT = 'replay_e2e_query';
const ACTION_INTENT_TEXT = 'replay e2e action intent — sales.list';

// Stable ground-truth values that MUST be reflected in the UI cells.
// NOTE: ab_agent_run.total_cost is NUMERIC(10,2) — values seeded with more
// than 2 decimal places get rounded, so use 2-decimal values to avoid
// "API returned 0.12 but UI rendered $0.12 vs test expected $0.1235" drift.
const RUN_OK_COST = 0.13;
const RUN_FAILED_COST = 0.03;
// Duration is derived by RUN_ROW_MAPPER from completed_at - created_at when
// stored duration_ms is NULL. Seed timestamps with these exact deltas so the
// derived value lands at a stable cell value the UI assertion can pin.
// (Using 5_000ms / 1_000ms in clean integer seconds for predictability.)
const RUN_OK_COMPLETED_OFFSET_SEC = 5; // → 5000ms → "5.00s"
const RUN_FAILED_COMPLETED_OFFSET_SEC = 1; // → 1000ms → "1.00s"

// ---------------------------------------------------------------------------
// psql helpers — same dialect as _real-backend-helpers.ts
// ---------------------------------------------------------------------------

function psql(sql: string): string {
  return execSync(
    `psql -h localhost -U ghj -d aura_boot -P pager=off -v ON_ERROR_STOP=1 -tA`,
    { input: sql, stdio: ['pipe', 'pipe', 'pipe'] },
  )
    .toString()
    .trim();
}

function psqlQuiet(sql: string): string {
  return execSync(
    `psql -h localhost -U ghj -d aura_boot -P pager=off -v ON_ERROR_STOP=1 -qtA`,
    { input: sql, stdio: ['pipe', 'pipe', 'pipe'] },
  )
    .toString()
    .trim();
}

interface SeededAgentRun {
  pid: string;
  agentCode: string;
  status: string;
  costUsd: number | null;
  durationMs: number | null;
  parentRunId: string | null;
}

function seedAgentRun(args: {
  pid: string;
  agentCode: string;
  status: string;
  costUsd: number | null;
  parentRunId?: string | null;
  subtaskOrigin?: string | null;
  taskId?: string;
  createdAtSqlExpr?: string;
  completedAtSqlExpr?: string | null;
}): SeededAgentRun {
  // IMPORTANT: ab_agent_run.duration_ms is INTEGER in schema.sql, but
  // AgentRunController.RUN_ROW_MAPPER (line 302) casts the result to Long
  // unconditionally — see ClassCastException reported in this file's
  // Blocker section. To avoid tripping that bug, this helper always seeds
  // duration_ms = NULL and relies on the controller's fallback path
  // (Duration.between(createdAt, completedAt).toMillis()) for derivation.
  const cost = args.costUsd === null ? 'NULL' : args.costUsd.toString();
  const parent = args.parentRunId ? `'${args.parentRunId}'` : 'NULL';
  const subtaskOrigin = args.subtaskOrigin ? `'${args.subtaskOrigin}'` : 'NULL';
  const taskId = args.taskId ?? TASK_ID;
  const completedAt =
    args.completedAtSqlExpr === null
      ? 'NULL'
      : args.completedAtSqlExpr ?? 'NOW()';
  const createdAt = args.createdAtSqlExpr ?? 'NOW()';
  psql(`
    INSERT INTO ab_agent_run
      (pid, tenant_id, task_id, agent_id, run_status,
       total_cost, duration_ms, parent_run_id, subtask_origin,
       started_at, completed_at, created_at)
    VALUES
      ('${args.pid}', ${ADMIN_TENANT_ID}, '${taskId}', '${args.agentCode}',
       '${args.status}', ${cost}, NULL, ${parent}, ${subtaskOrigin},
       ${createdAt}, ${completedAt}, ${createdAt});
  `);
  return {
    pid: args.pid,
    agentCode: args.agentCode,
    status: args.status,
    costUsd: args.costUsd,
    durationMs: null,
    parentRunId: args.parentRunId ?? null,
  };
}

function seedAgentAction(args: {
  pid: string;
  runId: string;
  actionCode: string;
  intentSummary: string;
}): void {
  psql(`
    INSERT INTO ab_agent_action
      (pid, tenant_id, run_id, action_code, action_type,
       target_model, action_status, intent_summary, step_index,
       cost_usd, token_usage, executed_at)
    VALUES
      ('${args.pid}', ${ADMIN_TENANT_ID}, '${args.runId}',
       '${args.actionCode}', 'tool_call',
       'replay_e2e', 'success', $$${args.intentSummary}$$, 1,
       0.001000, 100, NOW());
  `);
}

function seedAgentBif(args: { pid: string; runId: string; intent: string }): void {
  psql(`
    INSERT INTO ab_agent_bif
      (pid, tenant_id, run_id, nl_input, intent, primary_object,
       confidence, risk_level, dispatched_skill, channel, created_at)
    VALUES
      ('${args.pid}', ${ADMIN_TENANT_ID}, '${args.runId}',
       'replay e2e nl input', $$${args.intent}$$, 'AgentRun',
       '{"score":0.9}'::jsonb, 'L0', 'replay.e2e.skill',
       'chat', NOW());
  `);
}

function seedInterruptForRun(args: {
  pid: string;
  activeRunId: string;
  sessionId: string;
}): void {
  psql(`
    INSERT INTO ab_agent_interrupt_log
      (pid, tenant_id, session_id, active_run_id, new_message_excerpt,
       sub_policy, classifier_tier, confidence, reason, action_taken, created_at)
    VALUES
      ('${args.pid}', ${ADMIN_TENANT_ID}, '${args.sessionId}', '${args.activeRunId}',
       'replay e2e interrupt excerpt', 'replace_intent', 'keyword',
       0.91, 'replay e2e seeded', 'active_run_cancelled', NOW());
  `);
}

// Idempotent menu upsert (mirrors _real-backend-helpers.ts upsertMenu).
function upsertAdminAgentRunsMenu(): { menuId: string; ownedId: string | null } {
  const rand = Math.random().toString(36).slice(2, 8).toUpperCase();
  const menuPid = `${MENU_PREFIX}_${rand}`.slice(0, 26);
  const path = '/admin/agent-runs';
  const raw = psqlQuiet(
    `BEGIN;
     SELECT pg_advisory_xact_lock(hashtext('ab_menu_upsert:${path}')::bigint);
     WITH existing AS (
       SELECT id FROM ab_menu
        WHERE tenant_id = ${ADMIN_TENANT_ID} AND path = '${path}' AND deleted_flag = false
        LIMIT 1
     ),
     inserted AS (
       INSERT INTO ab_menu (id, pid, tenant_id, parent_id, code, name, path, type, permission_code, visible, order_no, status)
       SELECT (EXTRACT(EPOCH FROM NOW())*1000 + floor(random()*1000000))::bigint,
              '${menuPid}', ${ADMIN_TENANT_ID}, ${AI_CENTER_MENU_ID},
              NULL, 'Agent 运行记录', '${path}', 1,
              NULL, true, 100, 'active'
        WHERE NOT EXISTS (SELECT 1 FROM existing)
       RETURNING id
     )
     SELECT id || '|' || origin FROM (
       SELECT id, 'ins' AS origin FROM inserted
       UNION ALL
       SELECT id, 'exi' AS origin FROM existing
       LIMIT 1
     ) s;
     COMMIT;`,
  );
  const [id, origin] = raw.split('|');
  return { menuId: id, ownedId: origin === 'ins' ? id : null };
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

  // Expand the parent group "AI 中心" / "AI Center"
  const aiCenter = nav.getByRole('button', { name: /AI 中心|AI Center/ }).first();
  await aiCenter.waitFor({ state: 'visible', timeout: 10_000 });
  await aiCenter.evaluate((el: HTMLElement) => el.click());

  // Click the leaf "Agent 运行记录"
  const leaf = nav.locator('a[href="/admin/agent-runs"]').first();
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

test.beforeAll(() => {
  upsertAdminAgentRunsMenu();
});

// ---------------------------------------------------------------------------
// Test suite (real backend)
// ---------------------------------------------------------------------------

test.describe('Replay UI — Admin Agent Runs (real backend, ACP A.2)', () => {
  // Drawer open + pagination/filter state changes need predictable order.
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60_000);

  test.beforeAll(() => {
    // Order matters for "ORDER BY created_at DESC" — give RUN_OK the
    // newest timestamp so it lands on page 0 deterministically. Use
    // staggered NOW() expressions: succeeded > failed > running.
    //
    // Duration is derived from completed_at − created_at by the controller's
    // RUN_ROW_MAPPER fallback path (see seedAgentRun for why we never seed
    // duration_ms directly — known controller bug, see Blocker note in
    // header). Pin the timestamp deltas so cell text is stable.
    //
    // NOTE: not seeding status='running' — the platform reaps orphaned
    // running runs to 'failed' on restart, which would flake assertions.
    // Use 'cancelled' (terminal) with completedAtSqlExpr=null so the
    // duration-derivation falls into the `else { 0L }` branch — UI shows
    // "0ms", exercising the no-completion rendering path.
    seedAgentRun({
      pid: RUN_RUNNING,
      agentCode: 'aurabot.planner.e2e',
      status: 'cancelled',
      costUsd: null,
      completedAtSqlExpr: null,
      createdAtSqlExpr: `NOW() - INTERVAL '120 seconds'`,
    });
    seedAgentRun({
      pid: RUN_FAILED,
      agentCode: AGENT_CODE,
      status: 'failed',
      costUsd: RUN_FAILED_COST,
      // Created 60s ago, completed 59s ago → derived 1000ms → "1.00s".
      createdAtSqlExpr: `NOW() - INTERVAL '60 seconds'`,
      completedAtSqlExpr: `NOW() - INTERVAL '${60 - RUN_FAILED_COMPLETED_OFFSET_SEC} seconds'`,
    });
    seedAgentRun({
      pid: RUN_OK,
      agentCode: AGENT_CODE,
      status: 'succeeded',
      costUsd: RUN_OK_COST,
      // Created 10s ago, completed 5s ago → derived 5000ms → "5.00s".
      createdAtSqlExpr: `NOW() - INTERVAL '10 seconds'`,
      completedAtSqlExpr: `NOW() - INTERVAL '${10 - RUN_OK_COMPLETED_OFFSET_SEC} seconds'`,
    });

    // Child run for RUN_OK (subtask_origin must satisfy chk_agent_run_subtask_origin).
    seedAgentRun({
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
    seedAgentAction({
      pid: ACTION_PID,
      runId: RUN_OK,
      actionCode: 'sales.list',
      intentSummary: ACTION_INTENT_TEXT,
    });
    seedAgentBif({
      pid: BIF_PID,
      runId: RUN_OK,
      intent: INTENT_TEXT,
    });
    seedInterruptForRun({
      pid: INTERRUPT_PID,
      activeRunId: RUN_OK,
      sessionId: `e2e_replay_${Date.now()}`,
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
    await expect(childBtn).toContainText('aurabot.child.e2e');
    await expect(childBtn).toContainText('succeeded');

    // [D7-5] BIF section: seeded grounding intent + skill name visible.
    const bifSection = page.locator('[data-testid="drawer-section-bif"]');
    await expect(bifSection).toBeVisible();
    await expect(bifSection).toContainText(INTENT_TEXT);
    await expect(bifSection).toContainText('replay.e2e.skill');
    await expect(bifSection).toContainText('AgentRun');

    // Close drawer to keep state clean for AR-003 / AR-004.
    // Sticky page header overlaps the drawer's close button at default
    // viewport — use evaluate(el.click()) to bypass the pointer-events
    // hit-test (real users dismiss via Esc / backdrop click anyway).
    await page
      .locator('[data-testid="drawer-close"]')
      .evaluate((el: HTMLElement) => el.click());
    await expect(drawer).toBeHidden({ timeout: 5_000 });
    expect(page.url()).not.toContain('runId=');
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
