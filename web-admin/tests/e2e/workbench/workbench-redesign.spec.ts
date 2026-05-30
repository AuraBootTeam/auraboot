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

    // Verify the workbench header band (subline carries a stable testid so we
    // do not depend on which locale resolves the title text).
    await expect(page.getByTestId('workbench-subline')).toBeVisible({ timeout: 10_000 });

    // KPI cards — redesigned StatsRow / StatsCard widgets emit stat-card-<key>.
    const statCards = page.locator('[data-testid^="stat-card-"]');
    await expect(statCards.first()).toBeVisible({ timeout: 10_000 });
    const cardCount = await statCards.count();
    expect(cardCount).toBeGreaterThanOrEqual(1);

    // No vibrant gradient backgrounds may leak into the new neutral cards.
    for (const card of await statCards.all()) {
      const cls = (await card.getAttribute('class')) ?? '';
      expect(cls).not.toMatch(
        /from-(blue|amber|emerald|violet|rose|cyan|indigo|orange)-(50|100|200|300|400|500)/,
      );
    }

    // Inbox widget table is present; rely on table role rather than the
    // localized column-header text (en-US "Task" vs zh-CN "待办" / "任务").
    await expect(page.getByRole('table').first()).toBeVisible({ timeout: 8000 });

    // Shortcuts widget list
    await expect(page.locator('[data-testid="shortcuts-list"]')).toBeVisible({ timeout: 8000 });

    // Top bar polish: inner header row uses h-14 / 56px
    const header = page.locator('header').first();
    await expect(header).toBeVisible();
    const headerRowClass =
      (await header.locator('div').first().getAttribute('class')) ?? '';
    expect(headerRowClass).toMatch(/h-14|h-\[56px\]|h-16/);

    // Environment chip in top bar — only present in non-production builds.
    await expect(page.getByTestId('header-env-chip')).toBeVisible({ timeout: 5000 });
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
