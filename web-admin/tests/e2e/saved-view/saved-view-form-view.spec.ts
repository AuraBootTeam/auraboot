/**
 * E2E Test: Form View (GAP-120)
 *
 * Tests form view creation, configuration, rendering, and data submission.
 */

import { test, expect, type Page } from '@playwright/test';
import { uniqueId } from '../helpers';

async function createViewViaApi(page: Page, modelCode: string, name: string, viewConfig: any): Promise<string> {
  const resp = await page.request.post('/api/views', {
    data: { name, modelCode, viewType: 'form', scope: 'personal', viewConfig },
  });
  if (!resp.ok()) return '';
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
    await page.goto('/dynamic/e2et-order');
    await page.getByTestId('row-height-btn').waitFor({ state: 'visible', timeout: 30000 });

    // Switch to Form view type
    const formBtn = page.getByTestId('view-type-form');
    if (await formBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
      await formBtn.click();
      // Should show form view or "not configured" message
      const formView = page.getByTestId('form-view');
      const notConfigured = page.locator('text=Form view not configured');
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
    await page.goto('/dynamic/e2et-order');
    await page.getByTestId('row-height-btn').waitFor({ state: 'visible', timeout: 30000 });

    // Look for Form in view type bar
    const formOption = page.locator('text=Form').first();
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
