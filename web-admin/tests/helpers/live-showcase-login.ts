import { expect, type Page } from '@playwright/test';
import { DEFAULT_TEST_ACCOUNT } from './test-accounts';

/** Real UI login into the exact declared business space, including a fresh browser. */
export async function liveShowcaseLogin(page: Page) {
  const tenant = process.env.AURA_ADMIN_TENANT_ID;
  expect(tenant, 'Bind the exact owned business tenant').toBeTruthy();
  await page.goto('/login');
  await expect(page.getByTestId('login-page-root')).toHaveAttribute('data-hydrated', 'true');
  await page.locator('#identifier').fill(DEFAULT_TEST_ACCOUNT.email);
  await page.locator('#password').fill(DEFAULT_TEST_ACCOUNT.password);
  await page.getByRole('button', { name: '立即登录', exact: true }).click();
  await page.waitForURL((url) => !url.pathname.includes('/login'));
  if (new URL(page.url()).pathname === '/tenant-selection')
    await page.getByTestId(`space-business-${tenant}`).click();
  await expect(page.getByTestId('sidebar')).toBeVisible();
  const identity = await (await page.request.get('/api/auth/me')).json();
  if (String(identity.data.user.tenantId) !== tenant) {
    await page.getByTestId('user-menu').getByRole('button').first().click();
    await page.getByTestId(`tenant-switch-${tenant}`).click();
    await page.waitForLoadState('load');
  }
  const selected = await (await page.request.get('/api/auth/me')).json();
  expect(String(selected.code)).toBe('0');
  expect(String(selected.data.user.tenantId)).toBe(tenant);
  expect(selected.data.user.email).toBe(DEFAULT_TEST_ACCOUNT.email);
  return selected.data;
}
