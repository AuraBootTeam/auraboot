/**
 * E2E Tests — DSL Productization Upgrade
 *
 * Tests DP-001 ~ DP-006: Verify the Profile-based dynamic page rendering system
 * - DynamicPageRenderer renders list/form/detail pages via admin profile
 * - Skeleton screens appear during loading
 * - BlockErrorBoundary correctly isolates render errors
 *
 * Uses e2et-order model as the test subject.
 * Uses real database, NO MOCKING.
 *
 * @since 6.2.0
 */

import { test, expect } from '../../fixtures';
import { uniqueId } from '../quarry-management.setup';
import { ModelTestHelper } from '../../helpers/model-test-helper';
import { E2ET_ORDER_CONFIG } from '../../helpers/configs/e2et-order.config';
import { waitForFormReady } from '../helpers';

test.describe('DSL Productization — Profile-Based Dynamic Page Rendering', () => {
  /**
   * DP-001: List page renders through DynamicPageRenderer via admin profile
   */
  test('DP-001: list page renders via DynamicPageRenderer @smoke', async ({ page }) => {
    // Navigate to e2et-order list page
    await page.goto('/p/e2et_order');
    await page.waitForLoadState('domcontentloaded');

    // Verify DynamicPageRenderer wrapper is present (profile system active)
    const wrapper = page.locator('[data-testid="dynamic-page-list"]');
    await expect(wrapper).toBeVisible({ timeout: 15000 });

    // Verify table content rendered inside the profile-based page
    const table = wrapper.locator('table');
    await expect(table.first()).toBeVisible({ timeout: 10000 });

    // Verify page heading exists (list page title)
    const heading = page.locator('h2').first();
    await expect(heading).toBeVisible({ timeout: 10000 });
  });

  /**
   * DP-002: Form page (create) renders through DynamicPageRenderer via admin profile
   */
  test('DP-002: form page renders via DynamicPageRenderer @smoke', async ({ page }) => {
    // Navigate to e2et-order new form page
    await page.goto('/p/e2et_order/new');
    await page.waitForLoadState('domcontentloaded');

    await waitForFormReady(page, 15000);
    await expect(page.locator('main, [role="main"]').first()).toBeVisible({ timeout: 10000 });
    await expect(page.locator('[data-testid^="form-field-"]').first()).toBeVisible({
      timeout: 10000,
    });
  });

  /**
   * DP-003: Detail page renders through DynamicPageRenderer via admin profile
   */
  test('DP-003: detail page renders via DynamicPageRenderer @critical', async ({ page }) => {
    const order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);

    // Create test order via API
    const title = `DP003 ${uniqueId()}`;
    const orderPid = await order.createViaApi({ e2et_order_title: title });

    try {
      // Navigate to detail page
      await page.goto(`/p/e2et_order/view/${orderPid}`);
      await page.waitForLoadState('domcontentloaded');

      await expect(page.locator('main, [role="main"]').first()).toBeVisible({ timeout: 15000 });
      await expect(page.locator('body')).toContainText(title, { timeout: 10000 });
    } finally {
      await order.deleteViaApi(orderPid);
    }
  });

  /**
   * DP-004: Edit form page uses pageType="edit" when recordId is present
   */
  test('DP-004: edit form page renders via DynamicPageRenderer @critical', async ({ page }) => {
    const order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);

    // Create test order via API
    const title = `DP004 ${uniqueId()}`;
    const orderPid = await order.createViaApi({ e2et_order_title: title });

    try {
      // Navigate to edit page (edit mode uses /:recordId/edit route)
      await page.goto(`/p/e2et_order/${orderPid}/edit`);
      await page.waitForLoadState('domcontentloaded');

      await waitForFormReady(page, 15000);
      await expect(page.locator('[data-testid^="form-field-"]').first()).toBeVisible({
        timeout: 10000,
      });
    } finally {
      await order.deleteViaApi(orderPid);
    }
  });

  /**
   * DP-005: Skeleton screen appears during page load
   *
   * Since we use real API (no mocking), the skeleton may appear only briefly.
   * We race between skeleton detection and content detection:
   * - If skeleton appears first → the loading state works correctly
   * - If content appears first → the API was fast, skeleton was too brief to catch
   * Both are valid outcomes.
   */
  test('DP-005: skeleton or content appears during list page load @smoke', async ({ page }) => {
    // Navigate and immediately race between skeleton and content
    await page.goto('/p/e2et_order');

    const result = await Promise.race([
      page
        .locator('[data-testid="list-page-skeleton"]')
        .waitFor({ state: 'attached', timeout: 5000 })
        .then(() => 'skeleton' as const)
        .catch(() => null),
      page
        .locator('[data-testid="dynamic-page-list"]')
        .waitFor({ state: 'attached', timeout: 5000 })
        .then(() => 'content' as const)
        .catch(() => null),
    ]);

    // Either skeleton or content must have appeared
    expect(result).toBeTruthy();

    // After loading completes, verify content is shown
    const wrapper = page.locator('[data-testid="dynamic-page-list"]');
    await expect(wrapper).toBeVisible({ timeout: 15000 });
  });

  /**
   * DP-006: BlockErrorBoundary does not show errors when blocks render correctly
   *
   * Verifies that:
   * 1. All blocks rendered successfully (no error boundary fallback visible)
   * 2. The page content (toolbar, table) is functional
   */
  test('DP-006: blocks render without error boundary fallback @critical', async ({ page }) => {
    // Navigate to list page
    await page.goto('/p/e2et_order');
    await page.waitForLoadState('domcontentloaded');

    const wrapper = page.locator('[data-testid="dynamic-page-list"]');
    await expect(wrapper).toBeVisible({ timeout: 15000 });

    // Verify table loaded (the main data-table block rendered)
    const table = page.locator('table');
    await expect(table.first()).toBeVisible({ timeout: 10000 });

    // No error boundary fallback should be visible (all blocks render correctly)
    const errorBoundaryCount = await page.locator('[data-testid="block-error-boundary"]').count();
    expect(errorBoundaryCount).toBe(0);

    // Verify toolbar block rendered (has action buttons)
    const addBtn = page.locator('button').filter({ hasText: /新建|New|create/i });
    await expect(addBtn.first()).toBeVisible({ timeout: 5000 });
  });
});
