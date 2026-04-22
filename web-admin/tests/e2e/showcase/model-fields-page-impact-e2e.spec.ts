import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import {
  createFieldBindingData,
  createFieldData,
  createModelData,
} from '../../model-system/helpers/test-data';

async function openTab(page: Page, name: RegExp) {
  await page
    .locator('main button, main a')
    .filter({ hasText: name })
    .first()
    .click();
}

test.describe('Model Fields to Pages Impact E2E', () => {
  test('MF-01: field impact notice bridges pages workbench and designer, then supports create-and-bind field', async ({
    page,
    api,
  }) => {
    test.setTimeout(60_000);

    const unique = Date.now().toString(36);
    const modelData = createModelData({
      code: `mf_${unique}`,
      displayName: `MF ${unique}`,
      description: 'Model fields impact E2E',
      modelType: 'entity',
    });

    const cleanupFieldPids = new Set<string>();
    let modelPid: string | null = null;

    try {
      const createModelResp = await api.createModel(modelData);
      expect(createModelResp.code).toBe('0');
      expect(createModelResp.data).not.toBeNull();
      modelPid = createModelResp.data!.pid;

      const seedFieldResp = await api.createField(
        createFieldData('string', {
          code: `${modelData.code}_seed`,
          uiSchema: { label: 'MF Seed Field' },
        }),
      );
      expect(seedFieldResp.code).toBe('0');
      expect(seedFieldResp.data).not.toBeNull();
      cleanupFieldPids.add(seedFieldResp.data!.pid);

      const bindResp = await api.bindFieldToModel(
        modelPid,
        createFieldBindingData(seedFieldResp.data!.pid, {
          visible: true,
          editable: true,
          displayOrder: 1,
        }),
      );
      expect(bindResp.code).toBe('0');

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
      expect(generateBody.data?.generatedResources?.pages?.length).toBe(3);

      await page.goto(`/meta/models/${modelPid}#fields`, { waitUntil: 'domcontentloaded' }).catch(() => {});
      await openTab(page, /^字段(?:\s*\(\d+\))?$/);

      await expect(page.getByTestId('fields-page-impact-notice')).toBeVisible({ timeout: 10_000 });
      await expect(page.getByTestId('fields-page-impact-notice')).toContainText('字段变更可能影响页面配置');

      await page.getByTestId('fields-impact-view-pages').click();
      await expect(page.getByRole('heading', { name: '页面工作台' })).toBeVisible({ timeout: 10_000 });
      await expect(page.getByTestId('standard-page-card-detail')).toContainText('已创建');

      await page.goto(`/meta/models/${modelPid}#fields`, { waitUntil: 'domcontentloaded' }).catch(() => {});
      await openTab(page, /^字段(?:\s*\(\d+\))?$/);
      const designerNav = page.waitForURL(/\/page-designer\/.+/, { timeout: 20_000 });
      await page.getByTestId('fields-impact-open-designer').click();
      await designerNav;
      await expect(page.locator('body')).not.toContainText(/Page not found|Access forbidden|404/i);

      await page.goto(`/meta/models/${modelPid}#fields`, { waitUntil: 'domcontentloaded' }).catch(() => {});
      await openTab(page, /^字段(?:\s*\(\d+\))?$/);
      await page.getByTestId('model-fields-add-button').click();
      await expect(page.getByTestId('field-selection-dialog')).toBeVisible({ timeout: 10_000 });
      await page.getByTestId('field-selection-tab-create').click();
      const dialog = page.getByTestId('field-selection-dialog');
      await dialog.getByPlaceholder('例如: user_name, email, age').fill(`${modelData.code}_ui_added`);
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
      await expect(page.getByTestId('fields-page-impact-notice')).toBeVisible();
      await expect(page.locator('table')).toContainText(`${modelData.code}_ui_added`);
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
