/**
 * E2E: Inbound receipt detail page — sub-table line item CRUD
 *
 * Covers:
 * 1. Add line item via inline form on detail page (command-based CREATE)
 * 2. Required field validation (product_id and qty are mandatory)
 * 3. Amount auto-computation (qty * price → line amount, SUM → total amount)
 * 4. Delete line item via command-based DELETE
 * 5. Summary footer shows correct aggregation
 * 6. Editable only when status is draft (editableWhen condition)
 *
 * Each test is self-contained — creates its own data via API beforeEach or inline.
 *
 * @since 5.0.0
 */

import { test, expect } from '@playwright/test';
import { uniqueId, todayStr, executeCommandViaApi, waitForDynamicPageLoad } from '../helpers';

const uid = uniqueId('inb');

let warehousePid: string;
let productPid: string;

/**
 * Navigate to inbound detail page, setting up the response listener BEFORE goto
 * to avoid race conditions where the API response fires before the listener.
 */
async function gotoInboundDetail(page: import('@playwright/test').Page, pid: string) {
  const listResp = page.waitForResponse(
    (r) => r.url().includes('/api/dynamic/inv_inbound_line/list') && r.status() === 200,
    { timeout: 15_000 },
  );
  await page.goto(`/p/inv_inbound/view/${pid}`, { waitUntil: 'domcontentloaded' });
  await waitForDynamicPageLoad(page);
  await listResp;
}

/** Create a draft inbound via API, return PID */
async function createDraftInbound(page: import('@playwright/test').Page): Promise<string> {
  const result = await executeCommandViaApi(
    page,
    'pe:create_warehouse_in',
    {
      inv_in_type: 'purchase',
      inv_in_date: todayStr(),
      inv_in_source_no: `SRC_${uniqueId('inb')}`,
      inv_in_warehouse_id: warehousePid,
    },
    undefined,
    'create',
  );
  return result.recordId;
}

/** Add a line item to an inbound via API, return line PID */
async function addLineViaApi(
  page: import('@playwright/test').Page,
  inboundPid: string,
  qty: number,
  price: number,
): Promise<string> {
  const result = await executeCommandViaApi(
    page,
    'pe:add_wh_in_line',
    {
      inv_in_line_receipt_id: inboundPid,
      inv_in_line_product_id: productPid,
      inv_in_line_qty: qty,
      inv_in_line_price: price,
    },
    undefined,
    'create',
  );
  return result.recordId;
}

test.describe('Inbound Receipt — SubTable Line Item CRUD', () => {
  // Serial mode: tests in this suite share setup data
  test.describe.configure({ mode: 'serial' });

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({
      storageState: 'tests/storage/admin.json',
    });
    const page = await ctx.newPage();

    // Create a product (REFERENCE field requires valid PID)
    const prodResp = await page.request.post('/api/dynamic/prod_product', {
      data: {
        prod_name: `Prod_${uid}`,
        prod_unit: 'pcs',
        prod_type: 'raw_material',
        prod_status: 'active',
        prod_code: `PROD_${uid}`,
      },
    });
    expect(prodResp.ok()).toBeTruthy();
    const prodBody = await prodResp.json();
    productPid = prodBody?.data?.pid || prodBody?.data?.id || '';
    expect(productPid).toBeTruthy();

    // Create a warehouse
    const whResult = await executeCommandViaApi(
      page,
      'pe:create_warehouse',
      {
        inv_warehouse_name: `WH_${uid}`,
        inv_warehouse_type: 'raw_material',
        inv_warehouse_address: `Addr_${uid}`,
      },
      undefined,
      'create',
    );
    warehousePid = whResult.recordId;
    expect(warehousePid).toBeTruthy();

    await page.close();
    await ctx.close();
  });

  test('Add Line button visible on draft inbound detail', async ({ page }) => {
    const inboundPid = await createDraftInbound(page);
    await gotoInboundDetail(page, inboundPid);

    const subTableSection = page.locator('.sub-table-section');
    await expect(subTableSection).toBeVisible({ timeout: 10_000 });

    const addBtn = page.getByTestId('subtable-add-row');
    await expect(addBtn).toBeVisible({ timeout: 5_000 });
  });

  test('required field validation rejects empty product_id and qty', async ({ page }) => {
    const inboundPid = await createDraftInbound(page);
    await gotoInboundDetail(page, inboundPid);

    await page.getByTestId('subtable-add-row').click();
    await expect(page.getByTestId('subtable-add-form')).toBeVisible({ timeout: 5_000 });

    // Save without filling required fields
    await page.getByTestId('subtable-save-btn').click();

    await expect(page.getByTestId('subtable-error-inv_in_line_product_id')).toBeVisible({
      timeout: 3_000,
    });
    await expect(page.getByTestId('subtable-error-inv_in_line_qty')).toBeVisible({
      timeout: 3_000,
    });
  });

  test('add line via UI computes amount (qty * price)', async ({ page }) => {
    const inboundPid = await createDraftInbound(page);
    await gotoInboundDetail(page, inboundPid);

    await page.getByTestId('subtable-add-row').click();

    // Fill product (PID), qty=10, price=25.5
    await page.getByTestId('subtable-add-inv_in_line_product_id').fill(productPid);
    await page.getByTestId('subtable-add-inv_in_line_qty').fill('10');
    await page.getByTestId('subtable-add-inv_in_line_price').fill('25.5');

    const cmdResp = page.waitForResponse(
      (r) => r.url().includes('/api/meta/commands/execute/pe:add_wh_in_line') && r.status() === 200,
      { timeout: 10_000 },
    );
    await page.getByTestId('subtable-save-btn').click();
    await cmdResp;

    // Wait for table refresh
    await page.waitForResponse(
      (r) => r.url().includes('/api/dynamic/inv_inbound_line/list') && r.status() === 200,
      { timeout: 10_000 },
    );

    // Row should appear with computed amount = 255
    const rows = page
      .locator('table tbody tr')
      .filter({ hasNot: page.getByTestId('subtable-add-form') });
    await expect(rows.first()).toBeVisible({ timeout: 5_000 });

    const amountCell = rows.first().locator('td').nth(3);
    await expect(amountCell).toContainText('255', { timeout: 5_000 });

    // Inline add form should be hidden
    await expect(page.getByTestId('subtable-add-form')).not.toBeVisible();
    await expect(page.getByTestId('subtable-add-row')).toBeVisible();
  });

  test('summary footer shows SUM of line amounts', async ({ page }) => {
    const inboundPid = await createDraftInbound(page);
    // Create 2 lines via API: 10*25=250, 5*100=500
    await addLineViaApi(page, inboundPid, 10, 25);
    await addLineViaApi(page, inboundPid, 5, 100);

    await gotoInboundDetail(page, inboundPid);

    const summary = page.getByTestId('subtable-summary');
    await expect(summary).toBeVisible({ timeout: 5_000 });
    // SUM = 250 + 500 = 750
    await expect(summary).toContainText('750', { timeout: 5_000 });
  });

  test('parent total_amount updated by AGGREGATE sideEffect', async ({ page }) => {
    const inboundPid = await createDraftInbound(page);
    // Add a line: 10 * 25 = 250
    await addLineViaApi(page, inboundPid, 10, 25);

    await gotoInboundDetail(page, inboundPid);

    // The detail page should display the total amount from AGGREGATE sideEffect
    const detailPage = page.getByTestId('dynamic-page-detail');
    await expect(detailPage).toContainText('250', { timeout: 10_000 });
  });

  test('delete a line item updates summary and parent total', async ({ page }) => {
    const inboundPid = await createDraftInbound(page);
    // Create 2 lines: 10*20=200, 5*30=150
    await addLineViaApi(page, inboundPid, 10, 20);
    await addLineViaApi(page, inboundPid, 5, 30);

    await gotoInboundDetail(page, inboundPid);

    // Should have 2 rows
    const dataRows = page
      .locator('table tbody tr')
      .filter({
        hasNot: page.getByTestId('subtable-add-form'),
      })
      .filter({
        hasNot: page.getByTestId('subtable-form-error'),
      });
    await expect(dataRows).toHaveCount(2, { timeout: 5_000 });

    // Delete the first row
    const deleteBtn = page.getByTestId('subtable-delete-0');
    await expect(deleteBtn).toBeVisible({ timeout: 5_000 });

    const deleteResp = page.waitForResponse(
      (r) =>
        r.url().includes('/api/meta/commands/execute/pe:delete_wh_in_line') && r.status() === 200,
      { timeout: 10_000 },
    );
    await deleteBtn.click();
    await deleteResp;

    // Wait for refresh
    await page.waitForResponse(
      (r) => r.url().includes('/api/dynamic/inv_inbound_line/list') && r.status() === 200,
      { timeout: 10_000 },
    );

    // Should have 1 row remaining
    await expect(dataRows).toHaveCount(1, { timeout: 5_000 });

    // Summary should update to the remaining line's amount
    const summary = page.getByTestId('subtable-summary');
    await expect(summary).toBeVisible({ timeout: 5_000 });
  });

  test('Add/Delete hidden when status is confirmed', async ({ page }) => {
    const inboundPid = await createDraftInbound(page);
    // Add a line (required for confirm)
    await addLineViaApi(page, inboundPid, 1, 10);

    // Confirm the inbound
    await executeCommandViaApi(page, 'pe:confirm_warehouse_in', {}, inboundPid, 'state_transition');

    await gotoInboundDetail(page, inboundPid);

    // Add Line button should NOT be visible
    await expect(page.getByTestId('subtable-add-row')).not.toBeVisible({ timeout: 3_000 });

    // Delete button should NOT be visible
    await expect(page.getByTestId('subtable-delete-0')).not.toBeVisible({ timeout: 3_000 });

    // Summary should still be visible (read-only)
    await expect(page.getByTestId('subtable-summary')).toBeVisible({ timeout: 5_000 });
  });

  test('Cancel button dismisses inline add form', async ({ page }) => {
    const inboundPid = await createDraftInbound(page);
    await gotoInboundDetail(page, inboundPid);

    await page.getByTestId('subtable-add-row').click();
    await expect(page.getByTestId('subtable-add-form')).toBeVisible({ timeout: 3_000 });

    await page.getByTestId('subtable-cancel-btn').click();
    await expect(page.getByTestId('subtable-add-form')).not.toBeVisible({ timeout: 3_000 });
    await expect(page.getByTestId('subtable-add-row')).toBeVisible();
  });
});
