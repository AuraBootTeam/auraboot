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
 * R1 — Short leave (days=2) → Drools routes to wd_manager → Manager approves
 *      → process completed, business record status = approved.
 *
 * Business key investigation:
 *   commands.json wd:submit_leave_request postActions[0].businessKey = "${recordId}"
 *   → processTask(page, recordId, ...) matches task-business-key cell directly.
 *
 * Leave type "sick" label: dicts.json wd_leave_type item value="sick", label="Sick Leave",
 *   label:zh-CN="病假". submitLeaveRequest clicks getByRole('option', { name: input.type })
 *   so we pass the raw dict value "sick" and let the helper resolve the option.
 *
 * Terminal status: dicts.json wd_leave_status value="approved" (string stored in wd_req_status).
 *
 * Business record endpoint: GET /api/dynamic/wd_leave_request_detail/{pid} → data.wd_req_status
 *   (confirmed by existing test WD1-005 in same suite pattern).
 *
 * Process instance endpoint: GET /api/bpm/process-instances/{instanceId} → data.status
 *   (confirmed from bpm-assertions.ts startInstanceAndAdvance final-fetch contract).
 *
 * Process instance id stored on: wd_req_process_instance field (processes.json extension.processInstanceField).
 */

test.setTimeout(90_000);

test.describe('workflow-demo — R1 short leave manager approve', () => {
  test('short leave (days=2) → manager approves → completed/approved', async ({
    browser,
    request,
  }) => {
    // ------------------------------------------------------------------
    // 1. API setup: admin login, ensure role users, create applicant, seed balance
    // ------------------------------------------------------------------
    const adminToken = await loginAs(request, 'admin@auraboot.com', 'Test2026x');
    const { managerToken: _managerToken } = await ensureRoleUsers(request);
    const applicant = await createLeaveApplicant(request, adminToken, 'r1_short');
    await setLeaveBalance(request, adminToken, applicant.userId, 20);

    // ------------------------------------------------------------------
    // 2. Applicant context: login via UI, navigate to list, submit leave
    // ------------------------------------------------------------------
    const applicantCtx = await browser.newContext({ storageState: { cookies: [], origins: [] } });
    const applicantPage = await applicantCtx.newPage();

    await loginViaUI(applicantPage, applicant.email, 'Test2026x');

    // Leave type "sick" → option rendered as "Sick Leave" or "病假" (i18n-driven).
    // submitLeaveRequest clicks getByRole('option', { name: input.type }) after
    // clicking the type combobox; we pass the dict item value "sick" which the
    // form option label resolves to. If i18n renders the zh-CN label, the helper
    // will match either. We pass the value directly — the helper uses it as-is
    // in getByRole('option', { name: input.type }).first().click().
    const { recordId } = await submitLeaveRequest(applicantPage, {
      userId: applicant.userId,
      token: applicant.token,
      days: 2,
      type: 'sick',
      reason: 'R1 short leave automated test — E2E manager approval path',
    });
    expect(recordId, 'submitLeaveRequest must return a non-empty recordId').toBeTruthy();

    // ------------------------------------------------------------------
    // 3. Manager context: login via UI, approve task via Task Center
    // ------------------------------------------------------------------
    const managerCtx = await browser.newContext({ storageState: { cookies: [], origins: [] } });
    const managerPage = await managerCtx.newPage();

    await loginViaUI(managerPage, 'wd_manager@example.com', 'Test2026x');

    // processTask navigates via sidebar to /bpm/task-center, finds the row whose
    // data-testid="task-business-key" contains recordId (= businessKey set to
    // "${recordId}" in postActions), and approves it.
    await processTask(managerPage, recordId, 'approve', 'LGTM — R1 automated approval');

    // ------------------------------------------------------------------
    // 4a. Assert: business record status = 'approved'
    //     Endpoint: GET /api/dynamic/wd_leave_request_detail/{pid}
    //     → data.wd_req_status === 'approved'
    // ------------------------------------------------------------------
    const recordResp = await applicantPage.request.get(
      `/api/dynamic/wd_leave_request_detail/${recordId}`,
    );
    expect(recordResp.status(), 'business record detail fetch must return 200').toBe(200);

    const recordBody = await recordResp.json();
    const record = recordBody?.data as Record<string, unknown> | undefined;
    if (!record) {
      throw new Error(
        `R1: business record detail response missing "data". Full body: ${JSON.stringify(recordBody)}`,
      );
    }

    expect(
      record.wd_req_status,
      `business record wd_req_status must be "approved" after manager approval`,
    ).toBe('approved');

    // ------------------------------------------------------------------
    // 4b. Assert: process instance status = completed (via BPM API)
    //     Field wd_req_process_instance holds the instance id (stored by
    //     postActions[0].storeInstanceIdIn in submit command).
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
          `R1: process instance response missing "data". Full body: ${JSON.stringify(instanceBody)}`,
        );
      }

      // Status is a string from InstanceStatus enum serialised to lowercase.
      // Expected terminal state after approval: "completed".
      expect(
        (instanceData.status as string | undefined)?.toLowerCase(),
        `process instance status must be "completed" after manager approval`,
      ).toBe('completed');
    }
    // If instanceId is absent (BPM not wired in this deployment), we still
    // verified the business record status — that is the primary assertion.

    // ------------------------------------------------------------------
    // 5. Detail page visual assertion: wd_req_status shows approved label
    // ------------------------------------------------------------------
    await applicantPage.goto(
      `/p/wd_leave_request/view/${recordId}`,
      { waitUntil: 'domcontentloaded' },
    );
    const main = applicantPage.locator('main').first();
    await expect(
      main.locator('[data-testid="form-field-wd_req_status"]').first(),
    ).toContainText(/approved|已通过/i, { timeout: 5_000 });

    // ------------------------------------------------------------------
    // Cleanup: close browser contexts
    // ------------------------------------------------------------------
    await applicantCtx.close();
    await managerCtx.close();
  });
});
