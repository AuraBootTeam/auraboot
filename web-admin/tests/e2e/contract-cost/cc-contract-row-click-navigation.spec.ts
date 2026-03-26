/**
 * Contract List — Row Click Navigation E2E Tests
 *
 * Validates that clicking a row in cc_contract list navigates directly to the
 * detail page (not a drawer), equivalent to clicking the "view" action button.
 *
 * This tests the DSL `options.detailNavigation: "page"` configuration.
 *
 * Coverage:
 *   RCN-01: Row click navigates to detail page (not drawer)
 *   RCN-02: View button navigates to same detail page
 *   RCN-03: Row click and view button reach the same URL
 *   RCN-04: Detail page renders 4 tabs after row-click navigation
 *   RCN-05: Detail page shows correct record data after row click
 *   RCN-06: Back navigation returns to list page
 *   RCN-07: Row click on different status records all navigate to detail
 *
 * @since 7.3.0
 */

import { test, expect, type Page } from '@playwright/test';
import { uniqueId, executeCommandViaApi, dateOffsetStr, clickRowActionByLocator } from '../helpers/index';

test.describe('CC Contract Row Click Navigation @critical', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60000);

  const uid = uniqueId('e2ercn');
  const contractName = `RCN_Contract_${uid}`;
  const contractName2 = `RCN_Review_${uid}`;
  let contractPid: string;
  let contractPid2: string;

  // =========================================================================
  // Seed: 2 contracts — one DRAFT, one REVIEW (different statuses)
  // =========================================================================
  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      // Contract 1: DRAFT
      const c1 = await executeCommandViaApi(
        page, 'cc:create_contract',
        {
          cc_contract_name: contractName,
          cc_contract_amount: 500000,
          cc_contract_type: 'service',
          cc_party_a: `PartyA_${uid}`,
          cc_party_b: `PartyB_${uid}`,
          cc_signed_date: dateOffsetStr(-30),
          cc_start_date: dateOffsetStr(-30),
          cc_end_date: dateOffsetStr(180),
          cc_description: `E2E row click navigation test ${uid}`,
        },
        undefined, 'create',
      );
      contractPid = c1.recordId;
      expect(contractPid).toBeTruthy();

      // Contract 2: REVIEW (to test non-draft status row click)
      const c2 = await executeCommandViaApi(
        page, 'cc:create_contract',
        {
          cc_contract_name: contractName2,
          cc_contract_amount: 300000,
          cc_contract_type: 'design',
          cc_party_a: `PartyA2_${uid}`,
          cc_party_b: `PartyB2_${uid}`,
        },
        undefined, 'create',
      );
      contractPid2 = c2.recordId;
      expect(contractPid2).toBeTruthy();
      await executeCommandViaApi(page, 'cc:submit_review', {}, contractPid2, 'update');
    } finally {
      await ctx.close();
    }
  });

  /** Navigate to contract list via sidebar menu */
  async function navigateToContractList(page: Page) {
    await page.goto('/dashboards', { waitUntil: 'load' });

    // Expand CC menu group
    const ccMenu = page.locator('button', { hasText: /Contract & Cost|合同与成本/ });
    await ccMenu.first().scrollIntoViewIfNeeded();
    await ccMenu.first().click();

    // Click contracts submenu link
    const contractsLink = page.locator('a[href="/contract-cost/contracts"]');
    await contractsLink.first().waitFor({ state: 'visible', timeout: 5000 });
    await contractsLink.first().evaluate((el) => (el as HTMLAnchorElement).click());
    await expect(page).toHaveURL(/\/contract-cost\/contracts/, { timeout: 10000 });

    // Wait for list API to return
    await page.waitForResponse(
      (r) => r.url().includes('/list') && r.status() === 200,
      { timeout: 10000 },
    ).catch(() => null);
  }

  // =========================================================================
  // RCN-01: Row click navigates to detail page (NOT drawer)
  // =========================================================================
  test('RCN-01: Row click navigates to detail page instead of opening drawer', async ({ page }) => {
    await navigateToContractList(page);

    // Find our test contract row
    const row = page.locator('tbody tr', { hasText: contractName }).first();
    await row.waitFor({ state: 'visible', timeout: 10000 });

    // Click the row body (not an action button)
    await row.click();

    // Should navigate to detail page URL
    await expect(page).toHaveURL(/\/dynamic\/cc[-_]contract\/view\//, { timeout: 10000 });

    // Drawer should NOT be present
    const drawer = page.locator('[data-testid="record-preview-drawer"]');
    await expect(drawer).not.toBeVisible({ timeout: 2000 });

    // Detail page should show the contract name
    await expect(page.locator('body')).toContainText(contractName, { timeout: 10000 });
  });

  // =========================================================================
  // RCN-02: View button also navigates to detail page
  // =========================================================================
  test('RCN-02: View action button navigates to detail page', async ({ page }) => {
    await navigateToContractList(page);

    const row = page.locator('tbody tr', { hasText: contractName }).first();
    await row.waitFor({ state: 'visible', timeout: 10000 });

    // Click the view/detail action button (may be in More actions dropdown)
    await clickRowActionByLocator(page, row, 'detail', 'view');

    // Should navigate to same detail URL pattern
    await expect(page).toHaveURL(/\/dynamic\/cc[-_]contract\/view\//, { timeout: 10000 });

    // Drawer should NOT be present
    const drawer = page.locator('[data-testid="record-preview-drawer"]');
    await expect(drawer).not.toBeVisible({ timeout: 2000 });

    // Detail page should show the contract name
    await expect(page.locator('body')).toContainText(contractName, { timeout: 10000 });
  });

  // =========================================================================
  // RCN-03: Row click and view button navigate to the same URL
  // =========================================================================
  test('RCN-03: Row click and view button navigate to same record', async ({ page }) => {
    // Extract recordId from URL helper
    const extractRecordId = (url: string) => {
      const match = url.match(/\/view\/([^/?]+)/);
      return match?.[1];
    };

    // First: get recordId from row click
    await navigateToContractList(page);
    const row1 = page.locator('tbody tr', { hasText: contractName }).first();
    await row1.waitFor({ state: 'visible', timeout: 10000 });
    await row1.click();
    await expect(page).toHaveURL(/\/dynamic\/cc[-_]contract\/view\//, { timeout: 10000 });
    const rowClickRecordId = extractRecordId(page.url());

    // Second: go back and use view button (may be in More actions dropdown)
    await navigateToContractList(page);
    const row2 = page.locator('tbody tr', { hasText: contractName }).first();
    await row2.waitFor({ state: 'visible', timeout: 10000 });
    await clickRowActionByLocator(page, row2, 'detail', 'view');
    await expect(page).toHaveURL(/\/dynamic\/cc[-_]contract\/view\//, { timeout: 10000 });
    const viewBtnRecordId = extractRecordId(page.url());

    // Both should navigate to the same record (same recordId)
    expect(rowClickRecordId).toBeTruthy();
    expect(rowClickRecordId).toBe(viewBtnRecordId);

    // Both should show the detail page (not drawer)
    await expect(page.locator('body')).toContainText(contractName, { timeout: 10000 });
  });

  // =========================================================================
  // RCN-04: Detail page renders all 4 tabs after row-click navigation
  // =========================================================================
  test('RCN-04: Detail page shows 4 tabs after row click navigation', async ({ page }) => {
    await navigateToContractList(page);

    const row = page.locator('tbody tr', { hasText: contractName }).first();
    await row.waitFor({ state: 'visible', timeout: 10000 });
    await row.click();
    await expect(page).toHaveURL(/\/dynamic\/cc[-_]contract\/view\//, { timeout: 10000 });

    // Wait for detail page to load
    await page.waitForLoadState('networkidle').catch(() => null);

    // Find tab navigation area — look for the overview tab first
    const overviewTab = page.locator('button, [role="tab"]').filter({
      hasText: /概览|Overview/,
    });
    await expect(overviewTab.first()).toBeVisible({ timeout: 10000 });

    // Verify all 4 tabs are present
    const tabContainer = overviewTab.first().locator('..');

    const financialTab = tabContainer.locator('button, [role="tab"]').filter({
      hasText: /财务|Financial/,
    });
    await expect(financialTab.first()).toBeVisible({ timeout: 5000 });

    const changesTab = tabContainer.locator('button, [role="tab"]').filter({
      hasText: /变更|Changes/,
    });
    await expect(changesTab.first()).toBeVisible({ timeout: 5000 });

    const costsTab = tabContainer.locator('button, [role="tab"]').filter({
      hasText: /成本|Costs/,
    });
    await expect(costsTab.first()).toBeVisible({ timeout: 5000 });
  });

  // =========================================================================
  // RCN-05: Detail page shows correct record data after row click
  // =========================================================================
  test('RCN-05: Detail page displays correct contract data', async ({ page }) => {
    await navigateToContractList(page);

    const row = page.locator('tbody tr', { hasText: contractName }).first();
    await row.waitFor({ state: 'visible', timeout: 10000 });
    await row.click();
    await expect(page).toHaveURL(/\/dynamic\/cc[-_]contract\/view\//, { timeout: 10000 });

    // Wait for detail page content
    await page.waitForLoadState('networkidle').catch(() => null);

    // Verify record-specific data is rendered
    await expect(page.locator('body')).toContainText(contractName, { timeout: 10000 });
    // Amount should be displayed (500000 or formatted as 500,000)
    await expect(page.locator('body')).toContainText(/500[,.]?000/, { timeout: 5000 });
    // Party names should appear
    await expect(page.locator('body')).toContainText(`PartyA_${uid}`, { timeout: 5000 });
    await expect(page.locator('body')).toContainText(`PartyB_${uid}`, { timeout: 5000 });
  });

  // =========================================================================
  // RCN-06: Browser back returns to the list page
  // =========================================================================
  test('RCN-06: Browser back from detail returns to contract list', async ({ page }) => {
    await navigateToContractList(page);

    const row = page.locator('tbody tr', { hasText: contractName }).first();
    await row.waitFor({ state: 'visible', timeout: 10000 });
    await row.click();
    await expect(page).toHaveURL(/\/dynamic\/cc[-_]contract\/view\//, { timeout: 10000 });

    // Go back
    await page.goBack();
    await expect(page).toHaveURL(/\/contract-cost\/contracts/, { timeout: 10000 });

    // List should still show our contract
    await page.waitForResponse(
      (r) => r.url().includes('/list') && r.status() === 200,
      { timeout: 10000 },
    ).catch(() => null);

    const rowAfterBack = page.locator('tbody tr', { hasText: contractName }).first();
    await expect(rowAfterBack).toBeVisible({ timeout: 10000 });
  });

  // =========================================================================
  // RCN-07: Row click on REVIEW status contract also navigates to detail
  // =========================================================================
  test('RCN-07: Row click on non-draft contract navigates to detail', async ({ page }) => {
    await navigateToContractList(page);

    // Click the "review" status tab to filter
    const reviewTab = page.locator('[data-testid="list-tab-review"]').or(
      page.locator('button', { hasText: /审核|Review/ }),
    );
    if (await reviewTab.first().isVisible({ timeout: 3000 }).catch(() => false)) {
      await reviewTab.first().click();
      await page.waitForResponse(
        (r) => r.url().includes('/list') && r.status() === 200,
        { timeout: 10000 },
      ).catch(() => null);
    }

    // Find the review contract row
    const row = page.locator('tbody tr', { hasText: contractName2 }).first();
    await row.waitFor({ state: 'visible', timeout: 10000 });
    await row.click();

    // Should navigate to detail page (not drawer)
    await expect(page).toHaveURL(/\/dynamic\/cc[-_]contract\/view\//, { timeout: 10000 });

    const drawer = page.locator('[data-testid="record-preview-drawer"]');
    await expect(drawer).not.toBeVisible({ timeout: 2000 });

    // Should show the review contract data
    await expect(page.locator('body')).toContainText(contractName2, { timeout: 10000 });
  });
});
