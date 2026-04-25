/**
 * AuraBot -> AI Modeling Wizard Entry E2E
 *
 * Verifies the SparklesIcon button inside AuraBotPanel closes the panel and
 * navigates to /meta/ai-modeling. Uses storageState (admin.json) for auth,
 * interacts only via page.click (no page.goto direct-to-target).
 *
 * @since 4.0.0
 */

import { test, expect } from '../../fixtures';
import type { Page } from '@playwright/test';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';

async function ensureLoggedInAppShell(page: Page) {
  await page.goto('/meta/models');
  if (page.url().includes('/login')) {
    await page.locator('input#email').fill(DEFAULT_TEST_ACCOUNT.email);
    await page.locator('input#password').fill(DEFAULT_TEST_ACCOUNT.password);
    await page.locator('button:has-text("立即登录")').click();
    await page.waitForURL((url) => !url.toString().includes('/login'), { timeout: 20000 });
    await page.goto('/meta/models');
  }
  await page.waitForLoadState('domcontentloaded');
  await expect(page.locator('[data-testid="ai-panel-toggle"]')).toBeEnabled({ timeout: 10000 });
}

async function openPanelRobustly(page: Page) {
  const panel = page.locator('[data-testid="aurabot-panel"]');
  const toggle = page.locator('[data-testid="ai-panel-toggle"]');
  for (let attempt = 0; attempt < 3; attempt += 1) {
    await toggle.click();
    const opened = await panel.isVisible({ timeout: 2500 }).catch(() => false);
    if (opened) return panel;
  }

  await page.locator('body').click();
  await page.keyboard.press(process.platform === 'darwin' ? 'Meta+KeyJ' : 'Control+KeyJ').catch(() => null);
  const openedByShortcut = await panel.isVisible({ timeout: 2500 }).catch(() => false);
  if (openedByShortcut) return panel;

  await page.reload({ waitUntil: 'domcontentloaded' });
  await expect(toggle).toBeVisible({ timeout: 10000 });
  await expect(toggle).toBeEnabled({ timeout: 5000 });
  await toggle.click();
  await expect(panel).toBeVisible({ timeout: 10000 });
  return panel;
}

test.describe('AuraBot AI Modeling entry', () => {
  test('opens panel then navigates to /meta/ai-modeling on Sparkles click', async ({ page }) => {
    await ensureLoggedInAppShell(page);

    const panel = page.locator('[data-testid="aurabot-panel"]');
    const toggle = page.locator('[data-testid="ai-panel-toggle"]');

    // Open the AuraBot panel from the header toggle. In dev mode the first
    // click can race with layout updates, so retry once with the keyboard
    // shortcut that the header itself advertises.
    await openPanelRobustly(page);

    // Click the AI Modeling Wizard entry inside the panel
    const entry = page.locator('[data-testid="aurabot-ai-modeling-trigger"]');
    await expect(entry).toBeVisible();
    await entry.click();

    // Panel should close and URL should switch to the AI modeling page
    await page.waitForURL(/\/meta\/ai-modeling/, { timeout: 10000 });
    await expect(panel).toBeHidden({ timeout: 5000 });
    await page.waitForLoadState('domcontentloaded');

    // Page should render meaningful content (heading) rather than a blank shell
    const heading = page.locator('h1, h2').first();
    await expect(heading).toBeVisible({ timeout: 10000 });
  });
});
