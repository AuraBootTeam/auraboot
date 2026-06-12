import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { uniqueId } from '../helpers';

const CHART_DATA_SOURCE_ID = 'ds_pd_chart_refresh';
const REFRESH_INTERVAL_MS = 500;

async function createChartRefreshAuthoringPage(page: Page) {
  const id = uniqueId('pd_chart_refresh_authoring');
  const pageKey = id.replace(/-/g, '_');
  const title = `Chart refresh authoring ${id}`;
  const dataSources = {
    [CHART_DATA_SOURCE_ID]: {
      type: 'aggregate',
      modelCode: 'ab_tenant',
      dimensions: ['status'],
      metrics: [{ field: 'id', aggregation: 'count', alias: 'count' }],
      limit: 20,
      autoFetch: true,
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
    metaInfo: { runtimeE2E: true, chartRefreshAuthoring: true },
    semver: '0.1.0',
  };

  const createResp = await page.request.post('/api/pages', { data: payload });
  expect(
    createResp.ok(),
    `Create chart refresh authoring page failed: ${createResp.status()}`,
  ).toBeTruthy();
  const createBody = await createResp.json();
  expect(createBody.code, 'create page API code').toBe('0');
  const pid = String(createBody.data?.pid || '');
  expect(pid, 'created chart refresh authoring pid').toBeTruthy();

  return { pid, pageKey, title };
}

async function openDesignerByPid(page: Page, pid: string) {
  await page.goto(`/page-designer/${pid}`, { waitUntil: 'domcontentloaded' });
  await expect(page.getByTestId('designer-canvas')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByTestId('toolbar-save')).toBeVisible({ timeout: 10_000 });
}

async function canvasBlockIds(page: Page): Promise<string[]> {
  return page
    .locator('[data-testid="sortable-block"][data-block-id]')
    .evaluateAll((elements) =>
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

async function publishListRuntimePage(page: Page, pid: string, savedPage: Record<string, any>) {
  const savedBlocks = Array.isArray(savedPage.blocks) ? savedPage.blocks : [];
  const runtimePayload = {
    ...savedPage,
    kind: 'list',
    modelCode: 'page_schema',
    profile: 'admin',
    blocks: [
      {
        id: 'pd_chart_refresh_runtime_table',
        blockType: 'table',
        columns: [{ field: 'name', label: 'Name', width: 260 }],
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
  expect(
    updateResp.ok(),
    `Update chart refresh runtime page failed: ${updateResp.status()}`,
  ).toBeTruthy();
  const updateBody = await updateResp.json();
  expect(updateBody.code, 'update chart refresh runtime page API code').toBe('0');

  const publishResp = await page.request.post(`/api/pages/${pid}/publish`);
  expect(
    publishResp.ok(),
    `Publish chart refresh runtime page failed: ${publishResp.status()}`,
  ).toBeTruthy();
  const publishBody = await publishResp.json();
  expect(publishBody.code, 'publish chart refresh runtime page API code').toBe('0');
}

test.describe('Page Designer chart refresh authoring', () => {
  test.setTimeout(90_000);

  test('saves chart refresh interval and auto-refreshes the published runtime chart', async ({
    page,
  }) => {
    const { pid, pageKey } = await createChartRefreshAuthoringPage(page);

    await openDesignerByPid(page, pid);
    const chartBlockId = await addBlockViaPalette(page, 'chart-card');
    await selectCanvasBlock(page, chartBlockId);
    await openPropertyGroup(page, 'data');
    await page.getByTestId('chart-data-source-input').fill(CHART_DATA_SOURCE_ID);
    await page.getByTestId('chart-type-select').selectOption('bar');
    await page.getByTestId('chart-x-field-input').fill('status');
    await page.getByTestId('chart-y-field-input').fill('count');
    await page.getByTestId('chart-refresh-interval-input').fill(String(REFRESH_INTERVAL_MS));
    await openPropertyGroup(page, 'appearance');
    await page.getByTestId('chart-height-input').fill('240');

    await saveDesignerAndWait(page, pid);

    const savedPage = await fetchPageByPid(page, pid);
    const savedChart = (savedPage.blocks ?? []).find(
      (block: any) => block.blockType === 'chart-card',
    );
    expect(savedChart, 'saved refresh chart block').toMatchObject({
      dataSource: CHART_DATA_SOURCE_ID,
      refreshInterval: REFRESH_INTERVAL_MS,
      props: {
        chartType: 'bar',
        xField: 'status',
        yField: 'count',
        height: 240,
      },
    });

    await openDesignerByPid(page, pid);
    await selectCanvasBlock(page, chartBlockId);
    await openPropertyGroup(page, 'data');
    await expect(page.getByTestId('chart-refresh-interval-input')).toHaveValue(
      String(REFRESH_INTERVAL_MS),
    );

    await publishListRuntimePage(page, pid, savedPage);

    const chartResponses: unknown[] = [];
    page.on('response', async (response) => {
      if (response.url().includes('/api/meta/chart-data') && response.status() === 200) {
        chartResponses.push(await response.json());
      }
    });

    await page.goto(`/p/c/${pageKey}`, { waitUntil: 'domcontentloaded' });
    await expect
      .poll(
        () =>
          chartResponses.filter(
            (body: any) => Array.isArray(body?.data?.rows) && body.data.rows.length > 0,
          ).length >= 2,
        { timeout: 8_000, intervals: [100, 250, 500] },
      )
      .toBe(true);
    await expect(
      page.locator('.echarts-for-react canvas, .echarts-for-react svg').first(),
    ).toBeVisible({
      timeout: 10_000,
    });
  });
});
