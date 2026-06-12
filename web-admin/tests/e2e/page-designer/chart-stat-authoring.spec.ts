import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { uniqueId } from '../helpers';

const STAT_DATA_SOURCE_ID = 'ds_pd_stat_api';
const CHART_DATA_SOURCE_ID = 'ds_pd_chart_aggregate';

async function createChartStatAuthoringPage(page: Page) {
  const id = uniqueId('pd_chart_stat_authoring');
  const pageKey = id.replace(/-/g, '_');
  const title = `Chart stat authoring ${id}`;
  const dataSources = {
    [STAT_DATA_SOURCE_ID]: {
      type: 'api',
      method: 'get',
      endpoint: '/api/dynamic/page_schema/list',
      params: {
        pageNum: '1',
        pageSize: '5',
        keyword: pageKey,
      },
      adaptor: 'table',
      autoFetch: true,
    },
    [CHART_DATA_SOURCE_ID]: {
      type: 'aggregate',
      modelCode: 'ab_tenant',
      dimensions: ['status'],
      metrics: [{ field: 'id', aggregation: 'count', alias: 'count' }],
      limit: 20,
      autoFetch: false,
    },
  };
  const payload = {
    name: title,
    pageKey,
    title,
    kind: 'form',
    modelCode: 'page_schema',
    profile: 'admin',
    layout: { type: 'stack', gap: 12 },
    blocks: [],
    dataSources,
    schemaVersion: 4,
    metaInfo: { runtimeE2E: true, chartStatAuthoring: true },
    semver: '0.1.0',
  };

  const createResp = await page.request.post('/api/pages', { data: payload });
  expect(createResp.ok(), `Create chart/stat authoring page failed: ${createResp.status()}`).toBeTruthy();
  const createBody = await createResp.json();
  expect(createBody.code, 'create page API code').toBe('0');
  const pid = String(createBody.data?.pid || '');
  expect(pid, 'created chart/stat authoring pid').toBeTruthy();

  return { pid, pageKey, title, dataSources };
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

async function openPropertyGroup(page: Page, group: 'data' | 'appearance') {
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

async function publishListRuntimePage(
  page: Page,
  pid: string,
  savedPage: Record<string, any>,
) {
  const savedBlocks = Array.isArray(savedPage.blocks) ? savedPage.blocks : [];
  const runtimePayload = {
    ...savedPage,
    kind: 'list',
    modelCode: 'page_schema',
    profile: 'admin',
    blocks: [
      {
        id: 'pd_chart_stat_runtime_table',
        blockType: 'table',
        table: {
          columns: [
            { field: 'name', label: 'Name', width: 260 },
            { field: 'page_key', label: 'Page Key', width: 220 },
          ],
          pagination: { pageSize: 10 },
        },
        columns: [
          { field: 'name', label: 'Name', width: 260 },
          { field: 'page_key', label: 'Page Key', width: 220 },
        ],
      },
      ...savedBlocks,
    ],
    extension: {
      ...(savedPage.extension || {}),
      hideSavedViews: true,
      hideQuickFilters: true,
      hideSort: true,
      hideColumnSettings: true,
      hideRowHeight: true,
      hideFilterChips: true,
      miscBlocksPosition: 'beforeTable',
    },
  };

  const updateResp = await page.request.put(`/api/pages/${pid}`, { data: runtimePayload });
  expect(updateResp.ok(), `Update list runtime page failed: ${updateResp.status()}`).toBeTruthy();
  const updateBody = await updateResp.json();
  expect(updateBody.code, 'update list runtime page API code').toBe('0');

  const publishResp = await page.request.post(`/api/pages/${pid}/publish`);
  expect(publishResp.ok(), `Publish list runtime page failed: ${publishResp.status()}`).toBeTruthy();
  const publishBody = await publishResp.json();
  expect(publishBody.code, 'publish list runtime page API code').toBe('0');
  expect(publishBody.data?.status, 'published list runtime page status').toBe('published');
}

test.describe('Page Designer chart/stat authoring', () => {
  test.setTimeout(90_000);

  test('binds stat-card and chart-card data sources, saves schema, previews, and renders real runtime data', async ({
    page,
  }) => {
    const { pid, pageKey, title, dataSources } = await createChartStatAuthoringPage(page);

    const statProbeResp = await page.request.get('/api/dynamic/page_schema/list', {
      params: dataSources[STAT_DATA_SOURCE_ID].params,
    });
    expect(statProbeResp.ok(), `stat datasource probe failed: ${statProbeResp.status()}`).toBeTruthy();
    const statProbeBody = await statProbeResp.json();
    expect(statProbeBody.data?.records?.length, 'stat API datasource records').toBeGreaterThan(0);

    const chartProbeResp = await page.request.post('/api/meta/chart-data', {
      data: dataSources[CHART_DATA_SOURCE_ID],
    });
    expect(chartProbeResp.ok(), `chart datasource probe failed: ${chartProbeResp.status()}`).toBeTruthy();
    const chartProbeBody = await chartProbeResp.json();
    expect(chartProbeBody.code, 'chart-data API code').toBe('0');
    expect(chartProbeBody.data?.rows?.length, 'chart aggregate rows').toBeGreaterThan(0);

    await openDesignerByPid(page, pid);

    const statBlockId = await addBlockViaPalette(page, 'stat-card');
    await selectCanvasBlock(page, statBlockId);
    await openPropertyGroup(page, 'data');
    await page.getByTestId('stat-data-source-input').fill(STAT_DATA_SOURCE_ID);
    await page.getByTestId('stat-value-field-input').fill('name');
    await openPropertyGroup(page, 'appearance');
    await page.getByTestId('stat-suffix-input').fill('records');
    await page.getByTestId('stat-color-select').selectOption('green');

    const chartBlockId = await addBlockViaPalette(page, 'chart-card');
    await selectCanvasBlock(page, chartBlockId);
    await openPropertyGroup(page, 'data');
    await page.getByTestId('chart-data-source-input').fill(CHART_DATA_SOURCE_ID);
    await page.getByTestId('chart-type-select').selectOption('bar');
    await page.getByTestId('chart-x-field-input').fill('status');
    await page.getByTestId('chart-y-field-input').fill('count');
    await openPropertyGroup(page, 'appearance');
    await page.getByTestId('chart-height-input').fill('260');

    await page.getByTestId('toolbar-preview').click();
    await expect(page.getByTestId('preview-modal')).toBeVisible({ timeout: 5_000 });
    const previewBlockCountText = (await page.getByTestId('preview-block-count').textContent()) ?? '';
    const previewBlockCount = Number.parseInt(previewBlockCountText, 10);
    expect(previewBlockCount, 'preview includes authored stat/chart blocks').toBeGreaterThan(1);
    await page.getByTestId('preview-close').click();
    await expect(page.getByTestId('preview-modal')).toBeHidden({ timeout: 5_000 });

    await saveDesignerAndWait(page, pid);

    const savedPage = await fetchPageByPid(page, pid);
    const savedBlocks = savedPage.blocks ?? [];
    const statBlock = savedBlocks.find((block: any) => block.blockType === 'stat-card');
    const chartBlock = savedBlocks.find((block: any) => block.blockType === 'chart-card');
    expect(statBlock, 'saved stat-card block').toMatchObject({
      dataSource: STAT_DATA_SOURCE_ID,
      props: {
        valueField: 'name',
        suffix: 'records',
        color: 'green',
      },
    });
    expect(chartBlock, 'saved chart-card block').toMatchObject({
      dataSource: CHART_DATA_SOURCE_ID,
      props: {
        chartType: 'bar',
        xField: 'status',
        yField: 'count',
        height: 260,
      },
    });

    await publishListRuntimePage(page, pid, savedPage);

    const statRuntimeResp = page.waitForResponse(
      (response) =>
        response.url().includes('/api/dynamic/page_schema/list') &&
        response.url().includes(encodeURIComponent(pageKey)) &&
        response.status() === 200,
      { timeout: 15_000 },
    );
    const chartRuntimeResp = page.waitForResponse(
      (response) => response.url().includes('/api/meta/chart-data') && response.status() === 200,
      { timeout: 15_000 },
    );

    await page.goto(`/p/c/${pageKey}`, { waitUntil: 'domcontentloaded' });
    const [statResp, chartResp] = await Promise.all([statRuntimeResp, chartRuntimeResp]);
    const statRuntimeBody = await statResp.json();
    const chartRuntimeBody = await chartResp.json();
    expect(statRuntimeBody.data?.records?.length, 'runtime stat API records').toBeGreaterThan(0);
    expect(chartRuntimeBody.data?.rows?.length, 'runtime chart aggregate rows').toBeGreaterThan(0);

    await expect(page.getByTestId('list-misc-blocks')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId('stat-card-block')).toContainText(title, { timeout: 15_000 });
    await expect(page.getByTestId('stat-card-value')).toHaveText(title);
    await expect(page.getByTestId('stat-card-block')).toContainText('records');
    await expect(page.getByTestId('list-misc-blocks').locator('canvas').first()).toBeVisible({
      timeout: 15_000,
    });
  });
});
