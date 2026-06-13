import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { uniqueId } from '../helpers';

const STAT_DATA_SOURCE_ID = 'ds_pd_stat_refresh';
const REFRESH_INTERVAL_MS = 500;

async function createStatRefreshAuthoringPage(page: Page) {
  const id = uniqueId('pd_stat_refresh_authoring');
  const pageKey = id.replace(/-/g, '_');
  const title = `Stat refresh authoring ${id}`;
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
    metaInfo: { runtimeE2E: true, statRefreshAuthoring: true },
    semver: '0.1.0',
  };

  const createResp = await page.request.post('/api/pages', { data: payload });
  expect(
    createResp.ok(),
    `Create stat refresh authoring page failed: ${createResp.status()}`,
  ).toBeTruthy();
  const createBody = await createResp.json();
  expect(createBody.code, 'create page API code').toBe('0');
  const pid = String(createBody.data?.pid || '');
  expect(pid, 'created stat refresh authoring pid').toBeTruthy();

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

async function createAndPublishListRuntimePage(
  page: Page,
  savedPage: Record<string, any>,
): Promise<string> {
  const savedBlocks = Array.isArray(savedPage.blocks) ? savedPage.blocks : [];
  const runtimePageKey = uniqueId('pd_stat_refresh_runtime').replace(/-/g, '_');
  const runtimeTitle = `Stat refresh runtime ${runtimePageKey}`;
  const runtimePayload = {
    name: runtimeTitle,
    pageKey: runtimePageKey,
    title: runtimeTitle,
    kind: 'list',
    modelCode: 'page_schema',
    profile: 'admin',
    blocks: [
      {
        id: 'pd_stat_refresh_runtime_table',
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
    dataSources: savedPage.dataSources || {},
    layout: savedPage.layout || { type: 'stack', gap: 12 },
    schemaVersion: savedPage.schemaVersion ?? 4,
    metaInfo: { ...(savedPage.metaInfo || {}), statRefreshRuntime: true },
    semver: savedPage.semver ?? '0.1.0',
  };

  const createResp = await page.request.post('/api/pages', { data: runtimePayload });
  expect(
    createResp.ok(),
    `Create stat refresh runtime page failed: ${createResp.status()}`,
  ).toBeTruthy();
  const createBody = await createResp.json();
  expect(createBody.code, 'create stat refresh runtime page API code').toBe('0');
  const runtimePid = String(createBody.data?.pid || '');
  expect(runtimePid, 'created stat refresh runtime pid').toBeTruthy();

  const publishResp = await page.request.post(`/api/pages/${runtimePid}/publish`);
  expect(
    publishResp.ok(),
    `Publish stat refresh runtime page failed: ${publishResp.status()}`,
  ).toBeTruthy();
  const publishBody = await publishResp.json();
  expect(publishBody.code, 'publish stat refresh runtime page API code').toBe('0');
  return runtimePageKey;
}

test.describe('Page Designer stat refresh authoring', () => {
  test.setTimeout(90_000);

  test('saves stat refresh interval and auto-refreshes the published runtime stat data source', async ({
    page,
  }) => {
    const { pid, pageKey, title } = await createStatRefreshAuthoringPage(page);

    await openDesignerByPid(page, pid);
    const statBlockId = await addBlockViaPalette(page, 'stat-card');
    await selectCanvasBlock(page, statBlockId);
    await openPropertyGroup(page, 'data');
    await page.getByTestId('stat-data-source-input').fill(STAT_DATA_SOURCE_ID);
    await page.getByTestId('stat-value-field-input').fill('name');
    await page.getByTestId('stat-refresh-interval-input').fill(String(REFRESH_INTERVAL_MS));
    await openPropertyGroup(page, 'appearance');
    await page.getByTestId('stat-suffix-input').fill('records');
    await page.getByTestId('stat-color-select').selectOption('green');

    await saveDesignerAndWait(page, pid);

    const savedPage = await fetchPageByPid(page, pid);
    const savedStat = (savedPage.blocks ?? []).find(
      (block: any) => block.blockType === 'stat-card',
    );
    expect(savedStat, 'saved refresh stat block').toMatchObject({
      dataSource: STAT_DATA_SOURCE_ID,
      refreshInterval: REFRESH_INTERVAL_MS,
      props: {
        valueField: 'name',
        suffix: 'records',
        color: 'green',
      },
    });

    await openDesignerByPid(page, pid);
    await selectCanvasBlock(page, statBlockId);
    await openPropertyGroup(page, 'data');
    await expect(page.getByTestId('stat-refresh-interval-input')).toHaveValue(
      String(REFRESH_INTERVAL_MS),
    );

    const runtimePageKey = await createAndPublishListRuntimePage(page, savedPage);

    const statResponses: unknown[] = [];
    page.on('response', async (response) => {
      if (
        response.url().includes('/api/dynamic/page_schema/list') &&
        response.url().includes(encodeURIComponent(pageKey)) &&
        response.status() === 200
      ) {
        statResponses.push(await response.json());
      }
    });

    await page.goto(`/p/c/${runtimePageKey}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('stat-card-value')).toHaveText(title, { timeout: 15_000 });
    await expect(page.getByTestId('stat-card-block')).toContainText('records');
    await expect
      .poll(
        () =>
          statResponses.filter(
            (body: any) => Array.isArray(body?.data?.records) && body.data.records.length > 0,
          ).length >= 2,
        { timeout: 8_000, intervals: [100, 250, 500] },
      )
      .toBe(true);
  });
});
