/**
 * Sales — Price List & Discount Rule E2E Tests
 *
 * Tests SP-001 ~ SP-007: Core lifecycle coverage for:
 * - sl_price_list: Navigation smoke, create via API, activate, deactivate
 * - sl_discount_rule: Navigation smoke, create via API, activate
 * - i18n: Column headers render in Chinese, no raw field key leak
 * - Navigation: sidebar "价格与折扣" → child menu items
 *
 * Prerequisites: sales plugin must be imported and all models published.
 *
 * Menu hierarchy:
 *   Sales (root)
 *   └── 价格与折扣 (sl_pricing_dir)
 *       ├── 价格表         → /sales/price-lists      (sl_price_list_list)
 *       └── 折扣规则       → /sales/discount-rules   (sl_discount_rule_list)
 *
 * @since 10.0.0
 */

import { test, expect } from '../../fixtures';
import {
  uniqueId,
  executeCommandViaApi,
  findRowInPaginatedList,
  queryFilteredList,
} from '../helpers/index';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const UID = uniqueId('SP');

// ---------------------------------------------------------------------------
// Sidebar navigation helpers
// ---------------------------------------------------------------------------

/**
 * Expand the top-level "Sales" root menu (if not already open),
 * then expand the "价格与折扣" directory, and finally click the given
 * leaf menu link.
 *
 * Uses `.evaluate((el) => el.click())` to bypass Playwright's pointer
 * interception issues with overlapping Ant-Design sidebar items.
 *
 * @param leafName    - Displayed name of the leaf menu item
 * @param modelCode   - Model code used in the dynamic list API URL (e.g. "sl_price_list")
 */
async function navigateToPricingMenu(
  page: import('@playwright/test').Page,
  leafName: string,
  modelCode: string,
): Promise<void> {
  await page.goto('/dashboards');
  await page.waitForLoadState('domcontentloaded');

  const nav = page.locator('nav');

  // Expand Sales root if needed
  const salesRootBtn = nav.getByRole('button', { name: 'Sales' });
  await salesRootBtn.scrollIntoViewIfNeeded();
  await salesRootBtn.evaluate((el: HTMLElement) => el.click());
  // Brief pause to let submenu animate open — use response wait not timeout
  await page.waitForResponse(() => true, { timeout: 2_000 }).catch(() => null);

  // Expand "价格与折扣" directory
  const pricingDirBtn = nav.getByRole('button', { name: '价格与折扣' });
  await pricingDirBtn.scrollIntoViewIfNeeded();
  await pricingDirBtn.evaluate((el: HTMLElement) => el.click());
  await page.waitForResponse(() => true, { timeout: 2_000 }).catch(() => null);

  // Click the leaf menu link
  const leafLink = nav.getByRole('link', { name: leafName });
  await leafLink.scrollIntoViewIfNeeded();
  await leafLink.evaluate((el: HTMLElement) => el.click());

  // Wait for list API to respond — use modelCode not pageKey
  await page.waitForResponse(
    (r) => r.url().includes(`/api/dynamic/${modelCode}/list`) && r.status() === 200,
    { timeout: 15_000 },
  );

  // Table must be visible
  await expect(page.locator('table, [class*="ant-table"]')).toBeVisible({ timeout: 10_000 });
}

// ---------------------------------------------------------------------------
// Shared state across serial tests
// ---------------------------------------------------------------------------

let priceListPid: string;
let priceListName: string;
let priceListCode: string;

let discountRulePid: string;
let discountRuleName: string;
let discountRuleCode: string;

// ---------------------------------------------------------------------------
// Test suite
// ---------------------------------------------------------------------------

test.describe('Sales — Price List & Discount Rule', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60_000);

  // -----------------------------------------------------------------------
  // beforeAll: create test data via API so list pages always have real rows
  // -----------------------------------------------------------------------

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    const page = await ctx.newPage();

    try {
      // Create price list
      priceListName = `E2E PriceList ${UID}`;
      priceListCode = `PL-${UID}`;
      const plResult = await executeCommandViaApi(page, 'sl:create_price_list', {
        sl_pl_name: priceListName,
        sl_pl_code: priceListCode,
        sl_pl_currency: 'cny',
        sl_pl_priority: 10,
        sl_pl_status: 'draft',
      });
      priceListPid = plResult.recordId;
    } catch (e) {
      console.error('[beforeAll] Failed to create price list:', e);
    }

    try {
      // Create discount rule
      discountRuleName = `E2E DiscountRule ${UID}`;
      discountRuleCode = `DR-${UID}`;
      const drResult = await executeCommandViaApi(page, 'sl:create_discount_rule', {
        sl_dr_name: discountRuleName,
        sl_dr_code: discountRuleCode,
        sl_dr_type: 'percentage',
        sl_dr_value: 10,
        sl_dr_status: 'draft',
      });
      discountRulePid = drResult.recordId;
    } catch (e) {
      console.error('[beforeAll] Failed to create discount rule:', e);
    }

    await ctx.close();
  });

  // =========================================================================
  // SP-001 @smoke — Price list menu → page loads → table has data
  // =========================================================================

  test('SP-001 @smoke: Price list menu navigation and list renders', async ({ page }) => {
    await navigateToPricingMenu(page, '价格表', 'sl_price_list');

    // Assert at least 1 data row is visible (the one we created in beforeAll)
    const rows = page.locator('tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 10_000 });
    const rowCount = await rows.count();
    expect(rowCount).toBeGreaterThanOrEqual(1);

    // Assert Chinese column headers — no raw field key leak
    const headerRow = page.locator('thead tr').first();
    await expect(headerRow).toBeVisible({ timeout: 5_000 });
    const headerText = await headerRow.textContent();
    // i18n must render Chinese labels, not raw keys
    expect(headerText).not.toContain('sl_pl_');
    expect(headerText).not.toContain('sl_pl_');
  });

  // =========================================================================
  // SP-002 @smoke — Discount rule menu → page loads → table has data
  // =========================================================================

  test('SP-002 @smoke: Discount rule menu navigation and list renders', async ({ page }) => {
    await navigateToPricingMenu(page, '折扣规则', 'sl_discount_rule');

    // Assert at least 1 data row is visible
    const rows = page.locator('tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 10_000 });
    const rowCount = await rows.count();
    expect(rowCount).toBeGreaterThanOrEqual(1);

    // Assert Chinese column headers — no raw field key leak
    const headerRow = page.locator('thead tr').first();
    await expect(headerRow).toBeVisible({ timeout: 5_000 });
    const headerText = await headerRow.textContent();
    expect(headerText).not.toContain('sl_dr_');
    expect(headerText).not.toContain('sl_dr_');
  });

  // =========================================================================
  // SP-003 @critical — Created price list appears in list with draft status
  // =========================================================================

  test('SP-003 @critical: Created price list appears in list', async ({ page }) => {
    expect(priceListPid, 'beforeAll must have created a price list').toBeTruthy();

    // Navigate to list via sidebar
    await navigateToPricingMenu(page, '价格表', 'sl_price_list');

    // Find the row by name (uniqueId makes it distinguishable)
    const row = await findRowInPaginatedList(page, priceListName);
    expect(row).toBeTruthy();

    await expect(row).toBeVisible({ timeout: 5_000 });
    const rowText = await row.textContent();

    // Status should be draft (may render as Chinese "草稿" via i18n)
    const hasDraftStatus =
      rowText?.includes('草稿') || rowText?.includes('draft') || rowText?.includes('draft');
    expect(hasDraftStatus).toBe(true);
  });

  // =========================================================================
  // SP-004 @critical — Activate price list → status becomes active
  // =========================================================================

  test('SP-004 @critical: Activate price list changes status to active', async ({ page }) => {
    expect(priceListPid, 'beforeAll must have created a price list').toBeTruthy();

    // Execute activate command via API
    const result = await executeCommandViaApi(
      page,
      'sl:activate_price_list',
      {},
      priceListPid,
      'state_transition',
    );
    expect(result.recordId || result.code).toBeTruthy();

    // Verify status via direct record fetch (use model code, not page key)
    const resp = await page.request.get(`/api/dynamic/sl_price_list/${priceListPid}`);
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const record = body.data ?? body;
    expect(record.sl_pl_status).toBe('active');

    // Verify the updated status is visible on the list page
    await navigateToPricingMenu(page, '价格表', 'sl_price_list');
    const row = await findRowInPaginatedList(page, priceListName);
    await expect(row).toBeVisible({ timeout: 5_000 });
    const rowText = await row.textContent();
    const hasActiveStatus =
      rowText?.includes('active') ||
      rowText?.includes('已激活') ||
      rowText?.includes('生效') ||
      rowText?.includes('启用');
    expect(hasActiveStatus).toBe(true);
  });

  // =========================================================================
  // SP-005 @critical — Created discount rule appears in list with draft status
  // =========================================================================

  test('SP-005 @critical: Created discount rule appears in list', async ({ page }) => {
    expect(discountRulePid, 'beforeAll must have created a discount rule').toBeTruthy();

    await navigateToPricingMenu(page, '折扣规则', 'sl_discount_rule');

    const row = await findRowInPaginatedList(page, discountRuleName);
    expect(row).toBeTruthy();

    await expect(row).toBeVisible({ timeout: 5_000 });
    const rowText = await row.textContent();

    const hasDraftStatus =
      rowText?.includes('草稿') || rowText?.includes('draft') || rowText?.includes('draft');
    expect(hasDraftStatus).toBe(true);
  });

  // =========================================================================
  // SP-006 @critical — Activate discount rule → status becomes active
  // =========================================================================

  test('SP-006 @critical: Activate discount rule changes status to active', async ({ page }) => {
    expect(discountRulePid, 'beforeAll must have created a discount rule').toBeTruthy();

    // Execute activate command via API
    const result = await executeCommandViaApi(
      page,
      'sl:activate_discount_rule',
      {},
      discountRulePid,
      'state_transition',
    );
    expect(result.recordId || result.code).toBeTruthy();

    // Verify status via direct record fetch (use model code, not page key)
    const resp = await page.request.get(`/api/dynamic/sl_discount_rule/${discountRulePid}`);
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const record = body.data ?? body;
    expect(record.sl_dr_status).toBe('active');

    // Verify updated status on list page
    await navigateToPricingMenu(page, '折扣规则', 'sl_discount_rule');
    const row = await findRowInPaginatedList(page, discountRuleName);
    await expect(row).toBeVisible({ timeout: 5_000 });
    const rowText = await row.textContent();
    const hasActiveStatus =
      rowText?.includes('active') ||
      rowText?.includes('已激活') ||
      rowText?.includes('生效') ||
      rowText?.includes('启用');
    expect(hasActiveStatus).toBe(true);
  });

  // =========================================================================
  // SP-007 @critical — Deactivate price list → status becomes inactive
  // =========================================================================

  test('SP-007 @critical: Deactivate price list changes status to inactive', async ({ page }) => {
    expect(priceListPid, 'beforeAll must have created a price list').toBeTruthy();

    // Execute deactivate command via API (price list must be active first — done in SP-004)
    const result = await executeCommandViaApi(
      page,
      'sl:deactivate_price_list',
      {},
      priceListPid,
      'state_transition',
    );
    expect(result.recordId || result.code).toBeTruthy();

    // Verify status via direct record fetch (use model code, not page key)
    const resp = await page.request.get(`/api/dynamic/sl_price_list/${priceListPid}`);
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const record = body.data ?? body;
    expect(record.sl_pl_status).toBe('inactive');

    // Verify the updated status is visible on the list page
    await navigateToPricingMenu(page, '价格表', 'sl_price_list');
    const row = await findRowInPaginatedList(page, priceListName);
    await expect(row).toBeVisible({ timeout: 5_000 });
    const rowText = await row.textContent();
    const hasInactiveStatus =
      rowText?.includes('inactive') ||
      rowText?.includes('已停用') ||
      rowText?.includes('停用') ||
      rowText?.includes('未生效');
    expect(hasInactiveStatus).toBe(true);

    // Cross-verify via API query that data integrity is maintained
    // Use underscore model code to match the dynamic API endpoint format
    const records = await queryFilteredList(page, 'sl_price_list', 'sl_pl_name', priceListName, {
      operator: 'like',
    });
    expect(records.length).toBeGreaterThanOrEqual(1);
    expect(records[0].sl_pl_status).toBe('inactive');
  });
});
