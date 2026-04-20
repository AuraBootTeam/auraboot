/**
 * BPM Designer — D5: callActivity + parent↔child variable mapping
 *
 * Flow:
 *   Child process: start_child → user_child (userTask) → end_child
 *   Parent process: start_1 → call_1 (callActivity → child) → end_1
 *
 * Variable mapping (via JsonToBpmnConverter.writeCallActivity):
 *   Input:  parent.applicantId  → child.childApplicantId
 *   Output: child.childResult   → parent.parentResult
 *
 * Config field names (verified from types/index.ts and CallActivityEditor.tsx):
 *   - calledProcessKey   (the child process key, maps to BPMN calledElement attribute)
 *   - inputMappings      (Record<string,string>: parentVar → childVar)
 *   - outputMappings     (Record<string,string>: childVar  → parentVar)
 *
 * Converter behavior (JsonToBpmnConverter.java ~line 976):
 *   inputMappings/outputMappings are serialized as JSON inside aura.callMappings
 *   smart:property (NOT as BPMN <in>/<out> elements — SmartEngine rejects those).
 *   L2 raw XML checks the smart:property payload contains the mapping keys.
 *
 * Three-layer verification:
 *   L1 — assertDesignerJson: parent nodes + edges; call_1.data.calledProcessKey
 *   L2 — assertBpmnXml: hasFlowElement call_1; raw XML calledElement + aura.callMappings
 *   L3 — runtime:
 *         - Start parent with { applicantId: 'user-abc' }
 *         - Assert child user task (user_child) is active with a DIFFERENT instanceId
 *           than the parent (proving the child process was spawned)
 *         - Complete child task with { childResult: 'approved' }
 *         - Assert parent reaches completed status
 *         (Variable propagation: no GET-variables endpoint exists in this version;
 *          lifecycle assertion + child-spawn evidence is the coverage limit — noted as concern)
 *
 * Provisioning strategy:
 *   - Child process: provisioned via API (avoids second designer cycle)
 *   - Parent process: built via designer UI hook + saved + deployed
 *
 * Red lines honoured:
 *   - page.goto only to /login
 *   - No waitForTimeout
 *   - No afterAll cleanup
 *   - Network timeouts ≤ 15 s; UI assertions ≤ 5 s
 *
 * CONCERN: No GET /api/bpm/process-instances/{id}/variables endpoint in this version.
 *   L3 variable-propagation (applicantId→childApplicantId and childResult→parentResult)
 *   cannot be asserted numerically. Coverage is limited to lifecycle + child-spawn evidence.
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
} from '../../helpers/bpm-assertions';
import { loginAs } from '../../helpers/wd-fixtures';

// ---------------------------------------------------------------------------
// Unique timestamp per run
// ---------------------------------------------------------------------------
const TS = Date.now();

// ---------------------------------------------------------------------------
// Helper: provision + deploy the child process via API
// ---------------------------------------------------------------------------

/**
 * Create a minimal child process: start_child → user_child → end_child
 * Provisioned via API to avoid a second designer UI open cycle.
 * Returns the child pid (numeric DB id as string).
 */
async function createAndDeployChildProcess(
  request: import('@playwright/test').APIRequestContext,
  token: string,
  childProcessKey: string,
): Promise<string> {
  const childProcessName = `D5 Child ${childProcessKey}`;

  const designerJson = JSON.stringify({
    nodes: [
      {
        id: 'start_child',
        type: 'startEvent',
        position: { x: 80, y: 200 },
        data: { type: 'startEvent', label: 'Start' },
      },
      {
        id: 'user_child',
        type: 'userTask',
        position: { x: 280, y: 200 },
        data: {
          type: 'userTask',
          label: 'Child Task',
          assigneeType: 'role',
          assigneeValue: 'wd_manager',
        },
      },
      {
        id: 'end_child',
        type: 'endEvent',
        position: { x: 480, y: 200 },
        data: { type: 'endEvent', label: 'End' },
      },
    ],
    edges: [
      {
        id: 'e_sc_uc',
        source: 'start_child',
        target: 'user_child',
        type: 'smoothstep',
        data: { label: '' },
      },
      {
        id: 'e_uc_ec',
        source: 'user_child',
        target: 'end_child',
        type: 'smoothstep',
        data: { label: '' },
      },
    ],
  });

  // Minimal BPMN XML for the child (converter would emit the same structure)
  const bpmnXml = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:smart="http://auraboot.com/schema/smart"
             targetNamespace="http://auraboot.com/bpm"
             id="def_${childProcessKey}">
  <process id="${childProcessKey}" name="${childProcessName}" isExecutable="true">
    <startEvent id="start_child" name="Start"/>
    <userTask id="user_child" name="Child Task"/>
    <endEvent id="end_child" name="End"/>
    <sequenceFlow id="e_sc_uc" sourceRef="start_child" targetRef="user_child"/>
    <sequenceFlow id="e_uc_ec" sourceRef="user_child" targetRef="end_child"/>
  </process>
</definitions>`;

  // Create draft
  const createResp = await request.post('/api/bpm/process-definitions', {
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    data: {
      processKey: childProcessKey,
      processName: childProcessName,
      description: 'D5 callActivity child E2E',
      category: 'e2e-test',
      bpmnContent: bpmnXml,
      designerJson,
    },
  });
  expect(
    createResp.ok(),
    `child process create must succeed: ${createResp.status()}`,
  ).toBe(true);

  const createBody = await createResp.json();
  const childPid = String(createBody?.data?.pid ?? createBody?.data?.id ?? '');
  expect(childPid, 'child process create must return pid').toBeTruthy();

  // Deploy child
  const deployResp = await request.post(`/api/bpm/process-definitions/${childPid}/deploy`, {
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    data: {},
  });
  expect(
    deployResp.ok(),
    `child process deploy must succeed: ${deployResp.status()}`,
  ).toBe(true);

  return childPid;
}

// ===========================================================================
// Test suite
// ===========================================================================
test.describe('BPM designer — D5: callActivity + parent-child variable mapping', { tag: ['@bpm-regression'] }, () => {
  test.setTimeout(240_000);

  test('D5: callActivity with input/output variable mapping — L1/L2/L3', async ({
    page,
    request,
  }) => {
    const childProcessKey = `e2e_designer_ca_child_${TS}`;
    const parentProcessKey = `e2e_designer_ca_parent_${TS}`;

    // -----------------------------------------------------------------------
    // Auth: API token (admin session preloaded via storageState)
    // -----------------------------------------------------------------------
    // Admin session preloaded via storageState (tests/storage/admin.json).

    const adminToken = await loginAs(request, 'admin@example.com', 'Test2026x');

    // -----------------------------------------------------------------------
    // Step 1: Provision and deploy child process via API
    // -----------------------------------------------------------------------
    await createAndDeployChildProcess(request, adminToken, childProcessKey);

    // -----------------------------------------------------------------------
    // Step 2: Build parent process in designer UI
    // -----------------------------------------------------------------------
    await openDesigner(page, {
      processKey: parentProcessKey,
      name: `D5 Parent ${parentProcessKey}`,
    });

    // Add nodes
    await addNode(page, {
      id: 'start_1',
      type: 'startEvent',
      position: { x: 80, y: 200 },
      label: 'Start',
    });
    await addNode(page, {
      id: 'call_1',
      type: 'callActivity',
      position: { x: 280, y: 200 },
      label: 'Call Child',
    });
    await addNode(page, {
      id: 'end_1',
      type: 'endEvent',
      position: { x: 480, y: 200 },
      label: 'End',
    });

    // Configure call_1: calledProcessKey + input/output mappings.
    // MUST wrap in { config: { ... } } because:
    //   - configureNode(id, patch) → store.setNodeData(id, patch) → updateNode(id, patch)
    //   - updateNode merges patch directly into node.data (useBPMNStore.ts line 276)
    //   - JsonToBpmnConverter.writeCallActivity reads from data.path("config") (line 297)
    //   So fields must live at node.data.config, not node.data.
    // Field names verified from:
    //   web-admin/app/plugins/core-designer/components/bpmn-designer/types/index.ts:133
    //   CallActivityEditor.tsx lines 125,145,187,195
    //   JsonToBpmnConverter.java lines 936,976-977
    await configureNode(page, 'call_1', {
      config: {
        calledProcessKey: childProcessKey,
        calledProcessVersion: 'latest',
        inputMappings: { applicantId: 'childApplicantId' },
        outputMappings: { childResult: 'parentResult' },
      },
    });

    // Connect edges
    await connect(page, { from: 'start_1', to: 'call_1' });
    await connect(page, { from: 'call_1', to: 'end_1' });

    // -----------------------------------------------------------------------
    // Save parent process via designer UI
    // -----------------------------------------------------------------------
    const { processDefinitionId: parentPid } = await saveProcess(page);

    // -----------------------------------------------------------------------
    // Deploy parent process via designer UI
    // -----------------------------------------------------------------------
    await deployProcess(page, parentPid);

    // -----------------------------------------------------------------------
    // L1: assertDesignerJson — nodes, edges, call_1.data.calledProcessKey
    // -----------------------------------------------------------------------
    await assertDesignerJson(request, adminToken, parentPid, {
      nodeIds: ['start_1', 'call_1', 'end_1'],
      edgeSpecs: [
        { from: 'start_1', to: 'call_1' },
        { from: 'call_1', to: 'end_1' },
      ],
    });

    // L1 extension: assert call_1.data.calledProcessKey in raw designerJson
    const dtoResp = await request.get(`/api/bpm/process-definitions/${parentPid}`, {
      headers: { Authorization: `Bearer ${adminToken}` },
    });
    expect(dtoResp.ok(), `DTO fetch must succeed: ${dtoResp.status()}`).toBe(true);
    const dtoBody = await dtoResp.json();
    const rawDj = dtoBody?.data?.designerJson as string | undefined;
    expect(rawDj, 'parent designerJson must be present').toBeTruthy();

    const dj = JSON.parse(rawDj!) as Record<string, unknown>;
    const nodes = (dj.nodes ?? []) as Array<Record<string, unknown>>;
    const callNode = nodes.find((n) => n.id === 'call_1');
    expect(callNode, 'call_1 node must be present in designerJson').toBeTruthy();

    // configureNode calls store.setNodeData → updateNode which merges patch directly
    // into node.data (useBPMNStore.ts line 276: node.data = { ...node.data, ...data })
    // The patch is { config: { calledProcessKey, inputMappings, outputMappings } }
    // so these fields live at node.data.config (not node.data directly).
    // JsonToBpmnConverter reads from data.path("config") (line 297).
    const callData = callNode!.data as Record<string, unknown>;
    const callConfig = callData.config as Record<string, unknown>;
    expect(callConfig, 'call_1.data.config must be present').toBeTruthy();
    expect(
      callConfig.calledProcessKey,
      'call_1.data.config.calledProcessKey must equal childProcessKey',
    ).toBe(childProcessKey);

    expect(
      callConfig.inputMappings,
      'call_1.data.config.inputMappings must contain applicantId→childApplicantId',
    ).toMatchObject({ applicantId: 'childApplicantId' });
    expect(
      callConfig.outputMappings,
      'call_1.data.config.outputMappings must contain childResult→parentResult',
    ).toMatchObject({ childResult: 'parentResult' });

    // -----------------------------------------------------------------------
    // L2: assertBpmnXml — flow elements + calledElement attribute
    // -----------------------------------------------------------------------
    await assertBpmnXml(request, adminToken, parentPid, {
      hasFlowElement: ['call_1'],
    });

    // L2 extension: raw BPMN XML must carry calledElement + aura.callMappings payload
    const xmlResp = await request.get(`/api/bpm/process-definitions/${parentPid}/bpmn`, {
      headers: { Authorization: `Bearer ${adminToken}` },
    });
    expect(xmlResp.ok(), `BPMN XML fetch must succeed: ${xmlResp.status()}`).toBe(true);
    const xmlBody = await xmlResp.json();
    const xml = xmlBody?.data as string;
    expect(typeof xml === 'string' && xml.length > 0, 'BPMN XML must be non-empty').toBe(true);

    // callActivity element must be present with correct id
    expect(xml, 'BPMN XML must contain callActivity id="call_1"').toMatch(
      /callActivity[^>]*id=["']call_1["']/,
    );

    // calledElement must reference the child process key
    // JsonToBpmnConverter writes: writer.writeAttribute("calledElement", calledProcessKey)
    expect(xml, 'callActivity must carry calledElement pointing to child key').toContain(
      childProcessKey,
    );

    // aura.callMappings extension property must be emitted
    // (contains the serialized inputs/outputs JSON)
    expect(xml, 'BPMN XML must carry aura.callMappings smart property').toContain(
      'aura.callMappings',
    );
    // The callMappings JSON payload contains the mapping variable names
    expect(xml, 'callMappings must contain applicantId (input mapping source)').toContain(
      'applicantId',
    );
    expect(xml, 'callMappings must contain childApplicantId (input mapping target)').toContain(
      'childApplicantId',
    );

    // -----------------------------------------------------------------------
    // L3 (runtime): Start parent → child spawns → complete child → parent done
    // -----------------------------------------------------------------------

    // Start parent instance
    const startResp = await request.post('/api/bpm/process-instances', {
      headers: { Authorization: `Bearer ${adminToken}`, 'Content-Type': 'application/json' },
      data: {
        processDefinitionId: parentProcessKey,
        variables: { applicantId: 'user-abc' },
      },
    });
    expect(startResp.ok(), `parent instance start must succeed: ${startResp.status()}`).toBe(true);
    const startBody = await startResp.json();
    const parentInstanceId = String(startBody?.data?.instanceId ?? '');
    expect(parentInstanceId, 'parent instance must return instanceId').toBeTruthy();

    // NOTE: /api/bpm/tasks/by-process/{id} only returns tasks for that specific
    // process instance, NOT for spawned child instances. For callActivity, we
    // must query /todo (returns the authenticated user's pending tasks as a flat
    // list) and filter by the child process definition key.
    //
    // /todo response: { data: TaskInstance[] } (flat list, not paginated records).
    // TaskInstance fields: instanceId, processDefinitionIdAndVersion, processDefinitionActivityId,
    //                       processInstanceId, status.
    const todoResp = await request.get(
      `/api/bpm/tasks/todo?pageNum=1&pageSize=50`,
      { headers: { Authorization: `Bearer ${adminToken}` } },
    );
    expect(todoResp.ok(), `todo tasks fetch must succeed: ${todoResp.status()}`).toBe(true);
    const todoBody = await todoResp.json();
    // /todo returns a flat list: data is TaskInstance[]
    const allTasks = (Array.isArray(todoBody?.data) ? todoBody.data : todoBody?.data?.records ?? []) as Array<Record<string, unknown>>;

    // Assert the child's user_child task is present — filter by child process definition key
    // (processDefinitionIdAndVersion = "{childProcessKey}:{version}") and task activity key.
    const childTask = allTasks.find(
      (t) =>
        String(t.processDefinitionActivityId ?? '') === 'user_child' &&
        String(t.processDefinitionIdAndVersion ?? '').startsWith(childProcessKey),
    );
    expect(childTask, 'user_child task must be active after callActivity starts').toBeTruthy();

    // Assert child task belongs to a DIFFERENT process instance (child was spawned)
    const childInstanceId = String(childTask!.processInstanceId ?? '');
    expect(
      childInstanceId,
      'child task processInstanceId must be different from parent (child was spawned)',
    ).not.toBe(parentInstanceId);
    expect(childInstanceId, 'child instanceId must be non-empty').toBeTruthy();

    // Complete the child task with an output variable.
    // /todo returns TaskInstance where instanceId is the task ID used by /complete endpoint.
    const childTaskId = String(childTask!.instanceId ?? childTask!.taskId ?? '');
    expect(childTaskId, 'child task must have an instanceId/taskId').toBeTruthy();

    const completeResp = await request.post(
      `/api/bpm/tasks/${childTaskId}/complete`,
      {
        headers: { Authorization: `Bearer ${adminToken}`, 'Content-Type': 'application/json' },
        data: { variables: { childResult: 'approved' } },
      },
    );
    expect(
      completeResp.ok(),
      `child task complete must succeed: ${completeResp.status()}`,
    ).toBe(true);

    // After child completes, parent callActivity should finish and parent should reach end.
    // Poll the already-started parent instance for final status.
    // Note: for callActivity flows, startInstanceAndAdvance cannot be used here because
    // it uses by-process/{instanceId} which only returns tasks for the parent instance,
    // not for spawned child instances. The child task was already completed above.
    const finalResp = await request.get(
      `/api/bpm/process-instances/${parentInstanceId}`,
      { headers: { Authorization: `Bearer ${adminToken}` } },
    );
    expect(finalResp.ok(), `final status fetch must succeed: ${finalResp.status()}`).toBe(true);
    const finalBody = await finalResp.json();
    const finalStatus = String(finalBody?.data?.status ?? '').toLowerCase();
    expect(
      finalStatus,
      'parent instance must reach completed after child task is done',
    ).toBe('completed');

    // CONCERN: No GET /api/bpm/process-instances/{id}/variables endpoint available.
    // Variable propagation (applicantId→childApplicantId, childResult→parentResult)
    // cannot be numerically asserted. Coverage limited to:
    //   1. Child process spawned (childInstanceId !== parentInstanceId) ✓
    //   2. child task user_child active ✓
    //   3. Parent reaches completed after child done ✓
  });
});
