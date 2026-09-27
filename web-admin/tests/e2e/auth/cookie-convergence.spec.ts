import { expect, test, type Page } from '@playwright/test';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';

const LEGACY_AUTH_KEYS = ['jwt', 'jwtToken', 'refreshToken', 'tokenExpiry', 'remember'];

async function browserAuthStorage(page: Page) {
  return page.evaluate((keys) => ({
    local: Object.fromEntries(keys.map((key) => [key, localStorage.getItem(key)])),
    session: Object.fromEntries(keys.map((key) => [key, sessionStorage.getItem(key)])),
  }), LEGACY_AUTH_KEYS);
}

async function expectBrowserAuthStorageEmpty(page: Page) {
  const storage = await browserAuthStorage(page);
  expect(Object.values(storage.local).every((value) => value === null)).toBe(true);
  expect(Object.values(storage.session).every((value) => value === null)).toBe(true);
}

async function loginThroughForm(page: Page) {
  await page.goto('/login');
  await expect(page.getByTestId('login-page-root')).toHaveAttribute('data-hydrated', 'true');
  await page.locator('input#identifier, input#email').first().fill(DEFAULT_TEST_ACCOUNT.email);
  await page.locator('input#password').fill(DEFAULT_TEST_ACCOUNT.password);
  await page.locator('form').first().evaluate((form: HTMLFormElement) => form.requestSubmit());
  await page.waitForURL((url) => !url.pathname.includes('/login'));
}

test.describe('httpOnly cookie convergence', () => {
  test.use({ storageState: { cookies: [], origins: [] } });

  test('login, renewal and logout keep JWT material out of browser storage', async ({
    page,
    context,
  }) => {
    test.setTimeout(60_000);
    await loginThroughForm(page);

    const sessionCookie = (await context.cookies()).find((cookie) => cookie.name === '__session');
    expect(sessionCookie).toEqual(expect.objectContaining({ httpOnly: true, sameSite: 'Lax' }));
    await expectBrowserAuthStorageEmpty(page);

    await page.reload();
    await expect(page).not.toHaveURL(/\/login/);
    expect((await context.cookies()).some((cookie) => cookie.name === '__session')).toBe(true);
    await expectBrowserAuthStorageEmpty(page);

    const me = await page.evaluate(async () => {
      const response = await fetch('/api/auth/me', { credentials: 'same-origin' });
      return { status: response.status, body: await response.json() };
    });
    expect(me.status).toBe(200);
    expect(me.body?.data?.jwt).toBeUndefined();

    const renewal = await page.evaluate(async () => {
      const response = await fetch('/api/auth/session-renew', {
        method: 'POST',
        credentials: 'same-origin',
      });
      return { status: response.status, cacheControl: response.headers.get('cache-control') };
    });
    expect(renewal).toEqual({ status: 200, cacheControl: 'no-store' });
    await expectBrowserAuthStorageEmpty(page);

    const rejectedCsrf = await page.request.post('/api/auth/session-renew', {
      headers: { Origin: 'https://untrusted.invalid' },
    });
    expect(rejectedCsrf.status()).toBe(403);

    await page.goto('/logout');
    await page.locator('button:has-text("确认退出"), button:has-text("Log Out")').first().click();
    await page.waitForURL(/\/login/);
    expect((await context.cookies()).some((cookie) => cookie.name === '__session')).toBe(false);
    await expectBrowserAuthStorageEmpty(page);
  });

  test('a legacy localStorage JWT is exchanged once, then deleted', async ({ page, context }) => {
    test.setTimeout(45_000);
    const backendUrl = process.env.BACKEND_URL;
    expect(backendUrl, 'BACKEND_URL must point at the real backend').toBeTruthy();
    const login = await page.request.post(`${backendUrl}/api/auth/login`, {
      data: {
        email: DEFAULT_TEST_ACCOUNT.email,
        password: DEFAULT_TEST_ACCOUNT.password,
      },
    });
    expect(login.ok()).toBe(true);
    const legacyJwt = (await login.json())?.data?.jwt;
    expect(typeof legacyJwt).toBe('string');

    await page.addInitScript((token) => {
      if (!localStorage.getItem('jwt-cookie-migration-e2e-seeded')) {
        localStorage.setItem('jwt-cookie-migration-e2e-seeded', 'true');
        localStorage.setItem('jwtToken', token);
        localStorage.setItem('refreshToken', 'obsolete-refresh-token');
      }
    }, legacyJwt);

    const migration = page.waitForResponse(
      (response) =>
        response.url().endsWith('/api/auth/session-migrate') &&
        response.request().method() === 'POST',
    );
    await page.goto('/login');
    expect((await migration).status()).toBe(204);
    await page.waitForURL((url) => !url.pathname.includes('/login'));

    const sessionCookie = (await context.cookies()).find((cookie) => cookie.name === '__session');
    expect(sessionCookie?.httpOnly).toBe(true);
    await expectBrowserAuthStorageEmpty(page);
  });
});
