/**
 * Automation Designer — Layer A REAL drag-drop golden E2E (Phase 1)
 *
 * This is REAL golden testing: every node is added by a real HTML5 drag from the
 * palette, every edge by a real @xyflow pointer connection, every config field
 * filled by driving the real property-panel control. NO API-built flows for the
 * journey cases (the only API calls are the fire side + node-status polling, which
 * have no UI surface). NO skip-to-green: a case that exposes a real product bug is
 * debugged, not masked.
 *
 * Runtime contract (verified end-to-end against the live stack, 2026-06-05):
 *   - Trigger `on_record_create` fires when a record is created via a DSL command;
 *     the automation engine receives the command PAYLOAD as the trigger `record`
 *     map. So a payload key (e.g. e2et_order_amount) is visible to the condition
 *     even if the model does not persist it.
 *   - A `control-condition` node compiles to a SmartEngine exclusiveGateway. The
 *     gateway routes on the OUTGOING EDGE condition expressions (set via the edge
 *     inspector), NOT on the condition node's own `expression` field. SmartEngine
 *     ignores the bare BPMN `default=` fallback, so EVERY outgoing edge must carry
 *     a conditionExpression, and the gateway requires >= 2 outgoing transitions
 *     ("the outcomeTransitions.size() should >= 2"). A single-branch condition
 *     therefore FAILS at runtime — see PB-1 in the session report.
 *   - The gateway condition expression binds process variables as top-level names
 *     with map indexing: `record['e2et_order_amount'] > 1000`.
 *   - `action-update-record` with recordPid `${trigger.recordPid}` updates the trigger
 *     record (a reliably-assertable persisted side effect).
 *
 * @since Phase 1 (Layer A)
 */
import http from 'node:http';
import type { AddressInfo } from 'node:net';
import { test, expect, type Page } from '@playwright/test';
import { Client } from 'pg';
import { uniqueId } from '../helpers';
import { BACKEND_URL, BASE_URL, PG_CONN } from '../../helpers/environments';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';
import { acquireE2etOrderLock, releaseE2etOrderLock } from './_e2et-order-lock';
import {
  dragNodeToCanvas,
  connectEdge,
  fillNodeConfig,
  setEdgeCondition,
  saveAutomation,
  clickDesignerSave,
  enableViaListToggle,
  latestLog,
  pollNodeStatuses,
  deleteViaApi,
  currentNodeIds,
} from '../_helpers/flow-designer-harness';

const DESIGNER_NEW = '/automation/new';
const MODEL_CODE = 'e2et_order';
const MODEL_LABEL = '测试订单'; // e2et_order displayName — model-select options render by label.
const CREATE_COMMAND = 'e2eto:create_e2et_order';

async function loginAsAdmin(page: Page, baseURL: string): Promise<void> {
  const response = await page.request.post(`${baseURL}/login`, {
    form: {
      channelCode: 'email_password',
      identifier: DEFAULT_TEST_ACCOUNT.email,
      password: DEFAULT_TEST_ACCOUNT.password,
      remember: 'on',
      redirectTo: '/',
    },
    maxRedirects: 0,
  });

  expect(response.status(), `login failed: HTTP ${response.status()}`).toBe(302);
  const sessionCookie = response.headers()['set-cookie']?.match(/__session=([^;]+)/)?.[1];
  expect(sessionCookie, 'BFF login must return a __session cookie').toEqual(expect.any(String));

  const hostname = new URL(baseURL).hostname;
  const cookieBase = {
    name: '__session',
    value: sessionCookie!,
    httpOnly: true,
    secure: false,
    sameSite: 'Lax' as const,
    expires: Math.floor(Date.now() / 1000) + 60 * 60,
  };
  await page.context().addCookies([
    { ...cookieBase, url: baseURL },
    { ...cookieBase, domain: hostname, path: '/' },
    { ...cookieBase, domain: 'localhost', path: '/' },
    { ...cookieBase, domain: '127.0.0.1', path: '/' },
  ]);
}

function backendUrlForGolden(): URL | null {
  const raw = BACKEND_URL;
  if (!raw) return null;
  try {
    return new URL(raw);
  } catch {
    return null;
  }
}

const goldenBackendUrl = backendUrlForGolden();

// Reachable URLs for real outbound round-trips. With BACKEND_URL set, derive host-mode
// targets from that live backend; otherwise keep the Docker GA default
// (host.docker.internal:6444). Host-mode runs must start the backend with
// AURA_SSRF_ALLOWED_PRIVATE_HOSTS containing the derived host.
const OUTBOUND_HOST = process.env.E2E_OUTBOUND_HOST || goldenBackendUrl?.hostname || 'host.docker.internal';
const CALLAPI_OK_URL = process.env.E2E_CALLAPI_OK_URL || (goldenBackendUrl ? new URL('/actuator/health', goldenBackendUrl).toString() : 'http://host.docker.internal:6444/actuator/health');
const CALLAPI_404_URL =
  process.env.E2E_CALLAPI_404_URL ||
  (goldenBackendUrl ? new URL('/actuator/this-endpoint-does-not-exist-404', goldenBackendUrl).toString() : 'http://host.docker.internal:6444/api/this-endpoint-does-not-exist-404');

async function delay(ms: number): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, ms));
}

async function observeFor(ms: number, assertion: () => void, intervalMs = 100): Promise<void> {
  const deadline = Date.now() + ms;
  while (Date.now() < deadline) {
    assertion();
    await delay(intervalMs);
  }
  assertion();
}

// ───────────────────────── fire + assert helpers (no UI surface) ─────────────────────────

/**
 * Fire the trigger by creating an e2et_order record via the DSL command. `amount`
 * lands in the trigger `record` map (payload), which the gateway condition reads.
 * Returns the created record id so the side effect can be asserted.
 */
async function fireCreate(
  page: Page,
  amount: number,
  title: string,
  orderDate = '2026-05-30',
): Promise<string> {
  const resp = await page.request.post(`/api/meta/commands/execute/${CREATE_COMMAND}`, {
    data: {
      operationType: 'create',
      payload: {
        e2et_order_title: title,
        e2et_order_date: orderDate,
        e2et_order_type: 'normal',
        e2et_order_amount: amount,
      },
    },
  });
  const body = await resp.json();
  expect(String(body.code), `create command failed: ${JSON.stringify(body)}`).toBe('0');
  const recordId =
    body.data?.data?.recordId ??
    body.data?.data?.recordPid ??
    body.data?.data?.pid ??
    body.data?.recordId ??
    body.data?.recordPid ??
    body.data?.pid ??
    body.data?.id;
  expect(recordId, `create command did not return a record id: ${JSON.stringify(body)}`).toEqual(expect.any(String));
  return String(recordId);
}

/** GET the e2et_order record's persisted fields via the dynamic data API. */
async function getOrderRecord(page: Page, recordId: string): Promise<Record<string, unknown>> {
  const resp = await page.request.get(`/api/dynamic/${MODEL_CODE}/${recordId}`);
  expect(resp.ok(), `record fetch ${recordId} failed`).toBeTruthy();
  return (await resp.json()).data as Record<string, unknown>;
}

/** Poll the order record until a field reaches an expected value (side-effect wait). */
async function pollRecordField(
  page: Page,
  recordId: string,
  field: string,
  expected: string,
  timeoutMs = 20_000,
): Promise<Record<string, unknown>> {
  const deadline = Date.now() + timeoutMs;
  let last: Record<string, unknown> = {};
  while (Date.now() < deadline) {
    last = await getOrderRecord(page, recordId);
    if (last[field] === expected) return last;
    await delay(1_000);
  }
  return last;
}

async function readFlowConfig(page: Page, pid: string): Promise<any> {
  const resp = await page.request.get(`/api/automations/${pid}`);
  return (await resp.json()).data;
}

/** Poll the automation's latest log (started >= firedAt) until terminal. */
type AutomationRunLog = {
  id: number;
  status: string;
  triggerRecordPid?: string;
  errorMessage?: string;
  actionResults?: any[];
};

type ApiEnvelope<T> = {
  code?: string | number | null;
  success?: boolean;
  data?: T;
  message?: string;
};

type JsonResponseLike = {
  ok(): boolean;
  status(): number;
  url(): string;
  json(): Promise<unknown>;
  text(): Promise<string>;
};

type CurrentUserInfo = {
  user?: {
    id?: string;
    email?: string;
  };
};

type ImMessageRecord = {
  id?: number;
  conversationId?: number;
  senderType?: string;
  content?: string;
  cardPayload?: unknown;
};

async function pollLogTerminal(
  page: Page,
  pid: string,
  firedAt: number,
  timeoutMs = 30_000,
): Promise<AutomationRunLog | null> {
  const deadline = Date.now() + timeoutMs;
  let last: AutomationRunLog | null = null;
  while (Date.now() < deadline) {
    last = (await latestLog(page, pid, firedAt)) as AutomationRunLog | null;
    if (last && ['success', 'failed', 'partial_success'].includes(String(last.status).toLowerCase())) {
      return last;
    }
    await delay(1_000);
  }
  return last;
}

function isApiSuccess<T>(body: ApiEnvelope<T> | null | undefined): body is ApiEnvelope<T> {
  if (!body) return false;
  if (body.success === false) return false;
  const code = body.code;
  return code === undefined || code === null || String(code) === '0';
}

async function readApi<T>(response: JsonResponseLike): Promise<T> {
  const body = (await response.json().catch(async () => ({
    message: await response.text().catch(() => ''),
  }))) as ApiEnvelope<T>;
  expect(response.ok(), `HTTP ${response.status()} ${response.url()}: ${JSON.stringify(body)}`).toBe(
    true,
  );
  expect(isApiSuccess(body), `API failed: ${JSON.stringify(body)}`).toBe(true);
  return body.data as T;
}

type ActionAuditRow = {
  pid: string;
  rule_code: string;
  action_type: string;
  target: string | null;
  message: string | null;
  payload_json: Record<string, unknown>;
};

type RecordCommentRow = {
  pid: string;
  model_code: string;
  record_pid: string;
  content: string;
  mentions: string | null;
  created_by: string | null;
};

type InboxItemRow = {
  id: string;
  user_id: string;
  item_type: string;
  title: string;
  subtitle: string | null;
  priority: string;
  status: string;
  source_type: string | null;
  source_id: string | null;
  model_code: string | null;
  record_pid: string | null;
  deep_link: string | null;
  card_payload: Record<string, unknown> | null;
  client_item_id: string | null;
};

async function withDb<T>(fn: (client: Client) => Promise<T>): Promise<T> {
  const client = new Client(PG_CONN);
  await client.connect();
  try {
    return await fn(client);
  } finally {
    await client.end();
  }
}

async function waitForActionAuditRow(auditPid: string, timeoutMs = 15_000): Promise<ActionAuditRow | null> {
  const deadline = Date.now() + timeoutMs;
  let row: ActionAuditRow | null = null;
  while (Date.now() < deadline) {
    row = await withDb(async (client) => {
      const result = await client.query<ActionAuditRow>(
        `
        select pid, rule_code, action_type, target, message, payload_json
        from ab_drt_action_audit
        where pid = $1
        order by created_at desc, id desc
        limit 1
        `,
        [auditPid],
      );
      return result.rows[0] ?? null;
    });
    if (row) return row;
    await delay(500);
  }
  return row;
}

async function waitForRecordCommentRow(commentPid: string, timeoutMs = 15_000): Promise<RecordCommentRow | null> {
  const deadline = Date.now() + timeoutMs;
  let row: RecordCommentRow | null = null;
  while (Date.now() < deadline) {
    row = await withDb(async (client) => {
      const result = await client.query<RecordCommentRow>(
        `
        select pid, model_code, record_pid, content, mentions, created_by
        from ab_record_comment
        where pid = $1
          and (deleted_flag = false or deleted_flag is null)
        order by created_at desc, id desc
        limit 1
        `,
        [commentPid],
      );
      return result.rows[0] ?? null;
    });
    if (row) return row;
    await delay(500);
  }
  return row;
}

async function waitForInboxItemRow(itemId: string | number, timeoutMs = 15_000): Promise<InboxItemRow | null> {
  const deadline = Date.now() + timeoutMs;
  let row: InboxItemRow | null = null;
  while (Date.now() < deadline) {
    row = await withDb(async (client) => {
      const result = await client.query<InboxItemRow>(
        `
        select id::text, user_id::text, item_type, title, subtitle, priority, status,
               source_type, source_id, model_code, record_pid, deep_link, card_payload, client_item_id
        from ab_inbox_item
        where id = $1
        limit 1
        `,
        [String(itemId)],
      );
      return result.rows[0] ?? null;
    });
    if (row) return row;
    await delay(500);
  }
  return row;
}

// Bootstrap a designer canvas at /automation/new with the palette + pane ready.
async function openNewDesigner(page: Page): Promise<void> {
  await page.goto(DESIGNER_NEW);
  // Cold vite dev compiles the heavy @xyflow designer chunk on first hit, which can
  // exceed the default 5s action timeout (the navigation stays "in progress" while
  // the lazy chunk transforms). Wait explicitly for the outer editor to be
  // interactive (name input) with a cold-compile-absorbing timeout BEFORE any test
  // interaction, mirroring the sibling designer specs' name-input gate — otherwise
  // the first test in a fresh-stack run flakes on setAutomationName.
  await page
    .locator('[data-testid="automation-editor-name-input"]')
    .waitFor({ state: 'visible', timeout: 30_000 });
  await page.locator('[data-testid="flow-palette"]').waitFor({ state: 'attached', timeout: 20_000 });
  await page.locator('.react-flow__pane').waitFor({ state: 'visible', timeout: 10_000 });
}

// Set the automation name in the outer editor header. The backend rejects an
// empty name with HTTP 400, so this is required for save. The controlled input
// only commits via per-keystroke onChange — `fill()` does NOT stick (it sets the
// value without firing React's onChange), so we type it (proven pattern, mirrors
// automation-validation-gate.spec.ts).
async function setAutomationName(page: Page, name: string): Promise<void> {
  const input = page.locator('[data-testid="automation-editor-name-input"]');
  await input.waitFor({ state: 'visible' });
  // pressSequentially occasionally drops the first keystrokes when React has not yet
  // attached the controlled onChange handler — observed as value="" under full-suite
  // serial load (the residual flake source, since every case calls this). Retry the
  // clear→type→verify block until the value actually sticks.
  await expect(async () => {
    await input.click();
    await input.fill('');
    await input.pressSequentially(name, { delay: 15 });
    await expect(input).toHaveValue(name, { timeout: 2_000 });
  }).toPass({ timeout: 15_000, intervals: [250, 500, 1_000] });
}

// Webhook fail-closed (#557): an inbound-webhook automation must carry a token/
// signature validation mode + a secret, otherwise the endpoint refuses the trigger
// ("validation not configured"). These helpers configure token validation via the
// real property panel and fire the inbound POST with the matching X-Webhook-Token.
const WEBHOOK_TOKEN = 'e2e-designer-webhook-token';
async function configureWebhookToken(page: Page, triggerId: string): Promise<void> {
  await fillNodeConfig(page, triggerId, { validationMode: 'token', secret: WEBHOOK_TOKEN });
}
function fireInboundWebhook(page: Page, pid: string, data: Record<string, unknown>) {
  return page.request.post(`/api/automations/webhooks/${pid}`, {
    headers: { 'X-Webhook-Token': WEBHOOK_TOKEN },
    data,
  });
}

// ───────────────────────── tests ─────────────────────────

// Serialize against other e2et_order-mutating automation files. H1 leaves an enabled
// on_record_create→update_record automation live across the serial H1→H2→H3 chain;
// without this lock it corrupts automation-golden's records on a parallel worker.
// See _e2et-order-lock.
test.beforeAll(async () => {
  // This hook blocks until the shared e2et_order lock is free, which can take the
  // full duration of another automation spec. Disable the default 15s hook
  // timeout so the wait isn't killed (acquireE2etOrderLock has its own cap).
  test.setTimeout(0);
  await acquireE2etOrderLock('automation-designer-golden');
});
test.afterAll(() => releaseE2etOrderLock('automation-designer-golden'));

test.describe('Automation Designer — Layer A real drag-drop golden', () => {
  test.describe.configure({ mode: 'serial', timeout: 120_000 });

  const createdPids: string[] = [];
  // Shared across the serial H1→H2→H3 chain so the heavy drag-build runs once and
  // the persistence + overlay cases reuse the same saved automation.
  const h1: { pid?: string; logId?: number; nodeIds?: string[] } = {};

  test.beforeEach(async ({ page, baseURL }) => {
    await loginAsAdmin(page, baseURL ?? 'http://127.0.0.1:5194');
  });

  test.afterAll(async ({ browser }) => {
    if (!createdPids.length) return;
    const ctx = await browser.newContext();
    const page = await ctx.newPage();
    await loginAsAdmin(page, BASE_URL);
    for (const pid of createdPids) await deleteViaApi(page, pid);
    await page.close();
    await ctx.close();
  });

  test('H1: build trigger→condition→update-record by drag, save, reload, enable, fire, assert side effect @golden', async ({
    page,
  }) => {
    const name = `H1 golden ${uniqueId()}`;
    await openNewDesigner(page);
    await setAutomationName(page, name);

    // ── BUILD by real drag-drop ─────────────────────────────────────────────
    // Spacing keeps all four nodes + their handles inside the visible viewport
    // (fitView is disabled on an empty canvas so nodes render at drop coords).
    const trigger = await dragNodeToCanvas(page, 'trigger-record-create', { x: 150, y: 70 });
    const condition = await dragNodeToCanvas(page, 'control-condition', { x: 150, y: 210 });
    const actHigh = await dragNodeToCanvas(page, 'action-update-record', { x: 30, y: 360 });
    const actLow = await dragNodeToCanvas(page, 'action-update-record', { x: 240, y: 360 });
    expect((await currentNodeIds(page)).sort()).toEqual(
      [trigger, condition, actHigh, actLow].sort(),
    );

    // ── CONNECT edges (real @xyflow pointer drag) FIRST, while no property panel
    //    overlaps the canvas, so every handle is reachable. ───────────────────
    await page.locator('.react-flow__pane').click({ position: { x: 5, y: 5 } }); // deselect
    await connectEdge(page, trigger, condition);
    const eTrue = await connectEdge(page, condition, actHigh, 'true');
    const eFalse = await connectEdge(page, condition, actLow, 'false');

    // ── CONFIGURE each node via the real property panel ─────────────────────
    await fillNodeConfig(page, trigger, { modelCode: MODEL_LABEL });
    // The condition node's own expression field is required by the save-gate
    // (validateFlow) even though the gateway routes on the edge conditions.
    await fillNodeConfig(page, condition, { expression: "record['e2et_order_amount'] > 1000" });
    await fillNodeConfig(page, actHigh, {
      modelCode: MODEL_LABEL,
      recordPid: '${trigger.recordPid}',
      fields: '{"e2et_order_title":"AUTOMATION_FIRED_HIGH"}',
    });
    await fillNodeConfig(page, actLow, {
      modelCode: MODEL_LABEL,
      recordPid: '${trigger.recordPid}',
      fields: '{"e2et_order_title":"AUTOMATION_FIRED_LOW"}',
    });

    // ── SET the gateway branch conditions on the edges (real edge inspector) ─
    // SmartEngine requires every gateway out-edge to carry a conditionExpression
    // and >= 2 outgoing transitions (PB-1). Bind via top-level record map.
    await setEdgeCondition(page, eTrue, "record['e2et_order_amount'] > 1000");
    await setEdgeCondition(page, eFalse, "record['e2et_order_amount'] <= 1000");

    // ── SAVE (passes the validation gate; persists flowConfig) ──────────────
    const { pid } = await saveAutomation(page);
    createdPids.push(pid);

    // Assert flowConfig persisted with 4 nodes + 3 edges (incl. branch conditions).
    const saved = await readFlowConfig(page, pid);
    expect(saved.flowConfig.nodes).toHaveLength(4);
    expect(saved.flowConfig.edges).toHaveLength(3);
    const trueEdge = saved.flowConfig.edges.find((e: any) => e.id === eTrue);
    expect(trueEdge?.data?.condition?.content).toBe("record['e2et_order_amount'] > 1000");

    // ── RELOAD: re-open the editor and assert the 4-node graph re-renders ───
    await page.goto(`/automation/${pid}`);
    await expect(page.locator('.react-flow__node')).toHaveCount(4, { timeout: 15_000 });

    // ── ENABLE via the real list-page toggle ────────────────────────────────
    await enableViaListToggle(page, pid);

    // ── FIRE: create a record with amount satisfying the TRUE branch ────────
    const firedAt = Date.now();
    const recordId = await fireCreate(page, 5000, `H1-order ${uniqueId()}`);

    // ── POLL the automation log until it reaches a terminal status ──────────
    const log = await pollLogTerminal(page, pid, firedAt);
    expect(log, 'an automation log row should exist after fire').not.toBeNull();
    const statuses = await pollNodeStatuses(page, log!.id, 30_000);
    expect(String(log!.status).toLowerCase(), `log should be success: ${JSON.stringify(log)}`).toBe(
      'success',
    );
    // Trigger + condition + the TRUE action executed; none failed.
    const failed = statuses.filter((s) => s.status === 'failed');
    expect(failed, `no node should fail: ${JSON.stringify(statuses)}`).toHaveLength(0);
    const high = statuses.find((s) => s.nodeId === actHigh);
    expect(high?.status, 'the TRUE-branch update-record node should complete').toBe('completed');
    // The FALSE branch must NOT run.
    expect(statuses.find((s) => s.nodeId === actLow)).toBeUndefined();

    // ── ASSERT side effect: the trigger record's title was updated ──────────
    const record = await pollRecordField(page, recordId, 'e2et_order_title', 'AUTOMATION_FIRED_HIGH');
    expect(record.e2et_order_title, 'TRUE-branch update-record side effect').toBe(
      'AUTOMATION_FIRED_HIGH',
    );

    // ── Screenshot the canvas (persistence roundtrip view) ──────────────────
    await page.goto(`/automation/${pid}`);
    await expect(page.locator('.react-flow__node').first()).toBeVisible({ timeout: 15_000 });
    await page.screenshot({
      path: 'test-results/artifacts/automation-designer-H1-canvas.png',
      fullPage: false,
    });

    // Hand off to H2/H3 (serial): reuse this saved + fired automation.
    h1.pid = pid;
    h1.logId = log!.id;
    h1.nodeIds = [trigger, condition, actHigh, actLow];
  });

  test('H2: navigate away to /automations and back to the editor — 4-node graph re-renders (persistence roundtrip) @golden', async ({
    page,
  }) => {
    expect(h1.pid, 'H2 depends on H1 having saved an automation').toBeTruthy();
    // Open the editor, confirm the graph, then navigate AWAY to the list.
    await page.goto(`/automation/${h1.pid}`);
    await expect(page.locator('.react-flow__node')).toHaveCount(4, { timeout: 15_000 });
    await page.goto('/automations');
    await expect(page.locator(`[data-testid="status-${h1.pid}"]`)).toBeVisible({ timeout: 15_000 });
    // Navigate BACK to the editor — the 4-node graph must re-render from the DB.
    await page.goto(`/automation/${h1.pid}`);
    await expect(page.locator('.react-flow__node')).toHaveCount(4, { timeout: 15_000 });
    // The condition + both action nodes are still present (labels render).
    await expect(page.locator('.react-flow__node').filter({ hasText: '条件分支' })).toHaveCount(1);
    await expect(page.locator('.react-flow__node').filter({ hasText: '更新记录' })).toHaveCount(2);
  });

  test('H3: open the editor with ?logId — G5 runtime status badges render on the canvas @golden', async ({
    page,
  }) => {
    expect(h1.logId, 'H3 depends on H1 having produced a run log').toBeTruthy();
    // Open the editor scoped to the H1 run log; the route wires
    // useAutomationNodeStatuses(logId) → FlowDesigner.nodeStatuses, so each node
    // that executed renders a status badge ([data-testid="flow-node-<id>-status-badge"]).
    await page.goto(`/automation/${h1.pid}?logId=${h1.logId}`);
    await expect(page.locator('.react-flow__node').first()).toBeVisible({ timeout: 15_000 });

    // Only executed serviceTask (action) nodes record a node-execution row, so
    // the overlay badges the TRUE-branch update-record node. The trigger
    // (startEvent) and condition (exclusiveGateway) are not serviceTasks and
    // record no status. Assert the executed action shows a completed badge and
    // the not-taken FALSE branch shows none.
    const [, , actHigh, actLow] = h1.nodeIds!;
    const highBadge = page.locator(`[data-testid="flow-node-${actHigh}-status-badge"]`);
    await expect(highBadge, 'the executed TRUE-branch action should show a status badge').toBeVisible({
      timeout: 15_000,
    });
    await expect(page.locator(`[data-testid="flow-node-${actHigh}"]`)).toHaveAttribute(
      'data-runtime-status',
      'completed',
    );
    // The not-taken FALSE branch action did not execute → no badge.
    await expect(
      page.locator(`[data-testid="flow-node-${actLow}-status-badge"]`),
      'the not-taken FALSE branch should have no runtime badge',
    ).toHaveCount(0);

    await page.screenshot({
      path: 'test-results/artifacts/automation-designer-H3-status-overlay.png',
      fullPage: false,
    });
  });

  test('S1: required modelCode left empty → save is blocked by the validation gate with a field-level error @golden', async ({
    page,
  }) => {
    await openNewDesigner(page);
    await setAutomationName(page, `S1 ${uniqueId()}`);

    // Build a trigger node but leave its required modelCode empty.
    const trigger = await dragNodeToCanvas(page, 'trigger-record-create', { x: 180, y: 150 });

    // Click save; the inner FlowDesigner runs the validation gate and must block.
    // We watch for any /api/automations write — there must be none (not a silent save).
    let wroteApi = false;
    page.on('request', (r) => {
      if (/\/api\/automations(\/[^/]+)?$/.test(r.url()) && ['POST', 'PUT'].includes(r.method())) {
        wroteApi = true;
      }
    });
    await clickDesignerSave(page);

    // The errored node is auto-selected and its required field renders a
    // field-level error in the property panel — NOT just a generic toast.
    const panel = page.locator(`[data-testid="prop-field-modelCode"]`);
    await expect(panel.getByText('This field is required')).toBeVisible({ timeout: 5_000 });
    // The toolbar surfaces a structured error count (not a transient toast).
    await expect(page.getByText(/存在错误|Errors/).first()).toBeVisible();

    // The validation gate truly blocked the write.
    await observeFor(1_000, () => {
      expect(wroteApi, 'save must NOT fire an /api/automations write when invalid').toBe(false);
    });
    // And the store validation confirms the field-level error for the trigger.
    const validation = await page.evaluate(
      () => (window as unknown as { __flowDesignerStore?: any }).__flowDesignerStore?.getState().validationResult,
    );
    expect(validation?.valid).toBe(false);
    expect(validation?.errors?.[0]).toMatchObject({ nodeId: trigger, fieldKey: 'modelCode' });

    await page.screenshot({
      path: 'test-results/artifacts/automation-designer-S1-validation-gate.png',
      fullPage: false,
    });
  });

  test('S2: a dangerous condition expression is rejected at runtime — the flow fails and the side effect does not happen @golden', async ({
    page,
  }) => {
    const name = `S2 ${uniqueId()}`;
    await openNewDesigner(page);
    await setAutomationName(page, name);

    // Build a real 2-branch condition flow, but set the TRUE-branch edge condition
    // to a dangerous SpEL type reference. The designer persists it (there is no
    // client-side SpEL guard on the edge), and SmartEngine's read-only gateway
    // evaluator rejects it at runtime (T(...) is not resolvable) — so the run
    // fails and the dangerous branch never executes its side effect.
    const trigger = await dragNodeToCanvas(page, 'trigger-record-create', { x: 150, y: 70 });
    const condition = await dragNodeToCanvas(page, 'control-condition', { x: 150, y: 210 });
    const actHigh = await dragNodeToCanvas(page, 'action-update-record', { x: 30, y: 360 });
    const actLow = await dragNodeToCanvas(page, 'action-update-record', { x: 240, y: 360 });

    await page.locator('.react-flow__pane').click({ position: { x: 5, y: 5 } });
    await connectEdge(page, trigger, condition);
    const eTrue = await connectEdge(page, condition, actHigh, 'true');
    const eFalse = await connectEdge(page, condition, actLow, 'false');

    await fillNodeConfig(page, trigger, { modelCode: MODEL_LABEL });
    await fillNodeConfig(page, condition, { expression: 'dangerous' });
    await fillNodeConfig(page, actHigh, {
      modelCode: MODEL_LABEL,
      recordPid: '${trigger.recordPid}',
      fields: '{"e2et_order_title":"S2_SHOULD_NOT_HAPPEN"}',
    });
    await fillNodeConfig(page, actLow, {
      modelCode: MODEL_LABEL,
      recordPid: '${trigger.recordPid}',
      fields: '{"e2et_order_title":"S2_LOW"}',
    });
    // Dangerous TRUE-branch expression (SpEL type reference / code-exec attempt).
    const danger = 'T(java.lang.Runtime).getRuntime().exec("touch /tmp/pwned")';
    await setEdgeCondition(page, eTrue, danger);
    await setEdgeCondition(page, eFalse, "record['e2et_order_amount'] <= 1000");

    const { pid } = await saveAutomation(page);
    createdPids.push(pid);
    // It persists (no client guard) — proving the rejection is a runtime one.
    const saved = await readFlowConfig(page, pid);
    const trueEdge = saved.flowConfig.edges.find((e: any) => e.id === eTrue);
    expect(trueEdge?.data?.condition?.content).toBe(danger);

    await enableViaListToggle(page, pid);

    // Fire with amount that WOULD satisfy the (dangerous) TRUE branch if it
    // evaluated — but the evaluator rejects it.
    const firedAt = Date.now();
    const recordId = await fireCreate(page, 5000, `S2-order ${uniqueId()}`);
    const log = await pollLogTerminal(page, pid, firedAt);
    expect(log, 'a log row should exist').not.toBeNull();
    // The run fails because the dangerous expression is rejected by the evaluator.
    expect(String(log!.status).toLowerCase(), `S2 run should fail: ${JSON.stringify(log)}`).toBe(
      'failed',
    );
    expect(log!.errorMessage ?? '', 'the failure should reference the rejected expression').toMatch(
      /T\b|function not found|null pointer/i,
    );
    // The dangerous side effect did NOT happen — the record title is untouched.
    const record = await getOrderRecord(page, recordId);
    expect(record.e2et_order_title, 'dangerous branch must not have run').not.toBe(
      'S2_SHOULD_NOT_HAPPEN',
    );
  });

  test('E5: edit an enabled automation\'s action config via the UI, save, re-fire → the NEW behavior takes effect @golden', async ({
    page,
  }) => {
    expect(h1.pid, 'E5 reuses the H1 automation').toBeTruthy();
    const pid = h1.pid!;
    const [, , actHigh] = h1.nodeIds!;
    const NEW_MARKER = `E5_REBEHAVED_${uniqueId()}`;

    // Open the existing (enabled) automation and change the TRUE-branch action's
    // field mapping via the real property panel.
    await page.goto(`/automation/${pid}`);
    await expect(page.locator('.react-flow__node')).toHaveCount(4, { timeout: 15_000 });
    await fillNodeConfig(page, actHigh, {
      fields: `{"e2et_order_title":"${NEW_MARKER}"}`,
    });
    // Re-save via the designer (the gate passes — everything is still valid).
    const { pid: savedPid } = await saveAutomation(page);
    expect(savedPid).toBe(pid);
    const saved = await readFlowConfig(page, pid);
    const highNode = saved.flowConfig.nodes.find((n: any) => n.id === actHigh);
    expect(highNode.data.config.fields).toMatchObject({ e2et_order_title: NEW_MARKER });

    // It was already enabled in H1; re-fire and assert the NEW behavior.
    const firedAt = Date.now();
    const recordId = await fireCreate(page, 5000, `E5-order ${uniqueId()}`);
    const log = await pollLogTerminal(page, pid, firedAt);
    expect(String(log!.status).toLowerCase(), `E5 run should succeed: ${JSON.stringify(log)}`).toBe(
      'success',
    );
    const record = await pollRecordField(page, recordId, 'e2et_order_title', NEW_MARKER);
    expect(record.e2et_order_title, 'the edited action config should now drive the side effect').toBe(
      NEW_MARKER,
    );
  });

  // ─────────────────── Per-node real-UI golden (goal 2026-06-06) ───────────────────
  // Each case below drives the REAL designer (drag the node-under-test, configure via
  // the real property panel, connect, save, enable via the list toggle, fire a real
  // trigger) then verifies the backend ran correctly. Reuses only proven H1 patterns
  // (model-select by label, json field). SmartEngine-dependent nodes are covered only
  // when the live runtime seam is proven in this spec.

  /** Poll the dynamic list for an e2et_order_item carrying `name` (create-record side effect). */
  async function pollItemsByName(page: Page, name: string, timeoutMs = 20_000): Promise<any[]> {
    const deadline = Date.now() + timeoutMs;
    let rows: any[] = [];
    while (Date.now() < deadline) {
      const resp = await page.request.get(`/api/dynamic/e2et_order_item/list`, {
        params: { pageNum: 1, pageSize: 50, keyword: name },
      });
      if (resp.ok()) {
        const data = (await resp.json())?.data;
        rows = data?.records ?? data?.list ?? data?.content ?? data?.rows ?? (Array.isArray(data) ? data : []);
        const hit = rows.filter((r) => r?.e2et_item_name === name);
        if (hit.length) return hit;
      }
      await delay(1_000);
    }
    return rows.filter((r) => r?.e2et_item_name === name);
  }

  async function fetchItemsByName(page: Page, name: string): Promise<any[]> {
    const resp = await page.request.get(`/api/dynamic/e2et_order_item/list`, {
      params: { pageNum: 1, pageSize: 50, keyword: name },
    });
    if (!resp.ok()) return [];
    const data = (await resp.json())?.data;
    const rows = data?.records ?? data?.list ?? data?.content ?? data?.rows ?? (Array.isArray(data) ? data : []);
    return rows.filter((r: any) => r?.e2et_item_name === name);
  }

  async function expectNoItemsByNameFor(
    page: Page,
    name: string,
    observationMs: number,
    message: string,
  ): Promise<void> {
    const deadline = Date.now() + observationMs;
    while (Date.now() < deadline) {
      expect(await fetchItemsByName(page, name), message).toHaveLength(0);
      await delay(1_000);
    }
    expect(await fetchItemsByName(page, name), message).toHaveLength(0);
  }

  async function pollProcessStatusByBusinessKey(
    page: Page,
    businessKey: string,
    processKey: string,
    timeoutMs = 30_000,
  ): Promise<any | null> {
    const deadline = Date.now() + timeoutMs;
    let last: any = null;
    while (Date.now() < deadline) {
      const resp = await page.request.get('/api/bpm/process-instances/by-business-key/status', {
        params: { businessKey, processKey },
      });
      if (resp.ok()) {
        last = (await resp.json())?.data ?? null;
        if (last?.instanceId) return last;
      }
      await delay(1_000);
    }
    return last;
  }

  async function startBpmProcess(
    page: Page,
    processKey: string,
    businessKey: string,
    variables: Record<string, unknown>,
  ): Promise<string> {
    const resp = await page.request.post('/api/bpm/process-instances', {
      data: {
        processDefinitionId: processKey,
        businessKey,
        variables,
      },
    });
    expect(resp.ok(), `BPM start failed with ${resp.status()}: ${await resp.text()}`).toBe(true);
    const body = await resp.json();
    expect(String(body.code), `BPM start response: ${JSON.stringify(body)}`).toBe('0');
    const instanceId = body.data?.instanceId as string | undefined;
    expect(instanceId, `BPM start missing instanceId: ${JSON.stringify(body)}`).toBeTruthy();
    return instanceId!;
  }

  test('N-CREATE-RECORD: drag trigger-record-create→action-create-record, configure via panel, save, enable, fire → a child order_item is created (happy) @golden', async ({
    page,
  }) => {
    const itemName = `NCR-item-${uniqueId()}`;
    await openNewDesigner(page);
    await setAutomationName(page, `N-CREATE-RECORD ${uniqueId()}`);

    // BUILD by real drag-drop + connect.
    const trigger = await dragNodeToCanvas(page, 'trigger-record-create', { x: 150, y: 80 });
    const action = await dragNodeToCanvas(page, 'action-create-record', { x: 150, y: 240 });
    await page.locator('.react-flow__pane').click({ position: { x: 5, y: 5 } });
    await connectEdge(page, trigger, action);

    // CONFIGURE via the real property panel (model-select by label, json field).
    await fillNodeConfig(page, trigger, { modelCode: MODEL_LABEL });
    await fillNodeConfig(page, action, {
      modelCode: '订单明细', // e2et_order_item displayName
      fields:
        '{"e2et_order_id":"${recordId}","e2et_item_name":"' +
        itemName +
        '","e2et_item_spec":"std","e2et_item_qty":2,"e2et_item_price":50}',
    });

    // SAVE → assert persisted 2-node/1-edge flow.
    const { pid } = await saveAutomation(page);
    createdPids.push(pid);
    const saved = await readFlowConfig(page, pid);
    expect(saved.flowConfig.nodes).toHaveLength(2);
    expect(saved.flowConfig.edges).toHaveLength(1);

    // ENABLE via the real list toggle, then FIRE a real create.
    await enableViaListToggle(page, pid);
    const firedAt = Date.now();
    await fireCreate(page, 100, `NCR-order ${uniqueId()}`);

    // BACKEND verify: run success + the create-record node completed + the child item exists.
    const log = await pollLogTerminal(page, pid, firedAt);
    expect(String(log!.status).toLowerCase(), `N-CREATE-RECORD run: ${JSON.stringify(log)}`).toBe('success');
    const statuses = await pollNodeStatuses(page, log!.id, 30_000);
    expect(statuses.find((s) => s.nodeId === action)?.status, `create-record node completed: ${JSON.stringify(statuses)}`).toBe('completed');
    const items = await pollItemsByName(page, itemName);
    expect(items.length, `child order_item '${itemName}' created by the create-record action`).toBeGreaterThanOrEqual(1);
  });

  /** Fire a record UPDATE (top-level targetRecordPid). Fires on_record_update. */
  async function fireUpdate(page: Page, orderId: string, fields: Record<string, unknown>): Promise<void> {
    const resp = await page.request.post(`/api/meta/commands/execute/e2et:update_order`, {
      data: { operationType: 'update', targetRecordPid: orderId, payload: fields },
    });
    const body = await resp.json();
    expect(String(body.code), `update fire failed: ${JSON.stringify(body)}`).toBe('0');
  }

  test('N-TRIGGER-UPDATE: drag trigger-record-update→action-create-record, save, enable, fire via a real UPDATE → on_record_update runs @golden', async ({
    page,
  }) => {
    const itemName = `NTU-item-${uniqueId()}`;
    await openNewDesigner(page);
    await setAutomationName(page, `N-TRIGGER-UPDATE ${uniqueId()}`);

    const trigger = await dragNodeToCanvas(page, 'trigger-record-update', { x: 150, y: 80 });
    const action = await dragNodeToCanvas(page, 'action-create-record', { x: 150, y: 240 });
    await page.locator('.react-flow__pane').click({ position: { x: 5, y: 5 } });
    await connectEdge(page, trigger, action);
    await fillNodeConfig(page, trigger, { modelCode: MODEL_LABEL });
    await fillNodeConfig(page, action, {
      modelCode: '订单明细',
      fields:
        '{"e2et_order_id":"${recordId}","e2et_item_name":"' +
        itemName +
        '","e2et_item_spec":"std","e2et_item_qty":1,"e2et_item_price":10}',
    });
    const { pid } = await saveAutomation(page);
    createdPids.push(pid);
    await enableViaListToggle(page, pid);

    // Create an order (on_record_create — does NOT fire this on_record_update automation)…
    const orderId = await fireCreate(page, 100, `NTU-order ${uniqueId()}`);
    // …then UPDATE it → on_record_update fires.
    const firedAt = Date.now();
    await fireUpdate(page, orderId, { e2et_order_title: `NTU-changed ${uniqueId()}` });
    const log = await pollLogTerminal(page, pid, firedAt);
    expect(String(log!.status).toLowerCase(), `N-TRIGGER-UPDATE run: ${JSON.stringify(log)}`).toBe('success');
    expect(statusesNodeCompleted(await pollNodeStatuses(page, log!.id, 30_000), action), 'create-record node completed').toBe(true);
    expect((await pollItemsByName(page, itemName)).length, `child item created on update fire`).toBeGreaterThanOrEqual(1);
  });

  test('N-CALL-API: drag trigger-record-create→action-call-api, configure url+method via panel, save, enable, fire → node completes (real outbound GET) @golden', async ({
    page,
  }) => {
    await openNewDesigner(page);
    await setAutomationName(page, `N-CALL-API ${uniqueId()}`);

    const trigger = await dragNodeToCanvas(page, 'trigger-record-create', { x: 150, y: 80 });
    const action = await dragNodeToCanvas(page, 'action-call-api', { x: 150, y: 240 });
    await page.locator('.react-flow__pane').click({ position: { x: 5, y: 5 } });
    await connectEdge(page, trigger, action);
    await fillNodeConfig(page, trigger, { modelCode: MODEL_LABEL });
    // url (expression) → the backend's own actuator/health via the test-allowlisted
    // host.docker.internal; method (select) → GET. (call_api fix = FINDING-6.)
    await fillNodeConfig(page, action, {
      url: CALLAPI_OK_URL,
      method: 'GET',
    });
    const { pid } = await saveAutomation(page);
    createdPids.push(pid);
    await enableViaListToggle(page, pid);

    const firedAt = Date.now();
    await fireCreate(page, 100, `N-CALL-API-order ${uniqueId()}`);
    const log = await pollLogTerminal(page, pid, firedAt);
    expect(String(log!.status).toLowerCase(), `N-CALL-API run: ${JSON.stringify(log)}`).toBe('success');
    expect(statusesNodeCompleted(await pollNodeStatuses(page, log!.id, 30_000), action), 'call-api node completed (real outbound GET <400)').toBe(true);
  });

  // ── send-webhook REAL OUTBOUND (GAP-F): the node POSTs the payload directly to the ──
  // configured url (golden FINDING-10 — the executor previously ignored `url` and fanned
  // out to webhook subscriptions, so a designer-built send-webhook never hit the URL the
  // user typed). We stand up an in-process host receiver (reachable from the docker backend
  // via host.docker.internal) and assert the outbound POST actually LANDED with the payload.
  test('N-SEND-WEBHOOK-OUTBOUND: drag trigger-record-create→action-send-webhook, configure url+payload, fire → the outbound POST actually lands at a host receiver with the payload (happy) @golden', async ({
    page,
  }) => {
    const marker = `WBK_${uniqueId()}`;
    const receiver = await startWebhookReceiver();
    try {
      await openNewDesigner(page);
      await setAutomationName(page, `N-SEND-WEBHOOK-OUTBOUND ${uniqueId()}`);
      const trigger = await dragNodeToCanvas(page, 'trigger-record-create', { x: 150, y: 80 });
      const action = await dragNodeToCanvas(page, 'action-send-webhook', { x: 150, y: 240 });
      await page.locator('.react-flow__pane').click({ position: { x: 5, y: 5 } });
      await connectEdge(page, trigger, action);
      await fillNodeConfig(page, trigger, { modelCode: MODEL_LABEL });
      await fillNodeConfig(page, action, {
        url: `http://${OUTBOUND_HOST}:${receiver.port}/hook`,
        // ${recordId} must survive into the saved config (the executor resolves it); ${marker}
        // is interpolated by the test here so the receiver can key on it.
        payload: `{"event":"e2e.designer.webhook","marker":"${marker}","orderId":"\${recordId}"}`,
      });
      const { pid } = await saveAutomation(page);
      createdPids.push(pid);
      await enableViaListToggle(page, pid);

      const firedAt = Date.now();
      await fireCreate(page, 100, `WBK-order ${uniqueId()}`);
      const log = await pollLogTerminal(page, pid, firedAt);
      expect(String(log!.status).toLowerCase(), `N-SEND-WEBHOOK-OUTBOUND run: ${JSON.stringify(log)}`).toBe('success');
      expect(statusesNodeCompleted(await pollNodeStatuses(page, log!.id, 30_000), action), 'send-webhook node completed').toBe(true);
      // The outbound POST actually landed at the host receiver carrying the payload.
      await expect
        .poll(() => receiver.received.some((r) => r.body.includes(marker)), { timeout: 15_000 })
        .toBe(true);
      const hit = receiver.received.find((r) => r.body.includes(marker))!;
      expect(hit.method, 'webhook delivered as a POST').toBe('POST');
      expect(hit.path, 'POST landed on the configured /hook path').toContain('/hook');
      const parsed = JSON.parse(hit.body);
      expect(parsed.event, 'payload event field delivered').toBe('e2e.designer.webhook');
      expect(parsed.orderId, '${recordId} resolved in the delivered payload').toBeTruthy();

      const actionResult = (log!.actionResults ?? []).find((item: any) => item.actionType === 'send_webhook');
      expect(actionResult?.status, `send_webhook actionResult returned: ${JSON.stringify(log!.actionResults)}`).toBe('success');
      expect(actionResult?.result).toMatchObject({
        success: true,
        deliveryMode: 'direct_http',
        statusCode: 200,
      });

      await page.goto(`/automation/${pid}?logId=${log!.id}`);
      await expect(page.locator(`[data-testid="flow-node-${action}"]`)).toHaveAttribute(
        'data-runtime-status',
        'completed',
        { timeout: 15_000 },
      );
      const actionCard = page.getByTestId('automation-action-result-1');
      await expect(actionCard).toContainText(/发送 Webhook|Send Webhook/);
      await expect(actionCard).toContainText(/投递方式|Delivery Mode/);
      await expect(actionCard).toContainText(/直连 HTTP|Direct HTTP/);
      await expect(actionCard).toContainText(/HTTP 状态|HTTP Status/);
      await expect(actionCard).toContainText('200');
      await expect(actionCard).toContainText(/目标地址|Target URL/);
      await expect(actionCard).toContainText(`/hook`);
      await expect(actionCard).toContainText(/响应摘要|Response Preview/);
      await expect(actionCard).toContainText('{"ok":true}');
      const evidence = actionCard.getByTestId('automation-action-evidence');
      await expect(evidence).not.toContainText('deliveryMode');
      await expect(evidence).not.toContainText('responseBodyPreview');
      await expect(evidence).not.toContainText('responseBytes');
      await page.screenshot({
        path: 'test-results/artifacts/automation-designer-N-send-webhook-outbound-status-overlay.png',
        fullPage: false,
      });
    } finally {
      await receiver.close();
    }
  });

  // ── send-webhook SAD (real UI): the receiver returns 500 → the node FAILS with the status ──
  test('N-SEND-WEBHOOK-SAD: send-webhook to an endpoint returning 500 → the node FAILS with the upstream status, and the POST still physically reached the receiver (sad) @golden', async ({
    page,
  }) => {
    const receiver = await startWebhookReceiver();
    try {
      await openNewDesigner(page);
      await setAutomationName(page, `N-SEND-WEBHOOK-SAD ${uniqueId()}`);
      const trigger = await dragNodeToCanvas(page, 'trigger-record-create', { x: 150, y: 80 });
      const action = await dragNodeToCanvas(page, 'action-send-webhook', { x: 150, y: 240 });
      await page.locator('.react-flow__pane').click({ position: { x: 5, y: 5 } });
      await connectEdge(page, trigger, action);
      await fillNodeConfig(page, trigger, { modelCode: MODEL_LABEL });
      // The /fail path on the receiver returns HTTP 500 → SendWebhookExecutor throws on >=400.
      await fillNodeConfig(page, action, {
        url: `http://${OUTBOUND_HOST}:${receiver.port}/fail`,
        payload: '{"event":"e2e.designer.webhook.sad","orderId":"${recordId}"}',
      });
      const { pid } = await saveAutomation(page);
      createdPids.push(pid);
      await enableViaListToggle(page, pid);

      const firedAt = Date.now();
      await fireCreate(page, 100, `WBK-SAD-order ${uniqueId()}`);
      const log = await pollLogTerminal(page, pid, firedAt);
      expect(['failed', 'partial_success'], `N-SEND-WEBHOOK-SAD must fail (500): ${JSON.stringify(log)}`).toContain(String(log!.status).toLowerCase());
      const node = (await pollNodeStatuses(page, log!.id, 30_000)).find((s) => s.nodeId === action);
      expect(node?.status, 'send-webhook node should be failed on 500').toBe('failed');
      expect(node?.errorMessage ?? '', 'failure references the upstream status').toMatch(/Webhook POST failed|500|status/i);
      // The POST physically reached the receiver even though it 500'd (proves real outbound).
      expect(receiver.received.length, 'the POST reached the receiver before the 500').toBeGreaterThanOrEqual(1);

      const actionResult = (log!.actionResults ?? []).find((item: any) => item.actionType === 'send_webhook');
      expect(actionResult?.status, `send_webhook actionResult returned: ${JSON.stringify(log!.actionResults)}`).toBe('failed');
      expect(actionResult?.errorMessage ?? '', 'send_webhook action result exposes upstream failure').toMatch(
        /Webhook POST failed|500|status/i,
      );

      await page.goto(`/automation/${pid}?logId=${log!.id}`);
      await expect(page.locator(`[data-testid="flow-node-${action}"]`)).toHaveAttribute(
        'data-runtime-status',
        'failed',
        { timeout: 15_000 },
      );
      const actionCard = page.getByTestId('automation-action-result-1');
      await expect(actionCard).toContainText(/发送 Webhook|Send Webhook/);
      await expect(actionCard).toContainText(/失败|Failed/);
      await expect(actionCard).toContainText(/Webhook POST failed|500|status/i);
      await page.screenshot({
        path: 'test-results/artifacts/automation-designer-N-send-webhook-sad-status-overlay.png',
        fullPage: false,
      });
    } finally {
      await receiver.close();
    }
  });

  /** Fire a state_transition command (cancel: draft→cancelled). Fires on_state_change. */
  async function fireCancel(page: Page, orderId: string): Promise<void> {
    const resp = await page.request.post(`/api/meta/commands/execute/e2et:cancel_order`, {
      data: { operationType: 'state_transition', targetRecordPid: orderId, payload: {} },
    });
    const body = await resp.json();
    expect(String(body.code), `cancel fire failed: ${JSON.stringify(body)}`).toBe('0');
  }

  test('N-TRIGGER-FIELD-CHANGE: drag trigger-field-change(field-select)→action-create-record, save, enable, change the watched field → on_field_change runs @golden', async ({
    page,
  }) => {
    const itemName = `NFC-item-${uniqueId()}`;
    await openNewDesigner(page);
    await setAutomationName(page, `N-TRIGGER-FIELD ${uniqueId()}`);

    const trigger = await dragNodeToCanvas(page, 'trigger-field-change', { x: 150, y: 80 });
    const action = await dragNodeToCanvas(page, 'action-create-record', { x: 150, y: 240 });
    await page.locator('.react-flow__pane').click({ position: { x: 5, y: 5 } });
    await connectEdge(page, trigger, action);
    // modelCode (model-select) MUST be filled before fieldCode (field-select loads fields for it).
    await fillNodeConfig(page, trigger, { modelCode: MODEL_LABEL, fieldCode: '订单标题' });
    await fillNodeConfig(page, action, {
      modelCode: '订单明细',
      fields: '{"e2et_order_id":"${recordId}","e2et_item_name":"' + itemName + '","e2et_item_spec":"std","e2et_item_qty":1,"e2et_item_price":10}',
    });
    const { pid } = await saveAutomation(page);
    createdPids.push(pid);
    await enableViaListToggle(page, pid);

    const orderId = await fireCreate(page, 100, `NFC-order ${uniqueId()}`);
    const firedAt = Date.now();
    await fireUpdate(page, orderId, { e2et_order_title: `NFC-changed ${uniqueId()}` }); // watched field changes
    const log = await pollLogTerminal(page, pid, firedAt);
    expect(String(log!.status).toLowerCase(), `N-TRIGGER-FIELD run: ${JSON.stringify(log)}`).toBe('success');
    expect(statusesNodeCompleted(await pollNodeStatuses(page, log!.id, 30_000), action), 'create-record node completed').toBe(true);
    expect((await pollItemsByName(page, itemName)).length, 'child item created on field-change fire').toBeGreaterThanOrEqual(1);
  });

  test('N-TRIGGER-STATE-CHANGE: drag trigger-state-change(field-select)→action-create-record, save, enable, cancel the order → on_state_change runs @golden', async ({
    page,
  }) => {
    const itemName = `NSC-item-${uniqueId()}`;
    await openNewDesigner(page);
    await setAutomationName(page, `N-TRIGGER-STATE ${uniqueId()}`);

    const trigger = await dragNodeToCanvas(page, 'trigger-state-change', { x: 150, y: 80 });
    const action = await dragNodeToCanvas(page, 'action-create-record', { x: 150, y: 240 });
    await page.locator('.react-flow__pane').click({ position: { x: 5, y: 5 } });
    await connectEdge(page, trigger, action);
    // modelCode first, then stateField (field-select). toStates left empty (any transition;
    // FINDING-4b ⇒ a specific toStates filter is imprecise on the async event).
    await fillNodeConfig(page, trigger, { modelCode: MODEL_LABEL, stateField: '订单状态' });
    await fillNodeConfig(page, action, {
      modelCode: '订单明细',
      fields: '{"e2et_order_id":"${recordId}","e2et_item_name":"' + itemName + '","e2et_item_spec":"std","e2et_item_qty":1,"e2et_item_price":10}',
    });
    const { pid } = await saveAutomation(page);
    createdPids.push(pid);
    await enableViaListToggle(page, pid);

    const orderId = await fireCreate(page, 100, `NSC-order ${uniqueId()}`);
    const firedAt = Date.now();
    await fireCancel(page, orderId); // draft→cancelled state transition
    const log = await pollLogTerminal(page, pid, firedAt);
    expect(String(log!.status).toLowerCase(), `N-TRIGGER-STATE run: ${JSON.stringify(log)}`).toBe('success');
    expect(statusesNodeCompleted(await pollNodeStatuses(page, log!.id, 30_000), action), 'create-record node completed').toBe(true);
    expect((await pollItemsByName(page, itemName)).length, 'child item created on state-change fire').toBeGreaterThanOrEqual(1);
  });

  test('N-TRIGGER-WEBHOOK: drag trigger-webhook→action-call-api, save, enable, fire via a real inbound webhook POST → automation runs @golden', async ({
    page,
  }) => {
    await openNewDesigner(page);
    await setAutomationName(page, `N-TRIGGER-WEBHOOK ${uniqueId()}`);

    // The webhook endpoint is fail-closed (PR #557): a 'none' validationMode is
    // REFUSED. Configure token validation + a secret via the real property panel and
    // present it as X-Webhook-Token on the inbound POST. No modelCode (FINDING-1 made
    // model_code nullable). Action does not depend on ${recordId}.
    const webhookSecret = `nwh_${uniqueId()}`;
    const trigger = await dragNodeToCanvas(page, 'trigger-webhook', { x: 150, y: 80 });
    const action = await dragNodeToCanvas(page, 'action-call-api', { x: 150, y: 240 });
    await page.locator('.react-flow__pane').click({ position: { x: 5, y: 5 } });
    await connectEdge(page, trigger, action);
    await fillNodeConfig(page, trigger, {
      validationMode: 'token',
      secret: webhookSecret,
    });
    await fillNodeConfig(page, action, {
      url: CALLAPI_OK_URL,
      method: 'GET',
    });
    const { pid } = await saveAutomation(page);
    createdPids.push(pid);
    await enableViaListToggle(page, pid);

    // FIRE via the real inbound webhook endpoint (resolves the automation by pid),
    // presenting the configured token.
    const firedAt = Date.now();
    const resp = await page.request.post(`/api/automations/webhooks/${pid}`, {
      headers: { 'X-Webhook-Token': webhookSecret },
      data: { event: 'order.shipped', ref: `NWH ${uniqueId()}` },
    });
    expect(resp.status(), `webhook POST must not 5xx; got ${resp.status()}`).toBeLessThan(500);
    expect(String((await resp.json())?.code), 'token-validated webhook POST accepted').toBe('0');

    const log = await pollLogTerminal(page, pid, firedAt);
    expect(String(log!.status).toLowerCase(), `N-TRIGGER-WEBHOOK run: ${JSON.stringify(log)}`).toBe('success');
    expect(statusesNodeCompleted(await pollNodeStatuses(page, log!.id, 30_000), action), 'downstream action completed on webhook fire').toBe(true);
  });

  test('N-START-PROCESS: drag trigger-webhook→action-start-process, pick a BPM process, fire webhook → BPM instance is started and correlated by businessKey @golden', async ({
    page,
  }) => {
    const tag = uniqueId();
    const processKey = 'e2et_payment_approval';
    const businessKey = `NSP-${tag}`;
    await openNewDesigner(page);
    await setAutomationName(page, `N-START-PROCESS ${tag}`);

    const trigger = await dragNodeToCanvas(page, 'trigger-webhook', { x: 150, y: 80 });
    const action = await dragNodeToCanvas(page, 'action-start-process', { x: 150, y: 240 });
    await page.locator('.react-flow__pane').click({ position: { x: 5, y: 5 } });
    await connectEdge(page, trigger, action);
    await configureWebhookToken(page, trigger);
    await fillNodeConfig(page, action, {
      processKey: '付款审批流程',
      businessKey: '${bizKey}',
      variables: `{"origin":"designer-start-process","tag":"${tag}"}`,
    });

    const { pid } = await saveAutomation(page);
    createdPids.push(pid);
    const saved = await readFlowConfig(page, pid);
    const startNode = saved.flowConfig.nodes.find((n: any) => n.id === action);
    expect(startNode.data.config.processKey, 'process-select persisted the picked process key').toBe(
      processKey,
    );
    expect(startNode.data.config.businessKey, 'businessKey expression persisted from the panel').toBe(
      '${bizKey}',
    );
    expect(startNode.data.config.variables, 'variables JSON persisted as an object').toMatchObject({
      origin: 'designer-start-process',
      tag,
    });
    await enableViaListToggle(page, pid);

    const firedAt = Date.now();
    const resp = await fireInboundWebhook(page, pid, { bizKey: businessKey });
    expect(resp.status(), `webhook POST must not 5xx; got ${resp.status()}`).toBeLessThan(500);
    expect(String((await resp.json())?.code), 'webhook POST accepted').toBe('0');

    const log = await pollLogTerminal(page, pid, firedAt, 60_000);
    expect(String(log!.status).toLowerCase(), `N-START-PROCESS run: ${JSON.stringify(log)}`).toBe(
      'success',
    );
    const statuses = await pollNodeStatuses(page, log!.id, 30_000);
    expect(
      statuses.find((s) => s.nodeId === action)?.status,
      `start-process node completed: ${JSON.stringify(statuses)}`,
    ).toBe('completed');

    const bpmStatus = await pollProcessStatusByBusinessKey(page, businessKey, processKey);
    expect(bpmStatus?.processDefinitionId, `BPM status by businessKey: ${JSON.stringify(bpmStatus)}`).toBe(
      processKey,
    );
    expect(bpmStatus?.status, 'started BPM process remains at the manager review wait state').toBe(
      'running',
    );
    expect(
      bpmStatus?.currentNodes?.some((n: any) => n.nodeId === 'manager_review' && n.status === 'active'),
      'manager_review userTask is active',
    ).toBe(true);
    expect(
      bpmStatus?.completedNodes?.some((n: any) => n.nodeId === 'start_1' && n.status === 'completed'),
      'start event completed',
    ).toBe(true);
    expect(bpmStatus?.variables, 'process variables from the automation node reached BPM runtime').toMatchObject({
      origin: 'designer-start-process',
      tag,
    });
  });

  test('N-TRIGGER-BPM-EVENT: drag trigger-bpm-event→action-create-record, pick a BPM process and event type, start BPM → automation runs from task_assigned @golden', async ({
    page,
  }) => {
    const tag = uniqueId();
    const processKey = 'e2et_payment_approval';
    const businessKey = `NBE-${tag}`;
    const itemName = `NBE-item-${tag}`;
    await openNewDesigner(page);
    await setAutomationName(page, `N-TRIGGER-BPM-EVENT ${tag}`);

    const trigger = await dragNodeToCanvas(page, 'trigger-bpm-event', { x: 150, y: 80 });
    const action = await dragNodeToCanvas(page, 'action-create-record', { x: 150, y: 240 });
    await page.locator('.react-flow__pane').click({ position: { x: 5, y: 5 } });
    await connectEdge(page, trigger, action);
    await fillNodeConfig(page, trigger, {
      modelCode: '付款审批流程',
      eventTypes: ['任务分配'],
    });
    await fillNodeConfig(page, action, {
      modelCode: '订单明细',
      fields:
        '{"e2et_order_id":"${instanceId}","e2et_item_name":"' +
        itemName +
        '","e2et_item_spec":"bpm-event","e2et_item_qty":1,"e2et_item_price":10}',
    });

    const { pid } = await saveAutomation(page);
    createdPids.push(pid);
    const saved = await readFlowConfig(page, pid);
    const triggerNode = saved.flowConfig.nodes.find((n: any) => n.id === trigger);
    expect(triggerNode.data.config.modelCode, 'BPM process-select persisted process key').toBe(processKey);
    expect(triggerNode.data.config.eventTypes, 'eventTypes persisted as raw BPM event values').toContain(
      'task_assigned',
    );
    await enableViaListToggle(page, pid);

    const firedAt = Date.now();
    const instanceId = await startBpmProcess(page, processKey, businessKey, {
      origin: 'designer-bpm-event',
      tag,
    });
    const log = await pollLogTerminal(page, pid, firedAt, 60_000);
    expect(String(log!.status).toLowerCase(), `N-TRIGGER-BPM-EVENT run: ${JSON.stringify(log)}`).toBe(
      'success',
    );
    expect(log?.errorMessage ?? '', 'BPM event trigger run should not carry an error').toBe('');
    const statuses = await pollNodeStatuses(page, log!.id, 30_000);
    expect(
      statuses.find((s) => s.nodeId === action)?.status,
      `create-record node completed after BPM event: ${JSON.stringify(statuses)}`,
    ).toBe('completed');
    expect((await pollItemsByName(page, itemName)).length, 'child item created by BPM event trigger').toBeGreaterThanOrEqual(1);

    const bpmStatus = await pollProcessStatusByBusinessKey(page, businessKey, processKey);
    expect(bpmStatus?.instanceId, `BPM status by businessKey: ${JSON.stringify(bpmStatus)}`).toBe(instanceId);
    expect(
      bpmStatus?.currentNodes?.some((n: any) => n.nodeId === 'manager_review' && n.status === 'active'),
      'task_assigned came from the active manager_review userTask',
    ).toBe(true);
  });

  // ── golden SAD path (real UI) — a runtime failure surfaces on the node ──────────
  test('N-CALL-API-SAD: drag trigger-record-create→action-call-api with a 404 URL, save, enable, fire → the call-api node FAILS with the upstream status (sad path) @golden', async ({
    page,
  }) => {
    await openNewDesigner(page);
    await setAutomationName(page, `N-CALL-API-SAD ${uniqueId()}`);

    const trigger = await dragNodeToCanvas(page, 'trigger-record-create', { x: 150, y: 80 });
    const action = await dragNodeToCanvas(page, 'action-call-api', { x: 150, y: 240 });
    await page.locator('.react-flow__pane').click({ position: { x: 5, y: 5 } });
    await connectEdge(page, trigger, action);
    await fillNodeConfig(page, trigger, { modelCode: MODEL_LABEL });
    // A reachable host (SSRF test-allowlisted) but a path that 404s → CallApiExecutor throws
    // on status >= 400 → the node fails (not a silent success).
    await fillNodeConfig(page, action, {
      url: CALLAPI_404_URL,
      method: 'GET',
    });
    const { pid } = await saveAutomation(page);
    createdPids.push(pid);
    await enableViaListToggle(page, pid);

    const firedAt = Date.now();
    await fireCreate(page, 100, `N-CALL-API-SAD-order ${uniqueId()}`);
    const log = await pollLogTerminal(page, pid, firedAt);
    expect(['failed', 'partial_success'], `N-CALL-API-SAD must fail (4xx): ${JSON.stringify(log)}`).toContain(String(log!.status).toLowerCase());
    const statuses = await pollNodeStatuses(page, log!.id, 30_000);
    const node = statuses.find((s) => s.nodeId === action);
    expect(node?.status, `call-api node should be failed on 4xx: ${JSON.stringify(statuses)}`).toBe('failed');
    expect(node?.errorMessage ?? '', 'failure should reference the API failure / status').toMatch(/API call failed|40[0-9]|status/i);
  });

  /** Click the list-page toggle for an automation and assert the resulting enabled/disabled state. */
  async function setEnabledViaToggle(page: Page, pid: string, target: 'on' | 'off'): Promise<void> {
    await page.goto('/automations');
    const toggle = page.locator(`[data-testid="btn-toggle-${pid}"]`);
    await toggle.waitFor({ state: 'visible', timeout: 15_000 });
    await expect(toggle).toBeEnabled({ timeout: 10_000 });
    const respStatus = page
      .waitForResponse((r) => r.url().includes(`/api/automations/${pid}/toggle`) && r.request().method() === 'POST', { timeout: 15_000 })
      .then((r) => r.status())
      .catch(() => 0);
    await toggle.click();
    const status = await respStatus;
    if (status && status >= 400) throw new Error(`toggle returned HTTP ${status}`);
    const badge = page.locator(`[data-testid="status-${pid}"]`);
    if (target === 'on') await expect(badge).toContainText(/Enabled|已启用/, { timeout: 10_000 });
    else await expect(badge).toContainText(/Disabled|已禁用|已停用|未启用/, { timeout: 10_000 });
  }

  /** The set of AutomationLog ids currently recorded for an automation. */
  async function fetchLogIds(page: Page, pid: string): Promise<Set<number>> {
    const resp = await page.request.get(`/api/automations/${pid}/logs`, { params: { limit: 50 } });
    if (!resp.ok()) return new Set();
    const rows: any[] = (await resp.json())?.data ?? [];
    return new Set(rows.map((r) => Number(r.id)).filter((n) => Number.isFinite(n)));
  }

  async function expectNoNewLogIdsFor(
    page: Page,
    pid: string,
    before: Set<number>,
    observationMs: number,
    message: string,
  ): Promise<void> {
    const deadline = Date.now() + observationMs;
    while (Date.now() < deadline) {
      const current = await fetchLogIds(page, pid);
      const newIds = [...current].filter((id) => !before.has(id));
      expect(newIds, message).toHaveLength(0);
      await delay(1_000);
    }
    const current = await fetchLogIds(page, pid);
    const newIds = [...current].filter((id) => !before.has(id));
    expect(newIds, message).toHaveLength(0);
  }

  // ── golden CORNER path (real UI) — lifecycle: enable / disable / re-enable ──────
  test('N-CORNER-LIFECYCLE: enable→fire runs, disable→fire does NOT run, re-enable→fire runs (lifecycle via real UI toggle) @golden', async ({
    page,
  }) => {
    const itemName = `NCL-item-${uniqueId()}`;
    await openNewDesigner(page);
    await setAutomationName(page, `N-CORNER-LIFECYCLE ${uniqueId()}`);

    const trigger = await dragNodeToCanvas(page, 'trigger-record-create', { x: 150, y: 80 });
    const action = await dragNodeToCanvas(page, 'action-create-record', { x: 150, y: 240 });
    await page.locator('.react-flow__pane').click({ position: { x: 5, y: 5 } });
    await connectEdge(page, trigger, action);
    await fillNodeConfig(page, trigger, { modelCode: MODEL_LABEL });
    await fillNodeConfig(page, action, {
      modelCode: '订单明细',
      fields: '{"e2et_order_id":"${recordId}","e2et_item_name":"' + itemName + '","e2et_item_spec":"std","e2et_item_qty":1,"e2et_item_price":10}',
    });
    const { pid } = await saveAutomation(page);
    createdPids.push(pid);

    // ENABLED → fire → a run completes. Confirm enabled=true committed before firing.
    await setEnabledViaToggle(page, pid, 'on');
    await expect
      .poll(async () => (await (await page.request.get(`/api/automations/${pid}`)).json())?.data?.enabled, {
        timeout: 10_000,
      })
      .toBe(true);
    const firedAt1 = Date.now();
    await fireCreate(page, 100, `NCL-on ${uniqueId()}`);
    const run1 = await pollLogTerminal(page, pid, firedAt1);
    expect(String(run1!.status).toLowerCase(), `enabled fire should run: ${JSON.stringify(run1)}`).toBe('success');

    // DISABLED → fire → NO *new* run row appears. Confirm enabled=false committed (the badge
    // can flip a beat early), then compare the log-id SET before/after — a time-window check
    // would alias the just-prior enabled run (the slack), so we diff ids (cf. Layer B E6).
    await setEnabledViaToggle(page, pid, 'off');
    await expect
      .poll(async () => (await (await page.request.get(`/api/automations/${pid}`)).json())?.data?.enabled, {
        timeout: 10_000,
      })
      .toBe(false);
    const idsBefore = await fetchLogIds(page, pid);
    await fireCreate(page, 100, `NCL-off ${uniqueId()}`);
    await expectNoNewLogIdsFor(
      page,
      pid,
      idsBefore,
      6_000,
      'a disabled automation must NOT create a new run log during the observation window',
    );

    // RE-ENABLED → fire → runs again, a distinct newer log. Confirm enabled=true committed
    // (the deploy/re-enable can lag the badge) before firing, so the run is not missed.
    await setEnabledViaToggle(page, pid, 'on');
    await expect
      .poll(async () => (await (await page.request.get(`/api/automations/${pid}`)).json())?.data?.enabled, {
        timeout: 10_000,
      })
      .toBe(true);
    const firedAt3 = Date.now();
    await fireCreate(page, 100, `NCL-reon ${uniqueId()}`);
    const run3 = await pollLogTerminal(page, pid, firedAt3);
    expect(String(run3!.status).toLowerCase(), `re-enabled fire should run: ${JSON.stringify(run3)}`).toBe('success');
    expect(run3!.id, 're-enabled run is a distinct newer log row').not.toBe(run1!.id);
  });

  // ── golden EDGE path (real UI) — condition boundary value ───────────────────────
  test('N-CONDITION-EDGE: build trigger→condition(>1000)→true/false actions, fire at the EXACT boundary (amount=1000) → the FALSE branch runs, not TRUE (edge) @golden', async ({
    page,
  }) => {
    await openNewDesigner(page);
    await setAutomationName(page, `N-CONDITION-EDGE ${uniqueId()}`);

    const trigger = await dragNodeToCanvas(page, 'trigger-record-create', { x: 150, y: 70 });
    const condition = await dragNodeToCanvas(page, 'control-condition', { x: 150, y: 210 });
    const actHigh = await dragNodeToCanvas(page, 'action-update-record', { x: 30, y: 360 });
    const actLow = await dragNodeToCanvas(page, 'action-update-record', { x: 240, y: 360 });
    await page.locator('.react-flow__pane').click({ position: { x: 5, y: 5 } });
    await connectEdge(page, trigger, condition);
    const eTrue = await connectEdge(page, condition, actHigh, 'true');
    const eFalse = await connectEdge(page, condition, actLow, 'false');
    await fillNodeConfig(page, trigger, { modelCode: MODEL_LABEL });
    await fillNodeConfig(page, condition, { expression: "record['e2et_order_amount'] > 1000" });
    await fillNodeConfig(page, actHigh, { modelCode: MODEL_LABEL, recordPid: '${trigger.recordPid}', fields: '{"e2et_order_title":"EDGE_TRUE"}' });
    await fillNodeConfig(page, actLow, { modelCode: MODEL_LABEL, recordPid: '${trigger.recordPid}', fields: '{"e2et_order_title":"EDGE_FALSE"}' });
    await setEdgeCondition(page, eTrue, "record['e2et_order_amount'] > 1000");
    await setEdgeCondition(page, eFalse, "record['e2et_order_amount'] <= 1000");
    const { pid } = await saveAutomation(page);
    createdPids.push(pid);
    await enableViaListToggle(page, pid);

    // Fire EXACTLY at the boundary: amount=1000. 1000 > 1000 is FALSE → the FALSE branch runs.
    const firedAt = Date.now();
    const recordId = await fireCreate(page, 1000, `N-EDGE-order ${uniqueId()}`);
    const log = await pollLogTerminal(page, pid, firedAt);
    expect(String(log!.status).toLowerCase(), `N-CONDITION-EDGE run: ${JSON.stringify(log)}`).toBe('success');
    const statuses = await pollNodeStatuses(page, log!.id, 30_000);
    expect(statuses.find((s) => s.nodeId === actLow)?.status, 'FALSE branch runs at the boundary').toBe('completed');
    expect(statuses.find((s) => s.nodeId === actHigh), 'TRUE branch must NOT run at amount=1000 (not >1000)').toBeUndefined();
    const record = await pollRecordField(page, recordId, 'e2et_order_title', 'EDGE_FALSE');
    expect(record.e2et_order_title, 'boundary value took the FALSE branch').toBe('EDGE_FALSE');
  });

  test('N-SEND-NOTIFICATION: drag trigger-record-create→action-send-notification, configure title/content/recipients via panel, save, enable, fire → node completes (FINDING-8 recipients fix) @golden', async ({
    page,
  }) => {
    await openNewDesigner(page);
    await setAutomationName(page, `N-SEND-NOTIFICATION ${uniqueId()}`);

    const trigger = await dragNodeToCanvas(page, 'trigger-record-create', { x: 150, y: 80 });
    const action = await dragNodeToCanvas(page, 'action-send-notification', { x: 150, y: 240 });
    await page.locator('.react-flow__pane').click({ position: { x: 5, y: 5 } });
    await connectEdge(page, trigger, action);
    await fillNodeConfig(page, trigger, { modelCode: MODEL_LABEL });
    // notificationType is pre-set to in_app by the node defaultConfig. title/content/recipients
    // are expression fields; recipients='1' resolves to a single user id after the FINDING-8
    // tolerant-parse fix (configSchema types recipients as expression/string, executor reads a
    // list — previously a ClassCastException for any designer-built send-notification).
    await fillNodeConfig(page, action, {
      title: 'Order created',
      content: 'A new order was created by the automation',
      recipients: '1',
    });
    const { pid } = await saveAutomation(page);
    createdPids.push(pid);
    await enableViaListToggle(page, pid);

    const firedAt = Date.now();
    await fireCreate(page, 100, `N-NOTIFY-order ${uniqueId()}`);
    const log = await pollLogTerminal(page, pid, firedAt);
    expect(String(log!.status).toLowerCase(), `N-SEND-NOTIFICATION run: ${JSON.stringify(log)}`).toBe('success');
    expect(statusesNodeCompleted(await pollNodeStatuses(page, log!.id, 30_000), action), 'send-notification node completed').toBe(true);
  });

  test('N-SEND-SMS-PROVIDER-UNAVAILABLE: drag trigger-record-create→action-send-sms, configure phone+content, fire without a real provider → node fails with provider evidence (sad) @golden', async ({
    page,
  }) => {
    const marker = `SMS_${uniqueId()}`;
    await openNewDesigner(page);
    await setAutomationName(page, `N-SEND-SMS ${uniqueId()}`);

    await expect(page.getByTestId('palette-node-action-send-sms-status')).toContainText(
      /不可用|Unavailable/,
      { timeout: 15_000 },
    );
    await expect(page.getByTestId('palette-node-action-send-sms-status-text')).toContainText(
      '当前环境未配置真实短信 provider',
    );

    const trigger = await dragNodeToCanvas(page, 'trigger-record-create', { x: 150, y: 80 });
    const action = await dragNodeToCanvas(page, 'action-send-sms', { x: 150, y: 240 });
    await expect(page.getByTestId(`flow-node-${action}-availability-badge`)).toContainText(
      /不可用|Unavailable/,
    );
    await page.getByTestId(`flow-node-${action}`).click();
    await expect(page.getByTestId(`flow-node-availability-badge-${action}`)).toContainText(
      /不可用|Unavailable/,
    );
    await expect(page.getByTestId(`flow-node-availability-${action}`)).toContainText(
      '当前环境未配置真实短信 provider',
    );
    await page.screenshot({
      path: 'test-results/artifacts/automation-designer-N-send-sms-provider-availability-config.png',
      fullPage: false,
    });
    const closeInspector = page.getByTestId('flow-close-inspector');
    if (await closeInspector.isVisible().catch(() => false)) {
      await closeInspector.click();
    }

    await page.locator('.react-flow__pane').click({ position: { x: 5, y: 5 } });
    await connectEdge(page, trigger, action);
    await fillNodeConfig(page, trigger, { modelCode: MODEL_LABEL });
    await fillNodeConfig(page, action, {
      target: 'PHONE:+8613800138000',
      template: 'automation_timeout',
      content: `Automation SMS ${marker} for \${recordId}`,
    });

    const { pid } = await saveAutomation(page);
    createdPids.push(pid);
    const saved = await readFlowConfig(page, pid);
    const savedAction = saved.flowConfig.nodes.find((n: any) => n.id === action);
    expect(savedAction.data.config.actionType, 'send-sms action type persisted').toBe('send_sms');
    expect(savedAction.data.config.target, 'sms target persisted').toBe('PHONE:+8613800138000');
    expect(savedAction.data.config.content, 'sms content template persisted').toBe(
      `Automation SMS ${marker} for \${recordId}`,
    );

    await enableViaListToggle(page, pid);

    const firedAt = Date.now();
    await fireCreate(page, 100, `N-SMS-order ${uniqueId()}`);
    const log = await pollLogTerminal(page, pid, firedAt);
    expect(['failed', 'partial_success'], `N-SEND-SMS run should fail without provider: ${JSON.stringify(log)}`).toContain(
      String(log!.status).toLowerCase(),
    );
    const actionResult = (log!.actionResults ?? []).find((item: any) => item.actionType === 'send_sms');
    expect(actionResult?.status, `send_sms actionResult returned: ${JSON.stringify(log!.actionResults)}`).toBe('failed');
    expect(String(actionResult?.errorMessage ?? log!.errorMessage ?? ''), 'failure explains provider availability').toMatch(
      /No real SMS sender available|send_sms failed via/i,
    );

    const node = (await pollNodeStatuses(page, log!.id, 30_000)).find((status) => status.nodeId === action);
    expect(node?.status, 'send-sms node should be failed without a real provider').toBe('failed');
    expect(node?.errorMessage ?? '', 'node error explains SMS provider failure').toMatch(
      /No real SMS sender available|send_sms failed via/i,
    );

    await page.goto(`/automation/${pid}?logId=${log!.id}`);
    await expect(page.locator(`[data-testid="flow-node-${action}"]`)).toHaveAttribute(
      'data-runtime-status',
      'failed',
      { timeout: 15_000 },
    );
    const smsRuntimeCard = page.getByTestId('automation-action-result-1');
    await expect(smsRuntimeCard).toBeVisible({ timeout: 15_000 });
    await expect(smsRuntimeCard).toContainText(/发送短信|Send Sms|Send SMS/i);
    await expect(smsRuntimeCard).toContainText(/失败|Failed/i);
    await expect(smsRuntimeCard).toContainText(/通道|Channel/i);
    await expect(smsRuntimeCard).toContainText(/短信|SMS/i);
    await expect(smsRuntimeCard).toContainText(/短信模板|SMS Template/i);
    await expect(smsRuntimeCard).toContainText('automation_timeout');
    await expect(smsRuntimeCard).toContainText(/发送数|Sent/i);
    await expect(smsRuntimeCard).toContainText('0');
    await expect(smsRuntimeCard).toContainText(/手机号|Phone Targets/i);
    await expect(smsRuntimeCard).toContainText('+8613800138000');
    await expect(smsRuntimeCard).toContainText(/失败原因|Failure Reason/i);
    await expect(smsRuntimeCard).toContainText(/短信发送失败|SMS delivery failed/i);
    await expect(smsRuntimeCard).toContainText(/错误信息|Error Message/i);
    await expect(smsRuntimeCard).toContainText(/No real SMS sender available|send_sms failed via/i);
    const smsRuntimeEvidence = smsRuntimeCard.getByTestId('automation-action-evidence');
    await expect(smsRuntimeEvidence).not.toContainText('failureReason');
    await expect(smsRuntimeEvidence).not.toContainText('sms_delivery_failed');
    await expect(smsRuntimeEvidence).not.toContainText('targetPhones');
    await expect(smsRuntimeEvidence).not.toContainText('errorMessage');
    await smsRuntimeCard.screenshot({
      path: 'test-results/artifacts/automation-designer-N-send-sms-provider-unavailable-runtime-action-evidence.png',
    });
    await page.screenshot({
      path: 'test-results/artifacts/automation-designer-N-send-sms-provider-unavailable-status-overlay.png',
      fullPage: false,
    });
  });

  test('N-SEND-IM: drag trigger-record-create→action-send-im, configure current user target, fire → actionResults returns message ids and IM API exposes the bot message (happy) @golden', async ({
    page,
  }) => {
    const currentUser = await readApi<CurrentUserInfo>(await page.request.get('/api/auth/me'));
    const targetUserId = currentUser.user?.id;
    if (!targetUserId) {
      throw new Error(`Current user id is required for send_im target: ${JSON.stringify(currentUser)}`);
    }
    expect(targetUserId).toMatch(/^\d+$/);

    const marker = `IM_${uniqueId()}`;
    await openNewDesigner(page);
    await setAutomationName(page, `N-SEND-IM ${uniqueId()}`);

    const trigger = await dragNodeToCanvas(page, 'trigger-record-create', { x: 150, y: 80 });
    const action = await dragNodeToCanvas(page, 'action-send-im', { x: 150, y: 240 });
    await page.locator('.react-flow__pane').click({ position: { x: 5, y: 5 } });
    await connectEdge(page, trigger, action);
    await fillNodeConfig(page, trigger, { modelCode: MODEL_LABEL });
    await fillNodeConfig(page, action, {
      target: `USER:${targetUserId}`,
      channel: 'im',
      content: `Automation IM ${marker} for \${recordId}`,
    });

    const { pid } = await saveAutomation(page);
    createdPids.push(pid);
    const saved = await readFlowConfig(page, pid);
    const savedAction = saved.flowConfig.nodes.find((n: any) => n.id === action);
    expect(savedAction.data.config.actionType, 'send-im action type persisted').toBe('send_im');
    expect(savedAction.data.config.target, 'im target persisted').toBe(`USER:${targetUserId}`);
    expect(savedAction.data.config.content, 'im content template persisted').toBe(
      `Automation IM ${marker} for \${recordId}`,
    );

    await enableViaListToggle(page, pid);

    const firedAt = Date.now();
    await fireCreate(page, 100, `N-IM-order ${uniqueId()}`);
    const log = await pollLogTerminal(page, pid, firedAt);
    expect(String(log!.status).toLowerCase(), `N-SEND-IM run: ${JSON.stringify(log)}`).toBe('success');
    expect(statusesNodeCompleted(await pollNodeStatuses(page, log!.id, 30_000), action), 'send-im node completed').toBe(true);
    const recordId = log!.triggerRecordPid;
    expect(recordId, `send_im log should carry triggerRecordPid: ${JSON.stringify(log)}`).toEqual(expect.any(String));

    const actionResult = (log!.actionResults ?? []).find((item: any) => item.actionType === 'send_im');
    expect(actionResult?.status, `send_im actionResult returned: ${JSON.stringify(log!.actionResults)}`).toBe('success');
    const resultPayload = actionResult?.result as Record<string, unknown> | undefined;
    expect(resultPayload).toMatchObject({
      success: true,
      channel: 'im',
      sentCount: 1,
      modelCode: MODEL_CODE,
      recordPid: recordId,
    });
    expect(resultPayload?.targetUserIds, 'send_im target users').toEqual([Number(targetUserId)]);
    const conversationIds = resultPayload?.conversationIds as unknown[] | undefined;
    const messageIds = resultPayload?.messageIds as unknown[] | undefined;
    expect(conversationIds, `send_im result exposes conversationIds: ${JSON.stringify(resultPayload)}`).toEqual([
      expect.any(Number),
    ]);
    expect(messageIds, `send_im result exposes messageIds: ${JSON.stringify(resultPayload)}`).toEqual([
      expect.any(Number),
    ]);

    const messages = await readApi<ImMessageRecord[]>(
      await page.request.get(`/api/im/conversations/${conversationIds![0]}/messages`, {
        params: { limit: '10' },
      }),
    );
    const imMessage = messages.find((message) => message.id === messageIds![0]);
    expect(imMessage, `send_im bot message must be queryable via IM API: ${JSON.stringify(messages)}`).toBeTruthy();
    expect(imMessage).toMatchObject({
      id: messageIds![0],
      conversationId: conversationIds![0],
      senderType: 'system',
      content: `Automation IM ${marker} for ${recordId}`,
    });
    expect(JSON.stringify(imMessage?.cardPayload)).toContain('send_im');
    expect(JSON.stringify(imMessage?.cardPayload)).toContain(pid);
    expect(JSON.stringify(imMessage?.cardPayload)).toContain(recordId);

    await page.goto(`/automation/${pid}?logId=${log!.id}`);
    await expect(page.locator(`[data-testid="flow-node-${action}"]`)).toHaveAttribute(
      'data-runtime-status',
      'completed',
      { timeout: 15_000 },
    );
    const actionCard = page.getByTestId('automation-action-result-1');
    await expect(actionCard).toContainText(/发送 IM|Send IM/);
    await expect(actionCard).toContainText(/通道|Channel/);
    await expect(actionCard).toContainText(/IM 消息|IM message/);
    await expect(actionCard).toContainText(/发送数|Sent/);
    await expect(actionCard).toContainText(/接收用户|Target Users/);
    await expect(actionCard).toContainText(/会话 ID|Conversation IDs/);
    await expect(actionCard).toContainText(String(conversationIds![0]));
    await expect(actionCard).toContainText(/消息 ID|Message IDs/);
    await expect(actionCard).toContainText(String(messageIds![0]));
    const actionEvidence = actionCard.getByTestId('automation-action-evidence');
    await expect(actionEvidence).not.toContainText('targetUserIds');
    await expect(actionEvidence).not.toContainText('conversationIds');
    await expect(actionEvidence).not.toContainText('messageIds');
    await expect(actionEvidence).not.toContainText('channel');
    await page.screenshot({
      path: 'test-results/artifacts/automation-designer-N-send-im-runtime-action-evidence.png',
      fullPage: false,
    });
  });

  test('N-WRITE-AUDIT: drag trigger-record-create→action-write-audit, configure message+payload, fire → actionResults returns auditPid and DB audit row persists (happy) @golden', async ({
    page,
  }) => {
    const marker = `AUD_${uniqueId()}`;
    await openNewDesigner(page);
    await setAutomationName(page, `N-WRITE-AUDIT ${uniqueId()}`);

    const trigger = await dragNodeToCanvas(page, 'trigger-record-create', { x: 150, y: 80 });
    const action = await dragNodeToCanvas(page, 'action-write-audit', { x: 150, y: 240 });
    await page.locator('.react-flow__pane').click({ position: { x: 5, y: 5 } });
    await connectEdge(page, trigger, action);
    await fillNodeConfig(page, trigger, { modelCode: MODEL_LABEL });
    await fillNodeConfig(page, action, {
      message: `Audit ${marker} for \${recordId}`,
      payload: `{"marker":"${marker}","recordPid":"\${recordId}","source":"automation-designer-golden"}`,
    });

    const { pid } = await saveAutomation(page);
    createdPids.push(pid);
    const saved = await readFlowConfig(page, pid);
    const savedAction = saved.flowConfig.nodes.find((n: any) => n.id === action);
    expect(savedAction.data.config.actionType, 'write-audit action type persisted').toBe('write_audit');
    expect(savedAction.data.config.payload, 'write-audit JSON payload persisted as structured config').toMatchObject({
      marker,
      source: 'automation-designer-golden',
    });

    await enableViaListToggle(page, pid);

    const firedAt = Date.now();
    await fireCreate(page, 100, `N-AUDIT-order ${uniqueId()}`);
    const log = await pollLogTerminal(page, pid, firedAt);
    expect(String(log!.status).toLowerCase(), `N-WRITE-AUDIT run: ${JSON.stringify(log)}`).toBe('success');
    expect(statusesNodeCompleted(await pollNodeStatuses(page, log!.id, 30_000), action), 'write-audit node completed').toBe(true);
    const recordId = log!.triggerRecordPid;
    expect(recordId, `write_audit log should carry triggerRecordPid: ${JSON.stringify(log)}`).toEqual(expect.any(String));

    const actionResult = (log!.actionResults ?? []).find((item: any) => item.actionType === 'write_audit');
    expect(actionResult?.status, `write_audit actionResult returned: ${JSON.stringify(log!.actionResults)}`).toBe('success');
    const resultPayload = actionResult?.result as Record<string, unknown> | undefined;
    expect(resultPayload?.auditPid, 'write_audit actionResult returns persisted audit pid').toEqual(expect.any(String));
    expect(resultPayload?.ruleCode, 'write_audit actionResult is tied to the automation pid').toBe(pid);
    expect(resultPayload?.message, 'write_audit actionResult resolves the recordId template').toBe(
      `Audit ${marker} for ${recordId}`,
    );

    const auditRow = await waitForActionAuditRow(String(resultPayload!.auditPid));
    expect(auditRow, 'ab_drt_action_audit row must exist for the returned auditPid').toBeTruthy();
    expect(auditRow!.rule_code, 'audit row references the automation pid').toBe(pid);
    expect(auditRow!.action_type, 'audit row action type').toBe('write_audit');
    expect(auditRow!.message, 'audit row message resolves runtime variables').toBe(`Audit ${marker} for ${recordId}`);
    expect(auditRow!.payload_json, 'audit row payload resolves JSON field variables').toMatchObject({
      marker,
      recordPid: recordId,
      automationPid: pid,
      source: 'automation-designer-golden',
    });

    await page.goto(`/automation/${pid}?logId=${log!.id}`);
    await expect(page.locator(`[data-testid="flow-node-${action}"]`)).toHaveAttribute(
      'data-runtime-status',
      'completed',
      { timeout: 15_000 },
    );
    const actionCard = page.getByTestId('automation-action-result-1');
    await expect(actionCard).toContainText(/写入审计|Write Audit/);
    await expect(actionCard).toContainText(/审计记录|Audit Entry/);
    await expect(actionCard).toContainText(String(resultPayload!.auditPid));
    await expect(actionCard).toContainText(/规则 \/ 自动化|Rule \/ Automation/);
    await expect(actionCard).toContainText(pid);
    await expect(actionCard).toContainText(/消息|Message/);
    await expect(actionCard).toContainText(`Audit ${marker} for ${recordId}`);
    const actionEvidence = actionCard.getByTestId('automation-action-evidence');
    await expect(actionEvidence).not.toContainText('auditPid');
    await expect(actionEvidence).not.toContainText('ruleCode');
    await page.screenshot({
      path: 'test-results/artifacts/automation-designer-N-write-audit-runtime-action-evidence.png',
      fullPage: false,
    });
  });

  test('N-ADD-COMMENT: drag trigger-record-create→action-add-comment, configure content+mentions, fire → actionResults returns commentPid and DB comment row persists (happy) @golden', async ({
    page,
  }) => {
    const marker = `CMT_${uniqueId()}`;
    await openNewDesigner(page);
    await setAutomationName(page, `N-ADD-COMMENT ${uniqueId()}`);

    const trigger = await dragNodeToCanvas(page, 'trigger-record-create', { x: 150, y: 80 });
    const action = await dragNodeToCanvas(page, 'action-add-comment', { x: 150, y: 240 });
    await page.locator('.react-flow__pane').click({ position: { x: 5, y: 5 } });
    await connectEdge(page, trigger, action);
    await fillNodeConfig(page, trigger, { modelCode: MODEL_LABEL });
    await fillNodeConfig(page, action, {
      content: `Comment ${marker} for \${recordId}`,
      mentions: 'ROLE:wd_manager',
    });

    const { pid } = await saveAutomation(page);
    createdPids.push(pid);
    const saved = await readFlowConfig(page, pid);
    const savedAction = saved.flowConfig.nodes.find((n: any) => n.id === action);
    expect(savedAction.data.config.actionType, 'add-comment action type persisted').toBe('add_comment');
    expect(savedAction.data.config.content, 'comment content template persisted').toBe(
      `Comment ${marker} for \${recordId}`,
    );
    expect(savedAction.data.config.mentions, 'comment mentions persisted').toBe('ROLE:wd_manager');

    await enableViaListToggle(page, pid);

    const firedAt = Date.now();
    await fireCreate(page, 100, `N-COMMENT-order ${uniqueId()}`);
    const log = await pollLogTerminal(page, pid, firedAt);
    expect(String(log!.status).toLowerCase(), `N-ADD-COMMENT run: ${JSON.stringify(log)}`).toBe('success');
    expect(statusesNodeCompleted(await pollNodeStatuses(page, log!.id, 30_000), action), 'add-comment node completed').toBe(true);
    const recordId = log!.triggerRecordPid;
    expect(recordId, `add_comment log should carry triggerRecordPid: ${JSON.stringify(log)}`).toEqual(expect.any(String));

    const actionResult = (log!.actionResults ?? []).find((item: any) => item.actionType === 'add_comment');
    expect(actionResult?.status, `add_comment actionResult returned: ${JSON.stringify(log!.actionResults)}`).toBe('success');
    const resultPayload = actionResult?.result as Record<string, unknown> | undefined;
    expect(resultPayload?.success, 'add_comment actionResult reports success').toBe(true);
    expect(resultPayload?.modelCode, 'add_comment actionResult keeps trigger model').toBe(MODEL_CODE);
    expect(resultPayload?.recordPid, 'add_comment actionResult keeps trigger record pid').toBe(recordId);
    expect(resultPayload?.commentPid, 'add_comment actionResult returns persisted comment pid').toEqual(expect.any(String));

    const commentRow = await waitForRecordCommentRow(String(resultPayload!.commentPid));
    expect(commentRow, 'ab_record_comment row must exist for the returned commentPid').toBeTruthy();
    expect(commentRow!.model_code, 'comment row model').toBe(MODEL_CODE);
    expect(commentRow!.record_pid, 'comment row record pid').toBe(recordId);
    expect(commentRow!.content, 'comment row content resolves runtime variables').toBe(
      `Comment ${marker} for ${recordId}`,
    );
    expect(commentRow!.mentions, 'comment row mentions').toBe('ROLE:wd_manager');

    await page.goto(`/automation/${pid}?logId=${log!.id}`);
    await expect(page.locator(`[data-testid="flow-node-${action}"]`)).toHaveAttribute(
      'data-runtime-status',
      'completed',
      { timeout: 15_000 },
    );
    const actionCard = page.getByTestId('automation-action-result-1');
    await expect(actionCard).toContainText(/添加评论|Add Comment/);
    await expect(actionCard).toContainText(/评论|Comment/);
    await expect(actionCard).toContainText(String(resultPayload!.commentPid));
    await expect(actionCard).toContainText(/内容|Content/);
    await expect(actionCard).toContainText(`Comment ${marker} for ${recordId}`);
    await expect(actionCard).toContainText(/业务记录|Record/);
    await expect(actionCard).toContainText(String(recordId));
    await expect(actionCard).toContainText(/提及对象|Mentions/);
    await expect(actionCard).toContainText('ROLE:wd_manager');
    const actionEvidence = actionCard.getByTestId('automation-action-evidence');
    await expect(actionEvidence).not.toContainText('commentPid');
    await expect(actionEvidence).not.toContainText('recordPid');
    await expect(actionEvidence).not.toContainText('mentions');
    await page.screenshot({
      path: 'test-results/artifacts/automation-designer-N-add-comment-runtime-action-evidence.png',
      fullPage: false,
    });
  });

  test('N-CREATE-TASK: drag trigger-record-create→action-create-task, configure assignee+title, fire → actionResults returns inbox item id and DB inbox task persists (happy) @golden', async ({
    page,
  }) => {
    const marker = `TASK_${uniqueId()}`;
    await openNewDesigner(page);
    await setAutomationName(page, `N-CREATE-TASK ${uniqueId()}`);

    const trigger = await dragNodeToCanvas(page, 'trigger-record-create', { x: 150, y: 80 });
    const action = await dragNodeToCanvas(page, 'action-create-task', { x: 150, y: 240 });
    await page.locator('.react-flow__pane').click({ position: { x: 5, y: 5 } });
    await connectEdge(page, trigger, action);
    await fillNodeConfig(page, trigger, { modelCode: MODEL_LABEL });
    await fillNodeConfig(page, action, {
      assignee: 'USER:1',
      title: `Task ${marker} for \${recordId}`,
      dueDate: '2026-07-15T00:00:00Z',
    });

    const { pid } = await saveAutomation(page);
    createdPids.push(pid);
    const saved = await readFlowConfig(page, pid);
    const savedAction = saved.flowConfig.nodes.find((n: any) => n.id === action);
    expect(savedAction.data.config.actionType, 'create-task action type persisted').toBe('create_task');
    expect(savedAction.data.config.assignee, 'task assignee persisted').toBe('USER:1');
    expect(savedAction.data.config.title, 'task title template persisted').toBe(
      `Task ${marker} for \${recordId}`,
    );

    await enableViaListToggle(page, pid);

    const firedAt = Date.now();
    await fireCreate(page, 100, `N-TASK-order ${uniqueId()}`);
    const log = await pollLogTerminal(page, pid, firedAt);
    expect(String(log!.status).toLowerCase(), `N-CREATE-TASK run: ${JSON.stringify(log)}`).toBe('success');
    expect(statusesNodeCompleted(await pollNodeStatuses(page, log!.id, 30_000), action), 'create-task node completed').toBe(true);
    const recordId = log!.triggerRecordPid;
    expect(recordId, `create_task log should carry triggerRecordPid: ${JSON.stringify(log)}`).toEqual(expect.any(String));

    const actionResult = (log!.actionResults ?? []).find((item: any) => item.actionType === 'create_task');
    expect(actionResult?.status, `create_task actionResult returned: ${JSON.stringify(log!.actionResults)}`).toBe('success');
    const resultPayload = actionResult?.result as Record<string, unknown> | undefined;
    expect(resultPayload?.success, 'create_task actionResult reports success').toBe(true);
    expect(resultPayload?.delivery, 'create_task uses inbox delivery').toBe('inbox');
    expect(resultPayload?.itemType, 'create_task actionResult item type').toBe('task');
    expect(resultPayload?.createdCount, 'create_task creates one inbox item for USER:1').toBe(1);
    expect(resultPayload?.modelCode, 'create_task actionResult keeps trigger model').toBe(MODEL_CODE);
    expect(resultPayload?.recordPid, 'create_task actionResult keeps trigger record pid').toBe(recordId);
    const inboxIds = resultPayload?.inboxItemIds as unknown[] | undefined;
    expect(inboxIds, 'create_task actionResult returns inbox item ids').toEqual([expect.any(Number)]);

    const inboxRow = await waitForInboxItemRow(String(inboxIds![0]));
    expect(inboxRow, 'ab_inbox_item row must exist for the returned inbox item id').toBeTruthy();
    expect(inboxRow!.user_id, 'inbox item user').toBe('1');
    expect(inboxRow!.item_type, 'inbox item type').toBe('task');
    expect(inboxRow!.title, 'inbox title resolves runtime variables').toBe(`Task ${marker} for ${recordId}`);
    expect(inboxRow!.priority, 'default task priority').toBe('normal');
    expect(inboxRow!.status, 'new task status').toBe('pending');
    expect(inboxRow!.source_type, 'task source type').toBe('automation');
    expect(inboxRow!.source_id, 'task source id references automation').toBe(pid);
    expect(inboxRow!.model_code, 'task model').toBe(MODEL_CODE);
    expect(inboxRow!.record_pid, 'task record pid').toBe(recordId);
    expect(inboxRow!.deep_link, 'task deep link points to the trigger record').toBe(`/p/${MODEL_CODE}/view/${recordId}`);
    expect(inboxRow!.card_payload, 'task card payload keeps runtime context').toMatchObject({
      actionType: 'create_task',
      automationPid: pid,
      title: `Task ${marker} for ${recordId}`,
      modelCode: MODEL_CODE,
      recordPid: recordId,
      dueDate: '2026-07-15T00:00:00Z',
    });

    await page.goto(`/automation/${pid}?logId=${log!.id}`);
    await expect(page.locator(`[data-testid="flow-node-${action}"]`)).toHaveAttribute(
      'data-runtime-status',
      'completed',
      { timeout: 15_000 },
    );
    const actionCard = page.getByTestId('automation-action-result-1');
    await expect(actionCard).toContainText(/创建任务|Create Task/);
    await expect(actionCard).toContainText(/投递方式|Delivery/);
    await expect(actionCard).toContainText(/待办中心|Inbox/);
    await expect(actionCard).toContainText(/待办类型|Inbox Type/);
    await expect(actionCard).toContainText(/待办任务|Task/);
    await expect(actionCard).toContainText(/创建数量|Created/);
    await expect(actionCard).toContainText(/负责人|Assignees/);
    await expect(actionCard).toContainText(/待办项 ID|Inbox Item IDs/);
    await expect(actionCard).toContainText(String(inboxIds![0]));
    const actionEvidence = actionCard.getByTestId('automation-action-evidence');
    await expect(actionEvidence).not.toContainText('inboxItemIds');
    await expect(actionEvidence).not.toContainText('assigneeUserIds');
    await expect(actionEvidence).not.toContainText('createdCount');
    await expect(actionEvidence).not.toContainText('itemType');
    await page.screenshot({
      path: 'test-results/artifacts/automation-designer-N-create-task-runtime-action-evidence.png',
      fullPage: false,
    });
  });

  test('N-CC-TASK: drag trigger-record-create→action-cc-task, configure target+message, fire → actionResults returns inbox mention id and DB mention persists (happy) @golden', async ({
    page,
  }) => {
    const marker = `CC_${uniqueId()}`;
    await openNewDesigner(page);
    await setAutomationName(page, `N-CC-TASK ${uniqueId()}`);

    const trigger = await dragNodeToCanvas(page, 'trigger-record-create', { x: 150, y: 80 });
    const action = await dragNodeToCanvas(page, 'action-cc-task', { x: 150, y: 240 });
    await page.locator('.react-flow__pane').click({ position: { x: 5, y: 5 } });
    await connectEdge(page, trigger, action);
    await fillNodeConfig(page, trigger, { modelCode: MODEL_LABEL });
    await fillNodeConfig(page, action, {
      target: 'USER:1',
      message: `CC ${marker} for \${recordId}`,
    });

    const { pid } = await saveAutomation(page);
    createdPids.push(pid);
    const saved = await readFlowConfig(page, pid);
    const savedAction = saved.flowConfig.nodes.find((n: any) => n.id === action);
    expect(savedAction.data.config.actionType, 'cc-task action type persisted').toBe('cc_task');
    expect(savedAction.data.config.target, 'cc target persisted').toBe('USER:1');
    expect(savedAction.data.config.message, 'cc message template persisted').toBe(
      `CC ${marker} for \${recordId}`,
    );

    await enableViaListToggle(page, pid);

    const firedAt = Date.now();
    await fireCreate(page, 100, `N-CC-order ${uniqueId()}`);
    const log = await pollLogTerminal(page, pid, firedAt);
    expect(String(log!.status).toLowerCase(), `N-CC-TASK run: ${JSON.stringify(log)}`).toBe('success');
    expect(statusesNodeCompleted(await pollNodeStatuses(page, log!.id, 30_000), action), 'cc-task node completed').toBe(true);
    const recordId = log!.triggerRecordPid;
    expect(recordId, `cc_task log should carry triggerRecordPid: ${JSON.stringify(log)}`).toEqual(expect.any(String));

    const actionResult = (log!.actionResults ?? []).find((item: any) => item.actionType === 'cc_task');
    expect(actionResult?.status, `cc_task actionResult returned: ${JSON.stringify(log!.actionResults)}`).toBe('success');
    const resultPayload = actionResult?.result as Record<string, unknown> | undefined;
    expect(resultPayload?.success, 'cc_task actionResult reports success').toBe(true);
    expect(resultPayload?.delivery, 'cc_task uses inbox delivery without taskId').toBe('inbox');
    expect(resultPayload?.itemType, 'cc_task actionResult item type').toBe('mention');
    expect(resultPayload?.ccCount, 'cc_task creates one mention for USER:1').toBe(1);
    expect(resultPayload?.targetUserIds, 'cc_task target users').toEqual([1]);
    expect(resultPayload?.modelCode, 'cc_task actionResult keeps trigger model').toBe(MODEL_CODE);
    expect(resultPayload?.recordPid, 'cc_task actionResult keeps trigger record pid').toBe(recordId);
    const inboxIds = resultPayload?.inboxItemIds as unknown[] | undefined;
    expect(inboxIds, 'cc_task actionResult returns inbox item ids').toEqual([expect.any(Number)]);

    const inboxRow = await waitForInboxItemRow(String(inboxIds![0]));
    expect(inboxRow, 'ab_inbox_item row must exist for the returned mention id').toBeTruthy();
    expect(inboxRow!.user_id, 'mention user').toBe('1');
    expect(inboxRow!.item_type, 'mention item type').toBe('mention');
    expect(inboxRow!.title, 'mention title').toBe('任务抄送');
    expect(inboxRow!.subtitle, 'mention subtitle resolves runtime variables').toBe(
      `CC ${marker} for ${recordId}`,
    );
    expect(inboxRow!.priority, 'mention priority').toBe('normal');
    expect(inboxRow!.status, 'new mention status').toBe('pending');
    expect(inboxRow!.source_type, 'mention source type').toBe('automation');
    expect(inboxRow!.source_id, 'mention source id references automation').toBe(pid);
    expect(inboxRow!.model_code, 'mention model').toBe(MODEL_CODE);
    expect(inboxRow!.record_pid, 'mention record pid').toBe(recordId);
    expect(inboxRow!.deep_link, 'mention deep link points to the trigger record').toBe(`/p/${MODEL_CODE}/view/${recordId}`);
    expect(inboxRow!.card_payload, 'mention card payload keeps runtime context').toMatchObject({
      actionType: 'cc_task',
      automationPid: pid,
      title: '任务抄送',
      message: `CC ${marker} for ${recordId}`,
      modelCode: MODEL_CODE,
      recordPid: recordId,
    });

    await page.goto(`/automation/${pid}?logId=${log!.id}`);
    await expect(page.locator(`[data-testid="flow-node-${action}"]`)).toHaveAttribute(
      'data-runtime-status',
      'completed',
      { timeout: 15_000 },
    );
    const actionCard = page.getByTestId('automation-action-result-1');
    await expect(actionCard).toContainText(/抄送任务|CC Task/);
    await expect(actionCard).toContainText(/投递方式|Delivery/);
    await expect(actionCard).toContainText(/待办中心|Inbox/);
    await expect(actionCard).toContainText(/待办类型|Inbox Type/);
    await expect(actionCard).toContainText(/抄送提醒|Mention/);
    await expect(actionCard).toContainText(/抄送数量|CC Count/);
    await expect(actionCard).toContainText(/接收用户|Target Users/);
    await expect(actionCard).toContainText(/待办项 ID|Inbox Item IDs/);
    await expect(actionCard).toContainText(String(inboxIds![0]));
    const actionEvidence = actionCard.getByTestId('automation-action-evidence');
    await expect(actionEvidence).not.toContainText('inboxItemIds');
    await expect(actionEvidence).not.toContainText('targetUserIds');
    await expect(actionEvidence).not.toContainText('ccCount');
    await page.screenshot({
      path: 'test-results/artifacts/automation-designer-N-cc-task-runtime-action-evidence.png',
      fullPage: false,
    });
  });

  test('N-UPDATE-RECORD-HAPPY: drag trigger-record-create→action-update-record, configure fields, fire → actionResults exposes updated fields and trigger record mutates (happy) @golden', async ({
    page,
  }) => {
    const marker = `UPD_${uniqueId()}`;
    const updatedTitle = `Updated ${marker}`;
    const updatedRemark = `Remark ${marker}`;
    await openNewDesigner(page);
    await setAutomationName(page, `N-UPDATE-RECORD-HAPPY ${uniqueId()}`);

    const trigger = await dragNodeToCanvas(page, 'trigger-record-create', { x: 150, y: 80 });
    const action = await dragNodeToCanvas(page, 'action-update-record', { x: 150, y: 240 });
    await page.locator('.react-flow__pane').click({ position: { x: 5, y: 5 } });
    await connectEdge(page, trigger, action);
    await fillNodeConfig(page, trigger, { modelCode: MODEL_LABEL });
    await fillNodeConfig(page, action, {
      modelCode: MODEL_LABEL,
      recordPid: '${trigger.recordPid}',
      fields: JSON.stringify({
        e2et_order_title: updatedTitle,
        e2et_order_remark: updatedRemark,
      }),
    });

    const { pid } = await saveAutomation(page);
    createdPids.push(pid);
    const saved = await readFlowConfig(page, pid);
    const savedAction = saved.flowConfig.nodes.find((n: any) => n.id === action);
    expect(savedAction.data.config.actionType, 'update-record action type persisted').toBe('update_record');
    expect(savedAction.data.config.modelCode, 'update-record model persisted').toBe(MODEL_CODE);
    expect(savedAction.data.config.recordPid, 'update-record target pid template persisted').toBe('${trigger.recordPid}');
    expect(savedAction.data.config.fields, 'update-record fields persisted').toMatchObject({
      e2et_order_title: updatedTitle,
      e2et_order_remark: updatedRemark,
    });

    await enableViaListToggle(page, pid);

    const firedAt = Date.now();
    const recordId = await fireCreate(page, 100, `N-UPD-order ${uniqueId()}`);
    const log = await pollLogTerminal(page, pid, firedAt);
    expect(String(log!.status).toLowerCase(), `N-UPDATE-RECORD-HAPPY run: ${JSON.stringify(log)}`).toBe('success');
    expect(statusesNodeCompleted(await pollNodeStatuses(page, log!.id, 30_000), action), 'update-record node completed').toBe(true);
    expect(log!.triggerRecordPid, 'update_record log keeps trigger record pid').toBe(recordId);

    const actionResult = (log!.actionResults ?? []).find((item: any) => item.actionType === 'update_record');
    expect(actionResult?.status, `update_record actionResult returned: ${JSON.stringify(log!.actionResults)}`).toBe('success');
    const resultPayload = actionResult?.result as Record<string, unknown> | undefined;
    expect(resultPayload?.success, 'update_record actionResult reports success').toBe(true);
    expect(resultPayload?.actionType, 'update_record result action type').toBe('update_record');
    expect(resultPayload?.recordPid, 'update_record result record pid').toBe(recordId);
    expect(resultPayload?.updatedFields, 'update_record result lists changed fields').toEqual(
      expect.arrayContaining(['e2et_order_title', 'e2et_order_remark']),
    );

    const record = await pollRecordField(page, recordId, 'e2et_order_title', updatedTitle);
    expect(record.e2et_order_title, 'trigger record title mutates via DynamicDataService').toBe(updatedTitle);
    expect(record.e2et_order_remark, 'trigger record remark mutates via DynamicDataService').toBe(updatedRemark);

    await page.goto(`/automation/${pid}?logId=${log!.id}`);
    await expect(page.locator(`[data-testid="flow-node-${action}"]`)).toHaveAttribute(
      'data-runtime-status',
      'completed',
      { timeout: 15_000 },
    );
    await page.screenshot({
      path: 'test-results/artifacts/automation-designer-N-update-record-happy-status-overlay.png',
      fullPage: false,
    });
  });

  // ── golden SAD path (real UI) — update-record targeting a nonexistent field fails ──
  test('N-UPDATE-RECORD-SAD: drag trigger-record-create→action-update-record with a nonexistent field, save, enable, fire → the node FAILS at runtime with an error (sad) @golden', async ({
    page,
  }) => {
    await openNewDesigner(page);
    await setAutomationName(page, `N-UPDATE-RECORD-SAD ${uniqueId()}`);

    const trigger = await dragNodeToCanvas(page, 'trigger-record-create', { x: 150, y: 80 });
    const action = await dragNodeToCanvas(page, 'action-update-record', { x: 150, y: 240 });
    await page.locator('.react-flow__pane').click({ position: { x: 5, y: 5 } });
    await connectEdge(page, trigger, action);
    await fillNodeConfig(page, trigger, { modelCode: MODEL_LABEL });
    await fillNodeConfig(page, action, {
      modelCode: MODEL_LABEL,
      recordPid: '${trigger.recordPid}',
      fields: '{"e2et_order_nonexistent_zzz":"boom"}',
    });
    const { pid } = await saveAutomation(page);
    createdPids.push(pid);
    await enableViaListToggle(page, pid);

    const firedAt = Date.now();
    await fireCreate(page, 100, `N-UPD-SAD-order ${uniqueId()}`);
    const log = await pollLogTerminal(page, pid, firedAt);
    expect(['failed', 'partial_success'], `N-UPDATE-RECORD-SAD must fail (bad field): ${JSON.stringify(log)}`).toContain(String(log!.status).toLowerCase());
    const statuses = await pollNodeStatuses(page, log!.id, 30_000);
    const node = statuses.find((s) => s.nodeId === action);
    expect(node?.status, `update-record node should be failed: ${JSON.stringify(statuses)}`).toBe('failed');
    expect(node?.errorMessage ?? '', 'a failed node should carry an error message').toBeTruthy();
  });

  // ── golden SAD path (real UI) — create-record targeting a nonexistent field fails ──
  test('N-CREATE-RECORD-SAD: drag trigger-record-create→action-create-record with a nonexistent field, save, enable, fire → the node FAILS (sad) @golden', async ({
    page,
  }) => {
    await openNewDesigner(page);
    await setAutomationName(page, `N-CREATE-RECORD-SAD ${uniqueId()}`);

    const trigger = await dragNodeToCanvas(page, 'trigger-record-create', { x: 150, y: 80 });
    const action = await dragNodeToCanvas(page, 'action-create-record', { x: 150, y: 240 });
    await page.locator('.react-flow__pane').click({ position: { x: 5, y: 5 } });
    await connectEdge(page, trigger, action);
    await fillNodeConfig(page, trigger, { modelCode: MODEL_LABEL });
    await fillNodeConfig(page, action, {
      modelCode: '订单明细',
      fields: '{"e2et_item_nonexistent_zzz":"boom"}',
    });
    const { pid } = await saveAutomation(page);
    createdPids.push(pid);
    await enableViaListToggle(page, pid);

    const firedAt = Date.now();
    await fireCreate(page, 100, `N-CR-SAD-order ${uniqueId()}`);
    const log = await pollLogTerminal(page, pid, firedAt);
    expect(['failed', 'partial_success'], `N-CREATE-RECORD-SAD must fail (bad field): ${JSON.stringify(log)}`).toContain(String(log!.status).toLowerCase());
    const node = (await pollNodeStatuses(page, log!.id, 30_000)).find((s) => s.nodeId === action);
    expect(node?.status, 'create-record node should be failed').toBe('failed');
  });

  // ── execute-command: real command-select picker (FINDING-9 fix) happy + sad ──────
  // The command-select picker was broken (fetchCommandOptions hit GET /api/meta/commands
  // without the required modelCode → 500 → zero options). Fixed: the endpoint lists all
  // commands when modelCode is absent, and fetchCommandOptions reads the bare list. These
  // cases drive the now-working picker in the real designer.

  test('N-EXECUTE-COMMAND: drag trigger-record-create→action-execute-command, pick a permission-free command via the real command-select picker, set params, save, enable, fire → the command runs under the automation principal and updates the trigger record (happy) @golden', async ({
    page,
  }) => {
    const marker = `EXECCMD_OK_${uniqueId()}`;
    await openNewDesigner(page);
    await setAutomationName(page, `N-EXECUTE-COMMAND ${uniqueId()}`);

    const trigger = await dragNodeToCanvas(page, 'trigger-record-create', { x: 150, y: 80 });
    const action = await dragNodeToCanvas(page, 'action-execute-command', { x: 150, y: 240 });
    await page.locator('.react-flow__pane').click({ position: { x: 5, y: 5 } });
    await connectEdge(page, trigger, action);
    await fillNodeConfig(page, trigger, { modelCode: MODEL_LABEL });
    // commandCode via the real command-select picker — filter by the unique command code
    // (BaseResourceSelect filters by label OR value). e2et:touch_order_noperm is a
    // permission-free update (test-fixtures) the restricted automation principal CAN run.
    await fillNodeConfig(page, action, {
      commandCode: 'e2et:touch_order_noperm',
      params: `{"e2et_order_remark":"${marker}"}`,
    });
    const { pid } = await saveAutomation(page);
    createdPids.push(pid);
    // The real picker drove the selection — assert the chosen command persisted.
    const saved = await readFlowConfig(page, pid);
    const node = saved.flowConfig.nodes.find((n: any) => n.id === action);
    expect(node.data.config.commandCode, 'command-select persisted the picked command').toBe(
      'e2et:touch_order_noperm',
    );

    await enableViaListToggle(page, pid);
    const firedAt = Date.now();
    const recordId = await fireCreate(page, 100, `EXECCMD-order ${uniqueId()}`);
    const log = await pollLogTerminal(page, pid, firedAt);
    expect(String(log!.status).toLowerCase(), `N-EXECUTE-COMMAND run: ${JSON.stringify(log)}`).toBe('success');
    expect(statusesNodeCompleted(await pollNodeStatuses(page, log!.id, 30_000), action), 'execute-command node completed').toBe(true);
    // Side effect: the permission-free update command set the remark on the trigger record.
    const record = await pollRecordField(page, recordId, 'e2et_order_remark', marker);
    expect(record.e2et_order_remark, 'permission-free command updated the trigger record').toBe(marker);
  });

  test('N-EXECUTE-COMMAND-SAD: pick a permission-gated command via the picker; under the restricted automation principal the run FAILS with a clear permission-denied reason and the side effect does not happen (sad) @golden', async ({
    page,
  }) => {
    await openNewDesigner(page);
    await setAutomationName(page, `N-EXECUTE-COMMAND-SAD ${uniqueId()}`);

    const trigger = await dragNodeToCanvas(page, 'trigger-record-create', { x: 150, y: 80 });
    const action = await dragNodeToCanvas(page, 'action-execute-command', { x: 150, y: 240 });
    await page.locator('.react-flow__pane').click({ position: { x: 5, y: 5 } });
    await connectEdge(page, trigger, action);
    await fillNodeConfig(page, trigger, { modelCode: MODEL_LABEL });
    // e2et:update_order requires E2ET.order.manage → the automation principal lacks it (FINDING-3).
    await fillNodeConfig(page, action, {
      commandCode: 'e2et:update_order',
      params: '{"e2et_order_title":"EXEC_SHOULD_BE_DENIED"}',
    });
    const { pid } = await saveAutomation(page);
    createdPids.push(pid);
    await enableViaListToggle(page, pid);

    const firedAt = Date.now();
    const recordId = await fireCreate(page, 100, `EXEC-SAD-order ${uniqueId()}`);
    const log = await pollLogTerminal(page, pid, firedAt);
    expect(['failed', 'partial_success'], `N-EXECUTE-COMMAND-SAD must fail (denied): ${JSON.stringify(log)}`).toContain(String(log!.status).toLowerCase());
    const statuses = await pollNodeStatuses(page, log!.id, 30_000);
    const node = statuses.find((s) => s.nodeId === action);
    expect(node?.status, `execute-command node should be failed: ${JSON.stringify(statuses)}`).toBe('failed');
    expect(node?.errorMessage ?? '', 'denial must name the required permission (clear reason)').toMatch(/permission denied|E2ET\.order\.manage/i);
    // The denied command did NOT change the record title.
    const record = await getOrderRecord(page, recordId);
    expect(record.e2et_order_title, 'denied command must not have run').not.toBe('EXEC_SHOULD_BE_DENIED');
  });

  // ── control-loop: real designer, collection-carrying webhook fixture ─────────────
  // control-loop iterates its body action once per collection element (the SmartEngine
  // fork is userTask-only, so AutomationActionServiceTaskDelegate iterates serviceTask
  // bodies manually). resolveCollection does a FLAT process-var lookup, so the collection
  // must be a top-level var; the webhook trigger spreads the inbound body's top-level keys
  // into the process variables, so a webhook body { items: [...] } exposes `${items}`.
  test('N-LOOP: build trigger-webhook→control-loop→create-record(body) in the designer; fire a webhook carrying a 3-element collection → the body runs once per element creating 3 child items (loop) @golden', async ({
    page,
  }) => {
    const tag = uniqueId();
    const names = [`LOOP-A-${tag}`, `LOOP-B-${tag}`, `LOOP-C-${tag}`];
    // A parent order to satisfy the child item's parent reference (created directly; the
    // webhook automation is not enabled yet so this does not fire it).
    const parentOrderId = await fireCreate(page, 100, `LOOP-parent ${tag}`);

    await openNewDesigner(page);
    await setAutomationName(page, `N-LOOP ${tag}`);
    const trigger = await dragNodeToCanvas(page, 'trigger-webhook', { x: 150, y: 70 });
    const loop = await dragNodeToCanvas(page, 'control-loop', { x: 150, y: 200 });
    const body = await dragNodeToCanvas(page, 'action-create-record', { x: 150, y: 330 });
    await page.locator('.react-flow__pane').click({ position: { x: 5, y: 5 } });
    await connectEdge(page, trigger, loop);
    await connectEdge(page, loop, body);
    // collection references the top-level webhook var `items`; itemVariable=item (default).
    await fillNodeConfig(page, loop, { collection: '${items}', itemVariable: 'item' });
    // The body create-record runs once per element with `item` bound to the element value.
    await fillNodeConfig(page, body, {
      modelCode: '订单明细',
      fields:
        '{"e2et_order_id":"${parentOrderId}","e2et_item_name":"${item}","e2et_item_spec":"std","e2et_item_qty":1,"e2et_item_price":10}',
    });
    await configureWebhookToken(page, trigger);
    const { pid } = await saveAutomation(page);
    createdPids.push(pid);
    await enableViaListToggle(page, pid);

    // Fire via the real inbound webhook with a 3-element collection (+ the parent id).
    const firedAt = Date.now();
    const resp = await fireInboundWebhook(page, pid, { items: names, parentOrderId });
    expect(resp.status(), `webhook POST must not 5xx; got ${resp.status()}`).toBeLessThan(500);
    const log = await pollLogTerminal(page, pid, firedAt);
    expect(String(log!.status).toLowerCase(), `N-LOOP run: ${JSON.stringify(log)}`).toBe('success');
    // The loop body created one child item per element name.
    for (const n of names) {
      expect(
        (await pollItemsByName(page, n)).length,
        `loop body created a child item for element ${n}`,
      ).toBeGreaterThanOrEqual(1);
    }
  });

  // ── action-llm-call: deterministic stub provider (AGENT_LLM_STUB_MODE on the ──────
  // ga-e2e stack) so the node runs CI-portably with no real key. The stub returns a
  // fixed "[stub response]" text which the executor stores under the output variable;
  // a downstream update-record consumes ${llmOutput} so we can assert the LLM output
  // actually flowed through the run and persisted.
  test('N-LLM-CALL: drag trigger-record-create→action-llm-call→action-update-record, configure model+prompt+output via panel, save, enable, fire → the LLM (stub) output is captured and persisted (happy) @golden', async ({
    page,
  }) => {
    await openNewDesigner(page);
    await setAutomationName(page, `N-LLM-CALL ${uniqueId()}`);
    const trigger = await dragNodeToCanvas(page, 'trigger-record-create', { x: 150, y: 70 });
    const llm = await dragNodeToCanvas(page, 'action-llm-call', { x: 150, y: 200 });
    const upd = await dragNodeToCanvas(page, 'action-update-record', { x: 150, y: 330 });
    await page.locator('.react-flow__pane').click({ position: { x: 5, y: 5 } });
    await connectEdge(page, trigger, llm);
    await connectEdge(page, llm, upd);
    await fillNodeConfig(page, trigger, { modelCode: MODEL_LABEL });
    // model is a radix select (option label); prompt + output are text fields.
    await fillNodeConfig(page, llm, {
      model: 'Claude Sonnet 4.6',
      userPromptTemplate: 'Summarise order ${recordId}',
      outputVariableName: 'llmOutput',
    });
    await fillNodeConfig(page, upd, {
      modelCode: MODEL_LABEL,
      recordPid: '${trigger.recordPid}',
      fields: '{"e2et_order_remark":"${llmOutput}"}',
    });
    const { pid } = await saveAutomation(page);
    createdPids.push(pid);
    await enableViaListToggle(page, pid);
    const firedAt = Date.now();
    const recordId = await fireCreate(page, 100, `LLM-order ${uniqueId()}`);
    const log = await pollLogTerminal(page, pid, firedAt);
    expect(String(log!.status).toLowerCase(), `N-LLM-CALL run: ${JSON.stringify(log)}`).toBe('success');
    expect(statusesNodeCompleted(await pollNodeStatuses(page, log!.id, 30_000), llm), 'llm-call node completed').toBe(true);
    // The stub LLM output flowed into the downstream update and persisted on the record.
    const record = await pollRecordField(page, recordId, 'e2et_order_remark', '[stub response]');
    expect(record.e2et_order_remark, 'the LLM (stub) output was captured and persisted').toBe('[stub response]');
  });

  // ── on_state_change toStates filter (golden EDGE + FINDING-4b regression) ─────────
  // A toStates:['cancelled'] filter only fires on the matching transition. This is the
  // FINDING-4b regression: the @Async bridge reads the just-committed state via
  // CommandStateCheckExecutor.readCurrentState; before the tenant fix that read returned
  // null (TenantLineInterceptor appended an empty-tenant predicate), so toState was null
  // and the filter never matched. After the fix toState='cancelled' and the filter fires.
  test('N-TRIGGER-STATE-FILTER: trigger-state-change with toStates=[已取消] fires on cancel but NOT on a non-matching transition (edge / FINDING-4b) @golden', async ({
    page,
  }) => {
    const itemName = `NSF-${uniqueId()}`;
    await openNewDesigner(page);
    await setAutomationName(page, `N-TRIGGER-STATE-FILTER ${uniqueId()}`);
    const trigger = await dragNodeToCanvas(page, 'trigger-state-change', { x: 150, y: 80 });
    const action = await dragNodeToCanvas(page, 'action-create-record', { x: 150, y: 240 });
    await page.locator('.react-flow__pane').click({ position: { x: 5, y: 5 } });
    await connectEdge(page, trigger, action);
    // modelCode → stateField → toStates (multiselect; option label 已取消 = value cancelled).
    await fillNodeConfig(page, trigger, {
      modelCode: MODEL_LABEL,
      stateField: '订单状态',
      toStates: ['已取消'],
    });
    // A fixed-name child item so existence == "the filtered automation fired".
    await fillNodeConfig(page, action, {
      modelCode: '订单明细',
      fields:
        '{"e2et_order_id":"${recordId}","e2et_item_name":"' +
        itemName +
        '","e2et_item_spec":"std","e2et_item_qty":1,"e2et_item_price":10}',
    });
    const { pid } = await saveAutomation(page);
    createdPids.push(pid);
    // The multiselect drove a real value → the toStates filter persisted as the code.
    // (This is also the regression for the toStates picker fix: the multiselect now
    // cascades its options from the stateField's dict instead of fetching model fields.)
    const saved = await readFlowConfig(page, pid);
    const tnode = saved.flowConfig.nodes.find((n: any) => n.id === trigger);
    expect(tnode.data.config.toStates, 'toStates filter persisted the selected state code').toContain('cancelled');
    await enableViaListToggle(page, pid);

    // CONTROL: a plain create (no state transition) must NOT fire a toStates-filtered
    // on_state_change automation — confirms the filter is actually applied.
    await fireCreate(page, 100, `NSF-noop ${uniqueId()}`);
    await expectNoItemsByNameFor(
      page,
      itemName,
      5_000,
      'a plain create must NOT fire the toStates-filtered on_state_change automation',
    );

    // MATCH: cancel an order (draft→cancelled). toState=cancelled = the filter → fires,
    // creating the child item. Before FINDING-4b the read-back toState was null (the
    // @Async bridge's tenant-scoped read returned nothing) so the cancelled filter never
    // matched and this fire was lost.
    const orderB = await fireCreate(page, 100, `NSF-cancel ${uniqueId()}`);
    const firedAt = Date.now();
    await fireCancel(page, orderB);
    const log = await pollLogTerminal(page, pid, firedAt);
    expect(String(log!.status).toLowerCase(), `N-TRIGGER-STATE-FILTER run: ${JSON.stringify(log)}`).toBe('success');
    expect(
      (await pollItemsByName(page, itemName)).length,
      'the matching cancel transition fired the toStates-filtered automation (FINDING-4b)',
    ).toBeGreaterThanOrEqual(1);
  });

  // ── trigger-scheduled: real cron fire path (AutomationScheduler polls every 60s) ──
  // The scheduler evaluates a 6-field Spring cron against the automation's last-trigger
  // (or created) time on a 60s fixed-delay loop, so an every-second cron is due on the
  // next tick. The body creates a marked e2et_order; its existence proves the scheduled
  // automation fired with no triggering record (the scheduler supplies the tenant).
  test('N-SCHEDULED: drag trigger-scheduled(cron)→action-create-record, save, enable, wait for the scheduler → the cron automation fires and creates a marked record @golden', async ({
    page,
  }) => {
    test.setTimeout(240_000); // the scheduler runs on a 60s fixed-delay loop; allow load headroom
    const marker = `SCHED_${uniqueId()}`;
    await openNewDesigner(page);
    await setAutomationName(page, `N-SCHEDULED ${uniqueId()}`);
    const trigger = await dragNodeToCanvas(page, 'trigger-scheduled', { x: 150, y: 80 });
    const action = await dragNodeToCanvas(page, 'action-create-record', { x: 150, y: 240 });
    await page.locator('.react-flow__pane').click({ position: { x: 5, y: 5 } });
    await connectEdge(page, trigger, action);
    // 6-field Spring cron, every second → due on the next scheduler tick.
    await fillNodeConfig(page, trigger, { cron: '* * * * * *' });
    await fillNodeConfig(page, action, {
      modelCode: MODEL_LABEL,
      // A scheduled body create-records directly (no command), so it must supply every
      // required field including e2et_order_status (the create command would default it).
      fields:
        '{"e2et_order_title":"' +
        marker +
        '","e2et_order_status":"draft","e2et_order_date":"2026-05-30","e2et_order_type":"normal","e2et_order_amount":1}',
    });
    const { pid } = await saveAutomation(page);
    createdPids.push(pid);
    const saved = await readFlowConfig(page, pid);
    const tnode = saved.flowConfig.nodes.find((n: any) => n.id === trigger);
    expect(tnode.data.config.cron, 'cron persisted from the panel').toBe('* * * * * *');
    const firedAt = Date.now();
    await enableViaListToggle(page, pid);

    // Wait for the scheduler (≤ ~60s fixed-delay loop) to fire the cron automation, then assert
    // via the automation LOG + create-record node-status (both queried by pid/logId). These are
    // immune to cross-test interference; a title keyword-search is NOT reliable here. Root cause
    // (instrumented 2026-06-07): the scheduled body creates a generic e2et_order, and under full-
    // suite serial load the still-enabled on_record_create automations from earlier cases (notably
    // N-CONDITION-EDGE) re-fire on that order and overwrite e2et_order_title (→ EDGE_FALSE) before
    // the search runs — so the marker is gone even though the order WAS persisted. The create-
    // record node reaching 'completed' is the authoritative proof the scheduled run inserted a
    // record (verified: node=completed ⇔ the row exists, just re-titled by a concurrent automation).
    const firedLog = await pollLogTerminal(page, pid, firedAt, 150_000);
    expect(firedLog, 'the scheduler should have fired the cron automation (a run log exists)').not.toBeNull();
    expect(String(firedLog!.status).toLowerCase(), `the scheduled run should succeed: ${JSON.stringify(firedLog)}`).toBe('success');
    const statuses = await pollNodeStatuses(page, firedLog!.id, 30_000);
    expect(
      statuses.find((s) => s.nodeId === action)?.status,
      `the scheduled create-record node persisted a record: ${JSON.stringify(statuses)}`,
    ).toBe('completed');
  });

  test('N-TRIGGER-INACTIVITY: drag trigger-inactivity→action-create-record, configure stale-date/state filters, wait for scheduler → inactive record fires @golden', async ({
    page,
  }) => {
    const inactivityPollTimeout = Number(
      process.env.AUTOMATION_INACTIVITY_E2E_TIMEOUT_MS ||
        (process.env.AUTOMATION_INACTIVITY_FIXED_DELAY_MS ? '120000' : '420000'),
    );
    test.setTimeout(inactivityPollTimeout + 90_000);
    const itemName = `NINACT-item-${uniqueId()}`;
    const inactiveOrderId = await fireCreate(
      page,
      100,
      `NINACT-order ${uniqueId()}`,
      '2000-01-01',
    );
    await fireCancel(page, inactiveOrderId);

    await openNewDesigner(page);
    await setAutomationName(page, `N-TRIGGER-INACTIVITY ${uniqueId()}`);
    const trigger = await dragNodeToCanvas(page, 'trigger-inactivity', { x: 150, y: 80 });
    const action = await dragNodeToCanvas(page, 'action-create-record', { x: 150, y: 240 });
    await page.locator('.react-flow__pane').click({ position: { x: 5, y: 5 } });
    await connectEdge(page, trigger, action);
    await fillNodeConfig(page, trigger, {
      modelCode: MODEL_LABEL,
      inactivityHours: '100000',
      inactivityField: '下单日期',
      stateField: '订单状态',
      inactivityStates: ['已取消'],
    });
    await fillNodeConfig(page, action, {
      modelCode: '订单明细',
      fields:
        '{"e2et_order_id":"${recordId}","e2et_item_name":"' +
        itemName +
        '","e2et_item_spec":"inactivity","e2et_item_qty":1,"e2et_item_price":10}',
    });

    const { pid } = await saveAutomation(page);
    createdPids.push(pid);
    const saved = await readFlowConfig(page, pid);
    const triggerNode = saved.flowConfig.nodes.find((n: any) => n.id === trigger);
    expect(triggerNode.data.config.modelCode, 'inactivity model persisted as model code').toBe(MODEL_CODE);
    expect(triggerNode.data.config.inactivityHours, 'inactivityHours persisted from number input').toBe(100000);
    expect(triggerNode.data.config.inactivityField, 'inactivity date field persisted as field code').toBe(
      'e2et_order_date',
    );
    expect(triggerNode.data.config.stateField, 'state field persisted as field code').toBe(
      'e2et_order_status',
    );
    expect(triggerNode.data.config.inactivityStates, 'inactivity state filter persisted as dict code').toContain(
      'cancelled',
    );

    const firedAt = Date.now();
    await enableViaListToggle(page, pid);
    const log = await pollLogTerminal(page, pid, firedAt, inactivityPollTimeout);
    expect(log, 'the inactivity scheduler should create a run log').not.toBeNull();
    expect(String(log!.status).toLowerCase(), `inactivity run should succeed: ${JSON.stringify(log)}`).toBe(
      'success',
    );
    const statuses = await pollNodeStatuses(page, log!.id, 30_000);
    expect(statusesNodeCompleted(statuses, action), `create-record node completed: ${JSON.stringify(statuses)}`).toBe(true);
    const items = await pollItemsByName(page, itemName, 30_000);
    expect(
      items.some((item) => String(item.e2et_order_id) === inactiveOrderId),
      `inactive order ${inactiveOrderId} should receive the created child item: ${JSON.stringify(items)}`,
    ).toBe(true);
  });

  // ── golden EDGE (real UI) — field-change filter precision: only the watched field fires ──
  // AutomationTriggerServiceImpl#onFieldChange skips when the changed field != config.fieldCode
  // (`fieldCode.equals(config.getFieldCode())`); the command bridge emits a per-field change
  // event only when oldValue != newValue. So an update to an UNWATCHED field must NOT fire a
  // field-change automation watching a different field; the watched field MUST fire.
  test('N-FIELD-CHANGE-EDGE: trigger-field-change watching 订单标题 — updating a DIFFERENT field does NOT fire, updating the watched field DOES (edge / field filter) @golden', async ({
    page,
  }) => {
    const itemName = `NFCE-item-${uniqueId()}`;
    await openNewDesigner(page);
    await setAutomationName(page, `N-FIELD-CHANGE-EDGE ${uniqueId()}`);

    const trigger = await dragNodeToCanvas(page, 'trigger-field-change', { x: 150, y: 80 });
    const action = await dragNodeToCanvas(page, 'action-create-record', { x: 150, y: 240 });
    await page.locator('.react-flow__pane').click({ position: { x: 5, y: 5 } });
    await connectEdge(page, trigger, action);
    await fillNodeConfig(page, trigger, { modelCode: MODEL_LABEL, fieldCode: '订单标题' });
    await fillNodeConfig(page, action, {
      modelCode: '订单明细',
      fields: '{"e2et_order_id":"${recordId}","e2et_item_name":"' + itemName + '","e2et_item_spec":"std","e2et_item_qty":1,"e2et_item_price":10}',
    });
    const { pid } = await saveAutomation(page);
    createdPids.push(pid);
    await enableViaListToggle(page, pid);

    const orderId = await fireCreate(page, 100, `NFCE-order ${uniqueId()}`);
    // EDGE: update an UNWATCHED field (remark, not the title) → the fieldCode filter must
    // skip it → NO child item created.
    await fireUpdate(page, orderId, { e2et_order_remark: `unwatched ${uniqueId()}` });
    await expectNoItemsByNameFor(
      page,
      itemName,
      5_000,
      'changing an unwatched field must NOT fire the field-change automation',
    );

    // POSITIVE CONTROL: update the WATCHED field (title) → fires → child item created.
    const firedAt = Date.now();
    await fireUpdate(page, orderId, { e2et_order_title: `NFCE-changed ${uniqueId()}` });
    const log = await pollLogTerminal(page, pid, firedAt);
    expect(String(log!.status).toLowerCase(), `N-FIELD-CHANGE-EDGE watched-field run: ${JSON.stringify(log)}`).toBe('success');
    expect(
      (await pollItemsByName(page, itemName)).length,
      'the watched field change fired the automation (control)',
    ).toBeGreaterThanOrEqual(1);
  });

  // ── golden EDGE (real UI) — loop over an EMPTY collection runs the body 0 times ──
  // AutomationActionServiceTaskDelegate iterates `for (element : items)`; an empty collection
  // runs the body 0 times then still marks the node COMPLETED — so the run succeeds with no
  // side effect (boundary: empty input).
  test('N-LOOP-EDGE: control-loop over an EMPTY collection — the body runs 0 times, the run still succeeds, no child item is created (edge / boundary) @golden', async ({
    page,
  }) => {
    const tag = uniqueId();
    const itemName = `NLE-item-${tag}`;
    const parentOrderId = await fireCreate(page, 100, `NLE-parent ${tag}`);
    await openNewDesigner(page);
    await setAutomationName(page, `N-LOOP-EDGE ${tag}`);
    const trigger = await dragNodeToCanvas(page, 'trigger-webhook', { x: 150, y: 70 });
    const loop = await dragNodeToCanvas(page, 'control-loop', { x: 150, y: 200 });
    const body = await dragNodeToCanvas(page, 'action-create-record', { x: 150, y: 330 });
    await page.locator('.react-flow__pane').click({ position: { x: 5, y: 5 } });
    await connectEdge(page, trigger, loop);
    await connectEdge(page, loop, body);
    await fillNodeConfig(page, loop, { collection: '${items}', itemVariable: 'item' });
    await fillNodeConfig(page, body, {
      modelCode: '订单明细',
      fields: '{"e2et_order_id":"${parentOrderId}","e2et_item_name":"' + itemName + '","e2et_item_spec":"std","e2et_item_qty":1,"e2et_item_price":10}',
    });
    await configureWebhookToken(page, trigger);
    const { pid } = await saveAutomation(page);
    createdPids.push(pid);
    await enableViaListToggle(page, pid);

    // Fire with an EMPTY collection → the loop body iterates 0 times.
    const firedAt = Date.now();
    const resp = await fireInboundWebhook(page, pid, { items: [], parentOrderId });
    expect(resp.status(), `webhook POST must not 5xx; got ${resp.status()}`).toBeLessThan(500);
    const log = await pollLogTerminal(page, pid, firedAt);
    expect(String(log!.status).toLowerCase(), `N-LOOP-EDGE empty-collection run should still succeed: ${JSON.stringify(log)}`).toBe('success');
    expect(statusesNodeCompleted(await pollNodeStatuses(page, log!.id, 30_000), body), 'the loop body node completes with 0 iterations').toBe(true);
    await expectNoItemsByNameFor(
      page,
      itemName,
      3_000,
      'an empty collection creates no child items',
    );
  });

  // ── control-delay: real designer + SmartEngine runtime seam ────────────────────
  // The visual node persists duration/unit from the property panel; the backend compiler
  // must map control-delay to a SmartEngine serviceTask and ControlNodeExecutor must consume
  // duration/unit, not only legacy delayMs/delaySeconds. The downstream create-record side
  // effect proves the graph continued after the delay node.
  test('N-DELAY: build trigger-webhook→control-delay(1 second)→create-record in the designer; fire webhook → delay node and downstream action complete (runtime seam) @golden', async ({
    page,
  }) => {
    const tag = uniqueId();
    const itemName = `NDELAY-item-${tag}`;
    const parentOrderId = await fireCreate(page, 100, `NDELAY-parent ${tag}`);
    await openNewDesigner(page);
    await setAutomationName(page, `N-DELAY ${tag}`);

    const trigger = await dragNodeToCanvas(page, 'trigger-webhook', { x: 150, y: 70 });
    const delayNode = await dragNodeToCanvas(page, 'control-delay', { x: 150, y: 200 });
    const body = await dragNodeToCanvas(page, 'action-create-record', { x: 150, y: 330 });
    await page.locator('.react-flow__pane').click({ position: { x: 5, y: 5 } });
    await connectEdge(page, trigger, delayNode);
    await connectEdge(page, delayNode, body);
    await fillNodeConfig(page, delayNode, { duration: '1', unit: '秒' });
    await configureWebhookToken(page, trigger);
    await fillNodeConfig(page, body, {
      modelCode: '订单明细',
      fields: `{"e2et_order_id":"${parentOrderId}","e2et_item_name":"${itemName}","e2et_item_spec":"std","e2et_item_qty":1,"e2et_item_price":10}`,
    });
    const { pid } = await saveAutomation(page);
    createdPids.push(pid);
    const saved = await readFlowConfig(page, pid);
    const dnode = saved.flowConfig.nodes.find((n: any) => n.id === delayNode);
    expect(String(dnode.data.config.duration), 'delay duration persisted from the panel').toBe('1');
    expect(dnode.data.config.unit, 'delay unit persisted as the backend value').toBe('seconds');
    await enableViaListToggle(page, pid);

    const firedAt = Date.now();
    const resp = await fireInboundWebhook(page, pid, { parentOrderId });
    expect(resp.status(), `webhook POST must not 5xx; got ${resp.status()}`).toBeLessThan(500);
    const log = await pollLogTerminal(page, pid, firedAt, 60_000);
    expect(String(log!.status).toLowerCase(), `N-DELAY run: ${JSON.stringify(log)}`).toBe('success');
    const statuses = await pollNodeStatuses(page, log!.id, 30_000);
    expect(statuses.find((s) => s.nodeId === delayNode)?.status, `delay node completed: ${JSON.stringify(statuses)}`).toBe('completed');
    expect(statuses.find((s) => s.nodeId === body)?.status, `downstream body completed after delay: ${JSON.stringify(statuses)}`).toBe('completed');
    expect((await pollItemsByName(page, itemName)).length, 'downstream create-record ran after the delay').toBeGreaterThanOrEqual(1);
  });

  // ── golden CORNER (real UI) — re-entrancy: the same enabled automation fires twice ──────
  // Two back-to-back creates must each produce an independent run + its own side effect
  // (no dropped run, no cross-contamination). The child item name binds to ${recordId}
  // (the trigger order id, distinct per fire) so each run's side effect is independently keyed.
  test('N-CORNER-CONCURRENT: fire the same enabled automation TWICE back-to-back — both runs complete independently and each creates its own child item (corner / re-entrancy) @golden', async ({
    page,
  }) => {
    const tag = uniqueId();
    await openNewDesigner(page);
    await setAutomationName(page, `N-CORNER-CONCURRENT ${tag}`);
    const trigger = await dragNodeToCanvas(page, 'trigger-record-create', { x: 150, y: 80 });
    const action = await dragNodeToCanvas(page, 'action-create-record', { x: 150, y: 240 });
    await page.locator('.react-flow__pane').click({ position: { x: 5, y: 5 } });
    await connectEdge(page, trigger, action);
    await fillNodeConfig(page, trigger, { modelCode: MODEL_LABEL });
    await fillNodeConfig(page, action, {
      modelCode: '订单明细',
      fields: '{"e2et_order_id":"${recordId}","e2et_item_name":"${recordId}","e2et_item_spec":"std","e2et_item_qty":1,"e2et_item_price":10}',
    });
    const { pid } = await saveAutomation(page);
    createdPids.push(pid);
    await enableViaListToggle(page, pid);

    // Fire TWICE back-to-back (no wait between) → two independent runs.
    const firedAt = Date.now();
    const orderA = await fireCreate(page, 100, `NCC-A ${tag}`);
    const orderB = await fireCreate(page, 100, `NCC-B ${tag}`);
    // Each run created its own child item keyed by its distinct trigger order id.
    expect((await pollItemsByName(page, orderA)).length, 'first fire created its own child item').toBeGreaterThanOrEqual(1);
    expect((await pollItemsByName(page, orderB)).length, 'second fire created its own child item').toBeGreaterThanOrEqual(1);
    // Two distinct run logs were recorded for the two fires (re-entrancy, not a single merged run).
    const logsResp = await page.request.get(`/api/automations/${pid}/logs`, { params: { limit: 10 } });
    const logs: any[] = ((await logsResp.json())?.data ?? []).filter(
      (r: any) => (r.startedAt ? new Date(r.startedAt).getTime() : 0) >= firedAt - 5_000,
    );
    expect(logs.length, 'two back-to-back fires recorded two independent run logs').toBeGreaterThanOrEqual(2);
  });

  // ── golden CORNER (real UI) — unicode/i18n: a CJK+emoji payload round-trips intact ──────
  // The golden standard lists unicode/i18n as a corner case. A create-record carrying a
  // CJK+emoji+ascii name must persist byte-exact through the command pipeline + DB.
  test('N-CORNER-UNICODE: create-record with a CJK/emoji item name round-trips and persists exactly (corner / i18n robustness) @golden', async ({
    page,
  }) => {
    const tag = uniqueId();
    const itemName = `订单🎉项目-${tag}`; // CJK + emoji + ascii tag (ascii tag keeps it searchable)
    await openNewDesigner(page);
    await setAutomationName(page, `N-CORNER-UNICODE ${tag}`);
    const trigger = await dragNodeToCanvas(page, 'trigger-record-create', { x: 150, y: 80 });
    const action = await dragNodeToCanvas(page, 'action-create-record', { x: 150, y: 240 });
    await page.locator('.react-flow__pane').click({ position: { x: 5, y: 5 } });
    await connectEdge(page, trigger, action);
    await fillNodeConfig(page, trigger, { modelCode: MODEL_LABEL });
    await fillNodeConfig(page, action, {
      modelCode: '订单明细',
      fields: '{"e2et_order_id":"${recordId}","e2et_item_name":"' + itemName + '","e2et_item_spec":"规格✓","e2et_item_qty":1,"e2et_item_price":10}',
    });
    const { pid } = await saveAutomation(page);
    createdPids.push(pid);
    await enableViaListToggle(page, pid);

    const firedAt = Date.now();
    await fireCreate(page, 100, `NCU-order ${tag}`);
    const log = await pollLogTerminal(page, pid, firedAt);
    expect(String(log!.status).toLowerCase(), `N-CORNER-UNICODE run: ${JSON.stringify(log)}`).toBe('success');
    // Search by the ascii tag (reliably keyword-searchable), then match the EXACT unicode name.
    const deadline = Date.now() + 20_000;
    let found: any | undefined;
    while (Date.now() < deadline && !found) {
      const resp = await page.request.get(`/api/dynamic/e2et_order_item/list`, {
        params: { pageNum: 1, pageSize: 50, keyword: tag },
      });
      if (resp.ok()) {
        const data = (await resp.json())?.data;
        const rows = data?.records ?? data?.list ?? data?.content ?? data?.rows ?? (Array.isArray(data) ? data : []);
        found = rows.find((r: any) => r?.e2et_item_name === itemName);
      }
      if (!found) await delay(1_500);
    }
    expect(found, 'the unicode item name round-tripped and persisted exactly').toBeTruthy();
    expect(found.e2et_item_name, 'the persisted name matches the unicode source byte-exact').toBe(itemName);
  });
});

/** True if the given node reached 'completed' in the polled statuses. */
function statusesNodeCompleted(statuses: { nodeId: string; status: string }[], nodeId: string): boolean {
  return statuses.find((s) => s.nodeId === nodeId)?.status === 'completed';
}

interface WebhookReceiver {
  /** The ephemeral host port the docker backend reaches via host.docker.internal. */
  port: number;
  /** Every request the receiver captured (proves the outbound POST physically landed). */
  received: { method: string; path: string; body: string }[];
  close: () => Promise<void>;
}

/**
 * Stand up an in-process HTTP receiver bound to 0.0.0.0 on an ephemeral port. The
 * docker backend reaches it via host.docker.internal:<port> (the same path
 * N-CALL-API uses to hit host.docker.internal:6444). POST /hook → 200, POST /fail
 * → 500; every request is recorded so a golden case can assert the real outbound
 * webhook actually landed with its payload (GAP-F).
 */
async function startWebhookReceiver(): Promise<WebhookReceiver> {
  const received: { method: string; path: string; body: string }[] = [];
  const server = http.createServer((req, res) => {
    let body = '';
    req.on('data', (chunk) => (body += chunk));
    req.on('end', () => {
      received.push({ method: req.method || '', path: req.url || '', body });
      if ((req.url || '').includes('/fail')) {
        res.statusCode = 500;
        res.end('{"error":"forced failure"}');
      } else {
        res.statusCode = 200;
        res.end('{"ok":true}');
      }
    });
  });
  await new Promise<void>((resolve) => server.listen(0, '0.0.0.0', () => resolve()));
  const port = (server.address() as AddressInfo).port;
  return {
    port,
    received,
    close: () => new Promise<void>((resolve) => server.close(() => resolve())),
  };
}
