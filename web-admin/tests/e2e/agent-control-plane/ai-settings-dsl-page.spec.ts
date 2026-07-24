/**
 * Golden for the AI settings hub as a DSL page (Gap 1, slice 1).
 *
 * The old hand-written `pages/ai/settings.tsx` (a static React card grid) is replaced by a
 * DSL `card-grid` page (`ai_settings_hub`, kind:detail) driven by a static dataSource. Each
 * card's "open" action navigates to that row's own `target` via `to: "{target}"`, which the
 * platform's `resolvePageTargetPath` now resolves per-row (leading-placeholder enhancement).
 *
 * Proves: the DSL page renders all six settings cards, and a card actually navigates to its
 * distinct target — i.e. the per-row navigation the conversion depends on works end to end.
 */
import { test, expect } from '@playwright/test';

test.describe('AI settings hub — DSL card-grid page', () => {
  test('renders the six settings cards and a card navigates to its own target', async ({ page }) => {
    await page.goto('/p/c/ai_settings_hub', { waitUntil: 'domcontentloaded' });

    const grid = page.locator('[data-testid="card-grid-block"]');
    await expect(grid, 'the DSL card-grid must render (not the old React page)').toBeVisible({
      timeout: 20_000,
    });

    const cards = page.locator('[data-testid="card-grid-card"]');
    await expect(cards, 'all six settings items render as cards').toHaveCount(6);

    // Localized title proves the static dataSource rows and titleField resolve.
    await expect(grid).toContainText(/LLM Providers|大模型服务商/);

    // The first card ("LLM Providers") targets /aurabot/providers — clicking its action must
    // navigate there, proving per-row {target} resolution (not a single shared static path).
    await grid.locator('[data-testid="card-grid-action-open"]').first().click();
    await expect(page).toHaveURL(/\/aurabot\/providers/, { timeout: 15_000 });
  });
});
