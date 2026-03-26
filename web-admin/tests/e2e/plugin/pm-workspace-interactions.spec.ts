/**
 * PM Workspace Interaction E2E Tests
 *
 * Covers the G1-G17 interaction improvements:
 *   - G1: Project list row click -> workspace navigation
 *   - G3: Breadcrumb navigation in workspace header
 *   - G4: Subtask list in task detail drawer
 *   - G7: Inline priority edit in list view
 *   - G8: Gantt date rendering and mode toggle
 *   - G9: Overview donut chart + progress
 *   - G12: Filter bar (search + priority) in kanban and list views
 *   - G15: Keyboard shortcut N to create task
 *
 * Enters via sidebar menu navigation, not page.goto().
 * NO CLEANUP — test data is preserved as verification evidence.
 *
 * @since 7.3.0
 */

import { test, expect } from '@playwright/test';
import {
  uniqueId,
  executeCommandViaApi,
  dateOffsetStr,
} from '../helpers/index';

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

/** Expand PM submenu and click a menu link by href */
async function clickPmMenuLink(page: import('@playwright/test').Page, href: string) {
  const pmMenu = page.locator('button', { hasText: /Project Management|项目管理/ });
  await pmMenu.first().scrollIntoViewIfNeeded();
  await pmMenu.first().click();

  const link = page.locator(`a[href="${href}"]`);
  await link.first().waitFor({ state: 'attached', timeout: 5000 });
  await link.first().evaluate((el) => (el as HTMLAnchorElement).click());
}

/** Navigate to project list via menu, search and click a project row */
async function navigateToProjectWorkspace(page: import('@playwright/test').Page, projectName: string) {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  await clickPmMenuLink(page, '/dynamic/pm-project');
  await expect(page).toHaveURL(/\/dynamic\/pm-project/);

  // Wait for table
  await page.locator('tbody tr').first().waitFor({ state: 'visible', timeout: 10000 });

  // Search for our project
  const searchArea = page.getByTestId('search-area');
  if (await searchArea.isVisible({ timeout: 2000 }).catch(() => false)) {
    await searchArea.locator('input').first().fill(projectName);
    await page.getByTestId('filter-search').click();
    const table = page.locator('table, [role="table"]');
    const empty = page.locator('text=/no data|暂无/i');
    await expect(table.or(empty).first()).toBeVisible({ timeout: 10000 });
  }

  // Click project row
  const row = page.locator('tbody tr', { hasText: projectName }).first();
  await expect(row).toBeVisible({ timeout: 10000 });
  await row.click();

  await expect(page).toHaveURL(/\/project-management\/projects\//, { timeout: 10000 });
  await expect(page.getByTestId('project-workspace')).toBeVisible({ timeout: 15000 });
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

test.describe('PM Workspace Interactions', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60000);

  const projectName = uniqueId('E2EInteract');
  let projectPid: string;
  let parentTaskPid: string;
  let childTaskPid: string;

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      // Create project
      const proj = await executeCommandViaApi(
        page, 'pm:create_project',
        { pm_project_name: projectName },
        undefined, 'create',
      );
      projectPid = proj.recordId;
      expect(projectPid).toBeTruthy();

      // Activate project
      await executeCommandViaApi(page, 'pm:activate_project', {}, projectPid, 'update');

      // Create parent task with dates
      const parent = await executeCommandViaApi(
        page, 'pm:create_task',
        {
          pm_task_title: `Parent ${projectName}`,
          pm_task_project_id: projectPid,
          pm_task_type: 'task',
          pm_task_priority: 'high',
          pm_task_start_date: dateOffsetStr(-3),
          pm_task_due_date: dateOffsetStr(7),
        },
        undefined, 'create',
      );
      parentTaskPid = parent.recordId;
      expect(parentTaskPid).toBeTruthy();

      // Create child task
      const child = await executeCommandViaApi(
        page, 'pm:create_task',
        {
          pm_task_title: `Child ${projectName}`,
          pm_task_project_id: projectPid,
          pm_task_type: 'task',
          pm_task_priority: 'medium',
          pm_task_parent_id: parentTaskPid,
          pm_task_start_date: dateOffsetStr(0),
          pm_task_due_date: dateOffsetStr(5),
        },
        undefined, 'create',
      );
      childTaskPid = child.recordId;
      expect(childTaskPid).toBeTruthy();

      // Create a LOW priority task for filter testing
      await executeCommandViaApi(
        page, 'pm:create_task',
        {
          pm_task_title: `LowPri ${projectName}`,
          pm_task_project_id: projectPid,
          pm_task_type: 'bug',
          pm_task_priority: 'low',
        },
        undefined, 'create',
      );
    } finally {
      await page.close();
      await ctx.close();
    }
  });

  // -------------------------------------------------------------------------
  // G1: Project list row click -> workspace navigation (via menu)
  // -------------------------------------------------------------------------

  test('PM-INT-01: Project list row click navigates to workspace @smoke', async ({ page }) => {
    await navigateToProjectWorkspace(page, projectName);
    await expect(page.getByTestId('project-name')).toContainText(projectName);
  });

  // -------------------------------------------------------------------------
  // G3: Breadcrumb navigation
  // -------------------------------------------------------------------------

  test('PM-INT-02: Workspace shows breadcrumb with project name', async ({ page }) => {
    await navigateToProjectWorkspace(page, projectName);

    const breadcrumb = page.getByTestId('project-breadcrumb');
    await expect(breadcrumb).toBeVisible();
    await expect(breadcrumb).toContainText(projectName);
  });

  test('PM-INT-03: Breadcrumb back link navigates to project list', async ({ page }) => {
    await navigateToProjectWorkspace(page, projectName);

    // Click the back button in breadcrumb
    const backLink = page.getByTestId('project-breadcrumb').locator('button').first();
    await backLink.click();

    await expect(page).toHaveURL(/\/project-management\/projects/, { timeout: 10000 });
  });

  // -------------------------------------------------------------------------
  // G9: Overview donut chart + progress
  // -------------------------------------------------------------------------

  test('PM-INT-04: Overview tab shows donut chart and stat cards', async ({ page }) => {
    await navigateToProjectWorkspace(page, projectName);

    await page.getByTestId('tab-overview').click();
    await expect(page.getByTestId('project-overview')).toBeVisible({ timeout: 10000 });
    await expect(page.getByTestId('overview-kpi-cards')).toBeVisible();
    await expect(page.getByTestId('overview-task-progress')).toBeVisible();
    await expect(page.getByTestId('overview-cost-structure')).toBeVisible();
  });

  // -------------------------------------------------------------------------
  // G12: Filter bar in kanban view
  // -------------------------------------------------------------------------

  test('PM-INT-05: Kanban filter bar is visible and functional', async ({ page }) => {
    await navigateToProjectWorkspace(page, projectName);
    await expect(page.getByTestId('task-board')).toBeVisible({ timeout: 15000 });

    await expect(page.getByTestId('board-filter-bar')).toBeVisible();
    await expect(page.getByTestId('board-filter-search')).toBeVisible();
    await expect(page.getByTestId('board-filter-priority')).toBeVisible();
  });

  test('PM-INT-06: Kanban search filter narrows results', async ({ page }) => {
    await navigateToProjectWorkspace(page, projectName);
    await expect(page.getByTestId('task-board')).toBeVisible({ timeout: 15000 });

    await expect(page.locator('[data-testid^="task-card-"]').first()).toBeVisible({ timeout: 10000 });
    const totalBefore = await page.locator('[data-testid^="task-card-"]').count();
    expect(totalBefore).toBeGreaterThan(0);

    await page.getByTestId('board-filter-search').fill('LowPri');

    await expect(page.locator('[data-testid^="task-card-"]').first()).toBeVisible({ timeout: 5000 });
    const totalAfter = await page.locator('[data-testid^="task-card-"]').count();
    expect(totalAfter).toBeLessThan(totalBefore);
  });

  test('PM-INT-07: Kanban priority filter works', async ({ page }) => {
    await navigateToProjectWorkspace(page, projectName);
    await expect(page.getByTestId('task-board')).toBeVisible({ timeout: 15000 });

    const totalBefore = await page.locator('[data-testid^="task-card-"]').count();

    await page.getByTestId('board-filter-priority').selectOption('high');
    await expect(page.locator('[data-testid^="task-card-"]').first()).toBeVisible({ timeout: 5000 });
    const totalAfterHigh = await page.locator('[data-testid^="task-card-"]').count();
    expect(totalAfterHigh).toBeLessThanOrEqual(totalBefore);

    await page.getByTestId('board-filter-clear').click();
    await expect(page.locator('[data-testid^="task-card-"]').first()).toBeVisible({ timeout: 5000 });
  });

  // -------------------------------------------------------------------------
  // G12: Filter bar in list view
  // -------------------------------------------------------------------------

  test('PM-INT-08: List view filter bar is visible with priority and status dropdowns', async ({ page }) => {
    await navigateToProjectWorkspace(page, projectName);

    await page.getByTestId('view-list').click();
    await expect(page.getByTestId('task-list-view')).toBeVisible({ timeout: 10000 });

    await expect(page.getByTestId('task-list-filter-priority')).toBeVisible();
    await expect(page.getByTestId('task-list-filter-status')).toBeVisible();
  });

  test('PM-INT-09: List view priority filter narrows rows', async ({ page }) => {
    await navigateToProjectWorkspace(page, projectName);

    await page.getByTestId('view-list').click();
    await expect(page.getByTestId('task-list-view')).toBeVisible({ timeout: 10000 });

    const rows = page.locator('[data-testid^="task-row-"]');
    await expect(rows.first()).toBeVisible({ timeout: 10000 });
    const totalBefore = await rows.count();
    expect(totalBefore).toBeGreaterThan(0);

    await page.getByTestId('task-list-filter-priority').selectOption('low');

    // Wait for filter to take effect — use a condition-based wait
    await expect(async () => {
      const count = await rows.count();
      expect(count).toBeLessThan(totalBefore);
    }).toPass({ timeout: 5000 });

    await page.getByTestId('task-list-filter-clear').click();
  });

  // -------------------------------------------------------------------------
  // G7: Inline priority edit in list view
  // -------------------------------------------------------------------------

  test('PM-INT-10: List view inline priority edit changes value', async ({ page }) => {
    await navigateToProjectWorkspace(page, projectName);

    await page.getByTestId('view-list').click();
    await expect(page.getByTestId('task-list-view')).toBeVisible({ timeout: 10000 });
    await expect(page.locator('[data-testid^="task-row-"]').first()).toBeVisible({ timeout: 10000 });

    // Use the specific parent task's inline priority select
    const prioritySelect = page.getByTestId(`inline-priority-${parentTaskPid}`);
    await expect(prioritySelect).toBeVisible({ timeout: 5000 });

    const currentVal = await prioritySelect.inputValue();
    const targetVal = currentVal === 'critical' ? 'low' : 'critical';

    const updateResponse = page.waitForResponse(
      (r) => r.url().includes('/execute/pm:update_task') && r.status() === 200,
      { timeout: 10000 },
    );
    await prioritySelect.selectOption(targetVal);
    await updateResponse;

    await expect(prioritySelect).toHaveValue(targetVal);

    // Restore original value
    const restoreResponse = page.waitForResponse(
      (r) => r.url().includes('/execute/pm:update_task') && r.status() === 200,
      { timeout: 10000 },
    );
    await prioritySelect.selectOption(currentVal);
    await restoreResponse;
  });

  // -------------------------------------------------------------------------
  // G4: Subtask list in task detail drawer
  // -------------------------------------------------------------------------

  test('PM-INT-11: Task detail drawer shows subtasks section', async ({ page }) => {
    await navigateToProjectWorkspace(page, projectName);

    await page.getByTestId('view-list').click();
    await expect(page.getByTestId('task-list-view')).toBeVisible({ timeout: 10000 });
    await expect(page.locator('[data-testid^="task-row-"]').first()).toBeVisible({ timeout: 10000 });

    // Click the parent task row to open drawer
    const parentRow = page.getByTestId(`task-row-${parentTaskPid}`);
    await parentRow.scrollIntoViewIfNeeded();
    await parentRow.locator('td').nth(1).click();

    await expect(page.getByTestId('task-detail-drawer')).toBeVisible({ timeout: 5000 });
    await expect(page.getByTestId('task-subtasks-section')).toBeVisible({ timeout: 5000 });
    await expect(page.getByTestId(`subtask-${childTaskPid}`)).toBeVisible();
  });

  // -------------------------------------------------------------------------
  // G8: Gantt view renders with date-based tasks
  // -------------------------------------------------------------------------

  test('PM-INT-12: Gantt view renders tasks with dates', async ({ page }) => {
    await navigateToProjectWorkspace(page, projectName);

    await page.getByTestId('view-gantt').click();

    const ganttView = page.getByTestId('task-gantt-view');
    await expect(ganttView).toBeVisible({ timeout: 10000 });
    await expect(page.getByTestId('gantt-empty')).not.toBeVisible({ timeout: 3000 });

    await expect(ganttView.getByText(/\d+\s+(条任务|tasks)/)).toBeVisible({ timeout: 5000 });
    await expect(page.getByTestId('gantt-mode-day')).toBeVisible({ timeout: 5000 });
    await expect(page.getByTestId('gantt-mode-week')).toBeVisible({ timeout: 5000 });
    await expect(page.getByTestId('gantt-mode-month')).toBeVisible({ timeout: 5000 });
  });

  test('PM-INT-13: Gantt view mode toggle works', async ({ page }) => {
    await navigateToProjectWorkspace(page, projectName);

    await page.getByTestId('view-gantt').click();
    await expect(page.getByTestId('task-gantt-view')).toBeVisible({ timeout: 10000 });

    await page.getByTestId('gantt-mode-day').click();
    await page.getByTestId('gantt-mode-month').click();
    await page.getByTestId('gantt-mode-week').click();
  });

  // -------------------------------------------------------------------------
  // G15: Keyboard shortcut N to create task
  // -------------------------------------------------------------------------

  test('PM-INT-14: Pressing N key opens task create modal', async ({ page }) => {
    await navigateToProjectWorkspace(page, projectName);
    await expect(page.getByTestId('task-board')).toBeVisible({ timeout: 15000 });

    await page.keyboard.press('n');

    const modal = page.locator('[role="dialog"], [data-testid="task-form-modal"]');
    await expect(modal.first()).toBeVisible({ timeout: 5000 });
  });

  // -------------------------------------------------------------------------
  // G2: Terminal state cards not draggable (visual check)
  // -------------------------------------------------------------------------

  test('PM-INT-15: Completed task cards have reduced opacity', async ({ page }) => {
    // Create a task and complete it
    const doneTask = await executeCommandViaApi(
      page, 'pm:create_task',
      {
        pm_task_title: `DoneCheck ${projectName}`,
        pm_task_project_id: projectPid,
        pm_task_type: 'task',
        pm_task_priority: 'none',
      },
      undefined, 'create',
    );
    expect(doneTask.recordId).toBeTruthy();
    await executeCommandViaApi(page, 'pm:start_task', {}, doneTask.recordId, 'update');
    await executeCommandViaApi(page, 'pm:complete_task', {}, doneTask.recordId, 'update');

    await navigateToProjectWorkspace(page, projectName);

    const doneColumn = page.getByTestId('board-column-done');
    await expect(doneColumn).toBeVisible();

    const doneCard = doneColumn.locator('.opacity-60').first();
    await expect(doneCard).toBeVisible({ timeout: 5000 });
  });

  // -------------------------------------------------------------------------
  // Task detail drawer: comments and activity tabs
  // -------------------------------------------------------------------------

  test('PM-INT-16: Task detail drawer has comments and activity tabs', async ({ page }) => {
    await navigateToProjectWorkspace(page, projectName);
    await expect(page.getByTestId('task-board')).toBeVisible({ timeout: 15000 });

    await page.locator(`[data-testid="task-card-${parentTaskPid}"]`).click();
    await expect(page.getByTestId('task-detail-drawer')).toBeVisible({ timeout: 5000 });

    await expect(page.getByTestId('detail-tab-comments')).toBeVisible();
    await expect(page.getByTestId('detail-tab-activity')).toBeVisible();
    await expect(page.getByTestId('comment-input')).toBeVisible();

    await page.getByTestId('detail-tab-activity').click();
    await expect(page.getByTestId('task-activity-section')).toBeVisible({ timeout: 5000 });
  });
});
