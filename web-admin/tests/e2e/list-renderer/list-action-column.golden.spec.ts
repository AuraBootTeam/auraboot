/**
 * List renderer — action column & toolbar golden.
 *
 * Locks in the fixes for the "/p/page_schema 比较丑" report. Every assertion here
 * failed before the change:
 *
 *   Step 1  Action labels render on ONE line. The column was pinned at 112px while
 *           "统一设计器" needed ~130px, so the label wrapped and inflated the row.
 *   Step 2  Rows keep the medium row height (44px). The ~65px rows were never a
 *           row-height setting — they were 44px rows stretched by a wrapped button.
 *   Step 3  `inline: true` row actions are laid out inline (ux-design-system.md §3),
 *           and the rest stay behind the "⋮" trigger.
 *   Step 4  An unfiltered view renders NO filter-chip strip; the add-filter entry
 *           point lives in the toolbar instead.
 *   Step 5  The same holds on /meta/models — a second ListPageContent consumer, so the
 *           fix must be renderer-wide, not page-specific.
 *   Step 6  Zero product-level console errors.
 *
 * Prereqs: host-first stack up (scripts/oss-golden-stack.sh up <name>), env exported.
 *   PW_SKIP_WEBSERVER=1 npx playwright test -c playwright.config.ts \
 *     --project chromium tests/e2e/list-renderer/list-action-column.golden.spec.ts
 */

import { test, expect } from '../../fixtures';
import type { Page } from '@playwright/test';

/** savedView.ts ROW_HEIGHT_CONFIG: medium = 44px. `tall` (the next notch) is 60px. */
const MEDIUM_ROW_HEIGHT = 44;
/** Row borders add a pixel or two; anything near `tall` means something stretched the row. */
const ROW_HEIGHT_TOLERANCE = 6;

/**
 * Pre-existing on /meta/models, independently of this change: verified on a checkout
 * without it (worktree designer-wysiwyg-preview) — /meta/models logs this hydration
 * warning there too, while /p/page_schema logs nothing on either. Allow-listed rather
 * than dropped so any NEW console error still fails the golden. Not ours to fix here.
 */
const KNOWN_PRE_EXISTING = /A tree hydrated but some attributes of the server rendered HTML/;

function collectConsoleErrors(page: Page, { allowPreExisting = false } = {}): string[] {
  const errors: string[] = [];
  page.on('console', (msg) => {
    if (msg.type() !== 'error') return;
    const text = msg.text();
    // Ignore transport noise that is not a product defect.
    if (/favicon|net::ERR_ABORTED|Failed to load resource/i.test(text)) return;
    if (allowPreExisting && KNOWN_PRE_EXISTING.test(text)) return;
    errors.push(text);
  });
  return errors;
}

/**
 * The first row that actually holds a record. `tbody tr` alone also matches the
 * loading and empty-state placeholder rows, whose heights are meaningless here —
 * measuring those produced phantom 73px/149px "regressions".
 */
async function firstDataRow(page: Page) {
  const rowAction = page.locator('tbody tr [data-testid^="row-action-"]').first();
  await expect(rowAction).toBeVisible({ timeout: 30_000 });
  return page
    .locator('tbody tr')
    .filter({ has: page.locator('[data-testid^="row-action-"]') })
    .first();
}

test.describe('List renderer — action column & toolbar', () => {
  test('page_schema: action labels never wrap and rows stay at medium height', async ({ page }) => {
    const consoleErrors = collectConsoleErrors(page);

    await page.goto('/p/page_schema');
    const row = await firstDataRow(page);

    // --- Step 1: the inline action label renders on a single line ---------------
    const designerBtn = page.getByTestId('row-action-edit_unified').first();
    await expect(designerBtn).toBeVisible();

    const metrics = await designerBtn.evaluate((el) => ({
      scrollWidth: el.scrollWidth,
      clientWidth: el.clientWidth,
      height: el.getBoundingClientRect().height,
      whiteSpace: getComputedStyle(el).whiteSpace,
    }));
    // The regression was the label folding onto a second line inside the button.
    expect(metrics.whiteSpace).toBe('nowrap');
    // Column is sized to the label, so it is not even clipped (no ellipsis needed).
    expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.clientWidth + 1);
    // A single line of text-sm is ~20px + py-1 → ~28px. Two lines would be ~48px.
    expect(metrics.height).toBeLessThan(40);

    // --- Step 2: the row is back to the medium row height -----------------------
    const rowHeight = await row.evaluate((el) => el.getBoundingClientRect().height);
    expect(rowHeight).toBeLessThanOrEqual(MEDIUM_ROW_HEIGHT + ROW_HEIGHT_TOLERANCE);

    // --- Step 3: `inline: true` actions are laid out inline, rest collapse -------
    // page-manager marks edit_unified + publish inline; edit_legacy/archive/... do not.
    await expect(page.getByTestId('row-action-more').first()).toBeVisible();
    await expect(page.getByTestId('row-action-edit_legacy')).toHaveCount(0);

    // --- Step 4: an unfiltered view has no chip strip; toolbar owns add-filter ---
    await expect(page.getByTestId('add-filter-btn')).toBeVisible();
    await expect(page.locator('[data-testid="filter-chip-bar"]')).toHaveCount(0);

    await page.screenshot({
      path: 'test-results/artifacts/list-golden-page-schema.png',
      fullPage: true,
    });

    // --- Step 6 -----------------------------------------------------------------
    expect(consoleErrors, `console errors: ${consoleErrors.join('\n')}`).toHaveLength(0);
  });

  test('meta/models: the shared list renderer is not regressed', async ({ page }) => {
    // A second consumer of ListPageContent, reached through a hand-written tsx route
    // rather than /p/:pageKey, and populated by the platform's own metadata — so it
    // exercises the shared renderer on real rows without a showcase seed.
    const consoleErrors = collectConsoleErrors(page, { allowPreExisting: true });

    await page.goto('/meta/models');
    const row = await firstDataRow(page);

    // No action label anywhere on the page may wrap — the invariant is renderer-wide,
    // not specific to page_schema.
    const wrapped = await row.evaluate((tr) => {
      const buttons = Array.from(tr.querySelectorAll('[data-testid^="row-action-"]'));
      return buttons
        .filter((el) => el.scrollWidth > el.clientWidth + 1)
        .map((el) => el.textContent);
    });
    expect(wrapped, `wrapped action labels: ${wrapped.join(', ')}`).toHaveLength(0);

    // No row-height assertion here on purpose: this page's first column stacks the
    // model code above a source chip, so its rows are legitimately taller than 44px.
    // "Rows are 44px" is a property of single-line content, not of the renderer;
    // "action labels never wrap" is the renderer-wide invariant, asserted above.

    // This page declares a single row action, so there is no "⋮" trigger to assert on.
    // The default 1-inline + overflow contract that 33 E2E specs depend on is covered by
    // RowActionButtons' unit tests and by the page_schema golden above.

    // The toolbar changes reach this consumer too (it does not go through /p/:pageKey).
    await expect(page.getByTestId('add-filter-btn')).toBeVisible();
    await expect(page.locator('[data-testid="filter-chip-bar"]')).toHaveCount(0);

    await page.screenshot({
      path: 'test-results/artifacts/list-golden-meta-models.png',
      fullPage: true,
    });

    expect(consoleErrors, `console errors: ${consoleErrors.join('\n')}`).toHaveLength(0);
  });
});
