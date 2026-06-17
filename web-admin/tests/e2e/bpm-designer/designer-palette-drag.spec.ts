import { test, expect } from '@playwright/test';

/**
 * G-T1 golden — real palette drag-and-drop creates a node on the canvas.
 *
 * The existing bpm-designer golden suite builds graphs via the window.__bpmDesigner
 * test hook, which bypasses the real palette → canvas drag path entirely (the
 * gap flagged in docs/backlog/2026-06-17-bpmn-designer-golden-gap.md G-T1). This
 * spec exercises the REAL HTML5 drag handlers:
 *   - dispatch a real `dragstart` on the actual draggable palette item, so the
 *     designer's onDragStart populates dataTransfer('application/reactflow'),
 *   - dispatch `dragover` + `drop` on the real ReactFlow pane, so the canvas
 *     onDrop reads the payload and creates the node.
 * Playwright's mouse-based dragTo cannot trigger HTML5 dataTransfer DnD, so the
 * dispatchEvent technique is the reliable way to drive native palette drag.
 *
 * Verified live against a host-first stack (auraboot slot 43) on 2026-06-17.
 * Auth via project storageState; direct nav to /bpmn-designer (static route).
 */

const PALETTE_DRAGS: Array<{ type: string; testId: string }> = [
  { type: 'userTask', testId: 'bpmn-palette-item-userTask' },
  { type: 'exclusiveGateway', testId: 'bpmn-palette-item-exclusiveGateway' },
];

test('G-T1 — real palette HTML5 drag drops nodes onto the canvas', async ({ page }) => {
  await page.goto('/bpmn-designer', { waitUntil: 'domcontentloaded' });
  await page.locator('.react-flow').first().waitFor({ state: 'visible', timeout: 15_000 });
  await page.waitForFunction(() => !!(window as any).__bpmnDesignerStore, { timeout: 8_000 });

  // start clean
  await page.evaluate(() => (window as any).__bpmnDesignerStore.getState().reset());

  for (let i = 0; i < PALETTE_DRAGS.length; i++) {
    const { type, testId } = PALETTE_DRAGS[i];
    const result = await page.evaluate(
      ({ testId, offset }) => {
        const item = document.querySelector(`[data-testid="${testId}"][draggable="true"]`)
          || document.querySelector(`[data-testid="${testId}"]`);
        const pane = document.querySelector('.react-flow__pane')
          || document.querySelector('.react-flow__renderer')
          || document.querySelector('.react-flow');
        if (!item || !pane) return { ok: false, reason: 'element-missing' };
        const dt = new DataTransfer();
        const r = (pane as HTMLElement).getBoundingClientRect();
        const x = r.left + r.width / 2 + offset;
        const y = r.top + r.height / 2;
        // real dragstart -> designer onDragStart fills dataTransfer
        item.dispatchEvent(new DragEvent('dragstart', { bubbles: true, cancelable: true, dataTransfer: dt }));
        const payload = dt.getData('application/reactflow');
        // real dragover + drop -> canvas onDrop creates the node
        pane.dispatchEvent(new DragEvent('dragover', { bubbles: true, cancelable: true, dataTransfer: dt, clientX: x, clientY: y }));
        pane.dispatchEvent(new DragEvent('drop', { bubbles: true, cancelable: true, dataTransfer: dt, clientX: x, clientY: y }));
        return { ok: true, payload };
      },
      { testId, offset: (i - 0.5) * 160 },
    );
    expect(result.ok, `palette item ${testId} + pane must exist`).toBe(true);
    // onDragStart must have populated the real dataTransfer payload with this type
    expect(result.payload, `dataTransfer for ${type}`).toContain(type);
  }

  // assert the real onDrop created both nodes in the store
  const types = await page.evaluate(() =>
    (window as any).__bpmnDesignerStore.getState().nodes.map((n: any) => n.data?.type),
  );
  expect(types).toContain('userTask');
  expect(types).toContain('exclusiveGateway');

  // and they are rendered on the canvas
  const renderedCount = await page.locator('.react-flow__node').count();
  expect(renderedCount).toBeGreaterThanOrEqual(2);

  await page.screenshot({ path: 'test-results/gt1-palette-drag.png' });
});
