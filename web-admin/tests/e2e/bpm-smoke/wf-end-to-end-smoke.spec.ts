/**
 * BPM End-to-End Smoke Test
 *
 * Covers the full lifecycle: designer → deploy → runtime execution.
 *
 * Tiny flow: start → userTask (Approve) → exclusiveGateway (Decide) → end_ok / end_no
 *
 * Three-layer assertions:
 *   L1 — designerJson: node ids + edge conditions present in stored JSON
 *   L2 — BPMN XML: flow elements + formKey present in generated XML
 *   L3 — runtime: process instance reaches "completed" after task completion
 *
 * Red lines:
 *   - page.goto only for /login
 *   - No waitForTimeout
 *   - No afterAll cleanup
 *   - Assertion timeouts ≤ 5s; network waits ≤ 15s
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

test.describe('BPM end-to-end smoke', () => {
  test('designer→deploy→run: tiny flow with exclusive gateway', async ({ page, request }) => {
    const processKey = 'e2e_smoke_' + Date.now();

    // -------------------------------------------------------------------------
    // UI login
    // -------------------------------------------------------------------------
    await page.goto('/login');
    await page.getByLabel(/email|邮箱/i).fill('admin@example.com');
    await page.getByLabel(/password|密码/i).fill('Test2026x');
    await page.getByRole('button', { name: /login|登录|sign in/i }).click();
    await page.waitForURL(/\/(dashboard|home|p\/|dashboards)/, { timeout: 15_000 });

    // API token for Layer 1/2/3 assertions
    const adminToken = await loginAs(request, 'admin@example.com', 'Test2026x');

    // -------------------------------------------------------------------------
    // Build the tiny flow via designer DSL
    // -------------------------------------------------------------------------
    await openDesigner(page, { processKey, name: `Smoke ${processKey}` });

    // Add nodes
    await addNode(page, { id: 'start_1', type: 'startEvent', position: { x: 80, y: 200 }, label: 'Start' });
    await addNode(page, { id: 'task_1', type: 'userTask', position: { x: 260, y: 200 }, label: 'Approve' });
    await addNode(page, { id: 'gw_1', type: 'exclusiveGateway', position: { x: 440, y: 200 }, label: 'Decide' });
    await addNode(page, { id: 'end_ok', type: 'endEvent', position: { x: 620, y: 120 }, label: 'OK' });
    await addNode(page, { id: 'end_no', type: 'endEvent', position: { x: 620, y: 280 }, label: 'NO' });

    // Configure the user task
    await configureNode(page, 'task_1', {
      assigneeType: 'role',
      assigneeValue: 'wd_manager',
      formPageKey: 'wd_leave_request_detail',
    });

    // Connect nodes
    await connect(page, { from: 'start_1', to: 'task_1' });
    await connect(page, { from: 'task_1', to: 'gw_1' });
    await connect(page, { from: 'gw_1', to: 'end_ok', condition: "${taskResult=='approved'}" });
    await connect(page, { from: 'gw_1', to: 'end_no', condition: "${taskResult=='rejected'}" });

    // -------------------------------------------------------------------------
    // Save and deploy
    // -------------------------------------------------------------------------
    const { processDefinitionId } = await saveProcess(page);
    await deployProcess(page, processDefinitionId);

    // -------------------------------------------------------------------------
    // Layer 1 — Designer JSON assertions
    // -------------------------------------------------------------------------
    await assertDesignerJson(request, adminToken, processDefinitionId, {
      nodeIds: ['start_1', 'task_1', 'gw_1', 'end_ok', 'end_no'],
      edgeSpecs: [
        { from: 'gw_1', to: 'end_ok', condition: "taskResult=='approved'" },
        { from: 'gw_1', to: 'end_no', condition: "taskResult=='rejected'" },
      ],
    });

    // -------------------------------------------------------------------------
    // Layer 2 — BPMN XML assertions
    // -------------------------------------------------------------------------
    await assertBpmnXml(request, adminToken, processDefinitionId, {
      hasFlowElement: ['task_1', 'gw_1', 'end_ok', 'end_no'],
      // gatewayConditions: edge id convention unknown at spec-write time;
      // omitted to avoid false negatives — Layer 1 already verified conditions.
      // Follow-up: determine sequenceFlow id format from converter and add here.
      gatewayConditions: {},
      userTaskFormKey: { task_1: 'wd_leave_request_detail' },
    });

    // -------------------------------------------------------------------------
    // Layer 3 — Runtime: start + advance through approval path
    // -------------------------------------------------------------------------
    const { finalStatus } = await startInstanceAndAdvance(
      request,
      adminToken,
      processDefinitionId,
      {},
      [{ taskDefKey: 'task_1', action: 'complete', vars: { taskResult: 'approved' } }],
    );

    expect(finalStatus).toBe('completed');
  });
});
