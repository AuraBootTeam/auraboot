import { test, expect, type APIRequestContext } from '../../fixtures';
import type { Locator, Page } from '@playwright/test';
import {
  createLeaveApplicant,
  ensureRoleUsers,
  loginAs,
  loginViaUI,
} from '../../helpers/wd-fixtures';
import { listAuditEvents, waitForTodoTask } from '../bpm/_helpers/bpm-lifecycle';

test.setTimeout(180_000);

function dateOffsetStr(offsetDays: number): string {
  const d = new Date();
  d.setDate(d.getDate() + offsetDays);
  return d.toISOString().slice(0, 10);
}

interface LeaveRecordSnapshot {
  id: string;
  pid: string;
  code: string;
  status: string;
  processInstanceId: string;
  applicantPid: string;
  reason: string;
  days: string;
}

interface FieldChangeRecord {
  fieldCode: string;
  oldValue: string | null;
  newValue: string | null;
  commandCode: string | null;
}

interface FieldChangeQueryResult {
  status: number;
  records: FieldChangeRecord[];
}

interface ActivityRecord {
  activityType: string;
  commandCode: string | null;
  subject: string | null;
  content: string | null;
}

async function executeCommand(
  request: APIRequestContext,
  token: string,
  commandCode: string,
  data: Record<string, unknown>,
) {
  const resp = await request.post(`/api/meta/commands/execute/${commandCode}`, {
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
    data,
  });
  expect(resp.ok(), `${commandCode} must return 2xx`).toBeTruthy();
  const body = await resp.json();
  expect(String(body?.code), `${commandCode} must succeed`).toBe('0');
  return body;
}

async function createDraftLeave(
  request: APIRequestContext,
  token: string,
  input: {
    applicantPid: string;
    type: string;
    startDate: string;
    endDate: string;
    startSlot: string;
    endSlot: string;
    days: number;
    reason: string;
  },
): Promise<string> {
  const body = await executeCommand(request, token, 'wd:create_leave_request', {
    payload: {
      wd_req_applicant: input.applicantPid,
      wd_req_type: input.type,
      wd_req_start_date: input.startDate,
      wd_req_start_slot: input.startSlot,
      wd_req_end_date: input.endDate,
      wd_req_end_slot: input.endSlot,
      wd_req_days: input.days,
      wd_req_reason: input.reason,
    },
  });
  const recordId = String(body?.data?.data?.recordId ?? '');
  expect(recordId, 'create draft must return recordId').toBeTruthy();
  return recordId;
}

async function submitLeave(
  request: APIRequestContext,
  token: string,
  input: {
    recordId: string;
    applicantPid: string;
    type: string;
    days: number;
    startSlot: string;
    endSlot: string;
  },
): Promise<void> {
  await executeCommand(request, token, 'wd:submit_leave_request', {
    targetRecordId: input.recordId,
    payload: {
      wd_req_applicant: input.applicantPid,
      wd_req_type: input.type,
      wd_req_days: input.days,
      wd_req_start_slot: input.startSlot,
      wd_req_end_slot: input.endSlot,
    },
  });
}

async function fetchLeaveDetail(
  request: APIRequestContext,
  token: string,
  recordId: string,
): Promise<LeaveRecordSnapshot> {
  const resp = await request.get(`/api/dynamic/wd_leave_request_detail/${recordId}`, {
    headers: {
      Authorization: `Bearer ${token}`,
    },
  });
  expect(resp.ok(), `detail ${recordId} must load`).toBeTruthy();
  const body = await resp.json();
  const data = body?.data ?? {};
  return {
    id: String(data.id ?? ''),
    pid: String(data.pid ?? ''),
    code: String(data.wd_req_code ?? ''),
    status: String(data.wd_req_status ?? ''),
    processInstanceId: String(data.wd_req_process_instance ?? ''),
    applicantPid: String(data.wd_req_applicant ?? ''),
    reason: String(data.wd_req_reason ?? ''),
    days: String(data.wd_req_days ?? ''),
  };
}

async function waitForBusinessStatus(
  request: APIRequestContext,
  token: string,
  recordId: string,
  expectedStatus: string,
): Promise<LeaveRecordSnapshot> {
  let latest: LeaveRecordSnapshot | null = null;
  await expect
    .poll(
      async () => {
        latest = await fetchLeaveDetail(request, token, recordId);
        return latest.status;
      },
      {
        timeout: 20_000,
        message: `record ${recordId} should reach status=${expectedStatus}`,
      },
    )
    .toBe(expectedStatus);
  if (!latest) {
    throw new Error(`record ${recordId} should reach status=${expectedStatus}`);
  }
  return latest;
}

async function completeWorkflowTask(
  request: APIRequestContext,
  token: string,
  input: {
    processInstanceId: string;
    nodeId: string;
    action: 'approve' | 'reject';
    comment: string;
  },
): Promise<void> {
  const todo = await waitForTodoTask(
    request,
    token,
    (candidate) =>
      candidate.processInstanceId === input.processInstanceId &&
      candidate.processDefinitionActivityId.includes(input.nodeId),
    {
      timeout: 20_000,
      message: `${input.nodeId} todo task should appear`,
    },
  );

  const taskResult = input.action === 'approve' ? 'approved' : 'rejected';
  const resp = await request.post(`/api/bpm/tasks/${encodeURIComponent(String(todo.instanceId))}/${input.action}`, {
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
    data: {
      comment: input.comment,
      variables: { taskResult },
    },
  });
  expect(resp.status(), `${input.action} ${input.nodeId} must return < 400`).toBeLessThan(400);
}

async function listActivities(
  request: APIRequestContext,
  token: string,
  recordPid: string,
): Promise<ActivityRecord[]> {
  const resp = await request.get(
    `/api/activities?objectModel=wd_leave_request&objectRecord=${encodeURIComponent(recordPid)}&limit=50`,
    {
      headers: { Authorization: `Bearer ${token}` },
    },
  );
  expect(resp.ok(), `activities for ${recordPid} must load`).toBeTruthy();
  const body = await resp.json();
  return Array.isArray(body?.data) ? body.data : [];
}

async function listFieldChanges(
  request: APIRequestContext,
  token: string,
  numericId: string,
): Promise<FieldChangeQueryResult> {
  const resp = await request.get(
    `/api/audit/field-changes?modelCode=wd_leave_request&recordId=${encodeURIComponent(numericId)}`,
    {
      headers: { Authorization: `Bearer ${token}` },
    },
  );
  if (resp.status() === 403) {
    return { status: 403, records: [] };
  }
  expect(resp.ok(), `field changes for ${numericId} must load`).toBeTruthy();
  const body = await resp.json();
  return {
    status: resp.status(),
    records: Array.isArray(body?.data) ? body.data : [],
  };
}

async function openLeaveDetail(page: Page, recordId: string): Promise<void> {
  await page.goto(`/p/wd_leave_request/view/${recordId}`, { waitUntil: 'domcontentloaded' });
  await expect(page).toHaveURL(new RegExp(`/p/wd_leave_request/view/${recordId}$`));
  await expect(page.getByRole('tab', { name: /基本信息|Overview/i }).first()).toBeVisible({
    timeout: 10_000,
  });
}

async function ensureAdminSession(page: Page): Promise<void> {
  await page.goto('/home', { waitUntil: 'domcontentloaded' });
  if (/\/login(?:$|\?)/.test(page.url())) {
    await loginViaUI(page, 'admin@example.com', 'Test2026x');
  }
}

async function openDetailTab(page: Page, name: RegExp, expectedHash: string): Promise<void> {
  const tab = page.getByRole('tab', { name }).first();
  await expect(tab).toBeVisible({ timeout: 10_000 });
  await tab.click();
  await expect(tab).toHaveAttribute('aria-selected', 'true');
  await expect(page).toHaveURL(new RegExp(`${expectedHash}$`));
}

async function expectFieldHistoryTab(main: Locator, pattern?: RegExp): Promise<void> {
  await expect
    .poll(async () => (await main.textContent()) ?? '', {
      timeout: 10_000,
      message: 'field history tab should render a permission, empty, or data state',
    })
    .toMatch(pattern ?? /无审计查看权限|No audit read permission|No change history|暂无变更记录/i);
}

async function assertCommonTabs(page: Page): Promise<void> {
  await expect(page.getByRole('tab', { name: /基本信息|Overview/i }).first()).toBeVisible();
  await expect(page.getByRole('tab', { name: /审批历史|Approval History/i }).first()).toBeVisible();
  await expect(page.getByRole('tab', { name: /流程图|Workflow Diagram/i }).first()).toBeVisible();
  await expect(page.getByRole('tab', { name: /流程轨迹|Activity Timeline/i }).first()).toBeVisible();
  await expect(page.getByRole('tab', { name: /变更历史|Field History/i }).first()).toBeVisible();
}

function dateDisplayPattern(input: string): RegExp {
  const [year, month, day] = input.split('-').map((part) => String(Number(part)));
  return new RegExp(`${year}[/-]${month}[/-]${day}`);
}

async function expectOverviewContains(
  main: Locator,
  input: {
    code?: string;
    status: RegExp;
    reason: string;
    days: string;
    type: RegExp;
    startDate?: string;
    endDate?: string;
    startSlot?: RegExp;
    endSlot?: RegExp;
  },
): Promise<void> {
  if (input.code) {
    await expect(main.locator('[data-testid="form-field-wd_req_code"]').first()).toContainText(input.code);
  }
  await expect(main.locator('[data-testid="form-field-wd_req_status"]').first()).toContainText(input.status);
  await expect(main.locator('[data-testid="form-field-wd_req_reason"]').first()).toContainText(input.reason);
  await expect(main.locator('[data-testid="form-field-wd_req_days"]').first()).toContainText(input.days);
  await expect(main.locator('[data-testid="form-field-wd_req_type"]').first()).toContainText(input.type);
  if (input.startDate) {
    await expect(main.locator('[data-testid="form-field-wd_req_start_date"]').first()).toContainText(
      dateDisplayPattern(input.startDate),
    );
  }
  if (input.endDate) {
    await expect(main.locator('[data-testid="form-field-wd_req_end_date"]').first()).toContainText(
      dateDisplayPattern(input.endDate),
    );
  }
  if (input.startSlot) {
    await expect(main.locator('[data-testid="form-field-wd_req_start_slot"]').first()).toContainText(input.startSlot);
  }
  if (input.endSlot) {
    await expect(main.locator('[data-testid="form-field-wd_req_end_slot"]').first()).toContainText(input.endSlot);
  }
}

async function expectApprovalHistoryMatches(
  page: Page,
  expectedPatterns: RegExp[],
  opts?: { expectEmpty?: boolean },
): Promise<void> {
  const table = page.getByTestId('subtable-table').first();
  if (opts?.expectEmpty) {
    await expect(page.getByTestId('subtable-viewer').first()).toContainText(/No data|暂无数据/i);
    return;
  }
  const viewer = page.getByTestId('subtable-viewer').first();
  await expect(viewer).toBeVisible({ timeout: 10_000 });
  if (!(await table.isVisible({ timeout: 10_000 }).catch(() => false))) {
    await expect(viewer).toContainText(/No data|暂无数据/i);
    return;
  }
  await expect
    .poll(async () => await table.locator('[data-testid^="sortable-row-"]').count(), {
      timeout: 10_000,
      message: 'approval history should render at least one row',
    })
    .toBeGreaterThan(0);
  for (const pattern of expectedPatterns) {
    await expect(viewer).toContainText(pattern);
  }
}

async function expectActivityTimelineHasRecords(
  page: Page,
  activities: ActivityRecord[],
  expectedPatterns: RegExp[],
): Promise<void> {
  if (activities.length === 0) {
    await expect(page.getByTestId('activity-timeline-empty')).toBeVisible();
    return;
  }
  const timeline = page.getByTestId('activity-timeline');
  await expect(timeline).toBeVisible();
  await expect(page.locator('[data-testid^="activity-timeline-item-"]').first()).toBeVisible();
  for (const pattern of expectedPatterns) {
    await expect(timeline).toContainText(pattern);
  }
}

async function expectFieldHistoryHasRecords(
  page: Page,
  query: FieldChangeQueryResult,
  expectedPatterns: RegExp[],
): Promise<void> {
  if (query.status === 403) {
    await expect
      .poll(
        async () => {
          const error = await page.getByTestId('field-history-error').count();
          if (error > 0) {
            return (await page.getByTestId('field-history-error').first().textContent()) ?? '';
          }
          const empty = await page.getByTestId('field-history-empty').count();
          if (empty > 0) {
            return (await page.getByTestId('field-history-empty').first().textContent()) ?? '';
          }
          return '';
        },
        { timeout: 5_000, message: 'field history should show permission fallback state' },
      )
      .toMatch(/无审计查看权限|No audit read permission|暂无变更记录|No change history/i);
    return;
  }
  if (query.records.length === 0) {
    await expect(page.getByTestId('field-history-empty')).toBeVisible();
    return;
  }
  const history = page.getByTestId('field-history');
  await expect(history).toBeVisible();
  await expect(page.locator('[data-testid^="field-history-entry-"]').first()).toBeVisible();
  for (const pattern of expectedPatterns) {
    await expect(history).toContainText(pattern);
  }
}

async function expectBpmHistoryContainsAtLeast(
  page: Page,
  minimumCount: number,
  expectedPatterns: RegExp[],
): Promise<void> {
  const history = page.getByTestId('bpm-history-container').first();
  await expect(history).toBeVisible();
  await expect
    .poll(async () => await page.locator('[data-testid^="bpm-history-event-"]').count(), {
      timeout: 5_000,
      message: 'bpm history should render audit events',
    })
    .toBeGreaterThanOrEqual(minimumCount);
  for (const pattern of expectedPatterns) {
    await expect(history).toContainText(pattern);
  }
}

test.describe('workflow-demo — wd_leave_request detail tabs and status matrix', () => {
  test('covers every implemented detail tab across draft/submitted/cancelled/approved/rejected and manager/hr stages', async ({
    page,
    request,
  }) => {
    const adminToken = await loginAs(request, 'admin@example.com', 'Test2026x');
    const { managerToken, hrToken } = await ensureRoleUsers(request);

    const draftApplicant = await createLeaveApplicant(request, adminToken, 'wd_detail_draft');
    const shortApplicant = await createLeaveApplicant(request, adminToken, 'wd_detail_short');
    const longApplicant = await createLeaveApplicant(request, adminToken, 'wd_detail_long');
    const cancelledApplicant = await createLeaveApplicant(request, adminToken, 'wd_detail_cancelled');

    await ensureAdminSession(page);

    const draftReason = `wd detail draft ${Date.now()}`;
    const shortReason = `wd detail short ${Date.now()}`;
    const longReason = `wd detail long ${Date.now()}`;
    const draftStartDate = dateOffsetStr(10);
    const draftEndDate = draftStartDate;
    const shortStartDate = dateOffsetStr(12);
    const shortEndDate = dateOffsetStr(13);
    const longStartDate = dateOffsetStr(15);
    const longEndDate = dateOffsetStr(18);
    const cancelledStartDate = dateOffsetStr(14);
    const cancelledEndDate = dateOffsetStr(15);

    const draftRecordId = await createDraftLeave(request, draftApplicant.token, {
      applicantPid: draftApplicant.userId,
      type: 'annual',
      startDate: draftStartDate,
      endDate: draftEndDate,
      startSlot: 'AM',
      endSlot: 'AM',
      days: 0.5,
      reason: draftReason,
    });
    const draftRecord = await waitForBusinessStatus(request, adminToken, draftRecordId, 'draft');

    const shortRecordId = await createDraftLeave(request, shortApplicant.token, {
      applicantPid: shortApplicant.userId,
      type: 'annual',
      startDate: shortStartDate,
      endDate: shortEndDate,
      startSlot: 'AM',
      endSlot: 'PM',
      days: 2,
      reason: shortReason,
    });
    await submitLeave(request, shortApplicant.token, {
      recordId: shortRecordId,
      applicantPid: shortApplicant.userId,
      type: 'annual',
      days: 2,
      startSlot: 'AM',
      endSlot: 'PM',
    });
    const shortSubmitted = await waitForBusinessStatus(request, adminToken, shortRecordId, 'submitted');
    await waitForTodoTask(
      request,
      managerToken,
      (candidate) =>
        candidate.processInstanceId === shortSubmitted.processInstanceId &&
        candidate.processDefinitionActivityId.includes('task_manager_approve'),
      {
        timeout: 20_000,
        message: 'manager approval todo should exist before detail assertions',
      },
    );

    const cancelledReason = `wd detail cancelled ${Date.now()}`;
    const cancelledRecordId = await createDraftLeave(request, cancelledApplicant.token, {
      applicantPid: cancelledApplicant.userId,
      type: 'annual',
      startDate: cancelledStartDate,
      endDate: cancelledEndDate,
      startSlot: 'AM',
      endSlot: 'PM',
      days: 2,
      reason: cancelledReason,
    });
    await submitLeave(request, cancelledApplicant.token, {
      recordId: cancelledRecordId,
      applicantPid: cancelledApplicant.userId,
      type: 'annual',
      days: 2,
      startSlot: 'AM',
      endSlot: 'PM',
    });
    const cancelledSubmitted = await waitForBusinessStatus(request, adminToken, cancelledRecordId, 'submitted');
    await executeCommand(request, cancelledApplicant.token, 'wd:cancel_leave_request', {
      targetRecordId: cancelledRecordId,
      payload: {
        reason: 'detail tabs matrix cancellation',
      },
    });
    await waitForBusinessStatus(request, adminToken, cancelledRecordId, 'cancelled');

    const longRecordId = await createDraftLeave(request, longApplicant.token, {
      applicantPid: longApplicant.userId,
      type: 'annual',
      startDate: longStartDate,
      endDate: longEndDate,
      startSlot: 'AM',
      endSlot: 'PM',
      days: 4,
      reason: longReason,
    });
    await submitLeave(request, longApplicant.token, {
      recordId: longRecordId,
      applicantPid: longApplicant.userId,
      type: 'annual',
      days: 4,
      startSlot: 'AM',
      endSlot: 'PM',
    });
    const longSubmitted = await waitForBusinessStatus(request, adminToken, longRecordId, 'submitted');
    await waitForTodoTask(
      request,
      hrToken,
      (candidate) =>
        candidate.processInstanceId === longSubmitted.processInstanceId &&
        candidate.processDefinitionActivityId.includes('task_hr_approve'),
      {
        timeout: 20_000,
        message: 'hr approval todo should exist before detail assertions',
      },
    );

    await test.step('draft detail page: all tabs render and non-process tabs degrade correctly', async () => {
      const draftActivities = await listActivities(request, adminToken, draftRecord.pid);
      const draftChanges = await listFieldChanges(request, adminToken, draftRecord.id);

      await openLeaveDetail(page, draftRecordId);
      await assertCommonTabs(page);

      const main = page.locator('main').first();
      await expectOverviewContains(main, {
        code: draftRecord.code,
        status: /草稿|Draft/i,
        reason: draftReason,
        days: '0.5',
        type: /年假|annual/i,
        startDate: draftStartDate,
        endDate: draftEndDate,
        startSlot: /上午|AM/i,
        endSlot: /上午|AM/i,
      });

      await openDetailTab(page, /审批历史|Approval History/i, '#approval_history');
      await expectApprovalHistoryMatches(page, [], { expectEmpty: true });

      await openDetailTab(page, /流程图|Workflow Diagram/i, '#workflow_diagram');
      await expect(page.locator('[data-testid="bpm-panel"]')).toHaveAttribute('data-state', 'empty');
      await expect(page.locator('[data-testid="bpm-panel"]')).toContainText(/No workflow instance|暂无审批流程/i);
      await page.reload({ waitUntil: 'domcontentloaded' });
      await expect(page.getByRole('tab', { name: /流程图|Workflow Diagram/i }).first()).toHaveAttribute(
        'aria-selected',
        'true',
      );

      await openDetailTab(page, /流程轨迹|Activity Timeline/i, '#activity_timeline');
      await expectActivityTimelineHasRecords(page, draftActivities, [/wd:create_leave_request|创建|Created/i, new RegExp(draftReason)]);

      await openDetailTab(page, /变更历史|Field History/i, '#field_history');
      await expectFieldHistoryHasRecords(page, draftChanges, [/wd:create_leave_request|draft|草稿/i, /请假原因|wd_req_reason|wd_req_days/i]);
    });

    await test.step('submitted manager branch: approval history, bpm panel, activity and field history all reflect pending manager approval', async () => {
      const activities = await listActivities(request, adminToken, shortSubmitted.pid);
      const fieldChanges = await listFieldChanges(request, adminToken, shortSubmitted.id);
      const bpmAuditEvents = await listAuditEvents(request, adminToken, shortSubmitted.processInstanceId);

      await openLeaveDetail(page, shortRecordId);
      await assertCommonTabs(page);

      const main = page.locator('main').first();
      await expectOverviewContains(main, {
        code: shortSubmitted.code,
        status: /已提交|Submitted/i,
        reason: shortReason,
        days: '2',
        type: /年假|annual/i,
        startSlot: /上午|AM/i,
        endSlot: /下午|PM/i,
      });
      await expect(
        main.locator('[data-testid="form-field-wd_req_process_instance"]').first(),
      ).not.toContainText(/^[-—]$/);

      await openDetailTab(page, /审批历史|Approval History/i, '#approval_history');
      await expectApprovalHistoryMatches(page, [/task_manager_approve|pending/i]);

      await openDetailTab(page, /流程图|Workflow Diagram/i, '#workflow_diagram');
      await expect(page.locator('[data-testid="bpm-panel"]')).toHaveAttribute('data-state', 'ready');
      await expect(page.locator('[data-testid="bpm-section-status"]')).toBeVisible();
      await expect(page.locator('[data-testid="bpm-section-diagram"]')).toBeVisible();
      await expect(page.locator('[data-testid="bpm-section-operations"]')).toBeVisible();
      await expect(page.locator('[data-testid="bpm-section-history"]')).toBeVisible();
      await expect(page.locator('[data-testid="bpm-status-badge"]')).toHaveAttribute('data-status', 'running');
      await expect(
        page.locator('[data-testid="bpm-status-current-node-task_manager_approve"]'),
      ).toBeVisible();
      await expectBpmHistoryContainsAtLeast(page, bpmAuditEvents.length, [/启动|start|提交|submit|activity_event/i]);

      await openDetailTab(page, /流程轨迹|Activity Timeline/i, '#activity_timeline');
      await expectActivityTimelineHasRecords(page, activities, [/已提交|submitted|状态变更|State Change|wd:submit_leave_request/i, new RegExp(shortReason)]);

      await openDetailTab(page, /变更历史|Field History/i, '#field_history');
      await expectFieldHistoryHasRecords(page, fieldChanges, [/wd:submit_leave_request|submitted|已提交/i, /wd_req_status|状态|wd_req_process_instance/i]);
    });

    await test.step('cancelled applicant branch: detail page reflects withdrawn state across every tab', async () => {
      const activities = await listActivities(request, adminToken, (await fetchLeaveDetail(request, adminToken, cancelledRecordId)).pid);
      const fieldChanges = await listFieldChanges(
        request,
        adminToken,
        (await fetchLeaveDetail(request, adminToken, cancelledRecordId)).id,
      );
      const bpmAuditEvents = await listAuditEvents(request, adminToken, cancelledSubmitted.processInstanceId);

      await openLeaveDetail(page, cancelledRecordId);
      await assertCommonTabs(page);

      const main = page.locator('main').first();
      await expectOverviewContains(main, {
        status: /已撤销|Cancelled/i,
        reason: cancelledReason,
        days: '2',
        type: /年假|annual/i,
        startSlot: /上午|AM/i,
        endSlot: /下午|PM/i,
      });
      await expect(
        main.locator('[data-testid="form-field-wd_req_process_instance"]').first(),
      ).not.toContainText(/^[-—]$/);

      await openDetailTab(page, /审批历史|Approval History/i, '#approval_history');
      await expectApprovalHistoryMatches(page, [/aborted|撤销|cancel/i]);

      await openDetailTab(page, /流程图|Workflow Diagram/i, '#workflow_diagram');
      await expect(page.locator('[data-testid="bpm-panel"]')).toHaveAttribute('data-state', 'ready');
      await expect(page.locator('[data-testid="bpm-history-container"]')).toBeVisible();
      await expect(
        page.locator('[data-testid="bpm-status-current-node-task_manager_approve"]'),
      ).toHaveCount(0);
      await expect(page.locator('[data-testid="bpm-history-container"]')).toContainText(
        /撤销|withdraw|cancel/i,
      );
      await expectBpmHistoryContainsAtLeast(page, bpmAuditEvents.length, [/撤销|withdraw|cancel/i]);

      await openDetailTab(page, /流程轨迹|Activity Timeline/i, '#activity_timeline');
      await expectActivityTimelineHasRecords(page, activities, [/已撤销|cancelled|withdraw|wd:cancel_leave_request/i, new RegExp(cancelledReason)]);

      await openDetailTab(page, /变更历史|Field History/i, '#field_history');
      await expectFieldHistoryHasRecords(page, fieldChanges, [/cancelled|已撤销|wd:cancel_leave_request/i, /wd_req_status|状态/i]);

      expect(cancelledSubmitted.processInstanceId).toBeTruthy();
    });

    await test.step('approved manager branch: detail page reflects terminal approved state across all tabs', async () => {
      const approveComment = `manager approved ${Date.now()}`;
      await completeWorkflowTask(request, managerToken, {
        processInstanceId: shortSubmitted.processInstanceId,
        nodeId: 'task_manager_approve',
        action: 'approve',
        comment: approveComment,
      });

      await waitForBusinessStatus(request, adminToken, shortRecordId, 'approved');
      const approvedRecord = await fetchLeaveDetail(request, adminToken, shortRecordId);
      const activities = await listActivities(request, adminToken, approvedRecord.pid);
      const fieldChanges = await listFieldChanges(request, adminToken, approvedRecord.id);
      const bpmAuditEvents = await listAuditEvents(request, adminToken, shortSubmitted.processInstanceId);

      await openLeaveDetail(page, shortRecordId);
      const main = page.locator('main').first();
      await expectOverviewContains(main, {
        status: /已通过|Approved/i,
        reason: shortReason,
        days: '2',
        type: /年假|annual/i,
        startSlot: /上午|AM/i,
        endSlot: /下午|PM/i,
      });

      await openDetailTab(page, /审批历史|Approval History/i, '#approval_history');
      await expectApprovalHistoryMatches(page, [/completed/i]);

      await openDetailTab(page, /流程图|Workflow Diagram/i, '#workflow_diagram');
      await expect(page.locator('[data-testid="bpm-panel"]')).toHaveAttribute('data-state', 'ready');
      await expect(page.locator('[data-testid="bpm-history-container"]')).toBeVisible();
      await expect(
        page.locator('[data-testid="bpm-status-current-node-task_manager_approve"]'),
      ).toHaveCount(0);
      await expect(page.locator('[data-testid="bpm-history-container"]')).toContainText(
        /审批通过|Approved|task_approve/i,
      );
      await expectBpmHistoryContainsAtLeast(page, bpmAuditEvents.length, [/审批通过|Approved|approve/i]);

      await openDetailTab(page, /流程轨迹|Activity Timeline/i, '#activity_timeline');
      await expectActivityTimelineHasRecords(page, activities, [/已通过|approved|状态变更|State Change/i, new RegExp(approveComment)]);

      await openDetailTab(page, /变更历史|Field History/i, '#field_history');
      await expectFieldHistoryHasRecords(page, fieldChanges, [/approved|已通过/i, /wd_req_status|状态/i]);
    });

    await test.step('submitted hr branch: detail page reflects pending HR approval across every tab', async () => {
      const activities = await listActivities(request, adminToken, longSubmitted.pid);
      const fieldChanges = await listFieldChanges(request, adminToken, longSubmitted.id);
      const bpmAuditEvents = await listAuditEvents(request, adminToken, longSubmitted.processInstanceId);

      await openLeaveDetail(page, longRecordId);
      const main = page.locator('main').first();
      await expectOverviewContains(main, {
        status: /已提交|Submitted/i,
        reason: longReason,
        days: '4',
        type: /年假|annual/i,
        startSlot: /上午|AM/i,
        endSlot: /下午|PM/i,
      });

      await openDetailTab(page, /审批历史|Approval History/i, '#approval_history');
      await expectApprovalHistoryMatches(page, [/task_hr_approve|pending/i]);

      await openDetailTab(page, /流程图|Workflow Diagram/i, '#workflow_diagram');
      await expect(page.locator('[data-testid="bpm-panel"]')).toHaveAttribute('data-state', 'ready');
      await expect(
        page.locator('[data-testid="bpm-status-current-node-task_hr_approve"]'),
      ).toBeVisible();
      await expectBpmHistoryContainsAtLeast(page, bpmAuditEvents.length, [/启动|start|提交|submit|activity_event/i]);

      await openDetailTab(page, /流程轨迹|Activity Timeline/i, '#activity_timeline');
      await expectActivityTimelineHasRecords(page, activities, [/已提交|submitted|状态变更|State Change|wd:submit_leave_request/i, new RegExp(longReason)]);

      await openDetailTab(page, /变更历史|Field History/i, '#field_history');
      await expectFieldHistoryHasRecords(page, fieldChanges, [/wd:submit_leave_request|submitted|已提交/i, /wd_req_status|状态|wd_req_process_instance/i]);
    });

    await test.step('rejected hr branch: detail page reflects terminal rejected state across every tab', async () => {
      const rejectComment = `hr rejected ${Date.now()}`;
      await completeWorkflowTask(request, hrToken, {
        processInstanceId: longSubmitted.processInstanceId,
        nodeId: 'task_hr_approve',
        action: 'reject',
        comment: rejectComment,
      });

      await waitForBusinessStatus(request, adminToken, longRecordId, 'rejected');
      const rejectedRecord = await fetchLeaveDetail(request, adminToken, longRecordId);
      const activities = await listActivities(request, adminToken, rejectedRecord.pid);
      const fieldChanges = await listFieldChanges(request, adminToken, rejectedRecord.id);
      const bpmAuditEvents = await listAuditEvents(request, adminToken, longSubmitted.processInstanceId);

      await openLeaveDetail(page, longRecordId);
      const main = page.locator('main').first();
      await expectOverviewContains(main, {
        status: /已驳回|Rejected/i,
        reason: longReason,
        days: '4',
        type: /年假|annual/i,
        startSlot: /上午|AM/i,
        endSlot: /下午|PM/i,
      });

      await openDetailTab(page, /审批历史|Approval History/i, '#approval_history');
      await expectApprovalHistoryMatches(page, [/completed/i]);

      await openDetailTab(page, /流程图|Workflow Diagram/i, '#workflow_diagram');
      await expect(page.locator('[data-testid="bpm-panel"]')).toHaveAttribute('data-state', 'ready');
      await expect(
        page.locator('[data-testid="bpm-status-current-node-task_hr_approve"]'),
      ).toHaveCount(0);
      await expect(page.locator('[data-testid="bpm-history-container"]')).toContainText(
        /驳回|Rejected|task_reject/i,
      );
      await expectBpmHistoryContainsAtLeast(page, bpmAuditEvents.length, [/驳回|Rejected|reject/i]);

      await openDetailTab(page, /流程轨迹|Activity Timeline/i, '#activity_timeline');
      await expectActivityTimelineHasRecords(page, activities, [/已驳回|rejected|状态变更|State Change/i, new RegExp(rejectComment)]);

      await openDetailTab(page, /变更历史|Field History/i, '#field_history');
      await expectFieldHistoryHasRecords(page, fieldChanges, [/rejected|已驳回/i, /wd_req_status|状态/i]);
    });
  });
});
