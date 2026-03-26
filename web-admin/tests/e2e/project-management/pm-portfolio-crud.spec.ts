/**
 * PM Portfolio CRUD E2E Tests
 *
 * Tests the Project Portfolio (项目集) module — which is a separate model from
 * individual projects. Portfolios group multiple projects together.
 *
 * PORTFOLIO-01 @smoke   : Navigate to 项目集 list via sidebar menu
 * PORTFOLIO-02 @smoke   : Portfolio list renders with real data
 * PORTFOLIO-03 @critical: Create portfolio → appears in list with planning status
 * PORTFOLIO-04 @critical: Activate portfolio (planning → active)
 * PORTFOLIO-05 @critical: Hold portfolio (active → on_hold)
 * PORTFOLIO-06 @critical: Resume portfolio (on_hold → active)
 * PORTFOLIO-07 @critical: Close portfolio (active → closed)
 * PORTFOLIO-08          : Required field validation on portfolio form
 * PORTFOLIO-09          : Delete portfolio in draft/planning state
 *
 * Prerequisites:
 *   - project-management plugin imported and published
 *   - pm_portfolio model published and menus registered
 *
 * @since 10.1.0
 */

import { test, expect } from '../../fixtures';
import { uniqueId, executeCommandViaApi, findRowInPaginatedList } from '../helpers/index';

// ---------------------------------------------------------------------------
// Navigation helper
// ---------------------------------------------------------------------------

async function navigateToPortfolios(page: import('@playwright/test').Page): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });

  const nav = page.locator('nav');
  const pmBtn = nav.getByRole('button', { name: /Project Management|项目管理/ });
  await pmBtn.first().scrollIntoViewIfNeeded();
  await pmBtn.first().click();

  // Portfolio menu link
  const link = nav.locator('a[href="/dynamic/pm-portfolio"]');
  await link.first().waitFor({ state: 'attached', timeout: 8000 });

  const listRespPromise = page.waitForResponse(
    (r) =>
      (r.url().includes('/api/dynamic/pm_portfolio/list') ||
        r.url().includes('/api/dynamic/pm-portfolio/list')) &&
      r.status() === 200,
    { timeout: 15000 },
  );
  await link.first().evaluate((el: HTMLElement) => el.click());
  await listRespPromise.catch(() => null);

  await expect(page.locator('table, [class*="ant-table"]').first()).toBeVisible({ timeout: 10000 });
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const UID = uniqueId('PMPf');

// ---------------------------------------------------------------------------
// Test suite
// ---------------------------------------------------------------------------

test.describe('PM Portfolio CRUD', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(90000);

  let portfolioPid: string;
  let holdPortfolioPid: string;

  // =========================================================================
  // Setup: create portfolios via API for lifecycle tests
  // =========================================================================

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();

    try {
      // Primary portfolio for activate → hold → resume → close lifecycle
      const r1 = await executeCommandViaApi(
        page,
        'pm:create_portfolio',
        {
          pm_pf_name: `E2E Portfolio ${UID}`,
          pm_pf_description: `E2E test portfolio for lifecycle ${UID}`,
        },
        undefined,
        'create',
      );
      portfolioPid = r1.recordId;
      expect(portfolioPid).toBeTruthy();

      // Second portfolio for hold flow test
      const r2 = await executeCommandViaApi(
        page,
        'pm:create_portfolio',
        {
          pm_pf_name: `E2E PfHold ${UID}`,
          pm_pf_description: `E2E test portfolio for hold flow ${UID}`,
        },
        undefined,
        'create',
      );
      holdPortfolioPid = r2.recordId;
      expect(holdPortfolioPid).toBeTruthy();
    } finally {
      await ctx.close();
    }
  });

  // =========================================================================
  // PORTFOLIO-01: Navigate to portfolio list via sidebar menu
  // =========================================================================

  test('PORTFOLIO-01 @smoke: Navigate to 项目集 list via sidebar menu', async ({ page }) => {
    await navigateToPortfolios(page);

    await expect(page).toHaveURL(/\/dynamic\/pm-portfolio/);

    const table = page.locator('table, [class*="ant-table"]').first();
    await expect(table).toBeVisible({ timeout: 10000 });
  });

  // =========================================================================
  // PORTFOLIO-02: Portfolio list has real data rows
  // =========================================================================

  test('PORTFOLIO-02 @smoke: Portfolio list renders with seeded data', async ({ page }) => {
    await navigateToPortfolios(page);

    // Rows visible (beforeAll created 2)
    const rows = page.locator('tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 8000 });

    const rowCount = await rows.count();
    expect(rowCount, 'Should have at least 2 portfolio rows (seeded in beforeAll)').toBeGreaterThanOrEqual(2);

    // i18n: no raw field code leak
    const headerRow = page.locator('thead tr').first();
    const headerText = await headerRow.textContent();
    expect(headerText, 'Header should not contain raw field codes').not.toMatch(/pm_portfolio_/i);
  });

  // =========================================================================
  // PORTFOLIO-03: Created portfolio appears in list with planning status
  // =========================================================================

  test('PORTFOLIO-03 @critical: Created portfolio appears in list with planning status', async ({
    page,
  }) => {
    expect(portfolioPid, 'Portfolio should have been created in beforeAll').toBeTruthy();

    await navigateToPortfolios(page);

    const row = await findRowInPaginatedList(page, `E2E Portfolio ${UID}`);
    await expect(row).toBeVisible({ timeout: 8000 });

    // Status should be planning or draft (initial state)
    const rowText = await row.textContent();
    expect(
      rowText?.toLowerCase().includes('planning') || rowText?.includes('规划'),
      'Portfolio should be in planning/draft state',
    ).toBe(true);
  });

  // =========================================================================
  // PORTFOLIO-04: Activate portfolio (planning → active)
  // =========================================================================

  test('PORTFOLIO-04 @critical: Activate portfolio → active status', async ({ page }) => {
    expect(portfolioPid, 'Portfolio should have been created in beforeAll').toBeTruthy();

    // Activate via API command
    await executeCommandViaApi(
      page,
      'pm:activate_portfolio',
      {},
      portfolioPid,
      'state_transition',
    );

    // Verify via API
    const resp = await page.request.get(`/api/dynamic/pm_portfolio/${portfolioPid}`);
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const status = (body?.data ?? body).pm_pf_status;
    expect(status, 'Portfolio status should be active after activation').toBe('active');

    // Verify in list UI — navigate and find row
    await navigateToPortfolios(page);
    const row = await findRowInPaginatedList(page, `E2E Portfolio ${UID}`);
    await expect(row).toBeVisible({ timeout: 5000 });
    const rowText = await row.textContent();
    expect(
      rowText?.toLowerCase().includes('active') || rowText?.includes('活跃') || rowText?.includes('进行中'),
      'Portfolio status badge should show active',
    ).toBe(true);
  });

  // =========================================================================
  // PORTFOLIO-05: Hold portfolio (active → on_hold)
  // =========================================================================

  test('PORTFOLIO-05 @critical: Hold portfolio (active → on_hold)', async ({ page }) => {
    expect(holdPortfolioPid, 'Second portfolio should have been created in beforeAll').toBeTruthy();

    // First activate it
    await executeCommandViaApi(
      page,
      'pm:activate_portfolio',
      {},
      holdPortfolioPid,
      'state_transition',
    );

    // Then put on hold
    await executeCommandViaApi(
      page,
      'pm:hold_portfolio',
      {},
      holdPortfolioPid,
      'state_transition',
    );

    // Verify via API
    const resp = await page.request.get(`/api/dynamic/pm_portfolio/${holdPortfolioPid}`);
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const status = (body?.data ?? body).pm_pf_status;
    expect(status, 'Portfolio status should be on_hold').toBe('on_hold');
  });

  // =========================================================================
  // PORTFOLIO-06: Resume portfolio (on_hold → active)
  // =========================================================================

  test('PORTFOLIO-06 @critical: Resume portfolio (on_hold → active)', async ({ page }) => {
    expect(holdPortfolioPid, 'Second portfolio should have been created in beforeAll').toBeTruthy();

    await executeCommandViaApi(
      page,
      'pm:resume_portfolio',
      {},
      holdPortfolioPid,
      'state_transition',
    );

    // Verify via API
    const resp = await page.request.get(`/api/dynamic/pm_portfolio/${holdPortfolioPid}`);
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const status = (body?.data ?? body).pm_pf_status;
    expect(status, 'Portfolio status should be active after resume').toBe('active');
  });

  // =========================================================================
  // PORTFOLIO-07: Close portfolio (active → closed)
  // =========================================================================

  test('PORTFOLIO-07 @critical: Close portfolio (active → closed)', async ({ page }) => {
    expect(portfolioPid, 'Portfolio should have been created in beforeAll').toBeTruthy();

    await executeCommandViaApi(
      page,
      'pm:close_portfolio',
      {},
      portfolioPid,
      'state_transition',
    );

    // Verify via API
    const resp = await page.request.get(`/api/dynamic/pm_portfolio/${portfolioPid}`);
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const status = (body?.data ?? body).pm_pf_status;
    expect(status, 'Portfolio status should be closed').toBe('closed');

    // Verify in list UI
    await navigateToPortfolios(page);
    const row = await findRowInPaginatedList(page, `E2E Portfolio ${UID}`);
    await expect(row).toBeVisible({ timeout: 5000 });
    const rowText = await row.textContent();
    expect(
      rowText?.toLowerCase().includes('closed') || rowText?.includes('已关闭') || rowText?.includes('关闭'),
      'Portfolio status badge should show closed',
    ).toBe(true);
  });

  // =========================================================================
  // PORTFOLIO-08: Required field validation on portfolio form
  // =========================================================================

  test('PORTFOLIO-08: Portfolio creation validates required portfolio name', async ({ page }) => {
    // Navigate directly to the create form URL (DSL navigates to /new for create action)
    // This tests form validation behavior, not the Create button navigation
    await page.goto('/dynamic/pm_portfolio/new', { waitUntil: 'domcontentloaded' });

    // Wait for form to render
    const form = page.locator('[data-testid="dynamic-form"], form').first();
    await expect(form).toBeVisible({ timeout: 15_000 });

    // Submit without filling required portfolio name
    // Button may be inside or outside the form section — search page-wide via testid or role
    const submitBtn = page.locator('[data-testid="form-btn-save"], [data-testid="form-btn-submit"]')
      .or(page.getByRole('button', { name: /Save|保存/ }))
      .first();
    await submitBtn.waitFor({ state: 'visible', timeout: 8_000 });
    await submitBtn.click();

    // Validation error should appear
    const errorMsg = page.locator('[class*="error"], [class*="ant-form-item-explain-error"], .text-red-500');
    await expect(errorMsg.first()).toBeVisible({ timeout: 8_000 });

    // Page should still be at /new (form stayed open due to validation error)
    expect(page.url()).toContain('/new');
  });

  // =========================================================================
  // PORTFOLIO-09: Delete portfolio in planning state
  // =========================================================================

  test('PORTFOLIO-09: Delete portfolio in planning state → disappears from list', async ({ page }) => {
    // Create a disposable portfolio via API
    const delResp = await executeCommandViaApi(
      page,
      'pm:create_portfolio',
      {
        pm_pf_name: `E2E PfDel ${UID}`,
        pm_pf_description: `Portfolio to be deleted ${UID}`,
      },
      undefined,
      'create',
    );
    const delPid = delResp.recordId;
    expect(delPid, 'Disposable portfolio should be created').toBeTruthy();

    // Delete via command
    await executeCommandViaApi(page, 'pm:delete_portfolio', {}, delPid, 'delete');

    // Verify via API — should return 404 or empty data
    const checkResp = await page.request.get(`/api/dynamic/pm_portfolio/${delPid}`);
    const checkBody = await checkResp.json();
    const deletedRec = checkBody?.data ?? checkBody;
    const isDeleted =
      !checkResp.ok() ||
      deletedRec === null ||
      deletedRec?.deleted_flag === true ||
      Object.keys(deletedRec ?? {}).length === 0;
    expect(isDeleted, 'Deleted portfolio should not be retrievable').toBe(true);

    // Verify not in list UI — navigate and search
    await navigateToPortfolios(page);
    await page.locator('tbody tr').first().waitFor({ state: 'visible', timeout: 8000 }).catch(() => null);
    const rows = page.locator('tbody tr', { hasText: `E2E PfDel ${UID}` });
    const count = await rows.count();
    expect(count, 'Deleted portfolio should not appear in list').toBe(0);
  });
});
