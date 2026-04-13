/**
 * Login Channel Management E2E Tests
 *
 * Tests LC-001 ~ LC-005: Admin login channel configuration
 * - Page structure and channel visibility
 * - Enable/disable channel toggles
 * - Save channel configuration
 * - Arrow-based reordering
 * - Channel config reflects on login page
 *
 * Route: /admin/login-channels
 * Uses storageState for authentication.
 *
 * @since 4.0.0
 */

import { test, expect } from '../../fixtures';
import type { Page } from '@playwright/test';

const ALL_CHANNELS = ['email_password', 'sms', 'email_code', 'wechat', 'google', 'apple'] as const;

/** Default channel config — only EMAIL_PASSWORD enabled. */
const DEFAULT_CHANNELS = [
  { channel: 'email_password', enabled: true, sortOrder: 0 },
  { channel: 'sms', enabled: false, sortOrder: 1 },
  { channel: 'email_code', enabled: false, sortOrder: 2 },
  { channel: 'wechat', enabled: false, sortOrder: 3 },
  { channel: 'google', enabled: false, sortOrder: 4 },
  { channel: 'apple', enabled: false, sortOrder: 5 },
];

/** Navigate to login channels page and wait for API data to load. */
async function gotoLoginChannels(page: Page) {
  await page.goto('/admin/login-channels');
  await page
    .locator('h1')
    .filter({ hasText: /登录渠道管理/ })
    .waitFor({ state: 'visible', timeout: 8000 });
  await page
    .locator('[data-testid="login-channels-save-btn"]')
    .waitFor({ state: 'visible', timeout: 8000 });
  await page
    .locator('.animate-spin')
    .waitFor({ state: 'detached', timeout: 10000 })
    .catch(() => {});
  await page
    .locator('[data-testid^="login-channel-"]')
    .first()
    .waitFor({ state: 'visible', timeout: 8000 });
}

test.describe('Login Channel Management', () => {
  // Pre-set known state to avoid flakiness from parallel projects sharing DB
  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({
      storageState: './tests/storage/admin.json',
    });
    const setupPage = await ctx.newPage();
    try {
      await setupPage.request.put('/api/admin/login-channels', {
        data: DEFAULT_CHANNELS,
      });
    } finally {
      await setupPage.close();
      await ctx.close();
    }
  });

  test.afterAll(async ({ browser }) => {
    // Restore default channel configuration
    const ctx = await browser.newContext({
      storageState: './tests/storage/admin.json',
    });
    const cleanupPage = await ctx.newPage();
    try {
      await cleanupPage.request.put('/api/admin/login-channels', {
        data: DEFAULT_CHANNELS,
      });
    } finally {
      await cleanupPage.close();
      await ctx.close();
    }
  });

  /**
   * LC-001: Page load and basic structure
   * Verify page title, all 6 channel items, and save button are visible.
   */
  test('LC-001: should display page structure with all channels @smoke', async ({ page }) => {
    await gotoLoginChannels(page);

    // Page title
    await expect(page.locator('h1').filter({ hasText: /登录渠道管理/ })).toBeVisible();

    // All 6 channel items
    for (const ch of ALL_CHANNELS) {
      await expect(page.locator(`[data-testid="login-channel-${ch}"]`)).toBeVisible();
    }

    // Save button
    await expect(page.locator('[data-testid="login-channels-save-btn"]')).toBeVisible();
  });

  /**
   * LC-002: Enable/disable channel toggle
   * Toggle SMS channel and verify unsaved-changes indicator appears.
   */
  test('LC-002: should toggle channel and show unsaved indicator', async ({ page }) => {
    await gotoLoginChannels(page);

    const smsToggle = page.locator('[data-testid="login-channel-toggle-sms"]');
    await expect(smsToggle).toBeVisible();

    // Record current state
    const wasEnabled = (await smsToggle.getAttribute('aria-checked')) === 'true';

    // Click toggle
    await smsToggle.click();

    // State should have flipped (use expect with auto-retry for React re-render)
    await expect(smsToggle).toHaveAttribute('aria-checked', String(!wasEnabled), { timeout: 3000 });

    // Unsaved changes indicator should appear
    await expect(page.getByText('有未保存的更改')).toBeVisible({ timeout: 3000 });
  });

  /**
   * LC-003: Save channel configuration
   * Toggle SMS, save, and verify success toast.
   */
  test('LC-003: should save channel configuration @smoke', async ({ page }) => {
    await gotoLoginChannels(page);

    const smsToggle = page.locator('[data-testid="login-channel-toggle-sms"]');
    await expect(smsToggle).toBeVisible();

    // Record initial state
    const wasEnabled = (await smsToggle.getAttribute('aria-checked')) === 'true';

    // Toggle SMS to make a change
    await smsToggle.click();

    // Wait for toggle state to actually change (React re-render)
    await expect(smsToggle).toHaveAttribute('aria-checked', String(!wasEnabled), { timeout: 3000 });

    // Verify unsaved indicator appeared
    await expect(page.getByText('有未保存的更改')).toBeVisible({ timeout: 3000 });

    const saveBtn = page.locator('[data-testid="login-channels-save-btn"]');
    await expect(saveBtn).toBeEnabled({ timeout: 3000 });
    await saveBtn.click();

    // Success toast should appear
    await expect(page.getByText('登录渠道配置已保存')).toBeVisible({ timeout: 5000 });

    // Unsaved indicator should disappear
    await expect(page.getByText('有未保存的更改')).not.toBeVisible({
      timeout: 3000,
    });

    // Note: afterAll restores default channel config via API, no need to restore here
  });

  /**
   * LC-004: Arrow-based reordering
   * Move a channel down and verify position change + unsaved indicator.
   */
  test('LC-004: should reorder channels with arrow buttons', async ({ page }) => {
    await gotoLoginChannels(page);

    const firstChannel = page.locator('[data-testid="login-channel-email_password"]');
    await expect(firstChannel).toBeVisible();

    // Capture the initial order of channel items
    const channelList = page.locator(
      '[data-testid^="login-channel-"]:not([data-testid*="toggle"])',
    );
    const initialOrder = await channelList.evaluateAll((els) =>
      els.map((el) => el.getAttribute('data-testid')),
    );

    // Find the "down" arrow button inside the first channel item
    const downButton = firstChannel.locator('button[title="下移"]');
    await expect(downButton).toBeVisible();
    await downButton.click();

    // Wait for React re-render — unsaved indicator confirms state change
    await expect(page.getByText('有未保存的更改')).toBeVisible({ timeout: 3000 });

    // Capture the new order after DOM update
    const newOrder = await channelList.evaluateAll((els) =>
      els.map((el) => el.getAttribute('data-testid')),
    );

    // The first item should have moved to the second position
    expect(newOrder[0]).not.toBe(initialOrder[0]);
    expect(newOrder[1]).toBe(initialOrder[0]);
  });

  /**
   * LC-005: Channel config affects login page display
   * Enable SMS via API, then verify login page shows SMS tab.
   */
  test('LC-005: should reflect enabled channels on login page', async ({ page, browser }) => {
    // Enable SMS via API directly (more reliable than UI for this setup step)
    const enabledChannels = [
      { channel: 'email_password', enabled: true, sortOrder: 0 },
      { channel: 'sms', enabled: true, sortOrder: 1 },
      { channel: 'email_code', enabled: false, sortOrder: 2 },
      { channel: 'wechat', enabled: false, sortOrder: 3 },
      { channel: 'google', enabled: false, sortOrder: 4 },
      { channel: 'apple', enabled: false, sortOrder: 5 },
    ];
    const putResp = await page.request.put('/api/admin/login-channels', {
      data: enabledChannels,
    });
    expect(putResp.ok()).toBe(true);

    // Open a fresh browser context without storageState (unauthenticated)
    const freshContext = await browser.newContext({
      storageState: { cookies: [], origins: [] },
    });
    const freshPage = await freshContext.newPage();

    try {
      await freshPage.goto('/login', { waitUntil: 'domcontentloaded' });
      // The heading text comes from i18n key auth.welcome — may render as the translated text or the key itself
      await expect(freshPage.locator('h1').first()).toBeVisible({
        timeout: 8000,
      });
      await expect(freshPage.getByTestId('login-page-root')).toHaveAttribute(
        'data-hydrated',
        'true',
        { timeout: 8000 },
      );
      const loginTabs = freshPage.getByTestId('login-channel-tabs');
      const hasTabs = await loginTabs.isVisible({ timeout: 1500 }).catch(() => false);
      if (hasTabs) {
        await expect(loginTabs).toBeVisible({ timeout: 8000 });
      }

      // SMS must be exposed on login page after channel config is enabled.
      const smsTab = freshPage
        .locator('[data-testid="login-tab-sms"], button:has-text("短信"), button:has-text("sms")')
        .first();
      await expect(smsTab).toBeVisible({ timeout: 8000 });
      await smsTab.click();
      await expect(freshPage.locator('#mobile')).toBeVisible({ timeout: 8000 });
      await expect(freshPage.locator('#sms-code')).toBeVisible({ timeout: 8000 });

      const emailTab = freshPage
        .locator(
          '[data-testid="login-tab-email_password"], button:has-text("密码"), button:has-text("Email")',
        )
        .first();
      await expect(emailTab).toBeVisible({ timeout: 8000 });
      await emailTab.click();
      await expect(freshPage.locator('#email')).toBeVisible({ timeout: 8000 });
      await expect(freshPage.locator('#password')).toBeVisible({ timeout: 8000 });
    } finally {
      await freshPage.close();
      await freshContext.close();
    }
  });
});
