/**
 * Product Catalog (商品中心) Smoke & Critical Tests
 *
 * PC-001 @smoke  : Navigate to 商品管理 list → table visible, i18n headers
 * PC-002 @smoke  : Navigate to 品牌管理 list → table visible
 * PC-003 @smoke  : Navigate to 分类管理 list → table visible
 * PC-004 @critical: Created product appears in list with planned status
 * PC-005 @critical: Activate product through the row action → active
 * PC-006 @critical: Discontinue product → discontinued via command + verify in list
 * PC-007 @critical: Created brand & category appear in their respective lists
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
  clickRowActionByLocator,
} from '../helpers/index';

type ProductRecord = Record<string, unknown> & {
  id?: number | string;
  pid?: string;
  prod_name?: string;
  prod_status?: string;
};

async function fetchProductRecord(
  page: import('@playwright/test').Page,
  recordPid: string,
): Promise<ProductRecord> {
  const response = await page.request.get(`/api/dynamic/prod_product/${recordPid}`);
  expect(response.ok(), `product detail ${recordPid} must be readable`).toBe(true);
  const body = await response.json();
  if (body?.code !== undefined) {
    expect(String(body.code), `product detail ${recordPid} must succeed`).toBe('0');
  }
  return (body?.data ?? body) as ProductRecord;
}

function recordRow(
  page: import('@playwright/test').Page,
  record: Record<string, unknown>,
  recordLabel: string,
): import('@playwright/test').Locator {
  const numericId = String(record.id ?? '');
  expect(numericId, `${recordLabel} must expose a numeric id for exact row targeting`).toMatch(
    /^\d+$/,
  );
  return page.locator(`[data-testid="table-row-${numericId}"]`);
}

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
  const leafLink = nav.getByRole('link', { name: leafName });
  await expect(leafLink).toBeVisible({ timeout: 5_000 });
  await leafLink.scrollIntoViewIfNeeded();
  // Set up waitForResponse BEFORE click to avoid race condition
  const listResponsePromise = page.waitForResponse(
    (r) => r.url().includes(`/api/dynamic/${modelCode}/list`) && r.status() === 200,
    { timeout: 15_000 },
  );
  await leafLink.evaluate((el) => (el as HTMLElement).click());
  await listResponsePromise;
  await expect(page.locator('table, [class*="ant-table"]').first()).toBeVisible({
    timeout: 10_000,
  });
}

async function filterCatalogList(
  page: import('@playwright/test').Page,
  modelCode: string,
  searchText: string,
): Promise<void> {
  const searchInput = page.getByTestId('list-search-input');
  await expect(searchInput).toBeVisible({ timeout: 5_000 });
  const listResponsePromise = page.waitForResponse(
    (response) =>
      response.url().includes(`/api/dynamic/${modelCode}/list`) && response.status() === 200,
    { timeout: 15_000 },
  );
  await searchInput.fill(searchText);
  await searchInput.press('Enter');
  await listResponsePromise;
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
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
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

  test('PC-002 @smoke: Navigate to 品牌管理 list → table visible', async ({ page }) => {
    await navigateToCatalogPage(page, '品牌管理', 'prod_brand');

    const table = page.locator('table, [class*="ant-table"]').first();
    await expect(table).toBeVisible({ timeout: 10_000 });
  });

  test('PC-003 @smoke: Navigate to 分类管理 list → table visible', async ({ page }) => {
    await navigateToCatalogPage(page, '分类管理', 'prod_category');

    const table = page.locator('table, [class*="ant-table"]').first();
    await expect(table).toBeVisible({ timeout: 10_000 });
  });

  // =========================================================================
  // CRITICAL TESTS
  // =========================================================================

  test('PC-004 @critical: Created product appears in list with planned status', async ({
    page,
  }) => {
    const productRecord = await fetchProductRecord(page, productRecordId);
    expect(productRecord.pid).toBe(productRecordId);
    expect(productRecord.prod_name).toBe(productName);
    expect(productRecord.prod_status).toBe('planned');

    await navigateToCatalogPage(page, '商品管理', 'prod_product');
    await filterCatalogList(page, 'prod_product', productName);
    const row = recordRow(page, productRecord, 'product detail');
    await expect(row).toBeVisible({ timeout: 10_000 });
    await expect(row).toContainText(productName);

    const rowText = await row.innerText();
    expect(rowText).toMatch(/planned|规划中/i);
  });

  test('PC-005 @critical: Activate product through the row action → active', async ({ page }) => {
    const plannedRecord = await fetchProductRecord(page, productRecordId);
    expect(plannedRecord.prod_status).toBe('planned');

    await navigateToCatalogPage(page, '商品管理', 'prod_product');
    await filterCatalogList(page, 'prod_product', productName);
    const row = recordRow(page, plannedRecord, 'planned product detail');
    await expect(row).toBeVisible({ timeout: 10_000 });

    const activateResponsePromise = page.waitForResponse(
      (response) =>
        response.url().includes('/api/meta/commands/execute/prod:activate_product') &&
        response.request().method() === 'POST',
      { timeout: 20_000 },
    );
    await clickRowActionByLocator(page, row, 'activate', '启用商品');
    const activateResponse = await activateResponsePromise;
    const activateBody = await activateResponse.json();
    expect(activateResponse.ok(), JSON.stringify(activateBody)).toBe(true);
    expect(String(activateBody.code), JSON.stringify(activateBody)).toBe('0');

    const activeRecord = await fetchProductRecord(page, productRecordId);
    expect(activeRecord.prod_name).toBe(productName);
    expect(activeRecord.prod_status).toBe('active');

    await navigateToCatalogPage(page, '商品管理', 'prod_product');
    await filterCatalogList(page, 'prod_product', productName);
    const activeRow = recordRow(page, activeRecord, 'active product detail');
    await expect(activeRow).toBeVisible({ timeout: 10_000 });
    await expect(activeRow).toContainText(productName);
    expect(await activeRow.innerText()).toMatch(/active|已启用/i);
  });

  test('PC-006 @critical: Discontinue product → discontinued via command + verify in list', async ({
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

    const productRecord = await fetchProductRecord(page, productRecordId);
    expect(productRecord.prod_name).toBe(productName);
    expect(productRecord.prod_status).toBe('discontinued');

    await navigateToCatalogPage(page, '商品管理', 'prod_product');
    await filterCatalogList(page, 'prod_product', productName);
    const row = recordRow(page, productRecord, 'discontinued product detail');
    await expect(row).toBeVisible({ timeout: 10_000 });
    await expect(row).toContainText(productName);

    const rowText = await row.innerText();
    expect(rowText).toMatch(/discontinued|已停产/i);
  });

  test('PC-007 @critical: Created brand appears in brand list + created category appears in category list', async ({
    page,
  }) => {
    // --- Brand ---
    const brandRecords = await queryFilteredList(page, 'prod_brand', 'prod_brand_name', brandName);
    expect(brandRecords.length).toBeGreaterThan(0);
    const brandRecord = brandRecords.find((record) => record.prod_brand_name === brandName);
    expect(brandRecord, `brand ${brandName} must be returned by the filtered list`).toBeTruthy();

    await navigateToCatalogPage(page, '品牌管理', 'prod_brand');
    await filterCatalogList(page, 'prod_brand', brandName);
    const brandRow = recordRow(page, brandRecord!, 'brand record');
    await expect(brandRow).toBeVisible({ timeout: 10_000 });
    await expect(brandRow).toContainText(brandName);

    // --- Category ---
    const categoryRecords = await queryFilteredList(
      page,
      'prod_category',
      'prod_cat_name',
      categoryName,
    );
    expect(categoryRecords.length).toBeGreaterThan(0);
    const categoryRecord = categoryRecords.find((record) => record.prod_cat_name === categoryName);
    expect(
      categoryRecord,
      `category ${categoryName} must be returned by the filtered list`,
    ).toBeTruthy();

    await navigateToCatalogPage(page, '分类管理', 'prod_category');
    await filterCatalogList(page, 'prod_category', categoryName);
    const categoryRow = recordRow(page, categoryRecord!, 'category record');
    await expect(categoryRow).toBeVisible({ timeout: 10_000 });
    await expect(categoryRow).toContainText(categoryName);
  });
});
