/**
 * Authentication helper for E2E tests
 * Provides login functionality for test scenarios
 */

import { expect } from '@playwright/test';
import type { Page } from '@playwright/test';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';

export const TEST_CONFIG = {
  baseURL: 'http://localhost:5173',
  credentials: {
    email: DEFAULT_TEST_ACCOUNT.email,
    password: DEFAULT_TEST_ACCOUNT.password,
  },
  timeout: 30000
};

/**
 * Login to the application
 */
export async function login(page: Page): Promise<void> {
  await page.goto(`${TEST_CONFIG.baseURL}/`);
  await page.waitForLoadState('domcontentloaded');

  // Check if already logged in (no login form visible)
  const loginForm = page.locator('input#email, input#password');
  const hasLoginForm = await loginForm.first().isVisible().catch(() => false);

  if (!hasLoginForm) {
    // Already logged in, no action needed
    console.log('Already logged in');
    return;
  }

  // Fill email
  const emailInput = page.locator('input#email');
  await emailInput.fill(TEST_CONFIG.credentials.email);

  // Fill password
  const passwordInput = page.locator('input#password');
  await passwordInput.fill(TEST_CONFIG.credentials.password);

  // Click login button
  const loginButton = page.locator('button:has-text("立即登录")');
  await loginButton.click();

  // Wait for login to complete - either URL changes or login form disappears
  await Promise.race([
    page.waitForURL(url => !url.pathname.includes('login'), { timeout: TEST_CONFIG.timeout }),
    page.waitForSelector('input#email', { state: 'hidden', timeout: TEST_CONFIG.timeout })
  ]).catch(() => {
    // Timeout is ok if we're already past login
  });

  // Wait for page to stabilize
  await page.waitForLoadState('domcontentloaded');

  // Final check - verify not on login page
  const stillOnLoginPage = await page.locator('input#email').isVisible().catch(() => false);
  if (stillOnLoginPage) {
    throw new Error('Login failed: still on login page');
  }
}

/**
 * Get authorization headers for API requests
 */
export async function getAuthHeaders(page: Page): Promise<Record<string, string>> {
  // Get cookies from the page context
  const cookies = await page.context().cookies();
  const sessionCookie = cookies.find(c => c.name === '__session' || c.name === 'token');

  if (sessionCookie) {
    return {
      'Cookie': `${sessionCookie.name}=${sessionCookie.value}`
    };
  }

  return {};
}
