/**
 * BPM Showcase S5+S6 — 网关全家桶(并行/包容) + 规则驱动路由(Drools).
 *
 * Scenario SOT: workspace docs/plans/2026-08-28-oss-bpm-showcase-e2e-verification-plan.md §4 S5/S6.
 *
 * S5 网关 (互斥 covered by S1):
 * - S5.1  并行网关 fork → 两个审批(bob/carol)→ join: 双待办并存, 一人完成后
 *         实例仍挂起, 双双完成后实例到达 end。
 * - S5.2  包容网关: 金额大 → 法务+财务两分支同时激活(join 等待); 金额中等 →
 *         仅财务分支。条件经 UI 无关的 API 发起后由网关表达式判定。
 *
 * S6 规则驱动 (Drools): 复用 workflow-demo 种子的 wd_leave_approval
 * (svc_rule_route rule-task + Drools wd_leave_routing + 互斥网关按 approverRole
 * 分流)。days<3 → manager 组, days>=3 → hr 组; 断言分支 todo + 流程状态页的
 * 规则命中 trace(UI 证据, bpm-rule-trace testids)。
 * 完整 UI 表单发起链路见 workflow-demo-leave-flow.spec.ts B5.1(不在此重复)。
 */

import { test, expect, type APIRequestContext } from '../../fixtures';
import {
  loginAsAdmin,
  startProcessInstance,
  queryInstanceStatus,
  waitForTodoTask,
  listTodoTasks,
  undeployProcess,
} from '../bpm/_helpers/bpm-lifecycle';
import {
  SHOWCASE_ROLE_CODE,
  SHOWCASE_USERS,
  ensureShowcaseRole,
  ensureShowcaseUser,
  loginJwt,
  deployProcess,
  openUserSession,
  evidenceShot,
} from './_helpers/showcase';

const S5_KEY = `sc5_gateways_${Date.now()}`;
const WD_KEY = 'wd_leave_approval';

let adminToken = '';
let bobToken = '';
let carolToken = '';
let daveToken = '';
let managerToken = '';
let hrToken = '';
let s5Deployed = false;
const pids: Record<string, string> = {};

async function resolveUserPid(request: APIRequestContext, email: string): Promise<string> {
  const resp = await request.get(
    `/api/admin/users/search?keyword=${encodeURIComponent(email)}`,
    { headers: { Authorization: `Bearer ${adminToken}` } },
  );
  expect(resp.ok(), `user search ${email}: ${resp.status()}`).toBe(true);
  const body = await resp.json();
  const hit = ((body?.data ?? []) as Array<Record<string, unknown>>).find((u) => u.email === email);
  return String(hit?.pid ?? '');
}

function parallelGatewayGraph(bobPid: string, carolPid: string) {
  return {
    nodes: [
      { id: 'start', type: 'startEvent', position: { x: 80, y: 220 }, data: { type: 'startEvent', label: '开始' } },
      { id: 'fork', type: 'parallelGateway', position: { x: 250, y: 220 }, data: { type: 'parallelGateway', label: '并行分派' } },
      {
        id: 'legal_review', type: 'userTask', position: { x: 430, y: 120 },
        data: { type: 'userTask', label: '法务复核', config: { assignee: { type: 'user', userIds: [bobPid] } } },
      },
      {
        id: 'finance_review', type: 'userTask', position: { x: 430, y: 320 },
        data: { type: 'userTask', label: '财务复核', config: { assignee: { type: 'user', userIds: [carolPid] } } },
      },
      { id: 'join', type: 'parallelGateway', position: { x: 610, y: 220 }, data: { type: 'parallelGateway', label: '汇合' } },
      { id: 'end', type: 'endEvent', position: { x: 760, y: 220 }, data: { type: 'endEvent', label: '结束' } },
    ],
    edges: [
      { id: 'e1', source: 'start', target: 'fork', type: 'smoothstep', data: {} },
      { id: 'e2', source: 'fork', target: 'legal_review', type: 'smoothstep', data: {} },
      { id: 'e3', source: 'fork', target: 'finance_review', type: 'smoothstep', data: {} },
      { id: 'e4', source: 'legal_review', target: 'join', type: 'smoothstep', data: {} },
      { id: 'e5', source: 'finance_review', target: 'join', type: 'smoothstep', data: {} },
      { id: 'e6', source: 'join', target: 'end', type: 'smoothstep', data: {} },
    ],
  };
}

function inclusiveGatewayGraph(bobPid: string, carolPid: string, davePid: string) {
  return {
    nodes: [
      { id: 'start', type: 'startEvent', position: { x: 80, y: 220 }, data: { type: 'startEvent', label: '开始' } },
      { id: 'gw_incl', type: 'inclusiveGateway', position: { x: 250, y: 220 }, data: { type: 'inclusiveGateway', label: '范围判定' } },
      {
        id: 'legal_review', type: 'userTask', position: { x: 460, y: 100 },
        data: { type: 'userTask', label: '法务复核', config: { assignee: { type: 'user', userIds: [bobPid] } } },
      },
      {
        id: 'finance_review', type: 'userTask', position: { x: 460, y: 340 },
        data: { type: 'userTask', label: '财务复核', config: { assignee: { type: 'user', userIds: [davePid] } } },
      },
      { id: 'join', type: 'inclusiveGateway', position: { x: 650, y: 220 }, data: { type: 'inclusiveGateway', label: '汇合' } },
      { id: 'end', type: 'endEvent', position: { x: 800, y: 220 }, data: { type: 'endEvent', label: '结束' } },
    ],
    edges: [
      { id: 'e1', source: 'start', target: 'gw_incl', type: 'smoothstep', data: {} },
      {
        id: 'e_legal', source: 'gw_incl', target: 'legal_review', type: 'conditional',
        data: { label: '大额', condition: { type: 'expression', content: '${amount >= 10000}' } },
      },
      {
        id: 'e_finance', source: 'gw_incl', target: 'finance_review', type: 'conditional',
        data: { label: '需财务', condition: { type: 'expression', content: '${needFinance == true}' } },
      },
      { id: 'e4', source: 'legal_review', target: 'join', type: 'smoothstep', data: {} },
      { id: 'e5', source: 'finance_review', target: 'join', type: 'smoothstep', data: {} },
      { id: 'e6', source: 'join', target: 'end', type: 'smoothstep', data: {} },
    ],
  };
}

async function completeViaApproveApi(
  request: APIRequestContext,
  token: string,
  instanceId: string,
  activityId: string,
): Promise<void> {
  const task = await waitForTodoTask(
    request,
    token,
    (t) => t.processInstanceId === instanceId && t.processDefinitionActivityId === activityId,
    { timeout: 20_000, message: `todo ${activityId} for ${instanceId}` },
  );
  const resp = await request.post(`/api/bpm/tasks/${task.taskId}/approve`, {
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    data: { comment: `showcase approve ${activityId}` },
  });
  expect(resp.ok(), `approve ${activityId}: ${resp.status()} ${await resp.text()}`).toBe(true);
}

/**
 * Provision a user directly into arbitrary existing role codes (used for the
 * workflow-demo wd_manager / wd_hr groups, which exist as roles without
 * members in the demo plugin seed).
 */
async function ensureUserWithRoles(
  request: APIRequestContext,
  email: string,
  displayName: string,
  roleCodes: string[],
): Promise<void> {
  const probe = await request.post('/api/auth/login', {
    data: { email, password: 'Test2026x' },
    timeout: 20_000,
  });
  if (probe.ok()) {
    // already provisioned — make sure the requested roles are assigned
    const search = await request.post('/api/tenant/members/search', {
      data: { keyword: email },
      headers: { Authorization: `Bearer ${adminToken}`, 'Content-Type': 'application/json' },
      timeout: 15_000,
    });
    const searchBody = await search.json().catch(() => ({}) as Record<string, unknown>);
    const records = ((searchBody?.data?.records ?? []) as Array<Record<string, unknown>>)
      .filter((m) => (m.user as Record<string, unknown> | null)?.email === email);
    expect(records.length, `member search must find ${email}`).toBe(1);
    const assign = await request.post('/api/user-roles/assign-by-code', {
      data: { memberPid: String(records[0].pid), roleCodes },
      headers: { Authorization: `Bearer ${adminToken}`, 'Content-Type': 'application/json' },
      timeout: 20_000,
    });
    expect(assign.ok(), `assign roles to ${email}: ${assign.status()}`).toBe(true);
    return;
  }
  const create = await request.post('/api/admin/users', {
    headers: { Authorization: `Bearer ${adminToken}`, 'Content-Type': 'application/json' },
    data: { email, displayName, initialPassword: 'Test2026x', roleCodes, sendInviteEmail: false },
    timeout: 20_000,
  });
  const body = await create.json().catch(() => ({}) as Record<string, unknown>);
  expect(
    create.ok(),
    `provision ${email}: ${create.status()} ${JSON.stringify(body).slice(0, 300)}`,
  ).toBe(true);
}

test.describe('BPM Showcase S5+S6: gateways & rule routing (@bpm-showcase)', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(300_000);

  test.beforeAll(async ({ request }) => {
    adminToken = await loginAsAdmin(request);
    await ensureShowcaseRole(request);
    for (const user of Object.values(SHOWCASE_USERS)) {
      await ensureShowcaseUser(request, user, adminToken);
    }
    bobToken = await loginJwt(request, SHOWCASE_USERS.bob.email);
    carolToken = await loginJwt(request, SHOWCASE_USERS.carol.email);
    daveToken = await loginJwt(request, SHOWCASE_USERS.dave.email);
    // The seeded wd_* group roles exist but the demo seed provisions no users
    // for them in this profile — provision showcase members into wd_manager /
    // wd_hr so the Drools routing has real humans to land on.
    await ensureUserWithRoles(request, 'wd-showcase-manager@test.com', 'WD Manager', ['wd_manager', SHOWCASE_ROLE_CODE]);
    await ensureUserWithRoles(request, 'wd-showcase-hr@test.com', 'WD HR', ['wd_hr', SHOWCASE_ROLE_CODE]);
    managerToken = await loginJwt(request, 'wd-showcase-manager@test.com');
    hrToken = await loginJwt(request, 'wd-showcase-hr@test.com');
    for (const [key, email] of [
      ['bob', SHOWCASE_USERS.bob.email],
      ['carol', SHOWCASE_USERS.carol.email],
      ['dave', SHOWCASE_USERS.dave.email],
    ] as const) {
      pids[key] = await resolveUserPid(request, email);
    }

    await deployProcess(request, adminToken, {
      processKey: S5_KEY,
      processName: 'S5 网关全家桶',
      designerJson: parallelGatewayGraph(pids.bob, pids.carol),
    });
    s5Deployed = true;
  });

  test.afterAll(async ({ request }) => {
    if (!s5Deployed) return;
    const listResp = await request.get(`/api/bpm/process-definitions`, {
      headers: { Authorization: `Bearer ${adminToken}` },
    });
    if (!listResp.ok()) return;
    const body = await listResp.json();
    const defs = (body?.data?.records ?? body?.data ?? []) as Array<Record<string, unknown>>;
    const mine = defs.find((d) => d.processKey === S5_KEY);
    if (mine?.pid) await undeployProcess(request, adminToken, String(mine.pid));
  });

  test('S5.1 parallel gateway: dual todos, join waits, both complete → end', async ({ browser, request }, testInfo) => {
    const businessKey = `SC5P-${Date.now()}`;
    const aliceToken = await loginJwt(request, SHOWCASE_USERS.alice.email);
    const { instanceId } = await startProcessInstance(request, aliceToken, {
      processDefinitionId: S5_KEY,
      businessKey,
      variables: { source: 's5-parallel' },
    });

    for (const token of [bobToken, carolToken]) {
      await waitForTodoTask(
        request,
        token,
        (t) => t.processInstanceId === instanceId,
        { timeout: 20_000, message: 'parallel branch todo must appear' },
      );
    }

    // one branch completes → join must still hold the instance
    await completeViaApproveApi(request, bobToken, instanceId, 'legal_review');
    await expect
      .poll(
        async () =>
          (await listTodoTasks(request, carolToken)).filter((t) => t.processInstanceId === instanceId).length,
        { timeout: 10_000, message: 'carol branch todo must remain while join waits' },
      )
      .toBe(1);
    const mid = await queryInstanceStatus(request, adminToken, { processKey: S5_KEY, businessKey });
    expect(mid.completedNodes.some((n) => n.nodeId === 'end'), 'instance must not end with an open branch').toBe(false);

    await completeViaApproveApi(request, carolToken, instanceId, 'finance_review');
    await expect
      .poll(
        async () => {
          const status = await queryInstanceStatus(request, adminToken, { processKey: S5_KEY, businessKey });
          return status.completedNodes.some((n) => n.nodeId === 'end');
        },
        { timeout: 20_000, message: 'parallel instance must reach end after both branches' },
      )
      .toBe(true);
    void testInfo;
  });

  test('S5.2 inclusive gateway: big amount fires both branches; medium fires finance only', async ({ request }) => {
    // deploy the inclusive variant once
    const inclKey = `${S5_KEY}_incl`;
    await deployProcess(request, adminToken, {
      processKey: inclKey,
      processName: 'S5 包容网关',
      designerJson: inclusiveGatewayGraph(pids.bob, pids.carol, pids.dave),
    });

    // case A: amount>=10000 且 needFinance=true → legal + finance both
    const aliceToken = await loginJwt(request, SHOWCASE_USERS.alice.email);
    const bigKey = `SC5I-A-${Date.now()}`;
    const big = await startProcessInstance(request, aliceToken, {
      processDefinitionId: inclKey,
      businessKey: bigKey,
      variables: { amount: 20000, needFinance: true },
    });
    for (const token of [bobToken, daveToken]) {
      await waitForTodoTask(
        request,
        token,
        (t) => t.processInstanceId === big.instanceId,
        { timeout: 20_000, message: 'inclusive big-amount branch todo must appear' },
      );
    }
    // close both branches → end
    await completeViaApproveApi(request, bobToken, big.instanceId, 'legal_review');
    await completeViaApproveApi(request, daveToken, big.instanceId, 'finance_review');
    await expect
      .poll(
        async () => {
          const status = await queryInstanceStatus(request, adminToken, { processKey: inclKey, businessKey: bigKey });
          return status.completedNodes.some((n) => n.nodeId === 'end');
        },
        { timeout: 20_000 },
      )
      .toBe(true);

    // case B: amount small, needFinance=true → finance branch only
    const midKey = `SC5I-B-${Date.now()}`;
    const mid = await startProcessInstance(request, aliceToken, {
      processDefinitionId: inclKey,
      businessKey: midKey,
      variables: { amount: 100, needFinance: true },
    });
    await waitForTodoTask(
      request,
      daveToken,
      (t) => t.processInstanceId === mid.instanceId,
      { timeout: 20_000, message: 'finance branch todo must appear' },
    );
    const bobSees = (await listTodoTasks(request, bobToken)).filter((t) => t.processInstanceId === mid.instanceId);
    expect(bobSees, 'legal branch must NOT activate for small amount').toHaveLength(0);
    await completeViaApproveApi(request, daveToken, mid.instanceId, 'finance_review');
    await expect
      .poll(
        async () => {
          const status = await queryInstanceStatus(request, adminToken, { processKey: inclKey, businessKey: midKey });
          return status.completedNodes.some((n) => n.nodeId === 'end');
        },
        { timeout: 20_000 },
      )
      .toBe(true);
  });

  test('S6 Drools rule-driven routing on wd_leave_approval: days<3 → manager, days>=3 → hr + rule trace UI', async ({ browser, request }, testInfo) => {
    const aliceToken = await loginJwt(request, SHOWCASE_USERS.alice.email);

    // case 1: days=1 → manager branch
    const shortKey = `SC6-${Date.now()}-s`;
    const short = await startProcessInstance(request, aliceToken, {
      processDefinitionId: WD_KEY,
      businessKey: shortKey,
      variables: { days: 1, wd_req_reason: `S6 short ${shortKey}` },
    });
    await waitForTodoTask(
      request,
      managerToken,
      (t) => t.processInstanceId === short.instanceId,
      { timeout: 20_000, message: 'manager todo must appear for days<3 (Drools manager rule)' },
    );

    // case 2: days=5 → hr branch
    const longKey = `SC6-${Date.now()}-l`;
    const long = await startProcessInstance(request, aliceToken, {
      processDefinitionId: WD_KEY,
      businessKey: longKey,
      variables: { days: 5, wd_req_reason: `S6 long ${longKey}` },
    });
    await waitForTodoTask(
      request,
      hrToken,
      (t) => t.processInstanceId === long.instanceId,
      { timeout: 20_000, message: 'hr todo must appear for days>=3 (Drools hr rule)' },
    );
    const managerSeesLong = (await listTodoTasks(request, managerToken)).filter(
      (t) => t.processInstanceId === long.instanceId,
    );
    expect(managerSeesLong, 'manager must NOT receive the days>=3 case').toHaveLength(0);

    // UI evidence: process-status page surfaces the rule-binding trace
    const { context, page } = await openUserSession(browser, {
      key: 'admin',
      email: 'admin@auraboot.com',
      displayName: 'Administrator',
    });
    await page.goto(`/bpm/process-status?processInstanceId=${encodeURIComponent(short.instanceId)}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(
      page.locator('main, [role="main"]').first(),
    ).toBeVisible({ timeout: 15_000 });
    const tracePanel = page.getByTestId('bpm-rule-trace-panel');
    await expect(tracePanel, 'process status page must show the rule trace panel').toBeVisible({ timeout: 15_000 });
    await evidenceShot(page, testInfo, 's6-rule-trace');
    await context.close();
  });
});
