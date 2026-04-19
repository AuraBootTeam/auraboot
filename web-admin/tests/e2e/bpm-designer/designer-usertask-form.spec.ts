/**
 * D1 — UserTask formBinding + fieldPermissions
 *
 * Covers:
 *   - userTask node with assigneeType/assigneeValue configuration
 *   - formBinding: formRef, formType, fieldPermissions, saveStrategy
 *
 * Three-layer assertions:
 *   L1 — designerJson: node ids, edges, formBinding.fieldPermissions, saveStrategy
 *   L2 — BPMN XML: userTask flow element present, formKey attribute matches formRef
 *   L3 — runtime: start instance, advance through task_manager_approve, final status = completed
 *
 * Additional: task form schema assertion via GET /api/bpm/forms/task/{taskId}
 *   verifies formBinding.fieldPermissions returned at runtime.
 *
 * CONCERN: startInstanceAndAdvance helper (bpm-assertions.ts line 415) calls
 *   POST /api/bpm/tasks/{taskId}/complete — but task spec says real endpoint is
 *   /approve. If the backend only accepts /approve, L3 will fail at runtime.
 *   The helper is NOT modified in this task per red-line rules; surface here for follow-up.
 *
 * Task form endpoint: GET /api/bpm/forms/task/{taskId}
 *   Source: BpmFormController.java @GetMapping("/task/{taskId}"), returns TaskFormResponse
 *   with formBinding (FormBindingConfig) containing fieldPermissions and saveStrategy.
 *
 * Red lines honoured:
 *   - page.goto only for /login
 *   - No waitForTimeout
 *   - No afterAll
 *   - Network waits ≤ 15s, UI assertions ≤ 5s
 */

import { test, expect } from '@playwright/test';
import {
  openDesigner,
  addNode,
  connect,
  configureNode,
  saveProcess,
  deployProcess,
} from '../../helpers/designer-dsl';
import {
  assertDesignerJson,
  assertBpmnXml,
  startInstanceAndAdvance,
} from '../../helpers/bpm-assertions';
import { loginAs } from '../../helpers/wd-fixtures';

const BACKEND = 'http://localhost:6443';

test.describe('D1 — designer: userTask formBinding + fieldPermissions', () => {
  test('configure userTask with formBinding, assert L1/L2/L3 + runtime form schema', async ({
    page,
    request,
  }) => {
    const processKey = `e2e_designer_ut_${Date.now()}`;

    // -------------------------------------------------------------------------
    // Auth: API token (admin session preloaded via storageState)
    // -------------------------------------------------------------------------
    // Admin session preloaded via storageState (tests/storage/admin.json).

    // API token for backend assertions
    const adminToken = await loginAs(request, 'admin@example.com', 'Test2026x');

    // -------------------------------------------------------------------------
    // Build the minimal userTask flow
    // -------------------------------------------------------------------------
    await openDesigner(page, { processKey, name: `D1 UT ${processKey}` });

    await addNode(page, { id: 'start_1', type: 'startEvent', position: { x: 80, y: 200 }, label: 'Start' });
    await addNode(page, { id: 'task_manager_approve', type: 'userTask', position: { x: 280, y: 200 }, label: 'Manager Approve' });
    await addNode(page, { id: 'end_1', type: 'endEvent', position: { x: 480, y: 200 }, label: 'End' });

    // Configure the userTask: assignee + full formBinding
    await configureNode(page, 'task_manager_approve', {
      assigneeType: 'role',
      assigneeValue: 'wd_manager',
      formPageKey: 'wd_leave_request_detail',
      formBinding: {
        formRef: 'wd_leave_request_detail',
        formType: 'PAGE',
        fieldPermissions: {
          days: 'readonly',
          type: 'readonly',
          approver_comment: 'editable',
        },
        saveStrategy: 'business_only',
      },
    });

    // Connect nodes
    await connect(page, { from: 'start_1', to: 'task_manager_approve' });
    await connect(page, { from: 'task_manager_approve', to: 'end_1' });

    // -------------------------------------------------------------------------
    // Save and deploy
    // -------------------------------------------------------------------------
    const { processDefinitionId: pdId } = await saveProcess(page);
    await deployProcess(page, pdId);

    // -------------------------------------------------------------------------
    // L1 — Designer JSON: node ids + edges present
    // -------------------------------------------------------------------------
    await assertDesignerJson(request, adminToken, pdId, {
      nodeIds: ['start_1', 'task_manager_approve', 'end_1'],
      edgeSpecs: [
        { from: 'start_1', to: 'task_manager_approve' },
        { from: 'task_manager_approve', to: 'end_1' },
      ],
    });

    // L1 extended — verify formBinding stored in designerJson node data
    const pdResp = await request.get(`${BACKEND}/api/bpm/process-definitions/${pdId}`, {
      headers: { Authorization: `Bearer ${adminToken}` },
    });
    expect(pdResp.ok(), `GET /api/bpm/process-definitions/${pdId} failed: ${pdResp.status()}`).toBe(true);

    const pdBody = (await pdResp.json()) as Record<string, unknown>;
    const pdData = pdBody.data as Record<string, unknown>;
    const rawDesignerJson = pdData.designerJson as string;
    expect(typeof rawDesignerJson, 'designerJson must be a string').toBe('string');

    const dj = JSON.parse(rawDesignerJson) as Record<string, unknown>;
    const nodes = (dj.nodes ?? []) as Array<Record<string, unknown>>;
    const utNode = nodes.find((n) => n.id === 'task_manager_approve');
    expect(utNode, 'task_manager_approve node must exist in designerJson').toBeDefined();

    const utData = utNode!.data as Record<string, unknown> | undefined;
    expect(utData, 'task_manager_approve node must have data').toBeDefined();

    const storedFormBinding = utData!.formBinding as Record<string, unknown> | undefined;
    expect(storedFormBinding, 'formBinding must be stored in node data').toBeDefined();

    const storedFieldPerms = storedFormBinding!.fieldPermissions as Record<string, unknown> | undefined;
    expect(storedFieldPerms, 'fieldPermissions must be present in formBinding').toBeDefined();
    expect(storedFieldPerms!.days, 'days field must be readonly').toBe('readonly');
    expect(storedFieldPerms!.type, 'type field must be readonly').toBe('readonly');
    expect(storedFieldPerms!.approver_comment, 'approver_comment field must be editable').toBe('editable');
    expect(storedFormBinding!.saveStrategy, 'saveStrategy must be business_only').toBe('business_only');

    // -------------------------------------------------------------------------
    // L2 — BPMN XML: userTask element present + formKey attribute
    // -------------------------------------------------------------------------
    await assertBpmnXml(request, adminToken, pdId, {
      hasFlowElement: ['task_manager_approve'],
      userTaskFormKey: { task_manager_approve: 'wd_leave_request_detail' },
    });

    // -------------------------------------------------------------------------
    // L3 — Runtime: start instance, advance, assert completed
    //
    // CONCERN: startInstanceAndAdvance posts to /complete (line 415 in bpm-assertions.ts).
    // Task spec states real endpoint is /approve. If backend rejects /complete,
    // this step will fail. Do NOT modify the helper — flag for follow-up.
    // -------------------------------------------------------------------------
    const { instanceId, finalStatus } = await startInstanceAndAdvance(
      request,
      adminToken,
      pdId,
      {},
      [{ taskDefKey: 'task_manager_approve', action: 'complete', vars: { taskResult: 'approved' } }],
    );
    expect(finalStatus, 'process must reach completed status').toBe('completed');

    // -------------------------------------------------------------------------
    // L3 extended — Runtime form schema assertion
    // Fetch the active task BEFORE advancing (instance just started).
    // We start a second instance here because startInstanceAndAdvance already
    // consumed the first task. This is intentional: we need an active task
    // to query the form schema endpoint.
    // -------------------------------------------------------------------------
    const start2Resp = await request.post(`${BACKEND}/api/bpm/process-instances`, {
      headers: { Authorization: `Bearer ${adminToken}`, 'Content-Type': 'application/json' },
      data: { processDefinitionId: pdId, variables: {} },
    });
    expect(start2Resp.ok(), `Second start failed: ${start2Resp.status()}`).toBe(true);

    const start2Body = (await start2Resp.json()) as Record<string, unknown>;
    const start2Data = start2Body.data as Record<string, unknown>;
    const instanceId2 = start2Data.instanceId as string;
    expect(instanceId2, 'second instanceId must be present').toBeTruthy();

    // List active tasks for the second instance
    const tasksResp = await request.get(`${BACKEND}/api/bpm/tasks/by-process/${instanceId2}`, {
      headers: { Authorization: `Bearer ${adminToken}` },
    });
    expect(tasksResp.ok(), `GET tasks/by-process failed: ${tasksResp.status()}`).toBe(true);

    const tasksBody = (await tasksResp.json()) as Record<string, unknown>;
    const tasks = tasksBody.data as Array<Record<string, unknown>>;
    expect(Array.isArray(tasks), 'tasks must be an array').toBe(true);

    const activeTask = tasks.find(
      (t) => (t.processDefinitionActivityId as string) === 'task_manager_approve',
    );
    expect(activeTask, 'active task with processDefinitionActivityId task_manager_approve must exist').toBeDefined();

    const activeTaskId = activeTask!.instanceId as string;
    expect(activeTaskId, 'active task instanceId must be present').toBeTruthy();

    // GET /api/bpm/forms/task/{taskId} — returns TaskFormResponse with formBinding
    // Source: BpmFormController.java @GetMapping("/task/{taskId}")
    const formResp = await request.get(`${BACKEND}/api/bpm/forms/task/${activeTaskId}`, {
      headers: { Authorization: `Bearer ${adminToken}` },
    });
    expect(formResp.ok(), `GET /api/bpm/forms/task/${activeTaskId} failed: ${formResp.status()}`).toBe(true);

    const formBody = (await formResp.json()) as Record<string, unknown>;
    const formData = formBody.data as Record<string, unknown>;
    expect(formData, 'form response must have data').toBeDefined();

    const runtimeFormBinding = formData.formBinding as Record<string, unknown> | undefined;
    expect(runtimeFormBinding, 'runtime formBinding must be present in task form response').toBeDefined();

    const runtimeFieldPerms = runtimeFormBinding!.fieldPermissions as Record<string, unknown> | undefined;
    expect(runtimeFieldPerms, 'runtime fieldPermissions must be present').toBeDefined();
    expect(runtimeFieldPerms!.days, 'runtime: days must be readonly').toBe('readonly');
    expect(runtimeFieldPerms!.approver_comment, 'runtime: approver_comment must be editable').toBe('editable');
    expect(runtimeFormBinding!.saveStrategy, 'runtime: saveStrategy must be business_only').toBe('business_only');

    // Confirm instance 1 (the one advanced through startInstanceAndAdvance) reached completed
    expect(instanceId, 'first instance must have been started').toBeTruthy();
  });
});
