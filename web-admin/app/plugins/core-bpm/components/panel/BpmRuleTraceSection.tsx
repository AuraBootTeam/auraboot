/**
 * BpmRuleTraceSection - rule-center runtime trace for BPM process instances.
 *
 * The audit history section explains who operated the process. This section
 * explains what the rule center evaluated while the process was running:
 * decision code, BPM node, match status, outputs, trace id, and duration.
 */
import { useEffect, useMemo, useState } from 'react';
import { AlertCircle, CheckCircle2, Clock3, ExternalLink, GitBranch } from 'lucide-react';

import {
  listExecutionTimeline,
  type BpmExecutionLogEntry,
} from '~/plugins/core-bpm/services/bpmWorkbenchService';

type Translator = (
  key: string,
  params?: Record<string, unknown>,
  fallback?: string,
) => string;

export interface BpmRuleTraceSectionProps {
  processInstanceId: string | null | undefined;
  t?: Translator;
  compact?: boolean;
}

interface RuleBindingTracePayload {
  traceId?: string;
  consumerType?: string;
  consumerCode?: string;
  consumerNodeId?: string;
  bindingKind?: string;
  decisionCode?: string;
  version?: number | string | null;
  versionPolicy?: string;
  status?: string;
  matched?: boolean;
  inputs?: Record<string, unknown>;
  outputs?: Record<string, unknown>;
  fallbackApplied?: boolean;
  durationMs?: number;
  errorCode?: string;
  errors?: unknown[];
}

interface RuleTraceItem {
  entry: BpmExecutionLogEntry;
  payload: RuleBindingTracePayload;
}

interface ActionTracePayload {
  status?: string;
  actionType?: string;
  channel?: string;
  delivery?: string;
  itemType?: string;
  failureReason?: string;
  targetType?: string;
  target?: string;
  invalidTarget?: string;
  field?: string;
  requiredContext?: unknown;
  targetPhones?: unknown;
  targetUserId?: unknown;
  targetUserIds?: unknown;
  assigneeUserId?: unknown;
  assigneeUserIds?: unknown;
  sentCount?: number;
  recipientCount?: number;
  resolvedCount?: number;
  messageIds?: unknown;
  conversationIds?: unknown;
  inboxItemIds?: unknown;
  taskId?: string;
  recordPid?: string;
  modelCode?: string;
  providerType?: string;
  providerCodes?: unknown;
  idempotencyKey?: string;
}

interface ActionTraceItem {
  entry: BpmExecutionLogEntry;
  payload: ActionTracePayload;
}

type TimelineTraceItem =
  | { kind: 'rule'; entry: BpmExecutionLogEntry; payload: RuleBindingTracePayload }
  | { kind: 'action'; entry: BpmExecutionLogEntry; payload: ActionTracePayload };

const OUTPUT_LABELS: Record<string, string> = {
  approverRole: '审批角色',
  candidateGroup: '候选审批组',
  candidateGroups: '候选审批组',
  candidateGroupIds: '候选审批组',
  candidateUserIds: '候选审批人',
  candidateUsers: '候选审批人',
  primaryAssignee: '主审批人',
  reviewGroups: '审批组',
  reviewUsers: '审批人',
  assigneeUserId: '审批人',
  assigneeUserIds: '审批人',
  deadlineMinutes: '截止分钟数',
  warningMinutes: '预警分钟数',
  route: '路由',
  severity: '级别',
  message: '消息',
};

function decisionTraceHref(payload: RuleBindingTracePayload): string | null {
  if (!payload.traceId) return null;
  const params = new URLSearchParams({ traceId: payload.traceId });
  if (payload.decisionCode) params.set('decisionCode', payload.decisionCode);
  params.set('callerType', payload.consumerType || 'BPM');
  if (payload.consumerCode) params.set('callerRef', payload.consumerCode);
  return `/p/decisionops_execution_logs?${params.toString()}`;
}

const ACTION_LABELS: Record<string, string> = {
  ADD_COMMENT: '添加评论',
  CC_TASK: '抄送任务',
  CREATE_TASK: '创建任务',
  NOTIFY: '发送通知',
  PATCH_RECORD: '更新记录',
  SEND_IM: '发送 IM 消息',
  SEND_SMS: '发送短信',
  SEND_WEBHOOK: '发送 Webhook',
  START_PROCESS: '启动流程',
  UPDATE_RECORD: '更新记录',
  WEBHOOK: '发送 Webhook',
  WRITE_AUDIT: '写入审计',
};

const CHANNEL_LABELS: Record<string, string> = {
  in_app: '站内通知',
  im: 'IM',
  sms: '短信',
  webhook: 'Webhook',
};

const DELIVERY_LABELS: Record<string, string> = {
  bpm_cc: '流程抄送',
  inbox: '待办',
};

const ITEM_TYPE_LABELS: Record<string, string> = {
  mention: '抄送任务',
  task: '待办任务',
};

const TARGET_TYPE_LABELS: Record<string, string> = {
  GROUP: '用户组',
  PHONE: '手机号',
  ROLE: '角色',
  TEAM: '团队',
  UNKNOWN: '未识别',
  USER: '用户',
};

const FIELD_VALUE_LABELS: Record<string, string> = {
  'action.target': '动作目标',
  'payload.assignee': '负责人',
  'payload.content': '消息内容',
  'payload.fields': '更新字段',
  'payload.title': '任务标题',
  'record.entityCode': '记录模型',
  'record.recordPid': '业务记录',
  target: '动作目标',
  tenantId: '租户',
};

const FAILURE_REASON_LABELS: Record<string, string> = {
  action_payload_serialization_failed: '动作载荷序列化失败',
  action_target_missing: '缺少动作目标',
  audit_tenant_missing: '缺少租户上下文',
  audit_write_failed: '写入审计失败',
  cc_task_write_failed: '抄送任务失败',
  comment_content_missing: '缺少评论内容',
  comment_context_missing: '缺少业务记录上下文',
  comment_write_failed: '添加评论失败',
  configuration_missing: '配置不完整',
  handler_unavailable: '动作处理器不可用',
  im_delivery_failed: 'IM 发送失败',
  notify_delivery_failed: '通知发送失败',
  payload_content_missing: '缺少消息内容',
  payload_title_missing: '缺少任务标题',
  process_key_missing: '缺少流程标识',
  process_start_failed: '流程启动失败',
  provider_unavailable: '真实 provider 不可用',
  record_context_missing: '缺少业务记录上下文',
  record_update_failed: '更新记录失败',
  sms_delivery_failed: '短信发送失败',
  target_invalid: '目标格式无效',
  target_resolved_no_phone_numbers: '目标未匹配到手机号',
  target_resolved_no_users: '目标未匹配到用户',
  target_resolution_failed: '接收对象解析失败',
  target_role_code_missing: '角色目标缺少编码',
  target_value_missing: '目标值缺失',
  task_write_failed: '创建任务失败',
  tenant_context_missing: '缺少租户上下文',
  update_fields_missing: '缺少更新字段',
  webhook_dispatch_failed: 'Webhook 投递失败',
};

function fallbackT(_key: string, _params?: Record<string, unknown>, fallback?: string): string {
  return fallback ?? _key;
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}

function asString(value: unknown): string {
  if (value === null || value === undefined) return '';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  return JSON.stringify(value);
}

function formatValue(value: unknown): string {
  if (Array.isArray(value)) {
    return value.map((item) => asString(item)).join(', ');
  }
  return asString(value);
}

function formatLabeledValue(value: unknown): string {
  if (Array.isArray(value)) {
    return value.map((item) => formatLabeledValue(item)).join(', ');
  }
  const text = asString(value);
  return FIELD_VALUE_LABELS[text] ?? text;
}

function outputLabel(key: string): string {
  return OUTPUT_LABELS[key] ?? key;
}

function readRuleBindingPayload(entry: BpmExecutionLogEntry): RuleBindingTracePayload | null {
  const outputData = asRecord(entry.outputData);
  const nested = outputData ? asRecord(outputData.ruleBinding) : null;
  if (nested) {
    return nested as RuleBindingTracePayload;
  }
  if (entry.eventType === 'rule_evaluated' || entry.nodeType === 'ruleBinding') {
    return (outputData ?? {}) as RuleBindingTracePayload;
  }
  return null;
}

function readActionPayload(entry: BpmExecutionLogEntry): ActionTracePayload | null {
  const inputData = asRecord(entry.inputData);
  const outputData = asRecord(entry.outputData);
  const nested = inputData ? asRecord(inputData.action) : null;
  const outputAction = outputData ? asRecord(outputData.action) : null;
  const action = nested ?? outputAction;
  if (!action) {
    return null;
  }

  const actionType = asString(action.actionType ?? inputData?.actionType);
  const status = asString(action.status ?? inputData?.status).toUpperCase();
  if (entry.eventType !== 'node_failure' && entry.eventType !== 'action_executed' && !status) {
    return null;
  }
  if (!actionType && status !== 'FAILED' && status !== 'SUCCESS') {
    return null;
  }
  return {
    ...action,
    actionType,
    status: status || asString(action.status),
  } as ActionTracePayload;
}

function toRuleTraceItems(entries: BpmExecutionLogEntry[]): RuleTraceItem[] {
  return entries
    .map((entry) => {
      const payload = readRuleBindingPayload(entry);
      return payload ? { entry, payload } : null;
    })
    .filter((item): item is RuleTraceItem => item !== null);
}

function toActionTraceItems(entries: BpmExecutionLogEntry[]): ActionTraceItem[] {
  return entries
    .map((entry) => {
      const payload = readActionPayload(entry);
      return payload ? { entry, payload } : null;
    })
    .filter((item): item is ActionTraceItem => item !== null);
}

function toTraceItems(entries: BpmExecutionLogEntry[]): TimelineTraceItem[] {
  return [
    ...toRuleTraceItems(entries).map((item) => ({ kind: 'rule' as const, ...item })),
    ...toActionTraceItems(entries).map((item) => ({
      kind: 'action' as const,
      ...item,
    })),
  ];
}

function statusLabel(payload: RuleBindingTracePayload): string {
  const status = asString(payload.status).toUpperCase();
  if (isFailureClosed(payload)) return '失败关闭';
  if (status === 'ERROR' || payload.errorCode) return '执行失败';
  if (payload.matched === true || status === 'MATCHED') return '已命中';
  if (payload.fallbackApplied) return '已走失败策略';
  if (status === 'NO_MATCH' || payload.matched === false) return '未命中';
  if (status) return status;
  return '未知';
}

function statusClass(payload: RuleBindingTracePayload): string {
  if (isFailureClosed(payload) || asString(payload.status).toUpperCase() === 'ERROR') {
    return 'bg-red-50 text-red-700 ring-red-200';
  }
  if (payload.matched === true || asString(payload.status).toUpperCase() === 'MATCHED') {
    return 'bg-emerald-50 text-emerald-700 ring-emerald-200';
  }
  if (payload.fallbackApplied || payload.errorCode) {
    return 'bg-amber-50 text-amber-700 ring-amber-200';
  }
  return 'bg-gray-100 text-gray-600 ring-gray-200';
}

function isFailureClosed(payload: RuleBindingTracePayload): boolean {
  const status = asString(payload.status).toUpperCase();
  const hasError = Boolean(payload.errorCode) || status === 'ERROR' || Boolean(payload.errors?.length);
  return Boolean(payload.fallbackApplied && hasError);
}

function compactJson(value: unknown): string {
  if (value === null || value === undefined) return '{}';
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
}

function traceDetails(payload: RuleBindingTracePayload): Record<string, unknown> {
  const details: Record<string, unknown> = {
    status: statusLabel(payload),
    inputs: payload.inputs ?? {},
    outputs: payload.outputs ?? {},
  };
  if (isFailureClosed(payload)) {
    details.message = '规则执行失败，已按失败关闭策略阻断候选审批人分配。';
  }
  return details;
}

function actionLabel(actionType: unknown): string {
  const type = asString(actionType).toUpperCase();
  return ACTION_LABELS[type] ?? (type || '动作执行');
}

function isActionSuccess(payload: ActionTracePayload): boolean {
  return asString(payload.status).toUpperCase() === 'SUCCESS';
}

function actionFailureReasonLabel(payload: ActionTracePayload): string {
  const reason = asString(payload.failureReason).toLowerCase();
  if (asString(payload.actionType).toUpperCase() === 'SEND_SMS' && reason === 'provider_unavailable') {
    return '真实短信 provider 不可用';
  }
  return FAILURE_REASON_LABELS[reason] ?? '动作执行失败';
}

function actionChannelLabel(value: unknown): string {
  const channel = asString(value);
  return CHANNEL_LABELS[channel.toLowerCase()] ?? channel;
}

function actionDeliveryLabel(value: unknown): string {
  const delivery = asString(value);
  return DELIVERY_LABELS[delivery.toLowerCase()] ?? delivery;
}

function actionItemTypeLabel(value: unknown): string {
  const itemType = asString(value);
  return ITEM_TYPE_LABELS[itemType.toLowerCase()] ?? itemType;
}

function actionTargetTypeLabel(value: unknown): string {
  const targetType = asString(value).toUpperCase();
  return TARGET_TYPE_LABELS[targetType] ?? targetType;
}

function actionFields(payload: ActionTracePayload): Array<[string, string]> {
  const fields: Array<[string, string]> = [];
  if (!isActionSuccess(payload)) {
    fields.push(['失败原因', actionFailureReasonLabel(payload)]);
  }
  if (payload.channel) {
    fields.push(['通道', actionChannelLabel(payload.channel)]);
  }
  if (payload.delivery) {
    fields.push(['投递方式', actionDeliveryLabel(payload.delivery)]);
  }
  if (payload.itemType) {
    fields.push(['待办类型', actionItemTypeLabel(payload.itemType)]);
  }
  if (payload.targetType) {
    fields.push(['接收类型', actionTargetTypeLabel(payload.targetType)]);
  }
  if (payload.target) {
    fields.push(['接收对象', payload.target]);
  }
  if (payload.invalidTarget) {
    fields.push(['无效目标', payload.invalidTarget]);
  }
  if (payload.field) {
    fields.push(['字段', formatLabeledValue(payload.field)]);
  }
  if (payload.requiredContext !== null && payload.requiredContext !== undefined) {
    fields.push(['必需上下文', formatLabeledValue(payload.requiredContext)]);
  }
  if (payload.targetPhones !== null && payload.targetPhones !== undefined) {
    fields.push(['目标手机号', formatValue(payload.targetPhones)]);
  }
  if (payload.targetUserId !== null && payload.targetUserId !== undefined) {
    fields.push(['接收用户', formatValue(payload.targetUserId)]);
  }
  if (payload.targetUserIds !== null && payload.targetUserIds !== undefined) {
    fields.push(['接收用户', formatValue(payload.targetUserIds)]);
  }
  if (payload.assigneeUserId !== null && payload.assigneeUserId !== undefined) {
    fields.push(['负责人', formatValue(payload.assigneeUserId)]);
  }
  if (payload.assigneeUserIds !== null && payload.assigneeUserIds !== undefined) {
    fields.push(['负责人', formatValue(payload.assigneeUserIds)]);
  }
  if (payload.sentCount !== null && payload.sentCount !== undefined) {
    fields.push(['发送数量', asString(payload.sentCount)]);
  }
  if (payload.recipientCount !== null && payload.recipientCount !== undefined) {
    fields.push(['接收数量', asString(payload.recipientCount)]);
  }
  if (payload.resolvedCount !== null && payload.resolvedCount !== undefined) {
    fields.push(['解析数量', asString(payload.resolvedCount)]);
  }
  if (payload.messageIds !== null && payload.messageIds !== undefined) {
    fields.push(['消息', formatValue(payload.messageIds)]);
  }
  if (payload.conversationIds !== null && payload.conversationIds !== undefined) {
    fields.push(['会话', formatValue(payload.conversationIds)]);
  }
  if (payload.inboxItemIds !== null && payload.inboxItemIds !== undefined) {
    fields.push(['待办记录', formatValue(payload.inboxItemIds)]);
  }
  if (payload.taskId) {
    fields.push(['流程任务', payload.taskId]);
  }
  if (payload.modelCode) {
    fields.push(['模型', payload.modelCode]);
  }
  if (payload.recordPid) {
    fields.push(['业务记录', payload.recordPid]);
  }
  if (payload.providerType) {
    fields.push(['Provider 类型', asString(payload.providerType)]);
  }
  if (payload.providerCodes !== null && payload.providerCodes !== undefined) {
    fields.push(['Provider', formatValue(payload.providerCodes)]);
  }
  if (payload.idempotencyKey) {
    fields.push(['幂等键', asString(payload.idempotencyKey)]);
  }
  return fields;
}

function actionDetails(payload: ActionTracePayload): Record<string, unknown> {
  const details: Record<string, unknown> = {
    状态: isActionSuccess(payload) ? '动作成功' : '动作失败',
    动作: actionLabel(payload.actionType),
  };
  if (!isActionSuccess(payload)) {
    details.失败原因 = actionFailureReasonLabel(payload);
  }
  if (payload.channel) {
    details.通道 = actionChannelLabel(payload.channel);
  }
  if (payload.delivery) {
    details.投递方式 = actionDeliveryLabel(payload.delivery);
  }
  if (payload.itemType) {
    details.待办类型 = actionItemTypeLabel(payload.itemType);
  }
  if (payload.targetType) {
    details.接收类型 = actionTargetTypeLabel(payload.targetType);
  }
  if (payload.field) {
    details.字段 = formatLabeledValue(payload.field);
  }
  if (payload.sentCount !== null && payload.sentCount !== undefined) {
    details.发送数量 = payload.sentCount;
  }
  if (payload.providerType) {
    details.Provider类型 = payload.providerType;
  }
  if (payload.providerCodes !== null && payload.providerCodes !== undefined) {
    details.Provider = payload.providerCodes;
  }
  return details;
}

export function BpmRuleTraceSection({
  processInstanceId,
  t = fallbackT,
  compact = false,
}: BpmRuleTraceSectionProps) {
  const [timeline, setTimeline] = useState<BpmExecutionLogEntry[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!processInstanceId) {
      setTimeline([]);
      setError(null);
      setLoading(false);
      return;
    }

    let cancelled = false;
    setLoading(true);
    setError(null);

    listExecutionTimeline(processInstanceId)
      .then((result) => {
        if (cancelled) return;
        setTimeline(result);
        setLoading(false);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setError(err instanceof Error ? err.message : String(err));
        setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [processInstanceId]);

  const traceItems = useMemo(() => toTraceItems(timeline), [timeline]);

  if (!processInstanceId) {
    return null;
  }

  if (loading) {
    return (
      <section
        data-testid="bpm-rule-trace-loading"
        className="rounded border border-gray-200 bg-gray-50 p-4 text-sm text-gray-500"
      >
        {t('bpm.ruleTrace.loading', undefined, '加载规则执行轨迹...')}
      </section>
    );
  }

  if (error) {
    return (
      <section
        data-testid="bpm-rule-trace-error"
        className="rounded border border-red-200 bg-red-50 p-4 text-sm text-red-700"
      >
        <div className="flex items-center gap-2">
          <AlertCircle className="h-4 w-4" />
          <span>{t('bpm.ruleTrace.error', undefined, '规则执行轨迹加载失败')}: {error}</span>
        </div>
      </section>
    );
  }

  if (traceItems.length === 0) {
    return (
      <section
        data-testid="bpm-rule-trace-empty"
        className="rounded border border-gray-200 bg-white p-4 text-sm text-gray-500"
      >
        {t('bpm.ruleTrace.empty', undefined, '暂无规则或动作执行轨迹')}
      </section>
    );
  }

  return (
    <section
      data-testid="bpm-rule-trace-panel"
      className="rounded border border-gray-200 bg-white p-4"
    >
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <GitBranch className="h-4 w-4 text-blue-600" />
          <div>
            <h3 className="text-sm font-semibold text-gray-800">
              {t('bpm.ruleTrace.title', undefined, '规则与动作执行轨迹')}
            </h3>
            <p className="text-xs text-gray-500">
              {t(
                'bpm.ruleTrace.subtitle',
                undefined,
                'BPM 节点运行时的规则命中、动作结果与耗时',
              )}
            </p>
          </div>
        </div>
        <span className="rounded-full bg-blue-50 px-2.5 py-1 text-xs font-medium text-blue-700">
          {traceItems.length}
        </span>
      </div>

      <div className={compact ? 'space-y-2' : 'grid gap-3 md:grid-cols-2'}>
        {traceItems.map((item) => {
          if (item.kind === 'action') {
            const { entry, payload } = item;
            const nodeId = entry.nodeId || '-';
            const fields = actionFields(payload);
            const success = isActionSuccess(payload);
            const tone = success
              ? {
                  article: 'border-emerald-200 bg-emerald-50',
                  title: 'text-emerald-900',
                  subtle: 'text-emerald-700/70',
                  badge: 'bg-emerald-100 text-emerald-700 ring-emerald-200',
                  summary: 'bg-white ring-emerald-100',
                  text: 'text-emerald-800',
                  label: 'text-emerald-500',
                  sep: 'text-emerald-300',
                  muted: 'text-emerald-700/50',
                  details: 'text-emerald-700/70',
                  summaryText: 'text-emerald-500',
                  pre: 'text-emerald-900 ring-emerald-100',
                }
              : {
                  article: 'border-red-200 bg-red-50',
                  title: 'text-red-900',
                  subtle: 'text-red-700/70',
                  badge: 'bg-red-100 text-red-700 ring-red-200',
                  summary: 'bg-white ring-red-100',
                  text: 'text-red-800',
                  label: 'text-red-400',
                  sep: 'text-red-300',
                  muted: 'text-red-700/50',
                  details: 'text-red-700/70',
                  summaryText: 'text-red-500',
                  pre: 'text-red-900 ring-red-100',
                };
            return (
              <article
                key={entry.pid}
                data-testid={`bpm-action-trace-item-${nodeId}`}
                data-node-id={nodeId}
                className={`rounded border p-3 ${tone.article}`}
              >
                <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
                  <div className="min-w-0">
                    <div
                      data-testid="bpm-action-trace-title"
                      className={`truncate text-sm font-medium ${tone.title}`}
                      title={asString(payload.actionType)}
                    >
                      {actionLabel(payload.actionType)}
                    </div>
                    <div className={`mt-0.5 text-xs ${tone.subtle}`}>
                      {t('bpm.ruleTrace.node', undefined, '节点')} {nodeId}
                      {entry.nodeType ? ` · ${entry.nodeType}` : ' · ServiceTask'}
                    </div>
                  </div>
                  <span
                    data-testid="bpm-action-trace-status"
                    className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium ring-1 ${tone.badge}`}
                  >
                    {success ? <CheckCircle2 className="h-3.5 w-3.5" /> : <AlertCircle className="h-3.5 w-3.5" />}
                    {success ? '动作成功' : '动作失败'}
                  </span>
                </div>

                <div className={`space-y-1.5 text-xs ${tone.text}`}>
                  <div
                    data-testid="bpm-action-trace-summary"
                    className={`rounded px-2 py-1 ring-1 ${tone.summary}`}
                  >
                    {success
                      ? '动作执行成功，流程已继续推进。'
                      : '动作执行失败，流程已失败关闭。请先配置可用 provider 或调整失败策略。'}
                  </div>
                  <div className="flex flex-wrap gap-1.5">
                    {fields.map(([label, value]) => (
                      <span
                        key={label}
                        data-testid="bpm-action-trace-field"
                        className={`rounded bg-white px-2 py-1 ring-1 ${tone.summary} ${tone.text}`}
                      >
                        <span className={tone.label}>{label}</span>
                        <span className={`mx-1 ${tone.sep}`}>=</span>
                        {value}
                      </span>
                    ))}
                  </div>

                  <div className={`flex flex-wrap items-center gap-3 ${tone.muted}`}>
                    {entry.durationMs !== null && entry.durationMs !== undefined && (
                      <span className="inline-flex items-center gap-1">
                        <Clock3 className="h-3.5 w-3.5" />
                        {entry.durationMs}ms
                      </span>
                    )}
                    {entry.createdAt && <span>{entry.createdAt}</span>}
                  </div>
                </div>

                <details className={`mt-2 text-xs ${tone.details}`}>
                  <summary className={`cursor-pointer ${tone.summaryText}`}>
                    {t('bpm.ruleTrace.details', undefined, '输入输出明细')}
                  </summary>
                  <pre className={`mt-2 max-h-44 overflow-auto rounded bg-white p-2 text-[11px] ring-1 ${tone.pre}`}>
                    {compactJson(actionDetails(payload))}
                  </pre>
                </details>
              </article>
            );
          }

          const { entry, payload } = item;
          const nodeId = payload.consumerNodeId || entry.nodeId || '-';
          const outputs = asRecord(payload.outputs) ?? {};
          const outputEntries = Object.entries(outputs);
          const failedClosed = isFailureClosed(payload);
          const traceHref = decisionTraceHref(payload);
          return (
            <article
              key={entry.pid}
              data-testid={`bpm-rule-trace-item-${nodeId}`}
              data-node-id={nodeId}
              className="rounded border border-gray-200 bg-gray-50 p-3"
            >
              <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
                <div className="min-w-0">
                  <div
                    data-testid="bpm-rule-trace-decision"
                    className="truncate text-sm font-medium text-gray-800"
                    title={payload.decisionCode || '-'}
                  >
                    {payload.decisionCode || t('bpm.ruleTrace.noDecision', undefined, '条件规则')}
                  </div>
                  <div className="mt-0.5 text-xs text-gray-500">
                    {t('bpm.ruleTrace.node', undefined, '节点')} {nodeId}
                    {payload.bindingKind ? ` · ${payload.bindingKind}` : ''}
                    {payload.versionPolicy ? ` · ${payload.versionPolicy}` : ''}
                  </div>
                </div>
                <span
                  data-testid="bpm-rule-trace-status"
                  className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium ring-1 ${statusClass(payload)}`}
                >
                  {failedClosed ? (
                    <AlertCircle className="h-3.5 w-3.5" />
                  ) : (
                    <CheckCircle2 className="h-3.5 w-3.5" />
                  )}
                  {statusLabel(payload)}
                </span>
              </div>

              <div className="space-y-1.5 text-xs text-gray-600">
                {failedClosed ? (
                  <div
                    data-testid="bpm-rule-trace-fail-closed"
                    className="rounded bg-red-50 px-2 py-1 text-red-700 ring-1 ring-red-100"
                  >
                    {t(
                      'bpm.ruleTrace.failClosed',
                      undefined,
                      '规则执行失败，已按失败关闭策略阻断候选审批人分配',
                    )}
                  </div>
                ) : outputEntries.length > 0 ? (
                  <div data-testid="bpm-rule-trace-output" className="flex flex-wrap gap-1.5">
                    {outputEntries.map(([key, value]) => (
                      <span
                        key={key}
                        className="rounded bg-white px-2 py-1 text-gray-700 ring-1 ring-gray-200"
                        title={`${key}: ${formatValue(value)}`}
                      >
                        <span className="text-gray-400">{outputLabel(key)}</span>
                        <span className="mx-1 text-gray-300">=</span>
                        {formatValue(value)}
                      </span>
                    ))}
                  </div>
                ) : (
                  <div className="text-gray-400">
                    {t('bpm.ruleTrace.noOutputs', undefined, '无输出变量')}
                  </div>
                )}

                <div className="flex flex-wrap items-center gap-3 text-gray-400">
                  {payload.traceId && (
                    <span data-testid="bpm-rule-trace-id">Trace {payload.traceId}</span>
                  )}
                  {traceHref ? (
                    <a
                      data-testid={`bpm-rule-trace-open-decisionops-${nodeId}`}
                      href={traceHref}
                      className="inline-flex items-center gap-1 text-blue-600 hover:text-blue-700"
                    >
                      打开统一 Trace
                      <ExternalLink className="h-3.5 w-3.5" />
                    </a>
                  ) : null}
                  {(payload.durationMs ?? entry.durationMs) !== null &&
                    (payload.durationMs ?? entry.durationMs) !== undefined && (
                      <span className="inline-flex items-center gap-1">
                        <Clock3 className="h-3.5 w-3.5" />
                        {payload.durationMs ?? entry.durationMs}ms
                      </span>
                    )}
                  {entry.createdAt && <span>{entry.createdAt}</span>}
                </div>
              </div>

              <details className="mt-2 text-xs text-gray-500">
                <summary className="cursor-pointer text-gray-400">
                  {t('bpm.ruleTrace.details', undefined, '输入输出明细')}
                </summary>
                <pre className="mt-2 max-h-44 overflow-auto rounded bg-white p-2 text-[11px] text-gray-700 ring-1 ring-gray-200">
                  {compactJson(traceDetails(payload))}
                </pre>
              </details>
            </article>
          );
        })}
      </div>
    </section>
  );
}
