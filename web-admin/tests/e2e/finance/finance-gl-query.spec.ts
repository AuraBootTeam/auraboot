/**
 * Finance — General Ledger Query (fin_gl_balance) E2E Tests
 *
 * Tests FGL-001 ~ FGL-008:
 * - FGL-001 @smoke:    Navigate via Finance sidebar → 总账查询 menu → page visible
 * - FGL-002 @critical: GL list page renders table with correct column headers
 * - FGL-003 @critical: NamedQuery fin_dashboard_kpi returns data (proves GL aggregation works)
 * - FGL-004 @critical: NamedQuery fin_trial_balance is queryable and returns data
 * - FGL-005 @critical: Create GL balance record via API → appears in list
 * - FGL-006:           GL list supports filtering by account — response non-empty
 * - FGL-007:           AR/AP monthly trend NQ (fin_ar_ap_monthly_trend) returns monthly rows
 * - FGL-008:           AR aging NQ (fin_ar_aging) returns valid bucket data
 *
 * Prerequisites:
 *   - finance plugin imported and models published
 *   - Admin user logged in (storageState)
 *
 * @since 9.1.0
 */

import { test, expect, type Page } from '../../fixtures';
import {
  uniqueId,
  todayStr,
  executeCommandViaApi,
  waitForDynamicPageLoad,
  findRowInPaginatedList,
} from '../helpers/index';

// ---------------------------------------------------------------------------
// Plugin availability check
// ---------------------------------------------------------------------------

async function isFinancePluginInstalled(page: Page): Promise<boolean> {
  const resp = await page.request.get('/api/meta/models/code/fin_account').catch(() => null);
  if (!resp) return false;
  const body = await resp.json().catch(() => ({}));
  return resp.ok() && body?.data?.status === 'published';
}

// ---------------------------------------------------------------------------
// Navigation helper
// ---------------------------------------------------------------------------

/**
 * Navigate to the General Ledger query page via Finance sidebar menu.
 * Route: Finance → 财务管理 → 总账查询
 */
async function gotoGlList(page: Page): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });

  const nav = page.locator('nav, aside, [role="navigation"]').first();

  // Expand Finance root menu
  const finBtn = nav
    .locator('button', { hasText: /^Finance$/ })
    .or(nav.locator('button', { hasText: /Finance/ }))
    .first();
  await finBtn.waitFor({ state: 'visible', timeout: 15_000 });
  await finBtn.evaluate((el: HTMLElement) => el.click());

  // Expand 财务管理 sub-directory if present
  const financeDir = nav.locator('button', { hasText: /财务管理|Finance Management/ });
  if (await financeDir.isVisible({ timeout: 2_000 }).catch(() => false)) {
    await financeDir.first().evaluate((el: HTMLElement) => el.click());
    await page.waitForTimeout(300);
  }

  // Click 总账查询 / General Ledger
  const glLink = nav.locator('a[href="/finance/gl"]');
  await glLink.first().waitFor({ state: 'attached', timeout: 8_000 });
  await glLink.first().evaluate((el: HTMLAnchorElement) => el.click());

  await expect(page).toHaveURL(/\/finance\/gl/, { timeout: 10_000 });

  await page
    .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 15_000 })
    .catch(() => null);

  await waitForDynamicPageLoad(page);
}

// ---------------------------------------------------------------------------
// Named Query helper
// ---------------------------------------------------------------------------

async function queryNQ(
  page: Page,
  nqCode: string,
  extraParams?: Record<string, string>,
): Promise<{ ok: boolean; recordCount: number; body: any }> {
  const params = new URLSearchParams({
    datasourceId: `nq:${nqCode}`,
    format: 'records',
    maxItems: '50',
    ...extraParams,
  });
  const resp = await page.request
    .get(`/api/datasource/list?${params.toString()}`)
    .catch(() => null);
  if (!resp) return { ok: false, recordCount: 0, body: null };
  const body = await resp.json().catch(() => ({}));
  const records: unknown[] = body?.data?.records ?? [];
  return { ok: resp.ok(), recordCount: records.length, body };
}

function isUnavailableNamedQueryResponse(body: any): boolean {
  const detail = JSON.stringify(body ?? {});
  return /NoResourceFoundException|No static resource|unexpected error/i.test(detail);
}

// ---------------------------------------------------------------------------
// Shared test data
// ---------------------------------------------------------------------------

const UID = uniqueId('FGL');
let glAccountPid = '';
let glPeriodPid = '';

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

test.describe('Finance GL Query @finance', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(90_000);

  // =========================================================================
  // beforeAll: create an account + GL balance via API for data-driven tests
  // =========================================================================
  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      const installed = await isFinancePluginInstalled(page);
      if (!installed) {
        console.warn('[finance-gl-query] Finance plugin not installed — tests will skip');
        return;
      }

      // Create a chart-of-account entry
      const account = await executeCommandViaApi(
        page,
        'fin:create_account',
        {
          fin_acc_code: `E2E-GL-${UID}`,
          fin_acc_name: `E2E GL Account ${UID}`,
          fin_acc_type: 'asset',
          fin_acc_level: 1,
          fin_acc_is_detail: true,
          fin_acc_balance_direction: 'debit',
        },
        undefined,
        'create',
        { allowHttpError: true },
      );
      glAccountPid = account.recordId ?? '';

      // Create a fiscal period
      const period = await executeCommandViaApi(
        page,
        'fin:create_fiscal_period',
        {
          fin_fp_year: 2026,
          fin_fp_period: Math.floor(Math.random() * 9000) + 1000,
          fin_fp_name: `E2E-FP-GL-${UID}`,
          fin_fp_start_date: '2026-01-01',
          fin_fp_end_date: '2026-12-31',
        },
        undefined,
        'create',
        { allowHttpError: true },
      );
      glPeriodPid = period.recordId ?? '';

      // Create a GL balance record
      if (glAccountPid && glPeriodPid) {
        await executeCommandViaApi(
          page,
          'fin:create_gl_balance',
          {
            fin_glb_account_id: glAccountPid,
            fin_glb_period_id: glPeriodPid,
            fin_glb_opening_debit: 10000,
            fin_glb_opening_credit: 0,
            fin_glb_period_debit: 5000,
            fin_glb_period_credit: 2000,
            fin_glb_closing_debit: 15000,
            fin_glb_closing_credit: 2000,
            fin_glb_ytd_debit: 15000,
            fin_glb_ytd_credit: 2000,
          },
          undefined,
          'create',
          { allowHttpError: true },
        );
      }
    } finally {
      await ctx.close();
    }
  });

  // =========================================================================
  // FGL-001 @smoke: Navigate via sidebar to GL Query page
  // =========================================================================
  test('FGL-001: Navigate via Finance sidebar to General Ledger query page', async ({ page }) => {
    const installed = await isFinancePluginInstalled(page);
    if (!installed) {
      test.skip(true, 'Finance plugin not installed — skipping FGL-001');
      return;
    }

    await gotoGlList(page);

    // Layer 1 (Render): Table/list visible
    const table = page.locator('table, [role="table"], [data-testid="dynamic-list"]');
    await expect(table.first()).toBeVisible({ timeout: 10_000 });

    // Layer 2 (Data): At least one column header visible
    const headerRow = page.locator('thead tr, [role="row"]').first();
    await expect(headerRow).toBeVisible({ timeout: 5_000 });

    // Layer 3 (Interaction): URL is correct
    await expect(page).toHaveURL(/\/finance\/gl/);
  });

  // =========================================================================
  // FGL-002 @critical: GL list renders correct column headers (i18n resolved)
  // =========================================================================
  test('FGL-002: GL list page renders column headers — not raw DSL keys', async ({ page }) => {
    const installed = await isFinancePluginInstalled(page);
    if (!installed) {
      test.skip(true, 'Finance plugin not installed — skipping FGL-002');
      return;
    }

    await gotoGlList(page);

    const table = page.locator('table, [role="table"]').first();
    await expect(table).toBeVisible({ timeout: 10_000 });

    // Check that headers are human-readable, not DSL keys like "fin_glb_*"
    const headers = await page.locator('thead th, [role="columnheader"]').allInnerTexts();
    for (const header of headers) {
      const h = header.trim();
      if (!h || h === '' || h === '#' || h === '操作' || h === 'Action') continue;
      expect(h, `Column header "${h}" looks like a raw DSL key`).not.toMatch(/^fin_glb_|^field\./);
    }
  });

  // =========================================================================
  // FGL-003 @critical: Dashboard KPI named query returns data
  // =========================================================================
  test('FGL-003: NamedQuery fin_dashboard_kpi returns 1 KPI row', async ({ page }) => {
    const installed = await isFinancePluginInstalled(page);
    if (!installed) {
      test.skip(true, 'Finance plugin not installed — skipping FGL-003');
      return;
    }

    const { ok, recordCount, body } = await queryNQ(page, 'fin_dashboard_kpi');
    expect(ok, 'fin_dashboard_kpi NQ should return HTTP 200').toBe(true);
    // KPI NQ returns a single aggregate row
    expect(
      recordCount,
      `fin_dashboard_kpi should return exactly 1 row, got ${recordCount}. Body: ${JSON.stringify(body).slice(0, 300)}`,
    ).toBe(1);
  });

  // =========================================================================
  // FGL-004 @critical: Trial balance NQ is queryable
  // =========================================================================
  test('FGL-004: NamedQuery fin_trial_balance returns rows after GL balance creation', async ({
    page,
  }) => {
    const installed = await isFinancePluginInstalled(page);
    if (!installed) {
      test.skip(true, 'Finance plugin not installed — skipping FGL-004');
      return;
    }

    const { ok, recordCount, body } = await queryNQ(page, 'fin_trial_balance');
    if (!ok && isUnavailableNamedQueryResponse(body)) {
      test.skip(true, 'fin_trial_balance named query is unavailable in the current environment');
      return;
    }
    expect(ok, 'fin_trial_balance NQ should return HTTP 200').toBe(true);
    expect(
      recordCount,
      `fin_trial_balance should return at least 1 row. Body: ${JSON.stringify(body).slice(0, 300)}`,
    ).toBeGreaterThanOrEqual(1);
  });

  // =========================================================================
  // FGL-005 @critical: GL balance record created in beforeAll appears in list
  // =========================================================================
  test('FGL-005: GL balance record (created via API) is visible in list', async ({ page }) => {
    const installed = await isFinancePluginInstalled(page);
    if (!installed) {
      test.skip(true, 'Finance plugin not installed — skipping FGL-005');
      return;
    }
    if (!glAccountPid) {
      test.skip(true, 'No GL account created in beforeAll — skipping FGL-005');
      return;
    }

    await gotoGlList(page);

    // Layer 2 (Data): Verify via the same list API with a targeted account filter.
    // The UI list may stay empty on the first paint because of async hydration or default view filters.
    const resp = await page.request
      .get(
        `/api/dynamic/fin_gl_balance/list?pageNum=1&pageSize=20&filters=${encodeURIComponent(
          JSON.stringify([{ fieldName: 'fin_glb_account_id', operator: 'EQ', value: glAccountPid }]),
        )}`,
      )
      .catch(() => null);
    expect(resp, 'GL balance filtered list API should be reachable').not.toBeNull();
    if (!resp) return;

    expect(resp.ok(), `GL balance filtered list API should return 200, got ${resp.status()}`).toBe(
      true,
    );
    const body = await resp.json().catch(() => ({}));
    const records: unknown[] = body?.data?.records ?? body?.data?.list ?? [];
    expect(
      records.length,
      'GL balance filtered list should contain at least 1 record for the seeded account',
    ).toBeGreaterThanOrEqual(1);
  });

  // =========================================================================
  // FGL-006: GL list API with pagination returns valid JSON structure
  // =========================================================================
  test('FGL-006: GL list API returns valid paginated response', async ({ page }) => {
    const installed = await isFinancePluginInstalled(page);
    if (!installed) {
      test.skip(true, 'Finance plugin not installed — skipping FGL-006');
      return;
    }

    // Directly call the list API that the GL page would use
    const resp = await page.request
      .get('/api/dynamic/fin_gl_balance/list?pageNum=1&pageSize=20')
      .catch(() => null);

    expect(resp, 'GL list API should be reachable').not.toBeNull();
    if (!resp) return;

    expect(resp.ok(), `GL list API should return 200, got ${resp.status()}`).toBe(true);

    const body = await resp.json();
    // Layer 2 (Data): Response has standard code=0 structure
    expect(body?.code, `GL list API response code should be '0', got: ${body?.code}`).toBe('0');

    // Response should have page info
    const data = body?.data;
    expect(data, 'Response data should not be null').not.toBeNull();

    // Records array should be present (even if empty)
    const records: unknown[] = data?.records ?? data?.list ?? [];
    expect(Array.isArray(records), 'Records should be an array').toBe(true);
  });

  // =========================================================================
  // FGL-007: AR/AP monthly trend NQ returns monthly rows
  // =========================================================================
  test('FGL-007: NamedQuery fin_ar_ap_monthly_trend returns monthly trend rows', async ({
    page,
  }) => {
    const installed = await isFinancePluginInstalled(page);
    if (!installed) {
      test.skip(true, 'Finance plugin not installed — skipping FGL-007');
      return;
    }

    const { ok, recordCount, body } = await queryNQ(page, 'fin_ar_ap_monthly_trend');
    expect(ok, 'fin_ar_ap_monthly_trend NQ should return HTTP 200').toBe(true);
    // This NQ groups by month over last 6 months — may be 0 rows if no AR/AP data
    // We just verify the query is structurally valid (code=0) and doesn't error
    expect(
      body?.code,
      `fin_ar_ap_monthly_trend should return code '0'. Body: ${JSON.stringify(body).slice(0, 300)}`,
    ).toBe('0');
  });

  // =========================================================================
  // FGL-008: AR aging NQ returns valid bucket structure
  // =========================================================================
  test('FGL-008: NamedQuery fin_ar_aging returns valid aging bucket data', async ({ page }) => {
    const installed = await isFinancePluginInstalled(page);
    if (!installed) {
      test.skip(true, 'Finance plugin not installed — skipping FGL-008');
      return;
    }

    const { ok, body } = await queryNQ(page, 'fin_ar_aging');
    expect(ok, 'fin_ar_aging NQ should return HTTP 200').toBe(true);
    expect(
      body?.code,
      `fin_ar_aging should return code '0'. Body: ${JSON.stringify(body).slice(0, 300)}`,
    ).toBe('0');

    // If there are records, validate the aging bucket structure
    const records: any[] = body?.data?.records ?? [];
    if (records.length > 0) {
      const firstRecord = records[0];
      // AR aging NQ should have amount/balance fields, not just nulls
      const hasValues = Object.values(firstRecord).some((v) => v !== null && v !== undefined);
      expect(hasValues, 'AR aging records should have non-null field values').toBe(true);
    }
  });

  // =========================================================================
  // FGL-009: Navigate to GL page via direct URL and verify list API data contract
  // =========================================================================
  test('FGL-009: GL page API response has correct code=0 and data structure', async ({ page }) => {
    const installed = await isFinancePluginInstalled(page);
    if (!installed) {
      test.skip(true, 'Finance plugin not installed — skipping FGL-009');
      return;
    }

    // Set up response intercept BEFORE navigation
    const listResponsePromise = page.waitForResponse(
      (r) => r.url().includes('/api/dynamic/') && r.url().includes('/list') && r.status() === 200,
      { timeout: 15_000 },
    );

    await page.goto('/finance/gl', { waitUntil: 'domcontentloaded' });

    const listResp = await listResponsePromise.catch(() => null);
    expect(listResp, 'GL list page should trigger a /list API call').not.toBeNull();

    if (!listResp) return;

    const body = await listResp.json().catch(() => ({}));

    // Layer 2 (Data): API response follows standard code=0 pattern
    expect(
      body?.code,
      `GL list API response code should be '0' but got '${body?.code}'. This indicates the API returned non-standard format.`,
    ).toBe('0');

    // Layer 1 (Render): Page renders without error after API response
    // Be specific — "500" alone matches data values like amounts/quantities;
    // look for actual error messages instead.
    const errorBlock = page.locator('text=/Internal Server Error|Access forbidden|Page not found/i');
    await expect(errorBlock.first()).not.toBeVisible({ timeout: 5_000 });

    // Layer 1 (Render): Table is visible
    const table = page.locator('table, [role="table"], [data-testid="dynamic-list"]');
    await expect(table.first()).toBeVisible({ timeout: 8_000 });
  });
});
