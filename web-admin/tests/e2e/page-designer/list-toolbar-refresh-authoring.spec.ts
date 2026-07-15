import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { uniqueId } from '../helpers';

async function createListToolbarRefreshPage(page: Page) {
  const name = uniqueId('list-refresh-toolbar');
  const pageKey = `e2e_list_refresh_toolbar_${Date.now().toString(36)}_${Math.random()
    .toString(36)
    .slice(2, 7)}`;

  const payload = {
    name,
    pageKey,
    title: name,
    kind: 'list',
    modelCode: 'page_schema',
    blocks: [
      {
        id: 'blk_filters_1',
        blockType: 'filters',
        fields: ['name'],
        actions: ['search', 'reset'],
      },
      {
        id: 'blk_toolbar_2',
        blockType: 'toolbar',
        buttons: [],
      },
      {
        id: 'blk_table_3',
        blockType: 'table',
        dataSource: 'tableData',
        columns: [{ field: 'name', width: 180 }, 'pageKey'],
        props: {
          pageSize: 20,
          multiSelect: false,
          rowClickAction: 'detail',
        },
      },
    ],
    metaInfo: { componentCount: 3 },
    semver: '0.1.0',
  };

  const createResp = await page.request.post('/api/pages', { data: payload });
  expect(
    createResp.ok(),
    `Create list toolbar refresh page failed: ${createResp.status()}`,
  ).toBeTruthy();
  const createBody = await createResp.json();
  expect(createBody.code, 'create page API code').toBe('0');
  return { pid: createBody.data.pid as string, pageKey };
}

async function readPage(page: Page, pid: string): Promise<Record<string, any>> {
  const resp = await page.request.get(`/api/pages/${pid}`);
  expect(resp.ok(), `Read list toolbar refresh page failed: ${resp.status()}`).toBeTruthy();
  const body = await resp.json();
  expect(body.code, 'read page API code').toBe('0');
  return body.data as Record<string, any>;
}

function toolbarButtons(schema: Record<string, any>): Array<Record<string, any>> {
  const toolbar = (schema.blocks ?? []).find(
    (block: Record<string, any>) => block.blockType === 'toolbar',
  );
  return (toolbar?.buttons ?? []) as Array<Record<string, any>>;
}

test.describe('List Toolbar Refresh Authoring', () => {
  test('adds refresh as a list toolbar preset and persists it through reload', async ({
    page,
  }) => {
    const { pid, pageKey } = await createListToolbarRefreshPage(page);

    await page.goto(`/page-designer/${pid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('list-config-panel')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId('toolbar-save')).toBeVisible({ timeout: 10_000 });

    await page.getByTestId('list-tab-toolbar').click();
    await expect(page.getByTestId('toolbar-tab')).toBeVisible();

    const refreshPreset = page.getByTestId('toolbar-preset-refresh');
    await expect(refreshPreset).toBeVisible();
    await expect(refreshPreset).toBeEnabled();
    await refreshPreset.check();

    const saveResponse = page.waitForResponse(
      (response) =>
        response.url().includes(`/api/pages/${pid}`) &&
        response.request().method() === 'PUT',
    );
    await page.getByTestId('toolbar-save').click();
    expect((await saveResponse).ok(), 'toolbar refresh save response').toBeTruthy();

    const savedPage = await readPage(page, pid);
    expect(toolbarButtons(savedPage)).toContainEqual({ preset: 'refresh' });

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('list-config-panel')).toBeVisible({ timeout: 15_000 });
    await page.getByTestId('list-tab-toolbar').click();
    await expect(page.getByTestId('toolbar-preset-refresh')).toBeChecked();

    const publishResp = await page.request.post(`/api/pages/${pid}/publish`);
    expect(publishResp.ok(), `Publish list toolbar refresh page failed: ${publishResp.status()}`)
      .toBeTruthy();
    const publishBody = await publishResp.json();
    expect(publishBody.code, 'publish page API code').toBe('0');

    const initialListResponse = page.waitForResponse(
      (response) =>
        response.url().includes('/api/dynamic/page_schema/list') &&
        response.request().method() === 'GET',
    );
    await page.goto(`/p/c/${pageKey}`, { waitUntil: 'domcontentloaded' });
    expect((await initialListResponse).ok(), 'initial list runtime response').toBeTruthy();

    await expect(page.getByTestId('toolbar-btn-refresh')).toBeVisible({ timeout: 10_000 });
    const refreshResponse = page.waitForResponse(
      (response) =>
        response.url().includes('/api/dynamic/page_schema/list') &&
        response.request().method() === 'GET',
    );
    await page.getByTestId('toolbar-btn-refresh').click();
    expect((await refreshResponse).ok(), 'refresh preset runtime response').toBeTruthy();
  });
});
