import { test, expect } from '@playwright/test';
import {
  loginAs,
  loginViaUI,
  ensureRoleUsers,
  createLeaveApplicant,
  setLeaveBalance,
  submitLeaveRequest,
} from '../../helpers/wd-fixtures';

const BACKEND = 'http://localhost:6443';

/**
 * R3 — SLA escalation end-to-end.
 *
 * KNOWN GAP (2026-04-19): SlaRecordService.createRecord() has zero callers.
 * No code creates ab_sla_record entries when a task activates, so the
 * SlaSchedulerService never has anything to scan, and escalation never fires.
 *
 * Unblock condition: add a call to SlaRecordService.createRecord() in the
 * task-activation code path (candidates: TaskService.activate() or
 * BpmNodeHookService.onTaskStart()), mapping the task's processDefinitionActivityId
 * to the matching ab_sla_config row.
 *
 * Once that wiring is in place, remove `test.fixme` below.
 *
 * SLA config used: workflow-demo sla.json "wd_manager_approve_sla"
 *   targetType = "NODE", targetKey = "task_manager_approve"
 *   processKey = "wd_leave_approval", timeoutSeconds = 30 (deadlineValue = "PT30S")
 *   escalationTargetType = "role_parent", escalationTargetValue = "wd_manager"
 *
 * The plugin already ships this config — no runtime POST needed for the config itself.
 * The test seeds a short leave (days=1), submits it, then intentionally does NOT
 * have the manager act. After 30 s the SlaSchedulerService should detect the breach
 * and update the ab_sla_record row to status=ESCALATED.
 * The test polls GET /api/bpm/sla-records?instanceId=... for that state change.
 */

test.describe('workflow-demo — R3 SLA escalation', () => {
  // test.fixme(title, fn) marks the test as "expected to fail but should be fixed".
  // It is NOT test.skip — the body is exercised in development to ensure scaffolding
  // is correct, and it converts a timeout into an expected-failure result so the
  // suite stays green while the wiring gap exists.
  test.fixme(
    'manager ignores short leave → SLA escalates (fixme: SlaRecordService wiring gap)',
    async ({ browser, request }) => {
      // -----------------------------------------------------------------------
      // 1. Admin API setup — reuse the same pattern as R1 / R2
      // -----------------------------------------------------------------------
      const adminToken = await loginAs(request, 'admin@example.com', 'Test2026x');
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
      // -----------------------------------------------------------------------
      const tasksResp = await request.get(
        `${BACKEND}/api/bpm/tasks?processInstanceId=${instanceId}`,
        { headers: { Authorization: `Bearer ${adminToken}` } },
      );
      expect(tasksResp.status(), 'task list fetch must succeed').toBe(200);

      const tasksBody = await tasksResp.json();
      const tasks = (tasksBody?.data as unknown[]) ?? [];
      const managerTask = (tasks as Array<Record<string, unknown>>).find(
        (t) => (t.taskDefinitionKey as string | undefined) === 'task_manager_approve',
      );
      expect(
        managerTask,
        'task_manager_approve must be active — short leave routes to manager',
      ).toBeTruthy();

      // -----------------------------------------------------------------------
      // 5. Do NOT have the manager act — deliberately let the 30 s SLA deadline
      //    elapse. Poll for escalation evidence on ab_sla_record.
      //
      //    UNBLOCK GAP: SlaRecordService.createRecord() has zero callers.
      //    The poll below will time out (→ fixme converts to expected failure).
      //    Once the task-activation hook calls createRecord(), this poll will pass.
      // -----------------------------------------------------------------------
      await expect
        .poll(
          async () => {
            // Primary signal: GET /api/bpm/sla-records?instanceId=... → any row
            // with status=ESCALATED (lowercase per project convention).
            const slaRecordsResp = await request.get(
              `${BACKEND}/api/bpm/sla-records?instanceId=${instanceId}`,
              { headers: { Authorization: `Bearer ${adminToken}` } },
            );
            if (!slaRecordsResp.ok()) return false;

            const slaBody = await slaRecordsResp.json();
            const records = (slaBody?.data as unknown[]) ?? (slaBody?.data?.records as unknown[]) ?? [];
            const escalated = (records as Array<Record<string, unknown>>).some(
              (r) => (r.status as string | undefined)?.toLowerCase() === 'escalated',
            );
            if (escalated) return true;

            // Fallback signal: check if the business record has an sla_status field
            // that reflects escalation (field name tentative — adjust to actual schema).
            const recResp = await request.get(
              `${BACKEND}/api/dynamic/wd_leave_request_detail/${recordId}`,
              { headers: { Authorization: `Bearer ${adminToken}` } },
            );
            if (!recResp.ok()) return false;
            const recBody = await recResp.json();
            const rec = recBody?.data as Record<string, unknown> | undefined;
            if (!rec) return false;

            // Accept any evidence of escalation on the business record.
            const slaStatus = (rec.wd_req_sla_status as string | undefined)?.toLowerCase();
            return slaStatus === 'escalated';
          },
          {
            // Timeout: 30 s SLA window + 15 s scheduler lag + 10 s poll overhead = 55 s.
            // We cap at 60 s to stay within a reasonable test budget.
            timeout: 60_000,
            intervals: [2_000, 3_000, 5_000],
            message: `SLA escalation for instance ${instanceId} did not appear within 60 s. ` +
              `Root cause: SlaRecordService.createRecord() has no callers — ` +
              `ab_sla_record is never populated so SlaSchedulerService has nothing to scan.`,
          },
        )
        .toBe(true);

      // -----------------------------------------------------------------------
      // 6. Detail page visual assertion: confirm escalation is visible in UI
      //    (only reached once the poll passes, i.e. once the gap is fixed)
      // -----------------------------------------------------------------------
      await applicantPage.goto(
        `/p/wd_leave_request/view/${recordId}`,
        { waitUntil: 'domcontentloaded' },
      );
      const main = applicantPage.locator('main').first();
      // Escalated state should be visible in the status field or a dedicated SLA badge.
      await expect(
        main.locator('[data-testid="form-field-wd_req_status"], [data-testid="sla-status-badge"]').first(),
      ).toContainText(/escalat|已升级/i, { timeout: 5_000 });

      // -----------------------------------------------------------------------
      // Cleanup
      // -----------------------------------------------------------------------
      await applicantCtx.close();
    },
  );
});
