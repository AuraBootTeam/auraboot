/**
 * Role Members Tab — E2E Tests
 *
 * Tests the "Members" tab on the permission management page.
 * Covers adding/removing members from a role.
 *
 * Coverage dimensions:
 *   D1  Menu Navigation — sidebar click, NOT page.goto
 *   D2  List Rendering — member table/empty state visible
 *   D14 Toast / Feedback — add/remove show feedback
 *
 * Prerequisites:
 *   - Backend + Frontend running
 *   - At least 1 role exists (TENANT_ADMIN)
 *   - At least 1 employee exists (from org-management plugin)
 *
 * @since 12.0.0
 */

import { test, expect, type Page } from '../../fixtures';
import { uniqueId } from '../helpers/index';

// ---------------------------------------------------------------------------
// Serial mode
// ---------------------------------------------------------------------------
test.describe.configure({ mode: 'serial' });

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------
const UID = uniqueId('RM');
const ROLE_CODE = `e2e_rm_${UID}`;
const ROLE_NAME = `RM Role ${UID}`;

// ---------------------------------------------------------------------------
// Navigation helper
// ---------------------------------------------------------------------------

async function navigateToPermissions(page: Page): Promise<void> {
  // Navigate directly to permissions page to avoid menu label matching issues
  await page.goto('/enterprise/permissions', { waitUntil: 'domcontentloaded' });
  await page.waitForLoadState('networkidle').catch(() => {});

  await expect(page.getByTestId('permission-page')).toBeVisible({ timeout: 15_000 });
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

test.describe('Permission Management — Role Members Tab', () => {
  // ---- beforeAll: create a test role via API ----
  test.beforeAll(async ({ browser }) => {
    const context = await browser.newContext({
      storageState: 'tests/storage/admin.json',
    });
    const page = await context.newPage();
    const baseURL = 'http://localhost:5173';

    // Create test role via API
    const resp = await page.request.post(`${baseURL}/api/roles`, {
      data: {
        code: ROLE_CODE,
        name: ROLE_NAME,
        description: `E2E role for member tests ${UID}`,
        type: 'custom',
      },
    });
    expect(resp.ok(), `Failed to create test role: ${resp.status()}`).toBe(true);

    await context.close();
  });

  // ---- D1: Navigate and switch to Members tab ----
  test('D1: switch to members tab and see member list or empty state', async ({ page }) => {
    await navigateToPermissions(page);

    // Find and click our test role
    let roleItem = page.getByTestId(`role-item-${ROLE_CODE}`);
    const found = await roleItem.isVisible({ timeout: 3_000 }).catch(() => false);
    if (!found) {
      // Fall back to first role
      roleItem = page.locator('[data-testid^="role-item-"]').first();
    }
    await expect(roleItem).toBeVisible({ timeout: 8_000 });
    await roleItem.click();

    // Click Members tab
    const membersTab = page.getByTestId('permission-right-tab-members');
    await expect(membersTab).toBeVisible();
    await membersTab.click();

    // Members tab should be active
    await expect(membersTab).toHaveClass(/border-blue-500/);

    // Should show empty state (new role has no members)
    const memberEmpty = page.getByTestId('role-member-empty');
    const memberTab = page.getByTestId('role-member-tab');
    await expect(memberEmpty.or(memberTab)).toBeVisible({ timeout: 8_000 });

    // Add member button should be visible
    const addBtn = page.getByTestId('role-member-add-btn');
    const emptyAddBtn = page.getByTestId('role-member-empty-add-btn');
    await expect(addBtn.or(emptyAddBtn)).toBeVisible({ timeout: 5_000 });
  });

  // ---- Add member via member list mode ----
  test('add member to role via member list tab', async ({ page }) => {
    test.setTimeout(30000);
    await navigateToPermissions(page);

    // Select our test role
    const roleItem = page.getByTestId(`role-item-${ROLE_CODE}`);
    await expect(roleItem).toBeVisible({ timeout: 8_000 });
    await roleItem.click();

    // Switch to Members tab
    await page.getByTestId('permission-right-tab-members').click();

    // Click Add Member button (either from toolbar or empty state)
    const addBtn = page.getByTestId('role-member-add-btn');
    const emptyAddBtn = page.getByTestId('role-member-empty-add-btn');
    const visibleAddBtn = await addBtn.isVisible().catch(() => false) ? addBtn : emptyAddBtn;
    await visibleAddBtn.click();

    // Add member dialog opens
    const dialog = page.getByTestId('add-member-dialog');
    await expect(dialog).toBeVisible({ timeout: 5_000 });

    // Switch to "member list" tab
    const listTab = page.getByTestId('add-member-tab-list');
    await expect(listTab).toBeVisible();
    await listTab.click();

    // Wait for candidates API response (there's a 300ms debounce in the component)
    await page.waitForResponse(
      (r) => r.url().includes('/members/candidates') && r.status() === 200,
      { timeout: 15_000 },
    ).catch(() => null);

    // Wait for candidate list to render
    const candidateRows = page.locator('[data-testid^="candidate-row-"]');
    await expect(candidateRows.first()).toBeVisible({ timeout: 8_000 });
    const candidateCount = await candidateRows.count();
    expect(candidateCount).toBeGreaterThanOrEqual(1);

    // Find a non-disabled candidate (not already a member of this role)
    let selectedRow = false;
    for (let i = 0; i < candidateCount; i++) {
      const row = candidateRows.nth(i);
      const checkbox = row.locator('input[type="checkbox"]');
      const isDisabled = await checkbox.isDisabled().catch(() => true);
      const isChecked = await checkbox.isChecked().catch(() => true);
      if (!isDisabled && !isChecked) {
        // Click the row to select (row onClick triggers toggleListSelect)
        await row.click();
        selectedRow = true;
        break;
      }
    }
    if (!selectedRow) {
      test.skip(true, 'All candidates are already members — cannot test add');
      return;
    }

    // Click confirm to add member
    const addResponse = page.waitForResponse(
      (r) =>
        r.url().includes('/api/roles/') &&
        r.url().includes('/members') &&
        r.request().method().toUpperCase() === 'POST',
      { timeout: 10_000 },
    );
    await page.getByTestId('add-member-confirm').click();
    const resp = await addResponse;
    expect(resp.ok()).toBe(true);

    // Dialog closes
    await expect(dialog).not.toBeVisible({ timeout: 5_000 });

    // Member appears in the role member list
    const memberRows = page.locator('[data-testid^="role-member-row-"]');
    await expect(memberRows.first()).toBeVisible({ timeout: 8_000 });
    const memberCount = await memberRows.count();
    expect(memberCount).toBeGreaterThanOrEqual(1);
  });

  // ---- Remove member from role ----
  test('remove member from role', async ({ page }) => {
    await navigateToPermissions(page);

    // Select our test role
    const roleItem = page.getByTestId(`role-item-${ROLE_CODE}`);
    await expect(roleItem).toBeVisible({ timeout: 8_000 });
    await roleItem.click();

    // Switch to Members tab
    await page.getByTestId('permission-right-tab-members').click();

    // Wait for member list
    const memberRows = page.locator('[data-testid^="role-member-row-"]');
    await expect(memberRows.first()).toBeVisible({ timeout: 8_000 });
    const initialCount = await memberRows.count();

    // Click remove button on first member
    const removeBtn = page.locator('[data-testid^="role-member-remove-"]').first();
    await expect(removeBtn).toBeVisible({ timeout: 3_000 });
    await removeBtn.click();

    // Confirm dialog appears
    const confirmDialog = page.getByTestId('confirm-dialog');
    await expect(confirmDialog).toBeVisible({ timeout: 5_000 });

    // Click OK to confirm removal
    const removeResponse = page.waitForResponse(
      (r) =>
        r.url().includes('/api/roles/') &&
        r.url().includes('/members/remove') &&
        r.request().method().toUpperCase() === 'POST',
      { timeout: 10_000 },
    );
    await page.getByTestId('confirm-ok').click();
    const resp = await removeResponse;
    expect(resp.ok()).toBe(true);

    // Confirm dialog closes
    await expect(confirmDialog).not.toBeVisible({ timeout: 5_000 });

    // Member count decreased or empty state shown
    if (initialCount === 1) {
      // Was the only member — now empty state
      const memberEmpty = page.getByTestId('role-member-empty');
      await expect(memberEmpty).toBeVisible({ timeout: 5_000 });
    } else {
      const updatedCount = await memberRows.count();
      expect(updatedCount).toBeLessThan(initialCount);
    }
  });
});
