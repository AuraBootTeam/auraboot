/**
 * PM Error Branches E2E Tests
 *
 * Tests error scenarios and validation:
 *   - Invalid state transitions (precondition violations)
 *   - Required field validation on create/update
 *   - Delete preconditions (only PLANNING projects can be deleted)
 *   - Attempting actions on archived/completed projects
 *
 * Enters via sidebar menu navigation, not page.goto().
 * NO CLEANUP — test data is preserved as verification evidence.
 *
 * @since 7.3.0
 */

import { test, expect } from '@playwright/test';
import { uniqueId, executeCommandViaApi } from '../helpers/index';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

async function clickPmMenuLink(page: import('@playwright/test').Page, href: string) {
  const pmMenu = page.locator('button', { hasText: /Project Management|项目管理/ });
  await pmMenu.first().scrollIntoViewIfNeeded();
  await pmMenu.first().click();

  const link = page.locator(`a[href="${href}"]`);
  await link.first().waitFor({ state: 'attached', timeout: 5000 });
  await link.first().evaluate((el) => (el as HTMLAnchorElement).click());
}

async function navigateToProjectWorkspace(
  page: import('@playwright/test').Page,
  projectPid: string,
) {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  await clickPmMenuLink(page, '/p/pm_project');
  await expect(page).toHaveURL(/\/p\/pm_project/);

  // Navigate to workspace directly via row click or URL
  await page.goto(`/project-management/projects/${projectPid}`, { waitUntil: 'domcontentloaded' });
  await expect(page.getByTestId('project-workspace')).toBeVisible({ timeout: 15000 });
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

test.describe('PM Error Branches', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60000);

  const projectName = uniqueId('E2EError');
  let projectPid: string;
  let taskPid: string;

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      // Create project (PLANNING state)
      const proj = await executeCommandViaApi(
        page,
        'pm:create_project',
        { pm_project_name: projectName },
        undefined,
        'create',
      );
      projectPid = proj.recordId;
      expect(projectPid).toBeTruthy();

      // Activate project (PLANNING → in_progress)
      await executeCommandViaApi(page, 'pm:activate_project', {}, projectPid, 'update');

      // Create a task
      const t = await executeCommandViaApi(
        page,
        'pm:create_task',
        {
          pm_task_title: `ErrorTest ${projectName}`,
          pm_task_project_id: projectPid,
          pm_task_type: 'task',
          pm_task_priority: 'medium',
        },
        undefined,
        'create',
      );
      taskPid = t.recordId;
      expect(taskPid).toBeTruthy();
    } finally {
      await ctx.close();
    }
  });

  // =========================================================================
  // Invalid State Transitions (API level)
  // =========================================================================

  test('PM-ERR-01: Cannot activate already active project (API)', async ({ page }) => {
    // Project is in_progress, trying to activate again should fail
    const result = await executeCommandViaApi(
      page,
      'pm:activate_project',
      {},
      projectPid,
      'update',
      { allowHttpError: true },
    );
    // Should return error code (not '0')
    expect(result.code).not.toBe('0');
  });

  test('PM-ERR-02: Cannot archive non-completed project (API)', async ({ page }) => {
    // Project is in_progress, archive requires completed
    const result = await executeCommandViaApi(
      page,
      'pm:archive_project',
      {},
      projectPid,
      'update',
      { allowHttpError: true },
    );
    expect(result.code).not.toBe('0');
  });

  test('PM-ERR-03: Cannot complete TODO task directly (API)', async ({ page }) => {
    // Task is TODO, complete requires in_progress
    const result = await executeCommandViaApi(page, 'pm:complete_task', {}, taskPid, 'update', {
      allowHttpError: true,
    });
    expect(result.code).not.toBe('0');
  });

  test('PM-ERR-04: Cannot start already started task (API)', async ({ page }) => {
    // Start the task first
    await executeCommandViaApi(page, 'pm:start_task', {}, taskPid, 'update');

    // Now try to start again — should fail
    const result = await executeCommandViaApi(page, 'pm:start_task', {}, taskPid, 'update', {
      allowHttpError: true,
    });
    expect(result.code).not.toBe('0');
  });

  // =========================================================================
  // Required Field Validation (UI level)
  // =========================================================================

  test('PM-ERR-05: Task create form requires title field', async ({ page }) => {
    await navigateToProjectWorkspace(page, projectPid);
    await expect(page.getByTestId('task-board')).toBeVisible({ timeout: 15000 });

    // Open task form via add button
    const addBtn = page.getByTestId('board-add-task-btn').first();
    await expect(addBtn).toBeVisible({ timeout: 5000 });
    await addBtn.evaluate((el: HTMLElement) => el.click());
    const modal = page.locator('[role="dialog"], [data-testid="task-form-modal"]').first();
    await expect(modal).toBeVisible({ timeout: 5000 });

    // Leave title empty, try to submit
    const submitBtn = page.getByTestId('task-form-submit');

    // Submit button should be disabled when title is empty, or submission should fail
    const isDisabled = await submitBtn.isDisabled();
    if (!isDisabled) {
      // Click submit — should show validation error or prevent submission
      await submitBtn.click();

      // Modal should still be open (submission failed)
      await expect(modal).toBeVisible({ timeout: 3000 });
    }

    // Close modal
    await page.getByTestId('task-form-modal-close').click();
  });

  test('PM-ERR-06: Task create form validates and submits with required fields', async ({
    page,
  }) => {
    await navigateToProjectWorkspace(page, projectPid);
    await expect(page.getByTestId('task-board')).toBeVisible({ timeout: 15000 });

    const addBtn = page.getByTestId('board-add-task-btn').first();
    await expect(addBtn).toBeVisible({ timeout: 5000 });
    await addBtn.evaluate((el: HTMLElement) => el.click());
    const modal = page.locator('[role="dialog"], [data-testid="task-form-modal"]').first();
    await expect(modal).toBeVisible({ timeout: 5000 });

    // Fill only required field (title)
    const validTitle = `ValidTask ${projectName}`;
    await page.getByTestId('task-form-title').fill(validTitle);

    const createPromise = page.waitForResponse(
      (r) => r.url().includes('/execute/pm:create_task') && r.status() === 200,
      { timeout: 10000 },
    );
    await page.getByTestId('task-form-submit').click();
    await createPromise;

    // Modal should close on success
    await expect(modal).not.toBeVisible({ timeout: 5000 });

    // New task should appear
    await expect(page.locator(`text=${validTitle}`)).toBeVisible({ timeout: 10000 });
  });

  // =========================================================================
  // Delete Preconditions
  // =========================================================================

  test('PM-ERR-07: Cannot delete non-PLANNING project (API)', async ({ page }) => {
    // Project is in_progress — delete should fail (precondition: only PLANNING)
    const result = await executeCommandViaApi(page, 'pm:delete_project', {}, projectPid, 'delete', {
      allowHttpError: true,
    });
    expect(result.code).not.toBe('0');
  });

  test('PM-ERR-08: Invalid state transition buttons not shown in UI', async ({ page }) => {
    await navigateToProjectWorkspace(page, projectPid);

    // Project is in_progress — activate button should NOT be visible
    const activateBtn = page.getByTestId('action-pm:activate_project');
    await expect(activateBtn).not.toBeVisible({ timeout: 3000 });

    // Archive button should NOT be visible (requires completed)
    const archiveBtn = page.getByTestId('action-pm:archive_project');
    await expect(archiveBtn).not.toBeVisible({ timeout: 3000 });

    // Complete button SHOULD be visible (valid transition from in_progress)
    const completeBtn = page.getByTestId('action-pm:complete_project');
    await expect(completeBtn).toBeVisible({ timeout: 5000 });
  });
});
