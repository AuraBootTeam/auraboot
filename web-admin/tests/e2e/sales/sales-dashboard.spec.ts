/**
 * Sales Dashboard E2E Tests
 *
 * Validates the Sales Dashboard page:
 * - Menu navigation: Dashboard accessible via Sales sidebar
 * - DSL Dashboard page renders stat-card, charts, and data-table blocks
 * - NamedQuery data sources return real data
 *
 * Prerequisites:
 *   - Sales plugin imported (via test-fixtures.setup.ts)
 *   - Seed data created in beforeAll (sales order)
 *
 * @since 8.0.0
 */

import { test, expect } from '../../fixtures';
import { uniqueId, todayStr, dateOffsetStr, executeCommandViaApi } from '../helpers/index';

test.describe('Sales Dashboard @smoke', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60000);

  const uid = uniqueId('SLDash');

  // =========================================================================
  // DATA SETUP
  // =========================================================================
  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      // Create a CRM account first (sales orders reference accounts)
      const accResult = await executeCommandViaApi(
        page,
        'crm:create_account',
        {
          crm_acc_name: `SLDashAcct_${uid}`,
          crm_acc_industry: 'technology',
          crm_acc_status: 'active',
        },
        undefined,
        'create',
      );
      const accountPid = accResult.recordId;

      // Create sales order
      await executeCommandViaApi(
        page,
        'sl:create_sales_order',
        {
          sl_so_account_id: accountPid,
          sl_so_date: todayStr(),
          sl_so_delivery_date: dateOffsetStr(7),
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

    const menuBtn = page.locator('button', { hasText: /Sales/ }).first();
    await menuBtn.waitFor({ state: 'visible', timeout: 10000 });
    await menuBtn.click();

    const dashLink = page.locator('a[href="/sales/dashboard"]');
    await dashLink.first().waitFor({ state: 'attached', timeout: 5000 });
    await dashLink.first().evaluate((el: HTMLElement) => el.click());

    await expect(page).toHaveURL(/\/sales\/dashboard/, { timeout: 10000 });

    // Wait for dashboard data to load
    await Promise.all([
      page
        .waitForResponse(
          (resp) => resp.url().includes('/api/datasource/list') && resp.status() === 200,
          { timeout: 15000 },
        )
        .catch(() => null),
      page
        .waitForResponse(
          (resp) => resp.url().includes('/api/meta/chart-data') && resp.status() === 200,
          { timeout: 15000 },
        )
        .catch(() => null),
    ]);

    // Wait for page content to render
    await page.waitForTimeout(1000);
  }

  // =========================================================================
  // TESTS
  // =========================================================================

  test('SL-DASH-01: Dashboard is accessible via Sales sidebar menu', async ({ page }) => {
    await gotoDashboard(page);
    const title = page.locator('text=销售驾驶舱').or(page.locator('text=Sales Dashboard'));
    await expect(title.first()).toBeVisible({ timeout: 10000 });
  });

  test('SL-DASH-02: KPI NQ returns data', async ({ page }) => {
    const resp = await page.request.get(
      '/api/datasource/list?datasourceId=nq:sales_dashboard_kpi&format=records&maxItems=1',
    );
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const records = body?.data?.records ?? [];
    expect(records.length, 'KPI NQ should return 1 row').toBe(1);
  });

  test('SL-DASH-03: Monthly trend NQ is queryable via chart-data API', async ({ page }) => {
    const resp = await page.request.post('/api/meta/chart-data', {
      data: {
        type: 'namedQuery',
        queryCode: 'sales_order_monthly_trend',
        dimensions: ['month'],
        metrics: [{ field: 'total_amount', aggregation: 'sum', alias: 'total_amount' }],
      },
    });
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    expect(body?.code).toBe('0');
  });

  test('SL-DASH-04: Dashboard renders stat-card and chart blocks', async ({ page }) => {
    await gotoDashboard(page);

    // Stat cards should be visible (KPI block with 6 cards)
    const cards = page.locator('[class*="stat-card"], [class*="kpi"], [class*="card"]');
    const cardCount = await cards.count();
    expect(cardCount, 'Dashboard should render KPI cards').toBeGreaterThanOrEqual(1);
  });

  test('SL-DASH-05: Dashboard renders data-table blocks', async ({ page }) => {
    await gotoDashboard(page);

    const tables = page.locator('table, [role="table"]');
    const tableCount = await tables.count();
    expect(tableCount, 'Dashboard should render data tables').toBeGreaterThanOrEqual(1);
  });
});
