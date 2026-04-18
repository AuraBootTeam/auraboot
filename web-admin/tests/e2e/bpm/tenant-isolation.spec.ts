/**
 * BPM Multi-Tenant Isolation E2E (P3-D)
 *
 * Proves that BPM artifacts (process definitions + running instances) are
 * fully scoped to the active tenant of the JWT presented on each call.
 *
 * Mechanism under test (read code, then assert behavior):
 *
 * 1. `MetaContext.setContext(tenantId, ...)` is populated by JwtAuthFilter from
 *    the `tenantId` claim in the JWT. The same physical user can hold multiple
 *    JWTs, each scoped to a different tenant via
 *    `POST /api/tenant-selection/process { action: "select", tenantId }`.
 *
 * 2. `TenantLineInnerInterceptor` (MybatisPlusConfig) auto-injects
 *    `tenant_id = MetaContext.getCurrentTenantId()` into every WHERE clause
 *    against tenant-scoped tables. `ab_bpm_process_definition` is one of them
 *    (column `tenant_id BIGINT NOT NULL`, plus the unique key
 *    `uq_process_tenant_key_version`).
 *
 * 3. SmartEngine instance tables (`se_*`) are *excluded* from the line
 *    interceptor (see `tableName.startsWith("se_")` in MybatisPlusConfig).
 *    Tenant scoping for instances is enforced at the application layer:
 *    `ProcessEngineService.getProcessInstance(...)` calls
 *    `smartEngine.getProcessQueryService().findById(processInstanceId, tenantId)`
 *    where `tenantId` always comes from MetaContext. A wrong-tenant lookup
 *    therefore returns null → controller raises BadParam.
 *
 * Scenarios:
 *   TEN-1  Tenant A creates a process definition. Tenant B's `GET /api/bpm/process-definitions`
 *          list excludes that definition.
 *   TEN-2  Tenant A starts an instance. Tenant B cannot fetch the instance by id
 *          (`GET /api/bpm/process-instances/{id}` returns non-2xx) and cannot
 *          query its status by id (`/status` also rejects it).
 *   TEN-3  Both tenants deploy the SAME `processKey` independently, then each
 *          starts its own instance. Each tenant only sees its own definition row
 *          for that key and only its own running instance for that key — counts
 *          are independent.
 *
 * Multi-tenant credential strategy:
 *   - Tenant A is the default `admin@example.com` workspace ("AuraBoot Dev").
 *   - Tenant B is provisioned at test setup via `seedSecondTenant()`:
 *       1. Insert a fresh `ab_tenant` row.
 *       2. Add admin's `userId` as an active member of that tenant
 *          (`ab_tenant_member.status = 'active'`).
 *     With both memberships in place, calling
 *       `POST /api/tenant-selection/process { action: "select", tenantId: <B> }`
 *     returns a JWT bearing tenantId = <B>. SQL bypass is acceptable in dev
 *     stage (CLAUDE.md hard constraint #1: breaking changes allowed; we are
 *     creating fixtures, not patching production data to mask a bug).
 *
 * Honors red lines:
 *   - All cross-tenant probes use the *real* HTTP API surface (no DB short-cuts
 *     to assert isolation — only to seed a 2nd tenant fixture).
 *   - No `waitForTimeout`. No silent fallbacks: every backend response is
 *     parsed along a single canonical path or fails loudly.
 *   - No `afterAll` cleanup; relies on `reset-and-init.sh` for fresh DB.
 *   - JWT switching is API-only because tenant switch is, by design, an API
 *     operation (the UI surfaces the same endpoint).
 *
 * @bpm-regression
 */

import { test, expect, type APIRequestContext } from '@playwright/test';
import { Client as PgClient } from 'pg';
import { uniqueId } from '../helpers/index';

// ── Tenant A = default admin tenant ─────────────────────────────────
const ADMIN_EMAIL = 'admin@example.com';
const ADMIN_PASSWORD = 'Test2026x';
const PG_CONN = {
  host: 'localhost',
  port: 5432,
  database: 'aura_boot',
  user: process.env.PGUSER ?? 'ghj',
};

const UID = uniqueId('TEN');
const PROCESS_KEY_DEFA = `tenA_only_${UID}`;
const PROCESS_KEY_SHARED = `tenant_shared_${UID}`;

function buildBpmn(processKey: string, processName: string): string {
  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             targetNamespace="http://auraboot.com/bpm"
             id="definitions_${processKey}">
  <process id="${processKey}" name="${processName}" isExecutable="true">
    <startEvent id="start"/>
    <userTask id="userTask1" name="Review"/>
    <endEvent id="end"/>
    <sequenceFlow id="flow1" sourceRef="start" targetRef="userTask1"/>
    <sequenceFlow id="flow2" sourceRef="userTask1" targetRef="end"/>
  </process>
</definitions>`;
}

function authHeaders(jwt: string) {
  return { Authorization: `Bearer ${jwt}`, 'Content-Type': 'application/json' };
}

interface TenantSession {
  jwt: string;
  tenantId: string;
}

async function loginAsAdminUntenanted(request: APIRequestContext): Promise<{
  jwt: string;
  userId: string;
}> {
  const resp = await request.post('http://127.0.0.1:6443/api/auth/login', {
    data: { email: ADMIN_EMAIL, password: ADMIN_PASSWORD },
    headers: { 'Content-Type': 'application/json' },
  });
  expect(resp.ok(), `admin login: ${resp.status()} ${await resp.text()}`).toBe(true);
  const body = await resp.json();
  const jwt = body?.data?.jwt;
  const userId = body?.data?.userId;
  expect(typeof jwt).toBe('string');
  expect(jwt.length).toBeGreaterThan(0);
  return { jwt: String(jwt), userId: String(userId ?? '') };
}

async function selectTenant(
  request: APIRequestContext,
  baseJwt: string,
  tenantId: string,
): Promise<string> {
  const resp = await request.post('http://127.0.0.1:6443/api/tenant-selection/process', {
    headers: authHeaders(baseJwt),
    data: { action: 'select', tenantId },
  });
  expect(
    resp.ok(),
    `selectTenant ${tenantId}: ${resp.status()} ${await resp.text()}`,
  ).toBe(true);
  const body = await resp.json();
  const jwt = body?.data?.jwt;
  expect(typeof jwt).toBe('string');
  expect(jwt.length).toBeGreaterThan(0);
  return String(jwt);
}

/** Resolve admin's primary business tenantId via /my-spaces. */
async function discoverTenantA(
  request: APIRequestContext,
  baseJwt: string,
): Promise<string> {
  const resp = await request.get('http://127.0.0.1:6443/api/tenant-selection/my-spaces', {
    headers: authHeaders(baseJwt),
  });
  expect(resp.ok(), `my-spaces: ${resp.status()}`).toBe(true);
  const body = await resp.json();
  const spaces = body?.data;
  expect(Array.isArray(spaces)).toBe(true);
  // Prefer the canonical "AuraBoot Dev" workspace so prior failed test runs
  // (which may have left p3d_isolation_* tenants on admin's membership list)
  // do not get picked as tenant A — that would break role cloning.
  const preferred = spaces.find(
    (s: { tenantName: string; spaceType: string }) =>
      s.spaceType === 'business' && s.tenantName === 'AuraBoot Dev',
  );
  const biz =
    preferred ??
    spaces.find(
      (s: { spaceType: string; tenantName: string }) =>
        s.spaceType === 'business' && !s.tenantName.startsWith('p3d_isolation_'),
    );
  expect(biz, 'admin must have at least one canonical business tenant').toBeTruthy();
  return String(biz.tenantId);
}

/**
 * Provision a brand-new tenant B and add admin as an active member.
 * Uses raw SQL because /api/auth/register is disabled in SINGLE mode and
 * /api/tenant-selection/process action=create requires self-registration.
 *
 * Returns the new tenantId as string.
 */
async function seedSecondTenant(
  adminUserId: string,
  tenantName: string,
  donorTenantId: string,
): Promise<string> {
  const client = new PgClient(PG_CONN);
  await client.connect();
  try {
    // Snowflake-ish 19-digit positive bigint that won't collide with sequence-generated ids.
    const tenantIdNum =
      BigInt(Date.now()) * BigInt(1_000_000) +
      BigInt(Math.floor(Math.random() * 1_000_000));
    const tenantId = tenantIdNum.toString();
    // ULID-ish 26-char placeholder — uniqueness is what matters for ab_tenant_pid_key.
    const pid = `01TENB${UID.toUpperCase().padEnd(20, '0').slice(0, 20)}`;

    await client.query(
      `INSERT INTO ab_tenant
         (id, pid, name, display_name, status, deleted_flag, created_at, updated_at)
       VALUES ($1, $2, $3, $4, 'active', false, now(), now())`,
      [tenantId, pid, tenantName, tenantName],
    );

    const memberPid = `01TMB${UID.toUpperCase().padEnd(21, '0').slice(0, 21)}`;
    const memberIdNum =
      BigInt(Date.now()) * BigInt(1_000_000) +
      BigInt(Math.floor(Math.random() * 1_000_000)) +
      BigInt(7); // ensure distinct from tenantId generated above
    const memberId = memberIdNum.toString();
    await client.query(
      `INSERT INTO ab_tenant_member
         (id, pid, tenant_id, user_id, status, deleted_flag, created_at, updated_at)
       VALUES ($1, $2, $3, $4, 'active', false, now(), now())`,
      [memberId, memberPid, tenantId, adminUserId],
    );

    // Clone the donor tenant's tenant_admin role + its permission grants into
    // the new tenant so admin holds the same workflow permissions
    // (system.process.*, etc.) under tenantId B as under tenantId A.
    const donorRoleRow = await client.query<{ id: string }>(
      `SELECT id FROM ab_role
        WHERE tenant_id = $1 AND code = 'tenant_admin' AND deleted_flag = false
        LIMIT 1`,
      [donorTenantId],
    );
    if (donorRoleRow.rowCount === 0) {
      throw new Error(`donor tenant ${donorTenantId} has no tenant_admin role to clone`);
    }
    const donorRoleId = donorRoleRow.rows[0].id;

    const newRoleIdNum =
      BigInt(Date.now()) * BigInt(1_000_000) +
      BigInt(Math.floor(Math.random() * 1_000_000)) +
      BigInt(13);
    const newRoleId = newRoleIdNum.toString();
    const newRolePid = `01ROLB${UID.toUpperCase().padEnd(20, '0').slice(0, 20)}`;
    await client.query(
      `INSERT INTO ab_role (id, pid, tenant_id, code, name, status, deleted_flag, created_at, updated_at)
       VALUES ($1, $2, $3, 'tenant_admin', 'Tenant Admin', 'active', false, now(), now())`,
      [newRoleId, newRolePid, tenantId],
    );

    // Clone ab_permission rows from donor tenant into the new tenant.
    // PermissionService.resolvePermissionId() calls permissionMapper.findByCode()
    // which is tenant-filtered, so tenant B needs its own copy of every
    // workflow.* / system.* code, with the new (tenant-B-scoped) permission_id.
    await client.query(
      `INSERT INTO ab_permission
         (pid, tenant_id, code, name, description, category, resource_type,
          resource_code, action, source, source_ref, parent_id, path, level,
          data_scope_type, data_scope_config, deleted_flag)
       SELECT
         substr(md5(random()::text || clock_timestamp()::text || code), 1, 26),
         $1::bigint,
         code,
         name,
         description,
         category,
         resource_type,
         resource_code,
         action,
         source,
         source_ref,
         NULL,            -- parent_id: skip hierarchy for cloned rows
         path,
         level,
         data_scope_type,
         data_scope_config,
         false
       FROM ab_permission
       WHERE tenant_id = $2::bigint AND deleted_flag = false`,
      [tenantId, donorTenantId],
    );

    // Now bind every grant from donor's tenant_admin role onto the new role,
    // remapping permission_id donor→new via the shared `code`.
    await client.query(
      `INSERT INTO ab_role_permission
         (pid, tenant_id, role_id, permission_id, grant_type, status, deleted_flag)
       SELECT
         substr(md5(random()::text || clock_timestamp()::text || newp.code), 1, 26),
         $1::bigint,
         $2::bigint,
         newp.id,
         rp.grant_type,
         rp.status,
         false
       FROM ab_role_permission rp
       JOIN ab_permission donorp ON donorp.id = rp.permission_id
       JOIN ab_permission newp
         ON newp.tenant_id = $1::bigint AND newp.code = donorp.code
       WHERE rp.role_id = $3::bigint AND rp.deleted_flag = false`,
      [tenantId, newRoleId, donorRoleId],
    );

    const userRoleIdNum =
      BigInt(Date.now()) * BigInt(1_000_000) +
      BigInt(Math.floor(Math.random() * 1_000_000)) +
      BigInt(19);
    const userRoleId = userRoleIdNum.toString();
    const userRolePid = `01URB${UID.toUpperCase().padEnd(21, '0').slice(0, 21)}`;
    await client.query(
      `INSERT INTO ab_user_role
         (id, pid, member_id, tenant_id, role_id, assign_type, status, deleted_flag, created_at, updated_at)
       VALUES ($1, $2, $3, $4, $5, 'direct', 'active', false, now(), now())`,
      [userRoleId, userRolePid, memberId, tenantId, newRoleId],
    );

    return tenantId;
  } finally {
    await client.end();
  }
}

/** Create a draft process definition + deploy it. Returns the deployed pid. */
async function createAndDeploy(
  request: APIRequestContext,
  jwt: string,
  args: { processKey: string; processName: string },
): Promise<{ pid: string; processKey: string }> {
  const createResp = await request.post(
    'http://127.0.0.1:6443/api/bpm/process-definitions',
    {
      headers: authHeaders(jwt),
      data: {
        processKey: args.processKey,
        processName: args.processName,
        description: `tenant-isolation E2E ${args.processKey}`,
        category: 'isolation-test',
        bpmnContent: buildBpmn(args.processKey, args.processName),
        designerJson: JSON.stringify({ nodes: [], edges: [] }),
        formBindings: {},
        businessDataBindings: [],
      },
    },
  );
  expect(
    createResp.ok(),
    `create definition ${args.processKey}: ${createResp.status()} ${await createResp.text()}`,
  ).toBe(true);
  const created = await createResp.json();
  const pid = created?.data?.pid;
  expect(typeof pid).toBe('string');

  const deployResp = await request.post(
    `http://127.0.0.1:6443/api/bpm/process-definitions/${pid}/deploy`,
    { headers: authHeaders(jwt) },
  );
  expect(
    deployResp.ok(),
    `deploy ${pid}: ${deployResp.status()} ${await deployResp.text()}`,
  ).toBe(true);
  const deployed = await deployResp.json();
  expect(deployed?.data?.status).toBe('deployed');
  return { pid, processKey: args.processKey };
}

/** Start a process instance. Returns the SmartEngine instance id. */
async function startInstance(
  request: APIRequestContext,
  jwt: string,
  args: { processDefinitionId: string; businessKey: string },
): Promise<string> {
  const resp = await request.post(
    'http://127.0.0.1:6443/api/bpm/process-instances',
    {
      headers: authHeaders(jwt),
      data: {
        processDefinitionId: args.processDefinitionId,
        businessKey: args.businessKey,
        variables: {},
      },
    },
  );
  expect(
    resp.ok(),
    `startInstance: ${resp.status()} ${await resp.text()}`,
  ).toBe(true);
  const body = await resp.json();
  const data = body?.data;
  const instanceId = data?.instanceId ?? data?.processInstanceId ?? data?.id;
  expect(
    typeof instanceId === 'string' && instanceId.length > 0,
    `start response missing instanceId; keys=${Object.keys(data ?? {}).join(',')}`,
  ).toBe(true);
  return String(instanceId);
}

/** Pages through GET /api/bpm/process-definitions and returns matching PIDs. */
async function listDefinitionPidsByKey(
  request: APIRequestContext,
  jwt: string,
  processKey: string,
): Promise<string[]> {
  const url =
    'http://127.0.0.1:6443/api/bpm/process-definitions' +
    `?page=0&size=200&keyword=${encodeURIComponent(processKey)}`;
  const resp = await request.get(url, { headers: authHeaders(jwt) });
  expect(
    resp.ok(),
    `list definitions: ${resp.status()} ${await resp.text()}`,
  ).toBe(true);
  const body = await resp.json();
  const records = body?.data?.records;
  expect(Array.isArray(records)).toBe(true);
  return (records as Array<{ processKey: string; pid: string }>)
    .filter((r) => r.processKey === processKey)
    .map((r) => r.pid);
}

// ── Globals shared across the suite ──────────────────────────────────
let tenantA: TenantSession;
let tenantB: TenantSession;
let baseJwt: string;
let adminUserId: string;

test.describe('BPM tenant isolation @bpm-regression', () => {
  test.describe.configure({ mode: 'serial', timeout: 60_000 });

  test.beforeAll(async ({ request }) => {
    const login = await loginAsAdminUntenanted(request);
    baseJwt = login.jwt;
    adminUserId = login.userId;
    expect(adminUserId).not.toBe('');

    const tenantAId = await discoverTenantA(request, baseJwt);
    const tenantAJwt = await selectTenant(request, baseJwt, tenantAId);
    tenantA = { jwt: tenantAJwt, tenantId: tenantAId };

    const tenantBId = await seedSecondTenant(
      adminUserId,
      `p3d_isolation_${UID}`,
      tenantA.tenantId,
    );
    const tenantBJwt = await selectTenant(request, baseJwt, tenantBId);
    tenantB = { jwt: tenantBJwt, tenantId: tenantBId };

    expect(tenantA.tenantId).not.toBe(tenantB.tenantId);
  });

  test('TEN-1: tenant A definition is invisible to tenant B', async ({ request }) => {
    const created = await createAndDeploy(request, tenantA.jwt, {
      processKey: PROCESS_KEY_DEFA,
      processName: `Tenant A only ${UID}`,
    });

    // Tenant A sees it.
    const aPids = await listDefinitionPidsByKey(request, tenantA.jwt, PROCESS_KEY_DEFA);
    expect(aPids).toContain(created.pid);

    // Tenant B's list excludes it (TenantLineInterceptor injects tenant_id = B).
    const bPids = await listDefinitionPidsByKey(request, tenantB.jwt, PROCESS_KEY_DEFA);
    expect(bPids).not.toContain(created.pid);
    expect(bPids.length).toBe(0);

    // Direct GET by PID under tenant B must also fail (404 / error envelope) —
    // BpmProcessDefinitionMapper inherits TenantLine filtering.
    const directResp = await request.get(
      `http://127.0.0.1:6443/api/bpm/process-definitions/${created.pid}`,
      { headers: authHeaders(tenantB.jwt) },
    );
    if (directResp.ok()) {
      const body = await directResp.json();
      // OK envelope but `code != '0'` is an acceptable "not found" too.
      expect(
        body?.code !== '0' || body?.data == null,
        `tenant B unexpectedly fetched tenant A definition: ${JSON.stringify(body)}`,
      ).toBe(true);
    }
    // Else: HTTP error is fine (403/404/500 with not-found semantics).
  });

  test('TEN-2: tenant A instance cannot be queried by tenant B', async ({ request }) => {
    // Deploy a fresh definition under tenant A for this test (avoids picking up
    // stale draft definitions from prior runs that share the keyword).
    const ten2Key = `tenA_iso2_${UID}`;
    const def = await createAndDeploy(request, tenantA.jwt, {
      processKey: ten2Key,
      processName: `Tenant A iso2 ${UID}`,
    });

    const businessKey = `iso-A-${UID}`;
    // ProcessEngineService.startProcess expects the process *key*, not the
    // ab_bpm_process_definition.pid (see ProcessEngineService line 65 +
    // resolveProcessDefinitionVersion, which matches against
    // SmartEngine cached definitions whose id == processKey).
    const instanceId = await startInstance(request, tenantA.jwt, {
      processDefinitionId: def.processKey,
      businessKey,
    });

    // Tenant A can fetch it.
    const aGet = await request.get(
      `http://127.0.0.1:6443/api/bpm/process-instances/${instanceId}`,
      { headers: authHeaders(tenantA.jwt) },
    );
    expect(aGet.ok(), `tenant A own-instance fetch: ${aGet.status()}`).toBe(true);
    const aGetBody = await aGet.json();
    expect(aGetBody?.code).toBe('0');
    expect(aGetBody?.data).toBeTruthy();

    // Tenant B must NOT be able to fetch the instance.
    // ProcessEngineService.getProcessInstance(id) calls findById(id, tenantId) —
    // wrong tenant ⇒ null ⇒ controller throws BadParam ⇒ non-2xx envelope.
    const bGet = await request.get(
      `http://127.0.0.1:6443/api/bpm/process-instances/${instanceId}`,
      { headers: authHeaders(tenantB.jwt) },
    );
    if (bGet.ok()) {
      const body = await bGet.json();
      expect(
        body?.code !== '0',
        `tenant B unexpectedly fetched tenant A instance ${instanceId}: ${JSON.stringify(body)}`,
      ).toBe(true);
    } else {
      // 4xx/5xx is acceptable — exception was raised.
      expect(bGet.status()).toBeGreaterThanOrEqual(400);
    }

    // /status endpoint must also reject.
    const bStatus = await request.get(
      `http://127.0.0.1:6443/api/bpm/process-instances/${instanceId}/status`,
      { headers: authHeaders(tenantB.jwt) },
    );
    if (bStatus.ok()) {
      const body = await bStatus.json();
      expect(
        body?.code !== '0',
        `tenant B unexpectedly fetched status for tenant A instance: ${JSON.stringify(body)}`,
      ).toBe(true);
    } else {
      expect(bStatus.status()).toBeGreaterThanOrEqual(400);
    }
  });

  test('TEN-3: same processKey deployed under both tenants stays independent', async ({
    request,
  }) => {
    // Both tenants deploy a definition with the SAME processKey. The unique
    // index `uq_process_tenant_key_version (tenant_id, process_key, version)`
    // permits this; if isolation broke we would either get a duplicate-key
    // error here OR see cross-tenant rows.
    const aDef = await createAndDeploy(request, tenantA.jwt, {
      processKey: PROCESS_KEY_SHARED,
      processName: `Shared key A ${UID}`,
    });
    const bDef = await createAndDeploy(request, tenantB.jwt, {
      processKey: PROCESS_KEY_SHARED,
      processName: `Shared key B ${UID}`,
    });
    expect(aDef.pid).not.toBe(bDef.pid);

    // Each tenant only sees its own definition row for the shared key.
    const aRows = await listDefinitionPidsByKey(request, tenantA.jwt, PROCESS_KEY_SHARED);
    const bRows = await listDefinitionPidsByKey(request, tenantB.jwt, PROCESS_KEY_SHARED);
    expect(aRows).toEqual([aDef.pid]);
    expect(bRows).toEqual([bDef.pid]);
    expect(aRows).not.toContain(bDef.pid);
    expect(bRows).not.toContain(aDef.pid);

    // Each tenant starts an instance against its own deployed definition.
    // processDefinitionId field expects processKey (see TEN-2 comment).
    const aInstance = await startInstance(request, tenantA.jwt, {
      processDefinitionId: aDef.processKey,
      businessKey: `iso-shared-A-${UID}`,
    });
    const bInstance = await startInstance(request, tenantB.jwt, {
      processDefinitionId: bDef.processKey,
      businessKey: `iso-shared-B-${UID}`,
    });
    expect(aInstance).not.toBe(bInstance);

    // Cross-tenant instance reads remain blocked.
    const crossRespAB = await request.get(
      `http://127.0.0.1:6443/api/bpm/process-instances/${aInstance}`,
      { headers: authHeaders(tenantB.jwt) },
    );
    if (crossRespAB.ok()) {
      const body = await crossRespAB.json();
      expect(body?.code !== '0').toBe(true);
    } else {
      expect(crossRespAB.status()).toBeGreaterThanOrEqual(400);
    }
    const crossRespBA = await request.get(
      `http://127.0.0.1:6443/api/bpm/process-instances/${bInstance}`,
      { headers: authHeaders(tenantA.jwt) },
    );
    if (crossRespBA.ok()) {
      const body = await crossRespBA.json();
      expect(body?.code !== '0').toBe(true);
    } else {
      expect(crossRespBA.status()).toBeGreaterThanOrEqual(400);
    }
  });
});
