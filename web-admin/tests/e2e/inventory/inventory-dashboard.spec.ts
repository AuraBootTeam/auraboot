/**
 * Inventory Dashboard E2E Tests
 *
 * Validates the Inventory Dashboard page:
 * - Menu navigation: Dashboard accessible via Inventory sidebar
 * - DSL Dashboard page renders stat-card, charts, and data-table blocks
 * - NamedQuery data sources return real data
 *
 * @since 8.0.0
 */

import { test, expect } from '../../fixtures';
import {
  uniqueId,
  executeCommandViaApi,
} from '../helpers/index';

test.describe('Inventory Dashboard @smoke', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60000);

  const uid = uniqueId('INVDash');

  // =========================================================================
  // DATA SETUP
  // =========================================================================
  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      // Create warehouse
      await executeCommandViaApi(
        page,
        'pe:create_warehouse',
        {
          inv_warehouse_name: `DashWH_${uid}`,
          inv_warehouse_type: 'raw_material',
          inv_warehouse_address: `Test Address ${uid}`,
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

    const menuBtn = page.locator('button', { hasText: /Inventory/ }).first();
    await menuBtn.waitFor({ state: 'visible', timeout: 10000 });
    await menuBtn.click();

    const dashLink = page.locator('a[href="/inventory/dashboard"]');
    await dashLink.first().waitFor({ state: 'attached', timeout: 5000 });
    await dashLink.first().evaluate((el: HTMLElement) => el.click());

    await expect(page).toHaveURL(/\/inventory\/dashboard/, { timeout: 10000 });

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

  test('INV-DASH-01: Dashboard is accessible via Inventory sidebar menu', async ({ page }) => {
    await gotoDashboard(page);
    const title = page.locator('text=库存看板').or(page.locator('text=Inventory Dashboard'));
    await expect(title.first()).toBeVisible({ timeout: 10000 });
  });

  test('INV-DASH-02: KPI NQ returns data with warehouse count', async ({ page }) => {
    const resp = await page.request.get(
      '/api/datasource/list?datasourceId=nq:inv_dashboard_kpi&format=records&maxItems=1',
    );
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const records = body?.data?.records ?? [];
    expect(records.length, 'KPI NQ should return 1 row').toBe(1);

    const kpi = records[0];
    expect(Number(kpi.active_warehouses), 'Should have at least 1 active warehouse').toBeGreaterThanOrEqual(1);
  });

  test('INV-DASH-03: Movement trend NQ is queryable via chart-data API', async ({ page }) => {
    const resp = await page.request.post('/api/meta/chart-data', {
      data: {
        type: 'namedQuery',
        queryCode: 'inv_movement_monthly_trend',
        dimensions: ['month'],
        metrics: [
          { field: 'inbound_count', aggregation: 'sum', alias: 'inbound_count' },
          { field: 'outbound_count', aggregation: 'sum', alias: 'outbound_count' },
        ],
      },
    });
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    expect(body?.code).toBe('0');
    expect(body?.data?.rows?.length, 'Should have monthly data').toBeGreaterThanOrEqual(1);
  });

  test('INV-DASH-04: Dashboard renders stat-card and chart blocks', async ({ page }) => {
    await gotoDashboard(page);
    const cards = page.locator('[class*="stat-card"], [class*="kpi"], [class*="card"]');
    const cardCount = await cards.count();
    expect(cardCount, 'Dashboard should render KPI cards').toBeGreaterThanOrEqual(1);
  });

  test('INV-DASH-05: Dashboard renders data-table blocks', async ({ page }) => {
    await gotoDashboard(page);
    const tables = page.locator('table, [role="table"]');
    const tableCount = await tables.count();
    expect(tableCount, 'Dashboard should render data tables').toBeGreaterThanOrEqual(1);
  });
});
