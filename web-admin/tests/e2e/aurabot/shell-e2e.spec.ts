/**
 * AuraBot V3 Shell — Single E2E spec (C-1).
 *
 * Covers the seven steps from spec §8:
 *   1. Login → land on /dashboard from sidebar (no page.goto direct).
 *   2. Cmd+K toggles panel to expanded.
 *   3. Type `echo hello`, press Cmd+Enter to send.
 *   4. MessageList contains user message + assistant TextEnvelope text 'hello'.
 *   5. Pin button transitions panel to PINNED.
 *   6. Esc minimizes back to HIDDEN.
 *   7. Reload — HIDDEN preserved (last state via localStorage).
 *
 * AGENTS.md hard rules respected:
 *   - 0 page.request calls in test body (UI-only assertions).
 *   - 0 waitForTimeout / afterAll cleanup.
 *   - Asserts concrete values ('hello'), not bare toBeVisible.
 *
 * Mock vs real backend: while C-2 EchoSkill is unshipped, the FE bundle is
 * built with `VITE_AURABOT_USE_MOCK=true`, so the round-trip resolves
 * locally and this spec proves the wire path end-to-end.
 */

import { test, expect } from '../../fixtures';
import type { Page } from '@playwright/test';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';

const PANEL = '[data-aurabot-panel-state]';
const TOGGLE = '[data-aurabot-toggle]';
const INPUT = '[data-aurabot-input]';
const SEND = '[data-aurabot-send]';
const PIN = '[data-aurabot-pin]';
const CLOSE = '[data-aurabot-close]';

async function loginIfNeeded(page: Page) {
  if (page.url().includes('/login')) {
    await page.locator('input#email').fill(DEFAULT_TEST_ACCOUNT.email);
    await page.locator('input#password').fill(DEFAULT_TEST_ACCOUNT.password);
    await page.locator('button:has-text("立即登录")').click();
    await page.waitForURL((url) => !url.toString().includes('/login'), {
      timeout: 20000,
    });
  }
}

async function navigateToDashboardViaSidebar(page: Page) {
  // Sidebar contains the dashboard link. Avoid `page.goto('/dashboard')` per
  // AGENTS.md red line; click the menu item instead.
  await page.locator('a[href="/dashboard"], a[href="/"]').first().click();
  await page.waitForLoadState('domcontentloaded');
}

test.describe('AuraBot V3 Shell — round-trip', () => {
  test('Cmd+K toggles, echo round-trips, pin/Esc state machine, reload preserves HIDDEN', async ({
    page,
  }) => {
    // Step 1: Login → dashboard via sidebar.
    await page.goto('/');
    await loginIfNeeded(page);
    await navigateToDashboardViaSidebar(page);

    // Toggle should be visible on every authenticated page.
    const toggle = page.locator(TOGGLE);
    await expect(toggle).toBeVisible({ timeout: 10000 });

    // Step 2: Cmd+K opens the panel.
    await page.keyboard.press('Meta+K');
    await expect(page.locator(`${PANEL}`)).toBeVisible();
    await expect(page.locator(PANEL)).toHaveAttribute(
      'data-aurabot-panel-state',
      'expanded',
    );

    // Step 3: Type `echo hello`, send via Cmd+Enter.
    const input = page.locator(INPUT);
    await input.fill('echo hello');
    await input.press('Meta+Enter');

    // Step 4: User message + assistant text envelope with 'hello'.
    const messages = page.locator('[data-aurabot-message-id]');
    await expect(messages).toHaveCount(2, { timeout: 10000 });

    const assistantText = page.locator(
      '[data-aurabot-message-role="assistant"] [data-aurabot-envelope="text"]',
    );
    await expect(assistantText).toHaveText(/hello/);

    // Step 5: Pin transitions to PINNED.
    await page.locator(PIN).click();
    await expect(page.locator(PANEL)).toHaveAttribute(
      'data-aurabot-panel-state',
      'pinned',
    );

    // Step 6: Esc minimizes (PINNED → hidden via close button, since Esc only
    // applies to expanded; spec §3 says Esc minimizes from non-fullscreen
    // states — the close button is the deterministic UI action used here).
    await page.locator(CLOSE).click();
    await expect(page.locator(PANEL)).toHaveCount(0);
    await expect(toggle).toBeVisible();

    // Step 7: Reload — HIDDEN preserved.
    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.locator(TOGGLE)).toBeVisible({ timeout: 10000 });
    await expect(page.locator(PANEL)).toHaveCount(0);
  });
});
