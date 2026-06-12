import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { uniqueId } from '../helpers';

const TARGETED_REFRESH_DATA_SOURCE_ID = 'ds_pd_refresh_code_target';

async function createRefreshCodeRuntimePage(page: Page) {
  const id = uniqueId('pd_refresh_code_runtime');
  const pageKey = id.replace(/-/g, '_');
  const title = `Refresh code runtime ${id}`;
  const payload = {
    name: title,
    pageKey,
    title,
    kind: 'list',
    modelCode: 'page_schema',
    profile: 'admin',
    layout: { type: 'stack', gap: 12 },
    dataSources: {
      [TARGETED_REFRESH_DATA_SOURCE_ID]: {
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
        id: 'pd_refresh_code_toolbar',
        blockType: 'toolbar',
        buttons: [
          {
            code: 'refresh',
            label: 'Refresh',
            events: {
              onClick: {
                args: { target: TARGETED_REFRESH_DATA_SOURCE_ID },
              },
            },
          },
        ],
      },
      {
        id: 'pd_refresh_code_stat',
        blockType: 'stat-card',
        title: 'Refresh code stat',
        dataSource: TARGETED_REFRESH_DATA_SOURCE_ID,
        props: {
          valueField: 'name',
          suffix: 'records',
        },
      },
      {
        id: 'pd_refresh_code_table',
        blockType: 'table',
        columns: [{ field: 'name', label: 'Name', width: 260 }],
      },
    ],
    schemaVersion: 4,
    metaInfo: { runtimeE2E: true, refreshCodeRuntime: true },
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
  expect(createResp.ok(), `Create refresh code runtime page failed: ${createResp.status()}`).toBeTruthy();
  const createBody = await createResp.json();
  expect(createBody.code, 'create page API code').toBe('0');
  const pid = String(createBody.data?.pid || '');
  expect(pid, 'created refresh code runtime pid').toBeTruthy();

  const publishResp = await page.request.post(`/api/pages/${pid}/publish`);
  expect(publishResp.ok(), `Publish refresh code runtime page failed: ${publishResp.status()}`).toBeTruthy();
  const publishBody = await publishResp.json();
  expect(publishBody.code, 'publish refresh code runtime page API code').toBe('0');

  return { pageKey, title };
}

test.describe('Page Designer refresh code runtime', () => {
  test('toolbar code=refresh reloads a targeted page data source', async ({ page }) => {
    const { pageKey, title } = await createRefreshCodeRuntimePage(page);
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
    await expect(page.getByTestId('toolbar-btn-refresh')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId('stat-card-value')).toHaveText(title, { timeout: 15_000 });
    await expect
      .poll(
        () =>
          dataSourceBodies.filter((body: any) => Array.isArray(body?.data?.records) && body.data.records.length > 0)
            .length > 0,
        { timeout: 8_000, intervals: [100, 250, 500] },
      )
      .toBe(true);

    const beforeRefresh = dataSourceBodies.length;
    await page.getByTestId('toolbar-btn-refresh').click();
    await expect
      .poll(() => dataSourceBodies.length > beforeRefresh, {
        timeout: 8_000,
        intervals: [100, 250, 500],
      })
      .toBe(true);
    await expect(page.getByTestId('stat-card-value')).toHaveText(title);
  });
});
