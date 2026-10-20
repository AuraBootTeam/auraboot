import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { isAbsolute, join } from 'node:path';
import { test, expect, type Page, type TestInfo } from '@playwright/test';
import { navigateToMenuByClick } from '../helpers';

interface NodeDefinition {
  id: string;
  type: string;
  position: { x: number; y: number };
  data: Record<string, unknown>;
}
interface EdgeDefinition {
  id: string;
  source: string;
  target: string;
  data?: Record<string, unknown>;
}
interface Graph {
  key?: string;
  name?: string;
  category?: string;
  id?: string;
  nodes: NodeDefinition[];
  edges: EdgeDefinition[];
  aura?: Record<string, unknown>;
}
test.use({ locale: 'zh-CN', viewport: { width: 1680, height: 1000 } });
test.setTimeout(180_000);
const toolbar = (page: Page) => page.getByTestId('bpmn-toolbar').filter({ visible: true });
async function processMenu(page: Page) {
  await navigateToMenuByClick(page, ['流程管理', '流程定义']);
  await expect(page).toHaveURL(/\/p\/bpm_process_management$/);
}
async function capture(page: Page, info: TestInfo, name: string) {
  await expect(page.locator('main')).not.toContainText(/Page Unavailable|Internal Server Error/);
  await expect(page.getByTestId('toast-stack').getByRole('alert')).toHaveCount(0, {timeout:15000});
  if (!(await page.getByTestId('bpmn-save-dialog-panel').isVisible()) &&
      !(await page.getByTestId('bpmn-drawer-backdrop').isVisible())) {
    await page.getByRole('button', {name:/fit view/i}).click();
  }
  await info.attach(name, {
    body: await page.screenshot({ fullPage: true }),
    contentType: 'image/png',
  });
}
async function importFile(page: Page, content: string, name: string) {
  const chooser = page.waitForEvent('filechooser');
  await toolbar(page).getByTestId('bpmn-btn-import').click();
  await (
    await chooser
  ).setFiles({ name, mimeType: 'application/json', buffer: Buffer.from(content) });
}
async function exportFile(page: Page, key: string, info: TestInfo, name: string) {
  const event = page.waitForEvent('download');
  await toolbar(page).getByTestId('bpmn-btn-export').click();
  const download = await event;
  expect(download.suggestedFilename()).toBe(`${key}.json`);
  expect(await download.failure()).toBeNull();
  const bytes = await readFile((await download.path())!);
  expect(bytes.byteLength).toBeGreaterThan(0);
  const graph = JSON.parse(bytes.toString('utf8')) as Graph;
  expect(graph.key).toBe(key);
  await info.attach(name, { body: bytes, contentType: 'application/json' });
  return graph;
}
function semantics(graph: Graph) {
  return {
    nodes: graph.nodes
      .map((node) => ({ id: node.id, type: node.type, position: node.position, data: node.data }))
      .sort((a, b) => a.id.localeCompare(b.id)),
    edges: graph.edges
      .map((edge) => ({
        id: edge.id,
        source: edge.source,
        target: edge.target,
        data: edge.data ?? {},
      }))
      .sort((a, b) => a.id.localeCompare(b.id)),
    aura: graph.aura ?? {},
  };
}
async function saveNewDefinition(
  page: Page,
  key: string,
  name: string,
  info: TestInfo,
  scene: string,
) {
  await toolbar(page).getByTestId('bpmn-toolbar-btn-save').click();
  await expect(page.getByTestId('bpmn-save-dialog-panel')).toBeVisible();
  await page.locator('#bpmn-save-field-name').fill(name);
  await page.locator('#bpmn-save-field-key').fill(key);
  await capture(page, info, `${scene}-save-dialog`);
  const save = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/bpm/process-definitions' &&
      response.request().method() === 'POST',
  );
  await page.getByTestId('bpmn-save-dialog-submit').click();
  const response = await save;
  expect(response.ok()).toBe(true);
  expect(response.request().postDataJSON()).toMatchObject({ processKey: key, processName: name });
  const body = await response.json();
  expect(String(body.code)).toBe('0');
  const pid = String(body.data.pid);
  expect(pid).toMatch(/^[0-9A-Z]{26}$/);
  await expect(page.getByTestId('bpmn-save-dialog-panel')).toBeHidden();
  await expect(page).toHaveURL(new RegExp(`/bpm/designer\\?pid=${pid}$`));
  return pid;
}

test('SC05-BPM: real process fixture JSON → UI refinement → save/reopen → export/import copy', async ({
  page,
}, info) => {
  const bpmRoot = process.env.AURA_BPM_PROJECT_ROOT;
  expect(
    bpmRoot,
    'bind AURA_BPM_PROJECT_ROOT to the runtime frozen BPM product checkout',
  ).toBeTruthy();
  expect(isAbsolute(bpmRoot!)).toBe(true);
  const contribution = JSON.parse(
    await readFile(join(bpmRoot!, 'packages/aura-bpm-web/auraboot.contribution.json'), 'utf8'),
  );
  expect(
    contribution.routes.find((route: { id: string }) => route.id === 'bpm.designer').path,
  ).toBe('/bpm/designer');
  const coreRoot = process.env.AURA_CORE_PROJECT_ROOT;
  expect(coreRoot, 'Bind the exact Core checkout containing the delivery acceptance package').toBeTruthy();
  const fixturePath = join(coreRoot!, 'plugins/project-management/examples/acceptance-workflow.bpm-package.json');
  const fixtureText = await readFile(fixturePath, 'utf8');
  const source = JSON.parse(fixtureText);
  const graph = JSON.parse(source.processDefinition.extension.designerJson) as Graph;
  expect(graph.nodes).toHaveLength(8);
  expect(graph.edges).toHaveLength(9);
  const key = `delivery_bpm_${Date.now().toString(36)}`;
  const name = '客户项目交付 · 验收审批设计';
  // Reuse the actual delivery graph, not an unrelated leave-approval fixture.
  // This copy verifies authoring and round-trip; the canonical definition drives live approval.
  const imported: Graph = { ...graph, key, name, category: 'project-delivery' };
  await info.attach('SC05-BPM-fixture-origin', {
    body: JSON.stringify({
      root: coreRoot,
      fixturePath,
      sha256: createHash('sha256').update(fixtureText).digest('hex'),
      processKey: source.processKey,
    }),
    contentType: 'application/json',
  });
  await page.goto('/home');
  await processMenu(page);
  await page.getByTestId('toolbar-btn-create').click();
  await expect(page).toHaveURL(/\/bpm\/designer$/);
  await expect(toolbar(page)).toBeVisible();
  await importFile(page, JSON.stringify(imported), `${key}.json`);
  await expect(toolbar(page).getByTestId('bpmn-field-name')).toHaveValue(name);
  await expect(page.locator('.react-flow__node')).toHaveCount(8);
  await page.getByRole('button', { name: /fit view/i }).click();
  const managerNode = page.locator('.react-flow__node[data-id="tpm_enhanced_review"]');
  await managerNode.click();
  await expect(page.getByTestId('node-label-input')).toBeVisible();
  const refinedLabel = '加强审核 · 材料复核';
  await page.getByTestId('node-label-input').fill(refinedLabel);
  const refinedName = '客户项目交付 · 验收审批与材料复核';
  await toolbar(page).getByTestId('bpmn-field-name').fill(refinedName);
  await capture(page, info, 'SC05-BPM-imported-and-refined');
  if (await page.getByTestId('bpmn-drawer-backdrop').isVisible()) {
    await page.getByTestId('bpmn-close-inspector').click();
  }
  const pid = await saveNewDefinition(page, key, refinedName, info, 'SC05-BPM-original');
  const read = await page.request.get(`/api/bpm/process-definitions/${pid}`);
  expect(read.ok()).toBe(true);
  const persisted = (await read.json()).data;
  expect(persisted.processKey).toBe(key);
  expect(persisted.processName).toBe(refinedName);
  const savedGraph = JSON.parse(persisted.designerJson) as Graph;
  expect(savedGraph.nodes.find((node) => node.id === 'tpm_enhanced_review')?.data.label).toBe(
    refinedLabel,
  );
  expect(savedGraph.edges).toHaveLength(9);
  await processMenu(page);
  await page.getByTestId('list-search-input').fill(key);
  await page.getByTestId('list-search-input').press('Enter');
  const row = page.getByRole('row').filter({ hasText: key });
  await row.getByText(key, { exact: true }).click();
  await page.getByTestId('toolbar-btn-open_bpmn_designer').click();
  await expect(page).toHaveURL(new RegExp(`/bpm/designer\\?pid=${pid}$`));
  await expect(page.locator('.react-flow__node')).toHaveCount(8);
  await expect(toolbar(page).getByTestId('bpmn-field-name')).toHaveValue(refinedName);
  const exported = await exportFile(page, key, info, 'SC05-BPM-exported-definition');
  expect(semantics(exported)).toEqual(semantics(savedGraph));
  expect(exported.nodes.find((node) => node.id === 'tpm_enhanced_review')?.data.label).toBe(
    refinedLabel,
  );
  expect(exported.edges.some((edge) => edge.data?.condition)).toBe(true);
  await capture(page, info, 'SC05-BPM-saved-and-reopened');

  // The invalid shape is rejected before importFromJSON mutates the graph.
  await importFile(page, JSON.stringify({ malformed: true }), 'invalid-process.json');
  await expect(page.getByText(/Invalid BPMN JSON: missing required fields/)).toBeVisible();
  await expect(page.locator('.react-flow__node')).toHaveCount(8);
  await expect(toolbar(page).getByTestId('bpmn-field-name')).toHaveValue(refinedName);
  const afterInvalid = await exportFile(page, key, info, 'SC05-BPM-after-invalid-file');
  expect(semantics(afterInvalid)).toEqual(semantics(exported));
  await capture(page, info, 'SC05-BPM-invalid-file-preserved-graph');

  await processMenu(page);
  await page.getByTestId('toolbar-btn-create').click();
  const copyKey = `${key}_copy`;
  const copyName = `${refinedName} · 副本`;
  // Explicitly strip identity before importing a copy; importing an existing id
  // intentionally remains an edit of that definition in the current product.
  const { id: _id, ...portable } = exported;
  await importFile(
    page,
    JSON.stringify({ ...portable, key: copyKey, name: copyName }),
    `${copyKey}.json`,
  );
  await expect(page.locator('.react-flow__node')).toHaveCount(8);
  const copied = await exportFile(page, copyKey, info, 'SC05-BPM-reimported-copy');
  expect(semantics(copied)).toEqual(semantics(exported));
  const copyPid = await saveNewDefinition(page, copyKey, copyName, info, 'SC05-BPM-copy');
  expect(copyPid).not.toBe(pid);
  await page.reload();
  await expect(toolbar(page).getByTestId('bpmn-field-name')).toHaveValue(copyName);
  await expect(page.locator('.react-flow__node')).toHaveCount(8);
  const originalRead = await page.request.get(`/api/bpm/process-definitions/${pid}`);
  expect((await originalRead.json()).data.processName).toBe(refinedName);
  await capture(page, info, 'SC05-BPM-copy-saved-and-reopened');
});
