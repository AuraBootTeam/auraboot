import { test, expect } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';

test('open registration retains the published appearance and completes real account admission', async ({ browser }, testInfo) => {
  expect(process.env.PLAYWRIGHT_BASE_URL, 'Owned runtime must be explicit').toBeTruthy();
  const context = await browser.newContext({ baseURL: process.env.PLAYWRIGHT_BASE_URL, storageState: { cookies: [], origins: [] }, viewport: { width: 390, height: 844 } });
  try {
    const page = await context.newPage();
    const policy = await (await page.request.get('/api/auth/access-policy')).json();
    expect(policy.data.userRegistrationPolicy, 'Fixture must explicitly enable registration').toBe('open');
    const appearance = await (await page.request.get('/api/auth/appearance')).json();
    await page.goto('/signup');
    await expect(page).toHaveURL(/\/signup/);
    await expect(page.locator('[data-auth-template]')).toHaveAttribute('data-auth-template', appearance.data.appearance.template);
    await expect(page.locator('#email')).toBeVisible();
    await expect(page.locator('#displayName')).toBeVisible();
    await expect(page.locator('#password')).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: testInfo.outputPath('signup-open-mobile.png'), fullPage: true });
    const email = `appearance-signup-${randomUUID().slice(0, 8)}@e2e.local`;
    await page.locator('#email').fill(email);
    await page.locator('#displayName').fill('Appearance registration');
    await page.locator('#password').fill(DEFAULT_TEST_ACCOUNT.password);
    await page.locator('button[type="submit"]').click();
    await expect(page).not.toHaveURL(/\/(signup|login)/);
    const identity = await (await page.request.get('/api/auth/me')).json();
    expect(String(identity.code)).toBe('0');
    expect(identity.data.user.email).toBe(email);
    await testInfo.attach('registration-admission', { body: JSON.stringify({ policy: policy.data.userRegistrationPolicy, template: appearance.data.appearance.template, email, admitted: true }), contentType: 'application/json' });
  } finally { await context.close(); }
});
