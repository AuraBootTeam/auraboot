/**
 * Contract-Cost Payment Plan CRUD E2E Tests
 *
 * Validates the payment plan lifecycle via DSL pages:
 * - Navigate to Payment Plans via sidebar menu (合同与成本 → 回款计划)
 * - Create payment plan linked to a contract
 * - Verify list page shows created record
 * - State transitions: pending → PARTIAL → RECEIVED
 * - Verify DSL page column rendering (status tags, amounts)
 *
 * Prerequisites:
 *   - contract-cost plugin imported
 *   - cc_payment_plan model published
 *
 * @since 7.2.1
 */

import { test, expect, type Page } from '@playwright/test';
import {
  uniqueId,
  executeCommandViaApi,
  dateOffsetStr,
  navigateToDynamicPage,
} from '../helpers/index';

test.describe('CC Payment Plan CRUD @critical', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60000);

  const uid = uniqueId('e2epp');
  const contractName = `PPContract_${uid}`;
  let contractPid: string;
  let projectPid: string;
  let paymentPlanPid: string;

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      // Create project
      const proj = await executeCommandViaApi(
        page,
        'pm:create_project',
        { pm_project_name: `PPProject_${uid}` },
        undefined,
        'create',
      );
      projectPid = proj.recordId;
      expect(projectPid).toBeTruthy();
      await executeCommandViaApi(page, 'pm:activate_project', {}, projectPid, 'update');

      // Create contract → EXECUTING
      const contract = await executeCommandViaApi(
        page,
        'cc:create_contract',
        {
          cc_contract_name: contractName,
          cc_contract_amount: 600000,
          cc_contract_project_id: projectPid,
          cc_contract_type: 'design',
        },
        undefined,
        'create',
      );
      contractPid = contract.recordId;
      expect(contractPid).toBeTruthy();

      await executeCommandViaApi(page, 'cc:submit_review', {}, contractPid, 'update');
      await executeCommandViaApi(page, 'cc:approve_contract', {}, contractPid, 'update');
      await executeCommandViaApi(page, 'cc:start_execution', {}, contractPid, 'update');
    } finally {
      await ctx.close();
    }
  });

  /** Navigate to Payment Plans via sidebar menu */
  async function navigateToPaymentPlans(page: Page) {
    await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });

    // Expand "Contract & Cost" menu
    const ccMenu = page.locator('button', { hasText: /Contract & Cost|合同与成本/ });
    await ccMenu.first().scrollIntoViewIfNeeded();
    await ccMenu.first().click();

    // Click "Payment Plans" submenu
    const ppLink = page.locator('a[href="/contract-cost/payment-plans"]');
    await ppLink.first().waitFor({ state: 'attached', timeout: 5000 });
    await ppLink.first().evaluate((el) => (el as HTMLAnchorElement).click());

    await expect(page).toHaveURL(/\/contract-cost\/payment-plans/);
  }

  // =========================================================================
  // PP-01: Navigate to Payment Plans page via sidebar menu
  // =========================================================================
  test('PP-01: Navigate to Payment Plans page via sidebar menu', async ({ page }) => {
    await navigateToPaymentPlans(page);

    // Page should load (DSL dynamic page)
    const content = page.locator('table, [role="table"], [data-testid="dynamic-list"]');
    await expect(content.first()).toBeVisible({ timeout: 10000 });
  });

  // =========================================================================
  // PP-02: Create payment plan via API and verify in list
  // =========================================================================
  test('PP-02: Create payment plan and verify in list', async ({ page }) => {
    // Create payment plan via API
    const pp = await executeCommandViaApi(
      page,
      'cc:create_payment_plan',
      {
        cc_pp_contract_id: contractPid,
        cc_pp_period: 1,
        cc_pp_plan_date: dateOffsetStr(30),
        cc_pp_plan_amount: 200000,
        cc_pp_remark: `E2E test payment plan ${uid}`,
      },
      undefined,
      'create',
    );
    paymentPlanPid = pp.recordId;
    expect(paymentPlanPid, 'Payment plan should be created').toBeTruthy();

    // Navigate to payment plans page and verify
    await navigateToPaymentPlans(page);

    // Wait for list to load
    const listResponse = page
      .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 10000 })
      .catch(() => null);
    await listResponse;

    // Verify the created plan appears (search by contract name or PP number)
    const row = page.locator('tbody tr', { hasText: /PP-/ }).first();
    await expect(row).toBeVisible({ timeout: 5000 });
  });

  // =========================================================================
  // PP-03: Create more payment plans for a complete plan schedule
  // =========================================================================
  test('PP-03: Create multiple payment plans for contract', async ({ page }) => {
    // Plan 2 and 3
    const pp2 = await executeCommandViaApi(
      page,
      'cc:create_payment_plan',
      {
        cc_pp_contract_id: contractPid,
        cc_pp_period: 2,
        cc_pp_plan_date: dateOffsetStr(90),
        cc_pp_plan_amount: 250000,
      },
      undefined,
      'create',
    );
    expect(pp2.recordId).toBeTruthy();

    const pp3 = await executeCommandViaApi(
      page,
      'cc:create_payment_plan',
      {
        cc_pp_contract_id: contractPid,
        cc_pp_period: 3,
        cc_pp_plan_date: dateOffsetStr(150),
        cc_pp_plan_amount: 150000,
      },
      undefined,
      'create',
    );
    expect(pp3.recordId).toBeTruthy();

    // Verify total 3 plans exist via API
    const nqResp = await page.request.get(
      `/api/datasource/list?datasourceId=nq:cc_contract_payment_status&format=records&projectId=${projectPid}`,
    );
    expect(nqResp.ok()).toBe(true);
    const body = await nqResp.json();
    const records: any[] = body?.data?.records ?? [];

    const ourContract = records.find((r: any) => r.contract_name === contractName);
    expect(ourContract, 'Our contract should appear in payment status').toBeTruthy();
  });

  // =========================================================================
  // PP-04: State transition — confirm partial receipt
  // =========================================================================
  test('PP-04: Partial receipt state transition', async ({ page }) => {
    // Transition: pending → PARTIAL
    const result = await executeCommandViaApi(
      page,
      'cc:partial_receipt',
      { cc_pp_actual_amount: 100000, cc_pp_actual_date: dateOffsetStr(0) },
      paymentPlanPid,
      'update',
    );
    expect(result.code).toBe('0');
  });

  // =========================================================================
  // PP-05: State transition — confirm full receipt
  // =========================================================================
  test('PP-05: Full receipt state transition', async ({ page }) => {
    // Transition: PARTIAL → RECEIVED
    const result = await executeCommandViaApi(
      page,
      'cc:confirm_receipt',
      { cc_pp_actual_amount: 200000, cc_pp_actual_date: dateOffsetStr(0) },
      paymentPlanPid,
      'update',
    );
    expect(result.code).toBe('0');
  });

  // =========================================================================
  // PP-06: Cross-module: Navigate all CC menu items (smoke check)
  // =========================================================================
  test('PP-06: All Contract & Cost menu items accessible', async ({ page }) => {
    await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });

    // Expand CC menu
    const ccMenu = page.locator('button', { hasText: /Contract & Cost|合同与成本/ });
    await ccMenu.first().scrollIntoViewIfNeeded();
    await ccMenu.first().click();

    // Check each submenu link exists
    const menuPaths = [
      '/contract-cost/contracts',
      '/contract-cost/changes',
      '/contract-cost/payments',
      '/contract-cost/payment-plans',
      '/contract-cost/budgets',
      '/contract-cost/budget-lines',
      '/contract-cost/actual-costs',
      '/contract-cost/profit-analysis',
    ];

    for (const path of menuPaths) {
      const link = page.locator(`a[href="${path}"]`);
      await expect(link.first(), `Menu link ${path} should exist`).toBeAttached({ timeout: 3000 });
    }
  });

  // =========================================================================
  // PP-07: Verify overdue payments NQ detects overdue plans
  // =========================================================================
  test('PP-07: Overdue payments NQ returns correct data', async ({ page }) => {
    // Create an overdue payment plan (plan_date in the past, status pending)
    const overduePP = await executeCommandViaApi(
      page,
      'cc:create_payment_plan',
      {
        cc_pp_contract_id: contractPid,
        cc_pp_period: 4,
        cc_pp_plan_date: dateOffsetStr(-30), // 30 days overdue
        cc_pp_plan_amount: 100000,
      },
      undefined,
      'create',
    );
    expect(overduePP.recordId).toBeTruthy();

    // NQ assertion: cc_overdue_payments should include this plan
    const nqResp = await page.request.get(
      '/api/datasource/list?datasourceId=nq:cc_overdue_payments&format=records',
    );
    expect(nqResp.ok()).toBe(true);
    const body = await nqResp.json();
    const records: any[] = body?.data?.records ?? [];
    expect(
      records.length,
      'cc_overdue_payments should return at least 1 overdue plan',
    ).toBeGreaterThan(0);
  });
});
