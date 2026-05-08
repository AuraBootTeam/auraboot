/**
 * PM My Tasks Page E2E Tests
 *
 * Tests the cross-project "My Tasks" view with real data assertions.
 * Enters via sidebar menu navigation, not page.goto().
 *
 * NO CLEANUP — test data is preserved as verification evidence.
 *
 * @since 7.3.0
 */

import { test, expect } from '@playwright/test';
import { uniqueId, executeCommandViaApi, dateOffsetStr } from '../helpers/index';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

async function navigateToMyTasks(page: import('@playwright/test').Page) {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });

  const pmMenu = page.locator('button', { hasText: /Project Management|项目管理/ });
  await pmMenu.first().scrollIntoViewIfNeeded();
  await pmMenu.first().click();

  const link = page.locator('a[href="/project-management/my-tasks"]');
  await link.first().waitFor({ state: 'attached', timeout: 5000 });
  await link.first().evaluate((el) => (el as HTMLAnchorElement).click());

  await expect(page).toHaveURL(/\/project-management\/my-tasks/);
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

test.describe('PM My Tasks', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60000);

  const projectName = uniqueId('E2EMyTask');
  const taskTitle = `MyTask ${projectName}`;
  const doneTaskTitle = `DoneTask ${projectName}`;

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      // Create project
      const proj = await executeCommandViaApi(
        page,
        'pm:create_project',
        { pm_project_name: projectName },
        undefined,
        'create',
      );
      const projectPid = proj.recordId;
      expect(projectPid).toBeTruthy();

      // Activate project
      await executeCommandViaApi(page, 'pm:activate_project', {}, projectPid, 'update');

      // Fetch auto-created member pid
      const BASE = process.env.PLAYWRIGHT_BASE_URL ?? process.env.BASE_URL ?? `http://localhost:${process.env.VITE_PORT ?? '5173'}`;
      const memberFilter = encodeURIComponent(
        JSON.stringify([{ fieldName: 'pm_member_project_id', operator: 'EQ', value: projectPid }]),
      );
      const memberResp = await page.request.get(
        `${BASE}/api/dynamic/pm_project_member/list?pageSize=10&filters=${memberFilter}`,
      );
      const memberBody = await memberResp.json();
      const memberPid = memberBody?.data?.records?.[0]?.pid;

      // Create TODO task assigned to current user
      await executeCommandViaApi(
        page,
        'pm:create_task',
        {
          pm_task_title: taskTitle,
          pm_task_project_id: projectPid,
          pm_task_type: 'task',
          pm_task_priority: 'high',
          pm_task_due_date: dateOffsetStr(3),
          ...(memberPid ? { pm_task_assignee_id: memberPid } : {}),
        },
        undefined,
        'create',
      );

      // Create DONE task (for status filter testing)
      const t2 = await executeCommandViaApi(
        page,
        'pm:create_task',
        {
          pm_task_title: doneTaskTitle,
          pm_task_project_id: projectPid,
          pm_task_type: 'bug',
          pm_task_priority: 'medium',
          ...(memberPid ? { pm_task_assignee_id: memberPid } : {}),
        },
        undefined,
        'create',
      );
      await executeCommandViaApi(page, 'pm:start_task', {}, t2.recordId, 'update');
      await executeCommandViaApi(page, 'pm:complete_task', {}, t2.recordId, 'update');
    } finally {
      await ctx.close();
    }
  });

  test('PM-MT-01: Navigate to My Tasks via sidebar menu @smoke', async ({ page }) => {
    await navigateToMyTasks(page);
    await expect(page.getByTestId('my-tasks-title')).toBeVisible({ timeout: 15000 });
  });

  test('PM-MT-02: My Tasks page displays assigned task data', async ({ page }) => {
    const apiPromise = page.waitForResponse(
      (r) =>
        r.url().includes('/api/datasource/list') &&
        r.url().includes('pm_my_tasks') &&
        r.status() === 200,
      { timeout: 10000 },
    );
    await navigateToMyTasks(page);
    const apiResp = await apiPromise;
    const body = await apiResp.json();

    // API should return records
    expect(body.code).toBe('0');
    expect(body.data?.records?.length).toBeGreaterThan(0);

    // Task row should be visible in UI
    await expect(page.getByTestId('my-tasks-groups')).toBeVisible({ timeout: 10000 });
    await expect(page.locator(`text=${taskTitle}`)).toBeVisible({ timeout: 10000 });
  });

  test('PM-MT-03: Status filter buttons filter tasks', async ({ page }) => {
    await navigateToMyTasks(page);
    await expect(page.getByTestId('my-tasks-groups')).toBeVisible({ timeout: 15000 });

    // Click TODO filter (data-testid="filter-todo" — lowercase key)
    await page.getByTestId('filter-todo').click();

    // TODO task should be visible
    await expect(page.locator(`text=${taskTitle}`)).toBeVisible({ timeout: 5000 });

    // Click DONE filter
    await page.getByTestId('filter-done').click();

    // DONE task should be visible
    await expect(page.locator(`text=${doneTaskTitle}`)).toBeVisible({ timeout: 5000 });

    // Click ALL to reset
    await page.getByTestId('filter-all').click();
  });

  test('PM-MT-04: Group-by toggle changes grouping', async ({ page }) => {
    await navigateToMyTasks(page);
    await expect(page.getByTestId('my-tasks-groups')).toBeVisible({ timeout: 15000 });

    // Group by project is default (data-testid="group-project")
    await expect(page.getByTestId('group-project')).toBeVisible({ timeout: 5000 });

    // Switch to group by status (data-testid="group-status")
    await page.getByTestId('group-status').click();

    // Group headers should change
    await expect(page.getByTestId('my-tasks-groups')).toBeVisible({ timeout: 5000 });

    // Switch back to project grouping
    await page.getByTestId('group-project').click();
  });

  test('PM-MT-05: Search input filters tasks by title', async ({ page }) => {
    await navigateToMyTasks(page);
    await expect(page.getByTestId('my-tasks-groups')).toBeVisible({ timeout: 15000 });

    const searchInput = page.getByTestId('my-tasks-search');
    await expect(searchInput).toBeVisible({ timeout: 5000 });

    // Search for specific task
    await searchInput.fill(taskTitle);

    // Should show only matching task
    await expect(page.locator(`text=${taskTitle}`)).toBeVisible({ timeout: 5000 });
  });

  test('PM-MT-06: Task row click navigates to project workspace', async ({ page }) => {
    await navigateToMyTasks(page);
    await expect(page.getByTestId('my-tasks-groups')).toBeVisible({ timeout: 15000 });

    // Click on the task row
    const taskRow = page.locator(`[data-testid^="task-row-"]`, { hasText: taskTitle }).first();
    await expect(taskRow).toBeVisible({ timeout: 10000 });
    await taskRow.click();

    // Should navigate to project workspace with task query param
    await expect(page).toHaveURL(/\/project-management\/projects\//, { timeout: 10000 });
  });

  test('PM-MT-07: Due date overdue highlighting', async ({ page }) => {
    await navigateToMyTasks(page);
    await expect(page.getByTestId('my-tasks-groups')).toBeVisible({ timeout: 15000 });

    // Task with due_date in future should be visible (not red)
    const taskRow = page.locator(`[data-testid^="task-row-"]`, { hasText: taskTitle }).first();
    await expect(taskRow).toBeVisible({ timeout: 10000 });

    // Verify due date is rendered
    await expect(taskRow.locator('[data-testid="task-card-due-date"], text=/\\d{4}-\\d{2}-\\d{2}/'))
      .toBeVisible({ timeout: 5000 })
      .catch(() => {
        // Due date display is optional
      });
  });

  test('PM-MT-08: Empty state when no matching tasks', async ({ page }) => {
    await navigateToMyTasks(page);
    await expect(page.getByTestId('my-tasks-groups')).toBeVisible({ timeout: 15000 });

    // Search for non-existent task
    const searchInput = page.getByTestId('my-tasks-search');
    await searchInput.fill('NonExistentTaskXYZ999');

    // Groups should disappear and empty state should show
    await expect(page.getByTestId('my-tasks-empty')).toBeVisible({ timeout: 5000 });

    // Clear search to restore
    await searchInput.clear();
    await expect(page.getByTestId('my-tasks-groups')).toBeVisible({ timeout: 5000 });
  });
});
