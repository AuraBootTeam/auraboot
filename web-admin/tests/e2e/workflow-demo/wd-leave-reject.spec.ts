import { test, expect } from '@playwright/test';
import {
  loginAs,
  loginViaUI,
  ensureRoleUsers,
  createLeaveApplicant,
  setLeaveBalance,
  submitLeaveRequest,
  processTask,
} from '../../helpers/wd-fixtures';

/**
 * R4 — Short leave (days=2) → Drools routes to wd_manager → Manager REJECTS
 *      → process completed (reaches end_rejected event), business record status = rejected.
 *
 * Process investigation:
 *   processes.json task_manager_approve.taskActions[1]:
 *     { key: "reject", resultVariable: "taskResult", resultValue: "rejected" }
 *   Edges from gw_result:
 *     flow_result_rejected: ${taskResult == 'rejected'} → svc_notify_rejected → end_rejected
 *   Terminal end event: end_rejected (type=endEvent) — BPM engine marks instance as "completed".
 *
 * Post-action writes wd_req_status via the task result variable hook:
 *   Expected terminal value: "rejected" (matches resultValue in taskActions).
 *
 * Endpoints:
 *   Business record: GET /api/dynamic/wd_leave_request_detail/{pid} → data.wd_req_status
 *   Process instance: GET /api/bpm/process-instances/{instanceId} → data.status
 *   Instance id field: wd_req_process_instance (extension.processInstanceField)
 */

test.describe('workflow-demo — R4 short leave manager reject', () => {
  test('short leave (days=2) → manager rejects → completed/rejected', async ({
    browser,
    request,
  }) => {
    // ------------------------------------------------------------------
    // 1. API setup: admin login, ensure role users, create applicant, seed balance
    // ------------------------------------------------------------------
    const adminToken = await loginAs(request, 'admin@example.com', 'Test2026x');
    const { managerToken: _managerToken } = await ensureRoleUsers(request);
    const applicant = await createLeaveApplicant(request, adminToken, 'r4_reject');
    await setLeaveBalance(request, adminToken, applicant.userId, 20);

    // ------------------------------------------------------------------
    // 2. Applicant context: login via UI, navigate to list, submit leave
    // ------------------------------------------------------------------
    const applicantCtx = await browser.newContext({ storageState: { cookies: [], origins: [] } });
    const applicantPage = await applicantCtx.newPage();

    await loginViaUI(applicantPage, applicant.email, 'Test2026x');

    const { recordId } = await submitLeaveRequest(applicantPage, {
      days: 2,
      type: 'annual',
      reason: 'R4 reject test — short leave automated rejection path',
    });
    expect(recordId, 'submitLeaveRequest must return a non-empty recordId').toBeTruthy();

    // ------------------------------------------------------------------
    // 3. Manager context: login via UI, reject task via Task Center
    // ------------------------------------------------------------------
    const managerCtx = await browser.newContext({ storageState: { cookies: [], origins: [] } });
    const managerPage = await managerCtx.newPage();

    await loginViaUI(managerPage, 'wd_manager@example.com', 'Test2026x');

    // processTask navigates via sidebar to /bpm/task-center, finds the row whose
    // data-testid="task-business-key" contains recordId, and rejects it.
    // requireComment=true is set on the reject action in processes.json.
    await processTask(managerPage, recordId, 'reject', 'Denied — R4 automated: insufficient justification');

    // ------------------------------------------------------------------
    // 4a. Assert: business record status = 'rejected'
    //     Endpoint: GET /api/dynamic/wd_leave_request_detail/{pid}
    //     → data.wd_req_status === 'rejected'
    // ------------------------------------------------------------------
    const recordResp = await applicantPage.request.get(
      `/api/dynamic/wd_leave_request_detail/${recordId}`,
    );
    expect(recordResp.status(), 'business record detail fetch must return 200').toBe(200);

    const recordBody = await recordResp.json();
    const record = recordBody?.data as Record<string, unknown> | undefined;
    if (!record) {
      throw new Error(
        `R4: business record detail response missing "data". Full body: ${JSON.stringify(recordBody)}`,
      );
    }

    expect(
      record.wd_req_status,
      `business record wd_req_status must be "rejected" after manager rejection`,
    ).toBe('rejected');

    // ------------------------------------------------------------------
    // 4b. Assert: process instance status = completed (via BPM API)
    //     The reject path terminates at end_rejected (endEvent) which the
    //     BPM engine records as "completed" — not "rejected" at engine level.
    //     Field wd_req_process_instance holds the instance id.
    //     Endpoint: GET /api/bpm/process-instances/{instanceId} → data.status
    // ------------------------------------------------------------------
    const instanceId = record.wd_req_process_instance as string | undefined;
    if (instanceId) {
      const instanceResp = await applicantPage.request.get(
        `/api/bpm/process-instances/${instanceId}`,
      );
      expect(
        instanceResp.status(),
        `GET /api/bpm/process-instances/${instanceId} must return 200`,
      ).toBe(200);

      const instanceBody = await instanceResp.json();
      const instanceData = instanceBody?.data as Record<string, unknown> | undefined;
      if (!instanceData) {
        throw new Error(
          `R4: process instance response missing "data". Full body: ${JSON.stringify(instanceBody)}`,
        );
      }

      // The reject path ends at end_rejected (endEvent) — the engine marks the
      // instance as "completed" regardless of which end event was reached.
      expect(
        (instanceData.status as string | undefined)?.toLowerCase(),
        `process instance status must be "completed" after manager rejection`,
      ).toBe('completed');
    }
    // If instanceId is absent (BPM not wired), wd_req_status assertion above is the primary check.

    // ------------------------------------------------------------------
    // 5. Detail page visual assertion: wd_req_status shows rejected label
    // ------------------------------------------------------------------
    await applicantPage.goto(
      `/p/wd_leave_request/view/${recordId}`,
      { waitUntil: 'domcontentloaded' },
    );
    const main = applicantPage.locator('main').first();
    await expect(
      main.locator('[data-testid="form-field-wd_req_status"]').first(),
    ).toContainText(/rejected|已驳回/i, { timeout: 5_000 });

    // ------------------------------------------------------------------
    // Cleanup: close browser contexts
    // ------------------------------------------------------------------
    await applicantCtx.close();
    await managerCtx.close();
  });
});
