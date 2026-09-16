import { test, expect } from '@playwright/test';
import { HeaderPage } from '../../pages';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';

// Browser-driven login/logout plus real-backend renewal. Time travel belongs to
// JwtSessionLifetimeTest; this test never changes production clocks or signs JWTs.
test.use({ storageState: { cookies: [], origins: [] } });
test('seven-day login, active renewal endpoint and shared-session revocation', async ({ page, context, request }) => {
  test.setTimeout(60000);
  await page.goto('/login');
  await expect(page.getByTestId('login-page-root')).toHaveAttribute('data-hydrated', 'true');
  await page.locator('input#identifier, input#email').first().fill(DEFAULT_TEST_ACCOUNT.email);
  await page.locator('input#password').fill(DEFAULT_TEST_ACCOUNT.password);
  const activityCheck = page.waitForResponse(r => r.url().endsWith('/api/auth/session-renew') && r.request().method() === 'POST');
  await page.locator('form button[type="submit"]').first().click();
  await page.waitForURL(url => !url.pathname.includes('/login'));
  const activityResponse = await activityCheck;
  expect(activityResponse.status()).toBe(200);
  expect(activityResponse.headers()['cache-control']).toBe('no-store');
  const cookie = (await context.cookies()).find(c => c.name === '__session');
  expect(cookie?.httpOnly).toBe(true);
  const session = JSON.parse(Buffer.from(decodeURIComponent(cookie!.value).split('.')[0], 'base64').toString());
  const token = session.jwtToken;
  const claims = JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString());
  expect(claims.exp - claims.iat).toBe(7 * 86400);
  expect(claims.session_exp - claims.auth_time).toBe(180 * 86400);
  expect(Number(session.tokenExpiry)).toBe(claims.exp);
  const backend = process.env.SPRING_BOOT_URL || process.env.BACKEND_URL;
  expect(backend, 'Real backend URL is required').toBeTruthy();
  const renewedResponse = await request.post(`${backend}/api/auth/renew`, { headers: { Authorization: `Bearer ${token}` }, data: {} });
  expect(renewedResponse.ok()).toBe(true);
  const renewedBody = await renewedResponse.json();
  const renewedToken = renewedBody.data.jwt;
  const renewed = JSON.parse(Buffer.from(renewedToken.split('.')[1], 'base64url').toString());
  expect(renewed.sid).toBe(claims.sid);
  expect(renewed.auth_time).toBe(claims.auth_time);
  expect(renewed.session_exp).toBe(claims.session_exp);
  expect(renewed.jti).not.toBe(claims.jti);
  const forbidden = await request.post('/api/auth/session-renew', { headers: { Origin: 'https://untrusted.invalid' } });
  expect(forbidden.status()).toBe(403);
  const header = new HeaderPage(page);
  await expect(header.userAvatar).toBeVisible();
  await header.logout();
  await expect(page).toHaveURL(/\/login/);
  for (const jwt of [token, renewedToken]) {
    const rejected = await request.post(`${backend}/api/auth/renew`, { headers: { Authorization: `Bearer ${jwt}` }, data: {} });
    const body = await rejected.json();
    expect(rejected.status() === 401 || body.code === 401).toBe(true);
  }
  await page.screenshot({ path: test.info().outputPath('logged-out.png') });
});
