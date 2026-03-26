/**
 * DP Inspection Task — UI E2E Tests
 *
 * Tests the inspection task lifecycle through actual UI interactions.
 * Flow: pending → in_progress → completed
 *
 * Inspection tasks are auto-created when an issue is triaged as CREATE_INSPECTION.
 * This spec tests the inspection management UI after tasks exist.
 */
import { test, expect } from '@playwright/test';
import {
  navigateToDynamicPage,
  uniqueId,
  executeCommandViaApi,
  acceptConfirmDialog,
} from '../helpers/index';
import { getTestProjectId } from '../quarry-management.setup';

const INSP_MODEL = 'dp_inspection_task';

async function searchByKeyword(page: any, keyword: string) {
  const field = page.locator('[data-testid="form-field-dp_task_no"] input').first();
  if (await field.isVisible({ timeout: 3000 }).catch(() => false)) {
    await field.fill(keyword);
    await page.locator('[data-testid="filter-search"]').click();
    await page.waitForResponse((r: any) => r.url().includes('/list') && r.status() === 200, { timeout: 10000 }).catch(() => null);
  }
}

test.describe('DP Inspection Task — UI Tests', () => {
  test.describe.configure({ mode: 'serial' });

  let projectId: string | null = null;
  let issuePid: string;
  let inspPid: string;
  let inspTaskNo = '';
  let inspIssueRef = '';
  const createdPids: string[] = [];

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json', baseURL: 'http://localhost:5173' });
    const page = await ctx.newPage();
    try {
      projectId = await getTestProjectId(page);
    } catch (e: any) {
      console.warn('PM/QO plugin not available:', e.message);
      await page.close();
      await ctx.close();
      return;
    }

    // Create issue → submit → triage CREATE_INSPECTION to get an inspection task
    const cr = await executeCommandViaApi(page, 'dp:create_issue', {
      dp_issue_project_id: projectId,
      dp_issue_title: `Insp UI Setup ${uniqueId()}`,
      dp_issue_content: 'Setup for inspection UI test',
      dp_issue_area: 'Test Area D',
      dp_issue_source: 'daily_inspection',
    });
    issuePid = cr.recordId;
    createdPids.push(issuePid);
    await executeCommandViaApi(page, 'dp:submit_issue', {}, issuePid, 'state_transition');
    await executeCommandViaApi(page, 'dp:triage_issue', {
      dp_triage_decision: 'create_inspection',
      dp_triage_remark: 'Setup for inspection test',
    }, issuePid, 'update');

    // Get the auto-created inspection task
    const inspResp = await page.request.get(
      `/api/dynamic/dp-inspection-task/list?pageSize=50&filters=${encodeURIComponent(
        JSON.stringify([{ fieldName: 'dp_task_issue_id', operator: 'EQ', value: issuePid }]),
      )}`,
    );
    const inspBody = await inspResp.json();
    const tasks = inspBody.data?.records ?? inspBody.data?.list ?? [];
    if (tasks.length > 0) {
      inspPid = tasks[0].id;
      const detailResp = await page.request.get(`/api/dynamic/dp-inspection-task/${inspPid}`);
      if (detailResp.ok()) {
        const detailBody = await detailResp.json();
        const detail = detailBody.data ?? detailBody;
        inspTaskNo = String(detail.dp_task_no ?? tasks[0].dp_task_no ?? '').trim();
        inspIssueRef = String(detail.dp_task_issue_id ?? tasks[0].dp_task_issue_id ?? '').trim();
      } else {
        inspTaskNo = String(tasks[0].dp_task_no ?? '').trim();
        inspIssueRef = String(tasks[0].dp_task_issue_id ?? '').trim();
      }
    }

    await page.close();
    await ctx.close();
  });

  test.afterAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json', baseURL: 'http://localhost:5173' });
    const page = await ctx.newPage();
    for (const pid of createdPids) {
      await executeCommandViaApi(page, 'dp:delete_issue', {}, pid, 'delete').catch(() => {});
    }
    await ctx.close();
  });

  // ---- List Display ----

  test('should display inspection task list with tabs', async ({ page }) => {
    if (!projectId) { throw new Error(String('Project not available - PM/QO plugin may not be imported')); }
    await navigateToDynamicPage(page, INSP_MODEL);
    await expect(page.locator('table, [role="table"]').first()).toBeVisible();

    // Verify tab navigation exists (all, pending, in_progress, completed)
    const tabNav = page.locator('nav[aria-label="Tabs"]').first();
    if (await tabNav.isVisible({ timeout: 5000 }).catch(() => false)) {
      const tabs = tabNav.locator('button');
      expect(await tabs.count()).toBeGreaterThanOrEqual(3);
    }
  });

  // ---- Start Inspection via Row Action ----

  test('should start inspection via row action (pending → in_progress)', async ({ page }) => {
    if (!inspPid) { throw new Error(String('No inspection task available')); }

    await navigateToDynamicPage(page, INSP_MODEL);

    // Switch to "待巡检" tab
    const pendingTab = page.locator('[data-testid="tab-pending"]').first();
    if (await pendingTab.isVisible({ timeout: 3000 }).catch(() => false)) {
      await pendingTab.click();
      await page.waitForResponse(
        (r) => r.url().includes('/list') && r.status() === 200,
        { timeout: 10000 }
      ).catch(() => null);
    }

    // Find target row and click "开始"
    const rowKey = inspTaskNo || inspIssueRef || issuePid;
    await searchByKeyword(page, rowKey);
    const row = page.locator('tbody tr', { hasText: rowKey }).first();
    await expect(row).toBeVisible({ timeout: 10000 });
    const startBtn = row.locator('[data-testid="row-action-start"]').first();
    await startBtn.click();

    // Wait for potential confirmation or list refresh
    const confirmDialog = page.locator('[data-testid="confirm-dialog"]');
    if (await confirmDialog.isVisible({ timeout: 2000 }).catch(() => false)) {
      await page.locator('[data-testid="confirm-ok"]').click();
    }

    await page.waitForResponse(
      (r) => r.url().includes('/list') && r.status() === 200,
      { timeout: 10000 }
    ).catch(() => null);

    // Verify: should appear in "巡检中" tab
    const inProgressTab = page.locator('[data-testid="tab-in_progress"]').first();
    if (await inProgressTab.isVisible({ timeout: 3000 }).catch(() => false)) {
      await inProgressTab.click();
      await page.waitForResponse(
        (r) => r.url().includes('/list') && r.status() === 200,
        { timeout: 10000 }
      ).catch(() => null);
      await searchByKeyword(page, rowKey);
      const inProgressRow = page.locator('tbody tr', { hasText: rowKey }).first();
      await expect(inProgressRow).toBeVisible({ timeout: 10000 });
    }
  });

  // ---- Complete Inspection via Row Action ----

  test('should complete inspection via row action (in_progress → completed)', async ({ page }) => {
    if (!inspPid) { throw new Error(String('No inspection task available')); }

    await navigateToDynamicPage(page, INSP_MODEL);

    // Switch to "巡检中" tab
    const inProgressTab = page.locator('[data-testid="tab-in_progress"]').first();
    if (await inProgressTab.isVisible({ timeout: 3000 }).catch(() => false)) {
      await inProgressTab.click();
      await page.waitForResponse(
        (r) => r.url().includes('/list') && r.status() === 200,
        { timeout: 10000 }
      ).catch(() => null);
    }

    // Click "完成" on target row
    const rowKey = inspTaskNo || inspIssueRef || issuePid;
    await searchByKeyword(page, rowKey);
    const row = page.locator('tbody tr', { hasText: rowKey }).first();
    await expect(row).toBeVisible({ timeout: 10000 });
    const completeBtn = row.locator('[data-testid="row-action-complete"]').first();
    await completeBtn.click();

    // Accept confirmation
    await acceptConfirmDialog(page);

    // Wait for refresh
    await page.waitForResponse(
      (r) => r.url().includes('/list') && r.status() === 200,
      { timeout: 10000 }
    ).catch(() => null);

    // Verify: should appear in "已完成" tab
    const completedTab = page.locator('[data-testid="tab-completed"]').first();
    if (await completedTab.isVisible({ timeout: 3000 }).catch(() => false)) {
      await completedTab.click();
      await page.waitForResponse(
        (r) => r.url().includes('/list') && r.status() === 200,
        { timeout: 10000 }
      ).catch(() => null);
      await searchByKeyword(page, rowKey);
      const completedRow = page.locator('tbody tr', { hasText: rowKey }).first();
      await expect(completedRow).toBeVisible({ timeout: 10000 });
    }

    // API: verify status
    if (inspPid) {
      const resp = await page.request.get(`/api/dynamic/dp-inspection-task/${inspPid}`);
      if (resp.ok()) {
        const body = await resp.json();
        const data = body.data ?? body;
        expect(data.dp_task_status).toBe('completed');
      }
    }
  });

  // ---- Tab Filtering ----

  test('should filter inspection tasks by status tabs', async ({ page }) => {
    await navigateToDynamicPage(page, INSP_MODEL);

    const tabNav = page.locator('nav[aria-label="Tabs"]').first();
    if (!(await tabNav.isVisible({ timeout: 5000 }).catch(() => false))) {
      throw new Error(String('No tab navigation visible'))
    }

    // Click "全部" tab
    const allTab = page.locator('[data-testid="tab-all"]').first();
    if (await allTab.isVisible({ timeout: 3000 }).catch(() => false)) {
      await allTab.click();
      await page.waitForResponse(
        (r) => r.url().includes('/list') && r.status() === 200,
        { timeout: 10000 }
      ).catch(() => null);
    }

    // Click "已完成" tab and verify records exist
    const completedTab = page.locator('[data-testid="tab-completed"]').first();
    if (await completedTab.isVisible({ timeout: 3000 }).catch(() => false)) {
      await completedTab.click();
      await page.waitForResponse(
        (r) => r.url().includes('/list') && r.status() === 200,
        { timeout: 10000 }
      ).catch(() => null);
      await expect(page.locator('tbody tr').first()).toBeVisible({ timeout: 10000 });
    }
  });
});
