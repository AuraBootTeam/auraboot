/**
 * PCBA SRM — Deep E2E Tests (Supplier Evaluation)
 *
 * Covers pe_supplier_eval model with CRUD and status flow:
 * draft → submitted → approved.
 *
 * Prerequisites: PCBA SRM plugin must be imported and published.
 * Supplier eval is a child of pe_supplier, so a parent supplier is created for tests.
 *
 * @since 5.0.0
 */

import { test, expect } from '../../fixtures';
import { ErrorCodes } from '~/shared/services/http-client/types';
import {
  navigateToDynamicPage,
  uniqueId,
  executeCommandViaApi,
  queryFilteredList,
} from '../helpers/index';

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

const PAGE_KEYS = {
  supplier: 'pe-supplier',
  supplierEval: 'pe-supplier-eval',
};

type SrmBucket = {
  suppliers: string[];
  supplierEvals: string[];
};

function emptyBucket(): SrmBucket {
  return { suppliers: [], supplierEvals: [] };
}

async function deleteRecord(
  page: import('@playwright/test').Page,
  pageKey: string,
  pid: string,
): Promise<void> {
  await page.request.delete(`/api/dynamic/${pageKey}/${pid}`);
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

async function cleanup(page: import('@playwright/test').Page, b: SrmBucket): Promise<void> {
  for (const pid of [...b.supplierEvals].reverse()) {
    await deleteRecord(page, PAGE_KEYS.supplierEval, pid).catch(() => {});
  }
  for (const pid of [...b.suppliers].reverse()) {
    await deleteRecord(page, PAGE_KEYS.supplier, pid).catch(() => {});
  }
}

function mustSucceed(result: { code: string; recordId: string }, command: string): string {
  expect(result.code, `${command} should succeed`).toBe(ErrorCodes.SUCCESS);
  expect(result.recordId, `${command} should return recordId`).toBeTruthy();
  return result.recordId;
}

// ===========================================================================
// Test Suite
// ===========================================================================

test.describe('PCBA SRM Deep — Supplier Evaluation', () => {
  test.describe.configure({ timeout: 60000 });

  const bucket = emptyBucket();

  // Create a shared supplier for all eval tests
  let sharedSupplierId: string | null = null;

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const p = await ctx.newPage();

    const uid = uniqueId('srm_eval');
    const result = await executeCommandViaApi(
      p,
      'pe:create_supplier',
      {
        pe_supplier_name: `E2E Eval Supplier ${uid}`,
        pe_supplier_contact: 'E2E Eval Contact',
        pe_supplier_phone: '13900000000',
      },
      undefined,
      'create',
      { allowHttpError: true },
    );

    if (result.recordId && result.code === ErrorCodes.SUCCESS) {
      sharedSupplierId = result.recordId;
      bucket.suppliers.push(result.recordId);
    }

    await ctx.close();
  });

  test.afterAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const p = await ctx.newPage();
    await cleanup(p, bucket);
    await ctx.close();
  });

  test('PS-001: Supplier eval list page loads @smoke', async ({ page }) => {
    await navigateToDynamicPage(page, PAGE_KEYS.supplierEval);

    const table = page.locator('table, [role="table"]');
    await expect(table.first()).toBeVisible({ timeout: 15000 });
  });

  test('PS-002: Create supplier eval via API, verify in list', async ({ page }) => {
    if (!sharedSupplierId) {
      throw new Error(String('Shared supplier not available — plugin may not be imported'));
      return;
    }

    const result = await executeCommandViaApi(
      page,
      'pe:create_supplier_eval',
      {
        pe_se_supplier_id: sharedSupplierId,
        pe_se_period: '2026-Q1',
        pe_se_evaluator: 'E2E Tester',
        pe_se_remark: 'E2E automated supplier evaluation test',
      },
      undefined,
      'create',
      { allowHttpError: true },
    );

    if (!result.recordId || result.code !== ErrorCodes.SUCCESS) {
      throw new Error(String('Supplier eval creation failed — plugin may not be imported'));
      return;
    }
    bucket.supplierEvals.push(result.recordId);

    // Verify auto-generated fields
    const record = await fetchRecord(page, PAGE_KEYS.supplierEval, result.recordId);
    expect(record.pe_se_status).toBe('draft');
    const evalCode = String(record.pe_se_code ?? '');
    expect(evalCode).toBeTruthy();

    // Verify in list dataset via list API (deterministic, no pagination dependency)
    const records = await queryFilteredList(page, PAGE_KEYS.supplierEval, 'pe_se_code', evalCode, {
      operator: 'EQ',
      pageSize: 10,
    });
    expect(records.some((r) => String(r.pid ?? r.id ?? '') === result.recordId)).toBeTruthy();
  });

  test('PS-003: Supplier eval status flow draft → submitted → approved', async ({ page }) => {
    if (!sharedSupplierId) {
      throw new Error(String('Shared supplier not available'));
      return;
    }

    const result = await executeCommandViaApi(
      page,
      'pe:create_supplier_eval',
      {
        pe_se_supplier_id: sharedSupplierId,
        pe_se_period: '2026-Q2',
        pe_se_evaluator: 'E2E Status Tester',
        pe_se_remark: 'E2E eval status flow test',
      },
      undefined,
      'create',
      { allowHttpError: true },
    );

    if (!result.recordId || result.code !== ErrorCodes.SUCCESS) {
      throw new Error(String('Supplier eval creation failed'));
      return;
    }
    bucket.supplierEvals.push(result.recordId);

    let record = await fetchRecord(page, PAGE_KEYS.supplierEval, result.recordId);
    expect(record.pe_se_status).toBe('draft');
    const submitResult = await executeCommandViaApi(
      page,
      'pe:submit_supplier_eval',
      {},
      result.recordId,
      'update',
      { allowHttpError: true },
    );
    expect(submitResult.code).toBe(ErrorCodes.SUCCESS);

    record = await fetchRecord(page, PAGE_KEYS.supplierEval, result.recordId);
    expect(record.pe_se_status).toBe('submitted');

    const approveResult = await executeCommandViaApi(
      page,
      'pe:approve_supplier_eval',
      {},
      result.recordId,
      'update',
      { allowHttpError: true },
    );
    expect(approveResult.code).toBe(ErrorCodes.SUCCESS);

    record = await fetchRecord(page, PAGE_KEYS.supplierEval, result.recordId);
    expect(record.pe_se_status).toBe('approved');
  });
});
