/**
 * Quality Dashboard E2E Tests
 *
 * Validates the Quality Dashboard page:
 * - Menu navigation: Dashboard accessible via Quality sidebar
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

test.describe('Quality Dashboard @smoke', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60000);

  const uid = uniqueId('QCDash');

  // =========================================================================
  // DATA SETUP
  // =========================================================================
  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      // Create IQC order (incoming quality check)
      await executeCommandViaApi(
        page,
        'qc:create_iqc_order',
        {
          qc_iqc_supplier_id: `Supplier_${uid}`,
          qc_iqc_material_id: `MAT_${uid}`,
          qc_iqc_material_name: `Material_${uid}`,
          qc_iqc_qty_received: '100',
          qc_iqc_qty_inspected: '100',
          qc_iqc_qty_accepted: '95',
          qc_iqc_qty_rejected: '5',
          qc_iqc_date: todayStr(),
          qc_iqc_remark: `E2E test ${uid}`,
        },
        undefined,
        'create',
      );

      // Create defect record (needs product_id, source_type, etc.)
      await executeCommandViaApi(
        page,
        'qc:create_defect_record',
        {
          qc_dr_defect_type: 'dimension',
          qc_dr_severity: 'major',
          qc_dr_source_type: 'iqc',
          qc_dr_product_id: `PROD_${uid}`,
          qc_dr_description: `Defect test ${uid}`,
          qc_dr_detected_date: todayStr(),
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

    // Quality menu uses "质量管理" or "Quality"
    const menuBtn = page.locator('button', { hasText: /质量管理|Quality/ }).first();
    await menuBtn.waitFor({ state: 'visible', timeout: 10000 });
    await menuBtn.click();

    const dashLink = page.locator('a[href="/quality/quality-dashboard"]');
    await dashLink.first().waitFor({ state: 'attached', timeout: 5000 });
    await dashLink.first().evaluate((el: HTMLElement) => el.click());

    await expect(page).toHaveURL(/\/quality\/quality-dashboard/, { timeout: 10000 });

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

  test('QC-DASH-01: Dashboard is accessible via Quality sidebar menu', async ({ page }) => {
    await gotoDashboard(page);
    const title = page.locator('text=质量仪表盘').or(page.locator('text=Quality Dashboard'));
    await expect(title.first()).toBeVisible({ timeout: 10000 });
  });

  test('QC-DASH-02: KPI NQ returns data with inspection counts', async ({ page }) => {
    const resp = await page.request.get(
      '/api/datasource/list?datasourceId=nq:qa_dashboard_kpi&format=records&maxItems=1',
    );
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const records = body?.data?.records ?? [];
    expect(records.length, 'KPI NQ should return 1 row').toBe(1);

    const kpi = records[0];
    expect(Number(kpi.total_inspections), 'Should have at least 1 inspection').toBeGreaterThanOrEqual(1);
  });

  test('QC-DASH-03: Pass rate trend NQ is queryable via chart-data API', async ({ page }) => {
    const resp = await page.request.post('/api/meta/chart-data', {
      data: {
        type: 'namedQuery',
        queryCode: 'qa_pass_rate_monthly_trend',
        dimensions: ['month'],
        metrics: [
          { field: 'iqc_pass_rate', aggregation: 'sum', alias: 'iqc_pass_rate' },
          { field: 'fqc_pass_rate', aggregation: 'sum', alias: 'fqc_pass_rate' },
        ],
      },
    });
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    expect(body?.code).toBe('0');
    expect(body?.data?.rows?.length, 'Should have monthly data').toBeGreaterThanOrEqual(1);
  });

  test('QC-DASH-04: Defect type NQ returns seeded data', async ({ page }) => {
    const resp = await page.request.get(
      '/api/datasource/list?datasourceId=nq:qa_defect_by_type&format=records&maxItems=10',
    );
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const records = body?.data?.records ?? [];
    expect(records.length, 'Should have at least 1 defect type').toBeGreaterThanOrEqual(1);
  });

  test('QC-DASH-05: Dashboard renders stat-card and data-table blocks', async ({ page }) => {
    await gotoDashboard(page);

    const cards = page.locator('[class*="stat-card"], [class*="kpi"], [class*="card"]');
    const cardCount = await cards.count();
    expect(cardCount, 'Dashboard should render KPI cards').toBeGreaterThanOrEqual(1);

    const tables = page.locator('table, [role="table"]');
    const tableCount = await tables.count();
    expect(tableCount, 'Dashboard should render data tables').toBeGreaterThanOrEqual(1);
  });

  test('QC-DASH-06: IQC inspection result NQ returns data', async ({ page }) => {
    const resp = await page.request.get(
      '/api/datasource/list?datasourceId=nq:qa_inspection_status_stats&format=records&maxItems=10',
    );
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const records = body?.data?.records ?? [];
    expect(records.length, 'Should have at least 1 result status').toBeGreaterThanOrEqual(1);
  });
});
