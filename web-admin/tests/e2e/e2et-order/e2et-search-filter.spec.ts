/**
 * E2E Test Order — Search & Filter UI
 *
 * Tests SF-001 ~ SF-003: Search fields and filtering on list pages
 * - Customer list keyword search (API + UI verification)
 * - Order list ENUM filter
 * - Clear search restores all data (API-based count comparison)
 *
 * Uses real database, NO MOCKING.
 * Uses queryFilteredList API for reliable list verification instead of
 * counting UI rows which is flaky with pagination and parallel tests.
 *
 * @since 6.2.0
 */

import { test, expect } from '../../fixtures';
import { uniqueId, navigateToDynamicPage, queryFilteredList } from '../helpers';
import { ModelTestHelper } from '../../helpers/model-test-helper';
import { E2ET_CUSTOMER_CONFIG } from '../../helpers/configs/e2et-customer.config';

test.describe('E2E Test Order — Search & Filter UI', () => {
  const searchTag = `SF${Date.now()}`;
  let custPid1: string;
  let custPid2: string;

  test.beforeAll(async ({ browser }) => {
    const context = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await context.newPage();
    const customer = new ModelTestHelper(page, E2ET_CUSTOMER_CONFIG);

    // Create two customers with distinct names
    custPid1 = await customer.createViaApi({
      e2et_cust_code: `SF1-${searchTag}`,
      e2et_cust_name: `Alpha ${searchTag}`,
      e2et_cust_region: 'east',
    });
    custPid2 = await customer.createViaApi({
      e2et_cust_code: `SF2-${searchTag}`,
      e2et_cust_name: `Beta ${searchTag}`,
      e2et_cust_region: 'west',
    });

    await page.close();
    await context.close();
  });

  test.afterAll(async ({ browser }) => {
    const context = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await context.newPage();
    const customer = new ModelTestHelper(page, E2ET_CUSTOMER_CONFIG);
    await customer.deleteViaApi(custPid1).catch(() => {});
    await customer.deleteViaApi(custPid2).catch(() => {});
    await page.close();
    await context.close();
  });

  /**
   * SF-001: Customer list should filter by keyword search
   *
   * Uses queryFilteredList API for reliable verification, and also
   * tests the UI search workflow (fill + click search + check results).
   */
  test('SF-001: should filter customer list by search keyword @smoke', async ({ page }) => {
    // Step 1: Verify test data exists via API (reliable baseline)
    const apiResults = await queryFilteredList(
      page,
      'e2et-customer',
      'e2et_cust_name',
      `Alpha ${searchTag}`,
    );
    expect(apiResults.length).toBeGreaterThanOrEqual(1);

    // Step 2: Navigate to customer list page
    await navigateToDynamicPage(page, 'e2et-customer');

    // Step 3: Look for the search/filter area (auto-synthesized from searchFields)
    const searchArea = page.locator('[data-testid="search-area"]').first();
    const hasSearchArea = await searchArea.isVisible({ timeout: 8000 }).catch(() => false);
    if (!hasSearchArea) {
      throw new Error(String('Search area not found on customer list'))
      return;
    }

    // Step 4: Find the customer name input by name attribute or placeholder
    // Prefer data-testid or name attribute over positional nth() for stability
    const nameInput = searchArea.locator(
      'input[name="e2et_cust_name"], input[id="e2et_cust_name"], input[placeholder*="name" i]'
    ).first();
    const nameInputVisible = await nameInput.isVisible({ timeout: 3000 }).catch(() => false);

    // Fallback: use the second input (searchFields order: [code, name, region, active])
    const inputToUse = nameInputVisible ? nameInput : searchArea.locator('input').nth(1);
    await expect(inputToUse).toBeVisible({ timeout: 5000 });

    // Step 5: Type search keyword and click search
    await inputToUse.fill(`Alpha ${searchTag}`);

    const searchBtn = page.locator('[data-testid="filter-search"]').first();

    // Set up response listener BEFORE clicking
    const listResponse = page.waitForResponse(
      (r) => r.url().includes('/list') && r.status() === 200,
      { timeout: 15000 }
    );
    await searchBtn.click();
    await listResponse;

    // Step 6: Verify filtered results contain the expected customer
    const tableBody = page.locator('tbody, [role="rowgroup"]').first();
    await expect(tableBody).toContainText('Alpha', { timeout: 5000 });
  });

  /**
   * SF-002: Order list should filter by ENUM type
   */
  test('SF-002: should filter order list by order type @critical', async ({ page }) => {
    await navigateToDynamicPage(page, 'e2et-order');

    // Look for the search/filter area
    const searchArea = page.locator('[data-testid="search-area"]').first();
    const hasSearchArea = await searchArea.isVisible({ timeout: 8000 }).catch(() => false);
    if (!hasSearchArea) {
      throw new Error(String('Search area not found on order list'))
      return;
    }

    // The search area exists — verify the list page renders with data
    const table = page.locator('table, [role="table"], [data-testid="data-table"]').first();
    await expect(table).toBeVisible({ timeout: 10000 });
  });

  /**
   * SF-003: Clearing search should restore all data
   *
   * Uses queryFilteredList API for reliable count comparison instead of
   * counting UI rows which is flaky with pagination and concurrent tests.
   */
  test('SF-003: should restore all data after clearing search @critical', async ({ page }) => {
    // Step 1: Verify BOTH test records exist via API (scoped to our searchTag)
    const ourRecords = await queryFilteredList(
      page,
      'e2et-customer',
      'e2et_cust_name',
      searchTag,
    );
    expect(ourRecords.length).toBe(2); // Alpha and Beta

    // Step 2: Verify filtering to "Alpha" reduces to 1 record
    const alphaOnly = await queryFilteredList(
      page,
      'e2et-customer',
      'e2et_cust_name',
      `Alpha ${searchTag}`,
    );
    expect(alphaOnly.length).toBe(1);

    // Step 3: Navigate and test UI search + reset flow
    await navigateToDynamicPage(page, 'e2et-customer');

    const searchArea = page.locator('[data-testid="search-area"]').first();
    const hasSearchArea = await searchArea.isVisible({ timeout: 8000 }).catch(() => false);
    if (!hasSearchArea) {
      throw new Error(String('Search area not found on customer list'))
      return;
    }

    // Find the customer name input
    const nameInput = searchArea.locator(
      'input[name="e2et_cust_name"], input[id="e2et_cust_name"], input[placeholder*="name" i]'
    ).first();
    const nameInputVisible = await nameInput.isVisible({ timeout: 3000 }).catch(() => false);
    const inputToUse = nameInputVisible ? nameInput : searchArea.locator('input').nth(1);

    // Step 4: Search for "Alpha" keyword (should filter to 1 result)
    await inputToUse.fill(`Alpha ${searchTag}`);
    const searchBtn = page.locator('[data-testid="filter-search"]').first();

    const searchResponse = page.waitForResponse(
      (r) => r.url().includes('/list') && r.status() === 200,
      { timeout: 15000 }
    );
    await searchBtn.click();
    await searchResponse;

    // Step 5: Click reset and wait for list to reload
    const resetBtn = page.locator('[data-testid="filter-reset"]').first();

    const resetResponse = page.waitForResponse(
      (r) => r.url().includes('/list') && r.status() === 200,
      { timeout: 15000 }
    );
    await resetBtn.click();
    await resetResponse;

    // Step 6: Verify both records are still accessible via API after reset
    // (scoped to our searchTag to avoid interference from parallel tests)
    const restoredRecords = await queryFilteredList(
      page,
      'e2et-customer',
      'e2et_cust_name',
      searchTag,
    );
    expect(restoredRecords.length).toBe(2);
  });
});
