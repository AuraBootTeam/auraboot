import { test, expect, type APIRequestContext, type Page } from '@playwright/test';
import { Client } from 'pg';
import { BACKEND_URL, PG_CONN } from '../../helpers/environments';

type ApiEnvelope<T> = {
  code?: number | string;
  success?: boolean;
  message?: string;
  desc?: string;
  data?: T;
};

type RoleRecord = {
  id: number;
  pid: string;
  code: string;
  name: string;
};

type PermissionRecord = {
  id: number;
  pid: string;
  code: string;
  name: string;
};

type DecisionImpactRef = {
  sourceType?: string;
  sourceCode?: string;
  sourcePid?: string;
  targetType?: string;
  targetCode?: string;
  targetPath?: string;
  binding?: string;
  metadata?: Record<string, unknown>;
};

type DecisionImpact = {
  decisionCode: string;
  incoming: DecisionImpactRef[];
  outgoing: DecisionImpactRef[];
  risk?: {
    summary?: string;
    blocking?: boolean;
  };
};

const ADMIN_EMAIL = 'admin@auraboot.com';
const ADMIN_PASSWORD = 'Test2026x';
const TS = Date.now();
const EVIDENCE_DIR = '/Users/ghj/work/auraboot/aura-decision/docs/evidence';

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

async function loginBackend(request: APIRequestContext) {
  const data = await parseEnvelope<{ jwt: string }>(
    await request.post(`${BACKEND_URL}/api/auth/login`, {
      headers: { 'Content-Type': 'application/json' },
      data: {
        email: ADMIN_EMAIL,
        password: ADMIN_PASSWORD,
      },
    }),
  );
  expect(data.jwt).toBeTruthy();
  return data.jwt;
}

async function loginViaBff(page: Page, baseURL: string) {
  const response = await page.request.post(`${baseURL}/login`, {
    form: {
      channelCode: 'email_password',
      email: ADMIN_EMAIL,
      password: ADMIN_PASSWORD,
      remember: 'on',
      redirectTo: '/',
    },
    maxRedirects: 0,
  });
  expect(response.status(), `BFF login failed: ${response.status()}`).toBe(302);
  const setCookie = response.headers()['set-cookie'] ?? '';
  const match = setCookie.match(/__session=([^;]+)/);
  if (!match?.[1]) {
    throw new Error('BFF login did not return __session');
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

async function withDb<T>(fn: (client: Client) => Promise<T>): Promise<T> {
  const client = new Client(PG_CONN);
  await client.connect();
  try {
    return await fn(client);
  } finally {
    await client.end();
  }
}

async function seedPolicySchema(permissionPid: string, decisionCode: string) {
  const schema = {
    dynamicAbac: {
      type: 'rule-center',
      label: 'Dynamic ABAC',
      mode: 'decision',
      expectedMatched: true,
      timeoutMs: 75,
      decisions: [
        {
          code: decisionCode,
          name: 'Permission Department Guard',
        },
      ],
      fields: [
        {
          scope: 'actor',
          path: 'departmentId',
          label: 'Actor department',
          dataType: 'string',
        },
        {
          scope: 'resource',
          path: 'departmentId',
          label: 'Resource department',
          dataType: 'string',
        },
      ],
    },
  };

  await withDb(async (client) => {
    await client.query(
      'update ab_permission set policy_schema = $1::jsonb, updated_at = now() where pid = $2',
      [JSON.stringify(schema), permissionPid],
    );
  });
}

async function shapePermissionHierarchy(
  modulePermission: PermissionRecord,
  resourcePermission: PermissionRecord,
  actionPermission: PermissionRecord,
) {
  await withDb(async (client) => {
    await client.query(
      `
      update ab_permission
      set level = 1,
          parent_id = null,
          path = '/' || id,
          updated_at = now()
      where pid = $1
      `,
      [modulePermission.pid],
    );
    await client.query(
      `
      update ab_permission
      set level = 2,
          parent_id = $1::bigint,
          path = '/' || $1::bigint::text || '/' || id::text,
          updated_at = now()
      where pid = $2
      `,
      [modulePermission.id, resourcePermission.pid],
    );
    await client.query(
      `
      update ab_permission
      set level = 3,
          parent_id = $1::bigint,
          path = '/' || $2::bigint::text || '/' || $1::bigint::text || '/' || id::text,
          updated_at = now()
      where pid = $3
      `,
      [resourcePermission.id, modulePermission.id, actionPermission.pid],
    );
  });
}

async function findRolePermissionPid(rolePid: string, permissionPid: string) {
  return withDb(async (client) => {
    const result = await client.query<{ pid: string }>(
      `
      select rp.pid
      from ab_role_permission rp
      join ab_role r on r.id = rp.role_id
      join ab_permission p on p.id = rp.permission_id
      where r.pid = $1
        and p.pid = $2
        and rp.deleted_flag = false
      order by rp.updated_at desc nulls last, rp.id desc
      limit 1
      `,
      [rolePid, permissionPid],
    );
    const pid = result.rows[0]?.pid;
    expect(pid, 'role-permission binding pid must exist after grant').toBeTruthy();
    return pid;
  });
}

async function readStoredPolicy(rolePermissionPid: string) {
  return withDb(async (client) => {
    const result = await client.query<{ conditions: Record<string, unknown> }>(
      'select conditions from ab_role_permission where pid = $1',
      [rolePermissionPid],
    );
    return result.rows[0]?.conditions;
  });
}

function hasPermissionPolicyImpact(
  impact: DecisionImpact,
  args: {
    rolePermissionPid: string;
    permissionCode: string;
    decisionCode: string;
  },
) {
  return impact.incoming.some((ref) =>
    ref.sourceType === 'PERMISSION_POLICY' &&
    ref.sourcePid === args.rolePermissionPid &&
    ref.sourceCode === args.permissionCode &&
    ref.targetType === 'DECISION' &&
    ref.targetCode === args.decisionCode &&
    ref.binding === 'ROLE_PERMISSION_CONDITION',
  );
}

async function createRoleWithPermission(
  request: APIRequestContext,
  adminToken: string,
  args: {
    roleCode: string;
    moduleCode: string;
    resourceCode: string;
    permissionCode: string;
    decisionCode: string;
  },
) {
  const role = await backendPost<RoleRecord>(request, adminToken, '/api/roles', {
    code: args.roleCode,
    name: `Permission ABAC ${args.roleCode}`,
    description: `Rule-center ABAC E2E ${TS}`,
    type: 'custom',
  });

  const modulePermission = await backendPost<PermissionRecord>(request, adminToken, '/api/permissions', {
    code: `function.${args.moduleCode}.module`,
    name: `ABAC Module ${args.moduleCode}`,
    description: `Rule-center ABAC module E2E ${TS}`,
    resourceType: 'function',
    resourceCode: args.moduleCode,
    action: 'module',
    source: 'e2e',
    sourceRef: 'permission-abac-rule-center',
  });

  const resourcePermission = await backendPost<PermissionRecord>(request, adminToken, '/api/permissions', {
    code: `function.${args.resourceCode}.resource`,
    name: `ABAC Resource ${args.resourceCode}`,
    description: `Rule-center ABAC resource E2E ${TS}`,
    resourceType: 'function',
    resourceCode: args.resourceCode,
    action: 'resource',
    parentId: modulePermission.id,
    source: 'e2e',
    sourceRef: 'permission-abac-rule-center',
  });

  const permission = await backendPost<PermissionRecord>(request, adminToken, '/api/permissions', {
    code: args.permissionCode,
    name: `Approve ${args.resourceCode}`,
    description: `Rule-center ABAC policy E2E ${TS}`,
    resourceType: 'function',
    resourceCode: args.resourceCode,
    action: 'approve',
    parentId: resourcePermission.id,
    source: 'e2e',
    sourceRef: 'permission-abac-rule-center',
  });

  await shapePermissionHierarchy(modulePermission, resourcePermission, permission);
  await seedPolicySchema(permission.pid, args.decisionCode);
  await backendPost<boolean>(request, adminToken, `/api/roles/${role.pid}/permissions`, [permission.pid]);
  return { role, permission };
}

test('Permission matrix hosts rule-center ABAC policy and feeds impact graph @golden', async ({
  page,
  request,
  baseURL,
}, testInfo) => {
  test.setTimeout(120_000);
  const resolvedBaseURL = baseURL ?? 'http://127.0.0.1:5212';
  const suffix = `abac_${TS}`;
  const roleCode = `codex_perm_abac_${suffix}`;
  const moduleCode = `codex_abac_module_${suffix}`;
  const resourceCode = `codex_abac_resource_${suffix}`;
  const permissionCode = `function.${resourceCode}.approve`;
  const decisionCode = permissionCode.replace('function.', 'permission_');
  const adminToken = await loginBackend(request);

  await backendPost<unknown>(request, adminToken, '/api/decision/definitions', {
    decisionCode,
    decisionName: `Permission Department Guard ${suffix}`,
    description: 'Permission ABAC E2E fixture',
    scopeType: 'GOVERNANCE',
    ownerModule: 'permission',
    enabled: true,
  });

  const { role, permission } = await createRoleWithPermission(request, adminToken, {
    roleCode,
    moduleCode,
    resourceCode,
    permissionCode,
    decisionCode,
  });
  const rolePermissionPid = await findRolePermissionPid(role.pid, permission.pid);

  await loginViaBff(page, resolvedBaseURL);
  await page.goto('/enterprise/permissions', { waitUntil: 'domcontentloaded' });
  await expect(page.getByTestId('permission-page')).toBeVisible({ timeout: 15_000 });
  await page.getByTestId('role-search-input').fill(role.code);
  await expect(page.getByTestId(`role-item-${role.code}`)).toBeVisible({ timeout: 10_000 });
  await page.getByTestId(`role-item-${role.code}`).click();
  await expect(page.getByTestId('permission-matrix')).toBeVisible({ timeout: 15_000 });

  const gear = page.getByTestId(`matrix-policy-gear-${resourceCode}-approve`);
  await gear.scrollIntoViewIfNeeded();
  await expect(gear).toBeVisible({ timeout: 10_000 });
  await gear.click();

  const form = page.getByTestId('policy-config-form');
  await expect(form).toBeVisible({ timeout: 10_000 });
  await expect(page.getByTestId('policy-field-dynamicAbac')).toBeVisible();
  await expect(page.getByTestId('decision-rule-binding-block')).toBeVisible();
  await form.getByLabel('version-policy').selectOption('ROLLOUT');
  await form.getByRole('button', { name: '添加映射' }).click();
  await form.getByLabel('mapping-input-0').fill('departmentId');
  await form.getByLabel('mapping-field-0').selectOption('actor:departmentId');
  await form.getByLabel('fallback-mode').selectOption('FAIL_CLOSED');
  await page.getByTestId('policy-rule-timeout-dynamicAbac').fill('75');

  await page.screenshot({
    path: testInfo.outputPath('permission-abac-rule-center-policy-dialog-golden.png'),
    fullPage: true,
  });
  await page.screenshot({
    path: `${EVIDENCE_DIR}/2026-06-11-permission-abac-rule-center-policy-dialog-golden.png`,
    fullPage: true,
  });

  const saveResponse = page.waitForResponse(
    (r) =>
      r.url().includes(`/api/permissions/matrix/${role.pid}/policy/${permission.pid}`) &&
      r.request().method().toUpperCase() === 'PUT',
    { timeout: 15_000 },
  );
  await page.getByTestId('policy-save-button').click();
  const save = await saveResponse;
  expect(save.ok(), await save.text()).toBe(true);
  await expect(form).not.toBeVisible({ timeout: 10_000 });

  const apiPolicy = await parseEnvelope<Record<string, any>>(
    await page.request.get(`/api/permissions/matrix/${role.pid}/policy/${permission.pid}`),
  );
  expect(apiPolicy.dynamicAbac?.ruleBinding?.consumerType).toBe('PERMISSION');
  expect(apiPolicy.dynamicAbac?.ruleBinding?.consumerNodeId).toBe('dynamicAbac');
  expect(apiPolicy.dynamicAbac?.ruleBinding?.decisionBinding?.decisionCode).toBe(decisionCode);
  expect(apiPolicy.dynamicAbac?.ruleBinding?.decisionBinding?.versionPolicy).toBe('ROLLOUT');
  expect(apiPolicy.dynamicAbac?.ruleBinding?.decisionBinding?.fallbackPolicy?.mode).toBe('FAIL_CLOSED');
  expect(apiPolicy.dynamicAbac?.ruleBinding?.decisionBinding?.inputMappings?.[0]).toMatchObject({
    input: 'departmentId',
    source: { kind: 'FIELD', scope: 'actor', path: 'departmentId' },
  });

  const storedPolicy = await readStoredPolicy(rolePermissionPid);
  expect(storedPolicy?.dynamicAbac).toBeTruthy();
  expect((storedPolicy as any).dynamicAbac.ruleBinding.decisionBinding.decisionCode).toBe(decisionCode);

  await expect
    .poll(
      async () => {
        const impact = await backendGet<DecisionImpact>(
          request,
          adminToken,
          `/api/decision/definitions/${encodeURIComponent(decisionCode)}/impact`,
        );
        return hasPermissionPolicyImpact(impact, {
          rolePermissionPid,
          permissionCode,
          decisionCode,
        });
      },
      {
        message: 'setPolicy should refresh PERMISSION_POLICY usage-index without manual rebuild',
        timeout: 15_000,
      },
    )
    .toBe(true);

  await page.goto('/enterprise/permissions', { waitUntil: 'domcontentloaded' });
  await expect(page.getByTestId('permission-page')).toBeVisible({ timeout: 15_000 });
  await page.getByTestId('role-search-input').fill(role.code);
  await page.getByTestId(`role-item-${role.code}`).click();
  await expect(page.getByTestId('permission-matrix')).toBeVisible({ timeout: 15_000 });
  await page.getByTestId(`matrix-policy-gear-${resourceCode}-approve`).click();
  await expect(form).toBeVisible({ timeout: 10_000 });
  await expect(page.getByTestId('decision-binding-preview')).toContainText(decisionCode);
  await form.getByLabel('refresh-impact').click();
  await expect(page.getByTestId('decision-impact-summary')).toContainText('1 个引用', {
    timeout: 15_000,
  });
  await page.screenshot({
    path: testInfo.outputPath('permission-abac-rule-center-impact-golden.png'),
    fullPage: true,
  });

  await page.screenshot({
    path: `${EVIDENCE_DIR}/2026-06-11-permission-abac-rule-center-impact-golden.png`,
    fullPage: true,
  });
});
