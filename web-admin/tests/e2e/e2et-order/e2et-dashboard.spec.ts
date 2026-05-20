/**
 * E2E Test Dashboard — Order Statistics Dashboard
 *
 * Tests DB-001 ~ DB-002: Dashboard page rendering
 * - Verify dashboard page loads with data-table blocks
 * - Verify block titles match configuration
 *
 * Uses real database, NO MOCKING.
 *
 * @since 6.2.0
 */

import { test, expect } from '../../fixtures';
import {
  navigateToDynamicPage,
  waitForDynamicPageLoad,
  expectCollectionViewVisible,
} from '../helpers';

test.describe('E2E Test Dashboard', () => {
  /**
   * DB-001: Dashboard page loads with data-table blocks
   */
  test('DB-001: should load dashboard page with data-table blocks @smoke', async ({ page }) => {
    await navigateToDynamicPage(page, 'e2et-order-dashboard');
    await waitForDynamicPageLoad(page);

    await expectCollectionViewVisible(page, 10000);
  });

  /**
   * DB-002: Dashboard block titles match configuration
   */
  test('DB-002: should display correct block titles', async ({ page }) => {
    await navigateToDynamicPage(page, 'e2et-order-dashboard');
    await waitForDynamicPageLoad(page);

    const mainContent = page.locator('main').first();
    await expect(mainContent).toContainText(/近期订单|Recent Orders/, { timeout: 10_000 });
    await expect(mainContent).toContainText(/待审批付款|Pending Payments/, { timeout: 10_000 });
    await expect(mainContent).toContainText(/客户一览|Customer Overview/, { timeout: 10_000 });
  });
});
