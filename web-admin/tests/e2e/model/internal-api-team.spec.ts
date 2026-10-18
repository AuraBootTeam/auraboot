import { test, expect, type Page, type TestInfo } from '@playwright/test';
import fs from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import {
  provisionInternalApiUser,
  type InternalApiOwnedUser,
} from '../../helpers/internal-api-owned-user';
import pg from 'pg';
import { PG_CONN } from '../../helpers/environments';
import { createResponseShapeValidator } from '../../../../scripts/internal-api/response-shape.mjs';

const catalog = JSON.parse(
  await fs.readFile(
    new URL('../../../../scripts/internal-api/catalog.json', import.meta.url),
    'utf8',
  ),
);
const document = JSON.parse(
  await fs.readFile(
    new URL('../../../../contracts/internal-web-admin/openapi.json', import.meta.url),
    'utf8',
  ),
);
const validate = createResponseShapeValidator(document);
test.use({ storageState: { cookies: [], origins: [] } });
async function capture(id: string, page: Page, info: TestInfo) {
  const scene = catalog.scenarios.find((row: { id: string }) => row.id === id);
  expect(scene?.test).toBe(info.title);
  const path = info.outputPath(scene.screenshot);
  await page.screenshot({ path, fullPage: true, animations: 'disabled' });
  await info.attach(id, { path, contentType: 'image/png' });
  info.annotations.push({ type: 'internal-api-scenario', description: id });
}

test('generated team memberships preserve the menu add reload and remove journey', async ({
  browser,
  baseURL,
}, info) => {
  test.setTimeout(120_000);
  if (!baseURL) throw new Error('A managed runtime is required');
  const actor = await provisionInternalApiUser(browser, baseURL);
  const { page, context, origin } = actor;
  const code = `ic_team_${randomUUID().replaceAll('-', '').slice(0, 16)}`;
  const name = `Contract team ${code.slice(-8)}`;
  const rows: object[] = [],
    cleanupErrors: unknown[] = [],
    pageErrors: string[] = [];
  let teamPid: string | undefined, journeyError: unknown;
  page.on('pageerror', (error) => pageErrors.push(error.message));
  async function checked(
    response: import('@playwright/test').APIResponse | import('@playwright/test').Response,
    method: string,
    path: string,
  ) {
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
    expect(shape.errors).toEqual([]);
    expect(payload.code).toBe('0');
    return payload.data;
  }
  try {
    const response = await context.request.post('/api/org/teams', {
      headers: { Origin: origin },
      data: { code, name },
    });
    const payload = await response.json();
    if (typeof payload.data?.pid === 'string') teamPid = payload.data.pid;
    expect(response.status()).toBe(200);
    expect(payload.code).toBe('0');
    expect(teamPid).toBeTruthy();
    const menu = page.locator('nav a[href="/organization/teams"]');
    if (!(await menu.isVisible()))
      await page
        .locator('nav')
        .getByRole('button', { name: /Organization|组织管理/i })
        .click();
    await menu.click();
    const search = page.getByTestId('list-search-input');
    await expect(search).toBeVisible();
    await search.fill(name);
    await search.press('Enter');
    const row = page.locator('tbody tr').filter({ hasText: name });
    await expect(row).toHaveCount(1);
    await row.hover();
    await row.getByTestId('row-action-members').click();
    await expect(page).toHaveURL(new RegExp(`/organization/teams/${teamPid}$`));
    await expect(page.getByText('No team members', { exact: true })).toBeVisible();
    await expect(page.getByTestId('report-generate-button')).toHaveText('Report');
    await capture('TEAM-01', page, info);
    await page.getByTestId('team-members-add').click();
    const select = page.getByTestId('team-members-select');
    await expect(select.locator('option').filter({ hasText: actor.username })).toHaveCount(1);
    const option = select.locator('option').filter({ hasText: actor.username });
    const tenantMemberPid = await option.getAttribute('value');
    expect(tenantMemberPid).toBeTruthy();
    await select.selectOption(tenantMemberPid!);
    await capture('TEAM-02', page, info);
    const addedResponse = page.waitForResponse(
      (r) =>
        new URL(r.url()).pathname === `/api/org/teams/${teamPid}/members` &&
        r.request().method() === 'POST',
    );
    await page.getByTestId('team-members-confirm').click();
    const added = await addedResponse;
    expect(added.request().postDataJSON()).toEqual({ memberPid: tenantMemberPid, role: 'member' });
    const membership = await checked(added, 'post', '/api/org/teams/{teamPid}/members');
    expect(membership.userPid).toBe(actor.userPid);
    expect(membership.memberPid).toBe(tenantMemberPid);
    expect(membership.pid).not.toBe(tenantMemberPid);
    expect(membership.userEmail).toBeNull();
    await page.reload({ waitUntil: 'load' });
    const remove = page.getByTestId(`team-members-remove-${membership.pid}`);
    await expect(remove).toBeVisible();
    await expect(remove.locator('xpath=ancestor::tr')).toContainText(actor.username);
    await capture('TEAM-03', page, info);
    const removedResponse = page.waitForResponse(
      (r) =>
        new URL(r.url()).pathname === `/api/org/teams/${teamPid}/members/${membership.pid}` &&
        r.request().method() === 'DELETE',
    );
    page.once('dialog', (dialog) => dialog.accept());
    await remove.click();
    await checked(await removedResponse, 'delete', '/api/org/teams/{teamPid}/members/{memberPid}');
    await page.reload({ waitUntil: 'load' });
    await expect(page.getByText('No team members', { exact: true })).toBeVisible();
    expect(
      await checked(
        await context.request.get(`/api/org/teams/${teamPid}/members`),
        'get',
        '/api/org/teams/{teamPid}/members',
      ),
    ).toEqual([]);
    expect(pageErrors).toEqual([]);
    await capture('TEAM-04', page, info);
  } catch (error) {
    journeyError = error;
  } finally {
    if (teamPid) {
      try {
        await checked(
          await context.request.delete(`/api/org/teams/${teamPid}`, {
            headers: { Origin: origin },
          }),
          'delete',
          '/api/org/teams/{pid}',
        );
      } catch (error) {
        cleanupErrors.push(error);
      }
    }
    try {
      await actor.dispose();
    } catch (error) {
      cleanupErrors.push(error);
    }
    await info.attach('team-browser-observations', {
      body: JSON.stringify({
        claim:
          'owned-member-menu-journey-and-listed-response-structures; no full tenant/role acceptance',
        rows,
        pageErrors,
        journeyError: journeyError ? String(journeyError) : null,
        cleanupErrors: cleanupErrors.map(String),
      }),
      contentType: 'application/json',
    });
  }
  if (journeyError || cleanupErrors.length)
    throw new AggregateError(
      [...(journeyError ? [journeyError] : []), ...cleanupErrors],
      'Team browser journey or cleanup failed',
    );
});

test('team viewer reads the real menu while viewer and unassigned writes are forbidden', async ({
  browser,
  baseURL,
}, info) => {
  test.setTimeout(150_000);
  if (!baseURL) throw new Error('A managed runtime is required');
  const owner = await provisionInternalApiUser(browser, baseURL);
  let viewer: InternalApiOwnedUser | undefined, unassigned: InternalApiOwnedUser | undefined;
  const owned = new Map<string, string>(),
    reads: object[] = [],
    denials: object[] = [],
    cleanupErrors: unknown[] = [];
  const code = `ic_role_${randomUUID().replaceAll('-', '').slice(0, 16)}`;
  const name = `Contract access ${code.slice(-8)}`;
  let journeyError: unknown;
  let rolePid: string | undefined;
  const roleCode = `${code}_reader`;
  const readPermissions = ['org.team.read', 'org_management', 'org_teams', 'model.ab_team.read'];
  async function denied(actor: InternalApiOwnedUser, method: string, path: string, body?: object) {
    let createdCode: string | undefined;
    if (method === 'post' && path === '/api/org/teams') {
      if (!body || !('code' in body) || typeof body.code !== 'string')
        throw new Error('A unique owned code is required for creation probes');
      createdCode = body.code;
    }
    const response = await actor.context.request.fetch(path, {
      method,
      headers: { Origin: actor.origin },
      ...(body ? { data: body } : {}),
    });
    const payload = await response.json();
    // Even an authorization regression may create an owned resource. Track it before asserting.
    if (method === 'post' && path === '/api/org/teams' && typeof payload.data?.pid === 'string') {
      if (!createdCode) throw new Error('Missing owned creation code');
      owned.set(payload.data.pid, createdCode);
    }
    denials.push({ role: actor.role ?? 'unassigned', method, path, status: response.status() });
    expect(response.status(), `${actor.role ?? 'unassigned'} ${method} ${path}`).toBe(403);
  }
  try {
    const roleResponse = await owner.context.request.post('/api/roles', {
      headers: { Origin: owner.origin },
      data: {
        code: roleCode,
        name: roleCode,
        type: 'custom',
        scopeType: 'tenant',
        defaultDataScopeType: 'all',
      },
    });
    const rolePayload = await roleResponse.json();
    if (typeof rolePayload.data?.pid === 'string') rolePid = rolePayload.data.pid;
    expect(roleResponse.status()).toBe(200);
    expect(rolePayload.code).toBe('0');
    expect(rolePid).toBeTruthy();
    const treeResponse = await owner.context.request.get('/api/permissions/tree');
    expect(treeResponse.status()).toBe(200);
    const treePayload = await treeResponse.json();
    expect(treePayload.code).toBe('0');
    const permissionPids = new Map<string, string>();
    type PermissionNode = { code: string; pid: string; children?: PermissionNode[] };
    function collect(nodes: PermissionNode[]) {
      for (const node of nodes) {
        permissionPids.set(node.code, node.pid);
        collect(node.children ?? []);
      }
    }
    collect(treePayload.data);
    const grants = readPermissions.map((permission) => {
      const pid = permissionPids.get(permission);
      expect(pid, `Required permission ${permission}`).toBeTruthy();
      return pid!;
    });
    const granted = await owner.context.request.post(`/api/roles/${rolePid}/permissions`, {
      headers: { Origin: owner.origin },
      data: grants,
    });
    expect(granted.status()).toBe(200);
    expect((await granted.json()).code).toBe('0');
    viewer = await provisionInternalApiUser(browser, baseURL, roleCode);
    unassigned = await provisionInternalApiUser(browser, baseURL, null);
    const createdResponse = await owner.context.request.post('/api/org/teams', {
      headers: { Origin: owner.origin },
      data: { code, name },
    });
    const created = await createdResponse.json();
    if (typeof created.data?.pid === 'string') owned.set(created.data.pid, code);
    expect(createdResponse.status()).toBe(200);
    expect(created.code).toBe('0');
    const teamPid: string = created.data.pid;
    const memberResponse = await owner.context.request.post(`/api/org/teams/${teamPid}/members`, {
      headers: { Origin: owner.origin },
      data: { userPid: viewer.userPid },
    });
    const membership = await memberResponse.json();
    expect(memberResponse.status()).toBe(200);
    expect(membership.code).toBe('0');
    const membershipPid: string = membership.data.pid;
    for (const actor of [viewer, unassigned]) {
      const identityResponse = await actor.context.request.get('/api/auth/me');
      const identity = await identityResponse.json();
      expect(identityResponse.status()).toBe(200);
      expect(identity.code).toBe('0');
      expect(identity.data.permissions.roles.map((role: { code: string }) => role.code)).toEqual(
        actor.role ? [actor.role] : [],
      );
      const permissions: string[] = identity.data.permissions.permissionCodes;
      expect(permissions.includes('*')).toBe(false);
      expect(permissions.includes('org.team.manage')).toBe(false);
      expect(permissions.includes('org.team.read')).toBe(actor.role === roleCode);
      if (!actor.role) {
        await denied(actor, 'get', `/api/org/teams/${teamPid}`);
        await denied(actor, 'get', `/api/org/teams/${teamPid}/members`);
      }
      const suffix = actor.role ? 'reader' : 'unassigned';
      await denied(actor, 'post', '/api/org/teams', {
        code: `${code}_${suffix}`,
        name: 'Forbidden creation',
      });
      await denied(actor, 'put', `/api/org/teams/${teamPid}`, { name: 'Forbidden update' });
      await denied(actor, 'post', `/api/org/teams/${teamPid}/members`, { userPid: owner.userPid });
      await denied(actor, 'delete', `/api/org/teams/${teamPid}/members/${membershipPid}`);
      await denied(actor, 'delete', `/api/org/teams/${teamPid}`);
    }
    const read = await viewer.context.request.get(`/api/org/teams/${teamPid}`);
    const payload = await read.json();
    const shape = validate({
      method: 'get',
      path: '/api/org/teams/{pid}',
      status: read.status(),
      contentType: read.headers()['content-type'],
      payload,
    });
    reads.push({ method: 'get', path: '/api/org/teams/{pid}', status: read.status(), shape });
    expect(read.status()).toBe(200);
    expect(shape.errors).toEqual([]);
    expect(payload.data.name).toBe(name);
    const { page } = viewer;
    await page.reload({ waitUntil: 'load' });
    const menu = page.locator('nav a[href="/organization/teams"]');
    if (!(await menu.isVisible()))
      await page
        .locator('nav')
        .getByRole('button', { name: /Organization|组织管理/i })
        .click();
    await menu.click();
    await page.getByTestId('list-search-input').fill(name);
    await page.getByTestId('list-search-input').press('Enter');
    const row = page.locator('tbody tr').filter({ hasText: name });
    await expect(row).toHaveCount(1);
    await row.hover();
    await row.getByTestId('row-action-members').click();
    await expect(page).toHaveURL(new RegExp(`/organization/teams/${teamPid}$`));
    await expect(page.locator('tbody tr').filter({ hasText: viewer.username })).toHaveCount(1);
    await expect(page.getByTestId('team-members-add')).toHaveCount(0);
    await expect(page.locator('[data-testid^="team-members-remove-"]')).toHaveCount(0);
    await page.reload({ waitUntil: 'load' });
    await expect(page.locator('tbody tr').filter({ hasText: viewer.username })).toHaveCount(1);
    await expect(page.getByTestId('team-members-add')).toHaveCount(0);
    await expect(page.locator('[data-testid^="team-members-remove-"]')).toHaveCount(0);
    await capture('TEAM-05', page, info);
  } catch (error) {
    journeyError = error;
  } finally {
    const db = new pg.Client(PG_CONN);
    try {
      await db.connect();
      for (const [pid, ownedCode] of owned) {
        try {
          const rows = await db.query('SELECT deleted_flag FROM ab_team WHERE pid=$1 AND code=$2', [
            pid,
            ownedCode,
          ]);
          expect(rows.rowCount).toBe(1);
          if (!rows.rows[0].deleted_flag) {
            const deleted = await owner.context.request.delete(`/api/org/teams/${pid}`, {
              headers: { Origin: owner.origin },
            });
            expect(deleted.status()).toBe(200);
            expect((await deleted.json()).code).toBe('0');
          }
          expect(
            (
              await db.query('SELECT deleted_flag FROM ab_team WHERE pid=$1 AND code=$2', [
                pid,
                ownedCode,
              ])
            ).rows[0].deleted_flag,
          ).toBe(true);
        } catch (error) {
          cleanupErrors.push(error);
        }
      }
    } catch (error) {
      cleanupErrors.push(error);
    } finally {
      await db.end();
    }
    if (rolePid) {
      try {
        if (viewer) {
          expect(viewer.memberPid).toBeTruthy();
          const removed = await owner.context.request.delete('/api/user-roles/remove-by-pid', {
            headers: { Origin: owner.origin },
            data: { memberPid: viewer.memberPid, rolePids: [rolePid] },
          });
          expect(removed.status()).toBe(200);
          expect((await removed.json()).code).toBe('0');
        }
        const found = await owner.context.request.get(`/api/roles/${rolePid}`);
        expect(found.status()).toBe(200);
        expect((await found.json()).data.code).toBe(roleCode);
        const removed = await owner.context.request.delete(`/api/roles/${rolePid}`, {
          headers: { Origin: owner.origin },
        });
        expect(removed.status()).toBe(200);
        expect((await removed.json()).data).toBe(true);
        const verify = await owner.context.request.get('/api/roles/all');
        expect(verify.status()).toBe(200);
        expect(
          (await verify.json()).data.some(
            (role: { pid: string; code: string }) => role.pid === rolePid || role.code === roleCode,
          ),
        ).toBe(false);
      } catch (error) {
        cleanupErrors.push(error);
      }
    }
    for (const actor of [viewer, unassigned, owner]) {
      if (!actor) continue;
      try {
        await actor.dispose();
      } catch (error) {
        cleanupErrors.push(error);
      }
    }
    await info.attach('team-role-observations', {
      body: JSON.stringify({
        claim:
          'viewer browser read-only journey and API-driven write denial for viewer/unassigned; not cross-tenant or leader-rule acceptance',
        roleCode,
        readPermissions,
        reads,
        denials,
        sessionCleanup: [viewer, unassigned, owner]
          .filter((actor): actor is InternalApiOwnedUser => Boolean(actor))
          .map((actor) => ({ role: actor.role, sessions: actor.sessionCleanup })),
        journeyError: journeyError ? String(journeyError) : null,
        cleanupErrors: cleanupErrors.map(String),
      }),
      contentType: 'application/json',
    });
  }
  if (journeyError || cleanupErrors.length)
    throw new AggregateError(
      [...(journeyError ? [journeyError] : []), ...cleanupErrors],
      'Team role journey or cleanup failed',
    );
});
