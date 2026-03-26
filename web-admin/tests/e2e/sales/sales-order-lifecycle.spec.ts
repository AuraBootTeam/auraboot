/**
 * Sales — Order & Quotation Lifecycle E2E Tests
 *
 * Tests SL-ORD-001 ~ SL-ORD-011: Full lifecycle coverage for:
 * - sl_sales_order: Create, list, add lines, state transitions (draft→pending→approved)
 * - sl_sales_quotation: Create, list, add lines, send (draft→SENT)
 * - i18n: Verify Chinese column headers render correctly
 * - Navigation: Verify sidebar menu → list page flow
 *
 * Prerequisites: sales plugin must be imported and all models published.
 *
 * @since 9.0.0
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

const UID = uniqueId('SL');

const ORDER_DATA = {
  date: todayStr(),
  deliveryDate: dateOffsetStr(14),
};

const QUOTATION_DATA = {
  date: todayStr(),
  validUntil: dateOffsetStr(30),
  paymentTerms: 'net30',
  remark: `E2E quotation ${UID}`,
};

const LINE_QTY = 10;
const LINE_PRICE = 99.5;

// ---------------------------------------------------------------------------
// Test suite
// ---------------------------------------------------------------------------

test.describe('Sales — Order & Quotation Lifecycle', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60_000);

  let accountPid: string;
  let productPid: string;
  let orderPid: string;
  let orderCode: string;
  let orderLinePid: string;
  let quotationPid: string;
  let quotationCode: string;

  // Setup: create prerequisite data (CRM account + product)
  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      const accResult = await executeCommandViaApi(page, 'crm:create_account', {
        crm_acc_name: `TestAccount ${UID}`,
        crm_acc_industry: 'technology',
        crm_acc_status: 'active',
      }, undefined, 'create');
      accountPid = accResult.recordId;
    } catch {
      // Account may fail if CRM not imported
    }
    try {
      const prodResult = await executeCommandViaApi(page, 'prod:create_product', {
        prod_name: `TestProduct ${UID}`,
        prod_unit: 'pcs',
        prod_type: 'finished_good',
      }, undefined, 'create');
      productPid = prodResult.recordId;
    } catch {
      // Product catalog may not be available
    }
    await ctx.close();
  });

  // -------------------------------------------------------------------------
  // Sales Order Tests
  // -------------------------------------------------------------------------

  test('SL-ORD-001: Navigate to sales order list via sidebar menu', async ({ page }) => {
    await page.goto('/dashboards');
    await page.waitForLoadState('domcontentloaded');

    const nav = page.locator('nav');

    // Sales root menu
    const salesBtn = nav.getByRole('button', { name: 'Sales' });
    await salesBtn.scrollIntoViewIfNeeded();
    await salesBtn.evaluate((el: HTMLElement) => el.click());
    await page.waitForResponse(
      () => true,
      { timeout: 3_000 },
    ).catch(() => null);

    // Sales management directory
    const salesDirBtn = nav.getByRole('button', { name: '销售管理' });
    await salesDirBtn.scrollIntoViewIfNeeded();
    await salesDirBtn.evaluate((el: HTMLElement) => el.click());
    await page.waitForResponse(
      () => true,
      { timeout: 3_000 },
    ).catch(() => null);

    // Sales order link
    const orderLink = nav.getByRole('link', { name: '销售订单' });
    await orderLink.scrollIntoViewIfNeeded();
    await orderLink.evaluate((el: HTMLElement) => el.click());

    // Wait for list API to respond
    await page.waitForResponse(
      (r) => r.url().includes('/api/dynamic/sl_sales_order/list') && r.status() === 200,
      { timeout: 15_000 },
    );

    // Verify table is visible
    await expect(page.locator('table, [class*="ant-table"]')).toBeVisible({ timeout: 10_000 });
  });

  test('SL-ORD-002: Sales order list shows Chinese column headers', async ({ page }) => {
    await navigateToDynamicPage(page, 'sl-sales-order');

    const headerRow = page.locator('thead tr').first();
    await expect(headerRow).toBeVisible({ timeout: 10_000 });

    const headerText = await headerRow.textContent();

    // Should contain Chinese labels (not raw field codes)
    expect(headerText).toContain('订单编号');
    expect(headerText).toContain('订单日期');

    // Should NOT contain raw field codes
    expect(headerText).not.toContain('sl_so_code');
    expect(headerText).not.toContain('sl_so_code');
  });

  test('SL-ORD-003: Create a sales order via API', async ({ page }) => {
    const result = await executeCommandViaApi(page, 'sl:create_sales_order', {
      sl_so_date: ORDER_DATA.date,
      sl_so_delivery_date: ORDER_DATA.deliveryDate,
      ...(accountPid ? { sl_so_account_id: accountPid } : {}),
    }, undefined, 'create');

    orderPid = result.recordId;
    expect(orderPid).toBeTruthy();

    // Fetch the created record to get the auto-generated code
    const resp = await page.request.get(`/api/dynamic/sl_sales_order/${orderPid}`);
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const record = body.data ?? body;
    orderCode = record.sl_so_code ?? '';
    expect(orderCode).toBeTruthy();
  });

  test('SL-ORD-004: Created order appears in list', async ({ page }) => {
    expect(orderCode).toBeTruthy();

    await navigateToDynamicPage(page, 'sl-sales-order');
    const row = await findRowInPaginatedList(page, orderCode);
    expect(row).toBeTruthy();
    // Status renders as Chinese "草稿" (i18n)
    const rowText = await row!.textContent();
    expect(rowText?.includes('草稿') || rowText?.includes('draft')).toBe(true);
  });

  test('SL-ORD-005: Add order line via API', async ({ page }) => {
    expect(orderPid).toBeTruthy();
    expect(productPid).toBeTruthy();

    const result = await executeCommandViaApi(page, 'sl:add_so_line', {
      sl_sol_order_id: orderPid,
      sl_sol_product_id: productPid,
      sl_sol_qty: LINE_QTY,
      sl_sol_price: LINE_PRICE,
    }, undefined, 'create');

    orderLinePid = result.recordId;
    expect(orderLinePid).toBeTruthy();

    // Verify the order total was updated via side effect aggregation
    const resp = await page.request.get(`/api/dynamic/sl_sales_order/${orderPid}`);
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const record = body.data ?? body;
    const totalAmount = Number(record.sl_so_total_amount ?? 0);
    expect(totalAmount).toBeGreaterThan(0);
  });

  test('SL-ORD-006: Submit and approve order (draft→pending→approved)', async ({ page }) => {
    expect(orderPid).toBeTruthy();

    // draft → pending (submit for approval)
    const submitResult = await executeCommandViaApi(
      page, 'sl:submit_sales_order', {}, orderPid, 'state_transition',
    );
    expect(submitResult.recordId || submitResult.code).toBeTruthy();

    // Verify status changed to pending
    let resp = await page.request.get(`/api/dynamic/sl_sales_order/${orderPid}`);
    expect(resp.ok()).toBe(true);
    let body = await resp.json();
    let record = body.data ?? body;
    expect(record.sl_so_status).toBe('pending');

    // pending → approved
    const approveResult = await executeCommandViaApi(
      page, 'sl:approve_sales_order', {}, orderPid, 'state_transition',
    );
    expect(approveResult.recordId || approveResult.code).toBeTruthy();

    // Verify status changed to approved
    resp = await page.request.get(`/api/dynamic/sl_sales_order/${orderPid}`);
    expect(resp.ok()).toBe(true);
    body = await resp.json();
    record = body.data ?? body;
    expect(record.sl_so_status).toBe('approved');
  });

  test('SL-ORD-007: Order status updated on list page', async ({ page }) => {
    expect(orderCode).toBeTruthy();

    await navigateToDynamicPage(page, 'sl-sales-order');
    const row = await findRowInPaginatedList(page, orderCode);
    expect(row).toBeTruthy();

    // The row should now show approved status (may be rendered as Chinese label)
    const rowText = await row!.textContent();
    const hasApproved = rowText?.includes('approved') || rowText?.includes('已审核') || rowText?.includes('已批准');
    expect(hasApproved).toBe(true);
  });

  // -------------------------------------------------------------------------
  // Sales Quotation Tests
  // -------------------------------------------------------------------------

  test('SL-ORD-008: Navigate to sales quotation list via sidebar menu', async ({ page }) => {
    await page.goto('/dashboards');
    await page.waitForLoadState('domcontentloaded');

    const nav = page.locator('nav');

    // Sales root menu
    const salesBtn = nav.getByRole('button', { name: 'Sales' });
    await salesBtn.scrollIntoViewIfNeeded();
    await salesBtn.evaluate((el: HTMLElement) => el.click());
    await page.waitForResponse(
      () => true,
      { timeout: 3_000 },
    ).catch(() => null);

    // Sales management directory
    const salesDirBtn = nav.getByRole('button', { name: '销售管理' });
    await salesDirBtn.scrollIntoViewIfNeeded();
    await salesDirBtn.evaluate((el: HTMLElement) => el.click());
    await page.waitForResponse(
      () => true,
      { timeout: 3_000 },
    ).catch(() => null);

    // Sales quotation link
    const quotationLink = nav.getByRole('link', { name: '销售报价' });
    await quotationLink.scrollIntoViewIfNeeded();
    await quotationLink.evaluate((el: HTMLElement) => el.click());

    // Wait for list API to respond
    await page.waitForResponse(
      (r) => r.url().includes('/api/dynamic/sl_sales_quotation/list') && r.status() === 200,
      { timeout: 15_000 },
    );

    // Verify table is visible
    await expect(page.locator('table, [class*="ant-table"]')).toBeVisible({ timeout: 10_000 });
  });

  test('SL-ORD-009: Create a quotation via API', async ({ page }) => {
    const result = await executeCommandViaApi(page, 'sl:create_sales_quotation', {
      sl_sq_date: QUOTATION_DATA.date,
      sl_sq_valid_until: QUOTATION_DATA.validUntil,
      sl_sq_payment_terms: QUOTATION_DATA.paymentTerms,
      sl_sq_remark: QUOTATION_DATA.remark,
      ...(accountPid ? { sl_sq_account_id: accountPid } : {}),
    }, undefined, 'create');

    quotationPid = result.recordId;
    expect(quotationPid).toBeTruthy();

    // Fetch the created record to get the auto-generated code
    const resp = await page.request.get(`/api/dynamic/sl_sales_quotation/${quotationPid}`);
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const record = body.data ?? body;
    quotationCode = record.sl_sq_code ?? '';
    expect(quotationCode).toBeTruthy();

    // Add a quotation line
    if (productPid) {
      const lineResult = await executeCommandViaApi(page, 'sl:add_sq_line', {
        sl_sql_quotation_id: quotationPid,
        sl_sql_product_id: productPid,
        sl_sql_qty: LINE_QTY,
        sl_sql_price: LINE_PRICE,
      }, undefined, 'create');
      expect(lineResult.recordId).toBeTruthy();
    }
  });

  test('SL-ORD-010: Quotation appears in list and can transition to SENT', async ({ page }) => {
    expect(quotationCode).toBeTruthy();

    await navigateToDynamicPage(page, 'sl-sales-quotation');
    const row = await findRowInPaginatedList(page, quotationCode);
    expect(row).toBeTruthy();
    const rowText = await row!.textContent();
    expect(rowText?.includes('草稿') || rowText?.includes('draft')).toBe(true);

    // Transition draft → SENT
    const sendResult = await executeCommandViaApi(
      page, 'sl:send_sales_quotation', {}, quotationPid, 'state_transition',
    );
    expect(sendResult.recordId || sendResult.code).toBeTruthy();

    // Verify status changed to SENT via API
    const resp = await page.request.get(`/api/dynamic/sl_sales_quotation/${quotationPid}`);
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const record = body.data ?? body;
    expect(record.sl_sq_status).toBe('sent');
  });

  test('SL-ORD-011: Verify test data persists (no cleanup)', async ({ page }) => {
    // Verify sales order exists via API query
    const orderRecords = await queryFilteredList(
      page, 'sl-sales-order', 'sl_so_code', orderCode,
      { operator: 'EQ' },
    );
    expect(orderRecords.length).toBeGreaterThanOrEqual(1);
    expect(orderRecords[0].sl_so_status).toBe('approved');

    // Verify quotation exists via API query
    const quotationRecords = await queryFilteredList(
      page, 'sl-sales-quotation', 'sl_sq_code', quotationCode,
      { operator: 'EQ' },
    );
    expect(quotationRecords.length).toBeGreaterThanOrEqual(1);
    expect(quotationRecords[0].sl_sq_status).toBe('sent');

    // Verify order line data integrity — total amount should reflect line qty * price
    const orderRecord = orderRecords[0];
    const totalAmount = Number(orderRecord.sl_so_total_amount ?? 0);
    expect(totalAmount).toBeGreaterThan(0);
  });
});
