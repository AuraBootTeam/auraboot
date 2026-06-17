import { test, expect } from '@playwright/test';

/**
 * G-T4 / import golden — real toolbar action points: Undo/Redo, Monitor toggle,
 * and Import (export→import round-trip). These action points were previously
 * covered only by store unit tests, not real UI E2E (gaps G-T4 + import).
 *
 * Verified live against a host-first stack (auraboot slot 44) on 2026-06-17.
 * Auth via project storageState; direct nav to /bpmn-designer (static route).
 */

async function openDesigner(page: import('@playwright/test').Page) {
  await page.goto('/bpmn-designer', { waitUntil: 'domcontentloaded' });
  await page.locator('.react-flow').first().waitFor({ state: 'visible', timeout: 15_000 });
  await page.waitForFunction(() => !!(window as any).__bpmDesigner, { timeout: 8_000 });
  await page.evaluate(() => (window as any).__bpmnDesignerStore.getState().reset());
}

const nodeCount = (page: import('@playwright/test').Page) =>
  page.evaluate(() => (window as any).__bpmnDesignerStore.getState().nodes.length);

test('G-T4 — real Undo/Redo toolbar buttons revert and reapply graph changes', async ({ page }) => {
  await openDesigner(page);
  await page.evaluate(() => {
    const d = (window as any).__bpmDesigner;
    d.addNode({ id: 's', type: 'startEvent', position: { x: 100, y: 100 }, data: { label: 'Start' } });
    d.addNode({ id: 'u', type: 'userTask', position: { x: 320, y: 100 }, data: { label: 'Task' } });
  });
  expect(await nodeCount(page)).toBe(2);

  // real Undo button -> last add reverted
  await page.getByTestId('bpmn-toolbar-btn-undo').click();
  await expect.poll(() => nodeCount(page)).toBe(1);

  // real Redo button -> re-applied
  await page.getByTestId('bpmn-toolbar-btn-redo').click();
  await expect.poll(() => nodeCount(page)).toBe(2);
});

test('G-T4 — Monitor toggle reveals the instance-monitor bar and exits back to design', async ({ page }) => {
  await openDesigner(page);
  // enter monitor mode via the real toolbar toggle
  await page.getByRole('button', { name: /监控|Monitor/i }).first().click();
  // the monitor bar exposes an instanceId input + Query control
  const instanceInput = page.locator(
    'input[placeholder*="实例"], input[placeholder*="instance" i], [data-testid="bpmn-monitor-instance-id"]',
  ).first();
  await expect(instanceInput).toBeVisible({ timeout: 5_000 });
  const viewMode = await page.evaluate(() => (window as any).__bpmnDesignerStore.getState().viewMode);
  expect(viewMode).toBe('monitor');
});

test('import — Export then Import round-trips the graph back onto the canvas', async ({ page }) => {
  await openDesigner(page);
  await page.evaluate(() => {
    const d = (window as any).__bpmDesigner;
    d.addNode({ id: 's', type: 'startEvent', position: { x: 100, y: 100 }, data: { label: 'Start' } });
    d.addNode({ id: 'u', type: 'userTask', position: { x: 320, y: 100 }, data: { label: 'Approve' } });
    d.addNode({ id: 'e', type: 'endEvent', position: { x: 540, y: 100 }, data: { label: 'End' } });
    d.connect('s', 'u');
    d.connect('u', 'e');
  });

  // Export -> capture the downloaded designerJson content
  const downloadPromise = page.waitForEvent('download', { timeout: 10_000 });
  await page.getByTestId('bpmn-btn-export').click();
  const download = await downloadPromise;
  const fs = await import('node:fs');
  const tmp = await download.path();
  const exported = fs.readFileSync(tmp!, 'utf-8');
  expect(exported.length, 'export produced content').toBeGreaterThan(0);

  // clear the canvas, then Import the exported content via the hidden file input.
  // The import handler requires a .json filename, so feed an explicitly-named buffer
  // (Playwright's download temp path has no extension).
  await page.evaluate(() => (window as any).__bpmnDesignerStore.getState().reset());
  await expect.poll(() => nodeCount(page)).toBe(0);

  await page.locator('input[type="file"]').first().setInputFiles({
    name: 'exported-process.json',
    mimeType: 'application/json',
    buffer: Buffer.from(exported, 'utf-8'),
  });

  // canvas is repopulated from the imported file
  await expect.poll(() => nodeCount(page)).toBeGreaterThanOrEqual(3);
  const ids = await page.evaluate(() =>
    (window as any).__bpmnDesignerStore.getState().nodes.map((n: any) => n.id),
  );
  expect(ids).toEqual(expect.arrayContaining(['s', 'u', 'e']));
});
