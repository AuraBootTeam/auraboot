/**
 * PCBA ERP — Procurement Module CRUD E2E Tests
 *
 * Covers 3 models in the procurement domain:
 * - pe_purchase_order  (PO): CRUD + status tabs (draft/pending/approved/RECEIVING/completed)
 * - pe_purchase_request (PR): CRUD + status tabs (pending/processing/completed)
 * - pe_purchase_receipt (Receipt): CRUD + status tabs (draft/confirmed)
 *
 * Prerequisites: PCBA ERP plugin must be imported and published.
 *
 * @since 6.0.0
 */

import { test, expect } from '../../fixtures';
import { ErrorCodes } from '~/services/http-client/types';
import {
  navigateToDynamicPage,
  uniqueId,
  executeCommandViaApi,
  acceptConfirmDialog,
  clickTabAndWaitForLoad,
  findRowInPaginatedList,
  fillField,
  clickSaveButton,
  waitForFormReady,
  clickRowActionByLocator,
} from '../helpers/index';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const PAGE_KEYS = {
  purchaseOrder: 'pr-purchase-order',
  purchaseRequest: 'pr-purchase-request',
  purchaseReceipt: 'pr-purchase-receipt',
  supplier: 'pr-supplier',
  product: 'pr-product',
};

const COMMANDS = {
  createPO: 'pr:create_purchase_order',
  updatePO: 'pr:update_purchase_order',
  deletePO: 'pr:delete_purchase_order',
  createPR: 'pr:create_purchase_request',
  updatePR: 'pr:update_purchase_request',
  deletePR: 'pr:delete_purchase_request',
  createReceipt: 'pr:create_purchase_receipt',
  updateReceipt: 'pr:update_purchase_receipt',
  deleteReceipt: 'pr:delete_purchase_receipt',
  createSupplier: 'pe:create_supplier',
  deleteSupplier: 'pe:delete_supplier',
  createProduct: 'prod:create_product',
  deleteProduct: 'prod:delete_product',
};

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

type CleanupEntry = { commandCode: string; pid: string };

function mustSucceed(result: { code: string; recordId: string }, command: string): string {
  expect(result.code, `${command} should succeed`).toBe(ErrorCodes.SUCCESS);
  expect(result.recordId, `${command} should return recordId`).toBeTruthy();
  return result.recordId;
}

async function fetchRecord(
  page: import('@playwright/test').Page,
  pageKey: string,
  pid: string,
): Promise<Record<string, unknown>> {
  const resp = await page.request.get(`/api/dynamic/${pageKey}/${pid}`);
  expect(resp.ok(), `GET /api/dynamic/${pageKey}/${pid} should return 200`).toBe(true);
  const body = await resp.json();
  return (body.data ?? body) as Record<string, unknown>;
}

async function safeCleanup(
  page: import('@playwright/test').Page,
  entries: CleanupEntry[],
): Promise<void> {
  for (const { commandCode, pid } of [...entries].reverse()) {
    await executeCommandViaApi(page, commandCode, {}, pid, 'delete', {
      allowHttpError: true,
    }).catch(() => {});
  }
}

async function clickActionAndGetBody(
  page: import('@playwright/test').Page,
  row: import('@playwright/test').Locator,
  actionCode: string,
): Promise<any> {
  const commandResp = page.waitForResponse(
    (r) =>
      r.url().includes('/api/meta/commands/execute/') &&
      r.request().method().toLowerCase() === 'post',
    { timeout: 10000 },
  );
  const listResp = page
    .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, {
      timeout: 10000,
    })
    .catch(() => null);

  await clickRowActionByLocator(page, row, actionCode);
  await acceptConfirmDialog(page).catch(() => {});

  const resp = await commandResp;
  await listResp;
  return resp.json();
}

async function clickSaveAndWait(page: import('@playwright/test').Page) {
  const saveBtn = page
    .locator(
      '[data-testid="form-btn-submit"], [data-testid="form-btn-save"], button:has-text("保存"), button:has-text("Save")',
    )
    .first();
  await saveBtn.waitFor({ state: 'visible', timeout: 5000 });

  const settlePromise = Promise.race([
    page
      .waitForURL((url) => !/\/new(\?|$)|\/edit(\?|$)/.test(`${url.pathname}${url.search}`), {
        timeout: 10000,
      })
      .then(() => null)
      .catch(() => null),
    page
      .waitForResponse(
        (r) => r.request().method() !== 'get' && r.status() >= 200 && r.status() < 300,
        { timeout: 10000 },
      )
      .catch(() => null),
  ]);

  await clickSaveButton(page);
  await settlePromise;
}

// ---------------------------------------------------------------------------
// Purchase Order Tests
// ---------------------------------------------------------------------------

test.describe('PCBA Procurement — Purchase Order CRUD', () => {
  test.describe.configure({ timeout: 60000 });

  const created: CleanupEntry[] = [];
  let supplierPid: string | undefined;

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      const result = await executeCommandViaApi(
        page,
        COMMANDS.createSupplier,
        {
          pe_supplier_name: `E2E Supplier ${uniqueId('sup')}`,
          pe_supplier_contact: 'E2E Contact',
        },
        undefined,
        'create',
        { allowHttpError: true },
      );
      if (result.recordId && result.code === ErrorCodes.SUCCESS) {
        supplierPid = result.recordId;
      }
    } catch {
      // supplier creation failed — tests will skip gracefully
    }
    await ctx.close();
  });

  test.afterAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    await safeCleanup(page, created);
    if (supplierPid) {
      await executeCommandViaApi(page, COMMANDS.deleteSupplier, {}, supplierPid, 'delete', {
        allowHttpError: true,
      }).catch(() => {});
    }
    await ctx.close();
  });

  test('PP-001: PO list page loads @smoke', async ({ page }) => {
    await navigateToDynamicPage(page, PAGE_KEYS.purchaseOrder);
    const table = page.locator('table, [role="table"]');
    await expect(table.first()).toBeVisible({ timeout: 15000 });
    const headers = page.locator('thead th');
    await expect(headers.first()).toBeVisible({ timeout: 5000 });
  });

  test('PP-002: Create PO via API, verify in list', async ({ page }) => {
    expect(supplierPid, 'Supplier not available — skipping PO creation test').toBeTruthy();

    const result = await executeCommandViaApi(
      page,
      COMMANDS.createPO,
      {
        pr_po_supplier: supplierPid,
        pr_po_date: '2026-03-01',
      },
      undefined,
      'create',
      { allowHttpError: true },
    );

    if (!result.recordId || result.code !== ErrorCodes.SUCCESS) {
      throw new Error('PO creation failed — command may not be available');
      return;
    }
    created.push({ commandCode: COMMANDS.deletePO, pid: result.recordId });

    // Fetch record to get the auto-generated code
    const record = await fetchRecord(page, PAGE_KEYS.purchaseOrder, result.recordId);
    const poCode = String(record.pr_po_code ?? '');
    expect(poCode).toBeTruthy();

    await navigateToDynamicPage(page, PAGE_KEYS.purchaseOrder);
    const row = await findRowInPaginatedList(page, poCode);
    await expect(row).toBeVisible({ timeout: 10000 });
  });

  test('PP-003: Edit PO via UI', async ({ page }) => {
    expect(supplierPid, 'Supplier not available — skipping PO edit test').toBeTruthy();

    // Create a PO to edit
    const result = await executeCommandViaApi(
      page,
      COMMANDS.createPO,
      {
        pr_po_supplier: supplierPid,
        pr_po_date: '2026-03-01',
      },
      undefined,
      'create',
      { allowHttpError: true },
    );

    if (!result.recordId || result.code !== ErrorCodes.SUCCESS) {
      throw new Error('PO creation failed — skipping edit test');
      return;
    }
    created.push({ commandCode: COMMANDS.deletePO, pid: result.recordId });

    const record = await fetchRecord(page, PAGE_KEYS.purchaseOrder, result.recordId);
    const poCode = String(record.pr_po_code ?? '');

    await navigateToDynamicPage(page, PAGE_KEYS.purchaseOrder);
    const row = await findRowInPaginatedList(page, poCode);

    // Click edit action
    await clickRowActionByLocator(page, row, 'edit').catch(() => {
      throw new Error('Edit button not visible — row may not be in draft status');
    });

    // Wait for navigation to form page to stabilize
    await expect(page).toHaveURL(/\/edit\?commandCode=/, { timeout: 15000 });

    // Wait for dynamic form two-stage loading (schema fetch + field rendering)
    await waitForFormReady(page, 15000);

    // Modify a field (remark or arrival date)
    const remarkField = page.locator(
      '[data-testid="form-field-pr_po_remark"] input, [data-testid="form-field-pr_po_remark"] textarea',
    ).first();
    if (await remarkField.isVisible({ timeout: 5000 }).catch(() => false)) {
      await remarkField.fill('E2E edited remark');
    }

    // Save
    await clickSaveButton(page);

    // Wait for either success toast or navigation back to list
    const successOrList = page.locator(
      '.ant-message-success, [class*="toast"]:has-text("success"), table, [role="table"]',
    );
    await successOrList.first().waitFor({ state: 'visible', timeout: 15000 });
  });

  test('PP-004: PO status tabs (draft/approved/RECEIVING)', async ({ page }) => {
    await navigateToDynamicPage(page, PAGE_KEYS.purchaseOrder);

    // Verify tabs are visible
    const tabNav = page.locator('nav[aria-label="Tabs"]');
    if (!(await tabNav.isVisible({ timeout: 5000 }).catch(() => false))) {
      throw new Error('Tab navigation not found — page may not have tabs');
      return;
    }

    // Click "Draft" tab
    await clickTabAndWaitForLoad(page, /Draft|草稿/i, 5000, 'draft');
    // Table should still be visible after tab switch
    await expect(page.locator('table, [role="table"]').first()).toBeVisible({ timeout: 5000 });

    // Click "Approved" tab
    await clickTabAndWaitForLoad(page, /Approved|已审核/i, 5000, 'approved');
    await expect(page.locator('table, [role="table"]').first()).toBeVisible({ timeout: 5000 });

    // Click "All" tab to reset
    await clickTabAndWaitForLoad(page, /All|全部/i, 5000, 'all');
    await expect(page.locator('table, [role="table"]').first()).toBeVisible({ timeout: 5000 });
  });

  test('PP-005: Delete PO (draft only)', async ({ page }) => {
    expect(supplierPid, 'Supplier not available — skipping PO delete test').toBeTruthy();

    // Create a draft PO
    const result = await executeCommandViaApi(
      page,
      COMMANDS.createPO,
      {
        pr_po_supplier: supplierPid,
        pr_po_date: '2026-03-01',
      },
      undefined,
      'create',
      { allowHttpError: true },
    );

    if (!result.recordId || result.code !== ErrorCodes.SUCCESS) {
      throw new Error('PO creation failed — skipping delete test');
      return;
    }
    // Do NOT push to created — we expect this to be deleted by the test

    const record = await fetchRecord(page, PAGE_KEYS.purchaseOrder, result.recordId);
    const poCode = String(record.pr_po_code ?? '');

    await navigateToDynamicPage(page, PAGE_KEYS.purchaseOrder);
    const row = await findRowInPaginatedList(page, poCode);

    // Click delete action
    const commandResp = page.waitForResponse(
      (r) => r.url().includes('/api/meta/commands/execute/') && r.request().method().toLowerCase() === 'post',
      { timeout: 15000 },
    );
    const listResp = page
      .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 15000 })
      .catch(() => null);
    await clickRowActionByLocator(page, row, 'delete').catch(() => {
      created.push({ commandCode: COMMANDS.deletePO, pid: result.recordId });
      throw new Error('Delete button not visible');
    });
    await acceptConfirmDialog(page).catch(() => {});
    await commandResp;
    await listResp;

    // Verify record is gone from list (allow extra time for React re-render)
    const goneRow = page.locator('tbody tr', { hasText: poCode });
    await expect(goneRow).not.toBeVisible({ timeout: 10000 });
  });

  test('PP-006: PO field validation', async ({ page }) => {
    await navigateToDynamicPage(page, PAGE_KEYS.purchaseOrder);

    // Click create button
    const addButton = page.locator(
      'button:has-text("New"), button:has-text("新建"), button:has-text("Create"), [data-testid="add-button"]',
    );
    if (!(await addButton.first().isVisible({ timeout: 5000 }).catch(() => false))) {
      throw new Error('Create button not found');
      return;
    }
    await addButton.first().click();

    // Wait for dynamic form two-stage loading (schema fetch + field rendering)
    await waitForFormReady(page, 15000);

    // Try to save without filling required fields
    await clickSaveButton(page);

    // Verify validation: either error indicators appear or form remains open (not submitted)
    const errorIndicator = page.locator(
      '.ant-form-item-explain-error, [class*="error"]:not(header):not(nav), [role="alert"], .field-error, [data-testid*="error"], .text-red-500, .text-red-600, .border-red-500',
    );
    const hasErrors = await errorIndicator.first().isVisible({ timeout: 5000 }).catch(() => false);
    if (!hasErrors) {
      // Fallback: verify the form is still open (save did not succeed)
      const stillOnForm = await page.locator(
        'form, .ant-form, [data-testid="dynamic-form"]',
      ).first().isVisible({ timeout: 3000 }).catch(() => false);
      expect(stillOnForm).toBe(true);
    } else {
      expect(hasErrors).toBe(true);
    }
  });

  test('PP-007: PO i18n labels', async ({ page }) => {
    await navigateToDynamicPage(page, PAGE_KEYS.purchaseOrder);

    // Table headers should have translated labels (not raw field codes)
    const headers = page.locator('thead th');
    await expect(headers.first()).toBeVisible({ timeout: 10000 });

    const headerCount = await headers.count();
    expect(headerCount).toBeGreaterThan(0);

    // Verify none of the visible headers show raw field code patterns like "pr_po_*"
    for (let i = 0; i < Math.min(headerCount, 8); i++) {
      const text = (await headers.nth(i).innerText()).trim();
      if (text.length > 0) {
        expect(
          text,
          `Header should not be raw field code: ${text}`,
        ).not.toMatch(/^pr_po_/);
      }
    }
  });
});

// ---------------------------------------------------------------------------
// Purchase Request Tests
// ---------------------------------------------------------------------------

test.describe('PCBA Procurement — Purchase Request CRUD', () => {
  test.describe.configure({ timeout: 60000 });

  const created: CleanupEntry[] = [];
  let productPid: string | undefined;

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      const result = await executeCommandViaApi(
        page,
        COMMANDS.createProduct,
        {
          prod_name: `E2E Product ${uniqueId('prod')}`,
          prod_type: 'raw_material',
          prod_unit: 'pcs',
          prod_base_price: 10,
        },
        undefined,
        'create',
        { allowHttpError: true },
      );
      if (result.recordId && result.code === ErrorCodes.SUCCESS) {
        productPid = result.recordId;
      }
    } catch {
      // product creation failed
    }
    await ctx.close();
  });

  test.afterAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    await safeCleanup(page, created);
    if (productPid) {
      await executeCommandViaApi(page, COMMANDS.deleteProduct, {}, productPid, 'delete', {
        allowHttpError: true,
      }).catch(() => {});
    }
    await ctx.close();
  });

  test('PP-008: PR list page loads @smoke', async ({ page }) => {
    await navigateToDynamicPage(page, PAGE_KEYS.purchaseRequest);
    const table = page.locator('table, [role="table"]');
    await expect(table.first()).toBeVisible({ timeout: 15000 });
    const headers = page.locator('thead th');
    await expect(headers.first()).toBeVisible({ timeout: 5000 });
  });

  test('PP-009: Create PR via API, verify in list', async ({ page }) => {
    const result = await executeCommandViaApi(
      page,
      COMMANDS.createPR,
      {
        pr_preq_product_id: productPid ?? '',
        pr_preq_qty: 100,
        pr_preq_source: 'manual',
      },
      undefined,
      'create',
      { allowHttpError: true },
    );

    if (!result.recordId || result.code !== ErrorCodes.SUCCESS) {
      throw new Error('PR creation failed — command may not be available');
      return;
    }
    created.push({ commandCode: COMMANDS.deletePR, pid: result.recordId });

    const record = await fetchRecord(page, PAGE_KEYS.purchaseRequest, result.recordId);
    const prCode = String(record.pr_preq_code ?? '');
    expect(prCode).toBeTruthy();

    await navigateToDynamicPage(page, PAGE_KEYS.purchaseRequest);
    const row = await findRowInPaginatedList(page, prCode);
    await expect(row).toBeVisible({ timeout: 10000 });
  });

  test('PP-010: Edit PR via UI', async ({ page }) => {
    const result = await executeCommandViaApi(
      page,
      COMMANDS.createPR,
      {
        pr_preq_product_id: productPid ?? '',
        pr_preq_qty: 50,
        pr_preq_source: 'manual',
      },
      undefined,
      'create',
      { allowHttpError: true },
    );

    if (!result.recordId || result.code !== ErrorCodes.SUCCESS) {
      throw new Error('PR creation failed — skipping edit test');
      return;
    }
    created.push({ commandCode: COMMANDS.deletePR, pid: result.recordId });

    const record = await fetchRecord(page, PAGE_KEYS.purchaseRequest, result.recordId);
    const prCode = String(record.pr_preq_code ?? '');

    await navigateToDynamicPage(page, PAGE_KEYS.purchaseRequest);
    const row = await findRowInPaginatedList(page, prCode);

    await clickRowActionByLocator(page, row, 'edit').catch(() => {
      throw new Error('Edit button not visible — record may not be in pending status');
    });

    // Wait for navigation to form page to stabilize
    await expect(page).toHaveURL(/\/edit\?commandCode=/, { timeout: 15000 });

    // Wait for dynamic form two-stage loading (schema fetch + field rendering)
    await waitForFormReady(page, 15000);

    // Modify remark field
    const remarkField = page.locator(
      '[data-testid="form-field-pr_preq_remark"] input, [data-testid="form-field-pr_preq_remark"] textarea',
    ).first();
    if (await remarkField.isVisible({ timeout: 5000 }).catch(() => false)) {
      await remarkField.fill('E2E edited PR remark');
    }

    await clickSaveAndWait(page);
  });

  test('PP-011: Delete PR', async ({ page }) => {
    const result = await executeCommandViaApi(
      page,
      COMMANDS.createPR,
      {
        pr_preq_product_id: productPid ?? '',
        pr_preq_qty: 20,
        pr_preq_source: 'manual',
      },
      undefined,
      'create',
      { allowHttpError: true },
    );

    if (!result.recordId || result.code !== ErrorCodes.SUCCESS) {
      throw new Error('PR creation failed — skipping delete test');
      return;
    }

    const record = await fetchRecord(page, PAGE_KEYS.purchaseRequest, result.recordId);
    const prCode = String(record.pr_preq_code ?? '');

    await navigateToDynamicPage(page, PAGE_KEYS.purchaseRequest);
    const row = await findRowInPaginatedList(page, prCode);

    const commandResp = page.waitForResponse(
      (r) => r.url().includes('/api/meta/commands/execute/') && r.request().method().toLowerCase() === 'post',
      { timeout: 15000 },
    );
    const listResp = page
      .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 15000 })
      .catch(() => null);
    await clickRowActionByLocator(page, row, 'delete').catch(() => {
      created.push({ commandCode: COMMANDS.deletePR, pid: result.recordId });
      throw new Error('Delete button not visible');
    });
    await acceptConfirmDialog(page).catch(() => {});
    await commandResp;
    await listResp;

    // Verify record is gone from list (allow extra time for React re-render)
    const goneRow = page.locator('tbody tr', { hasText: prCode });
    await expect(goneRow).not.toBeVisible({ timeout: 10000 });
  });

  test('PP-012: PR status tabs (pending/processing/completed)', async ({ page }) => {
    await navigateToDynamicPage(page, PAGE_KEYS.purchaseRequest);

    const tabNav = page.locator('nav[aria-label="Tabs"]');
    if (!(await tabNav.isVisible({ timeout: 5000 }).catch(() => false))) {
      throw new Error('Tab navigation not found');
      return;
    }

    // Click "Pending" tab
    await clickTabAndWaitForLoad(page, /Pending|待处理/i, 5000, 'pending');
    await expect(page.locator('table, [role="table"]').first()).toBeVisible({ timeout: 5000 });

    // Click "Processing" tab
    await clickTabAndWaitForLoad(page, /Processing|处理中/i, 5000, 'processing');
    await expect(page.locator('table, [role="table"]').first()).toBeVisible({ timeout: 5000 });

    // Click "All" tab
    await clickTabAndWaitForLoad(page, /All|全部/i, 5000, 'all');
    await expect(page.locator('table, [role="table"]').first()).toBeVisible({ timeout: 5000 });
  });
});

// ---------------------------------------------------------------------------
// Purchase Receipt Tests
// ---------------------------------------------------------------------------

test.describe('PCBA Procurement — Purchase Receipt CRUD', () => {
  test.describe.configure({ timeout: 60000 });

  const created: CleanupEntry[] = [];
  let receiptPoPid: string | undefined;
  let receiptWarehousePid: string | undefined;
  let receiptSupplierPid: string | undefined;

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();

    // Query existing warehouse
    try {
      const whResp = await page.request.get('/api/dynamic/inv-warehouse/list?pageSize=1');
      if (whResp.ok()) {
        const whBody = await whResp.json();
        const whRec = whBody?.data?.records?.[0];
        if (whRec?.pid) {
          receiptWarehousePid = whRec.pid;
        }
      }
    } catch { /* ignore */ }

    // Create or query a supplier for the PO
    try {
      const supResult = await executeCommandViaApi(
        page,
        'pe:create_supplier',
        {
          pe_supplier_name: `E2E Receipt Supplier ${uniqueId('rsup')}`,
          pe_supplier_contact: 'E2E Receipt Contact',
        },
        undefined,
        'create',
        { allowHttpError: true },
      );
      if (supResult.recordId && supResult.code === ErrorCodes.SUCCESS) {
        receiptSupplierPid = supResult.recordId;
      }
    } catch { /* ignore */ }

    if (!receiptSupplierPid) {
      try {
        const supResp = await page.request.get('/api/dynamic/pe-supplier/list?pageSize=1');
        if (supResp.ok()) {
          const supBody = await supResp.json();
          const supRec = supBody?.data?.records?.[0];
          if (supRec?.pid) {
            receiptSupplierPid = supRec.pid;
          }
        }
      } catch { /* ignore */ }
    }

    // Create a purchase order to reference in receipts
    if (receiptSupplierPid) {
      try {
        const poResult = await executeCommandViaApi(
          page,
          'pr:create_purchase_order',
          {
            pr_po_supplier: receiptSupplierPid,
            pr_po_date: '2026-03-01',
          },
          undefined,
          'create',
          { allowHttpError: true },
        );
        if (poResult.recordId && poResult.code === ErrorCodes.SUCCESS) {
          receiptPoPid = poResult.recordId;
        }
      } catch { /* ignore */ }
    }

    await ctx.close();
  });

  test.afterAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    await safeCleanup(page, created);
    // Clean up the PO and supplier created for receipts
    if (receiptPoPid) {
      await executeCommandViaApi(page, 'pr:delete_purchase_order', {}, receiptPoPid, 'delete', {
        allowHttpError: true,
      }).catch(() => {});
    }
    if (receiptSupplierPid) {
      await executeCommandViaApi(page, 'pe:delete_supplier', {}, receiptSupplierPid, 'delete', {
        allowHttpError: true,
      }).catch(() => {});
    }
    await ctx.close();
  });

  test('PP-013: Receipt list page loads @smoke', async ({ page }) => {
    await navigateToDynamicPage(page, PAGE_KEYS.purchaseReceipt);
    const table = page.locator('table, [role="table"]');
    await expect(table.first()).toBeVisible({ timeout: 15000 });
    const headers = page.locator('thead th');
    await expect(headers.first()).toBeVisible({ timeout: 5000 });
  });

  test('PP-014: Create receipt via API, verify in list', async ({ page }) => {
    expect(receiptPoPid, 'Purchase order must be available for receipt tests').toBeTruthy();
    expect(receiptWarehousePid, 'Warehouse must be available for receipt tests').toBeTruthy();

    const result = await executeCommandViaApi(
      page,
      COMMANDS.createReceipt,
      {
        pr_rcpt_po_id: receiptPoPid,
        pr_rcpt_warehouse_id: receiptWarehousePid,
        pr_rcpt_date: '2026-03-01',
        pr_rcpt_status: 'draft',
      },
      undefined,
      'create',
      { allowHttpError: true },
    );

    if (!result.recordId || result.code !== ErrorCodes.SUCCESS) {
      throw new Error('Receipt creation failed — command may not be available');
      return;
    }
    created.push({ commandCode: COMMANDS.deleteReceipt, pid: result.recordId });

    const record = await fetchRecord(page, PAGE_KEYS.purchaseReceipt, result.recordId);
    const rcptCode = String(record.pr_rcpt_code ?? '');
    expect(rcptCode).toBeTruthy();

    await navigateToDynamicPage(page, PAGE_KEYS.purchaseReceipt);
    const row = await findRowInPaginatedList(page, rcptCode);
    await expect(row).toBeVisible({ timeout: 10000 });
  });

  test('PP-015: Edit receipt via UI', async ({ page }) => {
    expect(receiptPoPid, 'Purchase order must be available for receipt tests').toBeTruthy();
    expect(receiptWarehousePid, 'Warehouse must be available for receipt tests').toBeTruthy();

    const result = await executeCommandViaApi(
      page,
      COMMANDS.createReceipt,
      {
        pr_rcpt_po_id: receiptPoPid,
        pr_rcpt_warehouse_id: receiptWarehousePid,
        pr_rcpt_date: '2026-03-01',
        pr_rcpt_status: 'draft',
      },
      undefined,
      'create',
      { allowHttpError: true },
    );

    if (!result.recordId || result.code !== ErrorCodes.SUCCESS) {
      throw new Error('Receipt creation failed — skipping edit test');
      return;
    }
    created.push({ commandCode: COMMANDS.deleteReceipt, pid: result.recordId });

    const record = await fetchRecord(page, PAGE_KEYS.purchaseReceipt, result.recordId);
    const rcptCode = String(record.pr_rcpt_code ?? '');

    await navigateToDynamicPage(page, PAGE_KEYS.purchaseReceipt);
    const row = await findRowInPaginatedList(page, rcptCode);

    await clickRowActionByLocator(page, row, 'edit').catch(() => {
      throw new Error('Edit button not visible — record may not be in draft status');
    });

    // Wait for navigation to form page to stabilize
    await expect(page).toHaveURL(/\/edit\?commandCode=/, { timeout: 15000 });

    // Wait for dynamic form two-stage loading (schema fetch + field rendering)
    await waitForFormReady(page, 15000);

    // Modify remark
    const remarkField = page.locator(
      '[data-testid="form-field-pr_rcpt_remark"] input, [data-testid="form-field-pr_rcpt_remark"] textarea',
    ).first();
    if (await remarkField.isVisible({ timeout: 5000 }).catch(() => false)) {
      await remarkField.fill('E2E receipt remark');
    }

    await clickSaveAndWait(page);
  });

  test('PP-016: Receipt status tabs (draft/confirmed)', async ({ page }) => {
    await navigateToDynamicPage(page, PAGE_KEYS.purchaseReceipt);

    const tabNav = page.locator('nav[aria-label="Tabs"]');
    if (!(await tabNav.isVisible({ timeout: 5000 }).catch(() => false))) {
      throw new Error('Tab navigation not found');
      return;
    }

    // Click "Draft" tab
    await clickTabAndWaitForLoad(page, /Draft|草稿/i, 5000, 'draft');
    await expect(page.locator('table, [role="table"]').first()).toBeVisible({ timeout: 5000 });

    // Click "Confirmed" tab
    await clickTabAndWaitForLoad(page, /Confirmed|已确认/i, 5000, 'confirmed');
    await expect(page.locator('table, [role="table"]').first()).toBeVisible({ timeout: 5000 });

    // Click "All" tab
    await clickTabAndWaitForLoad(page, /All|全部/i, 5000, 'all');
    await expect(page.locator('table, [role="table"]').first()).toBeVisible({ timeout: 5000 });
  });
});
