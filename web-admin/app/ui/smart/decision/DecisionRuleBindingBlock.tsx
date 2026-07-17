import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ConditionBuilder, type FieldOption } from '~/shared/decision/ui/ConditionBuilder';
import { group, type GroupNode } from '~/shared/decision/ast/conditionAst';
import { getApiService } from '~/shared/services/ApiService';
import {
  createDecisionApi,
  type DecisionFactCatalog,
  type DecisionImpact,
  type DecisionModelField,
  type DecisionResult,
  type HttpClient,
  type ScopedContext,
} from '~/shared/decision/api/decisionApi';
import {
  factCatalogToFieldOptions,
  modelFieldsToFieldOptions,
} from '~/shared/decision/ui/factCatalogAdapter';
import {
  normalizeDecisionOutputFields,
  type DecisionOutputSchemaField,
  type DecisionOutputSchemaSource,
} from '~/shared/decision/ui/decisionOutputSchema';

export type DecisionVersionPolicy =
  | 'LATEST_PUBLISHED'
  | 'FIXED_VERSION'
  | 'VERSION_TAG'
  | 'ROLLOUT';

export interface DecisionOption {
  code: string;
  name?: string;
  outputs?: DecisionOutputSchemaSource[];
  outputSchemaJson?: unknown;
}

type RuleBindingWorkspacePanel = 'condition' | 'decision' | 'impact' | 'test';

interface RuleBindingWorkspaceTab {
  key: RuleBindingWorkspacePanel;
  label: string;
  meta: string;
}

export interface InputMapping {
  input: string;
  scope: FieldOption['scope'];
  path: string;
}

export type OutputTargetKind =
  | 'ACTION_PARAM'
  | 'FIELD'
  | 'PROCESS_VARIABLE'
  | 'SLA_FIELD'
  | 'PERMISSION_CONTEXT';

export interface OutputMapping {
  output: string;
  targetKind: OutputTargetKind;
  targetPath: string;
}

export interface RuleValueSourceDraft {
  kind: 'FIELD' | 'LITERAL';
  scope?: FieldOption['scope'];
  path?: string;
  value?: unknown;
}

export interface RuleMappingTargetDraft {
  kind: OutputTargetKind;
  path: string;
}

export interface DecisionBindingDraft {
  decisionCode: string;
  versionPolicy: DecisionVersionPolicy;
  inputMappings: InputMapping[];
  outputMappings: OutputMapping[];
  fallbackMode: 'FAIL_CLOSED' | 'FAIL_OPEN' | 'DEFAULT_VALUE';
}

export interface RuleConsumerBindingDraft {
  consumerType?: string;
  consumerCode?: string;
  consumerNodeId?: string;
  bindingKind: 'CONDITION' | 'DECISION_REF';
  conditionSpec?: {
    root: GroupNode;
    decisionBindings: unknown[];
  };
  decisionBinding?: {
    decisionCode: string;
    versionPolicy: DecisionVersionPolicy;
    inputMappings: Array<{
      input: string;
      source: RuleValueSourceDraft;
    }>;
    outputMappings: Array<{
      output: string;
      target: RuleMappingTargetDraft;
    }>;
    fallbackPolicy: {
      mode: DecisionBindingDraft['fallbackMode'];
    };
    traceMode: 'SAMPLED' | 'ALWAYS' | 'NONE';
    enabled: boolean;
  };
  enabled: boolean;
}

interface RuleBindingRuntime {
  getFieldValue?: (fieldCode: string) => unknown;
  updateField?: (fieldCode: string, value: unknown) => void;
  getContext?: () => {
    record?: Record<string, unknown>;
    row?: Record<string, unknown>;
    data?: Record<string, unknown>;
    form?: Record<string, unknown>;
    pageContext?: Record<string, unknown>;
    $page?: Record<string, unknown>;
  };
}

export interface RuleBindingDecisionApi {
  getDecisionImpact: (decisionCode: string) => Promise<DecisionImpact>;
  evaluate: (request: {
    decisionCode: string;
    binding?: 'LATEST' | 'FIXED_VERSION' | 'VERSION_TAG' | 'ROLLOUT';
    callerType?: string;
    callerRef?: string;
    routingKey?: string;
    context: ScopedContext;
  }) => Promise<DecisionResult>;
  getFactCatalog?: (modelCode?: string) => Promise<DecisionFactCatalog>;
  getModelFields?: () => Promise<DecisionModelField[]>;
}

type JsonBindingEnvelope = {
  type?: string;
  value?: string;
  null?: boolean;
};

type BindingValueInput = RuleConsumerBindingDraft | string | JsonBindingEnvelope;

interface DecisionRuleBindingBlockProps {
  block?: {
    props?: {
      mode?: 'condition' | 'decision' | 'combined';
      valueField?: string;
      value?: BindingValueInput;
      initialValue?: BindingValueInput;
      consumerType?: string;
      consumerCode?: string;
      consumerCodeField?: string;
      consumerNodeId?: string;
      readOnly?: boolean;
      variant?: 'editor' | 'summary';
      showImpactPreview?: boolean;
      showTestRunner?: boolean;
      initialContextJson?: string;
      fields?: FieldOption[];
      fieldCatalogMode?: 'disabled' | 'fallback' | 'merge';
      fieldCatalogModelCode?: string;
      fieldCatalogModelCodeField?: string;
      decisions?: DecisionOption[];
      initialDecisionCode?: string;
      initialVersionPolicy?: DecisionVersionPolicy;
    };
  };
  runtime?: RuleBindingRuntime;
  value?: BindingValueInput;
  onChange?: (next: RuleConsumerBindingDraft) => void;
  api?: RuleBindingDecisionApi;
}

const DEFAULT_FIELDS: FieldOption[] = [
  { scope: 'record', path: 'data.amount', label: '金额', dataType: 'decimal' },
  {
    scope: 'record',
    path: 'data.priority',
    label: '优先级',
    dataType: 'enum',
    options: ['HIGH', 'NORMAL', 'LOW'],
  },
  { scope: 'actor', path: 'departmentId', label: '用户部门', dataType: 'department' },
];

const DEFAULT_DECISIONS: DecisionOption[] = [
  { code: 'approval_routing', name: '审批路由' },
  { code: 'sla_deadline', name: 'SLA 截止时间' },
  {
    code: 'complaint_sla_deadline',
    name: '请假审批 SLA 截止时间',
    outputs: [
      { id: 'deadlineMinutes', label: '截止分钟', dataType: 'integer' },
      { id: 'warningBeforeMinutes', label: '提前提醒分钟', dataType: 'integer' },
      { id: 'escalationLevel', label: '升级等级', dataType: 'string' },
    ],
  },
];

const DECISION_NAME_OVERRIDES: Record<string, string> = {
  complaint_sla_deadline: '请假审批 SLA 截止时间',
  approval_routing: '请假审批分派',
  leave_request_automation: '请假申请自动化策略',
};

const STALE_DECISION_NAMES = new Set(['投诉 SLA 截止时间', '审批路由', '']);

function mergeDecisionOptions(configured?: DecisionOption[]): DecisionOption[] {
  if (!configured || configured.length === 0) return DEFAULT_DECISIONS;
  const byCode = new Map(DEFAULT_DECISIONS.map((decision) => [decision.code, decision]));
  configured.forEach((decision) => {
    const fallback = byCode.get(decision.code);
    byCode.set(decision.code, {
      ...fallback,
      ...decision,
      name: decision.name || fallback?.name,
      outputs:
        decision.outputs && decision.outputs.length > 0 ? decision.outputs : fallback?.outputs,
      outputSchemaJson: decision.outputSchemaJson ?? fallback?.outputSchemaJson,
    });
  });
  return Array.from(byCode.values());
}

const VERSION_POLICIES: DecisionVersionPolicy[] = [
  'LATEST_PUBLISHED',
  'FIXED_VERSION',
  'VERSION_TAG',
  'ROLLOUT',
];

const OUTPUT_TARGET_KINDS: OutputTargetKind[] = [
  'ACTION_PARAM',
  'FIELD',
  'PROCESS_VARIABLE',
  'SLA_FIELD',
  'PERMISSION_CONTEXT',
];

const VERSION_POLICY_LABELS: Record<DecisionVersionPolicy, string> = {
  LATEST_PUBLISHED: '最新已发布',
  FIXED_VERSION: '固定版本',
  VERSION_TAG: '版本标签',
  ROLLOUT: '灰度发布',
};

const FALLBACK_MODE_LABELS: Record<DecisionBindingDraft['fallbackMode'], string> = {
  FAIL_CLOSED: '异常时阻断',
  FAIL_OPEN: '异常时放行',
  DEFAULT_VALUE: '使用默认值',
};

const OUTPUT_TARGET_KIND_LABELS: Record<OutputTargetKind, string> = {
  ACTION_PARAM: '动作参数',
  FIELD: '业务字段',
  PROCESS_VARIABLE: '流程变量',
  SLA_FIELD: 'SLA 字段',
  PERMISSION_CONTEXT: '权限上下文',
};

const RESULT_STATUS_LABELS: Record<string, string> = {
  MATCHED: '已命中',
  NOT_MATCHED: '未命中',
  ERROR: '执行异常',
  SKIPPED: '已跳过',
  UNKNOWN: '未知状态',
};

const FIELD_SCOPE_LABELS: Record<FieldOption['scope'], string> = {
  meta: '元数据',
  event: '事件',
  record: '业务记录',
  before: '变更前',
  after: '变更后',
  process: '流程',
  task: '任务',
  sla: 'SLA',
  actor: '操作者',
  tenant: '租户',
  time: '时间',
  env: '环境',
};

function fieldKey(field: Pick<FieldOption, 'scope' | 'path'>): string {
  return `${field.scope}:${field.path}`;
}

function fieldGroupLabel(field: FieldOption): string {
  return field.modelName || FIELD_SCOPE_LABELS[field.scope] || field.scope;
}

function fieldSearchText(field: FieldOption): string {
  return [field.label, field.path, field.scope, field.modelCode, field.modelName, field.dataType]
    .filter(Boolean)
    .join(' ')
    .toLowerCase();
}

function isVisibleRuleField(field: FieldOption): boolean {
  return field.visible !== false;
}

function ruleInputDisabledReason(field: FieldOption): string | undefined {
  if (field.masked === true) return '字段已脱敏';
  return undefined;
}

function ruleInputOptionNote(field: FieldOption): string | undefined {
  return ruleInputDisabledReason(field) ?? (field.editable === false ? '只读字段' : undefined);
}

function isRuleInputSelectable(field: FieldOption): boolean {
  return isVisibleRuleField(field) && !ruleInputDisabledReason(field);
}

function filterFieldOptions(fields: FieldOption[], query: string): FieldOption[] {
  const normalized = query.trim().toLowerCase();
  if (!normalized) return fields;
  return fields.filter((field) => fieldSearchText(field).includes(normalized));
}

function groupFieldOptions(fields: FieldOption[]): Array<{ label: string; fields: FieldOption[] }> {
  const grouped = new Map<string, FieldOption[]>();
  fields.forEach((field) => {
    const label = fieldGroupLabel(field);
    grouped.set(label, [...(grouped.get(label) ?? []), field]);
  });
  return Array.from(grouped, ([label, groupFields]) => ({ label, fields: groupFields }));
}

function keepSelectedField(
  fields: FieldOption[],
  selectedField: FieldOption | undefined,
): FieldOption[] {
  const seen = new Set<string>();
  return [selectedField, ...fields]
    .filter((field): field is FieldOption => Boolean(field))
    .filter((field) => {
      const key = fieldKey(field);
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    });
}

function mergeFieldOptions(primary: FieldOption[], fallback: FieldOption[]): FieldOption[] {
  const seen = new Set<string>();
  return [...primary, ...fallback].filter((field) => {
    const key = fieldKey(field);
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function normalizeModelCode(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value.trim() : undefined;
}

function readRuntimeContextField(
  context: ReturnType<NonNullable<RuleBindingRuntime['getContext']>> | undefined,
  fieldCode: string,
): unknown {
  if (!context || !fieldCode) return undefined;
  const sources = [
    context.record,
    context.row,
    context.data,
    context.form,
    context.$page,
    context.pageContext,
  ].filter((source): source is Record<string, unknown> => Boolean(source));
  const camelField = fieldCode.replace(/_([a-z])/g, (_match, letter: string) =>
    letter.toUpperCase(),
  );
  const snakeField = fieldCode.replace(/[A-Z]/g, (letter) => `_${letter.toLowerCase()}`);
  for (const source of sources) {
    if (Object.prototype.hasOwnProperty.call(source, fieldCode)) return source[fieldCode];
    if (Object.prototype.hasOwnProperty.call(source, camelField)) return source[camelField];
    if (Object.prototype.hasOwnProperty.call(source, snakeField)) return source[snakeField];
  }
  return undefined;
}

function normalizeNonEmptyString(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value.trim() : undefined;
}

function resolveFieldCatalogModelCode(
  props: NonNullable<DecisionRuleBindingBlockProps['block']>['props'],
  runtime?: RuleBindingRuntime,
): string | undefined {
  const direct = normalizeModelCode(props?.fieldCatalogModelCode);
  if (direct) return direct;
  const field = normalizeModelCode(props?.fieldCatalogModelCodeField);
  if (!field) return undefined;
  return (
    normalizeModelCode(runtime?.getFieldValue?.(field)) ??
    normalizeModelCode(readRuntimeContextField(runtime?.getContext?.(), field))
  );
}

function resolveConsumerCode(
  props: NonNullable<DecisionRuleBindingBlockProps['block']>['props'],
  runtime: RuleBindingRuntime | undefined,
  initialRuleBinding?: RuleConsumerBindingDraft | null,
): string | undefined {
  const direct = normalizeNonEmptyString(props?.consumerCode);
  if (direct) return direct;
  const field = normalizeNonEmptyString(props?.consumerCodeField);
  if (field) {
    return (
      normalizeNonEmptyString(runtime?.getFieldValue?.(field)) ??
      normalizeNonEmptyString(readRuntimeContextField(runtime?.getContext?.(), field)) ??
      normalizeNonEmptyString(initialRuleBinding?.consumerCode)
    );
  }
  return normalizeNonEmptyString(initialRuleBinding?.consumerCode);
}

function fieldOptionMatchesModel(field: FieldOption, modelCode?: string): boolean {
  if (!modelCode) return true;
  if (field.scope !== 'record') return true;
  return field.modelCode === modelCode || field.modelName === modelCode;
}

function defaultCondition(): GroupNode {
  return group('AND', []);
}

function defaultDecisionApi(): RuleBindingDecisionApi {
  const service = getApiService();
  const http: HttpClient = {
    get: <T,>(endpoint: string, params?: Record<string, unknown>) =>
      service.get<T>(endpoint, params),
    post: <T,>(endpoint: string, body?: unknown) => service.post<T>(endpoint, body),
    delete: <T,>(endpoint: string) => service.delete<T>(endpoint),
  };
  return createDecisionApi(http);
}

function parseBindingValue(raw: unknown): RuleConsumerBindingDraft | undefined {
  if (!raw) return undefined;
  if (typeof raw === 'string') {
    try {
      const parsed = JSON.parse(raw);
      return parsed && typeof parsed === 'object'
        ? (parsed as RuleConsumerBindingDraft)
        : undefined;
    } catch {
      return undefined;
    }
  }
  if (
    raw &&
    typeof raw === 'object' &&
    !Array.isArray(raw) &&
    typeof (raw as { value?: unknown }).value === 'string' &&
    ['json', 'jsonb'].includes(String((raw as { type?: unknown }).type ?? '').toLowerCase())
  ) {
    return parseBindingValue((raw as { value: string }).value);
  }
  return typeof raw === 'object' ? (raw as RuleConsumerBindingDraft) : undefined;
}

function bindingValueFingerprint(raw: unknown): string {
  if (raw === undefined || raw === null || raw === '') return '';
  if (typeof raw === 'string') return raw;
  try {
    return JSON.stringify(raw);
  } catch {
    return String(raw);
  }
}

function mappingFromSource(input: string, source?: RuleValueSourceDraft): InputMapping | null {
  if (!source || source.kind !== 'FIELD' || !source.scope || !source.path) {
    return null;
  }
  return {
    input,
    scope: source.scope,
    path: source.path,
  };
}

function mappingFromTarget(output: string, target?: RuleMappingTargetDraft): OutputMapping | null {
  if (!output || !target?.kind || !target.path) {
    return null;
  }
  return {
    output,
    targetKind: target.kind,
    targetPath: target.path,
  };
}

function mappedFieldOption(mapping: InputMapping): FieldOption {
  return {
    scope: mapping.scope,
    path: mapping.path,
    label: `${mapping.scope}.${mapping.path}`,
    dataType: 'object',
  };
}

function buildInitialBinding(
  decisions: DecisionOption[],
  initialDecisionCode?: string,
  initialVersionPolicy?: DecisionVersionPolicy,
  initialValue?: RuleConsumerBindingDraft,
): DecisionBindingDraft {
  const decisionBinding = initialValue?.decisionBinding;
  return {
    decisionCode: decisionBinding?.decisionCode || initialDecisionCode || decisions[0]?.code || '',
    versionPolicy: decisionBinding?.versionPolicy || initialVersionPolicy || 'LATEST_PUBLISHED',
    inputMappings:
      decisionBinding?.inputMappings
        ?.map((mapping) => mappingFromSource(mapping.input, mapping.source))
        .filter((mapping): mapping is InputMapping => Boolean(mapping)) ?? [],
    outputMappings:
      decisionBinding?.outputMappings
        ?.map((mapping) => mappingFromTarget(mapping.output, mapping.target))
        .filter((mapping): mapping is OutputMapping => Boolean(mapping)) ?? [],
    fallbackMode: decisionBinding?.fallbackPolicy?.mode || 'FAIL_CLOSED',
  };
}

function buildRuleConsumerBinding(
  condition: GroupNode,
  binding: DecisionBindingDraft,
  options: {
    showCondition: boolean;
    showDecision: boolean;
    consumerType?: string;
    consumerCode?: string;
    consumerNodeId?: string;
  },
): RuleConsumerBindingDraft {
  return {
    consumerType: options.consumerType,
    consumerCode: options.consumerCode,
    consumerNodeId: options.consumerNodeId,
    bindingKind: options.showDecision ? 'DECISION_REF' : 'CONDITION',
    conditionSpec: options.showCondition
      ? {
          root: condition,
          decisionBindings: [],
        }
      : undefined,
    decisionBinding: options.showDecision
      ? {
          decisionCode: binding.decisionCode,
          versionPolicy: binding.versionPolicy,
          inputMappings: binding.inputMappings.map((mapping) => ({
            input: mapping.input,
            source: { kind: 'FIELD', scope: mapping.scope, path: mapping.path },
          })),
          outputMappings: binding.outputMappings.map((mapping) => ({
            output: mapping.output,
            target: { kind: mapping.targetKind, path: mapping.targetPath },
          })),
          fallbackPolicy: { mode: binding.fallbackMode },
          traceMode: 'SAMPLED',
          enabled: true,
        }
      : undefined,
    enabled: true,
  };
}

function errorMessage(error: unknown): string {
  if (error instanceof Error && error.message) return error.message;
  return '请求失败';
}

function versionPolicyToEvaluateBinding(
  policy: DecisionVersionPolicy,
): 'LATEST' | 'FIXED_VERSION' | 'VERSION_TAG' | 'ROLLOUT' {
  if (policy === 'LATEST_PUBLISHED') return 'LATEST';
  return policy;
}

function recordDataFromRuntime(runtime?: RuleBindingRuntime): Record<string, unknown> {
  const context = runtime?.getContext?.();
  const record = context?.record ?? context?.row ?? context?.data ?? {};
  if (record.data && typeof record.data === 'object' && !Array.isArray(record.data)) {
    return record.data as Record<string, unknown>;
  }
  return record;
}

function buildInitialContextJson(
  runtime?: RuleBindingRuntime,
  initialContextJson?: string,
): string {
  if (initialContextJson && initialContextJson.trim()) {
    return initialContextJson;
  }
  return JSON.stringify({ record: { data: recordDataFromRuntime(runtime) } }, null, 2);
}

function parseContextJson(raw: string): ScopedContext {
  const parsed = JSON.parse(raw);
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error('Context must be a JSON object');
  }
  return parsed as ScopedContext;
}

function parseContextObject(raw: string): ScopedContext {
  try {
    return parseContextJson(raw);
  } catch {
    return {};
  }
}

function cloneContext(context: ScopedContext): ScopedContext {
  return JSON.parse(JSON.stringify(context)) as ScopedContext;
}

function readPath(root: unknown, path: string): unknown {
  if (!root || typeof root !== 'object' || Array.isArray(root) || !path) return undefined;
  return path.split('.').reduce<unknown>((current, part) => {
    if (!current || typeof current !== 'object' || Array.isArray(current)) return undefined;
    return (current as Record<string, unknown>)[part];
  }, root);
}

function ensureObject(parent: Record<string, unknown>, key: string): Record<string, unknown> {
  const existing = parent[key];
  if (existing && typeof existing === 'object' && !Array.isArray(existing)) {
    return existing as Record<string, unknown>;
  }
  const next: Record<string, unknown> = {};
  parent[key] = next;
  return next;
}

function writePath(root: Record<string, unknown>, path: string, value: unknown): void {
  const parts = path.split('.').filter(Boolean);
  if (parts.length === 0) return;
  let current = root;
  for (const part of parts.slice(0, -1)) {
    current = ensureObject(current, part);
  }
  current[parts[parts.length - 1]] = value;
}

function coerceContextFieldValue(field: FieldOption, rawValue: string): unknown {
  const value = rawValue.trim();
  if (field.dataType === 'integer' || field.dataType === 'decimal') {
    if (!value) return '';
    const numberValue = Number(value);
    return Number.isFinite(numberValue) ? numberValue : rawValue;
  }
  if (field.dataType === 'boolean') {
    if (value.toLowerCase() === 'true') return true;
    if (value.toLowerCase() === 'false') return false;
  }
  return rawValue;
}

function formatContextFieldValue(value: unknown): string {
  if (value === undefined || value === null) return '';
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}

function contextFieldInputLabel(field: FieldOption): string {
  return `test-context-field-${field.scope}-${field.path.replace(/[^a-zA-Z0-9_-]/g, '-')}`;
}

function contextFieldSourceTestId(field: FieldOption): string {
  return `test-context-field-source-${field.scope}-${field.path.replace(/[^a-zA-Z0-9_-]/g, '-')}`;
}

function fieldSourceSummary(field: FieldOption): string | null {
  const sourceType = field.sourceType?.trim();
  const sourceRef = field.sourceRef?.trim();
  if (!sourceType && !sourceRef) return null;
  const sourceLabel = sourceType && sourceType !== 'physical' ? sourceType : null;
  return [sourceLabel, sourceRef].filter(Boolean).join(' · ');
}

function readContextFieldValue(context: ScopedContext, field: FieldOption): unknown {
  const scoped = (context as Record<string, unknown>)[field.scope];
  return readPath(scoped, field.path);
}

function updateContextFieldJson(contextJson: string, field: FieldOption, rawValue: string): string {
  const next = cloneContext(parseContextObject(contextJson));
  const scoped = ensureObject(next as Record<string, unknown>, field.scope);
  writePath(scoped, field.path, coerceContextFieldValue(field, rawValue));
  return JSON.stringify(next, null, 2);
}

function writeMappedDecisionInput(
  context: ScopedContext,
  mapping: InputMapping,
  value: unknown,
): void {
  const root = context as Record<string, unknown>;
  const scope = ensureObject(root, mapping.scope);
  if (mapping.scope === 'record') {
    ensureObject(scope, 'data')[mapping.input] = value;
    return;
  }
  scope[mapping.input] = value;
}

function applyInputMappings(context: ScopedContext, mappings: InputMapping[]): ScopedContext {
  if (mappings.length === 0) return context;
  const next = cloneContext(context);
  for (const mapping of mappings) {
    const scopeValue = (next as Record<string, unknown>)[mapping.scope];
    const value = readPath(scopeValue, mapping.path);
    if (value !== undefined) {
      writeMappedDecisionInput(next, mapping, value);
    }
  }
  return next;
}

function impactCount(impact: DecisionImpact | null): number {
  return (impact?.incoming?.length ?? 0) + (impact?.outgoing?.length ?? 0);
}

function getDecisionName(decisions: DecisionOption[], decisionCode: string): string {
  const decision = decisions.find((candidate) => candidate.code === decisionCode);
  return decisionDisplayName(decisionCode, decision?.name);
}

function getFieldDisplayName(
  fields: FieldOption[],
  scope: FieldOption['scope'],
  path: string,
): string {
  const field = fields.find((candidate) => candidate.scope === scope && candidate.path === path);
  return field?.label || `${FIELD_SCOPE_LABELS[scope] || scope}字段`;
}

function getFieldContextLabel(
  fields: FieldOption[],
  scope: FieldOption['scope'],
  path: string,
): string {
  const field = fields.find((candidate) => candidate.scope === scope && candidate.path === path);
  return field?.modelName || FIELD_SCOPE_LABELS[scope] || scope;
}

function bindingPreviewPayload(binding: DecisionBindingDraft) {
  return {
    bindingKind: 'DECISION_REF',
    decisionBinding: {
      decisionCode: binding.decisionCode,
      versionPolicy: binding.versionPolicy,
      inputMappings: binding.inputMappings.map((mapping) => ({
        input: mapping.input,
        source: { kind: 'FIELD', scope: mapping.scope, path: mapping.path },
      })),
      outputMappings: binding.outputMappings.map((mapping) => ({
        output: mapping.output,
        target: { kind: mapping.targetKind, path: mapping.targetPath },
      })),
      fallbackPolicy: { mode: binding.fallbackMode },
    },
  };
}

function decisionDisplayName(decisionCode: string, name?: string): string {
  const trimmedName = name?.trim() ?? '';
  if (trimmedName && !STALE_DECISION_NAMES.has(trimmedName)) return trimmedName;
  return DECISION_NAME_OVERRIDES[decisionCode] || trimmedName || decisionCode || '未选择决策';
}

function formatInputMapping(mapping: InputMapping, fields: FieldOption[]): string {
  return `${getFieldContextLabel(fields, mapping.scope, mapping.path)} · ${getFieldDisplayName(fields, mapping.scope, mapping.path)}`;
}

function formatOutputMapping(mapping: OutputMapping): string {
  return `${OUTPUT_TARGET_KIND_LABELS[mapping.targetKind]} · ${mapping.targetPath}`;
}

function outputFieldLabel(output: string, outputFields: DecisionOutputSchemaField[]): string {
  return outputFields.find((field) => field.id === output)?.label ?? output;
}

function outputSearchText(field: DecisionOutputSchemaField): string {
  return [field.id, field.label, field.dataType, ...(field.allowedValues ?? [])]
    .filter(Boolean)
    .join(' ')
    .toLowerCase();
}

function filterOutputFields(
  fields: DecisionOutputSchemaField[],
  query: string,
): DecisionOutputSchemaField[] {
  const normalized = query.trim().toLowerCase();
  if (!normalized) return fields;
  return fields.filter((field) => outputSearchText(field).includes(normalized));
}

function defaultOutputTargetKind(consumerType?: string): OutputTargetKind {
  const normalized = consumerType?.toUpperCase() ?? '';
  if (normalized.includes('BPM') || normalized.includes('PROCESS')) return 'PROCESS_VARIABLE';
  if (normalized.includes('SLA')) return 'SLA_FIELD';
  if (normalized.includes('PERMISSION')) return 'PERMISSION_CONTEXT';
  return 'ACTION_PARAM';
}

function isGeneratedTargetPath(path: string, output: string): boolean {
  return (
    !path || path === output || path === `result.${output}` || /^result\.output\d+$/.test(path)
  );
}

function versionPolicyLabel(policy: DecisionVersionPolicy): string {
  return VERSION_POLICY_LABELS[policy] ?? policy;
}

function fallbackModeLabel(mode: DecisionBindingDraft['fallbackMode']): string {
  return FALLBACK_MODE_LABELS[mode] ?? mode;
}

function outputTargetKindLabel(kind: OutputTargetKind): string {
  return OUTPUT_TARGET_KIND_LABELS[kind] ?? kind;
}

function resultStatusLabel(result: DecisionResult): string {
  const status = String(result.status ?? '').toUpperCase();
  if (status && RESULT_STATUS_LABELS[status]) return RESULT_STATUS_LABELS[status];
  return result.matched ? '已命中' : '未命中';
}

function formatOutputValue(value: unknown): string {
  if (value === null || value === undefined) return '-';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}

function DecisionBindingSummary({
  binding,
  decisions,
  fields,
  outputFields,
  showImpactPreview,
  impact,
  impactLoading,
  impactError,
  onRefreshImpact,
}: {
  binding: DecisionBindingDraft;
  decisions: DecisionOption[];
  fields: FieldOption[];
  outputFields: DecisionOutputSchemaField[];
  showImpactPreview: boolean;
  impact: DecisionImpact | null;
  impactLoading: boolean;
  impactError: string;
  onRefreshImpact: () => void;
}) {
  const decisionName = getDecisionName(decisions, binding.decisionCode);
  const inputCount = binding.inputMappings.length;
  const outputCount = binding.outputMappings.length;

  return (
    <div className="decision-rule-binding-summary" data-testid="decision-binding-summary">
      <div className="decision-rule-summary-head">
        <div>
          <div className="decision-rule-kicker">规则中心绑定</div>
          <h3>{decisionName}</h3>
          <p title={binding.decisionCode}>统一策略 · {versionPolicyLabel(binding.versionPolicy)}</p>
        </div>
        <div className="decision-rule-summary-badges">
          <span>{versionPolicyLabel(binding.versionPolicy)}</span>
          <span>{fallbackModeLabel(binding.fallbackMode)}</span>
        </div>
      </div>

      <div className="decision-rule-summary-grid">
        <div>
          <span>输入映射</span>
          <strong>{inputCount}</strong>
        </div>
        <div>
          <span>输出映射</span>
          <strong>{outputCount}</strong>
        </div>
        <div>
          <span>影响引用</span>
          <strong>{impact ? impactCount(impact) : '—'}</strong>
        </div>
        <div>
          <span>发布风险</span>
          <strong>{impact?.risk?.blocking ? '需确认' : impact ? '可继续' : '未加载'}</strong>
        </div>
      </div>

      <div className="decision-rule-summary-columns">
        <div className="decision-rule-summary-panel">
          <div className="decision-rule-summary-panel-head">
            <strong>输入映射</strong>
            <span>{inputCount} 条</span>
          </div>
          {inputCount === 0 ? (
            <div className="decision-rule-empty" data-testid="decision-binding-empty">
              未配置输入映射
            </div>
          ) : (
            <ul>
              {binding.inputMappings.map((mapping, index) => (
                <li key={`${mapping.input}-${index}`}>
                  <span>{mapping.input}</span>
                  <em title={`${mapping.scope}.${mapping.path}`}>
                    {formatInputMapping(mapping, fields)}
                  </em>
                </li>
              ))}
            </ul>
          )}
        </div>

        <div className="decision-rule-summary-panel">
          <div className="decision-rule-summary-panel-head">
            <strong>输出映射</strong>
            <span>{outputCount} 条</span>
          </div>
          {outputCount === 0 ? (
            <div className="decision-rule-empty" data-testid="decision-output-mapping-empty">
              未配置输出映射
            </div>
          ) : (
            <ul>
              {binding.outputMappings.map((mapping, index) => (
                <li key={`${mapping.output}-${index}`}>
                  <span title={mapping.output}>
                    {outputFieldLabel(mapping.output, outputFields)}
                  </span>
                  <code>{formatOutputMapping(mapping)}</code>
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>

      {showImpactPreview && (
        <div className="decision-rule-summary-impact" data-testid="decision-impact-preview">
          <div className="decision-rule-summary-panel-head">
            <strong>影响预览</strong>
            <button
              type="button"
              aria-label="refresh-impact"
              disabled={impactLoading}
              onClick={onRefreshImpact}
            >
              {impactLoading ? '加载中' : '刷新影响'}
            </button>
          </div>
          {impactError ? (
            <div className="decision-rule-error" data-testid="decision-impact-error">
              {impactError}
            </div>
          ) : impact ? (
            <div className="decision-rule-impact-summary" data-testid="decision-impact-summary">
              <strong>{impact.risk?.summary ?? '无影响摘要'}</strong>
              <span>{impactCount(impact)} 个引用</span>
              <span>{impact.risk?.blocking ? '需确认' : '可继续'}</span>
            </div>
          ) : (
            <div className="decision-rule-empty" data-testid="decision-impact-empty">
              尚未加载影响
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function DecisionBindingPreviewSummary({
  binding,
  decisions,
  fields,
  outputFields,
}: {
  binding: DecisionBindingDraft;
  decisions: DecisionOption[];
  fields: FieldOption[];
  outputFields: DecisionOutputSchemaField[];
}) {
  const decisionName = getDecisionName(decisions, binding.decisionCode);

  return (
    <div className="decision-rule-binding-review">
      <div data-testid="decision-binding-preview">
        <div className="decision-rule-review-head">
          <div>
            <strong title={binding.decisionCode}>{decisionName}</strong>
            <span>
              {versionPolicyLabel(binding.versionPolicy)} ·{' '}
              {fallbackModeLabel(binding.fallbackMode)}
            </span>
          </div>
          <div className="decision-rule-review-counts">
            <span>{binding.inputMappings.length} 输入</span>
            <span>{binding.outputMappings.length} 输出</span>
          </div>
        </div>

        <div className="decision-rule-review-grid">
          <section>
            <div className="decision-rule-summary-panel-head">
              <strong>输入映射</strong>
              <span>{binding.inputMappings.length} 条</span>
            </div>
            {binding.inputMappings.length === 0 ? (
              <div className="decision-rule-empty">暂未配置输入</div>
            ) : (
              <ul>
                {binding.inputMappings.map((mapping, index) => (
                  <li key={`${mapping.input}-${index}`}>
                    <span>{mapping.input}</span>
                    <em title={`${mapping.scope}.${mapping.path}`}>
                      {formatInputMapping(mapping, fields)}
                    </em>
                  </li>
                ))}
              </ul>
            )}
          </section>

          <section>
            <div className="decision-rule-summary-panel-head">
              <strong>输出映射</strong>
              <span>{binding.outputMappings.length} 条</span>
            </div>
            {binding.outputMappings.length === 0 ? (
              <div className="decision-rule-empty">暂未配置输出</div>
            ) : (
              <ul>
                {binding.outputMappings.map((mapping, index) => (
                  <li key={`${mapping.output}-${index}`}>
                    <span title={mapping.output}>
                      {outputFieldLabel(mapping.output, outputFields)}
                    </span>
                    <em title={mapping.targetPath}>{formatOutputMapping(mapping)}</em>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </div>
      </div>

      <details className="decision-rule-debug-details">
        <summary>调试明细</summary>
        <textarea
          aria-label="decision-binding-debug-json"
          readOnly
          value={JSON.stringify(bindingPreviewPayload(binding), null, 2)}
        />
      </details>
    </div>
  );
}

function decisionTraceHref({
  traceId,
  decisionCode,
  callerType,
  callerRef,
}: {
  traceId?: string;
  decisionCode?: string;
  callerType?: string;
  callerRef?: string;
}): string | undefined {
  if (!traceId) return undefined;
  const params = new URLSearchParams({ traceId });
  if (decisionCode) params.set('decisionCode', decisionCode);
  if (callerType) params.set('callerType', callerType);
  if (callerRef) params.set('callerRef', callerRef);
  return `/p/decisionops_execution_logs?${params.toString()}`;
}

function DecisionTestResultSummary({
  result,
  decisionCode,
  callerType,
  callerRef,
}: {
  result: DecisionResult;
  decisionCode?: string;
  callerType?: string;
  callerRef?: string;
}) {
  const outputs = Object.entries(result.outputs ?? {});
  const unknownReasons = result.unknownReasons ?? [];
  const traceHref = decisionTraceHref({
    traceId: result.traceId,
    decisionCode,
    callerType,
    callerRef,
  });

  return (
    <div className="decision-rule-test-result" data-testid="decision-test-result">
      <div className="decision-rule-test-status">
        <strong>{resultStatusLabel(result)}</strong>
        {traceHref ? (
          <a data-testid="decision-test-open-trace" href={traceHref}>
            打开统一 Trace
            <span>{result.traceId}</span>
          </a>
        ) : (
          <span>无 Trace</span>
        )}
      </div>
      {outputs.length > 0 ? (
        <dl>
          {outputs.map(([key, value]) => (
            <div key={key}>
              <dt>{key}</dt>
              <dd>{formatOutputValue(value)}</dd>
            </div>
          ))}
        </dl>
      ) : (
        <div className="decision-rule-empty">无输出结果</div>
      )}
      {unknownReasons.length > 0 && (
        <div className="decision-rule-test-unknown" data-testid="decision-test-unknown-reasons">
          <strong>未知原因</strong>
          <ul>
            {unknownReasons.map((reason) => (
              <li key={reason}>{reason}</li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

function FieldSearchSelect({
  fields,
  value,
  onChange,
  searchAriaLabel,
  selectAriaLabel,
  countTestId,
}: {
  fields: FieldOption[];
  value: string;
  onChange: (nextKey: string) => void;
  searchAriaLabel: string;
  selectAriaLabel: string;
  countTestId: string;
}) {
  const [query, setQuery] = useState('');
  const fieldMap = useMemo(() => {
    const map = new Map<string, FieldOption>();
    fields.forEach((field) => map.set(fieldKey(field), field));
    return map;
  }, [fields]);
  const matchedFields = useMemo(() => filterFieldOptions(fields, query), [fields, query]);
  const visibleFields = useMemo(
    () => keepSelectedField(matchedFields, fieldMap.get(value)),
    [fieldMap, matchedFields, value],
  );
  const groupedFields = useMemo(() => groupFieldOptions(visibleFields), [visibleFields]);

  return (
    <div className="decision-rule-field-picker">
      <label>
        字段搜索
        <input
          aria-label={searchAriaLabel}
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder="搜索字段、模型或路径"
        />
      </label>
      <div className="decision-rule-field-picker-row">
        <select
          aria-label={selectAriaLabel}
          value={value}
          onChange={(event) => onChange(event.target.value)}
        >
          {groupedFields.length === 0 ? (
            <option value="">无匹配字段</option>
          ) : (
            groupedFields.map((fieldGroup) => (
              <optgroup key={fieldGroup.label} label={fieldGroup.label}>
                {fieldGroup.fields.map((field) => (
                  <option
                    key={fieldKey(field)}
                    value={fieldKey(field)}
                    disabled={Boolean(ruleInputDisabledReason(field))}
                  >
                    {field.label}
                    {ruleInputOptionNote(field) ? ` · ${ruleInputOptionNote(field)}` : ''}
                  </option>
                ))}
              </optgroup>
            ))
          )}
        </select>
        <span data-testid={countTestId}>
          {matchedFields.length} / {fields.length}
        </span>
      </div>
    </div>
  );
}

function OutputTargetFieldSuggestion({
  fields,
  onPick,
  searchAriaLabel,
  selectAriaLabel,
  countTestId,
}: {
  fields: FieldOption[];
  onPick: (field: FieldOption) => void;
  searchAriaLabel: string;
  selectAriaLabel: string;
  countTestId: string;
}) {
  const [query, setQuery] = useState('');
  const fieldMap = useMemo(() => {
    const map = new Map<string, FieldOption>();
    fields.forEach((field) => map.set(fieldKey(field), field));
    return map;
  }, [fields]);
  const matchedFields = useMemo(() => filterFieldOptions(fields, query), [fields, query]);
  const groupedFields = useMemo(() => groupFieldOptions(matchedFields), [matchedFields]);

  return (
    <div className="decision-rule-field-picker decision-rule-target-suggestion">
      <label>
        目标字段建议
        <input
          aria-label={searchAriaLabel}
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder="搜索字段后填入目标路径"
        />
      </label>
      <div className="decision-rule-field-picker-row">
        <select
          aria-label={selectAriaLabel}
          value=""
          onChange={(event) => {
            const field = fieldMap.get(event.target.value);
            if (field) onPick(field);
          }}
        >
          <option value="">选择字段填入路径</option>
          {groupedFields.map((fieldGroup) => (
            <optgroup key={fieldGroup.label} label={fieldGroup.label}>
              {fieldGroup.fields.map((field) => (
                <option key={fieldKey(field)} value={fieldKey(field)}>
                  {field.label}
                </option>
              ))}
            </optgroup>
          ))}
        </select>
        <span data-testid={countTestId}>
          {matchedFields.length} / {fields.length}
        </span>
      </div>
    </div>
  );
}

function DecisionOutputSchemaPicker({
  outputs,
  value,
  onPick,
  searchAriaLabel,
  selectAriaLabel,
  countTestId,
}: {
  outputs: DecisionOutputSchemaField[];
  value: string;
  onPick: (field: DecisionOutputSchemaField) => void;
  searchAriaLabel: string;
  selectAriaLabel: string;
  countTestId: string;
}) {
  const [query, setQuery] = useState('');
  const outputMap = useMemo(() => {
    const map = new Map<string, DecisionOutputSchemaField>();
    outputs.forEach((output) => map.set(output.id, output));
    return map;
  }, [outputs]);
  const matchedOutputs = useMemo(() => filterOutputFields(outputs, query), [outputs, query]);
  const visibleOutputs = useMemo(() => {
    const selected = outputMap.get(value);
    const seen = new Set<string>();
    return [selected, ...matchedOutputs]
      .filter((field): field is DecisionOutputSchemaField => Boolean(field))
      .filter((field) => {
        if (seen.has(field.id)) return false;
        seen.add(field.id);
        return true;
      });
  }, [matchedOutputs, outputMap, value]);

  if (outputs.length === 0) return null;

  return (
    <div className="decision-rule-field-picker decision-rule-output-suggestion">
      <label>
        规则输出
        <input
          aria-label={searchAriaLabel}
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder="搜索输出变量"
        />
      </label>
      <div className="decision-rule-field-picker-row">
        <select
          aria-label={selectAriaLabel}
          value={outputMap.has(value) ? value : ''}
          onChange={(event) => {
            const output = outputMap.get(event.target.value);
            if (output) onPick(output);
          }}
        >
          <option value="">选择 DMN 输出</option>
          {visibleOutputs.map((output) => (
            <option key={output.id} value={output.id}>
              {output.label}
              {output.dataType ? ` · ${output.dataType}` : ''}
            </option>
          ))}
        </select>
        <span data-testid={countTestId}>
          {matchedOutputs.length} / {outputs.length}
        </span>
      </div>
    </div>
  );
}

function TestContextEditor({
  fields,
  contextJson,
  onChange,
}: {
  fields: FieldOption[];
  contextJson: string;
  onChange: (nextJson: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const context = useMemo(() => parseContextObject(contextJson), [contextJson]);
  const matchedFields = useMemo(() => filterFieldOptions(fields, query), [fields, query]);
  const groupedFields = useMemo(() => groupFieldOptions(matchedFields), [matchedFields]);

  return (
    <div className="decision-rule-context-shell">
      <div className="decision-rule-context-summary" data-testid="decision-test-context-summary">
        <div>
          <strong>测试上下文</strong>
          <span>{fields.length} 个字段</span>
        </div>
        <button
          type="button"
          aria-label="open-test-context-drawer"
          aria-expanded={open}
          onClick={() => setOpen((current) => !current)}
        >
          {open ? '收起上下文' : '编辑上下文'}
        </button>
      </div>

      {open && (
        <div className="decision-rule-context-drawer" data-testid="decision-test-context-drawer">
          <div className="decision-rule-context-tools">
            <label>
              字段搜索
              <input
                aria-label="test-context-field-search"
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder="搜索字段、模型或路径"
              />
            </label>
            <span data-testid="test-context-field-count">
              {matchedFields.length} / {fields.length}
            </span>
          </div>

          {matchedFields.length === 0 ? (
            <div className="decision-rule-empty">无匹配上下文字段</div>
          ) : (
            <div className="decision-rule-context-field-groups">
              {groupedFields.map((fieldGroup) => (
                <section key={fieldGroup.label}>
                  <strong>{fieldGroup.label}</strong>
                  {fieldGroup.fields.map((field) => (
                    <label key={fieldKey(field)} className="decision-rule-context-field-row">
                      <span>{field.label}</span>
                      <code>
                        {field.scope}.{field.path}
                      </code>
                      {fieldSourceSummary(field) && (
                        <small data-testid={contextFieldSourceTestId(field)}>
                          {fieldSourceSummary(field)}
                        </small>
                      )}
                      <input
                        aria-label={contextFieldInputLabel(field)}
                        value={formatContextFieldValue(readContextFieldValue(context, field))}
                        onChange={(event) =>
                          onChange(updateContextFieldJson(contextJson, field, event.target.value))
                        }
                      />
                    </label>
                  ))}
                </section>
              ))}
            </div>
          )}
        </div>
      )}

      <details className="decision-rule-context-advanced">
        <summary>高级 JSON</summary>
        <label className="decision-rule-context-editor">
          测试上下文 JSON
          <textarea
            aria-label="test-run-context"
            value={contextJson}
            onChange={(event) => onChange(event.target.value)}
          />
        </label>
      </details>
    </div>
  );
}

export function DecisionRuleBindingBlock({
  block,
  runtime,
  value,
  onChange,
  api,
}: DecisionRuleBindingBlockProps) {
  const props = block?.props ?? {};
  const mode = props.mode ?? 'combined';
  const configuredFields = props.fields && props.fields.length > 0 ? props.fields : undefined;
  const fieldCatalogMode = props.fieldCatalogMode ?? 'disabled';
  const fieldCatalogModelCode = resolveFieldCatalogModelCode(props, runtime);
  const readOnly = props.readOnly === true || props.variant === 'summary';
  const [activeWorkspacePanel, setActiveWorkspacePanel] = useState<RuleBindingWorkspacePanel>(
    mode === 'decision' ? 'decision' : 'condition',
  );
  const [catalogFields, setCatalogFields] = useState<FieldOption[]>([]);
  const fallbackFields = configuredFields ?? (fieldCatalogModelCode ? [] : DEFAULT_FIELDS);
  const baseFields =
    fieldCatalogMode === 'merge'
      ? mergeFieldOptions(catalogFields, fallbackFields)
      : (configuredFields ?? mergeFieldOptions(catalogFields, DEFAULT_FIELDS));
  const decisions = useMemo(() => mergeDecisionOptions(props.decisions), [props.decisions]);
  const defaultApiRef = useRef<RuleBindingDecisionApi | null>(null);
  const incomingRawBindingValue =
    value ??
    props.value ??
    (props.valueField ? runtime?.getFieldValue?.(props.valueField) : undefined) ??
    props.initialValue;
  const initialRuleBinding = parseBindingValue(incomingRawBindingValue);
  const [condition, setCondition] = useState<GroupNode>(
    () => initialRuleBinding?.conditionSpec?.root ?? defaultCondition(),
  );
  const [binding, setBinding] = useState<DecisionBindingDraft>(() =>
    buildInitialBinding(
      decisions,
      props.initialDecisionCode,
      props.initialVersionPolicy,
      initialRuleBinding,
    ),
  );
  const [impact, setImpact] = useState<DecisionImpact | null>(null);
  const [impactLoading, setImpactLoading] = useState(false);
  const [impactError, setImpactError] = useState('');
  const [contextJson, setContextJson] = useState(() =>
    buildInitialContextJson(runtime, props.initialContextJson),
  );
  const [testResult, setTestResult] = useState<DecisionResult | null>(null);
  const [testRunning, setTestRunning] = useState(false);
  const [testError, setTestError] = useState('');
  const initialValueWrittenRef = useRef(false);
  const syncedBindingFingerprintRef = useRef(bindingValueFingerprint(incomingRawBindingValue));

  const fields = useMemo(
    () =>
      mergeFieldOptions(
        baseFields.filter(isVisibleRuleField),
        binding.inputMappings.map(mappedFieldOption),
      ),
    [baseFields, binding.inputMappings],
  );
  const ruleInputFields = useMemo(() => fields.filter(isRuleInputSelectable), [fields]);
  const selectedDecisionOutputFields = useMemo(() => {
    const decision = decisions.find((candidate) => candidate.code === binding.decisionCode);
    return normalizeDecisionOutputFields(decision?.outputs, decision?.outputSchemaJson);
  }, [binding.decisionCode, decisions]);

  const fieldByKey = useMemo(() => {
    const map = new Map<string, FieldOption>();
    fields.forEach((field) => map.set(fieldKey(field), field));
    return map;
  }, [fields]);

  const showCondition = mode === 'condition' || mode === 'combined';
  const showDecision = mode === 'decision' || mode === 'combined';
  const showImpactPreview = showDecision && props.showImpactPreview !== false;
  const showTestRunner = showDecision && props.showTestRunner !== false;
  const showStandaloneImpactPreview = !readOnly && showImpactPreview;
  const showStandaloneTestRunner = !readOnly && showTestRunner;
  const workspaceTabs: RuleBindingWorkspaceTab[] = [];
  if (showCondition) {
    workspaceTabs.push({
      key: 'condition',
      label: '条件',
      meta: `${condition.children.length} 条`,
    });
  }
  if (showDecision) {
    workspaceTabs.push({
      key: 'decision',
      label: readOnly ? '摘要' : '决策',
      meta: versionPolicyLabel(binding.versionPolicy),
    });
  }
  if (showStandaloneImpactPreview) {
    workspaceTabs.push({
      key: 'impact',
      label: '影响',
      meta: impactError ? '异常' : impact ? `${impactCount(impact)} 引用` : '待刷新',
    });
  }
  if (showStandaloneTestRunner) {
    workspaceTabs.push({
      key: 'test',
      label: '测试',
      meta: testError ? '异常' : testResult ? resultStatusLabel(testResult) : '未运行',
    });
  }
  const workspacePanelKeys = workspaceTabs.map((tab) => tab.key).join('|');

  useEffect(() => {
    const availablePanels = workspacePanelKeys
      .split('|')
      .filter(Boolean) as RuleBindingWorkspacePanel[];
    if (availablePanels.length === 0 || availablePanels.includes(activeWorkspacePanel)) return;
    setActiveWorkspacePanel(availablePanels[0]);
  }, [activeWorkspacePanel, workspacePanelKeys]);

  const getDecisionApi = useCallback(() => {
    if (api) return api;
    if (!defaultApiRef.current) {
      defaultApiRef.current = defaultDecisionApi();
    }
    return defaultApiRef.current;
  }, [api]);

  useEffect(() => {
    const shouldLoadCatalog =
      fieldCatalogMode === 'merge' || (fieldCatalogMode === 'fallback' && !configuredFields);
    if (!shouldLoadCatalog) {
      setCatalogFields([]);
      return;
    }
    let cancelled = false;
    const loadCatalogFields = async () => {
      const decisionApi = getDecisionApi();
      if (typeof decisionApi.getFactCatalog === 'function') {
        const factFields = factCatalogToFieldOptions(
          await decisionApi.getFactCatalog(fieldCatalogModelCode),
        ).filter((field) => fieldOptionMatchesModel(field, fieldCatalogModelCode));
        if (factFields.length > 0) return factFields;
      }
      if (typeof decisionApi.getModelFields === 'function') {
        return modelFieldsToFieldOptions(await decisionApi.getModelFields()).filter((field) =>
          fieldOptionMatchesModel(field, fieldCatalogModelCode),
        );
      }
      return [];
    };
    loadCatalogFields()
      .then((nextFields) => {
        if (cancelled) return;
        setCatalogFields(nextFields);
      })
      .catch(async () => {
        if (cancelled) return;
        try {
          const rows = await getDecisionApi().getModelFields?.();
          if (cancelled) return;
          setCatalogFields(
            modelFieldsToFieldOptions(rows).filter((field) =>
              fieldOptionMatchesModel(field, fieldCatalogModelCode),
            ),
          );
        } catch {
          if (!cancelled) setCatalogFields([]);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [configuredFields, fieldCatalogMode, fieldCatalogModelCode, getDecisionApi]);

  useEffect(() => {
    const nextFingerprint = bindingValueFingerprint(incomingRawBindingValue);
    if (!nextFingerprint || nextFingerprint === syncedBindingFingerprintRef.current) return;
    const nextRuleBinding = parseBindingValue(incomingRawBindingValue);
    if (!nextRuleBinding) return;
    syncedBindingFingerprintRef.current = nextFingerprint;
    setCondition(nextRuleBinding.conditionSpec?.root ?? defaultCondition());
    setBinding(
      buildInitialBinding(
        decisions,
        props.initialDecisionCode,
        props.initialVersionPolicy,
        nextRuleBinding,
      ),
    );
  }, [decisions, incomingRawBindingValue, props.initialDecisionCode, props.initialVersionPolicy]);

  const effectiveConsumerType = props.consumerType ?? initialRuleBinding?.consumerType;
  const effectiveConsumerCode = resolveConsumerCode(props, runtime, initialRuleBinding);
  const effectiveConsumerNodeId = props.consumerNodeId ?? initialRuleBinding?.consumerNodeId;

  const emitChange = (nextCondition: GroupNode, nextBinding: DecisionBindingDraft) => {
    const nextValue = buildRuleConsumerBinding(nextCondition, nextBinding, {
      showCondition,
      showDecision,
      consumerType: effectiveConsumerType,
      consumerCode: effectiveConsumerCode,
      consumerNodeId: effectiveConsumerNodeId,
    });
    onChange?.(nextValue);
    if (props.valueField) {
      runtime?.updateField?.(props.valueField, nextValue);
    }
  };

  useEffect(() => {
    if (initialValueWrittenRef.current || !props.valueField) return;
    initialValueWrittenRef.current = true;
    const currentValue = runtime?.getFieldValue?.(props.valueField);
    if (currentValue !== undefined && currentValue !== null && currentValue !== '') return;
    emitChange(condition, binding);
  }, [binding, condition, props.valueField, runtime]);

  const addInputMapping = () => {
    const first = ruleInputFields[0];
    if (!first) return;
    setBinding((current) => {
      const next = {
        ...current,
        inputMappings: [
          ...current.inputMappings,
          {
            input: `input${current.inputMappings.length + 1}`,
            scope: first.scope,
            path: first.path,
          },
        ],
      };
      emitChange(condition, next);
      return next;
    });
  };

  const updateMapping = (index: number, patch: Partial<InputMapping>) => {
    setBinding((current) => {
      const next = current.inputMappings.slice();
      next[index] = { ...next[index], ...patch };
      const nextBinding = { ...current, inputMappings: next };
      emitChange(condition, nextBinding);
      return nextBinding;
    });
  };

  const updateMappingField = (index: number, key: string) => {
    const field = fieldByKey.get(key);
    if (!field || ruleInputDisabledReason(field)) return;
    updateMapping(index, { scope: field.scope, path: field.path });
  };

  const removeMapping = (index: number) => {
    setBinding((current) => {
      const next = {
        ...current,
        inputMappings: current.inputMappings.filter((_, i) => i !== index),
      };
      emitChange(condition, next);
      return next;
    });
  };

  const addOutputMapping = () => {
    setBinding((current) => {
      const usedOutputs = new Set(current.outputMappings.map((mapping) => mapping.output));
      const schemaOutput =
        selectedDecisionOutputFields.find((output) => !usedOutputs.has(output.id)) ??
        selectedDecisionOutputFields[0];
      const output = schemaOutput?.id ?? `output${current.outputMappings.length + 1}`;
      const next = {
        ...current,
        outputMappings: [
          ...current.outputMappings,
          {
            output,
            targetKind: defaultOutputTargetKind(props.consumerType),
            targetPath: schemaOutput?.id ?? `result.output${current.outputMappings.length + 1}`,
          },
        ],
      };
      emitChange(condition, next);
      return next;
    });
  };

  const updateOutputMapping = (index: number, patch: Partial<OutputMapping>) => {
    setBinding((current) => {
      const next = current.outputMappings.slice();
      next[index] = { ...next[index], ...patch };
      const nextBinding = { ...current, outputMappings: next };
      emitChange(condition, nextBinding);
      return nextBinding;
    });
  };

  const pickOutputMapping = (index: number, output: DecisionOutputSchemaField) => {
    setBinding((current) => {
      const next = current.outputMappings.slice();
      const currentMapping = next[index];
      if (!currentMapping) return current;
      next[index] = {
        ...currentMapping,
        output: output.id,
        targetPath: isGeneratedTargetPath(currentMapping.targetPath, currentMapping.output)
          ? output.id
          : currentMapping.targetPath,
      };
      const nextBinding = { ...current, outputMappings: next };
      emitChange(condition, nextBinding);
      return nextBinding;
    });
  };

  const removeOutputMapping = (index: number) => {
    setBinding((current) => {
      const next = {
        ...current,
        outputMappings: current.outputMappings.filter((_, i) => i !== index),
      };
      emitChange(condition, next);
      return next;
    });
  };

  const updateCondition = (nextCondition: GroupNode) => {
    setCondition(nextCondition);
    emitChange(nextCondition, binding);
  };

  const refreshImpact = async () => {
    if (!binding.decisionCode) {
      setImpactError('请选择决策');
      return;
    }
    setImpactLoading(true);
    setImpactError('');
    try {
      setImpact(await getDecisionApi().getDecisionImpact(binding.decisionCode));
    } catch (error) {
      setImpactError(errorMessage(error));
    } finally {
      setImpactLoading(false);
    }
  };

  const runDecisionTest = async () => {
    if (!binding.decisionCode) {
      setTestError('请选择决策');
      return;
    }
    setTestRunning(true);
    setTestError('');
    setTestResult(null);
    try {
      const result = await getDecisionApi().evaluate({
        decisionCode: binding.decisionCode,
        binding: versionPolicyToEvaluateBinding(binding.versionPolicy),
        callerType: effectiveConsumerType ?? 'RULE_BINDING_PREVIEW',
        callerRef: effectiveConsumerCode,
        context: applyInputMappings(parseContextJson(contextJson), binding.inputMappings),
      });
      setTestResult(result);
    } catch (error) {
      setTestError(errorMessage(error));
    } finally {
      setTestRunning(false);
    }
  };

  const panelAttrs = (panel: RuleBindingWorkspacePanel) => ({
    className: 'decision-rule-section-panel',
    'data-active': activeWorkspacePanel === panel ? 'true' : 'false',
    'data-workspace-panel': panel,
    'data-testid': `decision-rule-section-${panel}`,
  });

  return (
    <section className="decision-rule-binding-block" data-testid="decision-rule-binding-block">
      {workspaceTabs.length > 1 && (
        <div className="decision-rule-section-tabs" data-testid="decision-rule-section-tabs">
          {workspaceTabs.map((tab) => (
            <button
              type="button"
              key={tab.key}
              aria-pressed={activeWorkspacePanel === tab.key}
              data-testid={`decision-rule-section-tab-${tab.key}`}
              onClick={() => setActiveWorkspacePanel(tab.key)}
            >
              <span>{tab.label}</span>
              <strong>{tab.meta}</strong>
            </button>
          ))}
        </div>
      )}

      {showCondition && (
        <div {...panelAttrs('condition')}>
          <div className="decision-rule-binding-section">
            <div className="decision-rule-binding-heading">
              <strong>条件</strong>
              <span>{condition.children.length} 条</span>
            </div>
            <ConditionBuilder
              value={condition}
              fields={ruleInputFields}
              onChange={updateCondition}
            />
          </div>
        </div>
      )}

      {showDecision && readOnly ? (
        <div {...panelAttrs('decision')}>
          <DecisionBindingSummary
            binding={binding}
            decisions={decisions}
            fields={fields}
            outputFields={selectedDecisionOutputFields}
            showImpactPreview={showImpactPreview}
            impact={impact}
            impactLoading={impactLoading}
            impactError={impactError}
            onRefreshImpact={refreshImpact}
          />
        </div>
      ) : showDecision ? (
        <>
          <div {...panelAttrs('decision')}>
            <div className="decision-rule-binding-section" data-testid="decision-binding-editor">
              <div className="decision-rule-binding-heading">
                <strong>引用规则中心</strong>
                <span>{versionPolicyLabel(binding.versionPolicy)}</span>
              </div>

              <div className="decision-rule-binding-grid">
                <label>
                  决策
                  <select
                    aria-label="decision-code"
                    value={binding.decisionCode}
                    onChange={(event) =>
                      setBinding((current) => {
                        const next = {
                          ...current,
                          decisionCode: event.target.value,
                        };
                        emitChange(condition, next);
                        return next;
                      })
                    }
                  >
                    {decisions.map((decision) => (
                      <option key={decision.code} value={decision.code} title={decision.code}>
                        {decisionDisplayName(decision.code, decision.name)}
                      </option>
                    ))}
                  </select>
                </label>

                <label>
                  版本策略
                  <select
                    aria-label="version-policy"
                    value={binding.versionPolicy}
                    onChange={(event) =>
                      setBinding((current) => {
                        const next = {
                          ...current,
                          versionPolicy: event.target.value as DecisionVersionPolicy,
                        };
                        emitChange(condition, next);
                        return next;
                      })
                    }
                  >
                    {VERSION_POLICIES.map((policy) => (
                      <option key={policy} value={policy}>
                        {versionPolicyLabel(policy)}
                      </option>
                    ))}
                  </select>
                </label>

                <label>
                  失败策略
                  <select
                    aria-label="fallback-mode"
                    value={binding.fallbackMode}
                    onChange={(event) =>
                      setBinding((current) => {
                        const next = {
                          ...current,
                          fallbackMode: event.target.value as DecisionBindingDraft['fallbackMode'],
                        };
                        emitChange(condition, next);
                        return next;
                      })
                    }
                  >
                    <option value="FAIL_CLOSED">{fallbackModeLabel('FAIL_CLOSED')}</option>
                    <option value="FAIL_OPEN">{fallbackModeLabel('FAIL_OPEN')}</option>
                    <option value="DEFAULT_VALUE">{fallbackModeLabel('DEFAULT_VALUE')}</option>
                  </select>
                </label>
              </div>

              <div className="decision-rule-mapping-header">
                <strong>输入映射</strong>
                <button type="button" onClick={addInputMapping}>
                  添加映射
                </button>
              </div>

              {binding.inputMappings.length === 0 && (
                <div className="decision-rule-empty" data-testid="decision-binding-empty">
                  暂无输入映射
                </div>
              )}

              {binding.inputMappings.map((mapping, index) => (
                <div
                  className="decision-rule-mapping-row"
                  data-testid={`decision-binding-mapping-${index}`}
                  key={index}
                >
                  <input
                    aria-label={`mapping-input-${index}`}
                    value={mapping.input}
                    onChange={(event) => updateMapping(index, { input: event.target.value })}
                  />
                  <FieldSearchSelect
                    fields={fields}
                    value={fieldKey(mapping)}
                    onChange={(nextKey) => updateMappingField(index, nextKey)}
                    searchAriaLabel={`mapping-field-search-${index}`}
                    selectAriaLabel={`mapping-field-${index}`}
                    countTestId={`mapping-field-count-${index}`}
                  />
                  <button
                    type="button"
                    aria-label={`mapping-remove-${index}`}
                    onClick={() => removeMapping(index)}
                  >
                    删除
                  </button>
                </div>
              ))}

              <div className="decision-rule-mapping-header">
                <strong>输出映射</strong>
                <button type="button" onClick={addOutputMapping}>
                  添加输出
                </button>
              </div>

              {binding.outputMappings.length === 0 && (
                <div className="decision-rule-empty" data-testid="decision-output-mapping-empty">
                  暂无输出映射
                </div>
              )}

              {binding.outputMappings.map((mapping, index) => (
                <div
                  className="decision-rule-mapping-row"
                  data-testid={`decision-output-mapping-${index}`}
                  key={index}
                >
                  <input
                    aria-label={`output-mapping-output-${index}`}
                    value={mapping.output}
                    onChange={(event) => updateOutputMapping(index, { output: event.target.value })}
                  />
                  <DecisionOutputSchemaPicker
                    outputs={selectedDecisionOutputFields}
                    value={mapping.output}
                    onPick={(output) => pickOutputMapping(index, output)}
                    searchAriaLabel={`output-mapping-output-search-${index}`}
                    selectAriaLabel={`output-mapping-output-picker-${index}`}
                    countTestId={`output-mapping-output-count-${index}`}
                  />
                  <select
                    aria-label={`output-mapping-kind-${index}`}
                    value={mapping.targetKind}
                    onChange={(event) =>
                      updateOutputMapping(index, {
                        targetKind: event.target.value as OutputTargetKind,
                      })
                    }
                  >
                    {OUTPUT_TARGET_KINDS.map((kind) => (
                      <option key={kind} value={kind}>
                        {outputTargetKindLabel(kind)}
                      </option>
                    ))}
                  </select>
                  <input
                    aria-label={`output-mapping-path-${index}`}
                    value={mapping.targetPath}
                    onChange={(event) =>
                      updateOutputMapping(index, { targetPath: event.target.value })
                    }
                  />
                  <button
                    type="button"
                    aria-label={`output-mapping-remove-${index}`}
                    onClick={() => removeOutputMapping(index)}
                  >
                    删除
                  </button>
                  <OutputTargetFieldSuggestion
                    fields={fields}
                    onPick={(field) => updateOutputMapping(index, { targetPath: field.path })}
                    searchAriaLabel={`output-mapping-target-field-search-${index}`}
                    selectAriaLabel={`output-mapping-target-field-${index}`}
                    countTestId={`output-mapping-target-field-count-${index}`}
                  />
                </div>
              ))}

              <DecisionBindingPreviewSummary
                binding={binding}
                decisions={decisions}
                fields={fields}
                outputFields={selectedDecisionOutputFields}
              />
            </div>
          </div>

          {showStandaloneImpactPreview && (
            <div {...panelAttrs('impact')}>
              <div className="decision-rule-binding-section" data-testid="decision-impact-preview">
                <div className="decision-rule-binding-heading">
                  <strong>影响预览</strong>
                  <button
                    type="button"
                    aria-label="refresh-impact"
                    disabled={impactLoading}
                    onClick={refreshImpact}
                  >
                    {impactLoading ? '加载中' : '刷新'}
                  </button>
                </div>
                {impactError ? (
                  <div className="decision-rule-error" data-testid="decision-impact-error">
                    {impactError}
                  </div>
                ) : impact ? (
                  <div
                    className="decision-rule-impact-summary"
                    data-testid="decision-impact-summary"
                  >
                    <strong>{impact.risk?.summary ?? '无影响摘要'}</strong>
                    <span>{impactCount(impact)} 个引用</span>
                    <span>{impact.risk?.blocking ? '需确认' : '可继续'}</span>
                  </div>
                ) : (
                  <div className="decision-rule-empty" data-testid="decision-impact-empty">
                    尚未加载影响
                  </div>
                )}
              </div>
            </div>
          )}

          {showStandaloneTestRunner && (
            <div {...panelAttrs('test')}>
              <div className="decision-rule-binding-section" data-testid="decision-test-runner">
                <div className="decision-rule-binding-heading">
                  <strong>测试运行</strong>
                  <button
                    type="button"
                    aria-label="run-decision-test"
                    disabled={testRunning}
                    onClick={runDecisionTest}
                  >
                    {testRunning ? '运行中' : '运行'}
                  </button>
                </div>
                <TestContextEditor
                  fields={fields}
                  contextJson={contextJson}
                  onChange={setContextJson}
                />
                {testError ? (
                  <div className="decision-rule-error" data-testid="decision-test-error">
                    {testError}
                  </div>
                ) : testResult ? (
                  <DecisionTestResultSummary
                    result={testResult}
                    decisionCode={binding.decisionCode}
                    callerType={effectiveConsumerType ?? 'RULE_BINDING_PREVIEW'}
                    callerRef={effectiveConsumerCode}
                  />
                ) : (
                  <div className="decision-rule-empty" data-testid="decision-test-empty">
                    尚未运行
                  </div>
                )}
              </div>
            </div>
          )}
        </>
      ) : null}
    </section>
  );
}

export default DecisionRuleBindingBlock;
