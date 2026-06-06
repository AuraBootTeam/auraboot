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
 *   - `action-update-record` with recordId `${recordId}` updates the trigger
 *     record (a reliably-assertable persisted side effect).
 *
 * @since Phase 1 (Layer A)
 */
import { test, expect, type Page } from '@playwright/test';
import { uniqueId } from '../helpers';
import {
  dragNodeToCanvas,
  connectEdge,
  fillNodeConfig,
  setEdgeCondition,
  saveAutomation,
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
): Promise<string> {
  const resp = await page.request.post(`/api/meta/commands/execute/${CREATE_COMMAND}`, {
    data: {
      operationType: 'create',
      payload: {
        e2et_order_title: title,
        e2et_order_date: '2026-05-30',
        e2et_order_type: 'normal',
        e2et_order_amount: amount,
      },
    },
  });
  const body = await resp.json();
  expect(String(body.code), `create command failed: ${JSON.stringify(body)}`).toBe('0');
  return body.data.data.recordId as string;
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
    await page.waitForTimeout(1_000);
  }
  return last;
}

async function readFlowConfig(page: Page, pid: string): Promise<any> {
  const resp = await page.request.get(`/api/automations/${pid}`);
  return (await resp.json()).data;
}

/** Poll the automation's latest log (started >= firedAt) until terminal. */
async function pollLogTerminal(
  page: Page,
  pid: string,
  firedAt: number,
  timeoutMs = 30_000,
): Promise<{ id: number; status: string; errorMessage?: string } | null> {
  const deadline = Date.now() + timeoutMs;
  let last: { id: number; status: string; errorMessage?: string } | null = null;
  while (Date.now() < deadline) {
    last = await latestLog(page, pid, firedAt);
    if (last && ['success', 'failed', 'partial_success'].includes(String(last.status).toLowerCase())) {
      return last;
    }
    await page.waitForTimeout(1_000);
  }
  return last;
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
  await page.locator('[data-testid="flow-palette"]').waitFor({ state: 'visible', timeout: 20_000 });
  await page.locator('.react-flow__pane').waitFor({ state: 'visible', timeout: 10_000 });
}

// Set the automation name in the outer editor header. The backend rejects an
// empty name with HTTP 400, so this is required for save. The controlled input
// only commits via per-keystroke onChange — `fill()` does NOT stick (it sets the
// value without firing React's onChange), so we type it (proven pattern, mirrors
// automation-validation-gate.spec.ts).
async function setAutomationName(page: Page, name: string): Promise<void> {
  const input = page.locator('[data-testid="automation-editor-name-input"]');
  await input.click();
  await input.pressSequentially(name, { delay: 10 });
  await expect(input).toHaveValue(name);
}

// ───────────────────────── tests ─────────────────────────

test.describe('Automation Designer — Layer A real drag-drop golden', () => {
  test.describe.configure({ mode: 'serial', timeout: 120_000 });

  const createdPids: string[] = [];
  // Shared across the serial H1→H2→H3 chain so the heavy drag-build runs once and
  // the persistence + overlay cases reuse the same saved automation.
  const h1: { pid?: string; logId?: number; nodeIds?: string[] } = {};

  test.afterAll(async ({ browser }) => {
    if (!createdPids.length) return;
    const ctx = await browser.newContext({
      storageState:
        process.env.PW_ADMIN_STORAGE_STATE ||
        (process.env.PW_STORAGE_DIR
          ? `${process.env.PW_STORAGE_DIR}/admin.json`
          : 'tests/storage/admin.json'),
    });
    const page = await ctx.newPage();
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
      recordId: '${recordId}',
      fields: '{"e2et_order_title":"AUTOMATION_FIRED_HIGH"}',
    });
    await fillNodeConfig(page, actLow, {
      modelCode: MODEL_LABEL,
      recordId: '${recordId}',
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
    await page.locator('[data-testid="designer-save"]').click();

    // The errored node is auto-selected and its required field renders a
    // field-level error in the property panel — NOT just a generic toast.
    const panel = page.locator(`[data-testid="prop-field-modelCode"]`);
    await expect(panel.getByText('This field is required')).toBeVisible({ timeout: 5_000 });
    // The toolbar surfaces a structured error count (not a transient toast).
    await expect(page.getByText(/存在错误|Errors/).first()).toBeVisible();

    // The validation gate truly blocked the write.
    await page.waitForTimeout(1_000);
    expect(wroteApi, 'save must NOT fire an /api/automations write when invalid').toBe(false);
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
      recordId: '${recordId}',
      fields: '{"e2et_order_title":"S2_SHOULD_NOT_HAPPEN"}',
    });
    await fillNodeConfig(page, actLow, {
      modelCode: MODEL_LABEL,
      recordId: '${recordId}',
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
  // (model-select by label, json field). SmartEngine-dependent nodes (start-process,
  // bpm-event, control-delay) are excluded per the directive.

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
      await page.waitForTimeout(1_000);
    }
    return rows.filter((r) => r?.e2et_item_name === name);
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

  /** Fire a record UPDATE (top-level targetRecordId — FINDING-2). Fires on_record_update. */
  async function fireUpdate(page: Page, orderId: string, fields: Record<string, unknown>): Promise<void> {
    const resp = await page.request.post(`/api/meta/commands/execute/e2et:update_order`, {
      data: { operationType: 'update', targetRecordId: orderId, payload: fields },
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
      url: 'http://host.docker.internal:6444/actuator/health',
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

  test('N-SEND-WEBHOOK: drag trigger-record-create→action-send-webhook, configure url+payload via panel, save, enable, fire → node completes (dispatch) @golden', async ({
    page,
  }) => {
    await openNewDesigner(page);
    await setAutomationName(page, `N-SEND-WEBHOOK ${uniqueId()}`);

    const trigger = await dragNodeToCanvas(page, 'trigger-record-create', { x: 150, y: 80 });
    const action = await dragNodeToCanvas(page, 'action-send-webhook', { x: 150, y: 240 });
    await page.locator('.react-flow__pane').click({ position: { x: 5, y: 5 } });
    await connectEdge(page, trigger, action);
    await fillNodeConfig(page, trigger, { modelCode: MODEL_LABEL });
    await fillNodeConfig(page, action, {
      url: 'http://host.docker.internal:6444/actuator/health',
      payload: '{"event":"e2e.designer.webhook","orderId":"${recordId}"}',
    });
    const { pid } = await saveAutomation(page);
    createdPids.push(pid);
    await enableViaListToggle(page, pid);

    const firedAt = Date.now();
    await fireCreate(page, 100, `N-SEND-WEBHOOK-order ${uniqueId()}`);
    const log = await pollLogTerminal(page, pid, firedAt);
    expect(String(log!.status).toLowerCase(), `N-SEND-WEBHOOK run: ${JSON.stringify(log)}`).toBe('success');
    expect(statusesNodeCompleted(await pollNodeStatuses(page, log!.id, 30_000), action), 'send-webhook node completed (dispatch)').toBe(true);
  });

  /** Fire a state_transition command (cancel: draft→cancelled). Fires on_state_change. */
  async function fireCancel(page: Page, orderId: string): Promise<void> {
    const resp = await page.request.post(`/api/meta/commands/execute/e2et:cancel_order`, {
      data: { operationType: 'state_transition', targetRecordId: orderId, payload: {} },
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

    // trigger-webhook has no required config (validationMode defaults to none) and no
    // modelCode (FINDING-1 made model_code nullable). Action does not depend on ${recordId}.
    const trigger = await dragNodeToCanvas(page, 'trigger-webhook', { x: 150, y: 80 });
    const action = await dragNodeToCanvas(page, 'action-call-api', { x: 150, y: 240 });
    await page.locator('.react-flow__pane').click({ position: { x: 5, y: 5 } });
    await connectEdge(page, trigger, action);
    await fillNodeConfig(page, action, {
      url: 'http://host.docker.internal:6444/actuator/health',
      method: 'GET',
    });
    const { pid } = await saveAutomation(page);
    createdPids.push(pid);
    await enableViaListToggle(page, pid);

    // FIRE via the real inbound webhook endpoint (resolves the automation by pid).
    const firedAt = Date.now();
    const resp = await page.request.post(`/api/automations/webhooks/${pid}`, {
      data: { event: 'order.shipped', ref: `NWH ${uniqueId()}` },
    });
    expect(resp.status(), `webhook POST must not 5xx; got ${resp.status()}`).toBeLessThan(500);
    expect(String((await resp.json())?.code), 'webhook POST accepted').toBe('0');

    const log = await pollLogTerminal(page, pid, firedAt);
    expect(String(log!.status).toLowerCase(), `N-TRIGGER-WEBHOOK run: ${JSON.stringify(log)}`).toBe('success');
    expect(statusesNodeCompleted(await pollNodeStatuses(page, log!.id, 30_000), action), 'downstream action completed on webhook fire').toBe(true);
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
      url: 'http://host.docker.internal:6444/api/this-endpoint-does-not-exist-404',
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

    // ENABLED → fire → a run completes.
    await setEnabledViaToggle(page, pid, 'on');
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
    await page.waitForTimeout(6_000); // give the engine a chance to (not) run
    const newWhileDisabled = [...(await fetchLogIds(page, pid))].filter((id) => !idsBefore.has(id));
    expect(newWhileDisabled, `a disabled automation must NOT run (new logs: ${newWhileDisabled})`).toHaveLength(0);

    // RE-ENABLED → fire → runs again, a distinct newer log.
    await setEnabledViaToggle(page, pid, 'on');
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
    await fillNodeConfig(page, actHigh, { modelCode: MODEL_LABEL, recordId: '${recordId}', fields: '{"e2et_order_title":"EDGE_TRUE"}' });
    await fillNodeConfig(page, actLow, { modelCode: MODEL_LABEL, recordId: '${recordId}', fields: '{"e2et_order_title":"EDGE_FALSE"}' });
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
});

/** True if the given node reached 'completed' in the polled statuses. */
function statusesNodeCompleted(statuses: { nodeId: string; status: string }[], nodeId: string): boolean {
  return statuses.find((s) => s.nodeId === nodeId)?.status === 'completed';
}
