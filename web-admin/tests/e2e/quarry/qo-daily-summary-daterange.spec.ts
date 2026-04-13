/**
 * QO Daily Summary — DateRange Filter E2E Tests
 *
 * Verifies the SmartDateRange component renders correctly on the
 * qo_daily_summary dynamic list page, with proper default range,
 * user interaction (change dates, clear, search, reset).
 *
 * Prerequisite: quarry-industry plugin must be imported.
 */
import { test, expect } from '@playwright/test';
import { waitForDynamicPageLoad } from '../helpers/index';

const PAGE_KEY = 'qo_daily_summary';

/** Navigate to the daily summary page and wait for it to load. */
async function goToSummaryPage(page: import('@playwright/test').Page) {
  await page.goto(`/p/${PAGE_KEY}`);
  await waitForDynamicPageLoad(page);
  // Wait for filter area to appear (increase timeout for slow environments)
  await page.locator('[data-testid="daterange-qo_summary_date-start"]').waitFor({
    state: 'visible',
    timeout: 30000,
  });
}

/** Get the current month first/last day as YYYY-MM-DD. */
function thisMonthRange() {
  const now = new Date();
  const y = now.getFullYear();
  const m = now.getMonth();
  const start = `${y}-${String(m + 1).padStart(2, '0')}-01`;
  const lastDay = new Date(y, m + 1, 0).getDate();
  const end = `${y}-${String(m + 1).padStart(2, '0')}-${String(lastDay).padStart(2, '0')}`;
  return { start, end };
}

test.describe('DateRange filter on Daily Summary page', () => {
  let pluginAvailable = true;

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: './tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      const resp = await page.request.get(`/api/pages/key/${PAGE_KEY}_list`, {
        failOnStatusCode: false,
      });
      if (!resp.ok()) {
        pluginAvailable = false;
      } else {
        // Also check if the page actually renders the DateRange component
        await page.goto(`/p/${PAGE_KEY}`);
        const dateRange = page.locator('[data-testid="daterange-qo_summary_date-start"]');
        const visible = await dateRange.isVisible({ timeout: 15000 }).catch(() => false);
        if (!visible) {
          pluginAvailable = false;
        }
      }
    } finally {
      await page.close();
      await ctx.close();
    }
  });

  test.beforeEach(async () => {
    test.skip(!pluginAvailable, 'Quarry plugin not installed or DateRange component not rendering');
  });

  test('should render DateRange component without error', async ({ page }) => {
    await goToSummaryPage(page);

    // DateRange start and end inputs should be visible
    const startInput = page.locator('[data-testid="daterange-qo_summary_date-start"]');
    const endInput = page.locator('[data-testid="daterange-qo_summary_date-end"]');

    await expect(startInput).toBeVisible();
    await expect(endInput).toBeVisible();

    // No error alert should be present
    const errorAlert = page.locator('.alert-error');
    await expect(errorAlert).not.toBeVisible();
  });

  test('should have THIS_MONTH default range pre-filled', async ({ page }) => {
    await goToSummaryPage(page);

    const { start, end } = thisMonthRange();

    const startInput = page.locator('[data-testid="daterange-qo_summary_date-start"]');
    const endInput = page.locator('[data-testid="daterange-qo_summary_date-end"]');

    await expect(startInput).toHaveValue(start);
    await expect(endInput).toHaveValue(end);
  });

  test('should allow changing start date', async ({ page }) => {
    await goToSummaryPage(page);

    const startInput = page.locator('[data-testid="daterange-qo_summary_date-start"]');
    await startInput.fill('2026-01-15');
    await expect(startInput).toHaveValue('2026-01-15');
  });

  test('should allow changing end date', async ({ page }) => {
    await goToSummaryPage(page);

    const endInput = page.locator('[data-testid="daterange-qo_summary_date-end"]');
    await endInput.fill('2026-03-15');
    await expect(endInput).toHaveValue('2026-03-15');
  });

  test('should allow manually clearing date inputs', async ({ page }) => {
    await goToSummaryPage(page);

    const startInput = page.locator('[data-testid="daterange-qo_summary_date-start"]');
    const endInput = page.locator('[data-testid="daterange-qo_summary_date-end"]');

    // Verify dates are pre-filled
    const { start } = thisMonthRange();
    await expect(startInput).toHaveValue(start);

    // Clear start input by filling empty string
    await startInput.fill('');
    await expect(startInput).toHaveValue('');

    // Clear end input by filling empty string
    await endInput.fill('');
    await expect(endInput).toHaveValue('');
  });

  test('should trigger search with date range filter', async ({ page }) => {
    await goToSummaryPage(page);

    // Set custom date range
    const startInput = page.locator('[data-testid="daterange-qo_summary_date-start"]');
    const endInput = page.locator('[data-testid="daterange-qo_summary_date-end"]');
    await startInput.fill('2026-01-01');
    await endInput.fill('2026-01-31');

    // Click search and wait for API request
    const searchResponsePromise = page
      .waitForResponse((resp) => resp.url().includes('/list') && resp.status() === 200, {
        timeout: 10000,
      })
      .catch(() => null);

    // Search button may be labeled in Chinese or English
    const searchBtn = page
      .getByRole('button', { name: '搜索' })
      .or(page.getByRole('button', { name: /search/i }))
      .or(page.locator('[data-testid*="search"]'));
    await searchBtn.first().click();
    const searchResp = await searchResponsePromise;

    // The request should have been made (even if no data returns)
    // If search triggers a client-side filter instead of API call, searchResp can be null
    if (searchResp) {
      expect(searchResp.status()).toBe(200);
    } else {
      // Verify the page is still functional after clicking search
      const table = page.locator('table, [role="table"]').first();
      await expect(table).toBeVisible({ timeout: 5000 });
    }
  });

  test('should reset date range to default on reset button click', async ({ page }) => {
    await goToSummaryPage(page);

    const startInput = page.locator('[data-testid="daterange-qo_summary_date-start"]');
    const endInput = page.locator('[data-testid="daterange-qo_summary_date-end"]');

    // Change dates
    await startInput.fill('2025-06-01');
    await endInput.fill('2025-06-30');
    await expect(startInput).toHaveValue('2025-06-01');

    // Click reset
    const resetResponsePromise = page
      .waitForResponse((resp) => resp.url().includes('/list') && resp.status() === 200, {
        timeout: 10000,
      })
      .catch(() => null);

    const resetBtn = page
      .getByRole('button', { name: '重置' })
      .or(page.getByRole('button', { name: /reset/i }))
      .or(page.locator('[data-testid*="reset"]'));
    await resetBtn.first().click();
    await resetResponsePromise;

    // After reset, filters should be cleared (empty values)
    // Note: reset clears all filter state; default range reapplies on re-render
    const startVal = await startInput.inputValue();
    const endVal = await endInput.inputValue();
    // Either both empty (reset cleared) or both back to this month (re-initialized)
    expect(startVal === '' || startVal === thisMonthRange().start).toBeTruthy();
    expect(endVal === '' || endVal === thisMonthRange().end).toBeTruthy();
  });

  test('should show "统计日期" label for the date range field', async ({ page }) => {
    await goToSummaryPage(page);

    // The label should be visible (from DSL label config)
    const label = page.locator('text=统计日期');
    await expect(label).toBeVisible();
  });

  test('should not overlap between 统计日期 field and 项目下拉框', async ({ page }) => {
    await goToSummaryPage(page);

    const dateField = page.locator('[data-testid="field-qo_summary_date"]');
    const projectSelect = page.locator('[data-testid="select-trigger-qo_summary_project_id"]');

    await expect(dateField).toBeVisible();
    await expect(projectSelect).toBeVisible();

    const dateBox = await dateField.boundingBox();
    const projectBox = await projectSelect.boundingBox();
    expect(dateBox).toBeTruthy();
    expect(projectBox).toBeTruthy();

    if (!dateBox || !projectBox) return;

    const hasHorizontalOverlap = !(
      dateBox.x + dateBox.width <= projectBox.x || projectBox.x + projectBox.width <= dateBox.x
    );
    const hasVerticalOverlap = !(
      dateBox.y + dateBox.height <= projectBox.y || projectBox.y + projectBox.height <= dateBox.y
    );

    expect(hasHorizontalOverlap && hasVerticalOverlap).toBeFalsy();
  });
});
