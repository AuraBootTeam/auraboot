import { test, expect, type APIRequestContext, type APIResponse, type Page } from '@playwright/test';

type ApiEnvelope<T> = {
  code?: number | string;
  success?: boolean;
  message?: string;
  data?: T;
};

type PermissionRecord = {
  pid: string;
  code: string;
};

type RoleRecord = {
  pid: string;
  code: string;
};

type DecisionVersion = {
  pid: string;
  version?: number;
  status?: string;
};

type TestUser = {
  email: string;
  password: string;
};

const BACKEND_URL = process.env.BACKEND_URL ?? 'http://127.0.0.1:6482';
const ADMIN_EMAIL = 'admin@auraboot.com';
const ADMIN_PASSWORD = 'Test2026x';
const TEST_PASSWORD = 'Test2026x';
const TS = Date.now();
const HIGH_VALUE_AST = {
  type: 'compare',
  left: { type: 'path', scope: 'record', path: 'data.amount', dataType: 'decimal' },
  operator: 'GT',
  right: { type: 'literal', value: 10000, dataType: 'decimal' },
};

test.use({ storageState: { cookies: [], origins: [] } });

function headers(token: string) {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
}

async function parseEnvelope<T>(response: { ok(): boolean; status(): number; text(): Promise<string>; url(): string }) {
  const raw = await response.text();
  let body: ApiEnvelope<T>;
  try {
    body = JSON.parse(raw) as ApiEnvelope<T>;
  } catch {
    throw new Error(`Non-JSON response ${response.status()} ${response.url()}: ${raw}`);
  }
  expect(response.ok(), `HTTP ${response.status()} ${response.url()}: ${raw}`).toBe(true);
  if (String(body.code ?? '0') !== '0' || body.success === false) {
    throw new Error(`API returned non-success envelope: ${raw}`);
  }
  return body.data as T;
}

async function backendPost<T>(
  request: APIRequestContext,
  token: string,
  path: string,
  data?: unknown,
): Promise<T> {
  return parseEnvelope<T>(
    await request.post(`${BACKEND_URL}${path}`, {
      headers: headers(token),
      data,
    }),
  );
}

async function backendGet<T>(
  request: APIRequestContext,
  token: string,
  path: string,
): Promise<T> {
  return parseEnvelope<T>(
    await request.get(`${BACKEND_URL}${path}`, {
      headers: headers(token),
    }),
  );
}

async function loginBackend(request: APIRequestContext, email: string, password: string) {
  const response = await request.post(`${BACKEND_URL}/api/auth/login`, {
    headers: { 'Content-Type': 'application/json' },
    data: { email, password },
  });
  const data = await parseEnvelope<{ jwt: string }>(response);
  expect(data.jwt, `login must return JWT for ${email}`).toBeTruthy();
  return data.jwt;
}

async function loginViaBff(page: Page, baseURL: string, email: string, password: string) {
  const response = await page.request.post(`${baseURL}/login`, {
    form: {
      channelCode: 'email_password',
      email,
      password,
      remember: 'on',
      redirectTo: '/',
    },
    maxRedirects: 0,
  });
  expect(response.status(), `BFF login failed for ${email}: ${response.status()}`).toBe(302);
  const setCookie = response.headers()['set-cookie'] ?? '';
  const match = setCookie.match(/__session=([^;]+)/);
  if (!match?.[1]) {
    throw new Error(`BFF login did not return __session for ${email}`);
  }
  const cookieBase = {
    name: '__session',
    value: match[1],
    path: '/',
    httpOnly: true,
    sameSite: 'Lax' as const,
    expires: Math.floor(Date.now() / 1000) + 60 * 60,
  };
  await page.context().addCookies([
    { ...cookieBase, domain: new URL(baseURL).hostname },
    { ...cookieBase, domain: 'localhost' },
    { ...cookieBase, domain: '127.0.0.1' },
  ]);
}

async function loadDecisionPermissions(request: APIRequestContext, adminToken: string) {
  const permissions = await backendGet<PermissionRecord[]>(
    request,
    adminToken,
    '/api/permissions/resource-type/function',
  );
  const byCode = new Map(permissions.map((permission) => [permission.code, permission]));
  const required = [
    'decision.definition.read',
    'decision.definition.publish',
    'decision.definition.approve',
    'page.page.read',
  ];
  for (const code of required) {
    expect(byCode.get(code)?.pid, `permission ${code} must exist`).toBeTruthy();
  }
  return {
    read: byCode.get('decision.definition.read')!,
    publish: byCode.get('decision.definition.publish')!,
    approve: byCode.get('decision.definition.approve')!,
    pageRead: byCode.get('page.page.read')!,
  };
}

async function ensureModelReadPermission(
  request: APIRequestContext,
  adminToken: string,
  code: string,
  resourceCode: string,
) {
  const permissions = await backendGet<PermissionRecord[]>(
    request,
    adminToken,
    '/api/permissions/resource-type/model',
  );
  const existing = permissions.find((permission) => permission.code === code);
  if (existing) return existing;
  return backendPost<PermissionRecord>(request, adminToken, '/api/permissions', {
    code,
    name: `${resourceCode} read`,
    description: `E2E support permission for ${resourceCode} DSL read access`,
    resourceType: 'model',
    resourceCode,
    action: 'read',
    source: 'e2e',
    sourceRef: 'decisionops-permission-negative',
  });
}

async function createRoleWithPermissions(
  request: APIRequestContext,
  adminToken: string,
  args: { code: string; name: string; permissionPids: string[] },
) {
  const role = await backendPost<RoleRecord>(request, adminToken, '/api/roles', {
    code: args.code,
    name: args.name,
    description: `DecisionOps permission negative E2E ${TS}`,
    type: 'custom',
  });
  await backendPost<boolean>(request, adminToken, `/api/roles/${role.pid}/permissions`, args.permissionPids);
  return role;
}

async function createUserForRole(
  request: APIRequestContext,
  adminToken: string,
  args: { email: string; roleCode: string },
): Promise<TestUser> {
  await backendPost<unknown>(request, adminToken, '/api/admin/users', {
    email: args.email,
    displayName: args.email.split('@')[0],
    initialPassword: TEST_PASSWORD,
    roleCodes: [args.roleCode],
    sendInviteEmail: false,
  });
  return { email: args.email, password: TEST_PASSWORD };
}

async function seedDecisionVersions(request: APIRequestContext, adminToken: string, decisionCode: string) {
  await backendPost<unknown>(request, adminToken, '/api/decision/definitions', {
    decisionCode,
    decisionName: `Permission Negative ${decisionCode}`,
    description: 'DecisionOps permission negative E2E fixture',
    scopeType: 'GOVERNANCE',
    ownerModule: 'decision',
    enabled: true,
  });

  const validated = await backendPost<DecisionVersion>(
    request,
    adminToken,
    `/api/decision/definitions/${encodeURIComponent(decisionCode)}/versions`,
    {
      kind: 'SIMPLE_CONDITION',
      runtimeAdapter: 'AST_EVALUATOR',
      versionTag: `validated-${TS}`,
      contentJson: HIGH_VALUE_AST,
    },
  );
  await backendPost<unknown>(request, adminToken, `/api/decision/versions/${validated.pid}/validate`);

  const pending = await backendPost<DecisionVersion>(
    request,
    adminToken,
    `/api/decision/definitions/${encodeURIComponent(decisionCode)}/versions`,
    {
      kind: 'SIMPLE_CONDITION',
      runtimeAdapter: 'AST_EVALUATOR',
      versionTag: `pending-${TS}`,
      contentJson: HIGH_VALUE_AST,
    },
  );
  await backendPost<unknown>(request, adminToken, `/api/decision/versions/${pending.pid}/validate`);
  await backendPost<DecisionVersion>(
    request,
    adminToken,
    `/api/decision/versions/${pending.pid}/submit-for-approval`,
  );

  return { validated, pending };
}

async function openDecisionDetail(page: Page, decisionCode: string) {
  await page.goto(`/p/decisionops_definitions/view/${encodeURIComponent(decisionCode)}`, {
    waitUntil: 'domcontentloaded',
  });
  await expect(page.getByTestId('decision-definition-actions-block')).toBeVisible({
    timeout: 15_000,
  });
  await expect(page.getByTestId('dda-version-actions')).toBeVisible({ timeout: 15_000 });
}

async function expectForbidden(responsePromise: Promise<APIResponse>) {
  const response = await responsePromise;
  const text = await response.text();
  expect(response.status(), text).toBe(403);
  expect(text).toContain('decision.definition');
  return text;
}

test('DecisionOps definition lifecycle actions show role-level permission negative states @golden', async ({
  page,
  request,
  baseURL,
}, testInfo) => {
  test.setTimeout(120_000);
  const resolvedBaseURL = baseURL ?? 'http://127.0.0.1:5212';
  const adminToken = await loginBackend(request, ADMIN_EMAIL, ADMIN_PASSWORD);
  const decisionPermissions = await loadDecisionPermissions(request, adminToken);
  const listPageReadPermission = await ensureModelReadPermission(
    request,
    adminToken,
    'model.decisionops_definitions.read',
    'decisionops_definitions',
  );
  const detailPageReadPermission = await ensureModelReadPermission(
    request,
    adminToken,
    'model.decisionops_definitions_detail.read',
    'decisionops_definitions_detail',
  );
  const dslReadPermissionPids = [
    decisionPermissions.read.pid,
    decisionPermissions.pageRead.pid,
    listPageReadPermission.pid,
    detailPageReadPermission.pid,
  ];

  const suffix = `perm_${TS}`;
  const decisionCode = `codex_permission_negative_${suffix}`;
  const readRole = await createRoleWithPermissions(request, adminToken, {
    code: `codex_decision_read_${suffix}`,
    name: `Codex Decision Read ${suffix}`,
    permissionPids: dslReadPermissionPids,
  });
  const readOnlyUser = await createUserForRole(request, adminToken, {
    email: `codex-decision-read-${suffix}@example.com`,
    roleCode: readRole.code,
  });
  const { validated, pending } = await seedDecisionVersions(request, adminToken, decisionCode);

  await loginViaBff(page, resolvedBaseURL, ADMIN_EMAIL, ADMIN_PASSWORD);
  await page.goto('/enterprise/permissions', { waitUntil: 'domcontentloaded' });
  await expect(page.getByTestId('permission-page')).toBeVisible({ timeout: 15_000 });
  await page.getByTestId('role-search-input').fill(readRole.code);
  await expect(page.getByTestId(`role-item-${readRole.code}`)).toBeVisible({ timeout: 10_000 });
  await page.getByTestId(`role-item-${readRole.code}`).click();
  await expect(page.getByTestId('permission-matrix')).toBeVisible({ timeout: 10_000 });
  await page.screenshot({
    path: testInfo.outputPath('decisionops-permission-platform-reuse.png'),
    fullPage: true,
  });

  await page.context().clearCookies();
  await loginViaBff(page, resolvedBaseURL, readOnlyUser.email, readOnlyUser.password);
  await openDecisionDetail(page, decisionCode);

  const publish = page.getByTestId(`dda-publish-${validated.pid}`);
  const submit = page.getByTestId(`dda-submit-${validated.pid}`);
  const approve = page.getByTestId(`dda-approve-${pending.pid}`);
  const reject = page.getByTestId(`dda-reject-${pending.pid}`);
  await expect(publish).toBeDisabled();
  await expect(submit).toBeDisabled();
  await expect(approve).toBeDisabled();
  await expect(reject).toBeDisabled();
  await expect(page.getByTestId(`dda-publish-${validated.pid}-disabled-reason`)).toContainText(
    '缺少权限 decision.definition.publish',
  );
  await expect(page.getByTestId(`dda-submit-${validated.pid}-disabled-reason`)).toContainText(
    '缺少权限 decision.definition.publish',
  );
  await expect(page.getByTestId(`dda-approve-${pending.pid}-disabled-reason`)).toContainText(
    '缺少权限 decision.definition.approve',
  );
  await expect(page.getByTestId(`dda-reject-${pending.pid}-disabled-reason`)).toContainText(
    '缺少权限 decision.definition.approve',
  );
  await page.getByTestId('dda-version-panel').scrollIntoViewIfNeeded();
  await page.screenshot({
    path: testInfo.outputPath('decisionops-permission-disabled-reasons.png'),
    fullPage: false,
  });

  await expectForbidden(
    page.request.post(`/api/decision/versions/${validated.pid}/publish`, {
      data: { impactAcknowledged: true, note: 'forced publish negative' },
    }),
  );
  await expectForbidden(
    page.request.post(`/api/decision/versions/${pending.pid}/approve`, {
      data: { impactAcknowledged: true, note: 'forced approve negative' },
    }),
  );
});
