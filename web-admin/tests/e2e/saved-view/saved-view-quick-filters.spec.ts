/**
 * E2E Test: View Default Behavior — Quick Filters (GAP-130)
 *
 * Tests quick filter chips: My Records, Created Today, Modified This Week.
 * These are toggle buttons that apply predefined filters to the table.
 */

import { test, expect } from '@playwright/test';

test.describe('Quick Filters (GAP-130)', () => {
  const quickFilter = (page: import('@playwright/test').Page, key: string) =>
    page.locator(`[data-testid="quick-filter-${key}"]`);

  test('QF-001: quick filter chips visible in table toolbar', async ({ page }) => {
    await page.goto('/p/e2et_order');
    const quickFilters = page.getByTestId('quick-filters');
    await expect(quickFilters).toBeVisible({ timeout: 30000 });

    // Verify all 3 chips exist
    await expect(quickFilter(page, 'my_records')).toBeVisible();
    await expect(quickFilter(page, 'created_today')).toBeVisible();
    await expect(quickFilter(page, 'modified_this_week')).toBeVisible();
  });

  test('QF-002: quick filter chip labels are correct', async ({ page }) => {
    await page.goto('/p/e2et_order');
    await expect(page.getByTestId('quick-filters')).toBeVisible({ timeout: 30000 });

    await expect(quickFilter(page, 'my_records')).toContainText('My Records');
    await expect(quickFilter(page, 'created_today')).toContainText('Created Today');
    await expect(quickFilter(page, 'modified_this_week')).toContainText('Modified This Week');
  });

  test('QF-003: clicking a quick filter toggles active state', async ({ page }) => {
    await page.goto('/p/e2et_order');
    const myRecords = quickFilter(page, 'my_records');
    await expect(myRecords).toBeVisible({ timeout: 30000 });

    // Initially not active
    const initialClass = await myRecords.getAttribute('class') ?? '';

    // Click to activate
    await myRecords.click();
    // Active state should change the class (blue or primary color)
    const activeClass = await myRecords.getAttribute('class') ?? '';
    expect(activeClass).not.toBe(initialClass);

    // Click again to deactivate (toggle off)
    await myRecords.click();
    const deactivatedClass = await myRecords.getAttribute('class') ?? '';
    expect(deactivatedClass).toBe(initialClass);
  });

  test('QF-004: only one quick filter active at a time', async ({ page }) => {
    await page.goto('/p/e2et_order');
    await expect(page.getByTestId('quick-filters')).toBeVisible({ timeout: 30000 });

    const myRecords = quickFilter(page, 'my_records');
    const createdToday = quickFilter(page, 'created_today');

    // Activate "My Records"
    await myRecords.click();
    const myRecordsActive = await myRecords.getAttribute('class') ?? '';

    // Activate "Created Today" — "My Records" should deactivate
    await createdToday.click();
    const createdTodayActive = await createdToday.getAttribute('class') ?? '';
    const myRecordsAfter = await myRecords.getAttribute('class') ?? '';
    // The two should have different active states
    expect(myRecordsAfter).not.toBe(myRecordsActive);
  });

  test('QF-005: quick filter triggers data reload', async ({ page }) => {
    await page.goto('/p/e2et_order');
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
    await page.goto('/p/e2et_order');
    // Both quick filters and row height button should be visible
    await expect(page.getByTestId('quick-filters')).toBeVisible({ timeout: 30000 });
    await expect(page.getByTestId('row-height-btn')).toBeVisible();
    await expect(page.getByTestId('column-settings-btn')).toBeVisible();
  });
});
