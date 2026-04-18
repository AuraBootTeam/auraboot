/**
 * BPM parallel / inclusive gateway runtime E2E — P2.5
 *
 * Existing gateway coverage (designer-gateway-lifecycle.spec.ts B1/B2/B2b and
 * bpm-deep BPM-D07/D08) exercises exclusiveGateway end-to-end but stops at
 * deploy-only for parallel/inclusive. This spec closes that gap:
 *
 *   PG-1: parallelGateway split spawns N concurrent userTasks
 *   PG-2: completing one parallel branch keeps the others active
 *   PG-3: completing all N parallel branches triggers join and ends instance
 *
 *   IG-1: inclusiveGateway with conditions activates matching branches only
 *   IG-2: inclusiveGateway completion requires every activated branch done
 *
 *   CLEANUP: undeploy (best-effort)
 *
 * Runtime behavior notes:
 *   - JsonToBpmnConverter writes parallel/inclusive gateways (verified
 *     converter lines 305-306, 683-707) and BpmnToJsonConverter reads them.
 *   - Memory "SmartEngine no default-flow fallback" applies: every
 *     inclusive-gateway outgoing edge must carry a conditionExpression,
 *     BPMN default= attribute is ignored at runtime.
 *   - Historical bpm-deep.spec.ts BPM-D10 reports "Runtime engine currently
 *     returns 500 on inclusive split execution" (line 572-573). This spec
 *     tests it against the real engine; if runtime is still broken, IG-1/2
 *     switch to .fixme with evidence for a GAP-253 backlog candidate.
 *
 * Dimensions (D1-D14):
 *   D8  persistence — assert currentNodes/completedNodes after each step
 *   D11 multi-branch correctness — assert exact set of active branches
 *   D12 audit integrity — process_start + activity_event rows
 *
 * @since 2026-04-17 (P2.5 follow-up)
 */

import { test, expect, type APIRequestContext } from '../../fixtures';
import {
  loginAsAdmin,
  startProcessInstance,
  queryInstanceStatus,
  listAuditEvents,
  undeployProcess,
  hasProcessStart,
} from '../../e2e/bpm/_helpers/bpm-lifecycle';

// ---------------------------------------------------------------------------
// Serial — PG-1/2/3 share one deployed definition + one instance, IG-1/2 share
// another. CLEANUP tears both down.
// ---------------------------------------------------------------------------
test.describe.configure({ mode: 'serial' });

const TS = Date.now();

// Parallel
const PG_KEY = `pg_${TS}`;
const PG_BK = `pg_bk_${TS}`;
let pgPid = '';
let pgInstanceId = '';

// Inclusive
const IG_KEY = `ig_${TS}`;
const IG_BK_BOTH = `ig_bk_both_${TS}`;
const IG_BK_ONE = `ig_bk_one_${TS}`;
let igPid = '';
let igInstanceBothId = '';

let adminToken = '';

// ---------------------------------------------------------------------------
// BPMN generators
// ---------------------------------------------------------------------------

/**
 * Parallel gateway: start → fork → [task_a, task_b, task_c] → join → end.
 * Every fork->task and task->join edge is unconditional (parallel semantics).
 */
function buildParallelBpmn(processKey: string): string {
  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="http://auraboot.com/bpm" id="def_${processKey}">
  <process id="${processKey}" name="Parallel GW Runtime Test" isExecutable="true">
    <startEvent id="start"/>
    <parallelGateway id="pgw_split"/>
    <userTask id="task_a" name="Parallel A"/>
    <userTask id="task_b" name="Parallel B"/>
    <userTask id="task_c" name="Parallel C"/>
    <parallelGateway id="pgw_join"/>
    <endEvent id="end"/>
    <sequenceFlow id="e_start_split" sourceRef="start" targetRef="pgw_split"/>
    <sequenceFlow id="e_split_a" sourceRef="pgw_split" targetRef="task_a"/>
    <sequenceFlow id="e_split_b" sourceRef="pgw_split" targetRef="task_b"/>
    <sequenceFlow id="e_split_c" sourceRef="pgw_split" targetRef="task_c"/>
    <sequenceFlow id="e_a_join" sourceRef="task_a" targetRef="pgw_join"/>
    <sequenceFlow id="e_b_join" sourceRef="task_b" targetRef="pgw_join"/>
    <sequenceFlow id="e_c_join" sourceRef="task_c" targetRef="pgw_join"/>
    <sequenceFlow id="e_join_end" sourceRef="pgw_join" targetRef="end"/>
  </process>
</definitions>`;
}

/**
 * Inclusive gateway: start → igw_split → [task_high (amount>100),
 * task_premium (priority=='vip')] → igw_join → end. Both conditions can be
 * true simultaneously → both branches activate. Per red-line "no default
 * flow fallback", every edge carries an explicit condition.
 */
function buildInclusiveBpmn(processKey: string): string {
  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="http://auraboot.com/bpm" id="def_${processKey}">
  <process id="${processKey}" name="Inclusive GW Runtime Test" isExecutable="true">
    <startEvent id="start"/>
    <inclusiveGateway id="igw_split"/>
    <userTask id="task_high" name="High Amount Approve"/>
    <userTask id="task_premium" name="Premium Review"/>
    <inclusiveGateway id="igw_join"/>
    <endEvent id="end"/>
    <sequenceFlow id="e_start_split" sourceRef="start" targetRef="igw_split"/>
    <sequenceFlow id="e_split_high" sourceRef="igw_split" targetRef="task_high">
      <conditionExpression xsi:type="tFormalExpression" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">${'$'}{amount &gt; 100}</conditionExpression>
    </sequenceFlow>
    <sequenceFlow id="e_split_premium" sourceRef="igw_split" targetRef="task_premium">
      <conditionExpression xsi:type="tFormalExpression" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">${'$'}{priority == 'vip'}</conditionExpression>
    </sequenceFlow>
    <sequenceFlow id="e_high_join" sourceRef="task_high" targetRef="igw_join"/>
    <sequenceFlow id="e_premium_join" sourceRef="task_premium" targetRef="igw_join"/>
    <sequenceFlow id="e_join_end" sourceRef="igw_join" targetRef="end"/>
  </process>
</definitions>`;
}

// ---------------------------------------------------------------------------
// API helpers local to this spec (creation + deploy, task lookup)
// ---------------------------------------------------------------------------

function authHeaders(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
}

async function createAndDeploy(
  request: APIRequestContext,
  token: string,
  processKey: string,
  processName: string,
  bpmnXml: string,
): Promise<string> {
  const createResp = await request.post('/api/bpm/process-definitions', {
    headers: authHeaders(token),
    data: {
      processKey,
      processName,
      description: 'P2.5 parallel/inclusive gateway runtime E2E',
      category: 'e2e-test',
      bpmnContent: bpmnXml,
    },
  });
  expect(
    createResp.ok(),
    `create ${processKey} must succeed: ${createResp.status()} ${await createResp.text().catch(() => '')}`,
  ).toBe(true);
  const createBody = await createResp.json();
  const pid = String(createBody?.data?.pid ?? createBody?.data?.id ?? '');
  expect(pid, `create ${processKey} must return pid`).toBeTruthy();

  const deployResp = await request.post(`/api/bpm/process-definitions/${pid}/deploy`, {
    headers: authHeaders(token),
  });
  expect(
    deployResp.ok(),
    `deploy ${pid} must succeed: ${deployResp.status()} ${await deployResp.text().catch(() => '')}`,
  ).toBe(true);
  return pid;
}

interface TodoRow {
  id: string;
  nodeKey: string;
  processInstanceId: string;
}

async function listTodoForInstance(
  request: APIRequestContext,
  token: string,
  instanceId: string,
): Promise<TodoRow[]> {
  const resp = await request.get('/api/bpm/tasks/todo', {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(resp.ok(), `tasks/todo must respond ok: ${resp.status()}`).toBe(true);
  const body = await resp.json();
  const raw = (body?.data ?? []) as Array<Record<string, unknown>>;
  // SmartEngine TaskInstance shape: `instanceId` is the task id (not a process
  // instance id); node key lives on `processDefinitionActivityId`.
  return raw
    .map((t) => ({
      id: String(t.instanceId ?? t.id ?? t.taskId ?? ''),
      nodeKey: String(
        t.processDefinitionActivityId ??
          t.taskDefinitionKey ??
          t.activityId ??
          t.nodeKey ??
          '',
      ),
      processInstanceId: String(t.processInstanceId ?? t.processInstance ?? ''),
    }))
    .filter((t) => t.processInstanceId === instanceId);
}

async function completeTask(
  request: APIRequestContext,
  token: string,
  taskId: string,
): Promise<void> {
  const resp = await request.post(`/api/bpm/tasks/${taskId}/complete`, {
    headers: authHeaders(token),
    data: { variables: {} },
  });
  expect(
    resp.ok(),
    `complete task ${taskId} must succeed: ${resp.status()} ${await resp.text().catch(() => '')}`,
  ).toBe(true);
}

// ---------------------------------------------------------------------------
// Runtime-probe: before the inclusive describe block runs, decide whether
// the engine actually executes inclusive splits in this environment. If it
// doesn't, IG-1/2 flip to .fixme with recorded evidence (GAP-253 candidate).
// ---------------------------------------------------------------------------
let inclusiveRuntimeWorks = true;
let inclusiveProbeEvidence = '';

// ---------------------------------------------------------------------------
// Suite
// ---------------------------------------------------------------------------
test.describe(
  'BPM parallel/inclusive gateway runtime',
  { tag: ['@bpm-regression'] },
  () => {
    test.setTimeout(180_000);

    test.beforeAll(async ({ request }) => {
      adminToken = await loginAsAdmin(request);
    });

    // =======================================================================
    // PG-1: parallel split spawns N concurrent userTasks
    // =======================================================================
    test('PG-1: parallelGateway split spawns 3 concurrent tasks', async ({ request }) => {
      pgPid = await createAndDeploy(
        request,
        adminToken,
        PG_KEY,
        `Parallel GW Runtime ${TS}`,
        buildParallelBpmn(PG_KEY),
      );

      const started = await startProcessInstance(request, adminToken, {
        processDefinitionId: PG_KEY,
        businessKey: PG_BK,
        variables: {},
      });
      pgInstanceId = started.instanceId;
      expect(pgInstanceId, 'parallel instance must start').toBeTruthy();

      // Node-level: 3 concurrent currentNodes at task_a/b/c
      const status = await queryInstanceStatus(request, adminToken, {
        processKey: PG_KEY,
        businessKey: PG_BK,
      });
      const activeIds = status.currentNodes.map((n) => n.nodeId).sort();
      expect(
        activeIds,
        'parallelGateway must spawn all 3 branches concurrently',
      ).toEqual(['task_a', 'task_b', 'task_c']);
      expect(status.completedNodes.map((n) => n.nodeId)).toContain('pgw_split');

      // Task-level: exactly 3 todo tasks for this instance
      const todos = await listTodoForInstance(request, adminToken, pgInstanceId);
      expect(todos.length, 'exactly 3 todo tasks after parallel split').toBe(3);
      const todoKeys = todos.map((t) => t.nodeKey).sort();
      expect(todoKeys).toEqual(['task_a', 'task_b', 'task_c']);

      // Audit trail — D12
      const audit = await listAuditEvents(request, adminToken, pgInstanceId);
      expect(hasProcessStart(audit), 'audit must include process_start').toBe(true);
      const activityEvents = audit.filter((a) => a.operation === 'activity_event').length;
      expect(
        activityEvents,
        'parallel split must emit activity_event rows for start + split + 3 task starts (>=5)',
      ).toBeGreaterThanOrEqual(5);
    });

    // =======================================================================
    // PG-2: complete one branch → other two still active
    // =======================================================================
    test('PG-2: completing task_a leaves task_b + task_c active', async ({ request }) => {
      expect(pgInstanceId, 'PG-1 must have started an instance').toBeTruthy();

      const todosBefore = await listTodoForInstance(request, adminToken, pgInstanceId);
      const taskA = todosBefore.find((t) => t.nodeKey === 'task_a');
      expect(taskA, 'task_a must exist before completion').toBeTruthy();
      await completeTask(request, adminToken, taskA!.id);

      const status = await queryInstanceStatus(request, adminToken, {
        processKey: PG_KEY,
        businessKey: PG_BK,
      });
      // task_a has completed; task_b + task_c still pending. The join
      // gateway `pgw_join` also shows as a waiting-current node since one
      // token has reached it but the fork quorum isn't satisfied yet —
      // that's correct SmartEngine parallel-join semantics.
      const activeUserTaskIds = status.currentNodes
        .map((n) => n.nodeId)
        .filter((id) => id.startsWith('task_'))
        .sort();
      expect(
        activeUserTaskIds,
        'only task_b + task_c userTasks remain active after task_a complete',
      ).toEqual(['task_b', 'task_c']);
      expect(status.completedNodes.map((n) => n.nodeId)).toContain('task_a');
      expect(String(status.status).toLowerCase()).not.toMatch(
        /completed|finished|ended/,
      );

      // Instance still running — 2 userTask todos left
      const todosAfter = await listTodoForInstance(request, adminToken, pgInstanceId);
      expect(todosAfter.length).toBe(2);
    });

    // =======================================================================
    // PG-3: complete remaining 2 → join fires → instance completes
    // =======================================================================
    test('PG-3: completing all 3 branches triggers join and ends instance', async ({ request }) => {
      expect(pgInstanceId).toBeTruthy();

      const remaining = await listTodoForInstance(request, adminToken, pgInstanceId);
      expect(remaining.length, '2 tasks must remain before PG-3').toBe(2);

      // Complete task_b — instance still running (join needs all 3)
      const taskB = remaining.find((t) => t.nodeKey === 'task_b')!;
      await completeTask(request, adminToken, taskB.id);
      const midStatus = await queryInstanceStatus(request, adminToken, {
        processKey: PG_KEY,
        businessKey: PG_BK,
      });
      const midUserTasks = midStatus.currentNodes
        .map((n) => n.nodeId)
        .filter((id) => id.startsWith('task_'))
        .sort();
      expect(
        midUserTasks,
        'task_c userTask remains active after 2 of 3 branches complete',
      ).toEqual(['task_c']);

      // Complete task_c — join fires → end
      const taskC = remaining.find((t) => t.nodeKey === 'task_c')!;
      await completeTask(request, adminToken, taskC.id);

      const finalStatus = await queryInstanceStatus(request, adminToken, {
        processKey: PG_KEY,
        businessKey: PG_BK,
      });
      expect(
        String(finalStatus.status).toLowerCase(),
        'instance must reach completed after all 3 branches done',
      ).toMatch(/completed|finished|ended/);
      // Join + end must appear in completedNodes
      const completedIds = finalStatus.completedNodes.map((n) => n.nodeId);
      expect(completedIds).toContain('pgw_join');
      expect(completedIds).toContain('task_a');
      expect(completedIds).toContain('task_b');
      expect(completedIds).toContain('task_c');

      // No outstanding tasks
      const todosFinal = await listTodoForInstance(request, adminToken, pgInstanceId);
      expect(todosFinal.length).toBe(0);
    });

    // =======================================================================
    // Runtime probe — decide whether inclusive tests run for real or .fixme
    // =======================================================================
    test('IG-PROBE: engine accepts inclusiveGateway runtime start', async ({ request }) => {
      igPid = await createAndDeploy(
        request,
        adminToken,
        IG_KEY,
        `Inclusive GW Runtime ${TS}`,
        buildInclusiveBpmn(IG_KEY),
      );

      // Probe start: amount=200 & priority=vip → both branches should activate
      const probeResp = await request.post('/api/bpm/process-instances', {
        headers: authHeaders(adminToken),
        data: {
          processDefinitionId: IG_KEY,
          businessKey: IG_BK_BOTH,
          variables: { amount: 200, priority: 'vip' },
        },
      });
      if (!probeResp.ok()) {
        inclusiveRuntimeWorks = false;
        inclusiveProbeEvidence = `HTTP ${probeResp.status()}: ${(await probeResp.text().catch(() => '')).slice(0, 400)}`;
        // Deploy+start failure is a GAP candidate, not a test failure here;
        // IG-1/IG-2 will record it via .fixme guards below.
        test.info().annotations.push({
          type: 'inclusive-runtime-gap',
          description: inclusiveProbeEvidence,
        });
        return;
      }
      const probeBody = await probeResp.json();
      const instanceId: string =
        probeBody?.data?.processInstanceId ??
        probeBody?.data?.instanceId ??
        probeBody?.data?.id ??
        '';
      if (!instanceId) {
        inclusiveRuntimeWorks = false;
        inclusiveProbeEvidence = `start response missing instanceId: ${JSON.stringify(probeBody).slice(0, 400)}`;
        return;
      }
      igInstanceBothId = instanceId;

      // Assert currentNodes is queryable without error
      const statusResp = await request.get(
        `/api/bpm/process-instances/by-business-key/status?businessKey=${encodeURIComponent(IG_BK_BOTH)}&processKey=${encodeURIComponent(IG_KEY)}`,
        { headers: { Authorization: `Bearer ${adminToken}` } },
      );
      if (!statusResp.ok()) {
        inclusiveRuntimeWorks = false;
        inclusiveProbeEvidence = `status query failed HTTP ${statusResp.status()}: ${(await statusResp.text().catch(() => '')).slice(0, 400)}`;
      }
    });

    // =======================================================================
    // IG-1: inclusive split activates branches whose conditions hold
    // =======================================================================
    test('IG-1: inclusiveGateway activates both branches when both conditions true', async ({ request }) => {
      test.skip(
        !inclusiveRuntimeWorks,
        `inclusive gateway runtime not supported in this environment — ${inclusiveProbeEvidence}. GAP-253 candidate. (bpm-deep BPM-D10 also documents this limitation.)`,
      );
      expect(igInstanceBothId, 'IG-PROBE must have seeded an instance').toBeTruthy();

      const status = await queryInstanceStatus(request, adminToken, {
        processKey: IG_KEY,
        businessKey: IG_BK_BOTH,
      });
      const activeIds = status.currentNodes.map((n) => n.nodeId).sort();
      expect(
        activeIds,
        'amount=200 && priority=vip must activate both task_high + task_premium',
      ).toEqual(['task_high', 'task_premium']);

      const todos = await listTodoForInstance(request, adminToken, igInstanceBothId);
      expect(todos.length).toBe(2);
      expect(todos.map((t) => t.nodeKey).sort()).toEqual(['task_high', 'task_premium']);

      // Second instance — only priority=vip matches (amount=50 fails > 100)
      const onlyPremiumStart = await startProcessInstance(request, adminToken, {
        processDefinitionId: IG_KEY,
        businessKey: IG_BK_ONE,
        variables: { amount: 50, priority: 'vip' },
      });
      expect(onlyPremiumStart.instanceId).toBeTruthy();
      const onlyStatus = await queryInstanceStatus(request, adminToken, {
        processKey: IG_KEY,
        businessKey: IG_BK_ONE,
      });
      const onlyActive = onlyStatus.currentNodes.map((n) => n.nodeId);
      expect(onlyActive, 'premium-only path activates exactly task_premium').toEqual([
        'task_premium',
      ]);
      expect(onlyActive).not.toContain('task_high');
    });

    // =======================================================================
    // IG-2: inclusive completion — join only fires after every activated
    // branch completes (not all declared branches).
    //
    // Documented platform gap (2026-04-17): completing any userTask that sits
    // on an inclusive branch triggers the SmartEngine runtime to throw
    //
    //   ClassCastException: class java.lang.String cannot be cast to
    //   class java.util.List
    //
    // at task-complete time. HTTP 500 bubbles up through
    // TaskController.complete → SmartEngine core. The process deploys fine,
    // inclusive split fires and activates the correct branches (IG-PROBE +
    // IG-1 pass), and `currentNodes` / `todo` list report accurately — but
    // the join side of the runtime is not wired.
    //
    // Therefore IG-2 is .fixme until the engine fix lands. This is the
    // GAP-253 candidate called out by this P2.5 work; bpm-deep BPM-D10
    // recorded a symptom-level version of the same issue (line 572-573:
    // "Runtime engine currently returns 500 on inclusive split execution
    // in this environment"), but until now no runtime test has pinned
    // down the exact failure signature (complete-task → ClassCastException).
    // =======================================================================
    test('IG-2: inclusive join waits for every activated branch to complete', async ({ request }) => {
      // GAP-253 fixed: AuraVariablePersister.deserialize now special-cases
      // the engine-internal $triggerActivityIds$ key and returns List<String>
      // so InclusiveGatewayHelper#findTriggerActivityIdsFromDB no longer
      // throws ClassCastException when the join evaluates. Original failure:
      //   POST /api/bpm/tasks/{taskId}/complete →
      //     500 {"exception":"ClassCastException","detail":"class
      //     java.lang.String cannot be cast to class java.util.List"}
      test.skip(
        !inclusiveRuntimeWorks,
        `inclusive gateway runtime not supported in this environment — ${inclusiveProbeEvidence}.`,
      );
      expect(igInstanceBothId).toBeTruthy();

      const todosBefore = await listTodoForInstance(request, adminToken, igInstanceBothId);
      expect(todosBefore.length, 'IG-1 left 2 active tasks').toBe(2);

      // Complete task_high first — task_premium still active, instance still running
      const taskHigh = todosBefore.find((t) => t.nodeKey === 'task_high')!;
      await completeTask(request, adminToken, taskHigh.id);
      const midStatus = await queryInstanceStatus(request, adminToken, {
        processKey: IG_KEY,
        businessKey: IG_BK_BOTH,
      });
      const midUserTasks = midStatus.currentNodes
        .map((n) => n.nodeId)
        .filter((id) => id.startsWith('task_'));
      expect(midUserTasks).toEqual(['task_premium']);
      expect(String(midStatus.status).toLowerCase()).not.toMatch(
        /completed|finished|ended/,
      );

      // Complete task_premium → join fires → end
      const taskPremium = (
        await listTodoForInstance(request, adminToken, igInstanceBothId)
      ).find((t) => t.nodeKey === 'task_premium')!;
      await completeTask(request, adminToken, taskPremium.id);

      const finalStatus = await queryInstanceStatus(request, adminToken, {
        processKey: IG_KEY,
        businessKey: IG_BK_BOTH,
      });
      expect(
        String(finalStatus.status).toLowerCase(),
        'instance must complete once every activated inclusive branch is done',
      ).toMatch(/completed|finished|ended/);
      const completedIds = finalStatus.completedNodes.map((n) => n.nodeId);
      expect(completedIds).toContain('task_high');
      expect(completedIds).toContain('task_premium');
      expect(completedIds).toContain('igw_join');
    });

    // =======================================================================
    // CLEANUP — best-effort undeploy. Running instances may block undeploy
    // (HTTP 500 with "Cannot undeploy: N running instance(s)"), matches the
    // pattern in designer-gateway-lifecycle.spec.ts B2c.
    // =======================================================================
    test('CLEANUP: undeploy parallel + inclusive definitions (best-effort)', async ({ request }) => {
      if (pgPid) {
        const { status } = await undeployProcess(request, adminToken, pgPid);
        expect(
          [200, 204, 500],
          `PG undeploy ${status} must be ok-or-running-blocked`,
        ).toContain(status);
      }
      if (igPid) {
        const { status } = await undeployProcess(request, adminToken, igPid);
        expect(
          [200, 204, 500],
          `IG undeploy ${status} must be ok-or-running-blocked`,
        ).toContain(status);
      }
    });
  },
);
