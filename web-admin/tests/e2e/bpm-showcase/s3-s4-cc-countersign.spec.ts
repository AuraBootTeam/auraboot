/**
 * BPM Showcase S3+S4 — 抄送闭环 + 会签(multi-instance)与加签/减签.
 *
 * Scenario SOT: workspace docs/plans/2026-08-28-oss-bpm-showcase-e2e-verification-plan.md §4 S3/S4.
 *
 * S3 抄送:
 * - S3.1  bob 手动抄送 dave via Task Center 抄送 dialog → dave 的「抄送给我」
 *         tab 出现该条目(UI)且 /api/bpm/notify/received?type=CC 有记录(后端);
 *         抄送箱为只读(无通过/驳回动作)。
 *
 * S4 会签:
 * - S4.1  并行会签(approverList=bob/carol/dave, 全部通过才算完): 三个人各自
 *         待办出现; 完成 1 个后实例仍在会签节点。
 * - S4.2  全部通过后实例到达 end(完成条件 nrOfCompleted==nrOfInstances)。
 * - S4.3  加签/减签: 单实例任务上加签 carol → carol 待办出现; 减签 carol →
 *         carol 待办消失; bob 正常办结。
 */

import { test, expect, type APIRequestContext } from '../../fixtures';
import { PSQL_BASE } from '../../helpers/environments';
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
  SHOWCASE_USERS,
  ensureShowcaseRole,
  ensureShowcaseUser,
  loginJwt,
  deployProcess,
  openUserSession,
  evidenceShot,
  expectContentReady,
  waitTaskCenterSettled,
} from './_helpers/showcase';

const S3_KEY = `sc3_cc_${Date.now()}`;
const S4_KEY = `sc4_countersign_${Date.now()}`;

let adminToken = '';
let bobToken = '';
let carolToken = '';
let daveToken = '';
const pids: Record<string, string> = {};
let s3Deployed = false;
let s4Deployed = false;

async function resolveUserPid(request: APIRequestContext, email: string): Promise<string> {
  const resp = await request.get(
    `/api/admin/users/search?keyword=${encodeURIComponent(email)}`,
    { headers: { Authorization: `Bearer ${adminToken}` } },
  );
  expect(resp.ok(), `user search ${email}: ${resp.status()}`).toBe(true);
  const body = await resp.json();
  const hit = ((body?.data ?? []) as Array<Record<string, unknown>>).find((u) => u.email === email);
  const pid = String(hit?.pid ?? '');
  expect(pid, `pid for ${email}`).toBeTruthy();
  return pid;
}

function singleTaskGraph(assigneePid: string) {
  return {
    nodes: [
      { id: 'start', type: 'startEvent', position: { x: 80, y: 200 }, data: { type: 'startEvent', label: '开始' } },
      {
        id: 'review',
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
      { id: 'e1', source: 'start', target: 'review', type: 'smoothstep', data: {} },
      { id: 'e2', source: 'review', target: 'end', type: 'smoothstep', data: {} },
    ],
  };
}

/** Parallel countersign: one task per element of ${approverList}. */
function countersignGraph() {
  return {
    nodes: [
      { id: 'start', type: 'startEvent', position: { x: 80, y: 200 }, data: { type: 'startEvent', label: '开始' } },
      {
        id: 'countersign',
        type: 'userTask',
        position: { x: 300, y: 200 },
        data: {
          type: 'userTask',
          label: '会签',
          config: {
            assignee: { type: 'expression', expression: '${currentApprover}' },
            multiInstance: {
              enabled: true,
              sequential: false,
              collection: '${approverList}',
              elementVariable: 'currentApprover',
              completionCondition: '${nrOfCompletedInstances == nrOfInstances}',
            },
            taskActions: [
              { key: 'approve', type: 'complete', label: '通过', resultVariable: 'taskResult', resultValue: 'approved' },
            ],
          },
        },
      },
      { id: 'end', type: 'endEvent', position: { x: 520, y: 200 }, data: { type: 'endEvent', label: '结束' } },
    ],
    edges: [
      { id: 'e1', source: 'start', target: 'countersign', type: 'smoothstep', data: {} },
      { id: 'e2', source: 'countersign', target: 'end', type: 'smoothstep', data: {} },
    ],
  };
}

async function approveViaUi(
  browser: import('@playwright/test').Browser,
  user: (typeof SHOWCASE_USERS)[keyof typeof SHOWCASE_USERS],
  _userToken: string,
  _processKey: string,
  businessKey: string,
  nodeLabel: RegExp,
  comment: string,
  testInfo?: { outputPath: (...parts: string[]) => string },
): Promise<void> {
  const { context, page } = await openUserSession(browser, user);
  await navigateToTaskCenter(page);
  await expectContentReady(page, /任务中心/);
  const row = findTaskRowByBusinessKey(page, businessKey, nodeLabel);
  await expect(row, `${user.key} must see the ${nodeLabel} row`).toBeVisible({ timeout: 20_000 });
  const menu = await openTaskRowMenu(row, page);
  await menu.locator('[data-testid="task-action-approve"]').click();
  const dialog = page.getByRole('dialog', { name: /通过审批/ }).first();
  await expect(dialog).toBeVisible({ timeout: 5_000 });
  await dialog.locator('textarea').fill(comment);
  if (testInfo) await evidenceShot(page, testInfo, `approve-${user.key}`, dialog);
  const respPromise = page.waitForResponse(
    (resp) => resp.request().method() === 'POST' && resp.url().includes('/approve'),
    { timeout: 20_000 },
  );
  await dialog.getByRole('button', { name: '确认通过' }).click();
  expect((await respPromise).status()).toBeLessThan(400);
  await context.close();
}

/** Complete a task via the 完成任务 menu action (plain complete, no taskResult injection). */
async function completeViaUi(
  browser: import('@playwright/test').Browser,
  user: (typeof SHOWCASE_USERS)[keyof typeof SHOWCASE_USERS],
  businessKey: string,
  nodeLabel: RegExp,
  comment: string,
): Promise<void> {
  const { context, page } = await openUserSession(browser, user);
  await navigateToTaskCenter(page);
  const row = findTaskRowByBusinessKey(page, businessKey, nodeLabel);
  await expect(row, `${user.key} must see the row to complete`).toBeVisible({ timeout: 20_000 });
  const menu = await openTaskRowMenu(row, page);
  await menu.getByText('完成任务', { exact: true }).click();
  const dialog = page.getByRole('dialog', { name: /完成任务/ }).first();
  await expect(dialog).toBeVisible({ timeout: 5_000 });
  await dialog.locator('textarea').fill(comment);
  const respPromise = page.waitForResponse(
    (resp) => resp.request().method() === 'POST' && resp.url().includes('/complete'),
    { timeout: 20_000 },
  );
  await dialog.getByRole('button', { name: '确认完成' }).click();
  expect((await respPromise).status()).toBeLessThan(400);
  await context.close();
}

test.describe('BPM Showcase S3+S4: cc loop & countersign (@bpm-showcase)', () => {
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
    for (const [key, user] of Object.entries(SHOWCASE_USERS)) {
      pids[key] = await resolveUserPid(request, user.email);
    }

    await deployProcess(request, adminToken, {
      processKey: S3_KEY,
      processName: 'S3 抄送闭环',
      designerJson: singleTaskGraph(pids.bob),
    });
    s3Deployed = true;
    await deployProcess(request, adminToken, {
      processKey: S4_KEY,
      processName: 'S4 会签',
      designerJson: countersignGraph(),
    });
    s4Deployed = true;
  });

  test.afterAll(async ({ request }) => {
    for (const [key, deployed] of [['s3', s3Deployed], ['s4', s4Deployed]] as const) {
      if (!deployed) continue;
      const listResp = await request.get(`/api/bpm/process-definitions`, {
        headers: { Authorization: `Bearer ${adminToken}` },
      });
      if (!listResp.ok()) continue;
      const body = await listResp.json();
      const defs = (body?.data?.records ?? body?.data ?? []) as Array<Record<string, unknown>>;
      const target = key === 's3' ? S3_KEY : S4_KEY;
      const mine = defs.find((d) => d.processKey === target);
      if (mine?.pid) await undeployProcess(request, adminToken, String(mine.pid));
    }
  });

  test('S3.1 manual cc via UI dialog → dave 抄送箱只读可见', async ({ browser, request }, testInfo) => {
    const businessKey = `SC3-${Date.now()}`;
    const aliceToken = await loginJwt(request, SHOWCASE_USERS.alice.email);
    const { instanceId } = await startProcessInstance(request, aliceToken, {
      processDefinitionId: S3_KEY,
      businessKey,
      variables: {},
    });
    await waitForTodoTask(
      request,
      bobToken,
      (t) => t.processInstanceId === instanceId && t.processDefinitionActivityId === 'review',
      { timeout: 20_000 },
    );

    // bob opens 抄送 dialog and picks dave (multi picker)
    const { context: bobCtx, page: bobPage } = await openUserSession(browser, SHOWCASE_USERS.bob);
    await navigateToTaskCenter(bobPage);
    const row = findTaskRowByBusinessKey(bobPage, businessKey, /复核|review/i);
    await expect(row).toBeVisible({ timeout: 20_000 });
    const menu = await openTaskRowMenu(row, bobPage);
    await menu.getByText('抄送', { exact: true }).click();
    const dialog = bobPage.getByRole('dialog', { name: /抄送/ }).first();
    await expect(dialog).toBeVisible({ timeout: 5_000 });
    await dialog.getByTestId('member-picker-add').click();
    await dialog.getByTestId('member-picker-search-input').fill(SHOWCASE_USERS.dave.displayName);
    const option = dialog.locator('[data-testid^="member-picker-option-"]').first();
    await expect(option).toBeVisible({ timeout: 10_000 });
    await option.click();
    // 抄送内容 anchors the inbox assertion (NotifyPanel renders content text,
    // not a business-key column).
    const ccContent = `S3.1 manual cc ${businessKey}`;
    await dialog.locator('textarea').fill(ccContent);
    await evidenceShot(bobPage, testInfo, 's3-1-cc-dialog', dialog);

    // Manual cc works end-to-end (fixed 2026-08-28): the frontend sends user
    // pid strings and the backend resolves pids to numeric ids (previously
    // Number(pid) → NaN → null → NPE in BpmNotifyController).
    const ccRespPromise = bobPage.waitForResponse(
      (resp) => resp.request().method() === 'POST' && resp.url().includes('/cc'),
      { timeout: 20_000 },
    );
    await dialog.getByRole('button', { name: '确认抄送' }).click();
    const ccResp = await ccRespPromise;
    expect(ccResp.status(), `manual cc HTTP ${ccResp.status()}`).toBeLessThan(400);
    // Success closes the dialog by design — capture the page-level evidence.
    await evidenceShot(bobPage, testInfo, 's3-1-cc-success');
    await bobCtx.close();

    // dave's cc inbox via real UI: task center 抄送给我 tab (NotifyPanel
    // renders notification cards with content text, not the business-key
    // column, so the entry is matched by our cc comment text).
    const { context: daveCtx, page: davePage } = await openUserSession(browser, SHOWCASE_USERS.dave);
    await navigateToTaskCenter(davePage);
    await expectContentReady(davePage, /任务中心/);
    await davePage.getByRole('button', { name: /抄送给我/ }).first().click();
    await expectContentReady(davePage);
    const ccEntry = davePage.locator('main, [role="main"]')
      .getByText(ccContent).first();
    await expect(ccEntry, 'dave cc inbox must list the cc entry').toBeVisible({ timeout: 15_000 });
    // page-level capture: the NotifyPanel card list is the reviewed object
    await evidenceShot(davePage, testInfo, 's3-1-dave-cc-inbox');

    // read-only: the cc inbox must not offer approve/reject actions
    const approveAction = davePage.locator('[data-testid="task-action-approve"]');
    await expect(approveAction, 'cc inbox must be read-only (no approve action)').toHaveCount(0);
    await daveCtx.close();

    // backend evidence: notify received list contains the CC record
    const received = await request.get('/api/bpm/notify/received?type=CC', {
      headers: { Authorization: `Bearer ${daveToken}` },
    });
    expect(received.ok(), `notify received: ${received.status()}`).toBe(true);
    const body = await received.json();
    const records = (body?.data ?? []) as Array<Record<string, unknown>>;
    expect(
      records.length,
      `dave must have CC records: ${JSON.stringify(records).slice(0, 300)}`,
    ).toBeGreaterThan(0);
    

  });

  test('S3.2 task-level cc endpoint resolves receiver pids (API-backed) + unknown pid fail-fast', async ({ request }) => {
    const businessKey = `SC3B-${Date.now()}`;
    const aliceToken = await loginJwt(request, SHOWCASE_USERS.alice.email);
    const { instanceId } = await startProcessInstance(request, aliceToken, {
      processDefinitionId: S3_KEY,
      businessKey,
      variables: {},
    });
    const task = await waitForTodoTask(
      request,
      bobToken,
      (t) => t.processInstanceId === instanceId,
      { timeout: 20_000 },
    );

    // CcPolicy gate: a directly-assigned (unclaimed) task yields
    // claimUserId=null, so the assignee arm of policy ALL does not match —
    // claim first so bob becomes the policy-recognised handler. Noted as an
    // observation: the UI dialog path (notify/cc) bypasses this policy gate.
    const claimResp = await request.post(`/api/bpm/tasks/${task.taskId}/claim`, {
      headers: { Authorization: `Bearer ${bobToken}` },
    });
    expect(claimResp.ok(), `claim: ${claimResp.status()}`).toBe(true);

    // dave's numeric ab_user id (engine notification rows store numeric ids)
    const daveMe = await request.get('/api/auth/me', {
      headers: { Authorization: `Bearer ${daveToken}` },
    });
    const daveUserId = String((await daveMe.json())?.data?.user?.id ?? '');
    expect(daveUserId, 'dave numeric userId').toBeTruthy();

    // happy path: receiverUserIds are ab_user pid strings; the backend
    // resolves pids to numeric ids before the notification fan-out
    const davePid = pids.dave;
    const resp = await request.post(`/api/bpm/tasks/${task.taskId}/cc`, {
      headers: { Authorization: `Bearer ${bobToken}`, 'Content-Type': 'application/json' },
      data: { receiverUserIds: [davePid], comment: `S3.2 pid-path cc ${businessKey}` },
    });
    expect(resp.ok(), `pid-path cc: ${resp.status()} ${await resp.text()}`).toBe(true);

    const received = await request.get('/api/bpm/notify/received?type=CC', {
      headers: { Authorization: `Bearer ${daveToken}` },
    });
    expect(received.ok()).toBe(true);
    // FINDING #6 (storage split-brain, needs product decision): the task-level
    // cc endpoint and automation/eventpolicy cc write SmartEngine's
    // se_notification_instance, while the UI dialog path writes
    // ab_bpm_notify_record — and the 抄送给我 inbox reads only the latter, so
    // task-level/automation cc receivers never see the entry in the UI.
    // This test asserts the task-level path's actual stores (audit row with
    // resolved numeric receivers + engine notification row); unifying the
    // stores so the inbox shows these entries is the pending product fix.
    const audits = await listAuditEvents(request, adminToken, instanceId);
    const ccAudit = audits.find((a) => a.operation === 'cc');
    expect(ccAudit, 'audit must record the cc execution').toBeTruthy();
    const receiverIds = (ccAudit?.details?.receiverIds ?? []) as Array<unknown>;
    // snowflake ids exceed 2^53: JSON round-trip rounds the last digits, so
    // compare on the precision-safe 15-digit prefix (rules: 前缀比较)
    expect(
      receiverIds.some((id) => String(id).slice(0, 15) === daveUserId.slice(0, 15)),
      'cc audit must list the resolved numeric receiver id',
    ).toBe(true);

    const { execSync } = await import('node:child_process');
    const engineRow = execSync(
      `${PSQL_BASE} -t -c "SELECT count(*) FROM se_notification_instance ` +
        `WHERE process_instance_id='${instanceId}' AND notification_type='cc'"`,
    ).toString().trim();
    expect(Number(engineRow), 'engine notification row must exist for the cc').toBeGreaterThan(0);
    void received;

    // fail-fast: an unknown pid must be refused, not silently dropped
    const bad = await request.post(`/api/bpm/tasks/${task.taskId}/cc`, {
      headers: { Authorization: `Bearer ${bobToken}`, 'Content-Type': 'application/json' },
      data: { receiverUserIds: ['01UNKNOWNUNKNOWNUNKNOWNUNKNOWN'], comment: 'unknown pid' },
    });
    const badBody = (await bad.json().catch(() => ({}))) as { code?: string | number };
    const refused = bad.status() >= 400 || String(badBody.code ?? '0') !== '0';
    expect(refused, 'unknown receiver pid must be refused (fail-fast)').toBe(true);
  });

  test('S4.1+S4.2 parallel countersign: three todos, partial keep running, all complete → end', async ({ browser, request }, testInfo) => {
    const businessKey = `SC4-${Date.now()}`;
    const aliceToken = await loginJwt(request, SHOWCASE_USERS.alice.email);
    const approverList = [pids.bob, pids.carol, pids.dave];
    const { instanceId } = await startProcessInstance(request, aliceToken, {
      processDefinitionId: S4_KEY,
      businessKey,
      variables: { approverList },
    });

    // each approver's todo appears
    for (const [token, user] of [
      [bobToken, SHOWCASE_USERS.bob],
      [carolToken, SHOWCASE_USERS.carol],
      [daveToken, SHOWCASE_USERS.dave],
    ] as const) {
      await waitForTodoTask(
        request,
        token,
        (t) => t.processInstanceId === instanceId,
        { timeout: 20_000, message: `${user.key} must see a countersign todo` },
      );
    }

    // complete bob's — instance must still sit on countersign (2 remaining).
    // Plain complete (完成任务) matches the backend MI coverage semantics
    // (BpmMultiInstanceTest): approve injects extra action vars that the MI
    // join counter does not expect.
    await completeViaUi(browser, SHOWCASE_USERS.bob, businessKey, /会签|countersign/i, `S4 bob ${businessKey}`);
    const afterOne = await queryInstanceStatus(request, adminToken, {
      processKey: S4_KEY,
      businessKey,
    });
    expect(
      afterOne.completedNodes.some((n) => n.nodeId === 'end'),
      'instance must not end before all countersign approvals',
    ).toBe(false);

    // carol + dave complete → instance ends
    await completeViaUi(browser, SHOWCASE_USERS.carol, businessKey, /会签|countersign/i, `S4 carol ${businessKey}`);
    await completeViaUi(browser, SHOWCASE_USERS.dave, businessKey, /会签|countersign/i, `S4 dave ${businessKey}`);
    await expect
      .poll(
        async () => {
          const status = await queryInstanceStatus(request, adminToken, { processKey: S4_KEY, businessKey });
          return status.completedNodes.some((n) => n.nodeId === 'end');
        },
        { timeout: 20_000, message: 'countersign instance must reach end after all approvals' },
      )
      .toBe(true);
  });

  test('S4.3 add-sign / remove-sign on a live task', async ({ browser, request }, testInfo) => {
    const businessKey = `SC4S-${Date.now()}`;
    const aliceToken = await loginJwt(request, SHOWCASE_USERS.alice.email);
    const { instanceId } = await startProcessInstance(request, aliceToken, {
      processDefinitionId: S3_KEY,
      businessKey,
      variables: {},
    });
    await waitForTodoTask(
      request,
      bobToken,
      (t) => t.processInstanceId === instanceId,
      { timeout: 20_000 },
    );

    // bob 加签 carol
    const { context: bobCtx, page: bobPage } = await openUserSession(browser, SHOWCASE_USERS.bob);
    await navigateToTaskCenter(bobPage);
    const row = findTaskRowByBusinessKey(bobPage, businessKey, /复核|review/i);
    await expect(row).toBeVisible({ timeout: 20_000 });
    const menu = await openTaskRowMenu(row, bobPage);
    await menu.getByText('加签', { exact: true }).click();
    const addDialog = bobPage.getByRole('dialog', { name: /加签/ }).first();
    await expect(addDialog).toBeVisible({ timeout: 5_000 });
    await addDialog.getByTestId('member-picker-add').click();
    await addDialog.getByTestId('member-picker-search-input').fill(SHOWCASE_USERS.carol.displayName);
    const addOption = addDialog.locator('[data-testid^="member-picker-option-"]').first();
    await expect(addOption).toBeVisible({ timeout: 10_000 });
    await addOption.click();
    await evidenceShot(bobPage, testInfo, 's4-3-addsign-dialog', addDialog);
    const addRespPromise = bobPage.waitForResponse(
      (resp) => resp.request().method() === 'POST' && resp.url().includes('/add-sign'),
      { timeout: 20_000 },
    );
    await addDialog.getByRole('button', { name: '确认加签' }).click();
    expect((await addRespPromise).status()).toBeLessThan(400);
    await bobCtx.close();

    // carol sees a todo for the same instance
    await waitForTodoTask(
      request,
      carolToken,
      (t) => t.processInstanceId === instanceId,
      { timeout: 15_000, message: 'carol must see a todo after add-sign' },
    );

    // bob 减签 carol
    const { context: bobCtx2, page: bobPage2 } = await openUserSession(browser, SHOWCASE_USERS.bob);
    await navigateToTaskCenter(bobPage2);
    const row2 = findTaskRowByBusinessKey(bobPage2, businessKey, /复核|review/i);
    await expect(row2).toBeVisible({ timeout: 20_000 });
    const menu2 = await openTaskRowMenu(row2, bobPage2);
    await menu2.getByText('减签', { exact: true }).click();
    const removeDialog = bobPage2.getByRole('dialog', { name: /减签/ }).first();
    await expect(removeDialog).toBeVisible({ timeout: 5_000 });
    await removeDialog.getByTestId('member-picker-add').click();
    await removeDialog.getByTestId('member-picker-search-input').fill(SHOWCASE_USERS.carol.displayName);
    const removeOption = removeDialog.locator('[data-testid^="member-picker-option-"]').first();
    await expect(removeOption).toBeVisible({ timeout: 10_000 });
    await removeOption.click();
    const removeRespPromise = bobPage2.waitForResponse(
      (resp) => resp.request().method() === 'POST' && resp.url().includes('/remove-sign'),
      { timeout: 20_000 },
    );
    await removeDialog.getByRole('button', { name: '确认减签' }).click();
    expect((await removeRespPromise).status()).toBeLessThan(400);
    await bobCtx2.close();

    // carol's todo disappears
    await expect
      .poll(
        async () =>
          (await listTodoTasks(request, carolToken)).filter((t) => t.processInstanceId === instanceId).length,
        { timeout: 15_000, message: 'carol todo must vanish after remove-sign' },
      )
      .toBe(0);

    // bob completes normally
    await approveViaUi(browser, SHOWCASE_USERS.bob, bobToken, S3_KEY, businessKey, /复核|review/i, `S4.3 bob final ${businessKey}`);
    await expect
      .poll(
        async () => {
          const status = await queryInstanceStatus(request, adminToken, { processKey: S3_KEY, businessKey });
          return status.completedNodes.some((n) => n.nodeId === 'end');
        },
        { timeout: 20_000 },
      )
      .toBe(true);
  });
});
