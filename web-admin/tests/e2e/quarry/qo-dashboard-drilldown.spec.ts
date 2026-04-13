/**
 * QO Dashboard KPI Drill-Down — E2E Tests
 *
 * Tests that clicking KPI number cards on the Operations Dashboard navigates
 * to the target page with correct filter_* URL params (drill-down with
 * paramMapping).
 *
 * Dashboard page: /p/qo_dashboard_data
 * KPI blocks with drillDown + paramMapping:
 *   kpi_contract_total  -> cc-contract   ?filter_cc_contract_status=active
 *   kpi_contract_count  -> cc-contract   ?filter_cc_contract_status=active
 *   kpi_safety_issues   -> dp-issue      ?filter_dp_issue_status=open
 *   kpi_quality_checks  -> qm-checkpoint ?filter_qm_check_status=completed
 */
import { test, expect } from '@playwright/test';

const DASHBOARD_PAGE = 'qo_dashboard_data';

/**
 * Navigate to the dashboard and wait for it to render.
 */
async function gotoDashboard(page: import('@playwright/test').Page) {
  await page.goto(`/p/${DASHBOARD_PAGE}`, { waitUntil: 'domcontentloaded' });
  // Wait for the dashboard UI to actually render instead of racing with API responses
  await page.locator('[data-testid^="dashboard-block-"]').first().waitFor({ timeout: 20000 });
}

test.describe('QO Dashboard KPI Drill-Down @smoke', () => {
  // Dashboard page qo_dashboard_data with kind=dashboard does not exist in the DB.
  // Only list/form/detail pages exist. These tests require a dashboard page to be configured.
  test.fixme(true, 'Dashboard page qo_dashboard_data (kind=dashboard) not configured — only list/form/detail exist');

  test('DD-001: Dashboard renders all KPI blocks with drill-down', async ({ page }) => {
    await gotoDashboard(page);

    // Verify the 4 cross-module KPI blocks are visible
    const kpiBlocks = [
      'kpi_contract_total',
      'kpi_contract_count',
      'kpi_safety_issues',
      'kpi_quality_checks',
    ];
    for (const blockId of kpiBlocks) {
      await expect(page.locator(`[data-testid="dashboard-block-${blockId}"]`)).toBeVisible({
        timeout: 15000,
      });
    }

    // KPI cards with drillDown should have role="button" (clickable)
    for (const blockId of kpiBlocks) {
      const card = page
        .locator(`[data-testid="dashboard-block-${blockId}"]`)
        .locator('[role="button"]');
      await expect(card).toBeVisible({ timeout: 5000 });
    }
  });

  test('DD-002: Click contract total KPI navigates to cc-contract with filter', async ({
    page,
  }) => {
    await gotoDashboard(page);

    const contractBlock = page.locator('[data-testid="dashboard-block-kpi_contract_total"]');
    await expect(contractBlock).toBeVisible({ timeout: 15000 });

    // Click the KPI card (the clickable element inside)
    const clickable = contractBlock.locator('[role="button"]');
    await expect(clickable).toBeVisible({ timeout: 5000 });
    await clickable.click();

    // Should navigate to cc-contract with filter_cc_contract_status=active
    await expect(page).toHaveURL(/\/dynamic\/cc-contract/, { timeout: 10000 });
    await expect(page).toHaveURL(/filter_cc_contract_status=active/);
  });

  test('DD-003: Click contract count KPI navigates to cc-contract with filter', async ({
    page,
  }) => {
    await gotoDashboard(page);

    const contractCountBlock = page.locator('[data-testid="dashboard-block-kpi_contract_count"]');
    await expect(contractCountBlock).toBeVisible({ timeout: 15000 });

    const clickable = contractCountBlock.locator('[role="button"]');
    await expect(clickable).toBeVisible({ timeout: 5000 });
    await clickable.click();

    // Same target page as contract total, same filter
    await expect(page).toHaveURL(/\/dynamic\/cc-contract/, { timeout: 10000 });
    await expect(page).toHaveURL(/filter_cc_contract_status=active/);
  });

  test('DD-004: Click safety issues KPI navigates to dp-issue with filter', async ({ page }) => {
    await gotoDashboard(page);

    const safetyBlock = page.locator('[data-testid="dashboard-block-kpi_safety_issues"]');
    await expect(safetyBlock).toBeVisible({ timeout: 15000 });

    const clickable = safetyBlock.locator('[role="button"]');
    await expect(clickable).toBeVisible({ timeout: 5000 });
    await clickable.click();

    // Should navigate to dp-issue with filter_dp_issue_status=open
    await expect(page).toHaveURL(/\/dynamic\/dp-issue/, { timeout: 10000 });
    await expect(page).toHaveURL(/filter_dp_issue_status=open/);
  });

  test('DD-005: Click quality checks KPI navigates to qm-checkpoint with filter', async ({
    page,
  }) => {
    await gotoDashboard(page);

    const qualityBlock = page.locator('[data-testid="dashboard-block-kpi_quality_checks"]');
    await expect(qualityBlock).toBeVisible({ timeout: 15000 });

    const clickable = qualityBlock.locator('[role="button"]');
    await expect(clickable).toBeVisible({ timeout: 5000 });
    await clickable.click();

    // Should navigate to qm-checkpoint with filter_qm_check_status=completed
    await expect(page).toHaveURL(/\/dynamic\/qm-checkpoint/, { timeout: 10000 });
    await expect(page).toHaveURL(/filter_qm_check_status=completed/);
  });

  test('DD-006: Target page dp-issue loads with URL filter applied', async ({ page }) => {
    test.setTimeout(30000);
    // dp-issue is always published in the test environment (dual-prevention plugin).
    // Navigate directly with filter param (simulating drill-down landing).
    await page.goto('/p/dp_issue?filter_dp_issue_status=open', {
      waitUntil: 'domcontentloaded',
    });

    // Verify the page loaded with table content
    await expect(page.locator('table, [role="table"]').first()).toBeVisible({ timeout: 20000 });

    // The URL should still contain the filter param
    await expect(page).toHaveURL(/filter_dp_issue_status=open/);
  });

  test('DD-007: End-to-end drill-down from dashboard to dp-issue with filter', async ({ page }) => {
    test.setTimeout(30000);

    // Start on the dashboard
    await gotoDashboard(page);

    // Click the safety issues KPI to navigate to dp-issue
    const safetyBlock = page.locator('[data-testid="dashboard-block-kpi_safety_issues"]');
    await expect(safetyBlock).toBeVisible({ timeout: 15000 });

    const clickable = safetyBlock.locator('[role="button"]');
    await clickable.click();

    // Wait for navigation to dp-issue with filter param
    await expect(page).toHaveURL(/\/dynamic\/dp-issue/, { timeout: 10000 });
    await expect(page).toHaveURL(/filter_dp_issue_status=open/);

    // Wait for the target page to load (table content)
    await expect(page.locator('table, [role="table"]').first()).toBeVisible({ timeout: 15000 });

    // Verify we are on the dp-issue page with filter still in URL
    const url = page.url();
    expect(url).toContain('filter_dp_issue_status=open');
  });

  test('DD-008: Original QO KPI cards also have drill-down navigation', async ({ page }) => {
    await gotoDashboard(page);

    // The original 4 QO KPI cards also have drillDown (without paramMapping)
    const qoKpiBlocks = [
      'kpi_year_output',
      'kpi_year_sales_qty',
      'kpi_year_revenue',
      'kpi_avg_price',
    ];

    for (const blockId of qoKpiBlocks) {
      const block = page.locator(`[data-testid="dashboard-block-${blockId}"]`);
      await expect(block).toBeVisible({ timeout: 15000 });

      // These cards also have drillDown enabled, so they should be clickable
      const clickable = block.locator('[role="button"]');
      await expect(clickable).toBeVisible({ timeout: 5000 });
    }
  });
});
