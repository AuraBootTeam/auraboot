import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { uniqueId } from '../helpers';

async function createTabsChildBlockAuthoringPage(page: Page) {
  const id = uniqueId('pd_layout_tabs_child_blocks');
  const pageKey = id.replace(/-/g, '_');
  const title = `Layout tabs child blocks ${id}`;
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
    metaInfo: { runtimeE2E: true, layoutTabsChildBlockAuthoring: true },
    semver: '0.1.0',
  };

  const createResp = await page.request.post('/api/pages', { data: payload });
  expect(createResp.ok(), `Create tabs child block page failed: ${createResp.status()}`).toBeTruthy();
  const createBody = await createResp.json();
  expect(createBody.code, 'create page API code').toBe('0');
  const pid = String(createBody.data?.pid || '');
  expect(pid, 'created tabs child block pid').toBeTruthy();
  return { pid };
}

async function openDesignerByPid(page: Page, pid: string) {
  await page.goto(`/page-designer/${pid}`, { waitUntil: 'domcontentloaded' });
  await expect(page.getByTestId('designer-canvas')).toBeVisible();
  await expect(page.getByTestId('toolbar-save')).toBeVisible();
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
  await expect(page.getByTestId('library-tab-blocks')).toBeVisible();

  const beforeIds = await canvasBlockIds(page);
  const paletteItem = page.getByTestId(`block-palette-item-${blockType}`);
  await expect(paletteItem).toBeVisible();
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
  await expect(block).toBeVisible();
  await block.click();
}

async function saveDesignerAndWait(page: Page, pid: string) {
  const saveButton = page.getByTestId('toolbar-save');
  await expect(saveButton).toBeVisible();
  await expect.poll(async () => saveButton.isEnabled().catch(() => false)).toBe(true);

  const saveResp = page.waitForResponse(
    (response) =>
      response.url().includes(`/api/pages/${pid}`) &&
      response.request().method() === 'PUT' &&
      response.status() < 400,
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
    childCopy: string;
  },
) {
  await page.getByTestId('tab-filter-key-input').fill(values.key);
  await page.getByTestId('tab-filter-label-en-input').fill(values.labelEn);
  await page.getByTestId('tab-filter-label-zh-input').fill(values.labelZh);
  await page.getByTestId('tab-child-add-text-block').click();
  await page.getByTestId('tab-child-text-content-0').fill(values.childCopy);
}

test.describe('Page Designer layout tabs child block authoring', () => {
  test('saves and reloads text child blocks for individual tabs', async ({ page }) => {
    const { pid } = await createTabsChildBlockAuthoringPage(page);
    await openDesignerByPid(page, pid);

    const tabsBlockId = await addBlockViaPalette(page, 'tabs');
    await selectCanvasBlock(page, tabsBlockId);
    await expect(page.getByTestId('tab-child-blocks-editor')).toBeVisible();

    await fillCurrentTab(page, {
      key: 'overview',
      labelEn: 'Overview',
      labelZh: '概览',
      childCopy: 'Nested overview copy',
    });

    await page.getByTestId('tab-filter-add-tab').click();
    await fillCurrentTab(page, {
      key: 'history',
      labelEn: 'History',
      labelZh: '历史',
      childCopy: 'Nested history copy',
    });

    await saveDesignerAndWait(page, pid);
    const savedPage = await fetchPageByPid(page, pid);
    const savedBlock = savedBlockById(savedPage, tabsBlockId);
    expect(savedBlock).toMatchObject({
      blockType: 'tabs',
      tabs: [
        {
          key: 'overview',
          label: { 'en-US': 'Overview', 'zh-CN': '概览' },
          filter: null,
          blocks: [
            {
              blockType: 'text',
              title: { 'en-US': 'Text', 'zh-CN': '文本内容' },
              props: { content: 'Nested overview copy' },
            },
          ],
        },
        {
          key: 'history',
          label: { 'en-US': 'History', 'zh-CN': '历史' },
          filter: null,
          blocks: [
            {
              blockType: 'text',
              title: { 'en-US': 'Text', 'zh-CN': '文本内容' },
              props: { content: 'Nested history copy' },
            },
          ],
        },
      ],
    });

    await openDesignerByPid(page, pid);
    await selectCanvasBlock(page, tabsBlockId);
    await page.getByTestId('tab-history').click();
    await expect(page.getByTestId('tab-child-text-content-0')).toHaveValue('Nested history copy');
    await page.getByTestId('tab-overview').click();
    await expect(page.getByTestId('tab-child-text-content-0')).toHaveValue('Nested overview copy');
  });
});
