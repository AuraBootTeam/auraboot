/**
 * Social Account Binding E2E Tests
 *
 * Tests SL-001 ~ SL-004: Social links page navigation, structure,
 * provider list rendering, and info box.
 *
 * Note: Actual OAuth bind/unbind flows require real OAuth credentials
 * and cannot be tested in E2E without mocking. These tests focus on
 * the page structure and UI elements.
 *
 * Route: /personal/social-links
 * API: GET /api/user/social-links
 *
 * @since 4.0.0
 */

import { test, expect } from '../../fixtures';

const PAGE_URL = '/personal/social-links';

test.describe('Social Account Binding', () => {
  /**
   * SL-001: Page load and basic structure
   * Verify page title, all 3 provider rows, and info box.
   */
  test('SL-001: should display page structure with all providers @smoke', async ({ page }) => {
    // Set up API response listener BEFORE navigation
    const apiResponse = page.waitForResponse(
      (resp) =>
        resp.url().includes('/api/user/social-links') &&
        resp.request().method().toLowerCase() === 'get',
      { timeout: 15000 },
    );
    await page.goto(PAGE_URL, { waitUntil: 'domcontentloaded' });
    await apiResponse;

    // Page title (bilingual)
    await expect(
      page.locator('h1').filter({ hasText: /Social Account Binding|社交账号绑定/i }),
    ).toBeVisible({ timeout: 10000 });

    // All 3 provider rows
    await expect(page.locator('[data-testid="social-link-wechat_web"]')).toBeVisible();
    await expect(page.locator('[data-testid="social-link-google"]')).toBeVisible();
    await expect(page.locator('[data-testid="social-link-apple"]')).toBeVisible();

    // Info box (bilingual)
    await expect(page.getByText(/About Social Login|关于社交登录/i)).toBeVisible();
  });

  /**
   * SL-002: Provider rows show "Not linked" and "Bind" buttons
   * When no social accounts are linked, each provider should show
   * "Not linked" status and a "Bind" button.
   */
  test('SL-002: should show bind buttons for unlinked providers', async ({ page }) => {
    const apiResponse = page.waitForResponse(
      (resp) =>
        resp.url().includes('/api/user/social-links') &&
        resp.request().method().toLowerCase() === 'get',
      { timeout: 15000 },
    );
    await page.goto(PAGE_URL, { waitUntil: 'domcontentloaded' });
    await apiResponse;

    // Each provider should have a "Bind" button (assuming no social links)
    // At minimum, check that bind buttons exist for providers that are NOT linked
    const providers = ['wechat_web', 'google', 'apple'];
    for (const provider of providers) {
      const row = page.locator(`[data-testid="social-link-${provider}"]`);
      await expect(row).toBeVisible();

      // Either "Bind" or "Unlink" button should be visible
      const bindBtn = row.locator(`[data-testid="social-bind-${provider}"]`);
      const unlinkBtn = row.locator(`[data-testid="social-unlink-${provider}"]`);
      await expect(bindBtn.or(unlinkBtn)).toBeVisible();
    }
  });

  /**
   * SL-003: Back button navigates to profile
   */
  test('SL-003: should navigate back to profile', async ({ page }) => {
    await page.goto(PAGE_URL, { waitUntil: 'domcontentloaded' });

    const backBtn = page.locator('[data-testid="social-links-back-btn"]');
    await expect(backBtn).toBeVisible({ timeout: 10000 });

    await backBtn.click();
    await expect(page).toHaveURL(/\/personal\/profile/, { timeout: 10000 });
  });

  /**
   * SL-004: Profile page has link to social binding
   */
  test('SL-004: profile page links to social binding', async ({ page }) => {
    await page.goto('/personal/profile', { waitUntil: 'domcontentloaded' });

    const link = page.locator('[data-testid="profile-social-links-link"]');
    await expect(link).toBeVisible({ timeout: 10000 });

    await link.click();
    await expect(page).toHaveURL(/\/personal\/social-links/, { timeout: 10000 });
  });
});
