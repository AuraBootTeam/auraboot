import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { uniqueId } from '../helpers';

const CUSTOM_COMPONENT = 'decision-field-impact';

async function createCustomBlockAuthoringPage(page: Page) {
  const id = uniqueId('pd_custom_block_authoring');
  const pageKey = id.replace(/-/g, '_');
  const title = `Custom block authoring ${id}`;
  const payload = {
    name: title,
    pageKey,
    title,
    kind: 'form',
    modelCode: 'page_schema',
    profile: 'admin',
    layout: { type: 'stack', gap: 12 },
    blocks: [],
    dataSources: {},
    schemaVersion: 4,
    metaInfo: { runtimeE2E: true, customBlockAuthoring: true },
    semver: '0.1.0',
  };

  const createResp = await page.request.post('/api/pages', { data: payload });
  expect(createResp.ok(), `Create custom block authoring page failed: ${createResp.status()}`).toBeTruthy();
  const createBody = await createResp.json();
  expect(createBody.code, 'create page API code').toBe('0');
  const pid = String(createBody.data?.pid || '');
  expect(pid, 'created custom block authoring pid').toBeTruthy();
  return { pid, pageKey, title };
}

async function openDesignerByPid(page: Page, pid: string) {
  await page.goto(`/page-designer/${pid}`, { waitUntil: 'domcontentloaded' });
  await expect(page.getByTestId('designer-canvas')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByTestId('toolbar-save')).toBeVisible({ timeout: 10_000 });
}

async function canvasBlockIds(page: Page): Promise<string[]> {
  return page.locator('[data-testid="sortable-block"][data-block-id]').evaluateAll((elements) =>
    elements
      .map((element) => (element as HTMLElement).getAttribute('data-block-id') || '')
      .filter(Boolean),
  );
}

async function addBlockViaPalette(page: Page, blockType: string): Promise<string> {
  await page.getByTestId('designer-tab-blocks').click();
  await expect(page.getByTestId('library-tab-blocks')).toBeVisible({ timeout: 5_000 });

  const beforeIds = await canvasBlockIds(page);
  const paletteItem = page.getByTestId(`block-palette-item-${blockType}`);
  await expect(paletteItem).toBeVisible({ timeout: 5_000 });
  await paletteItem.evaluate((element: HTMLElement) => element.click());

  await expect
    .poll(async () => (await canvasBlockIds(page)).length, { timeout: 5_000 })
    .toBe(beforeIds.length + 1);

  const afterIds = await canvasBlockIds(page);
  const newId = afterIds.find((blockId) => !beforeIds.includes(blockId));
  expect(newId, `new ${blockType} block id`).toBeTruthy();
  return newId!;
}

async function selectCanvasBlock(page: Page, blockId: string) {
  const block = page.locator(`[data-testid="sortable-block"][data-block-id="${blockId}"]`);
  await expect(block).toBeVisible({ timeout: 5_000 });
  await block.click();
}

async function openPropertyGroup(page: Page, group: 'basic' | 'data') {
  const tab = page.getByTestId(`property-group-${group}`);
  await expect(tab).toBeVisible({ timeout: 5_000 });
  await tab.click();
}

async function saveDesignerAndWait(page: Page, pid: string) {
  const saveButton = page.getByTestId('toolbar-save');
  await expect(saveButton).toBeVisible({ timeout: 5_000 });
  await expect
    .poll(async () => saveButton.isEnabled().catch(() => false), { timeout: 8_000 })
    .toBe(true);

  const saveResp = page.waitForResponse(
    (response) =>
      response.url().includes(`/api/pages/${pid}`) &&
      response.request().method() === 'PUT' &&
      response.status() < 400,
    { timeout: 10_000 },
  );
  await saveButton.click();
  await saveResp;
}

async function fetchPageByPid(page: Page, pid: string): Promise<Record<string, any>> {
  const resp = await page.request.get(`/api/pages/${pid}`);
  expect(resp.ok(), `Fetch page ${pid} failed: ${resp.status()}`).toBeTruthy();
  const body = await resp.json();
  expect(body.code, 'fetch page API code').toBe('0');
  return body.data ?? {};
}

function savedBlockById(savedPage: Record<string, any>, blockId: string): Record<string, any> {
  const block = (savedPage.blocks ?? []).find((item: any) => item.id === blockId);
  expect(block, `saved block ${blockId}`).toBeTruthy();
  return block;
}

test.describe('Page Designer custom block authoring', () => {
  test('adds a custom block, saves component props, and reloads the property panel', async ({
    page,
  }) => {
    const { pid } = await createCustomBlockAuthoringPage(page);
    await openDesignerByPid(page, pid);

    const customBlockId = await addBlockViaPalette(page, 'custom');
    await selectCanvasBlock(page, customBlockId);
    await openPropertyGroup(page, 'basic');
    await page.getByTestId('block-title-input-zh').fill('Decision field impact');
    await page.getByTestId('custom-component-input').fill(CUSTOM_COMPONENT);

    await openPropertyGroup(page, 'data');
    await page.getByTestId('custom-props-json-input').fill(
      JSON.stringify({ initialCurrentDataType: 'number' }, null, 2),
    );
    await page.getByTestId('custom-value-field-input').fill('pid');

    await saveDesignerAndWait(page, pid);
    const savedPage = await fetchPageByPid(page, pid);
    const savedBlock = savedBlockById(savedPage, customBlockId);
    expect(savedBlock).toMatchObject({
      blockType: 'custom',
      title: {
        'zh-CN': 'Decision field impact',
        'en-US': 'Custom Block',
      },
      component: CUSTOM_COMPONENT,
      props: {
        initialCurrentDataType: 'number',
        valueField: 'pid',
      },
    });

    await openDesignerByPid(page, pid);
    await selectCanvasBlock(page, customBlockId);
    await expect(page.getByTestId('block-title-input-zh')).toHaveValue('Decision field impact');
    await expect(page.getByTestId('custom-component-input')).toHaveValue(CUSTOM_COMPONENT);
    await openPropertyGroup(page, 'data');
    await expect(page.getByTestId('custom-value-field-input')).toHaveValue('pid');
    await expect(page.getByTestId('custom-props-json-input')).toContainText(
      'initialCurrentDataType',
    );
  });
});
