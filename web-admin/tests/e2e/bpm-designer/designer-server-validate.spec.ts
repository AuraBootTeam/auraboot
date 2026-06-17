import { test, expect } from '@playwright/test';

/**
 * G-B1 (frontend wiring) golden — the Validate toolbar action calls the
 * server-side /validate endpoint and reports the result.
 *
 * handleValidate now runs client structural validation first (node highlight),
 * then POSTs the designerJson to /api/bpm/process-definitions/validate so
 * converter-level errors surface before Deploy. This spec asserts that clicking
 * Validate on a valid graph issues the real server request and shows success.
 *
 * Verified live against a host-first stack (auraboot slot 44) on 2026-06-17.
 */

test('G-B1 — Validate calls the server /validate endpoint for a valid graph', async ({ page }) => {
  await page.goto('/bpmn-designer', { waitUntil: 'domcontentloaded' });
  await page.locator('.react-flow').first().waitFor({ state: 'visible', timeout: 15_000 });
  await page.waitForFunction(() => !!(window as any).__bpmDesigner, { timeout: 8_000 });

  // valid linear graph (passes client validation -> server call proceeds)
  await page.evaluate(() => {
    const d = (window as any).__bpmDesigner;
    (window as any).__bpmnDesignerStore.getState().reset();
    d.addNode({ id: 's', type: 'startEvent', position: { x: 100, y: 100 }, data: { label: 'Start' } });
    d.addNode({ id: 'u', type: 'userTask', position: { x: 320, y: 100 }, data: { label: 'Approve' } });
    d.addNode({ id: 'e', type: 'endEvent', position: { x: 540, y: 100 }, data: { label: 'End' } });
    d.connect('s', 'u');
    d.connect('u', 'e');
  });

  // clicking Validate must issue the real POST /validate request
  const respPromise = page.waitForResponse(
    (r) => /\/api\/bpm\/process-definitions\/validate$/.test(r.url()) && r.request().method() === 'POST',
    { timeout: 15_000 },
  );
  await page.getByTestId('bpmn-btn-validate').click();
  const resp = await respPromise;
  expect(resp.ok(), 'server validate responded OK').toBe(true);

  const body = await resp.json();
  // backend ApiResponse: { code, data: { valid, errors } }
  expect(body?.data?.valid, 'valid graph validates server-side').toBe(true);
});
