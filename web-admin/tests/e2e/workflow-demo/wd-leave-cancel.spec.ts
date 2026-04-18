import { test, expect } from '@playwright/test';
import {
  loginAs,
  ensureRoleUsers,
  createLeaveApplicant,
  setLeaveBalance,
  submitLeaveRequest,
} from '../../helpers/wd-fixtures';

/**
 * R5 — Applicant submits leave → before manager acts, applicant cancels →
 *      process instance ends in terminal state, business record reflects
 *      cancellation, no tasks remain active, manager Task Center no longer
 *      shows the task.
 *
 * ## Investigation result: FIXME — cancellation not wired (2026-04-19)
 *
 * Gap analysis:
 *
 * 1. NO `wd:cancel_leave_request` command in
 *    `plugins/workflow-demo/config/commands.json`.
 *    Only: create / update / delete / submit / create_balance / update_balance.
 *
 * 2. NO cancel button in
 *    `plugins/workflow-demo/config/pages/wd_leave_request_detail.json`.
 *    toolbar.buttons has only: "edit" (navigate) + "submit" (command).
 *
 * 3. NO `cancelled` status value in `wd_leave_status` dict
 *    (`plugins/workflow-demo/config/dicts.json`).
 *    Declared values: draft | submitted | approving | approved | rejected.
 *
 * 4. NO withdrawPolicy configured on the `wd_leave_approval` process
 *    (`plugins/workflow-demo/config/processes.json` extension.withdrawPolicy = null).
 *    The BPM platform has a withdraw mechanism (POST /api/bpm/tasks/{taskId}/withdraw)
 *    but it is guarded by withdrawPolicy and the process declares none.
 *
 * 5. The BPM backend terminate endpoint
 *    POST /api/bpm/process-instances/{id}/terminate requires
 *    MetaPermission.WORKFLOW_ADMIN — not available to ordinary applicants.
 *    (BpmMonitorController also has POST /api/bpm/monitor/instances/{id}/terminate
 *    with MetaPermission.BPM_MONITOR_MANAGE — also admin-only.)
 *
 * ## What must be implemented to unblock this spec
 *
 * A. **Process config** (`processes.json` extension or designerJson.properties):
 *    Set `withdrawPolicy: "loose"` on `wd_leave_approval` so the applicant
 *    can withdraw/cancel while the process is still running.
 *
 * B. **Command** (`commands.json`):
 *    Add `wd:cancel_leave_request` with:
 *      - postActions writing `wd_req_status = "cancelled"` to the business record
 *        (or a BPM callback sets it via the withdraw completion hook)
 *      - OR wire to existing platform withdraw endpoint as a proxy command
 *
 * C. **Dict** (`dicts.json`, `wd_leave_status`):
 *    Add item `{ value: "cancelled", label: "Cancelled", "label:zh-CN": "已撤销" }`.
 *
 * D. **Detail page** (`pages/wd_leave_request_detail.json`):
 *    Add a "Cancel" / "撤销申请" toolbar button bound to `wd:cancel_leave_request`
 *    with `permissionCode: "wd.leave_request.submit"` and
 *    `confirmMessage: "confirm_cancel_leave_request"` for the confirmation dialog.
 *    Show button only when `wd_req_status ∈ {submitted, approving}`.
 *
 * E. **Business rule** (optional): update `rules.json` approval rule to
 *    treat `cancelled` as terminal and skip manager routing.
 *
 * F. **E2E unblocker**: after A–D, remove the `test.fixme` wrapper and run
 *    `cd web-admin && pnpm test tests/e2e/workflow-demo/wd-leave-cancel.spec.ts`.
 */

test.describe('workflow-demo — R5 applicant cancel', () => {
  test.fixme(
    'applicant cancels before manager approves → instance cancelled, no active tasks',
    // Fixme reason: cancellation not wired — see file-level comment for the full gap
    // analysis and the 5-point unblocker checklist (A–E) above.
    async ({ browser, request }) => {
      // ------------------------------------------------------------------
      // 1. API setup: admin login, ensure role users, create applicant, seed balance
      // ------------------------------------------------------------------
      const adminToken = await loginAs(request, 'admin@example.com', 'Test2026x');
      const { managerToken: _managerToken } = await ensureRoleUsers(request);
      const applicant = await createLeaveApplicant(request, adminToken, 'r5_cancel');
      await setLeaveBalance(request, adminToken, applicant.userId, 20);

      // ------------------------------------------------------------------
      // 2. Applicant UI: login → navigate to leave list → submit leave
      // ------------------------------------------------------------------
      const applicantCtx = await browser.newContext();
      const applicantPage = await applicantCtx.newPage();

      await applicantPage.goto('/login');
      await applicantPage.getByLabel(/email/i).fill(applicant.email);
      await applicantPage.getByLabel(/password|密码/i).fill('Test2026x');
      await applicantPage.getByRole('button', { name: /login|登录/i }).click();
      await applicantPage.waitForURL((u) => !u.pathname.endsWith('/login'), {
        timeout: 10_000,
      });

      const { recordId } = await submitLeaveRequest(applicantPage, {
        days: 2,
        type: 'annual',
        reason: 'R5 cancel test — applicant withdraws before manager acts',
      });
      expect(recordId, 'submitLeaveRequest must return a non-empty recordId').toBeTruthy();

      // ------------------------------------------------------------------
      // 3. Applicant cancels: navigate via sidebar to leave detail → click Cancel
      //
      // Expected UI flow (once wired):
      //   a. Sidebar → "我的请假" (wd_leave_request list)
      //   b. Click the submitted record row to open /p/wd_leave_request/view/{recordId}
      //   c. Toolbar shows "撤销申请" / "Cancel" button (visible because status=submitted)
      //   d. Click → confirmation dialog appears
      //   e. Confirm → command wd:cancel_leave_request fires
      //   f. Toast "撤销成功" / "Cancelled" appears
      //   g. Status badge on detail page changes to "已撤销" / "Cancelled"
      // ------------------------------------------------------------------
      await applicantPage.goto(`/p/wd_leave_request/view/${recordId}`, {
        waitUntil: 'domcontentloaded',
      });

      // Expect cancel button to be present (will fail until D is implemented)
      const cancelBtn = applicantPage
        .locator('[data-testid="toolbar-btn-cancel"], button')
        .filter({ hasText: /cancel|撤销/i })
        .first();
      await expect(cancelBtn, 'Cancel button must be visible on detail page').toBeVisible({
        timeout: 5_000,
      });

      await cancelBtn.click();

      // Confirmation dialog
      const confirmDialog = applicantPage.getByRole('dialog').filter({ hasText: /cancel|撤销/i });
      await expect(confirmDialog, 'Confirmation dialog must appear').toBeVisible({
        timeout: 5_000,
      });
      await confirmDialog.getByRole('button', { name: /confirm|确认|ok/i }).click();

      // Success toast
      await expect(
        applicantPage.locator('[class*="toast"], [class*="message"], [role="alert"]').first(),
        'Success toast must appear after cancellation',
      ).toBeVisible({ timeout: 5_000 });

      // ------------------------------------------------------------------
      // 4a. Assert: business record wd_req_status = 'cancelled'
      //     Endpoint: GET /api/dynamic/wd_leave_request_detail/{recordId}
      // ------------------------------------------------------------------
      const recordResp = await applicantPage.request.get(
        `/api/dynamic/wd_leave_request_detail/${recordId}`,
      );
      expect(recordResp.status(), 'business record fetch must return 200').toBe(200);

      const recordBody = await recordResp.json();
      const record = recordBody?.data as Record<string, unknown> | undefined;
      if (!record) {
        throw new Error(
          `R5: business record response missing "data". Full body: ${JSON.stringify(recordBody)}`,
        );
      }

      expect(
        record.wd_req_status,
        'business record wd_req_status must be "cancelled" after applicant cancels',
      ).toBe('cancelled');

      // ------------------------------------------------------------------
      // 4b. Assert: process instance in terminal state
      //     Field wd_req_process_instance holds the BPM instance id.
      //     Endpoint: GET /api/bpm/process-instances/{instanceId} → data.status
      //     Expected status: "cancelled" (ExecutionState.CANCELLED stored as lowercase)
      //     OR "completed" if the BPMN ends at a cancel end-event — verify at runtime.
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
            `R5: process instance response missing "data". Full body: ${JSON.stringify(instanceBody)}`,
          );
        }

        const instanceStatus = (instanceData.status as string | undefined)?.toLowerCase();
        expect(
          instanceStatus === 'cancelled' || instanceStatus === 'completed',
          `process instance status must be "cancelled" or "completed" (terminal), got: ${instanceStatus}`,
        ).toBe(true);
      }

      // ------------------------------------------------------------------
      // 4c. Assert: no active tasks remain
      //     Endpoint: GET /api/bpm/tasks?processInstanceId={instanceId}&status=pending
      //     Expects empty list.
      // ------------------------------------------------------------------
      if (instanceId) {
        const tasksResp = await applicantPage.request.get(
          `/api/bpm/tasks?processInstanceId=${instanceId}&status=pending`,
        );
        if (tasksResp.status() === 200) {
          const tasksBody = await tasksResp.json();
          const tasks = (tasksBody?.data ?? tasksBody?.data?.records ?? []) as unknown[];
          expect(
            tasks.length,
            'no active (pending) tasks must remain after applicant cancels',
          ).toBe(0);
        }
      }

      // ------------------------------------------------------------------
      // 4d. Assert: manager Task Center no longer shows this task
      // ------------------------------------------------------------------
      const managerCtx = await browser.newContext();
      const managerPage = await managerCtx.newPage();

      await managerPage.goto('/login');
      await managerPage.getByLabel(/email/i).fill('wd_manager@example.com');
      await managerPage.getByLabel(/password|密码/i).fill('Test2026x');
      await managerPage.getByRole('button', { name: /login|登录/i }).click();
      await managerPage.waitForURL((u) => !u.pathname.endsWith('/login'), {
        timeout: 10_000,
      });

      // Navigate via sidebar to Task Center
      await managerPage
        .getByRole('navigation')
        .getByRole('link', { name: /task center|待办|任务中心/i })
        .first()
        .click();
      await managerPage.waitForURL((u) => u.pathname.includes('/bpm/task-center'), {
        timeout: 10_000,
      });

      // The cancelled task must NOT appear in manager's pending task list
      const taskRow = managerPage.locator(`[data-testid="task-business-key-${recordId}"]`);
      await expect(
        taskRow,
        'cancelled task must NOT appear in manager Task Center',
      ).not.toBeVisible({ timeout: 5_000 });

      // ------------------------------------------------------------------
      // 4e. Detail page visual: status badge shows "已撤销" / "Cancelled"
      // ------------------------------------------------------------------
      await expect(
        applicantPage
          .locator('[data-testid="form-field-wd_req_status"]')
          .first(),
        'detail page must show cancelled status label',
      ).toContainText(/cancelled|已撤销/i, { timeout: 5_000 });

      // ------------------------------------------------------------------
      // Cleanup
      // ------------------------------------------------------------------
      await applicantCtx.close();
      await managerCtx.close();
    },
  );
});
