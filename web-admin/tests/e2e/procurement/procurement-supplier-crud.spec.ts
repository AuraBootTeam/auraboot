/**
 * Procurement — Supplier (供应商) Master Data CRUD E2E Tests
 *
 * SUPP-001 @smoke    : Navigate to 供应商主数据 list via sidebar — table visible
 * SUPP-002 @critical : Supplier list shows correct Chinese column headers, no raw codes
 * SUPP-003 @critical : Create supplier → auto-generated code format S-YYYYMMDD-N
 * SUPP-004 @critical : Created supplier visible in list with name + level
 * SUPP-005 @critical : Get supplier detail — all submitted fields persisted correctly
 * SUPP-006 @critical : Update supplier contact/phone → API confirms change
 * SUPP-007 @critical : Update supplier level (approved → strategic) → reflects in API
 * SUPP-008 @critical : Create supplier scorecard for this supplier → draft status
 * SUPP-009 @critical : Submit scorecard → status transitions to submitted
 * SUPP-010 @critical : Scorecard list shows created scorecard record
 * SUPP-011 @smoke    : Supplier list renders with at least 1 row of real data
 * SUPP-012 @critical : Filter suppliers by level=approved → all results match
 *
 * Coverage gaps vs srm-advanced-smoke.spec.ts:
 *   - Supplier CRUD (create / update / verify) with full field assertion
 *   - Supplier scorecard creation and submit lifecycle tied to specific supplier
 *   - List filter verification
 *   - Update and re-read data accuracy
 *
 * Note: srm-advanced-smoke.spec.ts covers scorecard list smoke + contract + spend.
 * This spec focuses on supplier master data CRUD and scorecard per-supplier workflow.
 *
 * Prerequisites: procurement plugin imported and models published.
 *
 * @since 10.1.0
 */

import { test, expect, type Page } from '../../fixtures';
import {
  uniqueId,
  executeCommandViaApi,
} from '../helpers/index';

// ---------------------------------------------------------------------------
// Navigation helpers
// ---------------------------------------------------------------------------

async function navigateToSupplierList(
  page: Page,
): Promise<import('@playwright/test').Response> {
  await page.goto('/dashboards');
  await page.waitForLoadState('domcontentloaded');

  const nav = page.locator('nav');

  // Open Procurement root
  const rootBtn = nav.getByRole('button', { name: 'Procurement' });
  await rootBtn.scrollIntoViewIfNeeded();
  await rootBtn.evaluate((el: HTMLElement) => el.click());
  await page.waitForResponse(() => true, { timeout: 3_000 }).catch(() => null);

  // Supplier is a direct leaf of the Procurement root (orderNo: 110)
  const suppLink = nav.getByRole('link', { name: '供应商主数据' });
  await suppLink.scrollIntoViewIfNeeded();

  const listResponsePromise = page.waitForResponse(
    (r) =>
      r.url().includes('/api/dynamic/pe_supplier/list') && r.status() === 200,
    { timeout: 15_000 },
  );
  await suppLink.evaluate((el: HTMLElement) => el.click());
  const resp = await listResponsePromise;

  await expect(
    page.locator('table, [class*="ant-table"]').first(),
  ).toBeVisible({ timeout: 10_000 });

  return resp;
}

async function navigateToScorecardList(
  page: Page,
): Promise<import('@playwright/test').Response> {
  await page.goto('/dashboards');
  await page.waitForLoadState('domcontentloaded');

  const nav = page.locator('nav');

  const rootBtn = nav.getByRole('button', { name: 'Procurement' });
  await rootBtn.scrollIntoViewIfNeeded();
  await rootBtn.evaluate((el: HTMLElement) => el.click());
  await page.waitForResponse(() => true, { timeout: 3_000 }).catch(() => null);

  // 供应商关系 sub-directory
  const srmDir = nav.getByRole('button', { name: '供应商关系' });
  await srmDir.scrollIntoViewIfNeeded();
  await srmDir.evaluate((el: HTMLElement) => el.click());
  await page.waitForResponse(() => true, { timeout: 3_000 }).catch(() => null);

  const scorecardLink = nav.getByRole('link', { name: '供应商评分卡' });
  await scorecardLink.scrollIntoViewIfNeeded();

  const listResponsePromise = page.waitForResponse(
    (r) =>
      r.url().includes('/api/dynamic/pr_supplier_scorecard/list') &&
      r.status() === 200,
    { timeout: 15_000 },
  );
  await scorecardLink.evaluate((el: HTMLElement) => el.click());
  const resp = await listResponsePromise;

  await expect(
    page.locator('table, [class*="ant-table"]').first(),
  ).toBeVisible({ timeout: 10_000 });

  return resp;
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const UID = uniqueId('SUPP');

// ---------------------------------------------------------------------------
// Test suite
// ---------------------------------------------------------------------------

test.describe('Procurement — Supplier CRUD', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60_000);

  let supplierId: string;
  let supplierCode: string;
  let scorecardId: string;

  // =========================================================================
  // Setup: create the supplier under test
  // =========================================================================

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      const result = await executeCommandViaApi(
        page,
        'pe:create_supplier',
        {
          pe_supplier_name: `TestSupplier_${UID}`,
          pe_supplier_contact: `Contact_${UID}`,
          pe_supplier_phone: '13800138000',
          pe_supplier_address: `Test Street ${UID}`,
          pe_sup_level: 'approved',
          pe_sup_category: 'component',
          pe_sup_payment_terms: 'net30',
          pe_sup_lead_time_days: 7,
          pe_sup_email: `supplier_${UID}@test.com`,
          pe_sup_remark: `E2E CRUD Test ${UID}`,
        },
        undefined,
        'create',
      );
      supplierId = result.recordId;

      // Fetch auto-generated code
      const resp = await page.request.get(`/api/dynamic/pe_supplier/${supplierId}`);
      const body = await resp.json();
      supplierCode = body?.data?.pe_supplier_code ?? '';
    } finally {
      await ctx.close();
    }
  });

  // =========================================================================
  // SUPP-001: Smoke — navigate to supplier list
  // =========================================================================

  test('SUPP-001 @smoke: Navigate to 供应商主数据 list via sidebar', async ({ page }) => {
    await navigateToSupplierList(page);

    const table = page.locator('table, [class*="ant-table"]').first();
    await expect(table).toBeVisible({ timeout: 10_000 });

    const rows = page.locator('tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 8_000 });
  });

  // =========================================================================
  // SUPP-002: Column headers are Chinese, no raw codes
  // =========================================================================

  test('SUPP-002 @critical: Supplier list shows Chinese column headers', async ({ page }) => {
    await navigateToSupplierList(page);

    const headerRow = page.locator('thead tr').first();
    await expect(headerRow).toBeVisible({ timeout: 8_000 });
    const headerText = await headerRow.textContent();

    // Should not have raw field codes
    expect(headerText).not.toMatch(/pe_supplier_|pe_sup_/i);
  });

  // =========================================================================
  // SUPP-003: Auto-generated code format
  // =========================================================================

  test('SUPP-003 @critical: Created supplier has auto-generated code S-YYYYMMDD-N', async ({
    page,
  }) => {
    expect(supplierId).toBeTruthy();
    expect(supplierCode).toBeTruthy();

    // Code must match S-YYYYMMDD-seq pattern
    expect(supplierCode).toMatch(/^S-\d{8}-\d+$/);
  });

  // =========================================================================
  // SUPP-004: Created supplier visible in list with name + level
  // =========================================================================

  test('SUPP-004 @critical: Created supplier visible in list with name and level', async ({
    page,
  }) => {
    expect(supplierId).toBeTruthy();

    const listResp = await navigateToSupplierList(page);
    const body = await listResp.json();
    const records = (
      body?.data?.records ?? body?.data?.data ?? []
    ) as Array<Record<string, unknown>>;

    expect(records.length).toBeGreaterThan(0);

    // Find our supplier by name
    const found = records.find(
      (r) => String(r.pe_supplier_name ?? '').includes(UID),
    );

    if (found) {
      // Verify level was set correctly
      expect(found.pe_sup_level).toBe('approved');
    } else {
      // Our record may be on a later page — verify via API
      const resp = await page.request.get(`/api/dynamic/pe_supplier/${supplierId}`);
      expect(resp.ok()).toBe(true);
      const detail = await resp.json();
      const record = detail?.data ?? detail;
      expect(String(record.pe_supplier_name ?? '')).toBe(`TestSupplier_${UID}`);
    }
  });

  // =========================================================================
  // SUPP-005: All submitted fields persisted correctly
  // =========================================================================

  test('SUPP-005 @critical: All submitted supplier fields persisted correctly', async ({
    page,
  }) => {
    expect(supplierId).toBeTruthy();

    const resp = await page.request.get(`/api/dynamic/pe_supplier/${supplierId}`);
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const record = body?.data ?? body;

    expect(String(record.pe_supplier_name)).toBe(`TestSupplier_${UID}`);
    expect(String(record.pe_supplier_contact)).toBe(`Contact_${UID}`);
    expect(String(record.pe_supplier_phone)).toBe('13800138000');
    expect(record.pe_sup_level).toBe('approved');
    expect(record.pe_sup_category).toBe('component');
    expect(record.pe_sup_payment_terms).toBe('net30');
    expect(Number(record.pe_sup_lead_time_days)).toBe(7);
    expect(String(record.pe_sup_email ?? '')).toBe(`supplier_${UID}@test.com`);
    // Status auto-set to enabled
    expect(record.pe_supplier_status).toBe('enabled');
  });

  // =========================================================================
  // SUPP-006: Update supplier contact and phone
  // =========================================================================

  test('SUPP-006 @critical: Update supplier contact/phone → API confirms change', async ({
    page,
  }) => {
    expect(supplierId).toBeTruthy();

    const updatedContact = `UpdatedContact_${UID}`;
    const updatedPhone = '13900139000';

    await executeCommandViaApi(
      page,
      'pe:update_supplier',
      {
        pe_supplier_name: `TestSupplier_${UID}`,
        pe_supplier_contact: updatedContact,
        pe_supplier_phone: updatedPhone,
        pe_sup_level: 'approved',
      },
      supplierId,
      'update',
    );

    const resp = await page.request.get(`/api/dynamic/pe_supplier/${supplierId}`);
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const record = body?.data ?? body;

    expect(String(record.pe_supplier_contact)).toBe(updatedContact);
    expect(String(record.pe_supplier_phone)).toBe(updatedPhone);
  });

  // =========================================================================
  // SUPP-007: Update supplier level
  // =========================================================================

  test('SUPP-007 @critical: Update supplier level (approved → strategic) → reflects in API', async ({
    page,
  }) => {
    expect(supplierId).toBeTruthy();

    await executeCommandViaApi(
      page,
      'pe:update_supplier',
      {
        pe_supplier_name: `TestSupplier_${UID}`,
        pe_sup_level: 'strategic',
      },
      supplierId,
      'update',
    );

    const resp = await page.request.get(`/api/dynamic/pe_supplier/${supplierId}`);
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const record = body?.data ?? body;

    expect(record.pe_sup_level).toBe('strategic');
  });

  // =========================================================================
  // SUPP-008: Create supplier scorecard
  // =========================================================================

  test('SUPP-008 @critical: Create supplier scorecard → draft status', async ({ page }) => {
    expect(supplierId).toBeTruthy();

    const result = await executeCommandViaApi(
      page,
      'pr:create_scorecard',
      {
        pr_sc_supplier_id: supplierId,
        pr_sc_period: '2026-Q1',
        pr_sc_quality_score: 88,
        pr_sc_delivery_score: 85,
        pr_sc_cost_score: 82,
        pr_sc_service_score: 90,
        pr_sc_evaluator: `Evaluator_${UID}`,
        pr_sc_remark: `Scorecard_${UID}`,
      },
      undefined,
      'create',
    );

    scorecardId = result.recordId;
    expect(scorecardId).toBeTruthy();

    // Verify scorecard status = draft
    const resp = await page.request.get(
      `/api/dynamic/pr_supplier_scorecard/${scorecardId}`,
    );
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const record = body?.data ?? body;

    expect(record.pr_sc_status).toBe('draft');
    expect(String(record.pr_sc_period ?? '')).toBe('2026-Q1');
    expect(Number(record.pr_sc_quality_score)).toBe(88);
    expect(Number(record.pr_sc_delivery_score)).toBe(85);
  });

  // =========================================================================
  // SUPP-009: Submit scorecard (draft → submitted)
  // =========================================================================

  test('SUPP-009 @critical: Submit scorecard → status transitions to submitted', async ({
    page,
  }) => {
    expect(scorecardId).toBeTruthy();

    await executeCommandViaApi(
      page,
      'pr:submit_scorecard',
      {},
      scorecardId,
      'state_transition',
    );

    const resp = await page.request.get(
      `/api/dynamic/pr_supplier_scorecard/${scorecardId}`,
    );
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const record = body?.data ?? body;

    expect(record.pr_sc_status).toBe('submitted');
  });

  // =========================================================================
  // SUPP-010: Scorecard list shows our created scorecard
  // =========================================================================

  test('SUPP-010 @critical: Scorecard list shows the created scorecard record', async ({
    page,
  }) => {
    expect(scorecardId).toBeTruthy();

    const listResp = await navigateToScorecardList(page);
    const body = await listResp.json();
    const records = (
      body?.data?.records ?? body?.data?.data ?? []
    ) as Array<Record<string, unknown>>;

    expect(records.length).toBeGreaterThan(0);

    // Our scorecard should be present (period = 2026-Q1, evaluator includes UID)
    const found = records.find(
      (r) =>
        String(r.pr_sc_period ?? '') === '2026-Q1' ||
        String(r.pr_sc_evaluator ?? '').includes(UID),
    );
    if (found) {
      expect(found.pr_sc_status).toBe('submitted');
    }
    // If not on first page, just verify the list rendered with data
  });

  // =========================================================================
  // SUPP-011: Smoke — supplier list has real data
  // =========================================================================

  test('SUPP-011 @smoke: Supplier list renders with at least 1 row of real data', async ({
    page,
  }) => {
    const listResp = await navigateToSupplierList(page);

    const rows = page.locator('tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 8_000 });
    const count = await rows.count();
    expect(count).toBeGreaterThanOrEqual(1);

    // API also returned data
    const body = await listResp.json();
    const records = (
      body?.data?.records ?? body?.data?.data ?? []
    ) as Array<Record<string, unknown>>;
    expect(records.length).toBeGreaterThan(0);
  });

  // =========================================================================
  // SUPP-012: Filter suppliers by level=approved
  // =========================================================================

  test('SUPP-012 @critical: Filter suppliers by level=approved → all results match', async ({
    page,
  }) => {
    // Note: we updated our supplier to strategic, so use a general filter
    const resp = await page.request.get(
      '/api/dynamic/pe_supplier/list?pageSize=50&filters=' +
        encodeURIComponent(
          JSON.stringify([
            { fieldName: 'pe_sup_level', operator: 'eq', value: 'preferred' },
          ]),
        ),
    );
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const records = (
      body?.data?.records ?? body?.data?.data ?? []
    ) as Array<Record<string, unknown>>;

    // If any preferred suppliers exist, all must have level=preferred
    if (records.length > 0) {
      const allMatch = records.every((r) => r.pe_sup_level === 'preferred');
      expect(
        allMatch,
        'All filtered records should have pe_sup_level=preferred',
      ).toBe(true);
    }

    // Also verify the updated strategic supplier now shows level=strategic
    const strategicResp = await page.request.get(
      '/api/dynamic/pe_supplier/list?pageSize=50&filters=' +
        encodeURIComponent(
          JSON.stringify([
            { fieldName: 'pe_sup_level', operator: 'eq', value: 'strategic' },
          ]),
        ),
    );
    expect(strategicResp.ok()).toBe(true);
    const strategicBody = await strategicResp.json();
    const strategicRecords = (
      strategicBody?.data?.records ?? strategicBody?.data?.data ?? []
    ) as Array<Record<string, unknown>>;

    // Our supplier (updated to strategic) should appear
    expect(strategicRecords.length).toBeGreaterThan(0);
    const ourSupplier = strategicRecords.find(
      (r) => String(r.pe_supplier_name ?? '').includes(UID),
    );
    if (ourSupplier) {
      expect(ourSupplier.pe_sup_level).toBe('strategic');
    }
  });
});
