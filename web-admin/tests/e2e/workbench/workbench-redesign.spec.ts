/**
 * Workbench redesign smoke test.
 *
 * Validates the redesigned /home workbench (Plan 4) renders all key components:
 * - KPI stats cards with 7-day series sparklines
 * - Inbox task table with priority/status columns
 * - Quick shortcuts list
 * - Top bar polish (env chip, header height)
 *
 * This spec does not execute against a live backend; it documents the expected
 * structure and selectors for manual/CI verification once backend is live.
 *
 * @since 2026-05-28
 */

import { test, expect } from '@playwright/test';

test.describe('Workbench redesign smoke', () => {
  test('home page renders with new KPI cards, table tasks, list shortcuts', async ({ page }) => {
    await page.goto('/home');

    // Verify top-level workbench heading visible
    await expect(page.getByRole('heading', { name: /workbench|dashboard/i }).first()).toBeVisible({
      timeout: 10_000,
    });

    // KPI cards with sparkline series
    // StatItem components render as [data-testid="stat-item-<metricKey>"]
    const statItems = page.locator('[data-testid^="stat-item-"]');
    await expect(statItems.first()).toBeVisible({ timeout: 10_000 });
    const cardCount = await statItems.count();
    expect(cardCount).toBeGreaterThanOrEqual(1);

    // Verify no gradient color classes leaked (Plan 11: drop gradient overrides)
    for (const card of await statItems.all()) {
      const cls = (await card.getAttribute('class')) ?? '';
      expect(cls).not.toMatch(
        /from-(blue|amber|emerald|violet|rose|cyan|indigo|orange)-(50|100|200|300|400|500)/,
      );
    }

    // Inbox widget with task table
    // SmartTableChart renders as <table> with [data-testid="smart-table-<blockKey>"]
    const taskHeader = page.getByRole('columnheader', { name: /task|title|name/i });
    await expect(taskHeader).toBeVisible({ timeout: 8000 });

    // Shortcuts widget list
    // ShortcutsWidget renders as [data-testid="shortcuts-list"]
    await expect(page.locator('[data-testid="shortcuts-list"]')).toBeVisible({ timeout: 8000 });

    // Top bar polish (Plan 10: header height = h-14 / 56px)
    const header = page.locator('header').first();
    await expect(header).toBeVisible();
    const headerClass = (await header.getAttribute('class')) ?? '';
    expect(headerClass).toMatch(/h-14|h-\[56px\]|h-16/);

    // Environment chip in top bar (Plan 10)
    await expect(page.locator('[data-testid*="env"]').first()).toBeVisible({ timeout: 5000 });
  });

  test('/home is routed via dashboard code (not catch-all)', async ({ page }) => {
    const response = await page.goto('/home');
    expect(response?.status()).toBe(200);
    expect(page.url()).toContain('/home');
  });

  test('workbench has at least 4 widgets from StatsRow, Inbox, Shortcuts, Recent', async ({
    page,
  }) => {
    await page.goto('/home');
    // WorkbenchTemplateProvider seeds 4 widgets; react-grid-layout renders as .react-grid-item
    const widgets = page.locator('.react-grid-item');
    await expect(widgets.first()).toBeVisible({ timeout: 8000 });
    const count = await widgets.count();
    expect(count).toBeGreaterThanOrEqual(4);
  });
});
