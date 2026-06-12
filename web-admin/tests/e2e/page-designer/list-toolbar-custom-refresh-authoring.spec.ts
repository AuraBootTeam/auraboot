import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { uniqueId } from '../helpers';

const TARGET_DATA_SOURCE_ID = 'ds_list_custom_refresh_target';
const BUTTON_CODE = 'refresh_targeted_data';
const BUTTON_LABEL = 'Refresh targeted data';

async function createCustomRefreshAuthoringPage(page: Page) {
  const id = uniqueId('list-custom-refresh');
  const pageKey = id.replace(/-/g, '_');
  const title = `List custom refresh ${id}`;
  const payload = {
    name: title,
    pageKey,
    title,
    kind: 'list',
    modelCode: 'page_schema',
    profile: 'admin',
    layout: { type: 'stack', gap: 12 },
    dataSources: {
      [TARGET_DATA_SOURCE_ID]: {
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
        blockType: 'filters',
        fields: ['name'],
        actions: ['search', 'reset'],
      },
      {
        blockType: 'toolbar',
        buttons: [],
      },
      {
        blockType: 'table',
        dataSource: 'tableData',
        columns: [{ field: 'name', width: 260 }, 'pageKey'],
        props: {
          pageSize: 20,
          multiSelect: false,
          rowClickAction: 'detail',
        },
      },
    ],
    schemaVersion: 4,
    metaInfo: { runtimeE2E: true, customRefreshAuthoring: true },
    extension: {
      hideSavedViews: true,
      hideQuickFilters: true,
      hideSort: true,
      hideColumnSettings: true,
      hideRowHeight: true,
      hideFilterChips: true,
    },
    semver: '0.1.0',
  };

  const createResp = await page.request.post('/api/pages', { data: payload });
  expect(
    createResp.ok(),
    `Create list custom refresh page failed: ${createResp.status()}`,
  ).toBeTruthy();
  const createBody = await createResp.json();
  expect(createBody.code, 'create page API code').toBe('0');
  return { pid: createBody.data.pid as string, pageKey };
}

async function readPage(page: Page, pid: string): Promise<Record<string, any>> {
  const resp = await page.request.get(`/api/pages/${pid}`);
  expect(resp.ok(), `Read list custom refresh page failed: ${resp.status()}`).toBeTruthy();
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

function targetDataSourceResponse(pageKey: string) {
  return (response: { url(): string; request(): { method(): string } }) =>
    response.url().includes('/api/dynamic/page_schema/list') &&
    response.url().includes(encodeURIComponent(pageKey)) &&
    response.request().method() === 'GET';
}

async function openToolbarTab(page: Page) {
  await page.getByTestId('list-tab-toolbar').click();
  await expect(page.getByTestId('toolbar-tab')).toBeVisible();
}

async function fieldInput(page: Page, key: string) {
  const field = page.getByTestId(`schema-config-field-${key}`);
  await expect(field).toBeVisible();
  return field.getByRole('textbox');
}

test.describe('List Toolbar Custom Refresh Authoring', () => {
  test('authors a custom toolbar dataSource.reload action and reloads the target at runtime', async ({
    page,
  }) => {
    const { pid, pageKey } = await createCustomRefreshAuthoringPage(page);

    await page.goto(`/page-designer/${pid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('list-config-panel')).toBeVisible();
    await expect(page.getByTestId('toolbar-save')).toBeVisible();

    await openToolbarTab(page);
    await page.getByTestId('toolbar-add-custom-button').click();
    await expect(page.getByTestId('schema-config-field-code')).toBeVisible();

    await (await fieldInput(page, 'label')).fill(BUTTON_LABEL);
    await (await fieldInput(page, 'code')).fill(BUTTON_CODE);
    await page.getByTestId('schema-config-field-actionKind').getByRole('combobox').click();
    await page.getByRole('option', { name: '刷新数据源' }).click();
    await (await fieldInput(page, 'targetDataSource')).fill(TARGET_DATA_SOURCE_ID);

    const saveResponse = page.waitForResponse(
      (response) =>
        response.url().includes(`/api/pages/${pid}`) &&
        response.request().method() === 'PUT',
    );
    await page.getByTestId('toolbar-save').click();
    expect((await saveResponse).ok(), 'custom refresh save response').toBeTruthy();

    const savedPage = await readPage(page, pid);
    expect(toolbarButtons(savedPage)).toContainEqual({
      code: BUTTON_CODE,
      label: BUTTON_LABEL,
      action: {
        type: 'flow',
        steps: [{ action: 'dataSource.reload', args: { target: TARGET_DATA_SOURCE_ID } }],
      },
      events: {
        onClick: {
          action: 'dataSource.reload',
          args: { target: TARGET_DATA_SOURCE_ID },
        },
      },
    });

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('list-config-panel')).toBeVisible();
    await openToolbarTab(page);
    await page.getByTestId('toolbar-custom-item-0').click();
    await expect(await fieldInput(page, 'label')).toHaveValue(BUTTON_LABEL);
    await expect(await fieldInput(page, 'code')).toHaveValue(BUTTON_CODE);
    await expect(page.getByTestId('schema-config-field-actionKind')).toContainText('刷新数据源');
    await expect(await fieldInput(page, 'targetDataSource')).toHaveValue(TARGET_DATA_SOURCE_ID);

    const publishResp = await page.request.post(`/api/pages/${pid}/publish`);
    expect(
      publishResp.ok(),
      `Publish list custom refresh page failed: ${publishResp.status()}`,
    ).toBeTruthy();
    const publishBody = await publishResp.json();
    expect(publishBody.code, 'publish page API code').toBe('0');

    const initialTargetLoad = page.waitForResponse(targetDataSourceResponse(pageKey));
    await page.goto(`/p/c/${pageKey}`, { waitUntil: 'domcontentloaded' });
    expect((await initialTargetLoad).ok(), 'initial target dataSource response').toBeTruthy();

    await expect(page.getByTestId(`toolbar-btn-${BUTTON_CODE}`)).toBeVisible();
    const refreshTargetLoad = page.waitForResponse(targetDataSourceResponse(pageKey));
    await page.getByTestId(`toolbar-btn-${BUTTON_CODE}`).click();
    expect((await refreshTargetLoad).ok(), 'custom refresh target response').toBeTruthy();
  });
});
