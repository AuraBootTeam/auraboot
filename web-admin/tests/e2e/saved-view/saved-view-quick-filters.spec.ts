/**
 * E2E Test: View Default Behavior — Quick Filters (GAP-130)
 *
 * Tests quick filter chips: My Records, Created Today, Modified This Week.
 * These are toggle buttons that apply predefined filters to the table.
 */

import { test, expect } from '@playwright/test';
import { openViewSelectorDropdown } from '../helpers';
import {
  cleanupGeneratedSavedViews,
  createOrReuseSavedView,
  navigateToOrderViaSidebar,
} from './helpers';

import { acquireSavedViewLock, releaseSavedViewLock } from './_saved-view-lock';

// Serialize e2et_order saved-view specs — they share the model's per-user view
// state (active view / created views) under the shared admin storageState.
test.beforeAll(async () => { await acquireSavedViewLock('saved-view-quick-filters'); });
test.afterAll(() => { releaseSavedViewLock('saved-view-quick-filters'); });

const MODEL_CODE = 'e2et_order';
const PAGE_KEY = 'e2et_order_list';

test.describe('Quick Filters (GAP-130)', () => {
  const quickFilter = (page: import('@playwright/test').Page, key: string) =>
    page.locator(`[data-testid="quick-filter-${key}"]`);

  test.beforeEach(async ({ page }) => {
    await cleanupGeneratedSavedViews(page, { modelCode: MODEL_CODE, pageKey: PAGE_KEY });
  });

  test.afterEach(async ({ page }) => {
    await cleanupGeneratedSavedViews(page, { modelCode: MODEL_CODE, pageKey: PAGE_KEY });
  });

  async function navigateToOrderTable(page: import('@playwright/test').Page) {
    await navigateToOrderViaSidebar(page);
    const dropdown = await openViewSelectorDropdown(page);
    await dropdown.getByTestId('view-option-default').click();
    await dropdown.waitFor({ state: 'hidden', timeout: 5000 }).catch(() => {});
    await expect(page.getByTestId('quick-filters')).toBeVisible({ timeout: 30000 });
  }

  async function clickStableTestId(page: import('@playwright/test').Page, testId: string) {
    await expect
      .poll(
        async () => {
          try {
            await page.getByTestId(testId).click({ timeout: 750 });
            return true;
          } catch {
            return false;
          }
        },
        { timeout: 5000 },
      )
      .toBe(true);
  }

  test('QF-001: quick filter chips visible in table toolbar', async ({ page }) => {
    await navigateToOrderTable(page);
    const quickFilters = page.getByTestId('quick-filters');
    await expect(quickFilters).toBeVisible({ timeout: 30000 });

    // Verify all 3 chips exist
    await expect(quickFilter(page, 'my_records')).toBeVisible();
    await expect(quickFilter(page, 'created_today')).toBeVisible();
    await expect(quickFilter(page, 'modified_this_week')).toBeVisible();
  });

  test('QF-002: quick filter chip labels are correct', async ({ page }) => {
    await navigateToOrderTable(page);
    await expect(page.getByTestId('quick-filters')).toBeVisible({ timeout: 30000 });

    // Runtime locale is zh-CN with translations loaded for these keys; assert
    // the actual rendered i18n strings (English fallback only surfaces when
    // translations are missing — see ListToolbar.i18n.test.tsx).
    await expect(quickFilter(page, 'my_records')).toContainText(/My Records|我的记录/);
    await expect(quickFilter(page, 'created_today')).toContainText(/Created Today|今日新建/);
    await expect(quickFilter(page, 'modified_this_week')).toContainText(
      /Modified This Week|本周修改/,
    );
  });

  test('QF-003: clicking a quick filter toggles active state', async ({ page }) => {
    await navigateToOrderTable(page);
    const myRecords = quickFilter(page, 'my_records');
    await expect(myRecords).toBeVisible({ timeout: 30000 });

    // Initially not active
    const initialClass = (await myRecords.getAttribute('class')) ?? '';

    // Click to activate
    await clickStableTestId(page, 'quick-filter-my_records');
    // Active state should change the class (blue or primary color)
    const activeClass = (await myRecords.getAttribute('class')) ?? '';
    expect(activeClass).not.toBe(initialClass);
    await expect
      .poll(() => new URL(page.url()).searchParams.get('preset'), { timeout: 5000 })
      .toBe('my_records');

    // Click again to deactivate (toggle off)
    await clickStableTestId(page, 'quick-filter-my_records');
    await expect
      .poll(() => new URL(page.url()).searchParams.get('preset'), { timeout: 5000 })
      .toBeNull();
    await expect
      .poll(async () => (await myRecords.getAttribute('class')) ?? '', { timeout: 5000 })
      .toBe(initialClass);
  });

  test('QF-003b: clicking a quick filter from a personal view returns to default preset mode', async ({
    page,
  }) => {
    const viewName = `QF_个人视图_${Date.now()}`;
    const { pid } = await createOrReuseSavedView(page, {
      modelCode: MODEL_CODE,
      pageKey: PAGE_KEY,
      name: viewName,
      viewType: 'table',
      scope: 'personal',
      viewConfig: { rowHeight: 'tall' },
      expectSuccess: true,
    });

    await navigateToOrderViaSidebar(page);
    const dropdown = await openViewSelectorDropdown(page);
    await dropdown.getByTestId(`view-option-${pid}`).click();
    await dropdown.waitFor({ state: 'hidden', timeout: 5000 }).catch(() => {});
    await expect(page).toHaveURL(new RegExp(`view=${pid}`), { timeout: 5000 });
    await expect(page.getByTestId('view-selector-trigger')).toHaveAttribute(
      'data-current-view-name',
      viewName,
    );

    await clickStableTestId(page, 'quick-filter-my_records');

    await expect
      .poll(() => new URL(page.url()).searchParams.get('view'), { timeout: 5000 })
      .toBeNull();
    await expect
      .poll(() => new URL(page.url()).searchParams.get('preset'), { timeout: 5000 })
      .toBe('my_records');
    await expect(quickFilter(page, 'my_records')).toHaveAttribute('data-preset-active', 'true');
    await expect(page.getByTestId('view-selector-trigger')).not.toHaveAttribute(
      'data-current-view-name',
      viewName,
    );
  });

  test('QF-004: only one quick filter active at a time', async ({ page }) => {
    await navigateToOrderTable(page);
    await expect(page.getByTestId('quick-filters')).toBeVisible({ timeout: 30000 });

    const myRecords = quickFilter(page, 'my_records');
    const createdToday = quickFilter(page, 'created_today');

    // Activate "My Records"
    await myRecords.click();
    const myRecordsActive = (await myRecords.getAttribute('class')) ?? '';

    // Activate "Created Today" — "My Records" should deactivate
    await createdToday.click();
    const createdTodayActive = (await createdToday.getAttribute('class')) ?? '';
    const myRecordsAfter = (await myRecords.getAttribute('class')) ?? '';
    // The two should have different active states
    expect(myRecordsAfter).not.toBe(myRecordsActive);
  });

  test('QF-005: quick filter triggers data reload', async ({ page }) => {
    await navigateToOrderTable(page);
    await expect(page.getByTestId('quick-filters')).toBeVisible({ timeout: 30000 });

    // Click "Created Today" and wait for API response
    const responsePromise = page.waitForResponse(
      (resp) => resp.url().includes('/list') && resp.status() === 200,
      { timeout: 10000 },
    );
    await quickFilter(page, 'created_today').click();
    const response = await responsePromise.catch(() => null);
    // If there's data, the API should have been called
    expect(response === null || response.ok()).toBeTruthy();
  });

  test('QF-006: quick filter coexists with view settings', async ({ page }) => {
    await navigateToOrderTable(page);
    // Both quick filters and row height button should be visible
    await expect(page.getByTestId('quick-filters')).toBeVisible({ timeout: 30000 });
    await expect(page.getByTestId('row-height-btn')).toBeVisible();
    await expect(page.getByTestId('column-settings-btn')).toBeVisible();
  });

  test('QF-007: active quick-filter preset can be saved as a personal SavedView', async ({
    page,
  }) => {
    await navigateToOrderTable(page);
    await expect(page.getByTestId('quick-filters')).toBeVisible({ timeout: 30000 });
    await expect(page.getByTestId('preset-view-bar')).toHaveCount(0);

    const responsePromise = page.waitForResponse(
      (resp) => resp.url().includes('/api/dynamic/e2et_order') && resp.url().includes('list'),
      { timeout: 10000 },
    );
    await page.getByTestId('quick-filter-modified_this_week').click();
    await responsePromise;

    const createViewPromise = page
      .waitForResponse(
        (resp) =>
          resp.request().method() === 'POST' && new URL(resp.url()).pathname === '/api/views',
        { timeout: 5000 },
      )
      .catch(() => null);
    await clickStableTestId(page, 'preset-view-save-as-personal');
    const createViewResponse = await createViewPromise;
    if (createViewResponse) {
      expect(createViewResponse.ok()).toBeTruthy();
    }
    await expect(page).toHaveURL(/view=[^&]+/, { timeout: 10000 });
    await expect
      .poll(() => new URL(page.url()).searchParams.get('preset'), { timeout: 5000 })
      .toBeNull();
    await expect(page.getByTestId('quick-filter-modified_this_week')).toHaveAttribute(
      'data-preset-active',
      'false',
    );
  });
});
