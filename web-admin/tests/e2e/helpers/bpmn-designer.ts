/**
 * BPMN Designer E2E helpers
 *
 * Provides programmatic seeding of a minimal valid BPMN process (StartEvent →
 * EndEvent with a connecting edge) so tests can exercise Save/Deploy flows
 * without simulating fragile react-flow drag-and-drop interactions.
 *
 * Usage:
 *   await page.waitForURL(/bpmn-designer/);
 *   await expect(page.locator('.react-flow')).toBeVisible();
 *   await drawMinimalBPMN(page);
 *
 * This relies on `window.__bpmnDesignerStore` being exposed by the designer
 * (see BPMNDesigner.tsx). The store type is intentionally `any` here to avoid
 * pulling app types into the test harness.
 */
import type { Page } from '@playwright/test';

/**
 * Seed a minimal valid process: one StartEvent + one EndEvent + one edge.
 * Asserts the store is available. Returns the ids assigned so callers can
 * chain further assertions if needed.
 */
export async function drawMinimalBPMN(
  page: Page,
): Promise<{ startId: string; endId: string; edgeId: string }> {
  // Wait until the store is exposed. The designer mounts it in a useEffect
  // right after first render, so this should be effectively immediate.
  await page.waitForFunction(
    () => Boolean((window as any).__bpmnDesignerStore),
    undefined,
    { timeout: 5_000 },
  );

  return await page.evaluate(() => {
    const store = (window as any).__bpmnDesignerStore;
    if (!store) throw new Error('BPMN designer store not exposed on window');
    const state = store.getState();

    const startId = `start-${Date.now()}`;
    const endId = `end-${Date.now() + 1}`;
    const edgeId = `flow-${Date.now() + 2}`;

    state.addNode({
      id: startId,
      type: 'startEvent',
      position: { x: 100, y: 200 },
      data: { type: 'startEvent', label: 'Start' },
    });
    state.addNode({
      id: endId,
      type: 'endEvent',
      position: { x: 400, y: 200 },
      data: { type: 'endEvent', label: 'End' },
    });
    state.addEdge({
      id: edgeId,
      source: startId,
      target: endId,
      type: 'smoothstep',
      data: { label: '' },
    });

    return { startId, endId, edgeId };
  });
}
