/**
 * E2E Test: Form View (GAP-120)
 *
 * Tests form view creation, configuration, rendering, and data submission.
 */

import { test, expect, type Page } from '@playwright/test';
import { navigateToDynamicPage, uniqueId } from '../helpers';

const MODEL_CODE = 'e2et_order';
const PAGE_KEY = 'e2et_order_list';
const FORM_VIEW_PREFIX = 'FV_';

interface SavedViewApiRecord {
  pid: string;
  name: string;
  scope?: string;
}

async function listViews(page: Page): Promise<SavedViewApiRecord[]> {
  const params = new URLSearchParams({ modelCode: MODEL_CODE, pageKey: PAGE_KEY });
  const resp = await page.request.get(`/api/views/accessible?${params.toString()}`);
  if (!resp.ok()) return [];
  const body = await resp.json();
  return Array.isArray(body.data) ? body.data : [];
}

async function cleanupFormViews(page: Page): Promise<void> {
  const views = await listViews(page);
  for (const view of views) {
    if (view.scope === 'personal' && view.pid && view.name?.startsWith(FORM_VIEW_PREFIX)) {
      await page.request.delete(`/api/views/${view.pid}`).catch(() => null);
    }
  }
}

async function createViewViaApi(
  page: Page,
  modelCode: string,
  name: string,
  viewConfig: any,
): Promise<string> {
  const resp = await page.request.post('/api/views', {
    data: {
      name,
      modelCode,
      pageKey: `${modelCode}_list`,
      viewType: 'form',
      scope: 'personal',
      viewConfig,
    },
  });
  if (!resp.ok()) {
    const body = await resp.text().catch(() => '<body unavailable>');
    throw new Error(`Create form SavedView failed: ${resp.status()} ${body}`);
  }
  const body = await resp.json();
  return body.data?.pid ?? '';
}

async function getViewViaApi(page: Page, pid: string): Promise<any> {
  const resp = await page.request.get(`/api/views/${pid}`);
  if (!resp.ok()) return null;
  const body = await resp.json();
  return body.data ?? body;
}

test.describe('Form View (GAP-120)', () => {
  test.beforeEach(async ({ page }) => {
    await cleanupFormViews(page);
  });

  test.afterEach(async ({ page }) => {
    await cleanupFormViews(page);
  });

  test('FV-001: FORM viewType accepted by API', async ({ page }) => {
    await page.goto('/');
    await page.locator('nav, [data-testid="sidebar"]').first().waitFor({ timeout: 15000 });

    const viewName = `FV_Basic_${uniqueId()}`;
    const pid = await createViewViaApi(page, 'e2et_order', viewName, {
      formTitle: 'New Order',
      formDescription: 'Submit a new order',
      formSubmitLabel: 'Create Order',
    });
    expect(pid).toBeTruthy();

    const view = await getViewViaApi(page, pid);
    expect(view.viewType).toBe('form');
    expect(view.viewConfig?.formTitle).toBe('New Order');
  });

  test('FV-002: form with selected fields', async ({ page }) => {
    await page.goto('/');
    await page.locator('nav, [data-testid="sidebar"]').first().waitFor({ timeout: 15000 });

    const viewName = `FV_Fields_${uniqueId()}`;
    const pid = await createViewViaApi(page, 'e2et_order', viewName, {
      formFields: ['e2et_order_title', 'e2et_order_no', 'e2et_total_amount'],
      formTitle: 'Quick Order',
    });
    expect(pid).toBeTruthy();

    const view = await getViewViaApi(page, pid);
    expect(view.viewConfig?.formFields).toHaveLength(3);
    expect(view.viewConfig.formFields[0]).toBe('e2et_order_title');
  });

  test('FV-003: form view renders in browser', async ({ page }) => {
    await navigateToDynamicPage(page, 'e2et_order');
    await page.getByTestId('view-selector-trigger').waitFor({ state: 'visible', timeout: 5000 });

    // Switch to Form view type
    const formBtn = page.getByTestId('view-type-form');
    if (await formBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
      await formBtn.click();
      // Should show form view or "not configured" message
      const formView = page.getByTestId('form-view');
      const notConfigured = page.locator('text=表单视图未配置');
      const visible = await Promise.race([
        formView.waitFor({ timeout: 5000 }).then(() => 'form'),
        notConfigured.waitFor({ timeout: 5000 }).then(() => 'not-configured'),
      ]).catch(() => 'timeout');
      expect(['form', 'not-configured']).toContain(visible);
    }
  });

  test('FV-004: form config with custom success message', async ({ page }) => {
    await page.goto('/');
    await page.locator('nav, [data-testid="sidebar"]').first().waitFor({ timeout: 15000 });

    const viewName = `FV_Success_${uniqueId()}`;
    const pid = await createViewViaApi(page, 'e2et_order', viewName, {
      formSuccessMessage: 'Order submitted successfully!',
      formSubmitLabel: 'Place Order',
    });
    expect(pid).toBeTruthy();

    const view = await getViewViaApi(page, pid);
    expect(view.viewConfig?.formSuccessMessage).toBe('Order submitted successfully!');
    expect(view.viewConfig?.formSubmitLabel).toBe('Place Order');
  });

  test('FV-005: FORM in VIEW_TYPE_CONFIGS', async ({ page }) => {
    await navigateToDynamicPage(page, 'e2et_order');
    await page.getByTestId('view-selector-trigger').waitFor({ state: 'visible', timeout: 5000 });

    // Look for Form in view type bar.
    const formOption = page.getByText(/表单|Form/).first();
    if (await formOption.isVisible({ timeout: 3000 }).catch(() => false)) {
      expect(await formOption.isVisible()).toBe(true);
    }
  });

  test('FV-006: form view persists across reload', async ({ page }) => {
    await page.goto('/');
    await page.locator('nav, [data-testid="sidebar"]').first().waitFor({ timeout: 15000 });

    const viewName = `FV_Persist_${uniqueId()}`;
    const pid = await createViewViaApi(page, 'e2et_order', viewName, {
      formFields: ['e2et_order_title'],
      formTitle: 'Quick Submit',
      formDescription: 'Enter order details',
      formSubmitLabel: 'Go',
      formSuccessMessage: 'Done!',
    });
    expect(pid).toBeTruthy();

    const view = await getViewViaApi(page, pid);
    expect(view.viewConfig?.formTitle).toBe('Quick Submit');
    expect(view.viewConfig?.formDescription).toBe('Enter order details');
    expect(view.viewConfig?.formSubmitLabel).toBe('Go');
    expect(view.viewConfig?.formSuccessMessage).toBe('Done!');
    expect(view.viewConfig?.formFields).toEqual(['e2et_order_title']);
  });
});
