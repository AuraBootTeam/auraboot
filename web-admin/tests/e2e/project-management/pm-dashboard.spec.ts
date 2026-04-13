/**
 * PM Dashboard E2E Tests
 *
 * Deep testing of the Project Management Dashboard page:
 * - Menu navigation → Dashboard loads
 * - KPI stat cards render with real data (values > 0)
 * - Chart blocks (line, pie, bar) render without errors
 * - Data-table blocks render with real data rows
 * - NQ API intermediate assertions (data non-empty)
 * - All 7 blocks visible with correct testid
 *
 * Prerequisites:
 *   - project-management plugin imported
 *   - Seed data created in beforeAll (projects + tasks + time entries)
 *
 * @since 8.1.0
 */

import { test, expect } from '../../fixtures';
import { uniqueId, executeCommandViaApi, todayStr, dateOffsetStr } from '../helpers/index';

test.describe('PM Dashboard @smoke', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60000);

  const uid = uniqueId('PMDash');

  // Track created IDs for assertions
  let projectPid: string;
  let project2Pid: string;
  let taskPid: string;

  // =========================================================================
  // DATA SETUP — Create diverse seed data for dashboard to display
  // =========================================================================
  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      // --- Project 1: Active (in_progress) with tasks ---
      const proj1 = await executeCommandViaApi(
        page,
        'pm:create_project',
        {
          pm_project_name: `DashProj_Active_${uid}`,
          pm_project_status: 'planning',
        },
        undefined,
        'create',
      );
      projectPid = proj1.recordId;

      // Activate project: planning → in_progress
      await executeCommandViaApi(page, 'pm:activate_project', {}, projectPid, 'update');

      // --- Project 2: Completed ---
      const proj2 = await executeCommandViaApi(
        page,
        'pm:create_project',
        {
          pm_project_name: `DashProj_Done_${uid}`,
          pm_project_status: 'planning',
        },
        undefined,
        'create',
      );
      project2Pid = proj2.recordId;

      await executeCommandViaApi(page, 'pm:activate_project', {}, project2Pid, 'update');
      await executeCommandViaApi(page, 'pm:complete_project', {}, project2Pid, 'update');

      // --- Tasks for Project 1 ---
      // Task 1: todo (default)
      await executeCommandViaApi(
        page,
        'pm:create_task',
        {
          pm_task_title: `DashTask_Todo_${uid}`,
          pm_task_project_id: projectPid,
          pm_task_type: 'task',
          pm_task_status: 'todo',
        },
        undefined,
        'create',
      );

      // Task 2: in_progress
      const task2 = await executeCommandViaApi(
        page,
        'pm:create_task',
        {
          pm_task_title: `DashTask_WIP_${uid}`,
          pm_task_project_id: projectPid,
          pm_task_type: 'task',
          pm_task_status: 'todo',
        },
        undefined,
        'create',
      );
      taskPid = task2.recordId;
      await executeCommandViaApi(page, 'pm:start_task', {}, taskPid, 'update');

      // Task 3: done
      const task3 = await executeCommandViaApi(
        page,
        'pm:create_task',
        {
          pm_task_title: `DashTask_Done_${uid}`,
          pm_task_project_id: projectPid,
          pm_task_type: 'task',
          pm_task_status: 'todo',
        },
        undefined,
        'create',
      );
      await executeCommandViaApi(page, 'pm:start_task', {}, task3.recordId, 'update');
      await executeCommandViaApi(page, 'pm:complete_task', {}, task3.recordId, 'update');

      // Task 4: overdue (due date in the past)
      await executeCommandViaApi(
        page,
        'pm:create_task',
        {
          pm_task_title: `DashTask_Overdue_${uid}`,
          pm_task_project_id: projectPid,
          pm_task_type: 'task',
          pm_task_status: 'todo',
          pm_task_due_date: dateOffsetStr(-7),
        },
        undefined,
        'create',
      );

      // --- Time Entry for resource utilization ---
      // Get current user ID for time entry
      const meResp = await page.request.get('/api/auth/me');
      const meBody = await meResp.json();
      const userId = meBody?.data?.user?.pid || meBody?.data?.userPid;

      await executeCommandViaApi(
        page,
        'pm:create_time_entry',
        {
          pm_te_project_id: projectPid,
          pm_te_user_id: userId,
          pm_te_date: todayStr(),
          pm_te_hours: 4.5,
          pm_te_billable: true,
          pm_te_description: `Dashboard test hours ${uid}`,
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

  /** Navigate to PM Dashboard via sidebar menu */
  async function gotoDashboard(page: import('@playwright/test').Page) {
    // Navigate to dashboard page
    await page.goto('/project-management/dashboard', { waitUntil: 'domcontentloaded' });

    // Wait for dashboard data to load — multiple API calls fire in parallel
    await Promise.all([
      page
        .waitForResponse(
          (r) =>
            r.url().includes('/api/datasource/list') &&
            r.url().includes('pm_dashboard_kpi') &&
            r.status() === 200,
          { timeout: 15000 },
        )
        .catch(() => null),
      page
        .waitForResponse((r) => r.url().includes('/api/meta/chart-data') && r.status() === 200, {
          timeout: 15000,
        })
        .catch(() => null),
    ]);

    // Wait for stat cards to render
    await page
      .locator('[data-testid="dashboard-block-block_pm_kpi"]')
      .waitFor({ state: 'visible', timeout: 10000 });
  }

  // =========================================================================
  // TEST 01: Menu Navigation
  // =========================================================================
  test('PM-DASH-01: Dashboard accessible via sidebar menu', async ({ page }) => {
    // Navigate to admin area first (root / shows marketing website)
    await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });

    // Wait for sidebar to render — look for the PM menu button
    const pmMenuButton = page.locator('button', { hasText: /Project Management|项目管理/ });
    await pmMenuButton.first().waitFor({ state: 'visible', timeout: 15000 });
    await pmMenuButton.first().click();

    // Wait for submenu to expand
    const dashLink = page.locator('a[href="/project-management/dashboard"]');
    await dashLink.first().waitFor({ state: 'attached', timeout: 5000 });
    // Use evaluate to bypass potential CSS animation issues (max-h transition)
    await dashLink.first().evaluate((el: HTMLElement) => el.click());

    await expect(page).toHaveURL(/\/project-management\/dashboard/, { timeout: 10000 });

    // Dashboard title visible
    const title = page.locator('text=项目管理仪表盘').or(page.locator('text=PM Dashboard'));
    await expect(title.first()).toBeVisible({ timeout: 10000 });
  });

  // =========================================================================
  // TEST 02: KPI NQ API returns real data
  // =========================================================================
  test('PM-DASH-02: KPI NQ returns real data with seeded records', async ({ page }) => {
    const resp = await page.request.get(
      '/api/datasource/list?datasourceId=nq:pm_dashboard_kpi&format=records&maxItems=1',
    );
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const records = body?.data?.records ?? [];
    expect(records.length, 'KPI NQ should return 1 row').toBe(1);

    const kpi = records[0];
    // We created 2 projects — total should be >= 2
    expect(Number(kpi.total_projects), 'total_projects >= 2').toBeGreaterThanOrEqual(2);
    // 1 active project
    expect(Number(kpi.active_projects), 'active_projects >= 1').toBeGreaterThanOrEqual(1);
    // 1 completed project
    expect(Number(kpi.completed_projects), 'completed_projects >= 1').toBeGreaterThanOrEqual(1);
    // 4 tasks created
    expect(Number(kpi.total_tasks), 'total_tasks >= 4').toBeGreaterThanOrEqual(4);
    // Time entry hours
    expect(Number(kpi.total_hours), 'total_hours >= 4.5').toBeGreaterThanOrEqual(4.5);
  });

  // =========================================================================
  // TEST 03: Project Status Distribution NQ returns data
  // =========================================================================
  test('PM-DASH-03: Project status distribution NQ returns grouped data', async ({ page }) => {
    const resp = await page.request.get(
      '/api/datasource/list?datasourceId=nq:pm_project_status_distribution&format=records',
    );
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const records = body?.data?.records ?? [];
    expect(records.length, 'Should have at least 2 status groups').toBeGreaterThanOrEqual(2);

    // Verify in_progress and completed groups exist
    const statuses = records.map((r: any) => r.label);
    expect(statuses, 'Should include in_progress').toContain('in_progress');
    expect(statuses, 'Should include completed').toContain('completed');
  });

  // =========================================================================
  // TEST 04: Chart-data API for charts returns success
  // =========================================================================
  test('PM-DASH-04: Chart-data API returns data for all chart blocks', async ({ page }) => {
    // Line chart: monthly task trend
    const trendResp = await page.request.post('/api/meta/chart-data', {
      data: {
        type: 'namedQuery',
        queryCode: 'pm_monthly_task_trend',
        dimensions: ['month'],
        metrics: [
          { field: 'created_count', aggregation: 'sum', alias: 'created_count' },
          { field: 'completed_count', aggregation: 'sum', alias: 'completed_count' },
        ],
      },
    });
    expect(trendResp.ok(), 'Trend chart API should return 200').toBe(true);
    const trendBody = await trendResp.json();
    expect(trendBody.code, 'Trend chart response code should be 0').toBe('0');
    expect(trendBody.data?.rows?.length, 'Trend should have month rows').toBeGreaterThanOrEqual(1);

    // Pie chart: project status distribution
    const pieResp = await page.request.post('/api/meta/chart-data', {
      data: {
        type: 'namedQuery',
        queryCode: 'pm_project_status_distribution',
        dimensions: ['label'],
        metrics: [{ field: 'value', aggregation: 'sum', alias: 'value' }],
      },
    });
    expect(pieResp.ok(), 'Pie chart API should return 200').toBe(true);
    const pieBody = await pieResp.json();
    expect(pieBody.code, 'Pie chart response code should be 0').toBe('0');
    expect(pieBody.data?.rows?.length, 'Pie should have status groups').toBeGreaterThanOrEqual(1);

    // Bar chart: resource utilization
    const barResp = await page.request.post('/api/meta/chart-data', {
      data: {
        type: 'namedQuery',
        queryCode: 'pm_resource_utilization',
        dimensions: ['user_name'],
        metrics: [
          { field: 'total_hours', aggregation: 'sum', alias: 'total_hours' },
          { field: 'billable_hours', aggregation: 'sum', alias: 'billable_hours' },
        ],
      },
    });
    expect(barResp.ok(), 'Bar chart API should return 200').toBe(true);
    const barBody = await barResp.json();
    expect(barBody.code, 'Bar chart response code should be 0').toBe('0');
  });

  // =========================================================================
  // TEST 05: KPI Stat Cards render with data
  // =========================================================================
  test('PM-DASH-05: KPI stat cards render with non-zero values', async ({ page }) => {
    await gotoDashboard(page);

    const kpiBlock = page.locator('[data-testid="dashboard-block-block_pm_kpi"]');
    await expect(kpiBlock).toBeVisible();

    // Stat cards should be visible as child elements
    const cards = kpiBlock.locator('.rounded-lg.border');
    const cardCount = await cards.count();
    expect(cardCount, 'Should render 6 KPI cards').toBe(6);

    // Verify at least some cards show non-zero values
    // The cards contain <p> with label and <p> with value
    const allCardTexts = await kpiBlock.allInnerTexts();
    const combinedText = allCardTexts.join(' ');

    // total_projects should be >= 2
    // Check that not all values are 0 — at least one numeric value > 0
    const numbers = combinedText.match(/\d+\.?\d*/g) || [];
    const hasNonZero = numbers.some((n) => parseFloat(n) > 0);
    expect(hasNonZero, 'At least one KPI card should show a non-zero value').toBe(true);
  });

  // =========================================================================
  // TEST 06: Chart blocks render without errors
  // =========================================================================
  test('PM-DASH-06: Chart blocks render without "Failed to load" errors', async ({ page }) => {
    await gotoDashboard(page);

    // Wait for chart blocks to be visible before checking for error state.
    await expect(page.locator('[data-testid="dashboard-block-block_task_trend"]')).toBeVisible({
      timeout: 10000,
    });
    await expect(page.locator('[data-testid="dashboard-block-block_project_status"]')).toBeVisible({
      timeout: 10000,
    });

    const chartBlockIds = [
      'block_task_trend',
      'block_project_status',
      'block_task_status',
      'block_resource_util',
    ];

    for (const blockId of chartBlockIds) {
      const block = page.locator(`[data-testid="dashboard-block-${blockId}"]`);
      await expect(block, `Block ${blockId} should be visible`).toBeVisible({ timeout: 10000 });

      // Assert NO "Failed to load chart" error message
      const errorMsg = block.locator('text=Failed to load chart');
      const hasError = await errorMsg.isVisible().catch(() => false);
      expect(hasError, `Block ${blockId} should NOT show "Failed to load chart"`).toBe(false);
    }
  });

  // =========================================================================
  // TEST 07: Project Health Overview table renders
  // =========================================================================
  test('PM-DASH-07: Project health table renders with seeded data', async ({ page }) => {
    await gotoDashboard(page);

    const healthBlock = page.locator('[data-testid="dashboard-block-block_project_health"]');
    await expect(healthBlock).toBeVisible({ timeout: 10000 });

    // Should have table headers
    const headers = healthBlock.locator('th');
    const headerCount = await headers.count();
    expect(headerCount, 'Health table should have column headers').toBeGreaterThanOrEqual(4);

    // Should have data rows (we created 2 projects)
    const rows = healthBlock.locator('tbody tr');
    const rowCount = await rows.count();
    expect(rowCount, 'Health table should show project rows').toBeGreaterThanOrEqual(1);
  });

  // =========================================================================
  // TEST 08: Overdue Tasks table renders
  // =========================================================================
  test('PM-DASH-08: Overdue tasks table renders with overdue task data', async ({ page }) => {
    await gotoDashboard(page);

    const overdueBlock = page.locator('[data-testid="dashboard-block-block_overdue_tasks"]');
    await expect(overdueBlock).toBeVisible({ timeout: 10000 });

    // Verify overdue tasks NQ returns our seeded overdue task (API-level assertion)
    const resp = await page.request.get(
      '/api/datasource/list?datasourceId=nq:pm_overdue_tasks&format=records&maxItems=20',
    );
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const records = body?.data?.records ?? [];
    // We created 1 task with a past due date
    expect(records.length, 'Should have at least 1 overdue task').toBeGreaterThanOrEqual(1);

    // Verify the block has rendered content (table or data rows)
    const tableOrContent = overdueBlock.locator('table, tr, td');
    const contentCount = await tableOrContent.count();
    expect(contentCount, 'Overdue block should render table content').toBeGreaterThanOrEqual(1);
  });

  // =========================================================================
  // TEST 09: All 7 dashboard blocks are visible
  // =========================================================================
  test('PM-DASH-09: All 7 dashboard blocks render on the page', async ({ page }) => {
    await gotoDashboard(page);

    const allBlockIds = [
      'block_pm_kpi',
      'block_task_trend',
      'block_project_status',
      'block_task_status',
      'block_resource_util',
      'block_project_health',
      'block_overdue_tasks',
    ];

    for (const blockId of allBlockIds) {
      const block = page.locator(`[data-testid="dashboard-block-${blockId}"]`);
      await expect(block, `Block ${blockId} should be visible`).toBeVisible({ timeout: 10000 });
    }
  });

  // =========================================================================
  // TEST 10: Data consistency — KPI values match seeded data
  // =========================================================================
  test('PM-DASH-10: Dashboard KPI values consistent with seeded data', async ({ page }) => {
    // Fetch KPI data via API
    const kpiResp = await page.request.get(
      '/api/datasource/list?datasourceId=nq:pm_dashboard_kpi&format=records&maxItems=1',
    );
    expect(kpiResp.ok()).toBe(true);
    const kpiBody = await kpiResp.json();
    const kpi = kpiBody?.data?.records?.[0];
    expect(kpi, 'KPI record should exist').toBeTruthy();

    // Verify project counts
    const totalProjects = Number(kpi.total_projects);
    const activeProjects = Number(kpi.active_projects);
    const completedProjects = Number(kpi.completed_projects);
    expect(totalProjects).toBeGreaterThanOrEqual(activeProjects + completedProjects);

    // Verify task counts
    const totalTasks = Number(kpi.total_tasks);
    expect(totalTasks, 'Total tasks should be >= 4 (we created 4)').toBeGreaterThanOrEqual(4);

    // Verify overdue count
    const overdueTasks = Number(kpi.overdue_tasks);
    expect(overdueTasks, 'Should have at least 1 overdue task').toBeGreaterThanOrEqual(1);

    // Verify hours from time entry
    const totalHours = Number(kpi.total_hours);
    expect(totalHours, 'Total hours should include our 4.5h entry').toBeGreaterThanOrEqual(4.5);
  });

  // =========================================================================
  // TEST 11: Monthly task trend has current month data
  // =========================================================================
  test('PM-DASH-11: Monthly task trend includes current month with created tasks', async ({
    page,
  }) => {
    const resp = await page.request.get(
      '/api/datasource/list?datasourceId=nq:pm_monthly_task_trend&format=records',
    );
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const records = body?.data?.records ?? [];

    // Current month should have created_count >= 4
    const currentMonth = todayStr().slice(0, 7); // "YYYY-MM"
    const currentMonthData = records.find((r: any) => r.month === currentMonth);
    expect(currentMonthData, `Should have data for current month ${currentMonth}`).toBeTruthy();
    expect(
      Number(currentMonthData.created_count),
      'Current month created_count >= 4',
    ).toBeGreaterThanOrEqual(4);
  });

  // =========================================================================
  // TEST 12: Resource utilization NQ returns user data
  // =========================================================================
  test('PM-DASH-12: Resource utilization NQ returns time entry data', async ({ page }) => {
    const resp = await page.request.get(
      '/api/datasource/list?datasourceId=nq:pm_resource_utilization&format=records',
    );
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const records = body?.data?.records ?? [];
    // We created a time entry with 4.5 hours
    expect(records.length, 'Should have at least 1 user with time entries').toBeGreaterThanOrEqual(
      1,
    );

    // Find our user's entry and verify hours
    const hasHours = records.some((r: any) => Number(r.total_hours) >= 4.5);
    expect(hasHours, 'At least one user should have >= 4.5 hours').toBe(true);
  });
});
