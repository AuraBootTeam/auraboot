import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { executeCommandViaApi, uniqueId } from '../helpers';

type PageSchemaPayload = Record<string, any>;

async function readPageByPid(page: Page, pid: string): Promise<PageSchemaPayload> {
  const resp = await page.request.get(`/api/pages/${pid}`);
  expect(resp.ok(), `Read page ${pid} failed: ${resp.status()}`).toBeTruthy();
  const body = await resp.json();
  expect(body.code, `read ${pid} API code`).toBe('0');
  return body.data ?? {};
}

async function createEditablePageRecord(
  page: Page,
  name: string,
  pageKey: string,
): Promise<string> {
  const payload = {
    name,
    pageKey,
    title: name,
    kind: 'list',
    modelCode: 'page_schema',
    profile: 'admin',
    layout: { type: 'stack', gap: 12 },
    blocks: [
      {
        id: 'form_refresh_runtime_table',
        blockType: 'table',
        columns: [{ field: 'name', label: 'Name', width: 220 }],
      },
    ],
    schemaVersion: 4,
    metaInfo: { runtimeE2E: true, formRefreshRuntime: true },
    semver: '0.1.0',
  };

  const resp = await page.request.post('/api/pages', { data: payload });
  expect(resp.ok(), `Create editable page failed: ${resp.status()}`).toBeTruthy();
  const body = await resp.json();
  expect(body.code, 'create editable page API code').toBe('0');
  const pid = String(body.data?.pid ?? '');
  expect(pid, 'created editable page pid').toBeTruthy();
  return pid;
}

async function createPublishedRefreshFormPage(page: Page): Promise<string> {
  const pageKey = uniqueId('form_refresh_custom_form').replace(/-/g, '_');
  const title = `Form refresh custom form ${pageKey}`;
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
        id: 'form_refresh_fields',
        blockType: 'form-section',
        title: 'Refresh form section',
        fields: [
          { field: 'name', label: 'Name', required: true, span: 12 },
          { field: 'page_key', label: 'Page Key', readonly: true, span: 12 },
        ],
      },
      {
        id: 'form_refresh_buttons',
        blockType: 'form-buttons',
        buttons: [
          {
            code: 'refresh',
            label: 'Refresh form data',
          },
        ],
      },
    ],
    schemaVersion: 4,
    metaInfo: { runtimeE2E: true, formRefreshRuntime: true },
    semver: '0.1.0',
  };

  const createResp = await page.request.post('/api/pages', { data: payload });
  expect(createResp.ok(), `Create refresh form page failed: ${createResp.status()}`).toBeTruthy();
  const createBody = await createResp.json();
  expect(createBody.code, 'create refresh form page API code').toBe('0');
  const pid = String(createBody.data?.pid ?? '');
  expect(pid, 'created refresh form page pid').toBeTruthy();

  const publishResp = await page.request.post(`/api/pages/${pid}/publish`);
  expect(
    publishResp.ok(),
    `Publish refresh form page failed: ${publishResp.status()}`,
  ).toBeTruthy();
  const publishBody = await publishResp.json();
  expect(publishBody.code, 'publish refresh form page API code').toBe('0');
  return pageKey;
}

async function createPublishedNestedFormButtonsPage(page: Page): Promise<string> {
  const pageKey = uniqueId('form_nested_buttons').replace(/-/g, '_');
  const title = `Form nested buttons ${pageKey}`;
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
        id: 'form_nested_fields',
        blockType: 'form-section',
        title: 'Nested button form section',
        fields: [
          { field: 'name', label: 'Name', required: true, span: 12 },
          { field: 'page_key', label: 'Page Key', readonly: true, span: 12 },
        ],
      },
      {
        id: 'form_runtime_nested_actions_tabs',
        blockType: 'tabs',
        title: 'Nested runtime actions',
        tabs: [
          {
            key: 'actions',
            label: 'Nested actions',
            blocks: [
              {
                id: 'form_runtime_nested_update_buttons',
                blockType: 'form-buttons',
                buttons: [
                  {
                    code: 'nested_update',
                    label: 'Nested update',
                    primary: true,
                    action: {
                      type: 'command',
                      command: 'pgm:update_page_schema',
                    },
                  },
                ],
              },
            ],
          },
        ],
      },
    ],
    schemaVersion: 4,
    metaInfo: { runtimeE2E: true, nestedFormButtonsRuntime: true },
    semver: '0.1.0',
  };

  const createResp = await page.request.post('/api/pages', { data: payload });
  expect(
    createResp.ok(),
    `Create nested form buttons page failed: ${createResp.status()}`,
  ).toBeTruthy();
  const createBody = await createResp.json();
  expect(createBody.code, 'create nested form buttons page API code').toBe('0');
  const pid = String(createBody.data?.pid ?? '');
  expect(pid, 'created nested form buttons page pid').toBeTruthy();

  const publishResp = await page.request.post(`/api/pages/${pid}/publish`);
  expect(
    publishResp.ok(),
    `Publish nested form buttons page failed: ${publishResp.status()}`,
  ).toBeTruthy();
  const publishBody = await publishResp.json();
  expect(publishBody.code, 'publish nested form buttons page API code').toBe('0');
  return pageKey;
}

async function updatePageNameViaCommand(page: Page, targetPid: string, refreshedName: string) {
  const result = await executeCommandViaApi(
    page,
    'pgm:update_page_schema',
    { name: refreshedName },
    targetPid,
    'update',
  );
  expect(result.code, 'update page command code').toBe('0');
  await expect
    .poll(async () => String((await readPageByPid(page, targetPid)).name ?? ''), {
      timeout: 10_000,
    })
    .toBe(refreshedName);
}

test.describe('Page Designer form-buttons refresh runtime', () => {
  test('refresh button bypasses submit validation and reloads the edit form record', async ({
    page,
  }) => {
    const formPageKey = await createPublishedRefreshFormPage(page);
    const targetPageKey = uniqueId('form_refresh_target').replace(/-/g, '_');
    const initialName = `Form refresh initial ${targetPageKey}`;
    const refreshedName = `Form refresh updated ${targetPageKey}`;
    const targetPid = await createEditablePageRecord(page, initialName, targetPageKey);

    await page.goto(`/p/c/${formPageKey}/edit/${targetPid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('dynamic-form')).toBeVisible();
    const nameInput = page.getByTestId('field-name').locator('input, textarea').first();
    await expect(nameInput).toHaveValue(initialName);
    await expect(page.getByTestId('form-btn-refresh')).toBeVisible();

    await nameInput.fill('');
    await updatePageNameViaCommand(page, targetPid, refreshedName);

    const reloadResponse = page.waitForResponse(
      (response) =>
        response.url().includes(`/api/dynamic/page_schema/${targetPid}`) &&
        response.request().method() === 'GET',
      { timeout: 5_000 },
    );
    await page.getByTestId('form-btn-refresh').click();
    expect((await reloadResponse).ok(), 'form refresh record reload response').toBeTruthy();
    await expect(nameInput).toHaveValue(refreshedName);
    await expect(page.getByTestId('field-name')).not.toContainText(
      /required|必填|不能为空|请填写/i,
    );
  });

  test('nested tabs form button executes an update command with persisted side effects', async ({
    page,
  }) => {
    const formPageKey = await createPublishedNestedFormButtonsPage(page);
    const targetPageKey = uniqueId('form_nested_target').replace(/-/g, '_');
    const initialName = `Nested form initial ${targetPageKey}`;
    const updatedName = `Nested form updated ${targetPageKey}`;
    const targetPid = await createEditablePageRecord(page, initialName, targetPageKey);

    await page.goto(`/p/c/${formPageKey}/edit/${targetPid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('dynamic-form')).toBeVisible();
    const nameInput = page.getByTestId('field-name').locator('input, textarea').first();
    await expect(nameInput).toHaveValue(initialName);
    await expect(page.getByRole('tab', { name: 'Nested actions' })).toBeVisible();
    await expect(page.getByTestId('form-btn-nested_update')).toBeVisible();

    await nameInput.fill(updatedName);
    const commandRequestCapture: { body?: Record<string, any> } = {};
    page.on('request', (request) => {
      if (
        request.url().includes('/api/meta/commands/execute/pgm:update_page_schema') &&
        request.method() === 'POST'
      ) {
        const postData = request.postData();
        commandRequestCapture.body = postData ? JSON.parse(postData) : undefined;
      }
    });
    const updateResponse = page.waitForResponse(
      (response) =>
        response.url().includes('/api/meta/commands/execute/pgm:update_page_schema') &&
        response.request().method() === 'POST',
      { timeout: 15_000 },
    );
    await page.getByTestId('form-btn-nested_update').click();
    const response = await updateResponse;
    expect(response.ok(), 'nested form button update command response').toBeTruthy();
    const body = await response.json();
    expect(body.code, 'nested form button update command code').toBe('0');
    const commandRequestBody = commandRequestCapture.body;
    expect(commandRequestBody?.targetRecordId, 'nested form button command target').toBe(targetPid);
    expect(commandRequestBody?.operationType, 'nested form button command operation').toBe(
      'UPDATE',
    );
    expect(commandRequestBody?.payload?.name, 'nested form button command payload name').toBe(
      updatedName,
    );

    await expect
      .poll(async () => String((await readPageByPid(page, targetPid)).name ?? ''), {
        timeout: 10_000,
      })
      .toBe(updatedName);
  });
});
