/**
 * Browser contract for the OAuth callback route.
 *
 * The external provider and callback API are controlled at the browser seam;
 * the JWT is minted by the real backend so the final BFF session and protected
 * application redirect remain real. Backend PG/Redis/provider orchestration is
 * covered separately by FederatedOAuthHttpIntegrationTest.
 */

import { test, expect } from '@playwright/test';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';

const PROVIDER = 'company-oidc';
const STATE_KEY = `auth.oauth.login.state.${PROVIDER}`;

test.describe('OAuth callback', () => {
  test.use({ storageState: { cookies: [], origins: [] } });

  test('OAUTH-CB-001: matching state creates the deployment-appropriate BFF session', async ({
    page,
  }) => {
    const passwordLogin = await page.request.post('/api/auth/login', {
      data: {
        email: DEFAULT_TEST_ACCOUNT.email,
        password: DEFAULT_TEST_ACCOUNT.password,
      },
    });
    expect(passwordLogin.ok(), 'Controlled callback needs a backend-valid JWT').toBe(true);
    const passwordLoginData = (await passwordLogin.json())?.data as {
      jwt: string;
      tenantId: string | null;
    };
    const jwt = passwordLoginData.jwt;
    expect(jwt?.length).toBeGreaterThan(50);
    const expectedRedirect = passwordLoginData.tenantId ? '/' : '/tenant-selection';

    let callbackRequests = 0;
    await page.route(`**/api/auth/login/social/${PROVIDER}/callback`, async (route) => {
      callbackRequests += 1;
      expect(route.request().method()).toBe('POST');
      expect(route.request().postDataJSON()).toEqual({
        code: 'controlled-code',
        state: 'server-state',
      });
      await route.fulfill({
        json: {
          code: '0',
          data: {
            jwt,
            userPid: 'controlled-user',
            username: 'Controlled User',
            tenantId: passwordLoginData.tenantId,
            tenantStatus: 'member',
            mustChangePassword: false,
            mergeRequired: false,
            mergeToken: null,
            mergeProvider: null,
          },
        },
      });
    });

    await page.goto('/login', { waitUntil: 'domcontentloaded' });
    await page.evaluate(({ key, state }) => window.sessionStorage.setItem(key, state), {
      key: STATE_KEY,
      state: 'server-state',
    });

    const sessionPost = page.waitForRequest(
      (request) => request.url().endsWith('/login') && request.method() === 'POST',
    );
    await page.goto(`/login/social/${PROVIDER}/callback?code=controlled-code&state=server-state`, {
      waitUntil: 'domcontentloaded',
    });
    const loginRequest = await sessionPost;
    const form = new URLSearchParams(loginRequest.postData() ?? '');
    expect(form.get('intent')).toBe('social-callback');
    expect(form.get('token')).toBe(jwt);
    expect(form.get('redirectTo')).toBe(expectedRedirect);

    if (expectedRedirect === '/tenant-selection') {
      await page.waitForURL(/tenant-selection/, { timeout: 20_000 });
    } else {
      await page.waitForURL(
        (url) => !url.pathname.includes('/login') && !url.pathname.includes('tenant-selection'),
        { timeout: 20_000 },
      );
    }
    expect(callbackRequests).toBe(1);
    expect(await page.evaluate((key) => window.sessionStorage.getItem(key), STATE_KEY)).toBeNull();
  });

  test('OAUTH-CB-002: mismatched state is consumed before any callback API call', async ({
    page,
  }) => {
    let callbackRequests = 0;
    await page.route(`**/api/auth/login/social/${PROVIDER}/callback`, async (route) => {
      callbackRequests += 1;
      await route.abort();
    });

    await page.goto('/login', { waitUntil: 'domcontentloaded' });
    await page.evaluate(({ key, state }) => window.sessionStorage.setItem(key, state), {
      key: STATE_KEY,
      state: 'expected-state',
    });
    await page.goto(`/login/social/${PROVIDER}/callback?code=attacker-code&state=attacker-state`, {
      waitUntil: 'domcontentloaded',
    });

    await expect(page.getByRole('heading', { name: 'Authentication Failed' })).toBeVisible();
    await expect(page.getByText('OAuth state validation failed')).toBeVisible();
    expect(callbackRequests).toBe(0);
    expect(await page.evaluate((key) => window.sessionStorage.getItem(key), STATE_KEY)).toBeNull();
  });

  test('OAUTH-CB-003: verified-email merge requires the existing account password', async ({
    page,
  }) => {
    await page.route(`**/api/auth/login/social/${PROVIDER}/callback`, async (route) => {
      await route.fulfill({
        json: {
          code: '0',
          data: {
            jwt: null,
            userPid: null,
            username: null,
            tenantId: null,
            tenantStatus: 'none',
            mustChangePassword: false,
            mergeRequired: true,
            mergeToken: 'merge-token',
            mergeProvider: PROVIDER,
          },
        },
      });
    });

    let mergeRequests = 0;
    await page.route('**/api/auth/login/social/confirm-merge', async (route) => {
      mergeRequests += 1;
      await route.abort();
    });

    await page.goto('/login', { waitUntil: 'domcontentloaded' });
    await page.evaluate(({ key, state }) => window.sessionStorage.setItem(key, state), {
      key: STATE_KEY,
      state: 'merge-state',
    });
    await page.goto(`/login/social/${PROVIDER}/callback?code=merge-code&state=merge-state`, {
      waitUntil: 'domcontentloaded',
    });

    await expect(page.getByTestId('social-merge-dialog')).toBeVisible();
    await expect(page.getByTestId('merge-confirm-btn')).toBeDisabled();
    expect(mergeRequests).toBe(0);
  });
});
