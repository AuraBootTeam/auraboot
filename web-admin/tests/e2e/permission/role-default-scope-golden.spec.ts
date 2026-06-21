import { test, expect, type Page } from '@playwright/test';

/**
 * Permission v2 ② — role-level default data scope golden.
 *
 * Proves the owner-requested behavior end-to-end on a real browser + real backend: setting a role's
 * default data scope (② drawer) makes newly-granted permissions INHERIT that scope (materialized at
 * grant time), not just apply to current grants. Grants via the ③ advanced atomic checkbox (the
 * precision-safe rolePid grant path) and asserts the new code's scope select reads the default.
 */

const SHOTS = 'test-results/rbac-default-scope';
const BASE = process.env.PLAYWRIGHT_BASE_URL || 'http://127.0.0.1:5158';

async function createRole(page: Page) {
  const code = `e2e_defscope_${Date.now()}`;
  const resp = await page.request.post(`${BASE}/api/roles`, {
    data: { code, name: `DefScope ${Date.now()}`, description: 'role default scope golden', type: 'custom' },
  });
  expect(resp.ok()).toBeTruthy();
  return (await resp.json()).data as { pid: string; code: string };
}

test('a role default data scope is inherited by newly-granted permissions', async ({ page }) => {
  const role = await createRole(page);

  await page.goto('/enterprise/permissions');
  await expect(page.getByTestId('permission-page')).toBeVisible({ timeout: 30_000 });
  await page.getByTestId('role-search-input').fill(role.code);
  await expect(page.getByTestId(`role-item-${role.code}`)).toBeVisible({ timeout: 10_000 });
  await page.getByTestId(`role-item-${role.code}`).click();
  await expect(page.getByTestId('capability-role-editor')).toBeVisible({ timeout: 15_000 });

  // ② set the role default scope to "dept" (仅本部门) via the drawer
  await page.getByTestId('data-scope-modify-btn').click();
  await expect(page.getByTestId('data-scope-drawer')).toBeVisible();
  await page.getByTestId('data-scope-option-dept').click();
  await page.getByTestId('data-scope-apply').click();
  await expect(page.getByTestId('data-scope-drawer')).toHaveCount(0, { timeout: 10_000 });
  await expect(page.getByTestId('data-scope-current')).toContainText(/仅本部门|Dept Only|本部门/, { timeout: 10_000 });

  // ③ expand advanced; grant a NEW atomic permission via its checkbox (precision-safe rolePid path)
  await page.getByTestId('advanced-atomic-toggle').click();
  await expect(page.getByTestId('advanced-atomic-body')).toBeVisible();
  const firstCheckbox = page.locator('[data-testid^="atomic-checkbox-"]').first();
  await expect(firstCheckbox).toBeVisible({ timeout: 10_000 });
  const tid = await firstCheckbox.getAttribute('data-testid');
  const code = tid!.replace('atomic-checkbox-', '');

  // a newly-granted code has no scope <select> until granted
  await expect(page.getByTestId(`atomic-scope-${code}`)).toHaveCount(0);

  // grant it — the hook materializes the role default onto this new grant; the editor refetches
  const grantResp = page.waitForResponse(
    (r) => r.url().includes('/api/permissions/matrix/') && r.url().includes('/batch'),
    { timeout: 10_000 },
  );
  await firstCheckbox.check();
  await grantResp;

  // the newly-granted code's scope select must read "dept" — INHERITED from the role default
  const scopeSelect = page.getByTestId(`atomic-scope-${code}`);
  await expect(scopeSelect).toBeVisible({ timeout: 10_000 });
  await expect(scopeSelect).toHaveValue('dept');

  await page.screenshot({ path: `${SHOTS}/01-inherited-scope.png`, fullPage: true });

  // backend cross-check: the role's stored default is persisted
  const defResp = await page.request.get(`${BASE}/api/permissions/matrix/${role.pid}/default-scope`);
  expect(defResp.ok()).toBeTruthy();
  expect((await defResp.json()).data).toBe('dept');
});

test('capability save grants via the precision-safe rolePid endpoint (snowflake-id role)', async ({ page }) => {
  // Regression guard for the snowflake-id precision bug: the capability endpoint must key on the
  // role PID (string), not the numeric id (which round-trips lossily through the browser and would
  // FK-violate / target the wrong role). Driven at the API layer the editor uses.
  const role = await createRole(page);

  const capUrl = `${BASE}/api/permission/capabilities?rolePid=${encodeURIComponent(role.pid)}`;
  const grantedCaps = (groups: any[]) => groups.flatMap((g) => g.capabilities).filter((c: any) => c.granted);

  // a freshly-created custom role has no granted capabilities yet
  const before = (await (await page.request.get(capUrl)).json()).data as Array<{
    capabilities: Array<{ code: string; includes: string[]; granted: boolean }>;
  }>;
  expect(grantedCaps(before).length).toBe(0);

  // pick a capability that expands to permission codes
  const cap = before.flatMap((g) => g.capabilities).find((c) => (c.includes?.length ?? 0) > 0);
  expect(cap, 'a capability with includes must exist').toBeTruthy();

  // save it via the rolePid endpoint — must succeed (no FK violation on the snowflake id) and grant
  const put = await page.request.put(capUrl, { data: [cap!.code] });
  expect(put.status()).toBe(200);

  // the role now holds the granted capability (proves the endpoint targeted the right role)
  const after = (await (await page.request.get(capUrl)).json()).data as any[];
  expect(grantedCaps(after).length).toBeGreaterThan(0);
  expect(grantedCaps(after).some((c: any) => c.code === cap!.code)).toBeTruthy();
});
