import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import {
  createFieldBindingData,
  createFieldData,
  createModelData,
} from '../../model-system/helpers/test-data';

type PageSchemaDto = {
  pid?: string;
  pageKey?: string;
  blocks?: unknown[];
};

async function openTab(page: Page, name: RegExp) {
  await page
    .locator('main button, main a')
    .filter({ hasText: name })
    .first()
    .click();
}

function pageSchemaContainsField(value: unknown, fieldCode: string): boolean {
  if (typeof value === 'string') {
    return value === fieldCode;
  }
  if (Array.isArray(value)) {
    return value.some((item) => pageSchemaContainsField(item, fieldCode));
  }
  if (value && typeof value === 'object') {
    return Object.values(value as Record<string, unknown>).some((item) =>
      pageSchemaContainsField(item, fieldCode),
    );
  }
  return false;
}

async function fetchPageSchema(page: Page, pageKey: string): Promise<PageSchemaDto> {
  const response = await page.request.get(`/api/pages/key/${pageKey}`);
  expect(response.ok(), `fetch page schema ${pageKey} failed`).toBeTruthy();
  const body = await response.json();
  expect(body.code).toBe('0');
  expect(body.data).toBeTruthy();
  return body.data as PageSchemaDto;
}

test.describe('Model Field Change Page Sync E2E', () => {
  test('MF-02: fields added after CRUD generation do not auto-sync existing page schemas, but the designer path remains available', async ({
    page,
    api,
  }) => {
    test.setTimeout(60_000);

    const unique = Date.now().toString(36);
    const modelData = createModelData({
      code: `mfs_${unique}`,
      displayName: `MFS ${unique}`,
      description: 'Model field change page sync E2E',
      modelType: 'entity',
    });

    const cleanupFieldPids = new Set<string>();
    let modelPid: string | null = null;

    try {
      const createModelResp = await api.createModel(modelData);
      expect(createModelResp.code).toBe('0');
      expect(createModelResp.data).not.toBeNull();
      modelPid = createModelResp.data!.pid;

      const initialFieldCode = `${modelData.code}_seed`;
      const addedFieldCode = `${modelData.code}_after_generate`;

      const seedFieldResp = await api.createField(
        createFieldData('string', {
          code: initialFieldCode,
          uiSchema: { label: 'Seed Field' },
        }),
      );
      expect(seedFieldResp.code).toBe('0');
      expect(seedFieldResp.data).not.toBeNull();
      cleanupFieldPids.add(seedFieldResp.data!.pid);

      const bindSeedResp = await api.bindFieldToModel(
        modelPid,
        createFieldBindingData(seedFieldResp.data!.pid, {
          visible: true,
          displayOrder: 1,
        }),
      );
      expect(bindSeedResp.code).toBe('0');

      const generateResp = await page.request.post('/api/templates/crud/generate', {
        data: {
          modelCode: modelData.code,
          config: {
            generateList: true,
            generateForm: true,
            generateDetail: true,
            createMenu: false,
            createPermissions: false,
            assignRoles: false,
            enableExport: false,
            enableImport: false,
          },
        },
      });
      expect(generateResp.ok()).toBeTruthy();
      const generateBody = await generateResp.json();
      expect(generateBody.code).toBe('0');

      const formPageBefore = await fetchPageSchema(page, `${modelData.code}_form`);
      expect(pageSchemaContainsField(formPageBefore.blocks ?? [], initialFieldCode)).toBe(true);
      expect(pageSchemaContainsField(formPageBefore.blocks ?? [], addedFieldCode)).toBe(false);

      await page.goto(`/meta/models/${modelPid}#fields`, { waitUntil: 'domcontentloaded' }).catch(() => {});
      await openTab(page, /^字段(?:\s*\(\d+\))?$/);
      await page.getByTestId('model-fields-add-button').click();
      await expect(page.getByTestId('field-selection-dialog')).toBeVisible({ timeout: 10_000 });
      await page.getByTestId('field-selection-tab-create').click();
      const dialog = page.getByTestId('field-selection-dialog');
      await dialog.getByPlaceholder('例如: user_name, email, age').fill(addedFieldCode);
      await dialog.locator('select').first().selectOption('string');

      const createdFieldResponse = page.waitForResponse(
        (resp) =>
          resp.url().includes('/api/meta/fields') &&
          resp.request().method() === 'POST' &&
          resp.status() === 200,
        { timeout: 20_000 },
      );
      const bindCreatedFieldResponse = page.waitForResponse(
        (resp) =>
          resp.url().includes(`/api/meta/models/${modelPid}/fields/bind`) &&
          resp.request().method() === 'POST' &&
          resp.status() === 200,
        { timeout: 20_000 },
      );

      await page.getByTestId('field-selection-create-bind').click();

      const createdFieldRaw = await createdFieldResponse;
      await bindCreatedFieldResponse;
      const createdFieldBody = await createdFieldRaw.json();
      if (createdFieldBody?.data?.pid) {
        cleanupFieldPids.add(createdFieldBody.data.pid);
      }

      await expect(page.getByTestId('field-selection-dialog')).toHaveCount(0, { timeout: 10_000 });
      await expect(page.locator('table')).toContainText(addedFieldCode);

      const formPageAfter = await fetchPageSchema(page, `${modelData.code}_form`);
      expect(pageSchemaContainsField(formPageAfter.blocks ?? [], initialFieldCode)).toBe(true);
      expect(pageSchemaContainsField(formPageAfter.blocks ?? [], addedFieldCode)).toBe(false);

      const designerNav = page.waitForURL(/\/page-designer\/.+/, { timeout: 20_000 });
      await page.getByTestId('fields-impact-open-designer').click();
      await designerNav;
      await expect(page.locator('body')).not.toContainText(/Page not found|Access forbidden|404/i);
    } finally {
      if (modelPid) {
        await api.deleteModel(modelPid).catch(() => null);
      }
      for (const fieldPid of cleanupFieldPids) {
        await api.deleteField(fieldPid).catch(() => null);
      }
    }
  });
});
