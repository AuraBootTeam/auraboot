import { expect, test } from '@playwright/test';
import {
  createLeaveApplicant,
  ensureRoleUsers,
  loginAs,
  loginViaUI,
  setLeaveBalance,
  submitLeaveRequest,
} from '../../helpers/wd-fixtures';
import { waitForTodoTask, listTodoTasks, listAuditEvents } from '../bpm/_helpers/bpm-lifecycle';
import { findTaskRowByBusinessKey, openTaskCenterAsRole } from '../bpm/_helpers/task-center';

test.describe('workflow-demo — R5 applicant cancel', () => {
  test.setTimeout(45_000);

  test('applicant withdraws submitted leave before manager action', async ({
    browser,
    request,
  }) => {
    const adminToken = await loginAs(request, 'admin@example.com', 'Test2026x');
    const { managerToken } = await ensureRoleUsers(request);
    const applicant = await createLeaveApplicant(request, adminToken, 'r5_cancel');
    await setLeaveBalance(request, adminToken, applicant.userId, 20);

    const applicantContext = await browser.newContext({ storageState: { cookies: [], origins: [] } });
    const applicantPage = await applicantContext.newPage();
    await loginViaUI(applicantPage, applicant.email, 'Test2026x');

    const { recordId } = await submitLeaveRequest(applicantPage, {
      userId: applicant.userId,
      token: applicant.token,
      days: 2,
      type: 'annual',
      reason: 'R5 cancel test — applicant withdraws before manager acts',
    });

    const pendingTask = await waitForTodoTask(
      request,
      managerToken,
      (task) => task.businessKey === recordId,
      {
        timeout: 20_000,
        message: `expected manager todo task for leave record ${recordId}`,
      },
    );

    await applicantPage.goto(`/p/wd_leave_request/view/${recordId}`, {
      waitUntil: 'domcontentloaded',
    });

    const cancelButton = applicantPage.getByTestId('toolbar-btn-cancel');
    await expect(cancelButton).toBeVisible({ timeout: 10_000 });
    await expect(cancelButton).toBeEnabled();
    await cancelButton.click();
    await expect(applicantPage.getByTestId('form-field-wd_req_status')).toContainText(/已撤销|cancelled/i, {
      timeout: 10_000,
    });

    await expect
      .poll(
        async () => {
          const detailResp = await applicantPage.request.get(`/api/dynamic/wd_leave_request_detail/${recordId}`);
          if (!detailResp.ok()) return null;
          const detailBody = await detailResp.json();
          return (detailBody?.data ?? null) as Record<string, unknown> | null;
        },
        { timeout: 10_000, message: 'expected leave detail API to reflect cancelled status' },
      )
      .toMatchObject({ wd_req_status: 'cancelled' });

    const detailResp = await applicantPage.request.get(`/api/dynamic/wd_leave_request_detail/${recordId}`);
    expect(detailResp.ok()).toBeTruthy();
    const detailBody = await detailResp.json();
    const refreshedRecord = detailBody?.data as Record<string, unknown> | undefined;
    const processInstanceId = refreshedRecord?.wd_req_process_instance;
    expect(typeof processInstanceId).toBe('string');

    await expect
      .poll(
        async () => {
          const tasks = await listTodoTasks(request, managerToken);
          return tasks.some((task) => task.businessKey === recordId);
        },
        {
          timeout: 20_000,
          message: `expected no pending todo task after applicant withdraws ${recordId}`,
        },
      )
      .toBe(false);

    const auditEvents = await listAuditEvents(request, adminToken, String(processInstanceId));
    expect(auditEvents.some((event) => event.operation === 'withdraw')).toBe(true);

    const { context: managerContext, page: managerPage } = await openTaskCenterAsRole(
      browser,
      'wd_manager@example.com',
      'Test2026x',
    );
    const taskRow = findTaskRowByBusinessKey(managerPage, recordId, /主管审批|Manager Approve/i);
    await expect(taskRow).toHaveCount(0);

    expect(pendingTask.processInstanceId).toBe(String(processInstanceId));

    await applicantContext.close();
    await managerContext.close();
  });
});
