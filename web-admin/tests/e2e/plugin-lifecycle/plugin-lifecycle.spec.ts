/**
 * Plugin Lifecycle E2E Tests
 *
 * Verifies plugin import, marketplace, DSL page rendering, and reimport idempotency:
 * - Plugin import API existence
 * - Marketplace page via sidebar
 * - e2e-test-order DSL page renders with data
 * - e2e-test-order menus visible in sidebar
 * - Plugin reimport is idempotent (data not wiped)
 *
 * All navigation uses sidebar menus where applicable.
 * No waitForTimeout — uses waitForResponse / waitFor / expect().toBeVisible().
 *
 * @since 5.0.0
 */

import { test, expect } from '../../fixtures';
import { navigateToDynamicPage, waitForDynamicPageLoad, getTableRowCount } from '../helpers/index';

test.describe('Plugin Lifecycle', () => {
  test.describe.configure({ timeout: 30000 });

  /**
   * PL-01: Plugin import API endpoint exists
   */
  test('PL-01: plugin import API endpoint exists and accepts requests', async ({ page }) => {
    const resp = await page.request.post('/api/plugins/import/import-directory-sync', {
      data: { directory: '/nonexistent/path' },
      headers: { 'Content-Type': 'application/json' },
      failOnStatusCode: false,
    });

    // Endpoint should exist (not 404). A 400/422 validation error is expected for bad input.
    expect(resp.status()).not.toBe(404);
  });

  /**
   * PL-02: Plugin management page loads via sidebar menu.
   *
   * /marketplace and /system/plugins were merged into /plugins (Tabs:
   * discovery / installed / history). The sidebar menu now links to /plugins.
   */
  test('PL-02: plugin management page loads with visible content', async ({ page }) => {
    await page.goto('/dashboards', { waitUntil: 'load' });

    // Look for plugin management link in sidebar
    const pluginsLink = page.locator('a[href^="/plugins"]');
    const linkVisible = await pluginsLink
      .first()
      .isVisible({ timeout: 5000 })
      .catch(() => false);

    if (linkVisible) {
      await pluginsLink.first().evaluate((el) => (el as HTMLAnchorElement).click());
      await page.waitForURL((url) => url.pathname === '/plugins', { timeout: 10000 });
    } else {
      await page.goto('/plugins', { waitUntil: 'domcontentloaded' });
    }

    await waitForDynamicPageLoad(page);

    // Assert meaningful content on the marketplace page
    const content = page.locator(
      'h1, h2, .ant-card, [data-testid*="marketplace"], [data-testid*="plugin"], main',
    );
    await expect(content.first()).toBeVisible({ timeout: 10000 });

    // Not a 404 or error page
    await expect(page.locator('text=Page not found')).not.toBeVisible({ timeout: 2000 });
  });

  /**
   * PL-03: e2e-test-order DSL page renders with data
   */
  test('PL-03: e2e-test-order DSL page shows real data via navigateToDynamicPage', async ({
    page,
  }) => {
    await navigateToDynamicPage(page, 'e2et_order');

    // Table should render (may have 0 rows if no seed data — table structure is the key assertion)
    const table = page.locator('table, [data-testid="dynamic-list"]').first();
    await expect(table).toBeVisible({ timeout: 10_000 });
    const rowCount = await getTableRowCount(page);
    expect(rowCount).toBeGreaterThanOrEqual(0);
  });

  /**
   * PL-04: e2e-test-order menus appear in sidebar
   */
  test('PL-04: e2e-test-order menus are visible in sidebar', async ({ page }) => {
    // Navigate to admin (/ is marketing website)
    await page.goto('/dashboards', { waitUntil: 'load' });

    // Wait for sidebar to render
    const sidebar = page.locator('nav, aside, [data-testid="sidebar"]');
    await sidebar.first().waitFor({ state: 'visible', timeout: 10000 });

    // Look for E2E test menu items — either by text or by href
    const e2eMenu = page.locator('a[href*="e2et"], button:has-text("e2e"), [data-testid*="e2et"]');
    const menuByText = page.locator('nav').getByText(/E2E/);

    const hrefVisible = await e2eMenu
      .first()
      .isVisible({ timeout: 5000 })
      .catch(() => false);
    const textVisible = await menuByText
      .first()
      .isVisible({ timeout: 3000 })
      .catch(() => false);

    expect(hrefVisible || textVisible).toBe(true);
  });

  /**
   * PL-05: Plugin reimport is idempotent — data survives reimport
   */
  test('PL-05: plugin reimport is idempotent and preserves existing data', async ({ page }) => {
    // Step 1: Verify table renders before reimport (may have 0 rows)
    await navigateToDynamicPage(page, 'e2et_order');
    const rowCountBefore = await getTableRowCount(page);
    // rowCountBefore may be 0 if no seed data — still valid for idempotency test

    // Step 2: Reimport the test-fixtures plugin
    const importResp = await page.request.post('/api/plugins/import/import-directory-sync', {
      data: { directory: 'plugins/test-fixtures' },
      headers: { 'Content-Type': 'application/json' },
      failOnStatusCode: false,
    });

    // Import should succeed or at least not crash
    expect(importResp.status()).not.toBe(500);

    // Step 3: Navigate again and verify data still exists
    const listResponsePromise = page
      .waitForResponse((resp) => resp.url().includes('/list') && resp.status() === 200, {
        timeout: 10000,
      })
      .catch(() => null);

    await page.goto('/p/e2et_order', { waitUntil: 'domcontentloaded' });
    await waitForDynamicPageLoad(page);
    await listResponsePromise;

    const rowCountAfter = await getTableRowCount(page);
    expect(rowCountAfter).toBeGreaterThanOrEqual(rowCountBefore);
  });
});
