/**
 * BPM Showcase S7 — SLA 时限与升级.
 *
 * Scenario SOT: workspace docs/plans/2026-08-28-oss-bpm-showcase-e2e-verification-plan.md §4 S7.
 *
 * - S7.1  节点级 SLA 配置(NODE targetKey=review, FIXED PT30S, 0% escalate)→
 *         发起后 SLA record RUNNING(backend),/bpm/sla-monitor 页可见(UI 截图)。
 * - S7.2  调度器(15s fixedRate)在时限后把 record 推到 WARNING/OVERDUE 且
 *         升级动作产生通知记录。
 * - S7.3  任务办结后 record → COMPLETED。
 *
 * SLA 语义链: UI/API 配置 → ab_sla_config 落库 → SlaActivationListener 在
 * 节点激活时建 record → SlaSchedulerService(15s)推进状态 → monitor 回显。
 * (配置走 API-backed: designer-sla-panel.spec.ts 已覆盖 SLA 面板 UI。)
 */

import { test, expect, type APIRequestContext } from '../../fixtures';
import {
  loginAsAdmin,
  startProcessInstance,
  waitForTodoTask,
  listTodoTasks,
  undeployProcess,
} from '../bpm/_helpers/bpm-lifecycle';
import {
  SHOWCASE_USERS,
  ensureShowcaseRole,
  ensureShowcaseUser,
  loginJwt,
  deployProcess,
  openUserSession,
  evidenceShot,
  expectContentReady,
} from './_helpers/showcase';

const S7_KEY = `sc7_sla_${Date.now()}`;
const REVIEW_NODE = 'review';
const ADMIN_EMAIL = 'admin@auraboot.com';

let adminToken = '';
let bobToken = '';
let aliceUserId = '';
let slaConfigPid = '';
let s7Deployed = false;

function singleTaskGraph(assigneePid: string) {
  return {
    nodes: [
      { id: 'start', type: 'startEvent', position: { x: 80, y: 200 }, data: { type: 'startEvent', label: '开始' } },
      {
        id: REVIEW_NODE,
        type: 'userTask',
        position: { x: 300, y: 200 },
        data: {
          type: 'userTask',
          label: '复核',
          config: { assignee: { type: 'user', userIds: [assigneePid] } },
          taskActions: [
            { key: 'approve', type: 'complete', label: '通过', resultVariable: 'taskResult', resultValue: 'approved' },
          ],
        },
      },
      { id: 'end', type: 'endEvent', position: { x: 520, y: 200 }, data: { type: 'endEvent', label: '结束' } },
    ],
    edges: [
      { id: 'e1', source: 'start', target: REVIEW_NODE, type: 'smoothstep', data: {} },
      { id: 'e2', source: REVIEW_NODE, target: 'end', type: 'smoothstep', data: {} },
    ],
  };
}

interface SlaRecord {
  pid: string;
  status: string;
  processInstanceId: string | null;
}

async function fetchSlaRecords(request: APIRequestContext): Promise<SlaRecord[]> {
  const resp = await request.get('/api/bpm/monitor/sla-records', {
    headers: { Authorization: `Bearer ${adminToken}` },
  });
  expect(resp.ok(), `sla-records: ${resp.status()}`).toBe(true);
  const body = await resp.json();
  const data = body?.data;
  return (Array.isArray(data) ? data : data?.records ?? []) as SlaRecord[];
}

test.describe('BPM Showcase S7: SLA deadline & escalation (@bpm-showcase)', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(300_000);

  test.beforeAll(async ({ request }) => {
    adminToken = await loginAsAdmin(request);
    await ensureShowcaseRole(request);
    for (const user of Object.values(SHOWCASE_USERS)) {
      await ensureShowcaseUser(request, user, adminToken);
    }
    bobToken = await loginJwt(request, SHOWCASE_USERS.bob.email);

    // numeric id of alice for the escalation recipient ("userId:<id>")
    const aliceToken = await loginJwt(request, SHOWCASE_USERS.alice.email);
    const me = await request.get('/api/auth/me', {
      headers: { Authorization: `Bearer ${aliceToken}` },
    });
    const meBody = await me.json();
    aliceUserId = String(meBody?.data?.user?.id ?? '');
    expect(aliceUserId, 'alice numeric userId').toBeTruthy();

    // bob pid for the assignee
    const search = await request.get('/api/admin/users/search?keyword=bpm-showcase-bob', {
      headers: { Authorization: `Bearer ${adminToken}` },
    });
    const searchBody = await search.json();
    const bobPid = String(
      ((searchBody?.data ?? []) as Array<Record<string, unknown>>).find(
        (u) => u.email === SHOWCASE_USERS.bob.email,
      )?.pid ?? '',
    );
    expect(bobPid, 'bob pid').toBeTruthy();

    await deployProcess(request, adminToken, {
      processKey: S7_KEY,
      processName: 'S7 SLA 时限',
      designerJson: singleTaskGraph(bobPid),
    });
    s7Deployed = true;

    // Node-level SLA: 30s deadline, escalate immediately at 0% threshold.
    const create = await request.post('/api/bpm/sla-configs', {
      headers: { Authorization: `Bearer ${adminToken}`, 'Content-Type': 'application/json' },
      data: {
        name: `S7 review SLA ${S7_KEY}`,
        targetType: 'NODE',
        targetKey: REVIEW_NODE,
        deadlineMode: 'FIXED',
        deadlineValue: 'PT30S',
        warningRules: [
          { threshold: '0%', action: 'escalate', recipients: `userId:${aliceUserId}` },
        ],
      },
    });
    const createBody = await create.json().catch(() => ({}) as Record<string, unknown>);
    expect(
      create.ok(),
      `sla config create: ${create.status()} ${JSON.stringify(createBody).slice(0, 300)}`,
    ).toBe(true);
    slaConfigPid = String((createBody as { data?: Record<string, unknown> })?.data?.pid ?? '');
    expect(slaConfigPid, 'sla config pid').toBeTruthy();
  });

  test.afterAll(async ({ request }) => {
    if (slaConfigPid) {
      await request.delete(`/api/bpm/sla-configs/${slaConfigPid}`, {
        headers: { Authorization: `Bearer ${adminToken}` },
      });
    }
    if (!s7Deployed) return;
    const listResp = await request.get(`/api/bpm/process-definitions`, {
      headers: { Authorization: `Bearer ${adminToken}` },
    });
    if (!listResp.ok()) return;
    const body = await listResp.json();
    const defs = (body?.data?.records ?? body?.data ?? []) as Array<Record<string, unknown>>;
    const mine = defs.find((d) => d.processKey === S7_KEY);
    if (mine?.pid) await undeployProcess(request, adminToken, String(mine.pid));
  });

  test('S7.1-S7.3 SLA record activates, escalates on deadline, completes on approval', async ({ browser, request }, testInfo) => {
    const businessKey = `SC7-${Date.now()}`;
    const aliceToken = await loginJwt(request, SHOWCASE_USERS.alice.email);
    const { instanceId } = await startProcessInstance(request, aliceToken, {
      processDefinitionId: S7_KEY,
      businessKey,
      variables: {},
    });
    await waitForTodoTask(
      request,
      bobToken,
      (t) => t.processInstanceId === instanceId,
      { timeout: 20_000 },
    );

    // S7.1: record activated for this instance
    let record: SlaRecord | undefined;
    await expect
      .poll(
        async () => {
          const records = await fetchSlaRecords(request);
          record = records.find((r) => r.processInstanceId === String(instanceId));
          return Boolean(record);
        },
        { timeout: 20_000, message: 'SLA record must activate for the instance' },
      )
      .toBe(true);
    // The scheduler may have already advanced a fresh record past RUNNING
    // (deadline 15s vs. suite pacing) — both states prove activation and
    // tracking; OVERDUE/escalation is asserted by the later phase.
    expect(
      ['RUNNING', 'WARNING'],
      'fresh SLA record must be active (RUNNING or already WARNING)',
    ).toContain((record?.status ?? '').toUpperCase());

    // UI evidence: admin sees the record on the SLA monitor page
    {
      const { context, page } = await openUserSession(browser, {
        key: 'admin',
        email: ADMIN_EMAIL,
        displayName: 'Administrator',
      });
      await page.goto('/bpm/sla-monitor', { waitUntil: 'domcontentloaded' });
      await expectContentReady(page);
      // Precise record state is asserted via the API above; the UI check
      // asserts the strategy chain lists our enabled SLA config (记录列表在
      // 该页是 stat-card 钻取, 不作默认断言).
      const monitorContent = page.locator('main, [role="main"]').first();
      await expect(
        page.getByTestId('sla-strategy-chain'),
        'sla monitor must render the strategy chain',
      ).toBeVisible({ timeout: 20_000 });
      const content = await monitorContent.innerText();
      expect(content, 'monitor must not show a load failure').not.toMatch(/加载失败|Page not found/);
      expect(
        content,
        'strategy chain must list our S7 config (no empty-state)',
      ).not.toContain('暂无已启用的 SLA 策略');
      // scoped capture: our own SLA config card only — neighbouring seeded
      // strategies carry their own historical action logs which are not this
      // scenario's evidence (review finding 2026-08-29).
      const cardTitle = page.getByTestId('sla-strategy-chain')
        .getByText(`S7 review SLA ${S7_KEY}`).first();
      await expect(cardTitle).toBeVisible({ timeout: 10_000 });
      // scoped capture: the strategy chain container (our config card is in
      // it; excludes the surrounding page chrome and stats)
      const chain = page.getByTestId('sla-strategy-chain');
      await evidenceShot(page, testInfo, 's7-sla-card-scoped', chain);
      await evidenceShot(page, testInfo, 's7-sla-monitor-running');
      void businessKey;
      await context.close();
    }

    // S7.2: scheduler (15s fixedRate) pushes the record past its 30s deadline
    await expect
      .poll(
        async () => {
          const records = await fetchSlaRecords(request);
          return records.find((r) => r.processInstanceId === String(instanceId))?.status ?? '';
        },
        { timeout: 70_000, message: 'SLA record must reach WARNING/OVERDUE after deadline' },
      )
      .toMatch(/warning|overdue/i);

    // escalation action: alice received a notification (SLA escalations land
    // in ab_bpm_notify_record with notify_type='urge' — verified on this stack)
    const received = await request.get('/api/bpm/notify/received?type=urge', {
      headers: { Authorization: `Bearer ${await loginJwt(request, SHOWCASE_USERS.alice.email)}` },
    });
    expect(received.ok(), `notify received: ${received.status()}`).toBe(true);
    const notifyBody = await received.json();
    const notifications = (notifyBody?.data ?? []) as Array<Record<string, unknown>>;
    expect(
      notifications.length,
      'escalation must produce a notification for the recipient',
    ).toBeGreaterThan(0);

    // S7.3: bob approves → task_completed event closes the NODE SLA record
    // (fixed 2026-08-28: SlaActivationListener now completes records on
    // task_completed; previously completeByTaskId had no callers).
    const task = await waitForTodoTask(
      request,
      bobToken,
      (t) => t.processInstanceId === instanceId,
      { timeout: 10_000 },
    );
    const approve = await request.post(`/api/bpm/tasks/${task.taskId}/approve`, {
      headers: { Authorization: `Bearer ${bobToken}`, 'Content-Type': 'application/json' },
      data: { comment: 'S7 approve closes SLA' },
    });
    expect(approve.ok(), `approve: ${approve.status()}`).toBe(true);

    await expect
      .poll(
        async () => {
          const records = await fetchSlaRecords(request);
          return (records.find((r) => r.processInstanceId === String(instanceId))?.status ?? '').toLowerCase();
        },
        { timeout: 30_000, message: 'SLA record must COMPLETE after task approval' },
      )
      .toBe('completed');
  });
});
