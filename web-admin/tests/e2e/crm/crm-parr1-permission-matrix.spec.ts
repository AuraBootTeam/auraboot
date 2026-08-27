import { expect, test, type Page } from '../../fixtures';

/**
 * PARR1 permission reverse matrix (PAR-03/04/05/10/11 shared debt).
 * Five personas x read/create/update/delete on the CRM core objects, following
 * the PAR-08 contract: allow/deny-403 per cell, denies have no side effects,
 * revoked sessions lose access immediately and recover after re-grant.
 *
 * All persona calls go through the backend directly with persona JWTs (the
 * proven parity-spec pattern); expect() receives real booleans so no truthy
 * string can masquerade as a pass.
 */

const RUN_ID = `parr1-matrix-${Date.now()}`;
const ADMIN_EMAIL = 'admin@auraboot.com';
const ADMIN_PASSWORD = 'Test2026x';
const PERSONA_PASSWORD = 'AuraBoot2026!';
const BACKEND_URL = process.env.BACKEND_URL || 'http://localhost:6461';

interface MatrixApiResult {
  ok: boolean;
  status: number;
  body: any;
  recordId: string;
}

async function loginJwt(email: string, password: string): Promise<string> {
  const resp = await fetch(`${BACKEND_URL}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
  });
  const body: any = await resp.json().catch(() => ({}));
  const jwt: string | undefined = body?.data?.jwt;
  expect(resp.status === 200 && Boolean(jwt), `login ${email}: HTTP ${resp.status} ${JSON.stringify(body).slice(0, 200)}`).toBe(true);
  return jwt as string;
}

async function matrixApi(
  jwt: string,
  path: string,
  method: 'GET' | 'POST' | 'DELETE' = 'GET',
  payload?: unknown,
): Promise<MatrixApiResult> {
  const resp = await fetch(`${BACKEND_URL}${path}`, {
    method,
    headers: { Authorization: `Bearer ${jwt}`, 'Content-Type': 'application/json' },
    body: payload === undefined ? undefined : JSON.stringify(payload),
  });
  const body: any = await resp.json().catch(() => null);
  const recordId: string =
    body?.data?.data?.recordId ?? body?.data?.data?.recordPid ?? body?.data?.data?.pid ?? '';
  return { ok: resp.ok && body?.code === '0', status: resp.status, body, recordId };
}

async function countRows(jwt: string, model: string, label = ''): Promise<number> {
  const result = await matrixApi(jwt, `/api/dynamic/${model}/list?pageNum=1&pageSize=200`);
  expect(result.ok, `[${label}] ${model} list HTTP ${result.status} ${JSON.stringify(result.body).slice(0, 200)}`).toBe(true);
  const data = result.body?.data ?? {};
  return data.total ?? data.records?.length ?? 0;
}

async function createViaCommand(
  jwt: string,
  commandCode: string,
  payload: Record<string, unknown>,
): Promise<MatrixApiResult> {
  return matrixApi(jwt, `/api/meta/commands/execute/${commandCode}`, 'POST', {
    payload,
    operationType: 'create',
  });
}

async function updateViaCommand(
  jwt: string,
  commandCode: string,
  targetPid: string,
  payload: Record<string, unknown>,
): Promise<MatrixApiResult> {
  return matrixApi(jwt, `/api/meta/commands/execute/${commandCode}`, 'POST', {
    payload,
    targetRecordPid: targetPid,
    operationType: 'update',
  });
}

test('PARR1 five-persona permission reverse matrix', async ({ page }) => {
  test.setTimeout(600_000);

  const adminJwt = await loginJwt(ADMIN_EMAIL, ADMIN_PASSWORD);

  async function createCommand(commandCode: string, payload: Record<string, unknown>): Promise<string> {
    const created = await createViaCommand(adminJwt, commandCode, payload);
    expect(created.recordId, `${commandCode}: HTTP ${created.status} ok=${created.ok} ${JSON.stringify(created.body).slice(0, 200)}`).toBeTruthy();
    return created.recordId;
  }

  async function createEmployee(
    email: string,
    name: string,
    deptPid: string,
    positionPid: string,
  ): Promise<{ memberPid: string; userPid: string }> {
    const result = await matrixApi(adminJwt, '/api/org/employees', 'POST', {
      name,
      email,
      phone: `139${Math.floor(10000000 + Math.random() * 89999999)}`,
      deptPid,
      positionPid,
    });
    const data = result.body?.data ?? result.body ?? {};
    const memberPid: string = data.memberPid || data.pid || '';
    expect(result.ok && Boolean(memberPid), `employee ${email}: HTTP ${result.status} ${JSON.stringify(result.body).slice(0, 300)}`).toBe(true);
    return { memberPid, userPid: data.userPid || memberPid };
  }

  async function assignRole(memberPid: string, roleCode: string): Promise<void> {
    const result = await matrixApi(adminJwt, '/api/user-roles/assign-by-code', 'POST', {
      memberPid,
      roleCodes: [roleCode],
    });
    expect(result.ok, `assign ${roleCode}: HTTP ${result.status} ${JSON.stringify(result.body).slice(0, 200)}`).toBe(true);
  }

  // ---- org skeleton ----
  const deptA = await createCommand('org:create_department', {
    org_dept_name: `${RUN_ID} 华东中心`,
    org_dept_order: 10,
    org_dept_status: 'active',
  });
  const deptB = await createCommand('org:create_department', {
    org_dept_name: `${RUN_ID} 华南中心`,
    org_dept_order: 20,
    org_dept_status: 'active',
  });
  const posA = await createCommand('org:create_position', {
    org_pos_code: `${RUN_ID}-A-MGR`,
    org_pos_name: `${RUN_ID} 华东经理岗`,
    org_pos_dept_id: deptA,
    org_pos_level: 'manager',
    org_pos_status: 'active',
  });
  const posAStaff = await createCommand('org:create_position', {
    org_pos_code: `${RUN_ID}-A-REP`,
    org_pos_name: `${RUN_ID} 华东销售岗`,
    org_pos_dept_id: deptA,
    org_pos_level: 'staff',
    org_pos_status: 'active',
  });
  const posBStaff = await createCommand('org:create_position', {
    org_pos_code: `${RUN_ID}-B-REP`,
    org_pos_name: `${RUN_ID} 华南销售岗`,
    org_pos_dept_id: deptB,
    org_pos_level: 'staff',
    org_pos_status: 'active',
  });
  const viewerPos = await createCommand('org:create_position', {
    org_pos_code: `${RUN_ID}-A-VIEW`,
    org_pos_name: `${RUN_ID} 只读岗`,
    org_pos_dept_id: deptA,
    org_pos_level: 'staff',
    org_pos_status: 'active',
  });

  // ---- five personas ----
  const managerEmail = `${RUN_ID}-mgr@e2e.local`;
  const ownerEmail = `${RUN_ID}-owner@e2e.local`;
  const otherEmail = `${RUN_ID}-other@e2e.local`;
  const viewerEmail = `${RUN_ID}-viewer@e2e.local`;

  const manager = await createEmployee(managerEmail, `${RUN_ID} 华东经理`, deptA, posA);
  await assignRole(manager.memberPid, 'crm_sales_manager');
  const owner = await createEmployee(ownerEmail, `${RUN_ID} 华东销售`, deptA, posAStaff);
  await assignRole(owner.memberPid, 'crm_sales');
  const other = await createEmployee(otherEmail, `${RUN_ID} 华南销售`, deptB, posBStaff);
  await assignRole(other.memberPid, 'crm_sales');
  const viewer = await createEmployee(viewerEmail, `${RUN_ID} 只读用户`, deptA, viewerPos);
  await assignRole(viewer.memberPid, 'crm_viewer');

  const managerJwt = await loginJwt(managerEmail, PERSONA_PASSWORD);
  const ownerJwt = await loginJwt(ownerEmail, PERSONA_PASSWORD);
  const otherJwt = await loginJwt(otherEmail, PERSONA_PASSWORD);
  const viewerJwt = await loginJwt(viewerEmail, PERSONA_PASSWORD);

  // ---- fixture owned by the owner persona ----
  const ACCOUNT = `${RUN_ID} 矩阵客户`;
  const owned = await createViaCommand(ownerJwt, 'crm:create_account', {
    crm_acc_name: ACCOUNT,
    crm_acc_industry: 'tech',
    crm_acc_rating: 'A',
  });
  expect(owned.ok, `owner creates account: HTTP ${owned.status} ${JSON.stringify(owned.body).slice(0, 200)}`).toBe(true);
  expect(owned.recordId).toBeTruthy();
  const accountPid = owned.recordId;

  // ---- data-scope shape: only the owner sees the record in list ----
  expect(await countRows(ownerJwt, 'crm_account_common', 'owner-first')).toBeGreaterThanOrEqual(1);
  expect(await countRows(otherJwt, 'crm_account_common', 'other-first'), 'other-dept sales sees none of the owned rows').toBe(0);

  // ---- create cell: viewer denied with no side effect; others allowed ----
  const viewerCountBefore = await countRows(viewerJwt, 'crm_account_common', 'viewer-before');
  const viewerCreate = await createViaCommand(viewerJwt, 'crm:create_account', {
    crm_acc_name: `${RUN_ID} 只读新建`,
    crm_acc_industry: 'tech',
  });
  expect(viewerCreate.ok, 'viewer create denied').toBe(false);
  expect(viewerCreate.status, 'viewer create deny is 403').toBe(403);
  expect(await countRows(viewerJwt, 'crm_account_common', 'viewer-after'), 'viewer rows unchanged after deny').toBe(viewerCountBefore);
  for (const [label, jwt] of [['manager', managerJwt], ['other', otherJwt]] as const) {
    const created = await createViaCommand(jwt, 'crm:create_account', {
      crm_acc_name: `${RUN_ID} ${label} 新建`,
      crm_acc_industry: 'tech',
    });
    expect(created.ok, `${label} create allowed: HTTP ${created.status}`).toBe(true);
  }

  // ---- update cell on the owner's record ----
  for (const [label, jwt] of [['other', otherJwt], ['viewer', viewerJwt]] as const) {
    const denied = await updateViaCommand(jwt, 'crm:update_account', accountPid, {
      crm_acc_name: `${ACCOUNT}-非法改`,
    });
    expect(denied.ok, `${label} update denied`).toBe(false);
    expect(denied.status, `${label} update deny is 403`).toBe(403);
  }
  for (const [label, jwt] of [['manager', managerJwt], ['owner', ownerJwt]] as const) {
    const allowed = await updateViaCommand(jwt, 'crm:update_account', accountPid, {
      crm_acc_remark: `${RUN_ID} ${label} 更新`,
    });
    expect(allowed.ok, `${label} update allowed: HTTP ${allowed.status}`).toBe(true);
  }

  // ---- delete cell ----
  // Observed semantics: managers may delete subordinate-owned accounts; other
  // departments and viewers are denied and the record must survive the denials.
  for (const [label, jwt] of [['other', otherJwt], ['viewer', viewerJwt]] as const) {
    const denied = await updateViaCommand(jwt, 'crm:delete_account', accountPid, {});
    expect(denied.ok, `${label} delete denied`).toBe(false);
    expect(denied.status, `${label} delete deny is 403`).toBe(403);
  }
  const stillThere = await matrixApi(adminJwt, `/api/dynamic/crm_account_common/${accountPid}`);
  expect(stillThere.ok, 'account survives denies').toBe(true);
  const managerThrowaway = await createViaCommand(ownerJwt, 'crm:create_account', {
    crm_acc_name: `${RUN_ID} 经理删除用`,
    crm_acc_industry: 'tech',
  });
  expect(managerThrowaway.ok).toBe(true);
  const managerDelete = await updateViaCommand(managerJwt, 'crm:delete_account', managerThrowaway.recordId, {});
  expect(managerDelete.ok, 'manager delete allowed on subordinate-owned account').toBe(true);
  const throwaway = await createViaCommand(adminJwt, 'crm:create_account', {
    crm_acc_name: `${RUN_ID} 管理员删除用`,
    crm_acc_industry: 'tech',
  });
  expect(throwaway.ok).toBe(true);
  const adminDelete = await updateViaCommand(adminJwt, 'crm:delete_account', throwaway.recordId, {});
  expect(adminDelete.ok, 'admin delete allowed').toBe(true);

  // ---- revoke / restore: owner loses and regains access without re-login ----
  const assignments = await matrixApi(
    adminJwt,
    `/api/user-roles?memberPid=${encodeURIComponent(owner.memberPid)}&pageNum=1&pageSize=100`,
  );
  const assignmentRows: any[] = Array.isArray(assignments.body?.data)
    ? assignments.body.data
    : assignments.body?.data?.records ?? [];
  const assignmentPids = assignmentRows
    .filter((row) => String(row?.status ?? 'active') === 'active')
    .map((row) => String(row?.pid ?? ''))
    .filter(Boolean);
  expect(assignmentPids.length, 'owner has active role assignments').toBeGreaterThan(0);
  const revoke = await matrixApi(adminJwt, '/api/user-roles/batch-remove-by-pid', 'DELETE', assignmentPids);
  expect(revoke.ok, `revoke crm_sales assignments: ${JSON.stringify(revoke.body).slice(0, 200)}`).toBe(true);
  const revokedList = await matrixApi(ownerJwt, '/api/dynamic/crm_account_common/list?pageNum=1&pageSize=200');
  expect(
    revokedList.status === 403 || (revokedList.ok && (revokedList.body?.data?.total ?? 1) === 0),
    `revoked session loses access: HTTP ${revokedList.status}`,
  ).toBe(true);
  const revokedUpdate = await updateViaCommand(ownerJwt, 'crm:update_account', accountPid, {
    crm_acc_remark: `${RUN_ID} 撤权后更新`,
  });
  expect(revokedUpdate.ok, 'revoked owner update denied').toBe(false);
  const restore = await matrixApi(adminJwt, '/api/user-roles/assign-by-code', 'POST', {
    memberPid: owner.memberPid,
    roleCodes: ['crm_sales'],
  });
  expect(restore.ok, `restore crm_sales: ${JSON.stringify(restore.body).slice(0, 200)}`).toBe(true);
  expect(await countRows(ownerJwt, 'crm_account_common', 'owner-first')).toBeGreaterThanOrEqual(1);
});
