import { test, expect } from '@playwright/test';

/**
 * G-T5 golden — real property-panel form editing reflects into the store.
 *
 * The existing golden suite configures nodes via the __bpmDesigner.configureNode
 * test hook, bypassing the real property-panel form inputs (gap G-T5). This spec
 * drags a real UserTask onto the canvas, selects it, and edits real property-panel
 * fields (description / priority / skipable), asserting each edit lands in the
 * store node's data.config — the real UI → store binding.
 *
 * Verified live against a host-first stack (auraboot slot 44) on 2026-06-17.
 * Auth via project storageState; direct nav to /bpmn-designer (static route).
 */

test('G-T5 — property panel real edits bind to the store node config', async ({ page }) => {
  await page.goto('/bpmn-designer', { waitUntil: 'domcontentloaded' });
  await page.locator('.react-flow').first().waitFor({ state: 'visible', timeout: 15_000 });
  await page.waitForFunction(() => !!(window as any).__bpmnDesignerStore, { timeout: 8_000 });
  await page.evaluate(() => (window as any).__bpmnDesignerStore.getState().reset());

  // real palette drag: drop a UserTask onto the canvas
  await page.evaluate(() => {
    const item = document.querySelector('[data-testid="bpmn-palette-item-userTask"]');
    const pane = document.querySelector('.react-flow__pane') || document.querySelector('.react-flow');
    const dt = new DataTransfer();
    const r = (pane as HTMLElement).getBoundingClientRect();
    item!.dispatchEvent(new DragEvent('dragstart', { bubbles: true, cancelable: true, dataTransfer: dt }));
    pane!.dispatchEvent(new DragEvent('dragover', { bubbles: true, cancelable: true, dataTransfer: dt, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2 }));
    pane!.dispatchEvent(new DragEvent('drop', { bubbles: true, cancelable: true, dataTransfer: dt, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2 }));
  });

  // select the node so the property panel renders the UserTask editor
  const nodeId: string = await page.evaluate(() => {
    const s = (window as any).__bpmnDesignerStore.getState();
    const n = s.nodes.find((x: any) => x.data?.type === 'userTask');
    s.setSelectedNode(n.id);
    return n.id;
  });
  await page.getByTestId('usertask-description').waitFor({ state: 'visible', timeout: 8_000 });

  // --- real property-panel form edits ---
  await page.getByTestId('usertask-description').fill('Manager approval step');
  await page.getByTestId('usertask-priority').fill('80');
  const skipable = page.getByTestId('usertask-skipable');
  await skipable.check();

  // --- assert the edits bound to the store node's data.config ---
  const cfg = await page.evaluate((id) => {
    const n = (window as any).__bpmnDesignerStore.getState().nodes.find((x: any) => x.id === id);
    return n?.data?.config ?? {};
  }, nodeId);

  expect(cfg.description, 'description bound').toBe('Manager approval step');
  expect(String(cfg.priority), 'priority bound').toBe('80');
  expect(cfg.skipable, 'skipable bound').toBe(true);

  await page.screenshot({ path: 'test-results/gt5-property-edit.png' });
});
