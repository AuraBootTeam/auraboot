/**
 * BPM Designer — representative node property matrix.
 *
 * This is the hardened replacement path for the historical
 * designer/bpmn-node-properties.spec.ts suite debt. It covers representative
 * node/edge property editors through real DOM controls, saves through the
 * toolbar dialog, then verifies backend designerJson and emitted BPMN XML.
 *
 * Coverage:
 *   - startEvent: initiator + formKey
 *   - userTask: expression assignee + approval mode + priority + skipable
 *   - serviceTask: command binding + async flag
 *   - exclusiveGateway: defaultFlow
 *   - callActivity: process picker + input/output variable mappings
 *   - sequenceFlow edge: label + advanced condition expression
 */

import { test, expect, type APIRequestContext, type Page } from '@playwright/test';
import {
  openDesigner,
  addNode,
  connect,
  saveProcess,
  deployProcess,
} from '../../helpers/designer-dsl';
import { loginAs } from '../../helpers/wd-fixtures';
import { BACKEND_URL } from '../../helpers/environments';

// Flow/BPMN designer uses a compact layout below 1600px (palette/inspector collapse
// behind toggles + a drawer backdrop intercepts canvas clicks). These specs assert the
// palette/canvas/nodes directly, so run them at the wide layout the designer targets.
// See FlowDesigner.tsx COMPACT_FLOW_DESIGNER_QUERY '(max-width: 1599px)'.
test.use({ viewport: { width: 1680, height: 1050 } });

const BACKEND = BACKEND_URL;

function authHeader(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}` };
}

function childBpmnXml(processKey: string): string {
  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             targetNamespace="http://auraboot.com/bpm"
             id="def_${processKey}">
  <process id="${processKey}" name="${processKey}" isExecutable="true">
    <startEvent id="child_start" name="Start"/>
    <userTask id="child_task" name="Child Task"/>
    <endEvent id="child_end" name="End"/>
    <sequenceFlow id="child_e1" sourceRef="child_start" targetRef="child_task"/>
    <sequenceFlow id="child_e2" sourceRef="child_task" targetRef="child_end"/>
  </process>
</definitions>`;
}

function childDesignerJson(): string {
  return JSON.stringify({
    nodes: [
      {
        id: 'child_start',
        type: 'startEvent',
        position: { x: 80, y: 180 },
        data: { type: 'startEvent', label: 'Start', config: {} },
      },
      {
        id: 'child_task',
        type: 'userTask',
        position: { x: 280, y: 180 },
        data: {
          type: 'userTask',
          label: 'Child Task',
          config: { assignee: { type: 'starter' } },
        },
      },
      {
        id: 'child_end',
        type: 'endEvent',
        position: { x: 480, y: 180 },
        data: { type: 'endEvent', label: 'End', config: {} },
      },
    ],
    edges: [
      { id: 'child_e1', source: 'child_start', target: 'child_task', type: 'smoothstep', data: {} },
      { id: 'child_e2', source: 'child_task', target: 'child_end', type: 'smoothstep', data: {} },
    ],
  });
}

async function createAndDeployChildProcess(
  request: APIRequestContext,
  token: string,
  processKey: string,
): Promise<void> {
  const createResp = await request.post(`${BACKEND}/api/bpm/process-definitions`, {
    headers: { ...authHeader(token), 'Content-Type': 'application/json' },
    data: {
      processKey,
      processName: `Node Matrix Child ${processKey}`,
      description: 'Child process for callActivity property matrix',
      category: 'e2e-test',
      bpmnContent: childBpmnXml(processKey),
      designerJson: childDesignerJson(),
    },
  });
  expect(createResp.ok(), `create child process failed: ${createResp.status()}`).toBe(true);
  const createBody = (await createResp.json()) as Record<string, unknown>;
  const pid = String((createBody.data as Record<string, unknown> | undefined)?.pid ?? '');
  expect(pid, 'child process create response must include pid').toBeTruthy();

  const deployResp = await request.post(
    `${BACKEND}/api/bpm/process-definitions/${pid}/deploy`,
    { headers: authHeader(token), data: {} },
  );
  expect(deployResp.ok(), `deploy child process failed: ${deployResp.status()}`).toBe(true);
}

async function selectNode(page: Page, nodeId: string): Promise<void> {
  const node = page.locator(`.react-flow__node[data-id="${nodeId}"]`);
  await expect(node, `node ${nodeId} should render on canvas`).toBeVisible({ timeout: 5_000 });
  await node.click();
  await expect(page.getByTestId('node-label-input')).toBeVisible({ timeout: 5_000 });
}

async function selectEdge(page: Page, edgeId: string): Promise<void> {
  await page.evaluate((id) => {
    const store = (
      window as unknown as {
        __bpmnDesignerStore?: { getState: () => Record<string, unknown> };
      }
    ).__bpmnDesignerStore;
    if (!store) throw new Error('__bpmnDesignerStore is not available');
    const state = store.getState() as unknown as {
      setSelectedNode: (nodeId: string | null) => void;
      setSelectedEdge: (edgeId: string | null) => void;
    };
    state.setSelectedNode(null);
    state.setSelectedEdge(id);
  }, edgeId);
  await expect(page.getByTestId('edge-label-input')).toBeVisible({ timeout: 5_000 });
}

async function fetchProcessDefinition(
  request: APIRequestContext,
  token: string,
  pid: string,
): Promise<{
  dto: Record<string, unknown>;
  designerJson: { nodes: Array<Record<string, unknown>>; edges: Array<Record<string, unknown>> };
}> {
  const resp = await request.get(`${BACKEND}/api/bpm/process-definitions/${pid}`, {
    headers: authHeader(token),
  });
  expect(resp.ok(), `GET process definition failed: ${resp.status()}`).toBe(true);
  const body = (await resp.json()) as Record<string, unknown>;
  const dto = body.data as Record<string, unknown>;
  expect(dto, 'process definition response must include data').toBeTruthy();
  const rawDesignerJson = dto.designerJson as string;
  expect(typeof rawDesignerJson, 'designerJson must be a string').toBe('string');
  return { dto, designerJson: JSON.parse(rawDesignerJson) };
}

function findNode(
  doc: { nodes: Array<Record<string, unknown>> },
  nodeId: string,
): Record<string, unknown> {
  const node = doc.nodes.find((candidate) => candidate.id === nodeId);
  expect(node, `designerJson must contain node ${nodeId}`).toBeDefined();
  return node!;
}

function findEdge(
  doc: { edges: Array<Record<string, unknown>> },
  edgeId: string,
): Record<string, unknown> {
  const edge = doc.edges.find((candidate) => candidate.id === edgeId);
  expect(edge, `designerJson must contain edge ${edgeId}`).toBeDefined();
  return edge!;
}

async function fetchBpmnXml(request: APIRequestContext, token: string, pid: string): Promise<string> {
  const resp = await request.get(`${BACKEND}/api/bpm/process-definitions/${pid}/bpmn`, {
    headers: authHeader(token),
  });
  expect(resp.ok(), `GET BPMN XML failed: ${resp.status()}`).toBe(true);
  const body = (await resp.json()) as Record<string, unknown>;
  const xml = body.data as string;
  expect(typeof xml === 'string' && xml.length > 0, 'BPMN XML must be non-empty').toBe(true);
  return xml;
}

test.describe('BPM designer — node property matrix', { tag: ['@bpm-regression'] }, () => {
  test.setTimeout(180_000);

  test('edits representative node and edge properties via UI, then persists DTO and BPMN XML', async ({
    page,
    request,
  }) => {
    const suffix = Date.now();
    const childProcessKey = `e2e_node_matrix_child_${suffix}`;
    const parentProcessKey = `e2e_node_matrix_parent_${suffix}`;
    const adminToken = await loginAs(request, 'admin@auraboot.com', 'Test2026x');

    await createAndDeployChildProcess(request, adminToken, childProcessKey);

    await openDesigner(page, {
      processKey: parentProcessKey,
      name: `Node Matrix ${suffix}`,
    });

    await addNode(page, { id: 'start_1', type: 'startEvent', position: { x: 80, y: 260 }, label: 'Start' });
    await addNode(page, { id: 'task_review', type: 'userTask', position: { x: 260, y: 260 }, label: 'Review' });
    await addNode(page, { id: 'gw_route', type: 'exclusiveGateway', position: { x: 460, y: 260 }, label: 'Route' });
    await addNode(page, { id: 'svc_notify', type: 'serviceTask', position: { x: 660, y: 160 }, label: 'Notify' });
    await addNode(page, { id: 'call_child', type: 'callActivity', position: { x: 660, y: 360 }, label: 'Call Child' });
    await addNode(page, { id: 'end_1', type: 'endEvent', position: { x: 860, y: 260 }, label: 'End' });

    await connect(page, { from: 'start_1', to: 'task_review' });
    await connect(page, { from: 'task_review', to: 'gw_route' });
    await connect(page, { from: 'gw_route', to: 'svc_notify', condition: '${amount >= 1000}' });
    await connect(page, { from: 'gw_route', to: 'call_child', condition: '${amount < 1000}' });
    await connect(page, { from: 'svc_notify', to: 'end_1' });
    await connect(page, { from: 'call_child', to: 'end_1' });

    await selectNode(page, 'start_1');
    await page.getByTestId('startevent-initiator').fill('starterUser');
    await page.getByTestId('startevent-form-key').fill('leave_start_form');

    await selectNode(page, 'task_review');
    await page.getByTestId('usertask-description').fill('Review amount and approve');
    await page.getByTestId('usertask-assignee-type').selectOption('expression');
    await page.getByTestId('usertask-expression').fill('${starter.managerId}');
    await page.getByTestId('usertask-approval-mode').selectOption('multi');
    await page.getByTestId('usertask-priority').fill('88');
    await page.getByTestId('usertask-skipable').check();

    await selectNode(page, 'svc_notify');
    await page.getByTestId('servicetask-service-type').selectOption('command');
    await page.getByTestId('servicetask-command-code').fill('wd:submit_leave_request');
    await page.getByTestId('servicetask-async').check();

    await selectNode(page, 'gw_route');
    await page.getByTestId('gateway-default-flow').selectOption('edge-gw_route-call_child');

    await selectEdge(page, 'edge-gw_route-svc_notify');
    await page.getByTestId('edge-label-input').fill('High amount approved');
    await page.getByTestId('condition-mode-advanced').click();
    await page.getByTestId('condition-advanced-content').fill('${amount >= 2000 && approved == true}');

    await selectNode(page, 'call_child');
    await page.getByTestId('callactivity-description').fill('Invoke deployed child process');
    await page.getByTestId('process-picker-select').selectOption(childProcessKey);
    await page.getByTestId('callactivity-version-mode').selectOption('latest');
    await page.getByTestId('callactivity-mapping-toggle').click();
    await page.getByTestId('callactivity-input-add').click();
    const inputRow = page.getByTestId('callactivity-input-row-0');
    await inputRow.locator('input').nth(0).fill('applicantId');
    await inputRow.locator('input').nth(1).fill('childApplicantId');
    await page.getByTestId('callactivity-output-add').click();
    const outputRow = page.getByTestId('callactivity-output-row-0');
    await outputRow.locator('input').nth(0).fill('childDecision');
    await outputRow.locator('input').nth(1).fill('parentDecision');

    await selectNode(page, 'end_1');
    await page.getByTestId('endevent-terminate-all').check();

    const { processDefinitionId } = await saveProcess(page);
    await deployProcess(page, processDefinitionId);

    const { designerJson } = await fetchProcessDefinition(request, adminToken, processDefinitionId);

    const start = findNode(designerJson, 'start_1');
    expect(start.data).toMatchObject({
      config: expect.objectContaining({
        initiator: 'starterUser',
        formKey: 'leave_start_form',
      }),
    });

    const userTask = findNode(designerJson, 'task_review');
    expect(userTask.data).toMatchObject({
      label: 'Review',
      config: expect.objectContaining({
        description: 'Review amount and approve',
        priority: 88,
        skipable: true,
        assignee: expect.objectContaining({
          type: 'expression',
          expression: '${starter.managerId}',
          assigneeMode: 'multi',
        }),
      }),
    });

    const serviceTask = findNode(designerJson, 'svc_notify');
    expect(serviceTask.data).toMatchObject({
      config: expect.objectContaining({
        serviceType: 'command',
        commandCode: 'wd:submit_leave_request',
        async: true,
      }),
    });

    const gateway = findNode(designerJson, 'gw_route');
    expect(gateway.data).toMatchObject({
      config: expect.objectContaining({
        defaultFlow: 'edge-gw_route-call_child',
      }),
    });

    const routedEdge = findEdge(designerJson, 'edge-gw_route-svc_notify');
    expect(routedEdge.data).toMatchObject({
      label: 'High amount approved',
      condition: expect.objectContaining({
        type: 'expression',
        content: '${amount >= 2000 && approved == true}',
      }),
    });

    const callActivity = findNode(designerJson, 'call_child');
    expect(callActivity.data).toMatchObject({
      config: expect.objectContaining({
        calledProcessKey: childProcessKey,
        calledProcessVersion: 'latest',
        inputMappings: { applicantId: 'childApplicantId' },
        outputMappings: { childDecision: 'parentDecision' },
      }),
    });

    const end = findNode(designerJson, 'end_1');
    expect(end.data).toMatchObject({
      config: expect.objectContaining({ terminateAll: true }),
    });

    const xml = await fetchBpmnXml(request, adminToken, processDefinitionId);
    expect(xml).toContain('id="task_review"');
    expect(xml).toContain('id="svc_notify"');
    expect(xml).toContain('commandServiceTaskDelegate');
    expect(xml).toContain(`calledElement="${childProcessKey}"`);
    expect(xml).toContain('aura.callMappings');
    expect(xml).toContain('amount >= 2000');
    expect(xml).toContain('approved == true');
  });
});
