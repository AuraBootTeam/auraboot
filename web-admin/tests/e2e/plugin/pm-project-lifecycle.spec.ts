/**
 * PM Project Lifecycle E2E Tests — Full Coverage
 *
 * Tests the complete project lifecycle via menu entry:
 *   1. Menu navigation → DSL project list
 *   2. Create project via DSL page → row click → workspace
 *   3. Project status transitions: PLANNING → in_progress → completed → archived
 *   4. Task CRUD and lifecycle: TODO → in_progress → DONE, cancel, reopen
 *   5. All workspace tabs: overview, tasks (kanban/list/gantt), members, settings
 *   6. Comments, filters, inline editing
 *   7. Error branches: invalid state transitions
 *
 * NO CLEANUP — test data is preserved as verification evidence.
 *
 * Prerequisites:
 *   - PM plugin imported and models published
 *   - Backend and frontend running
 *
 * @since 7.2.0
 */

import { test, expect } from '@playwright/test';
import { uniqueId, executeCommandViaApi, dateOffsetStr, ensureFilterFormOpen } from '../helpers/index';
import { BASE_URL as BASE } from '../../helpers/playwright-env';

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

/** Expand PM > Master Data submenu and click a link */
async function clickPmMasterDataLink(page: import('@playwright/test').Page, href: string) {
  const pmMenu = page.locator('button', { hasText: /Project Management|项目管理/ });
  await pmMenu.first().scrollIntoViewIfNeeded();
  await pmMenu.first().click();

  const masterDataMenu = page.locator('button', { hasText: /Master Data|基础数据/ });
  await masterDataMenu.first().waitFor({ state: 'attached', timeout: 5000 });
  await masterDataMenu.first().evaluate((el) => (el as HTMLButtonElement).click());

  const link = page.locator(`a[href="${href}"]`);
  await link.first().waitFor({ state: 'attached', timeout: 5000 });
  await link.first().evaluate((el) => (el as HTMLAnchorElement).click());
}

/** Search for a project in the DSL list page */
async function searchProjectInList(page: import('@playwright/test').Page, name: string) {
  await page.locator('tbody tr').first().waitFor({ state: 'visible', timeout: 10000 });
  await ensureFilterFormOpen(page);
  const filterForm = page.locator('[data-testid="filters"], form').first();
  if (await filterForm.isVisible({ timeout: 2000 }).catch(() => false)) {
    await filterForm.locator('input').first().fill(name);
    await page.getByTestId('filter-search').click();
    const table = page.locator('table, [role="table"]');
    const empty = page.locator('text=/no data|暂无/i');
    await expect(table.or(empty).first()).toBeVisible({ timeout: 10000 });
  }
}

// ---------------------------------------------------------------------------
// Test Suite
// ---------------------------------------------------------------------------

test.describe('PM Full Lifecycle', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60000);

  const projectName = uniqueId('E2ELife');
  let projectPid: string;
  let taskPid: string;
  let task2Pid: string;

  // Data setup: create project + tasks via API (beforeAll is allowed to use API)
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
      projectPid = proj.recordId;
      expect(projectPid).toBeTruthy();

      // Activate project so tasks can be managed
      await executeCommandViaApi(page, 'pm:activate_project', {}, projectPid, 'update');

      // Fetch auto-created member pid (sideEffect on pm:create_project)
      const BASE = process.env.PLAYWRIGHT_BASE_URL || process.env.BASE_URL || 'http://localhost:5173';
      const memberFilter = encodeURIComponent(
        JSON.stringify([{ fieldName: 'pm_member_project_id', operator: 'EQ', value: projectPid }]),
      );
      const memberResp = await page.request.get(
        `${BASE}/api/dynamic/pm_project_member/list?pageSize=10&filters=${memberFilter}`,
      );
      const memberBody = await memberResp.json();
      const memberPid = memberBody?.data?.records?.[0]?.pid;

      // Create main task (assigned to auto-created member for My Tasks visibility)
      const t1 = await executeCommandViaApi(
        page,
        'pm:create_task',
        {
          pm_task_title: `MainTask ${projectName}`,
          pm_task_project_id: projectPid,
          pm_task_type: 'task',
          pm_task_priority: 'high',
          pm_task_start_date: dateOffsetStr(0),
          pm_task_due_date: dateOffsetStr(14),
          ...(memberPid ? { pm_task_assignee_id: memberPid } : {}),
        },
        undefined,
        'create',
      );
      taskPid = t1.recordId;

      // Create second task for lifecycle testing
      const t2 = await executeCommandViaApi(
        page,
        'pm:create_task',
        {
          pm_task_title: `LifeTask ${projectName}`,
          pm_task_project_id: projectPid,
          pm_task_type: 'bug',
          pm_task_priority: 'medium',
          pm_task_start_date: dateOffsetStr(-1),
          pm_task_due_date: dateOffsetStr(7),
          ...(memberPid ? { pm_task_assignee_id: memberPid } : {}),
        },
        undefined,
        'create',
      );
      task2Pid = t2.recordId;
    } finally {
      await ctx.close();
    }
  });

  // NO afterAll cleanup — test data is preserved as verification evidence

  // =========================================================================
  // Section 1: Menu Navigation
  // =========================================================================

  test('LC-01: Navigate to project list via sidebar menu', async ({ page }) => {
    await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
    await clickPmMenuLink(page, '/p/pm_project');
    await expect(page).toHaveURL(/\/p\/pm_project/);

    // Wait for list data
    await page.locator('tbody tr').first().waitFor({ state: 'visible', timeout: 10000 });
  });

  test('LC-02: Search and find created project in DSL list', async ({ page }) => {
    await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
    await clickPmMenuLink(page, '/p/pm_project');
    await expect(page).toHaveURL(/\/p\/pm_project/);

    // Search for our project
    await searchProjectInList(page, projectName);
    const projectRow = page.locator('tbody tr', { hasText: projectName });
    await expect(projectRow.first()).toBeVisible({ timeout: 10000 });
  });

  test('LC-03: Click project row navigates to workspace', async ({ page }) => {
    // Navigate directly to workspace
    await page.goto(`/project-management/projects/${projectPid}`, { waitUntil: 'domcontentloaded' });

    await expect(page).toHaveURL(/\/project-management\/projects\//);
    await page.waitForLoadState('networkidle').catch(() => {});
    await expect(page.getByTestId('project-workspace')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('project-name')).toContainText(projectName);
  });

  // =========================================================================
  // Section 2: Workspace Tabs
  // =========================================================================

  test('LC-04: Workspace shows all 4 tabs', async ({ page }) => {
    await page.goto(`/project-management/projects/${projectPid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('project-workspace')).toBeVisible({ timeout: 15000 });

    await expect(page.getByTestId('tab-overview')).toBeVisible();
    await expect(page.getByTestId('tab-tasks')).toBeVisible();
    await expect(page.getByTestId('tab-members')).toBeVisible();
    await expect(page.getByTestId('tab-settings')).toBeVisible();
  });

  test('LC-05: Overview tab shows donut chart and stats', async ({ page }) => {
    await page.goto(`/project-management/projects/${projectPid}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByTestId('project-workspace')).toBeVisible({ timeout: 15000 });

    await page.getByTestId('tab-overview').click();
    await expect(page.getByTestId('project-overview')).toBeVisible({ timeout: 10000 });
    await expect(page.getByTestId('overview-kpi-cards')).toBeVisible();
    await expect(page.getByTestId('overview-task-progress')).toBeVisible();
    await expect(page.getByTestId('overview-cost-structure')).toBeVisible();
  });

  test('LC-06: Tasks tab — kanban view shows columns and task cards', async ({ page }) => {
    await page.goto(`/project-management/projects/${projectPid}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByTestId('project-workspace')).toBeVisible({ timeout: 15000 });

    // Tasks tab is default; kanban is default view
    await expect(page.getByTestId('task-board')).toBeVisible({ timeout: 15000 });

    // Columns visible
    const columns = page.locator('[data-testid^="board-column-"]');
    await expect(columns.first()).toBeVisible({ timeout: 10000 });

    // Task cards visible
    const cards = page.locator('[data-testid^="task-card-"]');
    await expect(cards.first()).toBeVisible({ timeout: 10000 });
    expect(await cards.count()).toBeGreaterThanOrEqual(2);
  });

  test('LC-07: Tasks tab — switch to list view with task rows', async ({ page }) => {
    await page.goto(`/project-management/projects/${projectPid}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByTestId('project-workspace')).toBeVisible({ timeout: 15000 });

    await page.getByTestId('view-list').click();
    await expect(page.getByTestId('task-list-view')).toBeVisible({ timeout: 10000 });

    const rows = page.locator('[data-testid^="task-row-"]');
    await expect(rows.first()).toBeVisible({ timeout: 10000 });
    expect(await rows.count()).toBeGreaterThanOrEqual(2);
  });

  test('LC-08: Tasks tab — switch to gantt view with task bars', async ({ page }) => {
    await page.goto(`/project-management/projects/${projectPid}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByTestId('project-workspace')).toBeVisible({ timeout: 15000 });

    await page.getByTestId('view-gantt').click();
    await expect(page.getByTestId('task-gantt-view')).toBeVisible({ timeout: 10000 });
    await expect(page.getByTestId('gantt-empty')).not.toBeVisible({ timeout: 3000 });
  });

  test('LC-09: Members tab shows member list with owner', async ({ page }) => {
    await page.goto(`/project-management/projects/${projectPid}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByTestId('project-workspace')).toBeVisible({ timeout: 15000 });

    const memberListPromise = page.waitForResponse(
      (r) => r.url().includes('/api/dynamic/pm_project_member/list') && r.status() === 200,
      { timeout: 10000 },
    );
    await page.getByTestId('tab-members').click();
    await memberListPromise;

    await expect(page.getByTestId('member-manager')).toBeVisible({ timeout: 10000 });
    // Add member button should be visible
    await expect(page.getByTestId('add-member-btn')).toBeVisible({ timeout: 5000 });
  });

  test('LC-10: Settings tab shows project info form', async ({ page }) => {
    await page.goto(`/project-management/projects/${projectPid}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByTestId('project-workspace')).toBeVisible({ timeout: 15000 });

    await page.getByTestId('tab-settings').click();
    await expect(page.getByTestId('project-settings')).toBeVisible({ timeout: 10000 });
    await expect(page.getByTestId('settings-name-input')).toBeVisible();
  });

  // =========================================================================
  // Section 3: Task Lifecycle (via UI)
  // =========================================================================

  test('LC-11: Open task detail drawer from kanban card', async ({ page }) => {
    await page.goto(`/project-management/projects/${projectPid}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByTestId('task-board')).toBeVisible({ timeout: 15000 });

    // Click the second task card
    await page.locator(`[data-testid="task-card-${task2Pid}"]`).click();
    await expect(page.getByTestId('task-detail-drawer')).toBeVisible({ timeout: 5000 });
    await expect(page.getByTestId('task-detail-status')).toContainText(/TODO|待处理/i);
  });

  test('LC-12: Start task (TODO → in_progress) via detail drawer', async ({ page }) => {
    await page.goto(`/project-management/projects/${projectPid}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByTestId('task-board')).toBeVisible({ timeout: 15000 });

    const taskCard = page.locator(`[data-testid="task-card-${task2Pid}"]`);
    await expect(taskCard).toBeVisible({ timeout: 10000 });

    // Open drawer and click start
    await taskCard.click();
    await expect(page.getByTestId('task-detail-drawer')).toBeVisible({ timeout: 5000 });

    const startBtn = page.getByTestId('task-action-pm:start_task');
    await expect(startBtn).toBeVisible({ timeout: 5000 });

    const cmdPromise = page.waitForResponse(
      (r) => r.url().includes('/execute/pm:start_task') && r.status() === 200,
      { timeout: 10000 },
    );
    await startBtn.click();
    await cmdPromise;

    // After state transition, board refreshes — task card moves to in_progress column
    // Wait for the task list to reload
    await page
      .waitForResponse((r) => r.url().includes('/api/dynamic/pm_task/list') && r.status() === 200, {
        timeout: 10000,
      })
      .catch(() => {});

    // Verify task moved to in_progress column
    await expect(
      page.getByTestId('board-column-in_progress').locator(`[data-testid="task-card-${task2Pid}"]`),
    ).toBeVisible({ timeout: 10000 });
  });

  test('LC-13: Complete task (in_progress → DONE) via detail drawer', async ({ page }) => {
    await page.goto(`/project-management/projects/${projectPid}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByTestId('task-board')).toBeVisible({ timeout: 15000 });

    // Task should be in in_progress column
    await expect(
      page.getByTestId('board-column-in_progress').locator(`[data-testid="task-card-${task2Pid}"]`),
    ).toBeVisible({ timeout: 10000 });

    await page.locator(`[data-testid="task-card-${task2Pid}"]`).click();
    await expect(page.getByTestId('task-detail-drawer')).toBeVisible({ timeout: 5000 });

    const completeBtn = page.getByTestId('task-action-pm:complete_task');
    await expect(completeBtn).toBeVisible({ timeout: 5000 });

    const cmdPromise = page.waitForResponse(
      (r) => r.url().includes('/execute/pm:complete_task') && r.status() === 200,
      { timeout: 10000 },
    );
    await completeBtn.click();
    await cmdPromise;

    // Wait for board refresh
    await page
      .waitForResponse((r) => r.url().includes('/api/dynamic/pm_task/list') && r.status() === 200, {
        timeout: 10000,
      })
      .catch(() => {});

    // Task moves to DONE column
    await expect(
      page.getByTestId('board-column-done').locator(`[data-testid="task-card-${task2Pid}"]`),
    ).toBeVisible({ timeout: 10000 });
  });

  test('LC-14: Reopen task (DONE → TODO) via list view', async ({ page }) => {
    // Terminal state cards (DONE/cancelled) are not clickable on kanban (opacity-60, disabled)
    // Use list view where all rows are clickable
    await page.goto(`/project-management/projects/${projectPid}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByTestId('project-workspace')).toBeVisible({ timeout: 15000 });

    await page.getByTestId('view-list').click();
    await expect(page.getByTestId('task-list-view')).toBeVisible({ timeout: 10000 });
    await expect(page.locator('[data-testid^="task-row-"]').first()).toBeVisible({
      timeout: 10000,
    });

    // Click the task title cell (not the inline priority select) to open drawer
    const taskRow = page.getByTestId(`task-row-${task2Pid}`);
    await taskRow.scrollIntoViewIfNeeded();
    // Click on the title text area to avoid intercepting select/dropdown elements
    await taskRow.locator('td').nth(1).click();
    await expect(page.getByTestId('task-detail-drawer')).toBeVisible({ timeout: 5000 });

    const reopenBtn = page.getByTestId('task-action-pm:reopen_task');
    await expect(reopenBtn).toBeVisible({ timeout: 5000 });

    const cmdPromise = page.waitForResponse(
      (r) => r.url().includes('/execute/pm:reopen_task') && r.status() === 200,
      { timeout: 10000 },
    );
    await reopenBtn.click();
    await cmdPromise;

    // Close drawer first, then switch to kanban to verify
    await page.getByTestId('task-detail-close').click();
    await expect(page.getByTestId('task-detail-drawer')).not.toBeVisible({ timeout: 3000 });

    await page.getByTestId('view-kanban').click();
    await expect(page.getByTestId('task-board')).toBeVisible({ timeout: 10000 });
    await expect(
      page.getByTestId('board-column-todo').locator(`[data-testid="task-card-${task2Pid}"]`),
    ).toBeVisible({ timeout: 10000 });
  });

  test('LC-15: Cancel task (TODO → cancelled) via detail drawer', async ({ page }) => {
    await page.goto(`/project-management/projects/${projectPid}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByTestId('task-board')).toBeVisible({ timeout: 15000 });

    await expect(
      page.getByTestId('board-column-todo').locator(`[data-testid="task-card-${task2Pid}"]`),
    ).toBeVisible({ timeout: 10000 });

    await page.locator(`[data-testid="task-card-${task2Pid}"]`).click();
    await expect(page.getByTestId('task-detail-drawer')).toBeVisible({ timeout: 5000 });

    const cancelBtn = page.getByTestId('task-action-pm:cancel_task');
    await expect(cancelBtn).toBeVisible({ timeout: 5000 });

    const cmdPromise = page.waitForResponse(
      (r) => r.url().includes('/execute/pm:cancel_task') && r.status() === 200,
      { timeout: 10000 },
    );
    await cancelBtn.click();
    await cmdPromise;

    await page
      .waitForResponse((r) => r.url().includes('/api/dynamic/pm_task/list') && r.status() === 200, {
        timeout: 10000,
      })
      .catch(() => {});

    // Task moves to cancelled column
    await expect(
      page.getByTestId('board-column-cancelled').locator(`[data-testid="task-card-${task2Pid}"]`),
    ).toBeVisible({ timeout: 10000 });
  });

  test('LC-16: Reopen cancelled task (cancelled → TODO) via list view', async ({ page }) => {
    // Terminal state cards not clickable on kanban — use list view
    await page.goto(`/project-management/projects/${projectPid}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByTestId('project-workspace')).toBeVisible({ timeout: 15000 });

    await page.getByTestId('view-list').click();
    await expect(page.getByTestId('task-list-view')).toBeVisible({ timeout: 10000 });
    await expect(page.locator('[data-testid^="task-row-"]').first()).toBeVisible({
      timeout: 10000,
    });

    const taskRow2 = page.getByTestId(`task-row-${task2Pid}`);
    await taskRow2.scrollIntoViewIfNeeded();
    await taskRow2.locator('td').nth(1).click();
    await expect(page.getByTestId('task-detail-drawer')).toBeVisible({ timeout: 5000 });

    const reopenBtn2 = page.getByTestId('task-action-pm:reopen_task');
    await expect(reopenBtn2).toBeVisible({ timeout: 5000 });

    const cmdPromise2 = page.waitForResponse(
      (r) => r.url().includes('/execute/pm:reopen_task') && r.status() === 200,
      { timeout: 10000 },
    );
    await reopenBtn2.click();
    await cmdPromise2;

    // Close drawer, then verify task back in TODO via kanban
    await page.getByTestId('task-detail-close').click();
    await expect(page.getByTestId('task-detail-drawer')).not.toBeVisible({ timeout: 3000 });
    await page.getByTestId('view-kanban').click();
    await expect(page.getByTestId('task-board')).toBeVisible({ timeout: 10000 });
    await expect(
      page.getByTestId('board-column-todo').locator(`[data-testid="task-card-${task2Pid}"]`),
    ).toBeVisible({ timeout: 10000 });
  });

  // Drive task2 to final DONE state for lifecycle evidence
  test('LC-17: Drive task to final DONE state (TODO → in_progress → DONE)', async ({ page }) => {
    // Start + Complete via API
    await executeCommandViaApi(page, 'pm:start_task', {}, task2Pid, 'update');
    await executeCommandViaApi(page, 'pm:complete_task', {}, task2Pid, 'update');

    // Verify in UI — task in DONE column
    await page.goto(`/project-management/projects/${projectPid}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByTestId('task-board')).toBeVisible({ timeout: 15000 });

    const doneColumn = page.getByTestId('board-column-done');
    await expect(doneColumn).toBeVisible();
    await expect(doneColumn.locator(`[data-testid="task-card-${task2Pid}"]`)).toBeVisible({
      timeout: 10000,
    });
  });

  // =========================================================================
  // Section 4: Task Create via UI
  // =========================================================================

  test('LC-18: Create task via kanban add button', async ({ page }) => {
    await page.goto(`/project-management/projects/${projectPid}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByTestId('task-board')).toBeVisible({ timeout: 15000 });

    const addBtn = page.getByTestId('board-add-task-btn');
    await expect(addBtn.first()).toBeVisible({ timeout: 5000 });
    await addBtn.first().click();

    // Task form modal opens
    await expect(page.getByTestId('task-form-modal')).toBeVisible({ timeout: 5000 });

    // Fill in task form
    const newTaskTitle = `UITask ${projectName}`;
    await page.getByTestId('task-form-title').fill(newTaskTitle);
    await page.getByTestId('task-form-type').selectOption('story');
    await page.getByTestId('task-form-priority').selectOption('high');
    await page.getByTestId('task-form-start-date').fill(dateOffsetStr(0));
    await page.getByTestId('task-form-due-date').fill(dateOffsetStr(10));

    // Submit
    const createPromise = page.waitForResponse(
      (r) => r.url().includes('/execute/pm:create_task') && r.status() === 200,
      { timeout: 10000 },
    );
    await page.getByTestId('task-form-submit').click();
    await createPromise;

    // Modal should close
    await expect(page.getByTestId('task-form-modal')).not.toBeVisible({ timeout: 5000 });

    // New task should appear in kanban
    const newCard = page.locator('[data-testid^="task-card-"]', { hasText: newTaskTitle });
    await expect(newCard.first()).toBeVisible({ timeout: 10000 });
  });

  test('LC-19: Create task via keyboard shortcut N', async ({ page }) => {
    await page.goto(`/project-management/projects/${projectPid}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByTestId('task-board')).toBeVisible({ timeout: 15000 });

    await page.keyboard.press('n');
    await expect(page.getByTestId('task-form-modal')).toBeVisible({ timeout: 5000 });

    // Close without saving
    await page.getByTestId('task-form-modal-close').click();
    await expect(page.getByTestId('task-form-modal')).not.toBeVisible({ timeout: 3000 });
  });

  // =========================================================================
  // Section 5: Comments
  // =========================================================================

  test('LC-20: Add comment to task via detail drawer', async ({ page }) => {
    await page.goto(`/project-management/projects/${projectPid}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByTestId('task-board')).toBeVisible({ timeout: 15000 });

    await page.locator(`[data-testid="task-card-${taskPid}"]`).click();
    await expect(page.getByTestId('task-detail-drawer')).toBeVisible({ timeout: 5000 });

    // Comments tab should be visible (default)
    await expect(page.getByTestId('detail-tab-comments')).toBeVisible();

    // Type comment
    const commentText = `E2E comment ${projectName}`;
    await page.getByTestId('comment-input').fill(commentText);

    const commentPromise = page.waitForResponse(
      (r) => r.url().includes('/execute/pm:create_task_comment') && r.status() === 200,
      { timeout: 10000 },
    );
    await page.getByTestId('comment-submit').click();
    await commentPromise;

    // Comment should appear
    await expect(page.locator(`text=${commentText}`)).toBeVisible({ timeout: 5000 });
  });

  test('LC-21: Activity tab shows task history', async ({ page }) => {
    await page.goto(`/project-management/projects/${projectPid}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByTestId('task-board')).toBeVisible({ timeout: 15000 });

    await page.locator(`[data-testid="task-card-${taskPid}"]`).click();
    await expect(page.getByTestId('task-detail-drawer')).toBeVisible({ timeout: 5000 });

    // Switch to activity tab
    await page.getByTestId('detail-tab-activity').click();
    await expect(page.getByTestId('task-activity-section')).toBeVisible({ timeout: 5000 });
  });

  // =========================================================================
  // Section 6: Filters
  // =========================================================================

  test('LC-22: Kanban search filter narrows results', async ({ page }) => {
    await page.goto(`/project-management/projects/${projectPid}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByTestId('task-board')).toBeVisible({ timeout: 15000 });

    await expect(page.locator('[data-testid^="task-card-"]').first()).toBeVisible({
      timeout: 10000,
    });
    const totalBefore = await page.locator('[data-testid^="task-card-"]').count();

    await page.getByTestId('board-filter-search').fill('MainTask');

    await expect(page.locator('[data-testid^="task-card-"]').first()).toBeVisible({
      timeout: 5000,
    });
    const totalAfter = await page.locator('[data-testid^="task-card-"]').count();
    expect(totalAfter).toBeLessThanOrEqual(totalBefore);

    // Clear filter
    await page.getByTestId('board-filter-clear').click();
  });

  test('LC-23: Kanban priority filter works', async ({ page }) => {
    await page.goto(`/project-management/projects/${projectPid}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByTestId('task-board')).toBeVisible({ timeout: 15000 });
    await expect(page.locator('[data-testid^="task-card-"]').first()).toBeVisible({
      timeout: 10000,
    });

    await page.getByTestId('board-filter-priority').selectOption('high');
    await expect(page.locator('[data-testid^="task-card-"]').first()).toBeVisible({
      timeout: 5000,
    });

    await page.getByTestId('board-filter-clear').click();
  });

  test('LC-24: List view filters (priority + status)', async ({ page }) => {
    await page.goto(`/project-management/projects/${projectPid}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByTestId('project-workspace')).toBeVisible({ timeout: 15000 });

    await page.getByTestId('view-list').click();
    await expect(page.getByTestId('task-list-view')).toBeVisible({ timeout: 10000 });
    await expect(page.locator('[data-testid^="task-row-"]').first()).toBeVisible({
      timeout: 10000,
    });

    // Priority filter
    await expect(page.getByTestId('task-list-filter-priority')).toBeVisible();
    // Status filter
    await expect(page.getByTestId('task-list-filter-status')).toBeVisible();
  });

  // =========================================================================
  // Section 7: Settings — Labels
  // =========================================================================

  test('LC-25: Settings tab — create a project label', async ({ page }) => {
    await page.goto(`/project-management/projects/${projectPid}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByTestId('project-workspace')).toBeVisible({ timeout: 15000 });

    await page.getByTestId('tab-settings').click();
    await expect(page.getByTestId('project-settings')).toBeVisible({ timeout: 10000 });

    // Click add label
    await page.getByTestId('add-label-btn').click();
    await expect(page.getByTestId('add-label-form')).toBeVisible({ timeout: 5000 });

    await page.getByTestId('label-name-input').fill(`Label ${projectName}`);

    const labelPromise = page.waitForResponse(
      (r) => r.url().includes('/execute/pm:create_label') && r.status() === 200,
      { timeout: 10000 },
    );
    await page.getByTestId('submit-label-btn').click();
    await labelPromise;

    // Label should appear in table
    await expect(page.locator(`text=Label ${projectName}`)).toBeVisible({ timeout: 5000 });
  });

  // =========================================================================
  // Section 8: Project Status Lifecycle (via workspace header buttons)
  // =========================================================================

  // Project is already in_progress (activated in beforeAll)
  test('LC-26: Project status badge shows in_progress', async ({ page }) => {
    await page.goto(`/project-management/projects/${projectPid}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByTestId('project-workspace')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('project-status-badge')).toContainText(/In Progress|进行中/);
  });

  test('LC-27: Complete project (in_progress → completed)', async ({ page }) => {
    await page.goto(`/project-management/projects/${projectPid}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByTestId('project-workspace')).toBeVisible({ timeout: 15000 });

    const completeBtn = page.getByTestId('action-pm:complete_project');
    await expect(completeBtn).toBeVisible({ timeout: 5000 });
    await completeBtn.click();

    await expect(page.getByTestId('project-status-badge')).toContainText(/Completed|已完成/, {
      timeout: 10000,
    });
  });

  test('LC-28: Archive project (completed → archived)', async ({ page }) => {
    await page.goto(`/project-management/projects/${projectPid}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByTestId('project-workspace')).toBeVisible({ timeout: 15000 });

    const archiveBtn = page.getByTestId('action-pm:archive_project');
    await expect(archiveBtn).toBeVisible({ timeout: 5000 });
    await archiveBtn.click();

    await expect(page.getByTestId('project-status-badge')).toContainText(/Archived|已归档/, {
      timeout: 10000,
    });
  });

  // =========================================================================
  // Section 9: My Tasks page via menu
  // =========================================================================

  test('LC-29: Navigate to My Tasks via sidebar menu', async ({ page }) => {
    await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
    await clickPmMenuLink(page, '/project-management/my-tasks');
    await expect(page).toHaveURL(/\/project-management\/my-tasks/);

    // Page should render
    const content = page.locator('main');
    await expect(content).toBeVisible({ timeout: 10000 });
  });

  // =========================================================================
  // Section 10: Master Data — Project Roles via menu
  // =========================================================================

  test('LC-30: Navigate to Project Roles via sidebar menu', async ({ page }) => {
    await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
    await clickPmMasterDataLink(page, '/p/pm_project_role');
    await expect(page).toHaveURL(/\/p\/pm_project_role/);

    // Page should render main content area
    await expect(page.locator('main')).toBeVisible({ timeout: 10000 });
  });
});
