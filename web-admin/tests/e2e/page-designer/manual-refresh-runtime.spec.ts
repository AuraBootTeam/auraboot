import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { uniqueId } from '../helpers';

const MANUAL_REFRESH_DATA_SOURCE_ID = 'ds_pd_manual_refresh';

async function createManualRefreshRuntimePage(page: Page) {
  const id = uniqueId('pd_manual_refresh_runtime');
  const pageKey = id.replace(/-/g, '_');
  const title = `Manual refresh runtime ${id}`;
  const payload = {
    name: title,
    pageKey,
    title,
    kind: 'list',
    modelCode: 'page_schema',
    profile: 'admin',
    layout: { type: 'stack', gap: 12 },
    dataSources: {
      [MANUAL_REFRESH_DATA_SOURCE_ID]: {
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
    },
    blocks: [
      {
        id: 'pd_manual_refresh_toolbar',
        blockType: 'toolbar',
        buttons: [
          {
            code: 'manual_refresh_stats',
            label: 'Manual refresh stats',
            action: {
              type: 'flow',
              steps: [
                {
                  action: 'dataSource.reload',
                  args: { target: MANUAL_REFRESH_DATA_SOURCE_ID },
                },
              ],
            },
          },
        ],
      },
      {
        id: 'pd_manual_refresh_stat',
        blockType: 'stat-card',
        title: 'Manual refresh stat',
        dataSource: MANUAL_REFRESH_DATA_SOURCE_ID,
        props: {
          valueField: 'name',
          suffix: 'records',
        },
      },
      {
        id: 'pd_manual_refresh_table',
        blockType: 'table',
        columns: [{ field: 'name', label: 'Name', width: 260 }],
      },
    ],
    schemaVersion: 4,
    metaInfo: { runtimeE2E: true, manualRefreshRuntime: true },
    extension: {
      hideSavedViews: true,
      hideQuickFilters: true,
      hideSort: true,
      hideColumnSettings: true,
      hideRowHeight: true,
      hideFilterChips: true,
      miscBlocksPosition: 'beforeTable',
    },
    semver: '0.1.0',
  };

  const createResp = await page.request.post('/api/pages', { data: payload });
  expect(createResp.ok(), `Create manual refresh runtime page failed: ${createResp.status()}`).toBeTruthy();
  const createBody = await createResp.json();
  expect(createBody.code, 'create page API code').toBe('0');
  const pid = String(createBody.data?.pid || '');
  expect(pid, 'created manual refresh runtime pid').toBeTruthy();

  const publishResp = await page.request.post(`/api/pages/${pid}/publish`);
  expect(publishResp.ok(), `Publish manual refresh runtime page failed: ${publishResp.status()}`).toBeTruthy();
  const publishBody = await publishResp.json();
  expect(publishBody.code, 'publish manual refresh runtime page API code').toBe('0');

  return { pageKey, title };
}

test.describe('Page Designer manual refresh runtime', () => {
  test('toolbar flow button reloads a page data source and refreshes stat-card data', async ({
    page,
  }) => {
    const { pageKey, title } = await createManualRefreshRuntimePage(page);
    const dataSourceBodies: unknown[] = [];

    page.on('response', async (response) => {
      if (
        response.url().includes('/api/dynamic/page_schema/list') &&
        response.url().includes(encodeURIComponent(pageKey)) &&
        response.status() === 200
      ) {
        dataSourceBodies.push(await response.json());
      }
    });

    await page.goto(`/p/c/${pageKey}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('toolbar-btn-manual_refresh_stats')).toBeVisible({
      timeout: 15_000,
    });
    await expect(page.getByTestId('stat-card-value')).toHaveText(title, { timeout: 15_000 });
    await expect(page.getByTestId('stat-card-block')).toContainText('records');
    await expect
      .poll(
        () =>
          dataSourceBodies.filter((body: any) => Array.isArray(body?.data?.records) && body.data.records.length > 0)
            .length > 0,
        { timeout: 8_000, intervals: [100, 250, 500] },
      )
      .toBe(true);

    const beforeManualRefresh = dataSourceBodies.length;
    await page.getByTestId('toolbar-btn-manual_refresh_stats').click();
    await expect
      .poll(() => dataSourceBodies.length > beforeManualRefresh, {
        timeout: 8_000,
        intervals: [100, 250, 500],
      })
      .toBe(true);
    await expect(page.getByTestId('stat-card-value')).toHaveText(title);
  });
});
