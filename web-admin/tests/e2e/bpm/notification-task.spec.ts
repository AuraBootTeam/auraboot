/**
 * BPM notification-task delivery — Epic P2.4
 *
 * Verifies that workflow-demo's wd_leave_approval process, once it crosses
 * gw_result (exclusiveGateway driven by `${taskResult == 'approved'|'rejected'}`),
 * actually fires the notify_approved / notify_rejected notification-tasks and
 * persists the in-app notification row to the recipient resolved by
 * `recipientFrom=applicant` (process variable `applicantUserId` ->
 * /initiatorUserId/startUserId fallback).
 *
 * Why this spec is NOT duplicating B5.3:
 *   B5.3 asserts audit trail includes activity_event rows for the userTask,
 *   but does NOT confirm that NotificationServiceTaskDelegate (the smart:class
 *   wired to type=notification-task) actually persisted the notification row
 *   or that the recipient mapping from `applicant` -> applicantUserId is
 *   correct. This spec closes that gap.
 *
 * Ground truth table selection
 * ----------------------------
 * The original task brief references `se_notification_instance`, but that
 * table is SmartEngine-internal and is only populated by CcService (task CC
 * path: `notification_type='cc'`). The BPM notification-task delegate
 * (NotificationServiceTaskDelegate) calls
 * `NotificationService.send` -> InAppChannel -> `ab_notification` (Notification
 * entity, @TableName("ab_notification")). We therefore assert against the
 * real persistence target `ab_notification` via
 * `GET /api/notifications?pageNum=1&pageSize=50` (NotificationController.list,
 * which scopes to the currently authenticated user — our admin).
 *
 * Template seeding
 * ----------------
 * workflow-demo does not ship notificationTemplates in its plugin.json
 * resourceDirs, so `wd_request_approved` / `wd_request_rejected` templates
 * do not exist after reset-and-init. `NotificationServiceImpl.send` logs
 * "Notification template not found, skipping" and returns early — the
 * notification row would never be written. This is a runtime gap, not a test
 * bug, so the spec seeds the two in-app templates via the public
 * `POST /api/notification-templates` API in beforeAll. That is a data
 * fixture, not a backend/plugin change, and is consistent with the task
 * constraint "禁止 改 plugin / backend".
 *
 * Dimensions covered
 * ------------------
 *   D1  — sidebar navigation to Task Center for both approve + reject flows.
 *   D9  — row "More" menu + dialog confirm reachable (visibility assertion).
 *   D10 — approve / reject task action surface.
 *   D12 — audit trail crossed the notification activity.
 *   D13 — persisted notification row linkage (ab_notification with matching
 *         source_type + source_id + recipient user id).
 *
 * @since Epic P2.4 (notification-task real delivery E2E)
 */

import { test, expect, type Page } from '../../fixtures';
import { uniqueId, dateOffsetStr } from '../helpers';
import {
  loginAsAdmin,
  queryInstanceStatus,
  listAuditEvents,
  AuditOp,
  type InstanceStatus,
  type AuditEvent,
} from './_helpers/bpm-lifecycle';

test.describe.configure({ mode: 'serial' });

const PROCESS_KEY = 'wd_leave_approval';
const UID = uniqueId('NT');

// Shared state threaded across serial tests
let adminToken = '';
let adminUserId = '';

// Approve branch state (NT-1)
let approvePid = '';
let approveCode = '';
let approveInstanceId = '';

// Reject branch state (NT-2)
let rejectPid = '';
let rejectCode = '';
let rejectInstanceId = '';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

type TemplateSpec = {
  code: string;
  name: string;
  subjectKey: string; // i18n key (we inline zh-CN text for test readability)
  subject: string;
  body: string;
};

const TEMPLATES: TemplateSpec[] = [
  {
    code: 'wd_request_approved',
    name: 'Leave request approved',
    subjectKey: 'notification.wd_request_approved.title',
    subject: 'Leave request approved',
    body: 'Your leave request ${businessKey} (${days} days) has been approved.',
  },
  {
    code: 'wd_request_rejected',
    name: 'Leave request rejected',
    subjectKey: 'notification.wd_request_rejected.title',
    subject: 'Leave request rejected',
    body: 'Your leave request ${businessKey} (${days} days) was rejected.',
  },
];

/**
 * Ensure in-app notification templates exist for both wd_request_approved and
 * wd_request_rejected. Idempotent: if a template already exists for a tenant
 * (unique constraint on tenant_id + code), the create call returns a
 * conflict / business error which we swallow — the precondition (template
 * present) still holds.
 */
async function ensureNotificationTemplates(
  request: import('@playwright/test').APIRequestContext,
  token: string,
): Promise<void> {
  // List existing first so we don't fire creates we know will conflict.
  const listResp = await request.get('/api/notification-templates', {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(listResp.ok(), `list templates: ${listResp.status()}`).toBe(true);
  const listBody = await listResp.json();
  const existing = new Set<string>(
    (Array.isArray(listBody?.data) ? listBody.data : []).map((t: Record<string, unknown>) =>
      String(t.code ?? ''),
    ),
  );

  for (const tpl of TEMPLATES) {
    if (existing.has(tpl.code)) continue;

    const resp = await request.post('/api/notification-templates', {
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
      },
      data: {
        code: tpl.code,
        name: tpl.name,
        channel: 'in_app',
        subjectTemplate: tpl.subject,
        bodyTemplate: tpl.body,
        enabled: true,
      },
    });
    // Accept 200 (created) or 4xx business conflict (another run beat us here).
    // Do NOT accept 5xx — that would mask a real backend regression.
    expect(
      resp.status(),
      `create template ${tpl.code}: HTTP=${resp.status()} body=${await resp.text().then((t) => t.slice(0, 200))}`,
    ).toBeLessThan(500);
  }
}

/**
 * Seed a draft leave request via the wd:create_leave_request command (same
 * hybrid pattern as B5). Returns the recordId (pid used as businessKey).
 */
async function seedLeaveDraft(
  request: import('@playwright/test').APIRequestContext,
  token: string,
  applicantUserId: string,
  reason: string,
): Promise<{ pid: string; code: string }> {
  const resp = await request.post(
    '/api/meta/commands/execute/wd:create_leave_request',
    {
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      data: {
        payload: {
          wd_req_applicant: applicantUserId,
          wd_req_type: 'annual',
          wd_req_start_date: dateOffsetStr(7),
          wd_req_end_date: dateOffsetStr(8),
          wd_req_days: 2,
          wd_req_reason: reason,
        },
        operationType: 'create',
      },
    },
  );
  expect(resp.ok(), `draft create: ${resp.status()}`).toBe(true);
  const body = await resp.json();
  expect(String(body?.code)).toBe('0');
  const recordId = String(
    body?.data?.data?.recordId ?? body?.data?.data?.pid ?? body?.data?.data?.id ?? '',
  );
  expect(recordId, 'create must return a recordId').toBeTruthy();

  // Submit -> BPM start. Payload shape (targetRecordId + UPDATE + payload)
  // mirrors wd-leave-sla-escalation.spec.ts, which is the canonical
  // API-only submit path for wd:submit_leave_request.
  const submitResp = await request.post(
    '/api/meta/commands/execute/wd:submit_leave_request',
    {
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      data: {
        targetRecordId: recordId,
        operationType: 'UPDATE',
        payload: {
          wd_req_applicant: applicantUserId,
          wd_req_type: 'annual',
          wd_req_days: 2,
        },
      },
    },
  );
  expect(
    submitResp.ok(),
    `submit HTTP=${submitResp.status()} body=${await submitResp.text().then((t) => t.slice(0, 300))}`,
  ).toBe(true);
  const submitBody = await submitResp.json();
  expect(String(submitBody?.code)).toBe('0');

  const detailResp = await request.get(
    `/api/dynamic/wd_leave_request_detail/${recordId}`,
    { headers: { Authorization: `Bearer ${token}` } },
  );
  expect(detailResp.ok()).toBe(true);
  const record = (await detailResp.json())?.data;
  const code = String(record?.wd_req_code ?? '');
  expect(code).toMatch(/^WDLR-/);

  return { pid: recordId, code };
}

/**
 * Find the active task_manager_approve task id for a given process instance.
 */
async function findManagerTaskId(
  request: import('@playwright/test').APIRequestContext,
  token: string,
  processInstanceId: string,
): Promise<string> {
  const resp = await request.get('/api/bpm/tasks/todo?pageNum=1&pageSize=50', {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(resp.ok(), `tasks/todo: ${resp.status()}`).toBe(true);
  const body = await resp.json();
  const raw = body?.data;
  const tasks = (Array.isArray(raw) ? raw : raw?.records ?? []) as Array<Record<string, unknown>>;
  const task = tasks.find(
    (t) =>
      String(t.processInstanceId ?? '') === processInstanceId &&
      String(t.processDefinitionActivityId ?? '').includes('task_manager_approve'),
  );
  expect(task, `no task_manager_approve found for instance ${processInstanceId}`).toBeTruthy();
  return String(task!.instanceId ?? '');
}

/**
 * Poll until the instance has completed the notify activity (activity_end
 * event for the given nodeId is present in the audit trail). Returns the
 * final audit list.
 */
async function waitForNotifyCompletion(
  request: import('@playwright/test').APIRequestContext,
  token: string,
  instanceId: string,
  notifyNodeId: string,
): Promise<AuditEvent[]> {
  let audit: AuditEvent[] = [];
  await expect
    .poll(
      async () => {
        audit = await listAuditEvents(request, token, instanceId);
        return audit.some(
          (a) =>
            a.operation === AuditOp.ACTIVITY_EVENT &&
            (a.details?.activityId as string) === notifyNodeId &&
            (a.details?.eventType as string) === 'activity_end',
        );
      },
      {
        timeout: 15_000,
        message: `activity_end for ${notifyNodeId} must appear in audit trail`,
      },
    )
    .toBe(true);
  return audit;
}

/**
 * Navigate via the sidebar to Task Center. Mirrors B5's helper so the
 * notification test also asserts a real navigation (D1), not a direct
 * page.goto to /task-center.
 */
async function navigateToTaskCenter(page: Page): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  const nav = page.locator('nav').first();
  await nav.waitFor({ state: 'visible', timeout: 10_000 });

  const bpmParent = nav
    .getByRole('button', { name: /流程管理|Process Management/i })
    .first();
  if (await bpmParent.isVisible({ timeout: 5_000 }).catch(() => false)) {
    await bpmParent.scrollIntoViewIfNeeded();
    await bpmParent.evaluate((el: HTMLElement) => el.click());
  }

  const taskCenterLink = nav.locator('a[href*="task-center"]').first();
  await taskCenterLink.waitFor({ state: 'attached', timeout: 8_000 });
  await taskCenterLink.evaluate((el: HTMLElement) => el.click());
  await page.waitForURL(/task-center/, { timeout: 20_000 });
  await expect(page.locator('h1:has-text("任务中心")')).toBeVisible({ timeout: 10_000 });
}

// ---------------------------------------------------------------------------
// Test suite
// ---------------------------------------------------------------------------

test.describe(
  'BPM notification-task delivery',
  { tag: ['@bpm-regression'] },
  () => {
    test.setTimeout(240_000);

    test.beforeAll(async ({ request }) => {
      adminToken = await loginAsAdmin(request);

      const meResp = await request.get('/api/auth/me', {
        headers: { Authorization: `Bearer ${adminToken}` },
      });
      expect(meResp.ok()).toBe(true);
      adminUserId = String((await meResp.json())?.data?.user?.id ?? '');
      expect(adminUserId, 'admin userId must be resolvable').toBeTruthy();

      // Precondition: ensure templates exist so NotificationServiceImpl.send
      // does not short-circuit on "template not found". This is a data
      // fixture (runtime seed via public API), NOT a backend/plugin change.
      await ensureNotificationTemplates(request, adminToken);
    });

    // =========================================================================
    // NT-1: approve path triggers notify_approved notification-task and
    //       persists an ab_notification row addressed to the applicant.
    // =========================================================================
    test(
      'NT-1: approve flow fires notify_approved and persists recipient notification',
      async ({ page, request }) => {
        // --- Seed + submit ---
        const seeded = await seedLeaveDraft(
          request,
          adminToken,
          adminUserId,
          `NT-1 notify approve ${UID}`,
        );
        approvePid = seeded.pid;
        approveCode = seeded.code;

        // Confirm BPM reached task_manager_approve
        const afterSubmit: InstanceStatus = await queryInstanceStatus(
          request,
          adminToken,
          { processKey: PROCESS_KEY, businessKey: approvePid },
        );
        expect(afterSubmit.status).toMatch(/running|active/i);
        approveInstanceId = String(afterSubmit.instanceId);
        expect(approveInstanceId).toBeTruthy();
        expect(afterSubmit.currentNodes.map((n) => n.nodeId)).toContain(
          'task_manager_approve',
        );

        // --- UI reachability: Task Center row menu surfaces "通过" ---
        await navigateToTaskCenter(page);
        const taskId = await findManagerTaskId(request, adminToken, approveInstanceId);

        const taskRow = page
          .locator('table tbody tr')
          .filter({ hasText: PROCESS_KEY })
          .filter({ hasText: /task_manager_approve|主管审批|Manager Approve/i })
          .first();
        await expect(taskRow, 'manager-approve row must render').toBeVisible({
          timeout: 15_000,
        });

        const moreBtn = taskRow
          .locator('button')
          .filter({ has: page.locator('svg.lucide-ellipsis') })
          .first();
        await expect(moreBtn).toBeVisible({ timeout: 5_000 });
        await moreBtn.click();
        const menu = page.locator('.absolute.right-0.z-10');
        await expect(menu).toBeVisible({ timeout: 3_000 });
        await expect(
          menu.locator('button:has-text("通过")').first(),
          'Approve action must be reachable from Task Center row menu',
        ).toBeVisible();
        await page.keyboard.press('Escape').catch(() => {});

        // --- Fire approve via API with taskResult=approved so gw_result routes
        // to svc_notify_approved. (B5 established that the UI dialog omits the
        // required variable — this is tracked separately; not this spec's
        // concern.)
        const approveResp = await request.post(
          `/api/bpm/tasks/${encodeURIComponent(taskId)}/approve`,
          {
            headers: {
              Authorization: `Bearer ${adminToken}`,
              'Content-Type': 'application/json',
            },
            data: {
              comment: `NT-1 approve ${UID}`,
              variables: { taskResult: 'approved' },
            },
          },
        );
        expect(
          approveResp.status(),
          `approve HTTP=${approveResp.status()} body=${await approveResp.text().then((t) => t.slice(0, 300))}`,
        ).toBeLessThan(400);

        // --- Wait for notify_approved activity to finish, then assert audit ---
        const audit = await waitForNotifyCompletion(
          request,
          adminToken,
          approveInstanceId,
          'svc_notify_approved',
        );

        type Ev = { activityId: string; eventType: string };
        const notifyEvents: Ev[] = audit
          .filter((a) => a.operation === AuditOp.ACTIVITY_EVENT)
          .map((a) => ({
            activityId: (a.details?.activityId as string) ?? '',
            eventType: (a.details?.eventType as string) ?? '',
          }))
          .filter((e) => e.activityId === 'svc_notify_approved');
        expect(
          notifyEvents.some((e) => e.eventType === 'activity_start'),
          `svc_notify_approved activity_start must be audited: ${JSON.stringify(notifyEvents)}`,
        ).toBe(true);
        expect(
          notifyEvents.some((e) => e.eventType === 'activity_end'),
          `svc_notify_approved activity_end must be audited: ${JSON.stringify(notifyEvents)}`,
        ).toBe(true);

        // --- Assert a notification row was persisted for the applicant ---
        // NotificationController.list scopes to the authenticated user, so
        // querying with the applicant's (admin's) token is equivalent to
        // "ab_notification WHERE user_id = adminUserId".
        // /api/notifications is scoped to MetaContext.getCurrentUserId() —
        // querying with the applicant's (admin's) token is equivalent to
        // "ab_notification WHERE user_id = adminUserId". A row appearing
        // here therefore proves recipient=applicant by construction.
        const notifListResp = await request.get(
          '/api/notifications?pageNum=1&pageSize=50',
          { headers: { Authorization: `Bearer ${adminToken}` } },
        );
        expect(
          notifListResp.ok(),
          `list notifications: ${notifListResp.status()}`,
        ).toBe(true);
        const notifBody = await notifListResp.json();
        const rows = (notifBody?.data?.records ?? []) as Array<Record<string, unknown>>;
        expect(
          Array.isArray(rows) && rows.length > 0,
          `notification list must expose records (got shape ${Object.keys(notifBody?.data ?? {}).join(',')})`,
        ).toBe(true);

        // NotificationDTO fields: sourceType, sourceId, title, content (camelCase).
        // The delegate sets sourceType="bpm" and sourceId=activityId (the notify
        // node id). Title comes from the template subject ("Leave request
        // approved"), body substitutes ${businessKey} and ${days}.
        const approveNotif = rows.find(
          (r) =>
            String(r.sourceType ?? '') === 'bpm' &&
            String(r.sourceId ?? '') === 'svc_notify_approved',
        );
        expect(
          approveNotif,
          `notification row (sourceType=bpm, sourceId=svc_notify_approved) must exist for applicant=${adminUserId}; ` +
            `got ${rows.length} rows first=${JSON.stringify(rows[0] ?? {}).slice(0, 300)}`,
        ).toBeTruthy();

        expect(
          String(approveNotif?.title ?? '').toLowerCase(),
          `title should reflect the approved template subject`,
        ).toContain('approved');

        // Body rendering: `${businessKey}` and `${days}` should have been
        // substituted, so the persisted content must include our pid + days.
        const content = String(approveNotif?.content ?? '');
        expect(
          content.includes(approvePid),
          `rendered body must substitute businessKey=${approvePid}: ${content}`,
        ).toBe(true);
        expect(
          content.includes('2'),
          `rendered body must substitute days=2: ${content}`,
        ).toBe(true);
      },
    );

    // =========================================================================
    // NT-2: reject path triggers notify_rejected (negative branch).
    // =========================================================================
    test(
      'NT-2: reject flow fires notify_rejected and persists recipient notification',
      async ({ page, request }) => {
        const seeded = await seedLeaveDraft(
          request,
          adminToken,
          adminUserId,
          `NT-2 notify reject ${UID}`,
        );
        rejectPid = seeded.pid;
        rejectCode = seeded.code;

        const afterSubmit: InstanceStatus = await queryInstanceStatus(
          request,
          adminToken,
          { processKey: PROCESS_KEY, businessKey: rejectPid },
        );
        rejectInstanceId = String(afterSubmit.instanceId);
        expect(rejectInstanceId).toBeTruthy();
        expect(afterSubmit.currentNodes.map((n) => n.nodeId)).toContain(
          'task_manager_approve',
        );

        // UI reachability: reject menu item is exposed
        await navigateToTaskCenter(page);
        const taskId = await findManagerTaskId(request, adminToken, rejectInstanceId);

        const taskRow = page
          .locator('table tbody tr')
          .filter({ hasText: PROCESS_KEY })
          .filter({ hasText: /task_manager_approve|主管审批|Manager Approve/i })
          .first();
        await expect(taskRow).toBeVisible({ timeout: 15_000 });
        const moreBtn = taskRow
          .locator('button')
          .filter({ has: page.locator('svg.lucide-ellipsis') })
          .first();
        await moreBtn.click();
        const menu = page.locator('.absolute.right-0.z-10');
        await expect(menu).toBeVisible({ timeout: 3_000 });
        await expect(
          menu.locator('button').filter({ hasText: /驳回|Reject|拒绝/i }).first(),
          'Reject action must be reachable from Task Center row menu',
        ).toBeVisible();
        await page.keyboard.press('Escape').catch(() => {});

        const rejectResp = await request.post(
          `/api/bpm/tasks/${encodeURIComponent(taskId)}/reject`,
          {
            headers: {
              Authorization: `Bearer ${adminToken}`,
              'Content-Type': 'application/json',
            },
            data: {
              comment: `NT-2 reject ${UID}`,
              variables: { taskResult: 'rejected' },
            },
          },
        );
        expect(
          rejectResp.status(),
          `reject HTTP=${rejectResp.status()} body=${await rejectResp.text().then((t) => t.slice(0, 300))}`,
        ).toBeLessThan(400);

        const audit = await waitForNotifyCompletion(
          request,
          adminToken,
          rejectInstanceId,
          'svc_notify_rejected',
        );

        type Ev = { activityId: string; eventType: string };
        const notifyEvents: Ev[] = audit
          .filter((a) => a.operation === AuditOp.ACTIVITY_EVENT)
          .map((a) => ({
            activityId: (a.details?.activityId as string) ?? '',
            eventType: (a.details?.eventType as string) ?? '',
          }))
          .filter((e) => e.activityId === 'svc_notify_rejected');
        expect(
          notifyEvents.some((e) => e.eventType === 'activity_start'),
          `svc_notify_rejected activity_start must be audited`,
        ).toBe(true);
        expect(
          notifyEvents.some((e) => e.eventType === 'activity_end'),
          `svc_notify_rejected activity_end must be audited`,
        ).toBe(true);
        // Cross-check: approved branch must NOT have fired for this instance.
        const approvedAny = audit.some(
          (a) =>
            a.operation === AuditOp.ACTIVITY_EVENT &&
            (a.details?.activityId as string) === 'svc_notify_approved',
        );
        expect(
          approvedAny,
          'svc_notify_approved must NOT appear in the rejected instance audit',
        ).toBe(false);

        const notifListResp = await request.get(
          '/api/notifications?pageNum=1&pageSize=50',
          { headers: { Authorization: `Bearer ${adminToken}` } },
        );
        expect(notifListResp.ok()).toBe(true);
        const rows = ((await notifListResp.json())?.data?.records ?? []) as Array<
          Record<string, unknown>
        >;

        const rejectNotif = rows.find(
          (r) =>
            String(r.sourceType ?? '') === 'bpm' &&
            String(r.sourceId ?? '') === 'svc_notify_rejected',
        );
        expect(
          rejectNotif,
          `notification row (sourceType=bpm, sourceId=svc_notify_rejected) must exist for applicant=${adminUserId}`,
        ).toBeTruthy();
        expect(
          String(rejectNotif?.title ?? '').toLowerCase(),
          'title should reflect the rejected template subject',
        ).toContain('reject');
        const content = String(rejectNotif?.content ?? '');
        expect(
          content.includes(rejectPid),
          `rendered body must substitute businessKey=${rejectPid}: ${content}`,
        ).toBe(true);
        expect(
          content.includes('2'),
          `rendered body must substitute days=2: ${content}`,
        ).toBe(true);
      },
    );

    // =========================================================================
    // NT-3: cleanup — terminate any leftover running instance (idempotent)
    // =========================================================================
    test('NT-3: cleanup terminates leftover running instances', async ({ request }) => {
      const ids = [approveInstanceId, rejectInstanceId].filter((v) => v.length > 0);
      if (ids.length === 0) return;

      for (const iid of ids) {
        // Only terminate if still running. By NT-1/NT-2 we expect the notify
        // tasks to have completed synchronously and the process to have
        // reached endEvent, but asynchronous endEvent variants or partial
        // failures would leave the instance running — in which case we
        // terminate so the next test run starts clean.
        const statusResp = await request.get(
          `/api/bpm/process-instances/${iid}`,
          { headers: { Authorization: `Bearer ${adminToken}` } },
        );
        if (!statusResp.ok()) continue;
        const status = String((await statusResp.json())?.data?.status ?? '').toLowerCase();
        if (status !== 'running' && status !== 'active') continue;

        const terminateResp = await request.post(
          `/api/bpm/process-instances/${iid}/terminate`,
          {
            headers: {
              Authorization: `Bearer ${adminToken}`,
              'Content-Type': 'application/json',
            },
            data: { reason: `${UID} cleanup` },
          },
        );
        expect([200, 204, 500]).toContain(terminateResp.status());
      }

      // Sanity: codes captured by seedLeaveDraft should be WDLR-*; kept as a
      // diagnostic cross-check (non-empty pids imply non-empty codes).
      if (approvePid) expect(approveCode).toMatch(/^WDLR-/);
      if (rejectPid) expect(rejectCode).toMatch(/^WDLR-/);
    });
  },
);
