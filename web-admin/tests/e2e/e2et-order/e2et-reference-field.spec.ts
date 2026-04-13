/**
 * E2E Test Order — REFERENCE Field UI
 *
 * Tests RF-001 ~ RF-002: REFERENCE field dropdown and display
 * - Order form shows customer reference dropdown
 * - Selected customer displays correctly on detail page
 *
 * Uses real database, NO MOCKING.
 *
 * @since 6.2.0
 */

import { test, expect } from '../../fixtures';
import { uniqueId, navigateToDynamicPage, waitForDynamicPageLoad } from '../helpers';
import { ModelTestHelper } from '../../helpers/model-test-helper';
import { E2ET_ORDER_CONFIG } from '../../helpers/configs/e2et-order.config';
import { E2ET_CUSTOMER_CONFIG } from '../../helpers/configs/e2et-customer.config';

test.describe('E2E Test Order — REFERENCE Field UI', () => {
  let customerPid: string;
  let customerName: string;

  test.beforeAll(async ({ browser }) => {
    const context = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await context.newPage();
    const customer = new ModelTestHelper(page, E2ET_CUSTOMER_CONFIG);
    customerName = `RefCust ${uniqueId('RC')}`;
    customerPid = await customer.createViaApi({
      e2et_cust_code: `REF-${uniqueId('R')}`,
      e2et_cust_name: customerName,
      e2et_cust_region: 'east',
    });
    await page.close();
    await context.close();
  });

  test.afterAll(async ({ browser }) => {
    const context = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await context.newPage();
    const customer = new ModelTestHelper(page, E2ET_CUSTOMER_CONFIG);
    await customer.deleteViaApi(customerPid).catch(() => {});
    await page.close();
    await context.close();
  });

  /**
   * RF-001: REFERENCE field should show dropdown with customer options
   */
  test('RF-001: should show customer reference dropdown on order form @smoke', async ({ page }) => {
    const order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);
    await order.gotoNewForm();

    // Find the customer reference field
    const refField = page.locator('[data-testid="form-field-e2et_order_customer"]').first();

    const refVisible = await refField.isVisible({ timeout: 5000 }).catch(() => false);
    if (!refVisible) {
      throw new Error(String('Customer reference field not found on order form'));
      return;
    }

    // The reference field should have a select or combobox element
    const selectEl = refField.locator('select, [role="combobox"], input').first();
    await expect(selectEl).toBeAttached({ timeout: 5000 });
  });

  /**
   * RF-002: Selected customer should display on detail page
   */
  test('RF-002: should display selected customer on detail page @critical', async ({ page }) => {
    const order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);

    // Create order with customer reference via API
    const orderPid = await order.createViaApi({
      e2et_order_title: `RefOrder ${uniqueId('RO')}`,
      e2et_order_customer: customerPid,
    });

    try {
      // Navigate directly to the created order's detail page
      await page.goto(`/p/e2et_order/view/${orderPid}`, { waitUntil: 'domcontentloaded' });
      await page
        .waitForResponse(
          (r) => r.url().includes('/api/dynamic/e2et_order') && !r.url().includes('/list'),
          { timeout: 12_000 },
        )
        .catch(() => null);
      await expect(page.getByText(/订单标题|关联客户|订单状态/).first()).toBeVisible({
        timeout: 10_000,
      });

      // Check if customer name or customer PID is displayed on the detail page
      // Note: REFERENCE field may display the referenced record's title OR the PID
      const pageContent = (await page.textContent('body')) || '';
      const hasCustomerName = pageContent.includes(customerName);
      const hasCustomerPid = pageContent.includes(customerPid);
      expect(hasCustomerName || hasCustomerPid).toBe(true);
    } finally {
      await order.deleteViaApi(orderPid);
    }
  });
});
