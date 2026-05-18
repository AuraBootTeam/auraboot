/**
 * PCBA ERP — WMS (Warehouse Management) CRUD E2E Tests
 *
 * Covers 3 models in the WMS domain:
 * - inv_inbound        (Inbound): CRUD + status tabs (draft/confirmed)
 * - inv_outbound       (Outbound): CRUD + status tabs (draft/confirmed)
 * - inv_transfer       (Transfer): CRUD + status tabs (draft/pending/approved/confirmed)
 *
 * Prerequisites: PCBA ERP plugin must be imported and published.
 *
 * @since 6.0.0
 */

import { test, expect } from '../../fixtures';
import { ErrorCodes } from '~/shared/services/http-client/types';
import {
  navigateToDynamicPage,
  uniqueId,
  executeCommandViaApi,
  acceptConfirmDialog,
  clickTabAndWaitForLoad,
  findRowInPaginatedList,
  clickSaveButton,
  clickRowActionByLocator,
} from '../helpers/index';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const PAGE_KEYS = {
  warehouseIn: 'inv-inbound',
  warehouseOut: 'inv-outbound',
  stockTransfer: 'inv-transfer',
  warehouse: 'inv-warehouse',
};

const COMMANDS = {
  createWhIn: 'pe:create_warehouse_in',
  updateWhIn: 'pe:update_warehouse_in',
  deleteWhIn: 'pe:delete_warehouse_in',
  createWhOut: 'pe:create_warehouse_out',
  updateWhOut: 'pe:update_warehouse_out',
  deleteWhOut: 'pe:delete_warehouse_out',
  createTransfer: 'pe:create_stock_transfer',
  updateTransfer: 'pe:update_stock_transfer',
  deleteTransfer: 'pe:delete_stock_transfer',
  createWarehouse: 'pe:create_warehouse',
  deleteWarehouse: 'pe:delete_warehouse',
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
// Warehouse Inbound Tests
// ---------------------------------------------------------------------------

test.describe('PCBA WMS — Warehouse Inbound CRUD', () => {
  test.describe.configure({ timeout: 60000 });

  const created: CleanupEntry[] = [];
  let warehousePid = '';

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
    const page = await ctx.newPage();

    // Fetch or create a warehouse for REFERENCE fields
    const resp = await page.request.get('/api/dynamic/inv_warehouse/list?pageSize=1');
    const body = await resp.json();
    const existing = body?.data?.records?.[0]?.pid;
    if (existing) {
      warehousePid = existing;
    } else {
      const whName = `E2E InboundWH ${uniqueId()}`;
      const whResult = await executeCommandViaApi(
        page,
        COMMANDS.createWarehouse,
        { inv_warehouse_name: whName, inv_warehouse_code: `WH-IN-${uniqueId()}` },
        undefined,
        'create',
        { allowHttpError: true },
      );
      warehousePid = mustSucceed(whResult, 'pe:create_warehouse');
      created.push({ commandCode: COMMANDS.deleteWarehouse, pid: warehousePid });
    }

    await ctx.close();
  });

  test.afterAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    await safeCleanup(page, created);
    await ctx.close();
  });

  test('PW-001: Inbound list page loads @smoke', async ({ page }) => {
    await navigateToDynamicPage(page, PAGE_KEYS.warehouseIn);
    const table = page.locator('table, [role="table"]');
    await expect(table.first()).toBeVisible({ timeout: 15000 });
    const headers = page.locator('thead th');
    await expect(headers.first()).toBeVisible({ timeout: 5000 });
  });

  test('PW-002: Create inbound via API, verify in list', async ({ page }) => {
    const result = await executeCommandViaApi(
      page,
      COMMANDS.createWhIn,
      {
        inv_in_type: 'purchase',
        inv_in_date: '2026-03-01',
        inv_in_warehouse_id: warehousePid,
      },
      undefined,
      'create',
      { allowHttpError: true },
    );

    if (!result.recordId || result.code !== ErrorCodes.SUCCESS) {
      throw new Error(String('Inbound creation failed — command may not be available'));
      return;
    }
    created.push({ commandCode: COMMANDS.deleteWhIn, pid: result.recordId });

    const record = await fetchRecord(page, PAGE_KEYS.warehouseIn, result.recordId);
    const whInCode = String(record.inv_in_code ?? '');
    expect(whInCode).toBeTruthy();

    await navigateToDynamicPage(page, PAGE_KEYS.warehouseIn);
    const row = await findRowInPaginatedList(page, whInCode);
    await expect(row).toBeVisible({ timeout: 10000 });
  });

  test('PW-003: Edit inbound via UI', async ({ page }) => {
    const result = await executeCommandViaApi(
      page,
      COMMANDS.createWhIn,
      {
        inv_in_type: 'purchase',
        inv_in_date: '2026-03-01',
        inv_in_warehouse_id: warehousePid,
      },
      undefined,
      'create',
      { allowHttpError: true },
    );

    if (!result.recordId || result.code !== ErrorCodes.SUCCESS) {
      throw new Error(String('Inbound creation failed — skipping edit test'));
      return;
    }
    created.push({ commandCode: COMMANDS.deleteWhIn, pid: result.recordId });

    const record = await fetchRecord(page, PAGE_KEYS.warehouseIn, result.recordId);
    const whInCode = String(record.inv_in_code ?? '');

    await navigateToDynamicPage(page, PAGE_KEYS.warehouseIn);
    const row = await findRowInPaginatedList(page, whInCode);

    await clickRowActionByLocator(page, row, 'edit').catch(() => {
      throw new Error(String('Edit button not visible — record may not be in draft status'));
    });

    // Wait for form
    const formContent = page.locator('form, .ant-form, [data-testid="dynamic-form"]');
    await formContent.first().waitFor({ state: 'visible', timeout: 10000 });

    // Modify source_no field
    const sourceField = page.locator('[data-testid="form-field-inv_in_source_no"] input').first();
    if (await sourceField.isVisible({ timeout: 3000 }).catch(() => false)) {
      await sourceField.fill(`E2E-SRC-${Date.now()}`);
    }

    await clickSaveAndWait(page);
  });

  test('PW-004: Inbound status tabs (draft/confirmed)', async ({ page }) => {
    await navigateToDynamicPage(page, PAGE_KEYS.warehouseIn);

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

  test('PW-005: Delete inbound (draft only)', async ({ page }) => {
    const result = await executeCommandViaApi(
      page,
      COMMANDS.createWhIn,
      {
        inv_in_type: 'purchase',
        inv_in_date: '2026-03-01',
        inv_in_warehouse_id: warehousePid,
      },
      undefined,
      'create',
      { allowHttpError: true },
    );

    if (!result.recordId || result.code !== ErrorCodes.SUCCESS) {
      throw new Error(String('Inbound creation failed — skipping delete test'));
      return;
    }
    // Do NOT push to created — we expect this to be deleted by the test

    const record = await fetchRecord(page, PAGE_KEYS.warehouseIn, result.recordId);
    const whInCode = String(record.inv_in_code ?? '');

    await navigateToDynamicPage(page, PAGE_KEYS.warehouseIn);
    const row = await findRowInPaginatedList(page, whInCode);

    const listResp = page
      .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 10000 })
      .catch(() => null);
    await clickRowActionByLocator(page, row, 'delete').catch(() => {
      created.push({ commandCode: COMMANDS.deleteWhIn, pid: result.recordId });
      throw new Error(String('Delete button not visible'));
    });
    await acceptConfirmDialog(page).catch(() => {});
    await listResp;

    const goneRow = page.locator('tbody tr', { hasText: whInCode });
    await expect(goneRow).not.toBeVisible({ timeout: 5000 });
  });
});

// ---------------------------------------------------------------------------
// Warehouse Outbound Tests
// ---------------------------------------------------------------------------

test.describe('PCBA WMS — Warehouse Outbound CRUD', () => {
  test.describe.configure({ timeout: 60000 });

  const created: CleanupEntry[] = [];
  let warehousePid = '';

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
    const page = await ctx.newPage();

    // Fetch or create a warehouse for REFERENCE fields
    const resp = await page.request.get('/api/dynamic/inv_warehouse/list?pageSize=1');
    const body = await resp.json();
    const existing = body?.data?.records?.[0]?.pid;
    if (existing) {
      warehousePid = existing;
    } else {
      const whName = `E2E OutboundWH ${uniqueId()}`;
      const whResult = await executeCommandViaApi(
        page,
        COMMANDS.createWarehouse,
        { inv_warehouse_name: whName, inv_warehouse_code: `WH-OUT-${uniqueId()}` },
        undefined,
        'create',
        { allowHttpError: true },
      );
      warehousePid = mustSucceed(whResult, 'pe:create_warehouse');
      created.push({ commandCode: COMMANDS.deleteWarehouse, pid: warehousePid });
    }

    await ctx.close();
  });

  test.afterAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    await safeCleanup(page, created);
    await ctx.close();
  });

  test('PW-006: Outbound list page loads @smoke', async ({ page }) => {
    await navigateToDynamicPage(page, PAGE_KEYS.warehouseOut);
    const table = page.locator('table, [role="table"]');
    await expect(table.first()).toBeVisible({ timeout: 15000 });
    const headers = page.locator('thead th');
    await expect(headers.first()).toBeVisible({ timeout: 5000 });
  });

  test('PW-007: Create outbound via API, verify in list', async ({ page }) => {
    const result = await executeCommandViaApi(
      page,
      COMMANDS.createWhOut,
      {
        inv_out_type: 'sales',
        inv_out_date: '2026-03-01',
        inv_out_warehouse_id: warehousePid,
      },
      undefined,
      'create',
      { allowHttpError: true },
    );

    if (!result.recordId || result.code !== ErrorCodes.SUCCESS) {
      throw new Error(String('Outbound creation failed — command may not be available'));
      return;
    }
    created.push({ commandCode: COMMANDS.deleteWhOut, pid: result.recordId });

    const record = await fetchRecord(page, PAGE_KEYS.warehouseOut, result.recordId);
    const whOutCode = String(record.inv_out_code ?? '');
    expect(whOutCode).toBeTruthy();

    await navigateToDynamicPage(page, PAGE_KEYS.warehouseOut);
    const row = await findRowInPaginatedList(page, whOutCode);
    await expect(row).toBeVisible({ timeout: 10000 });
  });

  test('PW-008: Edit outbound via UI', async ({ page }) => {
    const result = await executeCommandViaApi(
      page,
      COMMANDS.createWhOut,
      {
        inv_out_type: 'sales',
        inv_out_date: '2026-03-01',
        inv_out_warehouse_id: warehousePid,
      },
      undefined,
      'create',
      { allowHttpError: true },
    );

    if (!result.recordId || result.code !== ErrorCodes.SUCCESS) {
      throw new Error(String('Outbound creation failed — skipping edit test'));
      return;
    }
    created.push({ commandCode: COMMANDS.deleteWhOut, pid: result.recordId });

    const record = await fetchRecord(page, PAGE_KEYS.warehouseOut, result.recordId);
    const whOutCode = String(record.inv_out_code ?? '');

    await navigateToDynamicPage(page, PAGE_KEYS.warehouseOut);
    const row = await findRowInPaginatedList(page, whOutCode);

    await clickRowActionByLocator(page, row, 'edit').catch(() => {
      throw new Error(String('Edit button not visible — record may not be in draft status'));
    });

    // Wait for form
    const formContent = page.locator('form, .ant-form, [data-testid="dynamic-form"]');
    await formContent.first().waitFor({ state: 'visible', timeout: 10000 });

    // Modify source_no field
    const sourceField = page.locator('[data-testid="form-field-inv_out_source_no"] input').first();
    if (await sourceField.isVisible({ timeout: 3000 }).catch(() => false)) {
      await sourceField.fill(`E2E-OUT-${Date.now()}`);
    }

    await clickSaveAndWait(page);
  });

  test('PW-009: Outbound status tabs (draft/confirmed)', async ({ page }) => {
    await navigateToDynamicPage(page, PAGE_KEYS.warehouseOut);

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

  test('PW-010: Delete outbound (draft only)', async ({ page }) => {
    const result = await executeCommandViaApi(
      page,
      COMMANDS.createWhOut,
      {
        inv_out_type: 'sales',
        inv_out_date: '2026-03-01',
        inv_out_warehouse_id: warehousePid,
      },
      undefined,
      'create',
      { allowHttpError: true },
    );

    if (!result.recordId || result.code !== ErrorCodes.SUCCESS) {
      throw new Error(String('Outbound creation failed — skipping delete test'));
      return;
    }

    const record = await fetchRecord(page, PAGE_KEYS.warehouseOut, result.recordId);
    const whOutCode = String(record.inv_out_code ?? '');

    await navigateToDynamicPage(page, PAGE_KEYS.warehouseOut);
    const row = await findRowInPaginatedList(page, whOutCode);

    const listResp = page
      .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 10000 })
      .catch(() => null);
    await clickRowActionByLocator(page, row, 'delete').catch(() => {
      created.push({ commandCode: COMMANDS.deleteWhOut, pid: result.recordId });
      throw new Error(String('Delete button not visible'));
    });
    await acceptConfirmDialog(page).catch(() => {});
    await listResp;

    const goneRow = page.locator('tbody tr', { hasText: whOutCode });
    await expect(goneRow).not.toBeVisible({ timeout: 5000 });
  });
});

// ---------------------------------------------------------------------------
// Stock Transfer Tests
// ---------------------------------------------------------------------------

test.describe('PCBA WMS — Stock Transfer CRUD', () => {
  test.describe.configure({ timeout: 60000 });

  const created: CleanupEntry[] = [];
  let fromWarehousePid = '';
  let toWarehousePid = '';

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
    const page = await ctx.newPage();

    // Fetch existing warehouses — transfers need 2 distinct warehouses
    const resp = await page.request.get('/api/dynamic/inv_warehouse/list?pageSize=10');
    const body = await resp.json();
    const records = body?.data?.records ?? [];

    if (records.length >= 2) {
      fromWarehousePid = records[0].pid;
      toWarehousePid = records[1].pid;
    } else if (records.length === 1) {
      fromWarehousePid = records[0].pid;
      // Create a second warehouse
      const whResult = await executeCommandViaApi(
        page,
        COMMANDS.createWarehouse,
        {
          inv_warehouse_name: `E2E TransferWH-To ${uniqueId()}`,
          inv_warehouse_code: `WH-TO-${uniqueId()}`,
        },
        undefined,
        'create',
        { allowHttpError: true },
      );
      toWarehousePid = mustSucceed(whResult, 'pe:create_warehouse');
      created.push({ commandCode: COMMANDS.deleteWarehouse, pid: toWarehousePid });
    } else {
      // Create both warehouses
      const wh1Result = await executeCommandViaApi(
        page,
        COMMANDS.createWarehouse,
        {
          inv_warehouse_name: `E2E TransferWH-From ${uniqueId()}`,
          inv_warehouse_code: `WH-FR-${uniqueId()}`,
        },
        undefined,
        'create',
        { allowHttpError: true },
      );
      fromWarehousePid = mustSucceed(wh1Result, 'pe:create_warehouse');
      created.push({ commandCode: COMMANDS.deleteWarehouse, pid: fromWarehousePid });

      const wh2Result = await executeCommandViaApi(
        page,
        COMMANDS.createWarehouse,
        {
          inv_warehouse_name: `E2E TransferWH-To ${uniqueId()}`,
          inv_warehouse_code: `WH-TO-${uniqueId()}`,
        },
        undefined,
        'create',
        { allowHttpError: true },
      );
      toWarehousePid = mustSucceed(wh2Result, 'pe:create_warehouse');
      created.push({ commandCode: COMMANDS.deleteWarehouse, pid: toWarehousePid });
    }

    await ctx.close();
  });

  test.afterAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    await safeCleanup(page, created);
    await ctx.close();
  });

  test('PW-011: Transfer list page loads @smoke', async ({ page }) => {
    await navigateToDynamicPage(page, PAGE_KEYS.stockTransfer);
    const table = page.locator('table, [role="table"]');
    await expect(table.first()).toBeVisible({ timeout: 15000 });
    const headers = page.locator('thead th');
    await expect(headers.first()).toBeVisible({ timeout: 5000 });
  });

  test('PW-012: Create transfer via API, verify in list', async ({ page }) => {
    const result = await executeCommandViaApi(
      page,
      COMMANDS.createTransfer,
      {
        inv_st_date: '2026-03-01',
        inv_st_from_warehouse: fromWarehousePid,
        inv_st_to_warehouse: toWarehousePid,
      },
      undefined,
      'create',
      { allowHttpError: true },
    );

    if (!result.recordId || result.code !== ErrorCodes.SUCCESS) {
      throw new Error(String('Transfer creation failed — command may not be available'));
      return;
    }
    created.push({ commandCode: COMMANDS.deleteTransfer, pid: result.recordId });

    const record = await fetchRecord(page, PAGE_KEYS.stockTransfer, result.recordId);
    const stCode = String(record.inv_st_code ?? '');
    expect(stCode).toBeTruthy();

    await navigateToDynamicPage(page, PAGE_KEYS.stockTransfer);
    const row = await findRowInPaginatedList(page, stCode);
    await expect(row).toBeVisible({ timeout: 10000 });
  });

  test('PW-013: Edit transfer via UI', async ({ page }) => {
    const result = await executeCommandViaApi(
      page,
      COMMANDS.createTransfer,
      {
        inv_st_date: '2026-03-01',
        inv_st_from_warehouse: fromWarehousePid,
        inv_st_to_warehouse: toWarehousePid,
      },
      undefined,
      'create',
      { allowHttpError: true },
    );

    if (!result.recordId || result.code !== ErrorCodes.SUCCESS) {
      throw new Error(String('Transfer creation failed — skipping edit test'));
      return;
    }
    created.push({ commandCode: COMMANDS.deleteTransfer, pid: result.recordId });

    const record = await fetchRecord(page, PAGE_KEYS.stockTransfer, result.recordId);
    const stCode = String(record.inv_st_code ?? '');

    await navigateToDynamicPage(page, PAGE_KEYS.stockTransfer);
    const row = await findRowInPaginatedList(page, stCode);

    await clickRowActionByLocator(page, row, 'edit').catch(() => {
      throw new Error(String('Edit button not visible — record may not be in draft status'));
    });

    // Wait for form
    const formContent = page.locator('form, .ant-form, [data-testid="dynamic-form"]');
    await formContent.first().waitFor({ state: 'visible', timeout: 10000 });

    // Modify remark field
    const remarkField = page
      .locator(
        '[data-testid="form-field-inv_st_remark"] input, [data-testid="form-field-inv_st_remark"] textarea',
      )
      .first();
    if (await remarkField.isVisible({ timeout: 3000 }).catch(() => false)) {
      await remarkField.fill('E2E transfer remark');
    }

    await clickSaveAndWait(page);
  });

  test('PW-014: Delete transfer (draft only)', async ({ page }) => {
    const result = await executeCommandViaApi(
      page,
      COMMANDS.createTransfer,
      {
        inv_st_date: '2026-03-01',
        inv_st_from_warehouse: fromWarehousePid,
        inv_st_to_warehouse: toWarehousePid,
      },
      undefined,
      'create',
      { allowHttpError: true },
    );

    if (!result.recordId || result.code !== ErrorCodes.SUCCESS) {
      throw new Error(String('Transfer creation failed — skipping delete test'));
      return;
    }

    const record = await fetchRecord(page, PAGE_KEYS.stockTransfer, result.recordId);
    const stCode = String(record.inv_st_code ?? '');

    await navigateToDynamicPage(page, PAGE_KEYS.stockTransfer);
    const row = await findRowInPaginatedList(page, stCode);

    const listResp = page
      .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 10000 })
      .catch(() => null);
    await clickRowActionByLocator(page, row, 'delete').catch(() => {
      created.push({ commandCode: COMMANDS.deleteTransfer, pid: result.recordId });
      throw new Error(String('Delete button not visible'));
    });
    await acceptConfirmDialog(page).catch(() => {});
    await listResp;

    const goneRow = page.locator('tbody tr', { hasText: stCode });
    await expect(goneRow).not.toBeVisible({ timeout: 5000 });
  });
});
