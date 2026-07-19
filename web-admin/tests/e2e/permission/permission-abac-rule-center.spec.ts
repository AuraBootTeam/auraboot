import { test, expect, type APIRequestContext, type Locator, type Page } from '@playwright/test';
import { mkdirSync, rmSync, statSync } from 'node:fs';
import { resolve as resolvePath } from 'node:path';
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

type DecisionVersion = {
  pid: string;
  status?: string;
  version?: number;
};

type UserOption = {
  id?: string | number;
  pid?: string;
  displayName?: string;
  name?: string;
  realName?: string;
  nickName?: string;
  nickname?: string;
  username?: string;
  email?: string;
};

type DecisionTraceMetadata = {
  label?: string;
  dataType?: string;
  modelCode?: string;
  valueLabels?: Record<string, string>;
};

type DecisionTraceLog = {
  pid: string;
  traceId: string;
  decisionCode: string;
  callerType: string;
  callerRef: string;
  matched: boolean;
  status: string;
  traceSnapshot: {
    factMetadata?: Record<string, DecisionTraceMetadata>;
  };
};

type LeaveRequestRecordRow = {
  id: string;
  pid: string;
  tenant_id: string;
  wd_req_applicant: string | null;
};

type MatrixAction = {
  code: string;
  granted?: boolean;
  supported?: boolean;
  policySchema?: string | null;
};

type MatrixResource = {
  actions?: MatrixAction[];
};

type MatrixModule = {
  resources?: MatrixResource[];
};

type MatrixPayload = {
  modules?: MatrixModule[];
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

type FactCatalog = {
  entities?: Array<{
    facts?: Array<{
      scope?: string;
      path?: string;
      label?: string;
      editable?: boolean;
      visible?: boolean;
      masked?: boolean;
    }>;
  }>;
};

type FieldExtensionSnapshot = {
  fieldId: string;
  fieldCode: string;
  extension: Record<string, unknown> | null;
};

const ADMIN_EMAIL = 'admin@auraboot.com';
const ADMIN_PASSWORD = 'Test2026x';
const TS = Date.now();
const EVIDENCE_DIR = '/Users/ghj/work/auraboot/aura-decision/docs/evidence';
const SYSTEM_REFERENCE_ASSETS_DIR = resolvePath(process.cwd(), '../docs/system-reference/assets');
const LEAVE_REQUEST_FIELD_PERMISSION_LOCK = 'wd-leave-request-field-permission';

test.use({ storageState: { cookies: [], origins: [] } });

function headers(token: string) {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
}

function dateOffsetStr(offsetDays: number): string {
  const date = new Date();
  date.setDate(date.getDate() + offsetDays);
  return date.toISOString().slice(0, 10);
}

async function acquireFileLock(name: string): Promise<() => Promise<void>> {
  const root = resolvePath(process.cwd(), 'test-results/.locks');
  const lockPath = resolvePath(root, `${name}.lock`);
  const deadline = Date.now() + 60_000;
  mkdirSync(root, { recursive: true });

  while (true) {
    try {
      mkdirSync(lockPath);
      let released = false;
      return async () => {
        if (released) return;
        released = true;
        rmSync(lockPath, { recursive: true, force: true });
      };
    } catch (error) {
      const code = (error as NodeJS.ErrnoException).code;
      if (code !== 'EEXIST') throw error;
      const ageMs = Date.now() - statSync(lockPath).mtimeMs;
      if (ageMs > 120_000) {
        rmSync(lockPath, { recursive: true, force: true });
        continue;
      }
      if (Date.now() > deadline) {
        throw new Error(`Timed out waiting for ${name} lock at ${lockPath}`);
      }
      await new Promise((resolve) => setTimeout(resolve, 250));
    }
  }
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

async function loginBackendAs(request: APIRequestContext, email: string, password: string) {
  const data = await parseEnvelope<{ jwt: string }>(
    await request.post(`${BACKEND_URL}/api/auth/login`, {
      headers: { 'Content-Type': 'application/json' },
      data: {
        email,
        password,
      },
    }),
  );
  expect(data.jwt).toBeTruthy();
  return data.jwt;
}

async function loginBackend(request: APIRequestContext) {
  return loginBackendAs(request, ADMIN_EMAIL, ADMIN_PASSWORD);
}

async function loginViaBff(
  page: Page,
  baseURL: string,
  credentials: { email: string; password: string } = {
    email: ADMIN_EMAIL,
    password: ADMIN_PASSWORD,
  },
) {
  const response = await page.request.post(`${baseURL}/login`, {
    form: {
      channelCode: 'email_password',
      email: credentials.email,
      password: credentials.password,
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

async function clickLocatorCenter(page: Page, locator: Locator, label: string) {
  await locator.scrollIntoViewIfNeeded();
  const box = await locator.boundingBox();
  expect(box, `${label} must have a clickable bounding box`).toBeTruthy();
  await page.mouse.click(box!.x + box!.width / 2, box!.y + box!.height / 2);
}

async function openPermissionAuditTab(page: Page) {
  await expect(page.getByTestId('role-table')).toBeVisible({ timeout: 15_000 });
  await expect(page.locator('[data-testid="role-table"] tbody tr').first()).toBeVisible({
    timeout: 15_000,
  });
  const auditTabButton = page.getByTestId('permission-right-tab-audit');
  await expect(auditTabButton).toBeVisible({ timeout: 10_000 });
  await clickLocatorCenter(page, auditTabButton, 'permission audit tab');
  await expect(auditTabButton).toHaveAttribute('aria-selected', 'true', { timeout: 10_000 });
  await expect(page.getByTestId('permission-audit-tab')).toBeVisible({ timeout: 10_000 });
}

async function createStandaloneRole(
  request: APIRequestContext,
  adminToken: string,
  args: { roleCode: string; name?: string },
) {
  return backendPost<RoleRecord>(request, adminToken, '/api/roles', {
    code: args.roleCode,
    name: args.name ?? `Permission Field Role ${args.roleCode}`,
    description: `Rule-center low-permission field projection E2E ${TS}`,
    type: 'custom',
  });
}

async function createUserWithRoles(
  request: APIRequestContext,
  adminToken: string,
  args: {
    email: string;
    displayName: string;
    password: string;
    roleCodes: string[];
  },
) {
  return backendPost<{ userPid?: string }>(request, adminToken, '/api/admin/users', {
    email: args.email,
    displayName: args.displayName,
    initialPassword: args.password,
    roleCodes: args.roleCodes,
    sendInviteEmail: false,
  });
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

function e2ePid(prefix: string, suffix: string) {
  return `${prefix}${suffix.replace(/[^a-zA-Z0-9]/g, '')}`.padEnd(26, '0').slice(0, 26);
}

async function refreshModelCache(request: APIRequestContext, adminToken: string, modelCode: string) {
  const model = await backendGet<{ pid?: string }>(
    request,
    adminToken,
    `/api/meta/models/code/${encodeURIComponent(modelCode)}`,
  );
  expect(model?.pid, `model ${modelCode} must exist before refreshing cache`).toBeTruthy();
  await backendPost<void>(request, adminToken, `/api/meta/models/${model.pid}/refresh-cache`);
}

async function refreshModelCacheFromBrowser(page: Page, modelCode: string) {
  const modelResponse = await page.request.get(`/api/meta/models/code/${encodeURIComponent(modelCode)}`);
  const modelRaw = await modelResponse.text();
  expect(modelResponse.ok(), `browser model lookup should load for ${modelCode}: ${modelRaw}`).toBe(true);
  const modelBody = JSON.parse(modelRaw) as ApiEnvelope<{ pid?: string }>;
  expect(modelBody.data?.pid, `browser model ${modelCode} must expose pid before refreshing cache`).toBeTruthy();
  const refreshResponse = await page.request.post(`/api/meta/models/${modelBody.data!.pid}/refresh-cache`);
  const refreshRaw = await refreshResponse.text();
  expect(refreshResponse.ok(), `browser model cache refresh should succeed: ${refreshRaw}`).toBe(true);
}

async function setFieldPermission(
  modelCode: string,
  fieldCode: string,
  fieldPermission: { view: string[]; edit: string[] },
): Promise<FieldExtensionSnapshot[]> {
  return withDb(async (client) => {
    const selected = await client.query<{
      id: string;
      extension: Record<string, unknown> | null;
    }>(
      `
      select f.id, f.extension
      from ab_meta_field f
      join ab_meta_model_field_binding b on b.field_id = f.id
      join ab_meta_model m on m.id = b.model_id
      where m.code = $1
        and f.code = $2
        and m.deleted_flag = false
        and f.deleted_flag = false
        and b.deleted_flag = false
        and m.is_current = true
        and f.is_current = true
      order by f.updated_at desc nulls last, f.id desc
      `,
      [modelCode, fieldCode],
    );
    expect(
      selected.rows.length,
      `field ${modelCode}.${fieldCode} must exist before patching fieldPermission`,
    ).toBeGreaterThan(0);
    await client.query(
      `
      update ab_meta_field
      set extension = jsonb_set(
              coalesce(extension, '{}'::jsonb)
                || jsonb_build_object('extension', coalesce(extension->'extension', '{}'::jsonb)),
              '{extension,fieldPermission}',
              $2::jsonb,
              true
          ),
          updated_at = now()
      where id = any($1::bigint[])
      `,
      [selected.rows.map((row) => row.id), JSON.stringify(fieldPermission)],
    );
    return selected.rows.map((row) => ({
      fieldId: row.id,
      fieldCode,
      extension: row.extension ?? {},
    }));
  });
}

async function restoreFieldExtensions(snapshots: FieldExtensionSnapshot[]) {
  if (snapshots.length === 0) {
    return;
  }
  await withDb(async (client) => {
    for (const snapshot of snapshots) {
      await client.query(
        `
        update ab_meta_field
        set extension = $2::jsonb,
            updated_at = now()
        where id = $1
        `,
        [snapshot.fieldId, JSON.stringify(snapshot.extension ?? {})],
      );
    }
  });
}

async function seedPolicySchema(permissionPid: string, decisionCode: string) {
  const schema = {
    dynamicAbac: {
      type: 'rule-center',
      label: 'Dynamic ABAC',
      mode: 'decision',
      expectedMatched: true,
      timeoutMs: 75,
      fieldCatalogMode: 'merge',
      fieldCatalogModelCode: 'wd_leave_request',
      decisions: [
        {
          code: decisionCode,
          name: 'Permission Department Guard',
          outputs: [
            {
              id: 'allowed',
              label: '是否允许',
              dataType: 'boolean',
            },
            {
              id: 'grantReason',
              label: '授权说明',
              dataType: 'string',
            },
            {
              id: 'maskedFields',
              label: '脱敏字段',
              dataType: 'collection',
            },
          ],
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

async function findPermissionByCode(permissionCode: string) {
  return withDb(async (client) => {
    const result = await client.query<PermissionRecord>(
      `
      select id, pid, code, name
      from ab_permission
      where code = $1
        and deleted_flag = false
      order by id desc
      limit 1
      `,
      [permissionCode],
    );
    const permission = result.rows[0];
    expect(permission, `permission ${permissionCode} must exist`).toBeTruthy();
    return permission;
  });
}

async function findTenantMemberForEmail(email: string) {
  return withDb(async (client) => {
    const result = await client.query<{ id: string; tenant_id: string }>(
      `
      select tm.id, tm.tenant_id
      from ab_tenant_member tm
      join ab_user u on u.id = tm.user_id
      where u.email = $1
        and tm.deleted_flag = false
      order by tm.id desc
      limit 1
      `,
      [email],
    );
    const member = result.rows[0];
    expect(member, `tenant member for ${email} must exist`).toBeTruthy();
    return {
      memberId: member.id,
      tenantId: member.tenant_id,
    };
  });
}

async function pickLeaveRequestRecord() {
  return withDb(async (client) => {
    const result = await client.query<{ id: string; pid: string; tenant_id: string }>(
      `
      select id, pid, tenant_id
      from mt_wd_leave_request
      where tenant_id is not null
      order by id desc
      limit 1
      `,
    );
    const record = result.rows[0];
    expect(record, 'workflow-demo leave request seed must exist for permission access audit').toBeTruthy();
    return {
      id: Number(record.id),
      pid: record.pid,
      tenantId: Number(record.tenant_id),
    };
  });
}

async function findLatestLeaveRequestRecord() {
  return withDb(async (client) => {
    const result = await client.query<LeaveRequestRecordRow>(
      `
      select id, pid, tenant_id, wd_req_applicant
      from mt_wd_leave_request
      where tenant_id is not null
      order by id desc
      limit 1
      `,
    );
    return result.rows[0] ?? null;
  });
}

async function findLeaveRequestRecordByPid(pid: string) {
  return withDb(async (client) => {
    const result = await client.query<LeaveRequestRecordRow>(
      `
      select id, pid, tenant_id, wd_req_applicant
      from mt_wd_leave_request
      where pid = $1
        and tenant_id is not null
      order by id desc
      limit 1
      `,
      [pid],
    );
    return result.rows[0] ?? null;
  });
}

async function createDraftLeaveRequestSeed(
  request: APIRequestContext,
  adminToken: string,
  applicantPid: string,
  suffix: string,
) {
  const created = await backendPost<{
    data?: { recordPid?: string };
    recordPid?: string;
    pid?: string;
  }>(request, adminToken, '/api/meta/commands/execute/wd:create_leave_request', {
    payload: {
      wd_req_applicant: applicantPid,
      wd_req_type: 'annual',
      wd_req_start_date: dateOffsetStr(5),
      wd_req_start_slot: 'AM',
      wd_req_end_date: dateOffsetStr(5),
      wd_req_end_slot: 'PM',
      wd_req_days: 0.5,
      wd_req_reason: `permission applicant trace fixture ${suffix}`,
    },
    operationType: 'create',
  });
  const pid = String(created?.data?.recordPid ?? created?.recordPid ?? created?.pid ?? '');
  expect(pid, 'wd:create_leave_request must return recordPid for permission trace fixture').toBeTruthy();
  return pid;
}

async function pickLeaveRequestRecordWithApplicant(
  request: APIRequestContext,
  adminToken: string,
  applicantPid: string,
  suffix: string,
) {
  let record = await findLatestLeaveRequestRecord();
  let createdForTest = false;
  if (!record) {
    const createdPid = await createDraftLeaveRequestSeed(request, adminToken, applicantPid, suffix);
    record = await findLeaveRequestRecordByPid(createdPid);
    createdForTest = true;
  }
  expect(record, 'workflow-demo leave request seed must exist or be creatable for applicant reference permission trace')
    .toBeTruthy();

  await withDb(async (client) => {
    await client.query(
      `
      update mt_wd_leave_request
      set wd_req_applicant = $2,
          updated_at = now()
      where id = $1::bigint
      `,
      [record!.id, applicantPid],
    );
  });

  return {
    id: Number(record!.id),
    pid: record!.pid,
    tenantId: record!.tenant_id,
    applicantPid,
    originalApplicantPid: record!.wd_req_applicant,
    createdForTest,
  };
}

async function restoreLeaveRequestApplicant(snapshot: Awaited<ReturnType<typeof pickLeaveRequestRecordWithApplicant>>) {
  await withDb(async (client) => {
    await client.query(
      `
      update mt_wd_leave_request
      set wd_req_applicant = $2,
          updated_at = now()
      where id = $1::bigint
      `,
      [snapshot.id, snapshot.originalApplicantPid],
    );
  });
}

async function updateRolePermissionConditionAst(rolePermissionPid: string, ast: Record<string, unknown>) {
  await withDb(async (client) => {
    await client.query(
      `
      update ab_role_permission
      set condition_ast = $2::jsonb,
          updated_at = now()
      where pid = $1
      `,
      [rolePermissionPid, JSON.stringify(ast)],
    );
  });
}

async function updateRolePermissionConditions(rolePermissionPid: string, conditions: Record<string, unknown>) {
  await withDb(async (client) => {
    await client.query(
      `
      update ab_role_permission
      set conditions = $2::jsonb,
          condition_ast = null,
          updated_at = now()
      where pid = $1
      `,
      [rolePermissionPid, JSON.stringify(conditions)],
    );
  });
}

async function resolveFirstUserForReference(request: APIRequestContext, adminToken: string) {
  const users = await backendGet<UserOption[]>(
    request,
    adminToken,
    '/api/admin/users/search?keyword=&size=20',
  );
  const user = users.find((item) => item.pid || item.id);
  expect(user, 'at least one user must exist for Permission applicant reference trace evidence').toBeTruthy();
  const pid = String(user?.pid ?? user?.id ?? '');
  const label = String(
    user?.displayName ??
      user?.name ??
      user?.realName ??
      user?.nickName ??
      user?.nickname ??
      user?.username ??
      user?.email ??
      pid,
  );
  expect(pid).not.toEqual('');
  expect(label).not.toEqual('');
  return { pid, label };
}

async function createAndPublishApplicantDecision(
  request: APIRequestContext,
  adminToken: string,
  decisionCode: string,
  mismatchPid: string,
): Promise<DecisionVersion> {
  await backendPost<unknown>(request, adminToken, '/api/decision/definitions', {
    decisionCode,
    decisionName: `Permission Applicant Guard ${decisionCode}`,
    description: 'Permission ABAC applicant reference trace E2E fixture',
    scopeType: 'PERMISSION',
    ownerModule: 'permission',
    enabled: true,
  });

  const draft = await backendPost<DecisionVersion>(
    request,
    adminToken,
    `/api/decision/definitions/${encodeURIComponent(decisionCode)}/versions`,
    {
      kind: 'SIMPLE_CONDITION',
      runtimeAdapter: 'AST_EVALUATOR',
      versionTag: `permission-applicant-${Date.now()}`,
      contentJson: {
        type: 'compare',
        left: {
          type: 'path',
          scope: 'record',
          path: 'data.wd_req_applicant',
          dataType: 'user',
        },
        operator: 'EQ',
        right: {
          type: 'literal',
          value: mismatchPid,
          dataType: 'user',
        },
      },
    },
  );
  expect(draft.pid).toBeTruthy();

  const validation = await backendPost<{ valid?: boolean }>(
    request,
    adminToken,
    `/api/decision/versions/${encodeURIComponent(draft.pid)}/validate`,
  );
  expect(validation.valid).toBe(true);

  const published = await backendPost<DecisionVersion>(
    request,
    adminToken,
    `/api/decision/versions/${encodeURIComponent(draft.pid)}/publish`,
    {
      impactAcknowledged: true,
      note: 'Permission applicant reference trace E2E publish',
    },
  );
  expect(String(published.status ?? '')).toMatch(/published/i);
  return published;
}

function extractRuleTraceId(value: unknown): string | undefined {
  if (!value) {
    return undefined;
  }
  if (Array.isArray(value)) {
    for (const item of value) {
      const found = extractRuleTraceId(item);
      if (found) return found;
    }
    return undefined;
  }
  if (typeof value === 'object') {
    const record = value as Record<string, unknown>;
    const direct = record.ruleTraceId;
    if (typeof direct === 'string' && direct.trim().length > 0) {
      return direct;
    }
    for (const nested of Object.values(record)) {
      const found = extractRuleTraceId(nested);
      if (found) return found;
    }
  }
  return undefined;
}

function permissionAuditVisibleTraceId(traceId: string): string {
  return traceId.replace(/\b\d{6,}\b/g, '***');
}

async function readDecisionTraceLog(traceId: string): Promise<DecisionTraceLog> {
  return withDb(async (client) => {
    const result = await client.query<{
      pid: string;
      trace_id: string;
      decision_code: string;
      caller_type: string;
      caller_ref: string;
      matched: boolean;
      status: string;
      trace_snapshot: unknown;
    }>(
      `
      select pid, trace_id, decision_code, caller_type, caller_ref, matched, status, trace_snapshot
      from ab_drt_log
      where trace_id = $1
      order by id desc
      limit 1
      `,
      [traceId],
    );
    const row = result.rows[0];
    expect(row, `DecisionOps trace log ${traceId} must exist`).toBeTruthy();
    return {
      pid: row.pid,
      traceId: row.trace_id,
      decisionCode: row.decision_code,
      callerType: row.caller_type,
      callerRef: row.caller_ref,
      matched: row.matched,
      status: row.status,
      traceSnapshot:
        typeof row.trace_snapshot === 'string'
          ? JSON.parse(row.trace_snapshot)
          : (row.trace_snapshot as DecisionTraceLog['traceSnapshot']),
    };
  });
}

async function waitForPermissionAuditRow(args: {
  tenantId: string;
  memberId: string;
  resourceCode: string;
  actionCode: string;
  recordPid: string;
  sinceIso: string;
}) {
  return withDb(async (client) => {
    const result = await client.query<{
      id: string;
      reason: string;
      evaluation_trace: unknown;
    }>(
      `
      select id, reason, evaluation_trace
      from ab_permission_audit_log
      where tenant_id = $1
        and member_id = $2
        and resource_code = $3
        and action_code = $4
        and record_pid = $5
        and created_at >= $6::timestamp
      order by created_at desc, id desc
      limit 1
      `,
      [args.tenantId, args.memberId, args.resourceCode, args.actionCode, args.recordPid, args.sinceIso],
    );
    if (!result.rows[0]) {
      return null;
    }
    return {
      id: Number(result.rows[0].id),
      reason: result.rows[0].reason,
      evaluationTrace: result.rows[0].evaluation_trace,
    };
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

async function selectRoleAndWaitForMatrix(page: Page, role: RoleRecord) {
  const matrixResponse = page.waitForResponse(
    (r) => r.url().includes(`/api/permissions/matrix/${role.pid}`) && r.status() === 200,
    { timeout: 15_000 },
  );
  await page.getByTestId(`role-item-${role.code}`).click();
  await matrixResponse;
  await expect(page.locator(`[data-testid="capability-role-editor"][data-role-pid="${role.pid}"]`)).toBeVisible({
    timeout: 15_000,
  });
}

async function readMatrixActionFromBrowser(page: Page, rolePid: string, permissionCode: string) {
  const response = await page.request.get(`/api/permissions/matrix/${rolePid}`);
  expect(response.ok(), `Matrix API should load for role ${rolePid}: ${await response.text()}`).toBe(true);
  const body = (await response.json()) as ApiEnvelope<MatrixPayload>;
  let fallback: MatrixAction | null = null;
  for (const module of body.data?.modules ?? []) {
    for (const resource of module.resources ?? []) {
      for (const action of resource.actions ?? []) {
        if (action.code === permissionCode) {
          if (action.supported !== false) {
            return action;
          }
          fallback = fallback ?? action;
        }
      }
    }
  }
  return fallback;
}

async function readSelectOptions(select: Locator) {
  return select.locator('option').evaluateAll((items) =>
    items.map((item) => ({
      value: (item as HTMLOptionElement).value,
      label: item.textContent?.trim() ?? '',
      disabled: (item as HTMLOptionElement).disabled,
    })),
  );
}

function flattenFactCatalog(catalog: FactCatalog) {
  return (catalog.entities ?? []).flatMap((entity) => entity.facts ?? []);
}

async function readFactCatalogForToken(
  request: APIRequestContext,
  token: string,
  modelCode: string,
) {
  return backendGet<FactCatalog>(
    request,
    token,
    `/api/decision/facts/catalog?modelCode=${encodeURIComponent(modelCode)}`,
  );
}

async function openAtomicPolicyDialog(page: Page, role: RoleRecord, permissionCode: string) {
  for (let attempt = 0; attempt < 2; attempt += 1) {
    await page.goto('/enterprise/permissions', { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('permission-page')).toBeVisible({ timeout: 15_000 });
    await page.getByTestId('role-search-input').fill(role.code);
    await expect(page.getByTestId(`role-item-${role.code}`)).toBeVisible({ timeout: 10_000 });
    await selectRoleAndWaitForMatrix(page, role);

    await expect
      .poll(
        async () => {
          const action = await readMatrixActionFromBrowser(page, role.pid, permissionCode);
          return Boolean(action?.granted && action.policySchema);
        },
        {
          message: `matrix action ${permissionCode} should be granted and expose policy schema`,
          timeout: 10_000,
        },
      )
      .toBe(true);

    // v2 IA: the raw matrix is folded into the ③ advanced atomic-actions escape hatch.
    await page.getByTestId('advanced-atomic-toggle').click();
    await page.getByTestId('advanced-atomic-search').fill(permissionCode);
    const row = page.getByTestId(`atomic-row-${permissionCode}`);
    await expect(row).toBeVisible({ timeout: 15_000 });
    await row.scrollIntoViewIfNeeded();

    const gear = page.getByTestId(`atomic-policy-${permissionCode}`);
    const checkbox = page.getByTestId(`atomic-checkbox-${permissionCode}`);
    const matrixAction = await readMatrixActionFromBrowser(page, role.pid, permissionCode);
    if (!(await gear.isVisible().catch(() => false))) {
      if (matrixAction?.granted && matrixAction.policySchema) {
        if (attempt === 0) {
          continue;
        }
        await expect(gear).toBeVisible({ timeout: 5_000 });
      } else {
        await expect(checkbox).toBeVisible({ timeout: 5_000 });
        if (!(await checkbox.isChecked())) {
          const grantResponse = page.waitForResponse(
            (r) =>
              r.url().includes(`/api/permissions/matrix/${role.pid}/batch`) &&
              r.request().method().toUpperCase() === 'PUT',
            { timeout: 15_000 },
          );
          await checkbox.click();
          const grant = await grantResponse;
          expect(grant.ok(), `granting ${permissionCode} through the UI should succeed: ${await grant.text()}`).toBe(true);
          await expect
            .poll(
              async () => {
                const action = await readMatrixActionFromBrowser(page, role.pid, permissionCode);
                return Boolean(action?.granted && action.policySchema);
              },
              {
                message: `matrix action ${permissionCode} should be granted after UI toggle`,
                timeout: 10_000,
              },
            )
            .toBe(true);
        }
      }
    }
    try {
      await expect(gear).toBeVisible({ timeout: 5_000 });
    } catch (error) {
      if (attempt === 0) {
        continue;
      }
      throw error;
    }
    const factCatalogResponse = page.waitForResponse(
      (r) =>
        r.url().includes('/api/decision/facts/catalog') &&
        r.url().includes('wd_leave_request') &&
        r.status() === 200,
      { timeout: 15_000 },
    );
    await gear.click();
    await factCatalogResponse;

    const form = page.getByTestId('policy-config-form');
    await expect(form).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId('policy-field-dynamicAbac')).toBeVisible();
    await expect(page.getByTestId('decision-rule-binding-block')).toBeVisible();
    return form;
  }
  throw new Error(`Unable to open policy dialog for ${permissionCode}`);
}

async function fillValidDynamicAbacBinding(page: Page, form: Locator) {
  await form.getByLabel('version-policy').selectOption('ROLLOUT');
  await form.getByRole('button', { name: '添加映射' }).click();
  await form.getByLabel('mapping-input-0').fill('days');
  await form.getByLabel('mapping-field-0').selectOption('record:data.wd_req_days');
  await form.getByRole('button', { name: '添加输出' }).click();
  await expect(form.getByLabel('output-mapping-output-picker-0')).toContainText('授权说明');
  await form.getByLabel('output-mapping-output-picker-0').selectOption('grantReason');
  await expect(form.getByLabel('output-mapping-output-0')).toHaveValue('grantReason');
  await expect(form.getByLabel('output-mapping-kind-0')).toHaveValue('PERMISSION_CONTEXT');
  await form.getByLabel('fallback-mode').selectOption('FAIL_CLOSED');
  await page.getByTestId('policy-rule-timeout-dynamicAbac').fill('75');
  await expect(page.getByTestId('decision-binding-preview')).toContainText('请假', {
    timeout: 10_000,
  });
}

async function seedPermissionAuditEvidence(suffix: string) {
  return withDb(async (client) => {
    const recordResult = await client.query<{
      id: string;
      pid: string;
      tenant_id: string;
    }>(
      `
      select id, pid, tenant_id
      from mt_wd_leave_request
      where tenant_id is not null
      order by id desc
      limit 1
      `,
    );
    expect(recordResult.rows.length, 'workflow-demo leave request seed must exist for audit pid resolution').toBeGreaterThan(0);
    const record = recordResult.rows[0];
    const reason = `record.data.salary denied with value=9876543210 (${suffix})`;
    const ruleTraceId = `trace-permission-${suffix}`;
    const decisionCode = `leave_approval_route_${suffix}`;
    const decisionLogPid = e2ePid('01PERMTRACE', suffix);
    const correlationId = `permission-audit-${suffix}`;
    const contextMessage = 'Manager approval required';
    const decisionOutputs = {
      severity: 'high',
      message: contextMessage,
      actionType: 'notify',
    };
    const permissionContext = {
      severity: 'high',
      decisionMessage: contextMessage,
    };
    const trace = [
      {
        evaluatorName: 'Policy',
        verdict: 'DENY',
        reason: 'record.data.salary is not available in permission ABAC fact catalog',
        details: {
          ruleCenterFailures: [
            {
              grantId: 900,
              error: `record.data.salary is masked and cannot be used with token=secret-token-${suffix}`,
              fieldGovernance: {
                fieldRef: 'record.data.salary',
                reason: 'masked',
                validation: 'DENY',
                source: 'permission-policy-validation',
              },
            },
          ],
        },
        payload: {
          salary: '9876543210',
          token: `secret-token-${suffix}`,
        },
      },
      {
        evaluatorName: 'Rule Center',
        verdict: 'ALLOW',
        reason: 'Decision output mapped into permission context',
        details: {
          ruleTraceId,
          bindingKind: 'PERMISSION_POLICY',
          decisionCode,
          decisionVersion: 3,
          decisionStatus: 'PUBLISHED',
          matched: true,
          fallbackApplied: false,
          inputSnapshot: {
            record: {
              wd_req_days: 5,
              salary: '9876543210',
            },
          },
          decisionOutputs,
          permissionContext,
          fieldRefs: ['record.data.wd_req_days'],
          decisionRefs: [`${decisionCode}@3`],
        },
      },
    ];
    const inserted = await client.query<{ id: string }>(
      `
      insert into ab_permission_audit_log
        (tenant_id, member_id, resource_code, action_code, record_id, record_pid, result, reason, evaluation_trace, created_at)
      values
        ($1::bigint, 5, 'wd_leave_request', 'view', $2::bigint, $3, false, $4, $5::jsonb, now())
      returning id
      `,
      [record.tenant_id, record.id, record.pid, reason, JSON.stringify(trace)],
    );
    await client.query(
      `
      insert into ab_drt_log
        (pid, tenant_id, trace_id, correlation_id, decision_code, decision_version, selected_version,
         kind, runtime_adapter, caller_type, caller_ref, matched, status, matched_rules_json,
         output_snapshot, trace_snapshot, duration_ms, created_at)
      values
        ($1, $2::bigint, $3, $4, $5, 3, 3,
         'DMN', 'DMN', 'PERMISSION', 'wd_leave_request.view', true, 'MATCHED', $6::jsonb,
         $7::jsonb, $8::jsonb, 18, now())
      on conflict (pid) do nothing
      `,
      [
        decisionLogPid,
        record.tenant_id,
        ruleTraceId,
        correlationId,
        decisionCode,
        JSON.stringify(['permission_abac_allow']),
        JSON.stringify(decisionOutputs),
        JSON.stringify({
          source: 'permission-audit-e2e',
          permissionContext,
          fieldRefs: ['record.data.wd_req_days'],
        }),
      ],
    );
    return {
      id: Number(inserted.rows[0].id),
      decisionLogPid,
      recordPid: record.pid,
      internalRecordId: record.id,
      reason,
      secretToken: `secret-token-${suffix}`,
      ruleTraceId,
      decisionCode,
      correlationId,
      contextMessage,
    };
  });
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
  const form = await openAtomicPolicyDialog(page, role, permissionCode);
  await fillValidDynamicAbacBinding(page, form);

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
    input: 'days',
    source: { kind: 'FIELD', scope: 'record', path: 'data.wd_req_days' },
  });
  expect(apiPolicy.dynamicAbac?.ruleBinding?.decisionBinding?.outputMappings?.[0]).toMatchObject({
    output: 'grantReason',
    target: { kind: 'PERMISSION_CONTEXT', path: 'grantReason' },
  });

  const storedPolicy = await readStoredPolicy(rolePermissionPid);
  expect(storedPolicy?.dynamicAbac).toBeTruthy();
  expect((storedPolicy as any).dynamicAbac.ruleBinding.decisionBinding.decisionCode).toBe(decisionCode);
  expect((storedPolicy as any).dynamicAbac.ruleBinding.decisionBinding.outputMappings?.[0]).toMatchObject({
    output: 'grantReason',
    target: { kind: 'PERMISSION_CONTEXT', path: 'grantReason' },
  });

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

  const reloadedForm = await openAtomicPolicyDialog(page, role, permissionCode);
  const reloadedPreview = page.getByTestId('decision-binding-preview');
  await expect(reloadedPreview).toContainText('Permission Department Guard');
  await expect(reloadedPreview).toContainText('1 输入');
  await expect(reloadedPreview).toContainText('days');
  await expect(reloadedPreview).toContainText('请假');
  const impactTab = reloadedForm.getByTestId('decision-rule-section-tab-impact');
  await expect(impactTab).toBeVisible({ timeout: 10_000 });
  await impactTab.click();
  await expect(page.getByTestId('decision-impact-preview')).toBeVisible({ timeout: 10_000 });
  const refreshImpact = reloadedForm.locator('button[aria-label="refresh-impact"]').first();
  await expect(refreshImpact).toBeVisible({ timeout: 10_000 });
  await refreshImpact.scrollIntoViewIfNeeded();
  await refreshImpact.click();
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

test('Permission policy rejects tampered out-of-catalog field from the browser save path @golden', async ({
  page,
  request,
  baseURL,
}, testInfo) => {
  test.setTimeout(120_000);
  const resolvedBaseURL = baseURL ?? 'http://127.0.0.1:5212';
  const suffix = Date.now().toString(36);
  const roleCode = `cpa_t_${suffix}`;
  const moduleCode = `cpa_m_${suffix}`;
  const resourceCode = `cpa_r_${suffix}`;
  const permissionCode = `function.${resourceCode}.approve`;
  const decisionCode = permissionCode.replace('function.', 'permission_');
  const adminToken = await loginBackend(request);

  await backendPost<unknown>(request, adminToken, '/api/decision/definitions', {
    decisionCode,
    decisionName: `Permission Tamper Guard ${suffix}`,
    description: 'Permission ABAC tamper rejection E2E fixture',
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
  const form = await openAtomicPolicyDialog(page, role, permissionCode);
  await fillValidDynamicAbacBinding(page, form);

  const savePath = `/api/permissions/matrix/${role.pid}/policy/${permission.pid}`;
  let tamperedSourcePath: string | undefined;
  await page.route(`**${savePath}`, async (route) => {
    if (route.request().method().toUpperCase() !== 'PUT') {
      await route.continue();
      return;
    }
    const raw = route.request().postData() ?? '{}';
    const body = JSON.parse(raw) as Record<string, any>;
    const inputMappings =
      body.dynamicAbac?.ruleBinding?.decisionBinding?.inputMappings;
    expect(Array.isArray(inputMappings), 'UI save payload should contain rule binding input mappings').toBe(
      true,
    );
    inputMappings[0] = {
      ...inputMappings[0],
      input: 'secret',
      source: {
        kind: 'FIELD',
        scope: 'record',
        path: 'data.secret',
      },
    };
    tamperedSourcePath = inputMappings[0].source.path;
    await route.continue({
      headers: {
        ...route.request().headers(),
        'content-type': 'application/json',
      },
      postData: JSON.stringify(body),
    });
  });

  const saveResponse = page.waitForResponse(
    (r) =>
      r.url().includes(savePath) &&
      r.request().method().toUpperCase() === 'PUT',
    { timeout: 15_000 },
  );
  await page.getByTestId('policy-save-button').click();
  const save = await saveResponse;
  expect(tamperedSourcePath).toBe('data.secret');
  expect(save.status(), await save.text()).toBe(400);
  await expect(form).toBeVisible({ timeout: 10_000 });
  await expect(page.getByText('策略配置保存失败')).toBeVisible({ timeout: 10_000 });
  await page.screenshot({
    path: testInfo.outputPath('permission-abac-rule-center-tamper-rejected.png'),
    fullPage: true,
  });
  await page.screenshot({
    path: `${EVIDENCE_DIR}/2026-07-13-permission-abac-rule-center-tamper-rejected.png`,
    fullPage: true,
  });

  const storedPolicy = await readStoredPolicy(rolePermissionPid);
  expect(storedPolicy).toBeFalsy();
});

test('Low-permission browser fact catalog hides forbidden fields and keeps view-only inputs selectable @golden', async ({
  page,
  request,
  baseURL,
}, testInfo) => {
  test.setTimeout(120_000);
  const resolvedBaseURL = baseURL ?? 'http://127.0.0.1:5212';
  const suffix = Date.now().toString(36);
  const policyRoleCode = `cplp_policy_${suffix}`;
  const lowFieldRoleCode = `cplp_low_${suffix}`;
  const fullFieldRoleCode = `cplp_full_${suffix}`;
  const lowUserEmail = `codex.perm.low.${suffix}@example.com`;
  const lowUserPassword = 'Test2026x';
  const moduleCode = `cplp_module_${suffix}`;
  const resourceCode = `cplp_resource_${suffix}`;
  const permissionCode = `function.${resourceCode}.approve`;
  const decisionCode = permissionCode.replace('function.', 'permission_');
  const adminToken = await loginBackend(request);
  const snapshots: FieldExtensionSnapshot[] = [];

  await backendPost<unknown>(request, adminToken, '/api/decision/definitions', {
    decisionCode,
    decisionName: `Permission Low Catalog Guard ${suffix}`,
    description: 'Permission ABAC low-permission browser E2E fixture',
    scopeType: 'GOVERNANCE',
    ownerModule: 'permission',
    enabled: true,
  });

  await createStandaloneRole(request, adminToken, {
    roleCode: lowFieldRoleCode,
    name: `Low Field Viewer ${suffix}`,
  });
  await createStandaloneRole(request, adminToken, {
    roleCode: fullFieldRoleCode,
    name: `Full Field Editor ${suffix}`,
  });
  await createUserWithRoles(request, adminToken, {
    email: lowUserEmail,
    displayName: `Low Permission Browser ${suffix}`,
    password: lowUserPassword,
    roleCodes: ['tenant_admin', lowFieldRoleCode],
  });
  const lowUserToken = await loginBackendAs(request, lowUserEmail, lowUserPassword);

  const { role, permission } = await createRoleWithPermission(request, adminToken, {
    roleCode: policyRoleCode,
    moduleCode,
    resourceCode,
    permissionCode,
    decisionCode,
  });
  const rolePermissionPid = await findRolePermissionPid(role.pid, permission.pid);

  const releaseFieldPermissionLock = await acquireFileLock(LEAVE_REQUEST_FIELD_PERMISSION_LOCK);
  try {
    snapshots.push(
      ...(await setFieldPermission('wd_leave_request', 'wd_req_start_date', {
        view: [lowFieldRoleCode],
        edit: [fullFieldRoleCode],
      })),
    );
    snapshots.push(
      ...(await setFieldPermission('wd_leave_request', 'wd_req_type', {
        view: [fullFieldRoleCode],
        edit: [fullFieldRoleCode],
      })),
    );
    await refreshModelCache(request, adminToken, 'wd_leave_request');
    await refreshModelCache(request, lowUserToken, 'wd_leave_request');

    await loginViaBff(page, resolvedBaseURL, {
      email: lowUserEmail,
      password: lowUserPassword,
    });
    await refreshModelCacheFromBrowser(page, 'wd_leave_request');

    const catalog = await readFactCatalogForToken(request, lowUserToken, 'wd_leave_request');
    const facts = flattenFactCatalog(catalog);
    expect(facts.find((fact) => fact.path === 'data.wd_req_type')).toBeUndefined();
    const startDateFact = facts.find((fact) => fact.path === 'data.wd_req_start_date');
    expect(startDateFact).toBeTruthy();
    expect(startDateFact?.editable).toBe(false);
    expect(startDateFact?.masked).not.toBe(true);

    const form = await openAtomicPolicyDialog(page, role, permissionCode);
    await form.getByLabel('version-policy').selectOption('ROLLOUT');
    await form.getByRole('button', { name: '添加映射' }).click();
    await form.getByLabel('mapping-input-0').fill('startDate');

    const fieldPicker = form.getByLabel('mapping-field-0');
    await expect
      .poll(
        async () => {
          const currentOptions = await readSelectOptions(fieldPicker);
          return currentOptions.some(
            (option) =>
              option.value.includes('wd_req_start_date') ||
              option.label.includes('开始日期'),
          );
        },
        {
          message: 'low-permission field picker should load the projected view-only start-date field',
          timeout: 15_000,
        },
      )
      .toBe(true);
    const options = await readSelectOptions(fieldPicker);
    const hiddenTypeOption = options.find(
      (option) =>
        option.value.includes('wd_req_type') ||
        option.label.includes('请假类型'),
    );
    expect(hiddenTypeOption, 'hidden field must not be present in the low-permission browser picker').toBeUndefined();
    const startDateOption = options.find(
      (option) =>
        option.value.includes('wd_req_start_date') ||
        option.label.includes('开始日期'),
    );
    expect(startDateOption).toBeTruthy();
    expect(startDateOption?.label).toContain('开始日期');
    expect(startDateOption?.label).toContain('只读字段');
    expect(startDateOption?.disabled).toBe(false);

    await fieldPicker.selectOption(startDateOption!.value);
    await form.getByLabel('fallback-mode').selectOption('FAIL_CLOSED');
    await page.getByTestId('policy-rule-timeout-dynamicAbac').fill('75');
    await expect(page.getByTestId('decision-binding-preview')).toContainText('startDate', {
      timeout: 10_000,
    });
    await expect(page.getByTestId('decision-binding-preview')).toContainText('开始日期', {
      timeout: 10_000,
    });

    await page.screenshot({
      path: testInfo.outputPath('permission-abac-low-permission-field-picker.png'),
      fullPage: true,
    });
    await page.screenshot({
      path: `${EVIDENCE_DIR}/2026-07-13-permission-abac-low-permission-field-picker.png`,
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
    expect(apiPolicy.dynamicAbac?.ruleBinding?.decisionBinding?.inputMappings?.[0]).toMatchObject({
      input: 'startDate',
      source: { kind: 'FIELD', scope: 'record', path: 'data.wd_req_start_date' },
    });

    const storedPolicy = await readStoredPolicy(rolePermissionPid);
    expect((storedPolicy as any)?.dynamicAbac?.ruleBinding?.decisionBinding?.inputMappings?.[0]).toMatchObject({
      input: 'startDate',
      source: { kind: 'FIELD', scope: 'record', path: 'data.wd_req_start_date' },
    });
  } finally {
    try {
      await restoreFieldExtensions(snapshots);
      await refreshModelCache(request, adminToken, 'wd_leave_request');
      await refreshModelCache(request, lowUserToken, 'wd_leave_request');
      try {
        await refreshModelCacheFromBrowser(page, 'wd_leave_request');
      } catch {
        // The browser may not have reached a logged-in state if setup failed early.
      }
    } finally {
      await releaseFieldPermissionLock();
    }
  }
});

test('Low-permission dynamic record access creates permission audit trace @golden', async ({
  page,
  request,
  baseURL,
}, testInfo) => {
  test.setTimeout(120_000);
  const resolvedBaseURL = baseURL ?? 'http://127.0.0.1:5212';
  const suffix = Date.now().toString(36);
  const roleCode = `cplra_${suffix}`;
  const lowUserEmail = `codex.perm.access.${suffix}@example.com`;
  const lowUserPassword = 'Test2026x';
  const adminToken = await loginBackend(request);
  const permission = await findPermissionByCode('model.wd_leave_request.read');
  const role = await createStandaloneRole(request, adminToken, {
    roleCode,
    name: `Low Record Access ${suffix}`,
  });

  await backendPost<boolean>(request, adminToken, `/api/roles/${role.pid}/permissions`, [permission.pid]);
  const rolePermissionPid = await findRolePermissionPid(role.pid, permission.pid);
  await updateRolePermissionConditionAst(rolePermissionPid, {
    type: 'compare',
    left: {
      type: 'path',
      scope: 'record',
      path: 'data.wd_req_days',
      dataType: 'decimal',
    },
    operator: 'LT',
    right: {
      type: 'literal',
      value: 0,
      dataType: 'decimal',
    },
  });

  await createUserWithRoles(request, adminToken, {
    email: lowUserEmail,
    displayName: `Low Record Access ${suffix}`,
    password: lowUserPassword,
    roleCodes: [roleCode],
  });
  const lowUserToken = await loginBackendAs(request, lowUserEmail, lowUserPassword);
  const member = await findTenantMemberForEmail(lowUserEmail);
  const record = await pickLeaveRequestRecord();
  const sinceIso = new Date(Date.now() - 1_000).toISOString();

  const deniedResponse = await request.get(`${BACKEND_URL}/api/dynamic/wd_leave_request/${record.pid}`, {
    headers: headers(lowUserToken),
  });
  const deniedRaw = await deniedResponse.text();
  let deniedBody: ApiEnvelope<unknown> | null = null;
  try {
    deniedBody = JSON.parse(deniedRaw) as ApiEnvelope<unknown>;
  } catch {
    deniedBody = null;
  }
  expect(
    !deniedResponse.ok() ||
      String(deniedBody?.code ?? '0') !== '0' ||
      deniedBody?.success === false,
    `low-permission dynamic get must be denied: HTTP ${deniedResponse.status()} ${deniedRaw}`,
  ).toBe(true);
  expect(deniedRaw).toContain('Access denied');

  await expect
    .poll(
      async () =>
        Boolean(
          await waitForPermissionAuditRow({
            tenantId: member.tenantId,
            memberId: member.memberId,
            resourceCode: 'wd_leave_request',
            actionCode: 'read',
            recordPid: record.pid,
            sinceIso,
          }),
        ),
      {
        message: 'low-permission dynamic record deny should persist permission audit row',
        timeout: 20_000,
      },
    )
    .toBe(true);
  const audit = await waitForPermissionAuditRow({
    tenantId: member.tenantId,
    memberId: member.memberId,
    resourceCode: 'wd_leave_request',
    actionCode: 'read',
    recordPid: record.pid,
    sinceIso,
  });
  expect(audit).toBeTruthy();
  expect(audit!.reason).toContain('Condition guard not satisfied');

  await loginViaBff(page, resolvedBaseURL);
  await page.goto('/enterprise/permissions', { waitUntil: 'domcontentloaded' });
  await expect(page.getByTestId('permission-page')).toBeVisible({ timeout: 15_000 });
  await openPermissionAuditTab(page);
  await page.getByTestId('permission-audit-resource-filter').fill('wd_leave_request');
  await page.getByTestId('permission-audit-member-filter').fill(String(member.memberId));
  await expect(page.getByTestId('permission-audit-resource-filter')).toHaveValue('wd_leave_request');
  await expect(page.getByTestId('permission-audit-member-filter')).toHaveValue(String(member.memberId));

  const filteredAuditLoad = page.waitForResponse(
    (r) =>
      r.url().includes('/api/permissions/audit') &&
      r.url().includes('resourceCode=wd_leave_request') &&
      r.url().includes(`memberId=${member.memberId}`) &&
      r.status() === 200,
    { timeout: 15_000 },
  );
  await page.getByTestId('permission-audit-refresh').click();
  const filteredAuditResponse = await filteredAuditLoad;
  expect(filteredAuditResponse.ok(), await filteredAuditResponse.text()).toBe(true);

  const row = page.getByTestId(`permission-audit-row-${audit!.id}`);
  await expect(row).toBeVisible({ timeout: 15_000 });
  await expect(row).toContainText('DENY');
  await expect(row).toContainText('wd_leave_request / read');
  await expect(row).toContainText(record.pid);
  await expect(page.getByTestId(`permission-audit-reason-${audit!.id}`)).toContainText(
    'Condition guard not satisfied',
  );
  await expect(page.getByTestId(`permission-audit-trace-${audit!.id}`)).toContainText('RolePermission');
  await expect(page.getByTestId(`permission-audit-trace-${audit!.id}`)).toContainText('Policy');
  await expect(page.getByTestId(`permission-audit-trace-${audit!.id}`)).toContainText('DENY');

  await page.screenshot({
    path: testInfo.outputPath('permission-low-access-real-audit.png'),
    fullPage: true,
  });
  await page.screenshot({
    path: `${EVIDENCE_DIR}/2026-07-14-permission-low-access-real-audit.png`,
    fullPage: true,
  });
});

test('Permission Rule Center applicant reference deny links audit to unified Trace fact metadata @golden', async ({
  page,
  request,
  baseURL,
}, testInfo) => {
  test.setTimeout(120_000);
  const resolvedBaseURL = baseURL ?? 'http://127.0.0.1:5212';
  const suffix = Date.now().toString(36);
  const roleCode = `cprta_${suffix}`;
  const lowUserEmail = `codex.perm.applicant.trace.${suffix}@example.com`;
  const lowUserPassword = 'Test2026x';
  const decisionCode = `permission_applicant_trace_${suffix}`;
  const adminToken = await loginBackend(request);
  const permission = await findPermissionByCode('model.wd_leave_request.read');
  const applicant = await resolveFirstUserForReference(request, adminToken);
  let recordSnapshot: Awaited<ReturnType<typeof pickLeaveRequestRecordWithApplicant>> | null = null;

  await createAndPublishApplicantDecision(request, adminToken, decisionCode, `${applicant.pid}_mismatch`);

  const role = await createStandaloneRole(request, adminToken, {
    roleCode,
    name: `Applicant Trace Reader ${suffix}`,
  });
  await backendPost<boolean>(request, adminToken, `/api/roles/${role.pid}/permissions`, [permission.pid]);
  const rolePermissionPid = await findRolePermissionPid(role.pid, permission.pid);
  await updateRolePermissionConditions(rolePermissionPid, {
    dynamicAbac: {
      expectedMatched: true,
      ruleBinding: {
        consumerType: 'PERMISSION',
        consumerCode: permission.code,
        consumerNodeId: 'dynamicAbac',
        bindingKind: 'DECISION_REF',
        enabled: true,
        decisionBinding: {
          decisionCode,
          versionPolicy: 'LATEST_PUBLISHED',
          inputMappings: [
            {
              input: 'wd_req_applicant',
              source: {
                kind: 'FIELD',
                scope: 'record',
                path: 'data.wd_req_applicant',
              },
            },
          ],
          fallbackPolicy: { mode: 'FAIL_CLOSED' },
          traceMode: 'ALWAYS',
          enabled: true,
        },
      },
    },
  });

  await createUserWithRoles(request, adminToken, {
    email: lowUserEmail,
    displayName: `Applicant Trace Reader ${suffix}`,
    password: lowUserPassword,
    roleCodes: [roleCode],
  });
  const lowUserToken = await loginBackendAs(request, lowUserEmail, lowUserPassword);
  const member = await findTenantMemberForEmail(lowUserEmail);

  try {
    recordSnapshot = await pickLeaveRequestRecordWithApplicant(request, adminToken, applicant.pid, suffix);
    const sinceIso = new Date(Date.now() - 1_000).toISOString();
    const deniedResponse = await request.get(`${BACKEND_URL}/api/dynamic/wd_leave_request/${recordSnapshot.pid}`, {
      headers: headers(lowUserToken),
    });
    const deniedRaw = await deniedResponse.text();
    let deniedBody: ApiEnvelope<unknown> | null = null;
    try {
      deniedBody = JSON.parse(deniedRaw) as ApiEnvelope<unknown>;
    } catch {
      deniedBody = null;
    }
    expect(
      !deniedResponse.ok() ||
        String(deniedBody?.code ?? '0') !== '0' ||
        deniedBody?.success === false,
      `Permission Rule Center applicant decision must deny the low user: HTTP ${deniedResponse.status()} ${deniedRaw}`,
    ).toBe(true);
    expect(deniedRaw).toContain('Access denied');

    await expect
      .poll(
        async () =>
          Boolean(
            await waitForPermissionAuditRow({
              tenantId: member.tenantId,
              memberId: member.memberId,
              resourceCode: 'wd_leave_request',
              actionCode: 'read',
              recordPid: recordSnapshot!.pid,
              sinceIso,
            }),
          ),
        {
          message: 'Permission decisionBinding deny should persist an audit row with ruleTraceId',
          timeout: 20_000,
        },
      )
      .toBe(true);

    const audit = await waitForPermissionAuditRow({
      tenantId: member.tenantId,
      memberId: member.memberId,
      resourceCode: 'wd_leave_request',
      actionCode: 'read',
      recordPid: recordSnapshot.pid,
      sinceIso,
    });
    expect(audit).toBeTruthy();
    expect(audit!.reason).toContain(decisionCode);
    const traceId = extractRuleTraceId(audit!.evaluationTrace);
    expect(traceId, 'Permission audit trace must expose the DecisionOps ruleTraceId').toBeTruthy();

    const decisionTrace = await readDecisionTraceLog(traceId!);
    expect(decisionTrace).toMatchObject({
      decisionCode,
      callerType: 'PERMISSION',
      callerRef: permission.code,
      matched: false,
    });
    expect(decisionTrace.status).toMatch(/NOT_MATCHED|SKIPPED|ERROR/i);
    const applicantMetadata =
      decisionTrace.traceSnapshot.factMetadata?.['record.data.wd_req_applicant'] ??
      decisionTrace.traceSnapshot.factMetadata?.['data.wd_req_applicant'] ??
      decisionTrace.traceSnapshot.factMetadata?.wd_req_applicant;
    expect(applicantMetadata).toMatchObject({
      label: expect.stringMatching(/申请人|Applicant/i),
      modelCode: 'wd_leave_request',
      dataType: expect.stringMatching(/user|reference/i),
    });
    expect(applicantMetadata?.valueLabels?.[applicant.pid]).toContain(applicant.label);

    await loginViaBff(page, resolvedBaseURL);
    await page.goto('/enterprise/permissions', { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('permission-page')).toBeVisible({ timeout: 15_000 });
    await openPermissionAuditTab(page);
    await page.getByTestId('permission-audit-resource-filter').fill('wd_leave_request');
    await page.getByTestId('permission-audit-member-filter').fill(String(member.memberId));
    const filteredAuditLoad = page.waitForResponse(
      (r) =>
        r.url().includes('/api/permissions/audit') &&
        r.url().includes('resourceCode=wd_leave_request') &&
        r.url().includes(`memberId=${member.memberId}`) &&
        r.status() === 200,
      { timeout: 15_000 },
    );
    await page.getByTestId('permission-audit-refresh').click();
    const filteredAuditResponse = await filteredAuditLoad;
    expect(filteredAuditResponse.ok(), await filteredAuditResponse.text()).toBe(true);

    const auditRow = page.getByTestId(`permission-audit-row-${audit!.id}`);
    await expect(auditRow).toBeVisible({ timeout: 15_000 });
    await expect(auditRow).toContainText('DENY');
    await expect(auditRow).toContainText('wd_leave_request / read');
    await expect(auditRow).toContainText(recordSnapshot.pid);
    const auditTrace = page.getByTestId(`permission-audit-trace-${audit!.id}`);
    await expect(auditTrace).toContainText(decisionCode);
    const visibleTraceId = permissionAuditVisibleTraceId(traceId!);
    await expect(auditTrace).toContainText(visibleTraceId);
    if (visibleTraceId !== traceId) {
      await expect(auditTrace).not.toContainText(traceId!);
    }
    await expect(auditTrace).toContainText('record.data.wd_req_applicant');
    const decisionTraceLink = page
      .locator(`[data-testid^="permission-audit-open-decision-trace-${audit!.id}-"]`)
      .first();
    await expect(decisionTraceLink).toHaveAttribute(
      'href',
      `/p/decisionops_execution_logs?traceId=${traceId}`,
    );

    await page.screenshot({
      path: testInfo.outputPath('permission-applicant-reference-runtime-audit.png'),
      fullPage: true,
    });

    await decisionTraceLink.click();
    await expect(page).toHaveURL(new RegExp(`/p/decisionops_execution_logs\\?traceId=${traceId}$`), {
      timeout: 15_000,
    });
    await expect(page.getByTestId('execution-log-trace-block')).toBeVisible({ timeout: 15_000 });
    const decisionLogRow = page.getByTestId(`elta-row-${decisionTrace.pid}`);
    await expect(decisionLogRow).toBeVisible({ timeout: 15_000 });
    await expect(decisionLogRow).toContainText(traceId!);
    await expect(decisionLogRow).toContainText(decisionCode);
    await expect(decisionLogRow).toContainText(/权限|PERMISSION/);
    await page.getByTestId(`elta-open-trace-${decisionTrace.pid}`).click();
    await expect(page.getByTestId('elta-trace-drawer')).toBeVisible({ timeout: 10_000 });
    const factMetadata = page.getByTestId(`elta-fact-metadata-${decisionTrace.pid}`);
    await expect(factMetadata).toBeVisible({ timeout: 10_000 });
    await expect(factMetadata).toContainText('事实快照');
    await expect(factMetadata).toContainText(/申请人|Applicant/i);
    await expect(factMetadata).toContainText('record.data.wd_req_applicant');
    await expect(factMetadata).toContainText('模型 wd_leave_request');
    await expect(factMetadata).toContainText(/类型 (user|reference)/i);
    await expect(factMetadata).toContainText(applicant.pid);
    await expect(factMetadata).toContainText(applicant.label);
    await expect(page.getByTestId('elta-open-permission-audit')).toHaveText('打开权限审计');

    mkdirSync(SYSTEM_REFERENCE_ASSETS_DIR, { recursive: true });
    await page.screenshot({
      path: testInfo.outputPath('permission-applicant-reference-trace-fact-metadata.png'),
      fullPage: true,
    });
    await page.screenshot({
      path: `${SYSTEM_REFERENCE_ASSETS_DIR}/permission-applicant-reference-trace-fact-metadata-20260719.png`,
      fullPage: true,
    });
  } finally {
    if (recordSnapshot) {
      await restoreLeaveRequestApplicant(recordSnapshot);
    }
  }
});

test('Field permission filtering on dynamic detail creates field-governance audit trace @golden', async ({
  page,
  request,
  baseURL,
}, testInfo) => {
  test.setTimeout(120_000);
  const resolvedBaseURL = baseURL ?? 'http://127.0.0.1:5212';
  const suffix = Date.now().toString(36);
  const lowRoleCode = `cpfga_${suffix}`;
  const fullFieldRoleCode = `cpfga_full_${suffix}`;
  const setupAdminEmail = `codex.perm.field.admin.${suffix}@example.com`;
  const setupAdminPassword = 'Test2026x';
  const lowUserEmail = `codex.perm.field.audit.${suffix}@example.com`;
  const lowUserPassword = 'Test2026x';
  const bootstrapToken = await loginBackend(request);
  await createUserWithRoles(request, bootstrapToken, {
    email: setupAdminEmail,
    displayName: `Field Audit Admin ${suffix}`,
    password: setupAdminPassword,
    roleCodes: ['tenant_admin'],
  });
  const adminToken = await loginBackendAs(request, setupAdminEmail, setupAdminPassword);
  const permission = await findPermissionByCode('model.wd_leave_request.read');
  const snapshots: FieldExtensionSnapshot[] = [];

  const role = await createStandaloneRole(request, adminToken, {
    roleCode: lowRoleCode,
    name: `Field Audit Reader ${suffix}`,
  });
  await createStandaloneRole(request, adminToken, {
    roleCode: fullFieldRoleCode,
    name: `Field Audit Full ${suffix}`,
  });
  await backendPost<boolean>(request, adminToken, `/api/roles/${role.pid}/permissions`, [permission.pid]);
  await createUserWithRoles(request, adminToken, {
    email: lowUserEmail,
    displayName: `Field Audit Reader ${suffix}`,
    password: lowUserPassword,
    roleCodes: [lowRoleCode],
  });
  const lowUserToken = await loginBackendAs(request, lowUserEmail, lowUserPassword);
  const member = await findTenantMemberForEmail(lowUserEmail);
  const record = await pickLeaveRequestRecord();

  const releaseFieldPermissionLock = await acquireFileLock(LEAVE_REQUEST_FIELD_PERMISSION_LOCK);
  try {
    snapshots.push(
      ...(await setFieldPermission('wd_leave_request', 'wd_req_type', {
        view: [fullFieldRoleCode],
        edit: [fullFieldRoleCode],
      })),
    );
    await refreshModelCache(request, adminToken, 'wd_leave_request');

    const sinceIso = new Date(Date.now() - 1_000).toISOString();
    const detailResponse = await request.get(`${BACKEND_URL}/api/dynamic/wd_leave_request/${record.pid}`, {
      headers: headers(lowUserToken),
    });
    const detailRaw = await detailResponse.text();
    let detailBody: ApiEnvelope<Record<string, unknown>> | null = null;
    try {
      detailBody = JSON.parse(detailRaw) as ApiEnvelope<Record<string, unknown>>;
    } catch {
      detailBody = null;
    }
    expect(
      detailResponse.ok() && String(detailBody?.code ?? '0') === '0' && detailBody?.success !== false,
      `low-permission dynamic get should succeed with hidden fields filtered: HTTP ${detailResponse.status()} ${detailRaw}`,
    ).toBe(true);
    expect(detailBody?.data?.pid).toBe(record.pid);
    expect(detailBody?.data).not.toHaveProperty('wd_req_type');

    await expect
      .poll(
        async () =>
          Boolean(
            await waitForPermissionAuditRow({
              tenantId: member.tenantId,
              memberId: member.memberId,
              resourceCode: 'wd_leave_request',
              actionCode: 'read',
              recordPid: record.pid,
              sinceIso,
            }),
          ),
        {
          message: 'field-permission filtering should persist a field-governance permission audit row',
          timeout: 20_000,
        },
      )
      .toBe(true);
    const audit = await waitForPermissionAuditRow({
      tenantId: member.tenantId,
      memberId: member.memberId,
      resourceCode: 'wd_leave_request',
      actionCode: 'read',
      recordPid: record.pid,
      sinceIso,
    });
    expect(audit).toBeTruthy();
    expect(audit!.reason).toContain('字段权限拒绝');
    expect(JSON.stringify(audit!.evaluationTrace)).toContain('FieldPermission');
    expect(JSON.stringify(audit!.evaluationTrace)).toContain('record.data.wd_req_type');
    expect(JSON.stringify(audit!.evaluationTrace)).toContain('dynamic-data-field-permission');
    expect(JSON.stringify(audit!.evaluationTrace)).not.toContain('年假');

    await loginViaBff(page, resolvedBaseURL, {
      email: setupAdminEmail,
      password: setupAdminPassword,
    });
    await page.goto('/enterprise/permissions', { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('permission-page')).toBeVisible({ timeout: 15_000 });
    await openPermissionAuditTab(page);
    await page.getByTestId('permission-audit-resource-filter').fill('wd_leave_request');
    await page.getByTestId('permission-audit-member-filter').fill(String(member.memberId));
    await expect(page.getByTestId('permission-audit-resource-filter')).toHaveValue('wd_leave_request');
    await expect(page.getByTestId('permission-audit-member-filter')).toHaveValue(String(member.memberId));

    const filteredAuditLoad = page.waitForResponse(
      (r) =>
        r.url().includes('/api/permissions/audit') &&
        r.url().includes('resourceCode=wd_leave_request') &&
        r.url().includes(`memberId=${member.memberId}`) &&
        r.status() === 200,
      { timeout: 15_000 },
    );
    await page.getByTestId('permission-audit-refresh').click();
    const filteredAuditResponse = await filteredAuditLoad;
    expect(filteredAuditResponse.ok(), await filteredAuditResponse.text()).toBe(true);

    const row = page.getByTestId(`permission-audit-row-${audit!.id}`);
    await expect(row).toBeVisible({ timeout: 15_000 });
    await expect(row).toContainText('DENY');
    await expect(row).toContainText('wd_leave_request / read');
    await expect(row).toContainText(record.pid);
    await expect(page.getByTestId(`permission-audit-reason-${audit!.id}`)).toContainText('字段权限拒绝');
    await expect(page.getByTestId(`permission-audit-trace-step-${audit!.id}-0`)).toContainText('FieldPermission');
    await expect(page.getByTestId(`permission-audit-trace-step-${audit!.id}-0`)).toContainText('DENY');
    await expect(page.getByTestId(`permission-audit-field-governance-${audit!.id}-0`)).toContainText('字段治理');
    await expect(page.getByTestId(`permission-audit-field-governance-${audit!.id}-0`)).toContainText(
      'record.data.wd_req_type',
    );
    await expect(page.getByTestId(`permission-audit-field-governance-${audit!.id}-0`)).toContainText(
      'field-permission-hidden',
    );
    await expect(page.getByTestId(`permission-audit-field-governance-${audit!.id}-0`)).toContainText('DENY');
    await expect(page.getByTestId(`permission-audit-field-governance-${audit!.id}-0`)).toContainText(
      'dynamic-data-field-permission',
    );

    await page.screenshot({
      path: testInfo.outputPath('permission-field-permission-runtime-audit.png'),
      fullPage: true,
    });
    await page.screenshot({
      path: `${EVIDENCE_DIR}/2026-07-17-permission-field-permission-runtime-audit.png`,
      fullPage: true,
    });
  } finally {
    try {
      await restoreFieldExtensions(snapshots);
      await refreshModelCache(request, adminToken, 'wd_leave_request');
      try {
        await refreshModelCacheFromBrowser(page, 'wd_leave_request');
      } catch {
        // The browser may not have reached a logged-in state if setup failed early.
      }
    } finally {
      await releaseFieldPermissionLock();
    }
  }
});

test('Permission audit tab shows public record pid and masked rule trace @golden', async ({
  page,
  baseURL,
}, testInfo) => {
  test.setTimeout(60_000);
  const resolvedBaseURL = baseURL ?? 'http://127.0.0.1:5212';
  const suffix = Date.now().toString(36);
  const audit = await seedPermissionAuditEvidence(suffix);

  await loginViaBff(page, resolvedBaseURL);
  await page.goto('/enterprise/permissions', { waitUntil: 'domcontentloaded' });
  await expect(page.getByTestId('permission-page')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByTestId('role-table')).toBeVisible({ timeout: 15_000 });
  await openPermissionAuditTab(page);

  await page.getByTestId('permission-audit-resource-filter').fill('wd_leave_request');
  await expect(page.getByTestId('permission-audit-resource-filter')).toHaveValue('wd_leave_request');
  const filteredAuditLoad = page.waitForResponse(
    (r) =>
      r.url().includes('/api/permissions/audit') &&
      r.url().includes('resourceCode=wd_leave_request') &&
      r.status() === 200,
    { timeout: 15_000 },
  );
  await page.getByTestId('permission-audit-refresh').click();
  const filteredAuditResponse = await filteredAuditLoad;
  expect(filteredAuditResponse.ok(), await filteredAuditResponse.text()).toBe(true);

  const row = page.getByTestId(`permission-audit-row-${audit.id}`);
  await expect(row).toBeVisible({ timeout: 15_000 });
  await expect(row).toContainText('DENY');
  await expect(row).toContainText('wd_leave_request / view');
  await expect(row).toContainText(audit.recordPid);
  await expect(page.getByTestId(`permission-audit-reason-${audit.id}`)).toContainText(
    `record.data.salary denied with value=*** (${suffix})`,
  );
  await expect(page.getByTestId(`permission-audit-trace-step-${audit.id}-0`)).toContainText('Policy');
  await expect(page.getByTestId(`permission-audit-trace-step-${audit.id}-0`)).toContainText(
    'record.data.salary is not available in permission ABAC fact catalog',
  );
  await expect(page.getByTestId(`permission-audit-field-governance-${audit.id}-0`)).toContainText('字段治理');
  await expect(page.getByTestId(`permission-audit-field-governance-${audit.id}-0`)).toContainText('record.data.salary');
  await expect(page.getByTestId(`permission-audit-field-governance-${audit.id}-0`)).toContainText('masked');
  await expect(page.getByTestId(`permission-audit-field-governance-${audit.id}-0`)).toContainText('DENY');
  await expect(page.getByTestId(`permission-audit-field-governance-${audit.id}-0`)).toContainText('permission-policy-validation');
  await expect(page.getByTestId(`permission-audit-trace-step-${audit.id}-1`)).toContainText('Rule Center');
  await expect(page.getByTestId(`permission-audit-rule-meta-${audit.id}-1`)).toContainText(audit.ruleTraceId);
  await expect(page.getByTestId(`permission-audit-rule-meta-${audit.id}-1`)).toContainText(audit.decisionCode);
  await expect(page.getByTestId(`permission-audit-rule-meta-${audit.id}-1`)).toContainText('PUBLISHED');
  const decisionTraceLink = page.getByTestId(`permission-audit-open-decision-trace-${audit.id}-1`);
  await expect(decisionTraceLink).toContainText('统一 Trace');
  await expect(decisionTraceLink).toHaveAttribute(
    'href',
    `/p/decisionops_execution_logs?traceId=${audit.ruleTraceId}`,
  );
  await expect(page.getByTestId(`permission-audit-permission-context-${audit.id}-1`)).toContainText('权限上下文');
  await expect(page.getByTestId(`permission-audit-permission-context-${audit.id}-1`)).toContainText('severity');
  await expect(page.getByTestId(`permission-audit-permission-context-${audit.id}-1`)).toContainText('decisionMessage');
  await expect(page.getByTestId(`permission-audit-permission-context-${audit.id}-1`)).toContainText(audit.contextMessage);
  await expect(page.getByTestId(`permission-audit-decision-outputs-${audit.id}-1`)).toContainText('DMN 输出');
  await expect(page.getByTestId(`permission-audit-decision-outputs-${audit.id}-1`)).toContainText('actionType');
  await expect(page.getByTestId(`permission-audit-decision-outputs-${audit.id}-1`)).toContainText('notify');
  await expect(page.getByText('9876543210')).toHaveCount(0);
  await expect(page.getByText(audit.secretToken)).toHaveCount(0);

  await page.screenshot({
    path: testInfo.outputPath('permission-audit-tab-masked-trace.png'),
    fullPage: true,
  });
  await page.screenshot({
    path: `${EVIDENCE_DIR}/2026-07-15-permission-audit-tab-dmn-details.png`,
    fullPage: true,
  });
  await page.screenshot({
    path: `${EVIDENCE_DIR}/2026-07-17-permission-audit-field-governance.png`,
    fullPage: true,
  });

  await decisionTraceLink.click();
  await expect(page).toHaveURL(new RegExp(`/p/decisionops_execution_logs\\?traceId=${audit.ruleTraceId}$`), {
    timeout: 15_000,
  });
  await expect(page.getByTestId('execution-log-trace-block')).toBeVisible({ timeout: 15_000 });
  const decisionLogRow = page.getByTestId(`elta-row-${audit.decisionLogPid}`);
  await expect(decisionLogRow).toBeVisible({ timeout: 15_000 });
  await expect(decisionLogRow).toContainText(audit.ruleTraceId);
  await expect(decisionLogRow).toContainText(audit.decisionCode);
  await expect(decisionLogRow).toContainText(/权限|PERMISSION/);
  await page.getByTestId(`elta-open-trace-${audit.decisionLogPid}`).click();
  await expect(page.getByTestId('elta-trace-drawer')).toBeVisible({ timeout: 10_000 });
  const permissionAuditBackLink = page.getByTestId('elta-open-permission-audit');
  await expect(permissionAuditBackLink).toHaveText('打开权限审计');
  const backHref = await permissionAuditBackLink.getAttribute('href');
  expect(backHref).toContain('/enterprise/permissions?');
  expect(backHref).toContain(`traceId=${audit.ruleTraceId}`);
  expect(backHref).toContain('resourceCode=wd_leave_request');
  await page.screenshot({
    path: testInfo.outputPath('permission-audit-to-decisionops-trace-link.png'),
    fullPage: true,
  });
  await page.screenshot({
    path: `${EVIDENCE_DIR}/2026-07-17-permission-audit-to-decisionops-trace-link.png`,
    fullPage: true,
  });
  await permissionAuditBackLink.click();
  await expect(page).toHaveURL(/\/enterprise\/permissions\?.*traceId=/, { timeout: 15_000 });
  await expect(page.getByTestId('permission-audit-tab')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByTestId('permission-audit-trace-filter')).toHaveValue(audit.ruleTraceId);
  await expect(page.getByTestId('permission-audit-resource-filter')).toHaveValue('wd_leave_request');
  await expect(page.getByTestId(`permission-audit-row-${audit.id}`)).toBeVisible({ timeout: 15_000 });
  await page.screenshot({
    path: testInfo.outputPath('decisionops-trace-to-permission-audit-link.png'),
    fullPage: true,
  });
  await page.screenshot({
    path: `${EVIDENCE_DIR}/2026-07-17-decisionops-trace-to-permission-audit-link.png`,
    fullPage: true,
  });
});
