/**
 * Model Lifecycle E2E Tests (UI verification only)
 *
 * API tests (INT-01 ~ INT-07) migrated to: tests/api/model-lifecycle.spec.ts
 *
 * INT-08: Navigate to dynamic page in UI
 *
 * @since 4.0.0
 */

import { test, expect } from '../../fixtures';
import { ErrorCodes } from '~/services/http-client/types';

function generateCode(prefix: string = 'intg'): string {
  const timestamp = Date.now().toString(36);
  const random = Math.random().toString(36).substring(2, 6);
  return `${prefix}_e2e_${timestamp}_${random}`;
}

test.describe('Model Lifecycle UI', () => {
  test.describe.configure({ mode: 'serial' });

  let modelPid: string | null = null;
  let modelCode: string;
  let modelPublished = false;

  // Setup: create and publish model via API for UI verification
  test.beforeAll(async ({ request }) => {
    modelCode = generateCode('intg');

    try {
      // Create model
      const createResp = await request.post(`/api/meta/models`, {
        data: {
          code: modelCode,
          displayName: `Integration Test Model ${modelCode}`,
          description: 'Model lifecycle integration test',
          modelType: 'entity',
        },
      });
      if (!createResp.ok()) return;
      const createBody = await createResp.json();
      modelPid = createBody.data?.pid;
      if (!modelPid) return;

      // Add field
      const fieldResp = await request.post(`/api/meta/fields`, {
        data: { code: `test_name_${Date.now().toString(36)}`, dataType: 'string' },
      });
      if (fieldResp.ok()) {
        const fieldBody = await fieldResp.json();
        await request.post(`/api/meta/models/${modelPid}/fields/${fieldBody.data?.pid}`);
      }

      // Publish
      const publishResp = await request.post(`/api/meta/models/${modelPid}/publish`);
      if (publishResp.ok()) {
        const publishBody = await publishResp.json();
        modelPublished = publishBody.code === ErrorCodes.SUCCESS;
      }
    } catch (error) {
      console.warn('Model lifecycle UI setup failed:', error);
    }
  });

  test('INT-08: Navigate to dynamic page in UI', async ({ page }) => {
    test.skip(!modelCode, 'Model creation did not complete in beforeAll');
    test.skip(!modelPublished, 'Model publish did not complete successfully in beforeAll');

    const urlTableName = modelCode;
    await page.goto(`/p/${urlTableName}`);
    await page.waitForLoadState('domcontentloaded');

    await page
      .locator(
        'table, main, [role="table"], [role="grid"], h3:has-text("加载失败"), h1:has-text("404")',
      )
      .first()
      .waitFor({ timeout: 8000 })
      .catch(() => {});

    const errorPage = page
      .locator('h1:has-text("404")')
      .or(page.getByText('The requested page could not be found'))
      .or(page.getByText('Page Unavailable'));
    const hasError = await errorPage
      .first()
      .isVisible({ timeout: 2000 })
      .catch(() => false);
    expect(hasError).toBe(false);

    const hasLoadError = await page
      .locator('h3:has-text("加载失败")')
      .isVisible({ timeout: 1000 })
      .catch(() => false);
    expect(hasLoadError).toBe(false);

    const hasContent = await page
      .locator('table, main, [role="table"], [role="grid"]')
      .first()
      .isVisible({ timeout: 5000 })
      .catch(() => false);
    expect(hasContent).toBe(true);
  });

  test.afterAll(async ({ request }) => {
    if (modelPid) {
      try {
        await request.delete(`/api/meta/models/${modelPid}`);
      } catch {
        console.warn(`[Cleanup] Failed to delete model ${modelPid}`);
      }
    }
  });
});
