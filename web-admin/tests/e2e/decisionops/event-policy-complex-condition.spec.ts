import { test, expect, type APIResponse, type Page } from '@playwright/test';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';
import { loginViaUI } from '../../helpers/wd-fixtures';
import { ensureSidebarExpanded, uniqueId, waitForDynamicPageLoad } from '../helpers';

type JsonResponseLike = Pick<APIResponse, 'json' | 'text' | 'ok' | 'status' | 'url'>;

type ApiEnvelope<T> = {
  code?: number | string;
  success?: boolean;
  message?: string;
  msg?: string;
  data?: T;
};

type EventPolicyVersion = {
  pid: string;
  status?: string;
  version?: number;
  rulesJson?: unknown;
  rules_json?: unknown;
};

type DecisionVersion = {
  pid: string;
  status?: string;
  version?: number;
};

type FieldImpact = {
  fieldRef?: string;
  references?: Array<{
    sourceType?: string;
    sourceCode?: string;
    sourcePid?: string;
    binding?: string;
  }>;
};

type EventPolicyRunResult = {
  status?: string;
  matchedRuleCodes?: string[];
  actionPlans?: unknown[];
  correlationId?: string;
  decisionTraceIds?: string[];
};

type EventPolicyActionExecution = {
  ruleCode?: string;
  type?: string;
  idempotencyKey?: string;
  status?: string;
  error?: string | null;
  resultPayload?: Record<string, unknown>;
};

type EventPolicyExecutionResult = {
  policy?: EventPolicyRunResult;
  execution?: {
    policyCode?: string;
    overallStatus?: string;
    actions?: EventPolicyActionExecution[];
  };
};

type ActionLogRecord = {
  pid?: string;
  idempotencyKey?: string;
  policyCode?: string;
  decisionTraceId?: string;
  correlationId?: string;
  ruleCode?: string;
  actionType?: string;
  status?: string;
  failureStrategy?: string;
  errorMessage?: string | null;
  resultPayload?: Record<string, unknown>;
  actionPayload?: Record<string, unknown>;
  contextPayload?: Record<string, unknown>;
  attemptCount?: number;
  maxAttempts?: number;
  nextRetryAt?: string | null;
  lastRetryAt?: string | null;
  deadLetteredAt?: string | null;
};

type WebhookRecord = {
  pid?: string;
  eventType?: string;
  event_type?: string;
};

type WebhookDeliveryRecord = {
  pid?: string;
  subscriptionPid?: string;
  subscription_pid?: string;
  eventId?: string;
  event_id?: string;
  requestBody?: string;
  request_body?: string;
  deliveryStatus?: string;
  delivery_status?: string;
  errorMessage?: string;
  error_message?: string;
};

type CurrentUserInfo = {
  user?: {
    id?: string;
    email?: string;
  };
};

type UserOption = {
  pid?: string;
  id?: string;
  displayName?: string;
  name?: string;
  realName?: string;
  nickName?: string;
  nickname?: string;
  username?: string;
  userName?: string;
  email?: string;
};

type ImMessageRecord = {
  id?: number;
  pid?: string;
  conversationId?: number;
  senderType?: string;
  type?: string;
  content?: string;
  cardPayload?: unknown;
};

type PageRecords<T> = {
  records?: T[];
  total?: number;
};

type InboxItemRecord = {
  id?: number;
  itemType?: string;
  title?: string;
  summary?: string;
  sourceType?: string;
  sourceId?: string;
  sourceModel?: string;
  sourceRecordPid?: string;
  deepLink?: string;
  cardData?: Record<string, unknown>;
  clientItemId?: string;
};

type BpmProcessDefinitionRecord = {
  pid?: string;
  processKey?: string;
  processName?: string;
  status?: string;
};

type BpmProcessStatusRecord = {
  instanceId?: string;
  processDefinitionId?: string;
  status?: string;
  variables?: Record<string, unknown>;
};

test.use({ storageState: { cookies: [], origins: [] } });
test.setTimeout(120_000);

function isApiSuccess<T>(body: ApiEnvelope<T> | null | undefined): body is ApiEnvelope<T> {
  if (!body) return false;
  if (body.success === false) return false;
  const code = body.code;
  return code === undefined || code === null || String(code) === '0';
}

async function readApi<T>(response: JsonResponseLike): Promise<T> {
  const body = (await response.json().catch(async () => ({
    message: await response.text().catch(() => ''),
  }))) as ApiEnvelope<T>;
  expect(
    response.ok(),
    `HTTP ${response.status()} ${response.url()}: ${JSON.stringify(body)}`,
  ).toBe(true);
  expect(isApiSuccess(body), `API failed: ${JSON.stringify(body)}`).toBe(true);
  return body.data as T;
}

async function postApi<T>(page: Page, endpoint: string, data?: unknown): Promise<T> {
  const options = data === undefined ? undefined : { data };
  return readApi<T>(await page.request.post(endpoint, options));
}

function pageRecordRows<T>(value: PageRecords<T> | T[] | null | undefined): T[] {
  if (Array.isArray(value)) return value;
  return value?.records ?? [];
}

function userLabel(user: UserOption): string {
  return String(
    user.displayName ??
      user.realName ??
      user.nickName ??
      user.nickname ??
      user.name ??
      user.username ??
      user.userName ??
      user.email ??
      user.pid ??
      user.id ??
      '',
  );
}

async function resolveFirstUser(page: Page): Promise<{ pid: string; label: string }> {
  const payload = await readApi<PageRecords<UserOption> | UserOption[]>(
    await page.request.get('/api/admin/users/search', {
      params: {
        keyword: '',
        page: 1,
        size: 20,
      },
    }),
  );
  const users = pageRecordRows(payload);
  const match =
    users.find((user) => String(user.email ?? '').toLowerCase() === DEFAULT_TEST_ACCOUNT.email) ??
    users.find((user) => !userLabel(user).startsWith('Agent:') && (user.pid || user.id)) ??
    users.find((user) => user.pid || user.id);
  expect(match, 'Expected at least one tenant user for EventPolicy applicant trace').toBeTruthy();
  const pid = String(match?.pid ?? match?.id ?? '');
  const label = userLabel(match as UserOption);
  expect(pid).toBeTruthy();
  expect(label).toBeTruthy();
  return { pid, label };
}

async function createAndPublishApplicantDecision(
  page: Page,
  decisionCode: string,
  applicantPid: string,
): Promise<DecisionVersion> {
  await postApi(page, '/api/decision/definitions', {
    decisionCode,
    decisionName: `EventPolicy Applicant ${decisionCode}`,
    description: 'E2E decision verifies EventPolicy can reuse applicant reference conditions',
    scopeType: 'EVENT_POLICY',
    ownerModule: 'decision',
    enabled: true,
  });

  const draft = await postApi<DecisionVersion>(
    page,
    `/api/decision/definitions/${encodeURIComponent(decisionCode)}/versions`,
    {
      kind: 'SIMPLE_CONDITION',
      runtimeAdapter: 'AST_EVALUATOR',
      versionTag: `event-policy-applicant-${Date.now()}`,
      contentJson: {
        type: 'compare',
        left: {
          type: 'path',
          scope: 'record',
          path: 'data.wd_req_applicant',
          dataType: 'user',
        },
        operator: 'EQ',
        right: {
          type: 'literal',
          value: applicantPid,
          dataType: 'user',
        },
      },
    },
  );
  expect(draft.pid).toBeTruthy();

  const validation = await postApi<{ valid?: boolean }>(
    page,
    `/api/decision/versions/${encodeURIComponent(draft.pid)}/validate`,
  );
  expect(validation.valid).toBe(true);

  return postApi<DecisionVersion>(
    page,
    `/api/decision/versions/${encodeURIComponent(draft.pid)}/publish`,
  );
}

async function openEventPolicyListFromSidebar(page: Page): Promise<void> {
  const nav = page.locator('nav, aside, [role="navigation"]').first();
  if (!(await nav.isVisible({ timeout: 1000 }).catch(() => false))) {
    await page.goto('/home', { waitUntil: 'domcontentloaded' });
  }
  await ensureSidebarExpanded(page);
  const parent = nav
    .getByRole('button', { name: /决策中心|DecisionOps/i })
    .or(nav.getByRole('link', { name: /决策中心|DecisionOps/i }))
    .first();
  const eventPolicyLink = nav
    .locator('a[href="/p/decisionops_event_policies"]')
    .or(nav.getByRole('link', { name: /Event Policy/i }))
    .first();
  if (!(await eventPolicyLink.isVisible({ timeout: 1000 }).catch(() => false))) {
    await expect(parent).toBeVisible({ timeout: 10_000 });
    await parent.click();
  }
  await expect(eventPolicyLink).toBeVisible({ timeout: 10_000 });
  await eventPolicyLink.click();
  await expect(page).toHaveURL(/\/p\/decisionops_event_policies(?:$|\?)/, { timeout: 15_000 });
  await waitForDynamicPageLoad(page);
}

function parseRulesJson(value: unknown): unknown[] {
  if (typeof value === 'string') {
    const parsed = JSON.parse(value) as unknown;
    return Array.isArray(parsed) ? parsed : [];
  }
  return Array.isArray(value) ? value : [];
}

function eventPolicyContext(data: Record<string, unknown>) {
  return {
    record: {
      entityCode: 'complaint',
      recordId: uniqueId('cmp'),
      data,
    },
  };
}

function startProcessBpmn(processKey: string): string {
  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:smart="http://smart.alibaba.com"
             targetNamespace="http://auraboot.com/bpm">
  <process id="${processKey}" name="EventPolicy Start Process" isExecutable="true">
    <startEvent id="start" name="开始"/>
    <sequenceFlow id="f1" sourceRef="start" targetRef="review"/>
    <userTask id="review" name="策略审批" smart:assigneeType="starter"/>
    <sequenceFlow id="f2" sourceRef="review" targetRef="end"/>
    <endEvent id="end" name="结束"/>
  </process>
</definitions>`;
}

async function createAndDeployStartProcess(
  page: Page,
  processKey: string,
): Promise<BpmProcessDefinitionRecord> {
  const process = await readApi<BpmProcessDefinitionRecord>(
    await page.request.post('/api/bpm/process-definitions', {
      data: {
        processKey,
        processName: `EventPolicy Start Process ${processKey}`,
        description: 'EventPolicy START_PROCESS golden fixture',
        category: 'e2e-test',
        bpmnContent: startProcessBpmn(processKey),
      },
    }),
  );
  expect(process.pid, `create process must return pid: ${JSON.stringify(process)}`).toBeTruthy();
  expect(process.processKey).toBe(processKey);
  const deployed = await readApi<BpmProcessDefinitionRecord>(
    await page.request.post(`/api/bpm/process-definitions/${process.pid}/deploy`),
  );
  expect(deployed.processKey).toBe(processKey);
  expect(String(deployed.status ?? '')).toMatch(/deployed/i);
  return deployed;
}

test('EventPolicy designer persists complex AND/OR/NOT conditions and backend runtime honors them @golden', async ({
  page,
}, testInfo) => {
  await loginViaUI(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
  await expect(page).not.toHaveURL(/\/login(?:$|\?)/);
  const currentUser = await readApi<CurrentUserInfo>(await page.request.get('/api/auth/me'));
  const notifyRecipientId = currentUser.user?.id;
  if (!notifyRecipientId) {
    throw new Error(
      `Current user id is required for NOTIFY action target: ${JSON.stringify(currentUser)}`,
    );
  }
  expect(notifyRecipientId).toMatch(/^\d+$/);

  const suffix = uniqueId('ep_complex').replace(/[^a-zA-Z0-9_]/g, '_');
  const policyCode = `codex_ep_complex_${suffix}`;
  const targetKey = `complaint_${suffix}`;

  await openEventPolicyListFromSidebar(page);
  await expect(page.getByTestId('event-policy-actions-block')).toBeVisible({ timeout: 15_000 });
  await page.getByTestId('epa-new-policy').click();
  await expect(page.getByTestId('epa-editor')).toBeVisible({ timeout: 10_000 });
  await page.getByLabel('policy-code').fill(policyCode);
  await page.getByLabel('policy-name').fill(`Codex Complex Condition ${suffix}`);
  await page.getByLabel('policy-event-type').fill('FORM_SUBMITTED');
  await page.getByLabel('policy-target-type').fill('FORM');
  await page.getByLabel('policy-target-key').fill(targetKey);
  await page.getByTestId('epa-save-policy').click();
  await expect(page).toHaveURL(new RegExp(`/p/decisionops_event_policies/view/${policyCode}`), {
    timeout: 15_000,
  });

  await page.getByTestId('epa-open-designer').click();
  await expect(page).toHaveURL(/\/p\/decisionops_event_policy_designer\?policyCode=/, {
    timeout: 15_000,
  });
  await expect(page.getByTestId('event-policy-designer-block')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByTestId('epd-workflow')).toBeVisible();

  await page.getByTestId('epd-step-rules').click();
  await expect(page.getByTestId('policy-rules-editor')).toBeVisible();
  await page.getByTestId('cb-add').click();
  await expect(
    page.locator('select[aria-label="field-0"] option[value="record:data.priority"]'),
  ).toHaveCount(1, {
    timeout: 15_000,
  });
  await page.getByLabel('field-0').selectOption('record:data.priority');
  await page.getByLabel('operator-0').selectOption('EQ');
  await page.getByLabel('value-0').selectOption('HIGH');

  await page.getByTestId('cb-add-group').click();
  await page.getByTestId('op-or-1').click();
  await page.getByLabel('field-1-0').selectOption('record:data.amount');
  await page.getByLabel('operator-1-0').selectOption('GT');
  await page.getByLabel('value-1-0').fill('5000');
  await page.getByTestId('cb-add-1').click();
  await page.getByLabel('field-1-1').selectOption('record:data.status');
  await page.getByLabel('operator-1-1').selectOption('EQ');
  await page.getByLabel('value-1-1').fill('VIP');

  await page.getByTestId('cb-add-not').click();
  await expect(page.getByTestId('cb-not-2')).toBeVisible();
  await page.getByLabel('field-2-0').selectOption('record:data.status');
  await page.getByLabel('operator-2-0').selectOption('EQ');
  await page.getByLabel('value-2-0').fill('BLOCKED');
  await expect(page.getByTestId('cb-preview')).toContainText('并且');
  await expect(page.getByTestId('cb-preview')).toContainText('或');
  await expect(page.getByTestId('cb-preview')).toContainText('非');

  await page.getByTestId('epd-step-actions').click();
  await page.getByTestId('epd-add-action').click();
  await page.getByLabel('action-target-0').fill(`USER:${notifyRecipientId}`);
  await page.getByLabel('action-field-0-payload.title').fill('Policy notification');
  await page.getByLabel('action-field-0-payload.content').fill('complex_condition_alert');

  await page.screenshot({
    path: testInfo.outputPath('event-policy-complex-condition-configured.png'),
    fullPage: true,
  });

  await page.getByTestId('epd-step-publish').click();
  const draftResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes(`/api/event-policy/definitions/${policyCode}/versions`),
    { timeout: 15_000 },
  );
  await page.getByTestId('epd-save-draft').click();
  const draft = await readApi<EventPolicyVersion>(await draftResponsePromise);
  expect(draft.pid).toBeTruthy();
  await expect(page.getByTestId('epd-publish-status')).toContainText(/DRAFT|草稿/i, {
    timeout: 10_000,
  });

  const validateResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes(`/api/event-policy/versions/${draft.pid}/validate`),
    { timeout: 15_000 },
  );
  await page.getByTestId('epd-validate-version').click();
  await expect((await readApi<EventPolicyVersion>(await validateResponsePromise)).status).toBe(
    'VALIDATED',
  );
  await expect(page.getByTestId('epd-publish-status')).toContainText(/VALIDATED|已校验/i, {
    timeout: 10_000,
  });

  const publishResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes(`/api/event-policy/versions/${draft.pid}/publish`),
    { timeout: 15_000 },
  );
  await page.getByTestId('epd-publish-version').click();
  await expect((await readApi<EventPolicyVersion>(await publishResponsePromise)).status).toBe(
    'PUBLISHED',
  );
  await expect(page.getByTestId('epd-publish-status')).toContainText(/PUBLISHED|已发布/i, {
    timeout: 10_000,
  });

  await page.getByTestId('epd-step-test').click();
  const runButtonResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes('/api/event-policy/run-and-execute'),
    { timeout: 15_000 },
  );
  await page.getByTestId('epd-run-published').click();
  const execution = await readApi<EventPolicyExecutionResult>(await runButtonResponse);
  expect(execution.policy?.status).toBe('MATCHED');
  expect(execution.policy?.matchedRuleCodes).toContain('R-1');
  const correlationId = execution.policy?.correlationId;
  expect(
    correlationId,
    `run-and-execute must return correlationId: ${JSON.stringify(execution)}`,
  ).toMatch(/^ep-/);
  expect(execution.execution?.policyCode).toBe(policyCode);
  expect(execution.execution?.overallStatus).toBe('ALL_SUCCESS');
  expect(execution.execution?.actions).toHaveLength(1);
  expect(execution.execution?.actions?.[0]).toMatchObject({
    ruleCode: 'R-1',
    type: 'NOTIFY',
    status: 'SUCCESS',
  });
  expect(execution.execution?.actions?.[0]?.resultPayload).toMatchObject({
    channel: 'in_app',
    recipientType: 'USER',
    recipientId: notifyRecipientId,
    title: 'Policy notification',
    sourceId: 'R-1',
  });
  await expect(page.getByTestId('epd-run-result')).toBeVisible({ timeout: 10_000 });
  await expect(page.getByTestId('epd-run-result')).toContainText('已命中');
  await expect(page.getByTestId('epd-correlation-id')).toContainText(correlationId!);
  await expect(page.getByTestId('epd-action-execution-results')).toBeVisible({ timeout: 10_000 });
  await expect(page.getByTestId('epd-action-execution-0')).toContainText('发送站内通知');
  await expect(page.getByTestId('epd-action-execution-0')).toContainText('成功');
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText('通道');
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText('in_app');
  const eventPolicyTraceLink = page.getByTestId('epd-open-trace');
  await expect(eventPolicyTraceLink).toBeVisible({ timeout: 10_000 });
  const eventPolicyTraceHref = await eventPolicyTraceLink.getAttribute('href');
  expect(
    eventPolicyTraceHref,
    'EventPolicy run result should expose unified trace link',
  ).toBeTruthy();
  const eventPolicyTraceUrl = new URL(eventPolicyTraceHref!, 'http://127.0.0.1:5194');
  expect(eventPolicyTraceUrl.pathname).toBe('/p/decisionops_execution_logs');
  expect(eventPolicyTraceUrl.searchParams.get('policyCode')).toBe(policyCode);
  expect(eventPolicyTraceUrl.searchParams.get('correlationId')).toBe(correlationId);
  expect(eventPolicyTraceUrl.searchParams.get('callerType')).toBe('EVENT_POLICY');
  expect(eventPolicyTraceUrl.searchParams.get('callerRef')).toBe(policyCode);

  const actionLogs = await readApi<ActionLogRecord[]>(
    await page.request.get('/api/event-policy/action-logs', {
      params: { policyCode, correlationId: correlationId!, size: '20' },
    }),
  );
  expect(actionLogs).toHaveLength(1);
  expect(actionLogs[0]).toMatchObject({
    policyCode,
    correlationId,
    ruleCode: 'R-1',
    actionType: 'NOTIFY',
    status: 'SUCCESS',
    resultPayload: {
      channel: 'in_app',
      recipientType: 'USER',
      recipientId: notifyRecipientId,
      title: 'Policy notification',
      sourceId: 'R-1',
    },
  });

  const traceActionResponse = page.waitForResponse(
    (response) => {
      if (response.status() !== 200 || !response.url().includes('/api/event-policy/action-logs')) {
        return false;
      }
      const url = new URL(response.url());
      return (
        url.searchParams.get('policyCode') === policyCode &&
        url.searchParams.get('correlationId') === correlationId
      );
    },
    { timeout: 20_000 },
  );
  await eventPolicyTraceLink.click();
  await expect(page).toHaveURL(/\/p\/decisionops_execution_logs/);
  await readApi<ActionLogRecord[]>(await traceActionResponse);
  await expect(page.getByTestId('execution-log-trace-block')).toBeVisible({ timeout: 20_000 });
  await expect(page.getByLabel('log-caller-type')).toHaveValue('EVENT_POLICY');
  await expect(page.getByLabel('log-keyword')).toHaveValue(policyCode);
  const linkedActionEvidence = page.getByTestId('elta-linked-action-evidence');
  await expect(linkedActionEvidence).toBeVisible({ timeout: 20_000 });
  await expect(linkedActionEvidence).toContainText('动作执行证据');
  await expect(linkedActionEvidence).toContainText('发送站内通知');
  await expect(linkedActionEvidence).toContainText(notifyRecipientId);
  await expect(linkedActionEvidence).toContainText('Policy notification');
  await expect(linkedActionEvidence).toContainText('in_app');
  await expect(linkedActionEvidence).toContainText('尝试 1/3');
  await expect(linkedActionEvidence).not.toContainText('重试 1/3');
  await expect(page.getByTestId('elta-open-event-policy-detail')).toHaveAttribute(
    'href',
    `/p/decisionops_event_policies/view/${encodeURIComponent(policyCode)}`,
  );
  await expect(page.getByTestId('elta-open-event-policy-designer')).toHaveAttribute(
    'href',
    `/p/decisionops_event_policy_designer?policyCode=${encodeURIComponent(policyCode)}`,
  );

  await page.screenshot({
    path: testInfo.outputPath('event-policy-run-unified-trace-action-evidence.png'),
    fullPage: true,
  });

  const versions = await readApi<EventPolicyVersion[]>(
    await page.request.get(`/api/event-policy/definitions/${policyCode}/versions`),
  );
  const latest = versions.find((version) => version.pid === draft.pid) ?? versions[0];
  const rules = parseRulesJson(latest.rulesJson ?? latest.rules_json);
  expect(rules[0]).toMatchObject({
    ruleCode: 'R-1',
    condition: {
      type: 'group',
      op: 'AND',
      children: [
        {
          type: 'compare',
          operator: 'EQ',
          left: { scope: 'record', path: 'data.priority' },
          right: { value: 'HIGH' },
        },
        {
          type: 'group',
          op: 'OR',
        },
        {
          type: 'not',
          child: {
            type: 'compare',
            operator: 'EQ',
            left: { scope: 'record', path: 'data.status' },
            right: { value: 'BLOCKED' },
          },
        },
      ],
    },
  });

  const matched = await readApi<EventPolicyRunResult>(
    await page.request.post('/api/event-policy/run', {
      data: {
        eventType: 'FORM_SUBMITTED',
        targetType: 'FORM',
        targetKey,
        context: eventPolicyContext({
          priority: 'HIGH',
          amount: 9000,
          status: 'OPEN',
        }),
      },
    }),
  );
  expect(matched.status).toBe('MATCHED');
  expect(matched.matchedRuleCodes).toContain('R-1');
  expect(matched.actionPlans?.length).toBe(1);

  const notMatched = await readApi<EventPolicyRunResult>(
    await page.request.post('/api/event-policy/run', {
      data: {
        eventType: 'FORM_SUBMITTED',
        targetType: 'FORM',
        targetKey,
        context: eventPolicyContext({
          priority: 'HIGH',
          amount: 9000,
          status: 'BLOCKED',
        }),
      },
    }),
  );
  expect(notMatched.status).toBe('NOT_MATCHED');
  expect(notMatched.matchedRuleCodes ?? []).toEqual([]);
  expect(notMatched.actionPlans ?? []).toEqual([]);

  await readApi(await page.request.post('/api/decision/usage-index/rebuild'));
  const priorityImpact = await readApi<FieldImpact>(
    await page.request.get('/api/decision/fields/impact', {
      params: { fieldRef: 'record.data.priority' },
    }),
  );
  expect(priorityImpact.fieldRef).toBe('record.data.priority');
  expect(priorityImpact.references ?? []).toEqual(
    expect.arrayContaining([
      expect.objectContaining({
        sourceType: 'EVENT_POLICY',
        sourceCode: policyCode,
        sourcePid: draft.pid,
        binding: 'VERSION_RULES',
      }),
    ]),
  );

  await page.screenshot({
    path: testInfo.outputPath('event-policy-complex-condition-published.png'),
    fullPage: true,
  });

  await page.getByTestId('elta-open-event-policy-detail').click();
  await expect(page).toHaveURL(
    new RegExp(`/p/decisionops_event_policies/view/${encodeURIComponent(policyCode)}`),
    { timeout: 15_000 },
  );
  await expect(page.getByTestId('event-policy-actions-block')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByTestId('event-policy-actions-block')).toContainText('设计');
  const detailDesignerButton = page.getByTestId('epa-open-designer');
  await expect(detailDesignerButton).toBeVisible({ timeout: 10_000 });
  await page.screenshot({
    path: testInfo.outputPath('event-policy-trace-back-to-policy-detail.png'),
    fullPage: true,
  });
  await detailDesignerButton.click();
  await expect(page).toHaveURL(
    new RegExp(
      `/p/decisionops_event_policy_designer\\?policyCode=${encodeURIComponent(policyCode)}`,
    ),
    { timeout: 15_000 },
  );
  await expect(page.getByTestId('event-policy-designer-block')).toBeVisible({ timeout: 15_000 });
});

test('EventPolicy applicant user reference rule executes action and opens unified Trace fact metadata @golden', async ({
  page,
}, testInfo) => {
  await loginViaUI(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
  await expect(page).not.toHaveURL(/\/login(?:$|\?)/);
  const currentUser = await readApi<CurrentUserInfo>(await page.request.get('/api/auth/me'));
  const notifyRecipientId = currentUser.user?.id;
  if (!notifyRecipientId) {
    throw new Error(
      `Current user id is required for NOTIFY action target: ${JSON.stringify(currentUser)}`,
    );
  }
  expect(notifyRecipientId).toMatch(/^\d+$/);
  const user = await resolveFirstUser(page);

  const suffix = uniqueId('ep_applicant').replace(/[^a-zA-Z0-9_]/g, '_');
  const policyCode = `codex_ep_applicant_${suffix}`;
  const decisionCode = `ep_applicant_reference_${suffix}`;
  const eventType = `CODEX_LEAVE_APPLICANT_${suffix}`;
  const publishedDecision = await createAndPublishApplicantDecision(page, decisionCode, user.pid);
  expect(String(publishedDecision.status ?? '')).toMatch(/PUBLISHED|published/i);
  await postApi(page, '/api/decision/usage-index/rebuild');

  await openEventPolicyListFromSidebar(page);
  await expect(page.getByTestId('event-policy-actions-block')).toBeVisible({ timeout: 15_000 });
  await page.getByTestId('epa-new-policy').click();
  await expect(page.getByTestId('epa-editor')).toBeVisible({ timeout: 10_000 });
  await page.getByLabel('policy-code').fill(policyCode);
  await page.getByLabel('policy-name').fill(`Codex Applicant EventPolicy ${suffix}`);
  await page.getByTestId('epa-policy-event-type').fill(eventType);
  await page.getByTestId('epa-policy-target-type').fill('MODEL');
  await page.getByTestId('epa-policy-target-key').fill('wd_leave_request');
  await page.getByTestId('epa-save-policy').click();
  await expect(page).toHaveURL(new RegExp(`/p/decisionops_event_policies/view/${policyCode}`), {
    timeout: 15_000,
  });

  await page.getByTestId('epa-open-designer').click();
  await expect(page).toHaveURL(/\/p\/decisionops_event_policy_designer\?policyCode=/, {
    timeout: 15_000,
  });
  await expect(page.getByTestId('event-policy-designer-block')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByTestId('epd-workflow')).toBeVisible();

  await page.getByTestId('epd-step-rules').click();
  await expect(page.getByTestId('policy-rules-editor')).toBeVisible();
  await page.getByTestId('cb-add').click();
  await expect(
    page.locator('select[aria-label="field-0"] option[value="record:data.wd_req_applicant"]'),
  ).toContainText(/申请人|Applicant/i, {
    timeout: 15_000,
  });
  await page.getByLabel('field-0').selectOption('record:data.wd_req_applicant');
  await page.getByLabel('operator-0').selectOption('EQ');
  await expect(page.getByTestId('reference-value-trigger-0')).toContainText('选择用户');
  await page.getByTestId('reference-value-trigger-0').click();
  await expect(page.getByTestId('reference-value-menu-0')).toBeVisible({ timeout: 10_000 });
  await expect(page.getByTestId('reference-value-meta-0')).toContainText(
    /用户 .+ (displayName|nick_name|nickName)/,
  );
  await expect(page.getByTestId(`reference-value-option-0-${user.pid}`)).toBeVisible({
    timeout: 15_000,
  });
  await page.getByTestId(`reference-value-option-0-${user.pid}`).click();
  await expect(page.getByTestId('reference-value-trigger-0')).toContainText(user.label);
  await expect(page.getByTestId('cb-preview')).toContainText(/申请人|Applicant/i);
  await expect(page.getByTestId('cb-preview')).toContainText(user.label);
  await expect(page.getByTestId('cb-preview')).not.toContainText(user.pid);

  const bindingBlock = page
    .getByTestId('epd-rule-binding-0')
    .getByTestId('decision-rule-binding-block');
  await expect(bindingBlock).toBeVisible({ timeout: 15_000 });
  await expect(bindingBlock.getByLabel('decision-code').locator(`option[value="${decisionCode}"]`)).toHaveCount(1, {
    timeout: 15_000,
  });
  await bindingBlock.getByLabel('decision-code').selectOption(decisionCode);
  await bindingBlock.getByLabel('version-policy').selectOption('LATEST_PUBLISHED');
  await bindingBlock.getByLabel('fallback-mode').selectOption('FAIL_CLOSED');
  await bindingBlock.getByRole('button', { name: '添加映射' }).click();
  await expect(
    bindingBlock.locator('select[aria-label="mapping-field-0"] option[value="record:data.wd_req_applicant"]'),
  ).toHaveCount(1);
  await bindingBlock.getByLabel('mapping-input-0').fill('wd_req_applicant');
  await bindingBlock.getByLabel('mapping-field-0').selectOption('record:data.wd_req_applicant');
  await expect(bindingBlock.getByTestId('decision-binding-preview')).toContainText(decisionCode);
  await expect(bindingBlock.getByTestId('decision-binding-preview')).toContainText(/申请人|Applicant/i);

  await page.getByTestId('epd-step-actions').click();
  await page.getByTestId('epd-add-action').click();
  await page.getByLabel('action-target-0').fill(`USER:${notifyRecipientId}`);
  await page.getByLabel('action-field-0-payload.title').fill('Applicant event policy notice');
  await page
    .getByLabel('action-field-0-payload.content')
    .fill('申请人 ${record.data.wd_req_applicant} 触发事件策略');

  await page.getByTestId('epd-step-test').click();
  await expect(page.getByTestId('condition-testrun')).toContainText('5天长假申请');
  await expect(page.getByTestId('trp-context')).toContainText(/申请人|Applicant/i, {
    timeout: 15_000,
  });
  await expect(page.getByTestId('trp-context')).toContainText(user.pid);
  await expect(page.getByTestId('trp-result')).toHaveAttribute('data-truth', 'TRUE');

  await page.screenshot({
    path: testInfo.outputPath('event-policy-applicant-reference-configured.png'),
    fullPage: true,
  });

  await page.getByTestId('epd-step-publish').click();
  const draftResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes(`/api/event-policy/definitions/${policyCode}/versions`),
    { timeout: 15_000 },
  );
  await page.getByTestId('epd-save-draft').click();
  const draft = await readApi<EventPolicyVersion>(await draftResponsePromise);
  expect(draft.pid).toBeTruthy();
  await expect(page.getByTestId('epd-publish-status')).toContainText(/DRAFT|草稿/i, {
    timeout: 10_000,
  });

  const validateResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes(`/api/event-policy/versions/${draft.pid}/validate`),
    { timeout: 15_000 },
  );
  await page.getByTestId('epd-validate-version').click();
  await expect((await readApi<EventPolicyVersion>(await validateResponsePromise)).status).toBe(
    'VALIDATED',
  );

  const publishResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes(`/api/event-policy/versions/${draft.pid}/publish`),
    { timeout: 15_000 },
  );
  await page.getByTestId('epd-publish-version').click();
  await expect((await readApi<EventPolicyVersion>(await publishResponsePromise)).status).toBe(
    'PUBLISHED',
  );
  await expect(page.getByTestId('epd-publish-status')).toContainText(/PUBLISHED|已发布/i, {
    timeout: 10_000,
  });

  await page.getByTestId('epd-step-test').click();
  const runButtonResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes('/api/event-policy/run-and-execute'),
    { timeout: 15_000 },
  );
  await page.getByTestId('epd-run-published').click();
  const execution = await readApi<EventPolicyExecutionResult>(await runButtonResponse);
  expect(execution.policy?.status).toBe('MATCHED');
  expect(execution.policy?.matchedRuleCodes).toContain('R-1');
  expect(execution.policy?.decisionTraceIds).toHaveLength(1);
  expect(execution.execution?.policyCode).toBe(policyCode);
  expect(execution.execution?.overallStatus).toBe('ALL_SUCCESS');
  expect(execution.execution?.actions).toHaveLength(1);
  expect(execution.execution?.actions?.[0]).toMatchObject({
    ruleCode: 'R-1',
    type: 'NOTIFY',
    status: 'SUCCESS',
  });
  expect(execution.execution?.actions?.[0]?.resultPayload).toMatchObject({
    channel: 'in_app',
    recipientType: 'USER',
    recipientId: notifyRecipientId,
    title: 'Applicant event policy notice',
    sourceId: 'R-1',
  });
  const correlationId = execution.policy?.correlationId;
  const traceId = execution.policy?.decisionTraceIds?.[0];
  expect(correlationId).toMatch(/^ep-/);
  expect(traceId).toBeTruthy();
  await expect(page.getByTestId('epd-action-execution-0')).toContainText('发送站内通知');
  await expect(page.getByTestId('epd-action-execution-0')).toContainText('成功');

  const eventPolicyTraceLink = page.getByTestId('epd-open-trace');
  await expect(eventPolicyTraceLink).toBeVisible({ timeout: 10_000 });
  const eventPolicyTraceHref = await eventPolicyTraceLink.getAttribute('href');
  expect(eventPolicyTraceHref).toBeTruthy();
  const eventPolicyTraceUrl = new URL(eventPolicyTraceHref!, page.url());
  expect(eventPolicyTraceUrl.pathname).toBe('/p/decisionops_execution_logs');
  expect(eventPolicyTraceUrl.searchParams.get('policyCode')).toBe(policyCode);
  expect(eventPolicyTraceUrl.searchParams.get('correlationId')).toBe(correlationId);
  expect(eventPolicyTraceUrl.searchParams.get('callerType')).toBe('EVENT_POLICY');
  expect(eventPolicyTraceUrl.searchParams.get('callerRef')).toBe(policyCode);

  const traceActionResponse = page.waitForResponse(
    (response) => {
      if (response.status() !== 200 || !response.url().includes('/api/event-policy/action-logs')) {
        return false;
      }
      const url = new URL(response.url());
      return (
        url.searchParams.get('policyCode') === policyCode &&
        url.searchParams.get('correlationId') === correlationId
      );
    },
    { timeout: 20_000 },
  );
  await eventPolicyTraceLink.click();
  await expect(page).toHaveURL(/\/p\/decisionops_execution_logs/);
  await readApi<ActionLogRecord[]>(await traceActionResponse);
  await expect(page.getByTestId('execution-log-trace-block')).toBeVisible({ timeout: 20_000 });
  await expect(page.getByLabel('log-caller-type')).toHaveValue('EVENT_POLICY');
  await expect(page.getByLabel('log-keyword')).toHaveValue(policyCode);
  const linkedActionEvidence = page.getByTestId('elta-linked-action-evidence');
  await expect(linkedActionEvidence).toBeVisible({ timeout: 20_000 });
  await expect(linkedActionEvidence).toContainText('发送站内通知');
  await expect(linkedActionEvidence).toContainText('Applicant event policy notice');
  await expect(linkedActionEvidence).toContainText(notifyRecipientId);

  const traceRow = page.locator('tr[data-testid^="elta-row-"]').filter({ hasText: traceId! }).first();
  await expect(traceRow).toBeVisible({ timeout: 20_000 });
  const rowTestId = await traceRow.getAttribute('data-testid');
  const logKey = rowTestId?.replace('elta-row-', '');
  if (!logKey) {
    throw new Error(`Expected DecisionOps row test id for trace ${traceId}`);
  }
  await page.getByTestId(`elta-open-trace-${logKey}`).click();
  await expect(page.getByTestId('elta-trace-drawer')).toBeVisible({ timeout: 10_000 });
  await expect(page.getByTestId('elta-chain-caller-' + logKey)).toContainText(
    new RegExp(`(事件策略|EVENT_POLICY) / ${policyCode}`),
  );
  await expect(page.getByTestId('elta-open-event-policy-detail')).toHaveAttribute(
    'href',
    `/p/decisionops_event_policies/view/${encodeURIComponent(policyCode)}`,
  );
  await expect(page.getByTestId('elta-open-event-policy-designer')).toHaveAttribute(
    'href',
    `/p/decisionops_event_policy_designer?policyCode=${encodeURIComponent(policyCode)}`,
  );
  const factMetadata = page.getByTestId(`elta-fact-metadata-${logKey}`);
  await expect(factMetadata).toBeVisible({ timeout: 10_000 });
  await expect(factMetadata).toContainText('事实快照');
  await expect(factMetadata).toContainText(/申请人|Applicant/i);
  await expect(factMetadata).toContainText('record.data.wd_req_applicant');
  await expect(factMetadata).toContainText('模型 wd_leave_request');
  await expect(factMetadata).toContainText(/类型 (user|reference)/i);
  await expect(factMetadata).toContainText(user.pid);
  await expect(factMetadata).toContainText(user.label);

  await page.screenshot({
    path: testInfo.outputPath('event-policy-applicant-reference-trace-fact-metadata.png'),
    fullPage: true,
  });
  await page.screenshot({
    path: '../docs/system-reference/assets/event-policy-applicant-reference-trace-fact-metadata-20260719.png',
    fullPage: true,
  });
});

test('EventPolicy failed action can be replayed from unified Trace with retry evidence @golden', async ({
  page,
}, testInfo) => {
  await loginViaUI(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
  await expect(page).not.toHaveURL(/\/login(?:$|\?)/);

  const suffix = uniqueId('ep_retry').replace(/[^a-zA-Z0-9_]/g, '_');
  const policyCode = `codex_ep_retry_${suffix}`;
  const targetKey = `complaint_retry_${suffix}`;

  await openEventPolicyListFromSidebar(page);
  await expect(page.getByTestId('event-policy-actions-block')).toBeVisible({ timeout: 15_000 });
  await page.getByTestId('epa-new-policy').click();
  await expect(page.getByTestId('epa-editor')).toBeVisible({ timeout: 10_000 });
  await page.getByLabel('policy-code').fill(policyCode);
  await page.getByLabel('policy-name').fill(`Codex Retry Action ${suffix}`);
  await page.getByLabel('policy-event-type').fill('FORM_SUBMITTED');
  await page.getByLabel('policy-target-type').fill('FORM');
  await page.getByLabel('policy-target-key').fill(targetKey);
  await page.getByTestId('epa-save-policy').click();
  await expect(page).toHaveURL(new RegExp(`/p/decisionops_event_policies/view/${policyCode}`), {
    timeout: 15_000,
  });

  await page.getByTestId('epa-open-designer').click();
  await expect(page).toHaveURL(/\/p\/decisionops_event_policy_designer\?policyCode=/, {
    timeout: 15_000,
  });
  await expect(page.getByTestId('event-policy-designer-block')).toBeVisible({ timeout: 15_000 });

  await page.getByTestId('epd-step-rules').click();
  await page.getByTestId('cb-add').click();
  await expect(
    page.locator('select[aria-label="field-0"] option[value="record:data.priority"]'),
  ).toHaveCount(1, {
    timeout: 15_000,
  });
  await page.getByLabel('field-0').selectOption('record:data.priority');
  await page.getByLabel('operator-0').selectOption('EQ');
  await page.getByLabel('value-0').selectOption('HIGH');

  await page.getByTestId('epd-step-actions').click();
  await page.getByTestId('epd-add-action').click();
  await expect(
    page.locator('select[aria-label="action-type-0"] option[value="SEND_SMS"]'),
  ).toContainText('发送短信（不可用）', { timeout: 15_000 });
  await page.getByLabel('action-type-0').selectOption('SEND_SMS');
  await expect(page.getByTestId('epd-action-availability-0')).toContainText(
    '当前环境未配置真实短信 provider',
  );
  await page.getByLabel('action-target-0').fill('PHONE:+8613800138000');
  await page.getByLabel('action-field-0-payload.content').fill('retry sms ${record.data.status}');

  await page.getByTestId('epd-step-publish').click();
  await page.getByLabel('失败策略').selectOption('RETRY_ASYNC');
  await expect(page.getByLabel('失败策略')).toHaveValue('RETRY_ASYNC');
  const draftResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes(`/api/event-policy/definitions/${policyCode}/versions`),
    { timeout: 15_000 },
  );
  await page.getByTestId('epd-save-draft').click();
  const draft = await readApi<EventPolicyVersion>(await draftResponsePromise);
  expect(draft.pid).toBeTruthy();
  await expect(page.getByTestId('epd-publish-status')).toContainText(/DRAFT|草稿/i, {
    timeout: 10_000,
  });

  const validateResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes(`/api/event-policy/versions/${draft.pid}/validate`),
    { timeout: 15_000 },
  );
  await page.getByTestId('epd-validate-version').click();
  await expect((await readApi<EventPolicyVersion>(await validateResponsePromise)).status).toBe(
    'VALIDATED',
  );

  const publishResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes(`/api/event-policy/versions/${draft.pid}/publish`),
    { timeout: 15_000 },
  );
  await page.getByTestId('epd-publish-version').click();
  await expect((await readApi<EventPolicyVersion>(await publishResponsePromise)).status).toBe(
    'PUBLISHED',
  );
  await expect(page.getByTestId('epd-publish-status')).toContainText(/PUBLISHED|已发布/i, {
    timeout: 10_000,
  });

  await page.getByTestId('epd-step-test').click();
  const runButtonResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes('/api/event-policy/run-and-execute'),
    { timeout: 15_000 },
  );
  await page.getByTestId('epd-run-published').click();
  const execution = await readApi<EventPolicyExecutionResult>(await runButtonResponse);
  expect(execution.policy?.status).toBe('MATCHED');
  const correlationId = execution.policy?.correlationId;
  expect(
    correlationId,
    `run-and-execute must return correlationId: ${JSON.stringify(execution)}`,
  ).toMatch(/^ep-/);
  expect(execution.execution?.policyCode).toBe(policyCode);
  expect(execution.execution?.overallStatus).toBe('FAILED');
  expect(execution.execution?.actions).toHaveLength(1);
  expect(execution.execution?.actions?.[0]).toMatchObject({
    ruleCode: 'R-1',
    type: 'SEND_SMS',
    status: 'RETRY_PENDING',
    error: expect.stringContaining('No real SMS sender available'),
  });
  expect(execution.execution?.actions?.[0]?.resultPayload).toMatchObject({
    channel: 'sms',
    targetPhones: ['+8613800138000'],
    sentCount: 0,
  });
  await expect(page.getByTestId('epd-action-execution-0')).toContainText('发送短信');
  await expect(page.getByTestId('epd-action-execution-0')).toContainText('等待重试');
  await expect(page.getByTestId('epd-action-execution-0')).toContainText(
    'No real SMS sender available',
  );

  const actionLogs = await readApi<ActionLogRecord[]>(
    await page.request.get('/api/event-policy/action-logs', {
      params: { policyCode, correlationId: correlationId!, size: '20' },
    }),
  );
  expect(actionLogs).toHaveLength(1);
  const actionLog = actionLogs[0];
  expect(actionLog).toMatchObject({
    policyCode,
    correlationId,
    ruleCode: 'R-1',
    actionType: 'SEND_SMS',
    status: 'RETRY_PENDING',
    failureStrategy: 'RETRY_ASYNC',
    attemptCount: 1,
    maxAttempts: 3,
    errorMessage: expect.stringContaining('No real SMS sender available'),
    resultPayload: {
      channel: 'sms',
      targetPhones: ['+8613800138000'],
      sentCount: 0,
    },
  });
  expect(
    actionLog.pid,
    `action log pid required for replay: ${JSON.stringify(actionLog)}`,
  ).toBeTruthy();
  expect(actionLog.actionPayload).toMatchObject({
    ruleCode: 'R-1',
    type: 'SEND_SMS',
    target: 'PHONE:+8613800138000',
    payload: {
      content: 'retry sms ${record.data.status}',
    },
  });
  expect(actionLog.contextPayload?.record).toMatchObject({
    data: expect.objectContaining({
      status: 'OPEN',
    }),
  });

  const eventPolicyTraceLink = page.getByTestId('epd-open-trace');
  await expect(eventPolicyTraceLink).toBeVisible({ timeout: 10_000 });
  const traceActionResponse = page.waitForResponse(
    (response) => {
      if (response.status() !== 200 || !response.url().includes('/api/event-policy/action-logs')) {
        return false;
      }
      const url = new URL(response.url());
      return (
        url.searchParams.get('policyCode') === policyCode &&
        url.searchParams.get('correlationId') === correlationId
      );
    },
    { timeout: 20_000 },
  );
  await eventPolicyTraceLink.click();
  await expect(page).toHaveURL(/\/p\/decisionops_execution_logs/);
  await readApi<ActionLogRecord[]>(await traceActionResponse);
  const linkedActionEvidence = page.getByTestId('elta-linked-action-evidence');
  await expect(linkedActionEvidence).toBeVisible({ timeout: 20_000 });
  await expect(linkedActionEvidence).toContainText('SEND_SMS');
  await expect(linkedActionEvidence).toContainText('等待重试');
  await expect(linkedActionEvidence).toContainText('重试 1/3');
  await expect(linkedActionEvidence).toContainText('No real SMS sender available');

  const replayResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes(`/api/event-policy/action-logs/${actionLog.pid}/replay`),
    { timeout: 20_000 },
  );
  await page.getByTestId(`elta-action-replay-${actionLog.pid}`).click();
  const replayed = await readApi<ActionLogRecord>(await replayResponsePromise);
  expect(replayed).toMatchObject({
    pid: actionLog.pid,
    policyCode,
    correlationId,
    status: 'RETRY_PENDING',
    failureStrategy: 'RETRY_ASYNC',
    attemptCount: 2,
    maxAttempts: 3,
    errorMessage: expect.stringContaining('No real SMS sender available'),
  });
  await expect(page.getByTestId(`elta-action-card-${actionLog.pid}`)).toContainText('重试 2/3', {
    timeout: 10_000,
  });
  await expect(page.getByTestId(`elta-action-card-${actionLog.pid}`)).toContainText(
    'No real SMS sender available',
  );

  await page.screenshot({
    path: testInfo.outputPath('event-policy-retry-replay-trace-action-evidence.png'),
    fullPage: true,
  });

  const deadLetterResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes(`/api/event-policy/action-logs/${actionLog.pid}/replay`),
    { timeout: 20_000 },
  );
  await page.getByTestId(`elta-action-replay-${actionLog.pid}`).click();
  const deadLettered = await readApi<ActionLogRecord>(await deadLetterResponsePromise);
  expect(deadLettered).toMatchObject({
    pid: actionLog.pid,
    policyCode,
    correlationId,
    status: 'DEAD_LETTER',
    failureStrategy: 'RETRY_ASYNC',
    attemptCount: 3,
    maxAttempts: 3,
    errorMessage: expect.stringMatching(/Retry attempts exhausted after 3 attempts: .*No real SMS sender available/),
    resultPayload: {
      channel: 'sms',
      targetPhones: ['+8613800138000'],
      sentCount: 0,
      retryExhausted: true,
      attemptCount: 3,
      maxAttempts: 3,
    },
  });
  expect(
    deadLettered.deadLetteredAt,
    `deadLetteredAt required: ${JSON.stringify(deadLettered)}`,
  ).toBeTruthy();
  expect(deadLettered.nextRetryAt ?? null).toBeNull();
  await expect(page.getByTestId(`elta-action-card-${actionLog.pid}`)).toContainText('重试 3/3', {
    timeout: 10_000,
  });
  await expect(page.getByTestId(`elta-action-card-${actionLog.pid}`)).toContainText('重试已耗尽');
  await expect(page.getByTestId(`elta-action-card-${actionLog.pid}`)).toContainText('死信');
  await expect(page.getByTestId(`elta-action-card-${actionLog.pid}`)).toContainText(
    'Retry attempts exhausted after 3 attempts',
  );

  await page.screenshot({
    path: testInfo.outputPath('event-policy-retry-exhausted-dead-letter-trace-action-evidence.png'),
    fullPage: true,
  });

  const replayedLogs = await readApi<ActionLogRecord[]>(
    await page.request.get('/api/event-policy/action-logs', {
      params: { policyCode, correlationId: correlationId!, size: '20' },
    }),
  );
  expect(replayedLogs).toHaveLength(1);
  expect(replayedLogs[0]).toMatchObject({
    pid: actionLog.pid,
    status: 'DEAD_LETTER',
    attemptCount: 3,
    maxAttempts: 3,
    failureStrategy: 'RETRY_ASYNC',
    resultPayload: {
      retryExhausted: true,
      attemptCount: 3,
      maxAttempts: 3,
    },
  });
  expect(replayedLogs[0].deadLetteredAt).toBeTruthy();
});

test('EventPolicy multi-action webhook execution is linked in Trace and idempotent on rerun @golden', async ({
  page,
}, testInfo) => {
  await loginViaUI(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
  await expect(page).not.toHaveURL(/\/login(?:$|\?)/);
  const currentUser = await readApi<CurrentUserInfo>(await page.request.get('/api/auth/me'));
  const notifyRecipientId = currentUser.user?.id;
  if (!notifyRecipientId) {
    throw new Error(
      `Current user id is required for NOTIFY action target: ${JSON.stringify(currentUser)}`,
    );
  }
  expect(notifyRecipientId).toMatch(/^\d+$/);

  const suffix = uniqueId('ep_multi').replace(/[^a-zA-Z0-9_]/g, '_');
  const policyCode = `codex_ep_multi_${suffix}`;
  const targetKey = `complaint_multi_${suffix}`;
  const eventType = `codex.event_policy.${suffix}`;
  const expectedRecordPid = `TEST-${policyCode}`;
  const deliveryEventId = `epw-${suffix}`;

  const webhook = await readApi<WebhookRecord>(
    await page.request.post('/api/webhooks', {
      data: {
        name: `Codex EventPolicy Webhook ${suffix}`,
        targetUrl: 'http://127.0.0.1:6443/internal',
        eventType,
        maxRetries: 0,
        timeoutMs: 1000,
        enabled: true,
      },
    }),
  );
  expect(webhook.pid, `webhook subscription pid required: ${JSON.stringify(webhook)}`).toBeTruthy();

  await openEventPolicyListFromSidebar(page);
  await expect(page.getByTestId('event-policy-actions-block')).toBeVisible({ timeout: 15_000 });
  await page.getByTestId('epa-new-policy').click();
  await expect(page.getByTestId('epa-editor')).toBeVisible({ timeout: 10_000 });
  await page.getByLabel('policy-code').fill(policyCode);
  await page.getByLabel('policy-name').fill(`Codex Multi Action Webhook ${suffix}`);
  await page.getByLabel('policy-event-type').fill('FORM_SUBMITTED');
  await page.getByLabel('policy-target-type').fill('FORM');
  await page.getByLabel('policy-target-key').fill(targetKey);
  await page.getByTestId('epa-save-policy').click();
  await expect(page).toHaveURL(new RegExp(`/p/decisionops_event_policies/view/${policyCode}`), {
    timeout: 15_000,
  });

  await page.getByTestId('epa-open-designer').click();
  await expect(page).toHaveURL(/\/p\/decisionops_event_policy_designer\?policyCode=/, {
    timeout: 15_000,
  });
  await expect(page.getByTestId('event-policy-designer-block')).toBeVisible({ timeout: 15_000 });

  await page.getByTestId('epd-step-rules').click();
  await page.getByTestId('cb-add').click();
  await expect(
    page.locator('select[aria-label="field-0"] option[value="record:data.priority"]'),
  ).toHaveCount(1, {
    timeout: 15_000,
  });
  await page.getByLabel('field-0').selectOption('record:data.priority');
  await page.getByLabel('operator-0').selectOption('EQ');
  await page.getByLabel('value-0').selectOption('HIGH');

  await page.getByTestId('epd-step-actions').click();
  await page.getByTestId('epd-add-action').click();
  await page.getByLabel('action-target-0').fill(`USER:${notifyRecipientId}`);
  await page.getByLabel('action-field-0-payload.title').fill('Multi action notice');
  await page
    .getByLabel('action-field-0-payload.content')
    .fill('multi action ${record.data.status}');

  await page.getByTestId('epd-add-action').click();
  await page.getByLabel('action-type-1').selectOption('WEBHOOK');
  const webhookPayloadEditor = page.getByRole('textbox', {
    name: 'action-field-1-payload',
    exact: true,
  });
  await expect(webhookPayloadEditor).toBeVisible({ timeout: 10_000 });
  await webhookPayloadEditor.fill(
    JSON.stringify(
      {
        eventType,
        _eventId: deliveryEventId,
        recordPid: '${record.recordPid}',
        status: '${record.data.status}',
        priority: '${record.data.priority}',
      },
      null,
      2,
    ),
  );
  await expect(page.getByLabel('action-field-1-payload.eventType')).toHaveValue(eventType);

  await page.screenshot({
    path: testInfo.outputPath('event-policy-multi-webhook-configured.png'),
    fullPage: true,
  });

  await page.getByTestId('epd-step-publish').click();
  await page.getByLabel('失败策略').selectOption('CONTINUE_ON_ERROR');
  await expect(page.getByLabel('失败策略')).toHaveValue('CONTINUE_ON_ERROR');
  const draftResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes(`/api/event-policy/definitions/${policyCode}/versions`),
    { timeout: 15_000 },
  );
  await page.getByTestId('epd-save-draft').click();
  const draft = await readApi<EventPolicyVersion>(await draftResponsePromise);
  expect(draft.pid).toBeTruthy();
  await expect(page.getByTestId('epd-publish-status')).toContainText(/DRAFT|草稿/i, {
    timeout: 10_000,
  });

  const validateResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes(`/api/event-policy/versions/${draft.pid}/validate`),
    { timeout: 15_000 },
  );
  await page.getByTestId('epd-validate-version').click();
  await expect((await readApi<EventPolicyVersion>(await validateResponsePromise)).status).toBe(
    'VALIDATED',
  );

  const publishResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes(`/api/event-policy/versions/${draft.pid}/publish`),
    { timeout: 15_000 },
  );
  await page.getByTestId('epd-publish-version').click();
  await expect((await readApi<EventPolicyVersion>(await publishResponsePromise)).status).toBe(
    'PUBLISHED',
  );
  await expect(page.getByTestId('epd-publish-status')).toContainText(/PUBLISHED|已发布/i, {
    timeout: 10_000,
  });

  await page.getByTestId('epd-step-test').click();
  const firstRunResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes('/api/event-policy/run-and-execute'),
    { timeout: 15_000 },
  );
  await page.getByTestId('epd-run-published').click();
  const firstExecution = await readApi<EventPolicyExecutionResult>(await firstRunResponse);
  const firstCorrelationId = firstExecution.policy?.correlationId;
  expect(firstExecution.policy?.status).toBe('MATCHED');
  expect(
    firstCorrelationId,
    `run-and-execute must return correlationId: ${JSON.stringify(firstExecution)}`,
  ).toMatch(/^ep-/);
  expect(firstExecution.execution?.policyCode).toBe(policyCode);
  expect(firstExecution.execution?.overallStatus).toBe('ALL_SUCCESS');
  expect(firstExecution.execution?.actions).toHaveLength(2);
  expect(firstExecution.execution?.actions?.[0]).toMatchObject({
    ruleCode: 'R-1',
    type: 'NOTIFY',
    status: 'SUCCESS',
  });
  expect(firstExecution.execution?.actions?.[1]).toMatchObject({
    ruleCode: 'R-1',
    type: 'WEBHOOK',
    status: 'SUCCESS',
  });
  expect(firstExecution.execution?.actions?.[1]?.resultPayload).toMatchObject({
    eventType,
    dispatchAccepted: true,
    deliveryEventId,
    deliveryTraceStatus: 'tracked_delivery_logs',
    recordPid: expectedRecordPid,
  });
  expect(firstExecution.execution?.actions?.[1]?.resultPayload?.deliveryLogPids).toHaveLength(1);
  expect(firstExecution.execution?.actions?.[1]?.resultPayload?.deliveryReceipts).toHaveLength(1);
  expect(firstExecution.execution?.actions?.[1]?.resultPayload?.payloadKeys).toEqual(
    expect.arrayContaining(['recordPid', 'status', 'priority']),
  );
  await expect(page.getByTestId('epd-action-execution-results')).toBeVisible({ timeout: 10_000 });
  await expect(page.getByTestId('epd-action-execution-0')).toContainText('发送站内通知');
  await expect(page.getByTestId('epd-action-execution-0')).toContainText('成功');
  await expect(page.getByTestId('epd-action-execution-1')).toContainText('调用 Webhook');
  await expect(page.getByTestId('epd-action-execution-1')).toContainText('成功');
  await expect(page.getByTestId('epd-action-result-payload-1')).toContainText('投递状态');
  await expect(page.getByTestId('epd-action-result-payload-1')).toContainText('已记录投递日志');
  await expect(page.getByTestId('epd-action-result-payload-1')).toContainText(eventType);
  await expect(page.getByTestId('epd-action-result-payload-1')).toContainText(expectedRecordPid);

  const firstActionLogs = await readApi<ActionLogRecord[]>(
    await page.request.get('/api/event-policy/action-logs', {
      params: { policyCode, correlationId: firstCorrelationId!, size: '20' },
    }),
  );
  expect(firstActionLogs).toHaveLength(2);
  const notifyLog = firstActionLogs.find((log) => log.actionType === 'NOTIFY');
  const webhookLog = firstActionLogs.find((log) => log.actionType === 'WEBHOOK');
  expect(notifyLog).toMatchObject({
    policyCode,
    correlationId: firstCorrelationId,
    ruleCode: 'R-1',
    actionType: 'NOTIFY',
    status: 'SUCCESS',
  });
  expect(webhookLog).toMatchObject({
    policyCode,
    correlationId: firstCorrelationId,
    ruleCode: 'R-1',
    actionType: 'WEBHOOK',
    status: 'SUCCESS',
    resultPayload: {
      eventType,
      deliveryEventId,
      deliveryTraceStatus: 'tracked_delivery_logs',
      recordPid: expectedRecordPid,
    },
  });
  expect(notifyLog?.idempotencyKey).toContain(`${expectedRecordPid}:R-1:NOTIFY`);
  expect(webhookLog?.idempotencyKey).toContain(`${expectedRecordPid}:R-1:WEBHOOK`);
  const deliveryLogPid = (webhookLog?.resultPayload?.deliveryLogPids as string[] | undefined)?.[0];
  expect(
    deliveryLogPid,
    `webhook action result must expose delivery log pid: ${JSON.stringify(webhookLog)}`,
  ).toBeTruthy();

  const deliveries = await readApi<WebhookDeliveryRecord[]>(
    await page.request.get(`/api/webhooks/${webhook.pid}/deliveries`, {
      params: { limit: '10' },
    }),
  );
  const delivery = deliveries.find((item) => (item.eventId ?? item.event_id) === deliveryEventId);
  expect(
    delivery,
    `webhook delivery log must be queryable: ${JSON.stringify(deliveries)}`,
  ).toBeTruthy();
  expect(delivery?.pid).toBe(deliveryLogPid);
  expect((delivery?.deliveryStatus ?? delivery?.delivery_status)?.toLowerCase()).toBe('failed');
  expect(delivery?.requestBody ?? delivery?.request_body).toContain(expectedRecordPid);
  expect(delivery?.requestBody ?? delivery?.request_body).toContain('"status":"OPEN"');
  expect(delivery?.requestBody ?? delivery?.request_body).toContain('"priority":"HIGH"');

  const eventPolicyTraceLink = page.getByTestId('epd-open-trace');
  await expect(eventPolicyTraceLink).toBeVisible({ timeout: 10_000 });
  const traceActionResponse = page.waitForResponse(
    (response) => {
      if (response.status() !== 200 || !response.url().includes('/api/event-policy/action-logs')) {
        return false;
      }
      const url = new URL(response.url());
      return (
        url.searchParams.get('policyCode') === policyCode &&
        url.searchParams.get('correlationId') === firstCorrelationId
      );
    },
    { timeout: 20_000 },
  );
  await eventPolicyTraceLink.click();
  await expect(page).toHaveURL(/\/p\/decisionops_execution_logs/);
  await readApi<ActionLogRecord[]>(await traceActionResponse);
  const linkedActionEvidence = page.getByTestId('elta-linked-action-evidence');
  await expect(linkedActionEvidence).toBeVisible({ timeout: 20_000 });
  await expect(linkedActionEvidence).toContainText('发送站内通知');
  await expect(linkedActionEvidence).toContainText('调用 Webhook');
  await expect(linkedActionEvidence).toContainText('投递追踪');
  await expect(linkedActionEvidence).toContainText('投递状态 已记录投递日志');
  await expect(linkedActionEvidence).toContainText(`投递日志 ${deliveryLogPid}`);
  await expect(linkedActionEvidence).toContainText('投递回执');

  await page.screenshot({
    path: testInfo.outputPath('event-policy-multi-webhook-trace-action-evidence.png'),
    fullPage: true,
  });

  await page.goBack({ waitUntil: 'domcontentloaded' });
  await expect(page).toHaveURL(/\/p\/decisionops_event_policy_designer\?policyCode=/, {
    timeout: 15_000,
  });
  await page.getByTestId('epd-step-test').click();
  const secondRunResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes('/api/event-policy/run-and-execute'),
    { timeout: 15_000 },
  );
  await page.getByTestId('epd-run-published').click();
  const secondExecution = await readApi<EventPolicyExecutionResult>(await secondRunResponse);
  const secondCorrelationId = secondExecution.policy?.correlationId;
  expect(secondExecution.policy?.status).toBe('MATCHED');
  expect(secondCorrelationId).toMatch(/^ep-/);
  expect(secondCorrelationId).not.toBe(firstCorrelationId);
  expect(secondExecution.execution?.overallStatus).toBe('ALL_SUCCESS');
  expect(secondExecution.execution?.actions).toHaveLength(2);
  expect(secondExecution.execution?.actions?.[0]).toMatchObject({
    type: 'NOTIFY',
    status: 'SKIPPED',
  });
  expect(secondExecution.execution?.actions?.[1]).toMatchObject({
    type: 'WEBHOOK',
    status: 'SKIPPED',
  });
  expect(secondExecution.execution?.actions?.[0]?.idempotencyKey).toBe(notifyLog?.idempotencyKey);
  expect(secondExecution.execution?.actions?.[1]?.idempotencyKey).toBe(webhookLog?.idempotencyKey);
  await expect(page.getByTestId('epd-action-execution-0')).toContainText('幂等跳过', {
    timeout: 10_000,
  });
  await expect(page.getByTestId('epd-action-execution-1')).toContainText('幂等跳过');

  await page.screenshot({
    path: testInfo.outputPath('event-policy-multi-webhook-idempotency-skip.png'),
    fullPage: true,
  });

  const afterRerunLogs = await readApi<ActionLogRecord[]>(
    await page.request.get('/api/event-policy/action-logs', {
      params: { policyCode, size: '20' },
    }),
  );
  expect(afterRerunLogs.filter((log) => log.actionType === 'NOTIFY')).toHaveLength(1);
  expect(afterRerunLogs.filter((log) => log.actionType === 'WEBHOOK')).toHaveLength(1);
  expect(afterRerunLogs.filter((log) => log.correlationId === secondCorrelationId)).toHaveLength(0);

  const deliveriesAfterRerun = await readApi<WebhookDeliveryRecord[]>(
    await page.request.get(`/api/webhooks/${webhook.pid}/deliveries`, {
      params: { limit: '10' },
    }),
  );
  const matchingDeliveriesAfterRerun = deliveriesAfterRerun.filter(
    (item) => (item.eventId ?? item.event_id) === deliveryEventId,
  );
  expect(matchingDeliveriesAfterRerun).toHaveLength(1);
});

test('EventPolicy webhook overlong delivery id fails fast with friendly Trace evidence @golden', async ({
  page,
}, testInfo) => {
  await loginViaUI(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
  await expect(page).not.toHaveURL(/\/login(?:$|\?)/);

  const suffix = uniqueId('ep_webhook_guard').replace(/[^a-zA-Z0-9_]/g, '_');
  const policyCode = `codex_ep_webhook_guard_${suffix}`;
  const targetKey = `complaint_webhook_guard_${suffix}`;
  const eventType = `codex.event_policy.guard.${suffix}`;
  const overlongEventId = `epw-${'x'.repeat(61)}`;
  expect(overlongEventId).toHaveLength(65);

  await openEventPolicyListFromSidebar(page);
  await expect(page.getByTestId('event-policy-actions-block')).toBeVisible({ timeout: 15_000 });
  await page.getByTestId('epa-new-policy').click();
  await expect(page.getByTestId('epa-editor')).toBeVisible({ timeout: 10_000 });
  await page.getByLabel('policy-code').fill(policyCode);
  await page.getByLabel('policy-name').fill(`Codex Webhook Guard ${suffix}`);
  await page.getByLabel('policy-event-type').fill('FORM_SUBMITTED');
  await page.getByLabel('policy-target-type').fill('FORM');
  await page.getByLabel('policy-target-key').fill(targetKey);
  await page.getByTestId('epa-save-policy').click();
  await expect(page).toHaveURL(new RegExp(`/p/decisionops_event_policies/view/${policyCode}`), {
    timeout: 15_000,
  });

  await page.getByTestId('epa-open-designer').click();
  await expect(page).toHaveURL(/\/p\/decisionops_event_policy_designer\?policyCode=/, {
    timeout: 15_000,
  });
  await expect(page.getByTestId('event-policy-designer-block')).toBeVisible({ timeout: 15_000 });

  await page.getByTestId('epd-step-rules').click();
  await page.getByTestId('cb-add').click();
  await expect(
    page.locator('select[aria-label="field-0"] option[value="record:data.priority"]'),
  ).toHaveCount(1, {
    timeout: 15_000,
  });
  await page.getByLabel('field-0').selectOption('record:data.priority');
  await page.getByLabel('operator-0').selectOption('EQ');
  await page.getByLabel('value-0').selectOption('HIGH');

  await page.getByTestId('epd-step-actions').click();
  await page.getByTestId('epd-add-action').click();
  await page.getByLabel('action-type-0').selectOption('WEBHOOK');
  await page.getByLabel('action-field-0-payload.eventType').fill(eventType);
  await page.getByLabel('action-field-0-payload._eventId').fill(overlongEventId);

  await page.getByTestId('epd-step-publish').click();
  await page.getByLabel('失败策略').selectOption('CONTINUE_ON_ERROR');
  const draftResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes(`/api/event-policy/definitions/${policyCode}/versions`),
    { timeout: 15_000 },
  );
  await page.getByTestId('epd-save-draft').click();
  const draft = await readApi<EventPolicyVersion>(await draftResponsePromise);
  expect(draft.pid).toBeTruthy();

  const validateResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes(`/api/event-policy/versions/${draft.pid}/validate`),
    { timeout: 15_000 },
  );
  await page.getByTestId('epd-validate-version').click();
  await expect((await readApi<EventPolicyVersion>(await validateResponsePromise)).status).toBe(
    'VALIDATED',
  );

  const publishResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes(`/api/event-policy/versions/${draft.pid}/publish`),
    { timeout: 15_000 },
  );
  await page.getByTestId('epd-publish-version').click();
  await expect((await readApi<EventPolicyVersion>(await publishResponsePromise)).status).toBe(
    'PUBLISHED',
  );

  await page.getByTestId('epd-step-test').click();
  const runButtonResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes('/api/event-policy/run-and-execute'),
    { timeout: 15_000 },
  );
  await page.getByTestId('epd-run-published').click();
  const execution = await readApi<EventPolicyExecutionResult>(await runButtonResponse);
  const correlationId = execution.policy?.correlationId;
  expect(execution.policy?.status).toBe('MATCHED');
  expect(
    correlationId,
    `run-and-execute must return correlationId: ${JSON.stringify(execution)}`,
  ).toMatch(/^ep-/);
  expect(execution.execution?.overallStatus).toBe('FAILED');
  expect(execution.execution?.actions).toHaveLength(1);
  expect(execution.execution?.actions?.[0]).toMatchObject({
    ruleCode: 'R-1',
    type: 'WEBHOOK',
    status: 'FAILED',
    error: 'WEBHOOK payload._eventId must be 64 characters or fewer (current: 65)',
    resultPayload: {
      eventType,
      deliveryEventId: overlongEventId,
      deliveryTraceStatus: 'validation_failed',
      validationError: 'payload._eventId exceeds max length',
      field: 'payload._eventId',
      actualLength: 65,
      maxLength: 64,
    },
  });
  await expect(page.getByTestId('epd-action-execution-0')).toContainText('调用 Webhook');
  await expect(page.getByTestId('epd-action-execution-0')).toContainText('失败');
  await expect(page.getByTestId('epd-action-execution-0')).toContainText(
    'WEBHOOK payload._eventId must be 64 characters or fewer',
  );
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText('投递状态');
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText('校验失败');
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText('校验错误');
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText(
    '投递追踪 ID 超过 64 字符',
  );
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText('当前长度');
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText('65');
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText('最大长度');
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText('64');

  const actionLogs = await readApi<ActionLogRecord[]>(
    await page.request.get('/api/event-policy/action-logs', {
      params: { policyCode, correlationId: correlationId!, size: '20' },
    }),
  );
  expect(actionLogs).toHaveLength(1);
  expect(actionLogs[0]).toMatchObject({
    policyCode,
    correlationId,
    ruleCode: 'R-1',
    actionType: 'WEBHOOK',
    status: 'FAILED',
    failureStrategy: 'CONTINUE_ON_ERROR',
    errorMessage: 'WEBHOOK payload._eventId must be 64 characters or fewer (current: 65)',
    resultPayload: {
      eventType,
      deliveryEventId: overlongEventId,
      deliveryTraceStatus: 'validation_failed',
      validationError: 'payload._eventId exceeds max length',
      field: 'payload._eventId',
      actualLength: 65,
      maxLength: 64,
    },
  });

  const eventPolicyTraceLink = page.getByTestId('epd-open-trace');
  await expect(eventPolicyTraceLink).toBeVisible({ timeout: 10_000 });
  const traceActionResponse = page.waitForResponse(
    (response) => {
      if (response.status() !== 200 || !response.url().includes('/api/event-policy/action-logs')) {
        return false;
      }
      const url = new URL(response.url());
      return (
        url.searchParams.get('policyCode') === policyCode &&
        url.searchParams.get('correlationId') === correlationId
      );
    },
    { timeout: 20_000 },
  );
  await eventPolicyTraceLink.click();
  await expect(page).toHaveURL(/\/p\/decisionops_execution_logs/);
  await readApi<ActionLogRecord[]>(await traceActionResponse);
  const linkedActionEvidence = page.getByTestId('elta-linked-action-evidence');
  await expect(linkedActionEvidence).toBeVisible({ timeout: 20_000 });
  await expect(linkedActionEvidence).toContainText('WEBHOOK');
  await expect(linkedActionEvidence).toContainText('失败');
  await expect(linkedActionEvidence).toContainText(
    'WEBHOOK payload._eventId must be 64 characters or fewer',
  );
  await expect(linkedActionEvidence).toContainText('投递状态 校验失败');
  await expect(linkedActionEvidence).toContainText('校验错误 投递追踪 ID 超过 64 字符');
  await expect(linkedActionEvidence).toContainText('当前长度 65');
  await expect(linkedActionEvidence).toContainText('最大长度 64');

  await page.screenshot({
    path: testInfo.outputPath('event-policy-webhook-event-id-validation-trace-evidence.png'),
    fullPage: true,
  });
});

test('EventPolicy SEND_IM action writes bot message and linked Trace evidence @golden', async ({
  page,
}, testInfo) => {
  await loginViaUI(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
  await expect(page).not.toHaveURL(/\/login(?:$|\?)/);
  const currentUser = await readApi<CurrentUserInfo>(await page.request.get('/api/auth/me'));
  const imRecipientId = currentUser.user?.id;
  if (!imRecipientId) {
    throw new Error(
      `Current user id is required for SEND_IM action target: ${JSON.stringify(currentUser)}`,
    );
  }
  expect(imRecipientId).toMatch(/^\d+$/);

  const suffix = uniqueId('ep_im').replace(/[^a-zA-Z0-9_]/g, '_');
  const policyCode = `codex_ep_im_${suffix}`;
  const targetKey = `complaint_im_${suffix}`;
  const expectedRecordPid = `TEST-${policyCode}`;
  const expectedContent = `EventPolicy IM OPEN ${expectedRecordPid}`;

  await openEventPolicyListFromSidebar(page);
  await expect(page.getByTestId('event-policy-actions-block')).toBeVisible({ timeout: 15_000 });
  await page.getByTestId('epa-new-policy').click();
  await expect(page.getByTestId('epa-editor')).toBeVisible({ timeout: 10_000 });
  await page.getByLabel('policy-code').fill(policyCode);
  await page.getByLabel('policy-name').fill(`Codex EventPolicy IM ${suffix}`);
  await page.getByLabel('policy-event-type').fill('FORM_SUBMITTED');
  await page.getByLabel('policy-target-type').fill('FORM');
  await page.getByLabel('policy-target-key').fill(targetKey);
  await page.getByTestId('epa-save-policy').click();
  await expect(page).toHaveURL(new RegExp(`/p/decisionops_event_policies/view/${policyCode}`), {
    timeout: 15_000,
  });

  await page.getByTestId('epa-open-designer').click();
  await expect(page).toHaveURL(/\/p\/decisionops_event_policy_designer\?policyCode=/, {
    timeout: 15_000,
  });
  await expect(page.getByTestId('event-policy-designer-block')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByTestId('epd-workflow')).toBeVisible();
  await expect(page.getByTestId('epd-abnormal-actions')).toContainText('无异常动作');

  await page.getByTestId('epd-step-rules').click();
  await expect(page.getByTestId('policy-rules-editor')).toBeVisible();
  await page.getByTestId('cb-add').click();
  await expect(
    page.locator('select[aria-label="field-0"] option[value="record:data.priority"]'),
  ).toHaveCount(1, {
    timeout: 15_000,
  });
  await page.getByLabel('field-0').selectOption('record:data.priority');
  await page.getByLabel('operator-0').selectOption('EQ');
  await page.getByLabel('value-0').selectOption('HIGH');

  await page.getByTestId('epd-step-actions').click();
  await page.getByTestId('epd-add-action').click();
  await expect(
    page.locator('select[aria-label="action-type-0"] option[value="SEND_IM"]'),
  ).toContainText('发送 IM', {
    timeout: 10_000,
  });
  await page.getByLabel('action-type-0').selectOption('SEND_IM');
  await expect(page.getByTestId('epd-abnormal-actions')).toContainText('无异常动作');
  await expect(page.getByTestId('epd-action-schema-0')).toContainText('IM 接收人或群组表达式');
  await expect(page.getByTestId('epd-action-schema-0')).toContainText('IM 渠道');
  await expect(page.getByTestId('epd-action-schema-0')).toContainText('消息内容');
  await page.getByLabel('action-target-0').fill(`USER:${imRecipientId}`);
  await page.getByLabel('action-field-0-payload.channel').fill('im');
  await page
    .getByLabel('action-field-0-payload.content')
    .fill('EventPolicy IM ${record.data.status} ${record.recordPid}');

  await page.screenshot({
    path: testInfo.outputPath('event-policy-send-im-configured.png'),
    fullPage: true,
  });

  await page.getByTestId('epd-step-publish').click();
  const draftResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes(`/api/event-policy/definitions/${policyCode}/versions`),
    { timeout: 15_000 },
  );
  await page.getByTestId('epd-save-draft').click();
  const draft = await readApi<EventPolicyVersion>(await draftResponsePromise);
  expect(draft.pid).toBeTruthy();
  await expect(page.getByTestId('epd-publish-status')).toContainText(/DRAFT|草稿/i, {
    timeout: 10_000,
  });

  const validateResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes(`/api/event-policy/versions/${draft.pid}/validate`),
    { timeout: 15_000 },
  );
  await page.getByTestId('epd-validate-version').click();
  await expect((await readApi<EventPolicyVersion>(await validateResponsePromise)).status).toBe(
    'VALIDATED',
  );

  const publishResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes(`/api/event-policy/versions/${draft.pid}/publish`),
    { timeout: 15_000 },
  );
  await page.getByTestId('epd-publish-version').click();
  await expect((await readApi<EventPolicyVersion>(await publishResponsePromise)).status).toBe(
    'PUBLISHED',
  );
  await expect(page.getByTestId('epd-publish-status')).toContainText(/PUBLISHED|已发布/i, {
    timeout: 10_000,
  });

  await page.getByTestId('epd-step-test').click();
  const runButtonResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes('/api/event-policy/run-and-execute'),
    { timeout: 15_000 },
  );
  await page.getByTestId('epd-run-published').click();
  const execution = await readApi<EventPolicyExecutionResult>(await runButtonResponse);
  expect(execution.policy?.status).toBe('MATCHED');
  expect(execution.policy?.matchedRuleCodes).toContain('R-1');
  const correlationId = execution.policy?.correlationId;
  expect(
    correlationId,
    `run-and-execute must return correlationId: ${JSON.stringify(execution)}`,
  ).toMatch(/^ep-/);
  expect(execution.execution?.policyCode).toBe(policyCode);
  expect(execution.execution?.overallStatus).toBe('ALL_SUCCESS');
  expect(execution.execution?.actions).toHaveLength(1);
  expect(execution.execution?.actions?.[0]).toMatchObject({
    ruleCode: 'R-1',
    type: 'SEND_IM',
    status: 'SUCCESS',
    resultPayload: {
      channel: 'im',
      sentCount: 1,
      ruleCode: 'R-1',
      modelCode: targetKey,
      recordPid: expectedRecordPid,
    },
  });
  expect(execution.execution?.actions?.[0]?.resultPayload?.targetUserIds).toEqual(
    expect.arrayContaining([Number(imRecipientId)]),
  );
  expect(execution.execution?.actions?.[0]?.resultPayload?.messageIds).toHaveLength(1);
  expect(execution.execution?.actions?.[0]?.resultPayload?.conversationIds).toHaveLength(1);
  await expect(page.getByTestId('epd-action-execution-results')).toBeVisible({ timeout: 10_000 });
  await expect(page.getByTestId('epd-action-execution-0')).toContainText('发送 IM 消息');
  await expect(page.getByTestId('epd-action-execution-0')).toContainText('成功');
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText('发送数');
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText('1');
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText(expectedRecordPid);

  const actionLogs = await readApi<ActionLogRecord[]>(
    await page.request.get('/api/event-policy/action-logs', {
      params: { policyCode, correlationId: correlationId!, size: '20' },
    }),
  );
  expect(actionLogs).toHaveLength(1);
  const actionLog = actionLogs[0];
  expect(actionLog).toMatchObject({
    policyCode,
    correlationId,
    ruleCode: 'R-1',
    actionType: 'SEND_IM',
    status: 'SUCCESS',
    resultPayload: {
      channel: 'im',
      sentCount: 1,
      ruleCode: 'R-1',
      modelCode: targetKey,
      recordPid: expectedRecordPid,
    },
  });
  expect(actionLog.resultPayload?.targetUserIds).toEqual(
    expect.arrayContaining([Number(imRecipientId)]),
  );
  const conversationIds = actionLog.resultPayload?.conversationIds as number[] | undefined;
  const messageIds = actionLog.resultPayload?.messageIds as number[] | undefined;
  expect(
    conversationIds,
    `SEND_IM result must expose conversationIds: ${JSON.stringify(actionLog)}`,
  ).toHaveLength(1);
  expect(
    messageIds,
    `SEND_IM result must expose messageIds: ${JSON.stringify(actionLog)}`,
  ).toHaveLength(1);
  expect(actionLog.actionPayload).toMatchObject({
    ruleCode: 'R-1',
    type: 'SEND_IM',
    target: `USER:${imRecipientId}`,
    payload: {
      channel: 'im',
      content: 'EventPolicy IM ${record.data.status} ${record.recordPid}',
    },
  });

  const messages = await readApi<ImMessageRecord[]>(
    await page.request.get(`/api/im/conversations/${conversationIds![0]}/messages`, {
      params: { limit: '10' },
    }),
  );
  const imMessage = messages.find((message) => message.id === messageIds![0]);
  expect(
    imMessage,
    `SEND_IM bot message must be queryable via IM API: ${JSON.stringify(messages)}`,
  ).toBeTruthy();
  expect(imMessage).toMatchObject({
    id: messageIds![0],
    conversationId: conversationIds![0],
    senderType: 'system',
    content: expectedContent,
  });
  expect(JSON.stringify(imMessage?.cardPayload)).toContain('SEND_IM');
  expect(JSON.stringify(imMessage?.cardPayload)).toContain(expectedRecordPid);

  const eventPolicyTraceLink = page.getByTestId('epd-open-trace');
  await expect(eventPolicyTraceLink).toBeVisible({ timeout: 10_000 });
  const traceActionResponse = page.waitForResponse(
    (response) => {
      if (response.status() !== 200 || !response.url().includes('/api/event-policy/action-logs')) {
        return false;
      }
      const url = new URL(response.url());
      return (
        url.searchParams.get('policyCode') === policyCode &&
        url.searchParams.get('correlationId') === correlationId
      );
    },
    { timeout: 20_000 },
  );
  await eventPolicyTraceLink.click();
  await expect(page).toHaveURL(/\/p\/decisionops_execution_logs/);
  await readApi<ActionLogRecord[]>(await traceActionResponse);
  await expect(page.getByTestId('execution-log-trace-block')).toBeVisible({ timeout: 20_000 });
  const linkedActionEvidence = page.getByTestId('elta-linked-action-evidence');
  await expect(linkedActionEvidence).toBeVisible({ timeout: 20_000 });
  await expect(linkedActionEvidence).toContainText('发送 IM 消息');
  await expect(linkedActionEvidence).toContainText('成功');
  await expect(linkedActionEvidence).toContainText('发送数 1');
  await expect(linkedActionEvidence).toContainText(expectedRecordPid);
  await expect(linkedActionEvidence).toContainText('尝试 1/3');

  await page.screenshot({
    path: testInfo.outputPath('event-policy-send-im-trace-action-evidence.png'),
    fullPage: true,
  });
});

test('EventPolicy CREATE_TASK action creates inbox task and linked Trace evidence @golden', async ({
  page,
}, testInfo) => {
  await loginViaUI(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
  await expect(page).not.toHaveURL(/\/login(?:$|\?)/);
  const currentUser = await readApi<CurrentUserInfo>(await page.request.get('/api/auth/me'));
  const assigneeUserId = currentUser.user?.id;
  if (!assigneeUserId) {
    throw new Error(
      `Current user id is required for CREATE_TASK action assignee: ${JSON.stringify(currentUser)}`,
    );
  }
  expect(assigneeUserId).toMatch(/^\d+$/);

  const suffix = uniqueId('ep_task').replace(/[^a-zA-Z0-9_]/g, '_');
  const policyCode = `codex_ep_task_${suffix}`;
  const targetKey = `complaint_task_${suffix}`;
  const expectedRecordPid = `TEST-${policyCode}`;
  const expectedTitle = `EventPolicy task ${expectedRecordPid}`;

  await openEventPolicyListFromSidebar(page);
  await expect(page.getByTestId('event-policy-actions-block')).toBeVisible({ timeout: 15_000 });
  await page.getByTestId('epa-new-policy').click();
  await expect(page.getByTestId('epa-editor')).toBeVisible({ timeout: 10_000 });
  await page.getByLabel('policy-code').fill(policyCode);
  await page.getByLabel('policy-name').fill(`Codex EventPolicy Task ${suffix}`);
  await page.getByLabel('policy-event-type').fill('FORM_SUBMITTED');
  await page.getByLabel('policy-target-type').fill('FORM');
  await page.getByLabel('policy-target-key').fill(targetKey);
  await page.getByTestId('epa-save-policy').click();
  await expect(page).toHaveURL(new RegExp(`/p/decisionops_event_policies/view/${policyCode}`), {
    timeout: 15_000,
  });

  await page.getByTestId('epa-open-designer').click();
  await expect(page).toHaveURL(/\/p\/decisionops_event_policy_designer\?policyCode=/, {
    timeout: 15_000,
  });
  await expect(page.getByTestId('event-policy-designer-block')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByTestId('epd-workflow')).toBeVisible();
  await expect(page.getByTestId('epd-abnormal-actions')).toContainText('无异常动作');

  await page.getByTestId('epd-step-rules').click();
  await expect(page.getByTestId('policy-rules-editor')).toBeVisible();
  await page.getByTestId('cb-add').click();
  await expect(
    page.locator('select[aria-label="field-0"] option[value="record:data.priority"]'),
  ).toHaveCount(1, {
    timeout: 15_000,
  });
  await page.getByLabel('field-0').selectOption('record:data.priority');
  await page.getByLabel('operator-0').selectOption('EQ');
  await page.getByLabel('value-0').selectOption('HIGH');

  await page.getByTestId('epd-step-actions').click();
  await page.getByTestId('epd-add-action').click();
  await expect(
    page.locator('select[aria-label="action-type-0"] option[value="CREATE_TASK"]'),
  ).toContainText('创建任务', {
    timeout: 10_000,
  });
  await page.getByLabel('action-type-0').selectOption('CREATE_TASK');
  await expect(page.getByTestId('epd-abnormal-actions')).toContainText('无异常动作');
  await expect(page.getByTestId('epd-action-schema-0')).toContainText('任务标题');
  await expect(page.getByTestId('epd-action-schema-0')).toContainText('处理人表达式');
  await page
    .getByLabel('action-field-0-payload.title')
    .fill('EventPolicy task ${record.recordPid}');
  await page.getByLabel('action-field-0-payload.assignee').fill(`USER:${assigneeUserId}`);

  await page.screenshot({
    path: testInfo.outputPath('event-policy-create-task-configured.png'),
    fullPage: true,
  });

  await page.getByTestId('epd-step-publish').click();
  const draftResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes(`/api/event-policy/definitions/${policyCode}/versions`),
    { timeout: 15_000 },
  );
  await page.getByTestId('epd-save-draft').click();
  const draft = await readApi<EventPolicyVersion>(await draftResponsePromise);
  expect(draft.pid).toBeTruthy();
  await expect(page.getByTestId('epd-publish-status')).toContainText(/DRAFT|草稿/i, {
    timeout: 10_000,
  });

  const validateResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes(`/api/event-policy/versions/${draft.pid}/validate`),
    { timeout: 15_000 },
  );
  await page.getByTestId('epd-validate-version').click();
  await expect((await readApi<EventPolicyVersion>(await validateResponsePromise)).status).toBe(
    'VALIDATED',
  );

  const publishResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes(`/api/event-policy/versions/${draft.pid}/publish`),
    { timeout: 15_000 },
  );
  await page.getByTestId('epd-publish-version').click();
  await expect((await readApi<EventPolicyVersion>(await publishResponsePromise)).status).toBe(
    'PUBLISHED',
  );
  await expect(page.getByTestId('epd-publish-status')).toContainText(/PUBLISHED|已发布/i, {
    timeout: 10_000,
  });

  await page.getByTestId('epd-step-test').click();
  const runButtonResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes('/api/event-policy/run-and-execute'),
    { timeout: 15_000 },
  );
  await page.getByTestId('epd-run-published').click();
  const execution = await readApi<EventPolicyExecutionResult>(await runButtonResponse);
  expect(execution.policy?.status).toBe('MATCHED');
  expect(execution.policy?.matchedRuleCodes).toContain('R-1');
  const correlationId = execution.policy?.correlationId;
  expect(
    correlationId,
    `run-and-execute must return correlationId: ${JSON.stringify(execution)}`,
  ).toMatch(/^ep-/);
  expect(execution.execution?.policyCode).toBe(policyCode);
  expect(execution.execution?.overallStatus).toBe('ALL_SUCCESS');
  expect(execution.execution?.actions).toHaveLength(1);
  expect(execution.execution?.actions?.[0]).toMatchObject({
    ruleCode: 'R-1',
    type: 'CREATE_TASK',
    status: 'SUCCESS',
    resultPayload: {
      itemType: 'task',
      createdCount: 1,
      ruleCode: 'R-1',
      modelCode: targetKey,
      recordPid: expectedRecordPid,
    },
  });
  expect(execution.execution?.actions?.[0]?.resultPayload?.assigneeUserIds).toEqual(
    expect.arrayContaining([Number(assigneeUserId)]),
  );
  expect(execution.execution?.actions?.[0]?.resultPayload?.inboxItemIds).toHaveLength(1);
  await expect(page.getByTestId('epd-action-execution-results')).toBeVisible({ timeout: 10_000 });
  await expect(page.getByTestId('epd-action-execution-0')).toContainText('创建任务');
  await expect(page.getByTestId('epd-action-execution-0')).toContainText('成功');
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText('创建数');
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText('待办记录');
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText('处理人');
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText('待办类型');
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText('任务');
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText('业务记录');
  await expect(page.getByTestId('epd-action-result-payload-0')).not.toContainText('createdCount');
  await expect(page.getByTestId('epd-action-result-payload-0')).not.toContainText('inboxItemIds');
  await expect(page.getByTestId('epd-action-result-payload-0')).not.toContainText('modelCode');
  await expect(page.getByTestId('epd-action-result-payload-0')).not.toContainText('recordPid');
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText(expectedRecordPid);

  const actionLogs = await readApi<ActionLogRecord[]>(
    await page.request.get('/api/event-policy/action-logs', {
      params: { policyCode, correlationId: correlationId!, size: '20' },
    }),
  );
  expect(actionLogs).toHaveLength(1);
  const actionLog = actionLogs[0];
  expect(actionLog).toMatchObject({
    policyCode,
    correlationId,
    ruleCode: 'R-1',
    actionType: 'CREATE_TASK',
    status: 'SUCCESS',
    resultPayload: {
      itemType: 'task',
      createdCount: 1,
      ruleCode: 'R-1',
      modelCode: targetKey,
      recordPid: expectedRecordPid,
    },
  });
  expect(actionLog.resultPayload?.assigneeUserIds).toEqual(
    expect.arrayContaining([Number(assigneeUserId)]),
  );
  const inboxItemIds = actionLog.resultPayload?.inboxItemIds as number[] | undefined;
  expect(
    inboxItemIds,
    `CREATE_TASK result must expose inboxItemIds: ${JSON.stringify(actionLog)}`,
  ).toHaveLength(1);
  expect(actionLog.actionPayload).toMatchObject({
    ruleCode: 'R-1',
    type: 'CREATE_TASK',
    payload: {
      title: 'EventPolicy task ${record.recordPid}',
      assignee: `USER:${assigneeUserId}`,
    },
  });

  const inboxPage = await readApi<PageRecords<InboxItemRecord>>(
    await page.request.get('/api/inbox', {
      params: { itemType: 'task', pageNum: '1', pageSize: '50' },
    }),
  );
  const inboxItem = inboxPage.records?.find((item) => item.id === inboxItemIds![0]);
  expect(
    inboxItem,
    `CREATE_TASK inbox item must be queryable via inbox API: ${JSON.stringify(inboxPage)}`,
  ).toBeTruthy();
  expect(inboxItem).toMatchObject({
    id: inboxItemIds![0],
    itemType: 'task',
    title: expectedTitle,
    sourceType: 'event_policy',
    sourceId: 'R-1',
    sourceModel: targetKey,
    sourceRecordPid: expectedRecordPid,
    deepLink: `/p/${targetKey}/view/${expectedRecordPid}`,
  });
  expect(inboxItem?.cardData).toMatchObject({
    actionType: 'CREATE_TASK',
    ruleCode: 'R-1',
    title: expectedTitle,
    recordPid: expectedRecordPid,
  });
  expect(inboxItem?.clientItemId).toBeTruthy();
  expect(inboxItem!.clientItemId!.length).toBeLessThanOrEqual(128);

  const eventPolicyTraceLink = page.getByTestId('epd-open-trace');
  await expect(eventPolicyTraceLink).toBeVisible({ timeout: 10_000 });
  const traceActionResponse = page.waitForResponse(
    (response) => {
      if (response.status() !== 200 || !response.url().includes('/api/event-policy/action-logs')) {
        return false;
      }
      const url = new URL(response.url());
      return (
        url.searchParams.get('policyCode') === policyCode &&
        url.searchParams.get('correlationId') === correlationId
      );
    },
    { timeout: 20_000 },
  );
  await eventPolicyTraceLink.click();
  await expect(page).toHaveURL(/\/p\/decisionops_execution_logs/);
  await readApi<ActionLogRecord[]>(await traceActionResponse);
  await expect(page.getByTestId('execution-log-trace-block')).toBeVisible({ timeout: 20_000 });
  const linkedActionEvidence = page.getByTestId('elta-linked-action-evidence');
  await expect(linkedActionEvidence).toBeVisible({ timeout: 20_000 });
  await expect(linkedActionEvidence).toContainText('创建任务');
  await expect(linkedActionEvidence).toContainText('成功');
  await expect(linkedActionEvidence).toContainText('创建数 1');
  await expect(linkedActionEvidence).toContainText('待办记录');
  await expect(linkedActionEvidence).toContainText('待办类型 任务');
  await expect(linkedActionEvidence).toContainText('投递方式 待办');
  await expect(linkedActionEvidence).toContainText('模型');
  await expect(linkedActionEvidence).toContainText('业务记录');
  await expect(linkedActionEvidence).toContainText(expectedRecordPid);
  await expect(linkedActionEvidence).toContainText('尝试 1/3');
  await expect(linkedActionEvidence).not.toContainText('createdCount');
  await expect(linkedActionEvidence).not.toContainText('inboxItemIds');
  await expect(linkedActionEvidence).not.toContainText('modelCode');
  await expect(linkedActionEvidence).not.toContainText('recordPid');
  await expect(linkedActionEvidence).not.toContainText('attemptCount');
  await expect(linkedActionEvidence).not.toContainText('maxAttempts');

  await page.screenshot({
    path: testInfo.outputPath('event-policy-create-task-trace-action-evidence.png'),
    fullPage: true,
  });
});

test('EventPolicy CC_TASK action creates inbox mention and linked Trace evidence @golden', async ({
  page,
}, testInfo) => {
  await loginViaUI(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
  await expect(page).not.toHaveURL(/\/login(?:$|\?)/);
  const currentUser = await readApi<CurrentUserInfo>(await page.request.get('/api/auth/me'));
  const targetUserId = currentUser.user?.id;
  if (!targetUserId) {
    throw new Error(
      `Current user id is required for CC_TASK action target: ${JSON.stringify(currentUser)}`,
    );
  }
  expect(targetUserId).toMatch(/^\d+$/);

  const suffix = uniqueId('ep_cc').replace(/[^a-zA-Z0-9_]/g, '_');
  const policyCode = `codex_ep_cc_${suffix}`;
  const targetKey = `complaint_cc_${suffix}`;
  const expectedRecordPid = `TEST-${policyCode}`;
  const expectedTitle = '任务抄送';
  const expectedMessage = `EventPolicy cc ${expectedRecordPid}`;

  await openEventPolicyListFromSidebar(page);
  await expect(page.getByTestId('event-policy-actions-block')).toBeVisible({ timeout: 15_000 });
  await page.getByTestId('epa-new-policy').click();
  await expect(page.getByTestId('epa-editor')).toBeVisible({ timeout: 10_000 });
  await page.getByLabel('policy-code').fill(policyCode);
  await page.getByLabel('policy-name').fill(`Codex EventPolicy CC ${suffix}`);
  await page.getByLabel('policy-event-type').fill('FORM_SUBMITTED');
  await page.getByLabel('policy-target-type').fill('FORM');
  await page.getByLabel('policy-target-key').fill(targetKey);
  await page.getByTestId('epa-save-policy').click();
  await expect(page).toHaveURL(new RegExp(`/p/decisionops_event_policies/view/${policyCode}`), {
    timeout: 15_000,
  });

  await page.getByTestId('epa-open-designer').click();
  await expect(page).toHaveURL(/\/p\/decisionops_event_policy_designer\?policyCode=/, {
    timeout: 15_000,
  });
  await expect(page.getByTestId('event-policy-designer-block')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByTestId('epd-workflow')).toBeVisible();
  await expect(page.getByTestId('epd-abnormal-actions')).toContainText('无异常动作');

  await page.getByTestId('epd-step-rules').click();
  await expect(page.getByTestId('policy-rules-editor')).toBeVisible();
  await page.getByTestId('cb-add').click();
  await expect(
    page.locator('select[aria-label="field-0"] option[value="record:data.priority"]'),
  ).toHaveCount(1, {
    timeout: 15_000,
  });
  await page.getByLabel('field-0').selectOption('record:data.priority');
  await page.getByLabel('operator-0').selectOption('EQ');
  await page.getByLabel('value-0').selectOption('HIGH');

  await page.getByTestId('epd-step-actions').click();
  await page.getByTestId('epd-add-action').click();
  await expect(
    page.locator('select[aria-label="action-type-0"] option[value="CC_TASK"]'),
  ).toContainText('抄送任务', {
    timeout: 10_000,
  });
  await page.getByLabel('action-type-0').selectOption('CC_TASK');
  await expect(page.getByTestId('epd-abnormal-actions')).toContainText('无异常动作');
  await expect(page.getByTestId('epd-action-schema-0')).toContainText('抄送接收人表达式');
  await expect(page.getByTestId('epd-action-schema-0')).toContainText('抄送消息');
  await page.getByLabel('action-target-0').fill(`USER:${targetUserId}`);
  await page
    .getByLabel('action-field-0-payload.message')
    .fill('EventPolicy cc ${record.recordPid}');

  await page.screenshot({
    path: testInfo.outputPath('event-policy-cc-task-configured.png'),
    fullPage: true,
  });

  await page.getByTestId('epd-step-publish').click();
  const draftResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes(`/api/event-policy/definitions/${policyCode}/versions`),
    { timeout: 15_000 },
  );
  await page.getByTestId('epd-save-draft').click();
  const draft = await readApi<EventPolicyVersion>(await draftResponsePromise);
  expect(draft.pid).toBeTruthy();
  await expect(page.getByTestId('epd-publish-status')).toContainText(/DRAFT|草稿/i, {
    timeout: 10_000,
  });

  const validateResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes(`/api/event-policy/versions/${draft.pid}/validate`),
    { timeout: 15_000 },
  );
  await page.getByTestId('epd-validate-version').click();
  await expect((await readApi<EventPolicyVersion>(await validateResponsePromise)).status).toBe(
    'VALIDATED',
  );

  const publishResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes(`/api/event-policy/versions/${draft.pid}/publish`),
    { timeout: 15_000 },
  );
  await page.getByTestId('epd-publish-version').click();
  await expect((await readApi<EventPolicyVersion>(await publishResponsePromise)).status).toBe(
    'PUBLISHED',
  );
  await expect(page.getByTestId('epd-publish-status')).toContainText(/PUBLISHED|已发布/i, {
    timeout: 10_000,
  });

  await page.getByTestId('epd-step-test').click();
  const runButtonResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes('/api/event-policy/run-and-execute'),
    { timeout: 15_000 },
  );
  await page.getByTestId('epd-run-published').click();
  const execution = await readApi<EventPolicyExecutionResult>(await runButtonResponse);
  expect(execution.policy?.status).toBe('MATCHED');
  expect(execution.policy?.matchedRuleCodes).toContain('R-1');
  const correlationId = execution.policy?.correlationId;
  expect(
    correlationId,
    `run-and-execute must return correlationId: ${JSON.stringify(execution)}`,
  ).toMatch(/^ep-/);
  expect(execution.execution?.policyCode).toBe(policyCode);
  expect(execution.execution?.overallStatus).toBe('ALL_SUCCESS');
  expect(execution.execution?.actions).toHaveLength(1);
  expect(execution.execution?.actions?.[0]).toMatchObject({
    ruleCode: 'R-1',
    type: 'CC_TASK',
    status: 'SUCCESS',
    resultPayload: {
      delivery: 'inbox',
      itemType: 'mention',
      ccCount: 1,
      ruleCode: 'R-1',
      modelCode: targetKey,
      recordPid: expectedRecordPid,
    },
  });
  expect(execution.execution?.actions?.[0]?.resultPayload?.targetUserIds).toEqual(
    expect.arrayContaining([Number(targetUserId)]),
  );
  expect(execution.execution?.actions?.[0]?.resultPayload?.inboxItemIds).toHaveLength(1);
  await expect(page.getByTestId('epd-action-execution-results')).toBeVisible({ timeout: 10_000 });
  await expect(page.getByTestId('epd-action-execution-0')).toContainText('抄送任务');
  await expect(page.getByTestId('epd-action-execution-0')).toContainText('成功');
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText('抄送数');
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText('接收用户');
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText('待办记录');
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText('待办类型');
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText('抄送任务');
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText('投递方式');
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText('待办');
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText(expectedRecordPid);
  await expect(page.getByTestId('epd-action-result-payload-0')).not.toContainText('ccCount');
  await expect(page.getByTestId('epd-action-result-payload-0')).not.toContainText('targetUserIds');
  await expect(page.getByTestId('epd-action-result-payload-0')).not.toContainText('inboxItemIds');
  await expect(page.getByTestId('epd-action-result-payload-0')).not.toContainText('modelCode');
  await expect(page.getByTestId('epd-action-result-payload-0')).not.toContainText('recordPid');

  const actionLogs = await readApi<ActionLogRecord[]>(
    await page.request.get('/api/event-policy/action-logs', {
      params: { policyCode, correlationId: correlationId!, size: '20' },
    }),
  );
  expect(actionLogs).toHaveLength(1);
  const actionLog = actionLogs[0];
  expect(actionLog).toMatchObject({
    policyCode,
    correlationId,
    ruleCode: 'R-1',
    actionType: 'CC_TASK',
    status: 'SUCCESS',
    resultPayload: {
      delivery: 'inbox',
      itemType: 'mention',
      ccCount: 1,
      ruleCode: 'R-1',
      modelCode: targetKey,
      recordPid: expectedRecordPid,
    },
  });
  expect(actionLog.resultPayload?.targetUserIds).toEqual(
    expect.arrayContaining([Number(targetUserId)]),
  );
  const inboxItemIds = actionLog.resultPayload?.inboxItemIds as number[] | undefined;
  expect(
    inboxItemIds,
    `CC_TASK result must expose inboxItemIds: ${JSON.stringify(actionLog)}`,
  ).toHaveLength(1);
  expect(actionLog.actionPayload).toMatchObject({
    ruleCode: 'R-1',
    type: 'CC_TASK',
    target: `USER:${targetUserId}`,
    payload: {
      message: 'EventPolicy cc ${record.recordPid}',
    },
  });

  const inboxPage = await readApi<PageRecords<InboxItemRecord>>(
    await page.request.get('/api/inbox', {
      params: { itemType: 'mention', pageNum: '1', pageSize: '50' },
    }),
  );
  const inboxItem = inboxPage.records?.find((item) => item.id === inboxItemIds![0]);
  expect(
    inboxItem,
    `CC_TASK inbox mention must be queryable via inbox API: ${JSON.stringify(inboxPage)}`,
  ).toBeTruthy();
  expect(inboxItem).toMatchObject({
    id: inboxItemIds![0],
    itemType: 'mention',
    title: expectedTitle,
    summary: expectedMessage,
    sourceType: 'event_policy',
    sourceId: 'R-1',
    sourceModel: targetKey,
    sourceRecordPid: expectedRecordPid,
    deepLink: `/p/${targetKey}/view/${expectedRecordPid}`,
  });
  expect(inboxItem?.cardData).toMatchObject({
    actionType: 'CC_TASK',
    ruleCode: 'R-1',
    title: expectedTitle,
    message: expectedMessage,
    recordPid: expectedRecordPid,
  });
  expect(inboxItem?.clientItemId).toBeTruthy();
  expect(inboxItem!.clientItemId!.length).toBeLessThanOrEqual(128);

  const eventPolicyTraceLink = page.getByTestId('epd-open-trace');
  await expect(eventPolicyTraceLink).toBeVisible({ timeout: 10_000 });
  const traceActionResponse = page.waitForResponse(
    (response) => {
      if (response.status() !== 200 || !response.url().includes('/api/event-policy/action-logs')) {
        return false;
      }
      const url = new URL(response.url());
      return (
        url.searchParams.get('policyCode') === policyCode &&
        url.searchParams.get('correlationId') === correlationId
      );
    },
    { timeout: 20_000 },
  );
  await eventPolicyTraceLink.click();
  await expect(page).toHaveURL(/\/p\/decisionops_execution_logs/);
  await readApi<ActionLogRecord[]>(await traceActionResponse);
  await expect(page.getByTestId('execution-log-trace-block')).toBeVisible({ timeout: 20_000 });
  const linkedActionEvidence = page.getByTestId('elta-linked-action-evidence');
  await expect(linkedActionEvidence).toBeVisible({ timeout: 20_000 });
  await expect(linkedActionEvidence).toContainText('抄送任务');
  await expect(linkedActionEvidence).toContainText('成功');
  await expect(linkedActionEvidence).toContainText('抄送数 1');
  await expect(linkedActionEvidence).toContainText('接收用户');
  await expect(linkedActionEvidence).toContainText('待办记录');
  await expect(linkedActionEvidence).toContainText('待办类型 抄送任务');
  await expect(linkedActionEvidence).toContainText('投递方式 待办');
  await expect(linkedActionEvidence).toContainText('模型');
  await expect(linkedActionEvidence).toContainText('业务记录');
  await expect(linkedActionEvidence).toContainText(expectedRecordPid);
  await expect(linkedActionEvidence).toContainText('尝试 1/3');
  await expect(linkedActionEvidence).not.toContainText('ccCount');
  await expect(linkedActionEvidence).not.toContainText('targetUserIds');
  await expect(linkedActionEvidence).not.toContainText('inboxItemIds');
  await expect(linkedActionEvidence).not.toContainText('modelCode');
  await expect(linkedActionEvidence).not.toContainText('recordPid');
  await expect(linkedActionEvidence).not.toContainText('attemptCount');
  await expect(linkedActionEvidence).not.toContainText('maxAttempts');

  await page.screenshot({
    path: testInfo.outputPath('event-policy-cc-task-trace-action-evidence.png'),
    fullPage: true,
  });
});

test('EventPolicy START_PROCESS action starts a real BPM process and linked Trace evidence @golden', async ({
  page,
}, testInfo) => {
  await loginViaUI(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
  await expect(page).not.toHaveURL(/\/login(?:$|\?)/);

  const suffix = uniqueId('ep_start_process')
    .replace(/[^a-zA-Z0-9_]/g, '_')
    .toLowerCase();
  const processKey = `ep_start_process_${suffix}`;
  await createAndDeployStartProcess(page, processKey);

  const policyCode = `codex_ep_start_process_${suffix}`;
  const targetKey = `complaint_start_process_${suffix}`;
  const expectedRecordPid = `TEST-${policyCode}`;

  await openEventPolicyListFromSidebar(page);
  await expect(page.getByTestId('event-policy-actions-block')).toBeVisible({ timeout: 15_000 });
  await page.getByTestId('epa-new-policy').click();
  await expect(page.getByTestId('epa-editor')).toBeVisible({ timeout: 10_000 });
  await page.getByLabel('policy-code').fill(policyCode);
  await page.getByLabel('policy-name').fill(`Codex EventPolicy Start Process ${suffix}`);
  await page.getByLabel('policy-event-type').fill('FORM_SUBMITTED');
  await page.getByLabel('policy-target-type').fill('FORM');
  await page.getByLabel('policy-target-key').fill(targetKey);
  await page.getByTestId('epa-save-policy').click();
  await expect(page).toHaveURL(new RegExp(`/p/decisionops_event_policies/view/${policyCode}`), {
    timeout: 15_000,
  });

  await page.getByTestId('epa-open-designer').click();
  await expect(page).toHaveURL(/\/p\/decisionops_event_policy_designer\?policyCode=/, {
    timeout: 15_000,
  });
  await expect(page.getByTestId('event-policy-designer-block')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByTestId('epd-workflow')).toBeVisible();
  await expect(page.getByTestId('epd-abnormal-actions')).toContainText('无异常动作');

  await page.getByTestId('epd-step-rules').click();
  await expect(page.getByTestId('policy-rules-editor')).toBeVisible();
  await page.getByTestId('cb-add').click();
  await expect(
    page.locator('select[aria-label="field-0"] option[value="record:data.priority"]'),
  ).toHaveCount(1, {
    timeout: 15_000,
  });
  await page.getByLabel('field-0').selectOption('record:data.priority');
  await page.getByLabel('operator-0').selectOption('EQ');
  await page.getByLabel('value-0').selectOption('HIGH');

  await page.getByTestId('epd-step-actions').click();
  await page.getByTestId('epd-add-action').click();
  await expect(
    page.locator('select[aria-label="action-type-0"] option[value="START_PROCESS"]'),
  ).toContainText('启动流程', {
    timeout: 10_000,
  });
  await page.getByLabel('action-type-0').selectOption('START_PROCESS');
  await expect(page.getByTestId('epd-abnormal-actions')).toContainText('无异常动作');
  await expect(page.getByTestId('epd-action-schema-0')).toContainText('流程标识');
  await expect(page.getByTestId('epd-action-schema-0')).not.toContainText('流程定义 ID');
  await expect(page.getByTestId('epd-action-schema-0')).toContainText(
    '业务主键，默认使用业务记录 PID',
  );
  await expect(page.getByTestId('epd-action-schema-0')).toContainText('流程变量');
  await page.getByLabel('action-field-0-payload.processDefinitionId').fill(processKey);
  await page
    .getByLabel('action-field-0-payload.variables')
    .fill(JSON.stringify({ source: 'event-policy-golden', priority: 'HIGH' }, null, 2));

  await page.screenshot({
    path: testInfo.outputPath('event-policy-start-process-configured.png'),
    fullPage: true,
  });

  await page.getByTestId('epd-step-publish').click();
  const draftResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes(`/api/event-policy/definitions/${policyCode}/versions`),
    { timeout: 15_000 },
  );
  await page.getByTestId('epd-save-draft').click();
  const draft = await readApi<EventPolicyVersion>(await draftResponsePromise);
  expect(draft.pid).toBeTruthy();
  await expect(page.getByTestId('epd-publish-status')).toContainText(/DRAFT|草稿/i, {
    timeout: 10_000,
  });

  const validateResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes(`/api/event-policy/versions/${draft.pid}/validate`),
    { timeout: 15_000 },
  );
  await page.getByTestId('epd-validate-version').click();
  await expect((await readApi<EventPolicyVersion>(await validateResponsePromise)).status).toBe(
    'VALIDATED',
  );

  const publishResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes(`/api/event-policy/versions/${draft.pid}/publish`),
    { timeout: 15_000 },
  );
  await page.getByTestId('epd-publish-version').click();
  await expect((await readApi<EventPolicyVersion>(await publishResponsePromise)).status).toBe(
    'PUBLISHED',
  );
  await expect(page.getByTestId('epd-publish-status')).toContainText(/PUBLISHED|已发布/i, {
    timeout: 10_000,
  });

  await page.getByTestId('epd-step-test').click();
  const runButtonResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes('/api/event-policy/run-and-execute'),
    { timeout: 15_000 },
  );
  await page.getByTestId('epd-run-published').click();
  const execution = await readApi<EventPolicyExecutionResult>(await runButtonResponse);
  expect(execution.policy?.status).toBe('MATCHED');
  expect(execution.policy?.matchedRuleCodes).toContain('R-1');
  const correlationId = execution.policy?.correlationId;
  expect(
    correlationId,
    `run-and-execute must return correlationId: ${JSON.stringify(execution)}`,
  ).toMatch(/^ep-/);
  expect(execution.execution?.policyCode).toBe(policyCode);
  expect(execution.execution?.overallStatus).toBe('ALL_SUCCESS');
  expect(execution.execution?.actions).toHaveLength(1);
  expect(execution.execution?.actions?.[0]).toMatchObject({
    ruleCode: 'R-1',
    type: 'START_PROCESS',
    status: 'SUCCESS',
    resultPayload: {
      processDefinitionId: processKey,
      businessKey: expectedRecordPid,
      recordPid: expectedRecordPid,
    },
  });
  const processInstanceId = String(
    execution.execution?.actions?.[0]?.resultPayload?.processInstanceId ?? '',
  );
  expect(
    processInstanceId,
    `START_PROCESS must return processInstanceId: ${JSON.stringify(execution)}`,
  ).toMatch(/^\d+$/);
  await expect(page.getByTestId('epd-action-execution-results')).toBeVisible({ timeout: 10_000 });
  await expect(page.getByTestId('epd-action-execution-0')).toContainText('启动流程');
  await expect(page.getByTestId('epd-action-execution-0')).toContainText('成功');
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText('流程标识');
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText(processKey);
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText('流程实例');
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText(processInstanceId);
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText('业务主键');
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText(expectedRecordPid);
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText('业务记录');
  await expect(page.getByTestId('epd-action-result-payload-0')).not.toContainText(
    'processDefinitionId',
  );
  await expect(page.getByTestId('epd-action-result-payload-0')).not.toContainText(
    'processInstanceId',
  );
  await expect(page.getByTestId('epd-action-result-payload-0')).not.toContainText('businessKey');

  const bpmStatus = await readApi<BpmProcessStatusRecord | null>(
    await page.request.get('/api/bpm/process-instances/by-business-key/status', {
      params: { businessKey: expectedRecordPid, processKey },
    }),
  );
  expect(
    bpmStatus,
    `BPM status must be queryable by business key ${expectedRecordPid}: ${JSON.stringify(bpmStatus)}`,
  ).toBeTruthy();
  expect(bpmStatus?.instanceId).toBe(processInstanceId);
  expect(bpmStatus?.processDefinitionId).toBe(processKey);
  expect(bpmStatus?.variables).toMatchObject({
    source: 'event-policy-golden',
    priority: 'HIGH',
    recordPid: expectedRecordPid,
  });

  const actionLogs = await readApi<ActionLogRecord[]>(
    await page.request.get('/api/event-policy/action-logs', {
      params: { policyCode, correlationId: correlationId!, size: '20' },
    }),
  );
  expect(actionLogs).toHaveLength(1);
  const actionLog = actionLogs[0];
  expect(actionLog).toMatchObject({
    policyCode,
    correlationId,
    ruleCode: 'R-1',
    actionType: 'START_PROCESS',
    status: 'SUCCESS',
    resultPayload: {
      processDefinitionId: processKey,
      processInstanceId,
      businessKey: expectedRecordPid,
      recordPid: expectedRecordPid,
    },
  });
  expect(actionLog.actionPayload).toMatchObject({
    ruleCode: 'R-1',
    type: 'START_PROCESS',
    payload: {
      processDefinitionId: processKey,
      variables: {
        source: 'event-policy-golden',
        priority: 'HIGH',
      },
    },
  });

  const eventPolicyTraceLink = page.getByTestId('epd-open-trace');
  await expect(eventPolicyTraceLink).toBeVisible({ timeout: 10_000 });
  const traceActionResponse = page.waitForResponse(
    (response) => {
      if (response.status() !== 200 || !response.url().includes('/api/event-policy/action-logs')) {
        return false;
      }
      const url = new URL(response.url());
      return (
        url.searchParams.get('policyCode') === policyCode &&
        url.searchParams.get('correlationId') === correlationId
      );
    },
    { timeout: 20_000 },
  );
  await eventPolicyTraceLink.click();
  await expect(page).toHaveURL(/\/p\/decisionops_execution_logs/);
  await readApi<ActionLogRecord[]>(await traceActionResponse);
  await expect(page.getByTestId('execution-log-trace-block')).toBeVisible({ timeout: 20_000 });
  const linkedActionEvidence = page.getByTestId('elta-linked-action-evidence');
  await expect(linkedActionEvidence).toBeVisible({ timeout: 20_000 });
  await expect(linkedActionEvidence).toContainText('启动流程');
  await expect(linkedActionEvidence).toContainText('成功');
  await expect(linkedActionEvidence).toContainText(`流程标识 ${processKey}`);
  await expect(linkedActionEvidence).toContainText(`流程实例 ${processInstanceId}`);
  await expect(linkedActionEvidence).toContainText(`业务主键 ${expectedRecordPid}`);
  await expect(linkedActionEvidence).toContainText(`业务记录 ${expectedRecordPid}`);
  await expect(linkedActionEvidence).toContainText('尝试 1/3');
  await expect(linkedActionEvidence).not.toContainText('processDefinitionId');
  await expect(linkedActionEvidence).not.toContainText('processInstanceId');
  await expect(linkedActionEvidence).not.toContainText('businessKey');
  await expect(linkedActionEvidence).not.toContainText('recordPid');

  await page.screenshot({
    path: testInfo.outputPath('event-policy-start-process-trace-action-evidence.png'),
    fullPage: true,
  });
});

test('EventPolicy START_PROCESS failure shows productized BPM failure Trace evidence @golden', async ({
  page,
}, testInfo) => {
  await loginViaUI(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
  await expect(page).not.toHaveURL(/\/login(?:$|\?)/);

  const suffix = uniqueId('ep_start_process_fail')
    .replace(/[^a-zA-Z0-9_]/g, '_')
    .toLowerCase();
  const missingProcessKey = `missing_ep_start_process_${suffix}`;
  const policyCode = `codex_ep_start_process_fail_${suffix}`;
  const targetKey = `complaint_start_process_fail_${suffix}`;
  const expectedRecordPid = `TEST-${policyCode}`;

  await openEventPolicyListFromSidebar(page);
  await expect(page.getByTestId('event-policy-actions-block')).toBeVisible({ timeout: 15_000 });
  await page.getByTestId('epa-new-policy').click();
  await expect(page.getByTestId('epa-editor')).toBeVisible({ timeout: 10_000 });
  await page.getByLabel('policy-code').fill(policyCode);
  await page.getByLabel('policy-name').fill(`Codex EventPolicy Start Process Failure ${suffix}`);
  await page.getByLabel('policy-event-type').fill('FORM_SUBMITTED');
  await page.getByLabel('policy-target-type').fill('FORM');
  await page.getByLabel('policy-target-key').fill(targetKey);
  await page.getByTestId('epa-save-policy').click();
  await expect(page).toHaveURL(new RegExp(`/p/decisionops_event_policies/view/${policyCode}`), {
    timeout: 15_000,
  });

  await page.getByTestId('epa-open-designer').click();
  await expect(page).toHaveURL(/\/p\/decisionops_event_policy_designer\?policyCode=/, {
    timeout: 15_000,
  });
  await expect(page.getByTestId('event-policy-designer-block')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByTestId('epd-workflow')).toBeVisible();
  await expect(page.getByTestId('epd-abnormal-actions')).toContainText('无异常动作');

  await page.getByTestId('epd-step-rules').click();
  await expect(page.getByTestId('policy-rules-editor')).toBeVisible();
  await page.getByTestId('cb-add').click();
  await expect(
    page.locator('select[aria-label="field-0"] option[value="record:data.priority"]'),
  ).toHaveCount(1, {
    timeout: 15_000,
  });
  await page.getByLabel('field-0').selectOption('record:data.priority');
  await page.getByLabel('operator-0').selectOption('EQ');
  await page.getByLabel('value-0').selectOption('HIGH');

  await page.getByTestId('epd-step-actions').click();
  await page.getByTestId('epd-add-action').click();
  await expect(
    page.locator('select[aria-label="action-type-0"] option[value="START_PROCESS"]'),
  ).toContainText('启动流程', {
    timeout: 10_000,
  });
  await page.getByLabel('action-type-0').selectOption('START_PROCESS');
  await expect(page.getByTestId('epd-abnormal-actions')).toContainText('无异常动作');
  await expect(page.getByTestId('epd-action-schema-0')).toContainText('流程标识');
  await page.getByLabel('action-field-0-payload.processDefinitionId').fill(missingProcessKey);
  await page
    .getByLabel('action-field-0-payload.variables')
    .fill(JSON.stringify({ source: 'event-policy-failure-golden', priority: 'HIGH' }, null, 2));

  await page.screenshot({
    path: testInfo.outputPath('event-policy-start-process-failure-configured.png'),
    fullPage: true,
  });

  await page.getByTestId('epd-step-publish').click();
  const draftResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes(`/api/event-policy/definitions/${policyCode}/versions`),
    { timeout: 15_000 },
  );
  await page.getByTestId('epd-save-draft').click();
  const draft = await readApi<EventPolicyVersion>(await draftResponsePromise);
  expect(draft.pid).toBeTruthy();
  await expect(page.getByTestId('epd-publish-status')).toContainText(/DRAFT|草稿/i, {
    timeout: 10_000,
  });

  const validateResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes(`/api/event-policy/versions/${draft.pid}/validate`),
    { timeout: 15_000 },
  );
  await page.getByTestId('epd-validate-version').click();
  await expect((await readApi<EventPolicyVersion>(await validateResponsePromise)).status).toBe(
    'VALIDATED',
  );

  const publishResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes(`/api/event-policy/versions/${draft.pid}/publish`),
    { timeout: 15_000 },
  );
  await page.getByTestId('epd-publish-version').click();
  await expect((await readApi<EventPolicyVersion>(await publishResponsePromise)).status).toBe(
    'PUBLISHED',
  );
  await expect(page.getByTestId('epd-publish-status')).toContainText(/PUBLISHED|已发布/i, {
    timeout: 10_000,
  });

  await page.getByTestId('epd-step-test').click();
  const runButtonResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes('/api/event-policy/run-and-execute'),
    { timeout: 15_000 },
  );
  await page.getByTestId('epd-run-published').click();
  const execution = await readApi<EventPolicyExecutionResult>(await runButtonResponse);
  expect(execution.policy?.status).toBe('MATCHED');
  expect(execution.policy?.matchedRuleCodes).toContain('R-1');
  const correlationId = execution.policy?.correlationId;
  expect(
    correlationId,
    `run-and-execute must return correlationId: ${JSON.stringify(execution)}`,
  ).toMatch(/^ep-/);
  expect(execution.execution?.policyCode).toBe(policyCode);
  expect(execution.execution?.overallStatus).toBe('FAILED');
  expect(execution.execution?.actions).toHaveLength(1);
  expect(execution.execution?.actions?.[0]).toMatchObject({
    ruleCode: 'R-1',
    type: 'START_PROCESS',
    status: 'FAILED',
    error: '流程启动失败：流程未部署或流程标识不存在',
    resultPayload: {
      failureReason: 'process_start_failed',
      processDefinitionId: missingProcessKey,
      businessKey: expectedRecordPid,
      recordPid: expectedRecordPid,
    },
  });
  await expect(page.getByTestId('epd-action-execution-results')).toBeVisible({ timeout: 10_000 });
  await expect(page.getByTestId('epd-action-execution-0')).toContainText('启动流程');
  await expect(page.getByTestId('epd-action-execution-0')).toContainText('失败');
  await expect(page.getByTestId('epd-action-execution-0')).toContainText(
    '流程启动失败：流程未部署或流程标识不存在',
  );
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText('失败原因');
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText('流程启动失败');
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText('流程标识');
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText(missingProcessKey);
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText('业务主键');
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText(expectedRecordPid);
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText('业务记录');
  await expect(page.getByTestId('epd-action-result-payload-0')).not.toContainText(
    'failureReason',
  );
  await expect(page.getByTestId('epd-action-result-payload-0')).not.toContainText(
    'process_start_failed',
  );
  await expect(page.getByTestId('epd-action-result-payload-0')).not.toContainText(
    'processDefinitionId',
  );
  await expect(page.getByTestId('epd-action-result-payload-0')).not.toContainText('businessKey');
  await expect(page.getByTestId('epd-action-result-payload-0')).not.toContainText('recordPid');
  await expect(page.getByTestId('epd-action-result-payload-0')).not.toContainText(
    'Process definition version',
  );

  const actionLogs = await readApi<ActionLogRecord[]>(
    await page.request.get('/api/event-policy/action-logs', {
      params: { policyCode, correlationId: correlationId!, size: '20' },
    }),
  );
  expect(actionLogs).toHaveLength(1);
  expect(actionLogs[0]).toMatchObject({
    policyCode,
    correlationId,
    ruleCode: 'R-1',
    actionType: 'START_PROCESS',
    status: 'FAILED',
    errorMessage: '流程启动失败：流程未部署或流程标识不存在',
    resultPayload: {
      failureReason: 'process_start_failed',
      processDefinitionId: missingProcessKey,
      businessKey: expectedRecordPid,
      recordPid: expectedRecordPid,
    },
  });

  const eventPolicyTraceLink = page.getByTestId('epd-open-trace');
  await expect(eventPolicyTraceLink).toBeVisible({ timeout: 10_000 });
  const traceActionResponse = page.waitForResponse(
    (response) => {
      if (response.status() !== 200 || !response.url().includes('/api/event-policy/action-logs')) {
        return false;
      }
      const url = new URL(response.url());
      return (
        url.searchParams.get('policyCode') === policyCode &&
        url.searchParams.get('correlationId') === correlationId
      );
    },
    { timeout: 20_000 },
  );
  await eventPolicyTraceLink.click();
  await expect(page).toHaveURL(/\/p\/decisionops_execution_logs/);
  await readApi<ActionLogRecord[]>(await traceActionResponse);
  await expect(page.getByTestId('execution-log-trace-block')).toBeVisible({ timeout: 20_000 });
  const linkedActionEvidence = page.getByTestId('elta-linked-action-evidence');
  await expect(linkedActionEvidence).toBeVisible({ timeout: 20_000 });
  await expect(linkedActionEvidence).toContainText('启动流程');
  await expect(linkedActionEvidence).toContainText('失败');
  await expect(linkedActionEvidence).toContainText('失败原因 流程启动失败');
  await expect(linkedActionEvidence).toContainText(`流程标识 ${missingProcessKey}`);
  await expect(linkedActionEvidence).toContainText(`业务主键 ${expectedRecordPid}`);
  await expect(linkedActionEvidence).toContainText(`业务记录 ${expectedRecordPid}`);
  await expect(linkedActionEvidence).toContainText('重试 1/3');
  await expect(linkedActionEvidence).toContainText(
    '流程启动失败：流程未部署或流程标识不存在',
  );
  await expect(linkedActionEvidence).not.toContainText('failureReason');
  await expect(linkedActionEvidence).not.toContainText('process_start_failed');
  await expect(linkedActionEvidence).not.toContainText('processDefinitionId');
  await expect(linkedActionEvidence).not.toContainText('businessKey');
  await expect(linkedActionEvidence).not.toContainText('recordPid');
  await expect(linkedActionEvidence).not.toContainText('Process definition version');

  await page.screenshot({
    path: testInfo.outputPath('event-policy-start-process-failure-trace-evidence.png'),
    fullPage: true,
  });
});

test('EventPolicy target-resolution failure shows structured Trace evidence @golden', async ({
  page,
}, testInfo) => {
  await loginViaUI(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
  await expect(page).not.toHaveURL(/\/login(?:$|\?)/);

  const suffix = uniqueId('ep_empty_role').replace(/[^a-zA-Z0-9_]/g, '_');
  const policyCode = `codex_ep_empty_role_${suffix}`;
  const targetKey = `complaint_empty_role_${suffix}`;
  const expectedRecordPid = `TEST-${policyCode}`;

  await openEventPolicyListFromSidebar(page);
  await expect(page.getByTestId('event-policy-actions-block')).toBeVisible({ timeout: 15_000 });
  await page.getByTestId('epa-new-policy').click();
  await expect(page.getByTestId('epa-editor')).toBeVisible({ timeout: 10_000 });
  await page.getByLabel('policy-code').fill(policyCode);
  await page.getByLabel('policy-name').fill(`Codex EventPolicy Empty Role ${suffix}`);
  await page.getByLabel('policy-event-type').fill('FORM_SUBMITTED');
  await page.getByLabel('policy-target-type').fill('FORM');
  await page.getByLabel('policy-target-key').fill(targetKey);
  await page.getByTestId('epa-save-policy').click();
  await expect(page).toHaveURL(new RegExp(`/p/decisionops_event_policies/view/${policyCode}`), {
    timeout: 15_000,
  });

  await page.getByTestId('epa-open-designer').click();
  await expect(page).toHaveURL(/\/p\/decisionops_event_policy_designer\?policyCode=/, {
    timeout: 15_000,
  });
  await expect(page.getByTestId('event-policy-designer-block')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByTestId('epd-workflow')).toBeVisible();
  await expect(page.getByTestId('epd-abnormal-actions')).toContainText('无异常动作');

  await page.getByTestId('epd-step-rules').click();
  await expect(page.getByTestId('policy-rules-editor')).toBeVisible();
  await page.getByTestId('cb-add').click();
  await expect(
    page.locator('select[aria-label="field-0"] option[value="record:data.priority"]'),
  ).toHaveCount(1, {
    timeout: 15_000,
  });
  await page.getByLabel('field-0').selectOption('record:data.priority');
  await page.getByLabel('operator-0').selectOption('EQ');
  await page.getByLabel('value-0').selectOption('HIGH');

  await page.getByTestId('epd-step-actions').click();
  await page.getByTestId('epd-add-action').click();
  await expect(
    page.locator('select[aria-label="action-type-0"] option[value="CC_TASK"]'),
  ).toContainText('抄送任务', {
    timeout: 10_000,
  });
  await page.getByLabel('action-type-0').selectOption('CC_TASK');
  await expect(page.getByTestId('epd-action-schema-0')).toContainText('抄送接收人表达式');
  await expect(page.getByTestId('epd-action-schema-0')).toContainText('抄送消息');
  await page.getByLabel('action-target-0').fill('ROLE:empty_role');
  await page
    .getByLabel('action-field-0-payload.message')
    .fill('EventPolicy empty role ${record.recordPid}');

  await page.screenshot({
    path: testInfo.outputPath('event-policy-target-resolution-failure-configured.png'),
    fullPage: true,
  });

  await page.getByTestId('epd-step-publish').click();
  const draftResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes(`/api/event-policy/definitions/${policyCode}/versions`),
    { timeout: 15_000 },
  );
  await page.getByTestId('epd-save-draft').click();
  const draft = await readApi<EventPolicyVersion>(await draftResponsePromise);
  expect(draft.pid).toBeTruthy();
  await expect(page.getByTestId('epd-publish-status')).toContainText(/DRAFT|草稿/i, {
    timeout: 10_000,
  });

  const validateResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes(`/api/event-policy/versions/${draft.pid}/validate`),
    { timeout: 15_000 },
  );
  await page.getByTestId('epd-validate-version').click();
  await expect((await readApi<EventPolicyVersion>(await validateResponsePromise)).status).toBe(
    'VALIDATED',
  );

  const publishResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes(`/api/event-policy/versions/${draft.pid}/publish`),
    { timeout: 15_000 },
  );
  await page.getByTestId('epd-publish-version').click();
  await expect((await readApi<EventPolicyVersion>(await publishResponsePromise)).status).toBe(
    'PUBLISHED',
  );
  await expect(page.getByTestId('epd-publish-status')).toContainText(/PUBLISHED|已发布/i, {
    timeout: 10_000,
  });

  await page.getByTestId('epd-step-test').click();
  const runButtonResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes('/api/event-policy/run-and-execute'),
    { timeout: 15_000 },
  );
  await page.getByTestId('epd-run-published').click();
  const execution = await readApi<EventPolicyExecutionResult>(await runButtonResponse);
  expect(execution.policy?.status).toBe('MATCHED');
  expect(execution.policy?.matchedRuleCodes).toContain('R-1');
  const correlationId = execution.policy?.correlationId;
  expect(
    correlationId,
    `run-and-execute must return correlationId: ${JSON.stringify(execution)}`,
  ).toMatch(/^ep-/);
  expect(execution.execution?.policyCode).toBe(policyCode);
  expect(execution.execution?.overallStatus).toBe('FAILED');
  expect(execution.execution?.actions).toHaveLength(1);
  expect(execution.execution?.actions?.[0]).toMatchObject({
    ruleCode: 'R-1',
    type: 'CC_TASK',
    status: 'FAILED',
    resultPayload: {
      delivery: 'inbox',
      itemType: 'mention',
      failureReason: 'target_resolved_no_users',
      targetType: 'ROLE',
      target: 'ROLE:empty_role',
      resolvedCount: 0,
    },
  });
  expect(String(execution.execution?.actions?.[0]?.error ?? '')).toContain('ROLE:empty_role');
  await expect(page.getByTestId('epd-action-execution-results')).toBeVisible({ timeout: 10_000 });
  await expect(page.getByTestId('epd-action-execution-0')).toContainText('抄送任务');
  await expect(page.getByTestId('epd-action-execution-0')).toContainText('失败');
  await expect(page.getByTestId('epd-action-execution-0')).toContainText('ROLE:empty_role');
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText('失败原因');
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText('目标未匹配到用户');
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText('接收类型');
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText('角色');
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText('接收对象');
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText('ROLE:empty_role');
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText('解析人数');
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText('0');
  await expect(page.getByTestId('epd-action-result-payload-0')).not.toContainText('failureReason');
  await expect(page.getByTestId('epd-action-result-payload-0')).not.toContainText(
    'target_resolved_no_users',
  );
  await expect(page.getByTestId('epd-action-result-payload-0')).not.toContainText('resolvedCount');

  const actionLogs = await readApi<ActionLogRecord[]>(
    await page.request.get('/api/event-policy/action-logs', {
      params: { policyCode, correlationId: correlationId!, size: '20' },
    }),
  );
  expect(actionLogs).toHaveLength(1);
  const actionLog = actionLogs[0];
  expect(actionLog).toMatchObject({
    policyCode,
    correlationId,
    ruleCode: 'R-1',
    actionType: 'CC_TASK',
    status: 'FAILED',
    resultPayload: {
      delivery: 'inbox',
      itemType: 'mention',
      failureReason: 'target_resolved_no_users',
      targetType: 'ROLE',
      target: 'ROLE:empty_role',
      resolvedCount: 0,
    },
  });
  expect(actionLog.errorMessage ?? '').toContain('ROLE:empty_role');
  expect(actionLog.resultPayload?.inboxItemIds).toBeUndefined();
  expect(actionLog.actionPayload).toMatchObject({
    ruleCode: 'R-1',
    type: 'CC_TASK',
    target: 'ROLE:empty_role',
    payload: {
      message: 'EventPolicy empty role ${record.recordPid}',
    },
  });
  expect(actionLog.contextPayload?.record).toMatchObject({
    entityCode: targetKey,
    recordPid: expectedRecordPid,
    data: expect.objectContaining({
      recordPid: expectedRecordPid,
      priority: 'HIGH',
    }),
  });

  const eventPolicyTraceLink = page.getByTestId('epd-open-trace');
  await expect(eventPolicyTraceLink).toBeVisible({ timeout: 10_000 });
  const traceActionResponse = page.waitForResponse(
    (response) => {
      if (response.status() !== 200 || !response.url().includes('/api/event-policy/action-logs')) {
        return false;
      }
      const url = new URL(response.url());
      return (
        url.searchParams.get('policyCode') === policyCode &&
        url.searchParams.get('correlationId') === correlationId
      );
    },
    { timeout: 20_000 },
  );
  await eventPolicyTraceLink.click();
  await expect(page).toHaveURL(/\/p\/decisionops_execution_logs/);
  await readApi<ActionLogRecord[]>(await traceActionResponse);
  await expect(page.getByTestId('execution-log-trace-block')).toBeVisible({ timeout: 20_000 });
  const linkedActionEvidence = page.getByTestId('elta-linked-action-evidence');
  await expect(linkedActionEvidence).toBeVisible({ timeout: 20_000 });
  await expect(linkedActionEvidence).toContainText('抄送任务');
  await expect(linkedActionEvidence).toContainText('失败');
  await expect(linkedActionEvidence).toContainText('失败原因 目标未匹配到用户');
  await expect(linkedActionEvidence).toContainText('接收类型 角色');
  await expect(linkedActionEvidence).toContainText('接收对象 ROLE:empty_role');
  await expect(linkedActionEvidence).toContainText('解析人数 0');
  await expect(linkedActionEvidence).toContainText('重试 1/3');
  await expect(linkedActionEvidence).toContainText('尝试次数 1');
  await expect(linkedActionEvidence).toContainText('最大尝试 3');
  await expect(linkedActionEvidence).not.toContainText('failureReason');
  await expect(linkedActionEvidence).not.toContainText('target_resolved_no_users');
  await expect(linkedActionEvidence).not.toContainText('resolvedCount');

  await page.screenshot({
    path: testInfo.outputPath('event-policy-target-resolution-failure-trace-evidence.png'),
    fullPage: true,
  });
});

test('EventPolicy publishing keeps one active published version and runtime uses the newest one @golden', async ({
  page,
}, testInfo) => {
  await loginViaUI(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
  await expect(page).not.toHaveURL(/\/login(?:$|\?)/);
  const currentUser = await readApi<CurrentUserInfo>(await page.request.get('/api/auth/me'));
  const notifyRecipientId = currentUser.user?.id;
  if (!notifyRecipientId) {
    throw new Error(
      `Current user id is required for NOTIFY action target: ${JSON.stringify(currentUser)}`,
    );
  }
  expect(notifyRecipientId).toMatch(/^\d+$/);

  const suffix = uniqueId('ep_version').replace(/[^a-zA-Z0-9_]/g, '_');
  const policyCode = `codex_ep_version_${suffix}`;
  const targetKey = `complaint_${suffix}`;

  const saveValidatePublish = async (): Promise<EventPolicyVersion> => {
    await page.getByTestId('epd-step-publish').click();
    const draftResponsePromise = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        response.url().includes(`/api/event-policy/definitions/${policyCode}/versions`),
      { timeout: 15_000 },
    );
    await page.getByTestId('epd-save-draft').click();
    const draft = await readApi<EventPolicyVersion>(await draftResponsePromise);
    expect(draft.pid).toBeTruthy();

    const validateResponsePromise = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        response.url().includes(`/api/event-policy/versions/${draft.pid}/validate`),
      { timeout: 15_000 },
    );
    await page.getByTestId('epd-validate-version').click();
    await expect((await readApi<EventPolicyVersion>(await validateResponsePromise)).status).toBe(
      'VALIDATED',
    );

    const publishResponsePromise = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        response.url().includes(`/api/event-policy/versions/${draft.pid}/publish`),
      { timeout: 15_000 },
    );
    await page.getByTestId('epd-publish-version').click();
    const published = await readApi<EventPolicyVersion>(await publishResponsePromise);
    expect(published.status).toBe('PUBLISHED');
    await expect(page.getByTestId('epd-publish-status')).toContainText(/PUBLISHED|已发布/i, {
      timeout: 10_000,
    });
    return published;
  };

  await openEventPolicyListFromSidebar(page);
  await expect(page.getByTestId('event-policy-actions-block')).toBeVisible({ timeout: 15_000 });
  await page.getByTestId('epa-new-policy').click();
  await expect(page.getByTestId('epa-editor')).toBeVisible({ timeout: 10_000 });
  await page.getByLabel('policy-code').fill(policyCode);
  await page.getByLabel('policy-name').fill(`Codex Versioned Policy ${suffix}`);
  await page.getByLabel('policy-event-type').fill('FORM_SUBMITTED');
  await page.getByLabel('policy-target-type').fill('FORM');
  await page.getByLabel('policy-target-key').fill(targetKey);
  await page.getByTestId('epa-save-policy').click();
  await expect(page).toHaveURL(new RegExp(`/p/decisionops_event_policies/view/${policyCode}`), {
    timeout: 15_000,
  });

  await page.getByTestId('epa-open-designer').click();
  await expect(page).toHaveURL(/\/p\/decisionops_event_policy_designer\?policyCode=/, {
    timeout: 15_000,
  });
  await expect(page.getByTestId('event-policy-designer-block')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByTestId('epd-workflow')).toBeVisible();

  await page.getByTestId('epd-step-rules').click();
  await expect(page.getByTestId('policy-rules-editor')).toBeVisible();
  await page.getByTestId('cb-add').click();
  await expect(
    page.locator('select[aria-label="field-0"] option[value="record:data.priority"]'),
  ).toHaveCount(1, {
    timeout: 15_000,
  });
  await page.getByLabel('field-0').selectOption('record:data.priority');
  await page.getByLabel('operator-0').selectOption('EQ');
  await page.getByLabel('value-0').selectOption('HIGH');

  await page.getByTestId('epd-step-actions').click();
  await page.getByTestId('epd-add-action').click();
  await page.getByLabel('action-target-0').fill(`USER:${notifyRecipientId}`);
  await page.getByLabel('action-field-0-payload.title').fill('Published version v1');
  await page.getByLabel('action-field-0-payload.content').fill('published_version_v1');
  const firstPublished = await saveValidatePublish();
  expect(firstPublished.version).toBe(1);

  await page.getByTestId('epd-step-actions').click();
  await page.getByLabel('action-field-0-payload.title').fill('Published version v2');
  await page.getByLabel('action-field-0-payload.content').fill('published_version_v2');
  const secondPublished = await saveValidatePublish();
  expect(secondPublished.version).toBe(2);

  const versions = await readApi<EventPolicyVersion[]>(
    await page.request.get(`/api/event-policy/definitions/${policyCode}/versions`),
  );
  const publishedVersions = versions.filter((version) => version.status === 'PUBLISHED');
  expect(publishedVersions.map((version) => version.pid)).toEqual([secondPublished.pid]);
  expect(versions.find((version) => version.pid === firstPublished.pid)?.status).toBe('DEPRECATED');
  expect(versions.find((version) => version.pid === secondPublished.pid)?.status).toBe('PUBLISHED');

  await page.getByTestId('epd-step-test').click();
  const runButtonResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes('/api/event-policy/run-and-execute'),
    { timeout: 15_000 },
  );
  await page.getByTestId('epd-run-published').click();
  const execution = await readApi<EventPolicyExecutionResult>(await runButtonResponse);
  expect(execution.policy?.status).toBe('MATCHED');
  expect(execution.policy?.matchedRuleCodes).toContain('R-1');
  expect(execution.execution?.overallStatus).toBe('ALL_SUCCESS');
  expect(execution.execution?.actions).toHaveLength(1);
  expect(execution.execution?.actions?.[0]).toMatchObject({
    ruleCode: 'R-1',
    type: 'NOTIFY',
    status: 'SUCCESS',
  });
  expect(execution.execution?.actions?.[0]?.resultPayload).toMatchObject({
    channel: 'in_app',
    recipientType: 'USER',
    recipientId: notifyRecipientId,
    title: 'Published version v2',
    sourceId: 'R-1',
  });
  expect(JSON.stringify(execution.execution?.actions?.[0]?.resultPayload)).not.toContain(
    'Published version v1',
  );
  await expect(page.getByTestId('epd-action-result-payload-0')).toContainText(
    'Published version v2',
  );
  await expect(page.getByTestId('epd-action-result-payload-0')).not.toContainText(
    'Published version v1',
  );
  await page.getByTestId('epd-action-result-payload-0').scrollIntoViewIfNeeded();

  await page.screenshot({
    path: testInfo.outputPath('event-policy-unique-published-version-runtime-evidence.png'),
    fullPage: true,
  });
});
