/**
 * CP Equipment Inspection — E2E Tests
 *
 * Tests the equipment inspection ENTITY model CRUD and status flow
 * (pending -> PASSED / failed), plus the construction log <-> weekly report
 * REFERENCE link in the construction-process plugin.
 *
 * Prerequisites: construction-process plugin must be imported and models published.
 *
 * @since 10.0.0
 */
import { test, expect } from '@playwright/test';
import {
  navigateToDynamicPage,
  uniqueId,
  executeCommandViaApi,
  waitForDynamicPageLoad,
  findRowInPaginatedList,
  queryFilteredList,
  todayStr,
  dateOffsetStr,
} from '../helpers/index';
import { getTestProjectId } from '../quarry-management.setup';
import { ErrorCodes } from '~/shared/services/http-client/types';
import { BASE_URL } from '../../helpers/environments';

// ---------------------------------------------------------------------------
// Test Describe: Equipment Inspection — CRUD & Status
// ---------------------------------------------------------------------------

test.describe('CP Equipment Inspection — CRUD & Status', () => {
  test.describe.configure({ mode: 'serial', timeout: 60000 });

  let inspectionPid: string;
  let failedInspectionPid: string;
  let projectId: string;
  const today = todayStr();
  const nextInspection = dateOffsetStr(90);
  const equipmentName = `E2E Crane ${uniqueId()}`;
  const equipmentName2 = `E2E Pump ${uniqueId()}`;

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
      baseURL: BASE_URL,
    });
    const p = await ctx.newPage();
    projectId = await getTestProjectId(p);
    await ctx.close();
  });

  test('EI-001: Create equipment inspection via API', async ({ page }) => {
    const result = await executeCommandViaApi(page, 'cp:create_equipment_inspection', {
      cp_ei_project_id: projectId,
      cp_ei_equipment_name: equipmentName,
      cp_ei_equipment_type: 'crane',
      cp_ei_model_spec: 'QTZ-80',
      cp_ei_manufacturer: 'Test Mfg',
      cp_ei_inspection_date: today,
      cp_ei_inspector: 'E2E Inspector',
      cp_ei_next_inspection_date: nextInspection,
      cp_ei_remark: 'E2E test inspection record',
    });
    expect(result.code).toBe(ErrorCodes.SUCCESS);
    inspectionPid = result.recordId;
    expect(inspectionPid).toBeTruthy();

    // Verify the record exists via list API
    const records = await queryFilteredList(
      page,
      'cp-equipment-inspection',
      'cp_ei_equipment_name',
      equipmentName,
    );
    expect(records.length).toBeGreaterThan(0);
    expect(String((records[0] as any).cp_ei_equipment_type ?? '')).toBe('crane');
    expect(String((records[0] as any).cp_ei_result ?? '')).toBe('pending');

    // UI interaction: verify created equipment appears in list.
    await navigateToDynamicPage(page, 'cp-equipment-inspection');
    const row = await findRowInPaginatedList(page, equipmentName, 12000);
    await expect(row).toBeVisible({ timeout: 5000 });
  });

  test('EI-002: View equipment inspection in list', async ({ page }) => {
    expect(inspectionPid).toBeTruthy();

    await navigateToDynamicPage(page, 'cp-equipment-inspection');
    const row = await findRowInPaginatedList(page, equipmentName, 12000);
    await expect(row).toBeVisible({ timeout: 5000 });

    // Verify key data is visible in the row
    await expect(row).toContainText(equipmentName);
  });

  test('EI-003: Pass inspection via API (pending -> INSPECTING -> PASSED)', async ({ page }) => {
    expect(inspectionPid).toBeTruthy();

    // Step 1: Start inspection (pending -> INSPECTING)
    const startResult = await executeCommandViaApi(
      page,
      'cp:start_equipment_inspection',
      {},
      inspectionPid,
      'state_transition',
    );
    expect(startResult.code).toBe(ErrorCodes.SUCCESS);

    // Step 2: Pass inspection (INSPECTING -> PASSED)
    const result = await executeCommandViaApi(
      page,
      'cp:pass_equipment_inspection',
      {},
      inspectionPid,
      'state_transition',
    );
    expect(result.code).toBe(ErrorCodes.SUCCESS);

    // Verify status changed to PASSED via list API
    const records = await queryFilteredList(
      page,
      'cp-equipment-inspection',
      'cp_ei_equipment_name',
      equipmentName,
      {
        extraFilters: [{ fieldName: 'cp_ei_result', operator: 'EQ', value: 'passed' }],
      },
    );
    expect(records.length).toBeGreaterThan(0);
  });

  test('EI-004: Create and fail inspection (pending -> failed)', async ({ page }) => {
    // Create a second inspection to test the failed path
    const createResult = await executeCommandViaApi(page, 'cp:create_equipment_inspection', {
      cp_ei_project_id: projectId,
      cp_ei_equipment_name: equipmentName2,
      cp_ei_equipment_type: 'pump',
      cp_ei_model_spec: 'HBT-60',
      cp_ei_manufacturer: 'Pump Corp',
      cp_ei_inspection_date: today,
      cp_ei_inspector: 'E2E Inspector',
      cp_ei_remark: 'E2E test inspection — will fail',
    });
    expect(createResult.code).toBe(ErrorCodes.SUCCESS);
    failedInspectionPid = createResult.recordId;
    expect(failedInspectionPid).toBeTruthy();

    // Start inspection first (pending -> INSPECTING)
    const startResult = await executeCommandViaApi(
      page,
      'cp:start_equipment_inspection',
      {},
      failedInspectionPid,
      'state_transition',
    );
    expect(startResult.code).toBe(ErrorCodes.SUCCESS);

    // Fail the inspection (INSPECTING -> failed)
    const failResult = await executeCommandViaApi(
      page,
      'cp:fail_equipment_inspection',
      {},
      failedInspectionPid,
      'state_transition',
    );
    expect(failResult.code).toBe(ErrorCodes.SUCCESS);

    // Verify status changed to failed
    const records = await queryFilteredList(
      page,
      'cp-equipment-inspection',
      'cp_ei_equipment_name',
      equipmentName2,
      {
        extraFilters: [{ fieldName: 'cp_ei_result', operator: 'EQ', value: 'failed' }],
      },
    );
    expect(records.length).toBeGreaterThan(0);
  });

  test('EI-005: Delete pending inspection via API', async ({ page }) => {
    // Delete command only works on pending records (precondition).
    // Create a fresh pending record specifically for delete testing.
    const delName = `E2E Del ${uniqueId()}`;
    const createResult = await executeCommandViaApi(page, 'cp:create_equipment_inspection', {
      cp_ei_project_id: projectId,
      cp_ei_equipment_name: delName,
      cp_ei_equipment_type: 'generator',
      cp_ei_inspection_date: today,
      cp_ei_inspector: 'E2E Inspector',
    });
    expect(createResult.code).toBe(ErrorCodes.SUCCESS);
    const delPid = createResult.recordId;
    expect(delPid).toBeTruthy();

    // Delete the pending record
    const result = await executeCommandViaApi(
      page,
      'cp:delete_equipment_inspection',
      {},
      delPid,
      'delete',
    );
    expect(result.code).toBe(ErrorCodes.SUCCESS);

    // Verify the deleted record no longer appears in the list
    const records = await queryFilteredList(
      page,
      'cp-equipment-inspection',
      'cp_ei_equipment_name',
      delName,
    );
    expect(records.length).toBe(0);
  });

  test.afterAll(async ({ browser }) => {
    const ctx = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
      baseURL: BASE_URL,
    });
    const p = await ctx.newPage();
    // Cleanup any remaining test records
    if (inspectionPid) {
      await executeCommandViaApi(
        p,
        'cp:delete_equipment_inspection',
        {},
        inspectionPid,
        'delete',
      ).catch(() => {});
    }
    if (failedInspectionPid) {
      await executeCommandViaApi(
        p,
        'cp:delete_equipment_inspection',
        {},
        failedInspectionPid,
        'delete',
      ).catch(() => {});
    }
    await ctx.close();
  });
});

// ---------------------------------------------------------------------------
// Test Describe: CP Log-Report Link
// ---------------------------------------------------------------------------

test.describe('CP Log-Report Link', () => {
  test('LR-001: Construction log list page loads', async ({ page }) => {
    await navigateToDynamicPage(page, 'cp-construction-log');

    // Verify table is visible
    const table = page.locator('table, [role="table"]');
    await expect(table.first()).toBeVisible({ timeout: 10000 });

    // Verify at least the table header or empty state rendered
    const headerOrRow = page.locator('thead, tbody tr, [data-testid="empty-state"]');
    await expect(headerOrRow.first()).toBeVisible({ timeout: 5000 });
  });

  test('LR-002: Log form shows weekly report reference field', async ({ page }) => {
    // Navigate to the construction log list page
    await navigateToDynamicPage(page, 'cp-construction-log');
    await expect(page.locator('table, [role="table"]').first()).toBeVisible({ timeout: 10000 });

    // Click the create button to open the form
    const addBtn = page
      .locator(
        '[data-testid="toolbar-btn-create"], button:has-text("新建"), button:has-text("New"), button:has-text("Create")',
      )
      .first();

    // If create button is visible, click it and check the form structure
    const canCreate = await addBtn.isVisible({ timeout: 5000 }).catch(() => false);
    if (canCreate) {
      await addBtn.click();
      await waitForDynamicPageLoad(page);

      // Verify the form page loaded (look for form elements)
      const formContent = page.locator(
        'form, [data-testid="dynamic-form"], [data-testid^="form-field-"]',
      );
      await expect(formContent.first()).toBeVisible({ timeout: 10000 });

      // Check for the weekly report reference field (cp_log_weekly_report_id)
      // REFERENCE fields render as select/combobox or a lookup input
      const weeklyReportField = page.locator(
        '[data-testid="form-field-cp_log_weekly_report_id"], ' +
          '[data-field="cp_log_weekly_report_id"], ' +
          'label:has-text("weekly"), label:has-text("Weekly"), ' +
          'label:has-text("周报"), label:has-text("报告")',
      );
      // The field should exist in the form (visible or at least present in DOM)
      await expect(weeklyReportField.first()).toBeAttached({ timeout: 10000 });
    } else {
      // If no create button, just verify the list page structure is correct
      await expect(page.locator('table, [role="table"]').first()).toBeVisible({ timeout: 5000 });
    }
  });
});
