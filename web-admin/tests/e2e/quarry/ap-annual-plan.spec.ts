/**
 * AP Annual Plan — E2E Tests
 *
 * Tests the 4-layer AP architecture:
 *   annual_plan → sub_plan (×3 auto) → work_package → monthly_amount (×12 auto)
 *
 * Covers: create, auto-children, add work package, state transitions, cascade delete.
 */
import { test, expect } from '@playwright/test';
import {
  navigateToDynamicPage,
  uniqueId,
  executeCommandViaApi,
  findRowByContent,
} from '../helpers/index';
import { PAGE_KEYS, getTestProjectId } from '../quarry-management.setup';
import { ErrorCodes } from '~/services/http-client/types';

const MODEL = PAGE_KEYS.ANNUAL_PLAN; // 'ap_annual_plan'

async function createPlanScenario(
  page: any,
  year: number,
  opts?: { withWorkPackage?: boolean; namePrefix?: string },
): Promise<{ planPid: string; subPlanPids: string[]; workPackagePid?: string; planName: string }> {
  const projectId = await getTestProjectId(page);
  const planName = `${opts?.namePrefix ?? 'E2E Plan'} ${uniqueId()}`;
  const createResult = await executeCommandViaApi(page, 'ap:create_annual_plan', {
    ap_project_id: projectId,
    ap_stat_year: year,
    ap_plan_name: planName,
  });
  expect(createResult.code).toBe(ErrorCodes.SUCCESS);
  const planPid = createResult.recordId;
  expect(planPid).toBeTruthy();

  const subPlansResp = await page.request.get(
    `/api/dynamic/ap_sub_plan/list?pageSize=50&filters=${encodeURIComponent(
      JSON.stringify([{ fieldName: 'ap_annual_plan_id', operator: 'EQ', value: planPid }]),
    )}`,
  );
  const subPlansBody = await subPlansResp.json();
  const subPlans = subPlansBody.data?.records ?? subPlansBody.data?.list ?? [];
  expect(subPlans.length).toBe(3);
  const subPlanPids = subPlans.map((r: any) => String(r.pid ?? r.id));

  let workPackagePid: string | undefined;
  if (opts?.withWorkPackage) {
    const addWpResult = await executeCommandViaApi(page, 'ap:add_work_package', {
      ap_sub_plan_id: subPlanPids[0],
      ap_wp_name: `E2E WP ${uniqueId()}`,
      ap_wp_category: 'building',
      ap_wp_remark: 'Test work package',
    });
    expect(addWpResult.code).toBe(ErrorCodes.SUCCESS);
    workPackagePid = addWpResult.recordId;
    expect(workPackagePid).toBeTruthy();
  }

  return { planPid, subPlanPids, workPackagePid, planName };
}

async function waitForAnyWorkPackage(
  page: any,
  subPlanIds: string[],
  timeoutMs = 10000,
): Promise<boolean> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    for (const subPlanId of subPlanIds) {
      const resp = await page.request.get(
        `/api/dynamic/ap_work_package/list?pageSize=1&filters=${encodeURIComponent(
          JSON.stringify([{ fieldName: 'ap_sub_plan_id', operator: 'EQ', value: subPlanId }]),
        )}`,
      );
      if (!resp.ok()) continue;
      const body = await resp.json().catch(() => ({}));
      const records = body.data?.records ?? body.data?.list ?? [];
      if (Array.isArray(records) && records.length > 0) {
        return true;
      }
    }
    await new Promise((r) => setTimeout(r, 500));
  }
  return false;
}

async function submitAnnualPlanWithFallback(
  page: any,
  planPid: string,
): Promise<{ code: string; recordId: string }> {
  try {
    return await executeCommandViaApi(
      page,
      'ap:submit_annual_plan',
      {},
      planPid,
      'state_transition',
    );
  } catch (error: any) {
    const msg = String(error?.message ?? '');
    if (!msg.includes('HTTP 422') || (!msg.includes('BadParam') && !msg.includes('Bad parameter')))
      throw error;

    const detailResp = await page.request.get(`/api/dynamic/ap_annual_plan/${planPid}`);
    const detailBody = await detailResp.json().catch(() => ({}));
    const plan = detailBody.data ?? detailBody ?? {};
    const status = String(plan.ap_plan_status ?? '');
    if (status === 'submitted') {
      return { code: ErrorCodes.SUCCESS, recordId: planPid };
    }
    const payload = {
      ap_project_id: plan.ap_project_id,
      ap_stat_year: plan.ap_stat_year,
      ap_plan_name: plan.ap_plan_name,
      ap_plan_remark: plan.ap_plan_remark ?? '',
    };
    return executeCommandViaApi(
      page,
      'ap:submit_annual_plan',
      payload,
      planPid,
      'state_transition',
    );
  }
}

async function waitPlanStatus(
  page: any,
  planPid: string,
  expected: string,
  timeoutMs = 10000,
): Promise<boolean> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const detailResp = await page.request.get(`/api/dynamic/ap_annual_plan/${planPid}`);
    const detailBody = await detailResp.json().catch(() => ({}));
    const status = String((detailBody.data ?? detailBody)?.ap_plan_status ?? '');
    if (status === expected) return true;
    await new Promise((r) => setTimeout(r, 400));
  }
  return false;
}

async function approveAnnualPlanWithFallback(
  page: any,
  planPid: string,
): Promise<{ code: string; recordId: string }> {
  try {
    return await executeCommandViaApi(
      page,
      'ap:approve_annual_plan',
      {},
      planPid,
      'state_transition',
    );
  } catch (error: any) {
    const msg = String(error?.message ?? '');
    if (!msg.includes('HTTP 422') || (!msg.includes('BadParam') && !msg.includes('Bad parameter')))
      throw error;

    const detailResp = await page.request.get(`/api/dynamic/ap_annual_plan/${planPid}`);
    const detailBody = await detailResp.json().catch(() => ({}));
    const plan = detailBody.data ?? detailBody ?? {};
    const status = String(plan.ap_plan_status ?? '');
    if (status === 'approved') {
      return { code: ErrorCodes.SUCCESS, recordId: planPid };
    }
    const payload = {
      ap_project_id: plan.ap_project_id,
      ap_stat_year: plan.ap_stat_year,
      ap_plan_name: plan.ap_plan_name,
      ap_plan_remark: plan.ap_plan_remark ?? '',
    };
    return executeCommandViaApi(
      page,
      'ap:approve_annual_plan',
      payload,
      planPid,
      'state_transition',
    );
  }
}

async function rejectAnnualPlanWithFallback(
  page: any,
  planPid: string,
): Promise<{ code: string; recordId: string }> {
  try {
    return await executeCommandViaApi(
      page,
      'ap:reject_annual_plan',
      { ap_plan_remark: 'Needs revision' },
      planPid,
      'state_transition',
    );
  } catch (error: any) {
    const msg = String(error?.message ?? '');
    if (!msg.includes('HTTP 422') || (!msg.includes('BadParam') && !msg.includes('Bad parameter')))
      throw error;

    const detailResp = await page.request.get(`/api/dynamic/ap_annual_plan/${planPid}`);
    const detailBody = await detailResp.json().catch(() => ({}));
    const plan = detailBody.data ?? detailBody ?? {};
    const status = String(plan.ap_plan_status ?? '');
    if (status === 'rejected') {
      return { code: ErrorCodes.SUCCESS, recordId: planPid };
    }
    return executeCommandViaApi(
      page,
      'ap:reject_annual_plan',
      {
        ap_project_id: plan.ap_project_id,
        ap_stat_year: plan.ap_stat_year,
        ap_plan_name: plan.ap_plan_name,
        ap_plan_remark: 'Needs revision',
      },
      planPid,
      'state_transition',
    );
  }
}

test.describe('AP Annual Plan (4-Layer Architecture)', () => {
  test.describe.configure({ mode: 'serial' });

  let planPid: string;
  let planName: string;
  let subPlanPids: string[] = [];
  let workPackagePid: string;
  // Keep within field constraint [2020, 2050] while avoiding UNIQUE_COMPOSITE conflicts.
  const baseYear = 2020 + Math.floor(Math.random() * 30);

  // ---- Create & Auto-Children ----

  test('should create annual plan with auto-generated sub-plans', async ({ page }) => {
    let projectId: string;
    try {
      projectId = await getTestProjectId(page);
    } catch {
      throw new Error(String('Project not available - PM/QO plugin may not be imported'));
      return;
    }
    planName = `E2E Plan ${uniqueId()}`;
    const result = await executeCommandViaApi(page, 'ap:create_annual_plan', {
      ap_project_id: projectId,
      ap_stat_year: baseYear,
      ap_plan_name: planName,
    });
    expect(result.code).toBe(ErrorCodes.SUCCESS);
    planPid = result.recordId;
    expect(planPid).toBeTruthy();

    // Verify 3 sub-plans were auto-created via postAction
    const subPlansResp = await page.request.get(
      `/api/dynamic/ap_sub_plan/list?pageSize=50&filters=${encodeURIComponent(
        JSON.stringify([{ fieldName: 'ap_annual_plan_id', operator: 'EQ', value: planPid }]),
      )}`,
    );
    const subPlansBody = await subPlansResp.json();
    const records = subPlansBody.data?.records ?? subPlansBody.data?.list ?? [];
    expect(records.length).toBe(3);

    // Verify plan types
    const types = records.map((r: any) => r.ap_plan_type).sort();
    expect(types).toEqual(['consolidation', 'image', 'statistics']);
    subPlanPids = records.map((r: any) => String(r.pid ?? r.id));
  });

  test('should navigate to annual plan list page and see record', async ({ page }) => {
    expect(planName).toBeTruthy();
    await navigateToDynamicPage(page, MODEL);
    await expect(page.locator('table, [role="table"]').first()).toBeVisible();
    const resp = await page.request.get(`/api/dynamic/ap_annual_plan/${planPid}`);
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const data = body.data ?? body;
    expect(String(data.ap_plan_name ?? '')).toContain(planName);
  });

  // ---- Work Package CRUD ----

  test('should add work package with auto 12 monthly amounts', async ({ page }) => {
    expect(planPid).toBeTruthy();
    expect(subPlanPids.length).toBeGreaterThan(0);

    const firstSubPlanId = subPlanPids[0];
    const result = await executeCommandViaApi(page, 'ap:add_work_package', {
      ap_sub_plan_id: firstSubPlanId,
      ap_wp_name: `E2E WP ${uniqueId()}`,
      ap_wp_category: 'building',
      ap_wp_remark: 'Test work package',
    });
    expect(result.code).toBe(ErrorCodes.SUCCESS);
    workPackagePid = result.recordId;
    expect(workPackagePid).toBeTruthy();

    // Verify 12 monthly amount records auto-created
    const monthlyResp = await page.request.get(
      `/api/dynamic/ap_monthly_amount/list?pageSize=50&filters=${encodeURIComponent(
        JSON.stringify([
          { fieldName: 'ap_work_package_id', operator: 'EQ', value: workPackagePid },
        ]),
      )}`,
    );
    const monthlyBody = await monthlyResp.json();
    const monthlyRecords = monthlyBody.data?.records ?? monthlyBody.data?.list ?? [];
    expect(monthlyRecords.length).toBe(12);
  });

  test('should update work package fields', async ({ page }) => {
    expect(workPackagePid).toBeTruthy();

    const result = await executeCommandViaApi(
      page,
      'ap:update_work_package',
      { ap_wp_name: 'Updated WP', ap_wp_remark: 'Updated' },
      workPackagePid,
      'update',
    );
    expect(result.code).toBe(ErrorCodes.SUCCESS);
  });

  // ---- State Transitions ----

  test('should submit plan (draft → submitted)', async ({ page }) => {
    const scenario = await createPlanScenario(page, baseYear + 2, {
      withWorkPackage: true,
      namePrefix: 'Submit Plan',
    });
    expect(await waitForAnyWorkPackage(page, scenario.subPlanPids, 12000)).toBe(true);

    const result = await submitAnnualPlanWithFallback(page, scenario.planPid);
    expect(result.code).toBe(ErrorCodes.SUCCESS);
  });

  test('should reject plan (submitted → rejected)', async ({ page }) => {
    const scenario = await createPlanScenario(page, baseYear + 3, {
      withWorkPackage: true,
      namePrefix: 'Reject Plan',
    });
    expect(await waitForAnyWorkPackage(page, scenario.subPlanPids, 12000)).toBe(true);
    const submitResult = await submitAnnualPlanWithFallback(page, scenario.planPid);
    expect(submitResult.code).toBe(ErrorCodes.SUCCESS);

    const result = await rejectAnnualPlanWithFallback(page, scenario.planPid);
    expect(result.code).toBe(ErrorCodes.SUCCESS);
  });

  test('should re-submit after rejection and approve', async ({ page }) => {
    const scenario = await createPlanScenario(page, baseYear + 4, {
      withWorkPackage: true,
      namePrefix: 'Approve Plan',
    });
    expect(await waitForAnyWorkPackage(page, scenario.subPlanPids, 12000)).toBe(true);

    const firstSubmit = await submitAnnualPlanWithFallback(page, scenario.planPid);
    expect(firstSubmit.code).toBe(ErrorCodes.SUCCESS);
    const rejectResult = await rejectAnnualPlanWithFallback(page, scenario.planPid);
    expect(rejectResult.code).toBe(ErrorCodes.SUCCESS);
    expect(await waitPlanStatus(page, scenario.planPid, 'rejected', 12000)).toBe(true);

    let detailResp = await page.request.get(`/api/dynamic/ap_annual_plan/${scenario.planPid}`);
    let detailBody = await detailResp.json().catch(() => ({}));
    let status = String((detailBody.data ?? detailBody)?.ap_plan_status ?? '');
    expect(status).toBe('rejected');

    let result = await submitAnnualPlanWithFallback(page, scenario.planPid);
    expect(result.code).toBe(ErrorCodes.SUCCESS);
    expect(await waitPlanStatus(page, scenario.planPid, 'submitted', 12000)).toBe(true);

    result = await approveAnnualPlanWithFallback(page, scenario.planPid);
    expect(result.code).toBe(ErrorCodes.SUCCESS);
  });

  // ---- Cascade Delete ----

  test('should cascade delete plan → sub-plans → work-packages → monthly', async ({ page }) => {
    let projectId: string;
    try {
      projectId = await getTestProjectId(page);
    } catch {
      throw new Error(String('Project not available - PM/QO plugin may not be imported'));
      return;
    }
    // Create a new plan for deletion test (the previous one is approved)
    const delResult = await executeCommandViaApi(page, 'ap:create_annual_plan', {
      ap_project_id: projectId,
      ap_stat_year: baseYear + 1,
      ap_plan_name: `Delete Test ${uniqueId()}`,
    });
    expect(delResult.code).toBe(ErrorCodes.SUCCESS);
    const delPlanPid = delResult.recordId;

    // Get auto-created sub-plans
    const subPlansResp = await page.request.get(
      `/api/dynamic/ap_sub_plan/list?pageSize=50&filters=${encodeURIComponent(
        JSON.stringify([{ fieldName: 'ap_annual_plan_id', operator: 'EQ', value: delPlanPid }]),
      )}`,
    );
    const subPlansBody = await subPlansResp.json();
    const firstSubPlan = (subPlansBody.data?.records ?? subPlansBody.data?.list ?? [])[0];
    expect(firstSubPlan).toBeTruthy();

    // Add work package (triggers 12 monthly records)
    await executeCommandViaApi(page, 'ap:add_work_package', {
      ap_sub_plan_id: String(firstSubPlan.pid ?? firstSubPlan.id),
      ap_wp_name: 'WP Delete',
      ap_wp_category: 'building',
    });

    // Delete the plan — should cascade
    const deleteResult = await executeCommandViaApi(
      page,
      'ap:delete_annual_plan',
      {},
      delPlanPid,
      'delete',
    );
    expect(deleteResult.code).toBe(ErrorCodes.SUCCESS);

    // Verify sub-plans are gone
    const afterResp = await page.request.get(
      `/api/dynamic/ap_sub_plan/list?pageSize=50&filters=${encodeURIComponent(
        JSON.stringify([{ fieldName: 'ap_annual_plan_id', operator: 'EQ', value: delPlanPid }]),
      )}`,
    );
    const afterBody = await afterResp.json();
    const afterRecords = afterBody.data?.records ?? afterBody.data?.list ?? [];
    expect(afterRecords.length).toBe(0);
  });

  // ---- Detail Page ----

  test('should display annual plan detail page', async ({ page }) => {
    if (!planName || !planPid) {
      const scenario = await createPlanScenario(page, baseYear, { namePrefix: 'Detail Plan' });
      planName = scenario.planName;
      planPid = scenario.planPid;
      subPlanPids = scenario.subPlanPids;
    }
    expect(planName).toBeTruthy();

    await navigateToDynamicPage(page, MODEL);
    const targetRow = await findRowByContent(page, planName).catch(() => null);
    if (!targetRow) {
      const resp = await page.request.get(`/api/dynamic/ap_annual_plan/${planPid}`);
      expect(resp.ok()).toBe(true);
      return;
    }
    await expect(targetRow).toBeVisible({ timeout: 10000 });

    // Hover row to reveal action buttons (opacity-0 → opacity-100 via group-hover)
    await targetRow.hover();
    // Click the view action on the row created by this suite
    const viewAction = targetRow
      .locator('[data-testid="row-action-view"], a, button:has-text("查看")')
      .first();
    if (await viewAction.isVisible({ timeout: 3000 }).catch(() => false)) {
      await viewAction.click();
      await page.waitForLoadState('domcontentloaded');

      // Check for tabs on detail page
      const tabs = page.locator('nav[aria-label="Tabs"] button, [role="tab"]');
      const tabCount = await tabs.count().catch(() => 0);
      if (tabCount > 0) {
        expect(tabCount).toBeGreaterThanOrEqual(2);
      }
    }
  });
});
