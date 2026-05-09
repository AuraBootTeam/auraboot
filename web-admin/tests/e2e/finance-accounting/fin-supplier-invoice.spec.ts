/**
 * Finance — Supplier Invoice E2E Tests
 *
 * Tests SI-001 ~ SI-006: Full lifecycle for fin_supplier_invoice:
 * - SI-001 @smoke: Navigate via sidebar menu → table visible, i18n headers
 * - SI-002 @critical: Created invoice appears in list with draft status
 * - SI-003 @critical: Submit invoice draft → pending
 * - SI-004 @critical: Approve invoice pending → approved
 * - SI-005 @critical: Pay invoice approved → paid
 * - SI-006 @critical: Reject flow — create → submit → reject → rejected
 *
 * Prerequisites: finance plugin must be imported and all models published.
 *
 * @since 9.0.0
 */

import { test, expect, type Page } from '../../fixtures';
import {
  uniqueId,
  executeCommandViaApi,
  queryFilteredList,
  findRowInPaginatedList,
} from '../helpers/index';
import { BASE_URL } from '../../helpers/environments';

/** Navigate to the supplier invoice list page, setting up waitForResponse BEFORE goto. */
async function gotoInvoiceList(page: Page): Promise<void> {
  const listResponse = page.waitForResponse(
    (r) => r.url().includes('/api/dynamic/fin_supplier_invoice/list') && r.status() === 200,
    { timeout: 15_000 },
  );
  await page.goto(`${BASE_URL}/finance/supplier-invoices`);
  await listResponse;
  await expect(page.locator('table, [class*="ant-table"]')).toBeVisible({ timeout: 10_000 });
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const UID = uniqueId('SI');
const UID_REJECT = uniqueId('si_rej');

const INVOICE_DATA = {
  invoice_no: `INV-${UID}`,
  invoice_date: '2026-01-15',
  due_date: '2026-02-15',
  total_amount: 10000.0,
  tax_amount: 600.0,
  currency: 'cny',
  remark: `E2E lifecycle invoice ${UID}`,
};

const REJECT_INVOICE_DATA = {
  invoice_no: `INV-${UID_REJECT}`,
  invoice_date: '2026-01-20',
  due_date: '2026-02-20',
  total_amount: 5000.0,
  tax_amount: 300.0,
  currency: 'cny',
  remark: `E2E reject flow invoice ${UID_REJECT}`,
};

// ---------------------------------------------------------------------------
// Test suite
// ---------------------------------------------------------------------------

test.describe('Finance — Supplier Invoice', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60_000);

  let mainInvoicePid: string;
  let rejectInvoicePid: string;

  // -------------------------------------------------------------------------
  // Setup: Create 2 invoices via API
  // -------------------------------------------------------------------------

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();

    // Create main lifecycle invoice
    const mainResult = await executeCommandViaApi(
      page,
      'fin:create_supplier_invoice',
      {
        fin_si_invoice_no: INVOICE_DATA.invoice_no,
        fin_si_invoice_date: INVOICE_DATA.invoice_date,
        fin_si_due_date: INVOICE_DATA.due_date,
        fin_si_total_amount: INVOICE_DATA.total_amount,
        fin_si_tax_amount: INVOICE_DATA.tax_amount,
        fin_si_currency: INVOICE_DATA.currency,
        fin_si_remark: INVOICE_DATA.remark,
      },
      undefined,
      'create',
    );
    mainInvoicePid = mainResult.recordId;

    // Create reject-flow invoice
    const rejectResult = await executeCommandViaApi(
      page,
      'fin:create_supplier_invoice',
      {
        fin_si_invoice_no: REJECT_INVOICE_DATA.invoice_no,
        fin_si_invoice_date: REJECT_INVOICE_DATA.invoice_date,
        fin_si_due_date: REJECT_INVOICE_DATA.due_date,
        fin_si_total_amount: REJECT_INVOICE_DATA.total_amount,
        fin_si_tax_amount: REJECT_INVOICE_DATA.tax_amount,
        fin_si_currency: REJECT_INVOICE_DATA.currency,
        fin_si_remark: REJECT_INVOICE_DATA.remark,
      },
      undefined,
      'create',
    );
    rejectInvoicePid = rejectResult.recordId;

    await ctx.close();
  });

  // -------------------------------------------------------------------------
  // SI-001 @smoke: Navigate to 供应商发票 via sidebar menu
  // -------------------------------------------------------------------------

  test('SI-001 @smoke: Navigate to 供应商发票 via Finance → 财务管理 → 供应商发票', async ({
    page,
  }) => {
    await page.goto('/dashboards');
    await page.waitForLoadState('domcontentloaded');

    const nav = page.locator('nav');

    // Click root Finance button
    const financeRootBtn = nav.getByRole('button', { name: 'Finance' });
    const financeRootBtnAlt = nav.getByRole('button', { name: '财务' });

    // Try "Finance" first, fall back to "财务" variant
    const hasFinanceRoot = await financeRootBtn.isVisible({ timeout: 3000 }).catch(() => false);
    if (hasFinanceRoot) {
      await financeRootBtn.evaluate((el: HTMLElement) => el.click());
    } else {
      await financeRootBtnAlt.evaluate((el: HTMLElement) => el.click());
    }

    // Click 财务管理 sub-directory button
    const caiwuMgrBtn = nav.getByRole('button', { name: '财务管理' });
    await caiwuMgrBtn.waitFor({ state: 'visible', timeout: 5000 });
    await caiwuMgrBtn.evaluate((el: HTMLElement) => el.click());

    // Click 供应商发票 leaf link
    const invoiceLink = nav.getByRole('link', { name: '供应商发票' });
    await invoiceLink.waitFor({ state: 'visible', timeout: 5000 });

    const listResponsePromise = page.waitForResponse(
      (r) => r.url().includes('/api/dynamic/fin_supplier_invoice/list') && r.status() === 200,
      { timeout: 15_000 },
    );
    await invoiceLink.evaluate((el: HTMLElement) => el.click());
    await listResponsePromise;

    // Table must be visible
    await expect(page.locator('table, [class*="ant-table"]')).toBeVisible({ timeout: 10_000 });

    // i18n: headers must not contain raw fin_si_ keys
    const headerRow = page.locator('thead tr').first();
    await expect(headerRow).toBeVisible({ timeout: 5_000 });
    const headerText = await headerRow.textContent();
    expect(headerText).not.toMatch(/fin_si_/i);
  });

  // -------------------------------------------------------------------------
  // SI-002 @critical: Created invoice appears in list with draft status
  // -------------------------------------------------------------------------

  test('SI-002 @critical: Created invoice appears in list with draft status', async ({ page }) => {
    expect(mainInvoicePid).toBeTruthy();

    // Query via API for reliability
    const records = await queryFilteredList(
      page,
      'fin_supplier_invoice',
      'fin_si_invoice_no',
      INVOICE_DATA.invoice_no,
      { operator: 'EQ' },
    );
    expect(records.length).toBeGreaterThan(0);

    const record = records[0] as Record<string, unknown>;
    const status = record.fin_si_status as string;
    expect(status === 'draft' || status === '草稿').toBe(true);

    // Also verify on list UI — waitForResponse set up BEFORE navigation
    await gotoInvoiceList(page);

    const row = await findRowInPaginatedList(page, INVOICE_DATA.invoice_no);
    await expect(row).toBeVisible({ timeout: 5_000 });
  });

  // -------------------------------------------------------------------------
  // SI-003 @critical: Submit invoice draft → pending
  // -------------------------------------------------------------------------

  test('SI-003 @critical: Submit invoice draft → pending', async ({ page }) => {
    expect(mainInvoicePid).toBeTruthy();

    await executeCommandViaApi(
      page,
      'fin:submit_supplier_invoice',
      {},
      mainInvoicePid,
      'state_transition',
    );

    // Verify via API
    const records = await queryFilteredList(
      page,
      'fin_supplier_invoice',
      'fin_si_invoice_no',
      INVOICE_DATA.invoice_no,
      { operator: 'EQ' },
    );
    expect(records.length).toBeGreaterThan(0);

    const record = records[0] as Record<string, unknown>;
    const status = record.fin_si_status as string;
    expect(status === 'pending' || status === '待审批' || status === '待审核').toBe(true);

    // Verify on list UI — waitForResponse set up BEFORE navigation
    await gotoInvoiceList(page);

    const row = await findRowInPaginatedList(page, INVOICE_DATA.invoice_no);
    await expect(row).toBeVisible({ timeout: 5_000 });
    const rowText = await row.textContent();
    expect(
      rowText?.includes('pending') || rowText?.includes('待审批') || rowText?.includes('待审核'),
    ).toBe(true);
  });

  // -------------------------------------------------------------------------
  // SI-004 @critical: Approve invoice pending → approved
  // -------------------------------------------------------------------------

  test('SI-004 @critical: Approve invoice pending → approved', async ({ page }) => {
    expect(mainInvoicePid).toBeTruthy();

    await executeCommandViaApi(
      page,
      'fin:approve_supplier_invoice',
      {},
      mainInvoicePid,
      'state_transition',
    );

    // Verify via API
    const records = await queryFilteredList(
      page,
      'fin_supplier_invoice',
      'fin_si_invoice_no',
      INVOICE_DATA.invoice_no,
      { operator: 'EQ' },
    );
    expect(records.length).toBeGreaterThan(0);

    const record = records[0] as Record<string, unknown>;
    const status = record.fin_si_status as string;
    expect(status === 'approved' || status === '已审批' || status === '已批准').toBe(true);
  });

  // -------------------------------------------------------------------------
  // SI-005 @critical: Pay invoice approved → paid
  // -------------------------------------------------------------------------

  test('SI-005 @critical: Pay invoice approved → paid', async ({ page }) => {
    expect(mainInvoicePid).toBeTruthy();

    await executeCommandViaApi(
      page,
      'fin:pay_supplier_invoice',
      {},
      mainInvoicePid,
      'state_transition',
    );

    // Verify via API
    const records = await queryFilteredList(
      page,
      'fin_supplier_invoice',
      'fin_si_invoice_no',
      INVOICE_DATA.invoice_no,
      { operator: 'EQ' },
    );
    expect(records.length).toBeGreaterThan(0);

    const record = records[0] as Record<string, unknown>;
    const status = record.fin_si_status as string;
    expect(status === 'paid' || status === '已付款').toBe(true);

    // Verify on list UI — waitForResponse set up BEFORE navigation
    await gotoInvoiceList(page);

    const row = await findRowInPaginatedList(page, INVOICE_DATA.invoice_no);
    await expect(row).toBeVisible({ timeout: 5_000 });
    const rowText = await row.textContent();
    expect(rowText?.includes('paid') || rowText?.includes('已付款')).toBe(true);
  });

  // -------------------------------------------------------------------------
  // SI-006 @critical: Reject flow — create → submit → reject → rejected
  // -------------------------------------------------------------------------

  test('SI-006 @critical: Reject flow — submit then reject → rejected', async ({ page }) => {
    expect(rejectInvoicePid).toBeTruthy();

    // Submit the reject-flow invoice
    await executeCommandViaApi(
      page,
      'fin:submit_supplier_invoice',
      {},
      rejectInvoicePid,
      'state_transition',
    );

    // Verify it is now pending before rejection
    const pendingRecords = await queryFilteredList(
      page,
      'fin_supplier_invoice',
      'fin_si_invoice_no',
      REJECT_INVOICE_DATA.invoice_no,
      { operator: 'EQ' },
    );
    expect(pendingRecords.length).toBeGreaterThan(0);
    const pendingStatus = (pendingRecords[0] as Record<string, unknown>).fin_si_status as string;
    expect(
      pendingStatus === 'pending' || pendingStatus === '待审批' || pendingStatus === '待审核',
    ).toBe(true);

    // Reject the invoice
    await executeCommandViaApi(
      page,
      'fin:reject_supplier_invoice',
      {},
      rejectInvoicePid,
      'state_transition',
    );

    // Verify via API — status must be rejected
    const rejectedRecords = await queryFilteredList(
      page,
      'fin_supplier_invoice',
      'fin_si_invoice_no',
      REJECT_INVOICE_DATA.invoice_no,
      { operator: 'EQ' },
    );
    expect(rejectedRecords.length).toBeGreaterThan(0);

    const finalStatus = (rejectedRecords[0] as Record<string, unknown>).fin_si_status as string;
    expect(finalStatus === 'rejected' || finalStatus === '已拒绝' || finalStatus === '已驳回').toBe(
      true,
    );

    // Verify on list UI — waitForResponse set up BEFORE navigation
    await gotoInvoiceList(page);

    const row = await findRowInPaginatedList(page, REJECT_INVOICE_DATA.invoice_no);
    await expect(row).toBeVisible({ timeout: 5_000 });
    const rowText = await row.textContent();
    expect(
      rowText?.includes('rejected') || rowText?.includes('已拒绝') || rowText?.includes('已驳回'),
    ).toBe(true);
  });
});
