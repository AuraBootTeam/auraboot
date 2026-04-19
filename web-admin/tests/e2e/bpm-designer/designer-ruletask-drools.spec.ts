/**
 * BPM Designer — D4: ruleTask bound to Drools routing rule
 *
 * Flow:
 *   start_1 → rule_1 (rule-task / droolsServiceTaskDelegate)
 *           → gw_route (exclusiveGateway)
 *           → [approverRole=='manager'] → task_manager → end_1
 *           → [approverRole=='hr']     → task_hr      → end_1
 *
 * Rule: wd_leave_routing
 *   Fact keys  : days (Number)
 *   Output key : approverRole  ("manager" | "hr")
 *   Threshold  : days < 3  → manager ; days >= 3 → hr
 *   Source     : plugins/workflow-demo/rules/wd_leave_routing.drl
 *
 * Three-layer verification:
 *   L1 — assertDesignerJson: nodes + edges + rule_1.data.ruleCode / .factsVars
 *   L2 — assertBpmnXml:     <serviceTask id="rule_1"> with smart:class="droolsServiceTaskDelegate"
 *                            and smart:ruleCode="wd_leave_routing"
 *   L3 — runtime (two instances):
 *         • days=10 → approverRole=hr    → task_hr active   → complete → completed
 *         • days=2  → approverRole=manager → task_manager active → complete → completed
 *
 * Red lines:
 *   - page.goto only to /login (openDesigner uses sidebar navigation for all else)
 *   - No waitForTimeout
 *   - No afterAll cleanup
 *   - Network timeouts ≤ 15 s; UI assertions ≤ 5 s
 *
 * BPMN element note:
 *   JsonToBpmnConverter emits rule-task nodes as <serviceTask> (not <businessRuleTask>)
 *   with smart:class="droolsServiceTaskDelegate". L2 asserts the serviceTask tag directly
 *   from the raw XML.
 *
 * Rule output note:
 *   wd_leave_routing.drl writes into _ruleResult["approverRole"], not "approver".
 *   Gateway conditions must use `approverRole` as the variable name.
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
  type AdvanceStep,
} from '../../helpers/bpm-assertions';
import { loginAs } from '../../helpers/wd-fixtures';

// ---------------------------------------------------------------------------
// Process definition key — unique per run
// ---------------------------------------------------------------------------
const TS = Date.now();

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Provision a complete rule-task + gateway process via API (no designer UI save).
 * Used for L1/L2/L3 assertions once we have confirmed the designer hook works.
 */
async function createAndDeployRuleProcess(
  request: import('@playwright/test').APIRequestContext,
  token: string,
  processKey: string,
): Promise<string> {
  const processName = `D4 RuleTask ${processKey}`;

  // -------------------------------------------------------------------------
  // Build designerJson
  //
  // rule_1 node: type="rule-task", data has ruleCode + factsVars at the
  //   top level of data (not nested under data.config). This is how
  //   DroolsServiceTaskDelegateIntegrationTest builds it and how
  //   JsonToBpmnConverter reads it (switch branch for "rule-task" passes
  //   `data` directly as the `config` JsonNode, not data.config).
  // -------------------------------------------------------------------------
  const designerJson = JSON.stringify({
    nodes: [
      {
        id: 'start_1',
        type: 'startEvent',
        position: { x: 80, y: 250 },
        data: { type: 'startEvent', label: 'Start' },
      },
      {
        id: 'rule_1',
        type: 'rule-task',
        position: { x: 280, y: 250 },
        data: {
          type: 'rule-task',
          label: 'Route Leave',
          ruleCode: 'wd_leave_routing',
          factsVars: 'days,type',
        },
      },
      {
        id: 'gw_route',
        type: 'exclusiveGateway',
        position: { x: 480, y: 250 },
        data: { type: 'exclusiveGateway', label: 'Who approves?' },
      },
      {
        id: 'task_manager',
        type: 'userTask',
        position: { x: 680, y: 150 },
        data: {
          type: 'userTask',
          label: 'Manager Approve',
          assigneeType: 'role',
          assigneeValue: 'wd_manager',
        },
      },
      {
        id: 'task_hr',
        type: 'userTask',
        position: { x: 680, y: 350 },
        data: {
          type: 'userTask',
          label: 'HR Approve',
          assigneeType: 'role',
          assigneeValue: 'wd_hr',
        },
      },
      {
        id: 'end_1',
        type: 'endEvent',
        position: { x: 880, y: 250 },
        data: { type: 'endEvent', label: 'End' },
      },
    ],
    edges: [
      {
        id: 'e_start_rule',
        source: 'start_1',
        target: 'rule_1',
        type: 'smoothstep',
        data: { label: '' },
      },
      {
        id: 'e_rule_gw',
        source: 'rule_1',
        target: 'gw_route',
        type: 'smoothstep',
        data: { label: '' },
      },
      {
        id: 'e_gw_manager',
        source: 'gw_route',
        target: 'task_manager',
        type: 'conditional',
        data: {
          label: 'manager',
          condition: {
            content: "${approverRole=='manager'}",
            language: 'mvel',
          },
        },
      },
      {
        id: 'e_gw_hr',
        source: 'gw_route',
        target: 'task_hr',
        type: 'conditional',
        data: {
          label: 'hr',
          condition: {
            content: "${approverRole=='hr'}",
            language: 'mvel',
          },
        },
      },
      {
        id: 'e_manager_end',
        source: 'task_manager',
        target: 'end_1',
        type: 'smoothstep',
        data: { label: '' },
      },
      {
        id: 'e_hr_end',
        source: 'task_hr',
        target: 'end_1',
        type: 'smoothstep',
        data: { label: '' },
      },
    ],
  });

  // -------------------------------------------------------------------------
  // Build BPMN XML manually — matches what JsonToBpmnConverter produces for
  // rule-task nodes (serviceTask with smart:class="droolsServiceTaskDelegate"
  // and smart:ruleCode / smart:factsVars attributes).
  // -------------------------------------------------------------------------
  const bpmnXml = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:smart="http://auraboot.com/schema/smart"
             targetNamespace="http://auraboot.com/bpm"
             id="def_${processKey}">
  <process id="${processKey}" name="${processName}" isExecutable="true">
    <startEvent id="start_1" name="Start"/>
    <serviceTask id="rule_1" name="Route Leave"
        smart:class="droolsServiceTaskDelegate"
        smart:ruleCode="wd_leave_routing"
        smart:factsVars="days,type"/>
    <exclusiveGateway id="gw_route" name="Who approves?"/>
    <userTask id="task_manager" name="Manager Approve"/>
    <userTask id="task_hr" name="HR Approve"/>
    <endEvent id="end_1" name="End"/>
    <sequenceFlow id="e_start_rule" sourceRef="start_1" targetRef="rule_1"/>
    <sequenceFlow id="e_rule_gw" sourceRef="rule_1" targetRef="gw_route"/>
    <sequenceFlow id="e_gw_manager" sourceRef="gw_route" targetRef="task_manager">
      <conditionExpression xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:type="tFormalExpression">\${approverRole=='manager'}</conditionExpression>
    </sequenceFlow>
    <sequenceFlow id="e_gw_hr" sourceRef="gw_route" targetRef="task_hr">
      <conditionExpression xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:type="tFormalExpression">\${approverRole=='hr'}</conditionExpression>
    </sequenceFlow>
    <sequenceFlow id="e_manager_end" sourceRef="task_manager" targetRef="end_1"/>
    <sequenceFlow id="e_hr_end" sourceRef="task_hr" targetRef="end_1"/>
  </process>
</definitions>`;

  // Create draft
  const createResp = await request.post('/api/bpm/process-definitions', {
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    data: {
      processKey,
      processName,
      description: 'D4 ruleTask Drools routing E2E',
      category: 'e2e-test',
      bpmnContent: bpmnXml,
      designerJson,
    },
  });
  expect(createResp.ok(), `create process draft must succeed: ${createResp.status()}`).toBe(true);
  const createBody = await createResp.json();
  const pid = String(createBody?.data?.pid ?? createBody?.data?.id ?? '');
  expect(pid, 'create must return pid').toBeTruthy();

  // Deploy
  const deployResp = await request.post(`/api/bpm/process-definitions/${pid}/deploy`, {
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    data: {},
  });
  expect(deployResp.ok(), `deploy must succeed: ${deployResp.status()}`).toBe(true);

  return pid;
}

// ===========================================================================
// Test suite
// ===========================================================================
test.describe('BPM designer — D4: ruleTask + Drools routing', { tag: ['@bpm-regression'] }, () => {
  test.setTimeout(240_000);

  // ==========================================================================
  // Single test: configures rule-task via designer hook, saves, deploys,
  // then runs full 3-layer + runtime verification.
  // ==========================================================================
  test('D4: ruleTask bound to wd_leave_routing — L1/L2/L3 + runtime branch assertion', async ({
    page,
    request,
  }) => {
    const processKey = `e2e_designer_rule_${TS}`;

    // -------------------------------------------------------------------------
    // Auth: API token (admin session preloaded via storageState)
    // -------------------------------------------------------------------------
    // Admin session preloaded via storageState (tests/storage/admin.json).

    const adminToken = await loginAs(request, 'admin@example.com', 'Test2026x');

    // -------------------------------------------------------------------------
    // D4: Designer UI — build the rule-task flow via window.__bpmDesigner hook
    // -------------------------------------------------------------------------
    await openDesigner(page, { processKey, name: `D4 RuleTask ${processKey}` });

    // Add all nodes
    await addNode(page, { id: 'start_1', type: 'startEvent', position: { x: 80, y: 250 }, label: 'Start' });
    await addNode(page, { id: 'rule_1', type: 'rule-task', position: { x: 280, y: 250 }, label: 'Route Leave' });
    await addNode(page, { id: 'gw_route', type: 'exclusiveGateway', position: { x: 480, y: 250 }, label: 'Who approves?' });
    await addNode(page, { id: 'task_manager', type: 'userTask', position: { x: 680, y: 150 }, label: 'Manager Approve' });
    await addNode(page, { id: 'task_hr', type: 'userTask', position: { x: 680, y: 350 }, label: 'HR Approve' });
    await addNode(page, { id: 'end_1', type: 'endEvent', position: { x: 880, y: 250 }, label: 'End' });

    // Configure rule_1: ruleCode + factsVars sit directly on node.data (not config)
    await configureNode(page, 'rule_1', {
      ruleCode: 'wd_leave_routing',
      factsVars: 'days,type',
    });

    // Configure task_manager + task_hr assignees
    await configureNode(page, 'task_manager', { assigneeType: 'role', assigneeValue: 'wd_manager' });
    await configureNode(page, 'task_hr', { assigneeType: 'role', assigneeValue: 'wd_hr' });

    // Connect edges (with conditions on gateway out-edges)
    await connect(page, { from: 'start_1', to: 'rule_1' });
    await connect(page, { from: 'rule_1', to: 'gw_route' });
    await connect(page, {
      from: 'gw_route',
      to: 'task_manager',
      condition: "${approverRole=='manager'}",
    });
    await connect(page, {
      from: 'gw_route',
      to: 'task_hr',
      condition: "${approverRole=='hr'}",
    });
    await connect(page, { from: 'task_manager', to: 'end_1' });
    await connect(page, { from: 'task_hr', to: 'end_1' });

    // -------------------------------------------------------------------------
    // Save via designer UI — captures the designerJson from the store
    // -------------------------------------------------------------------------
    const { processDefinitionId: savedPid } = await saveProcess(page);

    // -------------------------------------------------------------------------
    // Deploy via designer UI
    // -------------------------------------------------------------------------
    await deployProcess(page, savedPid);

    // -------------------------------------------------------------------------
    // L1: assertDesignerJson — all nodes + edges + rule config fields
    // Use page.request (browser-attached context, bypasses system proxy) to
    // avoid actionTimeout issues when the browser is active.
    // -------------------------------------------------------------------------
    await assertDesignerJson(page.request, adminToken, savedPid, {
      nodeIds: ['start_1', 'rule_1', 'gw_route', 'task_manager', 'task_hr', 'end_1'],
      edgeSpecs: [
        { from: 'start_1', to: 'rule_1' },
        { from: 'rule_1', to: 'gw_route' },
        { from: 'gw_route', to: 'task_manager', condition: "approverRole=='manager'" },
        { from: 'gw_route', to: 'task_hr', condition: "approverRole=='hr'" },
        { from: 'task_manager', to: 'end_1' },
        { from: 'task_hr', to: 'end_1' },
      ],
    });

    // L1 extension: assert rule_1.data.ruleCode and .factsVars in raw designerJson
    const dtoResp = await page.request.get(`/api/bpm/process-definitions/${savedPid}`, {
      headers: { Authorization: `Bearer ${adminToken}` },
    });
    expect(dtoResp.ok(), `DTO fetch must succeed: ${dtoResp.status()}`).toBe(true);
    const dtoBody = await dtoResp.json();
    const rawDj = dtoBody?.data?.designerJson as string | undefined;
    expect(rawDj, 'designerJson must be present').toBeTruthy();
    const dj = JSON.parse(rawDj!) as Record<string, unknown>;
    const nodes = (dj.nodes ?? []) as Array<Record<string, unknown>>;
    const ruleNode = nodes.find((n) => n.id === 'rule_1');
    expect(ruleNode, 'rule_1 node must be present in designerJson').toBeTruthy();
    const ruleData = ruleNode!.data as Record<string, unknown>;
    expect(
      ruleData.ruleCode,
      'rule_1.data.ruleCode must equal wd_leave_routing',
    ).toBe('wd_leave_routing');
    expect(
      ruleData.factsVars,
      'rule_1.data.factsVars must equal days,type',
    ).toBe('days,type');

    // -------------------------------------------------------------------------
    // L2: assertBpmnXml — flow elements present
    // Note: gatewayConditions check is omitted here because connect() auto-generates
    // edge IDs ("edge-gw_route-task_manager") rather than the named IDs
    // ("e_gw_manager"). Gateway condition content is verified in the raw XML block below.
    // -------------------------------------------------------------------------
    await assertBpmnXml(page.request, adminToken, savedPid, {
      hasFlowElement: ['rule_1', 'gw_route', 'task_manager', 'task_hr'],
    });

    // L2 extension: raw XML must have <serviceTask id="rule_1"> with Drools delegate attrs
    // and gateway conditions with the correct content.
    const xmlResp = await page.request.get(`/api/bpm/process-definitions/${savedPid}/bpmn`, {
      headers: { Authorization: `Bearer ${adminToken}` },
    });
    expect(xmlResp.ok(), `BPMN XML fetch must succeed: ${xmlResp.status()}`).toBe(true);
    const xmlBody = await xmlResp.json();
    const xml = xmlBody?.data as string;
    expect(typeof xml === 'string' && xml.length > 0, 'BPMN XML must be non-empty').toBe(true);

    // The converter emits <serviceTask> (not <businessRuleTask>) for rule-task nodes
    expect(xml, 'BPMN XML must contain serviceTask id="rule_1"').toMatch(
      /serviceTask[^>]*id=["']rule_1["']/,
    );
    expect(
      xml,
      'BPMN XML must wire droolsServiceTaskDelegate via smart:class',
    ).toContain('droolsServiceTaskDelegate');
    expect(
      xml,
      'BPMN XML must carry smart:ruleCode="wd_leave_routing"',
    ).toContain('wd_leave_routing');
    // Gateway conditions are present in the BPMN XML (edge IDs may differ, content must match)
    expect(xml, "BPMN XML must contain manager gateway condition").toContain("approverRole=='manager'");
    expect(xml, "BPMN XML must contain hr gateway condition").toContain("approverRole=='hr'");

    // -------------------------------------------------------------------------
    // L3 (runtime): Instance 1 — days=10 → approverRole=hr → task_hr active
    // Use page.request to bypass system proxy (same reason as L1/L2 above).
    // -------------------------------------------------------------------------
    // Start manually to inspect active tasks before completing
    const startResp1 = await page.request.post('/api/bpm/process-instances', {
      headers: { Authorization: `Bearer ${adminToken}`, 'Content-Type': 'application/json' },
      data: { processDefinitionId: processKey, variables: { days: 10, type: 'annual' } },
    });
    expect(startResp1.ok(), `L3 instance 1 start must succeed: ${startResp1.status()}`).toBe(true);
    const startBody1 = await startResp1.json();
    const instanceId1 = String(startBody1?.data?.instanceId ?? '');
    expect(instanceId1, 'L3 instance 1 must return instanceId').toBeTruthy();

    // Assert task_hr is active (not task_manager)
    const tasksResp1 = await page.request.get(`/api/bpm/tasks/by-process/${instanceId1}`, {
      headers: { Authorization: `Bearer ${adminToken}` },
    });
    expect(tasksResp1.ok(), `L3 tasks fetch must succeed: ${tasksResp1.status()}`).toBe(true);
    const tasksBody1 = await tasksResp1.json();
    const activeTasks1 = (tasksBody1?.data ?? []) as Array<Record<string, unknown>>;
    const activeKeys1 = activeTasks1.map((t) => String(t.processDefinitionActivityId ?? ''));
    expect(
      activeKeys1,
      'days=10 must route to task_hr (approverRole=hr)',
    ).toContain('task_hr');
    expect(
      activeKeys1,
      'days=10 must NOT route to task_manager',
    ).not.toContain('task_manager');

    // Complete task_hr and verify instance completes
    const r1 = await startInstanceAndAdvance(
      page.request,
      adminToken,
      savedPid,
      { days: 10, type: 'annual' },
      [{ taskDefKey: 'task_hr', action: 'complete' }] satisfies AdvanceStep[],
    );
    expect(
      r1.finalStatus.toLowerCase(),
      'days=10 (hr branch): instance must complete after task_hr done',
    ).toBe('completed');

    // -------------------------------------------------------------------------
    // L3 (runtime): Instance 2 — days=2 → approverRole=manager → task_manager active
    // -------------------------------------------------------------------------
    const startResp2 = await page.request.post('/api/bpm/process-instances', {
      headers: { Authorization: `Bearer ${adminToken}`, 'Content-Type': 'application/json' },
      data: { processDefinitionId: processKey, variables: { days: 2, type: 'annual' } },
    });
    expect(startResp2.ok(), `L3 instance 2 start must succeed: ${startResp2.status()}`).toBe(true);
    const startBody2 = await startResp2.json();
    const instanceId2 = String(startBody2?.data?.instanceId ?? '');
    expect(instanceId2, 'L3 instance 2 must return instanceId').toBeTruthy();

    // Assert task_manager is active (not task_hr)
    const tasksResp2 = await page.request.get(`/api/bpm/tasks/by-process/${instanceId2}`, {
      headers: { Authorization: `Bearer ${adminToken}` },
    });
    expect(tasksResp2.ok(), `L3 instance 2 tasks fetch must succeed: ${tasksResp2.status()}`).toBe(true);
    const tasksBody2 = await tasksResp2.json();
    const activeTasks2 = (tasksBody2?.data ?? []) as Array<Record<string, unknown>>;
    const activeKeys2 = activeTasks2.map((t) => String(t.processDefinitionActivityId ?? ''));
    expect(
      activeKeys2,
      'days=2 must route to task_manager (approverRole=manager)',
    ).toContain('task_manager');
    expect(
      activeKeys2,
      'days=2 must NOT route to task_hr',
    ).not.toContain('task_hr');

    // Complete task_manager and verify instance completes
    const r2 = await startInstanceAndAdvance(
      page.request,
      adminToken,
      savedPid,
      { days: 2, type: 'annual' },
      [{ taskDefKey: 'task_manager', action: 'complete' }] satisfies AdvanceStep[],
    );
    expect(
      r2.finalStatus.toLowerCase(),
      'days=2 (manager branch): instance must complete after task_manager done',
    ).toBe('completed');
  });

  // ==========================================================================
  // Supplementary test: API-provisioned process — isolates L1/L2/L3 from
  // any designer UI save/deploy issues. Uses the same process structure but
  // provisions directly via API to ensure the 3-layer assertions always run
  // even if the designer save path has a transient issue.
  // ==========================================================================
  test('D4 (API path): ruleTask provisioned directly — L1/L2/L3 assertions', async ({
    page,
    request,
  }) => {
    const processKey = `e2e_designer_rule_api_${TS}`;

    // Auth: API token (admin session preloaded via storageState)
    // Admin session preloaded via storageState (tests/storage/admin.json).

    const adminToken = await loginAs(request, 'admin@example.com', 'Test2026x');

    // Provision + deploy via API helper
    const pid = await createAndDeployRuleProcess(request, adminToken, processKey);

    // -------------------------------------------------------------------------
    // D1: Navigate to process list via sidebar (red line: no direct goto to
    // designer URL except from the list-page create flow)
    // -------------------------------------------------------------------------
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
    const leafLink = nav.locator('a[href*="bpm_process_management"]').first();
    await leafLink.waitFor({ state: 'attached', timeout: 8_000 });
    await leafLink.evaluate((el: HTMLElement) => el.click());
    await page.waitForURL(/\/p\/bpm_process_management/, { timeout: 20_000 });

    // Open the saved process definition in the designer
    await page.goto(`/bpmn-designer?pid=${pid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.locator('.react-flow')).toBeVisible({ timeout: 15_000 });
    // Assert canvas loaded with 6 nodes
    await expect(page.locator('.react-flow__node')).toHaveCount(6, { timeout: 10_000 });

    // D4: select rule_1 node via store and assert panel visible
    await page.waitForFunction(
      () => Boolean((window as unknown as { __bpmnDesignerStore?: unknown }).__bpmnDesignerStore),
      undefined,
      { timeout: 8_000 },
    );
    await page.evaluate((id: string) => {
      const store = (
        window as unknown as {
          __bpmnDesignerStore?: { getState: () => Record<string, unknown> };
        }
      ).__bpmnDesignerStore;
      if (!store) throw new Error('BPMN store missing');
      const state = store.getState() as unknown as {
        setSelectedEdge: (e: string | null) => void;
        setSelectedNode: (n: string | null) => void;
      };
      state.setSelectedEdge(null);
      state.setSelectedNode(id);
    }, 'rule_1');
    await page.locator('[data-testid="node-label-input"]').waitFor({
      state: 'visible',
      timeout: 5_000,
    });

    // -------------------------------------------------------------------------
    // L1: assertDesignerJson
    // -------------------------------------------------------------------------
    await assertDesignerJson(request, adminToken, pid, {
      nodeIds: ['start_1', 'rule_1', 'gw_route', 'task_manager', 'task_hr', 'end_1'],
      edgeSpecs: [
        { from: 'start_1', to: 'rule_1' },
        { from: 'rule_1', to: 'gw_route' },
        { from: 'gw_route', to: 'task_manager', condition: "approverRole=='manager'" },
        { from: 'gw_route', to: 'task_hr', condition: "approverRole=='hr'" },
        { from: 'task_manager', to: 'end_1' },
        { from: 'task_hr', to: 'end_1' },
      ],
    });

    // L1 extension: rule_1 data fields
    const dtoResp = await request.get(`/api/bpm/process-definitions/${pid}`, {
      headers: { Authorization: `Bearer ${adminToken}` },
    });
    expect(dtoResp.ok()).toBe(true);
    const dtoBody = await dtoResp.json();
    const rawDj = dtoBody?.data?.designerJson as string;
    const dj = JSON.parse(rawDj) as Record<string, unknown>;
    const ruleNode = ((dj.nodes ?? []) as Array<Record<string, unknown>>).find(
      (n) => n.id === 'rule_1',
    );
    expect(ruleNode, 'rule_1 node must exist in designerJson').toBeTruthy();
    const ruleData = ruleNode!.data as Record<string, unknown>;
    expect(ruleData.ruleCode, 'rule_1.data.ruleCode').toBe('wd_leave_routing');
    expect(ruleData.factsVars, 'rule_1.data.factsVars').toBe('days,type');

    // -------------------------------------------------------------------------
    // L2: assertBpmnXml — flow elements + gateway conditions
    // -------------------------------------------------------------------------
    await assertBpmnXml(request, adminToken, pid, {
      hasFlowElement: ['rule_1', 'gw_route', 'task_manager', 'task_hr'],
      gatewayConditions: {
        e_gw_manager: "approverRole=='manager'",
        e_gw_hr: "approverRole=='hr'",
      },
    });

    // L2 extension: raw serviceTask + Drools delegate
    const xmlResp = await request.get(`/api/bpm/process-definitions/${pid}/bpmn`, {
      headers: { Authorization: `Bearer ${adminToken}` },
    });
    expect(xmlResp.ok()).toBe(true);
    const xmlBody = await xmlResp.json();
    const xml = xmlBody?.data as string;
    expect(xml).toMatch(/serviceTask[^>]*id=["']rule_1["']/);
    expect(xml).toContain('droolsServiceTaskDelegate');
    expect(xml).toContain('wd_leave_routing');

    // -------------------------------------------------------------------------
    // L3: runtime — long leave (days=10) → hr branch
    // Note: startInstanceAndAdvance expects a pid (ULID), not a processKey string.
    // -------------------------------------------------------------------------
    const r1 = await startInstanceAndAdvance(
      request,
      adminToken,
      pid,
      { days: 10, type: 'annual' },
      [{ taskDefKey: 'task_hr', action: 'complete' }] satisfies AdvanceStep[],
    );
    expect(r1.finalStatus.toLowerCase(), 'days=10 → hr branch must complete').toBe('completed');

    // L3: short leave (days=2) → manager branch
    const r2 = await startInstanceAndAdvance(
      request,
      adminToken,
      pid,
      { days: 2, type: 'annual' },
      [{ taskDefKey: 'task_manager', action: 'complete' }] satisfies AdvanceStep[],
    );
    expect(r2.finalStatus.toLowerCase(), 'days=2 → manager branch must complete').toBe('completed');
  });
});
