/**
 * BPM ruleBinding fail-closed runtime golden.
 *
 * Focused gap: a userTask with both static assignee and aura.ruleBinding must
 * fail closed when the bound decision is unavailable. The runtime must not
 * silently fall back to the static assignee, and the process status page must
 * show a business-readable fail-closed trace without leaking raw backend errors.
 *
 * Evidence pairing:
 * - Backend: deploy real BPMN, start SmartEngine instance, assert timeline trace
 *   and absence from the current user's todo list.
 * - Browser: open the real process-status UI and assert localized fail-closed
 *   Rule Trace.
 *
 * @bpm-regression
 */

import { test, expect, type APIRequestContext, type Page } from '../../fixtures';
import {
  listExecutionTimeline,
  listTodoTasks,
  loginAsAdmin as loginApiAsAdmin,
  queryInstanceStatus,
  startProcessInstance,
} from './_helpers/bpm-lifecycle';
import { uniqueId } from '../helpers';
import { BASE_URL } from '../../helpers/environments';

test.use({ storageState: { cookies: [], origins: [] } });

type ApiEnvelope<T> = {
  code?: number | string;
  success?: boolean;
  message?: string;
  data?: T;
};

const UID = uniqueId('BPMFC').toLowerCase();
const PROCESS_KEY = `bpm_fc_${UID}`;
const PROCESS_NAME = `BPM Fail Closed ${UID}`;
const BUSINESS_KEY = `E2E-BPM-FC-${UID}`;
const MISSING_DECISION_CODE = `missing_bpm_assignment_${UID}`;
const ACTION_UID = uniqueId('BPMACT').toLowerCase();
const ACTION_PROCESS_KEY = `bpm_action_fail_${ACTION_UID}`;
const ACTION_PROCESS_NAME = `BPM Action Failure ${ACTION_UID}`;
const ACTION_BUSINESS_KEY = `E2E-BPM-ACTION-${ACTION_UID}`;
const ACTION_SUCCESS_UID = uniqueId('BPMACTOK').toLowerCase();
const ACTION_SUCCESS_PROCESS_KEY = `bpm_action_success_${ACTION_SUCCESS_UID}`;
const ACTION_SUCCESS_PROCESS_NAME = `BPM Action Success ${ACTION_SUCCESS_UID}`;
const ACTION_SUCCESS_BUSINESS_KEY = `E2E-BPM-ACTION-SUCCESS-${ACTION_SUCCESS_UID}`;
const ACTION_MODERN_FAILURE_UID = uniqueId('BPMACTMOD').toLowerCase();
const ACTION_MODERN_FAILURE_PROCESS_KEY = `bpm_action_modern_fail_${ACTION_MODERN_FAILURE_UID}`;
const ACTION_MODERN_FAILURE_PROCESS_NAME = `BPM Action Modern Failure ${ACTION_MODERN_FAILURE_UID}`;
const ACTION_MODERN_FAILURE_BUSINESS_KEY = `E2E-BPM-ACTION-MODERN-${ACTION_MODERN_FAILURE_UID}`;

let adminToken = '';
let currentUserId = '';
let processPid = '';
let instanceId = '';
let actionProcessPid = '';
let actionInstanceId = '';
let actionSuccessProcessPid = '';
let actionSuccessInstanceId = '';
let actionModernFailureProcessPid = '';
let actionModernFailureInstanceId = '';

function authHeaders(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
}

function isApiSuccess<T>(body: ApiEnvelope<T> | null | undefined): body is ApiEnvelope<T> {
  if (!body) return false;
  if (body.success === false) return false;
  const code = body.code;
  return code === undefined || code === null || String(code) === '0';
}

async function readApi<T>(response: Awaited<ReturnType<APIRequestContext['get']>>): Promise<T> {
  const body = (await response.json().catch(async () => ({
    message: await response.text().catch(() => ''),
  }))) as ApiEnvelope<T>;
  expect(
    response.ok(),
    `HTTP ${response.status()} ${response.url()}: ${JSON.stringify(body).slice(0, 500)}`,
  ).toBe(true);
  expect(isApiSuccess(body), `API failed: ${JSON.stringify(body).slice(0, 500)}`).toBe(true);
  return body.data as T;
}

async function loginWebAsAdmin(page: Page, baseURL: string): Promise<void> {
  const response = await page.request.post(`${baseURL}/login`, {
    form: {
      channelCode: 'email_password',
      email: 'admin@auraboot.com',
      password: 'Test2026x',
      remember: 'on',
      redirectTo: '/',
    },
    maxRedirects: 0,
  });

  expect(response.status(), `login failed: HTTP ${response.status()}`).toBe(302);
  const setCookie = response.headers()['set-cookie'] ?? '';
  const match = setCookie.match(/__session=([^;]+)/);
  expect(match?.[1], 'login action must return __session cookie').toBeTruthy();

  await page.context().addCookies([
    {
      name: '__session',
      value: match![1],
      domain: new URL(baseURL).hostname,
      path: '/',
      httpOnly: true,
      sameSite: 'Lax',
      expires: Math.floor(Date.now() / 1000) + 60 * 60,
    },
  ]);
}

function xmlAttr(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/"/g, '&quot;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');
}

function buildRuleBindingJson(): string {
  return JSON.stringify({
    consumerType: 'BPM',
    consumerCode: PROCESS_KEY,
    consumerNodeId: 'approve',
    bindingKind: 'DECISION_REF',
    decisionBinding: {
      decisionCode: MISSING_DECISION_CODE,
      versionPolicy: 'LATEST_PUBLISHED',
      inputMappings: [
        {
          input: 'amount',
          source: { kind: 'FIELD', scope: 'record', path: 'amount' },
        },
      ],
      outputMappings: [
        {
          output: 'reviewUsers',
          target: { kind: 'ACTION_PARAM', path: 'candidateUsers' },
        },
        {
          output: 'reviewGroups',
          target: { kind: 'ACTION_PARAM', path: 'candidateGroups' },
        },
        {
          output: 'primaryAssignee',
          target: { kind: 'PROCESS_VARIABLE', path: 'assigneeUserId' },
        },
      ],
      fallbackPolicy: { mode: 'FAIL_CLOSED' },
      traceMode: 'ALWAYS',
      enabled: true,
    },
    enabled: true,
  });
}

function buildBpmnXml(): string {
  const binding = xmlAttr(buildRuleBindingJson());
  const staticFallbackAssignee = xmlAttr(currentUserId);
  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             xmlns:smart="http://smartengine.org/schema/process"
             targetNamespace="http://auraboot.com/bpm">
  <process id="${PROCESS_KEY}" name="${xmlAttr(PROCESS_NAME)}" isExecutable="true">
    <startEvent id="start"/>
    <sequenceFlow id="f_start_approve" sourceRef="start" targetRef="approve"/>
    <userTask id="approve" name="Rule Assigned Approval"
              smart:assigneeType="user" smart:assigneeId="${staticFallbackAssignee}">
      <extensionElements>
        <smart:properties>
          <smart:property name="aura.ruleBinding" value="${binding}"/>
        </smart:properties>
      </extensionElements>
    </userTask>
    <sequenceFlow id="f_approve_end" sourceRef="approve" targetRef="end"/>
    <endEvent id="end"/>
  </process>
</definitions>`;
}

function buildDesignerJson(): string {
  return JSON.stringify({
    nodes: [
      {
        id: 'start',
        type: 'startEvent',
        position: { x: 100, y: 220 },
        data: { type: 'startEvent', label: '开始' },
      },
      {
        id: 'approve',
        type: 'userTask',
        position: { x: 330, y: 220 },
        data: {
          type: 'userTask',
          label: '规则分派审批',
          config: {
            assigneeType: 'user',
            assigneeId: currentUserId,
            ruleBinding: JSON.parse(buildRuleBindingJson()),
          },
        },
      },
      {
        id: 'end',
        type: 'endEvent',
        position: { x: 600, y: 220 },
        data: { type: 'endEvent', label: '结束' },
      },
    ],
    edges: [
      { id: 'f_start_approve', source: 'start', target: 'approve', type: 'smoothstep' },
      { id: 'f_approve_end', source: 'approve', target: 'end', type: 'smoothstep' },
    ],
  });
}

function buildActionBpmnXml(): string {
  const payloadJson = xmlAttr(JSON.stringify({ content: '流程短信' }));
  const idempotencyKey = xmlAttr(`${ACTION_BUSINESS_KEY}:sms_action:SEND_SMS`);
  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             xmlns:smart="http://smartengine.org/schema/process"
             targetNamespace="http://auraboot.com/bpm">
  <process id="${ACTION_PROCESS_KEY}" name="${xmlAttr(ACTION_PROCESS_NAME)}" isExecutable="true">
    <startEvent id="start"/>
    <sequenceFlow id="f_start_sms" sourceRef="start" targetRef="sms_action"/>
    <serviceTask id="sms_action" name="发送短信"
                 smart:class="pluginActionServiceTaskDelegate"
                 smart:action="SEND_SMS"
                 smart:target="PHONE:+8613800138000"
                 smart:payloadJson="${payloadJson}"
                 smart:ruleCode="bpm-action-e2e"
                 smart:resultVar="smsResult"
                 smart:idempotencyKey="${idempotencyKey}"/>
    <sequenceFlow id="f_sms_end" sourceRef="sms_action" targetRef="end"/>
    <endEvent id="end"/>
  </process>
</definitions>`;
}

function buildActionDesignerJson(): string {
  return JSON.stringify({
    nodes: [
      {
        id: 'start',
        type: 'startEvent',
        position: { x: 100, y: 220 },
        data: { type: 'startEvent', label: '开始' },
      },
      {
        id: 'sms_action',
        type: 'serviceTask',
        position: { x: 330, y: 220 },
        data: {
          type: 'serviceTask',
          label: '发送短信',
          config: {
            actionType: 'SEND_SMS',
            actionTarget: 'PHONE:+8613800138000',
            actionResultVar: 'smsResult',
            actionIdempotencyKey: `${ACTION_BUSINESS_KEY}:sms_action:SEND_SMS`,
          },
        },
      },
      {
        id: 'end',
        type: 'endEvent',
        position: { x: 600, y: 220 },
        data: { type: 'endEvent', label: '结束' },
      },
    ],
    edges: [
      { id: 'f_start_sms', source: 'start', target: 'sms_action', type: 'smoothstep' },
      { id: 'f_sms_end', source: 'sms_action', target: 'end', type: 'smoothstep' },
    ],
  });
}

function buildActionSuccessBpmnXml(): string {
  const payloadJson = xmlAttr(JSON.stringify({ channel: 'im', content: '流程 IM ${process.businessKey}' }));
  const idempotencyKey = xmlAttr(`${ACTION_SUCCESS_BUSINESS_KEY}:send_im_action:SEND_IM`);
  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             xmlns:smart="http://smartengine.org/schema/process"
             targetNamespace="http://auraboot.com/bpm">
  <process id="${ACTION_SUCCESS_PROCESS_KEY}" name="${xmlAttr(ACTION_SUCCESS_PROCESS_NAME)}" isExecutable="true">
    <startEvent id="start"/>
    <sequenceFlow id="f_start_im" sourceRef="start" targetRef="send_im_action"/>
    <serviceTask id="send_im_action" name="发送 IM 消息"
                 smart:class="pluginActionServiceTaskDelegate"
                 smart:action="SEND_IM"
                 smart:target="USER:${xmlAttr(currentUserId)}"
                 smart:payloadJson="${payloadJson}"
                 smart:ruleCode="bpm-action-success-e2e"
                 smart:resultVar="imResult"
                 smart:idempotencyKey="${idempotencyKey}"/>
    <sequenceFlow id="f_im_end" sourceRef="send_im_action" targetRef="end"/>
    <endEvent id="end"/>
  </process>
</definitions>`;
}

function buildActionSuccessDesignerJson(): string {
  return JSON.stringify({
    nodes: [
      {
        id: 'start',
        type: 'startEvent',
        position: { x: 100, y: 220 },
        data: { type: 'startEvent', label: '开始' },
      },
      {
        id: 'send_im_action',
        type: 'serviceTask',
        position: { x: 330, y: 220 },
        data: {
          type: 'serviceTask',
          label: '发送 IM 消息',
          config: {
            actionType: 'SEND_IM',
            actionTarget: `USER:${currentUserId}`,
            actionPayload: { channel: 'im', content: '流程 IM ${process.businessKey}' },
            actionResultVar: 'imResult',
            actionIdempotencyKey: `${ACTION_SUCCESS_BUSINESS_KEY}:send_im_action:SEND_IM`,
          },
        },
      },
      {
        id: 'end',
        type: 'endEvent',
        position: { x: 600, y: 220 },
        data: { type: 'endEvent', label: '结束' },
      },
    ],
    edges: [
      { id: 'f_start_im', source: 'start', target: 'send_im_action', type: 'smoothstep' },
      { id: 'f_im_end', source: 'send_im_action', target: 'end', type: 'smoothstep' },
    ],
  });
}

function buildActionModernFailureBpmnXml(): string {
  const payloadJson = xmlAttr(JSON.stringify({ title: '流程任务', message: '现代失败目标' }));
  const idempotencyKey = xmlAttr(`${ACTION_MODERN_FAILURE_BUSINESS_KEY}:create_task_action:CREATE_TASK`);
  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             xmlns:smart="http://smartengine.org/schema/process"
             targetNamespace="http://auraboot.com/bpm">
  <process id="${ACTION_MODERN_FAILURE_PROCESS_KEY}" name="${xmlAttr(ACTION_MODERN_FAILURE_PROCESS_NAME)}" isExecutable="true">
    <startEvent id="start"/>
    <sequenceFlow id="f_start_task" sourceRef="start" targetRef="create_task_action"/>
    <serviceTask id="create_task_action" name="创建任务"
                 smart:class="pluginActionServiceTaskDelegate"
                 smart:action="CREATE_TASK"
                 smart:target="abc"
                 smart:payloadJson="${payloadJson}"
                 smart:ruleCode="bpm-action-modern-failure-e2e"
                 smart:resultVar="taskResult"
                 smart:idempotencyKey="${idempotencyKey}"/>
    <sequenceFlow id="f_task_end" sourceRef="create_task_action" targetRef="end"/>
    <endEvent id="end"/>
  </process>
</definitions>`;
}

function buildActionModernFailureDesignerJson(): string {
  return JSON.stringify({
    nodes: [
      {
        id: 'start',
        type: 'startEvent',
        position: { x: 100, y: 220 },
        data: { type: 'startEvent', label: '开始' },
      },
      {
        id: 'create_task_action',
        type: 'serviceTask',
        position: { x: 330, y: 220 },
        data: {
          type: 'serviceTask',
          label: '创建任务',
          config: {
            actionType: 'CREATE_TASK',
            actionTarget: 'abc',
            actionPayload: { title: '流程任务', message: '现代失败目标' },
            actionResultVar: 'taskResult',
            actionIdempotencyKey: `${ACTION_MODERN_FAILURE_BUSINESS_KEY}:create_task_action:CREATE_TASK`,
          },
        },
      },
      {
        id: 'end',
        type: 'endEvent',
        position: { x: 600, y: 220 },
        data: { type: 'endEvent', label: '结束' },
      },
    ],
    edges: [
      { id: 'f_start_task', source: 'start', target: 'create_task_action', type: 'smoothstep' },
      { id: 'f_task_end', source: 'create_task_action', target: 'end', type: 'smoothstep' },
    ],
  });
}

async function createAndDeployProcess(request: APIRequestContext): Promise<void> {
  const createBody = await readApi<{ pid?: string; id?: string }>(
    await request.post('/api/bpm/process-definitions', {
      headers: authHeaders(adminToken),
      data: {
        processKey: PROCESS_KEY,
        processName: PROCESS_NAME,
        description: 'E2E BPM fail-closed rule binding fixture',
        category: 'e2e-test',
        bpmnContent: buildBpmnXml(),
        designerJson: buildDesignerJson(),
      },
    }),
  );
  processPid = String(createBody?.pid ?? createBody?.id ?? '');
  expect(processPid, 'process definition create must return pid').toBeTruthy();

  await readApi<unknown>(
    await request.post(`/api/bpm/process-definitions/${processPid}/deploy`, {
      headers: authHeaders(adminToken),
    }),
  );
}

async function createAndDeployActionProcess(request: APIRequestContext): Promise<void> {
  const createBody = await readApi<{ pid?: string; id?: string }>(
    await request.post('/api/bpm/process-definitions', {
      headers: authHeaders(adminToken),
      data: {
        processKey: ACTION_PROCESS_KEY,
        processName: ACTION_PROCESS_NAME,
        description: 'E2E BPM serviceTask action provider failure fixture',
        category: 'e2e-test',
        bpmnContent: buildActionBpmnXml(),
        designerJson: buildActionDesignerJson(),
      },
    }),
  );
  actionProcessPid = String(createBody?.pid ?? createBody?.id ?? '');
  expect(actionProcessPid, 'action process definition create must return pid').toBeTruthy();

  await readApi<unknown>(
    await request.post(`/api/bpm/process-definitions/${actionProcessPid}/deploy`, {
      headers: authHeaders(adminToken),
    }),
  );
}

async function createAndDeployActionSuccessProcess(request: APIRequestContext): Promise<void> {
  const createBody = await readApi<{ pid?: string; id?: string }>(
    await request.post('/api/bpm/process-definitions', {
      headers: authHeaders(adminToken),
      data: {
        processKey: ACTION_SUCCESS_PROCESS_KEY,
        processName: ACTION_SUCCESS_PROCESS_NAME,
        description: 'E2E BPM serviceTask action success trace fixture',
        category: 'e2e-test',
        bpmnContent: buildActionSuccessBpmnXml(),
        designerJson: buildActionSuccessDesignerJson(),
      },
    }),
  );
  actionSuccessProcessPid = String(createBody?.pid ?? createBody?.id ?? '');
  expect(actionSuccessProcessPid, 'action success process definition create must return pid').toBeTruthy();

  await readApi<unknown>(
    await request.post(`/api/bpm/process-definitions/${actionSuccessProcessPid}/deploy`, {
      headers: authHeaders(adminToken),
    }),
  );
}

async function createAndDeployActionModernFailureProcess(request: APIRequestContext): Promise<void> {
  const createBody = await readApi<{ pid?: string; id?: string }>(
    await request.post('/api/bpm/process-definitions', {
      headers: authHeaders(adminToken),
      data: {
        processKey: ACTION_MODERN_FAILURE_PROCESS_KEY,
        processName: ACTION_MODERN_FAILURE_PROCESS_NAME,
        description: 'E2E BPM serviceTask modern action failure trace fixture',
        category: 'e2e-test',
        bpmnContent: buildActionModernFailureBpmnXml(),
        designerJson: buildActionModernFailureDesignerJson(),
      },
    }),
  );
  actionModernFailureProcessPid = String(createBody?.pid ?? createBody?.id ?? '');
  expect(actionModernFailureProcessPid, 'action modern failure process definition create must return pid')
    .toBeTruthy();

  await readApi<unknown>(
    await request.post(`/api/bpm/process-definitions/${actionModernFailureProcessPid}/deploy`, {
      headers: authHeaders(adminToken),
    }),
  );
}

async function startActionFailureProcess(request: APIRequestContext): Promise<void> {
  const response = await request.post('/api/bpm/process-instances', {
    headers: authHeaders(adminToken),
    data: {
      processDefinitionId: ACTION_PROCESS_KEY,
      businessKey: ACTION_BUSINESS_KEY,
      variables: { recordPid: ACTION_BUSINESS_KEY },
    },
  });
  const body = (await response.json().catch(async () => ({
    message: await response.text().catch(() => ''),
  }))) as ApiEnvelope<Record<string, unknown>>;

  if (response.ok() && isApiSuccess(body) && body.data) {
    actionInstanceId = String(
      body.data.processInstanceId ?? body.data.instanceId ?? body.data.id ?? '',
    );
  } else {
    expect(
      JSON.stringify(body),
      'SEND_SMS without a real provider should fail closed instead of silently completing',
    ).toMatch(/SEND_SMS|sms|provider|bpm\.action\.action_failed|No real SMS/i);
  }

  if (!actionInstanceId) {
    await expect
      .poll(
        async () => {
          const status = await queryInstanceStatus(request, adminToken, {
            processKey: ACTION_PROCESS_KEY,
            businessKey: ACTION_BUSINESS_KEY,
          }).catch(() => null);
          return status?.instanceId ?? '';
        },
        {
          timeout: 15_000,
          message: 'failed action process instance should remain queryable by business key',
        },
      )
      .not.toBe('');
    const status = await queryInstanceStatus(request, adminToken, {
      processKey: ACTION_PROCESS_KEY,
      businessKey: ACTION_BUSINESS_KEY,
    });
    actionInstanceId = status.instanceId;
  }
}

async function startActionModernFailureProcess(request: APIRequestContext): Promise<void> {
  const response = await request.post('/api/bpm/process-instances', {
    headers: authHeaders(adminToken),
    data: {
      processDefinitionId: ACTION_MODERN_FAILURE_PROCESS_KEY,
      businessKey: ACTION_MODERN_FAILURE_BUSINESS_KEY,
      variables: { recordPid: ACTION_MODERN_FAILURE_BUSINESS_KEY },
    },
  });
  const body = (await response.json().catch(async () => ({
    message: await response.text().catch(() => ''),
  }))) as ApiEnvelope<Record<string, unknown>>;

  if (response.ok() && isApiSuccess(body) && body.data) {
    actionModernFailureInstanceId = String(
      body.data.processInstanceId ?? body.data.instanceId ?? body.data.id ?? '',
    );
  } else {
    expect(
      JSON.stringify(body),
      'CREATE_TASK invalid target should fail closed instead of silently completing',
    ).toMatch(/CREATE_TASK|target|bpm\.action\.action_failed/i);
  }

  if (!actionModernFailureInstanceId) {
    await expect
      .poll(
        async () => {
          const status = await queryInstanceStatus(request, adminToken, {
            processKey: ACTION_MODERN_FAILURE_PROCESS_KEY,
            businessKey: ACTION_MODERN_FAILURE_BUSINESS_KEY,
          }).catch(() => null);
          return status?.instanceId ?? '';
        },
        {
          timeout: 15_000,
          message: 'modern failed action process instance should remain queryable by business key',
        },
      )
      .not.toBe('');
    const status = await queryInstanceStatus(request, adminToken, {
      processKey: ACTION_MODERN_FAILURE_PROCESS_KEY,
      businessKey: ACTION_MODERN_FAILURE_BUSINESS_KEY,
    });
    actionModernFailureInstanceId = status.instanceId;
  }
}

test.beforeAll(async ({ request }) => {
  adminToken = await loginApiAsAdmin(request);

  const me = await readApi<{ user?: { id?: string; pid?: string } }>(
    await request.get('/api/auth/me', { headers: authHeaders(adminToken) }),
  );
  currentUserId = String(me?.user?.id ?? '');
  expect(currentUserId, `current user id must be available: ${JSON.stringify(me)}`).toMatch(/^\d+$/);

  await createAndDeployProcess(request);

  const started = await startProcessInstance(request, adminToken, {
    processDefinitionId: PROCESS_KEY,
    businessKey: BUSINESS_KEY,
    variables: { amount: 20000 },
  });
  instanceId = started.instanceId;
  expect(instanceId, 'process instance id must be returned').toBeTruthy();

  await expect
    .poll(
      async () => {
        const timeline = await listExecutionTimeline(request, adminToken, instanceId);
        return timeline.some((entry) => {
          const ruleBinding = entry.outputData?.ruleBinding as Record<string, unknown> | undefined;
          return entry.nodeId === 'approve' && ruleBinding?.status === 'ERROR';
        });
      },
      {
        timeout: 15_000,
        message: 'rule_evaluated fail-closed trace should be written for approve node',
      },
    )
    .toBe(true);

  await createAndDeployActionProcess(request);
  await startActionFailureProcess(request);

  await expect
    .poll(
      async () => {
        if (!actionInstanceId) return false;
        const timeline = await listExecutionTimeline(request, adminToken, actionInstanceId);
        return timeline.some((entry) => {
          const action = entry.inputData?.action as Record<string, unknown> | undefined;
          return entry.nodeId === 'sms_action'
            && entry.eventType === 'node_failure'
            && action?.actionType === 'SEND_SMS'
            && action?.status === 'FAILED';
        });
      },
      {
        timeout: 15_000,
        message: 'node_failure action trace should be written for sms_action node',
      },
    )
    .toBe(true);

  await createAndDeployActionSuccessProcess(request);
  const actionSuccessStarted = await startProcessInstance(request, adminToken, {
    processDefinitionId: ACTION_SUCCESS_PROCESS_KEY,
    businessKey: ACTION_SUCCESS_BUSINESS_KEY,
    variables: { recordPid: ACTION_SUCCESS_BUSINESS_KEY },
  });
  actionSuccessInstanceId = actionSuccessStarted.instanceId;
  expect(actionSuccessInstanceId, 'action success process instance id must be returned').toBeTruthy();

  await expect
    .poll(
      async () => {
        const timeline = await listExecutionTimeline(request, adminToken, actionSuccessInstanceId);
        return timeline.some((entry) => {
          const action = entry.outputData?.action as Record<string, unknown> | undefined;
          return entry.nodeId === 'send_im_action'
            && entry.eventType === 'action_executed'
            && action?.actionType === 'SEND_IM'
            && action?.status === 'SUCCESS';
        });
      },
      {
        timeout: 15_000,
        message: 'action_executed trace should be written for send_im_action node',
      },
    )
    .toBe(true);

  await createAndDeployActionModernFailureProcess(request);
  await startActionModernFailureProcess(request);

  await expect
    .poll(
      async () => {
        if (!actionModernFailureInstanceId) return false;
        const timeline = await listExecutionTimeline(request, adminToken, actionModernFailureInstanceId);
        return timeline.some((entry) => {
          const action = entry.inputData?.action as Record<string, unknown> | undefined;
          return entry.nodeId === 'create_task_action'
            && entry.eventType === 'node_failure'
            && action?.actionType === 'CREATE_TASK'
            && action?.status === 'FAILED'
            && action?.failureReason === 'target_invalid';
        });
      },
      {
        timeout: 15_000,
        message: 'node_failure action trace should be written for create_task_action node',
      },
    )
    .toBe(true);
});

test('BPM userTask fail-closed blocks static assignee and renders localized Rule Trace', async ({
  page,
  request,
  baseURL,
}, testInfo) => {
  test.setTimeout(90_000);

  const status = await queryInstanceStatus(request, adminToken, {
    processKey: PROCESS_KEY,
    businessKey: BUSINESS_KEY,
  });
  expect(status.currentNodes.map((node) => node.nodeId)).toContain('approve');

  const todos = await listTodoTasks(request, adminToken);
  expect(
    todos.some((task) => task.processInstanceId === instanceId || task.businessKey === BUSINESS_KEY),
    'static fallback assignee is the current admin; fail-closed must keep this task out of admin todo',
  ).toBe(false);

  const timeline = await listExecutionTimeline(request, adminToken, instanceId);
  const trace = timeline
    .filter((entry) => entry.nodeId === 'approve')
    .map((entry) => entry.outputData?.ruleBinding as Record<string, unknown> | undefined)
    .find(Boolean);
  expect(trace, 'backend timeline must retain ruleBinding audit payload').toBeTruthy();
  expect(trace).toMatchObject({
    decisionCode: MISSING_DECISION_CODE,
    consumerType: 'BPM',
    consumerNodeId: 'approve',
    status: 'ERROR',
    matched: false,
    fallbackApplied: true,
    errorCode: 'DECISION_EVALUATION_FAILED',
  });
  expect(trace?.outputs).toEqual({});

  await loginWebAsAdmin(page, baseURL ?? BASE_URL);
  await page.goto(`/bpm/process-status?processInstanceId=${encodeURIComponent(instanceId)}`, {
    waitUntil: 'domcontentloaded',
  });

  await expect(page.getByRole('heading', { name: /流程状态|Process Status/i })).toBeVisible({
    timeout: 15_000,
  });
  await expect(page.getByTestId('bpm-process-status-rule-trace')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByTestId('bpm-rule-trace-panel')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByTestId('bpm-rule-trace-item-approve')).toBeVisible({ timeout: 15_000 });
  await expect(
    page.getByTestId('bpm-rule-trace-status').filter({ hasText: '失败关闭' }).first(),
  ).toBeVisible();
  await expect(page.getByTestId('bpm-rule-trace-fail-closed')).toContainText(
    '阻断候选审批人分配',
  );
  await expect(page.getByTestId('bpm-rule-trace-output')).toHaveCount(0);

  await expect(page.locator('body')).not.toContainText(/DECISION_EVALUATION_FAILED/);
  await expect(page.locator('body')).not.toContainText(/No version found for decision/);
  await expect(page.locator('body')).not.toContainText(/static-fallback-user/);
  await expect(page.locator('body')).not.toContainText(/candidateUserIds|candidateGroupIds/);

  await page.screenshot({
    path: testInfo.outputPath('bpm-rule-binding-fail-closed-process-status.png'),
    fullPage: true,
  });
});

test('BPM serviceTask action provider failure renders productized ProcessStatus trace', async ({
  page,
  request,
  baseURL,
}, testInfo) => {
  test.setTimeout(90_000);

  expect(actionInstanceId, 'action failure process instance id must be captured').toBeTruthy();

  const timeline = await listExecutionTimeline(request, adminToken, actionInstanceId);
  const actionTrace = timeline
    .filter((entry) => entry.nodeId === 'sms_action' && entry.eventType === 'node_failure')
    .map((entry) => entry.inputData?.action as Record<string, unknown> | undefined)
    .find(Boolean);
  expect(actionTrace, 'backend timeline must retain structured action failure payload').toBeTruthy();
  expect(actionTrace).toMatchObject({
    status: 'FAILED',
    actionType: 'SEND_SMS',
    channel: 'sms',
    sentCount: 0,
  });
  expect(actionTrace?.targetPhones).toEqual(['+8613800138000']);

  await loginWebAsAdmin(page, baseURL ?? BASE_URL);
  await page.goto(`/bpm/process-status?processInstanceId=${encodeURIComponent(actionInstanceId)}`, {
    waitUntil: 'domcontentloaded',
  });

  await expect(page.getByRole('heading', { name: /流程状态|Process Status/i })).toBeVisible({
    timeout: 15_000,
  });
  await expect(page.getByTestId('bpm-process-status-rule-trace')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByTestId('bpm-rule-trace-panel')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByTestId('bpm-action-trace-item-sms_action')).toBeVisible({
    timeout: 15_000,
  });
  await expect(page.getByTestId('bpm-action-trace-title')).toContainText('发送短信');
  await expect(page.getByTestId('bpm-action-trace-status')).toContainText('动作失败');
  await expect(page.getByTestId('bpm-action-trace-summary')).toContainText('流程已失败关闭');
  await expect(page.getByTestId('bpm-action-trace-field').filter({ hasText: '通道=短信' }).first())
    .toBeVisible();
  await expect(
    page.getByTestId('bpm-action-trace-field').filter({ hasText: '目标手机号=+8613800138000' }).first(),
  ).toBeVisible();
  await expect(page.getByTestId('bpm-action-trace-field').filter({ hasText: '发送数量=0' }).first())
    .toBeVisible();

  await expect(page.locator('body')).not.toContainText(/provider_unavailable/);
  await expect(page.locator('body')).not.toContainText(/No real SMS sender available/);
  await expect(page.locator('body')).not.toContainText(/bpm\.action\.action_failed/);

  await page.screenshot({
    path: testInfo.outputPath('bpm-service-task-action-provider-failure-process-status.png'),
    fullPage: true,
  });
});

test('BPM serviceTask action success renders productized ProcessStatus trace', async ({
  page,
  request,
  baseURL,
}, testInfo) => {
  test.setTimeout(90_000);

  expect(actionSuccessInstanceId, 'action success process instance id must be captured').toBeTruthy();

  const timeline = await listExecutionTimeline(request, adminToken, actionSuccessInstanceId);
  const actionTrace = timeline
    .filter((entry) => entry.nodeId === 'send_im_action' && entry.eventType === 'action_executed')
    .map((entry) => entry.outputData?.action as Record<string, unknown> | undefined)
    .find(Boolean);
  expect(actionTrace, 'backend timeline must retain structured action success payload').toBeTruthy();
  expect(actionTrace).toMatchObject({
    status: 'SUCCESS',
    actionType: 'SEND_IM',
    channel: 'im',
    sentCount: 1,
  });
  expect(Array.isArray(actionTrace?.messageIds)).toBe(true);
  expect((actionTrace?.messageIds as unknown[]).length).toBeGreaterThan(0);

  await loginWebAsAdmin(page, baseURL ?? BASE_URL);
  await page.goto(`/bpm/process-status?processInstanceId=${encodeURIComponent(actionSuccessInstanceId)}`, {
    waitUntil: 'domcontentloaded',
  });

  await expect(page.getByRole('heading', { name: /流程状态|Process Status/i })).toBeVisible({
    timeout: 15_000,
  });
  await expect(page.getByTestId('bpm-process-status-rule-trace')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByTestId('bpm-rule-trace-panel')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByTestId('bpm-action-trace-item-send_im_action')).toBeVisible({
    timeout: 15_000,
  });
  await expect(page.getByTestId('bpm-action-trace-title')).toContainText('发送 IM 消息');
  await expect(page.getByTestId('bpm-action-trace-status')).toContainText('动作成功');
  await expect(page.getByTestId('bpm-action-trace-summary')).toContainText('流程已继续推进');
  await expect(page.getByTestId('bpm-action-trace-field').filter({ hasText: '通道=IM' }).first())
    .toBeVisible();
  await expect(page.getByTestId('bpm-action-trace-field').filter({ hasText: '发送数量=1' }).first())
    .toBeVisible();
  await expect(page.getByTestId('bpm-action-trace-field').filter({ hasText: '消息=' }).first())
    .toBeVisible();

  await expect(page.locator('body')).not.toContainText(/SEND_IM/);
  await expect(page.locator('body')).not.toContainText(/SUCCESS/);
  await expect(page.locator('body')).not.toContainText(/provider_unavailable/);

  await page.screenshot({
    path: testInfo.outputPath('bpm-service-task-action-success-process-status.png'),
    fullPage: true,
  });
});

test('BPM serviceTask modern action failure renders productized ProcessStatus trace', async ({
  page,
  request,
  baseURL,
}, testInfo) => {
  test.setTimeout(90_000);

  expect(
    actionModernFailureInstanceId,
    'action modern failure process instance id must be captured',
  ).toBeTruthy();

  const timeline = await listExecutionTimeline(request, adminToken, actionModernFailureInstanceId);
  const actionTrace = timeline
    .filter((entry) => entry.nodeId === 'create_task_action' && entry.eventType === 'node_failure')
    .map((entry) => entry.inputData?.action as Record<string, unknown> | undefined)
    .find(Boolean);
  expect(actionTrace, 'backend timeline must retain modern structured action failure payload').toBeTruthy();
  expect(actionTrace).toMatchObject({
    status: 'FAILED',
    actionType: 'CREATE_TASK',
    delivery: 'inbox',
    itemType: 'task',
    failureReason: 'target_invalid',
    targetType: 'UNKNOWN',
    target: 'abc',
    field: 'target',
  });

  await loginWebAsAdmin(page, baseURL ?? BASE_URL);
  await page.goto(`/bpm/process-status?processInstanceId=${encodeURIComponent(actionModernFailureInstanceId)}`, {
    waitUntil: 'domcontentloaded',
  });

  await expect(page.getByRole('heading', { name: /流程状态|Process Status/i })).toBeVisible({
    timeout: 15_000,
  });
  await expect(page.getByTestId('bpm-process-status-rule-trace')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByTestId('bpm-rule-trace-panel')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByTestId('bpm-action-trace-item-create_task_action')).toBeVisible({
    timeout: 15_000,
  });
  await expect(page.getByTestId('bpm-action-trace-title')).toContainText('创建任务');
  await expect(page.getByTestId('bpm-action-trace-status')).toContainText('动作失败');
  await expect(page.getByTestId('bpm-action-trace-summary')).toContainText('流程已失败关闭');
  await expect(page.getByTestId('bpm-action-trace-field').filter({ hasText: '失败原因=目标格式无效' }).first())
    .toBeVisible();
  await expect(page.getByTestId('bpm-action-trace-field').filter({ hasText: '投递方式=待办' }).first())
    .toBeVisible();
  await expect(page.getByTestId('bpm-action-trace-field').filter({ hasText: '待办类型=待办任务' }).first())
    .toBeVisible();
  await expect(page.getByTestId('bpm-action-trace-field').filter({ hasText: '接收类型=未识别' }).first())
    .toBeVisible();
  await expect(page.getByTestId('bpm-action-trace-field').filter({ hasText: '字段=动作目标' }).first())
    .toBeVisible();

  await expect(page.locator('body')).not.toContainText(/target_invalid/);
  await expect(page.locator('body')).not.toContainText(/CREATE_TASK target must be/);
  await expect(page.locator('body')).not.toContainText(/bpm\.action\.action_failed/);

  await page.screenshot({
    path: testInfo.outputPath('bpm-service-task-modern-action-failure-process-status.png'),
    fullPage: true,
  });
});
