/**
 * Procurement — Purchase Order Lifecycle E2E Tests
 *
 * Tests PR-PO-001 ~ PR-PO-012: Full PO lifecycle covering:
 * - Navigation: sidebar menu → PO list, receipt list, request list
 * - i18n: Chinese column headers on list pages
 * - Data creation: supplier, purchase order, PO lines, receipt via API
 * - State transition: draft → pending → approved (submit + approve)
 * - Verification: created records visible on list pages
 *
 * Prerequisites: procurement plugin must be imported and all models published.
 *
 * @since 9.1.0
 */

import { test, expect } from '../../fixtures';
import {
  navigateToDynamicPage,
  uniqueId,
  executeCommandViaApi,
  findRowInPaginatedList,
  todayStr,
  dateOffsetStr,
  queryFilteredList,
} from '../helpers/index';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const UID = uniqueId('PR');

const SUPPLIER_DATA = {
  name: `TestSupplier ${UID}`,
  contact: `Contact ${UID}`,
  phone: '13800138000',
  address: `Test Address ${UID}`,
  level: 'approved',
  category: 'component',
  paymentTerms: 'net30',
};

// ---------------------------------------------------------------------------
// Test suite
// ---------------------------------------------------------------------------

test.describe('Procurement — Purchase Order Lifecycle', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60_000);

  let productPid: string;
  let warehousePid: string;
  let supplierPid: string;
  let poPid: string;
  let poLinePid: string;
  let receiptPid: string;
  let receiptLinePid: string;
  let requestPid: string;

  // Setup: create prerequisite data (product + warehouse)
  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      const prodResult = await executeCommandViaApi(page, 'prod:create_product', {
        prod_name: `TestProduct ${UID}`,
        prod_unit: 'pcs',
        prod_type: 'raw_material',
      }, undefined, 'create');
      productPid = prodResult.recordId;
    } catch {
      // Product catalog may not be available
    }
    try {
      const whResult = await executeCommandViaApi(page, 'inv:create_warehouse', {
        inv_warehouse_name: `TestWarehouse ${UID}`,
        inv_warehouse_code: `WH_${UID}`,
        inv_warehouse_type: 'standard',
        inv_warehouse_status: 'active',
      }, undefined, 'create');
      warehousePid = whResult.recordId;
    } catch {
      // Inventory may not be available; try to get existing warehouse
      try {
        const resp = await page.request.get('/api/dynamic/inv-warehouse/list?pageSize=1');
        const body = await resp.json();
        const records = body?.data?.records ?? [];
        if (records.length > 0) {
          warehousePid = records[0].pid;
        }
      } catch { /* ignore */ }
    }
    await ctx.close();
  });

  // -------------------------------------------------------------------------
  // PR-PO-001: Navigate to purchase order list via sidebar menu
  // -------------------------------------------------------------------------

  test('PR-PO-001: Navigate to purchase order list via sidebar menu', async ({ page }) => {
    await page.goto('/dashboards');
    await page.waitForLoadState('domcontentloaded');

    const nav = page.locator('nav');

    // Open Procurement root menu
    const procBtn = nav.getByRole('button', { name: 'Procurement' });
    await procBtn.scrollIntoViewIfNeeded();
    await procBtn.evaluate((el: HTMLElement) => el.click());
    await page.waitForResponse(
      () => true,
      { timeout: 3_000 },
    ).catch(() => null);

    // Open procurement sub-directory
    const purchaseDir = nav.getByRole('button', { name: '采购管理' });
    await purchaseDir.scrollIntoViewIfNeeded();
    await purchaseDir.evaluate((el: HTMLElement) => el.click());
    await page.waitForResponse(
      () => true,
      { timeout: 3_000 },
    ).catch(() => null);

    // Click purchase order link
    const poLink = nav.getByRole('link', { name: '采购订单' });
    await poLink.scrollIntoViewIfNeeded();
    await poLink.evaluate((el: HTMLElement) => el.click());

    // Wait for list API
    await page.waitForResponse(
      (r) => r.url().includes('/api/dynamic/pr_purchase_order/list') && r.status() === 200,
      { timeout: 15_000 },
    );

    await expect(page.locator('table, [class*="ant-table"]')).toBeVisible({ timeout: 10_000 });
  });

  // -------------------------------------------------------------------------
  // PR-PO-002: Verify list page shows Chinese column headers
  // -------------------------------------------------------------------------

  test('PR-PO-002: Verify PO list shows Chinese column headers', async ({ page }) => {
    await navigateToDynamicPage(page, 'pr-purchase-order');

    const headerRow = page.locator('thead tr').first();
    await expect(headerRow).toBeVisible({ timeout: 10_000 });

    const headerText = await headerRow.textContent();

    // Verify i18n: should contain Chinese labels (column headers like 采购单号, 供应商, etc.)
    expect(headerText).toContain('采购单号');

    // Should NOT contain raw field codes
    expect(headerText).not.toContain('pr_po_code');
    expect(headerText).not.toContain('pr_po_code');
  });

  // -------------------------------------------------------------------------
  // PR-PO-003: Create a supplier via API (prerequisite data)
  // -------------------------------------------------------------------------

  test('PR-PO-003: Create supplier via API', async ({ page }) => {
    const result = await executeCommandViaApi(page, 'pe:create_supplier', {
      pe_supplier_name: SUPPLIER_DATA.name,
      pe_supplier_contact: SUPPLIER_DATA.contact,
      pe_supplier_phone: SUPPLIER_DATA.phone,
      pe_supplier_address: SUPPLIER_DATA.address,
      pe_sup_level: SUPPLIER_DATA.level,
      pe_sup_category: SUPPLIER_DATA.category,
      pe_sup_payment_terms: SUPPLIER_DATA.paymentTerms,
    }, undefined, 'create');

    supplierPid = result.recordId;
    expect(supplierPid).toBeTruthy();
  });

  // -------------------------------------------------------------------------
  // PR-PO-004: Create a purchase order via API
  // -------------------------------------------------------------------------

  test('PR-PO-004: Create purchase order via API', async ({ page }) => {
    expect(supplierPid).toBeTruthy();

    const result = await executeCommandViaApi(page, 'pr:create_purchase_order', {
      pr_po_supplier: supplierPid,
      pr_po_date: todayStr(),
      pr_po_arrival_date: dateOffsetStr(14),
    }, undefined, 'create');

    poPid = result.recordId;
    expect(poPid).toBeTruthy();
  });

  // -------------------------------------------------------------------------
  // PR-PO-005: Verify created PO appears in list
  // -------------------------------------------------------------------------

  test('PR-PO-005: Verify created PO appears in list', async ({ page }) => {
    expect(poPid).toBeTruthy();

    // Fetch the PO to get its auto-generated code
    const resp = await page.request.get(`/api/dynamic/pr_purchase_order/${poPid}`);
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const poCode = body?.data?.pr_po_code ?? '';
    expect(poCode).toBeTruthy();

    await navigateToDynamicPage(page, 'pr-purchase-order');
    const row = await findRowInPaginatedList(page, poCode);
    expect(row).toBeTruthy();
  });

  // -------------------------------------------------------------------------
  // PR-PO-006: Create PO lines via API
  // -------------------------------------------------------------------------

  test('PR-PO-006: Create PO line via API', async ({ page }) => {
    expect(poPid).toBeTruthy();
    expect(productPid).toBeTruthy();

    const result = await executeCommandViaApi(page, 'pr:add_po_line', {
      pr_pol_order_id: poPid,
      pr_pol_product_id: productPid,
      pr_pol_qty: 100,
      pr_pol_price: 25.50,
    }, undefined, 'create');

    poLinePid = result.recordId;
    expect(poLinePid).toBeTruthy();
  });

  // -------------------------------------------------------------------------
  // PR-PO-007: Submit and approve PO (draft → pending → approved)
  // -------------------------------------------------------------------------

  test('PR-PO-007: Submit and approve PO (state transitions)', async ({ page }) => {
    expect(poPid).toBeTruthy();

    // draft → pending (submit for approval)
    const submitResult = await executeCommandViaApi(
      page, 'pr:submit_purchase_order', {}, poPid, 'state_transition',
    );
    expect(submitResult.recordId || submitResult.code).toBeTruthy();

    // Verify status changed to pending
    let resp = await page.request.get(`/api/dynamic/pr_purchase_order/${poPid}`);
    expect(resp.ok()).toBe(true);
    let body = await resp.json();
    let record = body.data ?? body;
    expect(record.pr_po_status).toBe('pending');

    // pending → approved
    const approveResult = await executeCommandViaApi(
      page, 'pr:approve_purchase_order', {}, poPid, 'state_transition',
    );
    expect(approveResult.recordId || approveResult.code).toBeTruthy();

    // Verify status changed to approved
    resp = await page.request.get(`/api/dynamic/pr_purchase_order/${poPid}`);
    expect(resp.ok()).toBe(true);
    body = await resp.json();
    record = body.data ?? body;
    expect(record.pr_po_status).toBe('approved');
  });

  // -------------------------------------------------------------------------
  // PR-PO-008: Verify PO status updated on list page
  // -------------------------------------------------------------------------

  test('PR-PO-008: Verify PO status shows as approved', async ({ page }) => {
    expect(poPid).toBeTruthy();

    // Verify via API that status is approved
    const resp = await page.request.get(`/api/dynamic/pr_purchase_order/${poPid}`);
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const status = body?.data?.pr_po_status;
    expect(status).toBe('approved');

    // Verify on list page — the PO code row should show approved status
    const poCode = body?.data?.pr_po_code ?? '';
    await navigateToDynamicPage(page, 'pr-purchase-order');
    const row = await findRowInPaginatedList(page, poCode);
    expect(row).toBeTruthy();

    // The row should contain the status text (Chinese: "已审核" or English: "Approved")
    const rowText = await row!.textContent();
    const hasApproved = rowText?.includes('已审核') || rowText?.includes('Approved');
    expect(hasApproved).toBe(true);
  });

  // -------------------------------------------------------------------------
  // PR-PO-009: Navigate to purchase receipt list
  // -------------------------------------------------------------------------

  test('PR-PO-009: Navigate to purchase receipt list via sidebar', async ({ page }) => {
    await page.goto('/dashboards');
    await page.waitForLoadState('domcontentloaded');

    const nav = page.locator('nav');

    const procBtn = nav.getByRole('button', { name: 'Procurement' });
    await procBtn.scrollIntoViewIfNeeded();
    await procBtn.evaluate((el: HTMLElement) => el.click());
    await page.waitForResponse(
      () => true,
      { timeout: 3_000 },
    ).catch(() => null);

    const purchaseDir = nav.getByRole('button', { name: '采购管理' });
    await purchaseDir.scrollIntoViewIfNeeded();
    await purchaseDir.evaluate((el: HTMLElement) => el.click());
    await page.waitForResponse(
      () => true,
      { timeout: 3_000 },
    ).catch(() => null);

    const receiptLink = nav.getByRole('link', { name: '收货管理' });
    await receiptLink.scrollIntoViewIfNeeded();
    await receiptLink.evaluate((el: HTMLElement) => el.click());

    await page.waitForResponse(
      (r) => r.url().includes('/api/dynamic/pr_purchase_receipt/list') && r.status() === 200,
      { timeout: 15_000 },
    );

    await expect(page.locator('table, [class*="ant-table"]')).toBeVisible({ timeout: 10_000 });
  });

  // -------------------------------------------------------------------------
  // PR-PO-010: Create a purchase receipt via API
  // -------------------------------------------------------------------------

  test('PR-PO-010: Create purchase receipt via API', async ({ page }) => {
    expect(poPid).toBeTruthy();

    expect(warehousePid).toBeTruthy();

    // Create receipt header
    const receiptResult = await executeCommandViaApi(page, 'pr:create_purchase_receipt', {
      pr_rcpt_po_id: poPid,
      pr_rcpt_date: todayStr(),
      pr_rcpt_warehouse_id: warehousePid,
      pr_rcpt_remark: `E2E receipt ${UID}`,
    }, undefined, 'create');

    receiptPid = receiptResult.recordId;
    expect(receiptPid).toBeTruthy();

    // Add a receipt line
    const lineResult = await executeCommandViaApi(page, 'pr:add_rcpt_line', {
      pr_rcptl_receipt_id: receiptPid,
      pr_rcptl_product_id: productPid,
      pr_rcptl_qty: 50,
      pr_rcptl_price: 25.50,
    }, undefined, 'create');

    receiptLinePid = lineResult.recordId;
    expect(receiptLinePid).toBeTruthy();
  });

  // -------------------------------------------------------------------------
  // PR-PO-011: Verify receipt appears in list
  // -------------------------------------------------------------------------

  test('PR-PO-011: Verify receipt appears in list', async ({ page }) => {
    expect(receiptPid).toBeTruthy();

    // Fetch receipt code
    const resp = await page.request.get(`/api/dynamic/pr_purchase_receipt/${receiptPid}`);
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const receiptCode = body?.data?.pr_rcpt_code ?? '';
    expect(receiptCode).toBeTruthy();

    await navigateToDynamicPage(page, 'pr-purchase-receipt');
    const row = await findRowInPaginatedList(page, receiptCode);
    expect(row).toBeTruthy();
  });

  // -------------------------------------------------------------------------
  // PR-PO-012: Navigate to purchase request list and verify data exists
  // -------------------------------------------------------------------------

  test('PR-PO-012: Navigate to purchase request list and verify data', async ({ page }) => {
    // Create a purchase request for this test run
    const reqResult = await executeCommandViaApi(page, 'pr:create_purchase_request', {
      pr_preq_product_id: productPid,
      pr_preq_qty: 200,
      pr_preq_source: 'manual',
      pr_preq_source_no: `REQ_${UID}`,
      pr_preq_remark: `E2E lifecycle test ${UID}`,
    }, undefined, 'create');

    requestPid = reqResult.recordId;
    expect(requestPid).toBeTruthy();

    // Navigate via sidebar
    await page.goto('/dashboards');
    await page.waitForLoadState('domcontentloaded');

    const nav = page.locator('nav');

    const procBtn = nav.getByRole('button', { name: 'Procurement' });
    await procBtn.scrollIntoViewIfNeeded();
    await procBtn.evaluate((el: HTMLElement) => el.click());
    await page.waitForResponse(
      () => true,
      { timeout: 3_000 },
    ).catch(() => null);

    const purchaseDir = nav.getByRole('button', { name: '采购管理' });
    await purchaseDir.scrollIntoViewIfNeeded();
    await purchaseDir.evaluate((el: HTMLElement) => el.click());
    await page.waitForResponse(
      () => true,
      { timeout: 3_000 },
    ).catch(() => null);

    const requestLink = nav.getByRole('link', { name: '采购需求' });
    await requestLink.scrollIntoViewIfNeeded();
    await requestLink.evaluate((el: HTMLElement) => el.click());

    await page.waitForResponse(
      (r) => r.url().includes('/api/dynamic/pr_purchase_request/list') && r.status() === 200,
      { timeout: 15_000 },
    );

    await expect(page.locator('table, [class*="ant-table"]')).toBeVisible({ timeout: 10_000 });

    // Verify at least 1 row of data
    const rows = page.locator('tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 10_000 });
    const count = await rows.count();
    expect(count).toBeGreaterThanOrEqual(1);
  });
});
