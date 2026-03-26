/**
 * QO Daily Report — E2E Tests
 *
 * Tests quarry operation daily report CRUD and state transitions.
 * Flow: create report → add product line → submit → withdraw → delete
 */
import { test, expect } from '@playwright/test';
import {
  navigateToDynamicPage,
  uniqueId,
  executeCommandViaApi,
} from '../helpers/index';
import { PAGE_KEYS, getTestProjectId } from '../quarry-management.setup';
import { ErrorCodes } from '~/services/http-client/types';

/** Generate a unique future date to avoid uniqueness constraint conflicts. */
function randomFutureDate(): string {
  const year = 2090 + Math.floor(Math.random() * 9);
  const month = String(Math.floor(Math.random() * 12) + 1).padStart(2, '0');
  const day = String(Math.floor(Math.random() * 28) + 1).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

const MODEL = PAGE_KEYS.DAILY_REPORT; // 'qo_daily_report'

test.describe('QO Daily Report', () => {
  test.describe.configure({ mode: 'serial' });

  let reportPid: string;
  let projectId: string | null = null;

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json', baseURL: 'http://localhost:5173' });
    const page = await ctx.newPage();
    try {
      projectId = await getTestProjectId(page);
    } catch (e: any) {
      console.warn('PM/QO plugin not available:', e.message);
    }
    await page.close();
    await ctx.close();
  });

  test('should navigate to daily report list page', async ({ page }) => {
    if (!projectId) { throw new Error(String('PM/QO plugin not imported — pm:create_project command unavailable')); }
    await navigateToDynamicPage(page, MODEL);
    await expect(page.locator('table, [role="table"]').first()).toBeVisible();
  });

  test('should create daily report and add product line', async ({ page }) => {
    if (!projectId) { throw new Error(String('PM/QO plugin not imported — pm:create_project command unavailable')); }
    // Create the report
    const createResult = await executeCommandViaApi(
      page,
      'qo:create_daily_report',
      {
        qo_project_id: projectId,
        qo_report_date: randomFutureDate(),
        qo_remark: `E2E Test ${uniqueId()}`,
      },
    );
    expect(createResult.code).toBe(ErrorCodes.SUCCESS);
    reportPid = createResult.recordId;
    expect(reportPid).toBeTruthy();

    // Add a product line (required before submit — HAS_CHILDREN validation)
    const lineResult = await executeCommandViaApi(
      page,
      'qo:add_report_line',
      {
        qo_report_id: reportPid,
        qo_product_category: 'stone',
        qo_product_spec: '10-20mm',
        qo_output: 100,
        qo_sales_qty: 80,
        qo_sales_amount: 8000,
        qo_base_price: 100,
      },
    );
    expect(lineResult.code).toBe(ErrorCodes.SUCCESS);

    // Navigate to list and verify row exists (UI verification)
    await navigateToDynamicPage(page, MODEL);
    await expect(page.locator('tbody tr').first()).toBeVisible({ timeout: 10000 });
  });

  test('should submit daily report (draft → submitted)', async ({ page }) => {
    if (!projectId) { throw new Error(String('PM/QO plugin not imported — pm:create_project command unavailable')); }
    expect(reportPid).toBeTruthy();

    const result = await executeCommandViaApi(
      page,
      'qo:submit_daily_report',
      {},
      reportPid,
      'state_transition',
    );
    expect(result.code).toBe(ErrorCodes.SUCCESS);

    // Navigate to list page to verify
    await navigateToDynamicPage(page, MODEL);
    await expect(page.locator('tbody tr').first()).toBeVisible({ timeout: 10000 });
  });

  test('should prevent editing a submitted report', async ({ page }) => {
    if (!projectId) { throw new Error(String('PM/QO plugin not imported — pm:create_project command unavailable')); }
    expect(reportPid).toBeTruthy();

    const result = await executeCommandViaApi(
      page,
      'qo:update_daily_report',
      { qo_remark: 'Should fail' },
      reportPid,
      'update',
      { allowHttpError: true },
    );
    // Expect failure (precondition: status must be draft)
    expect(result.code).not.toBe(ErrorCodes.SUCCESS);
  });

  test('should withdraw a submitted report (submitted → draft)', async ({ page }) => {
    if (!projectId) { throw new Error(String('PM/QO plugin not imported — pm:create_project command unavailable')); }
    expect(reportPid).toBeTruthy();

    const result = await executeCommandViaApi(
      page,
      'qo:withdraw_daily_report',
      {},
      reportPid,
      'state_transition',
    );
    expect(result.code).toBe(ErrorCodes.SUCCESS);
  });

  test('should delete draft report and verify in list', async ({ page }) => {
    if (!projectId) { throw new Error(String('PM/QO plugin not imported — pm:create_project command unavailable')); }
    expect(reportPid).toBeTruthy();

    const result = await executeCommandViaApi(
      page,
      'qo:delete_daily_report',
      {},
      reportPid,
      'delete',
    );
    expect(result.code).toBe(ErrorCodes.SUCCESS);
    reportPid = '';

    // Navigate to list and verify the table is still accessible
    await navigateToDynamicPage(page, MODEL);
    await expect(page.locator('table, [role="table"]').first()).toBeVisible();
  });
});
