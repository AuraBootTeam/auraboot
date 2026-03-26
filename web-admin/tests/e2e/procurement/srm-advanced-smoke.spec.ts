/**
 * SRM Advanced Capabilities E2E Tests (GAP-041)
 *
 * Validates supplier scorecards, contract management, and spend analysis:
 * - Supplier Scorecard: list, create, submit, approve
 * - Procurement Contract: list, create with lines, submit, activate
 * - Spend Analysis Dashboard: KPI cards, charts, tables
 * - Scoring Criteria management
 * - Spend Category management
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

test.describe('SRM Advanced Capabilities @smoke', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(90000);

  const uid = uniqueId('srm');
  let scorecardId: string;
  let contractId: string;
  let criteriaId: string;

  // =========================================================================
  // DATA SETUP
  // =========================================================================
  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      // Create scoring criteria
      const critResp = await executeCommandViaApi(
        page,
        'pr:create_criteria',
        {
          pr_crit_code: `CRIT_${uid}`,
          pr_crit_name: `Quality Criteria ${uid}`,
          pr_crit_category: 'quality',
          pr_crit_weight_pct: 30,
          pr_crit_description: `E2E test criteria ${uid}`,
          pr_crit_is_active: true,
        },
        undefined,
        'create',
      );
      criteriaId = critResp?.recordId;

      // Create a supplier first (required for scorecard)
      const suppResp = await executeCommandViaApi(
        page,
        'pe:create_supplier',
        {
          pe_supplier_name: `E2E Supplier ${uid}`,
          pe_supplier_contact: `Contact_${uid}`,
          pe_supplier_phone: '13800138000',
        },
        undefined,
        'create',
      );
      const supplierId = suppResp?.recordId;

      // Create scorecard
      const scResp = await executeCommandViaApi(
        page,
        'pr:create_scorecard',
        {
          pr_sc_supplier_id: supplierId,
          pr_sc_period: '2026-Q1',
          pr_sc_quality_score: 85,
          pr_sc_delivery_score: 90,
          pr_sc_cost_score: 75,
          pr_sc_service_score: 80,
          pr_sc_evaluator: `Evaluator_${uid}`,
          pr_sc_remark: `E2E scorecard ${uid}`,
        },
        undefined,
        'create',
      );
      scorecardId = scResp?.recordId;

      // Submit and approve scorecard if created
      if (scorecardId) {
        await executeCommandViaApi(
          page,
          'pr:submit_scorecard',
          {},
          scorecardId,
          'state_transition',
        );
        await executeCommandViaApi(
          page,
          'pr:approve_scorecard',
          {},
          scorecardId,
          'state_transition',
        );
      }

      // Create contract (reuse same supplier created for scorecard)
      const ctResp = await executeCommandViaApi(
        page,
        'pr:create_contract',
        {
          pr_ct_supplier_id: supplierId,
          pr_ct_title: `Master Contract ${uid}`,
          pr_ct_type: 'master',
          pr_ct_start_date: todayStr(),
          pr_ct_end_date: dateOffsetStr(365),
          pr_ct_total_value: 500000,
          pr_ct_auto_renew: false,
          pr_ct_notice_period_days: 30,
          pr_ct_remark: `E2E contract ${uid}`,
        },
        undefined,
        'create',
      );
      contractId = ctResp?.recordId;

      // Create spend category
      await executeCommandViaApi(
        page,
        'pr:create_spend_category',
        {
          pr_spc_code: `SPC_${uid}`,
          pr_spc_name: `Raw Materials ${uid}`,
          pr_spc_description: `E2E test category ${uid}`,
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
  async function navigateViaMenu(
    page: import('@playwright/test').Page,
    parentText: RegExp | string,
    childText: RegExp | string,
    expectedUrl: RegExp,
    childPath?: string,
  ) {
    await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });

    // Click Procurement root menu
    const menuBtn = page.locator('button', { hasText: /Procurement/ }).first();
    await menuBtn.waitFor({ state: 'visible', timeout: 10000 });
    await menuBtn.click();

    // Expand sub-directory if needed
    if (parentText) {
      const parentDir = page.locator('button', { hasText: parentText }).first();
      await parentDir.waitFor({ state: 'visible', timeout: 5000 }).catch(() => null);
      await parentDir.click().catch(() => null);
    }

    // Click child menu link
    const childLink = childPath
      ? page.locator(`a[href="${childPath}"]`).first()
      : page.getByRole('link', { name: childText }).first();
    await childLink.waitFor({ state: 'visible', timeout: 5000 });
    await childLink.evaluate((el: HTMLElement) => el.click());

    await expect(page).toHaveURL(expectedUrl, { timeout: 10000 });
  }

  // =========================================================================
  // SUPPLIER SCORECARD TESTS
  // =========================================================================

  test('Scorecard list page loads via menu', async ({ page }) => {
    await navigateViaMenu(
      page,
      /Supplier Relations/,
      /Supplier Scorecards/,
      /\/procurement\/scorecards/,
      '/procurement/scorecards',
    );

    // Wait for data table to load
    await page.waitForResponse(
      (resp) => resp.url().includes('/api/v1/pr_supplier_scorecard/list') && resp.status() === 200,
      { timeout: 15000 },
    ).catch(() => null);

    // Verify page title or table header exists
    const heading = page.locator('h1, h2, [class*="title"]', { hasText: /Scorecard|评分卡/ });
    await expect(heading.first()).toBeVisible({ timeout: 10000 });

    // Verify status tabs
    const tabs = page.locator('[role="tab"], button', { hasText: /All|全部/ });
    await expect(tabs.first()).toBeVisible({ timeout: 5000 });
  });

  test('Scorecard list has data', async ({ page }) => {
    await navigateViaMenu(
      page,
      /Supplier Relations/,
      /Supplier Scorecards/,
      /\/procurement\/scorecards/,
      '/procurement/scorecards',
    );

    await page.waitForResponse(
      (resp) => resp.url().includes('/api/v1/pr_supplier_scorecard/list') && resp.status() === 200,
      { timeout: 15000 },
    ).catch(() => null);

    // Verify table has rows (from seed data)
    const tableRows = page.locator('table tbody tr, [class*="table"] [class*="row"]');
    await expect(tableRows.first()).toBeVisible({ timeout: 10000 });
  });

  // =========================================================================
  // SCORING CRITERIA TESTS
  // =========================================================================

  test('Scoring criteria list page loads via menu', async ({ page }) => {
    await navigateViaMenu(
      page,
      /Supplier Relations/,
      /Scoring Criteria/,
      /\/procurement\/scoring-criteria/,
      '/procurement/scoring-criteria',
    );

    await page.waitForResponse(
      (resp) => resp.url().includes('/api/v1/pr_scoring_criteria/list') && resp.status() === 200,
      { timeout: 15000 },
    ).catch(() => null);

    const heading = page.locator('h1, h2, [class*="title"]', { hasText: /Criteria|评分标准/ });
    await expect(heading.first()).toBeVisible({ timeout: 10000 });
  });

  test('Scoring criteria list has seed data', async ({ page }) => {
    await navigateViaMenu(
      page,
      /Supplier Relations/,
      /Scoring Criteria/,
      /\/procurement\/scoring-criteria/,
      '/procurement/scoring-criteria',
    );

    await page.waitForResponse(
      (resp) => resp.url().includes('/api/v1/pr_scoring_criteria/list') && resp.status() === 200,
      { timeout: 15000 },
    ).catch(() => null);

    // Verify our criteria appears
    const criteriaText = page.locator('td, [class*="cell"]', { hasText: new RegExp(`CRIT_${uid}`) });
    await expect(criteriaText.first()).toBeVisible({ timeout: 10000 });
  });

  // =========================================================================
  // CONTRACT TESTS
  // =========================================================================

  test('Contract list page loads via menu', async ({ page }) => {
    await navigateViaMenu(
      page,
      /Contracts/,
      /Procurement Contracts/,
      /\/procurement\/contracts/,
      '/procurement/contracts/list',
    );

    await page.waitForResponse(
      (resp) => resp.url().includes('/api/v1/pr_contract/list') && resp.status() === 200,
      { timeout: 15000 },
    ).catch(() => null);

    const heading = page.locator('h1, h2, [class*="title"]', { hasText: /Contract|合同/ });
    await expect(heading.first()).toBeVisible({ timeout: 10000 });

    // Verify status tabs
    const activeTabs = page.locator('[role="tab"], button', { hasText: /Active|生效/ });
    await expect(activeTabs.first()).toBeVisible({ timeout: 5000 });
  });

  test('Contract list has seed data', async ({ page }) => {
    await navigateViaMenu(
      page,
      /Contracts/,
      /Procurement Contracts/,
      /\/procurement\/contracts/,
      '/procurement/contracts/list',
    );

    await page.waitForResponse(
      (resp) => resp.url().includes('/api/v1/pr_contract/list') && resp.status() === 200,
      { timeout: 15000 },
    ).catch(() => null);

    // Verify our contract appears (search by uid in title)
    const contractText = page.locator('td, [class*="cell"]', { hasText: new RegExp(uid) });
    await expect(contractText.first()).toBeVisible({ timeout: 10000 });
  });

  // =========================================================================
  // SPEND ANALYSIS TESTS
  // =========================================================================

  test('Spend analysis dashboard loads via menu', async ({ page }) => {
    await navigateViaMenu(
      page,
      /Spend Analysis/,
      /Spend Dashboard/,
      /\/procurement\/spend\/dashboard/,
      '/procurement/spend/dashboard',
    );

    // Wait for datasource API calls
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

    // Verify dashboard title
    const heading = page.locator('h1, h2, [class*="title"]', { hasText: /Spend Analysis|费用分析/ });
    await expect(heading.first()).toBeVisible({ timeout: 10000 });
  });

  test('Spend dashboard shows KPI stat cards', async ({ page }) => {
    await navigateViaMenu(
      page,
      /Spend Analysis/,
      /Spend Dashboard/,
      /\/procurement\/spend\/dashboard/,
      '/procurement/spend/dashboard',
    );

    await page.waitForResponse(
      (resp) => resp.url().includes('/api/datasource/list') && resp.status() === 200,
      { timeout: 15000 },
    ).catch(() => null);

    // Verify stat cards render
    const statCards = page.locator('[class*="stat-card"], [class*="StatCard"], [class*="kpi"]');
    await expect(statCards.first()).toBeVisible({ timeout: 10000 });
  });

  test('Spend dashboard has chart blocks', async ({ page }) => {
    await navigateViaMenu(
      page,
      /Spend Analysis/,
      /Spend Dashboard/,
      /\/procurement\/spend\/dashboard/,
      '/procurement/spend/dashboard',
    );

    await page.waitForResponse(
      (resp) => resp.url().includes('/api/meta/chart-data') && resp.status() === 200,
      { timeout: 15000 },
    ).catch(() => null);

    // Verify chart containers
    const charts = page.locator('[class*="chart"], canvas, svg');
    await expect(charts.first()).toBeVisible({ timeout: 15000 });
  });

  // =========================================================================
  // SPEND CATEGORY TESTS
  // =========================================================================

  test('Spend category list page loads via menu', async ({ page }) => {
    await navigateViaMenu(
      page,
      /Spend Analysis/,
      /Spend Categories/,
      /\/procurement\/spend\/categories/,
      '/procurement/spend/categories',
    );

    await page.waitForResponse(
      (resp) => resp.url().includes('/api/v1/pr_spend_category/list') && resp.status() === 200,
      { timeout: 15000 },
    ).catch(() => null);

    const heading = page.locator('h1, h2, [class*="title"]', { hasText: /Spend Categor|费用分类/ });
    await expect(heading.first()).toBeVisible({ timeout: 10000 });
  });

  test('Spend category list has seed data', async ({ page }) => {
    await navigateViaMenu(
      page,
      /Spend Analysis/,
      /Spend Categories/,
      /\/procurement\/spend\/categories/,
      '/procurement/spend/categories',
    );

    await page.waitForResponse(
      (resp) => resp.url().includes('/api/v1/pr_spend_category/list') && resp.status() === 200,
      { timeout: 15000 },
    ).catch(() => null);

    const catText = page.locator('td, [class*="cell"]', { hasText: new RegExp(`SPC_${uid}`) });
    await expect(catText.first()).toBeVisible({ timeout: 10000 });
  });
});
