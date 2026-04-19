/**
 * D6 — SLA panel: configure SLA for a userTask (single-level + multi-level escalation)
 *
 * Investigation findings:
 *   SLA in this system is a SEPARATE resource (NOT inlined into userTask node data).
 *   - SLA configs live in ab_sla_config (entity: SlaConfigEntity), managed via
 *     POST/GET /api/bpm/sla-configs.
 *   - A SLA config targets a BPMN node via `targetType` (NODE|TASK|PROCESS) +
 *     `targetKey` (the node id, e.g. "task_approve").
 *   - The designer has NO "SLA panel" in the node config — SLA is configured
 *     administratively through the sla-configs API and points back to nodes.
 *   - SlaRecordService.createRecord() is defined but has NO callers at task activation
 *     time — the scheduler (SlaSchedulerService) scans existing records but no code
 *     creates records when a task starts. Runtime SLA activation is NOT wired.
 *
 * Coverage design:
 *   - Build a userTask process in the designer UI (L1 + L2 = designer layer).
 *   - Create a SLA config targeting task_approve via POST /api/bpm/sla-configs (L1 ext).
 *   - Assert config is retrievable by target via GET /api/bpm/sla-configs/by-target.
 *   - Create a second SLA config with warningRules (multi-level escalation) and assert
 *     the warningRules are stored correctly.
 *   - Start an instance and call GET /api/bpm/monitor/instances/{id}/sla — expect
 *     empty (no scheduler activation). Annotated with test.fixme explanation.
 *
 * Three-layer assertions:
 *   L1 — designerJson: nodes + edges present; task_approve node exists with correct type.
 *   L2 — BPMN XML: task_approve flow element present.
 *   L3a — SLA config CRUD: single-level config + multi-level (warningRules) config.
 *   L3b — runtime SLA: start instance, GET /api/bpm/monitor/instances/{id}/sla →
 *          fixme because runtime activation not wired.
 *
 * Endpoints (verified by controller grep):
 *   POST   /api/bpm/sla-configs               — SlaConfigController.create()
 *   GET    /api/bpm/sla-configs/by-target      — SlaConfigController.findByTarget(?targetType=&targetKey=)
 *   GET    /api/bpm/monitor/instances/{id}/sla — BpmMonitorController.getSlaRecords()
 *
 * Red lines honoured:
 *   - page.goto only to /login
 *   - No waitForTimeout
 *   - No afterAll
 *   - Network timeouts ≤ 15 s; UI assertions ≤ 5 s
 *
 * CONCERN: SlaRecordService.createRecord() has no callers at task activation time
 *   (grep confirmed). L3b runtime SLA record verification is gated behind test.fixme.
 *   Resolution: wire SlaRecordService.createRecord() into TaskService.activateTask()
 *   using SlaConfigService.findByTarget("NODE", taskNodeId) to automatically create
 *   records when a task is activated.
 */

import { test, expect } from '@playwright/test';
import {
  openDesigner,
  addNode,
  connect,
  saveProcess,
  deployProcess,
} from '../../helpers/designer-dsl';
import {
  assertDesignerJson,
  assertBpmnXml,
  startInstanceAndAdvance,
} from '../../helpers/bpm-assertions';
import { loginAs } from '../../helpers/wd-fixtures';

// ---------------------------------------------------------------------------
// Unique timestamp per run
// ---------------------------------------------------------------------------
const TS = Date.now();
const BACKEND = 'http://localhost:6443';

// ---------------------------------------------------------------------------
// Test suite
// ---------------------------------------------------------------------------

test.describe('D6 — SLA panel: userTask SLA config (single-level + multi-level escalation)', { tag: ['@bpm-regression'] }, () => {
  test.setTimeout(240_000);

  test('D6: configure SLA targeting a userTask — L1/L2/L3 config + runtime lifecycle', async ({
    page,
    request,
  }) => {
    const processKey = `e2e_designer_sla_${TS}`;

    // -------------------------------------------------------------------------
    // Auth: API token (admin session preloaded via storageState)
    // -------------------------------------------------------------------------
    // Admin session preloaded via storageState (tests/storage/admin.json).

    const adminToken = await loginAs(request, 'admin@example.com', 'Test2026x');

    // -------------------------------------------------------------------------
    // Build the process: start → task_approve (userTask) → end
    // -------------------------------------------------------------------------
    await openDesigner(page, { processKey, name: `D6 SLA ${processKey}` });

    await addNode(page, {
      id: 'start_1',
      type: 'startEvent',
      position: { x: 80, y: 200 },
      label: 'Start',
    });
    await addNode(page, {
      id: 'task_approve',
      type: 'userTask',
      position: { x: 280, y: 200 },
      label: 'Approve',
    });
    await addNode(page, {
      id: 'end_1',
      type: 'endEvent',
      position: { x: 480, y: 200 },
      label: 'End',
    });

    await connect(page, { from: 'start_1', to: 'task_approve' });
    await connect(page, { from: 'task_approve', to: 'end_1' });

    // -------------------------------------------------------------------------
    // Save and deploy
    // -------------------------------------------------------------------------
    const { processDefinitionId: pdId } = await saveProcess(page);
    await deployProcess(page, pdId);

    // =========================================================================
    // L1 — Designer JSON: nodes + edges present; task_approve is a userTask node
    // =========================================================================
    await assertDesignerJson(request, adminToken, pdId, {
      nodeIds: ['start_1', 'task_approve', 'end_1'],
      edgeSpecs: [
        { from: 'start_1', to: 'task_approve' },
        { from: 'task_approve', to: 'end_1' },
      ],
    });

    // L1 extended: task_approve node type is stored correctly in designerJson
    const pdResp = await request.get(`${BACKEND}/api/bpm/process-definitions/${pdId}`, {
      headers: { Authorization: `Bearer ${adminToken}` },
    });
    expect(pdResp.ok(), `GET /api/bpm/process-definitions/${pdId} failed: ${pdResp.status()}`).toBe(true);

    const pdBody = (await pdResp.json()) as Record<string, unknown>;
    const pdData = pdBody.data as Record<string, unknown>;
    const rawDj = pdData.designerJson as string;
    expect(typeof rawDj, 'designerJson must be a string').toBe('string');

    const dj = JSON.parse(rawDj) as Record<string, unknown>;
    const nodes = (dj.nodes ?? []) as Array<Record<string, unknown>>;
    const approveNode = nodes.find((n) => n.id === 'task_approve');
    expect(approveNode, 'task_approve must be present in designerJson').toBeDefined();
    // node.type is the React Flow node type — for userTask nodes it is 'userTask'
    expect(approveNode!.type, 'task_approve must be a userTask node').toBe('userTask');

    // =========================================================================
    // L2 — BPMN XML: task_approve flow element present
    // =========================================================================
    await assertBpmnXml(request, adminToken, pdId, {
      hasFlowElement: ['task_approve'],
    });

    // L2 extended: raw XML must contain a userTask element with id="task_approve"
    const xmlResp = await request.get(`${BACKEND}/api/bpm/process-definitions/${pdId}/bpmn`, {
      headers: { Authorization: `Bearer ${adminToken}` },
    });
    expect(xmlResp.ok(), `BPMN XML fetch must succeed: ${xmlResp.status()}`).toBe(true);
    const xmlBody = (await xmlResp.json()) as Record<string, unknown>;
    const xml = xmlBody.data as string;
    expect(typeof xml === 'string' && xml.length > 0, 'BPMN XML must be non-empty').toBe(true);

    // userTask element with id="task_approve" must be present in BPMN XML
    expect(xml, 'BPMN XML must contain userTask id="task_approve"').toMatch(
      /userTask[^>]*id=["']task_approve["']/,
    );

    // =========================================================================
    // L3a — SLA config CRUD: single-level SLA targeting task_approve
    //
    // Architecture note: SLA is a SEPARATE resource, NOT inlined into node data.
    // SlaConfigEntity links to a BPMN node via:
    //   targetType = "node"   (NODE-level SLA)
    //   targetKey  = "task_approve"  (must match the BPMN node id)
    // deadlineMode = "FIXED", deadlineValue = "PT8H" (ISO-8601 duration: 8 hours)
    // =========================================================================
    const slaName1 = `e2e_sla_single_${TS}`;
    const createSla1Resp = await request.post(`${BACKEND}/api/bpm/sla-configs`, {
      headers: { Authorization: `Bearer ${adminToken}`, 'Content-Type': 'application/json' },
      data: {
        name: slaName1,
        targetType: 'node',
        targetKey: 'task_approve',
        deadlineMode: 'FIXED',
        deadlineValue: 'PT8H',
        enabled: true,
        warningRules: [],
      },
    });
    expect(
      createSla1Resp.ok(),
      `POST /api/bpm/sla-configs (single-level) must succeed: ${createSla1Resp.status()}`,
    ).toBe(true);

    const sla1Body = (await createSla1Resp.json()) as Record<string, unknown>;
    const sla1Data = sla1Body.data as Record<string, unknown>;
    expect(sla1Data, 'SLA config create must return data').toBeDefined();

    const sla1Pid = sla1Data.pid as string;
    expect(sla1Pid, 'SLA config must have a pid').toBeTruthy();

    // Verify the SLA config can be retrieved by target
    const byTargetResp = await request.get(
      `${BACKEND}/api/bpm/sla-configs/by-target?targetType=node&targetKey=task_approve`,
      { headers: { Authorization: `Bearer ${adminToken}` } },
    );
    expect(
      byTargetResp.ok(),
      `GET /api/bpm/sla-configs/by-target must succeed: ${byTargetResp.status()}`,
    ).toBe(true);

    const byTargetBody = (await byTargetResp.json()) as Record<string, unknown>;
    const byTargetList = byTargetBody.data as Array<Record<string, unknown>>;
    expect(Array.isArray(byTargetList), 'by-target response must return an array').toBe(true);

    const foundConfig = byTargetList.find((c) => (c.pid as string) === sla1Pid);
    expect(foundConfig, 'created SLA config must be retrievable by target').toBeDefined();
    expect(foundConfig!.name, 'SLA config name must match').toBe(slaName1);
    expect(foundConfig!.targetType, 'SLA config targetType must be "node"').toBe('node');
    expect(foundConfig!.targetKey, 'SLA config targetKey must be "task_approve"').toBe('task_approve');
    expect(foundConfig!.deadlineMode, 'SLA config deadlineMode must be FIXED').toBe('FIXED');
    expect(foundConfig!.deadlineValue, 'SLA config deadlineValue must be PT8H').toBe('PT8H');

    // =========================================================================
    // L3a — SLA config CRUD: multi-level escalation (warningRules)
    //
    // warningRules is a JSONB array of escalation levels.
    // Each rule has: level (int), threshold (float 0.0-1.0 = % of deadline elapsed),
    // notifyRoles, notifyUsers.
    // Example: warn at 50% (level 1) and escalate at 80% (level 2).
    // =========================================================================
    const slaName2 = `e2e_sla_multilevel_${TS}`;
    const warningRules = [
      {
        level: 1,
        threshold: 0.5,
        action: 'warning',
        notifyRoles: ['wd_manager'],
        notifyUsers: [],
      },
      {
        level: 2,
        threshold: 0.8,
        action: 'escalation',
        notifyRoles: ['wd_hr'],
        notifyUsers: [],
      },
    ];

    const createSla2Resp = await request.post(`${BACKEND}/api/bpm/sla-configs`, {
      headers: { Authorization: `Bearer ${adminToken}`, 'Content-Type': 'application/json' },
      data: {
        name: slaName2,
        targetType: 'node',
        targetKey: 'task_approve',
        deadlineMode: 'FIXED',
        deadlineValue: 'PT4H',
        enabled: true,
        warningRules,
      },
    });
    expect(
      createSla2Resp.ok(),
      `POST /api/bpm/sla-configs (multi-level) must succeed: ${createSla2Resp.status()}`,
    ).toBe(true);

    const sla2Body = (await createSla2Resp.json()) as Record<string, unknown>;
    const sla2Data = sla2Body.data as Record<string, unknown>;
    const sla2Pid = sla2Data.pid as string;
    expect(sla2Pid, 'multi-level SLA config must have a pid').toBeTruthy();

    // Retrieve the multi-level config by pid and verify warningRules are persisted
    const sla2Resp = await request.get(`${BACKEND}/api/bpm/sla-configs/${sla2Pid}`, {
      headers: { Authorization: `Bearer ${adminToken}` },
    });
    expect(sla2Resp.ok(), `GET /api/bpm/sla-configs/${sla2Pid} must succeed: ${sla2Resp.status()}`).toBe(true);

    const sla2GetBody = (await sla2Resp.json()) as Record<string, unknown>;
    const sla2GetData = sla2GetBody.data as Record<string, unknown>;
    expect(sla2GetData, 'GET by pid must return data').toBeDefined();
    expect(sla2GetData.name, 'multi-level SLA name must match').toBe(slaName2);
    expect(sla2GetData.deadlineValue, 'multi-level deadlineValue must be PT4H').toBe('PT4H');

    const storedRules = sla2GetData.warningRules as Array<Record<string, unknown>>;
    expect(Array.isArray(storedRules), 'warningRules must be stored as an array').toBe(true);
    expect(storedRules.length, 'must have 2 warning rules (1 per escalation level)').toBe(2);

    // Level 1 — warning at 50%
    const rule1 = storedRules.find((r) => Number(r.level) === 1);
    expect(rule1, 'escalation level 1 must be present in warningRules').toBeDefined();
    expect(Number(rule1!.threshold), 'level 1 threshold must be 0.5').toBe(0.5);
    expect(rule1!.action, 'level 1 action must be "warning"').toBe('warning');

    // Level 2 — escalation at 80%
    const rule2 = storedRules.find((r) => Number(r.level) === 2);
    expect(rule2, 'escalation level 2 must be present in warningRules').toBeDefined();
    expect(Number(rule2!.threshold), 'level 2 threshold must be 0.8').toBe(0.8);
    expect(rule2!.action, 'level 2 action must be "escalation"').toBe('escalation');

    // Verify level 2 targets wd_hr role (simulating escalation to a higher role)
    const rule2Roles = rule2!.notifyRoles as string[];
    expect(Array.isArray(rule2Roles), 'level 2 notifyRoles must be an array').toBe(true);
    expect(rule2Roles, 'level 2 must notify wd_hr (escalation target)').toContain('wd_hr');

    // =========================================================================
    // L3b — Runtime: start process instance, assert task is active
    // =========================================================================
    const { instanceId, finalStatus } = await startInstanceAndAdvance(
      request,
      adminToken,
      pdId,
      {},
      [{ taskDefKey: 'task_approve', action: 'complete', vars: { taskResult: 'approved' } }],
    );
    expect(
      finalStatus,
      'process must reach completed status after task_approve is completed',
    ).toBe('completed');
    expect(instanceId, 'instance must have been started').toBeTruthy();

    // =========================================================================
    // L3b-fixme — SLA records NOT auto-created at task activation
    //
    // CONCERN: SlaRecordService.createRecord() has no callers at task activation
    //   time. The scheduler (SlaSchedulerService) scans existing records but nothing
    //   creates them when a task starts. Confirmed by grep: no call sites for
    //   slaRecordService.createRecord() in TaskService, BpmNodeHookService, or
    //   ProcessOrchestrationService.
    //
    //   Expected fix: wire SlaRecordService.createRecord() into task activation
    //   using SlaConfigService.findByTarget("node", taskNodeId).
    //
    //   When the scheduler is wired, this fixme block should be replaced with:
    //     const slaResp = await request.get(
    //       `${BACKEND}/api/bpm/monitor/instances/${instanceId}/sla`,
    //       { headers: { Authorization: `Bearer ${adminToken}` } }
    //     );
    //     expect(slaResp.ok()).toBe(true);
    //     const slaList = (await slaResp.json()).data as Array<Record<string, unknown>>;
    //     expect(slaList.length, 'must have 2 active SLA records (one per config)').toBeGreaterThanOrEqual(1);
    //     const activeRecord = slaList.find(r => r.nodeId === 'task_approve');
    //     expect(activeRecord, 'SLA record for task_approve must exist').toBeDefined();
    //     expect(activeRecord!.status, 'SLA record must be running').toBe('running');
    // =========================================================================
    test.fixme(
      true,
      'SLA runtime activation not wired: SlaRecordService.createRecord() has no ' +
      'callers in TaskService/BpmNodeHookService. GET /api/bpm/monitor/instances/{id}/sla ' +
      'returns empty even after task activation. Fix: wire createRecord() into task ' +
      'activation path via SlaConfigService.findByTarget("node", activityId).',
    );
  });
});
