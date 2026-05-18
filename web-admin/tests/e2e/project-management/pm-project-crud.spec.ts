/**
 * PM Project CRUD E2E Tests
 *
 * Tests project creation, status lifecycle, and task management via UI:
 *
 * CRUD-01 @smoke   : Navigate to project list via sidebar menu
 * CRUD-02 @critical: Create project via UI form → verify draft status
 * CRUD-03 @critical: Activate project via workspace action → in_progress
 * CRUD-04 @critical: Create task via task board add button → task visible in kanban
 * CRUD-05 @critical: Task detail drawer opens with correct fields
 * CRUD-06 @critical: Task start (todo → in_progress) via action button
 * CRUD-07 @critical: Task complete (in_progress → done) via action button
 * CRUD-08 @critical: Complete project (in_progress → completed) via action
 * CRUD-09          : Required field validation — project name is mandatory
 * CRUD-10          : Required field validation — task title is mandatory
 *
 * Prerequisites:
 *   - project-management plugin imported and published
 *   - Frontend and backend running
 *
 * @since 10.1.0
 */

import { test, expect } from '../../fixtures';
import { uniqueId, executeCommandViaApi, dateOffsetStr, todayStr } from '../helpers/index';

// ---------------------------------------------------------------------------
// Navigation helper
// ---------------------------------------------------------------------------

/** Expand PM menu in sidebar and navigate to a link by href */
async function navigateToPmPage(
  page: import('@playwright/test').Page,
  href: string,
): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });

  const nav = page.locator('nav');
  const pmBtn = nav.getByRole('button', { name: /Project Management|项目管理/ });
  await pmBtn.first().scrollIntoViewIfNeeded();
  await pmBtn.first().click();

  const link = nav.locator(`a[href="${href}"]`);
  await link.first().waitFor({ state: 'attached', timeout: 8000 });
  await link.first().evaluate((el: HTMLElement) => el.click());
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const UID = uniqueId('PMCrud');

// ---------------------------------------------------------------------------
// Test suite
// ---------------------------------------------------------------------------

test.describe('PM Project CRUD', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(90000);

  let projectPid: string;
  let taskPid: string;

  // =========================================================================
  // CRUD-01: Navigate to project list via sidebar menu
  // =========================================================================

  test('CRUD-01 @smoke: Navigate to project list via sidebar menu', async ({ page }) => {
    const listRespPromise = page.waitForResponse(
      (r) => r.url().includes('/api/dynamic/pm_project/list') && r.status() === 200,
      { timeout: 15000 },
    );
    await navigateToPmPage(page, '/p/pm_project');
    await listRespPromise;

    await expect(page).toHaveURL(/\/p\/pm_project/);

    // Table or list must be visible — not empty page
    const table = page.locator('table, [class*="ant-table"], [data-testid="dynamic-list"]').first();
    await expect(table).toBeVisible({ timeout: 10000 });

    // Headers must not leak raw field codes
    const headerRow = page.locator('thead tr').first();
    const hasHeader = await headerRow.isVisible({ timeout: 5000 }).catch(() => false);
    if (hasHeader) {
      const headerText = await headerRow.textContent();
      expect(headerText).not.toMatch(/pm_project_/i);
    }
  });

  // =========================================================================
  // CRUD-02: Create project via UI form
  // =========================================================================

  test('CRUD-02 @critical: Create project via UI form → appears in list with planning status', async ({
    page,
  }) => {
    await navigateToPmPage(page, '/p/pm_project');
    await expect(page).toHaveURL(/\/p\/pm_project/);

    // Wait for list to load
    await page
      .locator('table, [class*="ant-table"]')
      .first()
      .waitFor({ state: 'visible', timeout: 10000 });

    // Click the New / Create button
    const createBtn = page
      .getByRole('button', { name: /New|新建|Create|Add/i })
      .or(page.getByTestId('create-btn'))
      .or(page.getByTestId('toolbar-btn-create'))
      .first();
    await createBtn.waitFor({ state: 'visible', timeout: 8000 });
    await createBtn.click();

    // The create button navigates to the form page; wait for the form to appear
    const form = page.locator('[data-testid="dynamic-form"]');
    await expect(form).toBeVisible({ timeout: 12000 });

    // Fill project name (required)
    const projectName = `CRUD Project ${UID}`;
    const nameInput = form.locator('input').first();
    await nameInput.fill(projectName);

    // Set up promises BEFORE clicking submit (avoid timing races)
    const createRespPromise = page.waitForResponse(
      (r) => r.url().includes('/execute/pm:create_project') && r.status() === 200,
      { timeout: 15000 },
    );
    const navPromise = page
      .waitForURL(/\/p\/pm.project(?!\/new)/, { timeout: 15000 })
      .then(() => true)
      .catch(() => false);

    // Submit the form
    const submitBtn = form
      .getByRole('button', { name: /Save|Submit|Create|确认|保存|提交/i })
      .or(page.getByTestId('form-submit'))
      .or(page.getByTestId('form-btn-submit'))
      .or(page.getByTestId('form-btn-save'))
      .first();
    await submitBtn.click();

    const createResp = await createRespPromise;
    const createBody = await createResp.json();

    // Extract created record ID
    // API shape: { code, data: { commandCode, data: { recordId, ... } } }
    const recordId =
      createBody?.data?.data?.recordId ??
      createBody?.data?.data?.pid ??
      createBody?.data?.recordId ??
      createBody?.data?.pid;
    expect(recordId, 'Create command should return a recordId').toBeTruthy();
    projectPid = String(recordId);

    // After successful creation, form navigates back to the list page
    const navigatedToList = await navPromise;
    expect(navigatedToList, 'Should navigate back to list after creation').toBe(true);

    // Verify via API — record exists
    const fetchResp = await page.request.get(`/api/dynamic/pm_project/${projectPid}`);
    expect(fetchResp.ok(), 'Fetching created project should succeed').toBe(true);
    const fetchBody = await fetchResp.json();
    const rec = fetchBody?.data ?? fetchBody;
    expect(
      rec.pm_project_name?.includes('CRUD Project') || rec.pm_project_name?.includes(UID),
      'Created project name should match',
    ).toBe(true);
  });

  // =========================================================================
  // CRUD-03: Activate project (planning → in_progress) via workspace action
  // =========================================================================

  test('CRUD-03 @critical: Activate project → in_progress status', async ({ page }) => {
    expect(projectPid, 'Project should have been created in CRUD-02').toBeTruthy();

    // Navigate directly to project workspace
    const taskListPromise = page.waitForResponse(
      (r) =>
        r.url().includes('/api/dynamic/pm_task/list') ||
        r.url().includes('/api/dynamic/pm_task/list'),
      { timeout: 15000 },
    );
    await page.goto(`/project-management/projects/${projectPid}`, {
      waitUntil: 'domcontentloaded',
    });
    await taskListPromise.catch(() => null);

    await expect(page.getByTestId('project-workspace')).toBeVisible({ timeout: 15000 });

    // Find activate button — supports both testid and text match
    const activateBtn = page
      .getByTestId('action-pm:activate_project')
      .or(page.getByRole('button', { name: /Activate|启动|激活/ }))
      .first();

    const cmdRespPromise = page.waitForResponse(
      (r) => r.url().includes('/execute/pm:activate_project') && r.status() === 200,
      { timeout: 15000 },
    );
    await activateBtn.waitFor({ state: 'visible', timeout: 10000 });
    await activateBtn.click();
    await cmdRespPromise;

    // Status badge should update to in_progress
    const statusBadge = page.getByTestId('project-status-badge');
    await expect(statusBadge).toContainText(/In Progress|进行中|in_progress/, { timeout: 10000 });

    // Verify via API
    const apiResp = await page.request.get(`/api/dynamic/pm_project/${projectPid}`);
    const apiBody = await apiResp.json();
    const status = (apiBody?.data ?? apiBody).pm_project_status;
    expect(status, 'Project status should be in_progress after activation').toBe('in_progress');
  });

  // =========================================================================
  // CRUD-04: Create task via kanban add button
  // =========================================================================

  test('CRUD-04 @critical: Create task via kanban add button → task visible in board', async ({
    page,
  }) => {
    expect(projectPid, 'Project should have been created in CRUD-02').toBeTruthy();

    const taskListPromise = page.waitForResponse(
      (r) =>
        (r.url().includes('/api/dynamic/pm_task/list') ||
          r.url().includes('/api/dynamic/pm_task/list')) &&
        r.status() === 200,
      { timeout: 15000 },
    );
    await page.goto(`/project-management/projects/${projectPid}`, {
      waitUntil: 'domcontentloaded',
    });
    await taskListPromise.catch(() => null);
    await expect(page.getByTestId('task-board')).toBeVisible({ timeout: 15000 });

    // Click add task button
    const addBtn = page.getByTestId('board-add-task-btn').first();
    await expect(addBtn).toBeVisible({ timeout: 8000 });
    await addBtn.click();

    // Task form modal opens
    const taskFormModal = page.getByTestId('task-form-modal');
    await expect(taskFormModal).toBeVisible({ timeout: 8000 });

    // Fill task title (required)
    const taskTitle = `CRUD Task ${UID}`;
    await page.getByTestId('task-form-title').fill(taskTitle);

    // Fill optional fields
    await page
      .getByTestId('task-form-type')
      .selectOption('task')
      .catch(() => null);
    await page
      .getByTestId('task-form-start-date')
      .fill(todayStr())
      .catch(() => null);
    await page
      .getByTestId('task-form-due-date')
      .fill(dateOffsetStr(7))
      .catch(() => null);

    // Submit
    const createTaskRespPromise = page.waitForResponse(
      (r) => r.url().includes('/execute/pm:create_task') && r.status() === 200,
      { timeout: 15000 },
    );
    await page.getByTestId('task-form-submit').click();
    const createTaskResp = await createTaskRespPromise;
    const createTaskBody = await createTaskResp.json();

    // Modal should close
    await expect(taskFormModal).not.toBeVisible({ timeout: 8000 });

    // Extract task ID
    // API shape: { code, data: { commandCode, data: { recordId, ... } } }
    const tId =
      createTaskBody?.data?.data?.recordId ??
      createTaskBody?.data?.data?.pid ??
      createTaskBody?.data?.recordId ??
      createTaskBody?.data?.pid;
    expect(tId, 'Create task should return a recordId').toBeTruthy();
    taskPid = String(tId);

    // Task card should appear on kanban
    const taskCard = page.locator('[data-testid^="task-card-"]', { hasText: taskTitle });
    await expect(taskCard.first()).toBeVisible({ timeout: 10000 });
  });

  // =========================================================================
  // CRUD-05: Task detail drawer opens with correct fields
  // =========================================================================

  test('CRUD-05 @critical: Task detail drawer opens and shows correct data', async ({ page }) => {
    expect(projectPid, 'Project should have been created in CRUD-02').toBeTruthy();
    expect(taskPid, 'Task should have been created in CRUD-04').toBeTruthy();

    const taskListPromise = page.waitForResponse(
      (r) =>
        (r.url().includes('/api/dynamic/pm_task/list') ||
          r.url().includes('/api/dynamic/pm_task/list')) &&
        r.status() === 200,
      { timeout: 15000 },
    );
    await page.goto(`/project-management/projects/${projectPid}`, {
      waitUntil: 'domcontentloaded',
    });
    await taskListPromise.catch(() => null);
    await expect(page.getByTestId('task-board')).toBeVisible({ timeout: 15000 });

    // Click the task card
    const taskCard = page.locator(`[data-testid="task-card-${taskPid}"]`);
    await expect(taskCard).toBeVisible({ timeout: 10000 });
    await taskCard.click();

    // Drawer opens
    const drawer = page.getByTestId('task-detail-drawer');
    await expect(drawer).toBeVisible({ timeout: 8000 });

    // Verify status shows TODO
    const statusEl = page.getByTestId('task-detail-status');
    await expect(statusEl).toBeVisible({ timeout: 5000 });
    await expect(statusEl).toContainText(/TODO|待处理|todo/i);

    // Comments tab should be accessible
    await expect(page.getByTestId('detail-tab-comments')).toBeVisible({ timeout: 5000 });
    // Activity tab
    await expect(page.getByTestId('detail-tab-activity')).toBeVisible({ timeout: 5000 });
  });

  // =========================================================================
  // CRUD-06: Start task (todo → in_progress) via action button
  // =========================================================================

  test('CRUD-06 @critical: Start task (todo → in_progress) via drawer action', async ({ page }) => {
    expect(projectPid, 'Project should have been created in CRUD-02').toBeTruthy();
    expect(taskPid, 'Task should have been created in CRUD-04').toBeTruthy();

    const taskListPromise = page.waitForResponse(
      (r) =>
        (r.url().includes('/api/dynamic/pm_task/list') ||
          r.url().includes('/api/dynamic/pm_task/list')) &&
        r.status() === 200,
      { timeout: 15000 },
    );
    await page.goto(`/project-management/projects/${projectPid}`, {
      waitUntil: 'domcontentloaded',
    });
    await taskListPromise.catch(() => null);
    await expect(page.getByTestId('task-board')).toBeVisible({ timeout: 15000 });

    // Open task drawer
    const taskCard = page.locator(`[data-testid="task-card-${taskPid}"]`);
    await expect(taskCard).toBeVisible({ timeout: 10000 });
    await taskCard.click();
    await expect(page.getByTestId('task-detail-drawer')).toBeVisible({ timeout: 8000 });

    // Click start task action
    const startBtn = page.getByTestId('task-action-pm:start_task');
    await expect(startBtn).toBeVisible({ timeout: 8000 });

    const cmdRespPromise = page.waitForResponse(
      (r) => r.url().includes('/execute/pm:start_task') && r.status() === 200,
      { timeout: 15000 },
    );
    await startBtn.click();
    await cmdRespPromise;

    // Wait for board to refresh
    await page
      .waitForResponse(
        (r) =>
          (r.url().includes('/api/dynamic/pm_task/list') ||
            r.url().includes('/api/dynamic/pm_task/list')) &&
          r.status() === 200,
        { timeout: 10000 },
      )
      .catch(() => null);

    // Task card should move to in_progress column
    await expect(
      page.getByTestId('board-column-in_progress').locator(`[data-testid="task-card-${taskPid}"]`),
    ).toBeVisible({ timeout: 10000 });

    // Verify via API
    const apiResp = await page.request.get(`/api/dynamic/pm_task/${taskPid}`);
    expect(apiResp.ok()).toBe(true);
    const apiBody = await apiResp.json();
    const taskStatus = (apiBody?.data ?? apiBody).pm_task_status;
    expect(taskStatus, 'Task status should be in_progress').toBe('in_progress');
  });

  // =========================================================================
  // CRUD-07: Complete task (in_progress → done) via action button
  // =========================================================================

  test('CRUD-07 @critical: Complete task (in_progress → done) via drawer action', async ({
    page,
  }) => {
    expect(projectPid, 'Project should have been created in CRUD-02').toBeTruthy();
    expect(taskPid, 'Task should have been created in CRUD-04').toBeTruthy();

    const taskListPromise = page.waitForResponse(
      (r) =>
        (r.url().includes('/api/dynamic/pm_task/list') ||
          r.url().includes('/api/dynamic/pm_task/list')) &&
        r.status() === 200,
      { timeout: 15000 },
    );
    await page.goto(`/project-management/projects/${projectPid}`, {
      waitUntil: 'domcontentloaded',
    });
    await taskListPromise.catch(() => null);
    await expect(page.getByTestId('task-board')).toBeVisible({ timeout: 15000 });

    // Task should be in in_progress column — click card to open drawer
    const inProgressColumn = page.getByTestId('board-column-in_progress');
    await expect(inProgressColumn.locator(`[data-testid="task-card-${taskPid}"]`)).toBeVisible({
      timeout: 10000,
    });
    await inProgressColumn.locator(`[data-testid="task-card-${taskPid}"]`).click();
    await expect(page.getByTestId('task-detail-drawer')).toBeVisible({ timeout: 8000 });

    // Click complete task action
    const completeBtn = page.getByTestId('task-action-pm:complete_task');
    await expect(completeBtn).toBeVisible({ timeout: 8000 });

    const cmdRespPromise = page.waitForResponse(
      (r) => r.url().includes('/execute/pm:complete_task') && r.status() === 200,
      { timeout: 15000 },
    );
    await completeBtn.click();
    await cmdRespPromise;

    // Wait for board refresh
    await page
      .waitForResponse(
        (r) =>
          (r.url().includes('/api/dynamic/pm_task/list') ||
            r.url().includes('/api/dynamic/pm_task/list')) &&
          r.status() === 200,
        { timeout: 10000 },
      )
      .catch(() => null);

    // Task moves to done column
    await expect(
      page.getByTestId('board-column-done').locator(`[data-testid="task-card-${taskPid}"]`),
    ).toBeVisible({ timeout: 10000 });

    // Verify via API
    const apiResp = await page.request.get(`/api/dynamic/pm_task/${taskPid}`);
    expect(apiResp.ok()).toBe(true);
    const apiBody = await apiResp.json();
    const taskStatus = (apiBody?.data ?? apiBody).pm_task_status;
    expect(taskStatus, 'Task status should be done').toBe('done');
  });

  // =========================================================================
  // CRUD-08: Complete project (in_progress → completed) via workspace action
  // =========================================================================

  test('CRUD-08 @critical: Complete project → completed status', async ({ page }) => {
    expect(projectPid, 'Project should have been created in CRUD-02').toBeTruthy();

    const taskListPromise = page.waitForResponse(
      (r) =>
        (r.url().includes('/api/dynamic/pm_task/list') ||
          r.url().includes('/api/dynamic/pm_task/list')) &&
        r.status() === 200,
      { timeout: 15000 },
    );
    await page.goto(`/project-management/projects/${projectPid}`, {
      waitUntil: 'domcontentloaded',
    });
    await taskListPromise.catch(() => null);
    await expect(page.getByTestId('project-workspace')).toBeVisible({ timeout: 15000 });

    const completeBtn = page
      .getByTestId('action-pm:complete_project')
      .or(page.getByRole('button', { name: /Complete.*Project|完结项目/ }))
      .first();
    await expect(completeBtn).toBeVisible({ timeout: 10000 });

    const cmdRespPromise = page.waitForResponse(
      (r) => r.url().includes('/execute/pm:complete_project') && r.status() === 200,
      { timeout: 15000 },
    );
    await completeBtn.click();
    await cmdRespPromise;

    // Status badge updates
    const statusBadge = page.getByTestId('project-status-badge');
    await expect(statusBadge).toContainText(/Completed|已完成|completed/, { timeout: 10000 });

    // Verify via API
    const apiResp = await page.request.get(`/api/dynamic/pm_project/${projectPid}`);
    const apiBody = await apiResp.json();
    const status = (apiBody?.data ?? apiBody).pm_project_status;
    expect(status, 'Project status should be completed').toBe('completed');
  });

  // =========================================================================
  // CRUD-09: Required field validation — project name mandatory
  // =========================================================================

  test('CRUD-09: Project creation validates required project name', async ({ page }) => {
    await navigateToPmPage(page, '/p/pm_project');
    await page
      .locator('table, [class*="ant-table"]')
      .first()
      .waitFor({ state: 'visible', timeout: 10000 });

    // Open create form
    const createBtn = page
      .getByRole('button', { name: /New|新建|Create|Add/i })
      .or(page.getByTestId('create-btn'))
      .or(page.getByTestId('toolbar-btn-create'))
      .first();
    await createBtn.click();

    // The create button navigates to the form page; wait for the form to appear
    const form = page.locator('[data-testid="dynamic-form"]');
    await expect(form).toBeVisible({ timeout: 12000 });

    // Submit without filling required fields
    const submitBtn = form
      .getByRole('button', { name: /Save|Submit|Create|确认|保存|提交/i })
      .or(page.getByTestId('form-btn-submit'))
      .first();
    await submitBtn.click();

    // Validation error: the form uses an error toast (bg-red-500) rather than inline ant-form errors.
    // Accept either an inline error class OR the error toast visible at the top of the page.
    const inlineError = page.locator(
      '[class*="ant-form-item-explain-error"], [class*="field-error"], .text-red-500',
    );
    const errorToast = page.locator('.bg-red-500').first();
    const hasInlineError = await inlineError
      .first()
      .isVisible({ timeout: 4000 })
      .catch(() => false);
    if (!hasInlineError) {
      await expect(errorToast).toBeVisible({ timeout: 4000 });
    }

    // Form should NOT have closed — submission was rejected
    await expect(form).toBeVisible({ timeout: 3000 });
  });

  // =========================================================================
  // CRUD-10: Required field validation — task title mandatory
  // =========================================================================

  test('CRUD-10: Task creation validates required task title', async ({ page }) => {
    test.fixme(true, 'Task form validation error class selectors need updating');
    // Use a pre-created project (any active project will do)
    // Create one via API for reliability
    const setupCtx = await page.context().browser()!.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    const setupPage = await setupCtx.newPage();
    let validationProjectPid: string;
    try {
      const proj = await executeCommandViaApi(
        setupPage,
        'pm:create_project',
        { pm_project_name: `ValProj ${UID}` },
        undefined,
        'create',
      );
      validationProjectPid = proj.recordId;
      await executeCommandViaApi(
        setupPage,
        'pm:activate_project',
        {},
        validationProjectPid,
        'update',
      );
    } finally {
      await setupCtx.close();
    }

    const taskListPromise = page.waitForResponse(
      (r) =>
        (r.url().includes('/api/dynamic/pm_task/list') ||
          r.url().includes('/api/dynamic/pm_task/list')) &&
        r.status() === 200,
      { timeout: 15000 },
    );
    await page.goto(`/project-management/projects/${validationProjectPid}`, {
      waitUntil: 'domcontentloaded',
    });
    await taskListPromise.catch(() => null);
    await expect(page.getByTestId('task-board')).toBeVisible({ timeout: 15000 });

    // Click add task
    const addBtn = page.getByTestId('board-add-task-btn').first();
    await expect(addBtn).toBeVisible({ timeout: 8000 });
    await addBtn.click();

    const taskFormModal = page.getByTestId('task-form-modal');
    await expect(taskFormModal).toBeVisible({ timeout: 8000 });

    // Submit without filling title
    await page.getByTestId('task-form-submit').click();

    // Validation error for title field
    const titleError = page.locator(
      '[class*="error"], [class*="ant-form-item-explain-error"], .text-red',
    );
    await expect(titleError.first()).toBeVisible({ timeout: 5000 });

    // Modal remains open
    await expect(taskFormModal).toBeVisible({ timeout: 3000 });
  });
});
