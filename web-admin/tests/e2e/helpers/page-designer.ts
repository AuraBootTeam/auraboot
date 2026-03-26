/**
 * Page Designer E2E Test Helpers
 *
 * Unified helper for opening page designer in E2E tests.
 * Uses direct URL navigation via API for reliability.
 *
 * @since 4.0.0
 */

import type { Page } from '@playwright/test';

/**
 * Open page designer by:
 * 1. Try direct navigation with a known page ID (fast, reliable)
 * 2. Fallback to list-based navigation (slower, less reliable)
 *
 * Returns true if we successfully entered the designer canvas.
 */
export async function openPageDesigner(page: Page): Promise<boolean> {
  // Step 1: Get first available page ID via API
  try {
    const resp = await page.request.get(
      `/api/pages?current=1&size=1&sortField=updatedAt&sortDirection=DESC`
    );
    if (resp.ok()) {
      const body = await resp.json();
      const pages = body.data?.data || body.data?.records || [];
      if (pages.length > 0) {
        const pageId = pages[0].pid || pages[0].id;
        await page.goto(`/page-designer/${pageId}`);
        await page.waitForLoadState('domcontentloaded');

        // Wait for designer toolbar (Save button)
        const saveBtn = page.locator('button:has-text("Save"), button:has-text("保存")');
        if (await saveBtn.first().isVisible({ timeout: 10000 }).catch(() => false)) {
          return true;
        }
      }
    }
  } catch {
    // API call failed, fall through to list-based navigation
  }

  // Step 2: Fallback to list navigation
  await page.goto(`/page-designer`);
  await page.waitForLoadState('domcontentloaded');

  // Check if page list loaded
  const pageCount = page.locator('text=/\\d+ 个页面/');
  const hasPages = await pageCount.isVisible({ timeout: 10000 }).catch(() => false);

  // Also check for page cards as fallback
  const pageCards = page.locator('main h3');
  const hasCards = await pageCards.first().isVisible({ timeout: 3000 }).catch(() => false);

  if (!hasPages && !hasCards) {
    return false;
  }

  // Find and double-click first page to open designer
  const pageHeadings = page.locator('main h3');
  const count = await pageHeadings.count();

  if (count === 0) {
    return false;
  }

  await pageHeadings.first().dblclick();
  await page.waitForLoadState('domcontentloaded');

  // Check if we're in the designer (increased timeout for parallel runs)
  const saveButton = page.locator('button:has-text("Save")');
  const hasSave = await saveButton.isVisible({ timeout: 10000 }).catch(() => false);
  if (hasSave) return true;

  // Fallback: check URL changed to designer route
  const url = page.url();
  return url.includes('/page-designer/') && !url.endsWith('/page-designer');
}
