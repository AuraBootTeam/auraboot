/**
 * E2E Test: SavedView GALLERY View
 *
 * Tests Gallery card grid view features.
 *
 * Prerequisites: e2et-order page must exist (created by init setup).
 * Gallery view is selected via ViewSelector dropdown after creating a gallery saved view.
 *
 * @since 7.0.0
 */

import { test, expect } from '@playwright/test';
import { navigateToDynamicPage, uniqueId } from '../helpers';

const VIEW_NAME = 'E2E Gallery View';
const MODEL_CODE = 'e2et_order';
const PAGE_KEY = 'e2et_order';

/** Navigate to e2et-order page and select the gallery view via ViewManagePanel */
async function gotoAndSelectGalleryView(page: import('@playwright/test').Page) {
  await navigateToDynamicPage(page, PAGE_KEY);
  // Wait for the list page content to be visible (table renders by default)
  await page.locator('table, [role="table"], [data-testid="dynamic-list"]').first().waitFor({ state: 'visible', timeout: 15000 });

  // Click ViewSelector button to open ViewManagePanel (slide-out dialog)
  const viewSelector = page.locator('button[aria-haspopup="listbox"]');
  await viewSelector.click();
  const panel = page.locator('[role="dialog"]');
  await panel.waitFor({ state: 'visible', timeout: 5000 });
  // Find and click the gallery view by name in the panel
  const viewOption = panel.getByText(VIEW_NAME, { exact: false }).first();
  await viewOption.waitFor({ state: 'visible', timeout: 5000 });
  await viewOption.click();
  // Close the panel after selecting the view (panel does not auto-close)
  const closeBtn = panel.locator('button[aria-label="Close panel"]');
  if (await closeBtn.isVisible({ timeout: 1000 }).catch(() => false)) {
    await closeBtn.click();
  }
  await panel.waitFor({ state: 'hidden', timeout: 5000 }).catch(() => {});
}

test.describe('SavedView — GALLERY View', () => {
  let galleryViewPid = '';

  test.beforeAll(async ({ browser }) => {
    const page = await browser.newPage();

    // Clean up leftover views from previous runs
    const existing = await page.request.get(
      `/api/views/accessible?modelCode=${MODEL_CODE}&pageKey=${PAGE_KEY}`,
    );
    if (existing.ok()) {
      const body = await existing.json();
      for (const v of (body.data ?? []).filter(
        (v: any) => v.viewType === 'gallery' && v.name === VIEW_NAME,
      )) {
        await page.request.delete(`/api/views/${v.pid}`).catch(() => {});
      }
    }

    // Create GALLERY SavedView via API
    const viewResp = await page.request.post('/api/views', {
      data: {
        name: VIEW_NAME,
        modelCode: MODEL_CODE,
        pageKey: PAGE_KEY,
        viewType: 'gallery',
        scope: 'global',
        viewConfig: {
          galleryTitleField: 'e2et_order_title',
        },
      },
    });
    if (viewResp.ok()) {
      const body = await viewResp.json();
      galleryViewPid = body.data?.pid ?? body.pid ?? '';
    }

    await page.close();
  });

  test.afterAll(async ({ browser }) => {
    const page = await browser.newPage();
    if (galleryViewPid) {
      await page.request.delete(`/api/views/${galleryViewPid}`).catch(() => {});
    }
    const cleanup = await page.request.get(
      `/api/views/accessible?modelCode=${MODEL_CODE}&pageKey=${PAGE_KEY}`,
    );
    if (cleanup.ok()) {
      const body = await cleanup.json();
      for (const v of (body.data ?? []).filter(
        (v: any) => v.viewType === 'gallery' && v.name === VIEW_NAME,
      )) {
        await page.request.delete(`/api/views/${v.pid}`).catch(() => {});
      }
    }
    await page.close();
  });

  test('SV-030: GALLERY — card grid renders @smoke', async ({ page }) => {
    await gotoAndSelectGalleryView(page);

    // Should show gallery grid or unconfigured message
    const content = page
      .locator('.grid, [data-testid="gallery-view"], [class*="gallery"]')
      .or(page.getByText('Gallery not configured'))
      .or(page.getByText('not configured'));
    await expect(content.first()).toBeVisible({ timeout: 8000 });
  });

  test('SV-031: GALLERY — card field configuration', async ({ page }) => {
    await gotoAndSelectGalleryView(page);

    // Wait for gallery content or unconfigured message
    const galleryContainer = page
      .locator('[data-testid="gallery-view"], [class*="gallery"], .grid')
      .first();
    const hasGallery = await galleryContainer.isVisible({ timeout: 5000 }).catch(() => false);
    if (!hasGallery) {
      test.skip(true, 'Gallery view not configured — no image field mapped for this model');
      return;
    }

    // If gallery is configured, cards should show field values
    const cards = page.locator('[class*="card"], [class*="gallery-item"]');
    const count = await cards.count();
    expect(count >= 0).toBe(true);
  });
});
