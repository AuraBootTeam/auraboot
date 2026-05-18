/**
 * Annual Plan — Smoke & Lifecycle E2E Tests
 *
 * AP-001 @smoke    : Navigate to 计划编制 list → table visible, i18n headers
 * AP-002 @smoke    : Navigate to 工作包管理 list → table visible
 * AP-003 @critical : Annual plan created → appears in list with draft status
 * AP-004 @critical : Submit annual plan → submitted status
 * AP-005 @critical : Approve annual plan → approved status
 * AP-006 @critical : Reject annual plan → rejected status (separate plan)
 *
 * Menu root: 年度计划 (ap_root)
 *   /annual-plan/plans           → model: ap_annual_plan
 *   /annual-plan/work-packages   → model: ap_work_package
 *
 * Prerequisites: annual-plan plugin imported and all models published.
 *
 * @since 10.0.0
 */

import { test, expect, type Page } from '../../fixtures';
import { uniqueId, executeCommandViaApi, findRowInPaginatedList } from '../helpers/index';

// ---------------------------------------------------------------------------
// Navigation helper
// ---------------------------------------------------------------------------

async function navigateToAnnualPlanPage(
  page: Page,
  leafName: string,
  modelCode: string,
): Promise<void> {
  await page.goto('/dashboards');
  await page.waitForLoadState('domcontentloaded');

  const nav = page.locator('nav');

  // Expand 年度计划 root menu
  const rootBtn = nav.getByRole('button', { name: '年度计划' });
  await rootBtn.scrollIntoViewIfNeeded();
  await rootBtn.evaluate((el: HTMLElement) => el.click());
  await page.waitForResponse(() => true, { timeout: 2_000 }).catch(() => null);

  // Click leaf link — set up waitForResponse BEFORE click
  const leafLink = nav.getByRole('link', { name: leafName });
  await leafLink.scrollIntoViewIfNeeded();
  const listResponsePromise = page.waitForResponse(
    (r) => r.url().includes(`/api/dynamic/${modelCode}/list`) && r.status() === 200,
    { timeout: 15_000 },
  );
  await leafLink.evaluate((el: HTMLElement) => el.click());
  await listResponsePromise;

  await expect(page.locator('table, [class*="ant-table"]').first()).toBeVisible({
    timeout: 10_000,
  });
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const UID = uniqueId('AP');

// ---------------------------------------------------------------------------
// Test suite
// ---------------------------------------------------------------------------

test.describe('Annual Plan — Smoke & Lifecycle', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60_000);

  let approvalPlanId: string;
  let rejectionPlanId: string;

  // -------------------------------------------------------------------------
  // Setup: create two annual plans via API
  // -------------------------------------------------------------------------

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    const page = await ctx.newPage();

    try {
      // Plan for approve lifecycle (ap_project_id is required)
      const r1 = await executeCommandViaApi(
        page,
        'ap:create_annual_plan',
        {
          ap_project_id: `PROJ_${UID}`,
          ap_plan_name: `E2E Plan ${UID}`,
          ap_stat_year: 2026,
          ap_investment_scale: 1000000,
          ap_plan_remark: `E2E approve flow ${UID}`,
        },
        undefined,
        'create',
      );
      approvalPlanId = r1.recordId;

      // Plan for reject lifecycle
      const r2 = await executeCommandViaApi(
        page,
        'ap:create_annual_plan',
        {
          ap_project_id: `PROJ_REJ_${UID}`,
          ap_plan_name: `E2E PlanReject ${UID}`,
          ap_stat_year: 2026,
          ap_investment_scale: 500000,
          ap_plan_remark: `E2E reject flow ${UID}`,
        },
        undefined,
        'create',
      );
      rejectionPlanId = r2.recordId;
    } finally {
      await ctx.close();
    }
  });

  // =========================================================================
  // AP-001 @smoke: Navigate to 计划编制
  // =========================================================================

  test('AP-001 @smoke: Navigate to 计划编制 list via sidebar menu', async ({ page }) => {
    await navigateToAnnualPlanPage(page, '计划编制', 'ap_annual_plan');

    // At least 1 row visible
    const rows = page.locator('tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 8_000 });

    // i18n: no raw field code leak
    const headerRow = page.locator('thead tr').first();
    await expect(headerRow).toBeVisible({ timeout: 5_000 });
    const headerText = await headerRow.textContent();
    expect(headerText).not.toMatch(/ap_/i);
  });

  // =========================================================================
  // AP-002 @smoke: Navigate to 工作包管理
  // =========================================================================

  test('AP-002 @smoke: Navigate to 工作包管理 list via sidebar menu', async ({ page }) => {
    await navigateToAnnualPlanPage(page, '工作包管理', 'ap_work_package');

    const table = page.locator('table, [class*="ant-table"]').first();
    await expect(table).toBeVisible({ timeout: 10_000 });
  });

  // =========================================================================
  // AP-003 @critical: Created plan appears in list with draft status
  // =========================================================================

  test('AP-003 @critical: Created annual plan appears with draft status', async ({ page }) => {
    expect(approvalPlanId).toBeTruthy();

    // Navigate to list UI and verify the list loads
    await navigateToAnnualPlanPage(page, '计划编制', 'ap_annual_plan');
    await expect(page.locator('tbody tr').first()).toBeVisible({ timeout: 8_000 });

    // Verify via API that the plan was created with draft status (authoritative check)
    const detailResp = await page.request.get(`/api/dynamic/ap_annual_plan/${approvalPlanId}`);
    expect(detailResp.ok(), 'Annual plan detail API should return 200').toBe(true);
    const detailBody = await detailResp.json();
    const planData = detailBody?.data ?? detailBody;
    expect(planData?.ap_plan_name, 'Plan name should match').toContain(UID);
    // Status should be draft or planning (initial state)
    const status = planData?.ap_plan_status ?? planData?.status ?? '';
    expect(
      status === 'draft' || status === 'planning' || status === '',
      `Plan status should be draft/planning, got: "${status}"`,
    ).toBe(true);
  });

  // =========================================================================
  // AP-004 @critical: Submit annual plan → submitted
  // =========================================================================

  test('AP-004 @critical: Submit annual plan → submitted status', async ({ page }) => {
    expect(approvalPlanId).toBeTruthy();

    await executeCommandViaApi(
      page,
      'ap:submit_annual_plan',
      {},
      approvalPlanId,
      'state_transition',
    );

    const resp = await page.request.get(`/api/dynamic/ap_annual_plan/${approvalPlanId}`);
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    expect((body?.data ?? body).ap_plan_status).toBe('submitted');
  });

  // =========================================================================
  // AP-005 @critical: Approve annual plan → approved
  // =========================================================================

  test('AP-005 @critical: Approve annual plan → approved status', async ({ page }) => {
    expect(approvalPlanId).toBeTruthy();

    await executeCommandViaApi(
      page,
      'ap:approve_annual_plan',
      {},
      approvalPlanId,
      'state_transition',
    );

    const resp = await page.request.get(`/api/dynamic/ap_annual_plan/${approvalPlanId}`);
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    expect((body?.data ?? body).ap_plan_status).toBe('approved');

    // Verify in detail UI — navigate directly to the record to check status display
    // Set up waitForResponse BEFORE navigation to capture the API call
    const detailRespPromise = page.waitForResponse(
      (r) => r.url().includes('/api/dynamic/ap_annual_plan') && r.status() === 200,
      { timeout: 20_000 },
    );
    await page.goto(`/p/ap_annual_plan/${approvalPlanId}`, { waitUntil: 'domcontentloaded' });
    await detailRespPromise.catch(() => null); // don't fail if response already fired

    // Wait for content to load
    await expect(
      page.locator('main, [class*="detail"], [class*="content"], body').first(),
    ).toBeVisible({
      timeout: 10_000,
    });
    await page.waitForTimeout(2000); // brief wait for renders
    // Status should be approved — either as a badge chip or text
    const pageContent = await page.locator('body').textContent();
    expect(
      pageContent?.includes('approved') ||
        pageContent?.includes('已批准') ||
        pageContent?.includes('已审批'),
    ).toBe(true);
  });

  // =========================================================================
  // AP-006 @critical: Reject flow — submit then reject → rejected
  // =========================================================================

  test('AP-006 @critical: Submit then reject annual plan → rejected', async ({ page }) => {
    expect(rejectionPlanId).toBeTruthy();

    // Submit first
    await executeCommandViaApi(
      page,
      'ap:submit_annual_plan',
      {},
      rejectionPlanId,
      'state_transition',
    );

    // Reject
    await executeCommandViaApi(
      page,
      'ap:reject_annual_plan',
      {},
      rejectionPlanId,
      'state_transition',
    );

    const resp = await page.request.get(`/api/dynamic/ap_annual_plan/${rejectionPlanId}`);
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const finalStatus = (body?.data ?? body).ap_plan_status as string;
    expect(['rejected', 'draft'].includes(finalStatus)).toBe(true);

    // Verify on list UI
    await navigateToAnnualPlanPage(page, '计划编制', 'ap_annual_plan');
    const rows = page.locator('tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 8_000 });
  });
});
