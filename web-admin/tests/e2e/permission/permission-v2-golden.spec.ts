import { test, expect, type Page } from '@playwright/test';

/**
 * Permission v2 (capability-primary / Feishu-style) UI golden.
 *
 * Verifies the reorganized /enterprise/permissions page on a real browser against a host-first
 * stack: ① capability checklist is the default surface with business-language labels (no raw codes),
 * ② data-scope bar + drawer, ③ read-only permission diagnostics (collapsed by default) with related
 * capabilities, and the members surface free of raw i18n keys. Screenshots saved for review.
 */

const SHOTS = 'test-results/rbac-v2-golden';

/** A bare lowercase ascii code segment (e.g. "license", "billing.license") = an untranslated leak. */
const RAW_CODE = /^[a-z][a-z0-9_]*(\.[a-z0-9_]+)*$/;

async function gotoPermissions(page: Page) {
  await page.goto('/enterprise/permissions');
  await expect(page.getByTestId('permission-page')).toBeVisible({ timeout: 30_000 });
  // a role auto-selects → the capability editor (default right tab) mounts
  await expect(page.getByTestId('capability-role-editor')).toBeVisible({ timeout: 30_000 });
}

test('v2 permissions: capability is the default, business-language surface', async ({ page }) => {
  await gotoPermissions(page);

  // ② data-scope bar is present (pulled out of the matrix cells)
  await expect(page.getByTestId('data-scope-bar')).toBeVisible();
  // ① capability checklist present; ③ advanced present but COLLAPSED by default
  await expect(page.getByTestId('capability-checklist')).toBeVisible();
  await expect(page.getByTestId('permission-diagnostics')).toBeVisible();
  await expect(page.locator('[data-testid^="diagnostic-action-"]')).toHaveCount(0);

  // no capability label leaks a raw resource/module code (the §2.1 fix)
  const capLabels = await page
    .locator('[data-testid^="capability-checkbox-"]')
    .evaluateAll((els) =>
      els.map((el) => el.closest('label')?.querySelector('span')?.textContent?.trim() ?? ''),
    );
  const rawLeaks = capLabels.filter((l) => l && RAW_CODE.test(l));
  expect(rawLeaks, `raw-code capability labels leaked: ${rawLeaks.join(', ')}`).toHaveLength(0);

  // group legends should not be bare lowercase module codes either
  const legends = await page.locator('[data-testid^="capability-group-"] legend').allInnerTexts();
  const legendLeaks = legends
    .map((l) => l.replace(/\s*\d+\/\d+\s*$/, '').trim()) // strip the "n/m" summary suffix
    .filter((l) => l && RAW_CODE.test(l));
  expect(legendLeaks, `raw module group leaked: ${legendLeaks.join(', ')}`).toHaveLength(0);

  await page.screenshot({ path: `${SHOTS}/01-capabilities-default.png`, fullPage: true });
});

test('v2 permissions: diagnostics are read only and searchable', async ({ page }) => {
  await gotoPermissions(page);
  const diagnostics = page.getByTestId('permission-diagnostics');
  await diagnostics.locator('summary').first().click();
  const rows = diagnostics.locator('[data-testid^="diagnostic-action-"]');
  await expect(rows.first()).toBeVisible();
  const before = await rows.count();
  expect(before).toBeGreaterThan(0);
  await expect(diagnostics.locator('input[type="checkbox"], select, button')).toHaveCount(0);
  const search = diagnostics.getByRole('textbox');
  await search.fill('zzz-no-such-code');
  await expect(rows).toHaveCount(0);
  await search.fill('');
  await expect(rows).toHaveCount(before);
  await page.screenshot({ path: `${SHOTS}/02-read-only-diagnostics.png`, fullPage: true });
});

test('v2 permissions: ② data-scope drawer opens with scope tiers', async ({ page }) => {
  await gotoPermissions(page);

  await page.getByTestId('data-scope-modify-btn').click();
  await expect(page.getByTestId('data-scope-drawer')).toBeVisible();
  await expect(page.getByTestId('data-scope-option-dept_and_sub')).toBeVisible();
  await expect(page.getByTestId('data-scope-option-self')).toBeVisible();

  await page.screenshot({ path: `${SHOTS}/03-data-scope-drawer.png`, fullPage: true });
  await page.getByTestId('data-scope-drawer-close').click();
  await expect(page.getByTestId('data-scope-drawer')).toHaveCount(0);
});

test('v2 permissions: members surface has no raw i18n keys', async ({ page }) => {
  await gotoPermissions(page);

  await page.getByTestId('permission-right-tab-members').click();
  await expect(page.getByTestId('role-member-tab')).toBeVisible({ timeout: 15_000 });

  // no "admin.permission.members.*" / "sidebar.noMenus" raw keys leak into the rendered body
  const body = (await page.locator('main, [data-testid="role-member-tab"]').first().innerText()) || '';
  expect(body).not.toMatch(/admin\.permission\.members\./);
  expect(body).not.toMatch(/sidebar\.noMenus/);

  // open add-member dialog → org tab shows a graceful empty state (OSS stub), not a blank panel
  await page.getByTestId('role-member-add-btn').click();
  await expect(page.getByTestId('add-member-dialog')).toBeVisible();
  await expect(page.getByTestId('org-tree-picker-empty')).toBeVisible();
  const dialogText = await page.getByTestId('add-member-dialog').innerText();
  expect(dialogText).not.toMatch(/admin\.permission\.members\./);

  await page.screenshot({ path: `${SHOTS}/04-members.png`, fullPage: true });
});
