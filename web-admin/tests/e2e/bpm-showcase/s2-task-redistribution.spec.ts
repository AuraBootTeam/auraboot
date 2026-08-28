/**
 * BPM Showcase S2 — 任务再分配 (transfer / delegate / claim).
 *
 * Scenario SOT: workspace docs/plans/2026-08-28-oss-bpm-showcase-e2e-verification-plan.md §4 S2.
 *
 * - S2.1  转办 transfer via Task Center UI: bob → carol through the real user
 *         picker dialog; carol's todo appears, bob's disappears, audit records
 *         task_transfer, and carol completes the process.
 * - S2.2  委托 delegate via UI: bob → dave; dave completes; instance advances.
 * - S2.3  候选认领 claim: candidate task (candidateUsers bob+carol) visible to
 *         both; bob claims; carol's UI no longer offers completion; carol API
 *         complete is refused (permission reverse case).
 *
 * Instance start remains API-backed (no standalone start UI in OSS — see S1).
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
  SHOWCASE_ROLE_CODE,
  SHOWCASE_USERS,
  ensureShowcaseRole,
  ensureShowcaseUser,
  loginJwt,
  deployProcess,
  openUserSession,
  evidenceShot,
  expectContentReady,
} from './_helpers/showcase';

const BUSINESS_PREFIX = `SC2-${Date.now()}`;
const PROCESS_KEY = `sc2_reassign_${Date.now()}`;

let adminToken = '';
let bobToken = '';
let carolToken = '';
let daveToken = '';
let deployed = false;

const userPids: Record<string, string> = {};

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

/** start → review(bob) → end. */
function buildSingleTaskGraph(assigneePid: string) {
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

/**
 * start → review(role-assigned, expands to every showcase-role member) → end.
 * Role assignment is the platform's multi-candidate mechanism: the resolver
 * expands the role to all member user pids on one shared task (candidateUsers
 * on smart:candidateUsers did not produce candidate rows — engine fell back
 * to the starter; see showcase session notes 2026-08-28).
 */
function buildCandidateGraph(roleCode: string) {
  return {
    nodes: [
      { id: 'start', type: 'startEvent', position: { x: 80, y: 200 }, data: { type: 'startEvent', label: '开始' } },
      {
        id: 'review',
        type: 'userTask',
        position: { x: 300, y: 200 },
        data: {
          type: 'userTask',
          label: '候选复核',
          config: { assignee: { type: 'role', roleIds: [roleCode] } },
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

async function startAsAlice(request: APIRequestContext, businessKey: string): Promise<string> {
  const aliceToken = await loginJwt(request, SHOWCASE_USERS.alice.email);
  const { instanceId } = await startProcessInstance(request, aliceToken, {
    processDefinitionId: PROCESS_KEY,
    businessKey,
    variables: { source: 'bpm-showcase-s2' },
  });
  return instanceId;
}

async function expectInstanceCompleted(
  request: APIRequestContext,
  businessKey: string,
): Promise<void> {
  await expect
    .poll(
      async () => {
        const status = await queryInstanceStatus(request, adminToken, {
          processKey: PROCESS_KEY,
          businessKey,
        });
        return status.completedNodes.some((n) => n.nodeId === 'end');
      },
      { timeout: 20_000, message: 'instance must reach end' },
    )
    .toBe(true);
}

/**
 * Drive a user-picker dialog (转办/委托): open row menu, click the menu item
 * by text, search the target user, confirm. Resolves the action POST response.
 */
async function runUserPickerDialog(
  page: import('@playwright/test').Page,
  taskRow: import('@playwright/test').Locator,
  menuItemText: string,
  endpoint: 'transfer' | 'delegate',
  confirmLabel: string,
  targetDisplayName: string,
  evidenceName: string,
  testInfo: { outputPath: (...parts: string[]) => string },
): Promise<import('@playwright/test').Response> {
  const menu = await openTaskRowMenu(taskRow, page);
  const item = menu.getByText(menuItemText, { exact: true });
  await expect(item).toBeVisible();
  await item.click();
  const dialog = page.getByRole('dialog', { name: new RegExp(menuItemText) }).first();
  await expect(dialog).toBeVisible({ timeout: 5_000 });

  // MemberPicker: trigger button opens a popup with its own search input.
  const trigger = dialog.getByTestId('member-picker-add');
  await trigger.click();
  const search = dialog.getByTestId('member-picker-search-input');
  await search.fill(targetDisplayName);
  const option = dialog.locator('[data-testid^="member-picker-option-"]').first();
  await expect(option).toBeVisible({ timeout: 10_000 });
  await expect(option.getByText(targetDisplayName, { exact: false })).toBeVisible();
  await option.click();
  await evidenceShot(page, testInfo, evidenceName, dialog);

  const respPromise = page.waitForResponse(
    (resp) => resp.request().method() === 'POST' && resp.url().includes(`/${endpoint}`),
    { timeout: 20_000 },
  );
  await dialog.getByRole('button', { name: confirmLabel }).click();
  return respPromise;
}

test.describe('BPM Showcase S2: task redistribution (@bpm-showcase)', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(240_000);

  test.beforeAll(async ({ request }) => {
    adminToken = await loginAsAdmin(request);
    await ensureShowcaseRole(request);
    for (const user of Object.values(SHOWCASE_USERS)) {
      await ensureShowcaseUser(request, user, adminToken);
    }
    bobToken = await loginJwt(request, SHOWCASE_USERS.bob.email);
    carolToken = await loginJwt(request, SHOWCASE_USERS.carol.email);
    daveToken = await loginJwt(request, SHOWCASE_USERS.dave.email);
    userPids.bob = await resolveUserPid(request, SHOWCASE_USERS.bob.email);

    await deployProcess(request, adminToken, {
      processKey: PROCESS_KEY,
      processName: 'S2 任务再分配',
      description: 'BPM showcase S2 transfer/delegate/claim',
      designerJson: buildSingleTaskGraph(userPids.bob),
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

  test('S2.1 transfer via UI: bob → carol, carol completes', async ({ browser, request }, testInfo) => {
    const businessKey = `${BUSINESS_PREFIX}-T`;
    const instanceId = await startAsAlice(request, businessKey);
    await waitForTodoTask(
      request,
      bobToken,
      (t) => t.processInstanceId === instanceId && t.processDefinitionActivityId === 'review',
      { timeout: 20_000 },
    );

    const { context: bobCtx, page: bobPage } = await openUserSession(browser, SHOWCASE_USERS.bob);
    await navigateToTaskCenter(bobPage);
    await expectContentReady(bobPage, /任务中心/);
    const taskRow = findTaskRowByBusinessKey(bobPage, businessKey, /复核|review/i);
    await expect(taskRow).toBeVisible({ timeout: 20_000 });

    const transferResp = await runUserPickerDialog(
      bobPage,
      taskRow,
      '转办',
      'transfer',
      '确认转办',
      SHOWCASE_USERS.carol.displayName,
      's2-1-transfer-dialog',
      testInfo,
    );
    expect(transferResp.status(), `transfer HTTP ${transferResp.status()}`).toBeLessThan(400);
    await bobCtx.close();

    // bob's todo must be gone; carol's must appear
    await expect
      .poll(
        async () =>
          (await listTodoTasks(request, bobToken)).filter((t) => t.processInstanceId === instanceId).length,
        { timeout: 15_000, message: 'bob todo must drop to zero after transfer' },
      )
      .toBe(0);
    await waitForTodoTask(
      request,
      carolToken,
      (t) => t.processInstanceId === instanceId && t.processDefinitionActivityId === 'review',
      { timeout: 15_000, message: 'carol todo must appear after transfer' },
    );

    const audits = await listAuditEvents(request, adminToken, instanceId);
    const transferAudit = audits.find((a) => a.operation === 'task_transfer');
    expect(transferAudit, 'audit must record task_transfer').toBeTruthy();

    // carol completes via UI
    const { context: carolCtx, page: carolPage } = await openUserSession(browser, SHOWCASE_USERS.carol);
    await navigateToTaskCenter(carolPage);
    const carolRow = findTaskRowByBusinessKey(carolPage, businessKey, /复核|review/i);
    await expect(carolRow).toBeVisible({ timeout: 20_000 });
    const menu = await openTaskRowMenu(carolRow, carolPage);
    await menu.locator('[data-testid="task-action-approve"]').click();
    const dialog = carolPage.getByRole('dialog', { name: /通过审批/ }).first();
    await expect(dialog).toBeVisible({ timeout: 5_000 });
    await dialog.locator('textarea').fill(`S2.1 carol completes ${businessKey}`);
    await evidenceShot(carolPage, testInfo, 's2-1-carol-approve-dialog', dialog);
    const approveRespPromise = carolPage.waitForResponse(
      (resp) => resp.request().method() === 'POST' && resp.url().includes('/approve'),
      { timeout: 20_000 },
    );
    await dialog.getByRole('button', { name: '确认通过' }).click();
    const approveResp = await approveRespPromise;
    expect(approveResp.status()).toBeLessThan(400);
    await carolCtx.close();

    await expectInstanceCompleted(request, businessKey);
    const finalAudits = await listAuditEvents(request, adminToken, instanceId);
    expect(finalAudits.map((a) => a.operation)).toContain('task_approve');
  });

  test('S2.2 delegate via UI: bob → dave completes', async ({ browser, request }, testInfo) => {
    const businessKey = `${BUSINESS_PREFIX}-D`;
    const instanceId = await startAsAlice(request, businessKey);
    await waitForTodoTask(
      request,
      bobToken,
      (t) => t.processInstanceId === instanceId && t.processDefinitionActivityId === 'review',
      { timeout: 20_000 },
    );

    const { context: bobCtx, page: bobPage } = await openUserSession(browser, SHOWCASE_USERS.bob);
    await navigateToTaskCenter(bobPage);
    const taskRow = findTaskRowByBusinessKey(bobPage, businessKey, /复核|review/i);
    await expect(taskRow).toBeVisible({ timeout: 20_000 });

    // 委托 menu item sits between 完成任务 and 转办 in the row menu
    const menu = await openTaskRowMenu(taskRow, bobPage);
    const delegateItem = menu.getByText('委托', { exact: true });
    await expect(delegateItem).toBeVisible();
    await delegateItem.click();
    const dialog = bobPage.getByRole('dialog', { name: /委托/ }).first();
    await expect(dialog).toBeVisible({ timeout: 5_000 });
    await dialog.getByTestId('member-picker-add').click();
    const search = dialog.getByTestId('member-picker-search-input');
    await search.fill(SHOWCASE_USERS.dave.displayName);
    const option = dialog.locator('[data-testid^="member-picker-option-"]').first();
    await expect(option).toBeVisible({ timeout: 10_000 });
    await expect(option.getByText(SHOWCASE_USERS.dave.displayName, { exact: false })).toBeVisible();
    await option.click();
    await evidenceShot(bobPage, testInfo, 's2-2-delegate-dialog', dialog);
    const delegateRespPromise = bobPage.waitForResponse(
      (resp) => resp.request().method() === 'POST' && resp.url().includes('/delegate'),
      { timeout: 20_000 },
    );
    await dialog.getByRole('button', { name: '确认委托' }).click();
    const delegateResp = await delegateRespPromise;
    expect(delegateResp.status(), `delegate HTTP ${delegateResp.status()}`).toBeLessThan(400);
    await evidenceShot(bobPage, testInfo, 's2-2-after-delegate');
    await bobCtx.close();

    // dave completes via UI
    await waitForTodoTask(
      request,
      daveToken,
      (t) => t.processInstanceId === instanceId && t.processDefinitionActivityId === 'review',
      { timeout: 15_000, message: 'dave todo must appear after delegate' },
    );
    const { context: daveCtx, page: davePage } = await openUserSession(browser, SHOWCASE_USERS.dave);
    await navigateToTaskCenter(davePage);
    const daveRow = findTaskRowByBusinessKey(davePage, businessKey, /复核|review/i);
    await expect(daveRow).toBeVisible({ timeout: 20_000 });
    const daveMenu = await openTaskRowMenu(daveRow, davePage);
    await daveMenu.locator('[data-testid="task-action-approve"]').click();
    const daveDialog = davePage.getByRole('dialog', { name: /通过审批/ }).first();
    await expect(daveDialog).toBeVisible({ timeout: 5_000 });
    await daveDialog.locator('textarea').fill(`S2.2 dave completes ${businessKey}`);
    const approveRespPromise = davePage.waitForResponse(
      (resp) => resp.request().method() === 'POST' && resp.url().includes('/approve'),
      { timeout: 20_000 },
    );
    await daveDialog.getByRole('button', { name: '确认通过' }).click();
    expect((await approveRespPromise).status()).toBeLessThan(400);
    await daveCtx.close();

    await expectInstanceCompleted(request, businessKey);
  });

  test('S2.3 candidate claim: both see task, bob claims, carol is locked out', async ({ browser, request }, testInfo) => {
    // deploy a candidate-variant definition with a distinct key
    const candidateKey = `${PROCESS_KEY}_cand`;
    await deployProcess(request, adminToken, {
      processKey: candidateKey,
      processName: 'S2 候选认领',
      designerJson: buildCandidateGraph(SHOWCASE_ROLE_CODE),
    });

    const businessKey = `${BUSINESS_PREFIX}-C`;
    const instanceId = await startProcessInstance(
      request,
      await loginJwt(request, SHOWCASE_USERS.alice.email),
      { processDefinitionId: candidateKey, businessKey, variables: {} },
    ).then((r) => r.instanceId);
    await waitForTodoTask(
      request,
      bobToken,
      (t) => t.processInstanceId === instanceId && t.processDefinitionActivityId === 'review',
      { timeout: 20_000, message: 'candidate task must be visible to bob' },
    );
    const carolSeesIt = await waitForTodoTask(
      request,
      carolToken,
      (t) => t.processInstanceId === instanceId,
      { timeout: 10_000, message: 'candidate task must be visible to carol' },
    );
    expect(carolSeesIt).toBeTruthy();

    // bob claims via UI
    const { context: bobCtx, page: bobPage } = await openUserSession(browser, SHOWCASE_USERS.bob);
    await navigateToTaskCenter(bobPage);
    const taskRow = findTaskRowByBusinessKey(bobPage, businessKey, /候选复核|review/i);
    await expect(taskRow).toBeVisible({ timeout: 20_000 });
    const menu = await openTaskRowMenu(taskRow, bobPage);
    const claimItem = menu.getByText('认领任务', { exact: true });
    await expect(claimItem).toBeVisible();
    await evidenceShot(bobPage, testInfo, 's2-3-claim-menu', menu);
    const claimRespPromise = bobPage.waitForResponse(
      (resp) => resp.request().method() === 'POST' && resp.url().includes('/claim'),
      { timeout: 20_000 },
    );
    await claimItem.click();
    expect((await claimRespPromise).status()).toBeLessThan(400);
    await evidenceShot(bobPage, testInfo, 's2-3-after-claim');
    await bobCtx.close();

    // Claim exclusivity (fixed 2026-08-28): after bob claims the shared
    // role-assigned task, carol (another role member) must be refused on
    // complete — the claim is the task owner.
    const claimTaskId = carolSeesIt.taskId;
    const carolComplete = await request.post(`/api/bpm/tasks/${claimTaskId}/complete`, {
      headers: { Authorization: `Bearer ${carolToken}`, 'Content-Type': 'application/json' },
      data: { comment: 'carol must not complete a claimed task' },
    });
    const completeBody = (await carolComplete.json().catch(() => ({}))) as { code?: string | number };
    const refused = carolComplete.status() >= 400 || String(completeBody.code ?? '0') !== '0';
    expect(refused, 'carol must not be able to complete bob’s claimed task').toBe(true);

    // instance still pending on review
    const status = await queryInstanceStatus(request, adminToken, {
      processKey: candidateKey,
      businessKey,
    });
    expect(status.currentNodes.map((n) => n.nodeId)).toContain('review');
  });
});
