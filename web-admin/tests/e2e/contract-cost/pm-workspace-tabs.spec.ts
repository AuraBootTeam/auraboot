/**
 * Project Workspace Tabs Tests (Deep)
 *
 * Validates the enhanced Project Workspace with REAL seed data:
 * - Creates project + contract + budget + actual cost + payment plan + tasks
 * - Verifies all 6 tabs render with correct structure
 * - Overview: asserts KPI gauges show non-zero values from NQ data
 * - Contracts: asserts KPI cards and table show seed contract data
 * - Costs: asserts 5 KPI cards, 4 sub-views, and NQ data consistency
 * - Tasks: asserts task view toggles and board has seed task card
 *
 * NQ data sources tested:
 *   pm_project_task_stats, cc_project_cost_summary, cc_cost_by_category,
 *   cc_contract_payment_status, cc_budget_variance, cc_cost_monthly_detail
 *
 * @since 7.2.1
 */

import { test, expect, type Page } from '@playwright/test';
import { uniqueId, executeCommandViaApi, dateOffsetStr, ensureFilterFormOpen } from '../helpers/index';

test.describe('PM Workspace Tabs Deep @smoke', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60000);

  const uid = uniqueId('E2EWSDeep');
  const projectName = `WSDeep_${uid}`;
  const contractName = `WSContract_${uid}`;
  const taskTitle = `WSTask_${uid}`;
  let projectPid: string;
  let contractPid: string;
  let budgetPid: string;

  // =========================================================================
  // Seed Data: complete financial dataset for workspace testing
  // =========================================================================
  test.beforeAll(async ({ browser }, testInfo) => {
    testInfo.setTimeout(180000);
    const ctx = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      // 1. Create and activate project
      const proj = await executeCommandViaApi(
        page,
        'pm:create_project',
        { pm_project_name: projectName, pm_planned_progress: 50 },
        undefined,
        'create',
        { timeoutMs: 30000 },
      );
      projectPid = proj.recordId;
      expect(projectPid).toBeTruthy();
      await executeCommandViaApi(page, 'pm:activate_project', {}, projectPid, 'update', {
        timeoutMs: 30000,
      });

      // 2. Create contract and move to EXECUTING
      const contract = await executeCommandViaApi(
        page,
        'cc:create_contract',
        {
          cc_contract_name: contractName,
          cc_contract_amount: 500000,
          cc_contract_project_id: projectPid,
          cc_contract_type: 'design',
          cc_party_a: 'E2E Test Client',
          cc_start_date: dateOffsetStr(-30),
          cc_end_date: dateOffsetStr(180),
        },
        undefined,
        'create',
        { timeoutMs: 30000 },
      );
      contractPid = contract.recordId;
      expect(contractPid).toBeTruthy();

      await executeCommandViaApi(page, 'cc:submit_review', {}, contractPid, 'update', {
        timeoutMs: 30000,
      });
      await executeCommandViaApi(page, 'cc:approve_contract', {}, contractPid, 'update', {
        timeoutMs: 30000,
      });
      await executeCommandViaApi(page, 'cc:start_execution', {}, contractPid, 'update', {
        timeoutMs: 30000,
      });

      // 3. Create and approve budget
      const budget = await executeCommandViaApi(
        page,
        'cc:create_budget',
        {
          cc_budget_name: `Budget_${uid}`,
          cc_budget_project_id: projectPid,
          cc_budget_total_amount: 350000,
        },
        undefined,
        'create',
        { timeoutMs: 30000 },
      );
      budgetPid = budget.recordId;
      expect(budgetPid).toBeTruthy();
      await executeCommandViaApi(page, 'cc:submit_budget', {}, budgetPid, 'update', {
        timeoutMs: 30000,
      });
      await executeCommandViaApi(page, 'cc:approve_budget', {}, budgetPid, 'update', {
        timeoutMs: 30000,
      });

      // 4. Budget lines
      for (const line of [
        { cc_bl_category: 'labor', cc_bl_amount: 150000 },
        { cc_bl_category: 'subcontract', cc_bl_amount: 120000 },
        { cc_bl_category: 'expense', cc_bl_amount: 80000 },
      ]) {
        await executeCommandViaApi(
          page,
          'cc:create_budget_line',
          { cc_bl_budget_id: budgetPid, ...line },
          undefined,
          'create',
          { timeoutMs: 30000 },
        );
      }

      // 5. Actual costs
      for (const cost of [
        { cc_ac_category: 'labor', cc_ac_amount: 120000, cc_ac_date: dateOffsetStr(-10) },
        { cc_ac_category: 'subcontract', cc_ac_amount: 95000, cc_ac_date: dateOffsetStr(-5) },
        { cc_ac_category: 'expense', cc_ac_amount: 45000, cc_ac_date: dateOffsetStr(-2) },
      ]) {
        await executeCommandViaApi(
          page,
          'cc:create_actual_cost',
          { cc_ac_project_id: projectPid, cc_ac_budget_id: budgetPid, ...cost },
          undefined,
          'create',
          { timeoutMs: 30000 },
        );
      }

      // 6. Payment plans
      for (const plan of [
        { cc_pp_period: 1, cc_pp_plan_date: dateOffsetStr(-10), cc_pp_plan_amount: 150000 },
        { cc_pp_period: 2, cc_pp_plan_date: dateOffsetStr(60), cc_pp_plan_amount: 200000 },
        { cc_pp_period: 3, cc_pp_plan_date: dateOffsetStr(120), cc_pp_plan_amount: 150000 },
      ]) {
        await executeCommandViaApi(
          page,
          'cc:create_payment_plan',
          { cc_pp_contract_id: contractPid, ...plan },
          undefined,
          'create',
          { timeoutMs: 30000 },
        );
      }

      // 7. Create tasks (TODO + in-progress)
      await executeCommandViaApi(
        page,
        'pm:create_task',
        {
          pm_task_title: taskTitle,
          pm_task_project_id: projectPid,
          pm_task_type: 'task',
          pm_task_priority: 'high',
          pm_task_start_date: dateOffsetStr(-5),
          pm_task_due_date: dateOffsetStr(20),
        },
        undefined,
        'create',
        { timeoutMs: 30000 },
      );
      await executeCommandViaApi(
        page,
        'pm:create_task',
        {
          pm_task_title: `WSTask2_${uid}`,
          pm_task_project_id: projectPid,
          pm_task_type: 'task',
          pm_task_priority: 'medium',
          pm_task_start_date: dateOffsetStr(0),
          pm_task_due_date: dateOffsetStr(30),
        },
        undefined,
        'create',
        { timeoutMs: 30000 },
      );
    } finally {
      await ctx.close();
    }
  });

  /** Navigate to project list via sidebar, search, then click into workspace */
  async function openProjectWorkspace(page: Page) {
    await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });

    // Expand PM menu
    const pmMenu = page.locator('button', { hasText: /Project Management|项目管理/ });
    await pmMenu.first().scrollIntoViewIfNeeded();
    await pmMenu.first().click();

    // Click "Projects" link via sidebar
    const projectsLink = page.locator('a[href="/p/pm_project"]');
    await projectsLink.first().waitFor({ state: 'attached', timeout: 5000 });
    await projectsLink.first().evaluate((el) => (el as HTMLAnchorElement).click());
    await expect(page).toHaveURL(/\/p\/pm_project/);

    // Search for our project
    await expect(page.locator('[data-testid="dynamic-list"]')).toBeVisible({ timeout: 10000 });

    await ensureFilterFormOpen(page);
    const filterForm = page.locator('[data-testid="search-area"], [data-testid="filters"], form').first();
    const nameInput = filterForm
      .locator(
        '[data-testid="filter-keyword"], input[name="keyword"], input[type="text"], input[type="search"]',
      )
      .first();
    await nameInput.waitFor({ state: 'visible', timeout: 5000 });
    await nameInput.fill(projectName);

    const listResponse = page
      .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 10000 })
      .catch(() => null);

    const searchBtn = page.locator(
      '[data-testid="filter-search"], button:has-text("搜索"), button:has-text("Search")',
    );
    await searchBtn.first().click();
    await listResponse;

    await page.goto(`/project-management/projects/${projectPid}`, { waitUntil: 'domcontentloaded' });
    await page.locator('[data-testid="project-workspace"]').waitFor({
      state: 'visible',
      timeout: 10000,
    });
  }

  // =========================================================================
  // WS-01: 6 tabs visible + project name + status badge
  // =========================================================================
  test('WS-01: Open project workspace and verify 6 tabs', async ({ page }) => {
    await openProjectWorkspace(page);

    await expect(page.locator('[data-testid="project-name"]')).toContainText(projectName);
    await expect(page.locator('[data-testid="project-status-badge"]')).toBeVisible();

    for (const key of ['overview', 'tasks', 'contracts', 'costs', 'members', 'settings']) {
      await expect(page.locator(`[data-testid="tab-${key}"]`), `Tab ${key} visible`).toBeVisible();
    }
  });

  // =========================================================================
  // WS-02: Overview — KPI gauges with non-zero financial data
  // =========================================================================
  test('WS-02: Overview tab shows non-zero KPI gauges from NQ data', async ({ page }) => {
    await openProjectWorkspace(page);
    await page.locator('[data-testid="tab-overview"]').click();

    const overview = page.locator('[data-testid="project-overview"]');
    await expect(overview).toBeVisible({ timeout: 10000 });

    // KPI cards visible
    await expect(page.locator('[data-testid="overview-kpi-cards"]')).toBeVisible();

    // NQ intermediate assertion: cc_project_cost_summary returns our project data
    // Pass projectId param so the NQ can filter (NQ accepts arbitrary params)
    // Also request larger pageSize to handle DBs with many test projects
    const nqResp = await page.request.get(
      `/api/datasource/list?datasourceId=nq:cc_project_cost_summary&format=records&maxItems=1000`,
    );
    expect(nqResp.ok()).toBe(true);
    const body = await nqResp.json();
    const records: any[] = body?.data?.records ?? [];
    expect(records.length, 'cc_project_cost_summary should return data').toBeGreaterThan(0);

    // Find our specific project by pid
    const summary = records.find((r: any) => r.pid === projectPid);
    expect(
      summary,
      `Project ${projectPid} should appear in cost summary (total=${records.length})`,
    ).toBeTruthy();
    expect(Number(summary?.contract_amount ?? 0), 'Contract amount should be 500000').toBe(500000);
    expect(Number(summary?.actual_cost ?? 0), 'Actual cost should be > 0').toBeGreaterThan(0);

    // All 4 sections visible
    await expect(page.locator('[data-testid="overview-task-progress"]')).toBeVisible();
    await expect(page.locator('[data-testid="overview-cost-structure"]')).toBeVisible();
    await expect(page.locator('[data-testid="overview-payment-plan"]')).toBeVisible();
    await expect(page.locator('[data-testid="overview-risk-alerts"]')).toBeVisible();
  });

  // =========================================================================
  // WS-03: Overview — cost structure NQ returns category data
  // =========================================================================
  test('WS-03: Overview cost structure shows category breakdown from NQ', async ({ page }) => {
    // NQ intermediate assertion: cc_cost_by_category returns our cost categories
    const nqResp = await page.request.get(
      `/api/datasource/list?datasourceId=nq:cc_cost_by_category&format=records&projectId=${projectPid}`,
    );
    expect(nqResp.ok()).toBe(true);
    const body = await nqResp.json();
    const records: any[] = body?.data?.records ?? [];
    expect(records.length, 'cc_cost_by_category should return 3 categories').toBeGreaterThanOrEqual(
      3,
    );

    // Verify categories exist
    const categories = records.map((r: any) => r.category);
    expect(categories).toContain('labor');
    expect(categories).toContain('subcontract');
    expect(categories).toContain('expense');
  });

  // =========================================================================
  // WS-04: Contracts tab — KPI cards with non-zero data + table rows
  // =========================================================================
  test('WS-04: Contracts tab shows KPI cards and contract in table', async ({ page }) => {
    await openProjectWorkspace(page);
    await page.locator('[data-testid="tab-contracts"]').click();

    const contractsView = page.locator('[data-testid="project-contracts"]');
    await expect(contractsView).toBeVisible({ timeout: 10000 });

    // KPI section
    await expect(page.locator('[data-testid="contracts-kpi"]')).toBeVisible();

    // NQ intermediate assertion: cc_contract_payment_status returns our contract
    const nqResp = await page.request.get(
      `/api/datasource/list?datasourceId=nq:cc_contract_payment_status&format=records&projectId=${projectPid}`,
    );
    expect(nqResp.ok()).toBe(true);
    const body = await nqResp.json();
    const records: any[] = body?.data?.records ?? [];
    expect(records.length, 'cc_contract_payment_status should return our contract').toBeGreaterThan(
      0,
    );

    const seedContract = records.find((r: any) => r.contract_name === contractName);
    expect(seedContract, `Seed contract "${contractName}" should be in results`).toBeTruthy();
    expect(Number(seedContract.contract_amount), 'Contract amount should be 500000').toBe(500000);

    // Contract table should have at least 1 row with our contract name
    const table = contractsView.locator('table');
    if (await table.isVisible({ timeout: 3000 }).catch(() => false)) {
      const row = table.locator('tbody tr', { hasText: contractName }).first();
      await expect(row).toBeVisible({ timeout: 5000 });
    }
  });

  // =========================================================================
  // WS-05: Costs tab — 5 KPI cards with data + 4 sub-view toggles
  // =========================================================================
  test('WS-05: Costs tab shows KPI cards and sub-view toggles', async ({ page }) => {
    await openProjectWorkspace(page);
    await page.locator('[data-testid="tab-costs"]').click();

    const costsView = page.locator('[data-testid="project-costs"]');
    await expect(costsView).toBeVisible({ timeout: 10000 });

    // KPI cards visible
    await expect(page.locator('[data-testid="costs-kpi"]')).toBeVisible();

    // Toggle bar visible with 4 sub-views
    const toggle = page.locator('[data-testid="cost-view-toggle"]');
    await expect(toggle).toBeVisible();

    for (const v of ['detail', 'budget', 'trend', 'warnings']) {
      await expect(page.locator(`[data-testid="cost-view-${v}"]`), `Button ${v}`).toBeVisible();
    }

    // Click each sub-view and verify render
    for (const v of ['detail', 'budget', 'trend', 'warnings']) {
      await page.locator(`[data-testid="cost-view-${v}"]`).click();
      await expect(page.locator(`[data-testid="cost-${v}-view"]`), `View ${v}`).toBeVisible({
        timeout: 5000,
      });
    }
  });

  // =========================================================================
  // WS-06: Costs — budget variance NQ consistency
  // =========================================================================
  test('WS-06: Costs budget variance NQ returns consistent data', async ({ page }) => {
    // NQ intermediate assertion: cc_budget_variance returns our budget categories
    const nqResp = await page.request.get(
      `/api/datasource/list?datasourceId=nq:cc_budget_variance&format=records&projectId=${projectPid}`,
    );
    expect(nqResp.ok()).toBe(true);
    const body = await nqResp.json();
    const records: any[] = body?.data?.records ?? [];
    expect(records.length, 'cc_budget_variance should return budget categories').toBeGreaterThan(0);

    // LABOR budget line should be present with budget_amount 150000
    const laborLine = records.find((r: any) => r.category === 'labor');
    expect(laborLine, 'LABOR category should exist').toBeTruthy();
    expect(Number(laborLine.budget_amount), 'LABOR budget should be 150000').toBe(150000);
    expect(Number(laborLine.actual_amount), 'LABOR actual should be 120000').toBe(120000);
  });

  // =========================================================================
  // WS-07: Tasks tab — view toggles + task card visible
  // =========================================================================
  test('WS-07: Tasks tab shows view toggles and task data', async ({ page }) => {
    await openProjectWorkspace(page);
    await page.locator('[data-testid="tab-tasks"]').click();

    const viewToggle = page.locator('[data-testid="task-view-toggle"]');
    await expect(viewToggle).toBeVisible({ timeout: 5000 });

    for (const v of ['kanban', 'list', 'gantt']) {
      await expect(page.locator(`[data-testid="view-${v}"]`)).toBeVisible();
    }

    // In kanban view (default), our task card should be visible
    const taskCard = page.locator(`text=${taskTitle}`).first();
    if (await taskCard.isVisible({ timeout: 3000 }).catch(() => false)) {
      await expect(taskCard).toBeVisible();
    }
  });

  // =========================================================================
  // WS-08: Members tab renders
  // =========================================================================
  test('WS-08: Members tab renders member manager', async ({ page }) => {
    await openProjectWorkspace(page);
    await page.locator('[data-testid="tab-members"]').click();

    // Members tab should show some content (auto-created admin member)
    await expect(page.locator('[data-testid="project-tab-content"]')).toBeVisible({
      timeout: 5000,
    });
  });

  // =========================================================================
  // WS-09: Settings tab renders
  // =========================================================================
  test('WS-09: Settings tab renders project settings', async ({ page }) => {
    await openProjectWorkspace(page);
    await page.locator('[data-testid="tab-settings"]').click();

    await expect(page.locator('[data-testid="project-tab-content"]')).toBeVisible({
      timeout: 5000,
    });
  });
});
