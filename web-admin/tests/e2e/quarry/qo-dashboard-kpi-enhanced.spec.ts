/**
 * QO Dashboard Enhanced KPIs — E2E Tests
 *
 * Tests the 3 new KPI number-cards and 2 new charts added to the quarry
 * operations dashboard.
 *
 * Dashboard page: /dynamic/qo_dashboard_data
 * New KPI blocks with drillDown:
 *   kpi_profit_rate       -> cc-profit-analysis
 *   kpi_cost_overrun      -> cc-cost-budget
 *   kpi_schedule_variance -> pm-schedule-deviation
 * New chart blocks:
 *   chart_safety_distribution — pie chart (safety issues by hazard level)
 *   chart_monthly_kpi         — bar chart (monthly output & sales)
 */
import { test, expect } from '@playwright/test';

const DASHBOARD_PAGE = 'qo_dashboard_data';

/**
 * Navigate to the dashboard and wait for data sources to load.
 */
async function gotoDashboard(page: import('@playwright/test').Page) {
  const dataLoaded = page.waitForResponse(
    (resp) =>
      (resp.url().includes('/api/meta/chart-data') ||
        resp.url().includes('/api/datasource/list')) &&
      resp.status() === 200,
    { timeout: 20000 },
  );
  await page.goto(`/dynamic/${DASHBOARD_PAGE}`, { waitUntil: 'domcontentloaded' });
  await dataLoaded;
}

test.describe('QO Dashboard Enhanced KPIs @smoke', () => {
  test('KPI-E-001: Dashboard renders all 3 new KPI blocks visible', async ({ page }) => {
    await gotoDashboard(page);

    const newKpiBlocks = [
      'kpi_profit_rate',
      'kpi_cost_overrun',
      'kpi_schedule_variance',
    ];

    for (const blockId of newKpiBlocks) {
      await expect(
        page.locator(`[data-testid="dashboard-block-${blockId}"]`),
      ).toBeVisible({ timeout: 15000 });
    }

    // New KPI cards with drillDown should have role="button" (clickable)
    for (const blockId of newKpiBlocks) {
      const card = page
        .locator(`[data-testid="dashboard-block-${blockId}"]`)
        .locator('[role="button"]');
      await expect(card).toBeVisible({ timeout: 5000 });
    }
  });

  test('KPI-E-002: Dashboard renders safety distribution pie chart', async ({ page }) => {
    await gotoDashboard(page);

    const chartBlock = page.locator(
      '[data-testid="dashboard-block-chart_safety_distribution"]',
    );
    await expect(chartBlock).toBeVisible({ timeout: 15000 });

    // Pie chart should render an SVG or canvas element inside the block
    const chartContent = chartBlock.locator('svg, canvas').first();
    await expect(chartContent).toBeVisible({ timeout: 10000 });
  });

  test('KPI-E-003: Dashboard renders monthly KPI bar chart', async ({ page }) => {
    await gotoDashboard(page);

    const chartBlock = page.locator(
      '[data-testid="dashboard-block-chart_monthly_kpi"]',
    );
    await expect(chartBlock).toBeVisible({ timeout: 15000 });

    // Bar chart should render an SVG or canvas element inside the block
    const chartContent = chartBlock.locator('svg, canvas').first();
    await expect(chartContent).toBeVisible({ timeout: 10000 });
  });

  test('KPI-E-004: Click profit rate KPI navigates to cc-profit-analysis', async ({ page }) => {
    await gotoDashboard(page);

    const profitBlock = page.locator(
      '[data-testid="dashboard-block-kpi_profit_rate"]',
    );
    await expect(profitBlock).toBeVisible({ timeout: 15000 });

    const clickable = profitBlock.locator('[role="button"]');
    await expect(clickable).toBeVisible({ timeout: 5000 });
    await clickable.click();

    // Should navigate to cc-profit-analysis
    await expect(page).toHaveURL(/\/dynamic\/cc-profit-analysis/, { timeout: 10000 });
  });

  test('KPI-E-005: Click cost overrun KPI navigates to cc-cost-budget', async ({ page }) => {
    await gotoDashboard(page);

    const costBlock = page.locator(
      '[data-testid="dashboard-block-kpi_cost_overrun"]',
    );
    await expect(costBlock).toBeVisible({ timeout: 15000 });

    const clickable = costBlock.locator('[role="button"]');
    await expect(clickable).toBeVisible({ timeout: 5000 });
    await clickable.click();

    // Should navigate to cc-cost-budget
    await expect(page).toHaveURL(/\/dynamic\/cc-cost-budget/, { timeout: 10000 });
  });

  test('KPI-E-006: Click schedule variance KPI navigates to pm-schedule-deviation', async ({ page }) => {
    await gotoDashboard(page);

    const scheduleBlock = page.locator(
      '[data-testid="dashboard-block-kpi_schedule_variance"]',
    );
    await expect(scheduleBlock).toBeVisible({ timeout: 15000 });

    const clickable = scheduleBlock.locator('[role="button"]');
    await expect(clickable).toBeVisible({ timeout: 5000 });
    await clickable.click();

    // Should navigate to pm-schedule-deviation
    await expect(page).toHaveURL(/\/dynamic\/pm-schedule-deviation/, { timeout: 10000 });
  });

  test('KPI-E-007: Total dashboard blocks count >= 13', async ({ page }) => {
    await gotoDashboard(page);

    // The dashboard should have at least 13 blocks (original 10 + 3 new KPIs + 2 new charts = 15)
    // We use >= 13 as a minimum threshold to account for any blocks that may
    // not render when data is empty.
    const allBlocks = page.locator('[data-testid^="dashboard-block-"]');
    await expect(allBlocks.first()).toBeVisible({ timeout: 15000 });

    const blockCount = await allBlocks.count();
    expect(blockCount).toBeGreaterThanOrEqual(13);
  });
});
