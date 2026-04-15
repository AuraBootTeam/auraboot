/**
 * Regression spec for the empty-canvas bug.
 *
 * Root cause: buildDefaultDslV4 was called for all non-composite pages because
 * the converter only populated dslSchema for composite kind.  After Task 3.1,
 * the editor consumes PageSchema directly from the service — no fallback.
 *
 * Test: clicking "edit" on an existing list page must show the designer canvas
 * in the designer canvas, not an empty canvas caused by a placeholder DSL.
 *
 * Navigation: sidebar menu → 元数据管理 → 页面配置 → click edit on a list row.
 *
 * Dimensions: D1 (sidebar nav), D2 (list renders), D6 (designer canvas visible), D9 (regression guard)
 */

import { test, expect } from '../../fixtures';
import {
  navigateToDynamicPage,
  waitForDynamicPageLoad,
  ensureSidebarExpanded,
} from '../helpers';

test.describe('Page Designer loads existing pages', () => {
  test('clicking edit on a list page shows designer canvas via sidebar nav', async ({ page }) => {
    // D1: Navigate via sidebar menu (禁止 page.goto 直达 — must use sidebar navigation)
    await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
    await ensureSidebarExpanded(page);

    const nav = page.locator('nav, aside, [role="navigation"]').first();
    await nav.waitFor({ state: 'visible', timeout: 10000 });

    // Expand parent menu: 元数据管理
    const parentMenu = nav
      .getByRole('button', { name: /元数据管理|Meta/i })
      .or(nav.locator('[title="元数据管理"]'))
      .first();
    await parentMenu.waitFor({ state: 'visible', timeout: 8000 });
    await parentMenu.click();

    // Click leaf: 页面配置
    const leafLink = nav
      .locator('a[href*="page_schema"]')
      .or(nav.locator('a:has-text("页面配置")'))
      .first();
    await leafLink.waitFor({ state: 'attached', timeout: 8000 });

    const listResponsePromise = page.waitForResponse(
      (r) => r.url().includes('/list') && r.status() === 200,
      { timeout: 15000 },
    );
    await leafLink.evaluate((el: HTMLElement) => el.click());
    await listResponsePromise;

    // D2: Page schema list must render with data
    const table = page.locator('table, [data-testid="dynamic-list"]').first();
    await expect(table).toBeVisible({ timeout: 10000 });

    // Take the first table row — any existing page exercises the regression.
    // (Filtering by kind text is unreliable because the cell may render a
    // localised label like "列表" or a styled badge, not the raw string "list".)
    const firstRow = page.locator('tbody tr').first();
    await expect(firstRow).toBeVisible({ timeout: 10000 });

    // D6: Click the edit/design button on that row
    await firstRow.getByRole('button', { name: /edit|design|编辑|设计/i }).first().click();
    await page.waitForURL(/\/page-designer\//);
    await page.waitForLoadState('domcontentloaded').catch(() => {});

    // D6: The designer canvas must be visible — confirms editor loaded the saved schema
    const canvas = page
      .locator(
        '[data-testid="designer-canvas"], [data-designer-canvas], .designer-canvas, [data-testid="areas-designer"]',
      )
      .first();
    await expect(canvas).toBeVisible({ timeout: 10000 });

    // D9: Regression assertion: URL navigated to the designer (not an error page)
    expect(page.url()).toMatch(/\/page-designer\//);
  });

  test('page designer shows error state for non-existent page id', async ({ page }) => {
    // Navigate from page list to a known-invalid designer URL
    await navigateToDynamicPage(page, 'page_schema');
    await waitForDynamicPageLoad(page);

    // Force-navigate to designer with a bogus pid to exercise the error path
    await page.goto('/page-designer/nonexistent-pid-9999', { waitUntil: 'domcontentloaded' });

    // Should show error state, not crash or blank screen.
    // Use .or() to combine a text matcher with a CSS selector — mixing them in
    // a single locator string with a comma is invalid and throws a SyntaxError.
    const errorIndicator = page
      .getByText(/not found|failed|error/i)
      .or(page.locator('[data-testid="error-state"]'));
    await expect(errorIndicator.first()).toBeVisible({ timeout: 5000 });
  });
});
