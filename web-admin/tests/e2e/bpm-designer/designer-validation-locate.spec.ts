import { test, expect } from '@playwright/test';

/**
 * G-U4 golden — validation banner error rows are clickable to locate the node.
 *
 * Complements G-U1 (canvas red-ring highlight): clicking a node-bound error in
 * the validation banner selects the offending node (setSelectedNode), so the
 * user jumps straight to it (selected ring + property panel) instead of hunting.
 *
 * Verified live against a host-first stack (auraboot slot 44) on 2026-06-17.
 */

test('G-U4 — clicking a banner error selects/locates the offending node', async ({ page }) => {
  await page.goto('/bpmn-designer', { waitUntil: 'domcontentloaded' });
  await page.locator('.react-flow').first().waitFor({ state: 'visible', timeout: 15_000 });
  await page.waitForFunction(() => !!(window as any).__bpmDesigner, { timeout: 8_000 });

  // invalid graph: exclusive gateway with a missing-condition outgoing flow -> error carries nodeId=gw1
  await page.evaluate(() => {
    const d = (window as any).__bpmDesigner;
    (window as any).__bpmnDesignerStore.getState().reset();
    d.addNode({ id: 'start1', type: 'startEvent', position: { x: 100, y: 80 }, data: { label: 'Start' } });
    d.addNode({ id: 'gw1', type: 'exclusiveGateway', position: { x: 300, y: 80 }, data: { label: 'Choose' } });
    d.addNode({ id: 'task1', type: 'userTask', position: { x: 500, y: 80 }, data: { label: 'Review' } });
    d.addNode({ id: 'end1', type: 'endEvent', position: { x: 700, y: 80 }, data: { label: 'End' } });
    d.connect('start1', 'gw1');
    d.connect('gw1', 'task1');
    d.connect('task1', 'end1');
  });

  await page.getByTestId('bpmn-btn-validate').click();

  // a node-bound error row is rendered as a clickable locate button
  const locate = page.locator('[data-testid="bpmn-validation-error-locate"][data-node-id="gw1"]').first();
  await expect(locate).toBeVisible({ timeout: 5_000 });

  await locate.click();

  // clicking selects the offending node in the store
  const selected = await page.evaluate(() =>
    (window as any).__bpmnDesignerStore.getState().selectedNodeId,
  );
  expect(selected, 'banner click selects the offending node').toBe('gw1');
});
