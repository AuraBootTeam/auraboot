import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import {
  createFieldBindingData,
  createFieldData,
  createModelData,
} from '../../model-system/helpers/test-data';

async function openPagesTab(page: Page) {
  await page
    .locator('main button, main a')
    .filter({ hasText: /^页面(?:\s*\(\d+\))?$/ })
    .first()
    .click();
}

test.describe('Model Page Workbench E2E', () => {
  test('MW-01: bulk CRUD generation closes the loop from workbench to designer and preview', async ({
    page,
    api,
  }) => {
    test.setTimeout(120_000);

    const modelData = createModelData({
      code: `mw_${Date.now().toString(36)}`,
      displayName: '交付需求登记',
      description: 'Model page workbench E2E',
      modelType: 'entity',
    });

    const createdFieldPids: string[] = [];
    let modelPid: string | null = null;

    try {
      const createModelResp = await api.createModel(modelData);
      expect(createModelResp.code).toBe('0');
      expect(createModelResp.data).not.toBeNull();
      modelPid = createModelResp.data!.pid;

      const fieldTypes = ['string', 'integer'] as const;
      for (const [index, dataType] of fieldTypes.entries()) {
        const fieldResp = await api.createField(
          createFieldData(dataType, {
            code: `${modelData.code}_f${index + 1}`,
            uiSchema: { label: index === 0 ? '需求标题' : '预计工期（天）' },
          }),
        );
        expect(fieldResp.code).toBe('0');
        expect(fieldResp.data).not.toBeNull();
        createdFieldPids.push(fieldResp.data!.pid);

        const bindResp = await api.bindFieldToModel(
          modelPid,
          createFieldBindingData(fieldResp.data!.pid, {
            visible: true,
            displayOrder: index + 1,
            required: index === 0,
          }),
        );
        expect(bindResp.code).toBe('0');
      }

      const published = await api.publishModel(modelPid, 'CRUD browser regression fixture');
      expect(published.code).toBe('0');
      modelPid = published.data!.pid;

      await page.goto(`/meta/models/${modelPid}#pages`, { waitUntil: 'domcontentloaded' });
      await openPagesTab(page);

      await expect(page.getByRole('heading', { name: '页面工作台' })).toBeVisible({
        timeout: 10_000,
      });
      await expect(page.getByTestId('model-primary-page-action')).toHaveText('生成基础 CRUD');
      await expect(page.getByTestId('model-primary-page-preview')).toHaveCount(0);
      await expect(page.getByTestId('pages-empty-generate-crud')).toBeVisible();

      await page.getByTestId('pages-empty-generate-crud').click();
      await expect(page.getByTestId('crud-template-dialog')).toBeVisible();
      await expect(page.getByTestId('crud-generate-list')).toBeChecked();
      await expect(page.getByTestId('crud-generate-form')).toBeChecked();
      await expect(page.getByTestId('crud-generate-detail')).toBeChecked();
      await expect(page.getByTestId('crud-open-designer')).not.toBeChecked();
      await expect(page.getByTestId('crud-form-presentation')).toHaveCount(0);
      await test.info().attach('ZC-01', { body: await page.screenshot(), contentType: 'image/png' });

      const generateRespPromise = page.waitForResponse(
        (resp) =>
          resp.url().includes('/api/templates/crud/generate') &&
          resp.request().method() === 'POST' &&
          resp.status() === 200,
        { timeout: 20_000 },
      );

      await page.getByTestId('crud-generate-submit').click();

      const generateResp = await generateRespPromise;
      const submittedRequest = generateResp.request().postDataJSON();
      expect(submittedRequest.modelCode).toBe(modelData.code);
      const submittedConfig = submittedRequest.config;
      expect(submittedConfig).not.toHaveProperty('formPresentation');
      expect(submittedConfig).not.toHaveProperty('presentation');
      expect(submittedConfig).toMatchObject({ createPermissions: false, assignRoles: false });

      await test.info().attach('default-generation-request.json', {
        body: JSON.stringify(submittedRequest), contentType: 'application/json',
      });
      const generateBody = await generateResp.json();
      expect(generateBody.code).toBe('0');
      expect(generateBody.data?.generatedResources?.pages?.length).toBe(3);

      await page.getByTestId('crud-open-page').first().click();
      await expect(page).toHaveURL(new RegExp(`/p/${modelData.code}$`));
      await expect(page.getByTestId('dynamic-list')).toBeVisible();
      await expect(page.locator('body')).not.toContainText(/Page not found|Access forbidden|404/i);

      // Core mutations must originate in the generated UI, with real persistence readback.
      await page.getByTestId('toolbar-btn-create').click();
      await expect(page.getByTestId('dynamic-form')).toBeVisible();
      await expect(page.locator('#created_at')).toHaveCount(0);
      await expect(page.locator('#updated_at')).toHaveCount(0);
      await page.getByTestId('form-btn-submit').click();
      await expect(page.getByText('请填写需求标题', { exact: true })).toBeVisible();
      await test.info().attach('ZC-02a', { body: await page.screenshot(), contentType: 'image/png' });
      await page.locator(`#${modelData.code}_f1`).fill('供应链接口联调');
      await page.locator(`#${modelData.code}_f2`).fill('7');
      const createResponse = page.waitForResponse(response =>
        new URL(response.url()).pathname === `/api/dynamic/${modelData.code}` && response.request().method() === 'POST');
      await page.getByTestId('form-btn-submit').click();
      const created = await createResponse;
      expect(created.status()).toBe(200);
      const createdBody = await created.json();
      expect(createdBody.code).toBe('0');
      const recordPid = createdBody.data?.pid;
      expect(recordPid).toEqual(expect.any(String));
      await expect(page.getByTestId('dynamic-list')).toBeVisible();
      const row = page.getByRole('row').filter({ hasText: '供应链接口联调' });
      await expect(row).toContainText('7');
      await row.getByRole('button', { name: '更多操作', exact: true }).click();
      await page.getByRole('menuitem', { name: /编辑|Edit/ }).click();
      await expect(page.locator('#created_at')).toHaveCount(0);
      await expect(page.locator('#updated_at')).toHaveCount(0);
      await expect(page.locator(`#${modelData.code}_f1`)).toHaveValue('供应链接口联调');
      await page.locator(`#${modelData.code}_f1`).fill('供应链接口联调与交付培训');
      await page.locator(`#${modelData.code}_f2`).fill('14');
      const updateResponse = page.waitForResponse(response =>
        response.url().includes(`/api/dynamic/${modelData.code}/`) && response.request().method() === 'PUT');
      await page.getByTestId('form-btn-submit').click();
      const updated = await updateResponse;
      expect(updated.status()).toBe(200);
      expect((await updated.json()).code).toBe('0');
      await expect(page.getByTestId('dynamic-list')).toBeVisible();
      await page.reload();
      const savedRow = page.getByRole('row').filter({ hasText: '供应链接口联调与交付培训' });
      await expect(savedRow).toContainText('14');
      await savedRow.getByRole('button', { name: /查看|详情|View/ }).click();
      await expect(page).toHaveURL(new RegExp(`/view/${recordPid}$`));
      await expect(page.locator('main')).toContainText('供应链接口联调与交付培训');
      await expect(page.locator('main')).toContainText('14');
      const readback = await page.request.get(`/api/dynamic/${modelData.code}/${recordPid}`);
      expect(readback.status()).toBe(200);
      expect((await readback.json()).data).toMatchObject({
        [`${modelData.code}_f1`]: '供应链接口联调与交付培训',
        [`${modelData.code}_f2`]: 14,
      });
      await test.info().attach('ZC-02b', { body: await page.screenshot(), contentType: 'image/png' });

      const relatedPagesResp = await page.request.get(`/api/meta/models/${modelPid}/pages`);
      expect(relatedPagesResp.ok()).toBeTruthy();
      const relatedPagesBody = await relatedPagesResp.json();
      expect(relatedPagesBody.code).toBe('0');
      expect((relatedPagesBody.data ?? []).length).toBeGreaterThanOrEqual(3);

      await page.goto(`/meta/models/${modelPid}#pages`, { waitUntil: 'domcontentloaded' });
      await openPagesTab(page);
      await expect(page.getByRole('heading', { name: '页面工作台' })).toBeVisible({
        timeout: 10_000,
      });

      await expect(page.getByTestId('model-primary-page-action')).toHaveText('打开页面设计');
      await expect(page.getByTestId('model-primary-page-preview')).toBeVisible();

      for (const kind of ['list', 'detail', 'form'] as const) {
        await expect(page.getByTestId(`standard-page-card-${kind}`)).toContainText('已创建');
        await expect(page.getByTestId(`standard-page-${kind}-edit`)).toBeVisible();
        await expect(page.getByTestId(`standard-page-${kind}-preview`)).toBeVisible();
      }

      const reopenDesignerPromise = page.waitForURL(/\/page-designer\/.+/, { timeout: 20_000 });
      await page.getByTestId('model-primary-page-action').click();
      await reopenDesignerPromise;
      await expect(page.locator('body')).not.toContainText(/Page not found|Access forbidden|404/i);

      await page.goto(`/meta/models/${modelPid}#pages`, { waitUntil: 'domcontentloaded' });
      await openPagesTab(page);
      const modelDetailPath = new URL(page.url()).pathname;
      await page.getByTestId('standard-page-detail-preview').click();
      await expect
        .poll(() => new URL(page.url()).pathname, {
          timeout: 20_000,
          message: 'expected detail page preview to leave the model detail page',
        })
        .not.toBe(modelDetailPath);
      await expect(page).toHaveURL(/\/(dynamic|p)\//, { timeout: 20_000 });
      await expect(page.locator('body')).not.toContainText(/Page not found|Access forbidden|404/i);
    } finally {
      if (modelPid) {
        await api.deleteModel(modelPid).catch(() => null);
      }
      for (const fieldPid of createdFieldPids) {
        await api.deleteField(fieldPid).catch(() => null);
      }
    }
  });
});
