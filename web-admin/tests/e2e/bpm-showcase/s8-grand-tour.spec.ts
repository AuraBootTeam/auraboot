/**
 * BPM Showcase S8 — 大串联收官:包容网关双分支 + 并行会签 + join 收束.
 *
 * Scenario SOT: workspace docs/plans/2026-08-28-oss-bpm-showcase-e2e-verification-plan.md §4 S8.
 *
 * 一条流程同时验证:包容网关条件分流(大额 → 法务 + 财务两分支同时激活)
 * → 财务分支为 multi-instance 会签(carol+dave 全部通过)→ inclusive join
 * 等待两支全部完成 → end。carol 的会签办结通过真实 UI 完成(浏览器证据),
 * 其余分支经 API 办结(API-backed,与既有单场景口径一致)。
 * SLA / 转派 / 抄送维度已由 S2/S3/S7 单场景覆盖,不在此重复堆叠。
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
  navigateToTaskCenter,
  findTaskRowByBusinessKey,
  openTaskRowMenu,
} from '../bpm/_helpers/task-center';
import {
  SHOWCASE_USERS,
  ensureShowcaseRole,
  ensureShowcaseUser,
  loginJwt,
  deployProcess,
  openUserSession,
  evidenceShot,
  waitTaskCenterSettled,
} from './_helpers/showcase';

interface SlaRecord { pid: string; status: string; processInstanceId: string | null; }

const S8_KEY = `sc8_grand_${Date.now()}`;
let adminToken = '';
let bobToken = '';
let carolToken = '';
let daveToken = '';
const pids: Record<string, string> = {};
let deployed = false;

async function resolveUserPid(request: APIRequestContext, email: string): Promise<string> {
  const resp = await request.get(`/api/admin/users/search?keyword=${encodeURIComponent(email)}`, {
    headers: { Authorization: `Bearer ${adminToken}` },
  });
  expect(resp.ok(), `user search ${email}`).toBe(true);
  const body = await resp.json();
  const hit = ((body?.data ?? []) as Array<Record<string, unknown>>).find((u) => u.email === email);
  return String(hit?.pid ?? '');
}

function grandTourGraph() {
  return {
    nodes: [
      { id: 'start', type: 'startEvent', position: { x: 60, y: 220 }, data: { type: 'startEvent', label: '开始' } },
      { id: 'gw_incl', type: 'inclusiveGateway', position: { x: 220, y: 220 }, data: { type: 'inclusiveGateway', label: '范围判定' } },
      {
        id: 'legal_review', type: 'userTask', position: { x: 420, y: 100 },
        data: {
          type: 'userTask', label: '法务复核',
          config: { assignee: { type: 'user', userIds: [pids.bob] } },
          taskActions: [
            { key: 'approve', type: 'complete', label: '通过', resultVariable: 'taskResult', resultValue: 'approved' },
          ],
        },
      },
      {
        id: 'finance_signoff', type: 'userTask', position: { x: 420, y: 340 },
        data: {
          type: 'userTask', label: '财务会签',
          config: {
            assignee: { type: 'expression', expression: '${currentApprover}' },
            multiInstance: {
              enabled: true,
              sequential: false,
              collection: '${approverList}',
              elementVariable: 'currentApprover',
              completionCondition: '${nrOfCompletedInstances == nrOfInstances}',
            },
          },
        },
      },
      { id: 'join', type: 'inclusiveGateway', position: { x: 640, y: 220 }, data: { type: 'inclusiveGateway', label: '汇合' } },
      { id: 'end', type: 'endEvent', position: { x: 790, y: 220 }, data: { type: 'endEvent', label: '结束' } },
    ],
    edges: [
      { id: 'e1', source: 'start', target: 'gw_incl', type: 'smoothstep', data: {} },
      {
        id: 'e_legal', source: 'gw_incl', target: 'legal_review', type: 'conditional',
        data: { label: '大额', condition: { type: 'expression', content: '${amount >= 10000}' } },
      },
      {
        id: 'e_finance', source: 'gw_incl', target: 'finance_signoff', type: 'conditional',
        data: { label: '需财务', condition: { type: 'expression', content: '${needFinance == true}' } },
      },
      { id: 'e4', source: 'legal_review', target: 'join', type: 'smoothstep', data: {} },
      { id: 'e5', source: 'finance_signoff', target: 'join', type: 'smoothstep', data: {} },
      { id: 'e6', source: 'join', target: 'end', type: 'smoothstep', data: {} },
    ],
  };
}

test.describe('BPM Showcase S8: grand tour (@bpm-showcase)', () => {
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
    for (const [key, email] of [
      ['bob', SHOWCASE_USERS.bob.email],
      ['carol', SHOWCASE_USERS.carol.email],
      ['dave', SHOWCASE_USERS.dave.email],
    ] as const) {
      pids[key] = await resolveUserPid(request, email);
    }
    await deployProcess(request, adminToken, {
      processKey: S8_KEY,
      processName: 'S8 大串联',
      designerJson: grandTourGraph(),
    });
    deployed = true;

    // SLA dimension: node-level config on the legal branch; the record must
    // complete when the branch approves (deadline 30s, tour finishes faster
    // or not — completion is driven by task completion either way).
    const sla = await request.post('/api/bpm/sla-configs', {
      headers: { Authorization: `Bearer ${adminToken}`, 'Content-Type': 'application/json' },
      data: {
        name: `S8 legal SLA ${S8_KEY}`,
        targetType: 'NODE',
        targetKey: 'legal_review',
        deadlineMode: 'FIXED',
        deadlineValue: 'PT30S',
        warningRules: [],
      },
    });
    const slaBody = await sla.json().catch(() => ({}) as Record<string, unknown>);
    expect(sla.ok(), `S8 sla config: ${sla.status()} ${JSON.stringify(slaBody).slice(0, 200)}`).toBe(true);
  });

  test.afterAll(async ({ request }) => {
    if (!deployed) return;
    const listResp = await request.get(`/api/bpm/process-definitions`, {
      headers: { Authorization: `Bearer ${adminToken}` },
    });
    if (!listResp.ok()) return;
    const body = await listResp.json();
    const defs = (body?.data?.records ?? body?.data ?? []) as Array<Record<string, unknown>>;
    const mine = defs.find((d) => d.processKey === S8_KEY);
    if (mine?.pid) await undeployProcess(request, adminToken, String(mine.pid));
  });

  test('S8 grand tour: inclusive branches + countersign join → end', async ({ browser, request }, testInfo) => {
    const businessKey = `SC8-${Date.now()}`;
    const aliceToken = await loginJwt(request, SHOWCASE_USERS.alice.email);
    const { instanceId } = await startProcessInstance(request, aliceToken, {
      processDefinitionId: S8_KEY,
      businessKey,
      variables: { amount: 50000, needFinance: true, approverList: [pids.carol, pids.dave] },
    });

    // both branches active: legal (bob) + both finance MI tasks (carol & dave)
    await waitForTodoTask(request, bobToken, (t) => t.processInstanceId === instanceId, {
      timeout: 20_000, message: 'legal branch todo',
    });
    await waitForTodoTask(request, carolToken, (t) => t.processInstanceId === instanceId, {
      timeout: 20_000, message: 'finance countersign todo (carol)',
    });
    await waitForTodoTask(request, daveToken, (t) => t.processInstanceId === instanceId, {
      timeout: 20_000, message: 'finance countersign todo (dave)',
    });

    // legal branch completes via API (API-backed)
    const legalTask = await waitForTodoTask(
      request, bobToken,
      (t) => t.processInstanceId === instanceId && t.processDefinitionActivityId === 'legal_review',
      { timeout: 10_000 },
    );
    const legalResp = await request.post(`/api/bpm/tasks/${legalTask.taskId}/approve`, {
      headers: { Authorization: `Bearer ${bobToken}`, 'Content-Type': 'application/json' },
      data: { comment: 'S8 legal approve' },
    });
    expect(legalResp.ok(), 'legal approve').toBe(true);

    // join must hold: instance not ended with finance countersign open
    const mid = await queryInstanceStatus(request, adminToken, { processKey: S8_KEY, businessKey });
    expect(mid.completedNodes.some((n) => n.nodeId === 'end')).toBe(false);

    // carol completes her countersign task via REAL UI (browser evidence)
    const { context: carolCtx, page: carolPage } = await openUserSession(browser, SHOWCASE_USERS.carol);
    await navigateToTaskCenter(carolPage);
    const row = findTaskRowByBusinessKey(carolPage, businessKey, /财务会签|finance_signoff/i);
    await expect(row).toBeVisible({ timeout: 20_000 });
    const menu = await openTaskRowMenu(row, carolPage);
    await menu.getByText('完成任务', { exact: true }).click();
    const dialog = carolPage.getByRole('dialog', { name: /完成任务/ }).first();
    await expect(dialog).toBeVisible({ timeout: 5_000 });
    await dialog.locator('textarea').fill(`S8 carol countersign ${businessKey}`);
    await evidenceShot(carolPage, testInfo, 's8-carol-countersign-dialog', dialog);
    const completeRespPromise = carolPage.waitForResponse(
      (resp) => resp.request().method() === 'POST' && resp.url().includes('/complete'),
      { timeout: 20_000 },
    );
    await dialog.getByRole('button', { name: '确认完成' }).click();
    expect((await completeRespPromise).status()).toBeLessThan(400);
    await waitTaskCenterSettled(carolPage);
    await evidenceShot(carolPage, testInfo, 's8-after-carol-complete');
    await carolCtx.close();

    // dave finishes the countersign via API
    const daveTask = await waitForTodoTask(
      request, daveToken,
      (t) => t.processInstanceId === instanceId,
      { timeout: 10_000, message: 'dave countersign todo must remain' },
    );
    const daveResp = await request.post(`/api/bpm/tasks/${daveTask.taskId}/complete`, {
      headers: { Authorization: `Bearer ${daveToken}`, 'Content-Type': 'application/json' },
      data: { comment: 'S8 dave countersign' },
    });
    expect(daveResp.ok(), `dave complete: ${daveResp.status()}`).toBe(true);

    // both branches done → inclusive join releases → end
    await expect
      .poll(
        async () => {
          const status = await queryInstanceStatus(request, adminToken, { processKey: S8_KEY, businessKey });
          return status.completedNodes.some((n) => n.nodeId === 'end');
        },
        { timeout: 20_000, message: 'grand tour instance must reach end' },
      )
      .toBe(true);

    // no open todos remain on this instance
    for (const token of [bobToken, carolToken, daveToken]) {
      const remaining = (await listTodoTasks(request, token)).filter(
        (t) => t.processInstanceId === instanceId,
      );
      expect(remaining, 'no todos may remain after grand tour completion').toHaveLength(0);
    }

    // SLA dimension: the node record must close as COMPLETED once the branch
    // task was approved (SlaActivationListener task_completed closure, #1713)
    await expect
      .poll(
        async () => {
          const resp = await request.get('/api/bpm/monitor/sla-records', {
            headers: { Authorization: `Bearer ${adminToken}` },
          });
          const records = (((await resp.json())?.data ?? []) as SlaRecord[])
            .filter((r) => r.processInstanceId === String(instanceId));
          return records.map((r) => (r.status ?? '').toLowerCase()).join(',');
        },
        { timeout: 20_000, message: 'grand-tour SLA record must COMPLETE' },
      )
      .toContain('completed');
  });
});
