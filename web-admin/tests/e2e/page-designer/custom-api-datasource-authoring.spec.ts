import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { uniqueId } from '../helpers';

const TEXT_BLOCK_ID = 'pd_custom_api_datasource_text';

interface PageDataSourceConfig {
  type: 'api';
  method: 'get' | 'post';
  endpoint: string;
  adaptor: 'table' | 'form' | 'raw';
  pagination: boolean;
  params: Record<string, string>;
  [key: string]: unknown;
}

async function createCustomApiDataSourcePage(page: Page) {
  const id = uniqueId('pd_custom_api_datasource');
  const pageKey = id.replace(/-/g, '_');
  const title = `Custom API data source ${id}`;
  const initialDataSource: PageDataSourceConfig = {
    type: 'api',
    method: 'get',
    endpoint: `/api/dynamic/page_schema/list?keyword=${encodeURIComponent(pageKey)}`,
    adaptor: 'table',
    pagination: true,
    params: {
      keyword: pageKey,
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
    blocks: [
      {
        id: TEXT_BLOCK_ID,
        blockType: 'text',
        title: 'Custom API data source seed',
        props: { content: 'Select this block to edit the page-level data source.' },
      },
    ],
    schemaVersion: 4,
    extension: {
      customApi: {
        listEndpoint: '/api/dynamic/page_schema/list',
        method: 'GET',
      },
      dataSource: initialDataSource,
    },
    metaInfo: { runtimeE2E: true, customApiDataSourceAuthoring: true },
    semver: '0.1.0',
  };

  const createResp = await page.request.post('/api/pages', { data: payload });
  expect(
    createResp.ok(),
    `Create custom API data source page failed: ${createResp.status()}`,
  ).toBeTruthy();
  const createBody = await createResp.json();
  expect(createBody.code, 'create page API code').toBe('0');
  const pid = String(createBody.data?.pid || '');
  expect(pid, 'created custom API data source pid').toBeTruthy();

  return { pid, pageKey, title, initialDataSource };
}

async function openDesignerByPid(page: Page, pid: string) {
  await page.goto(`/page-designer/${pid}`, { waitUntil: 'domcontentloaded' });
  await expect(page.getByTestId('designer-canvas')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByTestId('toolbar-save')).toBeVisible({ timeout: 10_000 });
}

async function selectSeedBlock(page: Page) {
  const block = page.locator(`[data-testid="sortable-block"][data-block-id="${TEXT_BLOCK_ID}"]`);
  await expect(block).toBeVisible({ timeout: 5_000 });
  await block.click();
  await expect(page.getByTestId('ds-editor')).toBeVisible({ timeout: 5_000 });
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

test.describe('Page Designer custom API data source authoring', () => {
  test.setTimeout(90_000);

  test('edits page-level API data source in the property panel, saves it, and reloads it', async ({
    page,
  }) => {
    const { pid, pageKey, initialDataSource } = await createCustomApiDataSourcePage(page);

    await openDesignerByPid(page, pid);
    await selectSeedBlock(page);

    await expect(page.getByTestId('ds-endpoint-input')).toHaveValue(initialDataSource.endpoint);
    await expect(page.getByTestId('ds-method-select')).toHaveValue(initialDataSource.method);
    await expect(page.getByTestId('ds-adaptor-select')).toHaveValue(initialDataSource.adaptor);
    await expect(page.getByTestId('ds-pagination-checkbox')).toBeChecked();
    await expect(page.getByTestId('ds-param-key-0')).toHaveValue('keyword');
    await expect(page.getByTestId('ds-param-value-0')).toHaveValue(pageKey);

    const detectResp = page.waitForResponse(
      (response) =>
        response.url().includes('/api/dynamic/page_schema/list') &&
        response.url().includes(encodeURIComponent(pageKey)) &&
        response.status() === 200,
      { timeout: 10_000 },
    );
    await page.getByTestId('ds-test-detect-button').click();
    await detectResp;
    await expect(page.getByTestId('ds-test-status')).toContainText('records');

    await page.getByTestId('ds-code-btn').click();
    await expect(page.getByTestId('ds-code-textarea')).toBeVisible({ timeout: 5_000 });

    const editedDataSource: PageDataSourceConfig = {
      type: 'api',
      method: 'post',
      endpoint: '/api/dynamic/page_schema/list',
      adaptor: 'raw',
      pagination: false,
      params: {
        keyword: pageKey,
        pageSize: '3',
      },
    };
    await page.getByTestId('ds-code-textarea').fill(JSON.stringify(editedDataSource, null, 2));
    await page.getByTestId('ds-code-apply').click();
    await page.getByTestId('ds-form-btn').click();

    await expect(page.getByTestId('ds-endpoint-input')).toHaveValue(editedDataSource.endpoint);
    await expect(page.getByTestId('ds-method-select')).toHaveValue(editedDataSource.method);
    await expect(page.getByTestId('ds-adaptor-select')).toHaveValue(editedDataSource.adaptor);
    await expect(page.getByTestId('ds-pagination-checkbox')).not.toBeChecked();
    await expect(page.getByTestId('ds-param-key-0')).toHaveValue('keyword');
    await expect(page.getByTestId('ds-param-value-0')).toHaveValue(pageKey);
    await expect(page.getByTestId('ds-param-key-1')).toHaveValue('pageSize');
    await expect(page.getByTestId('ds-param-value-1')).toHaveValue('3');

    await saveDesignerAndWait(page, pid);

    const savedPage = await fetchPageByPid(page, pid);
    expect(savedPage.extension?.dataSource, 'saved extension.dataSource').toMatchObject(
      editedDataSource,
    );

    await openDesignerByPid(page, pid);
    await selectSeedBlock(page);

    await expect(page.getByTestId('ds-endpoint-input')).toHaveValue(editedDataSource.endpoint);
    await expect(page.getByTestId('ds-method-select')).toHaveValue(editedDataSource.method);
    await expect(page.getByTestId('ds-adaptor-select')).toHaveValue(editedDataSource.adaptor);
    await expect(page.getByTestId('ds-pagination-checkbox')).not.toBeChecked();
    await expect(page.getByTestId('ds-param-key-0')).toHaveValue('keyword');
    await expect(page.getByTestId('ds-param-value-0')).toHaveValue(pageKey);
    await expect(page.getByTestId('ds-param-key-1')).toHaveValue('pageSize');
    await expect(page.getByTestId('ds-param-value-1')).toHaveValue('3');
  });
});
