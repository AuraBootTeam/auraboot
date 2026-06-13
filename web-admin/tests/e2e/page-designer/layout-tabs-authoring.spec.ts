import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { uniqueId } from '../helpers';

async function createTabsAuthoringPage(page: Page) {
  const id = uniqueId('pd_layout_tabs_authoring');
  const pageKey = id.replace(/-/g, '_');
  const title = `Layout tabs authoring ${id}`;
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
    metaInfo: { runtimeE2E: true, layoutTabsAuthoring: true },
    semver: '0.1.0',
  };

  const createResp = await page.request.post('/api/pages', { data: payload });
  expect(createResp.ok(), `Create tabs authoring page failed: ${createResp.status()}`).toBeTruthy();
  const createBody = await createResp.json();
  expect(createBody.code, 'create page API code').toBe('0');
  const pid = String(createBody.data?.pid || '');
  expect(pid, 'created tabs authoring pid').toBeTruthy();
  return { pid };
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

async function fillCurrentTab(
  page: Page,
  values: {
    key: string;
    labelEn: string;
    labelZh: string;
    filter?: { field: string; operator: string; value: string };
  },
) {
  await page.getByTestId('tab-filter-key-input').fill(values.key);
  await page.getByTestId('tab-filter-label-en-input').fill(values.labelEn);
  await page.getByTestId('tab-filter-label-zh-input').fill(values.labelZh);

  if (values.filter) {
    await page.getByTestId('tab-filter-add-condition').click();
    await page.getByTestId('tab-filter-field-input').fill(values.filter.field);
    await page.getByTestId('tab-filter-operator-select').selectOption(values.filter.operator);
    await page.getByTestId('tab-filter-value-input').fill(values.filter.value);
  }
}

test.describe('Page Designer layout tabs authoring', () => {
  test('adds a tabs block from the palette and saves tab filters through reload', async ({ page }) => {
    const { pid } = await createTabsAuthoringPage(page);
    await openDesignerByPid(page, pid);

    const tabsBlockId = await addBlockViaPalette(page, 'tabs');
    await selectCanvasBlock(page, tabsBlockId);
    await expect(page.getByTestId('tab-filter-editor')).toBeVisible({ timeout: 5_000 });

    await fillCurrentTab(page, {
      key: 'status_active',
      labelEn: 'Active',
      labelZh: '启用',
      filter: { field: 'status', operator: 'EQ', value: 'active' },
    });

    await page.getByTestId('tab-filter-add-tab').click();
    await fillCurrentTab(page, {
      key: 'all_records',
      labelEn: 'All records',
      labelZh: '全部记录',
    });

    await saveDesignerAndWait(page, pid);
    const savedPage = await fetchPageByPid(page, pid);
    const savedBlock = savedBlockById(savedPage, tabsBlockId);
    expect(savedBlock).toMatchObject({
      blockType: 'tabs',
      title: {
        'zh-CN': '标签页',
        'en-US': 'Tabs',
      },
      tabs: [
        {
          key: 'status_active',
          label: { 'en-US': 'Active', 'zh-CN': '启用' },
          filter: { field: 'status', operator: 'EQ', value: 'active' },
        },
        {
          key: 'all_records',
          label: { 'en-US': 'All records', 'zh-CN': '全部记录' },
          filter: null,
        },
      ],
    });

    await openDesignerByPid(page, pid);
    await selectCanvasBlock(page, tabsBlockId);
    await expect(page.getByTestId('tab-status_active')).toBeVisible({ timeout: 5_000 });
    await page.getByTestId('tab-all_records').click();
    await expect(page.getByTestId('tab-filter-key-input')).toHaveValue('all_records');
    await expect(page.getByTestId('tab-filter-label-en-input')).toHaveValue('All records');
    await expect(page.getByTestId('tab-filter-label-zh-input')).toHaveValue('全部记录');
  });
});
