import { expect, test } from '@playwright/test';

test.describe('Community branding baseline', () => {
  test('keeps AuraBoot attribution on the public authentication shell', async ({ page }) => {
    await page.context().clearCookies();
    await page.goto('/login');

    await expect(page.getByTestId('auth-site-title')).toHaveText('AuraBoot');
    await expect(page.getByRole('img', { name: 'AuraBoot' }).first()).toBeVisible();
    await expect(page.getByText(/登录以继续使用 AuraBoot/)).toBeVisible();

    await page.getByRole('button', { name: 'Switch language' }).click();
    await expect(page.getByRole('button', { name: 'English' })).toBeVisible();
    await expect(page).toHaveTitle('AuraBoot');
  });

  test('@smoke opens About from the authenticated account menu', async ({ page }, testInfo) => {
    await page.goto('/');
    await expect(page.locator('header[data-hydrated]')).toHaveAttribute('data-hydrated', 'true');

    await page.getByTestId('user-menu').getByRole('button', { name: 'User avatar' }).click();
    await page.getByTestId('about-link').click();

    await expect(page).toHaveURL(/\/about$/);
    await expect(page.getByTestId('about-page')).toBeVisible();
    await expect(page.getByRole('heading', { name: /AuraBoot/ })).toBeVisible();
    await expect(page.getByRole('link', { name: /AuraBoot License Agreement/ })).toBeVisible();
    await expect(page).toHaveTitle('About AuraBoot');
    await page.screenshot({ path: testInfo.outputPath('about-desktop.png'), fullPage: true });
  });

  test('keeps the About identity readable on a mobile viewport', async ({ page }, testInfo) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto('/');
    await expect(page.locator('header[data-hydrated]')).toHaveAttribute('data-hydrated', 'true');

    await page.getByTestId('user-menu').getByRole('button', { name: 'User avatar' }).click();
    await page.getByTestId('about-link').click();

    await expect(page).toHaveURL(/\/about$/);
    await expect(page.getByTestId('about-page')).toBeVisible();
    await expect(page.getByTestId('about-edition')).toBeVisible();
    await expect(page.getByRole('heading', { name: /AuraBoot/ })).toBeVisible();
    await page.screenshot({ path: testInfo.outputPath('about-mobile.png'), fullPage: true });

    const legalHeading = page.getByRole('heading', { name: /法律与品牌|Legal and branding/ });
    await legalHeading.scrollIntoViewIfNeeded();
    await expect(legalHeading).toBeVisible();
    await expect(
      page.getByRole('link', { name: /商业授权与支持|Commercial licensing and support/ }),
    ).toBeVisible();
    await page.screenshot({ path: testInfo.outputPath('about-mobile-bottom.png') });
  });
});
