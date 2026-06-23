import { test, expect, type Browser, type Page } from '@playwright/test';
import { createCookieSessionStorage } from 'react-router';
import { navigateToOrderViaSidebar } from '../saved-view/helpers';
import { uniqueId } from '../helpers';

const PASSWORD = 'Test2026x';
const MODEL_CODE = 'e2et_order';
const DEFAULT_BASE_URL = 'http://127.0.0.1:5173';
const JWT_TOKEN_KEY = 'jwtToken';

const authSessionStorage = createCookieSessionStorage({
  cookie: {
    name: '__session',
    httpOnly: true,
    path: '/',
    sameSite: 'lax',
    secrets: [process.env.SESSION_SECRET || 'dev-only-secret-do-not-use-in-production'],
    secure: process.env.NODE_ENV === 'production',
  },
});

type PermissionRecord = {
  id: number;
  pid?: string;
  code: string;
  resourceCode?: string;
  action?: string;
};

type TestUser = {
  email: string;
  displayName: string;
  password: string;
};

type DynamicRecord = {
  pid: string;
  title: string;
};

test.describe('Permission data scope runtime', () => {
  test.setTimeout(60_000);

  test('self-scoped role sees own Dynamic records while tenant admin sees all @smoke', async ({
    page,
    browser,
    baseURL,
  }) => {
    const resolvedBaseURL = baseURL ?? DEFAULT_BASE_URL;
    const uid = uniqueId('rbac_scope');
    const roleCode = `e2e_scope_${uid.replace(/[^a-zA-Z0-9_]/g, '_')}`.slice(0, 60);
    const owner: TestUser = {
      email: `${roleCode}@e2e.local`,
      displayName: `RBAC Scope ${uid}`,
      password: PASSWORD,
    };

    const rolePid = await createSelfScopedRole(page, roleCode);
    await provisionUser(page, owner, roleCode);

    const ownerContext = await newAuthenticatedContext(browser, resolvedBaseURL, owner);
    const ownerPage = await ownerContext.newPage();

    try {
      const ownerRecord = await createOrder(ownerPage, `RBAC Owner ${uid}`);
      const adminRecord = await createOrder(page, `RBAC Admin ${uid}`);

      await expectScopeMaterialized(page, rolePid);

      const ownerOwnRecords = await listOrdersByKeyword(ownerPage, ownerRecord.title);
      expect(
        ownerOwnRecords.some((record) => record.pid === ownerRecord.pid),
        'self-scoped user should see own record in Dynamic list API',
      ).toBe(true);

      const ownerOtherRecords = await listOrdersByKeyword(ownerPage, adminRecord.title);
      expect(
        ownerOtherRecords.some((record) => record.pid === adminRecord.pid),
        'self-scoped user must not see admin-created record in Dynamic list API',
      ).toBe(false);

      const deniedDetail = await ownerPage.request.get(`/api/dynamic/${MODEL_CODE}/${adminRecord.pid}`);
      const deniedBody = await deniedDetail.json().catch(() => ({}));
      expect(
        !deniedDetail.ok() || !isSuccessBody(deniedBody) || !deniedBody.data,
        'self-scoped user must not access admin-created record by detail API',
      ).toBe(true);

      await navigateToOrderViaSidebar(ownerPage);
      await searchOrderList(ownerPage, ownerRecord.title);
      await expect(ownerPage.getByText(ownerRecord.title).first()).toBeVisible({ timeout: 10_000 });

      await searchOrderList(ownerPage, adminRecord.title);
      await expect(ownerPage.getByText(adminRecord.title).first()).toHaveCount(0);

      await navigateToOrderViaSidebar(page);
      await searchOrderList(page, ownerRecord.title);
      await expect(page.getByText(ownerRecord.title).first()).toBeVisible({ timeout: 10_000 });

      await searchOrderList(page, adminRecord.title);
      await expect(page.getByText(adminRecord.title).first()).toBeVisible({ timeout: 10_000 });

      await expectSuccessfulDetail(page, ownerRecord);
      await expectSuccessfulDetail(page, adminRecord);
    } finally {
      await ownerContext.close();
    }
  });
});

async function createSelfScopedRole(page: Page, roleCode: string): Promise<string> {
  const createResp = await page.request.post('/api/roles', {
    data: {
      code: roleCode,
      name: `Self Scope ${roleCode.slice(-16)}`,
      description: 'E2E role for Dynamic data-scope runtime verification',
      type: 'custom',
      status: 'active',
      scopeType: 'tenant',
      defaultDataScopeType: 'self',
    },
  });
  await expectOk(createResp, 'create self-scoped role');
  const created = await createResp.json();
  const rolePid = String(created?.data?.pid ?? '');
  expect(rolePid, 'created role pid').toBeTruthy();

  const permissions = await requiredPermissions(page);
  const batchResp = await page.request.put(`/api/permissions/matrix/${rolePid}/batch`, {
    data: permissions.map((permission) => ({
      permissionId: permission.id,
      granted: true,
    })),
  });
  await expectOk(batchResp, 'grant role permissions');

  const scopeResp = await page.request.put(`/api/permissions/matrix/${rolePid}/default-scope`, {
    data: { scopeType: 'self' },
  });
  await expectOk(scopeResp, 'set role default scope');

  return rolePid;
}

async function requiredPermissions(page: Page): Promise<PermissionRecord[]> {
  return [
    await findPermission(page, ['model.e2et_order.read', 'DYNAMIC.e2et_order.read']),
    await findPermission(page, ['model.e2et_order.create', 'DYNAMIC.e2et_order.create']),
    await findPermission(page, ['e2et.order.manage']),
    await findPermission(page, ['e2et.order.read']),
  ];
}

async function findPermission(page: Page, codes: string[]): Promise<PermissionRecord> {
  const candidates = [
    `/api/permissions/model/${MODEL_CODE}`,
    '/api/permissions/resource-type/DYNAMIC',
    '/api/permissions/resource-type/data',
    '/api/permissions/resource-type/operation',
  ];

  for (const endpoint of candidates) {
    const resp = await page.request.get(endpoint);
    if (!resp.ok()) continue;
    const body = await resp.json().catch(() => ({}));
    const permissions = normalizePermissionList(body?.data);
    const found = permissions.find((permission) => codes.includes(permission.code));
    if (found) return found;
  }

  const treeResp = await page.request.get('/api/permissions/tree');
  if (treeResp.ok()) {
    const body = await treeResp.json().catch(() => ({}));
    const flattened = flattenPermissionTree(Array.isArray(body?.data) ? body.data : []);
    const found = flattened.find((permission) => codes.includes(permission.code));
    if (found) return found;
  }

  throw new Error(`Permission not found for any of: ${codes.join(', ')}`);
}

function normalizePermissionList(value: unknown): PermissionRecord[] {
  if (!Array.isArray(value)) return [];
  return value
    .map((item: any) => ({
      id: Number(item.id ?? item.permissionId),
      pid: item.pid ?? item.permissionPid,
      code: String(item.code ?? ''),
      resourceCode: item.resourceCode,
      action: item.action,
    }))
    .filter((item) => Number.isFinite(item.id) && item.code);
}

function flattenPermissionTree(nodes: any[]): PermissionRecord[] {
  const result: PermissionRecord[] = [];
  for (const node of nodes) {
    if (node?.code && Number.isFinite(Number(node.id)) && Number(node.id) > 0) {
      result.push({
        id: Number(node.id),
        pid: node.pid,
        code: String(node.code),
        resourceCode: node.resourceCode,
        action: node.action,
      });
    }
    if (Array.isArray(node?.children)) {
      result.push(...flattenPermissionTree(node.children));
    }
  }
  return result;
}

async function provisionUser(page: Page, user: TestUser, roleCode: string): Promise<void> {
  const resp = await page.request.post('/api/admin/users', {
    data: {
      email: user.email,
      displayName: user.displayName,
      initialPassword: user.password,
      roleCodes: [roleCode],
      sendInviteEmail: false,
    },
  });
  await expectOk(resp, `provision user ${user.email}`);
}

async function newAuthenticatedContext(
  browser: Browser,
  baseURL: string,
  user: TestUser,
) {
  const loginContext = await browser.newContext({
    baseURL,
    storageState: { cookies: [], origins: [] },
  });
  const loginPage = await loginContext.newPage();
  let jwt: string;
  try {
    jwt = await loginAndResolveJwt(loginPage, baseURL, user);
  } finally {
    await loginContext.close();
  }

  const cookieValue = await createSessionCookieValue(jwt);
  expect(cookieValue, `session cookie for ${user.email}`).toBeTruthy();

  const context = await browser.newContext({ baseURL });
  const cookieBase = {
    name: '__session',
    value: cookieValue!,
    httpOnly: true,
    sameSite: 'Lax' as const,
    expires: Math.floor(Date.now() / 1000) + 604800,
  };
  await context.addCookies([{ ...cookieBase, url: baseURL }]);
  return context;
}

async function loginAndResolveJwt(page: Page, baseURL: string, user: TestUser): Promise<string> {
  const loginResp = await page.request.post(`${baseURL}/api/auth/login`, {
    data: { email: user.email, password: user.password },
    headers: { 'Content-Type': 'application/json' },
  });
  await expectOk(loginResp, `login ${user.email}`);
  const loginBody = await loginResp.json();
  const loginJwt = loginBody?.data?.jwt;
  expect(typeof loginJwt === 'string' && loginJwt.length > 0, JSON.stringify(loginBody)).toBe(true);
  if (loginBody?.data?.tenantId) return loginJwt;

  const spacesResp = await page.request.get(`${baseURL}/api/tenant-selection/my-spaces`, {
    headers: {
      Authorization: `Bearer ${loginJwt}`,
    },
  });
  if (!spacesResp.ok()) return loginJwt;
  const spacesBody = await spacesResp.json().catch(() => ({}));
  const spaces = Array.isArray(spacesBody?.data) ? spacesBody.data : [];
  const selectedSpace =
    spaces.find((space) => String(space?.spaceType ?? space?.type ?? '').toLowerCase() === 'business') ??
    spaces.find((space) => space?.tenantId ?? space?.id);
  const tenantId = selectedSpace?.tenantId ?? selectedSpace?.id;
  if (!tenantId) return loginJwt;

  const selectResp = await page.request.post(`${baseURL}/api/tenant-selection/process`, {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${loginJwt}`,
    },
    data: { action: 'select', tenantId },
  });
  if (!selectResp.ok()) return loginJwt;
  const selectBody = await selectResp.json().catch(() => ({}));
  return String(selectBody?.data?.jwt || loginJwt);
}

async function createSessionCookieValue(jwt: string): Promise<string | null> {
  const session = await authSessionStorage.getSession();
  session.set(JWT_TOKEN_KEY, jwt);
  const setCookie = await authSessionStorage.commitSession(session, {
    maxAge: 60 * 60 * 24 * 7,
  });
  const match = setCookie.match(/__session=([^;]+)/);
  return match?.[1] ?? null;
}

async function createOrder(page: Page, title: string): Promise<DynamicRecord> {
  const resp = await page.request.post(`/api/dynamic/${MODEL_CODE}`, {
    data: {
      e2et_order_title: title,
      e2et_order_type: 'normal',
      e2et_order_status: 'draft',
      e2et_order_customer: `Customer ${title}`,
      e2et_order_urgent: false,
      e2et_order_amount: 100,
      e2et_order_qty: 1,
      e2et_order_date: new Date().toISOString().slice(0, 10),
    },
  });
  await expectOk(resp, `create order ${title}`);
  const body = await resp.json();
  expect(isSuccessBody(body), JSON.stringify(body)).toBe(true);
  const pid = String(body?.data?.pid ?? '');
  expect(pid, `created record pid for ${title}`).toBeTruthy();
  return { pid, title };
}

async function expectScopeMaterialized(page: Page, rolePid: string): Promise<void> {
  const resp = await page.request.get(`/api/permissions/matrix/${rolePid}`);
  await expectOk(resp, 'fetch role permission matrix');
  const body = await resp.json();
  const actions = flattenMatrixActions(body?.data);
  const read = actions.find((action) => action.code === 'model.e2et_order.read');
  expect(read, 'model.e2et_order.read must be present in role matrix').toBeTruthy();
  expect(read?.granted, 'model.e2et_order.read must be granted').toBe(true);
  expect(read?.scopeType, 'model.e2et_order.read must materialize self scope').toBe('self');
}

function flattenMatrixActions(matrix: any): Array<{ code: string; granted: boolean; scopeType?: string }> {
  const actions: Array<{ code: string; granted: boolean; scopeType?: string }> = [];
  for (const module of matrix?.modules ?? []) {
    for (const resource of module?.resources ?? []) {
      for (const action of resource?.actions ?? []) {
        actions.push({
          code: String(action?.code ?? ''),
          granted: Boolean(action?.granted),
          scopeType: action?.scopeType,
        });
      }
    }
  }
  return actions;
}

async function listOrdersByKeyword(page: Page, keyword: string): Promise<Array<Record<string, any>>> {
  const resp = await page.request.get(
    `/api/dynamic/${MODEL_CODE}/list?pageNum=1&pageSize=20&keyword=${encodeURIComponent(keyword)}`,
  );
  await expectOk(resp, `list orders by keyword ${keyword}`);
  const body = await resp.json();
  expect(isSuccessBody(body), JSON.stringify(body)).toBe(true);
  return body?.data?.records ?? body?.data?.list ?? [];
}

async function searchOrderList(page: Page, keyword: string): Promise<void> {
  const searchInput = page
    .locator(
      [
        '[data-testid="list-search-input"]',
        '[data-testid="table-search-input"]',
        '[data-testid="search-input"]:not([data-testid="global-search-input"])',
        'input[placeholder*="查询"]',
        'input[placeholder*="搜索"]:not([placeholder*="页面"]):not([placeholder*="记录"]):not([placeholder*="文档"])',
        'input[placeholder*="Search"]:not([placeholder*="page" i]):not([placeholder*="record" i]):not([placeholder*="document" i])',
      ].join(', '),
    )
    .first();
  await expect(searchInput).toBeVisible({ timeout: 10_000 });

  const responsePromise = page
    .waitForResponse(
      (resp) => resp.url().includes(`/api/dynamic/${MODEL_CODE}/list`) && resp.status() === 200,
      { timeout: 10_000 },
    )
    .catch(() => null);

  await searchInput.fill(keyword);
  await searchInput.press('Enter');
  let response = await responsePromise;

  if (!response) {
    const searchButton = page
      .locator(
        '[data-testid="search-button"], [data-testid="table-search-button"], [data-testid="filter-search"], button:has-text("搜索"), button:has-text("Search")',
      )
      .first();
    await expect(searchButton).toBeVisible({ timeout: 5_000 });
    const clickResponse = page
      .waitForResponse(
        (resp) => resp.url().includes(`/api/dynamic/${MODEL_CODE}/list`) && resp.status() === 200,
        { timeout: 10_000 },
      )
      .catch(() => null);
    await searchButton.click();
    response = await clickResponse;
  }

  expect(response, `list response after searching "${keyword}"`).toBeTruthy();
  await expect(page.locator('table, [role="table"], [data-testid="dynamic-list"]').first()).toBeVisible({
    timeout: 10_000,
  });
}

async function expectSuccessfulDetail(page: Page, record: DynamicRecord): Promise<void> {
  const resp = await page.request.get(`/api/dynamic/${MODEL_CODE}/${record.pid}`);
  await expectOk(resp, `fetch detail ${record.pid}`);
  const body = await resp.json();
  expect(isSuccessBody(body), JSON.stringify(body)).toBe(true);
  expect(body?.data?.e2et_order_title).toBe(record.title);
}

function isSuccessBody(body: any): boolean {
  return body?.success === true || String(body?.code) === '0';
}

async function expectOk(
  resp: { ok(): boolean; status(): number; text(): Promise<string> },
  context: string,
): Promise<void> {
  if (!resp.ok()) {
    throw new Error(`${context}: HTTP ${resp.status()} ${await resp.text().catch(() => '')}`);
  }
}
