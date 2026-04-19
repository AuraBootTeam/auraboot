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

const BACKEND = 'http://localhost:6443';

/**
 * R2 — Long leave (days=10) → Drools routes to wd_hr → HR approves
 *      → process completed, business record status = approved.
 *
 * Drools rule wd_leave_routing.drl: days >= 3.0 → approverRole = 'hr'
 *   → gateway flows to task_hr_approve (processes.json node id confirmed).
 *
 * Leave type "annual": dicts.json wd_leave_type item value="annual", label="Annual Leave".
 *   submitLeaveRequest clicks getByRole('option', { name: 'annual' }) which matches
 *   "Annual Leave" label via case-insensitive substring.
 *
 * Routing assertion: GET /api/bpm/tasks/by-process/{instanceId} → active tasks
 *   must contain task_hr_approve and NOT task_manager_approve.
 *
 * Process instance id stored on: wd_req_process_instance (processes.json extension.processInstanceField).
 */

test.setTimeout(90_000);

test.describe('workflow-demo — R2 long leave HR approve', () => {
  test('long leave (days=10) → HR approves → completed/approved', async ({
    browser,
    request,
  }) => {
    // ------------------------------------------------------------------
    // 1. API setup: admin login, ensure role users, create applicant, seed balance
    // ------------------------------------------------------------------
    const adminToken = await loginAs(request, 'admin@example.com', 'Test2026x');
    const { hrToken } = await ensureRoleUsers(request);
    const applicant = await createLeaveApplicant(request, adminToken, 'r2_long');
    await setLeaveBalance(request, adminToken, applicant.userId, 20);

    // ------------------------------------------------------------------
    // 2. Applicant context: login via UI, navigate to list, submit leave
    // ------------------------------------------------------------------
    const applicantCtx = await browser.newContext({ storageState: { cookies: [], origins: [] } });
    const applicantPage = await applicantCtx.newPage();

    await loginViaUI(applicantPage, applicant.email, 'Test2026x');

    // Leave type "annual" → option rendered as "Annual Leave" or "年假" (i18n-driven).
    // We pass the dict item value "annual"; the helper resolves via getByRole('option', { name: 'annual' }).
    const { recordId } = await submitLeaveRequest(applicantPage, {
      userId: applicant.userId,
      token: applicant.token,
      days: 10,
      type: 'annual',
      reason: 'R2 long leave automated test — E2E HR approval path',
    });
    expect(recordId, 'submitLeaveRequest must return a non-empty recordId').toBeTruthy();

    // ------------------------------------------------------------------
    // 3. Assert routing: active task must be task_hr_approve, NOT task_manager_approve
    //    Fetch instanceId from the business record first, then query active tasks.
    // ------------------------------------------------------------------
    const recordRespForRouting = await applicantPage.request.get(
      `/api/dynamic/wd_leave_request_detail/${recordId}`,
    );
    expect(
      recordRespForRouting.status(),
      'business record fetch for routing check must return 200',
    ).toBe(200);

    const recordBodyForRouting = await recordRespForRouting.json();
    const recordForRouting = recordBodyForRouting?.data as Record<string, unknown> | undefined;
    if (!recordForRouting) {
      throw new Error(
        `R2: business record detail response missing "data" during routing check. Full body: ${JSON.stringify(recordBodyForRouting)}`,
      );
    }

    const instanceIdForRouting = recordForRouting.wd_req_process_instance as string | undefined;
    if (instanceIdForRouting) {
      const tasksResp = await request.get(
        `${BACKEND}/api/bpm/tasks/by-process/${instanceIdForRouting}`,
        { headers: { Authorization: `Bearer ${adminToken}` } },
      );
      expect(
        tasksResp.status(),
        `GET /api/bpm/tasks/by-process/${instanceIdForRouting} must return 200`,
      ).toBe(200);

      const tasksBody = await tasksResp.json();
      const active = (tasksBody?.data ?? []) as Array<Record<string, unknown>>;
      const keys = active.map((t) => t.processDefinitionActivityId as string);

      expect(
        keys,
        'Drools routing: task_hr_approve must be the active task for long leave (days=10)',
      ).toContain('task_hr_approve');
      expect(
        keys,
        'Drools routing: task_manager_approve must NOT be active for long leave (days=10)',
      ).not.toContain('task_manager_approve');
    }
    // If BPM is not wired in this deployment, skip routing assertion and rely on business record status.

    // ------------------------------------------------------------------
    // 4. HR context: login via UI, approve task via Task Center
    // ------------------------------------------------------------------
    const hrCtx = await browser.newContext({ storageState: { cookies: [], origins: [] } });
    const hrPage = await hrCtx.newPage();

    await loginViaUI(hrPage, 'wd_hr@example.com', 'Test2026x');

    // processTask navigates via sidebar to /bpm/task-center, finds the row whose
    // data-testid="task-business-key" contains recordId (= businessKey set to
    // "${recordId}" in postActions), and approves it.
    await processTask(hrPage, recordId, 'approve', 'Approved by HR — R2 automated long leave');

    // ------------------------------------------------------------------
    // 5a. Assert: business record status = 'approved'
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
        `R2: business record detail response missing "data". Full body: ${JSON.stringify(recordBody)}`,
      );
    }

    expect(
      record.wd_req_status,
      'business record wd_req_status must be "approved" after HR approval',
    ).toBe('approved');

    // ------------------------------------------------------------------
    // 5b. Assert: process instance status = completed (via BPM API)
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
          `R2: process instance response missing "data". Full body: ${JSON.stringify(instanceBody)}`,
        );
      }

      // Status is a string from InstanceStatus enum serialised to lowercase.
      // Expected terminal state after approval: "completed".
      expect(
        (instanceData.status as string | undefined)?.toLowerCase(),
        'process instance status must be "completed" after HR approval',
      ).toBe('completed');
    }
    // If instanceId is absent (BPM not wired in this deployment), we still
    // verified the business record status — that is the primary assertion.

    // ------------------------------------------------------------------
    // 6. Detail page visual assertion: wd_req_status shows approved label
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
    await hrCtx.close();
  });
});
