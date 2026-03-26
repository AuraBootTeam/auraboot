/**
 * Product Catalog (商品中心) Smoke & Critical Tests
 *
 * PC-001 @smoke  : Navigate to 商品管理 list → table visible, i18n headers
 * PC-002 @smoke  : Navigate to 品牌管理 list → table visible
 * PC-003 @smoke  : Navigate to 分类管理 list → table visible
 * PC-004 @critical: Created product appears in list with draft status
 * PC-005 @critical: Activate product → active via API + verify in list
 * PC-006 @critical: Created brand & category appear in their respective lists
 *
 * Prerequisites:
 *   - product-catalog plugin imported and published
 *   - Menus registered under 商品中心 root (code=prod_root)
 *
 * @since 10.0.0
 */

import { test, expect } from '../../fixtures';
import {
  uniqueId,
  executeCommandViaApi,
  queryFilteredList,
  findRowInPaginatedList,
} from '../helpers/index';

// ---------------------------------------------------------------------------
// Navigation Helper
// ---------------------------------------------------------------------------

async function navigateToCatalogPage(
  page: import('@playwright/test').Page,
  leafName: string,
  modelCode: string,
): Promise<void> {
  await page.goto('/dashboards');
  await page.waitForLoadState('domcontentloaded');
  const nav = page.locator('nav');
  const rootBtn = nav.getByRole('button', { name: '商品中心' });
  await rootBtn.scrollIntoViewIfNeeded();
  await rootBtn.evaluate((el) => (el as HTMLElement).click());
  // Allow sidebar to expand
  await page.waitForResponse(() => true, { timeout: 2_000 }).catch(() => null);
  const leafLink = nav.getByRole('link', { name: leafName });
  await leafLink.scrollIntoViewIfNeeded();
  // Set up waitForResponse BEFORE click to avoid race condition
  const listResponsePromise = page.waitForResponse(
    (r) =>
      r.url().includes(`/api/dynamic/${modelCode}/list`) && r.status() === 200,
    { timeout: 15_000 },
  );
  await leafLink.evaluate((el) => (el as HTMLElement).click());
  await listResponsePromise;
  await expect(
    page.locator('table, [class*="ant-table"]').first(),
  ).toBeVisible({ timeout: 10_000 });
}

// ---------------------------------------------------------------------------
// Test Suite
// ---------------------------------------------------------------------------

test.describe('Product Catalog Smoke Tests', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60_000);

  const uid = uniqueId('prod');
  let brandRecordId: string;
  let categoryRecordId: string;
  let productRecordId: string;

  const brandName = `Brand_${uid}`;
  const brandCode = `BRD_${uid}`.slice(0, 32);
  const categoryName = `Cat_${uid}`;
  const categoryCode = `CAT_${uid}`.slice(0, 32);
  const productName = `Product_${uid}`;

  // =========================================================================
  // DATA SETUP
  // =========================================================================

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({
      storageState: 'tests/storage/admin.json',
    });
    const page = await ctx.newPage();
    try {
      // Create brand
      const brandResult = await executeCommandViaApi(
        page,
        'prod:create_brand',
        {
          prod_brand_name: brandName,
          prod_brand_code: brandCode,
        },
        undefined,
        'create',
      );
      brandRecordId = brandResult.recordId;

      // Create category
      const categoryResult = await executeCommandViaApi(
        page,
        'prod:create_category',
        {
          prod_cat_name: categoryName,
          prod_cat_code: categoryCode,
        },
        undefined,
        'create',
      );
      categoryRecordId = categoryResult.recordId;

      // Create product
      const productResult = await executeCommandViaApi(
        page,
        'prod:create_product',
        {
          prod_name: productName,
          prod_type: 'finished',
          prod_unit: 'pcs',
          prod_currency: 'cny',
        },
        undefined,
        'create',
      );
      productRecordId = productResult.recordId;
    } finally {
      await ctx.close();
    }
  });

  // =========================================================================
  // SMOKE TESTS
  // =========================================================================

  test('PC-001 @smoke: Navigate to 商品管理 list → table visible, i18n headers', async ({
    page,
  }) => {
    await navigateToCatalogPage(page, '商品管理', 'prod_product');

    // Table must be visible
    const table = page.locator('table, [class*="ant-table"]').first();
    await expect(table).toBeVisible({ timeout: 10_000 });

    // Column headers must not contain raw DSL field key patterns (e.g. prod_xxx)
    const headers = page.locator('thead th');
    const count = await headers.count();
    for (let i = 0; i < count; i++) {
      const text = (await headers.nth(i).innerText()).trim();
      // Raw field code patterns like "prod_name", "prod_code", "prod_status"
      // must NOT appear verbatim as header labels — i18n should resolve them
      expect(text).not.toMatch(/^prod_[a-z_]+$/);
    }
  });

  test('PC-002 @smoke: Navigate to 品牌管理 list → table visible', async ({
    page,
  }) => {
    await navigateToCatalogPage(page, '品牌管理', 'prod_brand');

    const table = page.locator('table, [class*="ant-table"]').first();
    await expect(table).toBeVisible({ timeout: 10_000 });
  });

  test('PC-003 @smoke: Navigate to 分类管理 list → table visible', async ({
    page,
  }) => {
    await navigateToCatalogPage(page, '分类管理', 'prod_category');

    const table = page.locator('table, [class*="ant-table"]').first();
    await expect(table).toBeVisible({ timeout: 10_000 });
  });

  // =========================================================================
  // CRITICAL TESTS
  // =========================================================================

  test('PC-004 @critical: Created product appears in list with active status', async ({
    page,
  }) => {
    // Query via API to verify the record exists with active status
    // (prod:create_product auto-sets prod_status = active)
    const records = await queryFilteredList(
      page,
      'prod_product',
      'prod_name',
      productName,
    );
    expect(records.length).toBeGreaterThan(0);
    const productRecord = records[0] as Record<string, unknown>;
    expect(String(productRecord.prod_status ?? '')).toBe('active');

    // Navigate to the UI list and find the row
    await navigateToCatalogPage(page, '商品管理', 'prod_product');
    const row = await findRowInPaginatedList(page, productName);
    await expect(row).toBeVisible({ timeout: 10_000 });

    // Verify status text shows active indicator (Chinese: 已启用)
    const rowText = await row.innerText();
    expect(rowText).toMatch(/active|已启用|启用/i);
  });

  test('PC-005 @critical: Discontinue product → DISCONTINUED via API + verify in list', async ({
    page,
  }) => {
    expect(productRecordId).toBeTruthy();

    // Discontinue the product via state transition command (active → DISCONTINUED)
    await executeCommandViaApi(
      page,
      'prod:discontinue_product',
      {},
      productRecordId,
      'state_transition',
    );

    // Verify via filtered API query that status changed to DISCONTINUED
    const records = await queryFilteredList(
      page,
      'prod_product',
      'prod_name',
      productName,
    );
    expect(records.length).toBeGreaterThan(0);
    const productRecord = records[0] as Record<string, unknown>;
    expect(String(productRecord.prod_status ?? '')).toBe('discontinued');

    // Navigate to the UI list and verify the status badge/text
    await navigateToCatalogPage(page, '商品管理', 'prod_product');
    const row = await findRowInPaginatedList(page, productName);
    await expect(row).toBeVisible({ timeout: 10_000 });

    const rowText = await row.innerText();
    expect(rowText).toMatch(/DISCONTINUED|已停产|停产/i);
  });

  test('PC-006 @critical: Created brand appears in brand list + created category appears in category list', async ({
    page,
  }) => {
    // --- Brand ---
    const brandRecords = await queryFilteredList(
      page,
      'prod_brand',
      'prod_brand_name',
      brandName,
    );
    expect(brandRecords.length).toBeGreaterThan(0);

    await navigateToCatalogPage(page, '品牌管理', 'prod_brand');
    const brandRow = await findRowInPaginatedList(page, brandName);
    await expect(brandRow).toBeVisible({ timeout: 10_000 });

    // --- Category ---
    const categoryRecords = await queryFilteredList(
      page,
      'prod_category',
      'prod_cat_name',
      categoryName,
    );
    expect(categoryRecords.length).toBeGreaterThan(0);

    await navigateToCatalogPage(page, '分类管理', 'prod_category');
    const categoryRow = await findRowInPaginatedList(page, categoryName);
    await expect(categoryRow).toBeVisible({ timeout: 10_000 });
  });
});
