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
});
