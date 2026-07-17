import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router';
import { getApiService } from '~/shared/services/ApiService';
import {
  createDecisionApi,
  type DecisionApi,
  type EventPolicyActionLogRecord,
  type DecisionLogFilters,
  type DecisionLogRecord,
  type DecisionVirtualSourceTrace,
  type HttpClient,
} from '~/shared/decision/api/decisionApi';
import { decisionStatusLabel } from '~/shared/decision/ui/statusLabels';
import { valueLabel } from '~/shared/decision/ui/displayLabels';

interface ExecutionLogTraceBlockProps {
  block?: {
    props?: ExecutionLogTraceProps;
    mode?: ExecutionLogTraceProps['mode'];
  };
  runtime?: {
    getContext?: () => {
      record?: Record<string, unknown>;
      row?: Record<string, unknown>;
      data?: Record<string, unknown>;
    };
  };
}

interface ExecutionLogTraceProps {
  mode?: 'list' | 'detail';
  initialDecisionCode?: string;
  initialKeyword?: string;
  pageSize?: number;
}

type MatchedFilter = 'ALL' | 'true' | 'false';

type FilterState = {
  keyword: string;
  decisionCode: string;
  status: string;
  callerType: string;
  callerRef: string;
  policyCode: string;
  correlationId: string;
  matched: MatchedFilter;
  rolloutArm: string;
  minDurationMs: string;
  maxDurationMs: string;
};

const STATUS_OPTIONS = ['ALL', 'MATCHED', 'NOT_MATCHED', 'ERROR', 'SKIPPED', 'UNKNOWN'];
const CALLER_OPTIONS = ['ALL', 'API', 'AUTOMATION', 'EVENT_POLICY', 'SLA', 'BPM', 'TEST'];
const ROLLOUT_OPTIONS = ['ALL', 'BASELINE', 'CANDIDATE'];
const CALLER_LABELS: Record<string, string> = {
  ALL: '全部',
  API: 'API',
  AUTOMATION: '自动化',
  EVENT_POLICY: '事件策略',
  SLA: 'SLA',
  BPM: 'BPM',
  TEST: '测试',
};
const ROLLOUT_LABELS: Record<string, string> = {
  ALL: '全部',
  BASELINE: '基线',
  CANDIDATE: '候选',
};
const DECISION_LABELS: Record<string, string> = {
  complaint_sla_deadline: '请假审批 SLA 截止时间',
  sla_deadline: 'SLA 截止时间',
  approval_routing: '请假审批分派',
  leave_request_automation: '请假申请自动化策略',
};
const ACTION_TYPE_LABELS: Record<string, string> = {
  NOTIFY: '发送站内通知',
  SEND_SMS: '发送短信',
  SEND_IM: '发送 IM 消息',
  START_PROCESS: '启动流程',
  CREATE_TASK: '创建任务',
  CC_TASK: '抄送任务',
  ADD_COMMENT: '添加评论',
  UPDATE_RECORD: '更新记录',
  PATCH_RECORD: '更新记录',
  WEBHOOK: '调用 Webhook',
  WRITE_AUDIT: '写入审计',
};
const ACTION_PAYLOAD_LABELS: Record<string, string> = {
  sentCount: '发送数',
  recipientCount: '接收人数',
  channel: '通道',
  targetPhones: '短信号码',
  targetType: '接收类型',
  target: '接收对象',
  targetUserId: '接收用户',
  assigneeUserId: '处理人',
  invalidTarget: '无效接收对象',
  source: '来源',
  sourceId: '来源',
  title: '标题',
  content: '评论内容',
  mentions: '提及对象',
  commentPid: '评论',
  auditPid: '审计记录',
  actionType: '动作类型',
  tenantId: '租户',
  ruleCode: '规则',
  message: '消息',
  notificationRef: '通知记录',
  recipientType: '接收类型',
  recipientId: '接收对象',
  targetUserIds: '接收用户',
  assigneeUserIds: '处理人',
  createdCount: '创建数',
  inboxItemIds: '待办记录',
  itemType: '待办类型',
  ccCount: '抄送数',
  delivery: '投递方式',
  taskId: '任务 ID',
  processInstanceId: '流程实例',
  processDefinitionId: '流程标识',
  businessKey: '业务主键',
  modelCode: '模型',
  recordPid: '业务记录',
  wd_req_type: '请假类型',
  leaveType: '请假类型',
  leave_type: '请假类型',
  reqType: '请假类型',
  updatedFields: '更新字段',
  attemptCount: '尝试次数',
  maxAttempts: '最大尝试',
  eventType: '事件',
  dispatchAccepted: '已接收调度',
  deliveryEventId: '投递追踪',
  deliveryTraceStatus: '投递状态',
  deliveryLogPids: '投递日志',
  deliveryReceipts: '投递回执',
  payloadKeys: 'Payload 字段',
  validationError: '校验错误',
  field: '字段',
  actualLength: '当前长度',
  maxLength: '最大长度',
  retryExhausted: '重试耗尽',
  failureReason: '失败原因',
  errorMessage: '错误信息',
  requiredContext: '必需上下文',
  fieldCount: '字段数',
  resolvedCount: '解析人数',
};
const ACTION_PAYLOAD_ORDER = [
  'sentCount',
  'recipientCount',
  'channel',
  'targetPhones',
  'recipientType',
  'recipientId',
  'targetUserIds',
  'assigneeUserIds',
  'createdCount',
  'ccCount',
  'inboxItemIds',
  'itemType',
  'delivery',
  'taskId',
  'processInstanceId',
  'processDefinitionId',
  'businessKey',
  'modelCode',
  'recordPid',
  'updatedFields',
  'eventType',
  'dispatchAccepted',
  'deliveryEventId',
  'deliveryTraceStatus',
  'deliveryLogPids',
  'deliveryReceipts',
  'validationError',
  'field',
  'requiredContext',
  'actualLength',
  'maxLength',
  'payloadKeys',
  'retryExhausted',
  'failureReason',
  'errorMessage',
  'resolvedCount',
  'attemptCount',
  'maxAttempts',
  'fieldCount',
  'targetType',
  'target',
  'source',
  'sourceId',
  'title',
  'content',
  'mentions',
  'commentPid',
  'auditPid',
  'actionType',
  'tenantId',
  'ruleCode',
  'message',
  'notificationRef',
];

const ACTION_PAYLOAD_VALUE_LABELS: Record<string, string> = {
  pending_async_delivery: '异步投递中',
  tracked_delivery_logs: '已记录投递日志',
  validation_failed: '校验失败',
  dispatch_failed: '投递失败',
  inbox: '待办',
  task: '任务',
  mention: '抄送任务',
  cc_task: '抄送任务',
  target_resolved_no_users: '目标未匹配到用户',
  webhook_dispatch_failed: 'Webhook 投递失败',
  process_definition_missing: '缺少流程标识',
  process_start_failed: '流程启动失败',
  record_context_missing: '缺少业务记录上下文',
  update_fields_missing: '缺少更新字段',
  record_update_failed: '更新记录失败',
  comment_context_missing: '缺少业务记录上下文',
  comment_content_missing: '缺少评论内容',
  comment_write_failed: '添加评论失败',
  audit_tenant_missing: '缺少租户上下文',
  audit_write_failed: '写入审计失败',
  'payload.processDefinitionId': '流程标识',
  'payload.fields': '更新字段',
  'payload.content': '评论内容',
  'record.entityCode': '记录模型',
  'record.recordPid': '业务记录',
  tenantId: '租户',
  NOTIFY: '发送站内通知',
  SEND_SMS: '发送短信',
  SEND_IM: '发送 IM 消息',
  CREATE_TASK: '创建任务',
  CC_TASK: '抄送任务',
  START_PROCESS: '启动流程',
  WEBHOOK: '调用 Webhook',
  UPDATE_RECORD: '更新记录',
  PATCH_RECORD: '更新记录',
  ADD_COMMENT: '添加评论',
  WRITE_AUDIT: '写入审计',
  ROLE: '角色',
  USER: '用户',
  GROUP: '群组',
  TEAM: '团队',
  UNKNOWN: '未知',
  modelCode: '模型',
  recordPid: '业务记录',
  action_target_missing: '缺少接收对象',
  payload_content_missing: '缺少消息内容',
  payload_title_missing: '缺少标题',
  tenant_context_missing: '缺少租户上下文',
  target_invalid: '接收对象格式错误',
  target_role_code_missing: '缺少角色编码',
  target_value_missing: '缺少接收对象值',
  target_resolved_no_phone_numbers: '目标未匹配到手机号',
  sms_delivery_failed: '短信发送失败',
  im_delivery_failed: 'IM 消息发送失败',
  task_write_failed: '创建任务失败',
  cc_task_write_failed: '抄送任务失败',
  notify_delivery_failed: '站内通知发送失败',
  action_payload_serialization_failed: '动作 Payload 序列化失败',
  'payload._eventId exceeds max length': '投递追踪 ID 超过 64 字符',
};
const TRACE_VALUE_LABELS_BY_FIELD: Record<string, Record<string, string>> = {
  wd_req_type: {
    annual: '年假',
    sick: '病假',
    personal: '事假',
    marriage: '婚假',
    maternity: '产假',
    bereavement: '丧假',
    compensatory: '调休',
  },
  leaveType: {
    annual: '年假',
    sick: '病假',
    personal: '事假',
    marriage: '婚假',
    maternity: '产假',
    bereavement: '丧假',
    compensatory: '调休',
  },
  leave_type: {
    annual: '年假',
    sick: '病假',
    personal: '事假',
    marriage: '婚假',
    maternity: '产假',
    bereavement: '丧假',
    compensatory: '调休',
  },
  reqType: {
    annual: '年假',
    sick: '病假',
    personal: '事假',
    marriage: '婚假',
    maternity: '产假',
    bereavement: '丧假',
    compensatory: '调休',
  },
};
const HIDDEN_TRACE_FIELDS = new Set(['id', 'tenant_id', 'tenantId', 'deleted_flag']);

function createApi(): DecisionApi {
  const service = getApiService();
  const http: HttpClient = {
    get: <T,>(endpoint: string, params?: Record<string, unknown>) =>
      service.get<T>(endpoint, params),
    post: <T,>(endpoint: string, body?: unknown) => service.post<T>(endpoint, body),
    delete: <T,>(endpoint: string) => service.delete<T>(endpoint),
  };
  return createDecisionApi(http);
}

function recordFromRuntime(runtime: ExecutionLogTraceBlockProps['runtime']) {
  const context = runtime?.getContext?.();
  return context?.record ?? context?.row ?? context?.data ?? {};
}

function stringValue(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value : undefined;
}

function pidFromPath(pathname: string): string | undefined {
  const match = pathname.match(/\/p\/decisionops_execution_logs\/view\/([^/?#]+)/);
  return match?.[1] ? decodeURIComponent(match[1]) : undefined;
}

function filtersFromSearch(
  search: string,
  props: ExecutionLogTraceProps,
  record: Record<string, unknown>,
): FilterState {
  const params = new URLSearchParams(search);
  const traceId = params.get('traceId') ?? undefined;
  const policyCode = params.get('policyCode') ?? undefined;
  const keyword =
    stringValue(record.traceId) ?? traceId ?? policyCode ?? props.initialKeyword ?? '';
  return {
    keyword,
    decisionCode:
      stringValue(record.decisionCode) ??
      params.get('decisionCode') ??
      props.initialDecisionCode ??
      '',
    status: params.get('status') ?? 'ALL',
    callerType: params.get('callerType') ?? 'ALL',
    callerRef: params.get('callerRef') ?? '',
    policyCode: policyCode ?? '',
    correlationId: params.get('correlationId') ?? '',
    matched: (params.get('matched') as MatchedFilter | null) ?? 'ALL',
    rolloutArm: params.get('rolloutArm') ?? 'ALL',
    minDurationMs: params.get('minDurationMs') ?? '',
    maxDurationMs: params.get('maxDurationMs') ?? '',
  };
}

function toNumberOrEmpty(value: string): number | '' {
  if (!value.trim()) return '';
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : '';
}

function apiFilters(filters: FilterState, pageSize: number): DecisionLogFilters {
  return {
    keyword: filters.keyword.trim(),
    decisionCode: filters.decisionCode.trim(),
    status: filters.status === 'ALL' ? '' : filters.status,
    callerType: filters.callerType === 'ALL' ? '' : filters.callerType,
    callerRef: filters.callerRef.trim(),
    matched: filters.matched === 'ALL' ? '' : filters.matched === 'true',
    rolloutArm: filters.rolloutArm === 'ALL' ? '' : filters.rolloutArm,
    minDurationMs: toNumberOrEmpty(filters.minDurationMs),
    maxDurationMs: toNumberOrEmpty(filters.maxDurationMs),
    page: 0,
    size: pageSize,
  };
}

function errorMessage(error: unknown): string {
  if (error instanceof Error && error.message) return error.message;
  return '日志加载失败';
}

function formatDate(value?: string): string {
  if (!value) return '-';
  return value
    .replace('T', ' ')
    .replace(/\.\d+Z?$/, '')
    .replace(/Z$/, '');
}

function display(value: unknown): string {
  if (value === undefined || value === null || value === '') return '-';
  if (typeof value === 'boolean') return value ? 'true' : 'false';
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}

function callerLabel(value: unknown): string {
  const code = display(value);
  return CALLER_LABELS[code] ?? code;
}

function callerDisplay(log: DecisionLogRecord): string {
  return `${callerLabel(log.callerType)} / ${display(log.callerRef)}`;
}

function permissionResourceCode(log: DecisionLogRecord): string | undefined {
  const callerRef = stringValue(log.callerRef);
  if (!callerRef) return undefined;
  const actionSeparator = callerRef.lastIndexOf('.');
  return actionSeparator > 0 ? callerRef.slice(0, actionSeparator) : callerRef;
}

function permissionAuditHref(log: DecisionLogRecord): string | undefined {
  const traceId = stringValue(log.traceId);
  if (!traceId || !isPermissionLog(log)) return undefined;
  const params = new URLSearchParams({ tab: 'audit', traceId });
  const resourceCode = permissionResourceCode(log);
  if (resourceCode) params.set('resourceCode', resourceCode);
  return `/enterprise/permissions?${params.toString()}`;
}

function slaConfigHref(log: DecisionLogRecord): string | undefined {
  const callerRef = stringValue(log.callerRef);
  if (!callerRef || !isSlaLog(log)) return undefined;
  return `/p/sla_config/view/${encodeURIComponent(callerRef)}`;
}

function automationHref(log: DecisionLogRecord): string | undefined {
  const callerRef = stringValue(log.callerRef);
  if (!callerRef || !isAutomationLog(log)) return undefined;
  return `/automation/${encodeURIComponent(callerRef)}`;
}

function eventPolicyCode(log: DecisionLogRecord): string | undefined {
  const callerRef = stringValue(log.callerRef);
  if (!callerRef || !isEventPolicyLog(log)) return undefined;
  return callerRef;
}

function eventPolicyDetailHrefForCode(policyCode?: string): string | undefined {
  if (!policyCode) return undefined;
  return `/p/decisionops_event_policies/view/${encodeURIComponent(policyCode)}`;
}

function eventPolicyDesignerHrefForCode(policyCode?: string): string | undefined {
  if (!policyCode) return undefined;
  const params = new URLSearchParams({ policyCode });
  return `/p/decisionops_event_policy_designer?${params.toString()}`;
}

function eventPolicyDetailHref(log: DecisionLogRecord): string | undefined {
  return eventPolicyDetailHrefForCode(eventPolicyCode(log));
}

function eventPolicyDesignerHref(log: DecisionLogRecord): string | undefined {
  return eventPolicyDesignerHrefForCode(eventPolicyCode(log));
}

function eventPolicyCodeFromFilters(filters: FilterState): string {
  if (filters.callerType.trim().toUpperCase() !== 'EVENT_POLICY') return '';
  return filters.policyCode.trim() || filters.callerRef.trim();
}

function rolloutLabel(value: unknown): string {
  const code = display(value);
  return ROLLOUT_LABELS[code] ?? code;
}

function rolloutDisplay(log: DecisionLogRecord): string {
  return `${rolloutLabel(log.rolloutArm)}${log.rolloutBucket != null ? ` #${log.rolloutBucket}` : ''}`;
}

function decisionLabel(value: unknown): string {
  const code = display(value);
  if (code === '-') return code;
  return DECISION_LABELS[code] ?? code;
}

function decisionTitle(value: unknown): string {
  const code = display(value);
  const label = decisionLabel(value);
  return label === code ? code : `${label} (${code})`;
}

function decisionCell(value: unknown) {
  return (
    <div className="elta-cell-text" title={decisionTitle(value)}>
      {decisionLabel(value)}
    </div>
  );
}

function payloadDisplay(value: unknown, key = ''): string {
  const fieldLabels = traceValueLabels(key);
  if (key === 'deliveryReceipts' && Array.isArray(value)) {
    return (
      value
        .map(formatDeliveryReceipt)
        .filter((item) => item !== '-')
        .join('; ') || '-'
    );
  }
  if (Array.isArray(value)) {
    return (
      value
        .map((item) => payloadDisplay(item, key))
        .filter((item) => item !== '-')
        .join(', ') || '-'
    );
  }
  if (typeof value === 'string') {
    const labeled = valueLabel(value, fieldLabels);
    if (labeled !== value) return labeled;
    return ACTION_PAYLOAD_VALUE_LABELS[value] ?? display(value);
  }
  return display(value);
}

function traceValueLabels(key: string): Record<string, string> | undefined {
  if (!key) return undefined;
  const normalized = key.split('.').filter(Boolean).pop() ?? key;
  return TRACE_VALUE_LABELS_BY_FIELD[normalized];
}

function formatDeliveryReceipt(value: unknown): string {
  if (!value || typeof value !== 'object') return payloadDisplay(value);
  const receipt = value as Record<string, unknown>;
  return [receipt.subscriptionPid, receipt.deliveryLogPid, receipt.deliveryStatus]
    .map((item) => (item == null || item === '' ? '-' : String(item)))
    .join(' / ');
}

function cellText(value: unknown, className?: string) {
  return (
    <div className={`elta-cell-text${className ? ` ${className}` : ''}`}>{display(value)}</div>
  );
}

function matchedRuleLabels(raw: unknown): string[] {
  if (!raw) return [];
  if (Array.isArray(raw)) {
    return raw
      .map((item) => {
        if (typeof item === 'string') return item;
        if (!item || typeof item !== 'object') return null;
        const row = item as Record<string, unknown>;
        const candidate = row.ruleId ?? row.ruleCode ?? row.name ?? row.reason;
        return typeof candidate === 'string' && candidate.trim() ? candidate : null;
      })
      .filter((item): item is string => Boolean(item));
  }
  if (typeof raw === 'object') {
    const obj = raw as Record<string, unknown>;
    if (Array.isArray(obj.rules)) return matchedRuleLabels(obj.rules);
    if (Array.isArray(obj.matchedRules)) return matchedRuleLabels(obj.matchedRules);
  }
  return typeof raw === 'string' && raw.trim() ? [raw] : [];
}

function outputSnapshotEntries(raw: unknown) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return [];
  return Object.entries(raw as Record<string, unknown>)
    .filter(([, value]) => value !== undefined)
    .sort(([left], [right]) => left.localeCompare(right));
}

function traceSnapshotRecord(raw: unknown): Record<string, unknown> | null {
  return raw && typeof raw === 'object' && !Array.isArray(raw)
    ? (raw as Record<string, unknown>)
    : null;
}

function virtualSourceEntries(raw: unknown): DecisionVirtualSourceTrace[] {
  const sources = traceSnapshotRecord(raw)?.virtualSources;
  return Array.isArray(sources)
    ? sources.filter((item): item is DecisionVirtualSourceTrace =>
        Boolean(traceSnapshotRecord(item)),
      )
    : [];
}

function virtualSourceFieldEntries(fields?: Record<string, unknown>) {
  if (!fields) return [];
  return Object.entries(fields)
    .filter(([key, value]) => value !== undefined && !HIDDEN_TRACE_FIELDS.has(key))
    .sort(([left], [right]) => left.localeCompare(right));
}

function unknownReasonEntries(raw: unknown): string[] {
  const reasons = traceSnapshotRecord(raw)?.unknownReasons;
  return Array.isArray(reasons)
    ? reasons.filter((item): item is string => typeof item === 'string' && item.trim().length > 0)
    : [];
}

function sortedTrace(records: DecisionLogRecord[]) {
  return [...records].sort((left, right) =>
    String(left.createdAt ?? '').localeCompare(String(right.createdAt ?? '')),
  );
}

function isEventPolicyLog(log: DecisionLogRecord): boolean {
  return String(log.callerType ?? '').toUpperCase() === 'EVENT_POLICY';
}

function isAutomationLog(log: DecisionLogRecord): boolean {
  return String(log.callerType ?? '').toUpperCase() === 'AUTOMATION';
}

function isPermissionLog(log: DecisionLogRecord): boolean {
  return String(log.callerType ?? '').toUpperCase() === 'PERMISSION';
}

function isSlaLog(log: DecisionLogRecord): boolean {
  return String(log.callerType ?? '').toUpperCase() === 'SLA';
}

function orderedPayloadEntries(payload?: Record<string, unknown>) {
  if (!payload) return [];
  const seen = new Set<string>();
  const ordered = ACTION_PAYLOAD_ORDER.filter((key) =>
    Object.prototype.hasOwnProperty.call(payload, key),
  ).map((key) => {
    seen.add(key);
    return [key, payload[key]] as const;
  });
  const rest = Object.entries(payload)
    .filter(([key]) => !seen.has(key))
    .sort(([left], [right]) => left.localeCompare(right));
  return [...ordered, ...rest];
}

function payloadLabel(key: string): string {
  return ACTION_PAYLOAD_LABELS[key] ?? key;
}

function actionRetryItems(action: EventPolicyActionLogRecord): string[] {
  const parts: string[] = [];
  const attempt = Number(action.attemptCount ?? 0);
  const maxAttempts = Number(action.maxAttempts ?? 0);
  const status = String(action.status ?? '')
    .trim()
    .toUpperCase();
  const retryState =
    ['FAILED', 'NO_HANDLER', 'RETRY_PENDING', 'DEAD_LETTER'].includes(status) ||
    Boolean(action.nextRetryAt || action.deadLetteredAt || action.resultPayload?.retryExhausted);
  const attemptLabel = retryState ? '重试' : '尝试';
  if (attempt > 0 && maxAttempts > 0) {
    parts.push(`${attemptLabel} ${attempt}/${maxAttempts}`);
  } else if (attempt > 0) {
    parts.push(`${attemptLabel} ${attempt}`);
  }
  if (retryState) {
    if (action.lastRetryAt) parts.push(`上次 ${formatDate(action.lastRetryAt)}`);
    if (action.nextRetryAt) parts.push(`下次 ${formatDate(action.nextRetryAt)}`);
    if (action.deadLetteredAt) parts.push(`死信 ${formatDate(action.deadLetteredAt)}`);
    if (action.resultPayload?.retryExhausted === true) parts.push('重试已耗尽');
  }
  return parts;
}

function isReplayableActionLog(action: EventPolicyActionLogRecord): boolean {
  if (!action.pid) return false;
  const status = String(action.status ?? '')
    .trim()
    .toUpperCase();
  return ['DEAD_LETTER', 'RETRY_PENDING', 'FAILED', 'NO_HANDLER'].includes(status);
}

function actionLogKey(action: EventPolicyActionLogRecord): string {
  return action.pid ?? action.idempotencyKey ?? `${action.ruleCode}-${action.actionType}`;
}

function idempotencyEvidence(value: unknown): string {
  return value == null || value === '' ? '幂等键 -' : '幂等键 已记录';
}

function idempotencyTitle(value: unknown): string | undefined {
  return value == null || value === '' ? undefined : String(value);
}

function actionTypeLabel(value: unknown): string {
  const code = display(value);
  if (code === '-') return code;
  return ACTION_TYPE_LABELS[code] ?? code;
}

function ActionLogCard({
  action,
  replayingActionPid,
  onReplay,
}: {
  action: EventPolicyActionLogRecord;
  replayingActionPid: string | null;
  onReplay: (action: EventPolicyActionLogRecord) => void;
}) {
  const retryItems = actionRetryItems(action);
  const payloadEntries = orderedPayloadEntries(action.resultPayload);
  const key = actionLogKey(action);
  return (
    <article className="elta-action-card" data-testid={`elta-action-card-${key}`}>
      <div className="elta-action-card-head">
        <strong>{display(action.ruleCode)}</strong>
        <span className={`elta-status elta-status-${action.status ?? 'UNKNOWN'}`}>
          {decisionStatusLabel(action.status)}
        </span>
      </div>
      <div className="elta-action-sub">
        <span>{actionTypeLabel(action.actionType)}</span>
        <span>{formatDate(action.executedAt)}</span>
        <span className="mono" title={idempotencyTitle(action.idempotencyKey)}>
          {idempotencyEvidence(action.idempotencyKey)}
        </span>
      </div>
      {retryItems.length ? (
        <div className="elta-action-retry" data-testid={`elta-action-retry-${key}`}>
          {retryItems.map((item) => (
            <span key={item}>{item}</span>
          ))}
        </div>
      ) : null}
      {isReplayableActionLog(action) ? (
        <button
          type="button"
          className="elta-action-replay"
          data-testid={`elta-action-replay-${key}`}
          disabled={replayingActionPid === action.pid}
          onClick={() => onReplay(action)}
        >
          {replayingActionPid === action.pid ? '重放中' : '重放'}
        </button>
      ) : null}
      {payloadEntries.length ? (
        <dl className="elta-action-payload">
          {payloadEntries.map(([payloadKey, value]) => (
            <div key={payloadKey}>
              <dt>{payloadLabel(payloadKey)} </dt>
              <dd>{payloadDisplay(value, payloadKey)}</dd>
            </div>
          ))}
        </dl>
      ) : null}
      {action.errorMessage ? <div className="elta-chain-error">{action.errorMessage}</div> : null}
    </article>
  );
}

export function ExecutionLogTraceBlock({ block, runtime }: ExecutionLogTraceBlockProps) {
  const api = useMemo(() => createApi(), []);
  const location = useLocation();
  const navigate = useNavigate();
  const params = useParams();
  const props = block?.props ?? {};
  const mode = props.mode ?? block?.mode ?? 'list';
  const pageSize = props.pageSize ?? 50;
  const record = useMemo(() => recordFromRuntime(runtime), [runtime]);
  const routePid =
    stringValue(record.pid) ?? stringValue(params.recordPid) ?? pidFromPath(location.pathname);
  const initialFilters = useMemo(
    () => filtersFromSearch(location.search, props, record),
    [location.search, props.initialDecisionCode, props.initialKeyword, record],
  );
  const [filters, setFilters] = useState<FilterState>(initialFilters);
  const [records, setRecords] = useState<DecisionLogRecord[]>([]);
  const [total, setTotal] = useState(0);
  const [selectedLog, setSelectedLog] = useState<DecisionLogRecord | null>(null);
  const [traceRecords, setTraceRecords] = useState<DecisionLogRecord[]>([]);
  const [actionLogs, setActionLogs] = useState<EventPolicyActionLogRecord[]>([]);
  const [linkedActionLogs, setLinkedActionLogs] = useState<EventPolicyActionLogRecord[]>([]);
  const [loading, setLoading] = useState(false);
  const [traceLoading, setTraceLoading] = useState(false);
  const [actionLogsLoading, setActionLogsLoading] = useState(false);
  const [linkedActionLogsLoading, setLinkedActionLogsLoading] = useState(false);
  const [replayingActionPid, setReplayingActionPid] = useState<string | null>(null);
  const [error, setError] = useState('');
  const traceDrawerRef = useRef<HTMLElement | null>(null);
  const linkedEventPolicyCode = eventPolicyCodeFromFilters(filters);
  const linkedEventPolicyDetailHref = eventPolicyDetailHrefForCode(linkedEventPolicyCode);
  const linkedEventPolicyDesignerHref = eventPolicyDesignerHrefForCode(linkedEventPolicyCode);

  useEffect(() => {
    setFilters(initialFilters);
  }, [initialFilters]);

  const loadTrace = useCallback(
    async (log: DecisionLogRecord) => {
      setSelectedLog(log);
      setTraceLoading(true);
      setActionLogs([]);
      setActionLogsLoading(false);
      setError('');
      try {
        const traceId = stringValue(log.traceId);
        const correlationId = stringValue(log.correlationId);
        const shouldLoadActionLogs = isEventPolicyLog(log) && Boolean(traceId || correlationId);
        setActionLogsLoading(shouldLoadActionLogs);
        const [chain, actions] = await Promise.all([
          traceId ? api.getLogs(traceId) : Promise.resolve([]),
          shouldLoadActionLogs
            ? api.getEventPolicyActionLogs({ decisionTraceId: traceId, correlationId })
            : Promise.resolve([]),
        ]);
        setTraceRecords(chain.length ? sortedTrace(chain) : [log]);
        setActionLogs(actions);
      } catch (e) {
        setError(errorMessage(e));
        setTraceRecords([log]);
        setActionLogs([]);
      } finally {
        setTraceLoading(false);
        setActionLogsLoading(false);
      }
    },
    [api],
  );

  const loadLinkedActionLogs = useCallback(
    async (nextFilters: FilterState) => {
      const policyCode = nextFilters.policyCode.trim();
      const correlationId = nextFilters.correlationId.trim();
      if (!policyCode && !correlationId) {
        setLinkedActionLogs([]);
        setLinkedActionLogsLoading(false);
        return;
      }
      setLinkedActionLogsLoading(true);
      try {
        const logs = await api.getEventPolicyActionLogs({
          policyCode,
          correlationId,
          size: Math.max(pageSize, 20),
        });
        setLinkedActionLogs(logs);
      } catch (e) {
        setError(errorMessage(e));
        setLinkedActionLogs([]);
      } finally {
        setLinkedActionLogsLoading(false);
      }
    },
    [api, pageSize],
  );

  const loadRecent = useCallback(
    async (nextFilters: FilterState) => {
      setLoading(true);
      setError('');
      try {
        const page = await api.getRecentLogs(apiFilters(nextFilters, pageSize));
        setRecords(page.records ?? []);
        setTotal(Number(page.total ?? page.records?.length ?? 0));
      } catch (e) {
        setError(errorMessage(e));
        setRecords([]);
        setTotal(0);
      } finally {
        setLoading(false);
      }
    },
    [api, pageSize],
  );

  useEffect(() => {
    if (mode !== 'list') return;
    void loadRecent(initialFilters);
    void loadLinkedActionLogs(initialFilters);
  }, [initialFilters, loadLinkedActionLogs, loadRecent, mode]);

  useEffect(() => {
    if (mode !== 'detail' || !routePid) return;
    setLoading(true);
    setError('');
    api
      .getLogByPid(routePid)
      .then((log) => {
        setRecords([log]);
        return loadTrace(log);
      })
      .catch((e) => {
        setError(errorMessage(e));
        setRecords([]);
        setSelectedLog(null);
        setTraceRecords([]);
      })
      .finally(() => setLoading(false));
  }, [api, loadTrace, mode, routePid]);

  useEffect(() => {
    if (!selectedLog || mode !== 'detail') return;
    traceDrawerRef.current?.scrollIntoView({ block: 'start', behavior: 'smooth' });
  }, [mode, selectedLog?.pid, selectedLog?.traceId]);

  const closeTrace = useCallback(() => {
    setSelectedLog(null);
    setTraceRecords([]);
    setActionLogs([]);
    setTraceLoading(false);
    setActionLogsLoading(false);
    setReplayingActionPid(null);
  }, []);

  const replayActionLog = useCallback(
    async (action: EventPolicyActionLogRecord) => {
      if (!action.pid) return;
      setReplayingActionPid(action.pid);
      setError('');
      try {
        const replayed = await api.replayEventPolicyActionLog(action.pid);
        setActionLogs((current) =>
          current.map((row) => (row.pid === action.pid ? { ...row, ...replayed } : row)),
        );
        setLinkedActionLogs((current) =>
          current.map((row) => (row.pid === action.pid ? { ...row, ...replayed } : row)),
        );
      } catch (e) {
        setError(errorMessage(e));
      } finally {
        setReplayingActionPid(null);
      }
    },
    [api],
  );

  useEffect(() => {
    if (!selectedLog || mode !== 'list') return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') closeTrace();
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [closeTrace, mode, selectedLog]);

  const updateFilter = (field: keyof FilterState, value: string) => {
    setFilters((current) => ({ ...current, [field]: value }));
  };

  const applyFilters = () => {
    void loadRecent(filters);
    void loadLinkedActionLogs(filters);
  };

  const resetFilters = () => {
    const next = filtersFromSearch('', props, {});
    setFilters(next);
    void loadRecent(next);
    void loadLinkedActionLogs(next);
  };

  const openDetail = (log: DecisionLogRecord) => {
    if (!log.pid) return;
    navigate(`/p/decisionops_execution_logs/view/${encodeURIComponent(log.pid)}`);
  };

  return (
    <section className="execution-log-trace-block" data-testid="execution-log-trace-block">
      {mode === 'list' && (
        <div className="elta-filters" data-testid="elta-filters">
          <label>
            <span>关键词</span>
            <input
              aria-label="log-keyword"
              value={filters.keyword}
              onChange={(e) => updateFilter('keyword', e.target.value)}
              placeholder="trace / caller / error"
            />
          </label>
          <label>
            <span>决策编码</span>
            <input
              aria-label="log-decision-code"
              value={filters.decisionCode}
              onChange={(e) => updateFilter('decisionCode', e.target.value)}
              placeholder="decisionCode"
            />
          </label>
          <label>
            <span>状态</span>
            <select
              aria-label="log-status"
              value={filters.status}
              onChange={(e) => updateFilter('status', e.target.value)}
            >
              {STATUS_OPTIONS.map((option) => (
                <option key={option} value={option}>
                  {option === 'ALL' ? '全部' : decisionStatusLabel(option)}
                </option>
              ))}
            </select>
          </label>
          <label>
            <span>调用方</span>
            <select
              aria-label="log-caller-type"
              value={filters.callerType}
              onChange={(e) => updateFilter('callerType', e.target.value)}
            >
              {CALLER_OPTIONS.map((option) => (
                <option key={option} value={option}>
                  {CALLER_LABELS[option] ?? option}
                </option>
              ))}
            </select>
          </label>
          <label>
            <span>命中</span>
            <select
              aria-label="log-matched"
              value={filters.matched}
              onChange={(e) => updateFilter('matched', e.target.value)}
            >
              <option value="ALL">全部</option>
              <option value="true">命中</option>
              <option value="false">未命中</option>
            </select>
          </label>
          <label>
            <span>灰度分支</span>
            <select
              aria-label="log-rollout-arm"
              value={filters.rolloutArm}
              onChange={(e) => updateFilter('rolloutArm', e.target.value)}
            >
              {ROLLOUT_OPTIONS.map((option) => (
                <option key={option} value={option}>
                  {ROLLOUT_LABELS[option] ?? option}
                </option>
              ))}
            </select>
          </label>
          <label>
            <span>最小耗时</span>
            <input
              aria-label="log-min-duration"
              type="number"
              min="0"
              value={filters.minDurationMs}
              onChange={(e) => updateFilter('minDurationMs', e.target.value)}
            />
          </label>
          <label>
            <span>最大耗时</span>
            <input
              aria-label="log-max-duration"
              type="number"
              min="0"
              value={filters.maxDurationMs}
              onChange={(e) => updateFilter('maxDurationMs', e.target.value)}
            />
          </label>
          <button type="button" data-testid="elta-apply" onClick={applyFilters} disabled={loading}>
            查询
          </button>
          <button type="button" data-testid="elta-reset" onClick={resetFilters} disabled={loading}>
            重置
          </button>
        </div>
      )}

      <div className="elta-summary">
        <strong>{mode === 'detail' ? '执行链路' : '执行日志'}</strong>
        <span data-testid="elta-count">{loading ? '加载中...' : `${records.length}/${total}`}</span>
        {error ? (
          <span className="elta-error" data-testid="elta-error">
            {error}
          </span>
        ) : null}
      </div>

      {mode === 'list' &&
      (filters.policyCode ||
        filters.correlationId ||
        linkedActionLogsLoading ||
        linkedActionLogs.length) ? (
        <section className="elta-action-evidence" data-testid="elta-linked-action-evidence">
          <div className="elta-action-evidence-head">
            <h4>动作执行证据</h4>
            <span>{linkedActionLogsLoading ? '加载中...' : `${linkedActionLogs.length} 条`}</span>
          </div>
          <div className="elta-drawer-meta">
            {filters.policyCode ? <span>策略 {filters.policyCode}</span> : null}
            {filters.correlationId ? <span>Correlation {filters.correlationId}</span> : null}
            {!selectedLog && linkedEventPolicyDetailHref ? (
              <a data-testid="elta-open-event-policy-detail" href={linkedEventPolicyDetailHref}>
                打开事件策略
              </a>
            ) : null}
            {!selectedLog && linkedEventPolicyDesignerHref ? (
              <a data-testid="elta-open-event-policy-designer" href={linkedEventPolicyDesignerHref}>
                打开策略设计器
              </a>
            ) : null}
          </div>
          {!linkedActionLogsLoading && !linkedActionLogs.length ? (
            <div className="elta-action-empty">暂无动作执行记录</div>
          ) : null}
          <div className="elta-action-list">
            {linkedActionLogs.map((action) => (
              <ActionLogCard
                action={action}
                key={actionLogKey(action)}
                onReplay={(row) => void replayActionLog(row)}
                replayingActionPid={replayingActionPid}
              />
            ))}
          </div>
        </section>
      ) : null}

      {mode === 'list' && (
        <div className="elta-table-wrap">
          <table className="elta-table">
            <colgroup>
              <col className="elta-col-trace" />
              <col className="elta-col-decision" />
              <col className="elta-col-version" />
              <col className="elta-col-status" />
              <col className="elta-col-caller" />
              <col className="elta-col-rollout" />
              <col className="elta-col-duration" />
              <col className="elta-col-time" />
              <col className="elta-col-actions" />
            </colgroup>
            <thead>
              <tr>
                <th>Trace ID</th>
                <th>决策</th>
                <th>版本</th>
                <th>状态</th>
                <th>调用方</th>
                <th>灰度</th>
                <th>耗时</th>
                <th>时间</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {records.map((log) => (
                <tr key={log.pid ?? log.traceId} data-testid={`elta-row-${log.pid ?? log.traceId}`}>
                  <td className="mono">{cellText(log.traceId, 'mono')}</td>
                  <td>{decisionCell(log.decisionCode)}</td>
                  <td>{cellText(log.selectedVersion ?? log.decisionVersion)}</td>
                  <td>
                    <span
                      className={`elta-status elta-status-${log.status ?? 'UNKNOWN'}`}
                      title={display(log.status)}
                    >
                      {decisionStatusLabel(log.status)}
                    </span>
                  </td>
                  <td>{cellText(callerDisplay(log))}</td>
                  <td>{cellText(rolloutDisplay(log))}</td>
                  <td>{cellText(log.durationMs != null ? `${log.durationMs}ms` : '-')}</td>
                  <td>{cellText(formatDate(log.createdAt))}</td>
                  <td className="elta-row-actions">
                    <button
                      type="button"
                      data-testid={`elta-open-trace-${log.pid ?? log.traceId}`}
                      onClick={() => void loadTrace(log)}
                    >
                      追踪
                    </button>
                    <button
                      type="button"
                      data-testid={`elta-open-detail-${log.pid ?? log.traceId}`}
                      onClick={() => openDetail(log)}
                      disabled={!log.pid}
                    >
                      详情
                    </button>
                  </td>
                </tr>
              ))}
              {!records.length && !loading ? (
                <tr>
                  <td colSpan={9} data-testid="elta-empty">
                    无匹配日志
                  </td>
                </tr>
              ) : null}
            </tbody>
          </table>
        </div>
      )}

      {selectedLog ? (
        <>
          {mode === 'list' ? (
            <button
              type="button"
              className="elta-drawer-backdrop"
              aria-label="关闭执行链路"
              data-testid="elta-trace-backdrop"
              onClick={closeTrace}
            />
          ) : null}
          <aside
            ref={traceDrawerRef}
            className="elta-drawer"
            role="dialog"
            aria-label="执行链路"
            aria-modal={mode === 'list' ? true : undefined}
            data-mode={mode}
            data-testid="elta-trace-drawer"
          >
            <div className="elta-drawer-head">
              <div>
                <h3>执行链路</h3>
                <span className="mono">{display(selectedLog.traceId)}</span>
              </div>
              <button type="button" data-testid="elta-close-trace" onClick={closeTrace}>
                关闭
              </button>
            </div>
            <div className="elta-drawer-meta">
              <span title={decisionTitle(selectedLog.decisionCode)}>
                决策 {decisionLabel(selectedLog.decisionCode)}
              </span>
              <span title={display(selectedLog.status)}>
                状态 {decisionStatusLabel(selectedLog.status)}
              </span>
              <span>调用方 {callerDisplay(selectedLog)}</span>
              <span>
                耗时 {selectedLog.durationMs != null ? `${selectedLog.durationMs}ms` : '-'}
              </span>
              {permissionAuditHref(selectedLog) ? (
                <a data-testid="elta-open-permission-audit" href={permissionAuditHref(selectedLog)}>
                  打开权限审计
                </a>
              ) : null}
              {slaConfigHref(selectedLog) ? (
                <a data-testid="elta-open-sla-config" href={slaConfigHref(selectedLog)}>
                  打开 SLA 配置
                </a>
              ) : null}
              {automationHref(selectedLog) ? (
                <a data-testid="elta-open-automation" href={automationHref(selectedLog)}>
                  打开自动化
                </a>
              ) : null}
              {eventPolicyDetailHref(selectedLog) ? (
                <a
                  data-testid="elta-open-event-policy-detail"
                  href={eventPolicyDetailHref(selectedLog)}
                >
                  打开事件策略
                </a>
              ) : null}
              {eventPolicyDesignerHref(selectedLog) ? (
                <a
                  data-testid="elta-open-event-policy-designer"
                  href={eventPolicyDesignerHref(selectedLog)}
                >
                  打开策略设计器
                </a>
              ) : null}
            </div>
            {traceLoading ? <div data-testid="elta-trace-loading">Trace 加载中...</div> : null}
            {isEventPolicyLog(selectedLog) ? (
              <section className="elta-action-evidence" data-testid="elta-action-evidence">
                <div className="elta-action-evidence-head">
                  <h4>动作执行证据</h4>
                  <span>{actionLogsLoading ? '加载中...' : `${actionLogs.length} 条`}</span>
                </div>
                {!actionLogsLoading && !actionLogs.length ? (
                  <div className="elta-action-empty">暂无动作执行记录</div>
                ) : null}
                <div className="elta-action-list">
                  {actionLogs.map((action) => (
                    <ActionLogCard
                      action={action}
                      key={actionLogKey(action)}
                      onReplay={(row) => void replayActionLog(row)}
                      replayingActionPid={replayingActionPid}
                    />
                  ))}
                </div>
              </section>
            ) : null}
            <ol className="elta-chain" data-testid="elta-trace-chain">
              {traceRecords.map((log, index) => (
                <li
                  key={log.pid ?? `${log.traceId}-${index}`}
                  className={log.pid && log.pid === selectedLog.pid ? 'elta-chain-current' : ''}
                  data-testid={`elta-chain-node-${log.pid ?? index}`}
                >
                  <div className="elta-chain-main">
                    <strong title={decisionTitle(log.decisionCode)}>
                      {decisionLabel(log.decisionCode)}
                    </strong>
                    <span
                      className={`elta-status elta-status-${log.status ?? 'UNKNOWN'}`}
                      title={display(log.status)}
                    >
                      {decisionStatusLabel(log.status)}
                    </span>
                  </div>
                  <div className="elta-chain-sub">
                    <span>v{display(log.selectedVersion ?? log.decisionVersion)}</span>
                    <span>{display(log.runtimeAdapter)}</span>
                    <span>{log.durationMs != null ? `${log.durationMs}ms` : '-'}</span>
                    <span>{formatDate(log.createdAt)}</span>
                  </div>
                  <div className="elta-chain-rules">
                    命中规则: {matchedRuleLabels(log.matchedRulesJson).join(', ') || '-'}
                  </div>
                  {outputSnapshotEntries(log.outputSnapshot).length ? (
                    <section
                      className="elta-output-snapshot"
                      data-testid={`elta-output-snapshot-${log.pid ?? index}`}
                    >
                      <h4>DMN 输出</h4>
                      <dl className="elta-action-payload">
                        {outputSnapshotEntries(log.outputSnapshot).map(([key, value]) => (
                          <div key={key}>
                            <dt>{payloadLabel(key)}</dt>
                            <dd>{payloadDisplay(value, key)}</dd>
                          </div>
                        ))}
                      </dl>
                    </section>
                  ) : null}
                  {virtualSourceEntries(log.traceSnapshot).length ? (
                    <section
                      className="elta-output-snapshot"
                      data-testid={`elta-virtual-sources-${log.pid ?? index}`}
                    >
                      <h4>虚拟源</h4>
                      {virtualSourceEntries(log.traceSnapshot).map((source, sourceIndex) => (
                        <article
                          className="elta-virtual-source"
                          data-testid={`elta-virtual-source-${log.pid ?? index}-${sourceIndex}`}
                          key={`${source.sourceRef ?? source.modelCode ?? sourceIndex}-${sourceIndex}`}
                        >
                          <div className="elta-chain-sub">
                            <span>{display(source.status)}</span>
                            <span className="mono">{display(source.sourceRef)}</span>
                            <span>{display(source.modelCode)}</span>
                            <span className="mono">{display(source.recordId)}</span>
                          </div>
                          {source.reason ? (
                            <div className="elta-chain-error">{source.reason}</div>
                          ) : null}
                          {virtualSourceFieldEntries(source.fields).length ? (
                            <dl className="elta-action-payload">
                              {virtualSourceFieldEntries(source.fields).map(([key, value]) => (
                                <div key={key}>
                                  <dt>{payloadLabel(key)}</dt>
                                  <dd>{payloadDisplay(value, key)}</dd>
                                </div>
                              ))}
                            </dl>
                          ) : null}
                        </article>
                      ))}
                    </section>
                  ) : null}
                  {unknownReasonEntries(log.traceSnapshot).length ? (
                    <section
                      className="elta-output-snapshot"
                      data-testid={`elta-unknown-reasons-${log.pid ?? index}`}
                    >
                      <h4>未知原因</h4>
                      <ul>
                        {unknownReasonEntries(log.traceSnapshot).map((reason) => (
                          <li key={reason}>{reason}</li>
                        ))}
                      </ul>
                    </section>
                  ) : null}
                  {log.errorMessage ? (
                    <div className="elta-chain-error">{log.errorMessage}</div>
                  ) : null}
                </li>
              ))}
            </ol>
          </aside>
        </>
      ) : null}
    </section>
  );
}

export default ExecutionLogTraceBlock;
