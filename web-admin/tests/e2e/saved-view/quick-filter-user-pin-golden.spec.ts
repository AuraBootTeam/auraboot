/**
 * E2E Golden: a user pins a SavedView to their quick-filter chip row (M2 — Half B).
 *
 * From the view-manage panel, the pin toggle (saved-view-action-pin-<pid>) pins
 * the view for the current user; the toolbar then shows it as a
 * quick-filter-view-<pid> chip that switches to the view on click. Unpinning
 * removes the chip. The pin flag lives in ab_saved_view_chip_pin (not the view's
 * meta), so this exercises the M2 backend + read/write frontend end to end.
 */
import { test, expect, type Page } from '@playwright/test';
import {
  cleanupGeneratedSavedViews,
  createOrReuseSavedView,
  navigateToOrderViaSidebar,
} from './helpers';

const MODEL_CODE = 'e2et_order';
const PAGE_KEY = 'e2et_order_list';

async function openPinToggle(page: Page, viewName: string, pid: string) {
  await page.getByTestId('view-selector-trigger').click();
  await expect(page.getByTestId('view-selector-search')).toBeVisible();
  await page.getByTestId('view-selector-manage').click();
  const panel = page.getByTestId('saved-view-manage-panel');
  await expect(panel).toBeVisible();
  // Narrow the list so the target view's action row is rendered.
  await panel.getByTestId('saved-view-manage-search').fill(viewName);
  return page.getByTestId(`saved-view-action-pin-${pid}`);
}

async function closeManagePanel(page: Page) {
  await page.keyboard.press('Escape');
  await page
    .getByTestId('saved-view-manage-panel')
    .waitFor({ state: 'hidden', timeout: 5000 })
    .catch(() => {});
}

test.describe('Quick-filter user pin (M2)', () => {
  test.beforeEach(async ({ page }) => {
    await cleanupGeneratedSavedViews(page, { modelCode: MODEL_CODE, pageKey: PAGE_KEY });
  });

  test.afterEach(async ({ page }) => {
    await cleanupGeneratedSavedViews(page, { modelCode: MODEL_CODE, pageKey: PAGE_KEY });
  });

  test('VC-U01: pin a view -> chip appears -> switches -> unpin -> chip gone', async ({ page }) => {
    const viewName = `VC_UserPin_${Date.now()}`;
    const { pid } = await createOrReuseSavedView(page, {
      modelCode: MODEL_CODE,
      pageKey: PAGE_KEY,
      name: viewName,
      viewType: 'table',
      scope: 'personal',
      // Distinctive config (not {rowHeight:'tall'}) so it never collides with
      // other specs' reuse keys under parallel runs.
      viewConfig: { showRowNumbers: true },
      expectSuccess: true,
    });

    await navigateToOrderViaSidebar(page);
    await expect(page.getByTestId('quick-filters')).toBeVisible({ timeout: 30000 });
    // Not pinned yet -> no chip.
    await expect(page.getByTestId(`quick-filter-view-${pid}`)).toHaveCount(0);

    // Pin from the manage panel.
    const pinBtn = await openPinToggle(page, viewName, pid);
    await expect(pinBtn).toHaveAttribute('data-pinned', 'false');
    await pinBtn.click();
    await expect(pinBtn).toHaveAttribute('data-pinned', 'true');
    await closeManagePanel(page);

    // Chip appears and switches the view on click.
    const chip = page.getByTestId(`quick-filter-view-${pid}`);
    await expect(chip).toBeVisible({ timeout: 15000 });
    await chip.click();
    await expect(page).toHaveURL(new RegExp(`view=${pid}`), { timeout: 10000 });

    // Unpin -> chip disappears.
    const pinBtn2 = await openPinToggle(page, viewName, pid);
    await expect(pinBtn2).toHaveAttribute('data-pinned', 'true');
    await pinBtn2.click();
    await expect(pinBtn2).toHaveAttribute('data-pinned', 'false');
    await closeManagePanel(page);

    await expect(page.getByTestId(`quick-filter-view-${pid}`)).toHaveCount(0);
  });
});
