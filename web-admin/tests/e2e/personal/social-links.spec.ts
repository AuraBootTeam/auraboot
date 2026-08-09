/**
 * Social Account Binding E2E Tests
 *
 * Tests SL-001 ~ SL-005: Social links page navigation, descriptor-driven
 * provider rendering, bind initiation, and info box.
 *
 * Token exchange still needs provider credentials. The deterministic bind-start
 * case uses a controlled provider redirect while the real-stack acceptance run
 * covers the production API/browser integration.
 *
 * Route: /personal/social-links
 * API: GET /api/user/social-links
 *
 * @since 4.0.0
 */

import { test, expect } from '../../fixtures';

const PAGE_URL = '/personal/social-links';

test.describe('Social Account Binding', () => {
  /**
   * SL-001: Page load and basic structure
   * Verify page title, the exact configured/linked provider union, and info box.
   */
  test('SL-001: should display the configured and linked provider union @smoke', async ({ page }) => {
    const linksResponse = page.waitForResponse(
      (resp) =>
        resp.url().includes('/api/user/social-links') &&
        resp.request().method().toLowerCase() === 'get',
      { timeout: 15000 },
    );
    const optionsResponse = page.waitForResponse(
      (resp) =>
        resp.url().includes('/api/auth/login/channel-options') &&
        resp.request().method().toLowerCase() === 'get',
      { timeout: 15000 },
    );
    await page.goto(PAGE_URL, { waitUntil: 'domcontentloaded' });
    const [linksBody, optionsBody] = await Promise.all([
      linksResponse.then((response) => response.json()),
      optionsResponse.then((response) => response.json()),
    ]);

    // Page title (bilingual)
    await expect(
      page.locator('h1').filter({ hasText: /Social Account Binding|社交账号绑定/i }),
    ).toBeVisible({ timeout: 10000 });

    const configured = Array.isArray(optionsBody?.data)
      ? optionsBody.data
          .filter((option: { kind?: string }) => option.kind === 'oauth')
          .map((option: { code: string }) => option.code)
      : [];
    const linked = Array.isArray(linksBody?.data)
      ? linksBody.data.map((socialLink: { provider: string }) => socialLink.provider)
      : [];
    const expectedProviders = [...new Set([...configured, ...linked])].sort();
    const actualProviders = await page
      .locator('[data-testid^="social-link-"]')
      .evaluateAll((rows) =>
        rows
          .map((row) => row.getAttribute('data-testid')?.replace('social-link-', ''))
          .filter((value): value is string => Boolean(value))
          .sort(),
      );
    expect(actualProviders).toEqual(expectedProviders);

    // Info box (bilingual)
    await expect(page.getByText(/About Social Login|关于社交登录/i)).toBeVisible();
  });

  /**
   * SL-002: An arbitrary runtime OIDC provider renders without a hard-coded entry.
   */
  test('SL-002: should render an arbitrary configured OIDC provider', async ({ page }) => {
    await page.route('**/api/user/social-links', async (route) => {
      await route.fulfill({ json: { code: '0', data: [] } });
    });
    await page.route('**/api/auth/login/channel-options', async (route) => {
      await route.fulfill({
        json: {
          code: '0',
          data: [
            {
              code: 'company-oidc',
              kind: 'oauth',
              displayName: 'Company OIDC',
              providerType: 'oidc',
            },
          ],
        },
      });
    });
    await page.goto(PAGE_URL, { waitUntil: 'domcontentloaded' });

    const row = page.locator('[data-testid="social-link-company-oidc"]');
    await expect(row).toBeVisible();
    await expect(row).toContainText('Company OIDC');
    await expect(row.locator('[data-testid="social-bind-company-oidc"]')).toBeVisible();
    await expect(page.locator('[data-testid="social-link-google"]')).toHaveCount(0);
  });

  /**
   * SL-003: Back button navigates to profile
   */
  test('SL-003: should navigate back to profile', async ({ page }) => {
    await page.goto(PAGE_URL, { waitUntil: 'domcontentloaded' });

    const backBtn = page.locator('[data-testid="social-links-back-btn"]');
    await expect(backBtn).toBeVisible({ timeout: 10000 });

    await backBtn.click();
    await expect(page).toHaveURL(/\/personal\/profile/, { timeout: 10000 });
  });

  /**
   * SL-004: Profile page has link to social binding
   */
  test('SL-004: profile page links to social binding', async ({ page }) => {
    await page.goto('/personal/profile', { waitUntil: 'domcontentloaded' });

    const link = page.locator('[data-testid="profile-social-links-link"]');
    await expect(link).toBeVisible({ timeout: 10000 });

    await link.click();
    await expect(page).toHaveURL(/\/personal\/social-links/, { timeout: 10000 });
  });

  test('SL-005: bind start stores server state and follows authorize URL', async ({ page }) => {
    await page.route('**/api/user/social-links', async (route) => {
      await route.fulfill({ json: { code: '0', data: [] } });
    });
    await page.route('**/api/auth/login/channel-options', async (route) => {
      await route.fulfill({
        json: {
          code: '0',
          data: [
            {
              code: 'company-oidc',
              kind: 'oauth',
              displayName: 'Company OIDC',
              providerType: 'oidc',
            },
          ],
        },
      });
    });
    await page.route('**/api/user/social-links/company-oidc/link', async (route) => {
      expect(route.request().method()).toBe('POST');
      await route.fulfill({
        json: {
          code: '0',
          data: { authorizeUrl: '/oauth-provider-fixture', state: 'server-state' },
        },
      });
    });
    await page.route('**/oauth-provider-fixture', async (route) => {
      await route.fulfill({ contentType: 'text/html', body: '<h1>Provider fixture</h1>' });
    });

    await page.goto(PAGE_URL, { waitUntil: 'domcontentloaded' });
    await page.locator('[data-testid="social-bind-company-oidc"]').click();
    await expect(page).toHaveURL(/\/oauth-provider-fixture$/, { timeout: 10000 });
    await expect(page.getByRole('heading', { name: 'Provider fixture' })).toBeVisible();
    expect(
      await page.evaluate(() => ({
        provider: window.sessionStorage.getItem('auth.oauth.link.provider'),
        state: window.sessionStorage.getItem('auth.oauth.link.state.company-oidc'),
      })),
    ).toEqual({ provider: 'company-oidc', state: 'server-state' });
  });
});
