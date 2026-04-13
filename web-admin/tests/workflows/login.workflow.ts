/**
 * Login Workflow
 *
 * Provides utilities for authentication verification in tests.
 *
 * NOTE: With storageState configured, most tests don't need to call these functions.
 * They are provided for special cases where you need to verify auth state.
 *
 * @since 4.0.0
 */

import { expect } from '@playwright/test';
import type { Page } from '@playwright/test';
import { DEFAULT_TEST_ACCOUNT } from '../helpers/test-accounts';

/**
 * Test account credentials (from test-accounts.ts)
 */
export const TEST_CREDENTIALS = {
  email: DEFAULT_TEST_ACCOUNT.email,
  password: DEFAULT_TEST_ACCOUNT.password,
};

/**
 * Verify that the page is authenticated (not on login page)
 *
 * @param page - Playwright page
 * @throws Error if not authenticated
 */
export async function ensureLoggedIn(page: Page): Promise<void> {
  // Check if on login page by looking for login form
  const hasLoginForm = await page
    .locator('input#email')
    .isVisible({ timeout: 2000 })
    .catch(() => false);

  if (hasLoginForm) {
    throw new Error('Not logged in - login form is visible. Check storageState configuration.');
  }
}

/**
 * Perform UI login (for tests that specifically test the login flow)
 *
 * NOTE: Most tests should use storageState instead of this function.
 * This is only for testing the login UI itself.
 *
 * @param page - Playwright page
 * @param credentials - Optional custom credentials
 */
export async function performLogin(
  page: Page,
  credentials: { email: string; password: string } = TEST_CREDENTIALS,
): Promise<void> {
  // Navigate to app
  await page.goto(`/`);
  await page.waitForLoadState('domcontentloaded');

  // Check if already logged in
  const hasLoginForm = await page
    .locator('input#email')
    .isVisible({ timeout: 3000 })
    .catch(() => false);

  if (!hasLoginForm) {
    // Already logged in
    return;
  }

  // Fill and submit login form
  await page.locator('input#email').fill(credentials.email);
  await page.locator('input#password').fill(credentials.password);
  await page.locator('button:has-text("立即登录")').click();

  // Wait for login to complete
  await page.waitForURL((url) => !url.pathname.includes('login'), { timeout: 30000 });

  // Verify login succeeded
  const stillOnLogin = await page
    .locator('input#email')
    .isVisible()
    .catch(() => false);
  if (stillOnLogin) {
    throw new Error('Login failed: still on login page');
  }
}

/**
 * Perform logout
 *
 * @param page - Playwright page
 */
export async function performLogout(page: Page): Promise<void> {
  // Click user avatar to open dropdown
  const userAvatar = page.locator('header img[alt="User avatar"]');
  await userAvatar.click();

  // Click logout link
  await page.locator('a[href="/logout"]').click();

  // Wait for redirect to login page
  await page.waitForURL(/login|^\/$/);
}
