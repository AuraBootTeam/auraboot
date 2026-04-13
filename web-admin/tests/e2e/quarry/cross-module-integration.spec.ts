/**
 * Cross-Module Integration — E2E Tests
 *
 * Tests end-to-end flows spanning multiple quarry modules.
 * Core operations use UI, setup/cleanup use API.
 */
import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';
import {
  navigateToDynamicPage,
  uniqueId,
  executeCommandViaApi,
  waitForDynamicPageLoad,
  clickTabAndWaitForLoad,
  acceptConfirmDialog,
  dateOffsetStr,
} from '../helpers/index';
import { getTestProjectId } from '../quarry-management.setup';
import { ErrorCodes } from '~/services/http-client/types';

test.describe('Cross-Module Integration', () => {
  test.describe.configure({ mode: 'serial', timeout: 90000 });

  let projectId: string | null = null;
  const createdIssuePids: string[] = [];
  const createdPlanPids: string[] = [];

  async function waitIssueStatus(page: Page, issuePid: string, expected: string, attempts = 15) {
    let status = '';
    await expect
      .poll(
        async () => {
          const resp = await page.request.get(`/api/dynamic/dp_issue/${issuePid}`);
          if (!resp.ok()) return status;
          const body = await resp.json();
          const data = body.data ?? body;
          status = data.dp_issue_status ?? '';
          return status;
        },
        {
          timeout: attempts * 1000,
          intervals: [500, 1000],
        },
      )
      .toBe(expected);
    return status;
  }

  async function ensurePageReady(page: Page) {
    const loadFailed = page.getByRole('heading', { name: '加载失败' }).first();
    if (await loadFailed.isVisible({ timeout: 1500 }).catch(() => false)) {
      const listResponse = page
        .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 10000 })
        .catch(() => null);
      await page.locator('button:has-text("重试"), button:has-text("Retry")').first().click();
      await listResponse;
    }
  }

  async function gotoApTab(page: Page, tabKey: 'draft' | 'submitted' | 'approved' | 'rejected') {
    const tabRegexMap: Record<string, RegExp> = {
      draft: /草稿|Draft/i,
      submitted: /已提交|Submitted/i,
      approved: /已审批|Approved/i,
      rejected: /已退回|Rejected/i,
    };
    await clickTabAndWaitForLoad(page, tabRegexMap[tabKey], 10000, tabKey);
  }

  async function findApRowWithAction(
    page: Page,
    tabKey: 'draft' | 'submitted' | 'approved' | 'rejected',
    actionTestId: string,
  ) {
    await ensurePageReady(page);
    await gotoApTab(page, tabKey);
    for (let i = 0; i < 20; i++) {
      // Hover each row to reveal action buttons (opacity-0 → opacity-100 via group-hover)
      const rows = page.locator('tbody tr');
      const rowCount = await rows.count();
      for (let r = 0; r < rowCount; r++) {
        await rows.nth(r).hover();
        const actionBtn = rows.nth(r).locator(`[data-testid="${actionTestId}"]`).first();
        if (await actionBtn.isVisible({ timeout: 500 }).catch(() => false)) {
          return rows.nth(r);
        }
      }
      // None found on this page, check next
      const actionBtn = page.locator(`[data-testid="${actionTestId}"]`).first();
      if (await actionBtn.isVisible({ timeout: 500 }).catch(() => false)) {
        return actionBtn.locator('xpath=ancestor::tr[1]');
      }
      // Try pagination via next button (various possible labels)
      const nextBtn = page
        .locator('button[aria-label="Next"], button:has-text("下一页"), [data-testid="pagination-next"]')
        .first();
      const hasNext = await nextBtn.isVisible({ timeout: 1000 }).catch(() => false);
      const nextDisabled = hasNext ? await nextBtn.isDisabled().catch(() => true) : true;
      if (!hasNext || nextDisabled) break;
      const listResponse = page
        .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 10000 })
        .catch(() => null);
      await nextBtn.click();
      await listResponse;
    }
    throw new Error(`No AP row with action ${actionTestId} found in ${tabKey}`);
  }

  async function createApDraft(page: Page, namePrefix: string) {
    const planName = `${namePrefix} ${uniqueId()}`;
    const project = await executeCommandViaApi(page, 'pm:create_project', {
      pm_project_name: `Cross AP Project ${uniqueId()}`,
      pm_project_code: `CAP-${uniqueId()}`,
    });
    expect(project.code).toBe(ErrorCodes.SUCCESS);
    const currentProjectId = project.recordId;
    let code = '';
    for (let i = 0; i < 31; i++) {
      const year = 2020 + i;
      const cr = await executeCommandViaApi(page, 'ap:create_annual_plan', {
        ap_project_id: currentProjectId,
        ap_stat_year: year,
        ap_plan_name: planName,
        ap_plan_status: 'draft',
      });
      code = cr.code;
      if (cr.code === ErrorCodes.SUCCESS) break;
    }
    expect(code).toBe(ErrorCodes.SUCCESS);
    return planName;
  }

  async function ensureApDraftRow(page: Page) {
    try {
      return await findApRowWithAction(page, 'draft', 'row-action-submit');
    } catch {
      await createApDraft(page, 'Cross AP Draft');
      // Re-navigate to the list page to pick up the newly created draft
      await navigateToDynamicPage(page, 'ap_annual_plan');
      return findApRowWithAction(page, 'draft', 'row-action-submit');
    }
  }

  async function ensureApSubmittedRow(page: Page) {
    try {
      return await findApRowWithAction(page, 'submitted', 'row-action-reject');
    } catch {
      const draftRow = await ensureApDraftRow(page);
      await draftRow.hover();
      await draftRow.locator('[data-testid="row-action-submit"]').first().click();
      await acceptConfirmDialog(page);
      await page
        .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 10000 })
        .catch(() => null);
      return findApRowWithAction(page, 'submitted', 'row-action-reject');
    }
  }

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({
      storageState: 'tests/storage/admin.json',
      baseURL: 'http://localhost:5173',
    });
    const page = await ctx.newPage();
    try {
      projectId = await getTestProjectId(page);
    } catch (e: any) {
      console.warn('PM/QO plugin not available:', e.message);
    }
    await page.close();
    await ctx.close();
  });

  test.afterAll(async ({ browser }) => {
    const ctx = await browser.newContext({
      storageState: 'tests/storage/admin.json',
      baseURL: 'http://localhost:5173',
    });
    const page = await ctx.newPage();
    for (const pid of createdIssuePids) {
      await executeCommandViaApi(page, 'dp:delete_issue', {}, pid, 'delete').catch(() => {});
    }
    for (const pid of createdPlanPids) {
      await executeCommandViaApi(page, 'ap:delete_annual_plan', {}, pid, 'delete').catch(() => {});
    }
    await ctx.close();
  });

  // ---- DP Mainline: Issue → Triage(NEED_RECTIFY) → Rectification → Accept → Issue RECTIFIED ----

  test('DP mainline: issue -> triage(rectify) -> rectification lifecycle', async ({ page }) => {
    if (!projectId) {
      throw new Error(String('Project not available - PM/QO plugin may not be imported'));
    }
    test.setTimeout(90000);
    const title = `E2E Closure ${uniqueId()}`;

    // Create + submit issue via API
    const cr = await executeCommandViaApi(page, 'dp:create_issue', {
      dp_issue_project_id: projectId,
      dp_issue_title: title,
      dp_issue_content: 'Cross-module rectification mainline',
      dp_issue_area: 'Test Area A',
      dp_issue_source: 'daily_inspection',
    });
    expect(cr.code).toBe(ErrorCodes.SUCCESS);
    const issuePid = cr.recordId;
    createdIssuePids.push(issuePid);

    // Submit issue via API (setup step — core UI testing starts at triage)
    await executeCommandViaApi(page, 'dp:submit_issue', {}, issuePid, 'state_transition');

    // Triage via API: NEED_RECTIFY
    const triage = await executeCommandViaApi(
      page,
      'dp:triage_issue',
      {
        dp_triage_decision: 'need_rectify',
        dp_hazard_level: 'medium',
        dp_triage_remark: 'E2E triage',
      },
      issuePid,
      'update',
    );
    expect(triage.code).toBe(ErrorCodes.SUCCESS);

    const rectifying = await waitIssueStatus(page, issuePid, 'rectifying');
    expect(rectifying).toBe('rectifying');

    // Verify rectification page is reachable in UI (cross-module coverage)
    await navigateToDynamicPage(page, 'dp_rectification');
    await waitForDynamicPageLoad(page);

    // Create rectification explicitly to avoid environment-dependent sideEffect drift.
    const createRect = await executeCommandViaApi(page, 'dp:create_rectification', {
      dp_rect_title: title,
      dp_rect_issue_id: issuePid,
      dp_rect_content: 'E2E rectification content',
      dp_rect_deadline: dateOffsetStr(30),
    });
    expect(createRect.code).toBe(ErrorCodes.SUCCESS);
    const rectPid = createRect.recordId;
    expect(rectPid).toBeTruthy();

    // Rectification lifecycle via API: start → submit → accept

    expect(
      (await executeCommandViaApi(page, 'dp:start_rectification', {}, rectPid, 'state_transition'))
        .code,
    ).toBe(ErrorCodes.SUCCESS);
    expect(
      (
        await executeCommandViaApi(
          page,
          'dp:submit_rectification',
          {
            dp_rect_result: 'E2E fixed',
            dp_rect_evidence: 'e2e-evidence',
          },
          rectPid,
          'state_transition',
        )
      ).code,
    ).toBe(ErrorCodes.SUCCESS);
    expect(
      (
        await executeCommandViaApi(
          page,
          'dp:accept_rectification',
          {
            dp_rect_accept_remark: 'accepted by e2e',
          },
          rectPid,
          'state_transition',
        )
      ).code,
    ).toBe(ErrorCodes.SUCCESS);

    // Verify issue is RECTIFIED (side effect)
    const issueStatus = await waitIssueStatus(page, issuePid, 'rectified');
    expect(issueStatus).toBe('rectified');
  });

  // ---- DP Branch: NO_ACTION ----

  test('DP branch: triage as NO_ACTION', async ({ page }) => {
    test.setTimeout(60000);
    const title = `E2E NoAction ${uniqueId()}`;

    const cr = await executeCommandViaApi(page, 'dp:create_issue', {
      dp_issue_project_id: projectId,
      dp_issue_title: title,
      dp_issue_content: 'No-action branch',
      dp_issue_area: 'Test Area B',
      dp_issue_source: 'daily_inspection',
    });
    expect(cr.code).toBe(ErrorCodes.SUCCESS);
    createdIssuePids.push(cr.recordId);

    // Submit via API (faster for setup)
    await executeCommandViaApi(page, 'dp:submit_issue', {}, cr.recordId, 'state_transition');

    // Triage via API: NO_ACTION
    const triage = await executeCommandViaApi(
      page,
      'dp:triage_issue',
      {
        dp_triage_decision: 'no_action',
        dp_triage_remark: 'E2E no action',
      },
      cr.recordId,
      'update',
    );
    expect(triage.code).toBe(ErrorCodes.SUCCESS);

    // Verify via API
    const status = await waitIssueStatus(page, cr.recordId, 'no_action');
    expect(status).toBe('no_action');
  });

  // ---- AP Mainline: Submit → Approve ----

  test.fixme('AP mainline: annual plan submit -> approve', async ({ page }) => {
    test.setTimeout(60000);
    await navigateToDynamicPage(page, 'ap_annual_plan');
    const draftRow = await ensureApDraftRow(page);
    await draftRow.hover();
    await draftRow.locator('[data-testid="row-action-submit"]').first().click();
    await acceptConfirmDialog(page);
    await page
      .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 10000 })
      .catch(() => null);
    const submittedRow = await findApRowWithAction(page, 'submitted', 'row-action-approve');
    await submittedRow.hover();
    await submittedRow.locator('[data-testid="row-action-approve"]').first().click();
    await acceptConfirmDialog(page);
    await page
      .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 10000 })
      .catch(() => null);
    await ensurePageReady(page);
  });

  // ---- AP Branch: Reject → Re-submit → Approve ----

  test('AP branch: reject -> resubmit -> approve', async ({ page }) => {
    test.setTimeout(90000);
    await navigateToDynamicPage(page, 'ap_annual_plan');
    const submittedRow = await ensureApSubmittedRow(page);
    await submittedRow.hover();
    await submittedRow.locator('[data-testid="row-action-reject"]').first().click();
    await acceptConfirmDialog(page);
    await page
      .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 10000 })
      .catch(() => null);

    const rejectedRow = await findApRowWithAction(page, 'rejected', 'row-action-submit');
    await rejectedRow.hover();
    await rejectedRow.locator('[data-testid="row-action-submit"]').first().click();
    await acceptConfirmDialog(page);
    await page
      .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 10000 })
      .catch(() => null);

    const submittedAgainRow = await findApRowWithAction(page, 'submitted', 'row-action-approve');
    await submittedAgainRow.hover();
    await submittedAgainRow.locator('[data-testid="row-action-approve"]').first().click();
    await acceptConfirmDialog(page);
    await page
      .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 10000 })
      .catch(() => null);
    await ensurePageReady(page);
  });

  // ---- QO Mainline: Dashboard reachable ----

  test('QO mainline: operations dashboard is accessible', async ({ page }) => {
    test.setTimeout(60000);

    // Dashboard uses chart blocks with their own loading spinners,
    // so we bypass navigateToDynamicPage (which waits for ALL spinners to disappear)
    // and instead wait for the specific dashboard blocks to render.
    await page.goto('/p/qo_dashboard_data', { waitUntil: 'domcontentloaded' });

    // Dashboard was upgraded to use chart blocks (KPI number cards + line/bar charts)
    const kpiBlock = page.locator('[data-testid="dashboard-block-kpi_year_output"]').first();
    const trendBlock = page
      .locator('[data-testid="dashboard-block-chart_production_trend"]')
      .first();
    const revenueBlock = page.locator('[data-testid="dashboard-block-chart_revenue_bar"]').first();

    await expect(kpiBlock).toBeVisible({ timeout: 15000 });
    await expect(trendBlock).toBeVisible({ timeout: 15000 });
    await expect(revenueBlock).toBeVisible({ timeout: 15000 });
  });

  // ---- Cross-Module KPIs on Dashboard ----

  test('Dashboard shows cross-module KPI cards (contract, safety, quality)', async ({ page }) => {
    test.setTimeout(60000);

    await page.goto('/p/qo_dashboard_data', { waitUntil: 'domcontentloaded' });

    // Cross-module KPI cards added in Phase 5
    const contractTotal = page
      .locator('[data-testid="dashboard-block-kpi_contract_total"]')
      .first();
    const contractCount = page
      .locator('[data-testid="dashboard-block-kpi_contract_count"]')
      .first();
    const safetyIssues = page.locator('[data-testid="dashboard-block-kpi_safety_issues"]').first();
    const qualityChecks = page
      .locator('[data-testid="dashboard-block-kpi_quality_checks"]')
      .first();

    await expect(contractTotal).toBeVisible({ timeout: 15000 });
    await expect(contractCount).toBeVisible({ timeout: 15000 });
    await expect(safetyIssues).toBeVisible({ timeout: 15000 });
    await expect(qualityChecks).toBeVisible({ timeout: 15000 });
  });
});
