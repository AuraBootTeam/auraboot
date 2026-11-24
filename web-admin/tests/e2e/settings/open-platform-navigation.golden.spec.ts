import { expect, test } from '@playwright/test';
import { loginViaUI, ensureBusinessSpace } from '../../helpers/auth-fixtures';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';

test.use({ locale: 'zh-CN', viewport: { width: 1440, height: 1000 }, trace: 'off', video: 'off', screenshot: 'off' });
process.env.PLAYWRIGHT_NO_COPY_PROMPT = '1';

test('keeps supported platform entries and retires the duplicate call-log workspace', async ({ page }) => {
  await page.context().addCookies([{ name: 'locale', value: 'zh-CN', url: process.env.PLAYWRIGHT_BASE_URL || 'http://localhost:5173' }]);
  await page.addInitScript(() => localStorage.setItem('locale', 'zh-CN'));
  await loginViaUI(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
  await ensureBusinessSpace(page);
  await page.goto('/home');
  const nav = page.getByTestId('sidebar');
  await expect(nav.getByRole('link', { name: '接口调用日志', exact: true })).toHaveCount(0);
  await nav.getByRole('link', { name: '应用管理', exact: true }).click();
  await expect(page).toHaveURL(/view=applications/);
  await expect(page.getByRole('navigation', { name: '开放平台导航' }).getByRole('link', { name: '接口调用日志', exact: true })).toHaveCount(0);
  await page.goto('/settings/api-docs?view=calls');
  await expect(page).toHaveURL(/view=applications/);
  await expect(page.getByRole('heading', { name: '接口调用日志', exact: true })).toHaveCount(0);
  await nav.getByTestId('platform-console-link').click();
  await expect(page).toHaveURL(/\/platform\/plugins/);
  await expect(page.getByRole('heading', { name: '内核插件管理', exact: true })).toBeVisible();
  for (const label of ['Overview', 'Tenants', 'Marketplace', 'Licenses', 'Cloud Config', 'Templates', 'Plugins', 'Audit Log', 'Settings'])
    await expect(nav.getByRole('link', { name: label, exact: true })).toHaveCount(0);
  for (const label of ['Commerce', 'Platform', 'System'])
    await expect(nav.getByRole('button', { name: label, exact: true })).toHaveCount(0);
  await expect(nav.getByRole('link', { name: '应用管理', exact: true })).toBeVisible();
  await expect(nav.getByRole('link', { name: 'Webhook 投递', exact: true })).toBeVisible();
});
