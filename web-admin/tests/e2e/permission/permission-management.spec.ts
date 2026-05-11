/**
 * Permission Management Page E2E Tests
 *
 * Tests the refactored permission management page at /enterprise/permissions.
 * Covers role CRUD, tab switching, assignment tab, permission tree, and confirm dialog.
 *
 * Uses real database, NO MOCKING. Test data is NOT cleaned up (verification evidence).
 *
 * @since 4.0.0
 */

import { test, expect } from '../../fixtures';
import { BASE_URL } from '../../helpers/environments';


function uniqueCode(prefix = 'e2e_role') {
  return `${prefix}_${Date.now()}`;
}

/**
 * Navigate to Permission Management page via sidebar menu.
 */
async function navigateToPermissions(page: any) {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  const sidebar = page.locator('nav').first();
  await expect(sidebar).toBeVisible({ timeout: 10000 });

  // Expand parent menu group if collapsed (Enterprise Settings / 企业设置)
  const enterpriseBtn = sidebar
    .locator('button')
    .filter({ hasText: /企业设置|Enterprise/i })
    .first();
  const isVisible = await enterpriseBtn.isVisible({ timeout: 3000 }).catch(() => false);
  if (isVisible) {
    await enterpriseBtn.click();
  }

  // Click the permissions menu link using evaluate to bypass pointer interception
  const permLink = sidebar.locator('a[href="/enterprise/permissions"]');
  await expect(permLink).toBeVisible({ timeout: 5000 });
  await permLink.evaluate((el: HTMLElement) => el.click());
  await expect(page).toHaveURL(/\/enterprise\/permissions/);
}

/**
 * Switch to the assignments tab from the permissions page.
 * Ensures the roles tab has fully loaded first, then clicks and verifies.
 */
async function switchToAssignmentsTab(page: any) {
  await expect(page.locator('[data-testid="permission-page"]')).toBeVisible({ timeout: 8000 });
  // Wait for roles tab to fully load (Create Role button indicates React is ready)
  await expect(page.locator('[data-testid="role-create-btn"]')).toBeVisible({ timeout: 8000 });

  // Click assignments tab — use Playwright native click with retry
  const assignTab = page.locator('[data-testid="permission-tab-assignments"]');
  await expect(assignTab).toBeVisible({ timeout: 5000 });
  await assignTab.click({ timeout: 5000 });

  // Verify URL changed; if not, retry the click
  try {
    await expect(page).toHaveURL(/tab=assignments/, { timeout: 3000 });
  } catch {
    // Retry click if first attempt didn't register
    await assignTab.click({ force: true });
    await expect(page).toHaveURL(/tab=assignments/, { timeout: 5000 });
  }

  // Wait for assignment tab content
  await expect(page.locator('[data-testid="assignment-tab"]')).toBeVisible({ timeout: 10000 });
}

/**
 * Create a role via API, returning { pid, code, name }.
 */
async function createRoleViaApi(page: any, code: string, name: string) {
  const resp = await page.request.post(`${BASE_URL}/api/roles`, {
    data: { code, name, description: 'E2E test role', type: 'custom' },
  });
  expect(resp.ok()).toBe(true);
  const body = await resp.json();
  return body.data as { pid: string; code: string; name: string };
}

test.describe.configure({ mode: 'serial' });

test.describe('Permission Management Page', () => {
  // ---- PM-UI-01 ----
  test('PM-UI-01: Navigate via menu, roles tab visible by default', async ({ page }) => {
    await navigateToPermissions(page);

    // Page container visible
    await expect(page.locator('[data-testid="permission-page"], main').first()).toBeVisible({ timeout: 15000 });

    // Roles tab should be visible — try testid first, fall back to text
    const rolesTab = page.locator('[data-testid="permission-tab-roles"]')
      .or(page.getByRole('tab', { name: /角色|Roles/i }))
      .or(page.locator('button, a').filter({ hasText: /角色|Roles/i }).first())
      .first();
    await expect(rolesTab).toBeVisible({ timeout: 8_000 });

    // Role table visible with at least 1 row (TENANT_ADMIN always exists)
    const roleTable = page.locator('[data-testid="role-table"]');
    await expect(roleTable).toBeVisible({ timeout: 15000 });
    const rows = roleTable.locator('tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 5000 });
    const rowCount = await rows.count();
    expect(rowCount).toBeGreaterThanOrEqual(1);
  });

  // ---- PM-UI-02 ----
  test('PM-UI-02: Create a new custom role', async ({ page }) => {
    const roleCode = uniqueCode();
    const roleName = `Test Role ${Date.now()}`;

    await navigateToPermissions(page);
    await expect(page.locator('[data-testid="role-table"]')).toBeVisible({ timeout: 8000 });

    // Click create button
    await page.locator('[data-testid="role-create-btn"]').click();
    await expect(page.locator('[data-testid="role-form-dialog"]')).toBeVisible({ timeout: 5000 });

    // Fill form
    await page.locator('[data-testid="role-form-code"]').fill(roleCode);
    await page.locator('[data-testid="role-form-name"]').fill(roleName);
    await page.locator('[data-testid="role-form-description"]').fill('E2E test role');
    await page.locator('[data-testid="role-form-type"]').selectOption('custom');

    // Submit with response wait
    const responsePromise = page.waitForResponse(
      (resp: any) =>
        resp.url().includes('/api/roles') && resp.request().method().toLowerCase() === 'post',
    );
    await page.locator('[data-testid="role-form-submit"]').click();
    const createResp = await responsePromise;
    expect(createResp.ok()).toBe(true);

    // Dialog closes
    await expect(page.locator('[data-testid="role-form-dialog"]')).not.toBeVisible({
      timeout: 5000,
    });

    // New row appears in table
    await expect(page.locator(`[data-testid="role-row-${roleCode}"]`)).toBeVisible({
      timeout: 8000,
    });
  });

  // ---- PM-UI-03 ----
  test('PM-UI-03: Edit a role', async ({ page }) => {
    const roleCode = uniqueCode();
    const originalName = `Original ${Date.now()}`;
    const updatedName = `Updated ${Date.now()}`;

    // Create role via API
    await createRoleViaApi(page, roleCode, originalName);

    await navigateToPermissions(page);
    await expect(page.locator('[data-testid="role-table"]')).toBeVisible({ timeout: 8000 });

    // Click edit button
    await page.locator(`[data-testid="role-action-edit-${roleCode}"]`).click();
    await expect(page.locator('[data-testid="role-form-dialog"]')).toBeVisible({ timeout: 5000 });

    // Verify pre-filled name
    const nameInput = page.locator('[data-testid="role-form-name"]');
    await expect(nameInput).toHaveValue(originalName);

    // Code should be disabled in edit mode
    await expect(page.locator('[data-testid="role-form-code"]')).toBeDisabled();

    // Change name
    await nameInput.clear();
    await nameInput.fill(updatedName);

    // Submit with response wait
    const responsePromise = page.waitForResponse(
      (resp: any) =>
        resp.url().includes('/api/roles/') && resp.request().method().toLowerCase() === 'put',
    );
    await page.locator('[data-testid="role-form-submit"]').click();
    await responsePromise;

    // Dialog closes
    await expect(page.locator('[data-testid="role-form-dialog"]')).not.toBeVisible({
      timeout: 5000,
    });
  });

  // ---- PM-UI-04 ----
  test('PM-UI-04: Toggle role status (disable then re-enable)', async ({ page }) => {
    const roleCode = uniqueCode();
    await createRoleViaApi(page, roleCode, `Toggle ${Date.now()}`);

    await navigateToPermissions(page);
    await expect(page.locator(`[data-testid="role-row-${roleCode}"]`)).toBeVisible({
      timeout: 8000,
    });

    const toggleBtn = page.locator(`[data-testid="role-action-toggle-${roleCode}"]`);

    // Disable: waitForResponse for /disable
    const disablePromise = page.waitForResponse(
      (resp: any) =>
        resp.url().includes('/disable') && resp.request().method().toLowerCase() === 'put',
    );
    await toggleBtn.click();
    const disableResp = await disablePromise;
    expect(disableResp.ok()).toBe(true);

    // Wait for table to refresh after disable
    await expect(page.locator(`[data-testid="role-row-${roleCode}"]`)).toBeVisible({
      timeout: 5000,
    });

    // Re-enable: waitForResponse for /enable
    const enablePromise = page.waitForResponse(
      (resp: any) =>
        resp.url().includes('/enable') && resp.request().method().toLowerCase() === 'put',
    );
    await toggleBtn.click();
    const enableResp = await enablePromise;
    expect(enableResp.ok()).toBe(true);
  });

  // ---- PM-UI-05 ----
  test('PM-UI-05: Delete role with ConfirmDialog', async ({ page }) => {
    const roleCode = uniqueCode();
    await createRoleViaApi(page, roleCode, `Delete ${Date.now()}`);

    await navigateToPermissions(page);
    await expect(page.locator(`[data-testid="role-row-${roleCode}"]`)).toBeVisible({
      timeout: 8000,
    });

    // Click delete button
    await page.locator(`[data-testid="role-action-delete-${roleCode}"]`).click();

    // Confirm dialog appears
    await expect(page.locator('[data-testid="confirm-dialog"]')).toBeVisible({ timeout: 5000 });

    // Click OK to confirm
    const deletePromise = page.waitForResponse(
      (resp: any) =>
        resp.url().includes('/api/roles/') && resp.request().method().toLowerCase() === 'delete',
    );
    await page.locator('[data-testid="confirm-ok"]').click();
    const deleteResp = await deletePromise;
    expect(deleteResp.ok()).toBe(true);

    // Row disappears
    await expect(page.locator(`[data-testid="role-row-${roleCode}"]`)).not.toBeVisible({
      timeout: 5000,
    });
  });

  // ---- PM-UI-06 ----
  test('PM-UI-06: Switch to assignments tab', async ({ page }) => {
    await navigateToPermissions(page);
    await switchToAssignmentsTab(page);

    // At least 1 role card visible (TENANT_ADMIN always exists)
    const roleCards = page.locator('[data-testid^="assignment-role-"]');
    await expect(roleCards.first()).toBeVisible({ timeout: 5000 });
    const cardCount = await roleCards.count();
    expect(cardCount).toBeGreaterThanOrEqual(1);
  });

  // ---- PM-UI-07 ----
  test('PM-UI-07: Select role and see permission tree', async ({ page }) => {
    await navigateToPermissions(page);
    await switchToAssignmentsTab(page);

    // Click first role card
    const firstRoleCard = page.locator('[data-testid^="assignment-role-"]').first();
    await expect(firstRoleCard).toBeVisible({ timeout: 5000 });
    await firstRoleCard.click();

    // Permission tree visible
    await expect(page.locator('[data-testid="permission-tree"]')).toBeVisible({ timeout: 8000 });

    // Save button visible
    await expect(page.locator('[data-testid="assignment-save-btn"]')).toBeVisible();

    // Selected count badge visible
    await expect(page.locator('[data-testid="assignment-selected-count"]')).toBeVisible();
  });

  // ---- PM-UI-08 ----
  test('PM-UI-08: Permission tree search', async ({ page }) => {
    await navigateToPermissions(page);
    await switchToAssignmentsTab(page);

    // Select first role
    const firstRoleCard = page.locator('[data-testid^="assignment-role-"]').first();
    await expect(firstRoleCard).toBeVisible({ timeout: 5000 });
    await firstRoleCard.click();

    // Wait for permission tree
    await expect(page.locator('[data-testid="permission-tree"]')).toBeVisible({ timeout: 8000 });

    // Search for something (use a generic term likely to exist)
    const searchInput = page.locator('[data-testid="permission-tree-search"]');
    await expect(searchInput).toBeVisible();
    await searchInput.fill('model');

    // Tree should still be visible (filtered results)
    await expect(page.locator('[data-testid="permission-tree"]')).toBeVisible();

    // Clear search
    await searchInput.clear();
    await expect(page.locator('[data-testid="permission-tree"]')).toBeVisible();
  });

  // ---- PM-UI-09 ----
  test('PM-UI-09: Tab switching preserves navigation', async ({ page }) => {
    await navigateToPermissions(page);
    await switchToAssignmentsTab(page);

    // Switch back to roles tab
    await page.locator('[data-testid="permission-tab-roles"]').click();
    await expect(page).toHaveURL(/tab=roles/);
    await expect(page.locator('[data-testid="role-table"]')).toBeVisible({ timeout: 8000 });
  });

  // ---- PM-UI-10 ----
  test('PM-UI-10: Cancel delete via ConfirmDialog', async ({ page }) => {
    const roleCode = uniqueCode();
    await createRoleViaApi(page, roleCode, `NoDel ${Date.now()}`);

    await navigateToPermissions(page);
    await expect(page.locator(`[data-testid="role-row-${roleCode}"]`)).toBeVisible({
      timeout: 8000,
    });

    // Click delete button
    await page.locator(`[data-testid="role-action-delete-${roleCode}"]`).click();

    // Confirm dialog appears
    await expect(page.locator('[data-testid="confirm-dialog"]')).toBeVisible({ timeout: 5000 });

    // Click cancel
    await page.locator('[data-testid="confirm-cancel"]').click();

    // Dialog dismissed
    await expect(page.locator('[data-testid="confirm-dialog"]')).not.toBeVisible({ timeout: 3000 });

    // Role row still present
    await expect(page.locator(`[data-testid="role-row-${roleCode}"]`)).toBeVisible();
  });
});
