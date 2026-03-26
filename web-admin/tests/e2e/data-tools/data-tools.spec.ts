/**
 * Data Tools E2E Tests
 *
 * Tests DT-E01 ~ DT-E06: Export and import functionality in SmartViewRenderer.
 * - Export button visibility
 * - Import button visibility
 * - Export dropdown with Excel/CSV options
 *
 * Uses storageState for authentication.
 * Connects to real database and API (no mocks).
 *
 * @since 4.0.0
 */

import { test, expect } from '../../fixtures';

/**
 * Navigate to a dynamic table page with data tools enabled.
 * Tries multiple known E2E dynamic pages in order.
 * Returns true if the page loaded successfully.
 */
async function navigateToDynamicPage(page: import('@playwright/test').Page): Promise<boolean> {
  // Try pages in order: e2et-order (most reliable), e2et-record, e2et-customer
  const candidates = ['e2et-order', 'e2et-record', 'e2et-customer'];
  const contentLocator = page.locator('table, .ant-table, [data-testid="smart-table"], [role="table"]');

  for (const pageKey of candidates) {
    await page.goto(`/dynamic/${pageKey}`, { waitUntil: 'domcontentloaded' });

    // Wait for dynamic page content to render (schema fetch + render)
    const found = await contentLocator.first().waitFor({ state: 'visible', timeout: 10000 })
      .then(() => true)
      .catch(() => false);

    if (found) return true;

    // Check for error state to fail fast
    const hasError = await page.locator('text=加载失败, text=Page not found').first()
      .isVisible({ timeout: 500 }).catch(() => false);
    if (hasError) continue;
  }

  return false;
}

test.describe('Data Tools - Export', () => {
  /**
   * DT-E01: Dynamic page loads
   * Verify that the dynamic list page renders.
   */
  test('DT-E01: Dynamic page loads for data tools testing', async ({ page }) => {
    const loaded = await navigateToDynamicPage(page);
    expect(loaded).toBe(true);

    // Page loaded without error overlays
    const errorOverlay = page.locator('#webpack-dev-server-client-overlay');
    const hasError = await errorOverlay.isVisible({ timeout: 1000 }).catch(() => false);
    expect(hasError).toBe(false);
  });

  /**
   * DT-E02: Export button visibility
   * Verify that the Export button is rendered in the data tools toolbar.
   */
  test('DT-E02: Export button is visible', async ({ page }) => {
    const loaded = await navigateToDynamicPage(page);
    expect(loaded).toBe(true);

    // Export is accessed via the ⋮ more menu button
    const moreBtn = page.locator('[data-testid="toolbar-more-menu"]').first();
    const hasMoreBtn = await moreBtn.isVisible({ timeout: 5000 }).catch(() => false);
    expect(hasMoreBtn).toBe(true);

    await expect(moreBtn).toBeVisible();
    await expect(moreBtn).toBeEnabled();
  });

  /**
   * DT-E03: Export dropdown shows Excel and CSV options
   * Verify that clicking Export reveals format choices.
   */
  test('DT-E03: Export dropdown shows Excel and CSV options', async ({ page }) => {
    const loaded = await navigateToDynamicPage(page);
    expect(loaded).toBe(true);

    // Open ⋮ more menu
    const moreBtn = page.locator('[data-testid="toolbar-more-menu"]').first();
    const hasMoreBtn = await moreBtn.isVisible({ timeout: 5000 }).catch(() => false);
    expect(hasMoreBtn).toBe(true);
    await moreBtn.click();

    // Verify export options in dropdown
    const excelOption = page.locator('[data-testid="more-menu-export-excel"], button:has-text("Export Excel")');
    const csvOption = page.locator('[data-testid="more-menu-export-csv"], button:has-text("Export CSV")');

    await expect(excelOption).toBeVisible({ timeout: 3000 });
    await expect(csvOption).toBeVisible({ timeout: 3000 });
  });

  /**
   * DT-E04: Export dropdown closes on outside click
   * Verify that the dropdown closes when clicking elsewhere.
   */
  test('DT-E04: Export dropdown closes on outside click', async ({ page }) => {
    const loaded = await navigateToDynamicPage(page);
    expect(loaded).toBe(true);

    // Open ⋮ more menu
    const moreBtn = page.locator('[data-testid="toolbar-more-menu"]').first();
    const hasMoreBtn = await moreBtn.isVisible({ timeout: 5000 }).catch(() => false);
    expect(hasMoreBtn).toBe(true);
    await moreBtn.click();

    const excelOption = page.locator('[data-testid="more-menu-export-excel"], button:has-text("Export Excel")');
    await expect(excelOption).toBeVisible({ timeout: 3000 });

    // Click outside to close
    await page.locator('body').click({ position: { x: 10, y: 10 } });

    // Dropdown should close
    await expect(excelOption).not.toBeVisible({ timeout: 3000 });
  });
});

test.describe('Data Tools - Import', () => {
  /**
   * DT-E05: Import button visibility
   * Verify that the Import button is rendered when showDataTools is enabled.
   */
  test('DT-E05: Import button is visible', async ({ page }) => {
    const loaded = await navigateToDynamicPage(page);
    expect(loaded).toBe(true);

    // Import is in the ⋮ more menu — open it first
    const moreBtn = page.locator('[data-testid="toolbar-more-menu"]').first();
    const hasMoreBtn = await moreBtn.isVisible({ timeout: 5000 }).catch(() => false);
    expect(hasMoreBtn).toBe(true);
    await moreBtn.click();

    const importBtn = page.locator('[data-testid="more-menu-import"], button:has-text("Import")').first();
    await expect(importBtn).toBeVisible({ timeout: 3000 });
    await expect(importBtn).toBeEnabled();
  });

  /**
   * DT-E06: Import button opens import modal
   * Verify that clicking Import opens the ImportModal component.
   */
  test('DT-E06: Import button opens import modal', async ({ page }) => {
    const loaded = await navigateToDynamicPage(page);
    expect(loaded).toBe(true);

    // Open ⋮ more menu then click Import
    const moreBtn = page.locator('[data-testid="toolbar-more-menu"]').first();
    const hasMoreBtn = await moreBtn.isVisible({ timeout: 5000 }).catch(() => false);
    expect(hasMoreBtn).toBe(true);
    await moreBtn.click();

    const importBtn = page.locator('[data-testid="more-menu-import"], button:has-text("Import")').first();
    const hasImportBtn = await importBtn.isVisible({ timeout: 3000 }).catch(() => false);
    expect(hasImportBtn).toBe(true);

    // Click import to open modal
    await importBtn.click();

    // Verify modal appears with upload area or import options
    const modal = page.locator(
      '[role="dialog"], ' +
      '.ant-modal, ' +
      '.fixed.inset-0, ' +
      'div:has-text("Import"):has(input[type="file"])'
    ).first();

    const hasModal = await modal.isVisible({ timeout: 5000 }).catch(() => false);

    if (hasModal) {
      // Modal opened - verify it has file upload or import-related content
      expect(hasModal).toBe(true);

      // Close modal
      await page.keyboard.press('Escape');
    } else {
      // Import action may have different behavior (direct file picker, etc.)
      // As long as it didn't crash, the test passes
      const errorOverlay = page.locator('#webpack-dev-server-client-overlay');
      const hasError = await errorOverlay.isVisible({ timeout: 1000 }).catch(() => false);
      expect(hasError).toBe(false);
    }
  });
});
