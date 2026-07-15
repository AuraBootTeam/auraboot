/**
 * E2E Golden: pinned SavedView appears as a quick-filter chip and switches the
 * view on click (M1 — Half A).
 *
 * A view with viewConfig.meta.pinnedAsQuickFilter === true surfaces as a
 * one-click chip in the list toolbar (data-testid quick-filter-view-<pid>).
 * Clicking it switches to that SavedView (URL view=<pid> + the view selector
 * reflects the view name) — i.e. a view chip does a full view switch, not a
 * bare filter apply. A view WITHOUT the flag must not produce a chip (the chip
 * exists BECAUSE of the flag — a falsifiable assertion).
 */
import { test, expect } from '@playwright/test';
import { openViewSelectorDropdown } from '../helpers';
import {
  cleanupGeneratedSavedViews,
  createOrReuseSavedView,
  navigateToOrderViaSidebar,
} from './helpers';

const MODEL_CODE = 'e2et_order';
const PAGE_KEY = 'e2et_order_list';

test.describe('Quick-filter view chip (M1)', () => {
  test.beforeEach(async ({ page }) => {
    await cleanupGeneratedSavedViews(page, { modelCode: MODEL_CODE, pageKey: PAGE_KEY });
  });

  test.afterEach(async ({ page }) => {
    await cleanupGeneratedSavedViews(page, { modelCode: MODEL_CODE, pageKey: PAGE_KEY });
  });

  async function gotoDefaultView(page: import('@playwright/test').Page) {
    await navigateToOrderViaSidebar(page);
    const dropdown = await openViewSelectorDropdown(page);
    await dropdown.getByTestId('view-option-default').click();
    await dropdown.waitFor({ state: 'hidden', timeout: 5000 }).catch(() => {});
    await expect(page.getByTestId('quick-filters')).toBeVisible({ timeout: 30000 });
  }

  test('VC-001: a pinned view shows as a chip and switches the view on click', async ({ page }) => {
    // VC_ prefix so cleanupGeneratedSavedViews treats it as generated (test isolation).
    const viewName = `VC_Pinned_${Date.now()}`;
    const { pid } = await createOrReuseSavedView(page, {
      modelCode: MODEL_CODE,
      pageKey: PAGE_KEY,
      name: viewName,
      viewType: 'table',
      scope: 'personal',
      // The pin flag + a distinguishing rowHeight so we can prove the view switched.
      viewConfig: {
        rowHeight: 'tall',
        meta: { pinnedAsQuickFilter: true, quickFilterIcon: '📦' },
      },
      expectSuccess: true,
    });

    await gotoDefaultView(page);

    const viewChip = page.getByTestId(`quick-filter-view-${pid}`);
    await expect(viewChip).toBeVisible({ timeout: 30000 });
    await expect(viewChip).toHaveAttribute('data-chip-kind', 'view');
    await expect(viewChip).toContainText(viewName);
    // Not the current view yet.
    await expect(viewChip).toHaveAttribute('data-preset-active', 'false');

    // Click the chip -> switch to the SavedView (full switch, not a filter apply).
    await viewChip.click();
    await expect(page).toHaveURL(new RegExp(`view=${pid}`), { timeout: 10000 });
    await expect(page.getByTestId('view-selector-trigger')).toHaveAttribute(
      'data-current-view-name',
      viewName,
    );
    // The chip is now the active one.
    await expect(viewChip).toHaveAttribute('data-preset-active', 'true');
  });

  test('VC-002: a view without the pin flag does NOT produce a chip', async ({ page }) => {
    const viewName = `VC_Unpinned_${Date.now()}`;
    const { pid } = await createOrReuseSavedView(page, {
      modelCode: MODEL_CODE,
      pageKey: PAGE_KEY,
      name: viewName,
      viewType: 'table',
      scope: 'personal',
      viewConfig: { rowHeight: 'short' },
      expectSuccess: true,
    });

    await gotoDefaultView(page);

    // Built-in preset chips are present, but no view chip for this unpinned view.
    await expect(page.getByTestId('quick-filter-my_records')).toBeVisible({ timeout: 30000 });
    await expect(page.getByTestId(`quick-filter-view-${pid}`)).toHaveCount(0);
  });
});
