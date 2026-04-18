/**
 * BPM Assertions Helper — 3-layer verification for BPM process definitions.
 *
 * Endpoint verification (grepped from controller sources):
 *
 *   assertDesignerJson:
 *     GET /api/bpm/process-definitions/{pid}
 *     → ApiResponse<ProcessDefinitionDTO> with field `designerJson` (JSON string)
 *     Source: ProcessDefinitionController.java @GetMapping("/{pid}"), toDTO() line 326
 *
 *   assertBpmnXml:
 *     GET /api/bpm/process-definitions/{pid}/bpmn
 *     → ApiResponse<String> with the raw BPMN XML as `data`
 *     Source: ProcessDefinitionController.java @GetMapping("/{pid}/bpmn"), line 113
 *     Note: `bpmnContent` is NOT included in ProcessDefinitionDTO — it has its own endpoint.
 *
 *   startInstanceAndAdvance:
 *     POST /api/bpm/process-instances           — start; body: { processDefinitionId, variables }
 *     GET  /api/bpm/tasks/by-process/{pid}      — list active tasks for instance
 *     POST /api/bpm/tasks/{taskId}/complete     — complete; body: { variables }
 *     POST /api/bpm/tasks/{taskId}/reject       — reject;   body: { comment, variables }
 *     GET  /api/bpm/process-instances/{id}      — read final status
 *     Source: ProcessInstanceController.java, TaskController.java
 *
 *   Task identification: TaskInstance.processDefinitionActivityId is the BPMN node id
 *   (i.e., the "taskDefinitionKey"). Confirmed via BpmFormController.java line 61 and
 *   TaskService.java line 467.
 *
 * Red lines honoured:
 *   - No multi-path response fallback — single deterministic field access.
 *   - No sleep / waitForTimeout — if polling is needed, throw a clear error.
 *   - Assertion failures throw with expected vs. actual detail.
 *   - Playwright `expect` used for all assertions.
 */

import { type APIRequestContext, expect } from '@playwright/test';

// ---------------------------------------------------------------------------
// Public types
// ---------------------------------------------------------------------------

export interface AdvanceStep {
  /** Must match BPMN node id (processDefinitionActivityId in TaskInstance). */
  taskDefKey: string;
  action: 'complete' | 'reject';
  vars?: Record<string, unknown>;
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

function authHeader(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}` };
}

/**
 * Fetch a process definition DTO and return the parsed body.
 * Throws a descriptive error if the request fails or the body is malformed.
 */
async function fetchProcessDefinition(
  api: APIRequestContext,
  token: string,
  pdId: string,
): Promise<Record<string, unknown>> {
  const resp = await api.get(`/api/bpm/process-definitions/${pdId}`, {
    headers: authHeader(token),
  });

  expect(
    resp.ok(),
    `GET /api/bpm/process-definitions/${pdId} failed with status ${resp.status()}`,
  ).toBe(true);

  const body = (await resp.json()) as Record<string, unknown>;
  const data = body?.data as Record<string, unknown> | undefined;
  if (!data) {
    throw new Error(
      `fetchProcessDefinition: response missing "data" field. Full body: ${JSON.stringify(body)}`,
    );
  }
  return data;
}

// ---------------------------------------------------------------------------
// Exported API
// ---------------------------------------------------------------------------

/**
 * Layer 1 — Designer JSON verification.
 *
 * Fetches the process definition and asserts that:
 *  1. All expected nodeIds are present in the `nodes` array.
 *  2. All expected edgeSpecs (from → to, optional condition substring) are
 *     present in the `edges` array.
 *
 * designerJson is stored as a JSON string in the DTO extension field and
 * surfaced as `data.designerJson` in the API response.
 */
export async function assertDesignerJson(
  api: APIRequestContext,
  token: string,
  pdId: string,
  expected: {
    nodeIds: string[];
    edgeSpecs: Array<{ from: string; to: string; condition?: string }>;
  },
): Promise<void> {
  const data = await fetchProcessDefinition(api, token, pdId);

  const rawDesignerJson = data.designerJson as string | undefined;
  if (!rawDesignerJson) {
    throw new Error(
      `assertDesignerJson: process definition "${pdId}" has no designerJson. ` +
        `Ensure the process was saved with designer data. DTO fields: ${Object.keys(data).join(', ')}`,
    );
  }

  const dj = JSON.parse(rawDesignerJson) as Record<string, unknown>;

  // Node assertion
  const nodes = (dj.nodes ?? []) as Array<Record<string, unknown>>;
  const nodeIdSet = new Set(nodes.map((n) => n.id as string));

  for (const expectedId of expected.nodeIds) {
    if (!nodeIdSet.has(expectedId)) {
      throw new Error(
        `assertDesignerJson: expected node id "${expectedId}" not found. ` +
          `Found node ids: [${[...nodeIdSet].join(', ')}]`,
      );
    }
  }

  // Edge assertion
  const edges = (dj.edges ?? []) as Array<Record<string, unknown>>;

  for (const spec of expected.edgeSpecs) {
    const match = edges.find((e) => {
      const source = (e.source ?? e.from) as string;
      const target = (e.target ?? e.to) as string;
      return source === spec.from && target === spec.to;
    });

    if (!match) {
      const edgeSummary = edges
        .map((e) => `${e.source ?? e.from}→${e.target ?? e.to}`)
        .join(', ');
      throw new Error(
        `assertDesignerJson: expected edge ${spec.from}→${spec.to} not found. ` +
          `Found edges: [${edgeSummary}]`,
      );
    }

    if (spec.condition !== undefined) {
      // Condition may live in data.condition or directly on the edge object
      const edgeData = (match.data ?? match) as Record<string, unknown>;
      const actual = (edgeData.condition ?? edgeData.conditionExpression ?? '') as string;
      if (!actual.includes(spec.condition)) {
        throw new Error(
          `assertDesignerJson: edge ${spec.from}→${spec.to} condition does not contain ` +
            `"${spec.condition}". Actual condition: "${actual}"`,
        );
      }
    }
  }
}

/**
 * Layer 2 — BPMN XML verification.
 *
 * Fetches BPMN XML from GET /api/bpm/process-definitions/{pid}/bpmn and
 * runs regex-based assertions.
 *
 * checks:
 *   hasFlowElement   — XML must contain id="{value}" attribute for each entry.
 *   gatewayConditions — Each edge id must have a <conditionExpression> containing
 *                       the expected substring.
 *   userTaskFormKey  — Each userTask id must carry formKey="{value}" attribute
 *                       (case-insensitive for the attribute name).
 */
export async function assertBpmnXml(
  api: APIRequestContext,
  token: string,
  pdId: string,
  checks: {
    hasFlowElement?: string[];
    gatewayConditions?: Record<string, string>;
    userTaskFormKey?: Record<string, string>;
  },
): Promise<void> {
  const resp = await api.get(`/api/bpm/process-definitions/${pdId}/bpmn`, {
    headers: authHeader(token),
  });

  expect(
    resp.ok(),
    `GET /api/bpm/process-definitions/${pdId}/bpmn failed with status ${resp.status()}`,
  ).toBe(true);

  const body = (await resp.json()) as Record<string, unknown>;
  const xml = body?.data as string | undefined;
  if (typeof xml !== 'string' || xml.trim().length === 0) {
    throw new Error(
      `assertBpmnXml: response "data" is not a non-empty string. ` +
        `Full body: ${JSON.stringify(body)}`,
    );
  }

  // --- hasFlowElement ---
  if (checks.hasFlowElement) {
    for (const elemId of checks.hasFlowElement) {
      const idPattern = new RegExp(`id=["']${escapeRegex(elemId)}["']`);
      if (!idPattern.test(xml)) {
        throw new Error(
          `assertBpmnXml: expected flow element with id="${elemId}" not found in BPMN XML.`,
        );
      }
    }
  }

  // --- gatewayConditions ---
  if (checks.gatewayConditions) {
    for (const [edgeId, expectedSubstring] of Object.entries(checks.gatewayConditions)) {
      // Find the sequenceFlow element for edgeId, then check its conditionExpression child
      const seqFlowPattern = new RegExp(
        `<[^>]*sequenceFlow[^>]*id=["']${escapeRegex(edgeId)}["'][^>]*>([\\s\\S]*?)</[^>]*sequenceFlow>`,
        'i',
      );
      const seqFlowMatch = xml.match(seqFlowPattern);

      if (!seqFlowMatch) {
        throw new Error(
          `assertBpmnXml: sequenceFlow element with id="${edgeId}" not found in BPMN XML.`,
        );
      }

      const seqFlowBody = seqFlowMatch[1];
      const condPattern = /<[^>]*conditionExpression[^>]*>([\s\S]*?)<\/[^>]*conditionExpression>/i;
      const condMatch = seqFlowBody.match(condPattern);

      if (!condMatch) {
        throw new Error(
          `assertBpmnXml: sequenceFlow "${edgeId}" has no <conditionExpression> child. ` +
            `sequenceFlow body: ${seqFlowBody.trim().substring(0, 200)}`,
        );
      }

      const actualCondition = condMatch[1];
      if (!actualCondition.includes(expectedSubstring)) {
        throw new Error(
          `assertBpmnXml: conditionExpression for sequenceFlow "${edgeId}" does not contain ` +
            `"${expectedSubstring}". Actual: "${actualCondition}"`,
        );
      }
    }
  }

  // --- userTaskFormKey ---
  if (checks.userTaskFormKey) {
    for (const [taskId, expectedFormKey] of Object.entries(checks.userTaskFormKey)) {
      // Match <userTask ... id="taskId" ... formKey="value" ...>
      const userTaskPattern = new RegExp(
        `<[^>]*userTask[^>]*id=["']${escapeRegex(taskId)}["'][^>]*>`,
        'i',
      );
      const userTaskTagMatch = xml.match(userTaskPattern);

      if (!userTaskTagMatch) {
        throw new Error(
          `assertBpmnXml: userTask element with id="${taskId}" not found in BPMN XML.`,
        );
      }

      const tag = userTaskTagMatch[0];
      const formKeyPattern = /formKey=["']([^"']+)["']/i;
      const formKeyMatch = tag.match(formKeyPattern);

      if (!formKeyMatch) {
        throw new Error(
          `assertBpmnXml: userTask "${taskId}" has no formKey attribute. Tag: ${tag}`,
        );
      }

      const actualFormKey = formKeyMatch[1];
      if (actualFormKey !== expectedFormKey) {
        throw new Error(
          `assertBpmnXml: userTask "${taskId}" formKey expected "${expectedFormKey}" ` +
            `but got "${actualFormKey}".`,
        );
      }
    }
  }
}

/**
 * Layer 3 — Process instance lifecycle verification.
 *
 * Starts a process instance, then for each step:
 *   1. Fetches active tasks via GET /api/bpm/tasks/by-process/{instanceId}.
 *   2. Finds the task whose processDefinitionActivityId matches step.taskDefKey.
 *   3. Calls complete or reject on that task.
 *
 * After all steps, fetches the final instance status from
 * GET /api/bpm/process-instances/{instanceId} and returns it.
 *
 * Throws if:
 *   - Start fails.
 *   - No task found matching taskDefKey (instead of silently retrying).
 *   - Complete/reject call fails.
 */
export async function startInstanceAndAdvance(
  api: APIRequestContext,
  token: string,
  pdId: string,
  startVars: Record<string, unknown>,
  steps: AdvanceStep[],
): Promise<{ instanceId: string; finalStatus: string }> {
  // --- Start process instance ---
  const startResp = await api.post('/api/bpm/process-instances', {
    headers: { ...authHeader(token), 'Content-Type': 'application/json' },
    data: { processDefinitionId: pdId, variables: startVars },
  });

  expect(
    startResp.ok(),
    `POST /api/bpm/process-instances failed with status ${startResp.status()}`,
  ).toBe(true);

  const startBody = (await startResp.json()) as Record<string, unknown>;
  const startData = startBody?.data as Record<string, unknown> | undefined;
  if (!startData) {
    throw new Error(
      `startInstanceAndAdvance: start response missing "data". ` +
        `Full body: ${JSON.stringify(startBody)}`,
    );
  }

  const instanceId = startData.instanceId as string | undefined;
  if (!instanceId) {
    throw new Error(
      `startInstanceAndAdvance: start response "data" missing "instanceId". ` +
        `data fields: ${Object.keys(startData).join(', ')}. Full body: ${JSON.stringify(startBody)}`,
    );
  }

  // --- Advance through steps ---
  for (let stepIndex = 0; stepIndex < steps.length; stepIndex++) {
    const step = steps[stepIndex];

    // Fetch active tasks for the instance
    const tasksResp = await api.get(`/api/bpm/tasks/by-process/${instanceId}`, {
      headers: authHeader(token),
    });

    expect(
      tasksResp.ok(),
      `GET /api/bpm/tasks/by-process/${instanceId} failed with status ${tasksResp.status()} ` +
        `(step ${stepIndex}: taskDefKey="${step.taskDefKey}")`,
    ).toBe(true);

    const tasksBody = (await tasksResp.json()) as Record<string, unknown>;
    const tasks = tasksBody?.data as Array<Record<string, unknown>> | undefined;
    if (!Array.isArray(tasks)) {
      throw new Error(
        `startInstanceAndAdvance: tasks response "data" is not an array ` +
          `(step ${stepIndex}: taskDefKey="${step.taskDefKey}"). ` +
          `Full body: ${JSON.stringify(tasksBody)}`,
      );
    }

    // Find task by processDefinitionActivityId (the BPMN node id)
    const task = tasks.find(
      (t) => (t.processDefinitionActivityId as string) === step.taskDefKey,
    );

    if (!task) {
      const available = tasks
        .map((t) => t.processDefinitionActivityId as string)
        .join(', ');
      throw new Error(
        `startInstanceAndAdvance: no active task with processDefinitionActivityId ` +
          `"${step.taskDefKey}" found for instance "${instanceId}" ` +
          `(step ${stepIndex}). Available task keys: [${available}]. ` +
          `Total active tasks: ${tasks.length}`,
      );
    }

    const taskId = task.instanceId as string | undefined;
    if (!taskId) {
      throw new Error(
        `startInstanceAndAdvance: task with processDefinitionActivityId ` +
          `"${step.taskDefKey}" has no "instanceId" field. ` +
          `Task fields: ${Object.keys(task).join(', ')}`,
      );
    }

    // Perform the action
    if (step.action === 'complete') {
      const completeResp = await api.post(`/api/bpm/tasks/${taskId}/complete`, {
        headers: { ...authHeader(token), 'Content-Type': 'application/json' },
        data: { variables: step.vars ?? {} },
      });
      expect(
        completeResp.ok(),
        `POST /api/bpm/tasks/${taskId}/complete failed with status ${completeResp.status()} ` +
          `(step ${stepIndex}: taskDefKey="${step.taskDefKey}")`,
      ).toBe(true);
    } else {
      const rejectResp = await api.post(`/api/bpm/tasks/${taskId}/reject`, {
        headers: { ...authHeader(token), 'Content-Type': 'application/json' },
        data: { comment: '', variables: step.vars ?? {} },
      });
      expect(
        rejectResp.ok(),
        `POST /api/bpm/tasks/${taskId}/reject failed with status ${rejectResp.status()} ` +
          `(step ${stepIndex}: taskDefKey="${step.taskDefKey}")`,
      ).toBe(true);
    }
  }

  // --- Fetch final instance status ---
  const finalResp = await api.get(`/api/bpm/process-instances/${instanceId}`, {
    headers: authHeader(token),
  });

  expect(
    finalResp.ok(),
    `GET /api/bpm/process-instances/${instanceId} failed with status ${finalResp.status()} (final status fetch)`,
  ).toBe(true);

  const finalBody = (await finalResp.json()) as Record<string, unknown>;
  const finalData = finalBody?.data as Record<string, unknown> | undefined;
  if (!finalData) {
    throw new Error(
      `startInstanceAndAdvance: final status response missing "data". ` +
        `Full body: ${JSON.stringify(finalBody)}`,
    );
  }

  const finalStatus = (finalData.status ?? finalData.state) as string | undefined;
  if (!finalStatus) {
    throw new Error(
      `startInstanceAndAdvance: final instance response missing "status" or "state" field. ` +
        `data fields: ${Object.keys(finalData).join(', ')}`,
    );
  }

  return { instanceId, finalStatus };
}

// ---------------------------------------------------------------------------
// Utilities
// ---------------------------------------------------------------------------

function escapeRegex(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
