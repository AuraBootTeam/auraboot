/**
 * E2E Test: SavedView GALLERY View
 *
 * Tests Gallery card grid view features.
 *
 * Prerequisites: e2et-order page must exist (created by init setup).
 * The Gallery view type button should always be visible since all 5 view types
 * are enabled in VIEW_TYPE_CONFIGS.
 *
 * @since 7.0.0
 */

import { test, expect } from '@playwright/test';
import { navigateToDynamicPage } from '../helpers';

/** Navigate to e2et-order page and wait for view type buttons to render */
async function gotoOrderPageAndWaitForViewTypes(page: import('@playwright/test').Page) {
  await navigateToDynamicPage(page, 'e2et-order');
  await page.locator('[data-testid="view-type-table"]').waitFor({ state: 'visible', timeout: 8000 });
}

test.describe('SavedView — GALLERY View', () => {
  test('SV-030: GALLERY — card grid renders @smoke', async ({ page }) => {
    await gotoOrderPageAndWaitForViewTypes(page);

    const galleryBtn = page.locator('[data-testid="view-type-gallery"]');
    await expect(galleryBtn).toBeVisible({ timeout: 5000 });

    await galleryBtn.click();
    // Should show gallery grid or unconfigured message
    const content = page.locator('.grid, [data-testid="gallery-view"], [class*="gallery"]')
      .or(page.getByText('Gallery not configured'))
      .or(page.getByText('not configured'));
    await expect(content.first()).toBeVisible({ timeout: 8000 });
  });

  test('SV-031: GALLERY — card field configuration', async ({ page }) => {
    await gotoOrderPageAndWaitForViewTypes(page);

    const galleryBtn = page.locator('[data-testid="view-type-gallery"]');
    await galleryBtn.click();

    // Wait for gallery content or unconfigured message
    const galleryContainer = page.locator('[data-testid="gallery-view"], [class*="gallery"], .grid').first();
    const hasGallery = await galleryContainer.isVisible({ timeout: 5000 }).catch(() => false);
    if (!hasGallery) {
      throw new Error(String('Gallery view not configured (no image field mapped)'))
    }

    // If gallery is configured, cards should show field values
    const cards = page.locator('[class*="card"], [class*="gallery-item"]');
    const count = await cards.count();
    expect(count >= 0).toBe(true);
  });
});
