import { test, expect, type Locator, type Page } from '@playwright/test';
import { BASE_URL } from '../../helpers/environments';

/**
 * Permission v2 smoke — the raw resource×action matrix is retired as a standalone tab; it now lives
 * inside the capability editor as the ③ "advanced · atomic actions" escape hatch. This smoke covers:
 * the page loads with the capability editor as the default surface, the retired tabs are gone, and a
 * grant toggle in ③ hits the batch API.
 */
async function createSmokeRole(page: Page): Promise<{ pid: string; code: string }> {
  const suffix = `${Date.now()}_${Math.floor(Math.random() * 10000)}`;
  const code = `e2e_perm_smoke_${suffix}`;
  const response = await page.request.post(`${BASE_URL}/api/roles`, {
    data: {
      code,
      name: `Permission Smoke ${suffix}`,
      description: 'Permission matrix smoke role',
      type: 'custom',
    },
  });
  expect(response.ok()).toBeTruthy();
  return (await response.json()).data as { pid: string; code: string };
}

async function selectRole(page: Page, code: string): Promise<void> {
  await page.getByTestId('role-search-input').fill(code);
  const roleItem = page.getByTestId(`role-item-${code}`);
  await expect(roleItem).toBeVisible({ timeout: 10000 });
  await roleItem.scrollIntoViewIfNeeded();
  await roleItem.click();
  await expect(page.getByTestId('capability-role-editor')).toBeVisible({ timeout: 10000 });
}

async function firstAtomicCheckboxByState(page: Page, checked: boolean): Promise<Locator> {
  const checkboxes = page.locator('[data-testid^="atomic-checkbox-"]');
  await expect(checkboxes.first()).toBeVisible({ timeout: 10000 });
  const count = await checkboxes.count();
  for (let index = 0; index < count; index += 1) {
    const checkbox = checkboxes.nth(index);
    if ((await checkbox.isChecked()) === checked) {
      const testId = await checkbox.getAttribute('data-testid');
      if (testId) return page.getByTestId(testId);
    }
  }
  throw new Error(`Permission smoke requires at least one ${checked ? 'checked' : 'unchecked'} atomic permission`);
}

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
    const role = await createSmokeRole(page);
    await page.goto('/enterprise/permissions');
    await expect(page.getByTestId('permission-page')).toBeVisible({ timeout: 10000 });
    await selectRole(page, role.code);

    await expect(page.getByTestId('capability-role-editor')).toBeVisible({ timeout: 10000 });
    await page.getByTestId('advanced-atomic-toggle').click();
    await expect(page.getByTestId('advanced-atomic-body')).toBeVisible();

    const checkbox = await firstAtomicCheckboxByState(page, false);
    await expect(checkbox).toBeVisible({ timeout: 10000 });
    await expect(checkbox).not.toBeChecked();

    const responsePromise = page.waitForResponse(
      (resp) => resp.url().includes('/api/permissions/matrix/') && resp.url().includes('/batch'),
      { timeout: 5000 },
    );
    await checkbox.click();
    const response = await responsePromise;
    expect(response.status()).toBe(200);
    await expect(checkbox).toBeChecked({ timeout: 10000 });

    // Restore original state (and settle the rollback request).
    const restorePromise = page.waitForResponse(
      (resp) => resp.url().includes('/api/permissions/matrix/') && resp.url().includes('/batch'),
      { timeout: 5000 },
    );
    await checkbox.click();
    const restore = await restorePromise;
    expect(restore.status()).toBe(200);
    await expect(checkbox).not.toBeChecked({ timeout: 10000 });
  });
});
