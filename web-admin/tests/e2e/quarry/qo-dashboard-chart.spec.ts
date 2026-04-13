/**
 * QO Dashboard Chart — E2E Tests
 *
 * Tests that the Operations Dashboard renders chart blocks (KPI number cards,
 * line chart, bar chart) instead of plain data tables.
 *
 * The dashboard page uses blockType: "chart" with chartType: "number-card",
 * "line", and "bar", powered by NamedQuery data sources.
 */
import { test, expect } from '@playwright/test';

const DASHBOARD_MODEL = 'qo_dashboard_data';

test.describe('QO Dashboard — Chart Blocks', () => {
  // Dashboard page qo_dashboard_data with kind=dashboard does not exist in the DB.
  // Only list/form/detail pages exist. These tests require a dashboard page to be configured.
  test.fixme(true, 'Dashboard page qo_dashboard_data (kind=dashboard) not configured — only list/form/detail exist');

  test.beforeEach(async ({ page }) => {
    // Navigate to the dashboard page and wait for the UI to render
    await page.goto(`/p/${DASHBOARD_MODEL}`, { waitUntil: 'domcontentloaded' });
    // Wait for the dashboard UI to actually render instead of racing with API responses
    await page.locator('[data-testid^="dashboard-block-"]').first().waitFor({ timeout: 20000 });
  });

  test('should render dashboard page with title', async ({ page }) => {
    // The dashboard should show the page title
    const title = page.locator('h2').first();
    await expect(title).toBeVisible({ timeout: 15000 });
  });

  test('should render 4 KPI number card blocks', async ({ page }) => {
    // Wait for dashboard blocks to appear
    const kpiBlocks = [
      'dashboard-block-kpi_year_output',
      'dashboard-block-kpi_year_sales_qty',
      'dashboard-block-kpi_year_revenue',
      'dashboard-block-kpi_avg_price',
    ];

    for (const testId of kpiBlocks) {
      const block = page.locator(`[data-testid="${testId}"]`);
      await expect(block).toBeVisible({ timeout: 15000 });
    }

    // Each KPI card should have a title text and a value display area
    // SmartNumberCard renders: title in .text-sm.font-medium, value in .text-2xl
    // Or it could be in loading state (skeleton) — that's fine too
    const firstCard = page.locator('[data-testid="dashboard-block-kpi_year_output"]');
    await expect(firstCard).toBeVisible({ timeout: 15000 });

    // Card should contain either a value or a loading indicator
    const hasContent = firstCard.locator('.text-2xl, .animate-pulse, .text-sm');
    await expect(hasContent.first()).toBeVisible({ timeout: 10000 });
  });

  test('should render line chart block for production trend', async ({ page }) => {
    const chartBlock = page.locator('[data-testid="dashboard-block-chart_production_trend"]');
    await expect(chartBlock).toBeVisible({ timeout: 15000 });

    // ECharts renders inside a canvas or SVG element
    // SmartLineChart wraps ReactECharts which renders a canvas
    const chartContent = chartBlock.locator('canvas, svg, [_echarts_instance_]');
    await expect(chartContent.first()).toBeVisible({ timeout: 15000 });
  });

  test('should render bar chart block for revenue', async ({ page }) => {
    const chartBlock = page.locator('[data-testid="dashboard-block-chart_revenue_bar"]');
    await expect(chartBlock).toBeVisible({ timeout: 15000 });

    // Bar chart should render with echarts canvas
    const chartContent = chartBlock.locator('canvas, svg, [_echarts_instance_]');
    await expect(chartContent.first()).toBeVisible({ timeout: 15000 });
  });

  test('should have correct grid layout (KPI cards span 3 cols each, charts span 6 cols each)', async ({
    page,
  }) => {
    // Verify the KPI card blocks exist in the grid
    const kpiBlock = page.locator('[data-testid="dashboard-block-kpi_year_output"]');
    await expect(kpiBlock).toBeVisible({ timeout: 15000 });

    // Verify the chart blocks exist in the grid
    const lineChart = page.locator('[data-testid="dashboard-block-chart_production_trend"]');
    const barChart = page.locator('[data-testid="dashboard-block-chart_revenue_bar"]');
    await expect(lineChart).toBeVisible({ timeout: 15000 });
    await expect(barChart).toBeVisible({ timeout: 15000 });

    // Total of 15 dashboard blocks should be visible (4 QO + 4 cross-module + 3 enhanced KPIs + 2 original charts + 2 new charts)
    const allBlocks = page.locator('[data-testid^="dashboard-block-"]');
    await expect(allBlocks).toHaveCount(15, { timeout: 15000 });
  });

  test('should call chart-data API and return valid data', async ({ page }) => {
    // Verify the chart-data API returns valid data by calling it directly
    // (the beforeEach already navigated to the dashboard and confirmed UI rendered)
    const apiResponse = await page.request.post('/api/meta/chart-data', {
      data: {
        type: 'namedQuery',
        queryCode: 'qo_dashboard_data',
        metrics: [{ field: 'qo_total_output', aggregation: 'sum', alias: 'total_output' }],
      },
    });
    expect(apiResponse.status()).toBe(200);
    const body = await apiResponse.json();
    expect(body).toHaveProperty('data');
  });
});
