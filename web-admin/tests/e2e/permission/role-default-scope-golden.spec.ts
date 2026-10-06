import { test, expect, type Page } from '@playwright/test';
import { BASE_URL } from '../../helpers/environments';

/**
 * Permission v2 ② — role-level default data scope golden.
 *
 * Proves the owner-requested behavior end-to-end on a real browser + real backend: setting a role's
 * default data scope (② drawer) makes newly-granted permissions INHERIT that scope (materialized at
 * grant time), not just apply to current grants. Grants via the primary capability editor and verifies the inherited scope in the capability settings.
 */

const BASE = BASE_URL;

async function captureStablePage(page: Page, path: string) {
  // Wait for transient notifications to finish without hiding persistent error states.
  await expect(page.getByRole('button', { name: 'Close notification', exact: true })).toHaveCount(0);
  await page.screenshot({ path, fullPage: true });
}

async function createRole(page: Page) {
  const code = `e2e_defscope_${Date.now()}`;
  const resp = await page.request.post(`${BASE}/api/roles`, {
    data: {
      code,
      name: `DefScope ${Date.now()}`,
      description: 'role default scope golden',
      type: 'custom',
    },
  });
  expect(resp.ok()).toBeTruthy();
  return (await resp.json()).data as { pid: string; code: string };
}

async function selectRoleFromMenu(page: Page, role: { pid: string; code: string }) {
  await page.goto('/home');
  await expect(page.locator('header[data-hydrated]')).toHaveAttribute('data-hydrated', 'true');
  await page.getByRole('link', { name: /角色|Roles/, exact: true }).click();
  await expect(page.getByTestId('permission-page')).toBeVisible();
  await page.getByTestId('role-search-input').fill(role.code);
  await page.getByTestId(`role-item-${role.code}`).click();
  await expect(page.getByTestId('capability-role-editor')).toHaveAttribute('data-role-pid', role.pid);
}

for (const scope of ['dept', 'team']) {
  test(`a role default ${scope} data scope is inherited by newly-granted permissions`, async ({
    page,
  }, info) => {
    const role = await createRole(page);

    await selectRoleFromMenu(page, role);

    // ② set the role default scope to "dept" (仅本部门) via the drawer
    await page.getByTestId('data-scope-modify-btn').click();
    await expect(page.getByTestId('data-scope-drawer')).toBeVisible();
    await page.getByTestId(`data-scope-option-${scope}`).click();
    await page.getByTestId('data-scope-apply').click();
    await expect(page.getByTestId('data-scope-drawer')).toHaveCount(0, { timeout: 10_000 });
    await expect(page.getByTestId('data-scope-default')).toContainText(
      scope === 'team' ? /团队|My teams/ : /仅本部门|Dept Only|本部门/,
      {
        timeout: 10_000,
      },
    );

    // Grant a complete declared business capability through the primary editor.
    const capabilityCode = 'qo.cap.quote_view';
    const checkbox = page.getByTestId(`capability-checkbox-${capabilityCode}`);
    await expect(checkbox).not.toBeChecked();
    await checkbox.click();
    await page.getByTestId('capability-save').click();
    await expect(page.getByTestId('confirm-dialog')).toBeVisible();
    const grantResp = page.waitForResponse(
      (response) =>
        response.url().includes('/api/permission/capabilities?') &&
        response.request().method() === 'PUT',
    );
    await page.getByTestId('confirm-ok').click();
    const response = await grantResp;
    expect(response.status()).toBe(200);
    expect(String((await response.json()).code)).toBe('0');
    await expect(page.getByTestId('capability-save')).toBeDisabled();
    await page.getByTestId(`capability-scope-configure-${capabilityCode}`).click();
    await expect(page.getByTestId('capability-scope-dialog')).toBeVisible();
    const scopeSelect = page.getByTestId('capability-scope-model.qo_quote_common.read');
    await expect(scopeSelect).toHaveValue(scope);
    const dialog = page.getByTestId('capability-scope-dialog');
    const scopedActions = [
      'qo.quote.read',
      'sys.file.read',
      'model.qo_quote_common.read',
      'model.qo_quote_line_common.read',
      'model.crm_customer_request_common.read',
      'model.crm_customer_request_pcba_rfq.read',
      'model.qo_supplier_request_line_common.read',
    ];
    await expect(dialog.getByRole('combobox')).toHaveCount(scopedActions.length);
    for (const action of scopedActions)
      await expect(dialog.getByTestId(`capability-scope-${action}`)).toHaveValue(scope);
    // Tab visibility is still granted, but has no record resource/action scope mapping.
    await expect(dialog.getByTestId('capability-scope-qo.quote.material.read')).toHaveCount(0);
    await expect(dialog).not.toContainText('范围配置无效');
    await expect(dialog).not.toContainText('Qo_supplier_request_line_common');
    const matrixResponse = await page.request.get(`${BASE}/api/permissions/matrix/${role.pid}`);
    expect(matrixResponse.ok()).toBeTruthy();
    const actions = (await matrixResponse.json()).data.modules
      .flatMap((module: any) => module.resources)
      .flatMap((resource: any) => resource.actions);
    expect(
      actions.find((action: any) => action.code === 'model.qo_quote_common.read').scopeType,
    ).toBe(scope);
    expect(actions.find((action: any) => action.code === 'qo.quote.material.read')).toMatchObject({
      granted: true,
      scopeType: null,
    });
    await expect(page.getByTestId('data-scope-current')).toContainText(
      scope === 'team' ? /团队|My teams/ : /仅本部门|Dept Only|本部门/,
    );
    await expect(page.getByTestId('data-scope-current')).not.toContainText(/多种范围|混合|Mixed|Multiple scopes/);

    await captureStablePage(page, info.outputPath(`01-inherited-${scope}-scope.png`));

    await page.keyboard.press('Escape');
    await expect(dialog).toHaveCount(0);
    await page.getByTestId('data-scope-current').scrollIntoViewIfNeeded();
    await expect(page.getByTestId('data-scope-current')).toBeInViewport();
    await expect(page.getByTestId('data-scope-default')).toBeInViewport();
    await captureStablePage(page, info.outputPath(`02-inherited-${scope}-summary.png`));

    // backend cross-check: the role's stored default is persisted
    const defResp = await page.request.get(
      `${BASE}/api/permissions/matrix/${role.pid}/default-scope`,
    );
    expect(defResp.ok()).toBeTruthy();
    expect((await defResp.json()).data).toBe(scope);
  });
}

test('actual mixed record scopes remain separate from the stored role default', async ({ page }, info) => {
  const role = await createRole(page);
  await selectRoleFromMenu(page, role);
  await page.getByTestId('data-scope-modify-btn').click();
  await page.getByTestId('data-scope-option-team').click();
  await page.getByTestId('data-scope-apply').click();
  await expect(page.getByTestId('data-scope-drawer')).toHaveCount(0);
  await page.getByTestId('capability-checkbox-qo.cap.quote_view').check();
  await page.getByTestId('capability-save').click();
  await page.getByTestId('confirm-ok').click();
  await expect(page.getByTestId('capability-save')).toBeDisabled();
  await expect(page.getByTestId('data-scope-current')).toContainText(/团队|My teams/);
  await page.getByTestId('capability-scope-configure-qo.cap.quote_view').click();
  await page.getByTestId('capability-scope-model.qo_quote_common.read').selectOption('self');
  const write = page.waitForResponse(response => response.request().method() === 'PUT' &&
    response.url().includes(`/api/permissions/matrix/${role.pid}/scope`));
  await page.getByTestId('capability-scope-apply').click();
  expect((await write).status()).toBe(200);
  await expect(page.getByTestId('capability-scope-dialog')).toHaveCount(0);
  await expect(page.getByTestId('data-scope-current')).toContainText(/多种范围|混合|Mixed|Multiple scopes/);
  await expect(page.getByTestId('data-scope-default')).toContainText(/团队|My teams/);
  const response = await page.request.get(`${BASE}/api/permissions/matrix/${role.pid}`);
  expect(response.status()).toBe(200);
  const actions = (await response.json()).data.modules.flatMap((module: any) => module.resources)
    .flatMap((resource: any) => resource.actions);
  expect(actions.find((action: any) => action.code === 'model.qo_quote_common.read').scopeType).toBe('self');
  expect(actions.find((action: any) => action.code === 'model.qo_quote_line_common.read').scopeType).toBe('team');
  await page.getByTestId('data-scope-current').scrollIntoViewIfNeeded();
  await expect(page.getByTestId('data-scope-current')).toBeInViewport();
  await expect(page.getByTestId('data-scope-default')).toBeInViewport();
  await captureStablePage(page, info.outputPath('scope-mixed.png'));
});

test('missing, failed, and invalid role defaults do not preselect a broader scope', async ({ page }, info) => {
  const role = await createRole(page);
  const url = `${BASE}/api/permissions/matrix/${role.pid}/default-scope`;
  let mode: 'error' | 'real' | 'invalid' = 'error';
  await page.route(url, async route => {
    if (route.request().method() !== 'GET' || mode === 'real') return route.continue();
    await route.fulfill({ status: mode === 'error' ? 503 : 200, contentType: 'application/json',
      body: JSON.stringify(mode === 'error' ? { code: '503', message: 'Injected unavailable' } :
        { code: '0', data: 'invalid-scope-fixture' }) });
  });
  await selectRoleFromMenu(page, role);
  await expect(page.getByTestId('data-scope-default')).toContainText(/读取.*失败|Could not load|加载.*失败/);
  await expect(page.getByTestId('data-scope-modify-btn')).toBeDisabled();
  await captureStablePage(page, info.outputPath('scope-load-error.png'));
  mode = 'real';
  await page.getByTestId('data-scope-retry').click();
  await expect(page.getByTestId('data-scope-default')).toContainText(/未配置|Not configured/);
  await page.getByTestId('data-scope-default').scrollIntoViewIfNeeded();
  await expect(page.getByTestId('data-scope-default')).toBeInViewport();
  await captureStablePage(page, info.outputPath('scope-not-configured-summary.png'));
  await page.getByTestId('data-scope-modify-btn').click();
  await expect(page.getByTestId('data-scope-apply')).toBeDisabled();
  await expect(page.getByTestId('data-scope-drawer').getByRole('radio', { checked: true })).toHaveCount(0);
  await captureStablePage(page, info.outputPath('scope-not-configured.png'));
  await page.keyboard.press('Escape');
  mode = 'invalid';
  await selectRoleFromMenu(page, role);
  await expect(page.getByTestId('data-scope-default')).toContainText(/无效|Invalid scope/);
  await page.getByTestId('data-scope-default').scrollIntoViewIfNeeded();
  await expect(page.getByTestId('data-scope-default')).toBeInViewport();
  await captureStablePage(page, info.outputPath('scope-invalid-summary.png'));
  await page.getByTestId('data-scope-modify-btn').click();
  await expect(page.getByTestId('data-scope-apply')).toBeDisabled();
  await expect(page.getByTestId('data-scope-drawer').getByRole('radio', { checked: true })).toHaveCount(0);
  await captureStablePage(page, info.outputPath('scope-invalid.png'));
  const stored = await page.request.get(url);
  expect(stored.status()).toBe(200);
  expect((await stored.json()).data).toBeNull();
  await page.unroute(url);
});

test('capability save grants via the precision-safe rolePid endpoint (snowflake-id role)', async ({
  page,
}) => {
  // Regression guard for the snowflake-id precision bug: the capability endpoint must key on the
  // role PID (string), not the numeric id (which round-trips lossily through the browser and would
  // FK-violate / target the wrong role). Driven at the API layer the editor uses.
  const role = await createRole(page);

  const capUrl = `${BASE}/api/permission/capabilities?rolePid=${encodeURIComponent(role.pid)}`;
  const grantedCaps = (groups: any[]) =>
    groups.flatMap((g) => g.capabilities).filter((c: any) => c.granted);

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
