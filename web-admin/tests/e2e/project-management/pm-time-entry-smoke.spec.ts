/**
 * PM Time Entry Smoke Tests
 *
 * Validates Time Entry module pages and lifecycle:
 * - Time Entry list page loads
 * - Create time entry (requires project)
 * - Submit time entry (draft -> submitted)
 * - Approve time entry (submitted -> approved)
 *
 * Prerequisites:
 *   - project-management plugin imported, pm_time_entry model published
 *   - Menu registered at /project-management/time-entries
 *
 * @since 8.0.0
 */

import { test, expect } from '../../fixtures';
import {
  navigateToDynamicPage,
  waitForDynamicPageLoad,
  uniqueId,
  executeCommandViaApi,
  todayStr,
  queryFilteredList,
} from '../helpers/index';

test.describe('PM Time Entry Smoke Tests @smoke', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60000);

  const uid = uniqueId('TE');
  let projectPid: string;
  let timeEntryPid: string;
  let currentUserPid: string;

  // =========================================================================
  // DATA SETUP — Create a project for time entries
  // =========================================================================
  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      const meResp = await page.request.get('/api/auth/me');
      expect(meResp.ok()).toBe(true);
      const meBody = await meResp.json();
      currentUserPid = String(meBody?.data?.user?.pid ?? '');
      expect(currentUserPid).toBeTruthy();

      // Create a project first (time entry requires project_id)
      const projResult = await executeCommandViaApi(
        page,
        'pm:create_project',
        {
          pm_project_code: `PROJ_${uid}`,
          pm_project_name: `Project ${uid}`,
          pm_description: `E2E test project for time entries ${uid}`,
          pm_project_status: 'active',
          pm_start_date: todayStr(),
        },
        undefined,
        'create',
      );
      projectPid = projResult.recordId;
    } finally {
      await ctx.close();
    }
  });

  // =========================================================================
  // TESTS
  // =========================================================================

  test('TE-001: Navigate to Time Entries list via menu', async ({ page }) => {
    await navigateToDynamicPage(page, 'pm-time-entry');
    await waitForDynamicPageLoad(page);
    await expect(page).toHaveURL(/\/p\/pm_time_entry/);

    const table = page.locator(
      '.ant-table, table, [role="table"], [data-testid="dynamic-list"], [data-testid="table-block"]',
    );
    await expect(table.first()).toBeVisible({ timeout: 10000 });
  });

  test('TE-002: Create a time entry linked to project', async ({ page }) => {
    expect(projectPid, 'Project should have been created in beforeAll').toBeTruthy();

    const result = await executeCommandViaApi(
      page,
      'pm:create_time_entry',
      {
        pm_te_user_id: currentUserPid,
        pm_te_project_id: projectPid,
        pm_te_date: todayStr(),
        pm_te_hours: 4.5,
        pm_te_category: 'development',
        pm_te_description: `Time entry ${uid} - development work`,
        pm_te_billable: true,
        pm_te_status: 'draft',
      },
      undefined,
      'create',
    );
    timeEntryPid = result.recordId;
    expect(timeEntryPid, 'Time entry should be created').toBeTruthy();

    // Verify via API
    const records = await queryFilteredList(page, 'pm-time-entry', 'pm_te_description', uid);
    expect(records.length, 'Created time entry should appear in list').toBeGreaterThanOrEqual(1);
  });

  test('TE-003: Submit time entry (draft -> submitted)', async ({ page }) => {
    expect(timeEntryPid, 'Time entry should have been created in TE-002').toBeTruthy();

    await executeCommandViaApi(page, 'pm:submit_time_entry', {}, timeEntryPid, 'update');

    // Verify status changed
    const records = await queryFilteredList(page, 'pm-time-entry', 'pm_te_description', uid, {
      extraFilters: [{ fieldName: 'pm_te_status', operator: 'EQ', value: 'submitted' }],
    });
    expect(records.length, 'Time entry should be submitted').toBeGreaterThanOrEqual(1);
  });

  test('TE-004: Approve time entry (submitted -> approved)', async ({ page }) => {
    expect(timeEntryPid, 'Time entry should have been created in TE-002').toBeTruthy();

    await executeCommandViaApi(page, 'pm:approve_time_entry', {}, timeEntryPid, 'update');

    // Verify status changed
    const records = await queryFilteredList(page, 'pm-time-entry', 'pm_te_description', uid, {
      extraFilters: [{ fieldName: 'pm_te_status', operator: 'EQ', value: 'approved' }],
    });
    expect(records.length, 'Time entry should be approved').toBeGreaterThanOrEqual(1);
  });
});
