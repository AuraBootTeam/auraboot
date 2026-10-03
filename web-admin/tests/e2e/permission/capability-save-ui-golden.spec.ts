import { test, expect, type Page } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { BASE_URL } from '../../helpers/environments';

/**
 * Regression guard for the snowflake-id precision bug (fixed in #993): capability save must work when
 * a role is selected and saved entirely through the BROWSER. Role ids are snowflakes beyond JS
 * safe-integer range; the API-level golden can't catch a numeric-id round-trip through the browser,
 * so this drives the real ① capability checklist → Save → persisted-grant flow on a snowflake-id
 * role. If the capability surface ever reverts to keying on the numeric id, the lossy id resolves to
 * the wrong (non-existent) role and the grant silently fails — this test then goes red.
 */

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

test('① capability save persists through the browser on a snowflake-id role', async ({
  page,
}, info) => {
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
      unlockedMenus: string[];
    }>;
  }>;
  expect(grantedCaps(view).length).toBe(0);
  const cap = view
    .flatMap((g) => g.capabilities)
    .find(
      (c) => !c.conventionDerived && (c.includes?.length ?? 0) > 0 && c.unlockedMenus?.length > 0,
    );
  expect(cap, 'a declared capability with real actions and related menus must exist').toBeTruthy();

  // drive the real browser flow: select the role, check the capability, Save, await the PUT
  await page.goto('/home');
  await expect(page.locator('header[data-hydrated]')).toHaveAttribute('data-hydrated', 'true');
  await page.getByRole('link', { name: /角色|Roles/, exact: true }).click();
  await expect(page.getByTestId('permission-page')).toBeVisible({ timeout: 30_000 });
  await page.getByTestId('role-search-input').fill(role.code);
  await expect(page.getByTestId(`role-item-${role.code}`)).toBeVisible({ timeout: 10_000 });
  await page.getByTestId(`role-item-${role.code}`).click();
  await expect(page.getByTestId('capability-role-editor')).toHaveAttribute(
    'data-role-pid',
    role.pid,
    { timeout: 15_000 },
  );

  const diagnostics = page.getByTestId('permission-diagnostics');
  await expect(diagnostics).toHaveJSProperty('open', false);
  await expect(page.getByTestId('advanced-atomic-section')).toHaveCount(0);
  await expect(page.getByTestId('data-scope-default')).toContainText(/未配置|Not configured/);
  await page.getByTestId('data-scope-default').scrollIntoViewIfNeeded();
  await expect(page.getByTestId('data-scope-default')).toBeInViewport();
  await page.screenshot({ path: info.outputPath('rbac-initial.png'), fullPage: true });
  await diagnostics.locator(':scope > summary').click();
  await diagnostics.locator('input').fill('model.qo_quote_common.read');
  const diagnosticRow = diagnostics.getByTestId('diagnostic-action-model.qo_quote_common.read');
  await expect(diagnosticRow).toBeVisible();
  const quoteReadCapability = view.flatMap(group => group.capabilities)
    .find(item => item.code === 'qo.cap.quote_view');
  expect(quoteReadCapability).toBeTruthy();
  await expect(diagnostics.locator('input[type="checkbox"]')).toHaveCount(0);
  await expect(diagnostics.locator('select')).toHaveCount(0);
  await expect(diagnostics.locator('button')).toHaveCount(0);
  await expect(page.getByTestId('capability-draft')).toHaveCount(0);
  await diagnostics.scrollIntoViewIfNeeded();
  await page.screenshot({ path: info.outputPath('advanced-source.png'), fullPage: true });
  await diagnostics.locator(':scope > summary').click();

  const checkbox = page.getByTestId(`capability-checkbox-${cap!.code}`);
  const menus = page.getByTestId(`capability-menus-${cap!.code}`);
  await expect(menus).toHaveJSProperty('open', false);
  await menus.locator('summary').click();
  await expect(menus).toHaveJSProperty('open', true);
  await expect(menus.locator('li')).toHaveCount(cap!.unlockedMenus.length);
  for (const menu of cap!.unlockedMenus) await expect(menus).toContainText(menu);
  await menus.scrollIntoViewIfNeeded();
  await page.screenshot({
    path: info.outputPath('00-capability-related-menus.png'),
    fullPage: true,
  });
  await menus.locator('summary').click();
  await expect(menus).toHaveJSProperty('open', false);
  await checkbox.scrollIntoViewIfNeeded();
  await checkbox.check();
  await expect(page.getByTestId('capability-save')).toBeEnabled(); // waits for React to register the selection

  const saveResp = page.waitForResponse(
    (r) => r.url().includes('/api/permission/capabilities') && r.request().method() === 'PUT',
    { timeout: 15_000 },
  );
  const previewResponse = page.waitForResponse(
    (response) =>
      response.url().includes('/api/permission/capabilities/preview') &&
      response.request().method() === 'POST',
  );
  await page.getByTestId('capability-save').click();
  const previewResult = await previewResponse;
  expect(previewResult.status()).toBe(200);
  const previewBody = await previewResult.json();
  expect(String(previewBody.code)).toBe('0');
  await expect(page.getByTestId('confirm-dialog')).toBeVisible();
  await expect(page.getByRole('dialog')).toBeInViewport({ ratio: 1 });
  await expect(page.getByTestId('confirm-ok')).toBeInViewport({ ratio: 1 });
  await expect(page.getByTestId('confirm-cancel')).toBeInViewport({ ratio: 1 });
  await page.screenshot({ path: info.outputPath('00-capability-preview.png'), fullPage: true });
  const impact = page.getByTestId('capability-preview-impact');
  await expect(impact).toHaveJSProperty('open', false);
  await impact.locator(':scope > summary').click();
  await expect(impact).toHaveJSProperty('open', true);
  await expect(page.getByTestId('capability-preview-resulting')).toBeVisible();
  const previewMenus = page.getByTestId('capability-preview-menus');
  await expect(previewMenus).toHaveCount(previewBody.data.relatedMenus.length ? 1 : 0);
  if (previewBody.data.relatedMenus.length) {
    await expect(previewMenus).toHaveJSProperty('open', false);
    await expect(previewMenus.locator('li')).toHaveCount(previewBody.data.relatedMenus.length);
    for (const item of await previewMenus.locator('li').all()) await expect(item).toBeHidden();
  }
  const secondaryCapabilities = previewBody.data.resultingCapabilities.filter(
    (affected: { code: string; authorizationState: string }) =>
      affected.authorizationState === 'partial' && affected.code !== cap!.code,
  );
  const partialImpact = page.getByTestId('capability-preview-partial-impact');
  await expect(partialImpact).toHaveCount(secondaryCapabilities.length > 0 ? 1 : 0);
  if (secondaryCapabilities.length > 0) {
    await expect(partialImpact).toHaveJSProperty('open', false);
    await expect(partialImpact.locator('li')).toHaveCount(secondaryCapabilities.length);
    for (const affected of secondaryCapabilities) {
      await expect(page.getByTestId(`capability-preview-impact-${affected.code}`)).toBeHidden();
    }
    await expect(page.getByTestId(`capability-preview-impact-${cap!.code}`)).toBeVisible();
    await page.screenshot({
      path: info.outputPath('00-capability-preview-partial-collapsed.png'),
      fullPage: true,
    });
    await partialImpact.locator('summary').click();
    await expect(partialImpact).toHaveJSProperty('open', true);
    for (const affected of secondaryCapabilities) {
      await expect(page.getByTestId(`capability-preview-impact-${affected.code}`)).toBeVisible();
    }
  }
  await expect(page.getByTestId('capability-preview-resulting').locator('li')).toHaveCount(
    previewBody.data.resultingCapabilities.length,
  );
  for (const affected of previewBody.data.resultingCapabilities) {
    await expect(page.getByTestId('capability-preview-resulting')).toContainText(affected.label);
  }
  await page.screenshot({
    path: info.outputPath('00-capability-preview-impact.png'),
    fullPage: true,
  });
  if (previewBody.data.relatedMenus.length) {
    await previewMenus.locator(':scope > summary').click();
    await expect(previewMenus).toHaveJSProperty('open', true);
    expect(await previewMenus.locator('li').allTextContents()).toEqual(
      previewBody.data.relatedMenus,
    );
    for (const item of await previewMenus.locator('li').all()) await expect(item).toBeVisible();
    if (secondaryCapabilities.length > 0) await partialImpact.locator(':scope > summary').click();
    await previewMenus.scrollIntoViewIfNeeded();
    await page.screenshot({
      path: info.outputPath('00-capability-preview-menus.png'),
      fullPage: true,
    });
  }
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
  await page.screenshot({ path: info.outputPath('01-capability-saved.png'), fullPage: true });
});

test('capability draft survives failed save and canceled navigation', async ({ page }, info) => {
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
  await checkbox.scrollIntoViewIfNeeded();
  await page.screenshot({
    path: info.outputPath('02-draft-selected-after-error.png'),
    fullPage: true,
  });
  await page.getByTestId('capability-save').scrollIntoViewIfNeeded();
  await expect(page.getByTestId('capability-save')).toBeInViewport({ ratio: 1 });
  await expect(page.getByTestId('capability-draft')).toBeInViewport({ ratio: 1 });
  await page.screenshot({ path: info.outputPath('02-draft-save-error.png'), fullPage: true });
  await page.unroute('**/api/permission/capabilities?**');
  await page.getByTestId('permission-right-tab-members').click();
  await page.getByTestId('confirm-ok').click();
  await expect(page.getByTestId('role-member-tab')).toBeVisible();
});

test('switching roles requires draft confirmation and never saves the discarded selection', async ({ page }, info) => {
  const original = await createRole(page);
  const other = await createRole(page);
  const url = `${BASE}/api/permission/capabilities?rolePid=${encodeURIComponent(original.pid)}`;
  const before = await page.request.get(url);
  expect(before.status()).toBe(200);
  const capability = (await before.json()).data.flatMap((group: any) => group.capabilities)
    .find((item: any) => !item.conventionDerived && item.includes.length > 0);
  expect(capability).toBeTruthy();
  await page.goto('/home');
  await expect(page.locator('header[data-hydrated]')).toHaveAttribute('data-hydrated', 'true');
  await page.getByRole('link', { name: /角色|Roles/, exact: true }).click();
  await page.getByTestId('role-search-input').fill(original.code);
  await page.getByTestId(`role-item-${original.code}`).click();
  await expect(page.getByTestId('capability-role-editor')).toHaveAttribute('data-role-pid', original.pid);
  await page.getByTestId(`capability-checkbox-${capability.code}`).check();
  await expect(page.getByTestId('capability-draft')).toBeVisible();
  await page.getByTestId('role-search-input').fill(other.code);
  await page.getByTestId(`role-item-${other.code}`).click();
  await expect(page.getByTestId('confirm-dialog')).toBeVisible();
  await expect(page.getByTestId('capability-role-editor')).toHaveAttribute('data-role-pid', original.pid);
  await page.screenshot({ path: info.outputPath('rbac-discard-confirm.png'), fullPage: true });
  await page.getByTestId('confirm-cancel').click();
  await expect(page.getByTestId(`capability-checkbox-${capability.code}`)).toBeChecked();
  await page.getByTestId(`role-item-${other.code}`).click();
  await page.getByTestId('confirm-ok').click();
  await expect(page.getByTestId('capability-role-editor')).toHaveAttribute('data-role-pid', other.pid);
  await expect(page.getByTestId('capability-draft')).toHaveCount(0);
  await page.getByTestId('role-search-input').fill(original.code);
  await page.getByTestId(`role-item-${original.code}`).click();
  await expect(page.getByTestId('capability-role-editor')).toHaveAttribute('data-role-pid', original.pid);
  await expect(page.getByTestId(`capability-checkbox-${capability.code}`)).not.toBeChecked();
  const after = await page.request.get(url);
  expect(after.status()).toBe(200);
  expect((await after.json()).data.flatMap((group: any) => group.capabilities)
    .find((item: any) => item.code === capability.code).granted).toBe(false);
});

test('capability grant readback failure blocks stale editing and retry reads the persisted grant', async ({
  page,
}, info) => {
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
  await page.screenshot({
    path: info.outputPath('03-capability-readback-error.png'),
    fullPage: true,
  });
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
  const partialSnapshot = (await readCaps()).find((cap: any) => cap.code === manage.code);
  await expect(page.getByTestId(`capability-partial-${manage.code}`)).toContainText(
    `${partialSnapshot.includes.length - partialSnapshot.missingCodes.length}/${partialSnapshot.includes.length}`,
  );
  await expect(page.getByTestId('capability-partial-guidance')).toHaveCount(1);
  await checkbox.scrollIntoViewIfNeeded();
  await page.screenshot({ path: info.outputPath('partial-actions-status.png') });
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
  await expect(page.getByTestId('confirm-dialog')).toContainText('修改角色');
  await expect(page.getByTestId('confirm-dialog')).not.toContainText('Organization role update');
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
  const completion = page.waitForResponse(
    (response) =>
      response.url().includes('/api/permission/capabilities?') &&
      response.request().method() === 'PUT',
  );
  await page.getByTestId('confirm-ok').click();
  const completed = await completion;
  expect(completed.ok()).toBe(true);
  expect(String((await completed.json()).code)).toBe('0');
  await expect(checkbox).toBeChecked();
  await expect(page.getByTestId('capability-draft')).toHaveCount(0);
  await expect(page.getByTestId('capability-save')).toBeDisabled();
  expect((await readCaps()).find((cap: any) => cap.code === manage.code).authorizationState).toBe(
    'full',
  );
  await page.reload();
  await page.getByTestId('role-search-input').fill(role.code);
  await page.getByTestId(`role-item-${role.code}`).click();
  await expect(checkbox).toBeChecked();
  await expect(page.getByTestId('data-scope-bar')).not.toContainText(/加载中|Loading/i);
  await checkbox.scrollIntoViewIfNeeded();
  await page.screenshot({
    path: info.outputPath('partial-completed-persisted.png'),
    fullPage: true,
  });
});


test('configured display groups support upper-level grant and revoke without changing actions', async ({ page }, info) => {
  const coreRoot = process.env.AURA_CORE_PROJECT_ROOT ?? resolve(process.cwd(), '..');
  const manifest = JSON.parse(readFileSync(resolve(coreRoot, 'plugins/org-management/plugin.json'), 'utf8'));
  const definitions = JSON.parse(readFileSync(resolve(coreRoot, 'plugins/org-management/config/capabilities.json'), 'utf8'));
  const original = definitions.find((item: { code: string }) => item.code === 'org.cap.member_view');
  expect(original).toBeTruthy();
  // Import only the existing declaration; do not import models, menus, or commands.
  delete manifest.resourceDirs;
  const groupName = `验收配置分组 ${Date.now()}`;
  const modified = { ...original, group: groupName, 'name:zh-CN': '验收配置成员查看' };
  const role = await createRole(page);
  const capUrl = `${BASE}/api/permission/capabilities?rolePid=${encodeURIComponent(role.pid)}`;
  const readGroups = async () => {
    const response = await page.request.get(capUrl);
    expect(response.status()).toBe(200);
    const body = await response.json();
    expect(String(body.code)).toBe('0');
    return body.data as Array<{ group: string; capabilities: Array<{
      code: string; includes: string[]; granted: boolean; authorizationState: string;
    }> }>;
  };
  const codesOf = (groups: Awaited<ReturnType<typeof readGroups>>) =>
    groups.flatMap(group => group.capabilities.map(cap => cap.code)).sort();
  const initial = await readGroups();
  expect(initial.flatMap(group => group.capabilities).filter(cap => cap.granted)).toEqual([]);
  const importDeclaration = async (declaration: typeof original, dryRun = false) => {
    const response = await page.request.post(
      `${BASE}/api/plugins/import/execute-direct?conflictStrategy=OVERWRITE&autoDeployProcesses=false&dryRun=${dryRun}`,
      { data: { ...manifest, capabilities: [declaration] } },
    );
    expect(response.status()).toBe(200);
    const result = await response.json();
    expect(dryRun ? result.valid : result.success).toBe(true);
    return result;
  };
  const readCap = async () => (await readGroups()).flatMap(group => group.capabilities)
    .find(cap => cap.code === original.code)!;

  await importDeclaration(modified, true);
  // Always restore this temporary display update, including after a failed UI assertion.
  // Created roles and grant/revoke evidence remain; no database cleanup is performed.
  try {
    const imported = await importDeclaration(modified);
    await info.attach('configured-group-import', { body: JSON.stringify(imported), contentType: 'application/json' });
    const updated = await readGroups();
    expect(codesOf(updated)).toEqual(codesOf(initial));
    expect(updated.find(group => group.group === groupName)?.capabilities.map(cap => cap.code)).toEqual([original.code]);
    expect((await readCap()).includes).toEqual(original.includes);
    expect((await readCap()).authorizationState).toBe('none');

    await page.goto('/home');
    await expect(page.locator('header[data-hydrated]')).toHaveAttribute('data-hydrated', 'true');
    await page.getByRole('link', { name: /角色|Roles/, exact: true }).click();
    await expect(page.getByTestId('permission-page')).toBeVisible();
    await page.getByTestId('role-search-input').fill(role.code);
    await page.getByTestId(`role-item-${role.code}`).click();
    await expect(page.getByTestId('capability-role-editor')).toHaveAttribute('data-role-pid', role.pid);
    const group = page.getByTestId(`capability-group-${groupName}`);
    const checkbox = group.getByTestId(`capability-checkbox-${original.code}`);
    await group.scrollIntoViewIfNeeded();
    await expect(group).toContainText('验收配置成员查看');
    await expect(checkbox).not.toBeChecked();
    await expect(page.getByTestId('advanced-atomic-section')).toHaveCount(0);
    await page.screenshot({ path: info.outputPath('configured-group-none.png'), fullPage: true });

    for (const grant of [true, false]) {
      await checkbox.scrollIntoViewIfNeeded();
      await checkbox.setChecked(grant);
      const previewPromise = page.waitForResponse(response =>
        response.url().includes('/api/permission/capabilities/preview') && response.request().method() === 'POST');
      await page.getByTestId('capability-save').click();
      const previewResponse = await previewPromise;
      expect(previewResponse.status()).toBe(200);
      const preview = await previewResponse.json();
      expect(String(preview.code)).toBe('0');
      expect(preview.data[grant ? 'grantedCodes' : 'revokedCodes'].sort()).toEqual([...original.includes].sort());
      expect(previewResponse.request().postDataJSON()).toEqual(grant ? [original.code] : []);
      await expect(page.getByTestId('confirm-dialog')).toBeVisible();
      await info.attach(grant ? 'configured-group-grant-preview' : 'configured-group-revoke-preview',
        { body: JSON.stringify(preview.data), contentType: 'application/json' });
      await page.screenshot({ path: info.outputPath(`configured-group-${grant ? 'grant' : 'revoke'}-preview.png`), fullPage: true });
      const savedPromise = page.waitForResponse(response =>
        response.url().includes('/api/permission/capabilities') && response.request().method() === 'PUT');
      await page.getByTestId('confirm-ok').click();
      const saved = await savedPromise;
      expect(saved.status()).toBe(200);
      expect(String((await saved.json()).code)).toBe('0');
      expect(saved.request().postDataJSON()).toEqual(grant ? [original.code] : []);
      expect((await readCap()).authorizationState).toBe(grant ? 'full' : 'none');
      await page.reload();
      await page.getByTestId('role-search-input').fill(role.code);
      await page.getByTestId(`role-item-${role.code}`).click();
      await expect(page.getByTestId('capability-role-editor')).toHaveAttribute('data-role-pid', role.pid);
      await group.scrollIntoViewIfNeeded();
      await expect(checkbox).toBeChecked({ checked: grant });
      await page.screenshot({ path: info.outputPath(`configured-group-${grant ? 'granted' : 'revoked'}.png`), fullPage: true });
    }
  } finally {
    const restored = await importDeclaration(original);
    await info.attach('configured-group-restored', { body: JSON.stringify(restored), contentType: 'application/json' });
    const groups = await readGroups();
    expect(codesOf(groups)).toEqual(codesOf(initial));
    expect(groups.some(group => group.group === groupName)).toBe(false);
    expect(groups.find(group => group.group === original.group)?.capabilities.some(cap => cap.code === original.code)).toBe(true);
    expect((await readCap()).includes).toEqual(original.includes);
  }
});
