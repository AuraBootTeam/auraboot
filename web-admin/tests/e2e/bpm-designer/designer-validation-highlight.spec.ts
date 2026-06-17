import { test, expect } from '@playwright/test';

/**
 * G-U1 golden — canvas node-level validation highlight.
 *
 * After clicking Validate (or Save) on an invalid graph, the store's
 * validationResult.errors[] carry a nodeId. The designer must highlight the
 * offending nodes ON THE CANVAS (red ring for error) — not just show a banner.
 * This spec drives the real Validate toolbar button and asserts the error nodes
 * carry `ring-red-500` while error-free nodes do not.
 *
 * Verified live during the 2026-06-17 BPMN golden session (screenshots
 * bpmn-golden-02-validate-highlight.png + DOM ring assertion). This spec
 * codifies that as a regression. It relies on the project storageState for
 * auth and navigates directly to /bpmn-designer (the designer is a static
 * route; this focused stack does not import the BPM plugin menu, so sidebar
 * nav is unavailable — documented deviation from the sidebar-only nav
 * convention for this component-level golden).
 */

test('G-U1 — Validate highlights error nodes with a red ring on the canvas', async ({ page }) => {
  // --- open the designer (static route; auth via project storageState) ---
  await page.goto('/bpmn-designer', { waitUntil: 'domcontentloaded' });
  await page.locator('.react-flow, [data-testid="bpmn-page-title"]').first()
    .waitFor({ state: 'visible', timeout: 15_000 });
  await page.waitForFunction(() => !!(window as any).__bpmDesigner, { timeout: 8_000 });

  // --- build an INVALID graph via the designer test hook ---
  //  - start1 with no outgoing edge -> 'start_no_outgoing' (error, nodeId=start1)
  //  - gw1 exclusive gateway with <2 outgoing + a flow missing condition -> errors on gw1
  await page.evaluate(() => {
    const d = (window as any).__bpmDesigner;
    (window as any).__bpmnDesignerStore.getState().reset();
    d.addNode({ id: 'start1', type: 'startEvent', position: { x: 120, y: 80 }, data: { label: 'Start' } });
    d.addNode({ id: 'gw1', type: 'exclusiveGateway', position: { x: 320, y: 80 }, data: { label: 'Choose' } });
    d.addNode({ id: 'task1', type: 'userTask', position: { x: 520, y: 80 }, data: { label: 'Review' } });
    d.addNode({ id: 'end1', type: 'endEvent', position: { x: 720, y: 80 }, data: { label: 'End' } });
    d.connect('gw1', 'task1'); // exclusive-gateway edge WITHOUT condition -> error on gw1
    d.connect('task1', 'end1');
  });

  // --- click the real Validate toolbar button ---
  await page.getByTestId('bpmn-btn-validate').click();

  // --- assert: validation failed and error nodes carry the red ring (G-U1) ---
  const result = await page.evaluate(() => {
    const vr = (window as any).__bpmnDesignerStore.getState().validationResult;
    const ring = (id: string) => {
      const el = document.querySelector(`.react-flow__node[data-id="${id}"]`);
      const inner = (el?.querySelector('[class*="ring-"]') as HTMLElement) || (el as HTMLElement);
      return /ring-red-500/.test(inner?.className || '');
    };
    return {
      valid: vr?.valid,
      start1Red: ring('start1'),
      gw1Red: ring('gw1'),
      task1Red: ring('task1'),
      end1Red: ring('end1'),
    };
  });

  expect(result.valid, 'graph should be invalid').toBe(false);
  expect(result.start1Red, 'start1 (no outgoing) should have red ring').toBe(true);
  expect(result.gw1Red, 'gw1 (gateway errors) should have red ring').toBe(true);
  // error-free nodes must NOT be red-ringed
  expect(result.task1Red, 'task1 has no error -> no red ring').toBe(false);
  expect(result.end1Red, 'end1 has no error -> no red ring').toBe(false);

  await page.screenshot({ path: 'test-results/gu1-validation-highlight.png' });
});
