import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { uniqueId } from '../helpers';

async function createTabsChildDropAuthoringPage(page: Page) {
  const id = uniqueId('pd_layout_tabs_child_drop');
  const pageKey = id.replace(/-/g, '_');
  const title = `Layout tabs child drop ${id}`;
  const payload = {
    name: title,
    pageKey,
    title,
    kind: 'form',
    modelCode: 'page_schema',
    profile: 'admin',
    layout: { type: 'stack', gap: 12 },
    blocks: [
      {
        id: 'tabs_drag_target',
        blockType: 'tabs',
        title: { 'en-US': 'Tabs', 'zh-CN': '标签页' },
        tabs: [
          {
            key: 'all',
            label: { 'en-US': 'All', 'zh-CN': '全部' },
            filter: null,
          },
        ],
      },
    ],
    dataSources: {},
    schemaVersion: 4,
    metaInfo: { runtimeE2E: true, layoutTabsChildDropAuthoring: true },
    semver: '0.1.0',
  };

  const createResp = await page.request.post('/api/pages', { data: payload });
  expect(createResp.ok(), `Create tabs child drop page failed: ${createResp.status()}`).toBeTruthy();
  const createBody = await createResp.json();
  expect(createBody.code, 'create page API code').toBe('0');
  const pid = String(createBody.data?.pid || '');
  expect(pid, 'created tabs child drop pid').toBeTruthy();
  return { pid, tabsBlockId: 'tabs_drag_target' };
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

async function selectCanvasBlock(page: Page, blockId: string) {
  const block = page.locator(`[data-testid="sortable-block"][data-block-id="${blockId}"]`);
  await expect(block).toBeVisible();
  await block.click();
}

async function dragPaletteBlockToTabChildDropZone(page: Page, blockType: string) {
  await page.getByTestId('designer-tab-blocks').click();
  await expect(page.getByTestId('library-tab-blocks')).toBeVisible();

  const source = page.getByTestId(`block-palette-item-${blockType}`);
  const target = page.getByTestId('tab-child-drop-zone');
  await expect(source).toBeVisible();
  await expect(target).toBeVisible();

  await source.scrollIntoViewIfNeeded();
  await source.dragTo(target);
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

test.describe('Page Designer layout tabs child block drop authoring', () => {
  test('drags a text block into the selected tab child block list', async ({ page }) => {
    const { pid, tabsBlockId } = await createTabsChildDropAuthoringPage(page);
    await openDesignerByPid(page, pid);

    await selectCanvasBlock(page, tabsBlockId);
    await expect(page.getByTestId('tab-child-blocks-editor')).toBeVisible();

    await page.getByTestId('tab-filter-key-input').fill('overview');
    await page.getByTestId('tab-filter-label-en-input').fill('Overview');
    await page.getByTestId('tab-filter-label-zh-input').fill('概览');

    const beforeTopLevelIds = await canvasBlockIds(page);
    await dragPaletteBlockToTabChildDropZone(page, 'text');

    await expect(page.getByTestId('tab-child-block-0')).toBeVisible();
    await page.getByTestId('tab-child-text-content-0').fill('Dragged nested overview copy');
    await expect.poll(() => canvasBlockIds(page)).toEqual(beforeTopLevelIds);

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
              props: { content: 'Dragged nested overview copy' },
            },
          ],
        },
      ],
    });

    await openDesignerByPid(page, pid);
    await selectCanvasBlock(page, tabsBlockId);
    await expect(page.getByTestId('tab-child-text-content-0')).toHaveValue(
      'Dragged nested overview copy',
    );
  });

  test('drags a stat card child block and edits its data settings', async ({ page }) => {
    const { pid, tabsBlockId } = await createTabsChildDropAuthoringPage(page);
    await openDesignerByPid(page, pid);

    await selectCanvasBlock(page, tabsBlockId);
    await expect(page.getByTestId('tab-child-blocks-editor')).toBeVisible();

    await page.getByTestId('tab-filter-key-input').fill('metrics');
    await page.getByTestId('tab-filter-label-en-input').fill('Metrics');
    await page.getByTestId('tab-filter-label-zh-input').fill('指标');

    const beforeTopLevelIds = await canvasBlockIds(page);
    await dragPaletteBlockToTabChildDropZone(page, 'stat-card');

    await expect(page.getByTestId('tab-child-block-0')).toBeVisible();
    await expect(page.getByTestId('tab-child-block-0')).toContainText('stat-card');
    await page.getByTestId('tab-child-title-en-input-0').fill('Nested metric card');
    await page.getByTestId('tab-child-title-zh-input-0').fill('嵌套指标卡片');
    await page.getByTestId('tab-child-stat-data-source-input-0').fill('nested_stats');
    await page.getByTestId('tab-child-stat-value-field-input-0').fill('totalCount');
    await page.getByTestId('tab-child-stat-suffix-input-0').fill('records');
    await page.getByTestId('tab-child-stat-color-select-0').selectOption('green');
    await page.getByTestId('tab-child-stat-refresh-interval-input-0').fill('1500');
    await expect.poll(() => canvasBlockIds(page)).toEqual(beforeTopLevelIds);

    await saveDesignerAndWait(page, pid);
    const savedPage = await fetchPageByPid(page, pid);
    const savedBlock = savedBlockById(savedPage, tabsBlockId);
    expect(savedBlock).toMatchObject({
      blockType: 'tabs',
      tabs: [
        {
          key: 'metrics',
          label: { 'en-US': 'Metrics', 'zh-CN': '指标' },
          filter: null,
          blocks: [
            {
              blockType: 'stat-card',
              title: { 'en-US': 'Nested metric card', 'zh-CN': '嵌套指标卡片' },
              dataSource: 'nested_stats',
              refreshInterval: 1500,
              props: { valueField: 'totalCount', suffix: 'records', color: 'green' },
            },
          ],
        },
      ],
    });

    await openDesignerByPid(page, pid);
    await selectCanvasBlock(page, tabsBlockId);
    await expect(page.getByTestId('tab-child-title-en-input-0')).toHaveValue(
      'Nested metric card',
    );
    await expect(page.getByTestId('tab-child-title-zh-input-0')).toHaveValue('嵌套指标卡片');
    await expect(page.getByTestId('tab-child-stat-data-source-input-0')).toHaveValue(
      'nested_stats',
    );
    await expect(page.getByTestId('tab-child-stat-value-field-input-0')).toHaveValue(
      'totalCount',
    );
    await expect(page.getByTestId('tab-child-stat-suffix-input-0')).toHaveValue('records');
    await expect(page.getByTestId('tab-child-stat-color-select-0')).toHaveValue('green');
    await expect(page.getByTestId('tab-child-stat-refresh-interval-input-0')).toHaveValue('1500');
  });

  test('drags a chart child block and edits its data settings', async ({ page }) => {
    const { pid, tabsBlockId } = await createTabsChildDropAuthoringPage(page);
    await openDesignerByPid(page, pid);

    await selectCanvasBlock(page, tabsBlockId);
    await expect(page.getByTestId('tab-child-blocks-editor')).toBeVisible();

    await page.getByTestId('tab-filter-key-input').fill('charts');
    await page.getByTestId('tab-filter-label-en-input').fill('Charts');
    await page.getByTestId('tab-filter-label-zh-input').fill('图表');

    const beforeTopLevelIds = await canvasBlockIds(page);
    await dragPaletteBlockToTabChildDropZone(page, 'chart-card');

    await expect(page.getByTestId('tab-child-block-0')).toBeVisible();
    await expect(page.getByTestId('tab-child-block-0')).toContainText('chart-card');
    await page.getByTestId('tab-child-title-en-input-0').fill('Nested trend chart');
    await page.getByTestId('tab-child-title-zh-input-0').fill('嵌套趋势图');
    await page.getByTestId('tab-child-chart-data-source-input-0').fill('nested_chart_ds');
    await page.getByTestId('tab-child-chart-type-select-0').selectOption('line');
    await page.getByTestId('tab-child-chart-x-field-input-0').fill('category');
    await page.getByTestId('tab-child-chart-y-field-input-0').fill('amount');
    await page.getByTestId('tab-child-chart-refresh-interval-input-0').fill('1500');
    await page.getByTestId('tab-child-chart-smooth-switch-0').click();
    await page.getByTestId('tab-child-chart-legend-switch-0').click();
    await page.getByTestId('tab-child-chart-height-input-0').fill('240');
    await expect.poll(() => canvasBlockIds(page)).toEqual(beforeTopLevelIds);

    await saveDesignerAndWait(page, pid);
    const savedPage = await fetchPageByPid(page, pid);
    const savedBlock = savedBlockById(savedPage, tabsBlockId);
    expect(savedBlock).toMatchObject({
      blockType: 'tabs',
      tabs: [
        {
          key: 'charts',
          label: { 'en-US': 'Charts', 'zh-CN': '图表' },
          filter: null,
          blocks: [
            {
              blockType: 'chart-card',
              title: { 'en-US': 'Nested trend chart', 'zh-CN': '嵌套趋势图' },
              dataSource: 'nested_chart_ds',
              refreshInterval: 1500,
              props: {
                chartType: 'line',
                xField: 'category',
                yField: 'amount',
                smooth: false,
                showLegend: false,
                height: 240,
              },
            },
          ],
        },
      ],
    });

    await openDesignerByPid(page, pid);
    await selectCanvasBlock(page, tabsBlockId);
    await expect(page.getByTestId('tab-child-title-en-input-0')).toHaveValue(
      'Nested trend chart',
    );
    await expect(page.getByTestId('tab-child-title-zh-input-0')).toHaveValue('嵌套趋势图');
    await expect(page.getByTestId('tab-child-chart-data-source-input-0')).toHaveValue(
      'nested_chart_ds',
    );
    await expect(page.getByTestId('tab-child-chart-type-select-0')).toHaveValue('line');
    await expect(page.getByTestId('tab-child-chart-x-field-input-0')).toHaveValue('category');
    await expect(page.getByTestId('tab-child-chart-y-field-input-0')).toHaveValue('amount');
    await expect(page.getByTestId('tab-child-chart-refresh-interval-input-0')).toHaveValue(
      '1500',
    );
    await expect(page.getByTestId('tab-child-chart-smooth-switch-0')).toHaveAttribute(
      'aria-checked',
      'false',
    );
    await expect(page.getByTestId('tab-child-chart-legend-switch-0')).toHaveAttribute(
      'aria-checked',
      'false',
    );
    await expect(page.getByTestId('tab-child-chart-height-input-0')).toHaveValue('240');
  });
});
