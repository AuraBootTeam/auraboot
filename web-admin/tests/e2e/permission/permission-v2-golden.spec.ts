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

test('v2 permissions: members surface supports add and remove without raw i18n keys', async ({ page }) => {
  const code = `e2e_members_${Date.now()}`;
  const created = await page.request.post('/api/roles', { data: { code, name: code, type: 'custom' } });
  expect(created.ok()).toBe(true);
  const role = (await created.json()).data;
  await gotoPermissions(page);
  await page.getByTestId('role-search-input').fill(code);
  await page.getByTestId(`role-item-${code}`).click();
  await expect(page.getByTestId('capability-role-editor')).toHaveAttribute('data-role-pid', role.pid);
  await page.getByTestId('permission-right-tab-members').click();
  await expect(page.getByTestId('role-member-empty')).toBeVisible();
  await page.getByTestId('role-member-add-btn').click();
  const dialog = page.getByTestId('add-member-dialog');
  await expect(dialog).toBeVisible();
  await expect(page.getByTestId('add-member-list-search')).toBeVisible();
  await expect(dialog).toContainText('限时授权');
  await expect(dialog).not.toContainText('Time-bounded access');
  await expect(dialog).not.toContainText('admin.permission.members.');
  const candidate = dialog.locator('tbody tr').first();
  await expect(candidate).toBeVisible();
  const memberId = (await candidate.getAttribute('data-testid'))!.replace('candidate-row-', '');
  await candidate.getByRole('checkbox').check();
  await page.screenshot({ path: `${SHOTS}/04-members.png`, fullPage: true });
  const added = page.waitForResponse(r => r.url().includes(`/api/roles/${role.pid}/members/assign`) && r.request().method() === 'POST');
  await page.getByTestId('add-member-confirm').click();
  const assignment = await added;
  expect(assignment.status()).toBe(200);
  expect(String((await assignment.json()).code)).toBe('0');
  const memberPids = assignment.request().postDataJSON().memberPids;
  expect(memberPids).toHaveLength(1);
  await expect(dialog).not.toBeVisible();
  const row = page.getByTestId(`role-member-row-${memberId}`);
  await expect(row).toBeVisible();
  await expect(row).toContainText('长期有效');
  await page.reload();
  await page.getByTestId('role-search-input').fill(code);
  await page.getByTestId(`role-item-${code}`).click();
  await page.getByTestId('permission-right-tab-members').click();
  await expect(row).toBeVisible();
  await page.getByTestId(`role-member-remove-${memberId}`).click();
  await expect(page.getByTestId('confirm-dialog')).toBeVisible();
  await page.screenshot({ path: `${SHOTS}/05-member-remove-confirm.png`, fullPage: true });
  const removed = page.waitForResponse(r => r.url().includes(`/api/roles/${role.pid}/members/remove`) && r.request().method() === 'POST');
  await page.getByTestId('confirm-ok').click();
  const removal = await removed;
  expect(removal.status()).toBe(200);
  expect(removal.request().postDataJSON()).toEqual(memberPids);
  expect(String((await removal.json()).code)).toBe('0');
  await expect(row).not.toBeVisible();
  await page.reload();
  await page.getByTestId('role-search-input').fill(code);
  await page.getByTestId(`role-item-${code}`).click();
  await page.getByTestId('permission-right-tab-members').click();
  await expect(page.getByTestId('role-member-empty')).toBeVisible();
  await page.screenshot({ path: `${SHOTS}/06-members-removed-persisted.png`, fullPage: true });
});
