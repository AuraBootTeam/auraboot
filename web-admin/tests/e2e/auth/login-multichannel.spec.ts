/**
 * Login Multi-Channel E2E Tests
 *
 * Tests the multi-channel login UI: tab switching between EMAIL_PASSWORD / SMS / EMAIL_CODE,
 * social login button rendering, form element visibility, validation, and remember-me.
 *
 * The available channels depend on the tenant configuration returned by
 * GET /api/auth/login/channels. Tests that require a specific channel
 * (SMS, EMAIL_CODE) gracefully skip when the channel is not enabled.
 *
 * @since 4.0.0
 */

import { test, expect } from '../../fixtures';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';
import { ErrorCodes } from '~/shared/services/http-client/types';

// ---------------------------------------------------------------------------
// Config
// ---------------------------------------------------------------------------

const TEST_CREDENTIALS = {
  email: DEFAULT_TEST_ACCOUNT.email,
  password: DEFAULT_TEST_ACCOUNT.password,
};

const LOGIN_URL = '/login';

// Channel tab label mapping (must match CHANNEL_LABELS in Login.tsx)
const TAB_LABELS: Record<string, string> = {
  email_password: '邮箱密码',
  EMAIL_PASSWORD: '邮箱密码',
  sms: '短信登录',
  SMS: '短信登录',
  email_code: '邮箱验证码',
  EMAIL_CODE: '邮箱验证码',
};

// ---------------------------------------------------------------------------
// All tests run in an unauthenticated context
// ---------------------------------------------------------------------------

test.describe('Login Multi-Channel @login-multichannel', () => {
  test.use({ storageState: { cookies: [], origins: [] } });

  // Helper: login and get JWT for admin API calls
  async function getAdminJwt(page: import('@playwright/test').Page): Promise<string> {
    const resp = await page.request.post('/api/auth/login', {
      data: { email: TEST_CREDENTIALS.email, password: TEST_CREDENTIALS.password },
    });
    const body = await resp.json();
    return body?.data?.jwt ?? '';
  }

  // Enable SMS and EMAIL_CODE channels before tests run
  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({
      storageState: 'tests/storage/admin.json',
      baseURL: (process.env.PLAYWRIGHT_BASE_URL ?? process.env.BASE_URL ?? 'http://localhost:5173'),
    });
    const page = await ctx.newPage();
    await page.request.put('/api/admin/login-channels', {
      data: [
        { channel: 'email_password', enabled: true, sortOrder: 0 },
        { channel: 'sms', enabled: true, sortOrder: 1 },
        { channel: 'email_code', enabled: true, sortOrder: 2 },
      ],
    });
    await page.close();
    await ctx.close();
  });

  // Restore default channels after tests
  test.afterAll(async ({ browser }) => {
    const ctx = await browser.newContext({
      storageState: 'tests/storage/admin.json',
      baseURL: (process.env.PLAYWRIGHT_BASE_URL ?? process.env.BASE_URL ?? 'http://localhost:5173'),
    });
    const page = await ctx.newPage();
    await page.request.put('/api/admin/login-channels', {
      data: [
        { channel: 'email_password', enabled: true, sortOrder: 0 },
        { channel: 'sms', enabled: false, sortOrder: 1 },
        { channel: 'email_code', enabled: false, sortOrder: 2 },
      ],
    });
    await page.close();
    await ctx.close();
  });

  // -----------------------------------------------------------------------
  // Helper: detect which tab-channels are available by intercepting the
  // channels API response during navigation.
  // -----------------------------------------------------------------------

  async function getAvailableChannels(page: import('@playwright/test').Page): Promise<string[]> {
    // Navigate to login page first
    await page.goto(LOGIN_URL);
    await page.waitForLoadState('domcontentloaded');

    // Channels are loaded via server-side BFF loader, not client-side fetch.
    // Use direct API call to check available channels.
    const resp = await page.request.get('/api/auth/login/channels');
    if (resp.ok()) {
      try {
        const body = await resp.json();
        if (body?.code === ErrorCodes.SUCCESS && Array.isArray(body.data)) {
          return body.data as string[];
        }
      } catch {
        // fall through
      }
    }

    // Fallback: EMAIL_PASSWORD is always available
    return ['email_password'];
  }

  // -----------------------------------------------------------------------
  // Helper: find a tab button by its channel code
  // -----------------------------------------------------------------------

  function tabButton(page: import('@playwright/test').Page, channelCode: string) {
    const label = TAB_LABELS[channelCode] || channelCode;
    return page.getByRole('tab', { name: label, exact: true });
  }

  // -----------------------------------------------------------------------
  // LM-001: Default login page shows email/password form
  // -----------------------------------------------------------------------

  test('LM-001: default login page shows email/password form', async ({ page }) => {
    await page.goto(LOGIN_URL);
    await page.waitForLoadState('domcontentloaded');

    // Email and password inputs visible
    await expect(page.locator('#email')).toBeVisible();
    await expect(page.locator('#password')).toBeVisible();

    // Submit button visible
    await expect(page.locator('button:has-text("立即登录")')).toBeVisible();
  });

  // -----------------------------------------------------------------------
  // LM-002: Email/password login works @smoke
  // -----------------------------------------------------------------------

  test('LM-002: email/password login works @smoke', async ({ browser }) => {
    test.setTimeout(30000); // Login + redirect can be slow under parallel load
    // Use an isolated context so authenticated state does not leak
    const context = await browser.newContext({ storageState: { cookies: [], origins: [] } });
    const page = await context.newPage();

    try {
      await page.goto(LOGIN_URL);
      await page.waitForLoadState('domcontentloaded');

      // Wait for the email input to be ready
      await page.locator('#email').waitFor({ state: 'visible', timeout: 5000 });

      // Fill credentials (click-before-fill to ensure React hydration)
      await page.locator('#email').click();
      await page.locator('#email').fill(TEST_CREDENTIALS.email);

      await page.locator('#password').click();
      await page.locator('#password').fill(TEST_CREDENTIALS.password);

      // Submit
      await page.locator('button:has-text("立即登录")').click();

      // Wait until we leave /login
      await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 20000 });

      expect(page.url()).not.toContain('/login');
    } finally {
      await context.close();
    }
  });

  // -----------------------------------------------------------------------
  // LM-003: Channel tab switching
  // -----------------------------------------------------------------------

  test('LM-003: channel tab switching', async ({ page }) => {
    const channels = await getAvailableChannels(page);
    if (!channels.includes('sms') || !channels.includes('email_code')) {
      test.skip(true, 'SMS or EMAIL_CODE login channel is not enabled for public login');
      return;
    }

    // beforeAll enables EMAIL_PASSWORD + SMS + EMAIL_CODE — all 3 tabs should be visible
    await page.goto(LOGIN_URL);
    await page.waitForLoadState('domcontentloaded');
    await page.locator('#email').waitFor({ state: 'visible', timeout: 5000 });

    // Switch to SMS tab
    await tabButton(page, 'sms').click();
    await expect(tabButton(page, 'sms')).toHaveAttribute('aria-selected', 'true');
    await expect(page.locator('#mobile')).toBeVisible();

    // Switch to EMAIL_CODE tab
    await tabButton(page, 'email_code').click();
    await expect(tabButton(page, 'email_code')).toHaveAttribute('aria-selected', 'true');
    await expect(page.locator('#ec-email')).toBeVisible();

    // Switch back to EMAIL_PASSWORD
    await tabButton(page, 'email_password').click();
    await expect(tabButton(page, 'email_password')).toHaveAttribute('aria-selected', 'true');
    await expect(page.locator('#email')).toBeVisible();
    await expect(page.locator('#password')).toBeVisible();
  });

  // -----------------------------------------------------------------------
  // LM-004: SMS form UI elements
  // -----------------------------------------------------------------------

  test('LM-004: SMS form UI elements', async ({ page }) => {
    const channels = await getAvailableChannels(page);
    if (!channels.includes('sms')) {
      test.skip(true, 'SMS login channel is not enabled for public login');
      return;
    }

    // beforeAll enables SMS channel — navigate and switch to SMS tab
    await page.goto(LOGIN_URL);
    await page.waitForLoadState('domcontentloaded');
    await page.locator('#email').waitFor({ state: 'visible', timeout: 5000 });

    await tabButton(page, 'sms').click();
    await expect(tabButton(page, 'sms')).toHaveAttribute('aria-selected', 'true');

    // Verify SMS form elements
    await expect(page.locator('#mobile')).toBeVisible();
    await expect(page.locator('#sms-code')).toBeVisible();

    // "获取验证码" button
    const sendBtn = page.locator('button:has-text("获取验证码")');
    await expect(sendBtn).toBeVisible();
  });

  // -----------------------------------------------------------------------
  // LM-005: Email code form UI elements
  // -----------------------------------------------------------------------

  test('LM-005: email code form UI elements', async ({ page }) => {
    const channels = await getAvailableChannels(page);
    if (!channels.includes('email_code')) {
      test.skip(true, 'EMAIL_CODE login channel is not enabled for public login');
      return;
    }

    // beforeAll enables EMAIL_CODE channel — navigate and switch to EMAIL_CODE tab
    await page.goto(LOGIN_URL);
    await page.waitForLoadState('domcontentloaded');
    await page.locator('#email').waitFor({ state: 'visible', timeout: 5000 });

    await tabButton(page, 'email_code').click();
    await expect(tabButton(page, 'email_code')).toHaveAttribute('aria-selected', 'true');

    // Verify email-code form elements
    await expect(page.locator('#ec-email')).toBeVisible();
    await expect(page.locator('#ec-code')).toBeVisible();

    // "获取验证码" button
    const sendBtn = page.locator('button:has-text("获取验证码")');
    await expect(sendBtn).toBeVisible();
  });

  // -----------------------------------------------------------------------
  // LM-006: Empty form submission shows validation
  // -----------------------------------------------------------------------

  test('LM-006: empty form submission shows validation', async ({ page }) => {
    await page.goto(LOGIN_URL);
    await page.waitForLoadState('domcontentloaded');
    await page.locator('#email').waitFor({ state: 'visible', timeout: 5000 });

    // Submit without filling anything
    await page.locator('button:has-text("立即登录")').click();

    // Should stay on login page — either HTML5 validation prevents submit
    // or the server returns an error.
    // Because the inputs have `required`, the browser prevents submission
    // and we remain on the login page.
    await page.waitForLoadState('domcontentloaded');

    // Verify we are still on the login page
    expect(page.url()).toContain('/login');

    // The email input should still be visible (form was not submitted)
    await expect(page.locator('#email')).toBeVisible();
  });

  // -----------------------------------------------------------------------
  // LM-007: Remember-me checkbox
  // -----------------------------------------------------------------------

  test('LM-007: remember-me checkbox', async ({ page }) => {
    await page.goto(LOGIN_URL);
    await page.waitForLoadState('domcontentloaded');
    await page.locator('#email').waitFor({ state: 'visible', timeout: 5000 });

    const rememberCheckbox = page.locator('#remember');

    // Initially unchecked (unless previously saved — fresh context has no localStorage)
    await expect(rememberCheckbox).not.toBeChecked();

    // Fill email first
    await page.locator('#email').click();
    await page.locator('#email').fill(TEST_CREDENTIALS.email);

    // Check the remember-me box
    await rememberCheckbox.check();
    await expect(rememberCheckbox).toBeChecked();

    // Uncheck it
    await rememberCheckbox.uncheck();
    await expect(rememberCheckbox).not.toBeChecked();
  });
});
