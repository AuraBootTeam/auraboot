import { expect, test } from '@playwright/test';
import { acquireSavedViewLock, releaseSavedViewLock } from '../saved-view/_saved-view-lock';

test.describe('Standard deployment branding', () => {
  test('applies the customer identity before authentication, including PWA assets', async ({
    page,
  }, testInfo) => {
    await page.context().clearCookies();
    await page.goto('/login');

    await expect(page.getByTestId('auth-site-title')).toHaveText('Northstar');
    const logo = page.getByRole('img', { name: 'Northstar' }).first();
    await expect(logo).toHaveAttribute('src', '/customer-brand/logo.png');
    await expect(logo).toBeVisible();
    await expect(page.getByText(/登录以继续使用 Northstar/)).toBeVisible();
    await expect(page).toHaveTitle('Northstar');

    await expect(page.locator('link[rel="manifest"]')).toHaveAttribute(
      'href',
      '/customer-brand/manifest.webmanifest',
    );
    await expect(page.locator('link[rel="icon"][href="/customer-brand/favicon.ico"]')).toHaveCount(
      1,
    );
    const browserIdentity = await page.evaluate(async () => {
      const manifestResponse = await fetch('/customer-brand/manifest.webmanifest');
      const manifest = await manifestResponse.json();
      const assetUrls = [
        '/customer-brand/logo.png',
        '/customer-brand/favicon.ico',
        '/customer-brand/favicon-32x32.png',
        '/customer-brand/apple-touch-icon.png',
        ...manifest.icons.map((icon: { src: string }) => icon.src),
      ];
      return {
        manifestStatus: manifestResponse.status,
        name: manifest.name,
        shortName: manifest.short_name,
        assetStatuses: await Promise.all(
          assetUrls.map(async (url: string) => (await fetch(url)).status),
        ),
      };
    });
    expect(browserIdentity).toEqual({
      manifestStatus: 200,
      name: 'Northstar Operations Platform',
      shortName: 'Northstar',
      assetStatuses: [200, 200, 200, 200, 200, 200],
    });
    await page.screenshot({ path: testInfo.outputPath('standard-login.png'), fullPage: true });
  });

  test('@smoke shows customer Header and About identity while retaining legal notices', async ({
    page,
  }, testInfo) => {
    await page.goto('/');
    await expect(page.locator('header[data-hydrated]')).toHaveAttribute('data-hydrated', 'true');
    await expect(page.getByRole('img', { name: 'Northstar' }).first()).toBeVisible();
    await expect(page.getByText('Northstar', { exact: true }).first()).toBeVisible();

    await page.getByTestId('user-menu').getByRole('button', { name: 'User avatar' }).click();
    await page.getByTestId('about-link').click();

    await expect(page).toHaveURL(/\/about$/);
    await expect(page.getByTestId('about-page')).toHaveAttribute(
      'data-branding-mode',
      'commercial',
    );
    await expect(page.getByRole('heading', { name: /Northstar/ })).toBeVisible();
    await expect(page.getByTestId('about-edition')).toHaveText('Standard');
    await expect(page.getByTestId('about-owner-copyright')).toContainText(
      'Northstar Holdings Ltd.',
    );
    await expect(page.getByTestId('about-auraboot-notice')).toContainText(
      'AuraBoot License Agreement 1.3',
    );
    await expect(page).toHaveTitle('About Northstar');
    await page.screenshot({ path: testInfo.outputPath('standard-about.png'), fullPage: true });
  });

  test('uses the customer attribution on a real anonymous shared view', async ({
    browser,
    page,
  }, testInfo) => {
    await acquireSavedViewLock('standard-deployment-branding-share');
    let viewPid = '';
    try {
      const pagesResponse = await page.request.get('/api/pages/published');
      const pagesBody = await pagesResponse.json();
      expect(pagesResponse.ok(), JSON.stringify(pagesBody)).toBeTruthy();
      const shareablePage = pagesBody.data?.find(
        (candidate: { kind?: string; modelCode?: string; pageKey?: string }) =>
          candidate.kind === 'list' && candidate.modelCode && candidate.pageKey,
      );
      expect(shareablePage, 'A published list page is required for the share fixture').toBeTruthy();

      const createResponse = await page.request.post('/api/views', {
        data: {
          name: `Standard Brand ${Date.now()}`,
          modelCode: shareablePage.modelCode,
          pageKey: shareablePage.pageKey,
          viewType: 'table',
          scope: 'personal',
          viewConfig: {},
        },
      });
      const createBody = await createResponse.json();
      expect(createResponse.ok(), JSON.stringify(createBody)).toBeTruthy();
      viewPid = createBody.data?.pid ?? '';
      expect(viewPid).toBeTruthy();

      const shareResponse = await page.request.post(`/api/views/${viewPid}/share`, { data: {} });
      const shareBody = await shareResponse.json();
      expect(shareResponse.ok(), JSON.stringify(shareBody)).toBeTruthy();
      const token = shareBody.data?.token;
      expect(token).toBeTruthy();

      const anonymousContext = await browser.newContext({
        baseURL: String(testInfo.project.use.baseURL),
      });
      try {
        const anonymousPage = await anonymousContext.newPage();
        await anonymousPage.goto(`/share/${token}`);
        await expect(anonymousPage.getByText(/^Powered by Northstar · \d+ records$/)).toBeVisible();
        await expect(anonymousPage).toHaveTitle('Northstar');
        await anonymousPage.screenshot({
          path: testInfo.outputPath('standard-shared-view.png'),
          fullPage: true,
        });
      } finally {
        await anonymousContext.close();
      }
    } finally {
      if (viewPid) {
        const deleteResponse = await page.request.delete(`/api/views/${viewPid}`);
        expect(deleteResponse.ok()).toBeTruthy();
      }
      releaseSavedViewLock('standard-deployment-branding-share');
    }
  });
});
