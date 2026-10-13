import { expect, test, type APIRequestContext, type Page, request as pwRequest } from '@playwright/test';
import { createCookieSessionStorage } from 'react-router';
import path from 'node:path';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { BACKEND_URL, BASE_URL as WEB_BASE_URL } from '../../helpers/environments';

// GAP-02: owner / maintainer / viewer / non-member verified with four real
// accounts against the real backend. UI screenshots only adjudicate rendering;
// every permission decision is decided by real management API responses and
// terminal member state (no route mocks in this spec).
const EVIDENCE_DIR = path.join(
  process.env.AURA_EVIDENCE_ROOT || '/tmp',
  'open-platform-multi-user-20260927',
);
const RUN_TAG = process.env.OP_MULTIUSER_RUN_TAG || `${Date.now()}-${Math.random().toString(16).slice(2, 8)}`;
const PASSWORD = 'Closure2026x!';
const ACCOUNTS = {
  admin: { email: 'admin@auraboot.com', password: 'Test2026x' },
  maintainer: { email: `opm-maintainer-${RUN_TAG}@op-closure.test`, password: PASSWORD },
  viewer: { email: `opm-viewer-${RUN_TAG}@op-closure.test`, password: PASSWORD },
  nonmember: { email: `opm-nonmember-${RUN_TAG}@op-closure.test`, password: PASSWORD },
  owner2: { email: `opm-owner2-${RUN_TAG}@op-closure.test`, password: PASSWORD },
};
const APP_NAME = `开放平台多账号权限验收应用 ${RUN_TAG}`;
const APP_DESCRIPTION = 'GAP-02 四真实账号权限验证：全部由真实后端裁决';
const PUBLICATION_MUTATION = process.env.OP_PUBLICATION_AUTHORITY_MUTATION || '';
if (PUBLICATION_MUTATION && PUBLICATION_MUTATION !== 'admin-principal') throw new Error('Unsupported publication authority mutation');

const sessionStorage = createCookieSessionStorage({
  cookie: {
    name: '__session',
    httpOnly: true,
    path: '/',
    sameSite: 'lax',
    secrets: [process.env.SESSION_SECRET || 'dev-only-secret-do-not-use-in-production'],
    secure: false,
  },
});

interface Session {
  jwt: string;
}

async function apiLogin(context: APIRequestContext, email: string, password: string): Promise<Session> {
  const loginResponse = await context.post(`${BACKEND_URL}/api/auth/login`, {
    data: { email, password },
  });
  expect(loginResponse.ok(), `login failed for ${email}: ${loginResponse.status()}`).toBeTruthy();
  const login = (await loginResponse.json()).data;
  let jwt = login.jwt as string;
  if (!login.tenantId) {
    const spacesResponse = await context.get(`${BACKEND_URL}/api/tenant-selection/my-spaces`, {
      headers: { Authorization: `Bearer ${jwt}` },
    });
    expect(spacesResponse.ok()).toBeTruthy();
    const spaces = (await spacesResponse.json()).data as Array<{ tenantId: string; spaceType: string }>;
    const businessTenant = spaces.find((space) => space.spaceType === 'business');
    expect(businessTenant, `no business tenant for ${email}`).toBeTruthy();
    const selectionResponse = await context.post(`${BACKEND_URL}/api/tenant-selection/process`, {
      headers: { Authorization: `Bearer ${jwt}` },
      data: { action: 'select', tenantId: businessTenant!.tenantId },
    });
    expect(selectionResponse.ok()).toBeTruthy();
    jwt = (await selectionResponse.json()).data.jwt;
  }
  expect(jwt).toEqual(expect.any(String));
  return { jwt };
}

async function installSessionCookie(page: Page, session: Session) {
  const state = await sessionStorage.getSession();
  state.set('jwtToken', session.jwt);
  const set = await sessionStorage.commitSession(state, { maxAge: 604800 });
  const value = set.match(/__session=([^;]+)/)?.[1];
  expect(value).toBeTruthy();
  await page.context().addCookies([
    { name: '__session', value: value!, url: WEB_BASE_URL, httpOnly: true, sameSite: 'Lax' },
    { name: 'locale', value: 'zh-CN', url: WEB_BASE_URL, sameSite: 'Lax' },
  ]);
  await page.addInitScript(() => localStorage.setItem('locale', 'zh-CN'));
}

async function loginAs(page: Page, account: { email: string; password: string }) {
  const session = await apiLogin(page.request, account.email, account.password);
  await installSessionCookie(page, session);
  return session;
}

function bearer(session: Session) {
  return { Authorization: `Bearer ${session.jwt}` };
}

async function capture(page: Page, id: string) {
  await page.screenshot({ path: path.join(EVIDENCE_DIR, `${id}.png`), fullPage: true });
}

// Disabled applications from earlier runs stay visible to the owner (correct
// product behavior), so every per-application assertion must be scoped to the
// card of THIS run's application.
function appCard(page: Page) {
  return page
    .getByTestId('open-platform-application-list')
    .locator('article')
    .filter({ hasText: APP_NAME });
}

async function openAccountMenu(page: Page) {
  await expect(page.locator('header[data-hydrated]')).toHaveAttribute('data-hydrated', 'true', {
    timeout: 20_000,
  });
  const userMenu = page.getByTestId('user-menu');
  await userMenu.getByRole('button', { name: /User avatar/ }).click();
  await expect(userMenu.getByTestId('user-dropdown')).toBeVisible();
}

async function gotoOpenPlatform(page: Page) {
  await page.goto('/');
  await expect(page).not.toHaveURL(/\/login|\/setup/);
  await expect(page.locator('.animate-spin')).toHaveCount(0, { timeout: 20_000 });
  await openAccountMenu(page);
  await expect(page.getByTestId('open-platform-link')).toBeVisible();
  await page.getByTestId('open-platform-link').click();
  await expect(page.getByTestId('open-platform-page')).toBeVisible({ timeout: 20_000 });
  await expect(page.locator('.animate-spin')).toHaveCount(0, { timeout: 20_000 });
}

let appPid: string;
let installationPid: string;
let adminPid: string;
const userPids: Record<string, string> = {};
let resourceDefinition: Record<string, unknown>;
let queryDefinition: Record<string, unknown>;
let resourceCode: string;
let queryCode: string;
let modelCode: string;
let namedCode: string;
const readField = (source: string, type: string) => ({ source, type, readable: true, writable: false,
  required: false, nullable: true, operators: [], sortable: false, referenceResource: null, properties: {}, items: null });

async function verifyPublicationDenied(page: Page, session: Session, screenshotPrefix: string) {
  await expect(page.getByTestId('open-platform-publications-link')).toHaveCount(0);
  const adminContext = await pwRequest.newContext();
  try {
    const admin = await apiLogin(adminContext, ACCOUNTS.admin.email, ACCOUNTS.admin.password);
    const baseline = async () => {
      const result: unknown[] = [];
      for (const [endpoint, code] of [['publications', resourceCode], ['query-publications', queryCode]]) {
        const response = await adminContext.get(`${BACKEND_URL}/api/open-platform/${endpoint}`, { headers: bearer(admin) });
        expect(response.status()).toBe(200); const body = await response.json(); expect(String(body.code)).toBe('0');
        const row = body.data.find((entry: { code: string }) => entry.code === code);
        expect(row).toMatchObject({ code, status: 'published', activeVersion: 1, draftRevision: 1 }); result.push(row);
        for (const kind of ['openapi', 'types']) {
          const artifact = await adminContext.get(`${BACKEND_URL}/api/open-platform/${endpoint}/${code}/${kind}`, { headers: bearer(admin) });
          expect(artifact.status()).toBe(200); result.push(await artifact.text());
        }
      }
      for (const kind of ['resources', 'queries']) {
        const grants = await adminContext.get(`${BACKEND_URL}/api/open-platform/installations/${installationPid}/${kind}`, { headers: bearer(admin) });
        expect(grants.status()).toBe(200); result.push((await grants.json()).data);
      }
      return result;
    };
    const before = await baseline();
    const headers = bearer(PUBLICATION_MUTATION === 'admin-principal' ? admin : session);
    for (const [endpoint, code, definition] of [['publications', resourceCode, resourceDefinition], ['query-publications', queryCode, queryDefinition]] as const) {
      expect((await page.request.get(`${BACKEND_URL}/api/open-platform/${endpoint}`, { headers })).status()).toBe(403);
      for (const kind of ['openapi', 'types']) expect((await page.request.get(`${BACKEND_URL}/api/open-platform/${endpoint}/${code}/${kind}`, { headers })).status()).toBe(403);
      expect((await page.request.put(`${BACKEND_URL}/api/open-platform/${endpoint}/${code}/draft`, { headers, data: { expectedRevision: 1, definition: { ...definition, schemaVersion: 2 } } })).status()).toBe(403);
      for (const action of ['publish', 'withdraw']) expect((await page.request.post(`${BACKEND_URL}/api/open-platform/${endpoint}/${code}/${action}`, { headers, data: { expectedRevision: 1 } })).status()).toBe(403);
    }
    for (const source of [`publication-sources/${modelCode}`, 'query-sources', `query-sources/${namedCode}`]) {
      expect((await page.request.get(`${BACKEND_URL}/api/open-platform/${source}`, { headers })).status()).toBe(403);
    }
    for (const [kind, code, policy] of [['resources', resourceCode, { operations: ['get', 'list'], readableFields: ['pid'], writableFields: [], rowConstraints: [] }],
      ['queries', queryCode, { readableFields: ['pid'] }]] as const) {
      const endpoint = `${BACKEND_URL}/api/open-platform/installations/${installationPid}/${kind}`;
      expect((await page.request.get(endpoint, { headers })).status()).toBe(403);
      expect((await page.request.put(`${endpoint}/${code}`, { headers, data: policy })).status()).toBe(403);
      expect((await page.request.delete(`${endpoint}/${code}`, { headers })).status()).toBe(403);
    }
    expect(await baseline()).toEqual(before);
    // A typed address is the denied bypass path after the normal menu hides the entry.
    for (const [pageKey, testId, kind] of [['open_platform_publications', 'publication-editor', 'resource'], ['open_platform_queries', 'query-publication-editor', 'query']] as const) {
      await page.goto(`/p/c/${pageKey}`); const editor = page.getByTestId(testId);
      await expect(editor.getByRole('alert')).toHaveText('当前账号没有开放平台管理权限。');
      await expect(editor.locator('form')).toHaveCount(0);
      await expect(editor.getByRole('button', { name: /新建|发布|停用|保存|下载/ })).toHaveCount(0);
      await capture(page, `${screenshotPrefix}-${kind}-denied`);
    }
    expect(await baseline()).toEqual(before);
  } finally { await adminContext.dispose(); }
}

test.describe.serial('Open Platform four-real-account authority', () => {
  test.setTimeout(180_000);
  test.use({
    storageState: { cookies: [], origins: [] },
    locale: 'zh-CN',
    viewport: { width: 1440, height: 1000 },
  });

  test.beforeAll(async () => {
    await mkdir(EVIDENCE_DIR, { recursive: true });
    if (process.env.AURA_UI_ACCEPTANCE_MATRIX) {
      const matrix = JSON.parse(await readFile(process.env.AURA_UI_ACCEPTANCE_MATRIX, 'utf8'));
      await writeFile(path.join(EVIDENCE_DIR, 'acceptance-matrix.json'), JSON.stringify(matrix, null, 2));
    }
    const admin = await apiLogin(await pwRequest.newContext(), ACCOUNTS.admin.email, ACCOUNTS.admin.password);
    const ctx = await pwRequest.newContext();

    // Applications are persistent lifecycle records: DELETE disables them.
    // This run uses its own app card and never removes unrelated prior fixtures.
    for (const key of ['maintainer', 'viewer', 'nonmember', 'owner2'] as const) {
      const account = ACCOUNTS[key];
      const create = await ctx.post(`${BACKEND_URL}/api/meta/commands/execute/admin:create_member`, {
        headers: bearer(admin),
        data: {
          payload: { name: `OPM ${key} ${RUN_TAG}`, email: account.email, password: account.password },
        },
      });
      expect(create.ok(), `create_member failed for ${key}: ${create.status()} ${await create.text()}`).toBeTruthy();
      const created = (await create.json()).data.data;
      userPids[key] = created.userPid;
    }

    const createApp = await ctx.post(`${BACKEND_URL}/api/open-platform/applications`, {
      headers: bearer(admin),
      data: { name: APP_NAME, description: APP_DESCRIPTION },
    });
    expect(createApp.ok(), `create application failed: ${createApp.status()} ${await createApp.text()}`).toBeTruthy();
    const app = (await createApp.json()).data;
    appPid = app.pid;
    expect(app.accessRole).toBe('owner');

    for (const [key, role] of [
      ['maintainer', 'maintainer'],
      ['viewer', 'viewer'],
      ['owner2', 'owner'],
    ] as const) {
      const add = await ctx.put(
        `${BACKEND_URL}/api/open-platform/applications/${appPid}/members/${userPids[key]}`,
        { headers: bearer(admin), data: { role } },
      );
      expect(add.ok(), `add member ${key} failed: ${add.status()} ${await add.text()}`).toBeTruthy();
      expect((await add.json()).data.role).toBe(role);
    }

    const listed = await ctx.get(`${BACKEND_URL}/api/open-platform/applications`, { headers: bearer(admin) });
    expect(listed.ok()).toBeTruthy();
    const listedApp = ((await listed.json()).data as Array<{
      pid: string;
      members: Array<{ userPid: string; role: string; email: string }>;
      installations: Array<{ pid: string }>;
    }>).find((entry) => entry.pid === appPid);
    expect(listedApp).toBeTruthy();
    adminPid = listedApp!.members.find((member) => member.role === 'owner' && member.email === ACCOUNTS.admin.email)!
      .userPid;
    expect(adminPid).toEqual(expect.any(String));
    expect(listedApp!.members).toHaveLength(4);

    modelCode = `dop_role_${RUN_TAG.replace(/[^a-z0-9]/g, '_')}`;
    resourceCode = `roles.parts.${Date.now()}`; queryCode = `roles.query.${Date.now()}`; namedCode = `${modelCode}_query`;
    const createdModel = await ctx.post(`${BACKEND_URL}/api/meta/models`, { headers: bearer(admin), data: {
      code: modelCode, displayName: `权限验证零件 ${RUN_TAG}`, tableName: modelCode, modelType: 'entity', sourceType: 'physical',
      capabilities: { list: true, detail: true, create: true, update: true, delete: true, filter: true, sort: true, paginate: true },
      fields: [{ code: 'dop_role_name', columnName: 'dop_role_name', displayName: '零件名称', dataType: 'string', required: true, editable: true }],
    } });
    expect(createdModel.status()).toBe(200); const model = (await createdModel.json()).data;
    expect(model.tableName).toMatch(/^[a-z][a-z0-9_]+$/);
    const modelPublished = await ctx.post(`${BACKEND_URL}/api/meta/models/${model.pid}/publish`, { headers: bearer(admin), data: { impactAcknowledged: true } });
    expect(modelPublished.status()).toBe(200);
    resourceDefinition = { code: resourceCode, modelCode, schemaVersion: 1, operations: ['get', 'list'], scopes: { get: 'op.role:read', list: 'op.role:read' },
      dataPolicy: 'tenant_wide', fields: { pid: { ...readField('pid', 'pid'), nullable: false } }, maxPageSize: 20, maxConditions: 8, timeoutMs: 5000, events: [] };
    const resourceSaved = await ctx.put(`${BACKEND_URL}/api/open-platform/publications/${resourceCode}/draft`, { headers: bearer(admin), data: { expectedRevision: 0, definition: resourceDefinition } });
    expect(resourceSaved.status()).toBe(200);
    expect((await ctx.post(`${BACKEND_URL}/api/open-platform/publications/${resourceCode}/publish`, { headers: bearer(admin), data: { expectedRevision: 1 } })).status()).toBe(200);
    const named = await ctx.post(`${BACKEND_URL}/api/meta/named-queries`, { headers: bearer(admin), data: { code: namedCode, title: `权限验证查询 ${RUN_TAG}`, resourceCode: modelCode, actionCode: 'read',
      fromSql: `${model.tableName} a`, defaultOrder: [{ field: 'pid', direction: 'asc' }], fields: [{ fieldCode: 'pid', columnExpr: 'a.pid', dataType: 'string', displayName: '记录标识', sortable: true, searchable: false }] } });
    expect(named.status()).toBe(200); const namedPid = (await named.json()).data.pid;
    for (const status of ['testing', 'published']) expect((await ctx.put(`${BACKEND_URL}/api/meta/named-queries/${namedPid}/status`, { headers: bearer(admin), data: { status } })).status()).toBe(200);
    const described = await ctx.get(`${BACKEND_URL}/api/open-platform/query-sources/${namedCode}`, { headers: bearer(admin) });
    expect(described.status()).toBe(200); const source = (await described.json()).data;
    queryDefinition = { code: queryCode, queryCode: namedCode, schemaVersion: 1, sourceFingerprint: source.fingerprint, scopes: { execute: 'op.role:query' },
      sourceResources: { [modelCode]: resourceCode }, parameters: {}, fields: { pid: readField('pid', 'pid') }, maxPageSize: 20, maxRows: 100, timeoutMs: 5000, maxConcurrent: 2 };
    expect((await ctx.put(`${BACKEND_URL}/api/open-platform/query-publications/${queryCode}/draft`, { headers: bearer(admin), data: { expectedRevision: 0, definition: queryDefinition } })).status()).toBe(200);
    expect((await ctx.post(`${BACKEND_URL}/api/open-platform/query-publications/${queryCode}/publish`, { headers: bearer(admin), data: { expectedRevision: 1 } })).status()).toBe(200);

    const install = await ctx.post(`${BACKEND_URL}/api/open-platform/applications/${appPid}/installations`, {
      headers: bearer(admin),
      data: { environment: 'production', scopes: ['assets.read', 'op.role:read', 'op.role:query'], rateLimitPerMinute: 600 },
    });
    expect(install.ok(), `install failed: ${install.status()} ${await install.text()}`).toBeTruthy();
    installationPid = (await install.json()).data.pid;
    const resourceGrant = await ctx.put(`${BACKEND_URL}/api/open-platform/installations/${installationPid}/resources/${resourceCode}`, {
      headers: bearer(admin), data: { operations: ['get', 'list'], readableFields: ['pid'], writableFields: [], rowConstraints: [] },
    });
    expect(resourceGrant.status()).toBe(200);
    const queryGrant = await ctx.put(`${BACKEND_URL}/api/open-platform/installations/${installationPid}/queries/${queryCode}`, {
      headers: bearer(admin), data: { readableFields: ['pid'] },
    });
    expect(queryGrant.status()).toBe(200);
  });

  test('owner (admin) sees full management surface from the real account menu', async ({ page }) => {
    await loginAs(page, ACCOUNTS.admin);
    await gotoOpenPlatform(page);
    const card = appCard(page);
    await expect(card).toContainText(APP_NAME, { timeout: 20_000 });
    await expect(card).toContainText('所有者');
    await expect(card.getByRole('button', { name: '管理成员' })).toBeVisible();
    await expect(card.getByRole('button', { name: '添加安装' })).toBeVisible();
    await expect(card.getByRole('button', { name: '运维' })).toBeVisible();
    await capture(page, 'OPX-UI-01-owner');

    await card.getByRole('button', { name: '管理成员' }).click();
    const memberDialog = page.getByRole('dialog', { name: '管理成员' });
    await expect(memberDialog).toBeVisible();
    await capture(page, 'OPX-UI-02-owner-members');
    await memberDialog.getByRole('button', { name: '取消' }).click();
  });

  test('maintainer manages runtime but is denied member and lifecycle writes server-side', async ({ page }) => {
    const session = await loginAs(page, ACCOUNTS.maintainer);
    await gotoOpenPlatform(page);
    const card = appCard(page);
    await expect(card).toContainText(APP_NAME, { timeout: 20_000 });
    await expect(card).toContainText('维护者');
    await expect(card.getByRole('button', { name: '添加安装' })).toBeVisible();
    await expect(card.getByRole('button', { name: '新建凭据' })).toBeVisible();
    await expect(card.getByRole('button', { name: '管理成员' })).toHaveCount(0);
    await expect(card.getByRole('button', { name: '停用', exact: true })).toHaveCount(0);
    await capture(page, 'OPX-UI-03-maintainer');

    const ctx = page.request;
    const denyAdd = await ctx.put(
      `${BACKEND_URL}/api/open-platform/applications/${appPid}/members/${userPids.nonmember}`,
      { headers: bearer(session), data: { role: 'viewer' } },
    );
    expect(denyAdd.status(), 'maintainer must not add members').toBe(403);
    const denyDelete = await ctx.delete(
      `${BACKEND_URL}/api/open-platform/applications/${appPid}/members/${userPids.viewer}`,
      { headers: bearer(session) },
    );
    expect(denyDelete.status(), 'maintainer must not remove members').toBe(403);
    const denyDisable = await ctx.delete(`${BACKEND_URL}/api/open-platform/applications/${appPid}`, {
      headers: bearer(session),
    });
    expect(denyDisable.status(), 'maintainer must not disable the application').toBe(403);
    await verifyPublicationDenied(page, session, 'OPX-UI-03-maintainer');
  });

  test('viewer is read-only and denied credentials and writes server-side', async ({ page }) => {
    const session = await loginAs(page, ACCOUNTS.viewer);
    await gotoOpenPlatform(page);
    const card = appCard(page);
    await expect(card).toContainText(APP_NAME, { timeout: 20_000 });
    await expect(card).toContainText('查看者');
    await expect(card.getByRole('button', { name: '运维' })).toBeVisible();
    await expect(card.getByRole('button', { name: '管理成员' })).toHaveCount(0);
    await expect(card.getByRole('button', { name: '添加安装' })).toHaveCount(0);
    await expect(card.getByRole('button', { name: '新建凭据' })).toHaveCount(0);
    await capture(page, 'OPX-UI-04-viewer');

    const ctx = page.request;
    const denyListCredentials = await ctx.get(
      `${BACKEND_URL}/api/open-platform/installations/${installationPid}/credentials`,
      { headers: bearer(session) },
    );
    expect(denyListCredentials.status(), 'viewer must not list credentials').toBe(403);
    const denyCreateCredential = await ctx.post(
      `${BACKEND_URL}/api/open-platform/installations/${installationPid}/credentials`,
      { headers: bearer(session) },
    );
    expect(denyCreateCredential.status(), 'viewer must not create credentials').toBe(403);
    const denyWrite = await ctx.put(
      `${BACKEND_URL}/api/open-platform/applications/${appPid}/members/${userPids.nonmember}`,
      { headers: bearer(session), data: { role: 'viewer' } },
    );
    expect(denyWrite.status(), 'viewer must not manage members').toBe(403);
    await verifyPublicationDenied(page, session, 'OPX-UI-04-viewer');
  });

  test('non-member sees an empty console and is denied all app access server-side', async ({ page }) => {
    const session = await loginAs(page, ACCOUNTS.nonmember);
    await gotoOpenPlatform(page);
    await expect(page.getByTestId('open-platform-empty')).toContainText('暂无共享给你的开放平台应用', {
      timeout: 20_000,
    });
    await expect(page.getByTestId('open-platform-create-app')).toHaveCount(0);
    await capture(page, 'OPX-UI-05-nonmember');

    const ctx = page.request;
    const apps = await ctx.get(`${BACKEND_URL}/api/open-platform/applications`, { headers: bearer(session) });
    expect(apps.ok()).toBeTruthy();
    expect((await apps.json()).data ?? []).toEqual([]);
    const denyWrite = await ctx.put(
      `${BACKEND_URL}/api/open-platform/applications/${appPid}/members/${userPids.nonmember}`,
      { headers: bearer(session), data: { role: 'owner' } },
    );
    expect(denyWrite.status(), 'non-member must not mutate the application').toBe(403);
    await verifyPublicationDenied(page, session, 'OPX-UI-05-nonmember');
  });

  test('missing and disabled applications return 404 for reads and locked member writes', async () => {
    const ctx = await pwRequest.newContext();
    const admin = await apiLogin(ctx, ACCOUNTS.admin.email, ACCOUNTS.admin.password);
    const missing = await ctx.post(`${BACKEND_URL}/api/open-platform/applications/missing-${RUN_TAG}/installations`, {
      headers: bearer(admin),
      data: { environment: 'staging', scopes: ['openapi.profile.read'], rateLimitPerMinute: 600 },
    });
    expect(missing.status()).toBe(404);
    expect((await missing.json()).code).toBe('404');
    const create = await ctx.post(`${BACKEND_URL}/api/open-platform/applications`, {
      headers: bearer(admin), data: { name: `停用边界验收 ${RUN_TAG}`, description: 'HTTP 404 lifecycle contract' },
    });
    expect(create.ok()).toBeTruthy();
    const pid = (await create.json()).data.pid;
    const disable = await ctx.delete(`${BACKEND_URL}/api/open-platform/applications/${pid}`, { headers: bearer(admin) });
    expect(disable.ok()).toBeTruthy();
    const listed = await ctx.get(`${BACKEND_URL}/api/open-platform/applications`, { headers: bearer(admin) });
    expect(listed.ok()).toBeTruthy();
    expect((await listed.json()).data).toEqual(expect.arrayContaining([expect.objectContaining({ pid, status: 'disabled' })]));
    const repeated = await ctx.delete(`${BACKEND_URL}/api/open-platform/applications/${pid}`, { headers: bearer(admin) });
    expect(repeated.status()).toBe(404);
    expect((await repeated.json()).code).toBe('404');
    const memberWrite = await ctx.put(`${BACKEND_URL}/api/open-platform/applications/${pid}/members/${userPids.viewer}`, {
      headers: bearer(admin), data: { role: 'viewer' },
    });
    expect(memberWrite.status()).toBe(404);
    expect((await memberWrite.json()).code).toBe('404');
    await ctx.dispose();
  });

  test('sole owner cannot self-remove; concurrent owner deletes keep exactly one owner', async () => {
    const admin = await apiLogin(await pwRequest.newContext(), ACCOUNTS.admin.email, ACCOUNTS.admin.password);
    const owner2 = await apiLogin(await pwRequest.newContext(), ACCOUNTS.owner2.email, ACCOUNTS.owner2.password);
    const ctxA = await pwRequest.newContext();
    const ctxB = await pwRequest.newContext();

    // owner2 leaves: admin becomes the sole owner, and a real request to remove
    // the last owner must be rejected.
    const leave = await ctxB.delete(
      `${BACKEND_URL}/api/open-platform/applications/${appPid}/members/${userPids.owner2}`,
      { headers: bearer(owner2) },
    );
    expect(leave.ok(), `owner2 self-removal failed: ${leave.status()} ${await leave.text()}`).toBeTruthy();
    const soleRemove = await ctxA.delete(
      `${BACKEND_URL}/api/open-platform/applications/${appPid}/members/${adminPid}`,
      { headers: bearer(admin) },
    );
    expect([400, 403, 409]).toContain(soleRemove.status());

    // Two owners again; concurrent deletions in both directions must keep
    // exactly one owner (row-lock serialization), not zero, not two.
    const reAdd = await ctxA.put(
      `${BACKEND_URL}/api/open-platform/applications/${appPid}/members/${userPids.owner2}`,
      { headers: bearer(admin), data: { role: 'owner' } },
    );
    expect(reAdd.ok(), `re-add owner2 failed: ${reAdd.status()} ${await reAdd.text()}`).toBeTruthy();

    const [aDeletesOwner2, bDeletesAdmin] = await Promise.all([
      ctxA.delete(`${BACKEND_URL}/api/open-platform/applications/${appPid}/members/${userPids.owner2}`, {
        headers: bearer(admin),
      }),
      ctxB.delete(`${BACKEND_URL}/api/open-platform/applications/${appPid}/members/${adminPid}`, {
        headers: bearer(owner2),
      }),
    ]);
    const outcomes = [aDeletesOwner2.status(), bDeletesAdmin.status()];
    expect(outcomes).toContain(200);

    const finalList = await ctxA.get(`${BACKEND_URL}/api/open-platform/applications`, {
      headers: bearer(admin),
    });
    expect(finalList.ok()).toBeTruthy();
    const finalApp = ((await finalList.json()).data as Array<{
      pid: string;
      members: Array<{ userPid: string; role: string }>;
    }>).find((entry) => entry.pid === appPid);
    expect(finalApp).toBeTruthy();
    const owners = finalApp!.members.filter((member) => member.role === 'owner');
    expect(owners).toHaveLength(1);
    expect([adminPid, userPids.owner2]).toContain(owners[0]!.userPid);
    expect(finalApp!.members).toHaveLength(3);
  });
});
