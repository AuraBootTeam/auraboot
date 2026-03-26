/**
 * Finance Dashboard E2E Tests
 *
 * Validates the Financial Dashboard page:
 * - Menu navigation: Dashboard accessible via Finance sidebar
 * - DSL Dashboard page renders stat-card, charts, and data-table blocks
 * - NamedQuery data sources return real data
 *
 * @since 8.0.0
 */

import { test, expect } from '../../fixtures';
import {
  uniqueId,
  todayStr,
  executeCommandViaApi,
} from '../helpers/index';

test.describe('Finance Dashboard @smoke', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60000);

  const uid = uniqueId('FINDash');

  // =========================================================================
  // DATA SETUP
  // =========================================================================
  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      // Create a finance account
      await executeCommandViaApi(
        page,
        'fin:create_account',
        {
          fin_acc_code: `ACC_${uid}`,
          fin_acc_name: `DashAccount_${uid}`,
          fin_acc_type: 'asset',
          fin_acc_level: 1,
          fin_acc_is_detail: true,
          fin_acc_balance_direction: 'debit',
          fin_acc_description: `E2E test account ${uid}`,
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

    const menuBtn = page.locator('button', { hasText: /Finance/ }).first();
    await menuBtn.waitFor({ state: 'visible', timeout: 10000 });
    await menuBtn.click();

    const dashLink = page.locator('a[href="/finance/financial-dashboard"]');
    await dashLink.first().waitFor({ state: 'attached', timeout: 5000 });
    await dashLink.first().evaluate((el: HTMLElement) => el.click());

    await expect(page).toHaveURL(/\/finance\/financial-dashboard/, { timeout: 10000 });

    await Promise.all([
      page.waitForResponse(
        (resp) => resp.url().includes('/api/datasource/list') && resp.status() === 200,
        { timeout: 15000 },
      ).catch(() => null),
      page.waitForResponse(
        (resp) => resp.url().includes('/api/meta/chart-data') && resp.status() === 200,
        { timeout: 15000 },
      ).catch(() => null),
    ]);

    await page.waitForTimeout(1000);
  }

  // =========================================================================
  // TESTS
  // =========================================================================

  test('FIN-DASH-01: Dashboard is accessible via Finance sidebar menu', async ({ page }) => {
    await gotoDashboard(page);
    const title = page.locator('text=财务仪表盘').or(page.locator('text=Financial Dashboard'));
    await expect(title.first()).toBeVisible({ timeout: 10000 });
  });

  test('FIN-DASH-02: KPI NQ returns data', async ({ page }) => {
    const resp = await page.request.get(
      '/api/datasource/list?datasourceId=nq:fin_dashboard_kpi&format=records&maxItems=1',
    );
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const records = body?.data?.records ?? [];
    expect(records.length, 'KPI NQ should return 1 row').toBe(1);
  });

  test('FIN-DASH-03: AR/AP trend NQ is queryable via chart-data API', async ({ page }) => {
    const resp = await page.request.post('/api/meta/chart-data', {
      data: {
        type: 'namedQuery',
        queryCode: 'fin_ar_ap_monthly_trend',
        dimensions: ['month'],
        metrics: [
          { field: 'ar_balance', aggregation: 'sum', alias: 'ar_balance' },
          { field: 'ap_balance', aggregation: 'sum', alias: 'ap_balance' },
        ],
      },
    });
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    expect(body?.code).toBe('0');
    expect(body?.data?.rows?.length, 'Should have monthly data').toBeGreaterThanOrEqual(1);
  });

  test('FIN-DASH-04: Dashboard renders stat-card blocks', async ({ page }) => {
    await gotoDashboard(page);
    const cards = page.locator('[class*="stat-card"], [class*="kpi"], [class*="card"]');
    const cardCount = await cards.count();
    expect(cardCount, 'Dashboard should render KPI cards').toBeGreaterThanOrEqual(1);
  });

  test('FIN-DASH-05: Dashboard renders data-table blocks', async ({ page }) => {
    await gotoDashboard(page);
    const tables = page.locator('table, [role="table"]');
    const tableCount = await tables.count();
    expect(tableCount, 'Dashboard should render data tables').toBeGreaterThanOrEqual(1);
  });
});
