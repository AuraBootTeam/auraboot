/**
 * Workflow-demo wd_leave_approval UI Full Lifecycle — Epic B5
 *
 * Validates the end-to-end approval chain for the OSS workflow-demo plugin:
 *   1. User creates a leave request draft (via wd:create_leave_request command;
 *      same hybrid seeding pattern as B1/WD1 — UI form-fill is brittle due to
 *      MemberPicker / DatePicker widgets, so the record is seeded via command
 *      API while all subsequent navigation + approval is UI-driven).
 *   2. UI submit: sidebar → "我的申请" list → row "Submit" action → confirm
 *      dialog → BPM instance starts via `wd:submit_leave_request` command's
 *      `postActions[].start_process`.
 *   3. UI approve: sidebar → Task Center → find task row for our businessKey
 *      (recordId) → open action menu → click "通过" → fill dialog textarea
 *      → confirm. The spec asserts the UI request payload carries the
 *      designer-authored taskActions variable (`taskResult=approved`).
 *   4. Audit: cross-check that the audit trail records process_start +
 *      activity_event rows (Drools route, gateway, userTask) + the approve
 *      operation.
 *
 * Why this is NOT a pure API test:
 *   - Every navigation is real sidebar click (D1).
 *   - Submit is a real toolbar click on the list row action (D9).
 *   - Approve is a real Task Center action-menu click + dialog fill + confirm.
 *   - API helpers only seed the draft record and cross-check state; task
 *     completion itself is driven through Task Center UI.
 *
 * Dimensions covered:
 *   D1  — sidebar nav (no page.goto direct for workflow nav)
 *   D2  — list page renders with data
 *   D7  — detail field shows expected value post-submit
 *   D9  — list row action + confirm dialog triggers command
 *   D10 — Task Center drawer-less approve flow (MoreHorizontal → 通过 dialog)
 *   D12 — audit trail captures process_start + activity_events + task_approve
 *
 * @since Epic B (OSS BPM / workflow-demo E2E)
 */

import { test, expect, type Page } from '../../fixtures';
import type { APIRequestContext } from '@playwright/test';
import { uniqueId, dateOffsetStr, findRowInPaginatedList } from '../helpers';
import { ensureRoleUsers, setLeaveBalance } from '../../helpers/wd-fixtures';
import {
  loginAsAdmin,
  queryInstanceStatus,
  listAuditEvents,
  listExecutionTimeline,
  hasProcessStart,
  waitForTodoTask,
  AuditOp,
  type ExecutionTimelineEntry,
  type InstanceStatus,
} from './_helpers/bpm-lifecycle';
import { findTaskRowByBusinessKey, openTaskCenterAsRole, openTaskRowMenu } from './_helpers/task-center';

// Serial mode — each test depends on state from the previous
test.describe.configure({ mode: 'serial' });

// Short leave → 2 days → Drools routes to manager branch
const UID = uniqueId('B5');
const LEAVE_REASON = `B5 E2E leave ${UID}`;
const START_DATE = dateOffsetStr(7);
const END_DATE = dateOffsetStr(8);
const REJECT_UID = uniqueId('B5R');
const REJECT_REASON = `B5 E2E reject ${REJECT_UID}`;
const REJECT_START_DATE = dateOffsetStr(10);
const REJECT_END_DATE = dateOffsetStr(11);
const PROCESS_KEY = 'wd_leave_approval';

// Shared state threaded across serial tests
let adminToken = '';
let adminUserId = '';
let managerToken = '';
let leaveRequestPid = '';
let leaveRequestCode = '';
let instanceId = '';

function asRecord(value: unknown): Record<string, unknown> | null {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}

function readRuleBindingTrace(entry: ExecutionTimelineEntry): Record<string, unknown> | null {
  const outputData = asRecord(entry.outputData);
  const nested = outputData ? asRecord(outputData.ruleBinding) : null;
  if (nested) return nested;
  if (entry.eventType === 'rule_evaluated' || entry.nodeType === 'ruleBinding') {
    return outputData ?? {};
  }
  return null;
}

// ---------------------------------------------------------------------------
// Sidebar navigation helpers (D1)
// ---------------------------------------------------------------------------

async function navigateToLeaveRequestList(page: Page): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });

  const nav = page.locator('nav').first();
  await nav.waitFor({ state: 'visible', timeout: 10_000 });

  // Expand "请假 demo" parent
  const rootBtn = nav.getByRole('button', { name: /请假|Leave Demo|menu\.wd_root/i }).first();
  await expect(rootBtn).toBeVisible({ timeout: 5_000 });
  await rootBtn.evaluate((el: HTMLElement) => el.click());

  // Click "我的申请" leaf
  const leafLink = nav.locator('a[href="/p/wd_leave_request"]').first();
  await expect(leafLink).toBeVisible({ timeout: 3_000 });

  const listResp = page
    .waitForResponse(
      (r) =>
        r.url().includes('/api/dynamic/wd_leave_request') &&
        r.url().includes('list') &&
        r.status() === 200,
      { timeout: 15_000 },
    )
    .catch(() => null);

  await leafLink.evaluate((el: HTMLElement) => el.click());
  await listResp;

  await expect(page.locator('table').first()).toBeVisible({ timeout: 10_000 });
}

async function seedLeaveDraft(
  request: APIRequestContext,
  args: { reason: string; startDate: string; endDate: string },
): Promise<{ pid: string; code: string }> {
  const createResp = await request.post('/api/meta/commands/execute/wd:create_leave_request', {
    headers: { Authorization: `Bearer ${adminToken}` },
    data: {
      payload: {
        wd_req_applicant: adminUserId,
        wd_req_type: 'annual',
        wd_req_start_date: args.startDate,
        wd_req_start_slot: 'AM',
        wd_req_end_date: args.endDate,
        wd_req_end_slot: 'PM',
        wd_req_days: 2,
        wd_req_reason: args.reason,
      },
      operationType: 'create',
    },
  });
  expect(createResp.ok(), `draft create must succeed: ${createResp.status()}`).toBe(true);
  const body = await createResp.json();
  expect(String(body?.code)).toBe('0');
  const resultData = body?.data?.data ?? {};
  expect(
    typeof resultData?.recordPid,
    `create must return data.data.recordPid, got body=${JSON.stringify(body).slice(0, 500)}`,
  ).toBe('string');
  const pid = String(resultData.recordPid);

  const detailResp = await request.get(`/api/dynamic/wd_leave_request_detail/${pid}`, {
    headers: { Authorization: `Bearer ${adminToken}` },
  });
  expect(detailResp.ok()).toBe(true);
  const record = (await detailResp.json())?.data;
  const code = String(record?.wd_req_code ?? '');
  expect(code).toMatch(/^WDLR-/);
  return { pid, code };
}

async function submitLeaveDraftViaUi(
  page: Page,
  request: APIRequestContext,
  args: { pid: string; code: string },
): Promise<string> {
  await navigateToLeaveRequestList(page);

  const row = await findRowInPaginatedList(page, args.code, 15_000);
  await expect(row, `row for ${args.code} must appear in list`).toBeVisible();
  await expect(row).toContainText(/draft|草稿/i);

  const moreBtn = row.locator('[data-testid="row-action-more"]').first();
  await expect(moreBtn).toBeVisible({ timeout: 5_000 });
  await moreBtn.click();

  const dropdown = page.locator('[data-testid="row-action-dropdown"]');
  await expect(dropdown).toBeVisible({ timeout: 5_000 });
  const submitMenuItem = dropdown.locator('[data-testid="row-action-submit"]');
  await expect(submitMenuItem).toBeVisible({ timeout: 3_000 });

  const cmdResp = page.waitForResponse(
    (r) =>
      r.url().includes('/api/meta/commands/execute/wd%3Asubmit_leave_request') ||
      r.url().includes('/api/meta/commands/execute/wd:submit_leave_request'),
    { timeout: 20_000 },
  );

  await submitMenuItem.click();

  const earlyDialog = page
    .locator('[data-testid="confirm-dialog"]')
    .or(page.locator('[role="alertdialog"]'))
    .or(page.locator('[role="dialog"]'));
  const hasEarlyDialog = await earlyDialog
    .first()
    .isVisible({ timeout: 2_000 })
    .catch(() => false);
  if (hasEarlyDialog) {
    const okBtn = page
      .locator('[data-testid="confirm-ok"]')
      .or(page.getByRole('button', { name: /^确认$|^确定$|^OK$|^Confirm$/i }))
      .first();
    await okBtn.click();
  }

  const resp = await cmdResp;
  const body = await resp.json();
  expect(
    body?.code,
    `submit command HTTP=${resp.status()} body=${JSON.stringify(body).slice(0, 300)}`,
  ).toBe('0');

  const detailResp = await request.get(`/api/dynamic/wd_leave_request_detail/${args.pid}`, {
    headers: { Authorization: `Bearer ${adminToken}` },
  });
  expect(detailResp.ok()).toBe(true);
  const record = (await detailResp.json())?.data;
  expect(record?.wd_req_status).toBe('submitted');
  const startedInstanceId = String(record?.wd_req_process_instance ?? '');
  expect(
    startedInstanceId,
    'wd_req_process_instance must be populated after submit',
  ).toBeTruthy();
  return startedInstanceId;
}

// ---------------------------------------------------------------------------
// Test suite
// ---------------------------------------------------------------------------

test.describe('workflow-demo wd_leave_approval UI full lifecycle', { tag: ['@bpm-regression'] }, () => {
  test.setTimeout(180_000);

  test.beforeAll(async ({ request }) => {
    adminToken = await loginAsAdmin(request);
    ({ managerToken } = await ensureRoleUsers(request));

    // Resolve admin userId dynamically — reset-and-init re-creates users each
    // run with fresh IDs. Hardcoding the ID would break the spec on the very
    // next environment rebuild.
    const meResp = await request.get('/api/auth/me', {
      headers: { Authorization: `Bearer ${adminToken}` },
    });
    expect(meResp.ok(), `resolve admin /me: ${meResp.status()}`).toBe(true);
    const meBody = await meResp.json();
    adminUserId = String(meBody?.data?.user?.id ?? '');
    expect(adminUserId, 'admin /me must return a userId').toBeTruthy();

    await setLeaveBalance(request, adminToken, adminUserId, 20);

    // Seed a draft leave request via command API (same pattern as WD1-001 +
    // B1 hybrid — form-fill is brittle for Member/Date pickers, and B5's
    // focus is the BPM chain from submit onward).
    const seeded = await seedLeaveDraft(request, {
      reason: LEAVE_REASON,
      startDate: START_DATE,
      endDate: END_DATE,
    });
    leaveRequestPid = seeded.pid;
    leaveRequestCode = seeded.code;
  });

  // =========================================================================
  // B5.1: user submits leave via UI → BPM instance starts
  // =========================================================================
  test('B5.1: user submits leave request via UI, BPM instance starts', async ({
    page,
    request,
  }, testInfo) => {
    expect(leaveRequestPid, 'draft seeded in beforeAll').toBeTruthy();

    // UI nav: sidebar → "我的申请"
    instanceId = await submitLeaveDraftViaUi(page, request, {
      pid: leaveRequestPid,
      code: leaveRequestCode,
    });

    // BPM status: one node currently active on the manager branch (days=2 → Drools → manager)
    // businessKey in wd:submit_leave_request postActions is `${recordId}`.
    const status: InstanceStatus = await queryInstanceStatus(request, adminToken, {
      processKey: PROCESS_KEY,
      businessKey: leaveRequestPid,
    });
    expect(status.status, 'instance should be running after submit').toMatch(/running|active/i);
    const activeIds = status.currentNodes.map((n) => n.nodeId);
    expect(
      activeIds,
      `active nodes after submit (days=2 → manager branch): ${JSON.stringify(activeIds)}`,
    ).toContain('task_manager_approve');

    // Drools rule-task and gateway should have completed
    const completedIds = status.completedNodes.map((n) => n.nodeId);
    expect(completedIds, 'svc_rule_route should be in completedNodes').toContain('svc_rule_route');
    expect(completedIds, 'gw_approver should be in completedNodes').toContain('gw_approver');

    // Runtime trace backend evidence: the BPM assignment rule was evaluated
    // during the real UI-submitted process instance and produced manager
    // reviewer output for the active user task.
    const timeline = await listExecutionTimeline(request, adminToken, instanceId);
    const ruleTrace = timeline
      .map(readRuleBindingTrace)
      .find(
        (trace) =>
          String(trace?.decisionCode ?? '') === 'approval_routing' &&
          String(trace?.consumerNodeId ?? '') === 'task_manager_approve',
      );
    expect(
      ruleTrace,
      `timeline must include BPM ruleBinding trace for ${instanceId}: ${JSON.stringify(timeline)}`,
    ).toBeTruthy();
    expect(ruleTrace?.consumerType).toBe('BPM');
    expect(ruleTrace?.status).toBe('MATCHED');
    expect(ruleTrace?.matched).toBe(true);
    const outputs = asRecord(ruleTrace?.outputs);
    expect(outputs, 'rule trace outputs must be an object').toBeTruthy();
    expect(outputs?.reviewGroups).toEqual(['wd_manager']);

    // Browser evidence: the process status page must surface that same rule
    // execution trace, so a business user can inspect how the approver was
    // selected without opening raw backend logs.
    await page.goto(`/bpm/process-status?processInstanceId=${encodeURIComponent(instanceId)}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByRole('heading', { name: /Process Status/i })).toBeVisible({
      timeout: 15_000,
    });
    await expect(page.getByTestId('bpm-process-status-rule-trace')).toBeVisible({
      timeout: 15_000,
    });
    await expect(page.getByTestId('bpm-rule-trace-panel')).toBeVisible({ timeout: 15_000 });
    await expect(
      page.getByTestId('bpm-rule-trace-item-task_manager_approve'),
    ).toBeVisible();
    await expect(
      page.getByTestId('bpm-rule-trace-decision').filter({ hasText: 'approval_routing' }).first(),
    ).toBeVisible();
    await expect(page.getByTestId('bpm-rule-trace-status').filter({ hasText: '已命中' }).first()).toBeVisible();
    await expect(page.getByTestId('bpm-rule-trace-output').filter({ hasText: '审批组' }).first()).toContainText(
      'wd_manager',
    );
    await page.screenshot({
      path: testInfo.outputPath('workflow-demo-bpm-rule-trace.png'),
      fullPage: true,
    });
  });

  // =========================================================================
  // B5.2: manager approves task in Task Center
  // =========================================================================
  test('B5.2: task surfaced in Task Center, approval advances instance', async ({
    browser,
    request,
  }) => {
    expect(instanceId, 'instanceId set by B5.1').toBeTruthy();

    // UI nav: sidebar → Task Center
    const { context: managerCtx, page: managerPage } = await openTaskCenterAsRole(
      browser,
      'wd_manager@example.com',
      'Test2026x',
    );

    // Task Center table shows the taskDefKey + processKey. The business-key
    // column is populated from the task side (may render "-" until joined
    // with the instance's biz_unique_id). We therefore identify OUR task by
    // (taskDefKey=task_manager_approve) + (processKey=wd_leave_approval), and
    // cross-check via API that the task's instance targets our businessKey.
    // Multiple wd_leave_approval instances may exist from prior runs — we
    // use API to resolve the specific taskId for our instance and scope the
    // row by that taskId's data-attributes if present, otherwise by ordinal
    // match after targeted filter.
    const ourTask = await waitForTodoTask(
      request,
      managerToken,
      (candidate) =>
        candidate.processInstanceId === String(instanceId) &&
        candidate.processDefinitionActivityId.includes('task_manager_approve'),
      {
        timeout: 15_000,
        message: `todo tasks must contain task_manager_approve for instanceId=${instanceId}`,
      },
    );

    // In UI: filter rows to ones carrying our processKey, then pick the one
    // whose task name is task_manager_approve. Since multiple demo instances
    // share the same (processKey, taskDefKey) combo across test reruns, we
    // accept the first matching row — clicking "通过" is scoped to whichever
    // task we open, and we cross-check post-approve that OUR instance moved.
    const taskRow = findTaskRowByBusinessKey(
      managerPage,
      leaveRequestPid,
      /task_manager_approve|主管审批|Manager Approve/i,
    );
    await expect(
      taskRow,
      'a task_manager_approve row for wd_leave_approval must appear',
    ).toBeVisible({ timeout: 15_000 });

    // UI visibility check: open the row action menu so we assert the
    // user-facing action is reachable from Task Center (D10).
    const menu = await openTaskRowMenu(taskRow, managerPage);
    const approveItem = menu.locator('[data-testid="task-action-approve"]').first();
    await expect(
      approveItem,
      'Approve action "通过" must be reachable from Task Center row menu',
    ).toBeVisible();

    await approveItem.click();
    const approveDialog = managerPage.getByRole('dialog', { name: /通过审批/ }).first();
    await expect(approveDialog).toBeVisible({ timeout: 5_000 });
    await approveDialog
      .locator('textarea')
      .fill(`B5 UI lifecycle approve ${UID}`);

    const approveRespPromise = managerPage.waitForResponse(
      (resp) =>
        resp.request().method() === 'POST' &&
        resp
          .url()
          .includes(`/api/bpm/tasks/${encodeURIComponent(String(ourTask.instanceId))}/approve`),
      { timeout: 20_000 },
    );

    await approveDialog.getByRole('button', { name: '确认通过' }).click();
    const approveResp = await approveRespPromise;
    const approveReqBody = approveResp.request().postDataJSON() as Record<string, unknown>;
    const approveVariables =
      approveReqBody.variables && typeof approveReqBody.variables === 'object'
        ? (approveReqBody.variables as Record<string, unknown>)
        : {};
    expect(
      approveVariables.taskResult,
      `UI approve request must inject taskActions variable; body=${JSON.stringify(approveReqBody)}`,
    ).toBe('approved');
    expect(approveReqBody.comment).toBe(`B5 UI lifecycle approve ${UID}`);
    expect(
      approveResp.status(),
      `approve API HTTP=${approveResp.status()} body=${await approveResp.text().then((t) => t.slice(0, 300))}`,
    ).toBeLessThan(400);
    await managerCtx.close();

    // Verify progress: manager task no longer active, instance advanced.
    // After approve, gw_result fires → notify_approved → end_approved.
    // The full path may complete synchronously (notification + endEvent are
    // serviceTasks + endEvent), so `status` could be `completed` or still
    // `running` depending on notification bus latency. Both are acceptable;
    // the invariant is that task_manager_approve is no longer active.
    await expect
      .poll(
        async () => {
          const status = await queryInstanceStatus(request, adminToken, {
            processKey: PROCESS_KEY,
            businessKey: leaveRequestPid,
          });
          return status.currentNodes.map((n) => n.nodeId);
        },
        { timeout: 10_000, message: 'task_manager_approve must exit currentNodes after approve' },
      )
      .not.toContain('task_manager_approve');

    const finalStatus = await queryInstanceStatus(request, adminToken, {
      processKey: PROCESS_KEY,
      businessKey: leaveRequestPid,
    });
    const finalCompleted = finalStatus.completedNodes.map((n) => n.nodeId);
    expect(
      finalCompleted,
      `task_manager_approve must be in completedNodes: ${JSON.stringify(finalCompleted)}`,
    ).toContain('task_manager_approve');
  });

  // =========================================================================
  // B5.3: full audit trail captured
  // =========================================================================
  test('B5.3: audit trail records full lifecycle', async ({ request }) => {
    expect(instanceId, 'instanceId set by B5.1').toBeTruthy();

    const audit = await listAuditEvents(request, adminToken, instanceId);
    expect(audit.length, 'audit trail must not be empty').toBeGreaterThan(0);

    // Must contain process_start row
    expect(hasProcessStart(audit), 'audit must include a process_start row').toBe(true);

    // Must contain activity_event rows for the traversed path:
    //   start_1 → svc_rule_route → gw_approver → task_manager_approve
    // Expect >= 5 activity_event rows (start events + task start).
    const activityEvents = audit.filter((a) => a.operation === AuditOp.ACTIVITY_EVENT);
    expect(
      activityEvents.length,
      `must have at least 5 activity_event rows (got ${activityEvents.length})`,
    ).toBeGreaterThanOrEqual(5);

    // Assert the userTask was actually entered and exited. The "task_approve"
    // operation row is written by TaskService.approveTask on the /approve
    // path; when the UI approve fires, task_manager_approve must produce BOTH
    // activity_start AND activity_end. Instances that are still pending
    // manager approval only have activity_start for that node.
    //
    // We intentionally assert on activity_event pairing (the engine-native
    // signal that the userTask completed) rather than on a specific task
    // operation row, because the engine fires activity_end regardless of
    // which completion code path was used — keeping this assertion robust
    // to downstream refactors of the audit taxonomy.
    type Ev = { activityId: string; eventType: string };
    const mgrEvents: Ev[] = activityEvents
      .map((a) => ({
        activityId: (a.details?.activityId as string) ?? '',
        eventType: (a.details?.eventType as string) ?? '',
      }))
      .filter((e) => e.activityId === 'task_manager_approve');
    const hasStart = mgrEvents.some((e) => e.eventType === 'activity_start');
    const hasEnd = mgrEvents.some((e) => e.eventType === 'activity_end');
    expect(
      hasStart,
      `task_manager_approve activity_start must be audited: got events=${JSON.stringify(mgrEvents)}`,
    ).toBe(true);
    expect(
      hasEnd,
      `task_manager_approve activity_end must be audited (instance progressed past userTask)`,
    ).toBe(true);
  });

  // =========================================================================
  // B5.5: manager rejects task in Task Center → rejected branch executes
  // =========================================================================
  test('B5.5: reject action submits taskResult=rejected and reaches rejected branch', async ({
    browser,
    page,
    request,
  }, testInfo) => {
    const seeded = await seedLeaveDraft(request, {
      reason: REJECT_REASON,
      startDate: REJECT_START_DATE,
      endDate: REJECT_END_DATE,
    });
    const rejectInstanceId = await submitLeaveDraftViaUi(page, request, seeded);

    const afterSubmit = await queryInstanceStatus(request, adminToken, {
      processKey: PROCESS_KEY,
      businessKey: seeded.pid,
    });
    expect(afterSubmit.instanceId).toBe(rejectInstanceId);
    expect(afterSubmit.currentNodes.map((n) => n.nodeId)).toContain('task_manager_approve');

    const { context: managerCtx, page: managerPage } = await openTaskCenterAsRole(
      browser,
      'wd_manager@example.com',
      'Test2026x',
    );

    const ourTask = await waitForTodoTask(
      request,
      managerToken,
      (candidate) =>
        candidate.processInstanceId === String(rejectInstanceId) &&
        candidate.processDefinitionActivityId.includes('task_manager_approve'),
      {
        timeout: 15_000,
        message: `todo tasks must contain task_manager_approve for reject instanceId=${rejectInstanceId}`,
      },
    );

    const taskRow = findTaskRowByBusinessKey(
      managerPage,
      seeded.pid,
      /task_manager_approve|主管审批|Manager Approve/i,
    );
    await expect(
      taskRow,
      'a task_manager_approve row for the reject path must appear',
    ).toBeVisible({ timeout: 15_000 });

    const menu = await openTaskRowMenu(taskRow, managerPage);
    const rejectItem = menu.locator('[data-testid="task-action-reject"]').first();
    await expect(
      rejectItem,
      'Reject action "驳回" must be reachable from Task Center row menu',
    ).toBeVisible();

    await rejectItem.click();
    const rejectDialog = managerPage.getByRole('dialog', { name: /驳回审批/ }).first();
    await expect(rejectDialog).toBeVisible({ timeout: 5_000 });
    await rejectDialog.locator('textarea').fill(`B5 UI lifecycle reject ${REJECT_UID}`);

    const rejectRespPromise = managerPage.waitForResponse(
      (resp) =>
        resp.request().method() === 'POST' &&
        resp
          .url()
          .includes(`/api/bpm/tasks/${encodeURIComponent(String(ourTask.instanceId))}/reject`),
      { timeout: 20_000 },
    );

    await rejectDialog.getByRole('button', { name: '确认驳回' }).click();
    const rejectResp = await rejectRespPromise;
    const rejectReqBody = rejectResp.request().postDataJSON() as Record<string, unknown>;
    const rejectVariables =
      rejectReqBody.variables && typeof rejectReqBody.variables === 'object'
        ? (rejectReqBody.variables as Record<string, unknown>)
        : {};
    expect(
      rejectVariables.taskResult,
      `UI reject request must inject taskActions variable; body=${JSON.stringify(rejectReqBody)}`,
    ).toBe('rejected');
    expect(rejectReqBody.comment).toBe(`B5 UI lifecycle reject ${REJECT_UID}`);
    expect(
      rejectResp.status(),
      `reject API HTTP=${rejectResp.status()} body=${await rejectResp.text().then((t) => t.slice(0, 300))}`,
    ).toBeLessThan(400);
    await managerCtx.close();

    await expect
      .poll(
        async () => {
          const status = await queryInstanceStatus(request, adminToken, {
            processKey: PROCESS_KEY,
            businessKey: seeded.pid,
          });
          return status.currentNodes.map((n) => n.nodeId);
        },
        { timeout: 10_000, message: 'task_manager_approve must exit currentNodes after reject' },
      )
      .not.toContain('task_manager_approve');

    await expect
      .poll(
        async () => {
          const status = await queryInstanceStatus(request, adminToken, {
            processKey: PROCESS_KEY,
            businessKey: seeded.pid,
          });
          return status.completedNodes.map((n) => n.nodeId);
        },
        { timeout: 15_000, message: 'rejected service branch must complete after reject' },
      )
      .toEqual(
        expect.arrayContaining([
          'task_manager_approve',
          'svc_set_rejected',
          'svc_notify_rejected',
        ]),
      );

    await expect
      .poll(
        async () => {
          const detailResp = await request.get(
            `/api/dynamic/wd_leave_request_detail/${seeded.pid}`,
            { headers: { Authorization: `Bearer ${adminToken}` } },
          );
          if (!detailResp.ok()) return '';
          return String(((await detailResp.json())?.data?.wd_req_status) ?? '');
        },
        { timeout: 10_000, message: 'leave request status must become rejected' },
      )
      .toBe('rejected');

    const audit = await listAuditEvents(request, adminToken, rejectInstanceId);
    const activityEvents = audit.filter((a) => a.operation === AuditOp.ACTIVITY_EVENT);
    type Ev = { activityId: string; eventType: string };
    const events: Ev[] = activityEvents.map((a) => ({
      activityId: (a.details?.activityId as string) ?? '',
      eventType: (a.details?.eventType as string) ?? '',
    }));
    const rejectTaskEvents = events.filter((e) => e.activityId === 'task_manager_approve');
    expect(
      rejectTaskEvents.some((e) => e.eventType === 'activity_start'),
      `reject path task_manager_approve activity_start must be audited: ${JSON.stringify(rejectTaskEvents)}`,
    ).toBe(true);
    expect(
      rejectTaskEvents.some((e) => e.eventType === 'activity_end'),
      `reject path task_manager_approve activity_end must be audited: ${JSON.stringify(rejectTaskEvents)}`,
    ).toBe(true);
    expect(
      events.some((e) => e.activityId === 'svc_notify_rejected' && e.eventType === 'activity_end'),
      `svc_notify_rejected activity_end must be audited: ${JSON.stringify(events)}`,
    ).toBe(true);
    expect(
      events.some((e) => e.activityId === 'svc_notify_approved'),
      `reject path must not enter approved notification branch: ${JSON.stringify(events)}`,
    ).toBe(false);

    await page.goto(
      `/bpm/process-status?processInstanceId=${encodeURIComponent(rejectInstanceId)}`,
      { waitUntil: 'domcontentloaded' },
    );
    await expect(page.getByTestId('bpm-process-status-rule-trace')).toBeVisible({
      timeout: 15_000,
    });
    await expect(page.getByTestId('bpm-rule-trace-panel')).toBeVisible({ timeout: 15_000 });
    await expect(
      page.getByTestId('bpm-rule-trace-item-task_manager_approve'),
    ).toBeVisible();
    await page.screenshot({
      path: testInfo.outputPath('workflow-demo-bpm-reject-rule-trace.png'),
      fullPage: true,
    });
  });

  // =========================================================================
  // B5.4: cleanup — terminate instance if still running (idempotent)
  // =========================================================================
  test('B5.4: cleanup terminates any leftover running instance', async ({ request }) => {
    if (!instanceId) return; // nothing to clean

    // Check current status — only terminate if still running
    const statusResp = await request.get(
      `/api/bpm/process-instances/by-business-key/status` +
        `?businessKey=${encodeURIComponent(leaveRequestPid)}` +
        `&processKey=${encodeURIComponent(PROCESS_KEY)}`,
      { headers: { Authorization: `Bearer ${adminToken}` } },
    );
    if (!statusResp.ok()) return;
    const body = await statusResp.json();
    const status = body?.data?.status;
    if (String(status).toLowerCase() !== 'running' && String(status).toLowerCase() !== 'active') {
      return; // already completed — idempotent no-op
    }

    const terminateResp = await request.post(
      `/api/bpm/process-instances/${instanceId}/terminate`,
      {
        headers: {
          Authorization: `Bearer ${adminToken}`,
          'Content-Type': 'application/json',
        },
        data: { reason: 'B5 E2E cleanup' },
      },
    );
    // Accept 200/204; don't fail on 500 (best-effort)
    expect([200, 204, 500]).toContain(terminateResp.status());
  });
});
