import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { uniqueId } from '../helpers';

const FORM_PAGE_KEY = 'page_schema_form';

type PageSchemaPayload = Record<string, any>;

async function readPageByKey(page: Page, pageKey: string): Promise<PageSchemaPayload> {
  const resp = await page.request.get(`/api/pages/key/${pageKey}`);
  expect(resp.ok(), `Read page ${pageKey} failed: ${resp.status()}`).toBeTruthy();
  const body = await resp.json();
  expect(body.code, `read ${pageKey} API code`).toBe('0');
  return body.data ?? {};
}

async function readPageByPid(page: Page, pid: string): Promise<PageSchemaPayload> {
  const resp = await page.request.get(`/api/pages/${pid}`);
  expect(resp.ok(), `Read page ${pid} failed: ${resp.status()}`).toBeTruthy();
  const body = await resp.json();
  expect(body.code, `read ${pid} API code`).toBe('0');
  return body.data ?? {};
}

function pageUpdatePayload(schema: PageSchemaPayload): PageSchemaPayload {
  const rawTitle = schema.title ?? schema.name;
  const title =
    typeof rawTitle === 'string' && /[^\x00-\x7F]/.test(rawTitle)
      ? { 'zh-CN': rawTitle, en: schema.pageKey ?? schema.name ?? 'Page' }
      : rawTitle;

  return {
    name: schema.name,
    pageKey: schema.pageKey,
    title,
    kind: schema.kind,
    modelCode: schema.modelCode,
    profile: schema.profile ?? 'admin',
    layout: schema.layout ?? { type: 'grid', cols: 12 },
    schemaVersion: schema.schemaVersion ?? 4,
    extension: schema.extension ?? {},
    dataSources: schema.dataSources ?? {},
    blocks: schema.blocks ?? [],
    metaInfo: schema.metaInfo ?? {},
    semver: schema.semver ?? '0.1.0',
  };
}

async function updateAndPublishPage(page: Page, pid: string, payload: PageSchemaPayload) {
  const updateResp = await page.request.put(`/api/pages/${pid}`, {
    data: pageUpdatePayload(payload),
  });
  if (!updateResp.ok()) {
    throw new Error(`Update page ${pid} failed: ${updateResp.status()} ${await updateResp.text()}`);
  }
  const updateBody = await updateResp.json();
  expect(updateBody.code, `update page ${pid} API code`).toBe('0');

  const publishResp = await page.request.post(`/api/pages/${pid}/publish`);
  if (!publishResp.ok()) {
    const publishText = await publishResp.text();
    if (!(publishResp.status() === 422 && publishText.includes('已经发布'))) {
      throw new Error(`Publish page ${pid} failed: ${publishResp.status()} ${publishText}`);
    }
    return;
  }
  const publishBody = await publishResp.json();
  expect(publishBody.code, `publish page ${pid} API code`).toBe('0');
}

function withRefreshButton(schema: PageSchemaPayload): PageSchemaPayload {
  const blocks = (schema.blocks ?? []).map((block: Record<string, any>) => {
    if (block.blockType !== 'form-buttons') return block;
    const existingButtons = Array.isArray(block.buttons) ? block.buttons : [];
    const filteredButtons = existingButtons.filter(
      (button: Record<string, any>) => button.code !== 'refresh',
    );
    return {
      ...block,
      buttons: [
        {
          code: 'refresh',
          label: 'Refresh form data',
        },
        ...filteredButtons,
      ],
    };
  });

  return {
    ...schema,
    blocks,
  };
}

function withoutRefreshButton(schema: PageSchemaPayload): PageSchemaPayload {
  const blocks = (schema.blocks ?? []).map((block: Record<string, any>) => {
    if (block.blockType !== 'form-buttons') return block;
    const existingButtons = Array.isArray(block.buttons) ? block.buttons : [];
    return {
      ...block,
      buttons: existingButtons.filter((button: Record<string, any>) => button.code !== 'refresh'),
    };
  });

  return {
    ...schema,
    blocks,
  };
}

async function createEditablePageRecord(page: Page, name: string, pageKey: string): Promise<string> {
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

test.describe('Page Designer form-buttons refresh runtime', () => {
  test('refresh button bypasses submit validation and reloads the edit form record', async ({
    page,
  }) => {
    const formPage = await readPageByKey(page, FORM_PAGE_KEY);
    const formPagePid = String(formPage.pid ?? '');
    expect(formPagePid, 'page_schema_form pid').toBeTruthy();

    const originalFormPayload = pageUpdatePayload(withoutRefreshButton(formPage));
    const targetPageKey = uniqueId('form_refresh_target').replace(/-/g, '_');
    const initialName = `Form refresh initial ${targetPageKey}`;
    const refreshedName = `Form refresh updated ${targetPageKey}`;
    const targetPid = await createEditablePageRecord(page, initialName, targetPageKey);

    try {
      await updateAndPublishPage(page, formPagePid, withRefreshButton(formPage));

      await page.goto(`/p/page_schema/edit/${targetPid}`, { waitUntil: 'domcontentloaded' });
      await expect(page.getByTestId('dynamic-form')).toBeVisible();
      const nameInput = page.getByTestId('field-name').locator('input, textarea').first();
      await expect(nameInput).toHaveValue(initialName);
      await expect(page.getByTestId('form-btn-refresh')).toBeVisible();

      await nameInput.fill('');
      await updateAndPublishPage(page, targetPid, {
        ...(await readPageByPid(page, targetPid)),
        name: refreshedName,
      });

      const reloadResponse = page.waitForResponse(
        (response) =>
          response.url().includes(`/api/dynamic/page_schema/${targetPid}`) &&
          response.request().method() === 'GET',
        { timeout: 5_000 },
      );
      await page.getByTestId('form-btn-refresh').click();
      expect((await reloadResponse).ok(), 'form refresh record reload response').toBeTruthy();
      await expect(nameInput).toHaveValue(refreshedName);
      await expect(page.getByTestId('field-name')).not.toContainText(/required|必填|不能为空|请填写/i);
    } finally {
      await updateAndPublishPage(page, formPagePid, originalFormPayload);
      await page.request.delete(`/api/pages/${targetPid}`).catch(() => undefined);
    }
  });
});
