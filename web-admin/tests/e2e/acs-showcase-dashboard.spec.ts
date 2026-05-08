/**
 * ACP Showcase Dashboard — E2E Test
 *
 * Verifies the redesigned acs_dashboard (15 widgets) renders correctly:
 * - All widget containers present via data-widget-id
 * - KPI cards show numeric values (not empty/placeholder)
 * - Pipeline SVG has all 6 data-layer nodes (L0-L5)
 * - Chart widgets render at least one data shape
 * - Recent logs table renders (0..10 rows)
 * - No $i18n: key leaks
 * - CTA strip routes to demo request create form
 *
 * Authentication: storageState (global-setup.ts) — no login in beforeEach.
 *
 * @since 10.3.0
 */

import { test, expect, type Page } from '../fixtures';

// ---------------------------------------------------------------------------
// Widget inventory — must match acs_dashboard.json exactly
// ---------------------------------------------------------------------------
const WIDGET_IDS = [
  'hero',
  'kpi_total_requests',
  'kpi_success_rate',
  'kpi_avg_duration',
  'kpi_safety_triggers',
  'pipeline_diagram',
  'cta_strip',
  'chart_status',
  'chart_risk',
  'chart_category',
  'chart_safety_trend',
  'kpi_pending_approvals',
  'kpi_total_cost',
  'recent_logs',
  'footer_guide',
] as const;

const KPI_WIDGET_IDS = [
  'kpi_total_requests',
  'kpi_success_rate',
  'kpi_avg_duration',
  'kpi_safety_triggers',
  'kpi_pending_approvals',
  'kpi_total_cost',
] as const;

// ---------------------------------------------------------------------------
// Navigation helper — uses ?code= query param (canonical URL).
// Sidebar nav was attempted earlier but the dashboard's i18n-resolved tab
// title varies by locale, making text-matching brittle. The /dashboards?code=
// pattern is the documented entry-point and is what dashboard tabs link to.
// ---------------------------------------------------------------------------
async function navigateToAcsDashboard(page: Page): Promise<void> {
  await page.goto('/dashboards?code=acs_dashboard', { waitUntil: 'domcontentloaded' });
  // Wait for the hero widget to appear (first widget in the layout)
  await expect(page.locator('[data-widget-id="hero"]')).toBeVisible({ timeout: 20_000 });
}

// ---------------------------------------------------------------------------
// Test suite
// ---------------------------------------------------------------------------
test.describe('ACP Showcase Dashboard', () => {
  test.setTimeout(90_000);

  test('renders all 15 widgets with real data and CTA flow', async ({ page }) => {
    await navigateToAcsDashboard(page);

    // --- 1. All 15 widget containers present (data-widget-id wrapper from WidgetRenderer)
    for (const id of WIDGET_IDS) {
      await expect(
        page.locator(`[data-widget-id="${id}"]`),
        `widget "${id}" should be visible`,
      ).toBeVisible({ timeout: 15_000 });
    }

    // --- 2. KPI cards show a numeric value (not '--' / empty / loading)
    for (const kpiId of KPI_WIDGET_IDS) {
      // Poll until the chart-data fetch resolves and the loading skeleton is gone.
      // SmartNumberCard renders "Loading..." while the request is in-flight and
      // a formatted numeric value once data arrives.
      await expect(
        page.locator(`[data-widget-id="${kpiId}"]`),
        `${kpiId} should render a numeric value (not stuck on Loading)`,
      ).toContainText(/\d/, { timeout: 20_000 });
    }

    // --- 3. Pipeline SVG: all 6 layer nodes visible (data-layer attr set in SVG content)
    for (const layer of ['L5', 'L4', 'L3', 'L2', 'L1', 'L0'] as const) {
      await expect(
        page.locator(`[data-widget-id="pipeline_diagram"] [data-layer="${layer}"]`),
        `pipeline layer ${layer} should be visible`,
      ).toBeVisible({ timeout: 10_000 });
    }

    // --- 4. Charts: ECharts renders to <canvas>; assert canvas presence + non-zero size.
    //         (We can't introspect bar/sector internals via DOM with canvas renderer.
    //          Data-correctness is covered by backend integration tests.)
    for (const chartId of ['chart_status', 'chart_risk', 'chart_category', 'chart_safety_trend']) {
      const canvas = page.locator(`[data-widget-id="${chartId}"] canvas`).first();
      await expect(canvas, `${chartId} should render an ECharts canvas`).toBeVisible({ timeout: 15_000 });
      const size = await canvas.boundingBox();
      expect(size?.width ?? 0, `${chartId} canvas width`).toBeGreaterThan(0);
      expect(size?.height ?? 0, `${chartId} canvas height`).toBeGreaterThan(0);
    }

    // --- 5. Recent logs table: 0..10 rows (empty state is valid when DB has no data)
    const rowCount = await page
      .locator('[data-widget-id="recent_logs"] tbody tr')
      .count();
    expect(rowCount, 'recent_logs row count should be 0–10').toBeLessThanOrEqual(10);

    // --- 6. No $i18n: key leaks anywhere on the page
    const bodyText = await page.locator('body').textContent();
    expect(bodyText, 'no $i18n: keys should be visible on page').not.toMatch(/\$i18n:/);

    // --- 7. CTA strip: "Run a Demo Request" routes to the create form (i18n verified end-to-end)
    await page.locator('[data-widget-id="cta_strip"]')
      .getByRole('link', { name: /Run a Demo Request|运行一次 Demo 请求/i })
      .click();
    await expect(page).toHaveURL(/\/p\/acs_demo_request\/new/, { timeout: 10_000 });
    // Verify model field i18n resolves on the form (acs_req_title is in acs_demo_request_form.json)
    await expect(
      page.getByLabel(/Request Title|请求标题/i),
      'create form should render with resolved i18n label for acs_req_title',
    ).toBeVisible({ timeout: 15_000 });
  });
});
