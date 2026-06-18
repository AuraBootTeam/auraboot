import { test, expect } from '@playwright/test';

/**
 * G-T5 residual golden — real property-panel form editing for non-userTask node types.
 *
 * The userTask real-form binding is covered by designer-property-edit.spec.ts. The existing
 * serviceTask / callActivity specs configure nodes via the __bpmnDesignerStore hook (bypassing
 * the real form). This spec drags real serviceTask / callActivity nodes, edits real
 * property-panel fields, and asserts each edit lands in the store node's data.config — the real
 * UI → store binding for those node types. Verified live against a host-first stack
 * (auraboot slot 51) at SmartEngine 4.0.2.
 */

async function dragNode(page: import('@playwright/test').Page, paletteType: string) {
  await page.evaluate((type) => {
    const item = document.querySelector(`[data-testid="bpmn-palette-item-${type}"]`);
    const pane = document.querySelector('.react-flow__pane') || document.querySelector('.react-flow');
    const dt = new DataTransfer();
    const r = (pane as HTMLElement).getBoundingClientRect();
    item!.dispatchEvent(new DragEvent('dragstart', { bubbles: true, cancelable: true, dataTransfer: dt }));
    pane!.dispatchEvent(new DragEvent('dragover', { bubbles: true, cancelable: true, dataTransfer: dt, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2 }));
    pane!.dispatchEvent(new DragEvent('drop', { bubbles: true, cancelable: true, dataTransfer: dt, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2 }));
  }, paletteType);
}

async function selectFirst(page: import('@playwright/test').Page, nodeType: string): Promise<string> {
  return page.evaluate((type) => {
    const s = (window as any).__bpmnDesignerStore.getState();
    const n = s.nodes.find((x: any) => x.data?.type === type);
    s.setSelectedNode(n.id);
    return n.id;
  }, nodeType);
}

async function nodeConfig(page: import('@playwright/test').Page, id: string) {
  return page.evaluate((nid) => {
    const n = (window as any).__bpmnDesignerStore.getState().nodes.find((x: any) => x.id === nid);
    return n?.data?.config ?? {};
  }, id);
}

test.beforeEach(async ({ page }) => {
  await page.goto('/bpmn-designer', { waitUntil: 'domcontentloaded' });
  await page.locator('.react-flow').first().waitFor({ state: 'visible', timeout: 15_000 });
  await page.waitForFunction(() => !!(window as any).__bpmnDesignerStore, { timeout: 8_000 });
  await page.evaluate(() => (window as any).__bpmnDesignerStore.getState().reset());
});

test('G-T5 — serviceTask property panel real edits bind to the store node config', async ({ page }) => {
  await dragNode(page, 'serviceTask');
  const nodeId = await selectFirst(page, 'serviceTask');
  await page.getByTestId('servicetask-service-type').waitFor({ state: 'visible', timeout: 8_000 });

  // real form: serviceType=command exposes commandCode; fill it; toggle async
  await page.getByTestId('servicetask-service-type').selectOption('command');
  await page.getByTestId('servicetask-command-code').fill('sl:approve');
  await page.getByTestId('servicetask-async').check();

  const cfg = await nodeConfig(page, nodeId);
  expect(cfg.serviceType, 'serviceType bound').toBe('command');
  expect(cfg.commandCode, 'commandCode bound').toBe('sl:approve');
  expect(cfg.async, 'async bound').toBe(true);

  await page.screenshot({ path: 'test-results/gt5-servicetask-property-edit.png' });
});

test('G-T5 — serviceTask http serviceType exposes + binds serviceUrl', async ({ page }) => {
  await dragNode(page, 'serviceTask');
  const nodeId = await selectFirst(page, 'serviceTask');
  await page.getByTestId('servicetask-service-type').waitFor({ state: 'visible', timeout: 8_000 });

  await page.getByTestId('servicetask-service-type').selectOption('http');
  await page.getByTestId('servicetask-service-url').fill('https://example.com/hook');

  const cfg = await nodeConfig(page, nodeId);
  expect(cfg.serviceType, 'serviceType bound').toBe('http');
  expect(cfg.serviceUrl, 'serviceUrl bound').toBe('https://example.com/hook');
});

test('G-T5 — callActivity property panel real edits bind to the store node config', async ({ page }) => {
  await dragNode(page, 'callActivity');
  const nodeId = await selectFirst(page, 'callActivity');
  await page.getByTestId('callactivity-description').waitFor({ state: 'visible', timeout: 8_000 });

  // description is a standalone fillable field. (calledProcessKey is a ProcessPicker that lists
  // deployed processes — exercised by designer-callactivity.spec via the store hook; the real
  // picker flow needs seeded processes, out of scope for this binding golden.)
  await page.getByTestId('callactivity-description').fill('Invoke the sub-approval process');

  const cfg = await nodeConfig(page, nodeId);
  expect(cfg.description, 'description bound').toBe('Invoke the sub-approval process');

  await page.screenshot({ path: 'test-results/gt5-callactivity-property-edit.png' });
});
