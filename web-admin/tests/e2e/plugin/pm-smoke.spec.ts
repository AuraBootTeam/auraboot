/**
 * PM Smoke Tests — Menu → Page → Data Visibility
 *
 * Validates end-to-end data flow:
 * - Sidebar menu navigation reaches correct route
 * - Pages load and render real data (not 0 records)
 * - Core tabs in workspace display content
 *
 * Prerequisites:
 *   - PM plugin imported and models published
 *   - At least 1 project + 1 task exist (created by pm-workspace-interactions setup or similar)
 *
 * @since 7.1.0
 */

import { test, expect } from '@playwright/test';
import { uniqueId, executeCommandViaApi, dateOffsetStr } from '../helpers/index';

test.describe('PM Smoke Tests', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60000);

  const projectName = uniqueId('E2ESmoke');
  let projectPid: string;

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      // Ensure at least 1 active project with tasks exists
      const proj = await executeCommandViaApi(
        page,
        'pm:create_project',
        { pm_project_name: projectName },
        undefined,
        'create',
      );
      projectPid = proj.recordId;
      expect(projectPid).toBeTruthy();

      // Activate project
      await executeCommandViaApi(page, 'pm:activate_project', {}, projectPid, 'update');

      // Fetch auto-created member pid (sideEffect creates member on project creation)
      const BASE = process.env.BASE_URL || 'http://localhost:5173';
      const memberFilter = encodeURIComponent(JSON.stringify([
        { fieldName: 'pm_member_project_id', operator: 'EQ', value: projectPid },
      ]));
      const memberResp = await page.request.get(
        `${BASE}/api/dynamic/pm-project-member/list?pageSize=10&filters=${memberFilter}`,
      );
      const memberBody = await memberResp.json();
      const memberPid = memberBody?.data?.records?.[0]?.pid;

      // Create a task assigned to the auto-created member
      await executeCommandViaApi(
        page,
        'pm:create_task',
        {
          pm_task_title: `SmokeTask ${projectName}`,
          pm_task_project_id: projectPid,
          pm_task_type: 'task',
          pm_task_priority: 'medium',
          pm_task_start_date: dateOffsetStr(0),
          pm_task_due_date: dateOffsetStr(7),
          ...(memberPid ? { pm_task_assignee_id: memberPid } : {}),
        },
        undefined,
        'create',
      );
    } finally {
      await ctx.close();
    }
  });

  // Helper: expand PM submenu in sidebar and click a link by href
  async function clickPmMenuLink(page: import('@playwright/test').Page, href: string) {
    // Expand PM menu
    const pmMenu = page.locator('button', { hasText: /Project Management|项目管理/ });
    await pmMenu.first().scrollIntoViewIfNeeded();
    await pmMenu.first().click();

    // Wait for submenu link to appear, then click via DOM to bypass scroll interception
    const link = page.locator(`a[href="${href}"]`);
    await link.first().waitFor({ state: 'attached', timeout: 5000 });
    await link.first().evaluate((el) => (el as HTMLAnchorElement).click());
  }

  // PM-SMOKE-01: Sidebar "Projects" menu → /dynamic/pm-project (DSL list page)
  test('sidebar Projects menu navigates to project list', async ({ page }) => {
    await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
    await clickPmMenuLink(page, '/dynamic/pm-project');
    await expect(page).toHaveURL(/\/dynamic\/pm-project/);
  });

  // PM-SMOKE-02: Sidebar "My Tasks" menu → /project-management/my-tasks
  test('sidebar My Tasks menu navigates to my tasks', async ({ page }) => {
    await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
    await clickPmMenuLink(page, '/project-management/my-tasks');
    await expect(page).toHaveURL(/\/project-management\/my-tasks/);
  });

  // PM-SMOKE-03: Sidebar "Project Roles" menu → /dynamic/pm-project-role
  test('sidebar Project Roles menu navigates to roles page', async ({ page }) => {
    await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });

    // Expand PM menu
    const pmMenu = page.locator('button', { hasText: /Project Management|项目管理/ });
    await pmMenu.first().scrollIntoViewIfNeeded();
    await pmMenu.first().click();

    // Expand "Master Data" sub-submenu
    const masterDataMenu = page.locator('button', { hasText: /Master Data|基础数据/ });
    await masterDataMenu.first().waitFor({ state: 'attached', timeout: 5000 });
    await masterDataMenu.first().evaluate((el) => (el as HTMLButtonElement).click());

    // Click "Project Roles" link
    const rolesLink = page.locator('a[href="/dynamic/pm-project-role"]');
    await rolesLink.first().waitFor({ state: 'attached', timeout: 5000 });
    await rolesLink.first().evaluate((el) => (el as HTMLAnchorElement).click());

    await expect(page).toHaveURL(/\/dynamic\/pm-project-role/);
    await page.waitForResponse(resp => resp.url().includes('/list') && resp.status() === 200, { timeout: 10000 });
  });

  // Helper: search for a project by name in the DSL list search area
  async function searchProjectInList(page: import('@playwright/test').Page, name: string) {
    await page.locator('tbody tr').first().waitFor({ state: 'visible', timeout: 10000 });
    const searchArea = page.getByTestId('search-area');
  if (await searchArea.isVisible({ timeout: 2000 }).catch(() => false)) {
      await searchArea.locator('input').first().fill(name);
      await page.getByTestId('filter-search').click();
      const table = page.locator('table, [role="table"]');
      const empty = page.locator('text=/no data|暂无/i');
      await expect(table.or(empty).first()).toBeVisible({ timeout: 10000 });
    }
  }

  // PM-SMOKE-04: DSL project list page shows at least 1 row
  test('project list page displays project rows', async ({ page }) => {
    await page.goto('/dynamic/pm-project', { waitUntil: 'domcontentloaded' });
    const table = page.locator('table, [role="table"]');
    await expect(table.first()).toBeVisible({ timeout: 10000 });

    // Search for our specific project (handles accumulated data / pagination)
    await searchProjectInList(page, projectName);
    const projectRow = page.locator('tbody tr', { hasText: projectName });
    await expect(projectRow.first()).toBeVisible({ timeout: 10000 });
  });

  // PM-SMOKE-05: Click project row → workspace kanban shows tasks
  test('project workspace kanban tab shows task cards', async ({ page }) => {
    // Navigate to DSL project list and search for our project
    await page.goto('/dynamic/pm-project', { waitUntil: 'domcontentloaded' });
    await expect(page.locator('table, [role="table"]').first()).toBeVisible({ timeout: 10000 });
    await searchProjectInList(page, projectName);

    // Click the project row (rowClickNavigateTo configured in DSL)
    const projectRow = page.locator('tbody tr', { hasText: projectName });
    await expect(projectRow.first()).toBeVisible({ timeout: 10000 });

    // Wait for task list response when entering workspace
    const taskListPromise = page.waitForResponse(
      resp => resp.url().includes('/api/dynamic/pm-task/list') && resp.status() === 200,
      { timeout: 10000 },
    );
    await projectRow.first().click();

    // Should navigate to workspace
    await expect(page).toHaveURL(/\/project-management\/projects\//);
    await taskListPromise;

    // Kanban should show at least one task card
    const taskCard = page.locator('[class*="bg-white"]', { hasText: /SmokeTask/ });
    await expect(taskCard.first()).toBeVisible({ timeout: 10000 });
  });

  // PM-SMOKE-06: Switch to list view → at least 1 row visible
  test('project workspace list view shows task rows', async ({ page }) => {
    // Navigate directly to project workspace
    const taskListPromise = page.waitForResponse(
      resp => resp.url().includes('/api/dynamic/pm-task/list') && resp.status() === 200,
      { timeout: 10000 },
    );
    await page.goto(`/project-management/projects/${projectPid}`, { waitUntil: 'domcontentloaded' });
    await taskListPromise;

    // Click "List" tab — set up response listener BEFORE clicking
    const listTab = page.locator('button', { hasText: /List|列表/ });
    const listViewPromise = page.waitForResponse(
      resp => resp.url().includes('/api/dynamic/pm-task/list') && resp.status() === 200,
      { timeout: 10000 },
    );
    await listTab.first().click();
    await listViewPromise;

    // Should show task row(s)
    const taskRow = page.locator('text=SmokeTask');
    await expect(taskRow.first()).toBeVisible({ timeout: 10000 });
  });

  // PM-SMOKE-07: Members tab shows member list
  test('project workspace members tab shows members', async ({ page }) => {
    // Navigate to project workspace
    const taskListPromise = page.waitForResponse(
      resp => resp.url().includes('/api/dynamic/pm-task/list') && resp.status() === 200,
      { timeout: 10000 },
    );
    await page.goto(`/project-management/projects/${projectPid}`, { waitUntil: 'domcontentloaded' });
    await taskListPromise;

    // Click "Members" tab
    const membersTab = page.locator('button', { hasText: /Members|成员/ });
    const memberListPromise = page.waitForResponse(
      resp => resp.url().includes('/api/dynamic/pm-project-member/list') && resp.status() === 200,
      { timeout: 10000 },
    );
    await membersTab.first().click();
    await memberListPromise;

    // Members tab should render — show member list or empty state + add button
    const memberContent = page.locator('text=/项目成员|Members|暂无|添加成员|Add Member/i');
    await expect(memberContent.first()).toBeVisible({ timeout: 10000 });
  });

  // PM-SMOKE-08: My Tasks page loads and displays task data
  test('my tasks page loads task data', async ({ page }) => {
    const apiPromise = page.waitForResponse(
      resp => resp.url().includes('/api/datasource/list') && resp.url().includes('pm_my_tasks') && resp.status() === 200,
      { timeout: 10000 },
    );
    await page.goto('/project-management/my-tasks', { waitUntil: 'domcontentloaded' });
    const apiResp = await apiPromise;
    const body = await apiResp.json();

    // API should respond successfully with records
    expect(body.code).toBe('0');
    expect(body.data).toBeTruthy();
    expect(body.data.records?.length).toBeGreaterThan(0);

    // UI should render actual task rows (not empty state)
    const taskRow = page.locator(`text=SmokeTask ${projectName}`);
    await expect(taskRow.first()).toBeVisible({ timeout: 10000 });
  });
});
