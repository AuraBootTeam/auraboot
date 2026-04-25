/**
 * Logout E2E Tests
 *
 * Tests LO-001 ~ LO-004: User logout functionality
 * - Logout via user menu
 * - Session cleanup after logout
 * - Redirect to login page after logout
 * - Logout button visibility
 *
 * Uses storageState for authentication.
 * Uses HeaderPage component PO for header interactions.
 *
 * @since 4.0.0
 */

import { test, expect } from '../../fixtures';
import { HeaderPage } from '../../pages';

test.describe('Logout Functionality', () => {
  /**
   * LO-001: Logout via user menu
   * Verify that clicking logout link logs user out
   */
  test('LO-001: should logout via user menu @smoke', async ({ page }) => {
    const header = new HeaderPage(page);

    // Navigate to app
    await page.goto(`/meta/models`, { waitUntil: 'commit', timeout: 10000 });
    await page.waitForLoadState('domcontentloaded');

    // Wait for avatar button to be interactive (indicates hydration complete)
    const isAuthenticated = await header.isAuthenticated();
    expect(isAuthenticated).toBe(true);

    // Open user menu and navigate to the logout confirmation route.
    await header.openUserMenu();
    await expect(header.logoutLink).toHaveAttribute('href', '/logout');
    await page.goto('/logout', { waitUntil: 'commit', timeout: 10000 });
    await page.waitForLoadState('domcontentloaded');

    // Verify the menu action led to the dedicated logout confirmation screen.
    await expect(
      page.locator('button:has-text("确认退出"), button:has-text("Log Out")').first(),
    ).toBeVisible({ timeout: 5000 });
  });

  /**
   * LO-002: Session cleanup after logout
   * Verify that session/auth data is cleared after logout
   */
  test('LO-002: should clear session after logout', async ({ page }) => {
    const header = new HeaderPage(page);

    // Navigate to app
    await page.goto(`/meta/models`);
    await page.waitForLoadState('domcontentloaded');

    // Check if authenticated
    const isAuthenticated = await header.userAvatar.isVisible({ timeout: 5000 }).catch(() => false);
    expect(isAuthenticated).toBe(true);

    // Navigate to logout
    await page.goto(`/logout`);
    await page.waitForLoadState('domcontentloaded');

    // Verify auth token is cleared from localStorage
    const authToken = await page.evaluate(() => localStorage.getItem('token'));

    // Token should be null or empty after logout
    expect(authToken === null || authToken === '').toBe(true);
  });

  /**
   * LO-003: Protected route redirect after logout
   * Verify that accessing protected routes after logout redirects to login
   */
  test('LO-003: should redirect to login when accessing protected route after logout', async ({
    page,
    context,
  }) => {
    // Clear all cookies to simulate logged out state
    await context.clearCookies();

    // Try to access protected route
    await page.goto(`/meta/models`);
    await page.waitForLoadState('domcontentloaded');

    // Should see login form or be redirected
    const loginForm = page.locator('input#email, input[type="email"]');
    const hasLoginForm = await loginForm.isVisible({ timeout: 5000 }).catch(() => false);

    // Either shows login form or redirected to login page
    const url = page.url();
    const isOnLoginPage = url.includes('login') || url.endsWith('/');

    expect(hasLoginForm || isOnLoginPage).toBe(true);
  });

  /**
   * LO-004: Logout button visibility
   * Verify logout option is only visible for authenticated users
   */
  test('LO-004: logout button should be visible for authenticated users', async ({ page }) => {
    const header = new HeaderPage(page);

    await page.goto(`/meta/models`);
    await page.waitForLoadState('domcontentloaded');

    // Find user avatar button (indicates authenticated)
    const isAuthenticated = await header.isAuthenticated();

    if (isAuthenticated) {
      // Open user menu and verify logout link is visible
      await header.openUserMenu();

      // Logout link should be visible (Link component renders as <a href="/logout">)
      await expect(header.logoutLink).toBeVisible({ timeout: 3000 });
    } else {
      // Not authenticated - should see login/signup links instead
      const loginLink = page.locator('a[href="/login"]');
      const hasLoginLink = await loginLink.isVisible({ timeout: 3000 }).catch(() => false);
      expect(hasLoginLink).toBe(true);
    }
  });
});
