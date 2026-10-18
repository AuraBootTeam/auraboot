import {
  test,
  expect,
  type Page,
  type TestInfo,
  type APIResponse,
  type Response,
} from '@playwright/test';
import fs from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import pg from 'pg';
import { PG_CONN } from '../../helpers/environments';
import { provisionInternalApiUser } from '../../helpers/internal-api-owned-user';
import { createResponseShapeValidator } from '../../../../scripts/internal-api/response-shape.mjs';
const document = JSON.parse(
  await fs.readFile(
    new URL('../../../../contracts/internal-web-admin/openapi.json', import.meta.url),
    'utf8',
  ),
);
const catalog = JSON.parse(
  await fs.readFile(
    new URL('../../../../scripts/internal-api/catalog.json', import.meta.url),
    'utf8',
  ),
);
const declarations = JSON.parse(
  await fs.readFile(
    new URL('../../../../plugins/org-management/config/capabilities.json', import.meta.url),
    'utf8',
  ),
);
const fixture = declarations.find((row: { code: string }) => row.code === 'org.cap.hr_view');
const validate = createResponseShapeValidator(document);
async function capture(id: string, page: Page, info: TestInfo) {
  const scene = catalog.scenarios.find((row: { id: string }) => row.id === id);
  expect(scene?.test).toBe(info.title);
  const path = info.outputPath(scene.screenshot);
  await page.screenshot({ path, fullPage: true, animations: 'disabled' });
  await info.attach(id, { path, contentType: 'image/png' });
  info.annotations.push({ type: 'internal-api-scenario', description: id });
}

test('generated capabilities preserve menu preview apply reload and revoke with persisted atomic grants', async ({
  browser,
  baseURL,
}, info) => {
  test.setTimeout(150_000);
  if (!baseURL) throw new Error('A managed runtime is required');
  expect(PG_CONN.database).toMatch(/^auraboot_\d+$/);
  expect(fixture?.includes.length).toBeGreaterThan(0);
  const expectedCodes: string[] = [...fixture.includes].sort();
  const owner = await provisionInternalApiUser(browser, baseURL);
  let denied: Awaited<ReturnType<typeof provisionInternalApiUser>> | undefined;
  const anonymous = await browser.newContext({
    baseURL,
    storageState: { cookies: [], origins: [] },
  });
  const db = new pg.Client(PG_CONN);
  const { context, page, origin } = owner,
    headers = { Origin: origin };
  const roleCode = `ic_cap_${randomUUID().replaceAll('-', '').slice(0, 16)}`;
  let rolePid: string | undefined,
    journeyError: unknown,
    connected = false;
  const rows: object[] = [],
    writes: object[] = [],
    storageProofs: object[] = [],
    denials: object[] = [];
  const cleanupErrors: unknown[] = [],
    pageErrors: string[] = [];
  page.on('pageerror', (error) => pageErrors.push(error.message));
  async function checked(response: APIResponse | Response, method: string, path: string) {
    const payload = await response.json();
    const shape = validate({
      method,
      path,
      status: response.status(),
      contentType: response.headers()['content-type'],
      payload,
    });
    rows.push({ method, path, status: response.status(), shape });
    expect(response.status()).toBe(200);
    expect(payload.code).toBe('0');
    expect(shape.errors).toEqual([]);
    return payload.data;
  }
  const responseFor = (method: string, preview = false) =>
    page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname ===
          `/api/permission/capabilities${preview ? '/preview' : ''}` &&
        new URL(response.url()).searchParams.get('rolePid') === rolePid &&
        response.request().method() === method,
    );
  async function selectedRole() {
    await expect(page.getByTestId('permission-page')).toBeVisible();
    await page.getByTestId('role-search-input').fill(roleCode);
    const loaded = responseFor('GET');
    await page.getByTestId(`role-item-${roleCode}`).click();
    const groups = await checked(await loaded, 'get', '/api/permission/capabilities');
    const capability = groups
      .flatMap(
        (group: { capabilities: Array<{ code: string; includes: string[] }> }) =>
          group.capabilities,
      )
      .find((cap: { code: string }) => cap.code === fixture.code);
    expect(capability?.includes.slice().sort()).toEqual(expectedCodes);
    await expect(page.getByTestId('capability-role-editor')).toHaveAttribute(
      'data-role-pid',
      rolePid!,
    );
    return capability;
  }
  async function storage(phase: string, expected: string[]) {
    const result = await db.query(
      `SELECT DISTINCT p.code FROM ab_role_permission rp
      JOIN ab_role r ON r.id=rp.role_id JOIN ab_permission p ON p.id=rp.permission_id
      WHERE r.pid=$1 AND r.code=$2 AND rp.grant_type='grant' AND rp.deleted_flag=false AND rp.status='active'
      AND (rp.effective_date IS NULL OR rp.effective_date<=CURRENT_DATE)
      AND (rp.expiry_date IS NULL OR rp.expiry_date>=CURRENT_DATE) ORDER BY p.code`,
      [rolePid, roleCode],
    );
    const codes = result.rows.map((row) => row.code);
    expect(codes).toEqual(expected);
    storageProofs.push({ phase, codes });
  }
  async function reviewAndSave(
    selected: string[],
    expectedGrant: string[],
    expectedRevoke: string[],
  ) {
    const previewed = responseFor('POST', true);
    await page.getByTestId('capability-save').click();
    const response = await previewed;
    const body = response.request().postDataJSON();
    expect(body).toEqual(selected);
    writes.push({ method: 'post', path: '/api/permission/capabilities/preview', body });
    const plan = await checked(response, 'post', '/api/permission/capabilities/preview');
    expect(plan.grantedCodes.slice().sort()).toEqual(expectedGrant);
    expect(plan.revokedCodes.slice().sort()).toEqual(expectedRevoke);
    expect(plan.preservedCodes).toEqual([]);
    await expect(page.getByTestId('capability-preview-summary')).toBeVisible();
    return async () => {
      const applied = responseFor('PUT');
      await page.getByTestId('confirm-ok').click();
      const response = await applied;
      const body = response.request().postDataJSON();
      expect(body).toEqual(selected);
      writes.push({ method: 'put', path: '/api/permission/capabilities', body });
      await checked(response, 'put', '/api/permission/capabilities');
      await expect(page.getByTestId('confirm-dialog')).not.toBeVisible();
      await expect(page.getByTestId('capability-save')).toBeDisabled();
    };
  }
  try {
    await db.connect();
    connected = true;
    await db.query('SET default_transaction_read_only=on');
    denied = await provisionInternalApiUser(browser, baseURL, null);
    const created = await context.request.post('/api/roles', {
      headers,
      data: { code: roleCode, name: 'Contract capability role', type: 'custom' },
    });
    const body = await created.json();
    if (typeof body.data?.pid === 'string') rolePid = body.data.pid;
    expect(created.status()).toBe(200);
    expect(body.code).toBe('0');
    expect(rolePid).toBeTruthy();
    const menu = page.locator('nav').getByRole('link', { name: /^Roles$|^角色$/ });
    if (!(await menu.isVisible()))
      await page
        .locator('nav')
        .getByRole('button', { name: /Organization|组织管理/i })
        .click();
    await menu.click();
    expect((await selectedRole()).granted).toBe(false);
    await storage('initial', []);
    const checkbox = page.getByTestId('capability-checkbox-org.cap.hr_view');
    await checkbox.check();
    await expect(page.getByTestId('capability-draft')).toBeVisible();
    await capture('CAP-01', page, info);
    const applyGrant = await reviewAndSave([fixture.code], expectedCodes, []);
    await capture('CAP-02', page, info);
    await applyGrant();
    await page.reload();
    expect((await selectedRole()).granted).toBe(true);
    await expect(checkbox).toBeChecked();
    await storage('granted-reload', expectedCodes);
    await capture('CAP-03', page, info);
    await checkbox.uncheck();
    const applyRevoke = await reviewAndSave([], [], expectedCodes);
    await applyRevoke();
    await page.reload();
    expect((await selectedRole()).granted).toBe(false);
    await expect(checkbox).not.toBeChecked();
    await storage('revoked-reload', []);
    await capture('CAP-04', page, info);
    for (const [actor, client, status] of [
      ['anonymous', anonymous, 401],
      ['no-role', denied.context, 403],
    ] as const) {
      for (const [method, suffix, data] of [
        ['get', '', undefined],
        ['post', '/preview', [fixture.code]],
        ['put', '', [fixture.code]],
      ] as const) {
        const response = await client.request.fetch(
          `/api/permission/capabilities${suffix}?rolePid=${encodeURIComponent(rolePid!)}`,
          { method, data, headers },
        );
        denials.push({ actor, method, suffix, status: response.status() });
        expect(response.status()).toBe(status);
      }
    }
    await storage('after-denials', []);
  } catch (error) {
    journeyError = error;
  } finally {
    try {
      const all = await context.request.get('/api/roles/all');
      expect(all.status()).toBe(200);
      const payload = await all.json();
      expect(payload.code).toBe('0');
      const owned = payload.data.filter((row: { code: string }) => row.code === roleCode);
      expect(owned.length).toBeLessThanOrEqual(1);
      if (owned.length) {
        if (rolePid) expect(owned[0].pid).toBe(rolePid);
        rolePid = owned[0].pid;
        const deleted = await context.request.delete(`/api/roles/${rolePid}`, { headers });
        expect(deleted.status()).toBe(200);
        expect((await deleted.json()).code).toBe('0');
        const after = await context.request.get('/api/roles/all');
        expect(after.status()).toBe(200);
        expect(
          (await after.json()).data.some((row: { code: string }) => row.code === roleCode),
        ).toBe(false);
      }
    } catch (error) {
      cleanupErrors.push(error);
    }
    if (connected)
      try {
        await db.end();
      } catch (error) {
        cleanupErrors.push(error);
      }
    for (const dispose of [() => anonymous.close(), () => denied?.dispose(), () => owner.dispose()])
      try {
        await dispose();
      } catch (error) {
        cleanupErrors.push(error);
      }
    await info.attach('capability-browser-observations', {
      body: JSON.stringify({
        rows,
        writes,
        storageProofs,
        denials,
        sessionCleanup: [owner.sessionCleanup, ...(denied ? [denied.sessionCleanup] : [])],
        pageErrors,
        journeyError: journeyError ? String(journeyError) : null,
        cleanupErrors: cleanupErrors.map(String),
        claim:
          'three capability operations through the Roles menu with exact fixture grants and owned cleanup; read-only, partial revocation and cross-tenant branches remain pending',
      }),
      contentType: 'application/json',
    });
  }
  if (journeyError || cleanupErrors.length)
    throw new AggregateError(
      [...(journeyError ? [journeyError] : []), ...cleanupErrors],
      'Capability journey or cleanup failed',
    );
});
