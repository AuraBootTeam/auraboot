/**
 * Shared E2E harness for the @xyflow flow-designer-sdk (automation + bpmn slices).
 *
 * Drag mechanism (verified 2026-06-05): the SDK canvas is `<ReactFlow>` (@xyflow);
 * palette→canvas uses HTML5 native drag — `FlowCanvas.onDrop` reads
 * `event.dataTransfer.getData('application/flow-node')` and `onDragOver` sets
 * `dropEffect='move'`. So we add a node by dispatching a synthetic `drop`
 * (DataTransfer carrying `application/flow-node` = node type) at a canvas point.
 * Edge connection uses @xyflow handle pointer-drag (multi-step pointer, NOT
 * single dragTo — red line §20 / dnd-conventions).
 *
 * Stable test selectors (added 2026-06-05, commit 7a4d632fc):
 *   palette item : [data-testid="palette-node-<type>"]
 *   node box     : [data-testid="flow-node-<id>"]
 *   handles      : [data-testid="node-handle-source-<id>"] / "-target-<id>"
 *                  condition dual: "node-handle-source-<id>-true" / "-false"
 *   prop field   : [data-testid="prop-field-<key>"]
 *   save button  : [data-testid="designer-save"] or [data-testid="automation-editor-toolbar-btn-save"]
 *   (no in-designer enable control — enable via the list page btn-toggle-<pid>,
 *    or the /enable API for behavioral setup.)
 *
 * NOTE: the UI helpers (drag/connect/config/save) are smoke-verified by the
 * Phase-0 harness smoke test before any Layer A case relies on them.
 */
import { expect } from '@playwright/test';
import type { Page, Locator } from '@playwright/test';

export const DATA_TRANSFER_KEY = 'application/flow-node';
export const API_OK = '0';

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

// ───────────────────────── UI helpers (real designer) ─────────────────────────

/** The @xyflow canvas pane locator (drop target). */
export function canvas(page: Page): Locator {
  return page.locator('.react-flow__pane').first();
}

/**
 * Drag a palette node onto the canvas via HTML5 native drag and return the new
 * node's id. Dispatches dragstart on the palette item, then dragover+drop on the
 * canvas at `offset` (relative to the canvas box), sharing one DataTransfer so
 * the `application/flow-node` payload survives — Playwright's per-event synthetic
 * DataTransfers do not persist across events, so we create one in-page.
 */
export async function dragNodeToCanvas(
  page: Page,
  paletteType: string,
  offset: { x: number; y: number },
): Promise<string> {
  const idsBefore = await currentNodeIds(page);
  const paletteSel = `[data-testid="palette-node-${paletteType}"]`;
  await ensurePaletteItemVisible(page, paletteSel);

  const box = await canvas(page).boundingBox();
  if (!box) throw new Error('canvas has no bounding box');
  const dropX = box.x + offset.x;
  const dropY = box.y + offset.y;

  await page.evaluate(
    ({ paletteSel, key, dropX, dropY }) => {
      const src = document.querySelector(paletteSel) as HTMLElement | null;
      const pane = document.querySelector('.react-flow__pane') as HTMLElement | null;
      if (!src || !pane) throw new Error('palette item or canvas pane not found');
      const dt = new DataTransfer();
      const fire = (el: Element, type: string, x?: number, y?: number) => {
        const ev = new DragEvent(type, {
          bubbles: true,
          cancelable: true,
          dataTransfer: dt,
          clientX: x,
          clientY: y,
        });
        el.dispatchEvent(ev);
      };
      fire(src, 'dragstart');
      // The palette's onDragStart sets dataTransfer; if it relied on the native
      // event's dataTransfer we have already shared `dt`. Ensure the key is set.
      if (!dt.getData(key)) dt.setData(key, (src.getAttribute('data-node-type') || ''));
      fire(pane, 'dragenter', dropX, dropY);
      fire(pane, 'dragover', dropX, dropY);
      fire(pane, 'drop', dropX, dropY);
      fire(src, 'dragend');
    },
    { paletteSel, key: DATA_TRANSFER_KEY, dropX, dropY },
  );

  // The new node id is the one not present before.
  const newId = await page.waitForFunction(
    (prev) => {
      const ids = Array.from(document.querySelectorAll('[data-testid^="flow-node-"]'))
        .map((el) => el.getAttribute('data-testid')!.replace('flow-node-', ''));
      const fresh = ids.find((id) => !prev.includes(id));
      return fresh || null;
    },
    idsBefore,
  );
  await closeCompactPaletteDrawer(page);
  return String(await newId.jsonValue());
}

async function ensurePaletteItemVisible(page: Page, paletteSel: string): Promise<void> {
  const paletteItem = page.locator(paletteSel);
  if (await paletteItem.isVisible().catch(() => false)) return;

  const toggle = page.locator('[data-testid="flow-toggle-palette"]');
  if (await toggle.isVisible().catch(() => false)) {
    const shell = page.locator('[data-testid="flow-palette-shell"]');
    if ((await shell.getAttribute('data-open').catch(() => 'false')) !== 'true') {
      await toggle.click();
    }
  }

  await paletteItem.waitFor({ state: 'visible' });
}

async function closeCompactPaletteDrawer(page: Page): Promise<void> {
  const close = page.locator('[data-testid="flow-close-palette"]');
  if (await close.isVisible().catch(() => false)) {
    await close.click();
  }
}

/** Current node ids on the canvas (by flow-node-<id> testid). */
export async function currentNodeIds(page: Page): Promise<string[]> {
  return page.$$eval('[data-testid^="flow-node-"]', (els) =>
    els.map((el) => el.getAttribute('data-testid')!.replace('flow-node-', '')),
  );
}

/**
 * Current edge ids on the canvas. @xyflow renders each edge group as
 * `.react-flow__edge[data-id="<edgeId>"]`; the store assigns the id.
 */
export async function currentEdgeIds(page: Page): Promise<string[]> {
  return page.$$eval('.react-flow__edge[data-id]', (els) =>
    els.map((el) => el.getAttribute('data-id')!).filter(Boolean),
  );
}

/**
 * Connect two nodes by pointer-dragging from the source node's source handle to
 * the target node's target handle (multi-step pointer — @xyflow connection).
 * `sourceHandle` lets condition nodes pick the `true`/`false` handle.
 */
export async function connectEdge(
  page: Page,
  sourceNodeId: string,
  targetNodeId: string,
  sourceHandle?: 'true' | 'false',
): Promise<string> {
  const idsBefore = await currentEdgeIds(page);
  const srcSel = sourceHandle
    ? `[data-testid="node-handle-source-${sourceNodeId}-${sourceHandle}"]`
    : `[data-testid="node-handle-source-${sourceNodeId}"]`;
  const tgtSel = `[data-testid="node-handle-target-${targetNodeId}"]`;
  const src = await page.locator(srcSel).boundingBox();
  const tgt = await page.locator(tgtSel).boundingBox();
  if (!src || !tgt) throw new Error(`handle missing: ${srcSel} or ${tgtSel}`);
  const from = { x: src.x + src.width / 2, y: src.y + src.height / 2 };
  const to = { x: tgt.x + tgt.width / 2, y: tgt.y + tgt.height / 2 };
  await page.mouse.move(from.x, from.y);
  await page.mouse.down();
  // Intermediate moves so @xyflow's connection line tracks (single jump misses).
  await page.mouse.move((from.x + to.x) / 2, (from.y + to.y) / 2, { steps: 5 });
  await page.mouse.move(to.x, to.y, { steps: 5 });
  await page.mouse.up();
  // The new edge id is the one not present before. @xyflow renders the new edge
  // group with data-id once the store commits the connection.
  const newId = await page.waitForFunction(
    (prev) => {
      const ids = Array.from(document.querySelectorAll('.react-flow__edge[data-id]'))
        .map((el) => el.getAttribute('data-id')!)
        .filter(Boolean);
      const fresh = ids.find((id) => !prev.includes(id));
      return fresh || null;
    },
    idsBefore,
  );
  return String(await newId.jsonValue());
}

/**
 * Select an edge by clicking its interaction path, then fill the built-in
 * DefaultEdgeEditor (FlowPropertyPanel → EdgeInspector). This is the real user
 * flow for setting a condition node branch expression: a condition node compiles
 * to a SmartEngine exclusiveGateway, and every outgoing flow MUST carry a
 * conditionExpression (SmartEngine ignores the bare BPMN `default=` fallback).
 *
 * @xyflow renders, per edge, a wide transparent `.react-flow__edge-interaction`
 * path used for hit-testing; we click that (via the edge group's data-id) so the
 * canvas `onEdgeClick` selects the edge and the inspector mounts.
 */
export async function setEdgeCondition(
  page: Page,
  edgeId: string,
  content: string,
  opts: { isDefault?: boolean } = {},
): Promise<void> {
  await page.locator(`.react-flow__edge[data-id="${edgeId}"]`).waitFor({ state: 'attached' });
  await closeCompactDrawers(page);
  const selectedNodeId = await page.evaluate(() => {
    const store = (window as unknown as { __flowDesignerStore?: any }).__flowDesignerStore;
    return store?.getState?.().selectedNodeId ?? null;
  });
  if (selectedNodeId) {
    await page.locator('.react-flow__pane').click({ position: { x: 5, y: 5 } });
  }
  await page.locator(`.react-flow__edge[data-id="${edgeId}"]`).waitFor({ state: 'attached' });
  await clickEdgeInteractionPath(page, edgeId);
  try {
    await page.waitForFunction(
      (id) => {
        const store = (window as unknown as { __flowDesignerStore?: any }).__flowDesignerStore;
        return store?.getState?.().selectedEdgeId === id;
      },
      edgeId,
      { timeout: 5_000 },
    );
  } catch (error) {
    const debug = await page.evaluate((id) => {
      const store = (window as unknown as { __flowDesignerStore?: any }).__flowDesignerStore;
      const state = store?.getState?.();
      const edge = document.querySelector(`.react-flow__edge[data-id="${id}"]`);
      const clickDebug = (window as unknown as { __lastFlowEdgeClickDebug?: unknown })
        .__lastFlowEdgeClickDebug;
      return {
        selectedNodeId: state?.selectedNodeId ?? null,
        selectedEdgeId: state?.selectedEdgeId ?? null,
        clickDebug,
        edgeClass: edge?.getAttribute('class') ?? null,
        edgeHtml: edge?.outerHTML.slice(0, 600) ?? null,
      };
    }, edgeId);
    throw new Error(`edge click did not select ${edgeId}: ${JSON.stringify(debug)}; ${String(error)}`);
  }
  // The EdgeInspector renders the condition textarea (placeholder "e.g. amount > 1000").
  const condArea = page
    .locator('textarea[placeholder="e.g. amount > 1000"]')
    .first();
  await condArea.waitFor({ state: 'visible', timeout: 5_000 });
  await condArea.fill(content);
  if (opts.isDefault) {
    await page.getByRole('checkbox').first().check();
  }
}

async function clickEdgeInteractionPath(page: Page, edgeId: string): Promise<void> {
  const result = await page.evaluate((id) => {
    const edge = document.querySelector(`.react-flow__edge[data-id="${id}"]`);
    const path =
      edge?.querySelector<SVGPathElement>('.react-flow__edge-interaction') ??
      edge?.querySelector<SVGPathElement>('path');
    if (!edge || !path) return { error: `edge interaction path missing: ${id}` };
    const ctm = path.getScreenCTM();
    if (!ctm) return { error: `edge interaction path has no CTM: ${id}` };
    const length = path.getTotalLength();
    const samples = [0.18, 0.3, 0.42, 0.5, 0.58, 0.7, 0.82].map((ratio) => {
      const point = path.getPointAtLength(length * ratio);
      const x = point.x * ctm.a + point.y * ctm.c + ctm.e;
      const y = point.x * ctm.b + point.y * ctm.d + ctm.f;
      const target = document.elementFromPoint(x, y);
      const targetEdge = target?.closest('.react-flow__edge');
      return {
        ratio,
        x,
        y,
        tag: target?.tagName ?? null,
        className: target?.getAttribute('class') ?? null,
        edgeId: targetEdge?.getAttribute('data-id') ?? null,
      };
    });
    const hit = samples.find((sample) => sample.edgeId === id) ?? samples.find((sample) => sample.tag === 'path');
    const point = hit ?? samples[Math.floor(samples.length / 2)];
    (window as unknown as { __lastFlowEdgeClickDebug?: unknown }).__lastFlowEdgeClickDebug = {
      edgeId: id,
      point,
      samples,
    };
    return { point, samples };
  }, edgeId);

  if ('error' in result && result.error) {
    throw new Error(result.error);
  }
  if (!('point' in result) || !result.point) {
    throw new Error(`edge ${edgeId} did not return a click point`);
  }

  await page.mouse.click(result.point.x, result.point.y);
}

/**
 * Select a node and fill its property-panel fields. `fields` maps configSchema
 * key → value; the writer dispatches per the rendered control type found under
 * [data-testid="prop-field-<key>"]. (Smoke test pins the exact control selectors.)
 */
export async function fillNodeConfig(
  page: Page,
  nodeId: string,
  fields: Record<string, string | string[] | boolean>,
): Promise<void> {
  await closeCompactDrawers(page);
  await page.locator(`[data-testid="flow-node-${nodeId}"]`).click();
  for (const [key, value] of Object.entries(fields)) {
    const field = page.locator(`[data-testid="prop-field-${key}"]`);
    await ensurePropertyFieldVisible(page, field);
    await fillOneField(page, field, value);
  }
}

async function ensurePropertyFieldVisible(page: Page, field: Locator): Promise<void> {
  if (await field.isVisible().catch(() => false)) return;
  await expandCollapsedPropertyGroups(page);
  await field.waitFor({ state: 'visible' });
}

async function expandCollapsedPropertyGroups(page: Page): Promise<void> {
  const toggles = page.locator('[data-testid^="prop-group-toggle-"][aria-expanded="false"]');
  for (let guard = 0; guard < 20; guard += 1) {
    if ((await toggles.count()) === 0) return;
    await toggles.first().click();
  }
  throw new Error('too many collapsed property groups to expand');
}

async function closeCompactDrawers(page: Page): Promise<void> {
  const backdrop = page.locator('[data-testid="flow-drawer-backdrop"]');
  if (await backdrop.isVisible().catch(() => false)) {
    await backdrop.click();
  }
}

/**
 * Drive a single property-panel control. The control type is discovered from the
 * DOM under [data-testid="prop-field-<key>"] (the renderer is
 * PropertyFieldRenderer), so the harness stays schema-agnostic. Handles:
 *   - boolean            → BaseSwitch (checkbox/switch)
 *   - expression         → ExpressionEditor (switch to text mode + formula textarea)
 *   - radix Select       → click trigger button + option (role=option)
 *   - model/resource sel → BaseResourceSelect (type to filter + click option)
 *   - text/number/json   → input/textarea fill
 *   - multiselect array  → open + pick each
 */
async function fillOneField(
  page: Page,
  field: Locator,
  value: string | string[] | boolean,
): Promise<void> {
  if (typeof value === 'boolean') {
    const cb = field.locator('input[type="checkbox"], [role="switch"]').first();
    if (value) await cb.check();
    else await cb.uncheck();
    return;
  }

  // ExpressionEditor: switch to free-text "Expression" mode, then type into the
  // formula textarea (the gateway/condition expression contract is plain text).
  const exprEditor = field.locator('[data-testid="expression-editor"]');
  if (await exprEditor.count()) {
    const textModeBtn = field.locator('[data-testid="mode-text"]');
    if (await textModeBtn.count()) await textModeBtn.click();
    const ta = field.locator('[data-testid="formula-editor-textarea"]').first();
    await ta.waitFor({ state: 'visible' });
    await ta.fill(typeof value === 'string' ? value : (value as string[]).join(', '));
    // Dismiss the $-autocomplete popup if it opened.
    await page.keyboard.press('Escape').catch(() => {});
    return;
  }

  // radix Select (BaseSelect): a button[role=combobox] trigger + listbox options.
  const radixTrigger = field.locator('[role="combobox"]').first();
  if (await radixTrigger.count()) {
    const v = Array.isArray(value) ? value[0] : value;
    await radixTrigger.click();
    await page.getByRole('option', { name: v, exact: false }).first().click();
    return;
  }

  if (Array.isArray(value)) {
    // DependentMultiSelect (e.g. trigger-state-change toStates): a custom div+input
    // that opens a dropdown of <button> option rows (NOT role=option). Its options load
    // async after the parent field (e.g. stateField) is set, so open the control and wait
    // for each option button to appear, then click it by its visible label.
    for (const v of value) {
      await field.click();
      const opt = field.locator('div.absolute button').filter({ hasText: v }).first();
      await opt.waitFor({ state: 'visible', timeout: 5_000 });
      await opt.click();
    }
    return;
  }

  // BaseResourceSelect (model-select / command-select / etc.): a text input with
  // `placeholder` containing "Select..." that filters an async option list
  // rendered as <button> rows inside an absolutely-positioned dropdown. Options
  // render by DISPLAY LABEL (e.g. model displayName), not by code — so the caller
  // passes the label to click. We type it to filter, then click the option row.
  const resourceInput = field.locator('input[placeholder*="Select"]').first();
  if (await resourceInput.count()) {
    await resourceInput.click();
    await resourceInput.fill(value);
    const option = field
      .locator('div.absolute button')
      .filter({ hasText: value })
      .first();
    await option.waitFor({ state: 'visible', timeout: 5_000 });
    await option.click();
    return;
  }

  // Plain BaseInput / BaseTextarea (text / number / json).
  const input = field.locator('input, textarea, [contenteditable="true"]').first();
  if (await input.count()) {
    const tag = await input.evaluate((el) => el.tagName.toLowerCase()).catch(() => '');
    if (tag === 'input' || tag === 'textarea') {
      await input.fill(value);
      return;
    }
  }

  // Last resort: treat as a click-to-open select rendered as a button/combobox.
  await field.click();
  await page.getByRole('option', { name: value, exact: false }).first().click();
}

/**
 * Click the designer Save button and return the created/updated automation pid.
 *
 * The inner FlowDesigner save runs the validation gate first; if the flow is
 * invalid it returns WITHOUT firing the API (no toast). We therefore time-box the
 * wait and, on timeout, surface the live validation result so the failure is a
 * clear "save blocked by validation" rather than an opaque hang.
 */
export async function saveAutomation(page: Page): Promise<{ pid: string }> {
  const respPromise = page
    .waitForResponse(
      (r) =>
        /\/api\/automations(\/[^/]+)?$/.test(r.url()) &&
        ['POST', 'PUT'].includes(r.request().method()),
      { timeout: 15_000 },
    )
    .catch(() => null);
  await clickDesignerSave(page);
  const resp = await respPromise;
  if (!resp) {
    const validation = await page
      .evaluate(() => {
        const store = (window as unknown as { __flowDesignerStore?: any }).__flowDesignerStore;
        return store ? store.getState().validationResult : 'no-store';
      })
      .catch(() => 'eval-failed');
    throw new Error(
      `save did not fire an /api/automations request within 15s — the validation gate ` +
        `likely blocked it. validationResult=${JSON.stringify(validation)}`,
    );
  }
  // Use the HTTP status (always available) as the success signal; reading the
  // body with resp.json() can hang when the page consumes the response stream
  // (observed on PUT updates that do not navigate). On a non-2xx, best-effort the
  // body for a useful message.
  const status = resp.status();
  if (status >= 400) {
    const msg = await resp.text().catch(() => '');
    throw new Error(`save failed (HTTP ${status}): ${msg.slice(0, 300)}`);
  }
  const method = resp.request().method();
  if (method === 'PUT') {
    // update returns the pid in the URL (POST→create returns data.pid in body,
    // but we avoid resp.json() to dodge the stream hang and read it back via API).
    return { pid: resp.url().split('/').pop()! };
  }
  // create (POST): the pid is in the body; read it via a fresh request to dodge
  // the response-stream hang — the create just committed, so list the newest.
  const body = await resp.json().catch(() => null);
  const pid = body?.data?.pid;
  if (!pid) throw new Error(`save (POST) succeeded but pid missing in response`);
  return { pid: String(pid) };
}

export async function clickDesignerSave(page: Page): Promise<void> {
  await page
    .locator('[data-testid="designer-save"], [data-testid="automation-editor-toolbar-btn-save"]')
    .first()
    .click();
}

/**
 * Enable an automation through the real list-page toggle (the only UI enable
 * affordance; the designer has no enable control). The "Enable" button calls
 * POST /api/automations/{pid}/toggle. We wait for that response so the backend
 * deploy step completes before the caller fires the trigger.
 */
export async function enableViaListToggle(page: Page, pid: string): Promise<void> {
  await page.goto('/automations');
  const toggle = page.locator(`[data-testid="btn-toggle-${pid}"]`);
  await toggle.waitFor({ state: 'visible', timeout: 15_000 });
  // The list disables the toggle until hydrated; wait for it to be enabled.
  await expect(toggle).toBeEnabled({ timeout: 10_000 });
  // Verify the toggle POST succeeds at the HTTP layer (catch a server-side
  // failure cleanly), but assert the *enabled* outcome via the UI status badge —
  // reading the response body directly can hang while the list re-renders.
  const respStatus = page
    .waitForResponse(
      (r) => r.url().includes(`/api/automations/${pid}/toggle`) && r.request().method() === 'POST',
      { timeout: 15_000 },
    )
    .then((r) => r.status())
    .catch(() => 0);
  await toggle.click();
  const status = await respStatus;
  if (status && status >= 400) {
    throw new Error(`toggle returned HTTP ${status}`);
  }
  // After enable, the toggle button label flips to "Disable" and the status
  // badge reads "Enabled". Assert the real UI state reflects enabled.
  await expect(page.locator(`[data-testid="status-${pid}"]`)).toContainText(/Enabled|已启用/, {
    timeout: 10_000,
  });
}

// ───────────────────────── API helpers (behavioral / setup) ─────────────────────────

export async function enableViaApi(page: Page, pid: string): Promise<void> {
  const resp = await page.request.post(`/api/automations/${pid}/enable`);
  const body = await resp.json();
  if (String(body.code) !== API_OK) {
    throw new Error(`enable failed: ${body.message || JSON.stringify(body)}`);
  }
}

export async function deleteViaApi(page: Page, pid: string): Promise<void> {
  // Disable first — an enabled automation (especially a scheduled every-second cron)
  // keeps firing, and leaving it enabled lets it pile up scheduler load across runs.
  // Best-effort + idempotent; then delete.
  await page.request.post(`/api/automations/${pid}/disable`).catch(() => {});
  await page.request.delete(`/api/automations/${pid}`).catch(() => {});
}

export interface NodeStatus {
  nodeId: string;
  status: string;
  errorMessage?: string;
}

/** Find the latest automation log started at/after `notBefore` (epoch ms). */
export async function latestLog(
  page: Page,
  automationPid: string,
  notBefore: number,
): Promise<{ id: number; status: string; errorMessage?: string } | null> {
  const resp = await page.request.get(`/api/automations/${automationPid}/logs`, {
    params: { limit: 20 },
  });
  if (!resp.ok()) return null;
  const rows: any[] = (await resp.json())?.data ?? [];
  const matched = rows.filter(
    (r) => (r.startedAt ? new Date(r.startedAt).getTime() : 0) >= notBefore - 5_000,
  );
  if (!matched.length) return null;
  matched.sort((a, b) => new Date(b.startedAt).getTime() - new Date(a.startedAt).getTime());
  return matched[0];
}

/** Poll node-statuses for a log until all reach a terminal state or timeout. */
export async function pollNodeStatuses(
  page: Page,
  logId: number,
  timeoutMs = 30_000,
): Promise<NodeStatus[]> {
  const deadline = Date.now() + timeoutMs;
  let last: NodeStatus[] = [];
  while (Date.now() < deadline) {
    const resp = await page.request.get(`/api/automation/executions/by-log/${logId}/node-statuses`);
    if (resp.ok()) {
      last = ((await resp.json())?.data ?? []) as NodeStatus[];
      if (last.length && last.every((s) => ['completed', 'failed', 'skipped'].includes(s.status))) {
        return last;
      }
    }
    await delay(1_000);
  }
  return last;
}
