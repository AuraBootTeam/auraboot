import { expect, test } from '@playwright/test';
import { acquireSavedViewLock, releaseSavedViewLock } from '../saved-view/_saved-view-lock';

test.describe('Community branding baseline', () => {
  test('keeps AuraBoot attribution on the public authentication shell', async ({ page }) => {
    await page.context().clearCookies();
    await page.goto('/login');

    await expect(page.getByTestId('auth-site-title')).toHaveText('AuraBoot');
    await expect(page.getByRole('img', { name: 'AuraBoot' }).first()).toBeVisible();
    await expect(page.getByText(/登录以继续使用 AuraBoot/)).toBeVisible();

    await expect(page.getByRole('button', { name: 'Switch language' })).toBeVisible();
    await expect(page).toHaveTitle('AuraBoot');

    await expect(page.locator('link[rel="manifest"]')).toHaveAttribute('href', '/manifest.json');
    await expect(page.locator('link[rel="icon"][href="/favicon.ico"]')).toHaveCount(1);
    const pwaIdentity = await page.evaluate(async () => {
      const manifestResponse = await fetch('/manifest.json');
      const manifest = await manifestResponse.json();
      const iconStatuses = await Promise.all(
        manifest.icons.map(async (icon: { src: string }) => {
          const iconUrl = new URL(icon.src, new URL('/manifest.json', window.location.origin));
          const response = await fetch(iconUrl);
          return response.status;
        }),
      );
      return {
        manifestStatus: manifestResponse.status,
        name: manifest.name,
        shortName: manifest.short_name,
        iconStatuses,
      };
    });
    expect(pwaIdentity).toEqual({
      manifestStatus: 200,
      name: 'AuraBoot',
      shortName: 'AuraBoot',
      iconStatuses: [200, 200, 200, 200],
    });
  });

  test('@smoke @viewer opens About from the authenticated account menu', async ({
    page,
  }, testInfo) => {
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

  test('keeps fixed attribution on a real anonymous shared view', async ({
    browser,
    page,
  }, testInfo) => {
    await acquireSavedViewLock('community-branding-share');
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
          name: `Community Brand ${Date.now()}`,
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
        await expect(anonymousPage.getByText(/^Powered by AuraBoot · \d+ records$/)).toBeVisible();
        await expect(anonymousPage).toHaveTitle('AuraBoot');
        await anonymousPage.screenshot({
          path: testInfo.outputPath('shared-view-public.png'),
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
      releaseSavedViewLock('community-branding-share');
    }
  });

  test('keeps the About identity readable on a mobile viewport', async ({ page }, testInfo) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto('/');
    await expect(page.locator('header[data-hydrated]')).toHaveAttribute('data-hydrated', 'true');

    // The account entry is docked at the sidebar footer; on mobile the
    // navigation sidebar is an off-canvas drawer, so open it first.
    await page.getByTestId('header-sidebar-toggle').click();
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
