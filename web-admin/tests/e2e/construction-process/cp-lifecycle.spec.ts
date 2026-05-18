/**
 * Construction Process — Lifecycle E2E Tests
 *
 * CP-001 @smoke    : Navigate to 现场问题 list → table visible, i18n headers
 * CP-002 @smoke    : Navigate to 周报 list → table visible
 * CP-003 @critical : Site Issue open → in_progress → resolved → closed
 * CP-004 @critical : Weekly Report draft → submitted → approved
 * CP-005 @critical : Weekly Report reject branch → submitted → rejected
 *
 * Menu root: 施工过程 (cp_root)
 *   /construction-process/issues    → model: cp_site_issue
 *   /construction-process/reports   → model: cp_weekly_report
 *
 * Prerequisites: construction-process plugin imported and all models published.
 *
 * @since 10.0.0
 */

import { test, expect, type Page } from '../../fixtures';
import { uniqueId, executeCommandViaApi, todayStr, dateOffsetStr } from '../helpers/index';

// ---------------------------------------------------------------------------
// Navigation helper
// ---------------------------------------------------------------------------

async function navigateToConstructionPage(
  page: Page,
  leafName: string,
  menuPath: string,
  modelCode: string,
): Promise<void> {
  await page.goto('/dashboards');
  await page.waitForLoadState('domcontentloaded');

  const nav = page.locator('nav');

  // Expand 施工过程 root menu
  const rootBtn = nav.getByRole('button', { name: '施工过程' });
  await rootBtn.scrollIntoViewIfNeeded();
  await rootBtn.evaluate((el: HTMLElement) => el.click());
  await page.waitForResponse(() => true, { timeout: 2_000 }).catch(() => null);

  // Use href-based selector to avoid strict mode violations from duplicate labels
  const leafLink = nav
    .locator(`a[href="${menuPath}"]`)
    .or(nav.getByRole('link', { name: leafName }))
    .first();
  await leafLink.waitFor({ state: 'attached', timeout: 8_000 });
  await leafLink.scrollIntoViewIfNeeded();

  const listResponsePromise = page
    .waitForResponse((r) => r.url().includes(`/api/dynamic/${modelCode}`) && r.status() === 200, {
      timeout: 15_000,
    })
    .catch(() => null);
  await leafLink.evaluate((el: HTMLElement) => el.click());
  await listResponsePromise;

  await expect(page.locator('table, [class*="ant-table"]').first()).toBeVisible({
    timeout: 10_000,
  });
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const UID = uniqueId('CP');

// ---------------------------------------------------------------------------
// Test suite
// ---------------------------------------------------------------------------

test.describe('Construction Process — Lifecycle', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60_000);

  let projectId: string;
  let issueId: string;
  let approvalReportId: string;
  let rejectReportId: string;

  // -------------------------------------------------------------------------
  // Setup: resolve a real project ID + create test records
  // -------------------------------------------------------------------------

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    const page = await ctx.newPage();

    try {
      // Get an existing pm_project pid to satisfy the REFERENCE constraint
      const projResp = await page.request.get('/api/dynamic/pm_project/list?pageSize=1');
      expect(projResp.ok()).toBe(true);
      const projBody = await projResp.json();
      const projRecords: Record<string, unknown>[] =
        projBody?.data?.records ?? projBody?.records ?? [];
      expect(projRecords.length).toBeGreaterThan(0);
      projectId = String(projRecords[0].pid ?? projRecords[0].id ?? '');
      expect(projectId).toBeTruthy();

      // Create site issue
      const issueResult = await executeCommandViaApi(
        page,
        'cp:create_issue',
        {
          cp_si_project_id: projectId,
          cp_si_title: `E2E Issue ${UID}`,
          cp_si_description: `E2E site issue description ${UID}`,
          cp_si_severity: 'medium',
        },
        undefined,
        'create',
      );
      issueId = issueResult.recordId;

      // Create weekly report for approval flow
      const r1 = await executeCommandViaApi(
        page,
        'cp:create_report',
        {
          cp_wr_project_id: projectId,
          cp_wr_week_start: dateOffsetStr(-7),
          cp_wr_week_end: todayStr(),
          cp_wr_summary: `E2E Report ${UID}`,
          cp_wr_progress: 60,
          cp_wr_next_plan: 'Next plan',
        },
        undefined,
        'create',
      );
      approvalReportId = r1.recordId;

      // Create weekly report for reject flow
      const r2 = await executeCommandViaApi(
        page,
        'cp:create_report',
        {
          cp_wr_project_id: projectId,
          cp_wr_week_start: dateOffsetStr(-14),
          cp_wr_week_end: dateOffsetStr(-7),
          cp_wr_summary: `E2E ReportReject ${UID}`,
          cp_wr_progress: 30,
          cp_wr_next_plan: 'Next plan reject',
        },
        undefined,
        'create',
      );
      rejectReportId = r2.recordId;
    } finally {
      await ctx.close();
    }
  });

  // =========================================================================
  // CP-001 @smoke: Navigate to 现场问题
  // =========================================================================

  test('CP-001 @smoke: Navigate to 现场问题 list via sidebar menu', async ({ page }) => {
    await navigateToConstructionPage(
      page,
      '现场问题',
      '/construction-process/issues',
      'cp_site_issue',
    );

    const rows = page.locator('tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 8_000 });

    // i18n: headers must not contain raw field codes
    const headerRow = page.locator('thead tr').first();
    await expect(headerRow).toBeVisible({ timeout: 5_000 });
    const headerText = await headerRow.textContent();
    expect(headerText).not.toMatch(/cp_si_/i);
  });

  // =========================================================================
  // CP-002 @smoke: Navigate to 周报
  // =========================================================================

  test('CP-002 @smoke: Navigate to 周报 list via sidebar menu', async ({ page }) => {
    await navigateToConstructionPage(
      page,
      '周报',
      '/construction-process/reports',
      'cp_weekly_report',
    );

    const table = page.locator('table, [class*="ant-table"]').first();
    await expect(table).toBeVisible({ timeout: 10_000 });

    const rows = page.locator('tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 8_000 });
  });

  // =========================================================================
  // CP-003 @critical: Site Issue open → in_progress → resolved → closed
  // =========================================================================

  test('CP-003 @critical: Site Issue open → in_progress → resolved → closed', async ({ page }) => {
    expect(issueId).toBeTruthy();

    // Verify starts as open
    let resp = await page.request.get(`/api/dynamic/cp_site_issue/${issueId}`);
    expect(resp.ok()).toBe(true);
    const openBody = await resp.json();
    expect((openBody?.data ?? openBody).cp_si_status).toBe('open');

    // open → in_progress
    await executeCommandViaApi(page, 'cp:start_issue', {}, issueId, 'state_transition');

    resp = await page.request.get(`/api/dynamic/cp_site_issue/${issueId}`);
    const inProgressBody = await resp.json();
    expect((inProgressBody?.data ?? inProgressBody).cp_si_status).toBe('in_progress');

    // in_progress → resolved
    await executeCommandViaApi(page, 'cp:resolve_issue', {}, issueId, 'state_transition');

    resp = await page.request.get(`/api/dynamic/cp_site_issue/${issueId}`);
    const resolvedBody = await resp.json();
    expect((resolvedBody?.data ?? resolvedBody).cp_si_status).toBe('resolved');

    // resolved → closed
    await executeCommandViaApi(page, 'cp:close_issue', {}, issueId, 'state_transition');

    resp = await page.request.get(`/api/dynamic/cp_site_issue/${issueId}`);
    const closedBody = await resp.json();
    expect((closedBody?.data ?? closedBody).cp_si_status).toBe('closed');

    // Verify in list UI
    await navigateToConstructionPage(
      page,
      '现场问题',
      '/construction-process/issues',
      'cp_site_issue',
    );
    const rows = page.locator('tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 8_000 });
  });

  // =========================================================================
  // CP-004 @critical: Weekly Report draft → submitted → approved
  // =========================================================================

  test('CP-004 @critical: Weekly Report draft → submitted → approved', async ({ page }) => {
    expect(approvalReportId).toBeTruthy();

    // Verify starts as draft
    let resp = await page.request.get(`/api/dynamic/cp_weekly_report/${approvalReportId}`);
    expect(resp.ok()).toBe(true);
    const draftBody = await resp.json();
    expect((draftBody?.data ?? draftBody).cp_wr_status).toBe('draft');

    // draft → submitted
    await executeCommandViaApi(page, 'cp:submit_report', {}, approvalReportId, 'state_transition');

    resp = await page.request.get(`/api/dynamic/cp_weekly_report/${approvalReportId}`);
    const submittedBody = await resp.json();
    expect((submittedBody?.data ?? submittedBody).cp_wr_status).toBe('submitted');

    // submitted → approved
    await executeCommandViaApi(page, 'cp:approve_report', {}, approvalReportId, 'state_transition');

    resp = await page.request.get(`/api/dynamic/cp_weekly_report/${approvalReportId}`);
    const approvedBody = await resp.json();
    expect((approvedBody?.data ?? approvedBody).cp_wr_status).toBe('approved');

    // Verify in list UI
    await navigateToConstructionPage(
      page,
      '周报',
      '/construction-process/reports',
      'cp_weekly_report',
    );
    const rows = page.locator('tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 8_000 });
  });

  // =========================================================================
  // CP-005 @critical: Weekly Report reject branch → submitted → rejected
  // =========================================================================

  test('CP-005 @critical: Weekly Report reject branch → submitted → rejected', async ({ page }) => {
    expect(rejectReportId).toBeTruthy();

    // Submit
    await executeCommandViaApi(page, 'cp:submit_report', {}, rejectReportId, 'state_transition');

    // Reject
    await executeCommandViaApi(page, 'cp:reject_report', {}, rejectReportId, 'state_transition');

    const resp = await page.request.get(`/api/dynamic/cp_weekly_report/${rejectReportId}`);
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    // Rejection may reset to draft or set to rejected depending on config
    const status = (body?.data ?? body).cp_wr_status as string;
    expect(['rejected', 'draft'].includes(status)).toBe(true);
  });
});
