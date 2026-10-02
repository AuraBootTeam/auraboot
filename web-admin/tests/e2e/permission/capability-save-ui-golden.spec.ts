import { test, expect, type Page } from '@playwright/test';
import { BASE_URL } from '../../helpers/environments';

/**
 * Regression guard for the snowflake-id precision bug (fixed in #993): capability save must work when
 * a role is selected and saved entirely through the BROWSER. Role ids are snowflakes beyond JS
 * safe-integer range; the API-level golden can't catch a numeric-id round-trip through the browser,
 * so this drives the real ① capability checklist → Save → persisted-grant flow on a snowflake-id
 * role. If the capability surface ever reverts to keying on the numeric id, the lossy id resolves to
 * the wrong (non-existent) role and the grant silently fails — this test then goes red.
 */

const SHOTS = 'test-results/rbac-capability-save';
const BASE = BASE_URL;

async function createRole(page: Page) {
  const code = `e2e_capsave_${Date.now()}`;
  const resp = await page.request.post(`${BASE}/api/roles`, {
    data: {
      code,
      name: `CapSave ${Date.now()}`,
      description: 'capability save ui golden',
      type: 'custom',
    },
  });
  expect(resp.ok()).toBeTruthy();
  return (await resp.json()).data as { pid: string; code: string };
}

test('① capability save persists through the browser on a snowflake-id role', async ({ page }) => {
  const role = await createRole(page);
  const capUrl = `${BASE}/api/permission/capabilities?rolePid=${encodeURIComponent(role.pid)}`;
  const grantedCaps = (groups: any[]) =>
    groups.flatMap((g) => g.capabilities).filter((c: any) => c.granted);

  // pick a capability that expands to real codes (avoid vacuous empty-includes ones)
  const view = (await (await page.request.get(capUrl)).json()).data as Array<{
    capabilities: Array<{
      code: string;
      includes: string[];
      granted: boolean;
      conventionDerived: boolean;
    }>;
  }>;
  expect(grantedCaps(view).length).toBe(0);
  const cap = view
    .flatMap((g) => g.capabilities)
    .find((c) => !c.conventionDerived && (c.includes?.length ?? 0) > 0);
  expect(cap, 'a capability with includes must exist').toBeTruthy();

  // drive the real browser flow: select the role, check the capability, Save, await the PUT
  await page.goto('/enterprise/permissions');
  await expect(page.getByTestId('permission-page')).toBeVisible({ timeout: 30_000 });
  await page.getByTestId('role-search-input').fill(role.code);
  await expect(page.getByTestId(`role-item-${role.code}`)).toBeVisible({ timeout: 10_000 });
  await page.getByTestId(`role-item-${role.code}`).click();
  await expect(page.getByTestId('capability-role-editor')).toHaveAttribute(
    'data-role-pid',
    role.pid,
    { timeout: 15_000 },
  );

  const checkbox = page.getByTestId(`capability-checkbox-${cap!.code}`);
  await checkbox.scrollIntoViewIfNeeded();
  await checkbox.check();
  await expect(page.getByTestId('capability-save')).toBeEnabled(); // waits for React to register the selection

  const saveResp = page.waitForResponse(
    (r) => r.url().includes('/api/permission/capabilities') && r.request().method() === 'PUT',
    { timeout: 15_000 },
  );
  await page.getByTestId('capability-save').click();
  await expect(page.getByTestId('confirm-dialog')).toBeVisible();
  await page.screenshot({ path: `${SHOTS}/00-capability-preview.png`, fullPage: true });
  await page.getByTestId('confirm-ok').click();
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

test('capability draft survives failed save and canceled navigation', async ({ page }) => {
  const role = await createRole(page);
  const capUrl = `${BASE}/api/permission/capabilities?rolePid=${encodeURIComponent(role.pid)}`;
  const before = (await (await page.request.get(capUrl)).json()).data;
  const cap = before
    .flatMap((g: any) => g.capabilities)
    .find((c: any) => !c.conventionDerived && c.includes?.length);
  expect(cap).toBeTruthy();
  await page.goto('/home');
  await page.getByRole('link', { name: /角色|Roles/, exact: true }).click();
  await expect(page.getByTestId('permission-page')).toBeVisible();
  await page.getByTestId('role-search-input').fill(role.code);
  await page.getByTestId(`role-item-${role.code}`).click();
  await expect(page.getByTestId('capability-role-editor')).toHaveAttribute(
    'data-role-pid',
    role.pid,
  );
  const checkbox = page.getByTestId(`capability-checkbox-${cap.code}`);
  await checkbox.check();
  await expect(checkbox).toBeChecked();
  await expect(page.getByTestId('capability-draft')).toBeVisible();
  await expect(page.getByTestId('data-scope-modify-btn')).toBeDisabled();
  await page.getByTestId('permission-right-tab-members').click();
  await expect(page.getByTestId('confirm-dialog')).toBeVisible();
  await page.getByTestId('confirm-cancel').click();
  await expect(checkbox).toBeChecked();
  await expect(page.getByTestId('capability-draft')).toBeVisible();
  await page.route('**/api/permission/capabilities?**', async (route) => {
    if (route.request().method() === 'PUT')
      await route.fulfill({
        status: 503,
        contentType: 'application/json',
        body: JSON.stringify({ code: 503, message: 'Injected unavailable' }),
      });
    else await route.continue();
  });
  await page.getByTestId('capability-save').click();
  await page.getByTestId('confirm-ok').click();
  await expect(page.getByTestId('capability-save')).toBeEnabled();
  await expect(checkbox).toBeChecked();
  await expect(page.getByTestId('capability-draft')).toBeVisible();
  const after = (await (await page.request.get(capUrl)).json()).data;
  expect(
    after.flatMap((g: any) => g.capabilities).find((c: any) => c.code === cap.code).granted,
  ).toBe(false);
  await page.screenshot({ path: `${SHOTS}/02-draft-save-error.png`, fullPage: true });
  await page.unroute('**/api/permission/capabilities?**');
  await page.getByTestId('permission-right-tab-members').click();
  await page.getByTestId('confirm-ok').click();
  await expect(page.getByTestId('role-member-tab')).toBeVisible();
});

test('capability grant readback failure blocks stale editing and retry reads the persisted grant', async ({
  page,
}) => {
  test.info().annotations.push({
    type: 'fault-injection',
    description: 'Browser GET matrix returns 503 after a real persisted capability grant.',
  });
  const role = await createRole(page);
  await page.goto('/home');
  await expect(page.locator('header[data-hydrated]')).toHaveAttribute('data-hydrated', 'true');
  await page.getByRole('link', { name: /角色|Roles/, exact: true }).click();
  await page.getByTestId('role-search-input').fill(role.code);
  await page.getByTestId(`role-item-${role.code}`).click();
  await expect(page.getByTestId('capability-role-editor')).toHaveAttribute(
    'data-role-pid',
    role.pid,
  );
  const capabilityCode = 'qo.cap.quote_view';
  const checkbox = page.getByTestId(`capability-checkbox-${capabilityCode}`);
  const code = 'qo.quote.read';
  await expect(checkbox).not.toBeChecked();
  const matrixPath = `/api/permissions/matrix/${role.pid}`;
  await page.route(`**${matrixPath}`, async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({
        status: 503,
        contentType: 'application/json',
        body: JSON.stringify({ code: '503', message: 'Injected readback unavailable' }),
      });
    } else await route.continue();
  });
  const grant = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/permission/capabilities' &&
      response.request().method() === 'PUT',
  );
  await checkbox.click();
  await page.getByTestId('capability-save').click();
  await expect(page.getByTestId('confirm-dialog')).toBeVisible();
  await page.getByTestId('confirm-ok').click();
  const response = await grant;
  expect(response.status()).toBe(200);
  expect(String((await response.json()).code)).toBe('0');
  await expect(page.getByTestId('capability-editor-error')).toBeVisible();
  await expect(page.getByTestId('capability-role-editor')).toHaveCount(0);
  const persisted = await page.request.get(`${BASE}${matrixPath}`);
  expect(persisted.ok()).toBeTruthy();
  const actions = (await persisted.json()).data.modules
    .flatMap((module: any) => module.resources)
    .flatMap((resource: any) => resource.actions);
  expect(actions.find((action: any) => action.code === code).granted).toBe(true);
  await page.screenshot({ path: `${SHOTS}/03-capability-readback-error.png`, fullPage: true });
  await page.unroute(`**${matrixPath}`);
  await page
    .getByTestId('capability-editor-error')
    .getByRole('button', { name: /重试|Retry/ })
    .click();
  await expect(page.getByTestId('capability-role-editor')).toHaveAttribute(
    'data-role-pid',
    role.pid,
  );
  await expect(page.getByTestId(`capability-checkbox-${capabilityCode}`)).toBeChecked();
  await expect(page.locator('[data-testid^="atomic-checkbox-"]')).toHaveCount(0);
});

test('partial revocation retains selected shared actions and completion persists', async ({
  page,
}, info) => {
  const role = await createRole(page);
  const capUrl = `${BASE}/api/permission/capabilities?rolePid=${encodeURIComponent(role.pid)}`;
  const matrixUrl = `${BASE}/api/permissions/matrix/${encodeURIComponent(role.pid)}`;
  const readCaps = async () => {
    const response = await page.request.get(capUrl);
    expect(response.ok()).toBe(true);
    return (await response.json()).data.flatMap((group: any) => group.capabilities);
  };
  const readActions = async () => {
    const response = await page.request.get(matrixUrl);
    expect(response.ok()).toBe(true);
    return (await response.json()).data.modules.flatMap((module: any) =>
      module.resources.flatMap((resource: any) => resource.actions),
    );
  };
  const caps = await readCaps();
  const view = caps.find((cap: any) => cap.code === 'org.cap.role_view');
  const manage = caps.find((cap: any) => cap.code === 'org.cap.role');
  expect(view?.includes.length).toBeGreaterThan(0);
  expect(manage?.includes).toContain('org.role.update');
  const actions = await readActions();
  const fixtureCodes = [...new Set<string>([...view.includes, 'org.role.update'])];
  const fixturePids = fixtureCodes.map((code) => {
    const action = actions.find((row: any) => row.code === code);
    expect(action?.permissionPid, `fixture permission ${code}`).toBeTruthy();
    return action.permissionPid;
  });
  const fixture = await page.request.post(`${BASE}/api/roles/${role.pid}/permissions`, {
    data: fixturePids,
  });
  expect(fixture.ok()).toBe(true);
  expect(String((await fixture.json()).code)).toBe('0');
  expect((await readCaps()).find((cap: any) => cap.code === manage.code).authorizationState).toBe(
    'partial',
  );
  await page.goto('/enterprise/permissions');
  await expect(page.getByTestId('permission-page')).toBeVisible();
  await page.getByTestId('role-search-input').fill(role.code);
  await page.getByTestId(`role-item-${role.code}`).click();
  await expect(page.getByTestId('capability-role-editor')).toHaveAttribute(
    'data-role-pid',
    role.pid,
  );
  const checkbox = page.getByTestId(`capability-checkbox-${manage.code}`);
  await expect(checkbox).toHaveAttribute('aria-checked', 'mixed');
  await expect(page.getByTestId(`capability-checkbox-${view.code}`)).toBeChecked();
  await expect(page.getByTestId('capability-save')).toBeDisabled();
  await page.getByTestId(`capability-revoke-partial-${manage.code}`).click();
  await expect(checkbox).toHaveAttribute('aria-checked', 'false');
  const previewResponse = page.waitForResponse(
    (response) =>
      response.url().includes('/api/permission/capabilities/preview') &&
      response.request().method() === 'POST',
  );
  await page.getByTestId('capability-save').click();
  const response = await previewResponse;
  expect(response.ok()).toBe(true);
  const plan = (await response.json()).data;
  expect(plan.revokedCodes).toEqual(['org.role.update']);
  expect(plan.grantedCodes).toEqual([]);
  await expect(page.getByTestId('confirm-dialog')).toBeVisible();
  await page.screenshot({
    path: info.outputPath('partial-revocation-preview.png'),
    fullPage: true,
  });
  const saved = page.waitForResponse(
    (response) =>
      response.url().includes('/api/permission/capabilities?') &&
      response.request().method() === 'PUT',
  );
  await page.getByTestId('confirm-ok').click();
  expect((await saved).ok()).toBe(true);
  await expect(page.getByTestId('capability-save')).toBeDisabled();
  const afterActions = await readActions();
  expect(afterActions.find((row: any) => row.code === 'org.role.update').granted).toBe(false);
  for (const code of view.includes)
    expect(
      afterActions.find((row: any) => row.code === code).granted,
      `shared action ${code}`,
    ).toBe(true);
  await checkbox.check();
  await page.getByTestId('capability-save').click();
  await expect(page.getByTestId('confirm-dialog')).toBeVisible();
  await page.getByTestId('confirm-ok').click();
  await expect(checkbox).toBeChecked();
  await expect(page.getByTestId('capability-save')).toBeDisabled();
  expect((await readCaps()).find((cap: any) => cap.code === manage.code).authorizationState).toBe(
    'full',
  );
  await page.reload();
  await page.getByTestId('role-search-input').fill(role.code);
  await page.getByTestId(`role-item-${role.code}`).click();
  await expect(checkbox).toBeChecked();
  await page.screenshot({
    path: info.outputPath('partial-completed-persisted.png'),
    fullPage: true,
  });
});
