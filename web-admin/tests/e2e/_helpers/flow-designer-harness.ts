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
 *   save button  : [data-testid="designer-save"]
 *   (no in-designer enable control — enable via the list page btn-toggle-<pid>,
 *    or the /enable API for behavioral setup.)
 *
 * NOTE: the UI helpers (drag/connect/config/save) are smoke-verified by the
 * Phase-0 harness smoke test before any Layer A case relies on them.
 */
import type { Page, Locator } from '@playwright/test';

export const DATA_TRANSFER_KEY = 'application/flow-node';
export const API_OK = '0';

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
  await page.locator(paletteSel).waitFor({ state: 'visible' });

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
  return String(await newId.jsonValue());
}

/** Current node ids on the canvas (by flow-node-<id> testid). */
export async function currentNodeIds(page: Page): Promise<string[]> {
  return page.$$eval('[data-testid^="flow-node-"]', (els) =>
    els.map((el) => el.getAttribute('data-testid')!.replace('flow-node-', '')),
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
): Promise<void> {
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
  await page.locator(`[data-testid="flow-node-${nodeId}"]`).click();
  for (const [key, value] of Object.entries(fields)) {
    const field = page.locator(`[data-testid="prop-field-${key}"]`);
    await field.waitFor({ state: 'visible' });
    if (typeof value === 'boolean') {
      const cb = field.locator('input[type="checkbox"], [role="switch"]').first();
      if (value) await cb.check();
      else await cb.uncheck();
    } else if (Array.isArray(value)) {
      // multiselect — open + pick each (selector pinned during smoke).
      for (const v of value) {
        await field.click();
        await page.getByRole('option', { name: v }).click();
      }
    } else {
      const input = field.locator('input, textarea, [contenteditable="true"]').first();
      const tag = await input.evaluate((el) => el.tagName.toLowerCase()).catch(() => '');
      if (tag === 'input' || tag === 'textarea') {
        await input.fill(value);
      } else {
        // a select-style control rendered as a button/combobox
        await field.click();
        await page.getByRole('option', { name: value }).click();
      }
    }
  }
}

/** Click the designer Save button and return the created/updated automation pid. */
export async function saveAutomation(page: Page): Promise<{ pid: string }> {
  const respPromise = page.waitForResponse(
    (r) => /\/api\/automations(\/[^/]+)?$/.test(r.url()) && ['POST', 'PUT'].includes(r.request().method()),
  );
  await page.locator('[data-testid="designer-save"]').click();
  const resp = await respPromise;
  const body = await resp.json();
  if (String(body.code) !== API_OK) {
    throw new Error(`save failed: ${body.message || JSON.stringify(body)}`);
  }
  // create returns data.pid; update returns the pid in the URL.
  const pid = body?.data?.pid ?? resp.url().split('/').pop()!;
  return { pid: String(pid) };
}

/** Enable an automation through the list page toggle (real UI; no in-designer control). */
export async function enableViaListToggle(page: Page, pid: string): Promise<void> {
  await page.goto('/automations');
  await page.locator(`[data-testid="btn-toggle-${pid}"]`).click();
  // wait for the status testid to reflect enabled
  await page.locator(`[data-testid="status-${pid}"]`).waitFor({ state: 'visible' });
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
    await page.waitForTimeout(1_000);
  }
  return last;
}
