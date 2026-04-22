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
    test.setTimeout(45_000);

    const modelData = createModelData({
      code: `mw_${Date.now().toString(36)}`,
      displayName: `MW ${Date.now().toString(36)}`,
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
            uiSchema: { label: `MW Field ${index + 1}` },
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
          }),
        );
        expect(bindResp.code).toBe('0');
      }

      await page.goto(`/meta/models/${modelPid}#pages`, { waitUntil: 'domcontentloaded' }).catch(() => {});
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
      await expect(page.getByTestId('crud-open-designer')).toBeChecked();

      const generateRespPromise = page.waitForResponse(
        (resp) =>
          resp.url().includes('/api/templates/crud/generate') &&
          resp.request().method() === 'POST' &&
          resp.status() === 200,
        { timeout: 20_000 },
      );

      const designerNavPromise = page.waitForURL(/\/page-designer\/.+/, { timeout: 20_000 });

      await page.getByTestId('crud-generate-submit').click();

      const generateResp = await generateRespPromise;
      await designerNavPromise;

      const generateBody = await generateResp.json();
      expect(generateBody.code).toBe('0');
      expect(generateBody.data?.generatedResources?.pages?.length).toBe(3);

      await expect(
        page
          .locator('button:has-text("Save"), button:has-text("保存"), [data-testid="designer-canvas"]')
          .first(),
      ).toBeVisible({ timeout: 15_000 });
      await expect(page.locator('body')).not.toContainText(/Page not found|Access forbidden|404/i);

      const relatedPagesResp = await page.request.get(`/api/meta/models/${modelPid}/pages`);
      expect(relatedPagesResp.ok()).toBeTruthy();
      const relatedPagesBody = await relatedPagesResp.json();
      expect(relatedPagesBody.code).toBe('0');
      expect((relatedPagesBody.data ?? []).length).toBeGreaterThanOrEqual(3);

      await page.goto(`/meta/models/${modelPid}#pages`, { waitUntil: 'domcontentloaded' }).catch(() => {});
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

      await page.goto(`/meta/models/${modelPid}#pages`, { waitUntil: 'domcontentloaded' }).catch(() => {});
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
