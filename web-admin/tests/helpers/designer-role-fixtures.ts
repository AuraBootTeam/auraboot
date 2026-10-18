import { expect, type Browser, type BrowserContext, type Page } from '@playwright/test';
import { createCookieSessionStorage } from 'react-router';
import { DEFAULT_TEST_ACCOUNT } from './test-accounts';

// Real fixture identities and read grants; no permission-payload substitution.
const JWT_TOKEN_KEY = 'jwtToken';
const authSessionStorage = createCookieSessionStorage({
  cookie: {
    name: '__session',
    httpOnly: true,
    path: '/',
    sameSite: 'lax',
    secrets: [process.env.SESSION_SECRET || 'dev-only-secret-do-not-use-in-production'],
    secure: (process.env.NODE_ENV ?? 'development') === 'production',
  },
});

export async function grantRolePermissionByCode(
  page: Page,
  roleCode: string,
  permissionCode: string,
): Promise<void> {
  const matrixResp = await page.request.get('/api/permissions/matrix');
  expect(matrixResp.ok(), 'permission matrix fetch failed').toBeTruthy();
  const matrixBody = await matrixResp.json();
  expect(String(matrixBody.code)).toBe('0');
  let permissionId: number | undefined;
  for (const module of matrixBody?.data?.modules ?? []) {
    for (const resource of module?.resources ?? []) {
      for (const action of resource?.actions ?? []) {
        if (action?.code === permissionCode) {
          permissionId = action.permissionId;
          break;
        }
      }
      if (permissionId !== undefined) break;
    }
    if (permissionId !== undefined) break;
  }
  expect(permissionId, `permission not found in matrix: ${permissionCode}`).toBeTruthy();

  // Keyword-scoped: the roles list is paginated and full-gate runs create
  // hundreds of e2e roles, pushing fixture roles past page 1.
  const rolesResp = await page.request.get(`/api/roles?keyword=${encodeURIComponent(roleCode)}`);
  expect(rolesResp.ok(), 'roles fetch failed').toBeTruthy();
  const rolesBody = await rolesResp.json();
  expect(String(rolesBody.code)).toBe('0');
  const records = rolesBody?.data?.records ?? [];
  const role = records.find((entry: { code?: string }) => entry?.code === roleCode);
  expect(role?.pid, `role not found: ${roleCode}`).toBeTruthy();

  const grantResp = await page.request.put(`/api/permissions/matrix/${role.pid}/batch`, {
    data: [{ permissionId, granted: true }],
  });
  expect(grantResp.ok(), `grant failed for ${roleCode}: ${grantResp.status()}`).toBeTruthy();
  expect(String((await grantResp.json()).code)).toBe('0');
}

export async function createAuthenticatedRoleContext(
  browser: Browser,
  baseURL: string,
  role: 'operator' | 'viewer',
): Promise<BrowserContext> {
  const context = await browser.newContext({ baseURL, storageState: { cookies: [], origins: [] } });
  const page = await context.newPage();
  const email = `e2e-${role}@test.com`;
  const loginResp = await page.request.post(`${baseURL}/api/auth/login`, {
    data: { email, password: DEFAULT_TEST_ACCOUNT.password },
  });
  expect(loginResp.ok(), await loginResp.text()).toBe(true);
  const loginBody = await loginResp.json();
  expect(loginBody.code).toBe('0');
  const jwt = String(loginBody.data?.jwt ?? '');
  expect(jwt).toBeTruthy();
  await addSessionCookie(context, baseURL, jwt);
  await selectBusinessSpace(page, context, baseURL, loginBody.data?.tenantId);
  await page.close();
  return context;
}

async function selectBusinessSpace(
  page: Page,
  context: BrowserContext,
  baseURL: string,
  currentTenantId?: string | number,
): Promise<void> {
  const response = await page.request.get(`${baseURL}/api/tenant-selection/my-spaces`);
  expect(response.status()).toBe(200);
  const body = await response.json();
  expect(String(body.code)).toBe('0');
  const spaces = body.data.filter((space: { spaceType: string }) => space.spaceType === 'business');
  expect(spaces, 'designer fixture accounts must have one unambiguous business space').toHaveLength(1);
  const tenantId = spaces[0].tenantId;
  expect(tenantId).toBeTruthy();
  if (currentTenantId != null && String(currentTenantId) === String(tenantId)) return;
  const selected = await page.request.post(`${baseURL}/api/tenant-selection/process`, {
    data: { action: 'select', tenantId },
  });
  expect(selected.status()).toBe(200);
  const selectedBody = await selected.json();
  expect(String(selectedBody.code)).toBe('0');
  const jwt = String(selectedBody.data?.jwt ?? '');
  expect(jwt.length).toBeGreaterThan(0);
  await addSessionCookie(context, baseURL, jwt);
}

async function addSessionCookie(
  context: BrowserContext,
  baseURL: string,
  jwt: string,
): Promise<void> {
  const session = await authSessionStorage.getSession();
  session.set(JWT_TOKEN_KEY, jwt);
  const setCookie = await authSessionStorage.commitSession(session, {
    maxAge: 60 * 60 * 24 * 7,
  });
  const cookieValue = setCookie.match(/__session=([^;]+)/)?.[1] ?? '';
  expect(cookieValue).toBeTruthy();
  await context.addCookies([
    {
      name: '__session',
      value: cookieValue,
      url: baseURL,
      httpOnly: true,
      sameSite: 'Lax',
      expires: Math.floor(Date.now() / 1000) + 60 * 60 * 24 * 7,
    },
  ]);
}

export function collectPermissionCodes(permissions: Record<string, unknown>): string[] {
  const objectCodes = Array.isArray(permissions.permissions)
    ? permissions.permissions
        .map((permission: { code?: unknown }) => permission.code)
        .filter((code: unknown): code is string => typeof code === 'string' && code.length > 0)
    : [];
  return [
    ...(Array.isArray(permissions.permissionCodes) ? permissions.permissionCodes : []),
    ...objectCodes,
  ].filter((code: unknown): code is string => typeof code === 'string' && code.length > 0);
}
