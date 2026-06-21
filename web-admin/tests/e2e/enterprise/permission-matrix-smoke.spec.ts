import { test, expect } from '@playwright/test';

/**
 * Permission v2 smoke — the raw resource×action matrix is retired as a standalone tab; it now lives
 * inside the capability editor as the ③ "advanced · atomic actions" escape hatch. This smoke covers:
 * the page loads with the capability editor as the default surface, the retired tabs are gone, and a
 * grant toggle in ③ hits the batch API.
 */
test.describe('Permission v2 — Smoke', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/enterprise/permissions');
    await expect(page.getByTestId('permission-page')).toBeVisible({ timeout: 10000 });
  });

  test('page loads with the capability editor as the default surface', async ({ page }) => {
    const roleItems = page.locator('[data-testid^="role-item-"]');
    await expect(roleItems.first()).toBeVisible({ timeout: 10000 });

    // v2 right-panel: capabilities (default) + members; the standalone matrix tab is retired.
    await expect(page.getByTestId('permission-right-tab-capabilities')).toBeVisible();
    await expect(page.getByTestId('permission-right-tab-members')).toBeVisible();
    await expect(page.getByTestId('permission-right-tab-permissions')).toHaveCount(0);
    await expect(page.getByTestId('capability-role-editor')).toBeVisible({ timeout: 10000 });
  });

  test('③ advanced atomic table is collapsed by default and reveals resource-grouped codes', async ({ page }) => {
    await expect(page.getByTestId('capability-role-editor')).toBeVisible({ timeout: 10000 });

    await expect(page.getByTestId('advanced-atomic-body')).toHaveCount(0);
    await page.getByTestId('advanced-atomic-toggle').click();
    await expect(page.getByTestId('advanced-atomic-body')).toBeVisible();

    const sources = page.locator('[data-testid^="atomic-source-"]');
    await expect(sources.first()).toBeVisible({ timeout: 10000 });
    expect(await sources.count()).toBeGreaterThan(0);
  });

  test('i18n labels render correctly (no raw keys)', async ({ page }) => {
    const capTab = page.getByTestId('permission-right-tab-capabilities');
    await expect(capTab).toBeVisible();
    expect(await capTab.textContent()).not.toContain('admin.permission.');

    const memberTab = page.getByTestId('permission-right-tab-members');
    expect(await memberTab.textContent()).not.toContain('admin.permission.');
  });

  test('toggling an atomic permission in ③ hits the batch API', async ({ page }) => {
    await expect(page.getByTestId('capability-role-editor')).toBeVisible({ timeout: 10000 });
    await page.getByTestId('advanced-atomic-toggle').click();
    await expect(page.getByTestId('advanced-atomic-body')).toBeVisible();

    const checkbox = page.locator('[data-testid^="atomic-checkbox-"]').first();
    await expect(checkbox).toBeVisible({ timeout: 10000 });
    const wasChecked = await checkbox.isChecked();

    const responsePromise = page.waitForResponse(
      (resp) => resp.url().includes('/api/permissions/matrix/') && resp.url().includes('/batch'),
      { timeout: 5000 },
    );
    await checkbox.click();
    const response = await responsePromise;
    expect(response.status()).toBe(200);

    // Restore original state (and settle the rollback request).
    const restorePromise = page.waitForResponse(
      (resp) => resp.url().includes('/api/permissions/matrix/') && resp.url().includes('/batch'),
      { timeout: 5000 },
    );
    await checkbox.click();
    const restore = await restorePromise;
    expect(restore.status()).toBe(200);
    expect(await checkbox.isChecked()).toBe(wasChecked);
  });
});
