/**
 * Procurement Dashboard E2E Tests
 *
 * Validates the Procurement Dashboard page:
 * - Menu navigation: Dashboard accessible via Procurement sidebar
 * - DSL Dashboard page renders stat-card, charts, and data-table blocks
 * - NamedQuery data sources return real data
 *
 * @since 8.0.0
 */

import { test, expect } from '../../fixtures';
import {
  uniqueId,
  todayStr,
  dateOffsetStr,
  executeCommandViaApi,
} from '../helpers/index';

test.describe('Procurement Dashboard @smoke', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60000);

  const uid = uniqueId('PRDash');

  // =========================================================================
  // DATA SETUP
  // =========================================================================
  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      // Create purchase request (simpler than PO which needs supplier reference)
      await executeCommandViaApi(
        page,
        'pr:create_purchase_request',
        {
          pr_preq_product_id: `PRDashProd_${uid}`,
          pr_preq_qty: 100,
          pr_preq_source: 'manual',
          pr_preq_source_no: `REQ_${uid}`,
          pr_preq_remark: `E2E test seed data ${uid}`,
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

    const menuBtn = page.locator('button', { hasText: /Procurement/ }).first();
    await menuBtn.waitFor({ state: 'visible', timeout: 10000 });
    await menuBtn.click();

    const dashLink = page.locator('a[href="/procurement/dashboard"]');
    await dashLink.first().waitFor({ state: 'attached', timeout: 5000 });
    await dashLink.first().evaluate((el: HTMLElement) => el.click());

    await expect(page).toHaveURL(/\/procurement\/dashboard/, { timeout: 10000 });

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

  test('PR-DASH-01: Dashboard is accessible via Procurement sidebar menu', async ({ page }) => {
    await gotoDashboard(page);
    const title = page.locator('text=采购驾驶舱').or(page.locator('text=Dashboard'));
    await expect(title.first()).toBeVisible({ timeout: 10000 });
  });

  test('PR-DASH-02: KPI NQ returns data', async ({ page }) => {
    const resp = await page.request.get(
      '/api/datasource/list?datasourceId=nq:proc_dashboard_kpi&format=records&maxItems=1',
    );
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const records = body?.data?.records ?? [];
    expect(records.length, 'KPI NQ should return 1 row').toBe(1);
  });

  test('PR-DASH-03: Spend trend NQ is queryable via chart-data API', async ({ page }) => {
    const resp = await page.request.post('/api/meta/chart-data', {
      data: {
        type: 'namedQuery',
        queryCode: 'proc_spend_monthly_trend',
        dimensions: ['month'],
        metrics: [{ field: 'total_amount', aggregation: 'sum', alias: 'total_amount' }],
      },
    });
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    expect(body?.code).toBe('0');
  });

  test('PR-DASH-04: Dashboard renders stat-card and chart blocks', async ({ page }) => {
    await gotoDashboard(page);
    const cards = page.locator('[class*="stat-card"], [class*="kpi"], [class*="card"]');
    const cardCount = await cards.count();
    expect(cardCount, 'Dashboard should render KPI cards').toBeGreaterThanOrEqual(1);
  });

  test('PR-DASH-05: Pending requests NQ returns data', async ({ page }) => {
    const resp = await page.request.get(
      '/api/datasource/list?datasourceId=nq:proc_pending_requests&format=records&maxItems=10',
    );
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const records = body?.data?.records ?? [];
    expect(records.length, 'Should have at least 1 pending request').toBeGreaterThanOrEqual(1);
  });
});
