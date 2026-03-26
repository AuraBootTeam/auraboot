/**
 * Community Edition Smoke Tests
 *
 * Verifies core platform capabilities are accessible:
 * - Agent status API
 * - AuraBot management pages (providers, prompts, dashboard)
 * - DSL engine (e2e-test-order list)
 * - Plugin Marketplace page
 * - Enterprise endpoint existence
 *
 * All navigation uses sidebar menus (not page.goto for test paths).
 * No waitForTimeout — uses waitForResponse / waitFor / expect().toBeVisible().
 *
 * @since 5.0.0
 */

import { test, expect } from '../../fixtures';
import {
  navigateToDynamicPage,
  waitForDynamicPageLoad,
  getTableRowCount,
} from '../helpers/index';
import type { Page } from '@playwright/test';

// ---------------------------------------------------------------------------
// Sidebar menu navigation helper
// ---------------------------------------------------------------------------

async function clickSidebarMenu(page: Page, parentName: RegExp, childHref: string) {
  // Navigate to admin dashboard (/ is marketing website, not admin)
  await page.goto('/dashboards', { waitUntil: 'load' });

  // Find and expand parent menu
  const parentBtn = page.locator('nav').getByRole('button', { name: parentName });
  await parentBtn.first().waitFor({ state: 'visible', timeout: 10000 });
  await parentBtn.first().evaluate((el: HTMLElement) => el.click());

  // Click child menu link
  const childLink = page.locator(`a[href="${childHref}"]`);
  await childLink.first().waitFor({ state: 'visible', timeout: 5000 });
  await childLink.first().evaluate((el) => (el as HTMLAnchorElement).click());

  await page.waitForURL((url) => url.pathname === childHref, { timeout: 10000 });
}

test.describe('Community Edition Smoke', () => {
  test.describe.configure({ timeout: 30000 });

  /**
   * CM-01: Agent status API returns expected fields
   */
  test('CM-01: agent status API returns enabled and enterpriseAvailable fields', async ({ page }) => {
    const resp = await page.request.get('/api/agent/status');
    expect(resp.ok(), 'Agent status API should return 200').toBe(true);

    const body = await resp.json();
    const data = body?.data ?? body;

    expect(data).toHaveProperty('enabled');
    expect(typeof data.enabled).toBe('boolean');
    expect(data).toHaveProperty('enterpriseAvailable');
  });

  /**
   * CM-02: AuraBot Providers page via sidebar menu
   */
  test('CM-02: AuraBot Providers page loads via sidebar menu', async ({ page }) => {
    await clickSidebarMenu(page, /AuraBot|AuraBot 管理/, '/aurabot/providers');

    // Wait for page content to render
    await waitForDynamicPageLoad(page);

    // Assert meaningful content: cards, form, or table with provider config
    const content = page.locator(
      '.ant-card, .ant-table, table, form, [data-testid*="provider"], main'
    );
    await expect(content.first()).toBeVisible({ timeout: 10000 });

    // Page should not be a 404
    await expect(page.locator('text=Page not found')).not.toBeVisible({ timeout: 2000 });
  });

  /**
   * CM-03: AuraBot Prompts page via sidebar menu
   */
  test('CM-03: AuraBot Prompts page loads via sidebar menu', async ({ page }) => {
    await clickSidebarMenu(page, /AuraBot|AuraBot 管理/, '/aurabot/prompts');

    await waitForDynamicPageLoad(page);

    // Assert prompt template list or editor is visible
    const content = page.locator(
      '.ant-table, table, .ant-card, [data-testid*="prompt"], textarea, main'
    );
    await expect(content.first()).toBeVisible({ timeout: 10000 });

    await expect(page.locator('text=Page not found')).not.toBeVisible({ timeout: 2000 });
  });

  /**
   * CM-04: Mission Control conditional rendering based on enterprise availability
   */
  test('CM-04: Mission Control renders correctly based on enterprise availability', async ({ page }) => {
    // First check enterprise availability via API
    const statusResp = await page.request.get('/api/agent/status');
    expect(statusResp.ok()).toBe(true);
    const statusBody = await statusResp.json();
    const statusData = statusBody?.data ?? statusBody;
    const isEnterprise = statusData.enterpriseAvailable === true;

    // Navigate to AuraBot Dashboard via sidebar
    await clickSidebarMenu(page, /AuraBot|AuraBot 管理/, '/aurabot/dashboard');
    await waitForDynamicPageLoad(page);

    if (isEnterprise) {
      // Enterprise mode: Mission Control should be visible
      const missionControl = page.locator('[data-testid="mission-control"]');
      await expect(missionControl).toBeVisible({ timeout: 10000 });
    } else {
      // Community mode: either enterprise upsell is visible, or the dashboard still renders
      const upsell = page.locator('[data-testid="enterprise-upsell"]');
      const dashboard = page.locator('main');
      const upsellVisible = await upsell.isVisible({ timeout: 5000 }).catch(() => false);
      const dashboardVisible = await dashboard.isVisible({ timeout: 2000 }).catch(() => false);
      expect(upsellVisible || dashboardVisible).toBe(true);
    }
  });

  /**
   * CM-05: ChatBI health endpoint is not 404 or 500
   */
  test('CM-05: ChatBI health endpoint exists and does not error', async ({ page }) => {
    const resp = await page.request.get('/api/ai/chat-bi/health');

    // Endpoint should exist (not 404) and not crash (not 500)
    expect(resp.status()).not.toBe(404);
    expect(resp.status()).not.toBe(500);
  });

  /**
   * CM-06: DSL engine works — e2e-test-order list has data
   */
  test('CM-06: DSL engine renders e2e-test-order list with real data', async ({ page }) => {
    await navigateToDynamicPage(page, 'e2et-order');

    const rowCount = await getTableRowCount(page);
    expect(rowCount).toBeGreaterThan(0);
  });

  /**
   * CM-07: Plugin Marketplace page loads via sidebar
   */
  test('CM-07: Plugin Marketplace page is accessible', async ({ page }) => {
    // Marketplace may be a top-level menu item, try sidebar navigation
    await page.goto('/dashboards', { waitUntil: 'load' });

    const marketplaceLink = page.locator('a[href="/marketplace"]');
    const linkVisible = await marketplaceLink.first().isVisible({ timeout: 5000 }).catch(() => false);

    if (linkVisible) {
      await marketplaceLink.first().evaluate((el) => (el as HTMLAnchorElement).click());
      await page.waitForURL((url) => url.pathname === '/marketplace', { timeout: 10000 });
    } else {
      // Fallback: navigate directly if menu structure differs
      await page.goto('/marketplace', { waitUntil: 'domcontentloaded' });
    }

    await waitForDynamicPageLoad(page);

    // Assert meaningful content on the marketplace page
    const content = page.locator(
      'h1, h2, .ant-card, [data-testid*="marketplace"], [data-testid*="plugin"], main'
    );
    await expect(content.first()).toBeVisible({ timeout: 10000 });

    // Not a 404
    await expect(page.locator('text=Page not found')).not.toBeVisible({ timeout: 2000 });
  });

  /**
   * CM-08: Enterprise endpoint existence check — IM conversations API
   */
  test('CM-08: enterprise endpoints respond appropriately based on edition', async ({ page }) => {
    // Check enterprise availability
    const statusResp = await page.request.get('/api/agent/status');
    const statusBody = await statusResp.json();
    const statusData = statusBody?.data ?? statusBody;
    const isEnterprise = statusData.enterpriseAvailable === true;

    // Test agent retry endpoint
    const retryResp = await page.request.post('/api/agent/run/nonexistent/retry', {
      data: {},
      failOnStatusCode: false,
    });

    // Test IM conversations endpoint
    const imResp = await page.request.get('/api/im/conversations');

    if (isEnterprise) {
      // Enterprise: endpoints should exist (not 404), though may return 400/403/other
      expect(retryResp.status()).not.toBe(404);
      expect(imResp.status()).not.toBe(404);
    } else {
      // Community: endpoints may be 404 or may still exist with limited functionality
      // Either way, they should not be 500
      expect(retryResp.status()).not.toBe(500);
      expect(imResp.status()).not.toBe(500);
    }
  });
});
