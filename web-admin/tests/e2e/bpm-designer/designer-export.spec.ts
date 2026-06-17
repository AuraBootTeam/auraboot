import { test, expect } from '@playwright/test';

/**
 * G-T2 golden — Export toolbar action downloads a designerJson file.
 *
 * The Export button (toolbar) was previously untested (gap G-T2). It is a
 * pure-frontend Blob download of the current designerJson. This spec builds a
 * graph, clicks the real Export button, captures the browser download, and
 * asserts the filename (<key>.json) and that the file content contains the
 * graph's nodes/edges.
 *
 * Verified live against a host-first stack (auraboot slot 43) on 2026-06-17.
 * Auth via project storageState; direct nav to /bpmn-designer (static route).
 */

test('G-T2 — Export downloads a designerJson file with the graph content', async ({ page }) => {
  await page.goto('/bpmn-designer', { waitUntil: 'domcontentloaded' });
  await page.locator('.react-flow').first().waitFor({ state: 'visible', timeout: 15_000 });
  await page.waitForFunction(() => !!(window as any).__bpmDesigner, { timeout: 8_000 });

  // build a small valid graph
  await page.evaluate(() => {
    const d = (window as any).__bpmDesigner;
    (window as any).__bpmnDesignerStore.getState().reset();
    d.addNode({ id: 's', type: 'startEvent', position: { x: 120, y: 100 }, data: { label: 'Start' } });
    d.addNode({ id: 'u', type: 'userTask', position: { x: 340, y: 100 }, data: { label: 'Approve' } });
    d.addNode({ id: 'e', type: 'endEvent', position: { x: 560, y: 100 }, data: { label: 'End' } });
    d.connect('s', 'u');
    d.connect('u', 'e');
  });

  // click the real Export button and capture the download
  const downloadPromise = page.waitForEvent('download', { timeout: 10_000 });
  await page.getByTestId('bpmn-btn-export').click();
  const download = await downloadPromise;

  // filename ends with .json
  const name = download.suggestedFilename();
  expect(name, 'export filename').toMatch(/\.json$/);

  // content is a parseable designerJson carrying the graph
  const stream = await download.createReadStream();
  const chunks: Buffer[] = [];
  for await (const c of stream) chunks.push(Buffer.from(c));
  const text = Buffer.concat(chunks).toString('utf-8');
  expect(text.length, 'export file non-empty').toBeGreaterThan(0);
  const json = JSON.parse(text);
  const ids = (json.nodes || []).map((n: any) => n.id);
  expect(ids).toEqual(expect.arrayContaining(['s', 'u', 'e']));
  expect((json.edges || []).length, 'edges exported').toBeGreaterThanOrEqual(2);
});
