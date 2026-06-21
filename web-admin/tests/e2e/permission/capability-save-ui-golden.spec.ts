import { test, expect, type Page } from '@playwright/test';

/**
 * Regression guard for the snowflake-id precision bug (fixed in #993): capability save must work when
 * a role is selected and saved entirely through the BROWSER. Role ids are snowflakes beyond JS
 * safe-integer range; the API-level golden can't catch a numeric-id round-trip through the browser,
 * so this drives the real ① capability checklist → Save → persisted-grant flow on a snowflake-id
 * role. If the capability surface ever reverts to keying on the numeric id, the lossy id resolves to
 * the wrong (non-existent) role and the grant silently fails — this test then goes red.
 */

const SHOTS = 'test-results/rbac-capability-save';
const BASE = process.env.PLAYWRIGHT_BASE_URL || 'http://127.0.0.1:5158';

async function createRole(page: Page) {
  const code = `e2e_capsave_${Date.now()}`;
  const resp = await page.request.post(`${BASE}/api/roles`, {
    data: { code, name: `CapSave ${Date.now()}`, description: 'capability save ui golden', type: 'custom' },
  });
  expect(resp.ok()).toBeTruthy();
  return (await resp.json()).data as { pid: string; code: string };
}

test('① capability save persists through the browser on a snowflake-id role', async ({ page }) => {
  const role = await createRole(page);
  const capUrl = `${BASE}/api/permission/capabilities?rolePid=${encodeURIComponent(role.pid)}`;
  const grantedCaps = (groups: any[]) => groups.flatMap((g) => g.capabilities).filter((c: any) => c.granted);

  // pick a capability that expands to real codes (avoid vacuous empty-includes ones)
  const view = (await (await page.request.get(capUrl)).json()).data as Array<{
    capabilities: Array<{ code: string; includes: string[]; granted: boolean }>;
  }>;
  expect(grantedCaps(view).length).toBe(0);
  const cap = view.flatMap((g) => g.capabilities).find((c) => (c.includes?.length ?? 0) > 0);
  expect(cap, 'a capability with includes must exist').toBeTruthy();

  // drive the real browser flow: select the role, check the capability, Save, await the PUT
  await page.goto('/enterprise/permissions');
  await expect(page.getByTestId('permission-page')).toBeVisible({ timeout: 30_000 });
  await page.getByTestId('role-search-input').fill(role.code);
  await expect(page.getByTestId(`role-item-${role.code}`)).toBeVisible({ timeout: 10_000 });
  await page.getByTestId(`role-item-${role.code}`).click();
  await expect(page.getByTestId('capability-role-editor')).toBeVisible({ timeout: 15_000 });

  const checkbox = page.getByTestId(`capability-checkbox-${cap!.code}`);
  await checkbox.scrollIntoViewIfNeeded();
  await checkbox.check();
  await expect(page.getByTestId('capability-save')).toBeEnabled(); // waits for React to register the selection

  const saveResp = page.waitForResponse(
    (r) => r.url().includes('/api/permission/capabilities') && r.request().method() === 'PUT',
    { timeout: 15_000 },
  );
  await page.getByTestId('capability-save').click();
  expect((await saveResp).status()).toBe(200);

  // the checkbox stays checked after the editor reloads (grant persisted, not reverted)
  await expect(checkbox).toBeChecked({ timeout: 10_000 });
  // wait for the save to fully settle (button leaves the "saving" state) for a clean evidence shot
  await expect(page.getByTestId('capability-save')).toBeDisabled({ timeout: 10_000 });

  // backend cross-check: the role actually holds the capability now (right role targeted)
  const after = (await (await page.request.get(capUrl)).json()).data as any[];
  expect(grantedCaps(after).some((c: any) => c.code === cap!.code)).toBeTruthy();

  await checkbox.scrollIntoViewIfNeeded();
  await page.screenshot({ path: `${SHOTS}/01-capability-saved.png`, fullPage: true });
});
