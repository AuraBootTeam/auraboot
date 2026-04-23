/**
 * BPM rule-task Drools Execution E2E — P2.3
 *
 * Validates that the `rule-task` node in `wd_leave_approval` actually fires
 * the Drools ruleset `wd_leave_routing`, writes a `approverRole` process
 * variable, and that the downstream exclusive gateway `gw_approver` routes
 * to the correct userTask branch based on the rule output.
 *
 * Unlike workflow-demo-leave-flow.spec.ts (B5), which exercises the full
 * UI submit → approve lifecycle, this spec focuses narrowly on the
 * rule-task semantics: fact input → rule fire → variable output →
 * gateway branching → audit trail evidence.
 *
 * Why this is NOT pure API:
 *   - Instance start is API-driven intentionally: the rule-task executes
 *     engine-internally (no UI surface for the rule itself), and the task
 *     description explicitly allows "API start instance + UI Task Center
 *     verification" (see P2.3 brief).
 *   - RT-2 drives the sidebar → Task Center navigation to confirm the
 *     hr-branch userTask actually surfaces in the user-facing Task Center
 *     (D1 + D10), not just in API tomography.
 *
 * Rule contract (plugins/workflow-demo/rules/wd_leave_routing.drl):
 *   days  < 3.0  →  approverRole = 'manager'
 *   days >= 3.0  →  approverRole = 'hr'
 *   (NO director branch — the task brief mentioned director, but the
 *   deployed .drl only has two branches. RT-3 is therefore repurposed to
 *   exercise the boundary value days=3 which must still route to hr.)
 *
 * Process routing (plugins/workflow-demo/config/processes.json):
 *   edge flow_gw_manager : ${approverRole == 'manager'} → task_manager_approve
 *   edge flow_gw_hr      : ${approverRole == 'hr'}      → task_hr_approve
 *
 * Dimensions covered:
 *   D1  — sidebar nav to Task Center (RT-2)
 *   D7  — variable values asserted on instance status DTO
 *   D10 — Task Center row visible for hr-branch task (RT-2)
 *   D12 — audit trail contains activity_start + activity_end for svc_rule_route
 *         and the correct downstream userTask
 *
 * @since P2.3 (OSS BPM rule-task regression)
 */

import { test, expect } from '../../fixtures';
import { uniqueId, dateOffsetStr } from '../helpers';
import { ensureRoleUsers } from '../../helpers/wd-fixtures';
import {
  loginAsAdmin,
  queryInstanceStatus,
  listAuditEvents,
  collectActivityEvents,
  waitForTodoTask,
  AuditOp,
  type InstanceStatus,
} from './_helpers/bpm-lifecycle';
import { findTaskRowByBusinessKey, openTaskCenterAsRole } from './_helpers/task-center';

const PROCESS_KEY = 'wd_leave_approval';
const RULE_NODE_ID = 'svc_rule_route';
const GATEWAY_NODE_ID = 'gw_approver';
const MANAGER_TASK_ID = 'task_manager_approve';
const HR_TASK_ID = 'task_hr_approve';

// Shared across serial tests
let adminToken = '';
let adminUserId = '';
let hrToken = '';

// Per-scenario state (instance ids + business keys)
const scenarios = {
  managerDays1: { pid: '', instanceId: '' },
  hrDays5: { pid: '', instanceId: '' },
  hrBoundaryDays3: { pid: '', instanceId: '' },
};

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Seed a draft wd_leave_request with the desired `days` value, then submit
 * via the wd:submit_leave_request command. The command's postActions start
 * the wd_leave_approval BPM instance with the record's fields as variables
 * (including `days`), which is what the rule-task consumes as facts.
 *
 * Returns { pid, instanceId } for downstream assertions.
 */
async function seedAndSubmitLeave(
  request: import('@playwright/test').APIRequestContext,
  days: number,
  uidTag: string,
): Promise<{ pid: string; instanceId: string }> {
  const createResp = await request.post(
    '/api/meta/commands/execute/wd:create_leave_request',
    {
      headers: { Authorization: `Bearer ${adminToken}` },
      data: {
        payload: {
          wd_req_applicant: adminUserId,
          wd_req_type: 'annual',
          wd_req_start_date: dateOffsetStr(10),
          wd_req_start_slot: 'AM',
          wd_req_end_date: dateOffsetStr(10 + days),
          wd_req_end_slot: 'PM',
          wd_req_days: days,
          wd_req_reason: `RT rule-task test ${uidTag} days=${days}`,
        },
        operationType: 'create',
      },
    },
  );
  expect(createResp.ok(), `draft create must succeed (days=${days}): ${createResp.status()}`).toBe(
    true,
  );
  const createBody = await createResp.json();
  expect(String(createBody?.code)).toBe('0');
  const pid = String(
    createBody?.data?.data?.recordId ??
      createBody?.data?.data?.pid ??
      createBody?.data?.data?.id ??
      '',
  );
  expect(pid, `create must return a recordId (days=${days})`).toBeTruthy();

  // state_transition commands identify the target record via the top-level
  // `targetRecordId` on CommandExecuteRequest (NOT payload.pid — see
  // CommandFieldMapExecutor.java line 234). The payload still needs to
  // carry the fields referenced by postActions[].variables expressions
  // (days, type, applicant) so the BPM instance receives correct facts
  // for the rule-task.
  const submitResp = await request.post(
    '/api/meta/commands/execute/wd:submit_leave_request',
    {
      headers: { Authorization: `Bearer ${adminToken}` },
      data: {
        targetRecordId: pid,
        payload: {
          wd_req_applicant: adminUserId,
          wd_req_type: 'annual',
          wd_req_start_date: dateOffsetStr(10),
          wd_req_start_slot: 'AM',
          wd_req_end_date: dateOffsetStr(10 + days),
          wd_req_end_slot: 'PM',
          wd_req_days: days,
          wd_req_reason: `RT rule-task test ${uidTag} days=${days}`,
        },
        operationType: 'update',
      },
    },
  );
  expect(
    submitResp.ok(),
    `submit must succeed (days=${days}): HTTP=${submitResp.status()} body=${await submitResp
      .text()
      .then((t) => t.slice(0, 300))}`,
  ).toBe(true);
  const submitBody = await submitResp.json();
  expect(String(submitBody?.code)).toBe('0');

  // Cross-check: the record now has a process instance id wired by postActions.
  const detailResp = await request.get(`/api/dynamic/wd_leave_request_detail/${pid}`, {
    headers: { Authorization: `Bearer ${adminToken}` },
  });
  expect(detailResp.ok()).toBe(true);
  const record = (await detailResp.json())?.data;
  expect(record?.wd_req_status, `record status after submit (days=${days})`).toBe('submitted');
  const instanceId = String(record?.wd_req_process_instance ?? '');
  expect(
    instanceId,
    `wd_req_process_instance must be populated after submit (days=${days})`,
  ).toBeTruthy();

  return { pid, instanceId };
}

/**
 * Poll the instance status until svc_rule_route has completed AND
 * gw_approver has completed. Returns the latest status.
 *
 * The rule-task + exclusive gateway both execute synchronously when the
 * instance starts, so this should be effectively immediate — but we still
 * poll briefly to avoid a race with the submit command's
 * process-start event bus.
 */
async function waitForRuleAndGatewayCompleted(
  request: import('@playwright/test').APIRequestContext,
  businessKey: string,
): Promise<InstanceStatus> {
  let lastStatus: InstanceStatus | null = null;
  await expect
    .poll(
      async () => {
        lastStatus = await queryInstanceStatus(request, adminToken, {
          processKey: PROCESS_KEY,
          businessKey,
        });
        const completedIds = lastStatus.completedNodes.map((n) => n.nodeId);
        return (
          completedIds.includes(RULE_NODE_ID) && completedIds.includes(GATEWAY_NODE_ID)
        );
      },
      {
        timeout: 5_000,
        message: `rule-task + gateway must complete for businessKey=${businessKey}`,
      },
    )
    .toBe(true);
  if (!lastStatus) {
    throw new Error('lastStatus unexpectedly null');
  }
  return lastStatus;
}

// ---------------------------------------------------------------------------
// Test suite
// ---------------------------------------------------------------------------

test.describe.serial(
  'BPM rule-task Drools execution (wd_leave_routing)',
  { tag: ['@bpm-regression'] },
  () => {
    test.setTimeout(120_000);

    test.beforeAll(async ({ request }) => {
      adminToken = await loginAsAdmin(request);
      ({ hrToken } = await ensureRoleUsers(request));
      const meResp = await request.get('/api/auth/me', {
        headers: { Authorization: `Bearer ${adminToken}` },
      });
      expect(meResp.ok(), `resolve admin /me: ${meResp.status()}`).toBe(true);
      adminUserId = String((await meResp.json())?.data?.user?.id ?? '');
      expect(adminUserId, 'admin userId must be resolvable').toBeTruthy();
    });

    // =====================================================================
    // RT-1: days=1 → wd_leave_routing fires → approverRole=manager → manager branch
    // =====================================================================
    test('RT-1: days=1 routes through rule-task to manager branch', async ({ request }) => {
      const uid = uniqueId('RT1');
      const { pid, instanceId } = await seedAndSubmitLeave(request, 1, uid);
      scenarios.managerDays1 = { pid, instanceId };

      const status = await waitForRuleAndGatewayCompleted(request, pid);

      // Rule output written onto process variables
      expect(
        status.variables.approverRole,
        `approverRole variable after rule fire (days=1): ${JSON.stringify(status.variables)}`,
      ).toBe('manager');

      // Gateway routed to manager userTask; hr branch must NOT be active
      const activeIds = status.currentNodes.map((n) => n.nodeId);
      expect(
        activeIds,
        `active nodes after manager routing: ${JSON.stringify(activeIds)}`,
      ).toContain(MANAGER_TASK_ID);
      expect(activeIds).not.toContain(HR_TASK_ID);

      // Rule-task + gateway must be in completed nodes (already asserted by poll,
      // but reassert explicitly for readability)
      const completedIds = status.completedNodes.map((n) => n.nodeId);
      expect(completedIds).toContain(RULE_NODE_ID);
      expect(completedIds).toContain(GATEWAY_NODE_ID);
    });

    // =====================================================================
    // RT-2: days=5 → approverRole=hr → hr branch; Task Center surfaces the task
    // =====================================================================
    test('RT-2: days=5 routes to hr branch and surfaces in Task Center', async ({
      browser,
      request,
    }) => {
      const uid = uniqueId('RT2');
      const { pid, instanceId } = await seedAndSubmitLeave(request, 5, uid);
      scenarios.hrDays5 = { pid, instanceId };

      const status = await waitForRuleAndGatewayCompleted(request, pid);

      expect(
        status.variables.approverRole,
        `approverRole variable (days=5): ${JSON.stringify(status.variables)}`,
      ).toBe('hr');

      const activeIds = status.currentNodes.map((n) => n.nodeId);
      expect(activeIds).toContain(HR_TASK_ID);
      expect(activeIds).not.toContain(MANAGER_TASK_ID);

      const ourTask = await waitForTodoTask(
        request,
        hrToken,
        (candidate) =>
          candidate.processInstanceId === String(instanceId) &&
          candidate.processDefinitionActivityId.includes(HR_TASK_ID),
        {
          timeout: 15_000,
          message: `todo tasks must include our hr-branch task for instanceId=${instanceId}`,
        },
      );

      // UI verification: the hr task must surface in Task Center (D1 + D10).
      const { context: hrCtx, page: hrPage } = await openTaskCenterAsRole(
        browser,
        'wd_hr@example.com',
        'Test2026x',
      );

      const taskRow = findTaskRowByBusinessKey(
        hrPage,
        pid,
        /task_hr_approve|HR 审批|HR Approve/i,
      );
      await expect(
        taskRow,
        `a task_hr_approve row for businessKey=${pid} must appear in Task Center`,
      ).toBeVisible({ timeout: 15_000 });
      expect(ourTask.instanceId).toBeTruthy();
      await hrCtx.close();
    });

    // =====================================================================
    // RT-3: days=3 boundary (>=3.0 in rule) → approverRole=hr → hr branch
    //
    // NOTE: The task brief requested days=15 → director. The deployed
    // wd_leave_routing.drl only has two branches (days<3 → manager,
    // days>=3 → hr) — no director role. Rather than mutating the plugin
    // rule file (red line: "don't modify plugin / backend"), RT-3 instead
    // exercises the rule threshold boundary at exactly days=3, which
    // verifies the `>=` operator behaves as declared. This keeps the
    // spec truthful to the shipped rule.
    // =====================================================================
    test('RT-3: days=3 boundary still routes to hr (rule threshold >= 3.0)', async ({
      request,
    }) => {
      const uid = uniqueId('RT3');
      const { pid, instanceId } = await seedAndSubmitLeave(request, 3, uid);
      scenarios.hrBoundaryDays3 = { pid, instanceId };

      const status = await waitForRuleAndGatewayCompleted(request, pid);

      expect(
        status.variables.approverRole,
        `days=3 boundary must map to hr (rule uses >= 3.0): ${JSON.stringify(status.variables)}`,
      ).toBe('hr');

      const activeIds = status.currentNodes.map((n) => n.nodeId);
      expect(activeIds).toContain(HR_TASK_ID);
      expect(activeIds).not.toContain(MANAGER_TASK_ID);
    });

    // =====================================================================
    // RT-4: audit trail captures rule-task + gateway + downstream userTask events
    // =====================================================================
    test('RT-4: audit trail contains activity_event evidence for rule-task execution', async ({
      request,
    }) => {
      const { instanceId } = scenarios.managerDays1;
      expect(instanceId, 'RT-1 instanceId must be set').toBeTruthy();

      const audit = await listAuditEvents(request, adminToken, instanceId);
      expect(audit.length, 'audit trail must not be empty').toBeGreaterThan(0);

      const activityEvents = collectActivityEvents(audit);
      expect(
        activityEvents.length,
        `must have activity_event rows (got ${activityEvents.length})`,
      ).toBeGreaterThan(0);

      // rule-task must have BOTH activity_start AND activity_end (it executes
      // synchronously and transitions through the node in one step).
      const ruleEvents = activityEvents.filter((e) => e.activityId === RULE_NODE_ID);
      const ruleStart = ruleEvents.some((e) => e.eventType === 'activity_start');
      const ruleEnd = ruleEvents.some((e) => e.eventType === 'activity_end');
      expect(
        ruleStart,
        `${RULE_NODE_ID} activity_start must be audited: events=${JSON.stringify(ruleEvents)}`,
      ).toBe(true);
      expect(
        ruleEnd,
        `${RULE_NODE_ID} activity_end must be audited (rule-task runs synchronously)`,
      ).toBe(true);

      // gw_approver should also have completed
      const gwEvents = activityEvents.filter((e) => e.activityId === GATEWAY_NODE_ID);
      expect(
        gwEvents.some((e) => e.eventType === 'activity_end'),
        `${GATEWAY_NODE_ID} activity_end must be audited`,
      ).toBe(true);

      // Downstream manager userTask must have started (instance was seeded with days=1)
      const mgrEvents = activityEvents.filter((e) => e.activityId === MANAGER_TASK_ID);
      expect(
        mgrEvents.some((e) => e.eventType === 'activity_start'),
        `${MANAGER_TASK_ID} activity_start must be audited for days=1 instance`,
      ).toBe(true);

      // Sanity: process_start row also present
      expect(
        audit.some(
          (a) =>
            a.operation === AuditOp.PROCESS_START ||
            (a.operation === AuditOp.PROCESS_EVENT &&
              a.details?.eventType === 'process_start'),
        ),
        'audit must include a process_start row',
      ).toBe(true);
    });

    // =====================================================================
    // RT-5: cleanup — terminate all running instances created in RT-1..RT-3
    // =====================================================================
    test('RT-5: cleanup terminates all test instances', async ({ request }) => {
      const targets = [
        scenarios.managerDays1,
        scenarios.hrDays5,
        scenarios.hrBoundaryDays3,
      ];

      for (const target of targets) {
        if (!target.instanceId) continue;

        const statusResp = await request.get(
          `/api/bpm/process-instances/by-business-key/status` +
            `?businessKey=${encodeURIComponent(target.pid)}` +
            `&processKey=${encodeURIComponent(PROCESS_KEY)}`,
          { headers: { Authorization: `Bearer ${adminToken}` } },
        );
        if (!statusResp.ok()) continue;
        const status = String((await statusResp.json())?.data?.status ?? '').toLowerCase();
        if (status !== 'running' && status !== 'active') continue;

        const terminateResp = await request.post(
          `/api/bpm/process-instances/${target.instanceId}/terminate`,
          {
            headers: {
              Authorization: `Bearer ${adminToken}`,
              'Content-Type': 'application/json',
            },
            data: { reason: 'RT rule-task cleanup' },
          },
        );
        // Accept 200/204/500 (backend may reject mid-state, best effort)
        expect([200, 204, 500]).toContain(terminateResp.status());
      }
    });
  },
);
