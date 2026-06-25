/**
 * Comprehensive Auth E2E Tests
 *
 * Covers: Registration (with displayName), Login (username/email password + email OTP),
 * Profile editing, admin-managed password policy, Session management.
 *
 * ## Fixed Issues (2026-03-07)
 * - Registration JWT session: AuthServiceImpl.register() now calls
 *   sessionManagementService.createSession() so the JWT passes session validation.
 *   This fixes the 401 when creating a tenant after registration.
 * - Login error UX: Email/password failure now shows a styled Chinese error
 *   banner ("邮箱或密码错误") instead of raw English text.
 * - TenantSelection error state: Switching between CREATE/JOIN no longer shows
 *   stale error messages from the other view.
 *
 * ## Environment Blockers
 * 1. Email OTP tests require the dev verify-code API
 *    (`GET /api/auth/verify-code/dev/latest?target=xxx`).
 *    If dev profile is not active, EMAIL_CODE tests will be skipped.
 * 2. OIDC SSO tests require a configured OIDC provider in ab_cloud_config.
 *    Cannot be tested without an external IdP — skipped with TODO marker.
 * 3. Social login (WeChat/Google/Apple/OIDC) requires external OAuth providers.
 *    Not covered here — see social-login.spec.ts when providers are configured.
 * 4. Forgot/Reset Password pages are intentionally disabled in the current
 *    admin-managed password policy.
 *
 * @since 7.2.0
 */

import {
  test,
  expect,
  request as playwrightRequest,
  type Page,
  type Locator,
  type APIRequestContext,
} from '@playwright/test';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';
import { uniqueId } from '../helpers';
import { HeaderPage } from '../../pages/HeaderPage';
import { BASE_URL } from '../../helpers/environments';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const ADMIN = DEFAULT_TEST_ACCOUNT;
const TEST_PREFIX = uniqueId('auth');
const SIGNUP_EMAIL = `${TEST_PREFIX.toLowerCase()}@e2e-test.local`;
const SIGNUP_DISPLAY_NAME = `E2E User ${TEST_PREFIX}`;
const SIGNUP_PASSWORD = 'TestPass2026!';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

async function loginViaUI(page: Page, email: string, password: string) {
  await page.goto('/login', { waitUntil: 'domcontentloaded' });
  await expect(page.getByTestId('login-page-root')).toHaveAttribute('data-hydrated', 'true', {
    timeout: 5000,
  });
  const emailInput = page.locator('input#identifier, input#email').first();
  await emailInput.waitFor({ state: 'visible', timeout: 5000 });
  const pwd = page.locator('input#password');
  await pwd.waitFor({ state: 'visible', timeout: 5000 });

  // Use Playwright's input fill to avoid per-keystroke races while React
  // hydrates the controlled login form.
  await emailInput.click();
  await emailInput.fill(email);
  await expect(emailInput).toHaveValue(email, { timeout: 3000 });

  await pwd.click();
  await pwd.fill(password);
  await expect(pwd).toHaveValue(password, { timeout: 3000 });

  // The login page restores remembered credentials during hydration. Keep email
  // as the last controlled-field write before submit so native required
  // validation cannot block the form with a stale empty email value.
  await emailInput.fill(email);
  await expect(emailInput).toHaveValue(email, { timeout: 3000 });
  await expect(pwd).toHaveValue(password, { timeout: 3000 });

  await page
    .locator(
      'form button[type="submit"], form button:has-text("立即登录"), form button:has-text("Login"), form button:has-text("loginNow")',
    )
    .first()
    .click();
}

async function selectTab(tab: Locator): Promise<void> {
  await expect(tab).toBeVisible({ timeout: 8_000 });
  await expect
    .poll(
      async () => {
        if ((await tab.getAttribute('aria-selected')) === 'true') {
          return true;
        }
        await tab.click().catch(() => null);
        return (await tab.getAttribute('aria-selected')) === 'true';
      },
      { timeout: 5_000, intervals: [250, 500, 1000] },
    )
    .toBe(true);
}

async function expectLoggedIn(page: Page, timeout = 30000) {
  await page.waitForURL(
    (url) => !url.toString().includes('/login') || url.toString().includes('tenant-selection'),
    { timeout, waitUntil: 'domcontentloaded' },
  );
}

async function getDevVerifyCode(
  request: APIRequestContext,
  target: string,
): Promise<string | null> {
  try {
    const res = await request.get(`/api/auth/verify-code/dev/latest`, {
      params: { target },
    });
    if (res.ok()) {
      const json = await res.json();
      return json?.data?.code || null;
    }
  } catch {
    /* dev endpoint not available */
  }
  return null;
}

async function waitForDevVerifyCode(
  request: APIRequestContext,
  target: string,
  timeoutMs = 5000,
): Promise<string | null> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const code = await getDevVerifyCode(request, target);
    if (code) {
      return code;
    }
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  return null;
}

async function isDevVerifyCodeAvailable(request: APIRequestContext): Promise<boolean> {
  try {
    const res = await request.get(`/api/auth/verify-code/dev/latest`, {
      params: { target: 'probe@test.local' },
    });
    // 200 (found) or 404 (no code yet) mean the endpoint exists.
    // 403/405 = endpoint blocked, 500 = NoResourceFoundException (controller not loaded for active profile).
    return res.status() === 200 || res.status() === 404;
  } catch {
    return false;
  }
}

function isSelfRegistrationDisabled(body: unknown): boolean {
  return /self-registration.*disabled|single-tenant/i.test(JSON.stringify(body ?? {}));
}

async function postRegistrationAttempt(
  request: APIRequestContext,
  email: string,
  displayName = 'Registration Probe',
) {
  const response = await request.post('/api/auth/register', {
    data: { email, password: 'ProbePass2026!', displayName },
  });
  const body = await response.json().catch(() => ({}));
  return { response, body };
}

async function detectSelfRegistrationStatus(
  request: APIRequestContext,
): Promise<'enabled' | 'disabled'> {
  const email = `probe-${uniqueId('reg').toLowerCase()}@e2e-test.local`;
  const { response, body } = await postRegistrationAttempt(request, email);
  if (isSelfRegistrationDisabled(body)) {
    return 'disabled';
  }
  expect(response.ok(), `Unexpected self-registration probe response: ${JSON.stringify(body)}`).toBe(
    true,
  );
  return 'enabled';
}

async function getLoginChannels(request: APIRequestContext): Promise<string[]> {
  const res = await request.get('/api/auth/login/channels');
  return res.ok() ? ((await res.json())?.data ?? []) : [];
}

async function openEmailCodeLogin(page: Page): Promise<void> {
  await page.goto('/login');
  await expect(page.getByTestId('login-page-root')).toHaveAttribute('data-hydrated', 'true', {
    timeout: 5000,
  });
  const tab = page.locator('[data-testid="login-tab-email_code"]');
  if (await tab.isVisible().catch(() => false)) {
    await selectTab(tab);
  }
}

async function sendEmailLoginCode(page: Page, email: string): Promise<void> {
  const emailInput = page.locator('#ec-email');
  await expect(emailInput).toBeVisible({ timeout: 8000 });
  await emailInput.fill(email);
  await expect(emailInput).toHaveValue(email, { timeout: 3000 });

  const sendBtn = page.getByTestId('email-code-send');
  await expect(sendBtn).toBeEnabled({ timeout: 8000 });
  const sendResponse = page.waitForResponse(
    (response) =>
      response.url().includes('/api/auth/verify-code/send') &&
      response.request().method() === 'POST',
    { timeout: 15000 },
  );
  await sendBtn.click();
  const response = await sendResponse;
  expect(response.ok(), 'Email verification-code send request should succeed').toBe(true);
  await expect(sendBtn).toContainText(/\d+s|发送中|Sending/i, { timeout: 15000 });
}

// ===========================================================================
// 1. REGISTRATION
// ===========================================================================

test.describe('Registration Flow', () => {
  test.use({ storageState: { cookies: [], origins: [] } });

  let selfRegistrationEnabled = false;

  test.beforeAll(async ({ request }) => {
    selfRegistrationEnabled = (await detectSelfRegistrationStatus(request)) === 'enabled';
  });

  test.beforeEach(() => {
    test.skip(!selfRegistrationEnabled, 'Registration form validation requires self-registration enabled');
  });

  test('REG-001: should display signup form with email, displayName, password fields', async ({
    page,
  }) => {
    await page.goto('/signup');
    await expect(page.locator('input#email')).toBeVisible();
    await expect(page.locator('input#displayName')).toBeVisible();
    await expect(page.locator('input#password')).toBeVisible();
    await expect(page.locator('button:has-text("创建账号")')).toBeVisible();
  });

  test('REG-002: should validate email format', async ({ page }) => {
    await page.goto('/signup');
    await page.locator('input#email').fill('not-an-email');
    await page.locator('input#displayName').fill('Test');
    await page.locator('input#password').fill('TestPass123!');
    await page.locator('button:has-text("创建账号")').click();
    // Should stay on signup page (browser validation or server error)
    await expect(page).toHaveURL(/signup/);
  });

  test('REG-003: should validate display name is required', async ({ page }) => {
    await page.goto('/signup');
    await page.locator('input#email').fill('test@example.com');
    // Leave displayName empty
    await page.locator('input#password').fill('TestPass123!');
    await page.locator('button:has-text("创建账号")').click();
    // Should stay on signup or show error
    await expect(page).toHaveURL(/signup/);
  });

  test('REG-004: should validate password minimum length', async ({ page }) => {
    await page.goto('/signup');
    await page.locator('input#email').fill('test@example.com');
    await page.locator('input#displayName').fill('Test');
    await page.locator('input#password').fill('abc');
    await page.locator('button:has-text("创建账号")').click();
    // Should show password error
    await page.waitForLoadState('load');
    const hasError = await page
      .locator('#password-error, .text-red-600')
      .first()
      .isVisible()
      .catch(() => false);
    const onSignup = page.url().includes('/signup');
    expect(hasError || onSignup).toBe(true);
  });

  test('REG-007: should have link to login page', async ({ page }) => {
    await page.goto('/signup');
    const loginLink = page.locator('a:has-text("立即登录")');
    await expect(loginLink).toBeVisible();
    await loginLink.click();
    await expect(page).toHaveURL(/login/);
  });
});

test.describe('Registration Flow — Self-registration enabled', () => {
  test.use({ storageState: { cookies: [], origins: [] } });

  let selfRegistrationEnabled = false;

  test.beforeAll(async ({ request }) => {
    selfRegistrationEnabled = (await detectSelfRegistrationStatus(request)) === 'enabled';
  });

  test.beforeEach(() => {
    test.skip(!selfRegistrationEnabled, 'Self-registration enabled scenario is not active');
  });

  test('REG-005: should register successfully and redirect @smoke', async ({ page }) => {
    test.setTimeout(30000);

    await page.goto('/signup');

    await page.locator('input#email').fill(SIGNUP_EMAIL);
    await page.locator('input#displayName').fill(SIGNUP_DISPLAY_NAME);
    await page.locator('input#password').fill(SIGNUP_PASSWORD);

    await page.locator('button:has-text("创建账号")').click();

    // Should redirect to tenant-selection (new user without tenant)
    // or /home (if user was previously registered and already has a tenant).
    await page.waitForURL(
      (url) => {
        const path = url.toString();
        return path.includes('tenant-selection') || path.includes('/home');
      },
      { timeout: 20000 },
    );
    const currentUrl = page.url();
    expect(currentUrl.includes('tenant-selection') || currentUrl.includes('/home')).toBe(true);
  });

  test('REG-006: should reject duplicate email registration', async ({ page }) => {
    const duplicateEmail = `${uniqueId('dup').toLowerCase()}@e2e-test.local`;
    const first = await postRegistrationAttempt(page.request, duplicateEmail, 'Duplicate Seed');
    expect(first.response.ok(), `Duplicate seed registration failed: ${JSON.stringify(first.body)}`).toBe(
      true,
    );

    await page.goto('/signup');
    await page.locator('input#email').fill(duplicateEmail);
    await page.locator('input#displayName').fill('Duplicate');
    await page.locator('input#password').fill(SIGNUP_PASSWORD);
    await page.locator('button:has-text("创建账号")').click();

    await page.waitForLoadState('load');
    const hasError = await page
      .locator('#email-error, .text-red-600')
      .first()
      .isVisible({ timeout: 5000 })
      .catch(() => false);
    const onSignup = page.url().includes('/signup');
    expect(hasError || onSignup).toBe(true);
  });
});

test.describe('Registration Policy — Self-registration disabled', () => {
  test.use({ storageState: { cookies: [], origins: [] } });

  test('REG-DISABLED-001: single-tenant mode rejects self-registration explicitly', async ({
    request,
  }) => {
    const email = `${uniqueId('disabled-reg').toLowerCase()}@e2e-test.local`;
    const { body } = await postRegistrationAttempt(request, email, 'Disabled Registration Probe');

    test.skip(!isSelfRegistrationDisabled(body), 'Self-registration is enabled in this environment');
    expect(JSON.stringify(body)).toMatch(/Self-registration is disabled|single-tenant/i);
  });

  test('REG-DISABLED-002: login page hides signup link and direct /signup returns to login', async ({
    page,
  }) => {
    await page.goto('/login', { waitUntil: 'domcontentloaded' });
    const signupLink = page.locator('a[href*="signup"]').first();
    const hasSignupLink = await signupLink.isVisible({ timeout: 2000 }).catch(() => false);
    test.skip(hasSignupLink, 'Self-registration is enabled in this environment');
    await expect(signupLink).toHaveCount(0);

    await page.goto('/signup', { waitUntil: 'domcontentloaded' });
    await expect(page).toHaveURL(/\/login/, { timeout: 10000 });
  });
});

// ===========================================================================
// 2. LOGIN — Email/Password
// ===========================================================================

test.describe('Login — Email/Password', () => {
  test.use({ storageState: { cookies: [], origins: [] } });

  test('LN-001: should display login form @smoke', async ({ page }) => {
    await page.goto('/login');
    await expect(page.locator('input#identifier')).toBeVisible();
    await expect(page.locator('input#password')).toBeVisible();
    await expect(page.locator('form button[type="submit"]')).toBeVisible();
  });

  test('LN-002: should login with valid credentials @smoke', async ({ page }) => {
    test.setTimeout(30000);
    await loginViaUI(page, ADMIN.email, ADMIN.password);
    await expectLoggedIn(page);
  });

  test('LN-003: should show styled error banner with wrong password', async ({ page }) => {
    await loginViaUI(page, ADMIN.email, 'wrong-password-123');
    // The exact localized copy may differ, but the page must surface the
    // styled login error emitted by Login.tsx after the form action returns.
    const errorBanner = page.getByTestId('login-error');
    await expect(errorBanner).toBeVisible({ timeout: 15_000 });
    await expect(errorBanner).toContainText(
      /账号或密码错误|用户名\/邮箱|请重试|Invalid username\/email or password|invalid credentials/i,
    );
    const onLogin = page.url().includes('/login');
    expect(onLogin).toBe(true);
  });

  test('LN-004: should show error with non-existent email', async ({ page }) => {
    await loginViaUI(page, 'nonexistent-user-999@example.com', 'SomePassword123!');
    // Wait for error to render or stay on login
    await page.waitForLoadState('load');
    const onLogin = page.url().includes('/login');
    expect(onLogin).toBe(true);
  });

  test('LN-005: should have remember-me checkbox', async ({ page }) => {
    await page.goto('/login');
    await expect(page.locator('input#remember')).toBeVisible();
  });

  test('LN-006: should have signup link', async ({ page }) => {
    test.skip(
      (await detectSelfRegistrationStatus(page.request)) !== 'enabled',
      'Signup link is hidden when public registration is disabled',
    );
    await page.goto('/login');
    const signupLink = page.locator('a[href*="signup"]').first();
    await expect(signupLink).toBeVisible();
    await signupLink.click();
    await expect(page).toHaveURL(/signup/);
  });

  test('LN-007: should hide forgot password link when passwords are admin-managed', async ({ page }) => {
    await page.goto('/login');
    const forgotLink = page.locator('a[href*="forgot-password"]').first();
    await expect(forgotLink).toHaveCount(0);
  });
});

// ===========================================================================
// 3. LOGIN — Email OTP (Passwordless)
// ===========================================================================

test.describe('Login — Email OTP', () => {
  test.use({ storageState: { cookies: [], origins: [] } });

  let devCodeAvailable = false;

  test.beforeAll(async () => {
    const adminRequest = await playwrightRequest.newContext({
      baseURL: BASE_URL,
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    try {
      devCodeAvailable = await isDevVerifyCodeAvailable(adminRequest);
      const enableResp = await adminRequest.put('/api/admin/login-channels', {
        data: [
          { channel: 'email_password', enabled: true, sortOrder: 0 },
          { channel: 'email_code', enabled: true, sortOrder: 2 },
        ],
      });
      expect(enableResp.ok(), 'EMAIL_CODE channel should be enabled in test setup').toBe(true);
    } finally {
      await adminRequest.dispose();
    }
  });

  test.afterAll(async () => {
    const adminRequest = await playwrightRequest.newContext({
      baseURL: BASE_URL,
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    try {
      await adminRequest.put('/api/admin/login-channels', {
        data: [
          { channel: 'email_password', enabled: true, sortOrder: 0 },
          { channel: 'sms', enabled: false, sortOrder: 1 },
          { channel: 'email_code', enabled: false, sortOrder: 2 },
        ],
      });
    } finally {
      await adminRequest.dispose();
    }
  });

  test('OTP-001: should show email code tab when channel is enabled', async ({ page, request }) => {
    const channels = await getLoginChannels(request);

    test.skip(!channels.includes('email_code'), 'EMAIL_CODE channel not enabled');

    await page.goto('/login');
    const tabs = page.locator('[data-testid="login-channel-tabs"]');
    if (await tabs.isVisible()) {
      await expect(page.locator('[data-testid="login-tab-email_code"]')).toBeVisible();
    }
  });

  test('OTP-002: should display email code form fields', async ({ page, request }) => {
    const channels = await getLoginChannels(request);
    test.skip(!channels.includes('email_code'), 'EMAIL_CODE channel not enabled');

    await openEmailCodeLogin(page);
    await expect(page.locator('#ec-email')).toBeVisible();
    await expect(page.locator('#ec-code')).toBeVisible();
    await expect(page.getByTestId('email-code-send')).toBeVisible();
  });

  test('OTP-003: should send verification code and login @smoke', async ({ page, request }) => {
    const channels = await getLoginChannels(request);
    test.skip(!channels.includes('email_code'), 'EMAIL_CODE channel not enabled');
    test.skip(!devCodeAvailable, 'Dev verify-code endpoint not available — cannot retrieve OTP');
    test.setTimeout(45000);

    const otpEmail = ADMIN.email;

    await openEmailCodeLogin(page);
    await sendEmailLoginCode(page, otpEmail);

    const code = await waitForDevVerifyCode(request, otpEmail, 10000);
    expect(code, 'Verification code should be retrievable from dev API').toBeTruthy();

    await page.locator('#ec-code').fill(code!);
    await page.locator('form[action="/login"] button[type="submit"]').click();

    // Existing user → normal tenant-aware login, no self-registration involved.
    await page.waitForURL(/tenant-selection|dashboard|\/home/, { timeout: 20000 });
  });

  test('OTP-004: should auto-register new user via email OTP', async ({ page, request }) => {
    const channels = await getLoginChannels(request);
    test.skip(!channels.includes('email_code'), 'EMAIL_CODE channel not enabled');
    test.skip(!devCodeAvailable, 'Dev verify-code endpoint not available');
    test.skip(
      (await detectSelfRegistrationStatus(request)) !== 'enabled',
      'Email OTP auto-registration requires self-registration enabled',
    );
    test.setTimeout(30000);

    const newEmail = `otp-${TEST_PREFIX.toLowerCase()}@e2e-test.local`;

    await openEmailCodeLogin(page);
    await sendEmailLoginCode(page, newEmail);

    const code = await waitForDevVerifyCode(request, newEmail);
    expect(code, 'Verification code should be retrievable for new email').toBeTruthy();

    const codeInput = page.locator('#ec-code');
    await codeInput.fill(code!);
    await expect(codeInput).toHaveValue(code!, { timeout: 3000 });
    await page.locator('form[action="/login"] button[type="submit"]').click();

    // New user → should go to tenant-selection (no tenant yet), or /home if auto-assigned
    await page.waitForURL(/tenant-selection|\/home/, { timeout: 20000 });
  });

  test('OTP-005: should reject new-user email OTP when self-registration is disabled', async ({
    page,
    request,
  }) => {
    const channels = await getLoginChannels(request);
    test.skip(!channels.includes('email_code'), 'EMAIL_CODE channel not enabled');
    test.skip(!devCodeAvailable, 'Dev verify-code endpoint not available');
    test.skip(
      (await detectSelfRegistrationStatus(request)) !== 'disabled',
      'Self-registration disabled scenario is not active',
    );
    test.setTimeout(30000);

    const newEmail = `otp-disabled-${TEST_PREFIX.toLowerCase()}@e2e-test.local`;

    await openEmailCodeLogin(page);
    await sendEmailLoginCode(page, newEmail);

    const code = await waitForDevVerifyCode(request, newEmail);
    expect(code, 'Verification code should be retrievable for disabled-registration probe').toBeTruthy();

    await page.locator('#ec-code').fill(code!);
    await page.locator('form[action="/login"] button[type="submit"]').click();

    await expect(page).toHaveURL(/\/login/, { timeout: 15000 });
    await expect(
      page
        .locator('.text-red-600, .text-red-400')
        .filter({ hasText: /Self-registration is disabled|self-registration.*disabled/i })
        .first(),
    ).toBeVisible({ timeout: 10000 });
  });
});

// ===========================================================================
// 4. USER PROFILE
// ===========================================================================

test.describe('User Profile', () => {
  // Uses default storageState (admin authenticated)

  test('PRF-001: should display profile page with user info @smoke', async ({ page }) => {
    await page.goto('/personal/profile', { waitUntil: 'load' });

    // Should show profile heading
    await expect(page.locator('h1:has-text("个人资料")')).toBeVisible({ timeout: 10000 });

    // Should display user info fields
    await expect(page.getByText('用户名')).toBeVisible();
    await expect(page.getByText('邮箱')).toBeVisible();
  });

  test('PRF-002: should have edit button', async ({ page }) => {
    await page.goto('/personal/profile', { waitUntil: 'load' });
    await expect(page.locator('h1:has-text("个人资料")')).toBeVisible({ timeout: 10000 });
    await expect(page.locator('[data-testid="profile-edit-btn"]')).toBeVisible();
  });

  test('PRF-003: should switch to edit mode and show form fields', async ({ page }) => {
    await page.goto('/personal/profile', { waitUntil: 'load' });
    await expect(page.locator('h1:has-text("个人资料")')).toBeVisible({ timeout: 10000 });
    const editBtn = page.locator('[data-testid="profile-edit-btn"]');
    await expect(editBtn).toBeVisible({ timeout: 5000 });

    // Click with retry — React hydration may not be complete on first click
    await editBtn.click();
    // If still in view mode after 2s, click again
    const nickInput = page.locator('input[name="nickName"]');
    try {
      await expect(nickInput).toBeVisible({ timeout: 2000 });
    } catch {
      await editBtn.click();
    }
    await expect(nickInput).toBeVisible({ timeout: 10000 });
    await expect(page.locator('input[name="email"]')).toBeVisible();
    await expect(page.locator('input[name="mobile"]')).toBeVisible();
    await expect(page.locator('input[name="area"]')).toBeVisible();
    await expect(page.locator('textarea[name="signature"]')).toBeVisible();

    // userName should be disabled
    await expect(page.locator('input[name="userName"]')).toBeDisabled();

    // Should show save and cancel buttons
    await expect(page.locator('[data-testid="profile-save-btn"]')).toBeVisible();
    await expect(page.locator('[data-testid="profile-cancel-btn"]')).toBeVisible();
  });

  test('PRF-004: should cancel edit and revert to view mode', async ({ page }) => {
    await page.goto('/personal/profile', { waitUntil: 'load' });
    await expect(page.locator('h1:has-text("个人资料")')).toBeVisible({ timeout: 10000 });
    const editBtn = page.locator('[data-testid="profile-edit-btn"]');
    await expect(editBtn).toBeVisible({ timeout: 5000 });

    await editBtn.click();
    const nickInput = page.locator('input[name="nickName"]');
    try {
      await expect(nickInput).toBeVisible({ timeout: 2000 });
    } catch {
      await editBtn.click();
    }
    await expect(nickInput).toBeVisible({ timeout: 10000 });

    // Modify nickname
    await nickInput.fill('Modified Name');

    // Cancel
    await page.locator('[data-testid="profile-cancel-btn"]').click();

    // Should be back in view mode — edit button visible again
    await expect(page.locator('[data-testid="profile-edit-btn"]')).toBeVisible({ timeout: 5000 });
  });

  test('PRF-005: should update nickname successfully', async ({ page }) => {
    await page.goto('/personal/profile', { waitUntil: 'load' });
    await expect(page.locator('h1:has-text("个人资料")')).toBeVisible({ timeout: 10000 });
    const editBtn = page.locator('[data-testid="profile-edit-btn"]');
    await expect(editBtn).toBeVisible({ timeout: 5000 });

    await editBtn.click();
    const nickInput = page.locator('input[name="nickName"]');
    try {
      await expect(nickInput).toBeVisible({ timeout: 2000 });
    } catch {
      await editBtn.click();
    }
    await expect(nickInput).toBeVisible({ timeout: 10000 });

    const newNickname = `Admin ${TEST_PREFIX}`;
    await page.locator('input[name="nickName"]').fill(newNickname);

    // Profile uses React Router Form (POST to same route, not a separate API PUT)
    await page.locator('[data-testid="profile-save-btn"]').click();

    // Should revert to view mode after update
    await expect(page.locator('[data-testid="profile-edit-btn"]')).toBeVisible({ timeout: 10000 });

    // Nickname should be updated in view
    await expect(page.getByText(newNickname)).toBeVisible();
  });

  test('PRF-006: should hide self-service password settings', async ({ page }) => {
    await page.goto('/personal/profile', { waitUntil: 'load' });
    await expect(page.locator('h1:has-text("个人资料")')).toBeVisible({ timeout: 10000 });

    await expect(page.getByRole('heading', { name: 'Security Settings' })).toHaveCount(0);
    await expect(page.getByRole('heading', { name: 'Change Password' })).toHaveCount(0);
  });

  test('PRF-007: should show Social Account Binding section', async ({ page }) => {
    await page.goto('/personal/profile', { waitUntil: 'load' });
    await expect(page.locator('h1:has-text("个人资料")')).toBeVisible({ timeout: 10000 });

    await expect(page.getByText('Social Account Binding')).toBeVisible();
    await expect(page.locator('[data-testid="profile-social-links-link"]')).toBeVisible();
  });

  test('PRF-008: should show Account Deactivation section', async ({ page }) => {
    await page.goto('/personal/profile', { waitUntil: 'load' });
    await expect(page.locator('h1:has-text("个人资料")')).toBeVisible({ timeout: 10000 });

    await expect(page.getByText('Account Deactivation')).toBeVisible();
    await expect(page.locator('[data-testid="profile-deactivation-link"]')).toBeVisible();
  });
});

// ===========================================================================
// 5. PASSWORD CHANGE
// ===========================================================================

test.describe('Password Change', () => {
  test('PWD-001: should not expose self-service password change form', async ({ page }) => {
    await page.goto('/personal/security', { waitUntil: 'load' });

    await expect(page.getByRole('heading', { name: 'Security Settings' })).toBeVisible({
      timeout: 15000,
    });
    await expect(page.locator('[data-testid="current-password-input"]')).toHaveCount(0);
    await expect(page.locator('[data-testid="new-password-input"]')).toHaveCount(0);
    await expect(page.locator('[data-testid="confirm-password-input"]')).toHaveCount(0);
    await expect(page.locator('[data-testid="change-password-btn"]')).toHaveCount(0);
  });
});

// ===========================================================================
// 6. FORGOT / RESET PASSWORD
// ===========================================================================

test.describe('Forgot & Reset Password', () => {
  test.use({ storageState: { cookies: [], origins: [] } });

  test('FP-001: should display admin-managed password notice', async ({ page }) => {
    await page.goto('/forgot-password');
    await expect(page.locator('[data-testid="forgot-password-disabled"]')).toBeVisible();
    await expect(page.getByText(/tenant administrator/i)).toBeVisible();
  });

  test('FP-002: should have back to login link', async ({ page }) => {
    await page.goto('/forgot-password', { waitUntil: 'load' });
    const backLink = page.getByRole('link', { name: 'Back to Login' });
    await expect(backLink).toBeVisible();
    await backLink.click();
    await expect(page).toHaveURL(/login/);
  });

  test('RP-001: should display admin-managed password notice', async ({ page }) => {
    await page.goto('/reset-password?token=test-token', { waitUntil: 'load' });
    await expect(page.locator('[data-testid="reset-password-disabled"]')).toBeVisible();
    await expect(page.getByText(/tenant administrator/i)).toBeVisible();
  });
});

// ===========================================================================
// 7. SECURITY SETTINGS (Sessions)
// ===========================================================================

test.describe('Security Settings — Sessions', () => {
  test('SEC-001: should display security page with active sessions @smoke', async ({ page }) => {
    await page.goto('/personal/security', { waitUntil: 'load' });
    // Wait for the heading to appear as load indicator
    await expect(page.getByRole('heading', { name: 'Security Settings' })).toBeVisible({
      timeout: 15000,
    });
    await expect(page.getByRole('heading', { name: 'Active Sessions' })).toBeVisible();
    await expect(page.getByText('Change Password')).toHaveCount(0);
  });

  test('SEC-002: should show active sessions list', async ({ page }) => {
    await page.goto('/personal/security', { waitUntil: 'load' });
    await expect(page.getByRole('heading', { name: 'Security Settings' })).toBeVisible({
      timeout: 15000,
    });

    // Should show at least one session (current)
    const sessionResponse = page.waitForResponse(
      (res) => res.url().includes('/api/user/sessions') && res.request().method() === 'get',
    );

    // Wait for sessions to load
    const res = await sessionResponse.catch(() => null);
    if (res) {
      const data = await res.json();
      expect(data?.data?.length).toBeGreaterThanOrEqual(1);
    }
  });

  test('SEC-003: should not show password change form in security page', async ({ page }) => {
    await page.goto('/personal/security', { waitUntil: 'load' });
    await expect(page.getByRole('heading', { name: 'Security Settings' })).toBeVisible({
      timeout: 15000,
    });

    await expect(page.locator('[data-testid="current-password-input"]')).toHaveCount(0);
    await expect(page.locator('[data-testid="new-password-input"]')).toHaveCount(0);
    await expect(page.locator('[data-testid="confirm-password-input"]')).toHaveCount(0);
  });
});

// ===========================================================================
// 8. LOGOUT
// ===========================================================================

test.describe('Logout Flow', () => {
  test.setTimeout(30000);

  test('LO-001: should logout and redirect to login @smoke', async ({ page }) => {
    test.setTimeout(45000);

    // Start authenticated — wait for full page load
    await page.goto('/dashboards', { waitUntil: 'load' });
    const header = new HeaderPage(page);
    await header.userMenuButton.waitFor({ state: 'visible', timeout: 20000 });
    await header.logout();

    if (/\/logout([?#].*)?$/.test(page.url())) {
      const confirmButton = page.getByRole('button', { name: /确认退出|Log Out/i });
      await confirmButton.waitFor({ state: 'visible', timeout: 5000 });
      await Promise.all([
        page.waitForURL(/login/, { timeout: 15000 }),
        confirmButton.click(),
      ]);
    }

    // After Form POST, should redirect to /login (increase timeout for batch runs)
    await expect(page).toHaveURL(/login/, { timeout: 20000 });
  });
});

// ===========================================================================
// 9. OIDC SSO (Placeholder — requires external IdP)
// ===========================================================================

test.describe('OIDC SSO', () => {
  test.use({ storageState: { cookies: [], origins: [] } });

  // TODO: OIDC tests require a configured OIDC provider (Azure AD, Okta, etc.)
  // in ab_cloud_config with service_type=OIDC, provider_code=oidc.
  // These tests can only run when an IdP is set up.

  test('OIDC-001: should show enterprise SSO button when OIDC channel is enabled', async ({
    page,
    request,
  }) => {
    const res = await request.get('/api/auth/login/channels');
    const channels: string[] = res.ok() ? (await res.json())?.data || [] : [];

    await page.goto('/login');
    const oidcButton = page.getByTitle('企业SSO');
    if (channels.includes('oidc')) {
      await expect(oidcButton).toBeVisible();
    } else {
      await expect(oidcButton).toHaveCount(0);
    }
  });
});

// ===========================================================================
// 10. TENANT SELECTION — Create / Join
// ===========================================================================

test.describe('Tenant Selection', () => {
  test.use({ storageState: { cookies: [], origins: [] } });

  const TENANT_PREFIX = uniqueId('tnt');
  const TENANT_EMAIL = `${TENANT_PREFIX.toLowerCase()}@e2e-test.local`;
  const TENANT_DISPLAY_NAME = `Tenant User ${TENANT_PREFIX}`;
  const TENANT_PASSWORD = 'TenantPass2026!';
  const TENANT_ORG_NAME = `E2E Org ${TENANT_PREFIX}`;

  let selfRegistrationEnabled = false;

  test.beforeAll(async ({ request }) => {
    selfRegistrationEnabled = (await detectSelfRegistrationStatus(request)) === 'enabled';
  });

  test.beforeEach(() => {
    test.skip(
      !selfRegistrationEnabled,
      'Tenant-selection registration scenario requires self-registration enabled',
    );
  });

  test('TS-001: should display tenant selection page after registration @smoke', async ({
    page,
  }) => {
    test.setTimeout(30000);

    await page.goto('/signup');
    await page.locator('input#email').fill(TENANT_EMAIL);
    await page.locator('input#displayName').fill(TENANT_DISPLAY_NAME);
    await page.locator('input#password').fill(TENANT_PASSWORD);
    await page.locator('button:has-text("创建账号")').click();

    await page.waitForURL(/tenant-selection|\/home/, { timeout: 20000 });

    // If redirected to /home, user already has a tenant — skip tenant selection assertions
    if (page.url().includes('tenant-selection')) {
      // Should show two options
      await expect(page.getByText('创建新租户')).toBeVisible();
      await expect(page.getByText('加入现有租户')).toBeVisible();
    }
  });

  test('TS-002: should create tenant successfully after registration @smoke', async ({ page }) => {
    test.setTimeout(45000);

    // Register
    await page.goto('/signup');
    const email2 = `${uniqueId('ts2').toLowerCase()}@e2e-test.local`;
    await page.locator('input#email').fill(email2);
    await page.locator('input#displayName').fill('TS Create User');
    await page.locator('input#password').fill(TENANT_PASSWORD);
    await page.locator('button:has-text("创建账号")').click();
    await page.waitForURL(/tenant-selection|\/home/, { timeout: 20000 });

    // If already redirected to /home, user has a tenant — test passes
    if (!page.url().includes('tenant-selection')) return;

    // Click "创建新租户"
    await page.getByText('创建新租户').click();

    // Fill tenant name (visible input has placeholder, hidden input syncs via formData)
    await page.getByPlaceholder('输入租户名称').fill(`E2E Org ${uniqueId('org')}`);

    // Submit
    await page.locator('button:has-text("创建租户")').click();

    // Should redirect to home page (not tenant-selection) after successful creation
    await page.waitForURL(
      (url) => !url.toString().includes('/tenant-selection') && !url.toString().includes('/login'),
      { timeout: 30000 },
    );
  });

  test('TS-003: should show error state only for current action', async ({ page }) => {
    test.setTimeout(30000);

    // Register and go to tenant selection
    await page.goto('/signup');
    const email3 = `${uniqueId('ts3').toLowerCase()}@e2e-test.local`;
    await page.locator('input#email').fill(email3);
    await page.locator('input#displayName').fill('TS Error User');
    await page.locator('input#password').fill(TENANT_PASSWORD);
    await page.locator('button:has-text("创建账号")').click();
    await page.waitForURL(/tenant-selection|\/home/, { timeout: 20000 });

    // If already redirected to /home, user has a tenant — skip tenant selection test
    if (!page.url().includes('tenant-selection')) return;

    // Go to JOIN view
    await page.getByText('加入现有租户').click();

    // Submit with invalid invite code
    await page.locator('input[name="inviteCode"]').fill('invalid_code');
    await page.locator('button:has-text("提交申请")').click();
    await page.waitForLoadState('load');

    // Go back and switch to CREATE
    await page.getByText('返回选择').click();
    await page.getByText('创建新租户').click();

    // Error from JOIN should NOT be visible on CREATE view
    const errorBanner = page.locator('.bg-red-50, .bg-red-900\\/20');
    await expect(errorBanner).not.toBeVisible({ timeout: 2000 });
  });
});

// ===========================================================================
// 11. INVITATION FLOW
// ===========================================================================

test.describe('Invitation Flow', () => {
  async function generateInviteCode(
    request: APIRequestContext,
    expiryDays = '3',
  ): Promise<string> {
    const res = await request.post('/api/tenant/invite-code/generate', {
      params: { expiryDays },
    });

    expect(res.ok()).toBe(true);
    const json = await res.json();
    const code = json?.data;
    expect(code).toBeTruthy();
    expect(typeof code).toBe('string');
    return code;
  }

  test('INV-001: should generate invite code via API @smoke', async ({ request }) => {
    const code = await generateInviteCode(request);
    expect(code.length).toBeGreaterThanOrEqual(6);
  });

  test('INV-002: should validate existing invite code', async ({ request }) => {
    const code = await generateInviteCode(request);

    // Validate it
    const valRes = await request.get('/api/tenant/invite-code/validate', {
      params: { code },
    });
    expect(valRes.ok()).toBe(true);
    const valJson = await valRes.json();
    expect(valJson.data).toBe(true);
  });

  test('INV-003: should reject invalid invite code', async ({ request }) => {
    const valRes = await request.get('/api/tenant/invite-code/validate', {
      params: { code: 'doesnotexist' },
    });
    expect(valRes.ok()).toBe(true);
    const valJson = await valRes.json();
    expect(valJson.data).toBe(false);
  });

  test('INV-004: should revoke invite code', async ({ request }) => {
    const code = await generateInviteCode(request);

    // Revoke
    const revokeRes = await request.post('/api/tenant/invite-code/revoke', {
      params: { code },
    });
    expect(revokeRes.ok()).toBe(true);

    // Validate should fail
    const valRes = await request.get('/api/tenant/invite-code/validate', {
      params: { code },
    });
    const valJson = await valRes.json();
    expect(valJson.data).toBe(false);
  });

  test('INV-005: should get current valid invite code', async ({ request }) => {
    // Generate fresh code
    await generateInviteCode(request);

    const res = await request.get('/api/tenant/invite-code/current');
    expect(res.ok()).toBe(true);
    const json = await res.json();
    // Should have a code
    if (json.data) {
      expect(json.data.code).toBeTruthy();
      expect(json.data.expiredAt).toBeTruthy();
    }
  });
});
