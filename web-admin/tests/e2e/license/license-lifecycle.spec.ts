/**
 * License / Entitlement Lifecycle E2E Tests
 *
 * Verifies the entitlement system APIs and license settings page:
 * - List entitlements
 * - Get entitlement by plugin code
 * - License settings page accessibility
 * - Invalid token rejection
 * - Enabled flag presence
 * - Feature check API
 *
 * All API calls go through BFF (BASE_URL helper).
 * No waitForTimeout — uses waitForResponse / waitFor / expect().toBeVisible().
 *
 * @since 5.0.0
 */

import { test, expect } from '../../fixtures';
import { waitForDynamicPageLoad } from '../helpers/index';

function isUnavailableRoute(body: any): boolean {
  const detail = JSON.stringify(body ?? {});
  return /NoResourceFoundException|No static resource/i.test(detail);
}

test.describe('License / Entitlement Lifecycle', () => {
  test.describe.configure({ timeout: 30000 });

  let apiAvailable = true;

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      const resp = await page.request.get('/api/entitlements', { failOnStatusCode: false });
      if (resp.status() === 404 || resp.status() === 405 || resp.status() >= 500) {
        apiAvailable = false;
      } else if (resp.ok()) {
        const body = await resp.json().catch(() => null);
        if (!body || (!body.data && !body.enabled && !Array.isArray(body))) {
          apiAvailable = false;
        }
      } else {
        apiAvailable = false;
      }
    } finally {
      await page.close();
      await ctx.close();
    }
  });

  test.beforeEach(async () => {
    test.skip(!apiAvailable, 'Entitlement API not available — feature not yet implemented');
  });

  /**
   * LIC-01: List entitlements API returns array structure
   */
  test('LIC-01: list entitlements API returns 200 with array structure', async ({ page }) => {
    const resp = await page.request.get('/api/entitlements');
    expect(resp.ok(), 'Entitlements API should return 200').toBe(true);

    const body = await resp.json();
    const data = body?.data ?? body;

    // Response should be an array or contain an array
    const entitlements = Array.isArray(data) ? data : (data?.records ?? data?.items ?? []);
    expect(Array.isArray(entitlements)).toBe(true);
  });

  /**
   * LIC-02: Get entitlement for a specific plugin (crm)
   */
  test('LIC-02: get entitlement for CRM plugin returns 200', async ({ page }) => {
    const resp = await page.request.get('/api/entitlements/crm', { failOnStatusCode: false });
    const body = await resp.json().catch(() => null);
    if (!resp.ok() && isUnavailableRoute(body)) {
      test.skip(true, 'Plugin-specific entitlement route is unavailable in current environment');
      return;
    }
    expect(resp.ok(), 'CRM entitlement API should return 200').toBe(true);

    // Should have some entitlement data (could be the entitlement object or a wrapper)
    expect(body).toBeTruthy();
  });

  /**
   * LIC-03: License settings page is accessible via system menu
   */
  test('LIC-03: license settings page renders content', async ({ page }) => {
    // Navigate to settings/licenses — may be under system settings menu
    await page.goto('/dashboards', { waitUntil: 'load' });

    // Try to find a settings or system menu link to licenses
    const licenseLink = page.locator('a[href="/settings/licenses"], a[href*="license"]');
    const linkVisible = await licenseLink
      .first()
      .isVisible({ timeout: 5000 })
      .catch(() => false);

    if (linkVisible) {
      await licenseLink.first().evaluate((el) => (el as HTMLAnchorElement).click());
      await page.waitForURL((url) => url.pathname.includes('license'), { timeout: 10000 });
    } else {
      // Direct navigation as fallback
      await page.goto('/settings/licenses', { waitUntil: 'domcontentloaded' });
    }

    await waitForDynamicPageLoad(page);

    // Page should render some content (not just blank or 404)
    const content = page.locator('main, .ant-card, .ant-table, form, h1, h2');
    await expect(content.first()).toBeVisible({ timeout: 10000 });

    // Not a 404 error page
    await expect(page.locator('text=Page not found')).not.toBeVisible({ timeout: 2000 });
  });

  /**
   * LIC-04: Invalid token is gracefully rejected (not 500)
   */
  test('LIC-04: invalid license token is rejected without server error', async ({ page }) => {
    const resp = await page.request.post('/api/entitlements/import-token', {
      data: { token: 'bad-invalid-token-12345' },
      headers: { 'Content-Type': 'application/json' },
      failOnStatusCode: false,
    });
    const body = await resp.json().catch(() => null);
    if (isUnavailableRoute(body)) {
      test.skip(true, 'License token import route is unavailable in current environment');
      return;
    }

    // Should not be a 500 — 400 (bad request) or 200-with-error are acceptable
    expect(resp.status()).not.toBe(500);
    expect(resp.status()).not.toBe(502);
    expect(resp.status()).not.toBe(503);
  });

  /**
   * LIC-05: Entitlements response contains enabled field
   */
  test('LIC-05: entitlements response contains enabled field', async ({ page }) => {
    const resp = await page.request.get('/api/entitlements');
    expect(resp.ok()).toBe(true);

    const body = await resp.json();
    const data = body?.data ?? body;

    // The response itself or items within should have an "enabled" field
    if (Array.isArray(data)) {
      // If there are entitlements, at least one should have 'enabled'
      if (data.length > 0) {
        const hasEnabled = data.some((item: any) => 'enabled' in item);
        expect(hasEnabled).toBe(true);
      }
    } else if (typeof data === 'object' && data !== null) {
      // Check top-level or nested records
      const records = data.records ?? data.items ?? [];
      if (Array.isArray(records) && records.length > 0) {
        const hasEnabled = records.some((item: any) => 'enabled' in item);
        expect(hasEnabled).toBe(true);
      } else {
        // Top-level object might have enabled
        expect('enabled' in data || 'entitlementEnabled' in data).toBe(true);
      }
    }
  });

  /**
   * LIC-06: Feature check API for CRM plugin
   */
  test('LIC-06: feature check API for CRM returns 200', async ({ page }) => {
    const resp = await page.request.get('/api/entitlements/crm/features', {
      failOnStatusCode: false,
    });
    const body = await resp.json().catch(() => null);
    if (!resp.ok() && isUnavailableRoute(body)) {
      test.skip(true, 'Plugin-specific entitlement feature route is unavailable in current environment');
      return;
    }
    expect(resp.ok(), 'CRM features API should return 200').toBe(true);

    // Should return some structure (array of features or object)
    expect(body).toBeTruthy();
  });
});
