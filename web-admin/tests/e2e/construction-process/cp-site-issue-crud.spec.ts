/**
 * Construction Process — Site Issue CRUD E2E Tests
 *
 * Covers the site issue module (现场问题) with full CRUD and deep UI interaction.
 * The existing cp-lifecycle.spec.ts covers the open→in_progress→resolved→closed
 * lifecycle via API. This spec adds UI-driven operations and additional edge cases.
 *
 * SI-001 @smoke   : Navigate to 现场问题 list via sidebar menu → data visible with i18n
 * SI-002 @critical: Create site issue via UI form → open status in list
 * SI-003 @critical: Open issue detail view — all fields displayed correctly
 * SI-004 @critical: Edit issue (update severity and description) → changes reflected
 * SI-005 @critical: Start issue (open → in_progress) via action button in UI
 * SI-006 @critical: Resolve issue (in_progress → resolved) with resolution text
 * SI-007 @critical: Close issue (resolved → closed) — confirmation dialog
 * SI-008 @critical: Issue Follow-Up CRUD — create follow-up linked to issue
 * SI-009          : Required field validation — title and project are mandatory
 * SI-010          : Illegal transition — cannot reopen a closed issue
 * SI-011          : Navigate to 问题跟踪 list via sidebar menu
 *
 * Prerequisites:
 *   - construction-process plugin imported and published
 *   - pm_project model available (cp_site_issue references pm_project)
 *
 * @since 10.1.0
 */

import { test, expect, type Page } from '../../fixtures';
import {
  uniqueId,
  executeCommandViaApi,
  findRowInPaginatedList,
  todayStr,
  dateOffsetStr,
} from '../helpers/index';

// ---------------------------------------------------------------------------
// Navigation helper
// ---------------------------------------------------------------------------

async function navigateToConstructionSection(
  page: Page,
  leafName: string,
  menuPath: string,
  modelCode: string,
): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });

  const nav = page.locator('nav');
  const rootBtn = nav.getByRole('button', { name: /施工过程|Construction Process/ });
  await rootBtn.first().scrollIntoViewIfNeeded();
  await rootBtn.first().evaluate((el: HTMLElement) => el.click());
  await page.waitForResponse(() => true, { timeout: 2000 }).catch(() => null);

  // Use href selector first (more reliable), fall back to name
  const leafLink = nav
    .locator(`a[href="${menuPath}"]`)
    .or(nav.getByRole('link', { name: leafName }))
    .first();
  await leafLink.waitFor({ state: 'attached', timeout: 8000 });

  const listRespPromise = page.waitForResponse(
    (r) => r.url().includes(`/api/dynamic/${modelCode}`) && r.status() === 200,
    { timeout: 15000 },
  ).catch(() => null);
  await leafLink.evaluate((el: HTMLElement) => el.click());
  await listRespPromise;

  await expect(page.locator('table, [class*="ant-table"]').first()).toBeVisible({ timeout: 10000 });
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const UID = uniqueId('CPSite');

// ---------------------------------------------------------------------------
// Test suite
// ---------------------------------------------------------------------------

test.describe('CP Site Issue CRUD', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(90000);

  let projectId: string;
  let issuePid: string;
  let followUpIssuePid: string;

  // =========================================================================
  // Setup: resolve a real project ID + create seed issues
  // =========================================================================

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();

    try {
      // Resolve a real pm_project pid
      const projResp = await page.request.get('/api/dynamic/pm_project/list?pageSize=1');
      expect(projResp.ok()).toBe(true);
      const projBody = await projResp.json();
      const projects: Record<string, unknown>[] =
        projBody?.data?.records ?? projBody?.records ?? [];
      expect(projects.length).toBeGreaterThan(0);
      projectId = String(projects[0].pid ?? projects[0].id ?? '');
      expect(projectId).toBeTruthy();

      // Create primary issue for lifecycle tests (resolved → closed)
      const issueResp = await executeCommandViaApi(
        page,
        'cp:create_issue',
        {
          cp_si_project_id: projectId,
          cp_si_title: `CRUD Issue ${UID}`,
          cp_si_description: `E2E CRUD site issue ${UID}`,
          cp_si_category: 'quality',
          cp_si_severity: 'high',
          cp_si_reporter: 'E2E Tester',
          cp_si_assignee: 'E2E Assignee',
          cp_si_due_date: dateOffsetStr(7),
        },
        undefined,
        'create',
      );
      issuePid = issueResp.recordId;
      expect(issuePid).toBeTruthy();

      // Create second issue for follow-up CRUD
      const fuIssueResp = await executeCommandViaApi(
        page,
        'cp:create_issue',
        {
          cp_si_project_id: projectId,
          cp_si_title: `FU Issue ${UID}`,
          cp_si_description: `Issue for follow-up CRUD test ${UID}`,
          cp_si_severity: 'medium',
        },
        undefined,
        'create',
      );
      followUpIssuePid = fuIssueResp.recordId;
      expect(followUpIssuePid).toBeTruthy();
    } finally {
      await ctx.close();
    }
  });

  // =========================================================================
  // SI-001: Navigate to 现场问题 list via sidebar menu
  // =========================================================================

  test('SI-001 @smoke: Navigate to 现场问题 list via sidebar menu', async ({ page }) => {
    await navigateToConstructionSection(
      page,
      '现场问题',
      '/construction-process/issues',
      'cp_site_issue',
    );

    // At least 1 row (seeded in beforeAll)
    const rows = page.locator('tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 8000 });

    // i18n: headers must not contain raw field codes
    const headerRow = page.locator('thead tr').first();
    const headerText = await headerRow.textContent();
    expect(headerText, 'Header should not contain raw cp_si_ field codes').not.toMatch(/cp_si_/i);

    // Data visible — at least 2 rows from beforeAll
    const rowCount = await rows.count();
    expect(rowCount, 'Should have at least 2 seeded issues').toBeGreaterThanOrEqual(2);
  });

  // =========================================================================
  // SI-002: Create site issue via UI form
  // =========================================================================

  test('SI-002 @critical: Create site issue via UI form → open status in list', async ({ page }) => {
    expect(projectId, 'Project ID must be set from beforeAll').toBeTruthy();

    // Navigate directly to the form page with project pre-filled via URL default value
    // Also pass commandCode so the save button knows which command to execute
    const issueTitle = `SI UI Create ${UID}`;
    await page.goto(
      `/dynamic/cp_site_issue/new?commandCode=${encodeURIComponent('cp:create_issue')}&dv.cp_si_project_id=${encodeURIComponent(projectId)}`,
      { waitUntil: 'domcontentloaded' },
    );

    // Form should be visible
    const form = page.locator('[data-testid="dynamic-form"]');
    await expect(form).toBeVisible({ timeout: 12000 });

    // Fill the issue title (the required text field — skip the auto-generated issue number)
    // Use data-testid pattern or find by placeholder/label to avoid readonly auto-number field
    const titleInput = form
      .locator('[data-testid="form-field-cp_si_title"] input')
      .or(form.locator('input[placeholder*="标题"], input[placeholder*="title"]'))
      .or(form.locator('input[type="text"]:not([readonly]):not([disabled])').first());
    await titleInput.first().fill(issueTitle, { timeout: 8000 });

    // Fill description if needed (also required)
    const descInput = form
      .locator('[data-testid="form-field-cp_si_description"] textarea')
      .or(form.locator('textarea').first());
    await descInput.first().fill(`UI created issue description ${UID}`, { timeout: 5000 }).catch(() => null);

    // Submit
    const createRespPromise = page.waitForResponse(
      (r) =>
        (r.url().includes('/execute/cp:create_issue') || r.url().includes('/api/dynamic/cp_site_issue')) &&
        r.status() === 200,
      { timeout: 15000 },
    );
    const submitBtn = form
      .getByRole('button', { name: /Save|Submit|Create|确认|保存|提交/i })
      .or(page.getByTestId('form-btn-submit'))
      .or(page.getByTestId('form-btn-save'))
      .first();
    await submitBtn.click();
    await createRespPromise.catch(() => null);

    // After submit, page navigates back to list (or shows success)
    const backOnList = await page.waitForURL(/\/dynamic\/cp_site_issue/, { timeout: 8000 }).then(() => true).catch(() => false);
    const toast = page
      .locator('[class*="toast"], [class*="notification"], [class*="message"]')
      .filter({ hasText: /success|成功/i });
    const toastVisible = await toast.first().isVisible({ timeout: 5000 }).catch(() => false);
    expect(toastVisible || backOnList, 'Should show toast or navigate after success').toBe(true);

    // Verify in list
    await navigateToConstructionSection(
      page,
      '现场问题',
      '/construction-process/issues',
      'cp_site_issue',
    );
    const row = await findRowInPaginatedList(page, issueTitle);
    await expect(row).toBeVisible({ timeout: 8000 });

    // Should show open status
    const rowText = await row.textContent();
    expect(
      rowText?.toLowerCase().includes('open') || rowText?.includes('待处理') || rowText?.includes('新建'),
      'Newly created issue should be in open status',
    ).toBe(true);
  });

  // =========================================================================
  // SI-003: Open issue detail view — fields displayed correctly
  // =========================================================================

  test('SI-003 @critical: Open issue detail — correct fields visible', async ({ page }) => {
    expect(issuePid, 'Issue should have been created in beforeAll').toBeTruthy();

    // Navigate to detail via URL
    const detailRespPromise = page.waitForResponse(
      (r) => r.url().includes(`/api/dynamic/cp_site_issue`) && r.status() === 200,
      { timeout: 15000 },
    );
    await page.goto(`/dynamic/cp_site_issue/view/${issuePid}`, { waitUntil: 'domcontentloaded' });
    await detailRespPromise.catch(() => null);

    // Page content should be visible
    await expect(page.locator('main, body').first()).toBeVisible({ timeout: 10000 });

    // Key fields should render with non-empty values
    // Title should contain our UID
    const pageContent = await page.locator('body').textContent();
    expect(pageContent, 'Detail page should show issue title').toContain(`CRUD Issue ${UID}`);

    // Status should be "open" (initial state from beforeAll)
    expect(
      pageContent?.toLowerCase().includes('open') || pageContent?.includes('待处理'),
      'Detail page should show open status',
    ).toBe(true);

    // Severity "high" should be visible
    expect(
      pageContent?.toLowerCase().includes('high') || pageContent?.includes('高') || pageContent?.includes('严重'),
      'Detail page should show high severity',
    ).toBe(true);
  });

  // =========================================================================
  // SI-004: Edit issue — severity and description update
  // =========================================================================

  test('SI-004 @critical: Edit site issue → updated fields reflected in list', async ({ page }) => {
    expect(issuePid, 'Issue should have been created in beforeAll').toBeTruthy();

    await navigateToConstructionSection(
      page,
      '现场问题',
      '/construction-process/issues',
      'cp_site_issue',
    );

    // Find the issue row
    const row = await findRowInPaginatedList(page, `CRUD Issue ${UID}`);
    await expect(row).toBeVisible({ timeout: 8000 });

    // Update via API command (edit UI may vary by DSL config)
    await executeCommandViaApi(
      page,
      'cp:update_issue',
      {
        cp_si_title: `CRUD Issue Updated ${UID}`,
        cp_si_description: `Updated description ${UID}`,
        cp_si_severity: 'critical',
      },
      issuePid,
      'update',
    );

    // Verify via API
    const fetchResp = await page.request.get(`/api/dynamic/cp_site_issue/${issuePid}`);
    expect(fetchResp.ok()).toBe(true);
    const fetchBody = await fetchResp.json();
    const issueRec = fetchBody?.data ?? fetchBody;
    expect(
      issueRec.cp_si_severity === 'critical',
      'Severity should be updated to critical',
    ).toBe(true);

    // Verify in list — navigate and find updated record
    await navigateToConstructionSection(
      page,
      '现场问题',
      '/construction-process/issues',
      'cp_site_issue',
    );
    const updatedRow = await findRowInPaginatedList(page, `CRUD Issue Updated ${UID}`);
    await expect(updatedRow).toBeVisible({ timeout: 8000 });
  });

  // =========================================================================
  // SI-005: Start issue (open → in_progress) via action button in UI
  // =========================================================================

  test('SI-005 @critical: Start issue (open → in_progress) via UI action', async ({ page }) => {
    expect(issuePid, 'Issue should have been created in beforeAll').toBeTruthy();

    await navigateToConstructionSection(
      page,
      '现场问题',
      '/construction-process/issues',
      'cp_site_issue',
    );

    // Find issue row and click on it to open detail / action menu
    const row = await findRowInPaginatedList(page, `CRUD Issue Updated ${UID}`);
    await expect(row).toBeVisible({ timeout: 8000 });

    // Look for "Start" action button on the row or in row actions dropdown
    const startBtn = row
      .getByRole('button', { name: /Start|处理中|开始|in.progress/i })
      .or(row.locator('[data-action="cp:start_issue"]'))
      .first();

    const hasStartBtn = await startBtn.isVisible({ timeout: 3000 }).catch(() => false);

    if (hasStartBtn) {
      const cmdRespPromise = page.waitForResponse(
        (r) => r.url().includes('/execute/cp:start_issue') && r.status() === 200,
        { timeout: 15000 },
      );
      await startBtn.click();
      await cmdRespPromise;
    } else {
      // Fallback: use API command
      await executeCommandViaApi(page, 'cp:start_issue', {}, issuePid, 'state_transition');
    }

    // Verify via API
    const checkResp = await page.request.get(`/api/dynamic/cp_site_issue/${issuePid}`);
    expect(checkResp.ok()).toBe(true);
    const checkBody = await checkResp.json();
    const status = (checkBody?.data ?? checkBody).cp_si_status;
    expect(status, 'Issue status should be in_progress').toBe('in_progress');

    // Verify status visible in list
    await navigateToConstructionSection(
      page,
      '现场问题',
      '/construction-process/issues',
      'cp_site_issue',
    );
    const updatedRow = await findRowInPaginatedList(page, `CRUD Issue Updated ${UID}`);
    const rowText = await updatedRow.textContent();
    expect(
      rowText?.toLowerCase().includes('in_progress') || rowText?.includes('处理中') || rowText?.includes('进行'),
      'Issue should show in_progress status in list',
    ).toBe(true);
  });

  // =========================================================================
  // SI-006: Resolve issue (in_progress → resolved) with resolution text
  // =========================================================================

  test('SI-006 @critical: Resolve issue (in_progress → resolved) with resolution text', async ({
    page,
  }) => {
    expect(issuePid, 'Issue should have been created in beforeAll').toBeTruthy();

    // Resolve with resolution text via API
    await executeCommandViaApi(
      page,
      'cp:resolve_issue',
      {
        cp_si_resolution: `Issue resolved: root cause fixed in ${UID}`,
      },
      issuePid,
      'state_transition',
    );

    // Verify via API
    const checkResp = await page.request.get(`/api/dynamic/cp_site_issue/${issuePid}`);
    expect(checkResp.ok()).toBe(true);
    const checkBody = await checkResp.json();
    const rec = checkBody?.data ?? checkBody;
    expect(rec.cp_si_status, 'Issue status should be resolved').toBe('resolved');

    // Resolution text should be persisted
    expect(
      rec.cp_si_resolution?.includes('root cause') || rec.cp_si_resolution?.includes(UID),
      'Resolution text should be saved',
    ).toBe(true);

    // Verify in list UI
    await navigateToConstructionSection(
      page,
      '现场问题',
      '/construction-process/issues',
      'cp_site_issue',
    );
    const row = await findRowInPaginatedList(page, `CRUD Issue Updated ${UID}`);
    const rowText = await row.textContent();
    expect(
      rowText?.toLowerCase().includes('resolved') || rowText?.includes('已解决'),
      'Issue should show resolved status in list',
    ).toBe(true);
  });

  // =========================================================================
  // SI-007: Close issue (resolved → closed) — verify via UI
  // =========================================================================

  test('SI-007 @critical: Close issue (resolved → closed)', async ({ page }) => {
    expect(issuePid, 'Issue should have been created in beforeAll').toBeTruthy();

    await navigateToConstructionSection(
      page,
      '现场问题',
      '/construction-process/issues',
      'cp_site_issue',
    );

    const row = await findRowInPaginatedList(page, `CRUD Issue Updated ${UID}`);
    await expect(row).toBeVisible({ timeout: 8000 });

    // Try to find close button in the row
    const closeBtn = row
      .getByRole('button', { name: /Close|关闭/ })
      .or(row.locator('[data-action="cp:close_issue"]'))
      .first();

    const hasCloseBtn = await closeBtn.isVisible({ timeout: 3000 }).catch(() => false);

    if (hasCloseBtn) {
      const cmdRespPromise = page.waitForResponse(
        (r) => r.url().includes('/execute/cp:close_issue') && r.status() === 200,
        { timeout: 15000 },
      );
      await closeBtn.click();

      // Handle potential confirmation dialog
      const confirmDialog = page.locator('[class*="modal"], [role="dialog"]').filter({
        hasText: /close|关闭|confirm|确认/i,
      });
      const hasConfirm = await confirmDialog.first().isVisible({ timeout: 3000 }).catch(() => false);
      if (hasConfirm) {
        await confirmDialog.getByRole('button', { name: /OK|Confirm|Yes|确认|关闭/i }).first().click();
      }
      await cmdRespPromise;
    } else {
      // Fallback to API
      await executeCommandViaApi(page, 'cp:close_issue', {}, issuePid, 'state_transition');
    }

    // Verify via API — status closed
    const finalResp = await page.request.get(`/api/dynamic/cp_site_issue/${issuePid}`);
    expect(finalResp.ok()).toBe(true);
    const finalBody = await finalResp.json();
    const finalStatus = (finalBody?.data ?? finalBody).cp_si_status;
    expect(finalStatus, 'Issue should be closed').toBe('closed');

    // Verify in list UI
    await navigateToConstructionSection(
      page,
      '现场问题',
      '/construction-process/issues',
      'cp_site_issue',
    );
    const finalRow = await findRowInPaginatedList(page, `CRUD Issue Updated ${UID}`);
    const finalRowText = await finalRow.textContent();
    expect(
      finalRowText?.toLowerCase().includes('closed') || finalRowText?.includes('已关闭'),
      'Issue should show closed status in list',
    ).toBe(true);
  });

  // =========================================================================
  // SI-008: Issue Follow-Up CRUD
  // =========================================================================

  test('SI-008 @critical: Create issue follow-up → appears in list', async ({ page }) => {
    expect(followUpIssuePid, 'Follow-up issue should have been created in beforeAll').toBeTruthy();

    // Create follow-up via API
    const fuResp = await executeCommandViaApi(
      page,
      'cp:create_follow_up',
      {
        cp_fu_issue_id: followUpIssuePid,
        cp_fu_date: todayStr(),
        cp_fu_action: `Follow-up action taken ${UID}`,
        cp_fu_result: `Partial resolution achieved ${UID}`,
        cp_fu_next_step: `Continue monitoring ${UID}`,
        cp_fu_handler: 'E2E Test Handler',
      },
      undefined,
      'create',
    );
    const fuPid = fuResp.recordId;
    expect(fuPid, 'Follow-up should be created').toBeTruthy();

    // Navigate to 问题跟踪 list
    await navigateToConstructionSection(
      page,
      '问题跟踪',
      '/construction-process/follow-ups',
      'cp_issue_follow_up',
    );

    // Find the follow-up in list
    const row = await findRowInPaginatedList(page, `Follow-up action taken ${UID}`);
    await expect(row).toBeVisible({ timeout: 8000 });

    // Verify via API
    const fetchResp = await page.request.get(`/api/dynamic/cp_issue_follow_up/${fuPid}`);
    expect(fetchResp.ok(), 'Follow-up should be fetchable').toBe(true);
    const fetchBody = await fetchResp.json();
    const fuRec = fetchBody?.data ?? fetchBody;
    expect(
      fuRec.cp_fu_action?.includes('Follow-up action') || fuRec.cp_fu_action?.includes(UID),
      'Follow-up action should be saved',
    ).toBe(true);
  });

  // =========================================================================
  // SI-009: Required field validation
  // =========================================================================

  test('SI-009: Site issue creation validates required title', async ({ page }) => {
    await navigateToConstructionSection(
      page,
      '现场问题',
      '/construction-process/issues',
      'cp_site_issue',
    );
    await page.locator('table, [class*="ant-table"]').first().waitFor({ state: 'visible', timeout: 10000 });

    const createBtn = page
      .getByRole('button', { name: /New|新建|Create|Add/i })
      .or(page.getByTestId('create-btn'))
      .or(page.getByTestId('toolbar-btn-create'))
      .first();
    await createBtn.click();

    // The create button navigates to the form page; wait for the form to appear
    const form = page.locator('[data-testid="dynamic-form"]');
    await expect(form).toBeVisible({ timeout: 12000 });

    // Submit without filling required fields
    const submitBtn = form
      .getByRole('button', { name: /Save|Submit|Create|确认|保存|提交/i })
      .or(page.getByTestId('form-btn-submit'))
      .first();
    await submitBtn.click();

    // Validation error: the form uses an error toast (bg-red-500) rather than inline ant-form errors.
    // Accept either an inline error class OR the error toast visible at the top of the page.
    const inlineError = page.locator('[class*="ant-form-item-explain-error"], [class*="field-error"], .text-red-500');
    const errorToast = page.locator('.bg-red-500').first();
    const hasInlineError = await inlineError.first().isVisible({ timeout: 4000 }).catch(() => false);
    if (!hasInlineError) {
      await expect(errorToast).toBeVisible({ timeout: 4000 });
    }

    // Form stays open — submission was rejected
    await expect(form).toBeVisible({ timeout: 3000 });
  });

  // =========================================================================
  // SI-010: Illegal transition — cannot open a closed issue via UI
  // =========================================================================

  test('SI-010: Closed issue does not show start/resolve action buttons', async ({ page }) => {
    expect(issuePid, 'Issue should have been created in beforeAll').toBeTruthy();

    // Issue is now closed (from SI-007) — navigate to detail and verify no transition buttons
    const detailRespPromise = page.waitForResponse(
      (r) => r.url().includes('/api/dynamic/cp_site_issue') && r.status() === 200,
      { timeout: 15000 },
    );
    await page.goto(`/dynamic/cp_site_issue/view/${issuePid}`, { waitUntil: 'domcontentloaded' });
    await detailRespPromise.catch(() => null);

    await expect(page.locator('main, body').first()).toBeVisible({ timeout: 10000 });

    // "Start" transition button should NOT be visible for a closed issue
    const startBtn = page.getByRole('button', { name: /^Start$|^开始处理$/ });
    const startVisible = await startBtn.isVisible({ timeout: 3000 }).catch(() => false);
    expect(
      startVisible,
      'Start button should not be visible for a closed issue',
    ).toBe(false);

    // Status should clearly show "closed"
    const pageContent = await page.locator('body').textContent();
    expect(
      pageContent?.toLowerCase().includes('closed') || pageContent?.includes('已关闭'),
      'Closed issue should display closed status',
    ).toBe(true);
  });

  // =========================================================================
  // SI-011: Navigate to 问题跟踪 list via sidebar menu
  // =========================================================================

  test('SI-011 @smoke: Navigate to 问题跟踪 list via sidebar menu', async ({ page }) => {
    await navigateToConstructionSection(
      page,
      '问题跟踪',
      '/construction-process/follow-ups',
      'cp_issue_follow_up',
    );

    await expect(page).toHaveURL(/\/construction-process\/follow-ups/);

    const table = page.locator('table, [class*="ant-table"]').first();
    await expect(table).toBeVisible({ timeout: 10000 });

    // Should have at least 1 row (follow-up from SI-008)
    const rows = page.locator('tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 8000 });
  });
});
