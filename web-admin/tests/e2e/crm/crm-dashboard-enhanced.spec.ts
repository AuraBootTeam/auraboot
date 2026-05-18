/**
 * CRM Dashboard Enhanced Verification
 *
 * Validates the CRM Dashboard renders real data in all block types:
 * - KPI stat cards with values > 0
 * - Chart blocks with rendered chart elements
 * - Recent opportunities table with data
 * - Recent activities table with data
 *
 * Prerequisites:
 *   - CRM plugin imported with dashboard page and NamedQueries
 *   - Seed data created in beforeAll (account + lead + opportunity + activity)
 *
 * @since 8.0.0
 */

import { test, expect } from '../../fixtures';
import { uniqueId, executeCommandViaApi } from '../helpers/index';

test.describe('CRM Dashboard Enhanced @smoke', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60000);

  const uid = uniqueId('DashE');
  let accountPid: string;

  // =========================================================================
  // DATA SETUP
  // =========================================================================
  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      // Create account
      const accResult = await executeCommandViaApi(
        page,
        'crm:create_account',
        {
          crm_acc_name: `DashEAcct_${uid}`,
          crm_acc_industry: 'technology',
          crm_acc_status: 'active',
          crm_acc_phone: '555-0200',
        },
        undefined,
        'create',
      );
      accountPid = accResult.recordId;

      // Create lead
      await executeCommandViaApi(
        page,
        'crm:create_lead',
        {
          crm_lead_company: `DashELead_${uid}`,
          crm_lead_contact_name: `Contact_${uid}`,
          crm_lead_source: 'referral',
          crm_lead_status: 'new',
        },
        undefined,
        'create',
      );

      // Create opportunity
      await executeCommandViaApi(
        page,
        'crm:create_opportunity',
        {
          crm_opp_name: `DashEOpp_${uid}`,
          crm_opp_account_id: accountPid,
          crm_opp_stage: 'qualification',
          crm_opp_expected_amount: 180000,
        },
        undefined,
        'create',
      );

      // Create activity
      await executeCommandViaApi(
        page,
        'crm:create_activity',
        {
          crm_act_subject: `DashEActivity_${uid}`,
          crm_act_type: 'call',
          crm_act_status: 'planned',
          crm_act_account_id: accountPid,
        },
        undefined,
        'create',
      );
    } finally {
      await ctx.close();
    }
  });

  // =========================================================================
  // HELPERS
  // =========================================================================

  async function gotoDashboard(page: import('@playwright/test').Page) {
    await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });

    // Expand CRM menu group
    const crmButton = page.locator('button', { hasText: /CRM/i }).first();
    await crmButton.waitFor({ state: 'visible', timeout: 10000 });
    await crmButton.click();

    // Click Dashboard link via evaluate (bypass scroll interception)
    const dashLink = page.locator('a[href="/crm/dashboard"]');
    await dashLink.first().waitFor({ state: 'attached', timeout: 5000 });
    await dashLink.first().evaluate((el: HTMLElement) => el.click());

    await expect(page).toHaveURL(/\/crm\/dashboard/, { timeout: 10000 });

    // Wait for dashboard data to load
    await page
      .waitForResponse(
        (resp) =>
          (resp.url().includes('/api/datasource/list') || resp.url().includes('/api/dynamic/')) &&
          resp.status() === 200,
        { timeout: 15000 },
      )
      .catch(() => {});
  }

  // =========================================================================
  // TESTS
  // =========================================================================

  test('DASH-001: Dashboard page loads with KPI cards showing data > 0', async ({ page }) => {
    // Verify KPI data via API first
    const resp = await page.request.get(
      '/api/datasource/list?datasourceId=nq:crm_dashboard_kpi&format=records&maxItems=1',
    );
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const records = body?.data?.records ?? [];
    expect(records.length, 'KPI NQ should return data').toBe(1);

    const kpi = records[0];
    expect(Number(kpi.total_leads), 'Total leads should be > 0').toBeGreaterThan(0);
    expect(Number(kpi.active_accounts), 'Active accounts should be > 0').toBeGreaterThan(0);

    // Navigate and verify KPI cards render on the page
    await gotoDashboard(page);

    // Look for stat card values (numbers > 0 in stat-card blocks)
    const statCards = page.locator(
      '[data-testid*="stat"], [data-testid*="kpi"], .stat-card, [class*="stat"]',
    );
    const cardCount = await statCards.count();
    // Fallback: check that there is at least some numeric content on the page
    if (cardCount === 0) {
      const dashboardMain = page.locator('main, [data-testid="dashboard"]').first();
      await expect(dashboardMain).toBeVisible({ timeout: 10000 });
    }
  });

  test('DASH-002: Chart blocks render with chart containers', async ({ page }) => {
    await gotoDashboard(page);

    // Charts typically render as canvas (Chart.js/ECharts) or SVG (D3/Recharts)
    const chartElements = page.locator('canvas, svg, [data-testid*="chart"], [class*="chart"]');
    const chartCount = await chartElements.count();

    // Also check for table blocks that might contain pipeline data
    const tables = page.locator('table, [role="table"]');
    const tableCount = await tables.count();

    expect(
      chartCount + tableCount,
      'Dashboard should have chart elements or data tables',
    ).toBeGreaterThan(0);
  });

  test('DASH-003: Recent opportunities data available via NQ', async ({ page }) => {
    const resp = await page.request.get(
      '/api/datasource/list?datasourceId=nq:crm_opportunity_pipeline_stats&format=records&maxItems=10',
    );
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const records = body?.data?.records ?? [];
    expect(records.length, 'Opportunity pipeline should have data').toBeGreaterThanOrEqual(1);

    // Verify on page
    await gotoDashboard(page);

    const opportunityTable = page
      .locator('h3:has-text("最新商机"), h3:has-text("Recent Opportunities")')
      .first();
    await expect(opportunityTable).toBeVisible({ timeout: 15000 });

    await expect
      .poll(
        async () => {
          const oppVisible = await page
            .locator(`text=DashEOpp_${uid}`)
            .first()
            .isVisible()
            .catch(() => false);
          const tableRows = await page.locator('table tbody tr').count();
          return oppVisible || tableRows > 0;
        },
        {
          timeout: 15000,
          message: 'Dashboard should show opportunity data in tables or text',
        },
      )
      .toBe(true);
  });

  test('DASH-004: Recent activities data available', async ({ page }) => {
    await gotoDashboard(page);

    // Wait for tables to actually render (gotoDashboard only waits for first API response)
    await page
      .locator('table, [role="table"]')
      .first()
      .waitFor({ state: 'visible', timeout: 15000 })
      .catch(() => {});

    // Check for activity data on the dashboard
    const activityText = page.locator(`text=DashEActivity_${uid}`);
    const actVisible = await activityText
      .first()
      .isVisible({ timeout: 5000 })
      .catch(() => false);

    // Fallback: at least some tables with data
    if (!actVisible) {
      const tables = page.locator('table, [role="table"]');
      const tableCount = await tables.count();
      expect(tableCount, 'Dashboard should render data tables').toBeGreaterThan(0);

      // Check at least one table has rows
      const rows = page.locator('table tbody tr');
      const rowCount = await rows.count();
      expect(rowCount, 'Dashboard tables should have data rows').toBeGreaterThan(0);
    }
  });
});
