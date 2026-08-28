/**
 * BPM Showcase S1 — 审批基本盘 (approval core).
 *
 * Scenario SOT: workspace docs/plans/2026-08-28-oss-bpm-showcase-e2e-verification-plan.md §4 S1.
 *
 * Coverage (前后端成对证据: each UI action pairs with a backend query):
 * - S1.1  bob sees the todo in Task Center UI; carol does not (multi-role + empty state)
 * - S1.2  bob approves via Task Center UI → request carries taskResult
 *         (Bug #8 Part 1 frontend injection) → instance reaches end_approved
 * - S1.3  reject via UI: empty comment cannot complete, filled reject reaches
 *         end_rejected (audit evidence)
 * - S1.4  API approve without variables reaches approved branch
 *         (Bug #8 Part 2 backend taskActions fallback, commit c5721588a) — API-backed
 * - S1.5  eve (unrelated user) cannot approve bob's task via API — permission reverse case
 *
 * Platform fact (2026-08-28): the OSS stack has no standalone "start arbitrary
 * process" UI — generic start is a DSL ActionDef(type=bpm) button or a business
 * plugin form (workflow-demo). Instance start is therefore API-backed and marked
 * as such in the coverage matrix; every task-center action below is driven
 * through the real UI.
 *
 * Instance completion is asserted via the SmartEngine execution timeline
 * (authoritative node ids), not the status enum — the enum value set is not a
 * test contract and guessing it would be a hidden fallback.
 */

import { test, expect, type APIRequestContext } from '../../fixtures';
import {
  loginAsAdmin,
  startProcessInstance,
  queryInstanceStatus,
  waitForTodoTask,
  listTodoTasks,
  listAuditEvents,
  undeployProcess,
} from '../bpm/_helpers/bpm-lifecycle';
import {
  navigateToTaskCenter,
  findTaskRowByBusinessKey,
  openTaskRowMenu,
} from '../bpm/_helpers/task-center';
import {
  SHOWCASE_PASSWORD,
  SHOWCASE_USERS,
  ensureShowcaseUser,
  ensureShowcaseRole,
  loginJwt,
  deployProcess,
  openUserSession,
  evidenceShot,
  expectContentReady,
} from './_helpers/showcase';

// Unique key per run: draft DELETE is a soft delete and the
// uq_process_tenant_key_version index keeps occupying the old key.
const PROCESS_KEY = `sc1_leave_${Date.now()}`;
const BUSINESS_PREFIX = `SC1-${Date.now()}`;

let adminToken = '';
let bobToken = '';
let eveToken = '';
let bobUserId = '';
let deployed = false;

/** Graph: start → manager_approve(userTask, bob, taskActions) → gw → approved|rejected ends. */
function buildLeaveGraph(bobId: string) {
  return {
    nodes: [
      { id: 'start', type: 'startEvent', position: { x: 80, y: 220 }, data: { type: 'startEvent', label: '开始' } },
      {
        id: 'manager_approve',
        type: 'userTask',
        position: { x: 300, y: 220 },
        data: {
          type: 'userTask',
          label: '主管审批',
          config: { assignee: { type: 'user', userIds: [bobId] } },
          taskActions: [
            { key: 'approve', type: 'complete', label: '通过', resultVariable: 'taskResult', resultValue: 'approved' },
            { key: 'reject', type: 'complete', label: '驳回', resultVariable: 'taskResult', resultValue: 'rejected' },
          ],
        },
      },
      { id: 'gw_result', type: 'exclusiveGateway', position: { x: 520, y: 220 }, data: { type: 'exclusiveGateway', label: '审批结果' } },
      { id: 'end_approved', type: 'endEvent', position: { x: 720, y: 140 }, data: { type: 'endEvent', label: '通过' } },
      { id: 'end_rejected', type: 'endEvent', position: { x: 720, y: 320 }, data: { type: 'endEvent', label: '拒绝' } },
    ],
    edges: [
      { id: 'e_start_task', source: 'start', target: 'manager_approve', type: 'smoothstep', data: {} },
      { id: 'e_task_gw', source: 'manager_approve', target: 'gw_result', type: 'smoothstep', data: {} },
      {
        id: 'e_gw_approved',
        source: 'gw_result',
        target: 'end_approved',
        type: 'conditional',
        data: { label: '通过', condition: { type: 'expression', content: "${taskResult == 'approved'}" } },
      },
      {
        id: 'e_gw_rejected',
        source: 'gw_result',
        target: 'end_rejected',
        type: 'conditional',
        data: { label: '拒绝', condition: { type: 'expression', content: "${taskResult == 'rejected'}" } },
      },
    ],
  };
}

async function startInstanceAsAlice(
  request: APIRequestContext,
  businessKey: string,
): Promise<string> {
  const aliceToken = await loginJwt(request, SHOWCASE_USERS.alice.email);
  const { instanceId } = await startProcessInstance(request, aliceToken, {
    processDefinitionId: PROCESS_KEY,
    businessKey,
    variables: { days: 3, reason: 'BPM showcase S1' },
  });
  return instanceId;
}

async function expectTaskGone(request: APIRequestContext, bobToken: string, instanceId: string): Promise<void> {
  const tasks = await listTodoTasks(request, bobToken);
  expect(
    tasks.filter((t) => t.processInstanceId === instanceId),
    'manager_approve todo must be gone after completion',
  ).toHaveLength(0);
}

/**
 * Poll instance status until the given node appears in completedNodes.
 * Completion evidence comes from SmartEngine instance queries + the BPM audit
 * trail — the /orchestration timeline is only populated on the orchestrated
 * start path, not for plain process-instance starts (ab_bpm_execution_log
 * stays empty there).
 */
async function expectInstanceReached(
  request: APIRequestContext,
  processKey: string,
  businessKey: string,
  nodeId: string,
  timeoutMs = 20_000,
): Promise<void> {
  await expect
    .poll(
      async () => {
        const status = await queryInstanceStatus(request, adminToken, { processKey, businessKey });
        return status.completedNodes.some((n) => n.nodeId === nodeId);
      },
      { timeout: timeoutMs, message: `instance must complete node ${nodeId}` },
    )
    .toBe(true);
}

test.describe('BPM Showcase S1: approval core (@bpm-showcase)', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(240_000);

  test.beforeAll(async ({ request }) => {
    adminToken = await loginAsAdmin(request);
    await ensureShowcaseRole(request);
    for (const user of Object.values(SHOWCASE_USERS)) {
      await ensureShowcaseUser(request, user, adminToken);
    }
    bobToken = await loginJwt(request, SHOWCASE_USERS.bob.email);
    eveToken = await loginJwt(request, SHOWCASE_USERS.eve.email);

    // Resolve bob's user PID — SmartEngine assignee matching and the todo
    // query both key on the user pid (ab_user.pid), not the numeric id.
    // Reset-and-init re-creates users with fresh pids on every rebuild.
    const searchResp = await request.get('/api/admin/users/search?keyword=bpm-showcase-bob', {
      headers: { Authorization: `Bearer ${adminToken}` },
    });
    expect(searchResp.ok(), `bob user search: ${searchResp.status()}`).toBe(true);
    const searchBody = await searchResp.json();
    const bobHit = ((searchBody?.data ?? []) as Array<Record<string, unknown>>).find(
      (u) => u.email === SHOWCASE_USERS.bob.email,
    );
    bobUserId = String(bobHit?.pid ?? '');
    expect(bobUserId, 'bob user pid must resolve').toBeTruthy();

    await deployProcess(request, adminToken, {
      processKey: PROCESS_KEY,
      processName: 'S1 请假审批(主管审批)',
      description: 'BPM showcase S1 approval core',
      designerJson: buildLeaveGraph(bobUserId),
    });
    deployed = true;
  });

  test.afterAll(async ({ request }) => {
    if (!deployed) return;
    const listResp = await request.get(`/api/bpm/process-definitions`, {
      headers: { Authorization: `Bearer ${adminToken}` },
    });
    if (!listResp.ok()) return;
    const body = await listResp.json();
    const defs = (body?.data?.records ?? body?.data ?? []) as Array<Record<string, unknown>>;
    const mine = defs.find((d) => d.processKey === PROCESS_KEY);
    if (mine?.pid) await undeployProcess(request, adminToken, String(mine.pid));
  });

  test('S1.1 bob sees the todo in Task Center UI; carol does not', async ({ browser, request }, testInfo) => {
    const businessKey = `${BUSINESS_PREFIX}-A`;
    const instanceId = await startInstanceAsAlice(request, businessKey);
    expect(instanceId).toBeTruthy();

    await waitForTodoTask(
      request,
      bobToken,
      (t) => t.processInstanceId === instanceId && t.processDefinitionActivityId === 'manager_approve',
      { timeout: 20_000, message: `bob todo must contain manager_approve for ${instanceId}` },
    );

    // bob via real UI login → sidebar → 任务中心, row scoped by business key
    const { context: bobCtx, page: bobPage } = await openUserSession(browser, SHOWCASE_USERS.bob);
    await navigateToTaskCenter(bobPage);
    await expectContentReady(bobPage, /任务中心/);

    const taskRow = findTaskRowByBusinessKey(bobPage, businessKey, /主管审批|manager_approve/i);
    await expect(taskRow, 'bob must see the manager_approve row').toBeVisible({ timeout: 20_000 });
    await evidenceShot(bobPage, testInfo, 's1-1-bob-todo-row', taskRow);
    await evidenceShot(bobPage, testInfo, 's1-1-bob-task-center');
    await bobCtx.close();

    // carol via real UI: no such row for this business key
    const { context: carolCtx, page: carolPage } = await openUserSession(browser, SHOWCASE_USERS.carol);
    await navigateToTaskCenter(carolPage);
    await expectContentReady(carolPage);
    const carolRow = findTaskRowByBusinessKey(carolPage, businessKey, /主管审批|manager_approve/i);
    await expect(carolRow, 'carol must NOT see the task').toHaveCount(0);
    await evidenceShot(carolPage, testInfo, 's1-1-carol-no-todo');
    await carolCtx.close();
  });

  test('S1.2 bob approves via UI → taskResult injected → approved branch', async ({ browser, request }, testInfo) => {
    const businessKey = `${BUSINESS_PREFIX}-B`;
    const instanceId = await startInstanceAsAlice(request, businessKey);

    const { context: bobCtx, page: bobPage } = await openUserSession(browser, SHOWCASE_USERS.bob);
    await navigateToTaskCenter(bobPage);

    const taskRow = findTaskRowByBusinessKey(bobPage, businessKey, /主管审批|manager_approve/i);
    await expect(taskRow).toBeVisible({ timeout: 20_000 });

    const menu = await openTaskRowMenu(taskRow, bobPage);
    const approveItem = menu.locator('[data-testid="task-action-approve"]');
    await expect(approveItem).toBeVisible();
    await evidenceShot(bobPage, testInfo, 's1-2-approve-menu', menu);

    // Part 1: the UI approve request must carry the DSL-declared variable.
    const approveRespPromise = bobPage.waitForResponse(
      (resp) => resp.request().method() === 'POST' && resp.url().includes('/approve'),
      { timeout: 20_000 },
    );
    await approveItem.click();
    const approveDialog = bobPage.getByRole('dialog', { name: /通过审批/ }).first();
    await expect(approveDialog).toBeVisible({ timeout: 5_000 });
    await approveDialog.locator('textarea').fill(`S1.2 approve ${businessKey}`);
    await evidenceShot(bobPage, testInfo, 's1-2-approve-dialog', approveDialog);
    await approveDialog.getByRole('button', { name: '确认通过' }).click();

    const approveResp = await approveRespPromise;
    const reqBody = approveResp.request().postDataJSON() as Record<string, unknown>;
    const variables = (reqBody.variables ?? {}) as Record<string, unknown>;
    expect(
      variables.taskResult,
      `UI approve must inject taskResult (Bug #8 Part 1): ${JSON.stringify(reqBody)}`,
    ).toBe('approved');
    expect(approveResp.status(), `approve HTTP ${approveResp.status()}`).toBeLessThan(400);

    // Backend evidence: end_approved completes; audit has task_approve.
    await expectInstanceReached(request, PROCESS_KEY, businessKey, 'end_approved');
    const audits = await listAuditEvents(request, adminToken, instanceId);
    expect(audits.map((a) => a.operation)).toContain('task_approve');
    await expectTaskGone(request, bobToken, instanceId);
    await evidenceShot(bobPage, testInfo, 's1-2-after-approve');
    await bobCtx.close();
  });

  test('S1.3 reject via UI: blank comment cannot complete; filled reject reaches rejected branch', async ({ browser, request }, testInfo) => {
    const businessKey = `${BUSINESS_PREFIX}-C`;
    const instanceId = await startInstanceAsAlice(request, businessKey);

    const { context: bobCtx, page: bobPage } = await openUserSession(browser, SHOWCASE_USERS.bob);
    await navigateToTaskCenter(bobPage);
    const taskRow = findTaskRowByBusinessKey(bobPage, businessKey, /主管审批|manager_approve/i);
    await expect(taskRow).toBeVisible({ timeout: 20_000 });

    const menu = await openTaskRowMenu(taskRow, bobPage);
    await menu.locator('[data-testid="task-action-reject"]').click();
    const rejectDialog = bobPage.getByRole('dialog', { name: /驳回审批/ }).first();
    await expect(rejectDialog).toBeVisible({ timeout: 5_000 });

    // Backend requires a non-blank rejection comment: the complete call must
    // not go through (no /reject POST succeeding, dialog stays open).
    const rejectRespPromise = bobPage.waitForResponse(
      (resp) => resp.request().method() === 'POST' && resp.url().includes('/reject'),
      { timeout: 15_000 },
    );
    await rejectDialog.getByRole('button', { name: '确认驳回' }).click();
    // Backend refuses a blank rejection comment via the ApiResponse error
    // envelope (HTTP stays 200, body code != '0'); the dialog stays open.
    const rejectResp = await rejectRespPromise;
    const rejectBody = (await rejectResp.json().catch(() => ({}))) as { code?: string | number };
    expect(
        String(rejectBody.code ?? '0') === '0',
        'blank-comment reject must be refused by the backend',
      ).toBe(false);
    await expect(rejectDialog, 'dialog must stay open after refused submit').toBeVisible({ timeout: 5_000 });
    await evidenceShot(bobPage, testInfo, 's1-3-reject-blank-refused', rejectDialog);

    await rejectDialog.locator('textarea').fill(`S1.3 reject ${businessKey}`);
    const rejectOkPromise = bobPage.waitForResponse(
      (resp) => resp.request().method() === 'POST' && resp.url().includes('/reject'),
      { timeout: 20_000 },
    );
    await rejectDialog.getByRole('button', { name: '确认驳回' }).click();
    const rejectOk = await rejectOkPromise;
    expect(rejectOk.status(), `reject HTTP ${rejectOk.status()}`).toBeLessThan(400);

    await expectInstanceReached(request, PROCESS_KEY, businessKey, 'end_rejected');
    const audits = await listAuditEvents(request, adminToken, instanceId);
    expect(audits.map((a) => a.operation)).toContain('task_reject');
    await expectTaskGone(request, bobToken, instanceId);
    await evidenceShot(bobPage, testInfo, 's1-3-after-reject');
    await bobCtx.close();
  });

  test('S1.4 API approve without variables still routes via backend taskActions fallback (API-backed)', async ({ request }) => {
    const businessKey = `${BUSINESS_PREFIX}-D`;
    const instanceId = await startInstanceAsAlice(request, businessKey);
    const task = await waitForTodoTask(
      request,
      bobToken,
      (t) => t.processInstanceId === instanceId && t.processDefinitionActivityId === 'manager_approve',
      { timeout: 20_000 },
    );

    const resp = await request.post(`/api/bpm/tasks/${task.taskId}/approve`, {
      headers: { Authorization: `Bearer ${bobToken}`, 'Content-Type': 'application/json' },
      data: { comment: 'S1.4 API approve (no variables)' },
    });
    expect(resp.ok(), `API approve: ${resp.status()} ${await resp.text()}`).toBe(true);

    await expectInstanceReached(
      request,
      PROCESS_KEY,
      businessKey,
      'end_approved',
      20_000,
    );
  });

  test('S1.5 eve (unrelated) cannot approve bob’s task via API — permission reverse case', async ({ request }) => {
    const businessKey = `${BUSINESS_PREFIX}-E`;
    const instanceId = await startInstanceAsAlice(request, businessKey);
    const task = await waitForTodoTask(
      request,
      bobToken,
      (t) => t.processInstanceId === instanceId && t.processDefinitionActivityId === 'manager_approve',
      { timeout: 20_000 },
    );

    const resp = await request.post(`/api/bpm/tasks/${task.taskId}/approve`, {
      headers: { Authorization: `Bearer ${eveToken}`, 'Content-Type': 'application/json' },
      data: { comment: 'eve should not be allowed' },
    });
    expect(resp.ok(), 'eve approve must not succeed').toBe(false);

    const status = await queryInstanceStatus(request, adminToken, {
      processKey: PROCESS_KEY,
      businessKey,
    });
    expect(
      status.completedNodes.map((n) => n.nodeId),
      'instance must still sit on manager_approve (nothing completed past it)',
    ).not.toContain('end_approved');
    expect(
      await listTodoTasks(request, bobToken).then(
        (ts) => ts.filter((t) => t.processInstanceId === instanceId).length,
      ),
      'bob todo must remain',
    ).toBe(1);
  });
});
