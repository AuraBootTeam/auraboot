/**
 * E2E: Inbound receipt FORM (edit) page — sub-table line item CRUD
 *
 * Covers:
 * 1. Edit page shows SubTableViewer with command-based add/delete (not plain SubTable)
 * 2. Add line via inline form with required validation
 * 3. Amount auto-computation + summary footer
 * 4. Delete line item
 * 5. Create mode shows placeholder (no lines before record exists)
 *
 * @since 5.0.0
 */

import { test, expect } from '@playwright/test';
import {
  uniqueId,
  todayStr,
  executeCommandViaApi,
  waitForDynamicPageLoad,
  waitForFormReady,
} from '../helpers';

const uid = uniqueId('inbf');

let warehousePid: string;
let productPid: string;

test.describe('Inbound Receipt Form — SubTable CRUD on Edit Page', () => {
  test.describe.configure({ mode: 'serial' });

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({
      storageState: 'tests/storage/admin.json',
    });
    const page = await ctx.newPage();

    // Create product
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
    productPid = prodBody?.data?.pid || '';
    expect(productPid).toBeTruthy();

    // Create warehouse
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

  /** Create a draft inbound via API */
  async function createDraftInbound(page: import('@playwright/test').Page): Promise<string> {
    const result = await executeCommandViaApi(
      page,
      'pe:create_warehouse_in',
      {
        inv_in_type: 'purchase',
        inv_in_date: todayStr(),
        inv_in_source_no: `SRC_${uniqueId('inbf')}`,
        inv_in_warehouse_id: warehousePid,
      },
      undefined,
      'create',
    );
    return result.recordId;
  }

  /** Navigate to the edit page for an inbound record */
  async function gotoEditPage(page: import('@playwright/test').Page, pid: string) {
    const listResp = page.waitForResponse(
      (r) => r.url().includes('/api/dynamic/inv_inbound_line/list') && r.status() === 200,
      { timeout: 15_000 },
    );
    await page.goto(`/p/inv_inbound/${pid}/edit?commandCode=pe%3Aupdate_warehouse_in`, {
      waitUntil: 'domcontentloaded',
    });
    await waitForFormReady(page);
    await listResp;
  }

  test('edit page shows Add Line button with command-based sub-table', async ({ page }) => {
    const inboundPid = await createDraftInbound(page);
    await gotoEditPage(page, inboundPid);

    // SubTableViewer should render with Add Line button
    const addBtn = page.getByTestId('subtable-add-row');
    await expect(addBtn).toBeVisible({ timeout: 10_000 });

    // Actions column header should be visible
    const actionsHeader = page.locator('th').filter({ hasText: /Actions|操作/ });
    await expect(actionsHeader).toBeVisible({ timeout: 5_000 });
  });

  test('edit page validates required fields before adding line', async ({ page }) => {
    const inboundPid = await createDraftInbound(page);
    await gotoEditPage(page, inboundPid);

    await page.getByTestId('subtable-add-row').click();
    await expect(page.getByTestId('subtable-add-form')).toBeVisible({ timeout: 5_000 });

    // Save without required fields
    await page.getByTestId('subtable-save-btn').click();

    // Validation errors should appear
    await expect(page.getByTestId('subtable-error-inv_in_line_product_id')).toBeVisible({
      timeout: 3_000,
    });
    await expect(page.getByTestId('subtable-error-inv_in_line_qty')).toBeVisible({
      timeout: 3_000,
    });
  });

  test('edit page adds line via command with computed amount', async ({ page }) => {
    const inboundPid = await createDraftInbound(page);
    await gotoEditPage(page, inboundPid);

    await page.getByTestId('subtable-add-row').click();

    await page.getByTestId('subtable-add-inv_in_line_product_id').fill(productPid);
    await page.getByTestId('subtable-add-inv_in_line_qty').fill('8');
    await page.getByTestId('subtable-add-inv_in_line_price').fill('50');

    const cmdResp = page.waitForResponse(
      (r) => r.url().includes('/api/meta/commands/execute/pe:add_wh_in_line') && r.status() === 200,
      { timeout: 10_000 },
    );
    await page.getByTestId('subtable-save-btn').click();
    await cmdResp;

    // Wait for refresh
    await page.waitForResponse(
      (r) => r.url().includes('/api/dynamic/inv_inbound_line/list') && r.status() === 200,
      { timeout: 10_000 },
    );

    // Row should appear with computed amount 8 * 50 = 400
    const rows = page
      .locator('table tbody tr')
      .filter({ hasNot: page.getByTestId('subtable-add-form') });
    await expect(rows.first()).toBeVisible({ timeout: 5_000 });

    const amountCell = rows.first().locator('td').nth(3);
    await expect(amountCell).toContainText('400', { timeout: 5_000 });
  });

  test('edit page shows summary footer', async ({ page }) => {
    const inboundPid = await createDraftInbound(page);
    // Add 2 lines via API: 3*10=30, 7*20=140
    await executeCommandViaApi(
      page,
      'pe:add_wh_in_line',
      {
        inv_in_line_receipt_id: inboundPid,
        inv_in_line_product_id: productPid,
        inv_in_line_qty: 3,
        inv_in_line_price: 10,
      },
      undefined,
      'create',
    );
    await executeCommandViaApi(
      page,
      'pe:add_wh_in_line',
      {
        inv_in_line_receipt_id: inboundPid,
        inv_in_line_product_id: productPid,
        inv_in_line_qty: 7,
        inv_in_line_price: 20,
      },
      undefined,
      'create',
    );

    await gotoEditPage(page, inboundPid);

    // Summary = 30 + 140 = 170
    const summary = page.getByTestId('subtable-summary');
    await expect(summary).toBeVisible({ timeout: 5_000 });
    await expect(summary).toContainText('170', { timeout: 5_000 });
  });

  test('edit page can delete a line item', async ({ page }) => {
    const inboundPid = await createDraftInbound(page);
    // Add 1 line via API
    await executeCommandViaApi(
      page,
      'pe:add_wh_in_line',
      {
        inv_in_line_receipt_id: inboundPid,
        inv_in_line_product_id: productPid,
        inv_in_line_qty: 2,
        inv_in_line_price: 100,
      },
      undefined,
      'create',
    );

    await gotoEditPage(page, inboundPid);

    // Delete button should be visible
    const deleteBtn = page.getByTestId('subtable-delete-0');
    await expect(deleteBtn).toBeVisible({ timeout: 5_000 });

    const deleteResp = page.waitForResponse(
      (r) =>
        r.url().includes('/api/meta/commands/execute/pe:delete_wh_in_line') && r.status() === 200,
      { timeout: 10_000 },
    );
    await deleteBtn.click();
    await deleteResp;

    // Wait for refresh — table should be empty
    await page.waitForResponse(
      (r) => r.url().includes('/api/dynamic/inv_inbound_line/list') && r.status() === 200,
      { timeout: 10_000 },
    );

    // No data message should appear
    const noData = page.locator('td').filter({ hasText: /No data|暂无数据/ });
    await expect(noData).toBeVisible({ timeout: 5_000 });
  });

  test('create mode shows placeholder instead of sub-table', async ({ page }) => {
    await page.goto('/p/inv_inbound/new?commandCode=pe%3Acreate_warehouse_in', {
      waitUntil: 'domcontentloaded',
    });
    await waitForFormReady(page);

    // Should show placeholder text for create mode
    const placeholder = page.locator('text=Save the record first');
    await expect(placeholder).toBeVisible({ timeout: 10_000 });

    // Add Line button should NOT be visible
    await expect(page.getByTestId('subtable-add-row')).not.toBeVisible({ timeout: 3_000 });
  });
});
