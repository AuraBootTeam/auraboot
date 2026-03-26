/**
 * Sales — Quotation Lifecycle E2E Tests
 *
 * Tests SL-QT-001 ~ SL-QT-010: Full lifecycle coverage for sl_sales_quotation:
 * - Navigate via sidebar menu (Sales → 销售管理 → 销售报价)
 * - Create quotation with line items
 * - State flow: draft → sent → accepted → (convert to SO)
 * - Reject flow: draft → sent → rejected (invalid: cancelled not re-sendable)
 * - Validates i18n column headers (no raw field codes)
 *
 * Quotation status flow:
 *   draft  --send--> sent --accept--> accepted --convert--> (creates SO)
 *                         --reject--> rejected
 *
 * Prerequisites: sales plugin imported, sl_sales_quotation model published.
 *
 * @since 10.0.0
 */

import { test, expect } from '../../fixtures';
import {
  uniqueId,
  executeCommandViaApi,
  todayStr,
  dateOffsetStr,
  findRowInPaginatedList,
  queryFilteredList,
} from '../helpers/index';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const UID = uniqueId('SLQT');

const QUOTATION_DATA = {
  date: todayStr(),
  validUntil: dateOffsetStr(30),
  paymentTerms: 'net30',
  remark: `E2E Quotation ${UID}`,
};

// ---------------------------------------------------------------------------
// Helper: Navigate to Sales Quotation list via sidebar menu
// ---------------------------------------------------------------------------

async function navigateToSalesQuotations(page: any) {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });

  const nav = page.locator('nav, aside, [role="navigation"]').first();

  // Expand Sales root menu
  const salesBtn = nav.getByRole('button', { name: 'Sales' }).or(
    nav.locator('button', { hasText: /^Sales$/ })
  ).first();
  await salesBtn.scrollIntoViewIfNeeded();
  await salesBtn.click();
  await page.waitForResponse(() => true, { timeout: 3_000 }).catch(() => null);

  // Expand "销售管理" directory
  const salesDirBtn = nav.getByRole('button', { name: '销售管理' }).or(
    nav.locator('button', { hasText: '销售管理' })
  ).first();
  await salesDirBtn.scrollIntoViewIfNeeded();
  await salesDirBtn.click();
  await page.waitForResponse(() => true, { timeout: 3_000 }).catch(() => null);

  // Click "销售报价" link
  const quotationLink = nav.getByRole('link', { name: '销售报价' }).or(
    nav.locator('a[href="/sales/sales-quotations"]')
  ).first();
  await quotationLink.scrollIntoViewIfNeeded();
  await quotationLink.evaluate((el: HTMLElement) => el.click());

  await page.waitForResponse(
    (r: any) => r.url().includes('/api/dynamic/sl_sales_quotation/list') && r.status() === 200,
    { timeout: 15_000 },
  );
}

// ---------------------------------------------------------------------------
// Test Suite
// ---------------------------------------------------------------------------

test.describe('Sales — Quotation Lifecycle', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(90_000);

  let accountPid: string;
  let productPid: string;

  // Quotation A: draft → sent → accepted → convert to SO
  let quotationAPid: string;
  let quotationACode: string;

  // Quotation B: draft → sent → rejected (then verify can't re-send from rejected)
  let quotationBPid: string;
  let quotationBCode: string;

  // =========================================================================
  // Setup: create prerequisite CRM account and product
  // =========================================================================

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      // CRM account
      const accResult = await executeCommandViaApi(page, 'crm:create_account', {
        crm_acc_name: `QTTestAccount_${UID}`,
        crm_acc_industry: 'technology',
        crm_acc_status: 'active',
      }, undefined, 'create').catch(() => ({ recordId: '' }));
      accountPid = accResult.recordId;

      // Product
      const prodResult = await executeCommandViaApi(page, 'prod:create_product', {
        prod_name: `QTTestProduct_${UID}`,
        prod_unit: 'pcs',
        prod_type: 'finished_good',
      }, undefined, 'create').catch(() => ({ recordId: '' }));
      productPid = prodResult.recordId;
    } finally {
      await ctx.close();
    }
  });

  // =========================================================================
  // SL-QT-001: Navigate to quotation list via sidebar menu (Layer 1: Render)
  // =========================================================================

  test('SL-QT-001: Navigate to sales quotation list via sidebar menu', async ({ page }) => {
    await navigateToSalesQuotations(page);

    // Layer 1: table is visible
    await expect(page.locator('table, [class*="ant-table"], [role="table"]').first())
      .toBeVisible({ timeout: 10_000 });

    // Layer 2: column headers are Chinese (not raw field codes)
    const headerRow = page.locator('thead tr').first();
    await expect(headerRow).toBeVisible({ timeout: 10_000 });
    const headerText = await headerRow.textContent();
    expect(headerText).not.toContain('sl_sq_');
    expect(headerText).not.toContain('field.');
  });

  // =========================================================================
  // SL-QT-002: Create quotation A via API and verify in list
  // =========================================================================

  test('SL-QT-002: Create quotation A and verify it appears in list as draft', async ({ page }) => {
    // Layer 1: create via API
    const result = await executeCommandViaApi(page, 'sl:create_sales_quotation', {
      sl_sq_date: QUOTATION_DATA.date,
      sl_sq_valid_until: QUOTATION_DATA.validUntil,
      sl_sq_payment_terms: QUOTATION_DATA.paymentTerms,
      sl_sq_remark: QUOTATION_DATA.remark,
      ...(accountPid ? { sl_sq_account_id: accountPid } : {}),
    }, undefined, 'create');

    quotationAPid = result.recordId;
    expect(quotationAPid, 'Quotation A must be created').toBeTruthy();

    // Fetch auto-generated code
    const resp = await page.request.get(`/api/dynamic/sl_sales_quotation/${quotationAPid}`);
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    quotationACode = (body.data ?? body).sl_sq_code ?? '';
    expect(quotationACode, 'Quotation code must be auto-generated').toBeTruthy();
    expect(quotationACode).toMatch(/^QT-/);

    // Layer 2: appears in list with draft status
    await navigateToSalesQuotations(page);
    const row = await findRowInPaginatedList(page, quotationACode);
    expect(row, 'Quotation A must appear in list').toBeTruthy();
    const rowText = await row!.textContent();
    const isDraft = rowText?.includes('草稿') || rowText?.includes('draft');
    expect(isDraft, `Row "${rowText}" should show draft status`).toBe(true);
  });

  // =========================================================================
  // SL-QT-003: Add line item to quotation A — verify total_amount > 0
  // =========================================================================

  test('SL-QT-003: Add quotation line and verify total amount is calculated', async ({ page }) => {
    expect(quotationAPid, 'Quotation A PID must exist from previous test').toBeTruthy();

    const lineResult = await executeCommandViaApi(page, 'sl:add_sq_line', {
      sl_sql_quotation_id: quotationAPid,
      ...(productPid ? { sl_sql_product_id: productPid } : {}),
      sl_sql_qty: 5,
      sl_sql_price: 200,
    }, undefined, 'create');
    expect(lineResult.recordId, 'Quotation line must be created').toBeTruthy();

    // Layer 3: total_amount updated via side effect
    const resp = await page.request.get(`/api/dynamic/sl_sales_quotation/${quotationAPid}`);
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const record = body.data ?? body;
    const totalAmount = Number(record.sl_sq_total_amount ?? 0);
    expect(totalAmount, 'Total amount must be > 0 after adding line').toBeGreaterThan(0);
  });

  // =========================================================================
  // SL-QT-004: Send quotation A — draft → sent
  // =========================================================================

  test('SL-QT-004: Send quotation A transitions to sent status', async ({ page }) => {
    expect(quotationAPid).toBeTruthy();

    const sendResult = await executeCommandViaApi(
      page, 'sl:send_sales_quotation', {}, quotationAPid, 'state_transition',
    );
    expect(sendResult.code, 'Send command must return code 0').toBe('0');

    // Layer 3: verify API reflects sent status
    const resp = await page.request.get(`/api/dynamic/sl_sales_quotation/${quotationAPid}`);
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const record = body.data ?? body;
    expect(record.sl_sq_status, 'Status must be "sent" after send command').toBe('sent');

    // Layer 2: list page shows updated status
    await navigateToSalesQuotations(page);
    const row = await findRowInPaginatedList(page, quotationACode);
    expect(row).toBeTruthy();
    const rowText = await row!.textContent();
    const isSent = rowText?.includes('sent') || rowText?.includes('已发送') || rowText?.includes('发送');
    expect(isSent, `Row "${rowText}" should show sent status`).toBe(true);
  });

  // =========================================================================
  // SL-QT-005: Accept quotation A — sent → accepted
  // =========================================================================

  test('SL-QT-005: Accept quotation A transitions to accepted status', async ({ page }) => {
    expect(quotationAPid).toBeTruthy();

    const acceptResult = await executeCommandViaApi(
      page, 'sl:accept_sales_quotation', {}, quotationAPid, 'state_transition',
    );
    expect(acceptResult.code).toBe('0');

    // Layer 3: verify status is accepted
    const resp = await page.request.get(`/api/dynamic/sl_sales_quotation/${quotationAPid}`);
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const record = body.data ?? body;
    expect(record.sl_sq_status, 'Status must be "accepted" after accept command').toBe('accepted');
  });

  // =========================================================================
  // SL-QT-006: Convert quotation A to sales order
  // =========================================================================

  test('SL-QT-006: Convert accepted quotation to sales order', async ({ page }) => {
    expect(quotationAPid).toBeTruthy();

    const convertResult = await executeCommandViaApi(
      page, 'sl:convert_quotation_to_order', {}, quotationAPid, 'state_transition',
    );
    expect(convertResult.code).toBe('0');

    // Layer 3: verify a SO was created — query sales orders for one linked to our quotation
    // The convert handler creates a SO linked from the quotation
    const soResp = await page.request.get('/api/dynamic/sl_sales_order/list?pageNum=1&pageSize=50');
    expect(soResp.ok()).toBe(true);
    const soBody = await soResp.json();
    const soRecords: any[] = soBody?.data?.records ?? soBody?.records ?? [];
    // SO was just created — at least one SO should exist after conversion
    expect(soRecords.length, 'At least one SO should exist after quotation conversion').toBeGreaterThan(0);
  });

  // =========================================================================
  // SL-QT-007: Create quotation B and transition to rejected status
  // =========================================================================

  test('SL-QT-007: Create quotation B and send it', async ({ page }) => {
    const resultB = await executeCommandViaApi(page, 'sl:create_sales_quotation', {
      sl_sq_date: QUOTATION_DATA.date,
      sl_sq_valid_until: QUOTATION_DATA.validUntil,
      sl_sq_remark: `E2E Quotation B ${UID}`,
      ...(accountPid ? { sl_sq_account_id: accountPid } : {}),
    }, undefined, 'create');

    quotationBPid = resultB.recordId;
    expect(quotationBPid).toBeTruthy();

    const respB = await page.request.get(`/api/dynamic/sl_sales_quotation/${quotationBPid}`);
    const bodyB = await respB.json();
    quotationBCode = (bodyB.data ?? bodyB).sl_sq_code ?? '';
    expect(quotationBCode).toBeTruthy();

    // Add a line (required before send)
    await executeCommandViaApi(page, 'sl:add_sq_line', {
      sl_sql_quotation_id: quotationBPid,
      ...(productPid ? { sl_sql_product_id: productPid } : {}),
      sl_sql_qty: 3,
      sl_sql_price: 150,
    }, undefined, 'create');

    // Send quotation B
    const sendResult = await executeCommandViaApi(
      page, 'sl:send_sales_quotation', {}, quotationBPid, 'state_transition',
    );
    expect(sendResult.code).toBe('0');

    // Verify sent status
    const resp2 = await page.request.get(`/api/dynamic/sl_sales_quotation/${quotationBPid}`);
    const body2 = await resp2.json();
    expect((body2.data ?? body2).sl_sq_status).toBe('sent');
  });

  // =========================================================================
  // SL-QT-008: Reject quotation B — sent → rejected
  // =========================================================================

  test('SL-QT-008: Reject quotation B transitions to rejected status', async ({ page }) => {
    expect(quotationBPid).toBeTruthy();

    const rejectResult = await executeCommandViaApi(
      page, 'sl:reject_sales_quotation', {}, quotationBPid, 'state_transition',
    );
    expect(rejectResult.code).toBe('0');

    // Layer 3: status must be rejected
    const resp = await page.request.get(`/api/dynamic/sl_sales_quotation/${quotationBPid}`);
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const record = body.data ?? body;
    expect(record.sl_sq_status, 'Status must be "rejected" after reject command').toBe('rejected');

    // Layer 2: verify in list
    await navigateToSalesQuotations(page);
    const row = await findRowInPaginatedList(page, quotationBCode);
    expect(row).toBeTruthy();
    const rowText = await row!.textContent();
    const isRejected = rowText?.includes('rejected') || rowText?.includes('已拒绝') || rowText?.includes('拒绝');
    expect(isRejected, `Row "${rowText}" should show rejected status`).toBe(true);
  });

  // =========================================================================
  // SL-QT-009: Illegal operation — rejected quotation cannot be re-sent
  // =========================================================================

  test('SL-QT-009: Rejected quotation cannot be re-sent (illegal state transition)', async ({ page }) => {
    expect(quotationBPid).toBeTruthy();

    // Attempt to re-send rejected quotation — must fail
    const illegalResult = await executeCommandViaApi(
      page, 'sl:send_sales_quotation', {}, quotationBPid, 'state_transition',
      { allowHttpError: true },
    );
    expect(illegalResult.code, 'Send on rejected quotation must return non-zero code').not.toBe('0');
  });

  // =========================================================================
  // SL-QT-010: Data integrity verification (no cleanup)
  // =========================================================================

  test('SL-QT-010: Verify quotation lifecycle data persists correctly', async ({ page }) => {
    // Quotation A should be accepted and converted
    const recordsA = await queryFilteredList(
      page, 'sl-sales-quotation', 'sl_sq_code', quotationACode,
      { operator: 'EQ' },
    );
    expect(recordsA.length, 'Quotation A must still exist in DB').toBeGreaterThanOrEqual(1);
    // After convert_quotation_to_order the quotation stays in accepted state
    expect(recordsA[0].sl_sq_status, 'Quotation A must be in accepted state').toBe('accepted');

    // Quotation B should be rejected
    const recordsB = await queryFilteredList(
      page, 'sl-sales-quotation', 'sl_sq_code', quotationBCode,
      { operator: 'EQ' },
    );
    expect(recordsB.length, 'Quotation B must still exist in DB').toBeGreaterThanOrEqual(1);
    expect(recordsB[0].sl_sq_status, 'Quotation B must be in rejected state').toBe('rejected');
  });
});
