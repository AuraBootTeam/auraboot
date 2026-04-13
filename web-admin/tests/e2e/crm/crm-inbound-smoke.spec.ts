/**
 * CRM Inbound & Calendar Smoke Tests
 *
 * Validates that all new CRM inbound/calendar pages load correctly:
 * - smoke-01: Inbound Channels page loads via menu navigation
 * - smoke-02: Lead Merge Queue page loads via menu navigation
 * - smoke-03: Web Forms page loads via menu navigation
 * - smoke-04: Calendar Sync page loads via menu navigation
 *
 * Menu paths:
 *   CRM → Settings → Inbound Channels  → /crm/settings/inbound-channels
 *   CRM → Lead Merge Queue             → /crm/merge-queue
 *   CRM → Settings → Web Forms         → /crm/settings/web-forms
 *   CRM → Settings → Calendar Integration → /crm/settings/calendar-sync
 *
 * @since 10.0.0
 */

import { test, expect, type Page } from '@playwright/test';

// ---------------------------------------------------------------------------
// Navigation helpers
// ---------------------------------------------------------------------------

/**
 * Navigate to a CRM page via sidebar menu.
 * Expands the CRM root menu, then optionally a sub-menu, then clicks the leaf.
 */
async function navigateViaCrmMenu(page: Page, href: string, subMenuName?: string): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  await page.waitForLoadState('domcontentloaded');

  const nav = page.locator('nav');

  // Expand CRM root
  const crmBtn = nav.getByRole('button', { name: 'crm' }).first();
  await crmBtn.scrollIntoViewIfNeeded();
  await crmBtn.evaluate((el: HTMLElement) => el.click());
  // Brief wait for menu to expand
  await page.waitForResponse(() => true, { timeout: 2000 }).catch(() => null);

  // Expand sub-menu if needed (e.g. "Settings", "Service Desk")
  if (subMenuName) {
    const subBtn = nav.getByRole('button', { name: subMenuName });
    const subVisible = await subBtn.isVisible({ timeout: 3000 }).catch(() => false);
    if (subVisible) {
      await subBtn.scrollIntoViewIfNeeded();
      await subBtn.evaluate((el: HTMLElement) => el.click());
      await page.waitForResponse(() => true, { timeout: 1500 }).catch(() => null);
    }
  }

  // Click leaf link by href
  const leafLink = nav.locator(`a[href="${href}"]`).first();
  await leafLink.waitFor({ state: 'attached', timeout: 8000 });
  await leafLink.scrollIntoViewIfNeeded();
  await leafLink.evaluate((el: HTMLElement) => el.click());

  // Wait for navigation
  await page.waitForURL((url) => url.pathname === href, { timeout: 10000 });
}

// ---------------------------------------------------------------------------
// Test suite
// ---------------------------------------------------------------------------

test.describe('CRM Inbound & Calendar @smoke', () => {
  test.setTimeout(30000);

  test('smoke-01: Inbound Channels page loads', async ({ page }) => {
    await navigateViaCrmMenu(page, '/crm/settings/inbound-channels', 'Settings');
    await expect(page).toHaveURL(/\/crm\/settings\/inbound-channels/);

    // Page heading should be visible
    await expect(page.getByRole('heading', { name: 'Inbound Channels' })).toBeVisible({
      timeout: 10000,
    });

    // Either the channel table or the empty state should be visible
    const table = page.locator('[data-testid="channel-list"]');
    const empty = page.locator('[data-testid="channel-empty"]');
    await expect(table.or(empty).first()).toBeVisible({ timeout: 10000 });
  });

  test('smoke-02: Lead Merge Queue page loads', async ({ page }) => {
    await navigateViaCrmMenu(page, '/crm/merge-queue');
    await expect(page).toHaveURL(/\/crm\/merge-queue/);

    // Page heading should be visible
    await expect(page.getByRole('heading', { name: 'Lead Merge Queue' })).toBeVisible({
      timeout: 10000,
    });

    // Filter tabs should be visible
    await expect(page.locator('[data-testid="merge-tab-all"]')).toBeVisible();
    await expect(page.locator('[data-testid="merge-tab-pending"]')).toBeVisible();

    // Either the queue list or the empty state should be visible
    const list = page.locator('[data-testid="merge-queue-list"]');
    const empty = page.locator('[data-testid="merge-queue-empty"]');
    await expect(list.or(empty).first()).toBeVisible({ timeout: 10000 });
  });

  test('smoke-03: Web Forms page loads', async ({ page }) => {
    await navigateViaCrmMenu(page, '/crm/settings/web-forms', 'Settings');
    await expect(page).toHaveURL(/\/crm\/settings\/web-forms/);

    // Page heading should be visible
    await expect(page.getByRole('heading', { name: 'Web Forms' })).toBeVisible({ timeout: 10000 });

    // Either the form list or the empty state should be visible
    const list = page.locator('[data-testid="webform-list"]');
    const empty = page.locator('[data-testid="webform-empty"]');
    await expect(list.or(empty).first()).toBeVisible({ timeout: 10000 });
  });

  test('smoke-04: Calendar Sync page loads', async ({ page }) => {
    await navigateViaCrmMenu(page, '/crm/settings/calendar-sync', 'Settings');
    await expect(page).toHaveURL(/\/crm\/settings\/calendar-sync/);

    // Wait for loading to finish — the Calendar Sync page shows "Loading..."
    // until the providers API returns, then renders the heading.
    // Use the provider cards as the signal that loading completed.
    await expect(page.locator('[data-testid="calendar-providers"]')).toBeVisible({
      timeout: 15000,
    });

    // Page heading should be visible
    await expect(page.getByRole('heading', { name: 'Calendar Sync', exact: true })).toBeVisible({
      timeout: 5000,
    });

    // Provider cards should be visible
    await expect(page.locator('[data-testid="calendar-providers"]')).toBeVisible({
      timeout: 10000,
    });

    // Both provider cards should render
    await expect(page.locator('[data-testid="calendar-provider-google"]')).toBeVisible();
    await expect(page.locator('[data-testid="calendar-provider-microsoft"]')).toBeVisible();

    // Connect buttons should be visible (since not connected by default)
    await expect(page.locator('[data-testid="connect-google"]')).toBeVisible();
    await expect(page.locator('[data-testid="connect-microsoft"]')).toBeVisible();
  });
});
