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
import { createCookieSessionStorage } from 'react-router';

const ADMIN_EMAIL = process.env.E2E_ADMIN_EMAIL || 'admin@auraboot.com';
const ADMIN_PASSWORD = process.env.E2E_ADMIN_PASSWORD || 'Test2026x';
const JWT_TOKEN_KEY = 'jwtToken';
const authSessionStorage = createCookieSessionStorage({
  cookie: {
    name: '__session',
    httpOnly: true,
    path: '/',
    sameSite: 'lax',
    secrets: [process.env.SESSION_SECRET || 'dev-only-secret-do-not-use-in-production'],
    secure: false,
  },
});

async function createSessionCookieValue(jwt: string): Promise<string> {
  const session = await authSessionStorage.getSession();
  session.set(JWT_TOKEN_KEY, jwt);
  const setCookie = await authSessionStorage.commitSession(session, {
    maxAge: 60 * 60 * 24 * 7,
  });
  const match = setCookie.match(/__session=([^;]+)/);
  if (!match?.[1]) {
    throw new Error('Failed to create __session cookie');
  }
  return match[1];
}

/**
 * Login through the real /login form. The crafted-cookie path below only
 * half-authenticates in the docker CI stack (its SESSION_SECRET differs from
 * the dev fallback, so the BFF cannot extract a token from the session:
 * the SSR shell renders but every client fetch is unauthorized and the
 * header never becomes fully interactive). Tests that need a WORKING header
 * (LO-001's user-menu logout) must hold a real session.
 */
async function loginViaForm(page: import('@playwright/test').Page): Promise<HeaderPage> {
  await page.goto('/login', { waitUntil: 'domcontentloaded' });
  await expect(page.getByTestId('login-page-root')).toHaveAttribute('data-hydrated', 'true', {
    timeout: 10000,
  });
  const emailInput = page.locator('input#identifier, input#email').first();
  await emailInput.fill(ADMIN_EMAIL);
  await expect(emailInput).toHaveValue(ADMIN_EMAIL, { timeout: 3000 });
  const pwd = page.locator('input#password');
  await pwd.fill(ADMIN_PASSWORD);
  await expect(pwd).toHaveValue(ADMIN_PASSWORD, { timeout: 3000 });
  await page
    .locator('form button[type="submit"], form button:has-text("立即登录"), form button:has-text("Login")')
    .first()
    .click();
  await page.waitForURL((url) => !url.toString().includes('/login'), { timeout: 20000 });

  const header = new HeaderPage(page);
  await expect(header.userAvatar).toBeVisible({ timeout: 15000 });
  return header;
}

async function ensureAuthenticated(page: import('@playwright/test').Page): Promise<HeaderPage> {
  const header = new HeaderPage(page);
  await page.goto(`/meta/models`, { waitUntil: 'domcontentloaded' });

  if (await header.isAuthenticated()) {
    return header;
  }

  const resp = await page.request.post('/api/auth/login', {
    data: { email: ADMIN_EMAIL, password: ADMIN_PASSWORD },
    headers: { 'Content-Type': 'application/json' },
  });
  expect(resp.ok()).toBe(true);
  const body = await resp.json();
  const jwt = body?.data?.jwt;
  expect(typeof jwt).toBe('string');

  const cookieValue = await createSessionCookieValue(jwt);
  const baseURL = new URL(page.url() || 'http://127.0.0.1:5173').origin;
  const cookieBase = {
    name: '__session',
    value: cookieValue,
    httpOnly: true,
    sameSite: 'Lax' as const,
    expires: Math.floor(Date.now() / 1000) + 604800,
  };
  await page.context().addCookies([
    { ...cookieBase, url: baseURL },
    { ...cookieBase, domain: 'localhost', path: '/' },
    { ...cookieBase, domain: '127.0.0.1', path: '/' },
  ]);

  await page.goto(`/meta/models`, { waitUntil: 'domcontentloaded' });
  await expect(header.userAvatar).toBeVisible({ timeout: 10000 });
  return header;
}

test.describe('Logout Functionality', () => {
  // Fresh-login flows (API login + hydration-aware menu interaction) do not fit
  // the fast profile's 15s default budget on CI containers (LO-001 died at
  // 15.1s: "Target page ... has been closed" = timeout cleanup mid-click).
  test.setTimeout(45_000);
  // Fresh session: every test here logs out server-side. Consuming the shared
  // admin storageState would invalidate that session for later specs in the
  // same run (space-selection etc.), so start empty — ensureAuthenticated
  // performs a disposable API login per test.
  test.use({ storageState: { cookies: [], origins: [] } });

  /**
   * LO-001: Logout via user menu
   * Verify that clicking logout link logs user out
   */
  test('LO-001: should logout via user menu @smoke', async ({ page }) => {
    const header = await loginViaForm(page);

    await header.logout();

    await expect(page.locator('input#identifier, input#email').first()).toBeVisible({
      timeout: 10000,
    });
  });

  /**
   * LO-002: Session cleanup after logout
   * Verify that session/auth data is cleared after logout
   */
  test('LO-002: should clear session after logout', async ({ page }) => {
    await ensureAuthenticated(page);

    // Navigate to logout confirmation and submit it.
    await page.goto(`/logout`, { waitUntil: 'commit', timeout: 10000 });
    await page.waitForLoadState('domcontentloaded');
    await page.locator('button:has-text("确认退出"), button:has-text("Log Out")').first().click();
    await page.waitForURL(/\/login/, { timeout: 10000 });

    // Verify client-side token state is cleared and protected pages no longer load authenticated UI.
    const authToken = await page.evaluate(() => localStorage.getItem('token'));
    expect(authToken === null || authToken === '').toBe(true);

    await page.goto(`/meta/models`, { waitUntil: 'domcontentloaded' });
    await expect(page.locator('input#identifier, input#email').first()).toBeVisible({
      timeout: 10000,
    });
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
    const loginForm = page.locator('input#identifier, input#email, input[type="email"]');
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
    const header = await ensureAuthenticated(page);

    // Open user menu and verify logout link is visible
    await header.openUserMenu();

    // Logout link should be visible (Link component renders as <a href="/logout">)
    await expect(header.logoutLink).toBeVisible({ timeout: 3000 });
  });
});
