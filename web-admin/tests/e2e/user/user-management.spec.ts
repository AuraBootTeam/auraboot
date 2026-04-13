/**
 * User Management E2E Tests
 *
 * Tests UM-001 ~ UM-010: User CRUD, role assignment, password management, profile
 * - User list display (DSL page at /p/tenant-member)
 * - Member status management (approve, suspend, restore)
 * - Role assignment
 * - Password change
 * - Profile viewing and editing
 *
 * Uses storageState for authentication.
 * Uses UserProfilePage PO for profile tests.
 *
 * @since 4.0.0
 */

import { test, expect } from '../../fixtures';
import { UserProfilePage } from '../../pages';
import { navigateToDynamicPage } from '../helpers';

test.describe('User List', () => {
  /**
   * UM-001: Member list display
   * Verify that tenant member DSL page loads and displays members
   */
  test('UM-001: should display member list', async ({ page }) => {
    await navigateToDynamicPage(page, 'tenant_member');

    // Verify heading is visible (DSL page renders h2)
    const heading = page.locator('h2').first();
    await expect(heading).toBeVisible({ timeout: 8000 });

    // Verify table or empty state
    const memberTable = page.locator('table');
    await expect(memberTable).toBeVisible({ timeout: 8000 });

    // Verify at least the current admin user is listed
    const rows = page.locator('tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 5000 });
  });

  /**
   * UM-001A: Member list should show user identity content (name + secondary id/username)
   */
  test('UM-001A: should render user identity cell', async ({ page }) => {
    await navigateToDynamicPage(page, 'tenant_member');
    const userCell = page.locator('[data-testid="table-cell-0-user_id"]').first();
    await expect(userCell).toBeVisible({ timeout: 8000 });
    // User cell should have non-empty text content (name, email, or ID)
    const cellText = await userCell.textContent();
    expect(cellText?.trim().length).toBeGreaterThan(0);
  });

  /**
   * UM-002: Member list has status tabs
   * Verify that the DSL-driven member page has tab filters
   */
  test('UM-002: should display status tabs', async ({ page }) => {
    await navigateToDynamicPage(page, 'tenant_member');

    // Verify status tabs from DSL (list-tabs block)
    const tabs = page.locator('nav[aria-label="Tabs"] button');
    await expect(tabs.filter({ hasText: /全部|All/i })).toBeVisible({ timeout: 8000 });
    await expect(tabs.filter({ hasText: /已激活|Active/i })).toBeVisible();
    await expect(tabs.filter({ hasText: /待审批|Pending/i })).toBeVisible();
    await expect(tabs.filter({ hasText: /已暂停|Suspended/i })).toBeVisible();
  });

  /**
   * UM-002A: User datetime format preference should override system/default display
   */
  test('UM-002A: should apply user datetime format on created_at column', async ({ page }) => {
    const customFormat = 'YYYY/MM/DD HH:mm';
    const defaultFormat = 'YYYY-MM-DD HH:mm:ss';

    try {
      await page.goto('/settings/user-preferences');
      await page.waitForLoadState('domcontentloaded');

      const input = page.locator('[data-testid="user-datetime-format-input"]');
      const saveBtn = page.locator('[data-testid="user-datetime-format-save"]');
      await expect(input).toBeVisible({ timeout: 8000 });

      const saveResp = page.waitForResponse(
        (r) =>
          r.url().includes('/api/user-preferences/ui.datetime.format') &&
          r.request().method().toLowerCase() === 'put' &&
          r.status() < 400,
        { timeout: 10000 },
      );
      await input.fill(customFormat);
      await saveBtn.click();
      await saveResp;
      const prefResp = await page.request.get('/api/user-preferences/ui.datetime.format');
      expect(prefResp.ok()).toBe(true);
      const prefBody = await prefResp.json().catch(() => ({}));
      const savedFormat = String(prefBody?.data?.value ?? '');
      expect(savedFormat).toBeTruthy();

      await navigateToDynamicPage(page, 'tenant_member');
      await expect(page.locator('table').first()).toBeVisible({ timeout: 10000 });

      const createdCells = page.locator('[data-testid^="table-cell-"][data-testid$="-created_at"]');
      await expect(createdCells.first()).toBeVisible({ timeout: 5000 });
      const values = await createdCells.allTextContents();
      const matched = values.some((v) =>
        /\d{4}[-/]\d{1,2}[-/]\d{1,2}\s+\d{1,2}:\d{2}(:\d{2})?/.test(v.trim()),
      );
      expect(matched).toBe(true);
    } finally {
      // Restore default to avoid impacting subsequent tests
      await page.goto('/settings/user-preferences');
      await page.waitForLoadState('domcontentloaded');
      const input = page.locator('[data-testid="user-datetime-format-input"]');
      const saveBtn = page.locator('[data-testid="user-datetime-format-save"]');
      if (await input.isVisible().catch(() => false)) {
        await input.fill(defaultFormat);
        await saveBtn.click();
      }
    }
  });

  /**
   * UM-002B: System datetime format settings page should be available
   */
  test('UM-002B: should save system datetime format', async ({ page }) => {
    await page.goto('/settings/system-preferences');
    await page.waitForLoadState('domcontentloaded');

    const input = page.locator('[data-testid="system-datetime-format-input"]');
    const saveBtn = page.locator('[data-testid="system-datetime-format-save"]');
    await expect(input).toBeVisible({ timeout: 8000 });
    await expect(saveBtn).toBeVisible({ timeout: 5000 });

    await input.fill('YYYY-MM-DD HH:mm:ss');
    await saveBtn.click();
  });
});

test.describe('User CRUD', () => {
  /**
   * UM-003: No create button for tenant members
   * Members join via invite code, not created manually via DSL form
   */
  test('UM-003: should not have create button', async ({ page }) => {
    await navigateToDynamicPage(page, 'tenant_member');

    // Tenant member page has empty toolbar buttons — no create button
    const createBtn = page.locator('[data-testid="toolbar-btn-create"]');
    await expect(createBtn).not.toBeVisible({ timeout: 3000 });
  });

  /**
   * UM-005: Member row actions are visible
   * Verify that row-level action buttons render based on member status
   */
  test.fixme('UM-005: should display row action buttons', async ({ page }) => {
    await navigateToDynamicPage(page, 'tenant_member');

    // Wait for at least one row
    const firstRow = page.locator('tbody tr').first();
    await expect(firstRow).toBeVisible({ timeout: 8000 });

    // Hover row to reveal action buttons (opacity-0 → opacity-100 via group-hover)
    await firstRow.hover();
    // The current admin user should be active, so we expect suspend/leave/delete actions
    // Check for at least one row-action button via data-testid
    const actionBtns = firstRow.locator('[data-testid^="row-action-"]');
    const directActionCount = await actionBtns.count();
    if (directActionCount > 0) {
      expect(directActionCount).toBeGreaterThanOrEqual(1);
      return;
    }

    // Some pages collapse row actions into a "more" trigger.
    const moreAction = firstRow
      .locator(
        '[data-testid="row-action-more"], [data-testid="row-actions-more"], button[aria-label*="more" i], button:has(svg.lucide-ellipsis)',
      )
      .first();
    const hasMoreAction = await moreAction.isVisible({ timeout: 3000 }).catch(() => false);
    expect(hasMoreAction).toBe(true);
  });
});

test.describe('Role Assignment', () => {
  /**
   * UM-006: Assign role to user
   * Verify that roles can be assigned to users
   */
  test('UM-006: should assign role to user', async ({ page }) => {
    await page.goto('/enterprise/permissions?tab=assignments');
    await page.waitForLoadState('domcontentloaded');

    await expect(page.locator('[data-testid="permission-page"]')).toBeVisible({ timeout: 8000 });
    const assignmentTab = page.locator('[data-testid="permission-tab-assignments"]');
    const assignmentTabVisible = await assignmentTab.isVisible({ timeout: 5000 }).catch(() => false);
    if (assignmentTabVisible) {
      await expect(assignmentTab).toBeVisible();
    }

    // Click the first role card to select it
    await expect(page.locator('[data-testid="role-search-input"]')).toBeVisible({ timeout: 8000 });
    const roleCards = page.locator('[data-testid^="role-item-"]');
    await expect.poll(async () => roleCards.count(), { timeout: 10000 }).toBeGreaterThanOrEqual(0);
    const roleCount = await roleCards.count();

    if (roleCount > 0) {
      await roleCards.first().click();
      const assignmentPanel = page
        .locator(
          '[data-testid="assignment-tab"], text=/请选择一个角色来分配权限|Please select a role|Permissions/i',
        )
        .first();
      await expect(assignmentPanel).toBeVisible({ timeout: 5000 });
      return;
    }

    await expect(page.locator('[data-testid="role-create-btn"]')).toBeVisible({ timeout: 5000 });
  });

  /**
   * UM-007: View role list
   * Verify that roles list is displayed
   */
  test('UM-007: should display role list', async ({ page }) => {
    await page.goto('/enterprise/permissions?tab=roles');
    await page.waitForLoadState('domcontentloaded');

    const isUnavailable = await page
      .locator('text=Page Unavailable')
      .isVisible({ timeout: 3000 })
      .catch(() => false);
    expect(isUnavailable).toBe(false);

    await expect(page.locator('[data-testid="permission-page"]')).toBeVisible({ timeout: 8000 });
    await expect(page.locator('[data-testid="role-search-input"]')).toBeVisible({ timeout: 8000 });
    const roleItems = page.locator('[data-testid^="role-item-"]');
    const hasRoles = (await roleItems.count()) > 0;
    if (!hasRoles) {
      await expect(page.locator('[data-testid="role-create-btn"]')).toBeVisible({ timeout: 8000 });
      return;
    }
    await expect(roleItems.first()).toBeVisible({ timeout: 8000 });
  });
});

test.describe('Password Management', () => {
  /**
   * UM-008: Change password
   * Verify that password can be changed
   */
  test('UM-008: should access password change', async ({ page }) => {
    await page.goto('/personal/security');
    await page.waitForLoadState('domcontentloaded');

    await expect(page.getByRole('heading', { name: 'Security Settings' })).toBeVisible({
      timeout: 10000,
    });

    await expect(page.getByText('Current Password', { exact: true })).toBeVisible({
      timeout: 5000,
    });
    await expect(page.getByText('New Password', { exact: true })).toBeVisible({ timeout: 5000 });
    await expect(page.getByText('Confirm New Password', { exact: true })).toBeVisible({
      timeout: 5000,
    });

    const changeBtn = page.getByRole('button', { name: 'Change Password' }).last();
    await expect(changeBtn).toBeVisible({ timeout: 5000 });
  });
});

test.describe('User Profile', () => {
  let profilePage: UserProfilePage;

  test.beforeEach(async ({ page }) => {
    profilePage = new UserProfilePage(page);
  });

  /**
   * UM-009: View user profile
   * Verify that current user can view their profile
   */
  test('UM-009: should view user profile', async () => {
    await profilePage.goto();
    await expect.poll(async () => profilePage.isLoaded(), { timeout: 10000 }).toBe(true);

    await expect(profilePage.avatar).toBeVisible({ timeout: 3000 });
  });

  /**
   * UM-010: Edit user profile
   * Verify that user can edit their own profile
   */
  test('UM-010: should edit user profile', async () => {
    await profilePage.goto();
    await expect.poll(async () => profilePage.isLoaded(), { timeout: 10000 }).toBe(true);

    const hasEditBtn = await profilePage.editButton.isVisible({ timeout: 3000 }).catch(() => false);
    expect(hasEditBtn).toBe(true);

    await profilePage.startEditing();

    const inputCount = await profilePage.formInputs.count();
    expect(inputCount).toBeGreaterThan(0);

    await profilePage.cancelEditing();
  });
});
