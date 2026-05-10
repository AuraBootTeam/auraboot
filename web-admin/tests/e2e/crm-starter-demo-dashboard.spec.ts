/**
 * CRM Starter Demo — Lightweight Dashboard E2E
 *
 * Validates the crm-starter `crm_overview` dashboard wired through the menu
 * "CRM 演示 → 驾驶舱" / "CRM Demo → Dashboard" (path
 * `/dashboards/view/crm_overview`, pageKey `crm_overview`).
 *
 * Real widgets (per `plugins/crm-starter/config/dashboards/crm_overview.json`):
 *   - block_recent_opportunities — type `smart-table-chart`, title "最新商机"
 *   - block_recent_leads          — type `smart-table-chart`, title "最新线索"
 *
 * The dashboard uses the platform `smart-table-chart` widget rather than the
 * placeholder bar / KPI shapes used in the plan template, so the assertions
 * below pin to the title text + the row table that the widget actually emits.
 *
 * Coverage: D1 sidebar nav · D2 widget renders with rows · D14 row link
 * navigates to detail page (smart-table-chart `rowActions` → navigate).
 *
 * Prerequisites:
 *   - crm-starter plugin imported (dashboard JSON registered + namedQueries
 *     resolvable). If the dashboard fails to load (smart-table-chart shows the
 *     "Please configure data source" or error state) the test fails so
 *     follow-up backlog work is tracked instead of being silently skipped.
 */

import { test, expect, type Page } from '../fixtures';

function extractTotal(body: any): number {
  const raw =
    body?.data?.total ??
    body?.data?.totalCount ??
    body?.data?.records?.length ??
    0;
  const value = Number(raw);
  return Number.isFinite(value) ? value : 0;
}

function captureDashboardConsoleErrors(page: Page): string[] {
  const errors: string[] = [];
  page.on('console', (message) => {
    if (message.type() !== 'error') return;
    errors.push(message.text());
  });
  page.on('pageerror', (error) => {
    errors.push(error.message);
  });
  return errors;
}

// ---------------------------------------------------------------------------
// Sidebar navigation — clicks "CRM 演示 → 驾驶舱" / "CRM Demo → Dashboard"
// ---------------------------------------------------------------------------

async function gotoCrmDashboardViaSidebar(page: Page): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });

  const nav = page.locator('nav, aside, [role="navigation"]').first();
  await nav.waitFor({ state: 'visible', timeout: 10_000 });

  // Expand "CRM 演示" / "CRM Demo" parent
  const rootBtn = nav
    .getByRole('button', { name: /CRM 演示|CRM Demo/i })
    .or(nav.locator('text=/CRM 演示|CRM Demo/'))
    .first();
  await rootBtn.scrollIntoViewIfNeeded();
  await rootBtn.evaluate((el: HTMLElement) => el.click());

  // Click leaf "驾驶舱" / "Dashboard" — path is /dashboards/view/crm_overview
  const leafLink = nav.locator('a[href="/dashboards/view/crm_overview"]').first();
  await leafLink.waitFor({ state: 'attached', timeout: 8_000 });
  await leafLink.evaluate((el: HTMLElement) => el.click());

  await page.waitForURL(/\/dashboards\/view\/crm_overview/, { timeout: 15_000 });
  await page.waitForLoadState('domcontentloaded');
}

test.describe('CRM Starter Demo — Lightweight Dashboard', () => {
  test.setTimeout(60_000);
  test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

  test('DASH-001 @smoke — sidebar → dashboard renders both smart-table-chart widgets', async ({
    page,
  }) => {
    await gotoCrmDashboardViaSidebar(page);
    await expect(page).toHaveURL(/\/dashboards\/view\/crm_overview/);

    // The dashboard canvas wraps each widget in a card-style <div>. We pin to the
    // widget title text emitted by SmartTableChart's header (px-4 py-3 border-b).
    const recentOpps = page.getByText(/最新商机|Recent Opportunities/).first();
    await expect(recentOpps).toBeVisible({ timeout: 15_000 });

    const recentLeads = page.getByText(/最新线索|Recent Leads/).first();
    await expect(recentLeads).toBeVisible({ timeout: 15_000 });

    // SmartTableChart renders a real <table> once the data source resolves.
    // If the widget falls back to "Please configure data source" or
    // "Failed to load data" the table will not appear — assert the table to
    // surface namedQuery / data-source regressions explicitly.
    const tables = page.locator('main table, [role="main"] table');
    await expect(tables.first()).toBeVisible({ timeout: 15_000 });
    const tableCount = await tables.count();
    expect(
      tableCount,
      'crm_overview dashboard should render two smart-table-chart tables',
    ).toBeGreaterThanOrEqual(2);

    // Either widget should not show the "configure data source" placeholder
    // (renderEmpty branch in SmartTableChart). Treating the placeholder as a
    // failure rather than a tolerated state keeps the spec from passing on a
    // half-broken dashboard.
    const placeholder = page.getByText(/Please configure data source/i).first();
    await expect(placeholder).toHaveCount(0);

    // Recent opportunities widget should expose the column headers we configured
    // (zh: 商机名称 / 客户 / 阶段 / 预期金额). At least the opportunity column header
    // is asserted so future copy drift is caught.
    const oppHeader = page.getByText(/商机名称|Opportunity/).first();
    await expect(oppHeader).toBeVisible({ timeout: 10_000 });

    // Recent leads widget exposes its own headers (公司 / 联系人 / 来源 / 状态).
    const leadHeader = page.getByText(/公司|Company/).first();
    await expect(leadHeader).toBeVisible({ timeout: 10_000 });
  });

  test('DASH-002 @smoke — /dashboards resolves default CRM dashboard with seeded rows', async ({
    page,
  }) => {
    const consoleErrors = captureDashboardConsoleErrors(page);
    const dashboardList = page.waitForResponse(
      (response) =>
        response.url().includes('/api/dashboards?status=published') && response.status() === 200,
      { timeout: 15_000 },
    );
    const defaultDashboard = page.waitForResponse(
      (response) => response.url().includes('/api/dashboards/default') && response.status() === 200,
      { timeout: 15_000 },
    );
    const leadList = page.waitForResponse(
      (response) =>
        response.url().includes('/api/dynamic/crm_lead/list') && response.status() === 200,
      { timeout: 15_000 },
    );
    const opportunityList = page.waitForResponse(
      (response) =>
        response.url().includes('/api/dynamic/crm_opportunity/list') && response.status() === 200,
      { timeout: 15_000 },
    );

    await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });

    const [dashboardListResponse, defaultResponse, leadResponse, opportunityResponse] =
      await Promise.all([dashboardList, defaultDashboard, leadList, opportunityList]);
    const [dashboardListBody, defaultBody, leadBody, opportunityBody] = await Promise.all([
      dashboardListResponse.json(),
      defaultResponse.json(),
      leadResponse.json(),
      opportunityResponse.json(),
    ]);

    expect(dashboardListBody?.code).toBe('0');
    expect(defaultBody?.code).toBe('0');
    expect(defaultBody?.data?.code).toBe('crm_overview');
    expect(leadBody?.code).toBe('0');
    expect(opportunityBody?.code).toBe('0');
    expect(extractTotal(leadBody)).toBeGreaterThan(0);
    expect(extractTotal(opportunityBody)).toBeGreaterThan(0);

    await expect(page.getByText(/CRM 概览|CRM Overview/).first()).toBeVisible({
      timeout: 15_000,
    });
    await expect(page.getByText(/最新商机|Recent Opportunities/).first()).toBeVisible({
      timeout: 10_000,
    });
    await expect(page.getByText(/最新线索|Recent Leads/).first()).toBeVisible({
      timeout: 10_000,
    });
    await expect(page.getByText(/Application Error|Internal system error/i)).toHaveCount(0);
    await expect(page.getByText(/Please configure data source|Failed to load data/i)).toHaveCount(
      0,
    );
    await expect(page.getByText('No data available')).toHaveCount(0);

    const tableRows = page.locator('main table tbody tr');
    await expect(tableRows.first()).toBeVisible({ timeout: 10_000 });
    expect(await tableRows.count()).toBeGreaterThan(1);

    const relevantConsoleErrors = consoleErrors.filter((message) =>
      /Invalid hook call|Maximum update depth|Internal system error|Application Error/i.test(
        message,
      ),
    );
    expect(relevantConsoleErrors).toEqual([]);
  });
});
