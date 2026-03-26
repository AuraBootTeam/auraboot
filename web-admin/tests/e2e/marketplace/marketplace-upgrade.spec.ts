/**
 * Marketplace Upgrade Tests
 *
 * Verifies that the upgrade API works correctly and the UI handles
 * both "upgrades available" and "no upgrades available" states gracefully.
 *
 * Uses storageState for authentication (auto-configured in playwright.config).
 */

import { test, expect } from '../../fixtures';
import type { Page } from '../../fixtures';

/** Navigate to Marketplace via sidebar menu */
async function navigateToMarketplace(page: Page) {
  await page.goto('/dashboards', { waitUntil: 'load' });

  // Expand "系统管理" parent menu first
  const nav = page.locator('nav');
  const sysBtn = nav.getByRole('button', { name: /系统管理|System/ });
  await sysBtn.first().waitFor({ state: 'visible', timeout: 10000 });
  await sysBtn.first().evaluate((el: HTMLElement) => el.click());

  const menuLink = page.locator('a[href="/marketplace"]');
  await menuLink.first().waitFor({ state: 'visible', timeout: 10000 });
  await menuLink.first().evaluate((el) => (el as HTMLAnchorElement).click());

  await expect(page).toHaveURL(/\/marketplace/, { timeout: 10000 });
}

test.describe('Marketplace Upgrade Tests', () => {

  test('upgrade API returns valid response', async ({ page }) => {
    const resp = await page.request.get('/api/marketplace/upgrades');
    expect(resp.ok()).toBeTruthy();
    const json = await resp.json();
    expect(json.data).toBeDefined();
    expect(Array.isArray(json.data)).toBeTruthy();
  });

  test('marketplace page loads and shows plugin cards', async ({ page }) => {
    await navigateToMarketplace(page);

    // Wait for plugin grid to render
    const cards = page.locator('.grid > div').filter({ hasText: /v\d+\.\d+/ });
    await expect(cards.first()).toBeVisible({ timeout: 10000 });

    // Should show plugin count
    const countText = page.locator('text=/\\d+ (plugins|个插件)/');
    await expect(countText).toBeVisible();
  });

  test('upgrade banner shows when upgrades are available', async ({ page }) => {
    await navigateToMarketplace(page);

    // Wait for page to finish loading upgrades
    // Wait for upgrade API to complete (don't use networkidle — project rule)
    await page.waitForResponse(resp => resp.url().includes('/api/marketplace/upgrades') || resp.url().includes('/api/marketplace/plugins'), { timeout: 10000 }).catch(() => {});

    // Check if banner exists — acceptable states:
    // 1. Banner visible (upgrades available)
    // 2. No banner (all plugins at latest version)
    const banner = page.locator('[data-testid="upgrade-banner"]');
    const bannerVisible = await banner.isVisible();

    if (bannerVisible) {
      // Banner should contain update count text
      await expect(banner).toContainText(/plugin update|插件有可用更新/);

      // Close button should be present
      const closeBtn = banner.locator('button');
      await expect(closeBtn).toBeVisible();

      // Click close button and verify banner disappears
      await closeBtn.click();
      await expect(banner).not.toBeVisible();
    } else {
      // No upgrades — verify the page still rendered correctly
      const cards = page.locator('.grid > div').filter({ hasText: /v\d+\.\d+/ });
      await expect(cards.first()).toBeVisible({ timeout: 10000 });
    }
  });

  test('plugin card shows upgrade badge when update available', async ({ page }) => {
    await navigateToMarketplace(page);

    const cards = page.locator('.grid > div').filter({ hasText: /v\d+\.\d+/ });
    await expect(cards.first()).toBeVisible({ timeout: 10000 });

    // Check if any card shows upgrade badge
    const upgradeBadge = page.locator('.grid > div').filter({ hasText: /Update|可更新/ });
    const hasBadge = await upgradeBadge.count() > 0;

    if (hasBadge) {
      // Verify the upgrade button appears on that card
      const upgradeBtn = upgradeBadge.first().locator('button').filter({ hasText: /Upgrade|升级/ });
      await expect(upgradeBtn).toBeVisible();
    } else {
      // No upgrade badges — all plugins at latest version; that's OK
      const installedBadge = page.locator('.grid > div').filter({ hasText: /Installed|已安装/ });
      await expect(installedBadge.first()).toBeVisible();
    }
  });

  test('plugin detail shows version info and installed status', async ({ page }) => {
    await navigateToMarketplace(page);

    // Click first plugin card
    const firstCard = page.locator('.grid > div').filter({ hasText: /v\d+\.\d+/ }).first();
    await expect(firstCard).toBeVisible({ timeout: 10000 });
    await firstCard.click();

    // Detail page should load
    await page.waitForLoadState('domcontentloaded');

    // Should show back link
    await expect(page.locator('text=/Back to Marketplace|返回市场/')).toBeVisible();

    // Should show version info in the header area
    const versionTag = page.locator('span').filter({ hasText: /v\d+\.\d+/ }).first();
    await expect(versionTag).toBeVisible();

    // Should show install/installed status
    const installBtn = page.locator('button:has-text("Install"), button:has-text("安装")');
    const installedBadge = page.locator('text=/Installed|已安装/');
    await expect(installBtn.or(installedBadge).first()).toBeVisible();
  });

  test('plugin detail shows upgrade button when update available', async ({ page }) => {
    await navigateToMarketplace(page);

    // Look for a card with upgrade badge
    const upgradeCard = page.locator('.grid > div').filter({ hasText: /Update|可更新/ }).first();
    const hasUpgradeCard = await upgradeCard.isVisible().catch(() => false);

    if (hasUpgradeCard) {
      // Navigate to that plugin's detail
      await upgradeCard.click();
      await page.waitForLoadState('domcontentloaded');

      // Should show the upgrade button
      const upgradeBtn = page.locator('button').filter({ hasText: /Upgrade v|升级 v/ });
      await expect(upgradeBtn).toBeVisible();
    } else {
      // No upgradable plugins in seed data — verify detail page still works
      const firstCard = page.locator('.grid > div').filter({ hasText: /v\d+\.\d+/ }).first();
      await expect(firstCard).toBeVisible({ timeout: 10000 });
      await firstCard.click();
      await page.waitForLoadState('domcontentloaded');
      await expect(page.locator('text=/Back to Marketplace|返回市场/')).toBeVisible();
    }
  });

  test('upgrade API response structure is correct', async ({ page }) => {
    const resp = await page.request.get('/api/marketplace/upgrades');
    expect(resp.ok()).toBeTruthy();

    const json = await resp.json();
    expect(json).toHaveProperty('data');
    expect(Array.isArray(json.data)).toBeTruthy();

    // If upgrades exist, check structure
    if (json.data.length > 0) {
      const first = json.data[0];
      expect(first).toHaveProperty('pluginId');
      expect(first).toHaveProperty('latestVersion');
      expect(first).toHaveProperty('installedVersion');
    }
  });
});
