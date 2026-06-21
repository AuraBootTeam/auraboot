import { test, expect } from '@playwright/test';

/**
 * Non-destructive UX-review screenshot capture of the RBAC / permission-management surfaces.
 * Never clicks Save/Delete — only navigates + opens panels/dialogs and screenshots them.
 */
const DIR = 'test-results/rbac-ux';

async function gotoPermissions(page: import('@playwright/test').Page) {
  await page.goto('/enterprise/permissions', { waitUntil: 'domcontentloaded' });
  await expect(page.locator('[data-testid="role-table"]')).toBeVisible({ timeout: 15000 });
  // select the first role so the right panel is populated
  await page.locator('[data-testid="role-table"] tbody tr').first().click().catch(() => {});
  await page.waitForTimeout(600);
}

test.describe('RBAC UX review screenshots', () => {
  test('capture all permission-management surfaces', async ({ page }) => {
    await gotoPermissions(page);

    // 1. Roles tab — default right panel (Permissions matrix)
    await page.screenshot({ path: `${DIR}/01-roles-default.png`, fullPage: true });

    // 2. Capabilities right tab (v2)
    await page.locator('[data-testid="permission-right-tab-capabilities"]').click().catch(() => {});
    await page.waitForTimeout(700);
    await page.screenshot({ path: `${DIR}/02-capabilities.png`, fullPage: true });

    // 3. Permissions matrix right tab
    await page.locator('[data-testid="permission-right-tab-permissions"]').click().catch(() => {});
    await page.waitForTimeout(700);
    await page.screenshot({ path: `${DIR}/03-permissions-matrix.png`, fullPage: true });

    // 4. Members right tab
    await page.locator('[data-testid="permission-right-tab-members"]').click().catch(() => {});
    await page.waitForTimeout(700);
    await page.screenshot({ path: `${DIR}/04-members.png`, fullPage: true });

    // 5. Add member dialog (org tree picker) — open + screenshot, never submit
    const addBtn = page.locator('[data-testid="role-member-add-btn"], [data-testid="add-member-btn"]').first();
    if (await addBtn.count()) {
      await addBtn.click().catch(() => {});
      await page.waitForTimeout(700);
      await page.screenshot({ path: `${DIR}/05-add-member-dialog.png`, fullPage: true });
      await page.keyboard.press('Escape').catch(() => {});
    }

    // 6. Role create/edit dialog — open + screenshot, never submit
    await page.locator('[data-testid="role-create-btn"]').click().catch(() => {});
    await page.waitForTimeout(600);
    await page.screenshot({ path: `${DIR}/06-role-form-dialog.png`, fullPage: true });
    await page.keyboard.press('Escape').catch(() => {});
    await page.waitForTimeout(300);

    // 7. Assignments top tab
    await page.goto('/enterprise/permissions?tab=assignments', { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(1200);
    await page.screenshot({ path: `${DIR}/07-assignments.png`, fullPage: true });

    // capture viewport (above-the-fold) of the roles page too for first-impression review
    await page.goto('/enterprise/permissions', { waitUntil: 'domcontentloaded' });
    await expect(page.locator('[data-testid="role-table"]')).toBeVisible({ timeout: 15000 });
    await page.waitForTimeout(500);
    await page.screenshot({ path: `${DIR}/00-roles-viewport.png` });
  });
});
