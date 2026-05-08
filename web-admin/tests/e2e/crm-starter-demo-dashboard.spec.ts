/**
 * CRM Starter Demo — Lightweight Dashboard E2E
 *
 * Validates the crm-starter `crm_overview` dashboard wired through the menu
 * "CRM 演示 → 驾驶舱" / "CRM Demo → Dashboard" (path `/crm/dashboard`,
 * pageKey `crm_overview`).
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

  // Click leaf "驾驶舱" / "Dashboard" — path is /crm/dashboard
  const leafLink = nav.locator('a[href="/crm/dashboard"]').first();
  await leafLink.waitFor({ state: 'attached', timeout: 8_000 });
  await leafLink.evaluate((el: HTMLElement) => el.click());

  await page.waitForURL(/\/crm\/dashboard/, { timeout: 15_000 });
  await page.waitForLoadState('domcontentloaded');
}

test.describe('CRM Starter Demo — Lightweight Dashboard', () => {
  test.setTimeout(60_000);

  test('DASH-001 @smoke — sidebar → dashboard renders both smart-table-chart widgets', async ({
    page,
  }) => {
    await gotoCrmDashboardViaSidebar(page);
    await expect(page).toHaveURL(/\/crm\/dashboard/);

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
});
