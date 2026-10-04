import type { Page } from '@playwright/test';
import { expect } from '@playwright/test';
import { uniqueId } from '../helpers';

type PageSchemaPayload = Record<string, any>;

export async function readPageByPid(page: Page, pid: string): Promise<PageSchemaPayload> {
  const resp = await page.request.get(`/api/pages/${pid}`);
  expect(resp.ok(), `Read page ${pid} failed: ${resp.status()}`).toBeTruthy();
  const body = await resp.json();
  expect(body.code, `read ${pid} API code`).toBe('0');
  return body.data ?? {};
}

export async function createEditablePageRecord(
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

export async function createPublishedRefreshFormPage(page: Page): Promise<string> {
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

export async function createPublishedNestedFormButtonsPage(page: Page): Promise<string> {
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
