import { test, expect } from '@playwright/test';

test.describe('Permission Matrix — Smoke', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/enterprise/permissions');
    // Wait for the page container to appear (domcontentloaded is enough;
    // networkidle may never settle due to SSE / polling on this page).
    await expect(page.getByTestId('permission-page')).toBeVisible({ timeout: 10000 });
  });

  test('page loads with role list and matrix tab', async ({ page }) => {
    // Role list should load and show at least one item
    const roleItems = page.locator('[data-testid^="role-item-"]');
    await expect(roleItems.first()).toBeVisible({ timeout: 10000 });

    // Right-panel tab bar must be present
    await expect(page.getByTestId('permission-right-tab-permissions')).toBeVisible();
    await expect(page.getByTestId('permission-right-tab-members')).toBeVisible();
  });

  test('matrix shows module sections with independent action columns', async ({ page }) => {
    // Wait for a role item to auto-select and the matrix to load
    const roleItems = page.locator('[data-testid^="role-item-"]');
    await expect(roleItems.first()).toBeVisible({ timeout: 10000 });

    // Wait for the matrix to appear (a role should be auto-selected on load)
    await expect(page.getByTestId('permission-matrix')).toBeVisible({ timeout: 10000 });

    // Must have at least one module section
    const modules = page.locator('[data-testid^="matrix-module-"]');
    const moduleCount = await modules.count();
    expect(moduleCount).toBeGreaterThan(0);

    // Each module has a toggle button
    const firstModuleToggle = page.locator('[data-testid^="matrix-module-toggle-"]').first();
    await expect(firstModuleToggle).toBeVisible();
  });

  test('i18n labels render correctly (no raw keys)', async ({ page }) => {
    // Both tabs must show translated text, not raw i18n keys
    const permTab = page.getByTestId('permission-right-tab-permissions');
    await expect(permTab).toBeVisible();
    const permText = await permTab.textContent();
    expect(permText).not.toContain('admin.permission.');

    const memberTab = page.getByTestId('permission-right-tab-members');
    const memberText = await memberTab.textContent();
    expect(memberText).not.toContain('admin.permission.');
  });

  test('toggling a permission shows toast feedback', async ({ page }) => {
    // Wait for matrix to load with a role auto-selected
    await expect(page.getByTestId('permission-matrix')).toBeVisible({ timeout: 10000 });

    const checkbox = page.locator('[data-testid^="matrix-checkbox-"]').first();
    await expect(checkbox).toBeVisible();

    // Intercept the batch update API call to verify it succeeds
    const responsePromise = page.waitForResponse(
      (resp) => resp.url().includes('/api/permissions/matrix/') && resp.url().includes('/batch'),
      { timeout: 5000 },
    );
    await checkbox.click();
    const response = await responsePromise;
    expect(response.status()).toBe(200);

    // The custom Toast component renders with role="alert"
    await expect(page.locator('[role="alert"]')).toBeVisible({ timeout: 3000 });

    // Restore original state
    await page.waitForTimeout(500);
    await checkbox.click();
  });
});
