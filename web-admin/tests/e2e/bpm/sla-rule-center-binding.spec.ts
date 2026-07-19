import { test, expect, type APIResponse, type Page } from '@playwright/test';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';
import { loginAs, loginViaUI } from '../../helpers/wd-fixtures';
import {
  clickRowActionByLocator,
  ensureSidebarExpanded,
  extractRecordId,
  uniqueId,
  waitForDynamicPageLoad,
} from '../helpers';
import { BACKEND_URL } from '../../helpers/environments';

type ApiEnvelope<T> = {
  code?: number | string;
  success?: boolean;
  message?: string;
  msg?: string;
  data?: T;
};

type JsonResponseLike = Pick<APIResponse, 'json' | 'text' | 'ok' | 'status' | 'url'>;

type DecisionVersion = {
  pid: string;
  status?: string;
};

type DecisionResult = {
  status?: string;
  matched?: boolean;
  outputs?: Record<string, unknown>;
  traceId?: string;
};

type SlaConfigRecord = {
  pid: string;
  name?: string;
  ruleBinding?: unknown;
  rule_binding?: unknown;
  actionPolicy?: unknown;
  action_policy?: unknown;
};

type ActionLogRecord = {
  pid?: string;
  policyCode?: string;
  decisionTraceId?: string;
  correlationId?: string;
  ruleCode?: string;
  actionType?: string;
  status?: string;
  errorMessage?: string;
  resultPayload?: Record<string, unknown>;
};

type WebhookRecord = {
  pid: string;
  eventType?: string;
  event_type?: string;
};

type WebhookDeliveryRecord = {
  pid?: string;
  subscriptionPid?: string;
  subscription_pid?: string;
  eventId?: string;
  event_id?: string;
  deliveryStatus?: string;
  delivery_status?: string;
  requestBody?: string;
  request_body?: string;
};

type CurrentUser = {
  id: string;
  pid: string;
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
  email?: string;
};

type DecisionFactCatalog = {
  entities?: Array<{
    modelCode?: string;
    facts?: Array<{
      scope?: string;
      path?: string;
      label?: string;
      dataType?: string;
      allowedValues?: Array<{ value?: string; label?: string }>;
    }>;
  }>;
};

type DecisionImpact = {
  incoming?: Array<{
    sourceType?: string;
    sourcePid?: string;
    binding?: string;
  }>;
};

type FieldImpact = {
  references?: Array<{
    sourceType?: string;
    sourcePid?: string;
    binding?: string;
    targetPath?: string;
  }>;
};

test.use({ storageState: { cookies: [], origins: [] } });
test.setTimeout(120_000);

let backendJwtPromise: Promise<string> | null = null;

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

function apiEndpoint(endpoint: string): string {
  if (/^https?:\/\//i.test(endpoint)) {
    return endpoint;
  }
  const backendUrl = BACKEND_URL.replace(/\/+$/, '');
  return `${backendUrl}${endpoint.startsWith('/') ? endpoint : `/${endpoint}`}`;
}

function shouldUseBackendAuth(endpoint: string): boolean {
  if (!/^https?:\/\//i.test(endpoint)) {
    return true;
  }
  return endpoint.startsWith(BACKEND_URL.replace(/\/+$/, ''));
}

async function backendAuthHeaders(
  page: Page,
  endpoint: string,
  headers?: unknown,
): Promise<Record<string, string>> {
  const merged = { ...((headers ?? {}) as Record<string, string>) };
  if (!shouldUseBackendAuth(endpoint) || merged.Authorization) {
    return merged;
  }
  backendJwtPromise ??= loginAs(
    page.request,
    DEFAULT_TEST_ACCOUNT.email,
    DEFAULT_TEST_ACCOUNT.password,
  );
  merged.Authorization = `Bearer ${await backendJwtPromise}`;
  return merged;
}

async function requestGet(
  page: Page,
  endpoint: string,
  options?: Parameters<Page['request']['get']>[1],
): Promise<APIResponse> {
  return page.request.get(apiEndpoint(endpoint), {
    ...options,
    headers: await backendAuthHeaders(page, endpoint, options?.headers),
  });
}

async function requestPost(
  page: Page,
  endpoint: string,
  options?: Parameters<Page['request']['post']>[1],
): Promise<APIResponse> {
  return page.request.post(apiEndpoint(endpoint), {
    ...options,
    headers: await backendAuthHeaders(page, endpoint, options?.headers),
  });
}

async function requestDelete(page: Page, endpoint: string): Promise<APIResponse> {
  return page.request.delete(apiEndpoint(endpoint), {
    headers: await backendAuthHeaders(page, endpoint),
  });
}

async function postApi<T>(page: Page, endpoint: string, data?: unknown): Promise<T> {
  return readApi<T>(await requestPost(page, endpoint, { data }));
}

function recordsFromPayload<T>(payload: unknown): T[] {
  if (Array.isArray(payload)) return payload as T[];
  if (!payload || typeof payload !== 'object') return [];
  const value = payload as Record<string, unknown>;
  if (Array.isArray(value.records)) return value.records as T[];
  if (Array.isArray(value.content)) return value.content as T[];
  if (Array.isArray(value.list)) return value.list as T[];
  if (Array.isArray(value.data)) return value.data as T[];
  return [];
}

async function ensureDecisionDefinition(page: Page, decisionCode: string): Promise<void> {
  const existing = await requestGet(page, `/api/decision/definitions/${decisionCode}`);
  const body = (await existing.json().catch(() => null)) as ApiEnvelope<unknown> | null;
  if (existing.ok() && isApiSuccess(body)) return;
  await postApi(page, '/api/decision/definitions', {
    decisionCode,
    decisionName: `SLA Rule Center ${decisionCode}`,
    scopeType: 'SLA',
    ownerModule: 'decision',
    enabled: true,
  });
}

async function publishSlaDecisionVersion(
  page: Page,
  decisionCode: string,
  catalogFieldPath: string,
): Promise<DecisionVersion> {
  await ensureDecisionDefinition(page, decisionCode);
  const draft = await postApi<DecisionVersion>(
    page,
    `/api/decision/definitions/${encodeURIComponent(decisionCode)}/versions`,
    {
      kind: 'DECISION_TABLE',
      runtimeAdapter: 'PLATFORM_DECISION_TABLE',
      versionTag: `sla-ui-${Date.now()}`,
      contentJson: {
        hitPolicy: 'FIRST',
        inputs: [
          {
            id: 'leaveType',
            label: '请假类型',
            expr: {
              type: 'path',
              scope: 'record',
              path: catalogFieldPath,
              dataType: 'string',
            },
          },
        ],
        outputs: [{ id: 'deadlineMinutes', label: 'Deadline Minutes', dataType: 'integer' }],
        rules: [
          {
            ruleId: 'leave-type-annual',
            priority: 10,
            when: { leaveType: { operator: 'EQ', value: 'annual' } },
            then: { deadlineMinutes: 45 },
          },
        ],
        defaultOutput: { deadlineMinutes: 120 },
      },
    },
  );
  await postApi(page, `/api/decision/versions/${draft.pid}/validate`);
  return postApi(page, `/api/decision/versions/${draft.pid}/publish`, {
    impactAcknowledged: true,
    note: 'SLA rule-center binding E2E fixture',
  });
}

async function publishSlaApplicantDecisionVersion(
  page: Page,
  decisionCode: string,
  applicantPid: string,
): Promise<DecisionVersion> {
  await ensureDecisionDefinition(page, decisionCode);
  const draft = await postApi<DecisionVersion>(
    page,
    `/api/decision/definitions/${encodeURIComponent(decisionCode)}/versions`,
    {
      kind: 'SIMPLE_CONDITION',
      runtimeAdapter: 'AST_EVALUATOR',
      versionTag: `sla-applicant-${Date.now()}`,
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
  await postApi(page, `/api/decision/versions/${draft.pid}/validate`);
  return postApi(page, `/api/decision/versions/${draft.pid}/publish`, {
    impactAcknowledged: true,
    note: 'SLA applicant reference rule-center binding E2E fixture',
  });
}

async function createSlaConfig(page: Page, name: string, targetKey: string): Promise<string> {
  const response = await requestPost(page, '/api/meta/commands/execute/admin:create_sla_config', {
    data: {
      operationType: 'create',
      payload: {
        name,
        target_type: 'NODE',
        target_key: targetKey,
        model_code: 'wd_leave_request',
        deadline_mode: 'FIXED',
        deadline_value: 'PT24H',
        suspend_policy: 'pause',
        enabled: true,
      },
    },
  });
  const body = await response.json().catch(async () => ({
    message: await response.text().catch(() => ''),
  }));
  expect(response.ok(), `Create SLA config failed: ${JSON.stringify(body)}`).toBe(true);
  const pid = extractRecordId(body);
  expect(Boolean(pid), `Cannot extract SLA pid: ${JSON.stringify(body)}`).toBe(true);
  return pid;
}

async function createRecordLevelSmsSlaConfig(
  page: Page,
  name: string,
  failureStrategy?: 'RETRY_ASYNC' | 'DEAD_LETTER' | 'FAIL_FAST' | 'CONTINUE_ON_ERROR',
): Promise<string> {
  const response = await requestPost(page, '/api/meta/commands/execute/admin:create_sla_config', {
    data: {
      operationType: 'create',
      payload: {
        name,
        target_type: 'RECORD',
        target_key: 'wd_leave_request',
        model_code: 'wd_leave_request',
        deadline_mode: 'FIXED',
        deadline_value: 'PT0S',
        suspend_policy: 'pause',
        enabled: true,
        action_policy: {
          trigger: 'SLA_TIMEOUT',
          ...(failureStrategy ? { failureStrategy } : {}),
          actions: [
            {
              type: 'SEND_SMS',
              target: 'PHONE:+8613800138000',
              order: 10,
              payload: {
                content: 'SLA ${sla.recordPid} 已超时',
                template: 'sla_timeout',
              },
              idempotencyKeyTemplate: '${sla.recordPid}:timeout:SEND_SMS',
            },
          ],
        },
      },
    },
  });
  const body = await response.json().catch(async () => ({
    message: await response.text().catch(() => ''),
  }));
  expect(response.ok(), `Create record-level SLA config failed: ${JSON.stringify(body)}`).toBe(
    true,
  );
  const pid = extractRecordId(body);
  expect(Boolean(pid), `Cannot extract record-level SLA pid: ${JSON.stringify(body)}`).toBe(true);
  return pid;
}

async function createRecordLevelTaskSlaConfig(
  page: Page,
  name: string,
  assigneeUserId: string,
): Promise<string> {
  const response = await requestPost(page, '/api/meta/commands/execute/admin:create_sla_config', {
    data: {
      operationType: 'create',
      payload: {
        name,
        target_type: 'RECORD',
        target_key: 'wd_leave_request',
        model_code: 'wd_leave_request',
        deadline_mode: 'FIXED',
        deadline_value: 'PT0S',
        suspend_policy: 'pause',
        enabled: true,
        action_policy: {
          trigger: 'SLA_TIMEOUT',
          actions: [
            {
              type: 'CREATE_TASK',
              target: `USER:${assigneeUserId}`,
              order: 10,
              payload: {
                title: 'SLA 待办 ${sla.recordPid}',
                message: '记录 ${record.recordPid} 已超时',
                priority: 'urgent',
              },
              idempotencyKeyTemplate: '${sla.recordPid}:timeout:CREATE_TASK',
            },
          ],
        },
      },
    },
  });
  const body = await response.json().catch(async () => ({
    message: await response.text().catch(() => ''),
  }));
  expect(response.ok(), `Create record-level task SLA config failed: ${JSON.stringify(body)}`).toBe(
    true,
  );
  const pid = extractRecordId(body);
  expect(Boolean(pid), `Cannot extract record-level task SLA pid: ${JSON.stringify(body)}`).toBe(
    true,
  );
  return pid;
}

async function createRecordLevelCcTaskSlaConfig(
  page: Page,
  name: string,
  targetUserId: string,
): Promise<string> {
  const response = await requestPost(page, '/api/meta/commands/execute/admin:create_sla_config', {
    data: {
      operationType: 'create',
      payload: {
        name,
        target_type: 'RECORD',
        target_key: 'wd_leave_request',
        model_code: 'wd_leave_request',
        deadline_mode: 'FIXED',
        deadline_value: 'PT0S',
        suspend_policy: 'pause',
        enabled: true,
        action_policy: {
          trigger: 'SLA_TIMEOUT',
          actions: [
            {
              type: 'CC_TASK',
              target: `USER:${targetUserId}`,
              order: 10,
              payload: {
                taskTitle: 'SLA 抄送 ${sla.recordPid}',
                message: '记录 ${record.recordPid} 超时需关注',
              },
              idempotencyKeyTemplate: '${sla.recordPid}:timeout:CC_TASK',
            },
          ],
        },
      },
    },
  });
  const body = await response.json().catch(async () => ({
    message: await response.text().catch(() => ''),
  }));
  expect(
    response.ok(),
    `Create record-level cc-task SLA config failed: ${JSON.stringify(body)}`,
  ).toBe(true);
  const pid = extractRecordId(body);
  expect(Boolean(pid), `Cannot extract record-level cc-task SLA pid: ${JSON.stringify(body)}`).toBe(
    true,
  );
  return pid;
}

async function createRecordLevelImSlaConfig(
  page: Page,
  name: string,
  targetUserId: string,
): Promise<string> {
  const response = await requestPost(page, '/api/meta/commands/execute/admin:create_sla_config', {
    data: {
      operationType: 'create',
      payload: {
        name,
        target_type: 'RECORD',
        target_key: 'wd_leave_request',
        model_code: 'wd_leave_request',
        deadline_mode: 'FIXED',
        deadline_value: 'PT0S',
        suspend_policy: 'pause',
        enabled: true,
        action_policy: {
          trigger: 'SLA_TIMEOUT',
          actions: [
            {
              type: 'SEND_IM',
              target: `USER:${targetUserId}`,
              order: 10,
              payload: {
                title: 'SLA IM ${sla.recordPid}',
                content: '记录 ${record.recordPid} 超时，请在 IM 中关注',
                channel: 'im',
              },
              idempotencyKeyTemplate: '${sla.recordPid}:timeout:SEND_IM',
            },
          ],
        },
      },
    },
  });
  const body = await response.json().catch(async () => ({
    message: await response.text().catch(() => ''),
  }));
  expect(response.ok(), `Create record-level IM SLA config failed: ${JSON.stringify(body)}`).toBe(
    true,
  );
  const pid = extractRecordId(body);
  expect(Boolean(pid), `Cannot extract record-level IM SLA pid: ${JSON.stringify(body)}`).toBe(
    true,
  );
  return pid;
}

async function createWebhookSubscription(
  page: Page,
  name: string,
  eventType: string,
): Promise<string> {
  const webhook = await postApi<WebhookRecord>(page, '/api/webhooks', {
    name,
    targetUrl: 'http://127.0.0.1:6443/internal',
    eventType,
    maxRetries: 0,
    timeoutMs: 1000,
    enabled: true,
  });
  expect(webhook.pid, `Webhook subscription pid missing: ${JSON.stringify(webhook)}`).toBeTruthy();
  return webhook.pid;
}

async function deleteWebhookSubscription(page: Page, pid?: string): Promise<void> {
  if (!pid) return;
  await requestDelete(page, `/api/webhooks/${encodeURIComponent(pid)}`).catch(() => undefined);
}

async function createRecordLevelWebhookSlaConfig(
  page: Page,
  name: string,
  eventType: string,
): Promise<string> {
  const response = await requestPost(page, '/api/meta/commands/execute/admin:create_sla_config', {
    data: {
      operationType: 'create',
      payload: {
        name,
        target_type: 'RECORD',
        target_key: 'wd_leave_request',
        model_code: 'wd_leave_request',
        deadline_mode: 'FIXED',
        deadline_value: 'PT0S',
        suspend_policy: 'pause',
        enabled: true,
        action_policy: {
          trigger: 'SLA_TIMEOUT',
          actions: [
            {
              type: 'WEBHOOK',
              target: `WEBHOOK:${eventType}`,
              order: 10,
              payload: {
                eventType,
                _eventId: '${sla.recordPid}:timeout:WEBHOOK:event',
                recordPid: '${record.recordPid}',
                slaRecordPid: '${sla.recordPid}',
                source: 'sla-timeout',
              },
              idempotencyKeyTemplate: '${sla.recordPid}:timeout:WEBHOOK',
            },
          ],
        },
      },
    },
  });
  const body = await response.json().catch(async () => ({
    message: await response.text().catch(() => ''),
  }));
  expect(
    response.ok(),
    `Create record-level webhook SLA config failed: ${JSON.stringify(body)}`,
  ).toBe(true);
  const pid = extractRecordId(body);
  expect(Boolean(pid), `Cannot extract record-level webhook SLA pid: ${JSON.stringify(body)}`).toBe(
    true,
  );
  return pid;
}

async function createRecordLevelAuditSlaConfig(page: Page, name: string): Promise<string> {
  const response = await requestPost(page, '/api/meta/commands/execute/admin:create_sla_config', {
    data: {
      operationType: 'create',
      payload: {
        name,
        target_type: 'RECORD',
        target_key: 'wd_leave_request',
        model_code: 'wd_leave_request',
        deadline_mode: 'FIXED',
        deadline_value: 'PT0S',
        suspend_policy: 'pause',
        enabled: true,
        action_policy: {
          trigger: 'SLA_TIMEOUT',
          actions: [
            {
              type: 'WRITE_AUDIT',
              target: 'AUDIT:${record.entityCode}',
              order: 10,
              payload: {
                message: 'SLA 审计 ${record.recordPid}',
                source: 'sla-timeout',
              },
              idempotencyKeyTemplate: '${sla.recordPid}:timeout:WRITE_AUDIT',
            },
          ],
        },
      },
    },
  });
  const body = await response.json().catch(async () => ({
    message: await response.text().catch(() => ''),
  }));
  expect(
    response.ok(),
    `Create record-level audit SLA config failed: ${JSON.stringify(body)}`,
  ).toBe(true);
  const pid = extractRecordId(body);
  expect(Boolean(pid), `Cannot extract record-level audit SLA pid: ${JSON.stringify(body)}`).toBe(
    true,
  );
  return pid;
}

async function createRecordLevelCommentSlaConfig(page: Page, name: string): Promise<string> {
  const response = await requestPost(page, '/api/meta/commands/execute/admin:create_sla_config', {
    data: {
      operationType: 'create',
      payload: {
        name,
        target_type: 'RECORD',
        target_key: 'wd_leave_request',
        model_code: 'wd_leave_request',
        deadline_mode: 'FIXED',
        deadline_value: 'PT0S',
        suspend_policy: 'pause',
        enabled: true,
        action_policy: {
          trigger: 'SLA_TIMEOUT',
          actions: [
            {
              type: 'ADD_COMMENT',
              target: 'RECORD',
              order: 10,
              payload: {
                content: 'SLA 评论 ${record.recordPid}',
                mentions: 'ROLE:wd_manager',
              },
              idempotencyKeyTemplate: '${sla.recordPid}:timeout:ADD_COMMENT',
            },
          ],
        },
      },
    },
  });
  const body = await response.json().catch(async () => ({
    message: await response.text().catch(() => ''),
  }));
  expect(
    response.ok(),
    `Create record-level comment SLA config failed: ${JSON.stringify(body)}`,
  ).toBe(true);
  const pid = extractRecordId(body);
  expect(Boolean(pid), `Cannot extract record-level comment SLA pid: ${JSON.stringify(body)}`).toBe(
    true,
  );
  return pid;
}

async function createRecordLevelFailFastSlaConfig(
  page: Page,
  name: string,
  userId: string,
): Promise<string> {
  const response = await requestPost(page, '/api/meta/commands/execute/admin:create_sla_config', {
    data: {
      operationType: 'create',
      payload: {
        name,
        target_type: 'RECORD',
        target_key: 'wd_leave_request',
        model_code: 'wd_leave_request',
        deadline_mode: 'FIXED',
        deadline_value: 'PT0S',
        suspend_policy: 'pause',
        enabled: true,
        action_policy: {
          trigger: 'SLA_TIMEOUT',
          failureStrategy: 'FAIL_FAST',
          actions: [
            {
              type: 'UNKNOWN_ACTION',
              target: 'SYSTEM',
              order: 10,
              payload: { reason: 'force fail-fast' },
              idempotencyKeyTemplate: '${sla.recordPid}:timeout:UNKNOWN_ACTION',
            },
            {
              type: 'NOTIFY',
              target: `USER:${userId}`,
              order: 20,
              payload: {
                title: '不应发送 ${sla.recordPid}',
                content: '前序动作失败后应被阻断',
              },
              idempotencyKeyTemplate: '${sla.recordPid}:timeout:NOTIFY',
            },
          ],
        },
      },
    },
  });
  const body = await response.json().catch(async () => ({
    message: await response.text().catch(() => ''),
  }));
  expect(
    response.ok(),
    `Create record-level fail-fast SLA config failed: ${JSON.stringify(body)}`,
  ).toBe(true);
  const pid = extractRecordId(body);
  expect(
    Boolean(pid),
    `Cannot extract record-level fail-fast SLA pid: ${JSON.stringify(body)}`,
  ).toBe(true);
  return pid;
}

async function deleteSlaConfig(page: Page, pid: string): Promise<void> {
  await requestPost(page, '/api/meta/commands/execute/admin:delete_sla_config', {
    data: { targetRecordId: pid, operationType: 'delete', payload: {} },
  }).catch(() => undefined);
}

async function resolveCurrentUser(page: Page): Promise<CurrentUser> {
  const me = await readApi<{ user?: { pid?: string; id?: string } }>(
    await requestGet(page, '/api/auth/me'),
  );
  const id = String(me.user?.id ?? '');
  const pid = String(me.user?.pid ?? me.user?.id ?? '');
  expect(id, '/api/auth/me must return current user numeric id for action assignee').toBeTruthy();
  expect(pid, '/api/auth/me must return current user pid').toBeTruthy();
  expect(Number.isFinite(Number(id)), `/api/auth/me user.id must be numeric: ${id}`).toBe(true);
  return { id, pid };
}

async function resolveCurrentUserPid(page: Page): Promise<string> {
  const { pid } = await resolveCurrentUser(page);
  return pid;
}

async function resolveFirstUser(page: Page): Promise<{ pid: string; label: string }> {
  const payload = await readApi<unknown>(
    await requestGet(page, '/api/admin/users/search?keyword=&size=20'),
  );
  const users = recordsFromPayload<UserOption>(payload);
  const user = users.find((item) => item.pid || item.id);
  expect(user, 'at least one user must exist for applicant reference evidence').toBeTruthy();
  const pid = String(user?.pid ?? user?.id ?? '');
  const label = String(
    user?.displayName ??
      user?.name ??
      user?.realName ??
      user?.nickName ??
      user?.nickname ??
      user?.username ??
      user?.email ??
      pid,
  );
  expect(pid, 'user pid must be resolved from /api/admin/users/search').toBeTruthy();
  expect(label, 'user label must be resolved from /api/admin/users/search').toBeTruthy();
  return { pid, label };
}

async function createLeaveRequestDraft(
  page: Page,
  applicantPid: string,
  reason: string,
): Promise<string> {
  const today = new Date();
  const startDate = today.toISOString().slice(0, 10);
  const endDate = new Date(today.getTime() + 86_400_000).toISOString().slice(0, 10);
  const response = await requestPost(page, '/api/meta/commands/execute/wd:create_leave_request', {
    data: {
      operationType: 'create',
      payload: {
        wd_req_applicant: applicantPid,
        wd_req_type: 'annual',
        wd_req_start_date: startDate,
        wd_req_start_slot: 'AM',
        wd_req_end_date: endDate,
        wd_req_end_slot: 'PM',
        wd_req_days: 2,
        wd_req_reason: reason,
      },
    },
  });
  const body = await response.json().catch(async () => ({
    message: await response.text().catch(() => ''),
  }));
  expect(
    response.ok(),
    `Create leave request for SLA evidence failed: ${JSON.stringify(body)}`,
  ).toBe(true);
  const pid = extractRecordId(body);
  expect(Boolean(pid), `Cannot extract leave request pid: ${JSON.stringify(body)}`).toBe(true);
  return pid;
}

async function waitForSlaActionLog(
  page: Page,
  configPid: string,
  actionType: string,
  status: string,
  predicate: (log: ActionLogRecord) => boolean,
  message: string,
): Promise<ActionLogRecord> {
  const policyCode = `SLA_TIMEOUT:${configPid}`;
  let latest: ActionLogRecord[] = [];
  await expect
    .poll(
      async () => {
        latest = await readApi<ActionLogRecord[]>(
          await requestGet(page, '/api/event-policy/action-logs', {
            params: { policyCode, size: '10' },
          }),
        );
        return latest.some(
          (log) =>
            log.policyCode === policyCode &&
            log.actionType === actionType &&
            log.status === status &&
            predicate(log),
        );
      },
      {
        timeout: 45_000,
        intervals: [1000, 2000, 3000, 5000],
        message,
      },
    )
    .toBe(true);
  return latest.find(
    (log) =>
      log.policyCode === policyCode &&
      log.actionType === actionType &&
      log.status === status &&
      predicate(log),
  )!;
}

function actionLogHasTargetPhone(log: ActionLogRecord, phone: string): boolean {
  const phones = log.resultPayload?.targetPhones;
  return Array.isArray(phones) && phones.includes(phone);
}

async function openSlaConfigListFromSidebar(page: Page): Promise<void> {
  await page.goto('/home', { waitUntil: 'domcontentloaded' });
  await ensureSidebarExpanded(page);
  const nav = page.locator('nav, aside, [role="navigation"]').first();
  const link = nav
    .locator('a[href="/p/sla_config"]')
    .or(nav.getByRole('link', { name: /SLA\s*配置|SLA Configuration/i }))
    .first();
  const adminParent = nav
    .getByRole('button', { name: /管理|Admin|系统|Platform/i })
    .or(nav.getByRole('link', { name: /管理|Admin|系统|Platform/i }))
    .first();
  if (!(await link.isVisible({ timeout: 1000 }).catch(() => false))) {
    await adminParent.click().catch(() => undefined);
  }
  await expect(link).toBeVisible({ timeout: 15_000 });
  await link.click();
  await expect(page).toHaveURL(/\/p\/sla_config(?:$|\?)/, { timeout: 15_000 });
  await waitForDynamicPageLoad(page);
}

async function openSlaMonitorFromSidebar(page: Page): Promise<void> {
  await page.goto('/home', { waitUntil: 'domcontentloaded' });
  await ensureSidebarExpanded(page);
  const nav = page.locator('nav, aside, [role="navigation"]').first();
  const link = nav
    .locator('a[href="/bpm/sla-monitor"]')
    .or(nav.getByRole('link', { name: /SLA\s*监控|SLA\s*Monitor/i }))
    .first();
  const bpmParent = nav
    .getByRole('button', { name: /流程|BPM|Workflow/i })
    .or(nav.getByRole('link', { name: /流程|BPM|Workflow/i }))
    .first();
  if (!(await link.isVisible({ timeout: 1000 }).catch(() => false))) {
    await bpmParent.click().catch(() => undefined);
  }
  await expect(link).toBeVisible({ timeout: 15_000 });
  await link.click();
  await expect(page).toHaveURL(/\/bpm\/sla-monitor(?:$|\?)/, { timeout: 15_000 });
}

async function openSlaConfigEditor(page: Page, name: string): Promise<void> {
  await openSlaConfigListFromSidebar(page);
  await page.getByTestId('list-search-input').fill(name);
  await page.getByTestId('list-search-input').press('Enter');
  const row = page.locator('tbody tr').filter({ hasText: name }).first();
  await expect(row).toBeVisible({ timeout: 15_000 });
  await clickRowActionByLocator(page, row, 'edit', '编辑');
  await expect(page.getByTestId('decision-rule-binding-block')).toBeVisible({ timeout: 15_000 });
}

async function openSlaConfigDetail(page: Page, name: string): Promise<void> {
  await openSlaConfigListFromSidebar(page);
  await page.getByTestId('list-search-input').fill(name);
  await page.getByTestId('list-search-input').press('Enter');
  const row = page.locator('tbody tr').filter({ hasText: name }).first();
  await expect(row).toBeVisible({ timeout: 15_000 });
  await clickRowActionByLocator(page, row, 'detail', '详情');
  await expect(page).toHaveURL(/\/p\/sla_config\/view\//, { timeout: 15_000 });
  await waitForDynamicPageLoad(page);
}

test('SLA config form hosts rule-center binding with backend field catalog and impact evidence @golden', async ({
  page,
}, testInfo) => {
  await loginViaUI(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
  await expect(page).not.toHaveURL(/\/login(?:$|\?)/);

  const suffix = uniqueId('sla_rule').replace(/[^a-zA-Z0-9_]/g, '_');
  const decisionCode = 'complaint_sla_deadline';
  const catalogFieldPath = 'data.wd_req_type';
  const catalogFieldRef = `record.${catalogFieldPath}`;
  const slaName = `Codex SLA Rule Center ${suffix}`;
  const targetKey = `approve_${suffix}`;

  await publishSlaDecisionVersion(page, decisionCode, catalogFieldPath);
  await postApi(page, '/api/decision/usage-index/rebuild');

  const pid = await createSlaConfig(page, slaName, targetKey);

  try {
    const factCatalogResponse = page.waitForResponse(
      (response) =>
        response.url().includes('/api/decision/facts/catalog') &&
        response.url().includes('modelCode=wd_leave_request') &&
        response.status() < 400,
      { timeout: 15_000 },
    );
    await openSlaConfigEditor(page, slaName);
    const block = page.getByTestId('decision-rule-binding-block');
    const factCatalog = await readApi<DecisionFactCatalog>(await factCatalogResponse);
    const leaveFacts = factCatalog.entities?.flatMap((entity) => entity.facts ?? []) ?? [];
    const leaveTypeFact = leaveFacts.find(
      (field) => field.scope === 'record' && field.path === 'data.wd_req_type',
    );
    expect(
      leaveTypeFact,
      `fact catalog should include ${catalogFieldRef}: ${JSON.stringify(factCatalog)}`,
    ).toBeTruthy();
    expect(leaveTypeFact?.label ?? '').toMatch(/请假类型|Leave Type/i);
    expect(leaveTypeFact?.allowedValues ?? []).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ value: 'annual' }),
        expect.objectContaining({ value: 'sick' }),
      ]),
    );

    await block.getByLabel('decision-code').selectOption(decisionCode);
    await block.getByLabel('version-policy').selectOption('LATEST_PUBLISHED');
    await block.getByLabel('fallback-mode').selectOption('FAIL_CLOSED');
    await block.getByRole('button', { name: '添加映射' }).click();
    await expect(
      block.locator(
        `select[aria-label="mapping-field-0"] option[value="record:${catalogFieldPath}"]`,
      ),
    ).toHaveCount(1);
    await block.getByLabel('mapping-input-0').fill('leaveType');
    await block.getByLabel('mapping-field-0').selectOption(`record:${catalogFieldPath}`);
    await block.getByRole('button', { name: '添加输出' }).click();
    await expect(block.getByLabel('output-mapping-output-picker-0')).toContainText('截止分钟');
    await block.getByLabel('output-mapping-output-picker-0').selectOption('deadlineMinutes');
    await expect(block.getByLabel('output-mapping-output-0')).toHaveValue('deadlineMinutes');
    await block.getByLabel('output-mapping-kind-0').selectOption('SLA_FIELD');
    await block.getByLabel('output-mapping-path-0').fill('deadlineMinutes');

    const testTab = block.getByTestId('decision-rule-section-tab-test');
    if (await testTab.isVisible().catch(() => false)) {
      await testTab.click();
    } else {
      await block.getByTestId('decision-test-runner').scrollIntoViewIfNeeded();
    }
    await block.getByLabel('open-test-context-drawer').click();
    await block.getByLabel('test-context-field-record-data-wd_req_type').fill('annual');
    const runResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' && response.url().includes('/api/decision/evaluate'),
      { timeout: 15_000 },
    );
    await block.getByLabel('run-decision-test').click();
    const runResult = await readApi<{ traceId?: string }>(await runResponse);
    expect(runResult.traceId).toBeTruthy();
    await expect(block.getByTestId('decision-test-result')).toContainText(/已命中|MATCHED/i, {
      timeout: 15_000,
    });
    await expect(block.getByTestId('decision-test-result')).toContainText('deadlineMinutes');
    await expect(block.getByTestId('decision-test-result')).toContainText('45');
    const traceLink = block.getByTestId('decision-test-open-trace');
    await expect(traceLink).toBeVisible();
    const traceHref = await traceLink.getAttribute('href');
    expect(traceHref).toBeTruthy();
    const traceUrl = new URL(traceHref!, page.url());
    expect(traceUrl.pathname).toBe('/p/decisionops_execution_logs');
    expect(traceUrl.searchParams.get('traceId')).toBe(runResult.traceId);
    expect(traceUrl.searchParams.get('decisionCode')).toBe(decisionCode);
    expect(traceUrl.searchParams.get('callerType')).toBe('SLA');
    expect(traceUrl.searchParams.get('callerRef')).toBe(pid);
    const decisionTraceHref = `${traceUrl.pathname}${traceUrl.search}`;

    const saveResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        response.url().includes('/api/meta/commands/execute/admin:update_sla_config'),
      { timeout: 15_000 },
    );
    await page.getByRole('button', { name: /^保存$|^Save$/ }).click();
    await readApi(await saveResponse);

    const saved = await readApi<SlaConfigRecord>(
      await requestGet(page, `/api/bpm/sla-configs/${pid}`),
    );
    const ruleBinding = (saved.ruleBinding ?? saved.rule_binding) as Record<string, unknown>;
    expect(ruleBinding).toMatchObject({
      consumerType: 'SLA',
      consumerCode: pid,
      bindingKind: 'DECISION_REF',
      decisionBinding: {
        decisionCode,
        versionPolicy: 'LATEST_PUBLISHED',
        inputMappings: [
          {
            input: 'leaveType',
            source: { kind: 'FIELD', scope: 'record', path: catalogFieldPath },
          },
        ],
        outputMappings: [
          {
            output: 'deadlineMinutes',
            target: { kind: 'SLA_FIELD', path: 'deadlineMinutes' },
          },
        ],
      },
    });

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('decision-rule-binding-block')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByLabel('mapping-input-0')).toHaveValue('leaveType');
    await expect(page.getByLabel('mapping-field-0')).toHaveValue(`record:${catalogFieldPath}`);

    await postApi(page, '/api/decision/usage-index/rebuild');
    const impact = await readApi<DecisionImpact>(
      await requestGet(page, `/api/decision/definitions/${decisionCode}/impact`),
    );
    expect(impact.incoming ?? []).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          sourceType: 'SLA_RULE',
          sourcePid: pid,
          binding: 'RULE_BINDING',
        }),
      ]),
    );
    const fieldImpact = await readApi<FieldImpact>(
      await requestGet(page, '/api/decision/fields/impact', {
        params: { fieldRef: catalogFieldRef },
      }),
    );
    expect(fieldImpact.references ?? []).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          sourceType: 'SLA_RULE',
          sourcePid: pid,
          binding: 'RULE_BINDING',
        }),
      ]),
    );

    await page.screenshot({
      path: testInfo.outputPath('sla-rule-center-binding-saved.png'),
      fullPage: true,
    });

    await page.goto(decisionTraceHref, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('execution-log-trace-block')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByLabel('log-keyword')).toHaveValue(runResult.traceId!);
    await expect(page.getByLabel('log-decision-code')).toHaveValue(decisionCode);
    await expect(page.getByLabel('log-caller-type')).toHaveValue('SLA');
    expect(new URL(page.url()).searchParams.get('callerRef')).toBe(pid);
    const traceRow = page
      .locator('tr[data-testid^="elta-row-"]')
      .filter({ hasText: runResult.traceId! })
      .first();
    await expect(traceRow).toBeVisible({ timeout: 15_000 });
    await expect(traceRow).toContainText('请假审批 SLA 截止时间');
    await expect(traceRow).toContainText('SLA');
    await traceRow.getByRole('button', { name: '追踪' }).click();
    await expect(page.getByTestId('elta-trace-drawer')).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId('elta-trace-chain')).toContainText('请假审批 SLA 截止时间');
    const openSlaConfig = page.getByTestId('elta-open-sla-config');
    await expect(openSlaConfig).toHaveAttribute('href', `/p/sla_config/view/${pid}`);
    await page.screenshot({
      path: testInfo.outputPath('sla-rule-binding-test-run-trace-link.png'),
      fullPage: true,
    });
    await openSlaConfig.click();
    await expect(page).toHaveURL(new RegExp(`/p/sla_config/view/${pid}`), { timeout: 15_000 });
    await expect(page.getByTestId('decision-rule-binding-block')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId('decision-rule-binding-block')).toContainText(
      '请假审批 SLA 截止时间',
    );
    await page.screenshot({
      path: testInfo.outputPath('sla-rule-binding-trace-back-to-config-detail.png'),
      fullPage: true,
    });
  } finally {
    await deleteSlaConfig(page, pid);
  }
});

test('SLA rule binding test-run traces applicant user reference fact metadata @golden', async ({
  page,
}, testInfo) => {
  await loginViaUI(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
  await expect(page).not.toHaveURL(/\/login(?:$|\?)/);

  const user = await resolveFirstUser(page);
  const suffix = uniqueId('sla_applicant_trace').replace(/[^a-zA-Z0-9_]/g, '_').toLowerCase();
  const decisionCode = `sla_applicant_reference_${suffix}`;
  const catalogFieldPath = 'data.wd_req_applicant';
  const catalogFieldRef = `record.${catalogFieldPath}`;
  const slaName = `Codex SLA Applicant Trace ${suffix}`;
  const targetKey = `applicant_${suffix}`;

  await publishSlaApplicantDecisionVersion(page, decisionCode, user.pid);
  await postApi(page, '/api/decision/usage-index/rebuild');

  const pid = await createSlaConfig(page, slaName, targetKey);

  try {
    const factCatalogResponse = page.waitForResponse(
      (response) =>
        response.url().includes('/api/decision/facts/catalog') &&
        response.url().includes('modelCode=wd_leave_request') &&
        response.status() < 400,
      { timeout: 15_000 },
    );
    await openSlaConfigEditor(page, slaName);
    const block = page.getByTestId('decision-rule-binding-block');
    const factCatalog = await readApi<DecisionFactCatalog>(await factCatalogResponse);
    const leaveFacts = factCatalog.entities?.flatMap((entity) => entity.facts ?? []) ?? [];
    const applicantFact = leaveFacts.find(
      (field) => field.scope === 'record' && field.path === catalogFieldPath,
    );
    expect(
      applicantFact,
      `fact catalog should include ${catalogFieldRef}: ${JSON.stringify(factCatalog)}`,
    ).toBeTruthy();
    expect(applicantFact?.label ?? '').toMatch(/申请人|Applicant/i);
    expect(applicantFact?.dataType ?? '').toMatch(/user|reference/i);

    await block.getByLabel('decision-code').selectOption(decisionCode);
    await block.getByLabel('version-policy').selectOption('LATEST_PUBLISHED');
    await block.getByLabel('fallback-mode').selectOption('FAIL_CLOSED');
    await block.getByRole('button', { name: '添加映射' }).click();
    await expect(
      block.locator(
        `select[aria-label="mapping-field-0"] option[value="record:${catalogFieldPath}"]`,
      ),
    ).toHaveCount(1);
    await block.getByLabel('mapping-input-0').fill('wd_req_applicant');
    await block.getByLabel('mapping-field-0').selectOption(`record:${catalogFieldPath}`);

    const testTab = block.getByTestId('decision-rule-section-tab-test');
    if (await testTab.isVisible().catch(() => false)) {
      await testTab.click();
    } else {
      await block.getByTestId('decision-test-runner').scrollIntoViewIfNeeded();
    }
    await block.getByLabel('open-test-context-drawer').click();
    await block.getByLabel('test-context-field-record-data-wd_req_applicant').fill(user.pid);
    const runResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' && response.url().includes('/api/decision/evaluate'),
      { timeout: 15_000 },
    );
    await block.getByLabel('run-decision-test').click();
    const runResult = await readApi<DecisionResult>(await runResponse);
    expect(runResult.traceId).toBeTruthy();
    expect(runResult.matched).toBe(true);
    await expect(block.getByTestId('decision-test-result')).toContainText(/已命中|MATCHED/i, {
      timeout: 15_000,
    });

    const traceLink = block.getByTestId('decision-test-open-trace');
    await expect(traceLink).toBeVisible();
    const traceHref = await traceLink.getAttribute('href');
    expect(traceHref).toBeTruthy();
    const traceUrl = new URL(traceHref!, page.url());
    expect(traceUrl.pathname).toBe('/p/decisionops_execution_logs');
    expect(traceUrl.searchParams.get('traceId')).toBe(runResult.traceId);
    expect(traceUrl.searchParams.get('decisionCode')).toBe(decisionCode);
    expect(traceUrl.searchParams.get('callerType')).toBe('SLA');
    expect(traceUrl.searchParams.get('callerRef')).toBe(pid);
    const decisionTraceHref = `${traceUrl.pathname}${traceUrl.search}`;

    const saveResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        response.url().includes('/api/meta/commands/execute/admin:update_sla_config'),
      { timeout: 15_000 },
    );
    await page.getByRole('button', { name: /^保存$|^Save$/ }).click();
    await readApi(await saveResponse);

    const saved = await readApi<SlaConfigRecord>(
      await requestGet(page, `/api/bpm/sla-configs/${pid}`),
    );
    const ruleBinding = (saved.ruleBinding ?? saved.rule_binding) as Record<string, unknown>;
    expect(ruleBinding).toMatchObject({
      consumerType: 'SLA',
      consumerCode: pid,
      bindingKind: 'DECISION_REF',
      decisionBinding: {
        decisionCode,
        versionPolicy: 'LATEST_PUBLISHED',
        inputMappings: [
          {
            input: 'wd_req_applicant',
            source: { kind: 'FIELD', scope: 'record', path: catalogFieldPath },
          },
        ],
      },
    });

    await page.goto(decisionTraceHref, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('execution-log-trace-block')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByLabel('log-keyword')).toHaveValue(runResult.traceId!);
    await expect(page.getByLabel('log-decision-code')).toHaveValue(decisionCode);
    await expect(page.getByLabel('log-caller-type')).toHaveValue('SLA');
    expect(new URL(page.url()).searchParams.get('callerRef')).toBe(pid);

    const traceRow = page
      .locator('tr[data-testid^="elta-row-"]')
      .filter({ hasText: runResult.traceId! })
      .first();
    await expect(traceRow).toBeVisible({ timeout: 15_000 });
    await expect(traceRow).toContainText('SLA');
    await traceRow.getByRole('button', { name: '追踪' }).click();
    await expect(page.getByTestId('elta-trace-drawer')).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId('elta-trace-chain')).toContainText('SLA');

    const factMetadata = page.locator('[data-testid^="elta-fact-metadata-"]').first();
    await expect(factMetadata).toBeVisible({ timeout: 10_000 });
    await expect(factMetadata).toContainText('事实快照');
    await expect(factMetadata).toContainText(/申请人|Applicant/i);
    await expect(factMetadata).toContainText('record.data.wd_req_applicant');
    await expect(factMetadata).toContainText('模型 wd_leave_request');
    await expect(factMetadata).toContainText(/类型 (user|reference)/i);
    await expect(factMetadata).toContainText(user.pid);
    await expect(factMetadata).toContainText(user.label);

    const openSlaConfig = page.getByTestId('elta-open-sla-config');
    await expect(openSlaConfig).toHaveAttribute('href', `/p/sla_config/view/${pid}`);
    await page.screenshot({
      path: testInfo.outputPath('sla-applicant-reference-trace-fact-metadata.png'),
      fullPage: true,
    });
    await openSlaConfig.click();
    await expect(page).toHaveURL(new RegExp(`/p/sla_config/view/${pid}`), { timeout: 15_000 });
    await expect(page.getByTestId('decision-rule-binding-block')).toBeVisible({ timeout: 15_000 });
  } finally {
    await deleteSlaConfig(page, pid);
  }
});

test('SLA monitor shows timeout action execution evidence and provider-unavailable failure @golden', async ({
  page,
}, testInfo) => {
  await loginViaUI(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
  await expect(page).not.toHaveURL(/\/login(?:$|\?)/);

  const suffix = uniqueId('sla_monitor_action').replace(/[^a-zA-Z0-9_]/g, '_');
  const slaName = `Codex SLA Monitor Action ${suffix}`;
  const pid = await createRecordLevelSmsSlaConfig(page, slaName);

  try {
    const userPid = await resolveCurrentUserPid(page);
    await createLeaveRequestDraft(page, userPid, `SLA monitor action evidence ${suffix}`);

    const actionLog = await waitForSlaActionLog(
      page,
      pid,
      'SEND_SMS',
      'FAILED',
      (log) => /No real SMS sender available/i.test(String(log.errorMessage ?? '')),
      `expected SEND_SMS provider-unavailable action log for SLA_TIMEOUT:${pid}`,
    );
    expect(actionLog).toMatchObject({
      policyCode: `SLA_TIMEOUT:${pid}`,
      ruleCode: 'SLA_TIMEOUT',
      actionType: 'SEND_SMS',
      status: 'FAILED',
      errorMessage: 'No real SMS sender available',
    });

    const actionLogsResponse = page.waitForResponse(
      (response) => {
        if (
          response.status() !== 200 ||
          !response.url().includes('/api/event-policy/action-logs')
        ) {
          return false;
        }
        const url = new URL(response.url());
        return url.searchParams.get('policyCodePrefix') === 'SLA_TIMEOUT:';
      },
      { timeout: 20_000 },
    );
    await openSlaMonitorFromSidebar(page);
    await readApi<ActionLogRecord[]>(await actionLogsResponse);

    const strategyChain = page.getByTestId('sla-strategy-chain');
    await expect(strategyChain).toBeVisible({ timeout: 20_000 });
    const configCard = strategyChain.locator('article').filter({ hasText: slaName }).first();
    await expect(configCard).toBeVisible({ timeout: 20_000 });
    await expect(configCard).toContainText('SLA 超时');
    await expect(configCard).toContainText('短信');
    await expect(configCard).toContainText('动作执行证据');
    await expect(configCard).toContainText(/失败|Failed/i);
    await expect(configCard).toContainText(/短信 provider 不可用|No real SMS sender available/i);
    await expect(configCard).toContainText('+8613800138000');

    await page.screenshot({
      path: testInfo.outputPath('sla-monitor-action-evidence-provider-unavailable.png'),
      fullPage: true,
    });
  } finally {
    await deleteSlaConfig(page, pid);
  }
});

test('SLA monitor shows RETRY_ASYNC timeout action strategy, retry timeline, and replay evidence @golden', async ({
  page,
}, testInfo) => {
  await loginViaUI(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
  await expect(page).not.toHaveURL(/\/login(?:$|\?)/);

  const suffix = uniqueId('sla_monitor_retry').replace(/[^a-zA-Z0-9_]/g, '_');
  const slaName = `Codex SLA Monitor Retry ${suffix}`;
  const pid = await createRecordLevelSmsSlaConfig(page, slaName, 'RETRY_ASYNC');

  try {
    const userPid = await resolveCurrentUserPid(page);
    await createLeaveRequestDraft(page, userPid, `SLA monitor retry evidence ${suffix}`);

    const actionLog = await waitForSlaActionLog(
      page,
      pid,
      'SEND_SMS',
      'RETRY_PENDING',
      (log) =>
        /No real SMS sender available/i.test(String(log.errorMessage ?? '')) &&
        actionLogHasTargetPhone(log, '+8613800138000'),
      `expected SEND_SMS retry-pending action log for SLA_TIMEOUT:${pid}`,
    );
    expect(actionLog).toMatchObject({
      policyCode: `SLA_TIMEOUT:${pid}`,
      ruleCode: 'SLA_TIMEOUT',
      actionType: 'SEND_SMS',
      status: 'RETRY_PENDING',
      errorMessage: 'No real SMS sender available',
    });
    const actionLogPid = String(actionLog.pid ?? '');
    expect(
      actionLogPid,
      `RETRY_PENDING action log pid missing: ${JSON.stringify(actionLog)}`,
    ).toBeTruthy();

    const actionLogsResponse = page.waitForResponse(
      (response) => {
        if (
          response.status() !== 200 ||
          !response.url().includes('/api/event-policy/action-logs')
        ) {
          return false;
        }
        const url = new URL(response.url());
        return url.searchParams.get('policyCodePrefix') === 'SLA_TIMEOUT:';
      },
      { timeout: 20_000 },
    );
    await openSlaMonitorFromSidebar(page);
    await readApi<ActionLogRecord[]>(await actionLogsResponse);

    const strategyChain = page.getByTestId('sla-strategy-chain');
    await expect(strategyChain).toBeVisible({ timeout: 20_000 });
    const configCard = strategyChain.locator('article').filter({ hasText: slaName }).first();
    await expect(configCard).toBeVisible({ timeout: 20_000 });
    await expect(configCard.getByTestId(`sla-failure-strategy-${pid}`)).toContainText('异步重试');
    await expect(configCard).toContainText('短信');
    await expect(configCard).toContainText('等待重试');
    await expect(configCard).toContainText('短信 provider 不可用');
    await expect(configCard).toContainText('+8613800138000');

    const actionLogCard = configCard.getByTestId(`sla-action-log-${actionLogPid}`);
    await expect(actionLogCard).toBeVisible({ timeout: 20_000 });
    const retryTimeline = actionLogCard.getByTestId(`sla-action-retry-${actionLogPid}`);
    await expect(retryTimeline).toBeVisible();
    await expect(retryTimeline).toContainText(/重试|下次|上次/);

    const replayButton = actionLogCard.getByTestId(`sla-action-replay-${actionLogPid}`);
    await expect(replayButton).toBeVisible();
    await expect(replayButton).toContainText('重放');
    const traceLink = actionLogCard.getByTestId(`sla-action-trace-${actionLogPid}`);
    await expect(traceLink).toBeVisible();
    await expect(traceLink).toContainText('统一 Trace');
    const traceHref = await traceLink.getAttribute('href');
    expect(traceHref, 'SLA action log unified trace href should be present').toBeTruthy();
    const traceUrl = new URL(traceHref!, 'http://127.0.0.1:5194');
    expect(traceUrl.pathname).toBe('/p/decisionops_execution_logs');
    expect(traceUrl.searchParams.get('policyCode')).toBe(`SLA_TIMEOUT:${pid}`);
    expect(traceUrl.searchParams.get('callerType')).toBe('SLA');
    expect(traceUrl.searchParams.get('callerRef')).toBe(pid);
    if (actionLog.decisionTraceId) {
      expect(traceUrl.searchParams.get('traceId')).toBe(actionLog.decisionTraceId);
    }
    if (actionLog.correlationId) {
      expect(traceUrl.searchParams.get('correlationId')).toBe(actionLog.correlationId);
    }
    const replayResponsePromise = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        response.url().includes(`/api/event-policy/action-logs/${actionLogPid}/replay`),
      { timeout: 20_000 },
    );
    await replayButton.click();
    const replayResponse = await replayResponsePromise;
    await readApi<ActionLogRecord>(replayResponse);
    await expect(actionLogCard).toBeVisible({ timeout: 20_000 });
    await expect(actionLogCard).toContainText(/等待重试|进入死信|失败|短信 provider 不可用/);

    await page.screenshot({
      path: testInfo.outputPath('sla-monitor-retry-timeline-replay-evidence.png'),
      fullPage: true,
    });

    await traceLink.click();
    await expect(page).toHaveURL(/\/p\/decisionops_execution_logs/);
    await expect(page.getByTestId('execution-log-trace-block')).toBeVisible({ timeout: 20_000 });
    await expect(page.getByLabel('log-caller-type')).toHaveValue('SLA');
    await expect(page.getByLabel('log-keyword')).toHaveValue(
      actionLog.decisionTraceId ?? `SLA_TIMEOUT:${pid}`,
    );
    expect(new URL(page.url()).searchParams.get('callerRef')).toBe(pid);
    const linkedActionEvidence = page.getByTestId('elta-linked-action-evidence');
    await expect(linkedActionEvidence).toBeVisible({ timeout: 20_000 });
    await expect(linkedActionEvidence).toContainText('动作执行证据');
    await expect(linkedActionEvidence).toContainText('SEND_SMS');
    await expect(linkedActionEvidence).toContainText(
      /等待重试|进入死信|失败|短信 provider 不可用|No real SMS sender available/,
    );
    await page.screenshot({
      path: testInfo.outputPath('sla-monitor-unified-trace-link-evidence.png'),
      fullPage: true,
    });
  } finally {
    await deleteSlaConfig(page, pid);
  }
});

test('SLA monitor shows DEAD_LETTER timeout action strategy and dead-letter evidence @golden', async ({
  page,
}, testInfo) => {
  await loginViaUI(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
  await expect(page).not.toHaveURL(/\/login(?:$|\?)/);

  const suffix = uniqueId('sla_monitor_dead_letter').replace(/[^a-zA-Z0-9_]/g, '_');
  const slaName = `Codex SLA Monitor Dead Letter ${suffix}`;
  const pid = await createRecordLevelSmsSlaConfig(page, slaName, 'DEAD_LETTER');

  try {
    const userPid = await resolveCurrentUserPid(page);
    await createLeaveRequestDraft(page, userPid, `SLA monitor dead-letter evidence ${suffix}`);

    const actionLog = await waitForSlaActionLog(
      page,
      pid,
      'SEND_SMS',
      'DEAD_LETTER',
      (log) =>
        /No real SMS sender available/i.test(String(log.errorMessage ?? '')) &&
        actionLogHasTargetPhone(log, '+8613800138000'),
      `expected SEND_SMS dead-letter action log for SLA_TIMEOUT:${pid}`,
    );
    expect(actionLog).toMatchObject({
      policyCode: `SLA_TIMEOUT:${pid}`,
      ruleCode: 'SLA_TIMEOUT',
      actionType: 'SEND_SMS',
      status: 'DEAD_LETTER',
      errorMessage: 'No real SMS sender available',
    });

    const actionLogsResponse = page.waitForResponse(
      (response) => {
        if (
          response.status() !== 200 ||
          !response.url().includes('/api/event-policy/action-logs')
        ) {
          return false;
        }
        const url = new URL(response.url());
        return url.searchParams.get('policyCodePrefix') === 'SLA_TIMEOUT:';
      },
      { timeout: 20_000 },
    );
    await openSlaMonitorFromSidebar(page);
    await readApi<ActionLogRecord[]>(await actionLogsResponse);

    const strategyChain = page.getByTestId('sla-strategy-chain');
    await expect(strategyChain).toBeVisible({ timeout: 20_000 });
    const configCard = strategyChain.locator('article').filter({ hasText: slaName }).first();
    await expect(configCard).toBeVisible({ timeout: 20_000 });
    await expect(configCard.getByTestId(`sla-failure-strategy-${pid}`)).toContainText('进入死信');
    await expect(configCard).toContainText('短信');
    await expect(configCard).toContainText('已进入死信');
    await expect(configCard).toContainText('短信 provider 不可用');
    await expect(configCard).toContainText('+8613800138000');

    await page.screenshot({
      path: testInfo.outputPath('sla-monitor-dead-letter-action-evidence.png'),
      fullPage: true,
    });
  } finally {
    await deleteSlaConfig(page, pid);
  }
});

test('SLA monitor shows CREATE_TASK timeout action evidence and created inbox task @golden', async ({
  page,
}, testInfo) => {
  await loginViaUI(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
  await expect(page).not.toHaveURL(/\/login(?:$|\?)/);

  const suffix = uniqueId('sla_monitor_task').replace(/[^a-zA-Z0-9_]/g, '_');
  const slaName = `Codex SLA Monitor Task ${suffix}`;
  const currentUser = await resolveCurrentUser(page);
  const pid = await createRecordLevelTaskSlaConfig(page, slaName, currentUser.id);

  try {
    const leavePid = await createLeaveRequestDraft(
      page,
      currentUser.pid,
      `SLA monitor create task evidence ${suffix}`,
    );

    const actionLog = await waitForSlaActionLog(
      page,
      pid,
      'CREATE_TASK',
      'SUCCESS',
      (log) => {
        const payload = log.resultPayload ?? {};
        return (
          payload.createdCount === 1 &&
          Array.isArray(payload.inboxItemIds) &&
          payload.inboxItemIds.length >= 1 &&
          payload.recordPid === leavePid
        );
      },
      `expected CREATE_TASK success action log for SLA_TIMEOUT:${pid}`,
    );
    expect(actionLog).toMatchObject({
      policyCode: `SLA_TIMEOUT:${pid}`,
      ruleCode: 'SLA_TIMEOUT',
      actionType: 'CREATE_TASK',
      status: 'SUCCESS',
    });
    expect(actionLog.resultPayload).toMatchObject({
      itemType: 'task',
      createdCount: 1,
      recordPid: leavePid,
    });
    expect(actionLog.resultPayload?.assigneeUserIds).toEqual(
      expect.arrayContaining([Number(currentUser.id)]),
    );
    expect(Array.isArray(actionLog.resultPayload?.inboxItemIds)).toBe(true);

    const actionLogsResponse = page.waitForResponse(
      (response) => {
        if (
          response.status() !== 200 ||
          !response.url().includes('/api/event-policy/action-logs')
        ) {
          return false;
        }
        const url = new URL(response.url());
        return url.searchParams.get('policyCodePrefix') === 'SLA_TIMEOUT:';
      },
      { timeout: 20_000 },
    );
    await openSlaMonitorFromSidebar(page);
    await readApi<ActionLogRecord[]>(await actionLogsResponse);

    const strategyChain = page.getByTestId('sla-strategy-chain');
    await expect(strategyChain).toBeVisible({ timeout: 20_000 });
    const configCard = strategyChain.locator('article').filter({ hasText: slaName }).first();
    await expect(configCard).toBeVisible({ timeout: 20_000 });
    await expect(configCard).toContainText('SLA 超时');
    await expect(configCard).toContainText('创建任务');
    await expect(configCard).toContainText('动作执行证据');
    await expect(configCard).toContainText(/成功|Success/i);
    await expect(configCard).toContainText('创建任务 1');
    await expect(configCard).toContainText('待办 1 条');
    await expect(configCard).toContainText(`记录 ${leavePid}`);
    await expect(configCard).toContainText('用户 1 人');

    await page.screenshot({
      path: testInfo.outputPath('sla-monitor-create-task-action-evidence.png'),
      fullPage: true,
    });
  } finally {
    await deleteSlaConfig(page, pid);
  }
});

test('SLA monitor shows CC_TASK timeout action evidence and inbox mention @golden', async ({
  page,
}, testInfo) => {
  await loginViaUI(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
  await expect(page).not.toHaveURL(/\/login(?:$|\?)/);

  const suffix = uniqueId('sla_monitor_cc').replace(/[^a-zA-Z0-9_]/g, '_');
  const slaName = `Codex SLA Monitor CC ${suffix}`;
  const currentUser = await resolveCurrentUser(page);
  const pid = await createRecordLevelCcTaskSlaConfig(page, slaName, currentUser.id);

  try {
    const leavePid = await createLeaveRequestDraft(
      page,
      currentUser.pid,
      `SLA monitor cc task evidence ${suffix}`,
    );

    const actionLog = await waitForSlaActionLog(
      page,
      pid,
      'CC_TASK',
      'SUCCESS',
      (log) => {
        const payload = log.resultPayload ?? {};
        return (
          payload.ccCount === 1 &&
          payload.itemType === 'mention' &&
          Array.isArray(payload.inboxItemIds) &&
          payload.inboxItemIds.length >= 1 &&
          payload.recordPid === leavePid
        );
      },
      `expected CC_TASK success action log for SLA_TIMEOUT:${pid}`,
    );
    expect(actionLog).toMatchObject({
      policyCode: `SLA_TIMEOUT:${pid}`,
      ruleCode: 'SLA_TIMEOUT',
      actionType: 'CC_TASK',
      status: 'SUCCESS',
    });
    expect(actionLog.resultPayload).toMatchObject({
      itemType: 'mention',
      ccCount: 1,
      recordPid: leavePid,
    });
    expect(Array.isArray(actionLog.resultPayload?.targetUserIds)).toBe(true);
    expect(Array.isArray(actionLog.resultPayload?.inboxItemIds)).toBe(true);

    const actionLogsResponse = page.waitForResponse(
      (response) => {
        if (
          response.status() !== 200 ||
          !response.url().includes('/api/event-policy/action-logs')
        ) {
          return false;
        }
        const url = new URL(response.url());
        return url.searchParams.get('policyCodePrefix') === 'SLA_TIMEOUT:';
      },
      { timeout: 20_000 },
    );
    await openSlaMonitorFromSidebar(page);
    await readApi<ActionLogRecord[]>(await actionLogsResponse);

    const strategyChain = page.getByTestId('sla-strategy-chain');
    await expect(strategyChain).toBeVisible({ timeout: 20_000 });
    const configCard = strategyChain.locator('article').filter({ hasText: slaName }).first();
    await expect(configCard).toBeVisible({ timeout: 20_000 });
    await expect(configCard).toContainText('SLA 超时');
    await expect(configCard).toContainText('抄送任务');
    await expect(configCard).toContainText('动作执行证据');
    await expect(configCard).toContainText(/成功|Success/i);
    await expect(configCard).toContainText('抄送 1');
    await expect(configCard).toContainText('用户 1 人');
    await expect(configCard).toContainText('待办 1 条');
    await expect(configCard).toContainText(`记录 ${leavePid}`);

    await page.screenshot({
      path: testInfo.outputPath('sla-monitor-cc-task-action-evidence.png'),
      fullPage: true,
    });
  } finally {
    await deleteSlaConfig(page, pid);
  }
});

test('SLA monitor shows SEND_IM timeout action evidence and bot message @golden', async ({
  page,
}, testInfo) => {
  await loginViaUI(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
  await expect(page).not.toHaveURL(/\/login(?:$|\?)/);

  const suffix = uniqueId('sla_monitor_im').replace(/[^a-zA-Z0-9_]/g, '_');
  const slaName = `Codex SLA Monitor IM ${suffix}`;
  const currentUser = await resolveCurrentUser(page);
  const pid = await createRecordLevelImSlaConfig(page, slaName, currentUser.id);

  try {
    const leavePid = await createLeaveRequestDraft(
      page,
      currentUser.pid,
      `SLA monitor send im evidence ${suffix}`,
    );

    const actionLog = await waitForSlaActionLog(
      page,
      pid,
      'SEND_IM',
      'SUCCESS',
      (log) => {
        const payload = log.resultPayload ?? {};
        return (
          payload.sentCount === 1 &&
          Array.isArray(payload.messageIds) &&
          payload.messageIds.length >= 1 &&
          Array.isArray(payload.conversationIds) &&
          payload.conversationIds.length >= 1 &&
          payload.recordPid === leavePid
        );
      },
      `expected SEND_IM success action log for SLA_TIMEOUT:${pid}`,
    );
    expect(actionLog).toMatchObject({
      policyCode: `SLA_TIMEOUT:${pid}`,
      ruleCode: 'SLA_TIMEOUT',
      actionType: 'SEND_IM',
      status: 'SUCCESS',
    });
    expect(actionLog.resultPayload).toMatchObject({
      channel: 'im',
      sentCount: 1,
      recordPid: leavePid,
    });
    expect(actionLog.resultPayload?.targetUserIds).toEqual(
      expect.arrayContaining([Number(currentUser.id)]),
    );
    expect(Array.isArray(actionLog.resultPayload?.messageIds)).toBe(true);
    expect(Array.isArray(actionLog.resultPayload?.conversationIds)).toBe(true);

    const actionLogsResponse = page.waitForResponse(
      (response) => {
        if (
          response.status() !== 200 ||
          !response.url().includes('/api/event-policy/action-logs')
        ) {
          return false;
        }
        const url = new URL(response.url());
        return url.searchParams.get('policyCodePrefix') === 'SLA_TIMEOUT:';
      },
      { timeout: 20_000 },
    );
    await openSlaMonitorFromSidebar(page);
    await readApi<ActionLogRecord[]>(await actionLogsResponse);

    const strategyChain = page.getByTestId('sla-strategy-chain');
    await expect(strategyChain).toBeVisible({ timeout: 20_000 });
    const configCard = strategyChain.locator('article').filter({ hasText: slaName }).first();
    await expect(configCard).toBeVisible({ timeout: 20_000 });
    await expect(configCard).toContainText('SLA 超时');
    await expect(configCard).toContainText('发送 IM');
    await expect(configCard).toContainText('动作执行证据');
    await expect(configCard).toContainText(/成功|Success/i);
    await expect(configCard).toContainText('发送 1');
    await expect(configCard).toContainText('消息 1 条');
    await expect(configCard).toContainText('用户 1 人');
    await expect(configCard).toContainText(`记录 ${leavePid}`);

    await page.screenshot({
      path: testInfo.outputPath('sla-monitor-send-im-action-evidence.png'),
      fullPage: true,
    });
  } finally {
    await deleteSlaConfig(page, pid);
  }
});

test('SLA monitor shows WEBHOOK timeout action evidence and tracked delivery log @golden', async ({
  page,
}, testInfo) => {
  await loginViaUI(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
  await expect(page).not.toHaveURL(/\/login(?:$|\?)/);

  const suffix = uniqueId('sla_monitor_webhook').replace(/[^a-zA-Z0-9_]/g, '_');
  const slaName = `Codex SLA Monitor Webhook ${suffix}`;
  const eventType = `sla.timeout.${suffix}`;
  const currentUser = await resolveCurrentUser(page);
  let webhookPid: string | undefined;
  let pid: string | undefined;

  try {
    webhookPid = await createWebhookSubscription(page, `Codex SLA Webhook ${suffix}`, eventType);
    pid = await createRecordLevelWebhookSlaConfig(page, slaName, eventType);
    const leavePid = await createLeaveRequestDraft(
      page,
      currentUser.pid,
      `SLA monitor webhook evidence ${suffix}`,
    );

    const actionLog = await waitForSlaActionLog(
      page,
      pid,
      'WEBHOOK',
      'SUCCESS',
      (log) => {
        const payload = log.resultPayload ?? {};
        return (
          payload.eventType === eventType &&
          payload.recordPid === leavePid &&
          Array.isArray(payload.deliveryLogPids) &&
          payload.deliveryLogPids.length >= 1 &&
          Array.isArray(payload.deliveryReceipts) &&
          payload.deliveryReceipts.length >= 1
        );
      },
      `expected WEBHOOK success action log for SLA_TIMEOUT:${pid}`,
    );
    expect(actionLog).toMatchObject({
      policyCode: `SLA_TIMEOUT:${pid}`,
      ruleCode: 'SLA_TIMEOUT',
      actionType: 'WEBHOOK',
      status: 'SUCCESS',
    });
    expect(actionLog.resultPayload).toMatchObject({
      eventType,
      dispatchAccepted: true,
      deliveryTraceStatus: 'tracked_delivery_logs',
      recordPid: leavePid,
    });
    expect(Array.isArray(actionLog.resultPayload?.deliveryLogPids)).toBe(true);
    const deliveryEventId = String(actionLog.resultPayload?.deliveryEventId ?? '');
    expect(deliveryEventId).toContain(':timeout:WEBHOOK:event');

    const deliveries = await readApi<WebhookDeliveryRecord[]>(
      await requestGet(page, `/api/webhooks/${encodeURIComponent(webhookPid)}/deliveries`, {
        params: { limit: '10' },
      }),
    );
    const matchingDelivery = deliveries.find(
      (delivery) => (delivery.eventId ?? delivery.event_id) === deliveryEventId,
    );
    expect(
      matchingDelivery,
      `delivery log ${deliveryEventId} missing: ${JSON.stringify(deliveries)}`,
    ).toBeTruthy();
    expect(matchingDelivery?.deliveryStatus ?? matchingDelivery?.delivery_status).toBe('failed');
    expect(
      String(matchingDelivery?.requestBody ?? matchingDelivery?.request_body ?? ''),
      'webhook delivery request body should include the business record pid',
    ).toContain(leavePid);

    const actionLogsResponse = page.waitForResponse(
      (response) => {
        if (
          response.status() !== 200 ||
          !response.url().includes('/api/event-policy/action-logs')
        ) {
          return false;
        }
        const url = new URL(response.url());
        return url.searchParams.get('policyCodePrefix') === 'SLA_TIMEOUT:';
      },
      { timeout: 20_000 },
    );
    await openSlaMonitorFromSidebar(page);
    await readApi<ActionLogRecord[]>(await actionLogsResponse);

    const strategyChain = page.getByTestId('sla-strategy-chain');
    await expect(strategyChain).toBeVisible({ timeout: 20_000 });
    const configCard = strategyChain.locator('article').filter({ hasText: slaName }).first();
    await expect(configCard).toBeVisible({ timeout: 20_000 });
    await expect(configCard).toContainText('SLA 超时');
    await expect(configCard).toContainText('Webhook');
    await expect(configCard).toContainText('动作执行证据');
    await expect(configCard).toContainText(/成功|Success/i);
    await expect(configCard).toContainText(`事件 ${eventType}`);
    await expect(configCard).toContainText('投递日志 1 条');
    await expect(configCard).toContainText('投递 failed');
    await expect(configCard).toContainText(`记录 ${leavePid}`);

    await page.screenshot({
      path: testInfo.outputPath('sla-monitor-webhook-action-evidence.png'),
      fullPage: true,
    });
  } finally {
    if (pid) await deleteSlaConfig(page, pid);
    await deleteWebhookSubscription(page, webhookPid);
  }
});

test('SLA monitor shows WRITE_AUDIT timeout action evidence and rendered audit row @golden', async ({
  page,
}, testInfo) => {
  await loginViaUI(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
  await expect(page).not.toHaveURL(/\/login(?:$|\?)/);

  const suffix = uniqueId('sla_monitor_audit').replace(/[^a-zA-Z0-9_]/g, '_');
  const slaName = `Codex SLA Monitor Audit ${suffix}`;
  const currentUser = await resolveCurrentUser(page);
  const pid = await createRecordLevelAuditSlaConfig(page, slaName);

  try {
    const leavePid = await createLeaveRequestDraft(
      page,
      currentUser.pid,
      `SLA monitor audit evidence ${suffix}`,
    );

    const actionLog = await waitForSlaActionLog(
      page,
      pid,
      'WRITE_AUDIT',
      'SUCCESS',
      (log) => {
        const payload = log.resultPayload ?? {};
        return Boolean(payload.auditPid) && payload.message === `SLA 审计 ${leavePid}`;
      },
      `expected WRITE_AUDIT success action log for SLA_TIMEOUT:${pid}`,
    );
    expect(actionLog).toMatchObject({
      policyCode: `SLA_TIMEOUT:${pid}`,
      ruleCode: 'SLA_TIMEOUT',
      actionType: 'WRITE_AUDIT',
      status: 'SUCCESS',
    });
    expect(actionLog.resultPayload).toMatchObject({
      actionType: 'WRITE_AUDIT',
      message: `SLA 审计 ${leavePid}`,
      target: 'AUDIT:wd_leave_request',
    });
    expect(Boolean(actionLog.resultPayload?.auditPid)).toBe(true);

    const actionLogsResponse = page.waitForResponse(
      (response) => {
        if (
          response.status() !== 200 ||
          !response.url().includes('/api/event-policy/action-logs')
        ) {
          return false;
        }
        const url = new URL(response.url());
        return url.searchParams.get('policyCodePrefix') === 'SLA_TIMEOUT:';
      },
      { timeout: 20_000 },
    );
    await openSlaMonitorFromSidebar(page);
    await readApi<ActionLogRecord[]>(await actionLogsResponse);

    const strategyChain = page.getByTestId('sla-strategy-chain');
    await expect(strategyChain).toBeVisible({ timeout: 20_000 });
    const configCard = strategyChain.locator('article').filter({ hasText: slaName }).first();
    await expect(configCard).toBeVisible({ timeout: 20_000 });
    await expect(configCard).toContainText('SLA 超时');
    await expect(configCard).toContainText('审计');
    await expect(configCard).toContainText('动作执行证据');
    await expect(configCard).toContainText(/成功|Success/i);
    await expect(configCard).toContainText('审计 1 条');
    await expect(configCard).toContainText(`内容 SLA 审计 ${leavePid}`);

    await page.screenshot({
      path: testInfo.outputPath('sla-monitor-write-audit-action-evidence.png'),
      fullPage: true,
    });
  } finally {
    await deleteSlaConfig(page, pid);
  }
});

test('SLA monitor shows ADD_COMMENT timeout action evidence and rendered comment @golden', async ({
  page,
}, testInfo) => {
  await loginViaUI(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
  await expect(page).not.toHaveURL(/\/login(?:$|\?)/);

  const suffix = uniqueId('sla_monitor_comment').replace(/[^a-zA-Z0-9_]/g, '_');
  const slaName = `Codex SLA Monitor Comment ${suffix}`;
  const currentUser = await resolveCurrentUser(page);
  const pid = await createRecordLevelCommentSlaConfig(page, slaName);

  try {
    const leavePid = await createLeaveRequestDraft(
      page,
      currentUser.pid,
      `SLA monitor comment evidence ${suffix}`,
    );

    const actionLog = await waitForSlaActionLog(
      page,
      pid,
      'ADD_COMMENT',
      'SUCCESS',
      (log) => {
        const payload = log.resultPayload ?? {};
        return (
          Boolean(payload.commentPid) &&
          payload.recordPid === leavePid &&
          payload.content === `SLA 评论 ${leavePid}`
        );
      },
      `expected ADD_COMMENT success action log for SLA_TIMEOUT:${pid}`,
    );
    expect(actionLog).toMatchObject({
      policyCode: `SLA_TIMEOUT:${pid}`,
      ruleCode: 'SLA_TIMEOUT',
      actionType: 'ADD_COMMENT',
      status: 'SUCCESS',
    });
    expect(actionLog.resultPayload).toMatchObject({
      modelCode: 'wd_leave_request',
      recordPid: leavePid,
      content: `SLA 评论 ${leavePid}`,
      mentions: 'ROLE:wd_manager',
    });
    expect(Boolean(actionLog.resultPayload?.commentPid)).toBe(true);

    const actionLogsResponse = page.waitForResponse(
      (response) => {
        if (
          response.status() !== 200 ||
          !response.url().includes('/api/event-policy/action-logs')
        ) {
          return false;
        }
        const url = new URL(response.url());
        return url.searchParams.get('policyCodePrefix') === 'SLA_TIMEOUT:';
      },
      { timeout: 20_000 },
    );
    await openSlaMonitorFromSidebar(page);
    await readApi<ActionLogRecord[]>(await actionLogsResponse);

    const strategyChain = page.getByTestId('sla-strategy-chain');
    await expect(strategyChain).toBeVisible({ timeout: 20_000 });
    const configCard = strategyChain.locator('article').filter({ hasText: slaName }).first();
    await expect(configCard).toBeVisible({ timeout: 20_000 });
    await expect(configCard).toContainText('SLA 超时');
    await expect(configCard).toContainText('评论');
    await expect(configCard).toContainText('动作执行证据');
    await expect(configCard).toContainText(/成功|Success/i);
    await expect(configCard).toContainText('评论 1 条');
    await expect(configCard).toContainText(`内容 SLA 评论 ${leavePid}`);
    await expect(configCard).toContainText(`记录 ${leavePid}`);

    await page.screenshot({
      path: testInfo.outputPath('sla-monitor-add-comment-action-evidence.png'),
      fullPage: true,
    });
  } finally {
    await deleteSlaConfig(page, pid);
  }
});

test('SLA monitor shows FAIL_FAST timeout action strategy and blocked action evidence @golden', async ({
  page,
}, testInfo) => {
  await loginViaUI(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
  await expect(page).not.toHaveURL(/\/login(?:$|\?)/);

  const suffix = uniqueId('sla_monitor_fail_fast').replace(/[^a-zA-Z0-9_]/g, '_');
  const slaName = `Codex SLA Monitor Fail Fast ${suffix}`;
  const currentUser = await resolveCurrentUser(page);
  const pid = await createRecordLevelFailFastSlaConfig(page, slaName, currentUser.id);

  try {
    await createLeaveRequestDraft(
      page,
      currentUser.pid,
      `SLA monitor fail-fast evidence ${suffix}`,
    );

    const missingHandlerLog = await waitForSlaActionLog(
      page,
      pid,
      'UNKNOWN_ACTION',
      'NO_HANDLER',
      (log) => String(log.errorMessage ?? '').includes('no handler for action type UNKNOWN_ACTION'),
      `expected UNKNOWN_ACTION no-handler action log for SLA_TIMEOUT:${pid}`,
    );
    expect(missingHandlerLog).toMatchObject({
      policyCode: `SLA_TIMEOUT:${pid}`,
      ruleCode: 'SLA_TIMEOUT',
      actionType: 'UNKNOWN_ACTION',
      status: 'NO_HANDLER',
    });

    const blockedLog = await waitForSlaActionLog(
      page,
      pid,
      'NOTIFY',
      'NOT_EXECUTED',
      () => true,
      `expected NOTIFY blocked action log for SLA_TIMEOUT:${pid}`,
    );
    expect(blockedLog).toMatchObject({
      policyCode: `SLA_TIMEOUT:${pid}`,
      ruleCode: 'SLA_TIMEOUT',
      actionType: 'NOTIFY',
      status: 'NOT_EXECUTED',
    });

    const actionLogsResponse = page.waitForResponse(
      (response) => {
        if (
          response.status() !== 200 ||
          !response.url().includes('/api/event-policy/action-logs')
        ) {
          return false;
        }
        const url = new URL(response.url());
        return url.searchParams.get('policyCodePrefix') === 'SLA_TIMEOUT:';
      },
      { timeout: 20_000 },
    );
    await openSlaMonitorFromSidebar(page);
    await readApi<ActionLogRecord[]>(await actionLogsResponse);

    const strategyChain = page.getByTestId('sla-strategy-chain');
    await expect(strategyChain).toBeVisible({ timeout: 20_000 });
    const configCard = strategyChain.locator('article').filter({ hasText: slaName }).first();
    await expect(configCard).toBeVisible({ timeout: 20_000 });
    await expect(configCard.getByTestId(`sla-failure-strategy-${pid}`)).toContainText('失败即停止');
    await expect(configCard).toContainText('处理器缺失');
    await expect(configCard).toContainText('未执行');
    await expect(configCard).toContainText('动作处理器不可用');
    await expect(configCard).toContainText('前序失败已阻断');

    await page.screenshot({
      path: testInfo.outputPath('sla-monitor-fail-fast-action-evidence.png'),
      fullPage: true,
    });
  } finally {
    await deleteSlaConfig(page, pid);
  }
});

test('SLA config form saves timeout action policy with reusable context variables @golden', async ({
  page,
}, testInfo) => {
  await loginViaUI(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
  await expect(page).not.toHaveURL(/\/login(?:$|\?)/);

  const suffix = uniqueId('sla_action').replace(/[^a-zA-Z0-9_]/g, '_');
  const slaName = `Codex SLA Action Policy ${suffix}`;
  const targetKey = `approve_${suffix}`;
  const pid = await createSlaConfig(page, slaName, targetKey);

  try {
    const actionCatalogResponse = page.waitForResponse(
      (response) =>
        response.url().includes('/api/decision/actions/catalog') && response.status() < 400,
      { timeout: 15_000 },
    );
    await openSlaConfigEditor(page, slaName);
    const actionBlock = page.getByTestId('decision-action-plan-block');
    await expect(actionBlock).toBeVisible({ timeout: 15_000 });
    await readApi(await actionCatalogResponse);
    await expect(actionBlock.getByTestId('dap-failure-strategy')).toContainText('失败后继续');
    await actionBlock.getByLabel('action-failure-strategy').selectOption('FAIL_FAST');
    await expect(actionBlock.getByTestId('dap-failure-strategy')).toContainText('失败即停止');

    await actionBlock.getByTestId('dap-add-action').click();
    const firstAction = actionBlock.getByTestId('dap-action-0');
    await expect(firstAction).toBeVisible({ timeout: 10_000 });
    await firstAction.getByLabel('action-type-0').selectOption('NOTIFY');
    await expect(firstAction.getByTestId('dap-action-field-0-payload.title')).toBeVisible({
      timeout: 10_000,
    });

    await firstAction.getByLabel('action-order-0').fill('10');
    await firstAction.getByLabel('action-target-0').fill('USER:1');
    await firstAction.getByLabel('action-field-0-payload.title').fill('SLA 超时 ${sla.recordPid}');
    await firstAction
      .getByLabel('action-field-0-payload.content')
      .fill('节点 ${task.nodeId} 已超时，规则输出 ${decision.outputs.deadlineMinutes}');

    await actionBlock.getByTestId('dap-add-action').click();
    const secondAction = actionBlock.getByTestId('dap-action-1');
    await expect(secondAction).toBeVisible({ timeout: 10_000 });
    await expect(
      secondAction.locator('select[aria-label="action-type-1"] option[value="SEND_SMS"]'),
    ).toContainText('发送短信（不可用）', { timeout: 15_000 });
    await secondAction.getByLabel('action-type-1').selectOption('SEND_SMS');
    await expect(secondAction.getByTestId('dap-action-availability-1')).toContainText(
      '当前环境未配置真实短信 provider',
    );
    await secondAction.getByLabel('action-order-1').fill('20');
    await secondAction.getByLabel('action-target-1').fill('PHONE:+8613800138000');
    await secondAction
      .getByLabel('action-field-1-payload.content')
      .fill('短信 SLA 超时 ${sla.recordPid}');

    const contentField = firstAction.getByTestId('dap-action-field-0-payload.content');
    await contentField.getByRole('button', { name: /插入字段/ }).click();
    const fieldPicker = page.getByTestId('formula-field-picker');
    await expect(fieldPicker).toContainText('SLA 记录');
    await expect(fieldPicker).toContainText('sla.recordPid');
    await expect(fieldPicker).toContainText('规则输出');
    await expect(fieldPicker).toContainText('decision.outputs.deadlineMinutes');
    await page.keyboard.press('Escape').catch(() => undefined);

    const saveResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        response.url().includes('/api/meta/commands/execute/admin:update_sla_config'),
      { timeout: 15_000 },
    );
    await page.getByRole('button', { name: /^保存$|^Save$/ }).click();
    await readApi(await saveResponse);

    const saved = await readApi<SlaConfigRecord>(
      await requestGet(page, `/api/bpm/sla-configs/${pid}`),
    );
    const actionPolicy = (saved.actionPolicy ?? saved.action_policy) as Record<string, unknown>;
    expect(actionPolicy).toMatchObject({
      trigger: 'SLA_TIMEOUT',
      failureStrategy: 'FAIL_FAST',
      actions: [
        {
          type: 'NOTIFY',
          target: 'USER:1',
          order: 10,
          payload: {
            title: 'SLA 超时 ${sla.recordPid}',
            content: '节点 ${task.nodeId} 已超时，规则输出 ${decision.outputs.deadlineMinutes}',
          },
        },
        {
          type: 'SEND_SMS',
          target: 'PHONE:+8613800138000',
          order: 20,
          payload: {
            content: '短信 SLA 超时 ${sla.recordPid}',
          },
        },
      ],
    });

    await openSlaConfigDetail(page, slaName);
    const readonlyBlock = page.getByTestId('decision-action-plan-block');
    await expect(readonlyBlock).toBeVisible({ timeout: 15_000 });
    await expect(readonlyBlock).toContainText('超时后动作');
    await expect(readonlyBlock).toContainText('失败即停止');
    await expect(readonlyBlock).toContainText('USER:1');
    await expect(readonlyBlock).toContainText('节点 ${task.nodeId} 已超时');
    await expect(readonlyBlock.getByTestId('dap-action-availability-1')).toContainText(
      '当前环境未配置真实短信 provider',
    );
    await expect(readonlyBlock).toContainText('PHONE:+8613800138000');
    await expect(readonlyBlock).toContainText('短信 SLA 超时 ${sla.recordPid}');
    await expect(readonlyBlock.getByRole('link', { name: '查看日志' })).toHaveAttribute(
      'href',
      `/p/decisionops_execution_logs?callerType=SLA&callerRef=${pid}`,
    );

    await readonlyBlock.scrollIntoViewIfNeeded();
    await readonlyBlock.screenshot({
      path: testInfo.outputPath('sla-action-policy-saved.png'),
    });
  } finally {
    await deleteSlaConfig(page, pid);
  }
});
