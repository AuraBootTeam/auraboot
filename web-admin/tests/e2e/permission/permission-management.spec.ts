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
 * Navigate to Permission Management page via sidebar menu, falling back to direct navigation when
 * the sidebar menu isn't seeded (minimal-bootstrap golden stacks have no menus — see
 * docs/backlog/2026-06-21-permission-v2-capability-ui-golden-findings.md §3). The menu path is still
 * exercised whenever menus exist.
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

  // Click the permissions menu link; fall back to direct nav if menus aren't seeded.
  const permLink = sidebar.locator('a[href="/enterprise/permissions"]');
  const menuVisible = await permLink.isVisible({ timeout: 5000 }).catch(() => false);
  if (menuVisible) {
    await permLink.evaluate((el: HTMLElement) => el.click());
  } else {
    await page.goto('/enterprise/permissions', { waitUntil: 'domcontentloaded' });
  }
  await expect(page).toHaveURL(/\/enterprise\/permissions/);
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

  // ---- PM-UI-09 ---- (v2 IA: capability editor is the default right tab; switch to members)
  test('PM-UI-09: Right-panel tab switching (capabilities ↔ members)', async ({ page }) => {
    await navigateToPermissions(page);
    await expect(page.locator('[data-testid="role-table"]')).toBeVisible({ timeout: 8000 });

    // A role auto-selects → the capability editor (default right tab) mounts.
    await expect(page.getByTestId('capability-role-editor')).toBeVisible({ timeout: 15000 });
    // The retired assignments top-tab and standalone matrix tab are gone.
    await expect(page.getByTestId('permission-tab-assignments')).toHaveCount(0); // gate:retired
    await expect(page.getByTestId('permission-right-tab-permissions')).toHaveCount(0); // gate:retired

    // Switch to members, then back to capabilities.
    await page.getByTestId('permission-right-tab-members').click();
    await expect(page.getByTestId('role-member-tab')).toBeVisible({ timeout: 10000 });
    await page.getByTestId('permission-right-tab-capabilities').click();
    await expect(page.getByTestId('capability-role-editor')).toBeVisible({ timeout: 10000 });
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
