/**
 * PM Plugin — Master Data CRUD E2E Tests
 *
 * Covers list, UI create, edit, delete for pm_project_role via DSL pages.
 * Tests enter via sidebar menu, not page.goto().
 * NO CLEANUP — test data is preserved as verification evidence.
 *
 * @since 7.3.0
 */

import { test, expect } from '@playwright/test';
import {
  acceptConfirmDialog,
  uniqueId,
  executeCommandViaApi,
  extractRecordId,
  ensureFilterFormOpen,
  clickRowActionByLocator,
} from '../helpers/index';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Expand PM > Master Data submenu and click Project Roles link */
async function navigateToProjectRoles(page: import('@playwright/test').Page) {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });

  const pmMenu = page.locator('button', { hasText: /Project Management|项目管理/ });
  await pmMenu.first().scrollIntoViewIfNeeded();
  await pmMenu.first().click();

  const masterDataMenu = page.locator('button', { hasText: /Master Data|基础数据/ });
  await masterDataMenu.first().waitFor({ state: 'attached', timeout: 5000 });
  await masterDataMenu.first().evaluate((el) => (el as HTMLButtonElement).click());

  const link = page.locator('a[href="/p/pm_project_role"]');
  await link.first().waitFor({ state: 'attached', timeout: 5000 });
  await link.first().evaluate((el) => (el as HTMLAnchorElement).click());

  await expect(page).toHaveURL(/\/p\/pm_project_role/);
  // Wait for table to render
  const table = page.locator('table, [role="table"]');
  const empty = page.locator('text=/no data|暂无/i');
  await expect(table.or(empty).first()).toBeVisible({ timeout: 15000 });
}

/** Search for a role by name using DSL filter form (handles pagination) */
async function searchRole(page: import('@playwright/test').Page, name: string) {
  await ensureFilterFormOpen(page);
  // DSL filter form renders a textbox labeled "角色名称"
  const filterInput = page.getByRole('textbox', { name: /角色名称|pm_role_name/i });
  await expect(filterInput).toBeVisible({ timeout: 5000 });
  await filterInput.fill(name);
  const searchBtn = page.locator('[data-testid="filter-search"]');
  await searchBtn.click();
  const table = page.locator('table, [role="table"]');
  const empty = page.locator('text=/no data|暂无/i');
  await expect(table.or(empty).first()).toBeVisible({ timeout: 10000 });
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

test.describe('PM Master Data — Project Role CRUD', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60000);

  const roleName = uniqueId('TestRole');
  const updatedRoleName = `${roleName}_Updated`;
  const deleteRoleName = uniqueId('DelRole');
  let rolePid: string | undefined;
  let deleteRolePid: string | undefined;

  // NO afterAll cleanup — test data is preserved

  test('PM-ROLE-01: Navigate to project role list via sidebar menu @smoke', async ({ page }) => {
    await navigateToProjectRoles(page);
    const table = page.locator('table, [role="table"]');
    const empty = page.locator('text=/no data|暂无/i');
    await expect(table.or(empty).first()).toBeVisible({ timeout: 10000 });
  });

  test('PM-ROLE-02: Create project role via API, verify in list', async ({ page }) => {
    const result = await executeCommandViaApi(
      page,
      'pm:create_project_role',
      {
        pm_role_name: roleName,
        pm_role_description: 'E2E test role',
      },
      undefined,
      'create',
    );
    rolePid = result.recordId;
    expect(rolePid).toBeTruthy();

    await navigateToProjectRoles(page);
    await searchRole(page, roleName);
    await expect(page.locator(`text=${roleName}`)).toBeVisible({ timeout: 10000 });
  });

  test('PM-ROLE-03: Create project role via UI form', async ({ page }) => {
    await navigateToProjectRoles(page);

    // Click the DSL "新建" button in toolbar
    const addBtn = page.locator('button:has-text("新建"), button:has-text("Create")');
    await expect(addBtn.first()).toBeVisible({ timeout: 5000 });
    await addBtn.first().click();

    // Wait for DSL form page to load (URL uses underscores: pm_project_role)
    await expect(page).toHaveURL(/\/p\/pm_project_role\/new/, { timeout: 10000 });

    // DSL form fields use data-testid="form-field-{fieldCode}"
    const nameField = page.getByTestId('form-field-pm_role_name');
    await expect(nameField).toBeVisible({ timeout: 10000 });
    await nameField.locator('input').fill(deleteRoleName);

    const descField = page.getByTestId('form-field-pm_role_description');
    if (await descField.isVisible({ timeout: 2000 }).catch(() => false)) {
      await descField.locator('input, textarea').first().fill('Created via UI for delete test');
    }

    // Submit via DSL form button
    const submitPromise = page.waitForResponse(
      (r) => r.url().includes('/execute/pm:create_project_role') && r.status() === 200,
      { timeout: 10000 },
    );
    const saveBtn = page
      .getByTestId('form-btn-pm:create_project_role')
      .or(page.locator('button[type="submit"], button:has-text("Save"), button:has-text("保存")'));
    await saveBtn.first().click();
    const submitResp = await submitPromise;
    const submitBody = await submitResp.json();
    deleteRolePid = extractRecordId(submitBody);

    // Navigate back to list and verify via search
    await navigateToProjectRoles(page);
    await searchRole(page, deleteRoleName);
    await expect(page.locator(`text=${deleteRoleName}`)).toBeVisible({ timeout: 10000 });
  });

  test('PM-ROLE-04: Edit project role via UI', async ({ page }) => {
    test.skip(!rolePid, 'No role created');
    await navigateToProjectRoles(page);
    await searchRole(page, roleName);

    // Find row with our role and click "edit" button
    const roleRow = page.locator('tbody tr', { hasText: roleName }).first();
    await expect(roleRow).toBeVisible({ timeout: 10000 });

    const editBtn = roleRow.locator('button:has-text("edit"), button:has-text("编辑")').first();
    await expect(editBtn).toBeVisible({ timeout: 5000 });
    await editBtn.evaluate((el) => (el as HTMLButtonElement).click());

    // Wait for edit form page (URL uses underscores: pm_project_role)
    await expect(page).toHaveURL(/\/p\/pm_project_role\/.*\/edit/, { timeout: 15000 });
    await expect(page.getByTestId('form-field-pm_role_name')).toBeVisible({ timeout: 15000 });

    // Update name
    const nameInput = page.getByTestId('form-field-pm_role_name').locator('input');
    await nameInput.clear();
    await nameInput.fill(updatedRoleName);

    // Submit and wait for the edit command response.
    const submitPromise = page.waitForResponse(
      (r) =>
        (r.url().includes('/execute/pm:update_project_role') ||
          r.url().includes('/execute/pm:create_project_role')) &&
        r.status() === 200,
      { timeout: 15000 },
    );
    const saveBtn = page.getByTestId('form-btn-save');
    await saveBtn.click();
    await submitPromise;

    // Verify updated name in list
    await navigateToProjectRoles(page);
    await searchRole(page, updatedRoleName);
    await expect(page.locator(`text=${updatedRoleName}`)).toBeVisible({ timeout: 10000 });
  });

  test('PM-ROLE-05: Delete project role via UI', async ({ page }) => {
    test.skip(!deleteRolePid, 'No role to delete');
    await navigateToProjectRoles(page);
    await searchRole(page, deleteRoleName);

    // Find the delete-target row
    const roleRow = page.locator('tbody tr', { hasText: deleteRoleName }).first();
    await expect(roleRow).toBeVisible({ timeout: 10000 });

    // Click delete action via DSL row action button (may be in dropdown)
    const deletePromise = page
      .waitForResponse(
        (r) => r.url().includes('/execute/pm:delete_project_role') && r.status() === 200,
        { timeout: 10000 },
      )
      .catch(() => null);
    await clickRowActionByLocator(page, roleRow, 'delete_project_role', 'Delete');

    await acceptConfirmDialog(page).catch(async () => {
      const confirmBtn = page
        .locator('button:has-text("OK"), button:has-text("确定"), button:has-text("Confirm")')
        .first();
      if (await confirmBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
        await confirmBtn.click();
      }
    });
    await deletePromise;

    // Role should be gone
    await expect(page.locator(`text=${deleteRoleName}`)).not.toBeVisible({ timeout: 10000 });
  });

  test('PM-ROLE-06: Verify updated role persists after page reload', async ({ page }) => {
    test.skip(!rolePid, 'No role created');
    await navigateToProjectRoles(page);
    await searchRole(page, updatedRoleName);
    await expect(page.locator(`text=${updatedRoleName}`)).toBeVisible({ timeout: 10000 });
  });

  test('PM-ROLE-07: Project role list supports pagination or shows all entries', async ({
    page,
  }) => {
    await navigateToProjectRoles(page);

    // Table should render with at least 1 row (our created role)
    const table = page.locator('table, [role="table"]');
    await expect(table.first()).toBeVisible({ timeout: 10000 });
    const rowCount = await page.locator('tbody tr').count();
    expect(rowCount).toBeGreaterThan(0);
  });
});
