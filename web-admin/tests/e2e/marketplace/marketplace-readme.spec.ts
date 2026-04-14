/**
 * Marketplace README Tests
 *
 * Verifies that the plugin detail page renders the README/description section
 * and that the API response includes readme-related fields.
 *
 * Uses storageState for authentication (auto-configured in playwright.config).
 */

import { test, expect } from '../../fixtures';
import type { Page } from '../../fixtures';

/**
 * Navigate to the Plugin Management page (discovery tab) via sidebar menu.
 *
 * The former /marketplace page is now the "discovery" tab on /plugins.
 */
async function navigateToMarketplace(page: Page) {
  await page.goto('/dashboards', { waitUntil: 'load' });

  const nav = page.locator('nav');
  const sysBtn = nav.getByRole('button', { name: /系统管理|System/ });
  await sysBtn.first().waitFor({ state: 'visible', timeout: 10000 });
  await sysBtn.first().evaluate((el: HTMLElement) => el.click());

  const menuLink = page.locator('a[href^="/plugins"]');
  await menuLink.first().waitFor({ state: 'visible', timeout: 10000 });
  await menuLink.first().evaluate((el) => (el as HTMLAnchorElement).click());

  await expect(page).toHaveURL(/\/plugins/, { timeout: 10000 });

  const discoveryTab = page.getByRole('tab', { name: /Discovery|发现/ });
  await discoveryTab.first().waitFor({ state: 'visible', timeout: 10000 });
  await discoveryTab.first().click();
  await expect(page).toHaveURL(/tab=discovery/, { timeout: 10000 });
}

test.describe('Marketplace README Tests', () => {
  test('plugin detail page shows description section', async ({ page }) => {
    await navigateToMarketplace(page);

    const firstCard = page.locator('.grid > div').filter({ hasText: /v\d+\.\d+/ }).first();
    await expect(firstCard).toBeVisible({ timeout: 10000 });
    await firstCard.click();
    await page.waitForLoadState('domcontentloaded');

    // Should show either markdown rendered content OR plain text description
    await expect(page.locator('text=/Back to (Marketplace|Plugins)|返回(市场|插件)/')).toBeVisible();

    // Description section should be visible
    const descSection = page.locator('.prose, p.text-gray-700, [class*="description"]');
    await expect(descSection.first()).toBeVisible({ timeout: 10000 });
  });

  test('plugin detail API includes readme fields', async ({ page }) => {
    const resp = await page.request.get('/api/marketplace/plugins');
    const data = await resp.json();
    const plugins = data.data?.records || data.data;

    if (Array.isArray(plugins) && plugins.length > 0) {
      const pluginId = plugins[0].pluginId;
      const detailResp = await page.request.get(`/api/marketplace/plugins/${pluginId}`);
      expect(detailResp.ok()).toBeTruthy();

      // readmeMarkdown and screenshots fields should be present in response
      const detail = (await detailResp.json()).data;
      expect(detail).toBeDefined();

      // These fields may be null but should be in the response schema
      expect(
        'readmeMarkdown' in detail ||
        'readme_markdown' in detail ||
        detail.readmeMarkdown !== undefined
      ).toBeTruthy();
    }
  });
});
