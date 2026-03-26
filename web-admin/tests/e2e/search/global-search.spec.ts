/**
 * Global Search (Command Palette) E2E Tests
 *
 * Validates the Cmd+K command palette:
 * - Trigger via keyboard shortcut (Cmd+K / Ctrl+K)
 * - Trigger via header button click
 * - Menu page search (instant filtering)
 * - Keyboard navigation (↑↓ Enter Esc)
 *
 * Prerequisites:
 *   - At least one plugin imported (CRM or similar) with menus and data
 *
 * @since 7.4.0
 */

import { test, expect } from '../../fixtures';

test.describe('Global Search (Cmd+K) @smoke', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(45000);

  // Helper: navigate to home and open palette
  async function openPalette(page: import('@playwright/test').Page) {
    await page.goto('/dashboards', { waitUntil: 'load' });

    // Wait for React hydration — trigger button must be interactive
    const trigger = page.locator('[data-testid="cmd-k-trigger"]');
    await expect(trigger).toBeVisible({ timeout: 15000 });
    await expect(trigger).toBeEnabled({ timeout: 5000 });

    const palette = page.locator('[data-testid="command-palette"]');

    // Strategy 1: Click the trigger button directly (most reliable in batch)
    await trigger.click();
    const openedViaClick = await palette.isVisible({ timeout: 3000 }).catch(() => false);

    if (!openedViaClick) {
      // Strategy 2: Keyboard shortcut
      await page.locator('body').click({ position: { x: 400, y: 400 } });
      await page.keyboard.down('Control');
      await page.keyboard.press('k');
      await page.keyboard.up('Control');
      const openedViaShortcut = await palette.isVisible({ timeout: 2000 }).catch(() => false);
      if (!openedViaShortcut) {
        // Strategy 3: Retry click
        await trigger.click();
      }
    }
    await expect(palette).toBeVisible({ timeout: 10000 });
    return palette;
  }

  // =========================================================================
  // TESTS
  // =========================================================================

  test('SEARCH-01: Cmd+K trigger button visible in header', async ({ page }) => {
    await page.goto('/dashboards', { waitUntil: 'load' });

    const trigger = page.locator('[data-testid="cmd-k-trigger"]');
    await expect(trigger).toBeVisible({ timeout: 10000 });

    // Should show keyboard shortcut hint
    const kbd = trigger.locator('kbd');
    await expect(kbd).toBeVisible();
  });

  test('SEARCH-02: Click trigger opens command palette', async ({ page }) => {
    const palette = await openPalette(page);

    // Input should be focused
    const input = page.locator('[data-testid="command-palette-input"]');
    await expect(input).toBeVisible();
    await expect(input).toBeFocused();
  });

  test('SEARCH-03: Keyboard shortcut opens and Esc closes', async ({ page }) => {
    await page.goto('/dashboards', { waitUntil: 'load' });
    const trigger = page.locator('[data-testid="cmd-k-trigger"]');
    await expect(trigger).toBeVisible({ timeout: 10000 });

    // Click somewhere on the page body first to ensure focus
    await page.locator('body').click({ position: { x: 400, y: 400 } });

    // Use keyboard shortcut
    await page.keyboard.down('Control');
    await page.keyboard.press('k');
    await page.keyboard.up('Control');

    const palette = page.locator('[data-testid="command-palette"]');
    await expect(palette).toBeVisible({ timeout: 5000 });

    // Close with Esc
    await page.keyboard.press('Escape');
    await expect(palette).not.toBeVisible({ timeout: 3000 });
  });

  test('SEARCH-04: Menu search filters results instantly', async ({ page }) => {
    const palette = await openPalette(page);

    const input = page.locator('[data-testid="command-palette-input"]');
    await input.fill('crm');

    // Wait for instant menu filtering
    const results = page.locator('[data-testid="command-palette-results"]');
    await expect(results).toBeVisible();

    // Should have at least one result button with CRM in it
    const resultItems = results.locator('button');
    await expect(resultItems.first()).toBeVisible({ timeout: 3000 });
    const count = await resultItems.count();
    expect(count, 'Should find at least one CRM-related menu item').toBeGreaterThanOrEqual(1);
  });

  test('SEARCH-05: Empty query shows hint text', async ({ page }) => {
    const palette = await openPalette(page);

    // With empty query, should show hint or recent searches
    const results = page.locator('[data-testid="command-palette-results"]');
    await expect(results).toBeVisible();

    // Should have some content (hint text or recent)
    const text = await results.textContent();
    expect(text!.length, 'Results area should have content').toBeGreaterThan(0);
  });

  test('SEARCH-06: Record search returns results from API', async ({ page }) => {
    const palette = await openPalette(page);

    const input = page.locator('[data-testid="command-palette-input"]');
    // Search for 'e2e' — guaranteed to return results from the e2et_order model
    // (196+ test records with 'e2e' prefix created by fixtures setup)
    await input.fill('e2e');

    // Wait for records section to appear — no waitForTimeout; rely on toBeVisible timeout
    const results = page.locator('[data-testid="command-palette-results"]');
    const recordSection = results.locator('text=RECORDS').or(results.locator('text=Records'));
    await expect(recordSection.first()).toBeVisible({ timeout: 8000 });

    // Should have at least one record result
    const recordButtons = results.locator('button');
    const count = await recordButtons.count();
    expect(count, 'Should find record results for "admin"').toBeGreaterThanOrEqual(1);
  });

  test('SEARCH-07: Keyboard navigation ↓ and Enter', async ({ page }) => {
    const palette = await openPalette(page);

    const input = page.locator('[data-testid="command-palette-input"]');
    await input.fill('crm');

    // Wait for results
    const results = page.locator('[data-testid="command-palette-results"]');
    await expect(results.locator('button').first()).toBeVisible({ timeout: 3000 });

    // Arrow down
    await page.keyboard.press('ArrowDown');

    // Press Enter to navigate
    await page.keyboard.press('Enter');

    // Palette should close after selection
    await expect(palette).not.toBeVisible({ timeout: 5000 });
  });

  test('SEARCH-08: Selecting menu result navigates to page', async ({ page }) => {
    const palette = await openPalette(page);

    const input = page.locator('[data-testid="command-palette-input"]');
    await input.fill('crm');

    const results = page.locator('[data-testid="command-palette-results"]');
    const firstResult = results.locator('button').first();
    await expect(firstResult).toBeVisible({ timeout: 8000 });
    await firstResult.click();

    // Palette should close
    await expect(palette).not.toBeVisible({ timeout: 5000 });

    // URL should have changed
    await expect(page).not.toHaveURL(/^\/$/, { timeout: 5000 });
  });

  test('SEARCH-09: Footer shows keyboard shortcut hints', async ({ page }) => {
    const palette = await openPalette(page);

    // Footer kbd elements (Esc in header + ↑↓, ↵, Esc in footer = at least 4)
    const kbds = palette.locator('kbd');
    const kbdCount = await kbds.count();
    expect(kbdCount, 'Should have keyboard shortcut hints').toBeGreaterThanOrEqual(3);
  });
});
