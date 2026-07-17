import { useEffect, useMemo, useState } from 'react';
import type {
  DecisionAction,
  DecisionApi,
  EventPolicySummary,
  EventPolicyVersionSummary,
  ScopedContext,
} from '../api/decisionApi';
import { group, type CompareNode, type ConditionNode, type GroupNode } from '../ast/conditionAst';
import { PolicyRulesEditor, type MatchMode, type PolicyRulesValue } from './PolicyRulesEditor';
import type { FieldOption } from './ConditionBuilder';
import type { TestSample } from './ConditionTestRunPanel';
import { ConditionTestRunPanel } from './ConditionTestRunPanel';
import {
  actionDefinitionFor,
  actionFieldInputKind,
  actionSchemaFields,
  type ActionSchemaField,
  payloadToJson,
  readActionFieldValue,
  writeActionFieldValue,
} from './actionSchemaFields';
import { resolveDecisionActionAvailability } from './actionAvailability';
import {
  DecisionRuleBindingBlock,
  type DecisionOption,
  type RuleConsumerBindingDraft,
} from '~/ui/smart/decision/DecisionRuleBindingBlock';

type DesignerStep = 'trigger' | 'rules' | 'actions' | 'test' | 'publish' | 'history';
type PolicyPhase = 'BEFORE_SUBMIT' | 'AFTER_COMMIT' | 'ASYNC_WORKER';
type ExecutionMode = 'ORDERED' | 'UNORDERED';
type FailureStrategy =
  | 'FAIL_FAST'
  | 'CONTINUE_ON_ERROR'
  | 'ALL_OR_NOTHING'
  | 'RETRY_ASYNC'
  | 'DEAD_LETTER';
type ConflictStrategy =
  | 'REJECT_ON_CONFLICT'
  | 'PRIORITY_WINS'
  | 'LAST_WRITE_WINS'
  | 'MERGE_IF_COMPATIBLE';
type DedupStrategy = 'NONE' | 'BY_IDEMPOTENCY_KEY' | 'BY_ACTION_TYPE_AND_TARGET';
type DecisionBindingValue = NonNullable<RuleConsumerBindingDraft['decisionBinding']>;

export interface EventPolicyDesignerWorkflowProps {
  api: DecisionApi;
  fields: FieldOption[];
  selectedPolicy?: EventPolicySummary | null;
  samples?: TestSample[];
}

export interface PolicyActionDraft {
  type: string;
  target: string;
  order: number;
  payloadJson: string;
  idempotencyKeyTemplate: string;
}

const STEPS: { key: DesignerStep; label: string }[] = [
  { key: 'trigger', label: '触发源' },
  { key: 'rules', label: '规则条件' },
  { key: 'actions', label: '执行动作' },
  { key: 'test', label: '测试运行' },
  { key: 'publish', label: '发布治理' },
  { key: 'history', label: '版本历史' },
];

const DEFAULT_IDEMPOTENCY =
  '${record.entityCode}:${record.recordPid}:${rule.ruleCode}:${action.type}';
const POLICY_PHASES: readonly PolicyPhase[] = ['BEFORE_SUBMIT', 'AFTER_COMMIT', 'ASYNC_WORKER'];
const EXECUTION_MODES: readonly ExecutionMode[] = ['ORDERED', 'UNORDERED'];
const FAILURE_STRATEGIES: readonly FailureStrategy[] = [
  'FAIL_FAST',
  'CONTINUE_ON_ERROR',
  'ALL_OR_NOTHING',
  'RETRY_ASYNC',
  'DEAD_LETTER',
];
const CONFLICT_STRATEGIES: readonly ConflictStrategy[] = [
  'REJECT_ON_CONFLICT',
  'PRIORITY_WINS',
  'LAST_WRITE_WINS',
  'MERGE_IF_COMPATIBLE',
];
const DEDUP_STRATEGIES: readonly DedupStrategy[] = [
  'NONE',
  'BY_IDEMPOTENCY_KEY',
  'BY_ACTION_TYPE_AND_TARGET',
];
const EVENT_POLICY_CONSUMER_TYPE = 'EVENT_POLICY';
const SAFE_ACTIONS: DecisionAction[] = [
  { actionType: 'NOTIFY', label: '发送站内通知', handlerAvailable: true },
  {
    actionType: 'SEND_SMS',
    label: '发送短信',
    handlerAvailable: false,
    availabilityStatus: 'UNAVAILABLE',
    availabilityReason: '当前环境未配置真实短信 provider',
  },
  { actionType: 'SEND_IM', label: '发送 IM 消息', handlerAvailable: true },
  { actionType: 'START_PROCESS', label: '启动流程', handlerAvailable: true },
  { actionType: 'CREATE_TASK', label: '创建任务', handlerAvailable: true },
  { actionType: 'CC_TASK', label: '抄送任务', handlerAvailable: true },
  { actionType: 'ADD_COMMENT', label: '添加评论', handlerAvailable: true },
  { actionType: 'UPDATE_RECORD', label: '更新记录', handlerAvailable: true },
  { actionType: 'PATCH_RECORD', label: '更新记录', handlerAvailable: true },
  { actionType: 'WEBHOOK', label: '调用 Webhook', handlerAvailable: true },
  { actionType: 'WRITE_AUDIT', label: '写入审计', handlerAvailable: true },
];

const PHASE_LABELS: Record<PolicyPhase, string> = {
  BEFORE_SUBMIT: '提交前同步检查',
  AFTER_COMMIT: '保存后提交执行',
  ASYNC_WORKER: '异步队列执行',
};

const EXECUTION_MODE_LABELS: Record<ExecutionMode, string> = {
  ORDERED: '按顺序执行',
  UNORDERED: '可并行执行',
};

const FAILURE_STRATEGY_LABELS: Record<FailureStrategy, string> = {
  FAIL_FAST: '失败即停止',
  CONTINUE_ON_ERROR: '失败后继续',
  ALL_OR_NOTHING: '全部成功才提交',
  RETRY_ASYNC: '异步重试',
  DEAD_LETTER: '进入死信队列',
};

const CONFLICT_STRATEGY_LABELS: Record<ConflictStrategy, string> = {
  REJECT_ON_CONFLICT: '冲突时拒绝',
  PRIORITY_WINS: '优先级高者生效',
  LAST_WRITE_WINS: '后写入生效',
  MERGE_IF_COMPATIBLE: '兼容时合并',
};

const DEDUP_STRATEGY_LABELS: Record<DedupStrategy, string> = {
  NONE: '不去重',
  BY_IDEMPOTENCY_KEY: '按幂等键去重',
  BY_ACTION_TYPE_AND_TARGET: '按动作和目标去重',
};

const MATCH_MODE_LABELS: Record<MatchMode, string> = {
  FIRST_MATCH: '命中首条即停止',
  COLLECT_ALL: '收集全部命中',
  UNIQUE: '必须唯一命中',
  PRIORITY_FIRST: '按优先级命中',
};

const ACTION_LABELS: Record<string, string> = {
  NOTIFY: '发送站内通知',
  SEND_SMS: '发送短信',
  SEND_IM: '发送 IM 消息',
  START_PROCESS: '启动流程',
  ADD_COMMENT: '添加评论',
  UPDATE_RECORD: '更新记录',
  PATCH_RECORD: '更新记录',
  WEBHOOK: '调用 Webhook',
  WRITE_AUDIT: '写入审计',
  CREATE_TASK: '创建任务',
  CC_TASK: '抄送任务',
};

const ACTION_RESULT_LABELS: Record<string, string> = {
  channel: '通道',
  recipientType: '接收类型',
  recipientId: '接收对象',
  targetType: '接收类型',
  target: '接收对象',
  targetUserId: '接收用户',
  assigneeUserId: '处理人',
  invalidTarget: '无效接收对象',
  sentCount: '发送数',
  recipientCount: '接收人数',
  targetUserIds: '接收用户',
  assigneeUserIds: '处理人',
  createdCount: '创建数',
  inboxItemIds: '待办记录',
  itemType: '待办类型',
  ccCount: '抄送数',
  delivery: '投递方式',
  taskId: '任务 ID',
  sourceId: '来源',
  processDefinitionId: '流程标识',
  processInstanceId: '流程实例',
  businessKey: '业务主键',
  recordPid: '业务记录',
  modelCode: '模型',
  ruleCode: '规则',
  updatedFields: '更新字段',
  actionType: '动作类型',
  commentPid: '评论',
  content: '评论内容',
  mentions: '提及对象',
  auditPid: '审计',
  message: '消息',
  eventType: '事件',
  tenantId: '租户',
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
  attemptCount: '尝试次数',
  maxAttempts: '最大尝试',
  failureReason: '失败原因',
  errorMessage: '错误信息',
  requiredContext: '必需上下文',
  fieldCount: '字段数',
  resolvedCount: '解析人数',
};

const ACTION_RESULT_ORDER = [
  'sentCount',
  'recipientCount',
  'createdCount',
  'ccCount',
  'targetUserIds',
  'assigneeUserIds',
  'inboxItemIds',
  'itemType',
  'delivery',
  'failureReason',
  'errorMessage',
  'targetType',
  'target',
  'resolvedCount',
  'taskId',
  'channel',
  'recipientType',
  'recipientId',
  'processInstanceId',
  'processDefinitionId',
  'businessKey',
  'modelCode',
  'recordPid',
  'ruleCode',
  'updatedFields',
  'commentPid',
  'content',
  'mentions',
  'auditPid',
  'message',
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
  'attemptCount',
  'maxAttempts',
  'fieldCount',
  'payloadKeys',
  'sourceId',
  'actionType',
  'tenantId',
];

const ACTION_RESULT_VALUE_LABELS: Record<string, string> = {
  pending_async_delivery: '异步投递中',
  tracked_delivery_logs: '已记录投递日志',
  validation_failed: '校验失败',
  dispatch_failed: '投递失败',
  inbox: '待办',
  task: '任务',
  mention: '抄送任务',
  cc_task: '抄送任务',
  ROLE: '角色',
  USER: '用户',
  UNKNOWN: '未知',
  GROUP: '群组',
  TEAM: '团队',
  target_resolved_no_users: '目标未匹配到用户',
  target_resolved_no_phone_numbers: '目标未匹配到手机号',
  action_target_missing: '缺少接收对象',
  payload_content_missing: '缺少消息内容',
  payload_title_missing: '缺少标题',
  tenant_context_missing: '缺少租户上下文',
  target_invalid: '接收对象格式错误',
  target_role_code_missing: '缺少角色编码',
  target_value_missing: '缺少接收对象值',
  sms_delivery_failed: '短信发送失败',
  im_delivery_failed: 'IM 消息发送失败',
  task_write_failed: '创建任务失败',
  cc_task_write_failed: '抄送任务失败',
  notify_delivery_failed: '站内通知发送失败',
  action_payload_serialization_failed: '动作 Payload 序列化失败',
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
  'record.entityCode': '记录模型',
  'record.recordPid': '业务记录',
  tenantId: '租户',
  'payload.processDefinitionId': '流程标识',
  'payload.fields': '更新字段',
  'payload.content': '评论内容',
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
  modelCode: '模型',
  recordPid: '业务记录',
  'payload._eventId exceeds max length': '投递追踪 ID 超过 64 字符',
};

const POLICY_STATUS_LABELS: Record<string, string> = {
  UNSAVED: '未保存',
  DRAFT: '草稿',
  VALIDATED: '已校验',
  PUBLISHED: '已发布',
  ENABLED: '已启用',
  DISABLED: '已停用',
  DEPRECATED: '已废弃',
  RETIRED: '已停用',
};

const RUN_STATUS_LABELS: Record<string, string> = {
  MATCHED: '已命中',
  NOT_MATCHED: '未命中',
  SUCCESS: '成功',
  ERROR: '执行异常',
  SKIPPED: '已跳过',
  UNKNOWN: '未知',
};

const EXECUTION_STATUS_LABELS: Record<string, string> = {
  ALL_SUCCESS: '全部成功',
  PARTIAL_SUCCESS: '部分成功',
  FAILED: '失败',
  NOTHING_TO_DO: '无动作',
  SUCCESS: '成功',
  SKIPPED: '幂等跳过',
  NO_HANDLER: '无处理器',
  RETRY_PENDING: '等待重试',
  DEAD_LETTER: '死信',
  NOT_EXECUTED: '未执行',
};

function defaultRules(matchMode?: string): PolicyRulesValue {
  return {
    matchMode: (matchMode as MatchMode | undefined) ?? 'COLLECT_ALL',
    rules: [
      {
        ruleCode: 'R-1',
        ruleName: 'Rule 1',
        priority: 100,
        enabled: true,
        condition: group('AND', []),
        actions: [],
      },
    ],
  };
}

function actionsOf(rule: PolicyRulesValue['rules'][number] | undefined): PolicyActionDraft[] {
  return (rule as { actions?: PolicyActionDraft[] } | undefined)?.actions ?? [];
}

function recordOf(value: unknown): Record<string, unknown> | null {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}

function enumOr<T extends string>(value: unknown, allowed: readonly T[], fallback: T): T {
  return typeof value === 'string' && allowed.includes(value as T) ? (value as T) : fallback;
}

function numberOr(value: unknown, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

function stringOr(value: unknown, fallback: string): string {
  return typeof value === 'string' && value.trim() ? value : fallback;
}

function isCompareNode(value: unknown): value is CompareNode {
  const record = recordOf(value);
  return record?.type === 'compare';
}

function isGroupNode(value: unknown): value is GroupNode {
  const record = recordOf(value);
  return record?.type === 'group' && Array.isArray(record.children);
}

function isConditionNode(value: unknown): value is ConditionNode {
  const record = recordOf(value);
  return isCompareNode(value) || isGroupNode(value) || record?.type === 'not';
}

function conditionGroup(value: unknown): GroupNode {
  if (isGroupNode(value)) return value;
  if (isConditionNode(value)) return group('AND', [value]);
  return group('AND', []);
}

function hydrateActions(value: unknown): PolicyActionDraft[] {
  if (!Array.isArray(value)) return [];
  return value.map((raw, idx) => {
    const action = recordOf(raw) ?? {};
    const payload = action.payload && typeof action.payload === 'object' ? action.payload : {};
    return {
      type: stringOr(action.type, 'NOTIFY'),
      target: stringOr(action.target, ''),
      order: numberOr(action.order, idx + 1),
      payloadJson: JSON.stringify(payload, null, 2),
      idempotencyKeyTemplate: stringOr(action.idempotencyKeyTemplate, DEFAULT_IDEMPOTENCY),
    };
  });
}

function hydrateDecisionBinding(value: unknown): DecisionBindingValue | undefined {
  const binding = recordOf(value);
  const fallbackPolicy = recordOf(binding?.fallbackPolicy);
  if (!binding || typeof binding.decisionCode !== 'string' || !binding.decisionCode.trim()) {
    return undefined;
  }
  return {
    decisionCode: binding.decisionCode,
    versionPolicy: enumOr(
      binding.versionPolicy,
      ['LATEST_PUBLISHED', 'FIXED_VERSION', 'VERSION_TAG', 'ROLLOUT'],
      'LATEST_PUBLISHED',
    ),
    inputMappings: Array.isArray(binding.inputMappings)
      ? (binding.inputMappings as DecisionBindingValue['inputMappings'])
      : [],
    outputMappings: Array.isArray(binding.outputMappings)
      ? (binding.outputMappings as DecisionBindingValue['outputMappings'])
      : [],
    fallbackPolicy: {
      mode: enumOr(
        fallbackPolicy?.mode,
        ['FAIL_CLOSED', 'FAIL_OPEN', 'DEFAULT_VALUE'],
        'FAIL_CLOSED',
      ),
    },
    traceMode: enumOr(binding.traceMode, ['SAMPLED', 'ALWAYS', 'NONE'], 'SAMPLED'),
    enabled: typeof binding.enabled === 'boolean' ? binding.enabled : true,
  };
}

function hydrateRules(value: unknown, matchMode?: string): PolicyRulesValue {
  if (!Array.isArray(value) || value.length === 0) return defaultRules(matchMode);
  return {
    matchMode: enumOr(
      matchMode,
      ['FIRST_MATCH', 'COLLECT_ALL', 'UNIQUE', 'PRIORITY_FIRST'],
      'COLLECT_ALL',
    ),
    rules: value.map((raw, idx) => {
      const rule = recordOf(raw) ?? {};
      const ruleCode = stringOr(rule.ruleCode, `R-${idx + 1}`);
      return {
        ruleCode,
        ruleName: stringOr(rule.ruleName, ruleCode),
        priority: numberOr(rule.priority, (idx + 1) * 100),
        enabled: typeof rule.enabled === 'boolean' ? rule.enabled : true,
        condition: conditionGroup(rule.condition),
        actions: hydrateActions(rule.actions),
        decisionBinding: hydrateDecisionBinding(rule.decisionBinding),
      };
    }),
  };
}

function latestVersion(
  versions: EventPolicyVersionSummary[],
  latestPid?: string,
): EventPolicyVersionSummary | undefined {
  return (
    versions.find((version) => latestPid && version.pid === latestPid) ??
    versions.slice().sort((a, b) => (b.version ?? 0) - (a.version ?? 0))[0]
  );
}

function parsePayload(json: string): Record<string, unknown> {
  if (!json.trim()) return {};
  const parsed = JSON.parse(json) as unknown;
  return parsed && typeof parsed === 'object' && !Array.isArray(parsed)
    ? (parsed as Record<string, unknown>)
    : {};
}

function buildRulesJson(value: PolicyRulesValue) {
  return value.rules.map((rule) => {
    const ruleJson: Record<string, unknown> = {
      ruleCode: rule.ruleCode,
      ruleName: rule.ruleName,
      priority: rule.priority,
      enabled: rule.enabled,
      condition: rule.condition,
      actions: actionsOf(rule).map((action) => ({
        type: action.type,
        target: action.target,
        order: action.order,
        payload: parsePayload(action.payloadJson),
        idempotencyKeyTemplate: action.idempotencyKeyTemplate,
      })),
    };
    if (rule.decisionBinding) {
      ruleJson.decisionBinding = rule.decisionBinding;
    }
    return ruleJson;
  });
}

function selectedRuleIndex(value: PolicyRulesValue, code: string): number {
  const idx = value.rules.findIndex((rule) => rule.ruleCode === code);
  return idx >= 0 ? idx : 0;
}

function enumOption<T extends string>(value: T, labels: Record<T, string>) {
  return (
    <option key={value} value={value}>
      {labels[value]}
    </option>
  );
}

function actionLabel(action: DecisionAction): string {
  return ACTION_LABELS[action.actionType] ?? action.label ?? action.actionType;
}

function actionAvailability(action?: DecisionAction) {
  return resolveDecisionActionAvailability(action, EVENT_POLICY_CONSUMER_TYPE);
}

function actionOptionLabel(action: DecisionAction): string {
  const label = actionLabel(action);
  return actionAvailability(action).unavailable ? `${label}（不可用）` : label;
}

function actionAvailabilityForType(type: string, catalog: DecisionAction[]) {
  return actionAvailability(catalog.find((action) => action.actionType === type));
}

function actionTypeLabel(type: string): string {
  return ACTION_LABELS[type] ?? type;
}

function editableActionFields(
  action: PolicyActionDraft,
  catalog: DecisionAction[],
): ActionSchemaField[] {
  const fields = actionSchemaFields(actionDefinitionFor(action.type, catalog));
  if (fields.length > 0) return fields;
  return [{ path: 'target', label: '执行目标', dataType: 'string', required: false }];
}

function uniqueDecisions(rules: PolicyRulesValue['rules']): DecisionOption[] {
  const decisions: DecisionOption[] = [
    { code: 'approval_routing', name: '审批路由' },
    { code: 'leave_request_automation', name: '请假策略决策' },
    { code: 'complaint_sla_deadline', name: '投诉 SLA 截止时间' },
    { code: 'task_assignee', name: '任务分派' },
  ];
  const seen = new Set(decisions.map((decision) => decision.code));
  rules.forEach((rule) => {
    const decisionCode = rule.decisionBinding?.decisionCode;
    if (decisionCode && !seen.has(decisionCode)) {
      seen.add(decisionCode);
      decisions.push({ code: decisionCode, name: decisionCode });
    }
  });
  return decisions;
}

function eventPolicyBindingValue(
  rule: PolicyRulesValue['rules'][number],
  selectedPolicy?: EventPolicySummary | null,
): RuleConsumerBindingDraft {
  return {
    consumerType: 'EVENT_POLICY',
    consumerCode: selectedPolicy?.policyCode,
    consumerNodeId: rule.ruleCode,
    bindingKind: 'DECISION_REF',
    decisionBinding: rule.decisionBinding,
    enabled: true,
  };
}

function payloadTitle(payloadJson: string): string {
  try {
    const payload = parsePayload(payloadJson);
    const title = payload.title;
    const message = payload.message ?? payload.content;
    if (typeof title === 'string' && title.trim()) return title;
    if (typeof message === 'string' && message.trim()) return message;
  } catch {
    return '负载需要修正';
  }
  return '未配置负载';
}

function runStatus(value: unknown): string {
  const result = recordOf(value);
  const policy = recordOf(result?.policy) ?? result;
  const status = stringOr(
    policy?.status,
    value === null || value === undefined ? '-' : String(value),
  );
  return RUN_STATUS_LABELS[status] ?? status;
}

function executionRecord(value: unknown): Record<string, unknown> | null {
  const result = recordOf(value);
  return recordOf(result?.execution);
}

function policyRecord(value: unknown): Record<string, unknown> | null {
  const result = recordOf(value);
  return recordOf(result?.policy) ?? recordOf(value);
}

function runCorrelationId(value: unknown): string {
  const policy = policyRecord(value);
  return stringOr(policy?.correlationId, '');
}

function eventPolicyTraceHref(value: unknown, selectedPolicy?: EventPolicySummary | null): string {
  const policyCode = stringOr(
    selectedPolicy?.policyCode,
    stringOr(policyRecord(value)?.policyCode, ''),
  );
  const correlationId = runCorrelationId(value);
  if (!policyCode || !correlationId) return '';
  const params = new URLSearchParams({
    policyCode,
    correlationId,
    callerType: 'EVENT_POLICY',
    callerRef: policyCode,
  });
  return `/p/decisionops_execution_logs?${params.toString()}`;
}

function executionStatus(value: unknown): string {
  const status = String(value ?? '-').toUpperCase();
  return EXECUTION_STATUS_LABELS[status] ?? (value == null ? '-' : String(value));
}

function actionExecutionRows(value: unknown): Record<string, unknown>[] {
  const actions = executionRecord(value)?.actions;
  if (!Array.isArray(actions)) return [];
  return actions
    .map((action) => recordOf(action))
    .filter((action): action is Record<string, unknown> => Boolean(action));
}

function isFailedExecutionAction(action: Record<string, unknown>): boolean {
  if (action.error) return true;
  const status = String(action.status ?? '').toUpperCase();
  if (!status) return false;
  return !['SUCCESS', 'SKIPPED', 'NOT_EXECUTED'].includes(status);
}

function resultPayloadRows(
  action: Record<string, unknown>,
): Array<{ key: string; label: string; value: string }> {
  const payload = recordOf(action.resultPayload);
  if (!payload) return [];
  const keys = [
    ...ACTION_RESULT_ORDER.filter((key) => Object.prototype.hasOwnProperty.call(payload, key)),
    ...Object.keys(payload).filter((key) => !ACTION_RESULT_ORDER.includes(key)),
  ];
  return keys
    .map((key) => ({
      key,
      label: ACTION_RESULT_LABELS[key] ?? key,
      value: resultPayloadValue(key, payload[key]),
    }))
    .filter((row) => row.value !== '-');
}

function resultPayloadValue(key: string, value: unknown): string {
  if (value === undefined || value === null || value === '') return '-';
  if (typeof value === 'boolean') return value ? '是' : '否';
  if (key === 'deliveryReceipts' && Array.isArray(value)) {
    return (
      value
        .map(formatDeliveryReceipt)
        .filter((item) => item !== '-')
        .join('; ') || '-'
    );
  }
  if (Array.isArray(value))
    return (
      value
        .map((item) => resultPayloadValue('', item))
        .filter((item) => item !== '-')
        .join(', ') || '-'
    );
  if (typeof value === 'string') return ACTION_RESULT_VALUE_LABELS[value] ?? value;
  if (typeof value === 'object') {
    try {
      return JSON.stringify(value);
    } catch {
      return String(value);
    }
  }
  return String(value);
}

function idempotencyEvidence(value: unknown): string {
  return value == null || value === '' ? '幂等键 -' : '幂等键 已记录';
}

function idempotencyTitle(value: unknown): string | undefined {
  return value == null || value === '' ? undefined : String(value);
}

function formatDeliveryReceipt(value: unknown): string {
  const receipt = recordOf(value);
  if (!receipt) return resultPayloadValue('', value);
  return [receipt.subscriptionPid, receipt.deliveryLogPid, receipt.deliveryStatus]
    .map((item) => (item == null || item === '' ? '-' : String(item)))
    .join(' / ');
}

function executionContextForSample(sample: TestSample | undefined): ScopedContext {
  return (sample?.executionContext?.() ??
    sample?.context ?? { record: { data: {} } }) as ScopedContext;
}

function policyStatusLabel(status: unknown): string {
  const value = String(status ?? '').toUpperCase();
  return POLICY_STATUS_LABELS[value] ?? (status ? String(status) : '-');
}

export function EventPolicyDesignerWorkflow({
  api,
  fields,
  selectedPolicy,
  samples = [],
}: EventPolicyDesignerWorkflowProps) {
  const [step, setStep] = useState<DesignerStep>('trigger');
  const [phase, setPhase] = useState<PolicyPhase>(
    (selectedPolicy?.phase as PolicyPhase | undefined) ?? 'AFTER_COMMIT',
  );
  const [executionMode, setExecutionMode] = useState<ExecutionMode>('ORDERED');
  const [failureStrategy, setFailureStrategy] = useState<FailureStrategy>('FAIL_FAST');
  const [conflictStrategy, setConflictStrategy] = useState<ConflictStrategy>('REJECT_ON_CONFLICT');
  const [dedupStrategy, setDedupStrategy] = useState<DedupStrategy>('BY_IDEMPOTENCY_KEY');
  const [rulesValue, setRulesValue] = useState<PolicyRulesValue>(() =>
    defaultRules(selectedPolicy?.matchMode),
  );
  const [selectedRuleCode, setSelectedRuleCode] = useState('R-1');
  const [draftPid, setDraftPid] = useState(selectedPolicy?.latestVersionPid ?? '');
  const [publishStatus, setPublishStatus] = useState(selectedPolicy?.status ?? 'UNSAVED');
  const [error, setError] = useState('');
  const [versionLoading, setVersionLoading] = useState(false);
  const [versionError, setVersionError] = useState('');
  const [runResult, setRunResult] = useState<unknown>(null);
  const [catalogActions, setCatalogActions] = useState<DecisionAction[]>([]);
  const [catalogError, setCatalogError] = useState('');

  const currentRuleIdx = selectedRuleIndex(rulesValue, selectedRuleCode);
  const currentRule = rulesValue.rules[currentRuleIdx];
  const currentActions = actionsOf(currentRule);
  const firstSampleContext = samples[0]?.context ?? { record: { data: {} } };
  const actionOptions = useMemo(() => {
    const runtimeActions = catalogActions.filter((action) => action.actionType);
    return runtimeActions.length > 0 ? runtimeActions : SAFE_ACTIONS;
  }, [catalogActions]);
  const decisionOptions = useMemo(() => uniqueDecisions(rulesValue.rules), [rulesValue.rules]);
  const configuredActions = useMemo(
    () =>
      rulesValue.rules.flatMap((rule) =>
        actionsOf(rule).map((action) => ({
          ...action,
          ruleCode: rule.ruleCode,
          ruleName: rule.ruleName,
        })),
      ),
    [rulesValue.rules],
  );
  const decisionBindingCount = useMemo(
    () => rulesValue.rules.filter((rule) => Boolean(rule.decisionBinding?.decisionCode)).length,
    [rulesValue.rules],
  );
  const missingHandlerActions = useMemo(
    () =>
      configuredActions.filter((action) => {
        const option = actionOptions.find((candidate) => candidate.actionType === action.type);
        return option?.handlerAvailable === false;
      }),
    [actionOptions, configuredActions],
  );
  const executionRows = useMemo(() => actionExecutionRows(runResult), [runResult]);
  const failedExecutionActions = useMemo(
    () => executionRows.filter(isFailedExecutionAction),
    [executionRows],
  );
  const abnormalActionCount = missingHandlerActions.length + failedExecutionActions.length;
  const runExecutionStatus = executionRecord(runResult)?.overallStatus;
  const runSummary =
    runResult === null
      ? '待运行样例'
      : `${runStatus(runResult)} / ${executionStatus(runExecutionStatus)}`;
  const runDetail =
    runResult === null
      ? (samples[0]?.label ?? '暂无样例事实')
      : executionRows.length > 0
        ? `${executionRows.length} 个动作返回执行证据`
        : '本次没有需要执行的动作';
  const abnormalSummary =
    abnormalActionCount === 0 ? '无异常动作' : `${abnormalActionCount} 项需要处理`;
  const abnormalDetail =
    abnormalActionCount === 0
      ? '动作处理器和本次执行状态正常'
      : [
          missingHandlerActions.length > 0
            ? `${missingHandlerActions.length} 个动作当前不可用`
            : '',
          failedExecutionActions.length > 0
            ? `${failedExecutionActions.length} 个动作执行异常`
            : '',
        ]
          .filter(Boolean)
          .join('，');

  const draftJson = useMemo(
    () => ({
      phase,
      matchMode: rulesValue.matchMode,
      executionMode,
      failureStrategy,
      conflictStrategy,
      dedupStrategy,
      rules: buildRulesJson(rulesValue),
    }),
    [conflictStrategy, dedupStrategy, executionMode, failureStrategy, phase, rulesValue],
  );

  useEffect(() => {
    let cancelled = false;
    setCatalogError('');
    api
      .getActionCatalog()
      .then((catalog) => {
        if (!cancelled) setCatalogActions(catalog.actions ?? []);
      })
      .catch((e) => {
        if (!cancelled) {
          setCatalogActions([]);
          setCatalogError(e instanceof Error ? e.message : String(e));
        }
      });
    return () => {
      cancelled = true;
    };
  }, [api]);

  useEffect(() => {
    const policyCode = selectedPolicy?.policyCode;
    const fallbackPhase = enumOr(selectedPolicy?.phase, POLICY_PHASES, 'AFTER_COMMIT');
    const fallbackRules = defaultRules(selectedPolicy?.matchMode);
    let cancelled = false;

    setPhase(fallbackPhase);
    setExecutionMode('ORDERED');
    setFailureStrategy('FAIL_FAST');
    setConflictStrategy('REJECT_ON_CONFLICT');
    setDedupStrategy('BY_IDEMPOTENCY_KEY');
    setRulesValue(fallbackRules);
    setSelectedRuleCode(fallbackRules.rules[0]?.ruleCode ?? '');
    setDraftPid(selectedPolicy?.latestVersionPid ?? '');
    setPublishStatus(selectedPolicy?.status ?? 'UNSAVED');
    setError('');
    setVersionError('');
    setRunResult(null);

    if (!policyCode) {
      setVersionLoading(false);
      return () => {
        cancelled = true;
      };
    }

    setVersionLoading(true);
    api
      .listPolicyVersions(policyCode)
      .then((versions) => {
        if (cancelled) return;
        const version = latestVersion(versions, selectedPolicy?.latestVersionPid);
        if (!version) return;
        const hydratedRules = hydrateRules(
          version.rulesJson,
          version.matchMode ?? selectedPolicy?.matchMode,
        );
        setPhase(enumOr(version.phase, POLICY_PHASES, fallbackPhase));
        setExecutionMode(enumOr(version.executionMode, EXECUTION_MODES, 'ORDERED'));
        setFailureStrategy(enumOr(version.failureStrategy, FAILURE_STRATEGIES, 'FAIL_FAST'));
        setConflictStrategy(
          enumOr(version.conflictStrategy, CONFLICT_STRATEGIES, 'REJECT_ON_CONFLICT'),
        );
        setDedupStrategy(enumOr(version.dedupStrategy, DEDUP_STRATEGIES, 'BY_IDEMPOTENCY_KEY'));
        setRulesValue(hydratedRules);
        setSelectedRuleCode(hydratedRules.rules[0]?.ruleCode ?? '');
        setDraftPid(version.pid);
        setPublishStatus(version.status ?? selectedPolicy?.status ?? 'UNSAVED');
      })
      .catch((e) => {
        if (!cancelled) setVersionError(e instanceof Error ? e.message : String(e));
      })
      .finally(() => {
        if (!cancelled) setVersionLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [
    api,
    selectedPolicy?.latestVersionPid,
    selectedPolicy?.matchMode,
    selectedPolicy?.phase,
    selectedPolicy?.policyCode,
    selectedPolicy?.status,
  ]);

  const patchRuleActions = (actions: PolicyActionDraft[]) => {
    const rules = rulesValue.rules.slice();
    rules[currentRuleIdx] = { ...rules[currentRuleIdx], actions };
    setRulesValue({ ...rulesValue, rules });
  };

  const patchRuleDecisionBinding = (
    idx: number,
    decisionBinding: RuleConsumerBindingDraft['decisionBinding'],
  ) => {
    const rules = rulesValue.rules.slice();
    if (!rules[idx]) return;
    rules[idx] = { ...rules[idx], decisionBinding };
    setRulesValue({ ...rulesValue, rules });
  };

  const addAction = () => {
    patchRuleActions([
      ...currentActions,
      {
        type: actionOptions[0]?.actionType ?? 'NOTIFY',
        target: '',
        order: currentActions.length + 1,
        payloadJson: '{}',
        idempotencyKeyTemplate: DEFAULT_IDEMPOTENCY,
      },
    ]);
  };

  const updateAction = (idx: number, patch: Partial<PolicyActionDraft>) => {
    const actions = currentActions.slice();
    actions[idx] = { ...actions[idx], ...patch };
    patchRuleActions(actions);
  };

  const updateActionField = (idx: number, field: ActionSchemaField, value: string) => {
    const action = currentActions[idx];
    if (!action) return;
    try {
      const payload = parsePayload(action.payloadJson);
      const next = writeActionFieldValue(action.target, payload, field, value);
      updateAction(idx, { target: next.target ?? '', payloadJson: payloadToJson(next.payload) });
    } catch {
      // Keep the previous structured payload until the JSON field becomes valid again.
    }
  };

  const createDraft = async () => {
    if (!selectedPolicy?.policyCode) return;
    setError('');
    try {
      const result = await api.createPolicyDraftVersion(selectedPolicy.policyCode, {
        phase,
        matchMode: rulesValue.matchMode,
        executionMode,
        failureStrategy,
        conflictStrategy,
        dedupStrategy,
        rulesJson: buildRulesJson(rulesValue),
      });
      setDraftPid(result.pid);
      setPublishStatus(result.status ?? 'DRAFT');
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const validateDraft = async () => {
    if (!draftPid) return;
    const result = await api.validatePolicyVersion(draftPid);
    setPublishStatus(result.status ?? 'VALIDATED');
  };

  const publishDraft = async () => {
    if (!draftPid) return;
    const result = await api.publishPolicyVersion(draftPid);
    setPublishStatus(result.status ?? 'PUBLISHED');
  };

  const runPublishedPolicy = async () => {
    if (!selectedPolicy?.eventType || !selectedPolicy.targetType || !selectedPolicy.targetKey)
      return;
    const result = await api.runAndExecutePolicy({
      eventType: selectedPolicy.eventType,
      targetType: selectedPolicy.targetType,
      targetKey: selectedPolicy.targetKey,
      context: executionContextForSample(samples[0]),
    });
    setRunResult(result);
  };

  return (
    <div className="epd-workflow" data-testid="epd-workflow">
      <section className="epd-command-center" data-testid="epd-command-center">
        <div className="epd-command-main" data-testid="epd-strategy-summary">
          <div className="epd-panel-heading">
            <div>
              <span className="epd-eyebrow">策略链路摘要</span>
              <strong>
                {selectedPolicy?.policyName ?? selectedPolicy?.policyCode ?? '未选择策略'}
              </strong>
              <div className="epd-context-meta">
                <span>{selectedPolicy?.policyCode ?? '-'}</span>
                <span>{selectedPolicy?.eventType ?? '-'}</span>
                <span>
                  {selectedPolicy?.targetType ?? '-'} / {selectedPolicy?.targetKey ?? '-'}
                </span>
              </div>
            </div>
            <span className={`epd-status epd-status-${String(publishStatus).toLowerCase()}`}>
              {policyStatusLabel(publishStatus)}
            </span>
          </div>
          <div className="epd-command-metrics">
            <span>{rulesValue.rules.length} 条规则</span>
            <span>{configuredActions.length} 个动作</span>
            <span>{decisionBindingCount} 个决策引用</span>
            <span>{MATCH_MODE_LABELS[rulesValue.matchMode]}</span>
          </div>
        </div>

        <div className="epd-command-card" data-testid="epd-run-summary">
          <span>最近执行</span>
          <strong>{runSummary}</strong>
          <small>{runDetail}</small>
          <button type="button" onClick={() => setStep('test')}>
            测试运行
          </button>
        </div>

        <div className="epd-command-card" data-testid="epd-abnormal-actions">
          <span>异常动作</span>
          <strong>{abnormalSummary}</strong>
          <small>{abnormalDetail}</small>
          <button type="button" onClick={() => setStep('actions')}>
            检查动作
          </button>
        </div>
      </section>

      <nav className="epd-steps" role="tablist">
        {STEPS.map((s) => (
          <button
            key={s.key}
            type="button"
            role="tab"
            data-testid={`epd-step-${s.key}`}
            aria-selected={step === s.key}
            className={step === s.key ? 'is-active' : ''}
            onClick={() => setStep(s.key)}
          >
            {s.label}
          </button>
        ))}
      </nav>

      {step === 'trigger' && (
        <section className="epd-panel" data-testid="epd-trigger-panel">
          <div className="epd-panel-heading" data-testid="epd-trigger-context">
            <div>
              <span className="epd-eyebrow">事件策略</span>
              <strong>
                {selectedPolicy?.policyName ?? selectedPolicy?.policyCode ?? '未选择策略'}
              </strong>
              <div className="epd-context-meta">
                <span>{selectedPolicy?.policyCode ?? '-'}</span>
                <span>{selectedPolicy?.eventType ?? '-'}</span>
                <span>
                  {selectedPolicy?.targetType ?? '-'} / {selectedPolicy?.targetKey ?? '-'}
                </span>
              </div>
            </div>
            <span className={`epd-status epd-status-${String(publishStatus).toLowerCase()}`}>
              {policyStatusLabel(publishStatus)}
            </span>
          </div>
          {versionLoading && (
            <div className="epd-state" data-testid="epd-version-loading">
              正在加载版本...
            </div>
          )}
          {versionError && (
            <div className="epd-state is-error" data-testid="epd-version-error">
              {versionError}
            </div>
          )}
          <div className="epd-summary-grid">
            <div className="epd-summary-card">
              <span>策略编码</span>
              <strong>{selectedPolicy?.policyCode ?? '-'}</strong>
            </div>
            <div className="epd-summary-card">
              <span>触发事件</span>
              <strong>{selectedPolicy?.eventType ?? '-'}</strong>
            </div>
            <div className="epd-summary-card">
              <span>目标对象</span>
              <strong>
                {selectedPolicy?.targetType ?? '-'} / {selectedPolicy?.targetKey ?? '-'}
              </strong>
            </div>
            <div className="epd-summary-card">
              <span>匹配模式</span>
              <strong>{MATCH_MODE_LABELS[rulesValue.matchMode]}</strong>
            </div>
          </div>
          <div className="epd-field-row">
            <label htmlFor="epd-phase">执行阶段</label>
            <select
              id="epd-phase"
              value={phase}
              onChange={(e) => setPhase(e.target.value as PolicyPhase)}
            >
              {POLICY_PHASES.map((value) => enumOption(value, PHASE_LABELS))}
            </select>
          </div>
        </section>
      )}

      {step === 'rules' && (
        <section className="epd-panel">
          <div className="epd-panel-heading">
            <div>
              <span className="epd-eyebrow">条件编排</span>
              <strong>规则命中后才执行动作</strong>
            </div>
            <span className="epd-chip">{rulesValue.rules.length} 条规则</span>
          </div>
          <PolicyRulesEditor
            value={rulesValue}
            fields={fields}
            onChange={(next) => {
              setRulesValue(next);
              if (!next.rules.some((rule) => rule.ruleCode === selectedRuleCode)) {
                setSelectedRuleCode(next.rules[0]?.ruleCode ?? '');
              }
            }}
          />
          <div className="epd-rule-binding-list">
            {rulesValue.rules.map((rule, idx) => (
              <div
                className="epd-rule-binding-card"
                data-testid={`epd-rule-binding-${idx}`}
                key={rule.ruleCode}
              >
                <div className="epd-panel-heading">
                  <div>
                    <span className="epd-eyebrow">规则中心复用</span>
                    <strong>{rule.ruleName || rule.ruleCode}</strong>
                    <div className="epd-context-meta">
                      <span>{rule.decisionBinding?.decisionCode ?? '未绑定决策'}</span>
                      <span>
                        {rule.decisionBinding?.inputMappings
                          ?.map((mapping) => mapping.input)
                          .filter(Boolean)
                          .join(', ') || '未配置输入映射'}
                      </span>
                    </div>
                  </div>
                </div>
                <DecisionRuleBindingBlock
                  value={eventPolicyBindingValue(rule, selectedPolicy)}
                  onChange={(next) => patchRuleDecisionBinding(idx, next.decisionBinding)}
                  block={{
                    props: {
                      mode: 'decision',
                      consumerType: 'EVENT_POLICY',
                      consumerCode: selectedPolicy?.policyCode,
                      consumerNodeId: rule.ruleCode,
                      fields,
                      decisions: decisionOptions,
                      initialDecisionCode:
                        rule.decisionBinding?.decisionCode ?? decisionOptions[0]?.code,
                      initialContextJson: JSON.stringify(firstSampleContext, null, 2),
                      showImpactPreview: true,
                      showTestRunner: true,
                    },
                  }}
                />
              </div>
            ))}
          </div>
        </section>
      )}

      {step === 'actions' && (
        <section className="epd-panel" data-testid="epd-actions-panel">
          <div className="epd-panel-heading">
            <div>
              <span className="epd-eyebrow">动作编排</span>
              <strong>命中规则后的处理动作</strong>
            </div>
            <button type="button" data-testid="epd-add-action" onClick={addAction}>
              添加动作
            </button>
          </div>
          <div className="epd-field-row">
            <label htmlFor="epd-rule-select">当前规则</label>
            <select
              id="epd-rule-select"
              value={currentRule?.ruleCode ?? ''}
              onChange={(e) => setSelectedRuleCode(e.target.value)}
            >
              {rulesValue.rules.map((rule) => (
                <option key={rule.ruleCode} value={rule.ruleCode}>
                  {rule.ruleName || rule.ruleCode}
                </option>
              ))}
            </select>
          </div>
          {catalogError && (
            <div className="epd-state is-warning" data-testid="epd-action-catalog-error">
              动作目录暂不可用，已使用内置动作类型
            </div>
          )}
          <div className="epd-action-grid">
            {currentActions.length === 0 && (
              <div className="epd-empty">
                还没有动作。命中该规则后不会发送通知、启动流程或更新记录。
              </div>
            )}
            {currentActions.map((action, idx) => {
              const fields = editableActionFields(action, actionOptions);
              const availability = actionAvailabilityForType(action.type, actionOptions);
              const payload = (() => {
                try {
                  return parsePayload(action.payloadJson);
                } catch {
                  return {};
                }
              })();
              return (
                <div className="epd-action-card" key={idx} data-testid={`epd-action-${idx}`}>
                  <div className="epd-action-title">
                    <strong>{actionTypeLabel(action.type)}</strong>
                    {availability.unavailable && (
                      <span className="epd-action-availability-badge">不可用</span>
                    )}
                    <span>#{action.order}</span>
                  </div>
                  {availability.unavailable && (
                    <div
                      className="epd-action-availability"
                      data-testid={`epd-action-availability-${idx}`}
                    >
                      <div>{availability.reason}</div>
                      {availability.providerSummary && (
                        <div className="mt-1" data-testid={`epd-action-provider-${idx}`}>
                          {availability.providerSummary}
                        </div>
                      )}
                    </div>
                  )}
                  <div className="epd-field-row">
                    <label htmlFor={`epd-action-type-${idx}`}>动作类型</label>
                    <select
                      id={`epd-action-type-${idx}`}
                      aria-label={`action-type-${idx}`}
                      value={action.type}
                      onChange={(e) => updateAction(idx, { type: e.target.value })}
                    >
                      {actionOptions.map((option) => (
                        <option key={option.actionType} value={option.actionType}>
                          {actionOptionLabel(option)}
                        </option>
                      ))}
                    </select>
                  </div>
                  <div className="epd-inline-fields">
                    <div className="epd-field-row">
                      <label htmlFor={`epd-action-order-${idx}`}>顺序</label>
                      <input
                        id={`epd-action-order-${idx}`}
                        aria-label={`action-order-${idx}`}
                        type="number"
                        value={action.order}
                        onChange={(e) => updateAction(idx, { order: Number(e.target.value) })}
                      />
                    </div>
                    <div className="epd-payload-summary">
                      <span>负载摘要</span>
                      <strong>{payloadTitle(action.payloadJson)}</strong>
                    </div>
                  </div>
                  <div className="epd-action-schema" data-testid={`epd-action-schema-${idx}`}>
                    {fields.map((field) => {
                      const inputKind = actionFieldInputKind(field);
                      const ariaLabel =
                        field.path === 'target'
                          ? `action-target-${idx}`
                          : `action-field-${idx}-${field.path}`;
                      const fieldValue = readActionFieldValue(action.target, payload, field);
                      return (
                        <label key={field.path} className="epd-field-row">
                          <span>
                            {field.label}
                            {field.required && <em>必填</em>}
                          </span>
                          {inputKind === 'textarea' || inputKind === 'json' ? (
                            <textarea
                              aria-label={ariaLabel}
                              value={fieldValue}
                              onChange={(e) => updateActionField(idx, field, e.target.value)}
                            />
                          ) : (
                            <input
                              aria-label={ariaLabel}
                              value={fieldValue}
                              onChange={(e) => updateActionField(idx, field, e.target.value)}
                              placeholder={
                                field.path === 'target' ? '例如 ROLE:wd_manager' : undefined
                              }
                            />
                          )}
                        </label>
                      );
                    })}
                  </div>
                  <details className="epd-advanced">
                    <summary>高级负载</summary>
                    <textarea
                      aria-label={`action-payload-${idx}`}
                      value={action.payloadJson}
                      onChange={(e) => updateAction(idx, { payloadJson: e.target.value })}
                    />
                  </details>
                </div>
              );
            })}
          </div>
        </section>
      )}

      {step === 'test' && (
        <section className="epd-panel" data-testid="epd-test-panel">
          <div className="epd-panel-heading">
            <div>
              <span className="epd-eyebrow">测试运行</span>
              <strong>用样例事实验证策略命中和动作执行</strong>
            </div>
            <button type="button" data-testid="epd-run-published" onClick={runPublishedPolicy}>
              运行并执行动作
            </button>
          </div>
          {currentRule && samples.length > 0 && (
            <ConditionTestRunPanel
              condition={currentRule.condition}
              samples={samples}
              fields={fields}
              emptyPreviewLabel="当前版本以已发布策略条件为准"
            />
          )}
          {samples.length === 0 && (
            <div className="epd-empty">
              暂无样例事实，可直接运行已发布策略或在调用方传入测试样例。
            </div>
          )}
          {runResult !== null && (
            <div className="epd-result" data-testid="epd-run-result">
              <span>运行结果</span>
              <strong>{runStatus(runResult)}</strong>
              {runCorrelationId(runResult) ? (
                <span className="epd-correlation" data-testid="epd-correlation-id">
                  Correlation {runCorrelationId(runResult)}
                </span>
              ) : null}
              {eventPolicyTraceHref(runResult, selectedPolicy) ? (
                <a
                  data-testid="epd-open-trace"
                  href={eventPolicyTraceHref(runResult, selectedPolicy)}
                >
                  打开统一 Trace
                </a>
              ) : null}
            </div>
          )}
          {runResult !== null && executionRecord(runResult) && (
            <div className="epd-action-execution" data-testid="epd-action-execution-results">
              <div className="epd-result">
                <span>动作执行</span>
                <strong>{executionStatus(executionRecord(runResult)?.overallStatus)}</strong>
              </div>
              <div className="epd-action-grid">
                {actionExecutionRows(runResult).length === 0 ? (
                  <div className="epd-empty">本次没有需要执行的动作。</div>
                ) : (
                  actionExecutionRows(runResult).map((action, idx) => {
                    const payloadRows = resultPayloadRows(action);
                    return (
                      <div
                        className="epd-action-card"
                        key={idx}
                        data-testid={`epd-action-execution-${idx}`}
                      >
                        <div className="epd-action-title">
                          <strong>{actionTypeLabel(stringOr(action.type, '-'))}</strong>
                          <span>{executionStatus(action.status)}</span>
                        </div>
                        <div className="epd-context-meta">
                          <span>{String(action.ruleCode ?? '-')}</span>
                          <span title={idempotencyTitle(action.idempotencyKey)}>
                            {idempotencyEvidence(action.idempotencyKey)}
                          </span>
                        </div>
                        {action.error ? (
                          <div className="epd-state is-error">{String(action.error)}</div>
                        ) : null}
                        {payloadRows.length > 0 ? (
                          <dl
                            className="epd-action-result-payload"
                            data-testid={`epd-action-result-payload-${idx}`}
                          >
                            {payloadRows.map((row) => (
                              <div key={row.key}>
                                <dt>{row.label}</dt>
                                <dd>{row.value}</dd>
                              </div>
                            ))}
                          </dl>
                        ) : null}
                      </div>
                    );
                  })
                )}
              </div>
            </div>
          )}
        </section>
      )}

      {step === 'publish' && (
        <section className="epd-panel" data-testid="epd-publish-panel">
          <div className="epd-panel-heading">
            <div>
              <span className="epd-eyebrow">发布治理</span>
              <strong>保存、校验并发布策略版本</strong>
            </div>
            <div className="epd-publish-actions">
              <button type="button" data-testid="epd-save-draft" onClick={createDraft}>
                保存草稿
              </button>
              <button
                type="button"
                data-testid="epd-validate-version"
                disabled={!draftPid}
                onClick={validateDraft}
              >
                校验版本
              </button>
              <button
                type="button"
                data-testid="epd-publish-version"
                disabled={!draftPid}
                onClick={publishDraft}
              >
                发布版本
              </button>
            </div>
          </div>
          <div className="epd-summary-grid">
            <div className="epd-field-row">
              <label htmlFor="epd-execution-mode">执行模式</label>
              <select
                id="epd-execution-mode"
                value={executionMode}
                onChange={(e) => setExecutionMode(e.target.value as ExecutionMode)}
              >
                {EXECUTION_MODES.map((value) => enumOption(value, EXECUTION_MODE_LABELS))}
              </select>
            </div>
            <div className="epd-field-row">
              <label htmlFor="epd-failure">失败策略</label>
              <select
                id="epd-failure"
                value={failureStrategy}
                onChange={(e) => setFailureStrategy(e.target.value as FailureStrategy)}
              >
                {FAILURE_STRATEGIES.map((value) => enumOption(value, FAILURE_STRATEGY_LABELS))}
              </select>
            </div>
            <div className="epd-field-row">
              <label htmlFor="epd-conflict">冲突策略</label>
              <select
                id="epd-conflict"
                value={conflictStrategy}
                onChange={(e) => setConflictStrategy(e.target.value as ConflictStrategy)}
              >
                {CONFLICT_STRATEGIES.map((value) => enumOption(value, CONFLICT_STRATEGY_LABELS))}
              </select>
            </div>
            <div className="epd-field-row">
              <label htmlFor="epd-dedup">去重策略</label>
              <select
                id="epd-dedup"
                value={dedupStrategy}
                onChange={(e) => setDedupStrategy(e.target.value as DedupStrategy)}
              >
                {DEDUP_STRATEGIES.map((value) => enumOption(value, DEDUP_STRATEGY_LABELS))}
              </select>
            </div>
          </div>
          <div className="epd-result">
            <span>当前状态</span>
            <strong data-testid="epd-publish-status">{policyStatusLabel(publishStatus)}</strong>
          </div>
          {error && (
            <div className="epd-state is-error" data-testid="epd-error">
              {error}
            </div>
          )}
        </section>
      )}

      {step === 'history' && (
        <section className="epd-panel" data-testid="epd-history-panel">
          <div className="epd-panel-heading">
            <div>
              <span className="epd-eyebrow">版本历史</span>
              <strong>当前策略版本</strong>
            </div>
          </div>
          <div className="epd-summary-grid">
            <div className="epd-summary-card">
              <span>当前状态</span>
              <strong>{policyStatusLabel(selectedPolicy?.status)}</strong>
            </div>
            <div className="epd-summary-card">
              <span>当前版本</span>
              <strong>v{selectedPolicy?.version ?? '-'}</strong>
            </div>
            <div className="epd-summary-card">
              <span>草稿状态</span>
              <strong>{draftPid ? '已创建' : '未创建'}</strong>
            </div>
          </div>
        </section>
      )}

      <pre hidden data-testid="epd-draft-json">
        {JSON.stringify(draftJson)}
      </pre>
    </div>
  );
}

export default EventPolicyDesignerWorkflow;
