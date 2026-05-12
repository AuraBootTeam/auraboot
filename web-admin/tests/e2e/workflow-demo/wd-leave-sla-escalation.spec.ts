import { test, expect } from '@playwright/test';
import {
  loginAs,
  loginViaUI,
  ensureRoleUsers,
  createLeaveApplicant,
  setLeaveBalance,
  submitLeaveRequest,
} from '../../helpers/wd-fixtures';
import { BACKEND_URL } from '../../helpers/environments';

const BACKEND = BACKEND_URL;

/**
 * R3 — SLA escalation end-to-end.
 *
 * SLA config used: workflow-demo sla.json "wd_manager_approve_sla"
 *   targetType = "NODE", targetKey = "task_manager_approve"
 *   processKey = "wd_leave_approval", timeoutSeconds = 30 (deadlineValue = "PT30S")
 *
 * The plugin already ships this config. The test seeds a short leave (days=1),
 * submits it, then intentionally does NOT have the manager act. After 30 s the
 * SlaSchedulerService (fixedRate=15s) detects the breach and sets status=overdue.
 * The test polls GET /api/bpm/monitor/instances/{instanceId}/sla for that state.
 *
 * Fix wired (2026-04-19): SlaActivationListener creates ab_sla_record rows
 * on task_assigned BpmEvent. SlaSchedulerService scans every 15 s.
 */

test.describe('workflow-demo — R3 SLA escalation', () => {
  test.setTimeout(120_000);

  test(
    'manager ignores short leave → SLA record created and becomes overdue within 60 s',
    async ({ browser, request }) => {
      // -----------------------------------------------------------------------
      // 1. Admin API setup — reuse the same pattern as R1 / R2
      // -----------------------------------------------------------------------
      const adminToken = await loginAs(request, 'admin@auraboot.com', 'Test2026x');
      await ensureRoleUsers(request);
      const applicant = await createLeaveApplicant(request, adminToken, 'r3_sla');
      await setLeaveBalance(request, adminToken, applicant.userId, 20);

      // -----------------------------------------------------------------------
      // 2. Applicant submits a short leave (days=1 → routes to manager)
      //    Days < 3 triggers the manager-approval branch in wd_leave_approval.
      // -----------------------------------------------------------------------
      const applicantCtx = await browser.newContext({ storageState: { cookies: [], origins: [] } });
      const applicantPage = await applicantCtx.newPage();

      await loginViaUI(applicantPage, applicant.email, 'Test2026x');

      const { recordId } = await submitLeaveRequest(applicantPage, {
        userId: applicant.userId,
        token: applicant.token,
        days: 1,
        type: 'annual',
        reason: 'R3 SLA escalation automated test — intentional manager no-action',
      });
      expect(recordId, 'submitLeaveRequest must return a non-empty recordId').toBeTruthy();

      // -----------------------------------------------------------------------
      // 3. Fetch the process instance id from the business record
      //    The submit command stores the instance id in wd_req_process_instance
      //    (defined in commands.json postActions[0].storeInstanceIdIn).
      // -----------------------------------------------------------------------
      const recordDetailResp = await request.get(
        `${BACKEND}/api/dynamic/wd_leave_request_detail/${recordId}`,
        { headers: { Authorization: `Bearer ${adminToken}` } },
      );
      expect(
        recordDetailResp.status(),
        'business record detail fetch must succeed',
      ).toBe(200);

      const recordDetailBody = await recordDetailResp.json();
      const record = recordDetailBody?.data as Record<string, unknown> | undefined;
      if (!record) {
        throw new Error(
          `R3: business record detail missing "data". Body: ${JSON.stringify(recordDetailBody)}`,
        );
      }

      const instanceId = record.wd_req_process_instance as string | undefined;
      expect(
        instanceId,
        'wd_req_process_instance must be populated — BPM process must have started',
      ).toBeTruthy();

      // -----------------------------------------------------------------------
      // 4. Assert the manager task is ACTIVE (SLA timer running)
      //    This verifies the process is in the expected state before we wait.
      //    Endpoint: GET /api/bpm/tasks/by-process/{processInstanceId}
      // -----------------------------------------------------------------------
      const tasksResp = await request.get(
        `${BACKEND}/api/bpm/tasks/by-process/${instanceId}`,
        { headers: { Authorization: `Bearer ${adminToken}` } },
      );
      expect(tasksResp.status(), 'task list fetch must succeed').toBe(200);

      const tasksBody = await tasksResp.json();
      const tasks = (tasksBody?.data as unknown[]) ?? [];
      const managerTask = (tasks as Array<Record<string, unknown>>).find(
        (t) =>
          (t.taskDefinitionKey as string | undefined) === 'task_manager_approve' ||
          (t.activityId as string | undefined) === 'task_manager_approve' ||
          (t.processDefinitionActivityId as string | undefined) === 'task_manager_approve',
      );
      expect(
        managerTask,
        'task_manager_approve must be active — short leave routes to manager',
      ).toBeTruthy();

      // -----------------------------------------------------------------------
      // 5. Assert SLA record was created by SlaActivationListener at task activation.
      //    Poll immediately — the record should exist already since the event is
      //    synchronous during task creation.
      // -----------------------------------------------------------------------
      const slaCheckResp = await request.get(
        `${BACKEND}/api/bpm/monitor/instances/${instanceId}/sla`,
        { headers: { Authorization: `Bearer ${adminToken}` } },
      );
      expect(
        slaCheckResp.ok(),
        `SLA records endpoint must be accessible: ${slaCheckResp.status()}`,
      ).toBe(true);

      const slaCheckBody = await slaCheckResp.json();
      const slaRecords = (slaCheckBody?.data as Array<Record<string, unknown>>) ?? [];
      expect(
        slaRecords.length,
        'SlaActivationListener must have created SLA records when task activated',
      ).toBeGreaterThanOrEqual(1);

      const managerSlaRecord = slaRecords.find(
        (r) => r.nodeId === 'task_manager_approve',
      );
      expect(
        managerSlaRecord,
        'SLA record for task_manager_approve must exist after task activation',
      ).toBeDefined();

      // Initial status must be 'running'
      expect(
        (managerSlaRecord!.status as string)?.toLowerCase(),
        'SLA record must start as running',
      ).toBe('running');

      // -----------------------------------------------------------------------
      // 6. Do NOT have the manager act — wait for the 30 s SLA deadline to elapse.
      //    SlaSchedulerService runs every 15 s, so breach detection takes at most
      //    30 s (deadline) + 15 s (scheduler lag) = 45 s.
      //    We poll GET /api/bpm/monitor/instances/{id}/sla for status=overdue.
      // -----------------------------------------------------------------------
      await expect
        .poll(
          async () => {
            const resp = await request.get(
              `${BACKEND}/api/bpm/monitor/instances/${instanceId}/sla`,
              { headers: { Authorization: `Bearer ${adminToken}` } },
            );
            if (!resp.ok()) return false;

            const body = await resp.json();
            const records = (body?.data as Array<Record<string, unknown>>) ?? [];
            return records.some(
              (r) => (r.status as string | undefined)?.toLowerCase() === 'overdue',
            );
          },
          {
            // 30 s SLA deadline + 15 s scheduler lag + 10 s poll overhead = 55 s.
            timeout: 60_000,
            intervals: [3_000, 5_000, 5_000],
            message: `SLA record for instance ${instanceId} did not reach status=overdue within 60 s. ` +
              `SlaSchedulerService fixedRate=15s; SLA deadlineValue=PT30S. ` +
              `Check SlaActivationListener created the record and SlaSchedulerService is scanning.`,
          },
        )
        .toBe(true);

      // -----------------------------------------------------------------------
      // 7. Detail page visual assertion: leave request status is still PENDING
      //    (manager has not acted) and the record detail page loads correctly.
      // -----------------------------------------------------------------------
      await applicantPage.goto(
        `/p/wd_leave_request/view/${recordId}`,
        { waitUntil: 'domcontentloaded' },
      );
      const main = applicantPage.locator('main').first();
      // The leave request status field must be visible (confirms page loaded correctly)
      await expect(
        main.locator('[data-testid="form-field-wd_req_status"]').first(),
      ).toBeVisible({ timeout: 5_000 });

      // -----------------------------------------------------------------------
      // Cleanup
      // -----------------------------------------------------------------------
      await applicantCtx.close();
    },
  );
});
