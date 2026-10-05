import { useI18n } from '~/contexts/I18nContext';
import { bindingText, type BindingTextKey } from './decisionBindingText';
import { bindingPresentation } from './decisionBindingPresentation';
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

interface DecisionDefinitionRecord {
  decisionCode?: string;
  code?: string;
  decisionName?: string;
  name?: string;
  enabled?: boolean;
  outputs?: DecisionOutputSchemaSource[];
  outputSchemaJson?: unknown;
}

type DecisionDefinitionListPayload =
  | DecisionDefinitionRecord[]
  | {
      records?: DecisionDefinitionRecord[];
      data?: DecisionDefinitionRecord[];
      content?: DecisionDefinitionRecord[];
    };

type DecisionDefinitionPagePayload = Exclude<
  DecisionDefinitionListPayload,
  DecisionDefinitionRecord[]
>;

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
  listDefinitions?: (filters?: {
    keyword?: string;
    page?: number;
    size?: number;
  }) => Promise<unknown>;
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

const STALE_DECISION_NAMES = new Set(['投诉 SLA 截止时间', '审批路由', '']);

function mergeDecisionOptions(
  configured?: DecisionOption[],
  locale: string = 'zh-CN',
): DecisionOption[] {
  if (!configured || configured.length === 0) return bindingPresentation(locale).DEFAULT_DECISIONS;
  const byCode = new Map(
    bindingPresentation(locale).DEFAULT_DECISIONS.map((decision) => [decision.code, decision]),
  );
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

function recordsFromDecisionDefinitionPayload(raw: unknown): DecisionDefinitionRecord[] {
  if (Array.isArray(raw)) return raw as DecisionDefinitionRecord[];
  if (!raw || typeof raw !== 'object') return [];
  const payload = raw as DecisionDefinitionPagePayload;
  if (Array.isArray(payload.records)) return payload.records;
  if (Array.isArray(payload.data)) return payload.data;
  if (Array.isArray(payload.content)) return payload.content;
  return [];
}

function decisionDefinitionsToOptions(raw: unknown): DecisionOption[] {
  return recordsFromDecisionDefinitionPayload(raw).reduce<DecisionOption[]>((options, record) => {
    if (!record || record.enabled === false) return options;
    const code = record.decisionCode || record.code || '';
    if (!code.trim()) return options;
    options.push({
      code,
      name: record.decisionName || record.name || code,
      outputs: record.outputs,
      outputSchemaJson: record.outputSchemaJson,
    });
    return options;
  }, []);
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

function fieldKey(field: Pick<FieldOption, 'scope' | 'path'>): string {
  return `${field.scope}:${field.path}`;
}

function fieldGroupLabel(field: FieldOption, locale: string = 'zh-CN'): string {
  return (
    field.modelName || bindingPresentation(locale).FIELD_SCOPE_LABELS[field.scope] || field.scope
  );
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

function ruleInputDisabledReason(field: FieldOption, locale: string = 'zh-CN'): string | undefined {
  if (field.masked === true) return bindingText('maskedField', locale);
  return undefined;
}

function ruleInputOptionNote(field: FieldOption, locale: string = 'zh-CN'): string | undefined {
  return (
    ruleInputDisabledReason(field, locale) ??
    (field.editable === false ? bindingText('readOnlyField', locale) : undefined)
  );
}

function isRuleInputSelectable(field: FieldOption): boolean {
  return isVisibleRuleField(field) && !ruleInputDisabledReason(field);
}

function filterFieldOptions(fields: FieldOption[], query: string): FieldOption[] {
  const normalized = query.trim().toLowerCase();
  if (!normalized) return fields;
  return fields.filter((field) => fieldSearchText(field).includes(normalized));
}

function groupFieldOptions(
  fields: FieldOption[],
  locale: string = 'zh-CN',
): Array<{ label: string; fields: FieldOption[] }> {
  const grouped = new Map<string, FieldOption[]>();
  fields.forEach((field) => {
    const label = fieldGroupLabel(field, locale);
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

function errorMessage(error: unknown, locale: string = 'zh-CN'): string {
  if (error instanceof Error && error.message) return error.message;
  return bindingText('requestFailed', locale);
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

function getDecisionName(
  decisions: DecisionOption[],
  decisionCode: string,
  locale: string = 'zh-CN',
): string {
  const decision = decisions.find((candidate) => candidate.code === decisionCode);
  return decisionDisplayName(decisionCode, decision?.name, locale);
}

function getFieldDisplayName(
  fields: FieldOption[],
  scope: FieldOption['scope'],
  path: string,
  locale: string = 'zh-CN',
): string {
  const field = fields.find((candidate) => candidate.scope === scope && candidate.path === path);
  return (
    field?.label ||
    bindingText('scopeField', locale, {
      scope: bindingPresentation(locale).FIELD_SCOPE_LABELS[scope] || scope,
    })
  );
}

function getFieldContextLabel(
  fields: FieldOption[],
  scope: FieldOption['scope'],
  path: string,
  locale: string = 'zh-CN',
): string {
  const field = fields.find((candidate) => candidate.scope === scope && candidate.path === path);
  return field?.modelName || bindingPresentation(locale).FIELD_SCOPE_LABELS[scope] || scope;
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

function decisionDisplayName(
  decisionCode: string,
  name?: string,
  locale: string = 'zh-CN',
): string {
  const trimmedName = name?.trim() ?? '';
  if (trimmedName && !STALE_DECISION_NAMES.has(trimmedName)) return trimmedName;
  return (
    bindingPresentation(locale).DECISION_NAME_OVERRIDES[decisionCode] ||
    trimmedName ||
    decisionCode ||
    bindingText('noDecisionSelected', locale)
  );
}

function formatInputMapping(
  mapping: InputMapping,
  fields: FieldOption[],
  locale: string = 'zh-CN',
): string {
  return `${getFieldContextLabel(fields, mapping.scope, mapping.path, locale)} · ${getFieldDisplayName(fields, mapping.scope, mapping.path, locale)}`;
}

function formatOutputMapping(mapping: OutputMapping, locale: string = 'zh-CN'): string {
  return `${bindingPresentation(locale).OUTPUT_TARGET_KIND_LABELS[mapping.targetKind]} · ${mapping.targetPath}`;
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

function versionPolicyLabel(policy: DecisionVersionPolicy, locale: string = 'zh-CN'): string {
  return bindingPresentation(locale).VERSION_POLICY_LABELS[policy] ?? policy;
}

function fallbackModeLabel(
  mode: DecisionBindingDraft['fallbackMode'],
  locale: string = 'zh-CN',
): string {
  return bindingPresentation(locale).FALLBACK_MODE_LABELS[mode] ?? mode;
}

function outputTargetKindLabel(kind: OutputTargetKind, locale: string = 'zh-CN'): string {
  return bindingPresentation(locale).OUTPUT_TARGET_KIND_LABELS[kind] ?? kind;
}

function resultStatusLabel(result: DecisionResult, locale: string = 'zh-CN'): string {
  const status = String(result.status ?? '').toUpperCase();
  if (status && bindingPresentation(locale).RESULT_STATUS_LABELS[status])
    return bindingPresentation(locale).RESULT_STATUS_LABELS[status];
  return result.matched ? bindingText('matched', locale) : bindingText('notMatched', locale);
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
  const { locale } = useI18n();

  const decisionName = getDecisionName(decisions, binding.decisionCode, locale);
  const inputCount = binding.inputMappings.length;
  const outputCount = binding.outputMappings.length;

  return (
    <div className="decision-rule-binding-summary" data-testid="decision-binding-summary">
      <div className="decision-rule-summary-head">
        <div>
          <div className="decision-rule-kicker">{bindingText('ruleCenterBinding', locale)}</div>
          <h3>{decisionName}</h3>
          <p title={binding.decisionCode}>
            {bindingText('unifiedPolicy', locale)}
            {versionPolicyLabel(binding.versionPolicy, locale)}
          </p>
        </div>
        <div className="decision-rule-summary-badges">
          <span>{versionPolicyLabel(binding.versionPolicy, locale)}</span>
          <span>{fallbackModeLabel(binding.fallbackMode, locale)}</span>
        </div>
      </div>

      <div className="decision-rule-summary-grid">
        <div>
          <span>{bindingText('inputMappings', locale)}</span>
          <strong>{inputCount}</strong>
        </div>
        <div>
          <span>{bindingText('outputMappings', locale)}</span>
          <strong>{outputCount}</strong>
        </div>
        <div>
          <span>{bindingText('impactReferences', locale)}</span>
          <strong>{impact ? impactCount(impact) : '—'}</strong>
        </div>
        <div>
          <span>{bindingText('publicationRisk', locale)}</span>
          <strong>
            {impact?.risk?.blocking
              ? bindingText('confirmationRequired', locale)
              : impact
                ? bindingText('readyToContinue', locale)
                : bindingText('notLoaded', locale)}
          </strong>
        </div>
      </div>

      <div className="decision-rule-summary-columns">
        <div className="decision-rule-summary-panel">
          <div className="decision-rule-summary-panel-head">
            <strong>{bindingText('inputMappings', locale)}</strong>
            <span>
              {inputCount} {bindingText('entries', locale)}
            </span>
          </div>
          {inputCount === 0 ? (
            <div className="decision-rule-empty" data-testid="decision-binding-empty">
              {bindingText('noInputMappingsConfigured', locale)}
            </div>
          ) : (
            <ul>
              {binding.inputMappings.map((mapping, index) => (
                <li key={`${mapping.input}-${index}`}>
                  <span>{mapping.input}</span>
                  <em title={`${mapping.scope}.${mapping.path}`}>
                    {formatInputMapping(mapping, fields, locale)}
                  </em>
                </li>
              ))}
            </ul>
          )}
        </div>

        <div className="decision-rule-summary-panel">
          <div className="decision-rule-summary-panel-head">
            <strong>{bindingText('outputMappings', locale)}</strong>
            <span>
              {outputCount} {bindingText('entries', locale)}
            </span>
          </div>
          {outputCount === 0 ? (
            <div className="decision-rule-empty" data-testid="decision-output-mapping-empty">
              {bindingText('noOutputMappingsConfigured', locale)}
            </div>
          ) : (
            <ul>
              {binding.outputMappings.map((mapping, index) => (
                <li key={`${mapping.output}-${index}`}>
                  <span title={mapping.output}>
                    {outputFieldLabel(mapping.output, outputFields)}
                  </span>
                  <code>{formatOutputMapping(mapping, locale)}</code>
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>

      {showImpactPreview && (
        <div className="decision-rule-summary-impact" data-testid="decision-impact-preview">
          <div className="decision-rule-summary-panel-head">
            <strong>{bindingText('impactPreview', locale)}</strong>
            <button
              type="button"
              aria-label="refresh-impact"
              disabled={impactLoading}
              onClick={onRefreshImpact}
            >
              {impactLoading
                ? bindingText('loading', locale)
                : bindingText('refreshImpact', locale)}
            </button>
          </div>
          {impactError ? (
            <div className="decision-rule-error" data-testid="decision-impact-error">
              {impactError}
            </div>
          ) : impact ? (
            <div className="decision-rule-impact-summary" data-testid="decision-impact-summary">
              <strong>{impact.risk?.summary ?? bindingText('noImpactSummary', locale)}</strong>
              <span>
                {impactCount(impact)} {bindingText('references', locale)}
              </span>
              <span>
                {impact.risk?.blocking
                  ? bindingText('confirmationRequired', locale)
                  : bindingText('readyToContinue', locale)}
              </span>
            </div>
          ) : (
            <div className="decision-rule-empty" data-testid="decision-impact-empty">
              {bindingText('impactNotLoadedYet', locale)}
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
  const { locale } = useI18n();

  const decisionName = getDecisionName(decisions, binding.decisionCode, locale);

  return (
    <div className="decision-rule-binding-review">
      <div data-testid="decision-binding-preview">
        <div className="decision-rule-review-head">
          <div>
            <strong title={binding.decisionCode}>{decisionName}</strong>
            <span>
              {versionPolicyLabel(binding.versionPolicy, locale)} ·{' '}
              {fallbackModeLabel(binding.fallbackMode, locale)}
            </span>
          </div>
          <div className="decision-rule-review-counts">
            <span>
              {binding.inputMappings.length} {bindingText('inputs', locale)}
            </span>
            <span>
              {binding.outputMappings.length} {bindingText('outputs', locale)}
            </span>
          </div>
        </div>

        <div className="decision-rule-review-grid">
          <section>
            <div className="decision-rule-summary-panel-head">
              <strong>{bindingText('inputMappings', locale)}</strong>
              <span>
                {binding.inputMappings.length} {bindingText('entries', locale)}
              </span>
            </div>
            {binding.inputMappings.length === 0 ? (
              <div className="decision-rule-empty">
                {bindingText('noInputsConfiguredYet', locale)}
              </div>
            ) : (
              <ul>
                {binding.inputMappings.map((mapping, index) => (
                  <li key={`${mapping.input}-${index}`}>
                    <span>{mapping.input}</span>
                    <em title={`${mapping.scope}.${mapping.path}`}>
                      {formatInputMapping(mapping, fields, locale)}
                    </em>
                  </li>
                ))}
              </ul>
            )}
          </section>

          <section>
            <div className="decision-rule-summary-panel-head">
              <strong>{bindingText('outputMappings', locale)}</strong>
              <span>
                {binding.outputMappings.length} {bindingText('entries', locale)}
              </span>
            </div>
            {binding.outputMappings.length === 0 ? (
              <div className="decision-rule-empty">
                {bindingText('noOutputsConfiguredYet', locale)}
              </div>
            ) : (
              <ul>
                {binding.outputMappings.map((mapping, index) => (
                  <li key={`${mapping.output}-${index}`}>
                    <span title={mapping.output}>
                      {outputFieldLabel(mapping.output, outputFields)}
                    </span>
                    <em title={mapping.targetPath}>{formatOutputMapping(mapping, locale)}</em>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </div>
      </div>

      <details className="decision-rule-debug-details">
        <summary>{bindingText('debugDetails', locale)}</summary>
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
  const { locale } = useI18n();

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
        <strong>{resultStatusLabel(result, locale)}</strong>
        {traceHref ? (
          <a data-testid="decision-test-open-trace" href={traceHref}>
            {bindingText('openUnifiedTrace', locale)}
            <span>{result.traceId}</span>
          </a>
        ) : (
          <span>{bindingText('noTrace', locale)}</span>
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
        <div className="decision-rule-empty">{bindingText('noOutputs', locale)}</div>
      )}
      {unknownReasons.length > 0 && (
        <div className="decision-rule-test-unknown" data-testid="decision-test-unknown-reasons">
          <strong>{bindingText('unknownReasons', locale)}</strong>
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
  const { locale } = useI18n();

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
  const groupedFields = useMemo(
    () => groupFieldOptions(visibleFields, locale),
    [visibleFields, locale],
  );

  return (
    <div className="decision-rule-field-picker">
      <label>
        {bindingText('fieldSearch', locale)}
        <input
          aria-label={searchAriaLabel}
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder={bindingText('searchFieldsModelsOrPaths', locale)}
        />
      </label>
      <div className="decision-rule-field-picker-row">
        <select
          aria-label={selectAriaLabel}
          value={value}
          onChange={(event) => onChange(event.target.value)}
        >
          {groupedFields.length === 0 ? (
            <option value="">{bindingText('noMatchingFields', locale)}</option>
          ) : (
            groupedFields.map((fieldGroup) => (
              <optgroup key={fieldGroup.label} label={fieldGroup.label}>
                {fieldGroup.fields.map((field) => (
                  <option
                    key={fieldKey(field)}
                    value={fieldKey(field)}
                    disabled={Boolean(ruleInputDisabledReason(field, locale))}
                  >
                    {field.label}
                    {ruleInputOptionNote(field, locale)
                      ? ` · ${ruleInputOptionNote(field, locale)}`
                      : ''}
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
  const { locale } = useI18n();

  const [query, setQuery] = useState('');
  const fieldMap = useMemo(() => {
    const map = new Map<string, FieldOption>();
    fields.forEach((field) => map.set(fieldKey(field), field));
    return map;
  }, [fields]);
  const matchedFields = useMemo(() => filterFieldOptions(fields, query), [fields, query]);
  const groupedFields = useMemo(
    () => groupFieldOptions(matchedFields, locale),
    [matchedFields, locale],
  );

  return (
    <div className="decision-rule-field-picker decision-rule-target-suggestion">
      <label>
        {bindingText('targetFieldSuggestions', locale)}
        <input
          aria-label={searchAriaLabel}
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder={bindingText('searchFieldsToFillTheTargetPath', locale)}
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
          <option value="">{bindingText('selectAFieldToFillThePath', locale)}</option>
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
  const { locale } = useI18n();

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
        {bindingText('ruleOutputs', locale)}
        <input
          aria-label={searchAriaLabel}
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder={bindingText('searchOutputVariables', locale)}
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
          <option value="">{bindingText('selectADMNOutput', locale)}</option>
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
  const { locale } = useI18n();

  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const context = useMemo(() => parseContextObject(contextJson), [contextJson]);
  const matchedFields = useMemo(() => filterFieldOptions(fields, query), [fields, query]);
  const groupedFields = useMemo(
    () => groupFieldOptions(matchedFields, locale),
    [matchedFields, locale],
  );

  return (
    <div className="decision-rule-context-shell">
      <div className="decision-rule-context-summary" data-testid="decision-test-context-summary">
        <div>
          <strong>{bindingText('testContext', locale)}</strong>
          <span>
            {fields.length} {bindingText('fields', locale)}
          </span>
        </div>
        <button
          type="button"
          aria-label="open-test-context-drawer"
          aria-expanded={open}
          onClick={() => setOpen((current) => !current)}
        >
          {open ? bindingText('collapseContext', locale) : bindingText('editContext', locale)}
        </button>
      </div>

      {open && (
        <div className="decision-rule-context-drawer" data-testid="decision-test-context-drawer">
          <div className="decision-rule-context-tools">
            <label>
              {bindingText('fieldSearch', locale)}
              <input
                aria-label="test-context-field-search"
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder={bindingText('searchFieldsModelsOrPaths', locale)}
              />
            </label>
            <span data-testid="test-context-field-count">
              {matchedFields.length} / {fields.length}
            </span>
          </div>

          {matchedFields.length === 0 ? (
            <div className="decision-rule-empty">
              {bindingText('noMatchingContextFields', locale)}
            </div>
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
        <summary>{bindingText('advancedJSON', locale)}</summary>
        <label className="decision-rule-context-editor">
          {bindingText('testContextJSON', locale)}
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
  const { locale } = useI18n();

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
  const [definitionDecisionOptions, setDefinitionDecisionOptions] = useState<DecisionOption[]>([]);
  const fallbackFields =
    configuredFields ?? (fieldCatalogModelCode ? [] : bindingPresentation(locale).DEFAULT_FIELDS);
  const baseFields =
    fieldCatalogMode === 'merge'
      ? mergeFieldOptions(catalogFields, fallbackFields)
      : (configuredFields ??
        mergeFieldOptions(catalogFields, bindingPresentation(locale).DEFAULT_FIELDS));
  const decisions = useMemo(
    () => mergeDecisionOptions([...(props.decisions ?? []), ...definitionDecisionOptions], locale),
    [definitionDecisionOptions, props.decisions, locale],
  );
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
  const [impactFailure, setImpactFailure] = useState<{
    key?: BindingTextKey;
    cause?: unknown;
  } | null>(null);
  const impactError = impactFailure
    ? impactFailure.key
      ? bindingText(impactFailure.key, locale)
      : errorMessage(impactFailure.cause, locale)
    : '';
  const [contextJson, setContextJson] = useState(() =>
    buildInitialContextJson(runtime, props.initialContextJson),
  );
  const [testResult, setTestResult] = useState<DecisionResult | null>(null);
  const [testRunning, setTestRunning] = useState(false);
  const [testFailure, setTestFailure] = useState<{ key?: BindingTextKey; cause?: unknown } | null>(
    null,
  );
  const testError = testFailure
    ? testFailure.key
      ? bindingText(testFailure.key, locale)
      : errorMessage(testFailure.cause, locale)
    : '';
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
  const canAddInputMapping = ruleInputFields.length > 0;
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
      label: bindingText('condition', locale),
      meta: bindingText('entryCount', locale, { count: condition.children.length }),
    });
  }
  if (showDecision) {
    workspaceTabs.push({
      key: 'decision',
      label: readOnly ? bindingText('summary', locale) : bindingText('decision', locale),
      meta: versionPolicyLabel(binding.versionPolicy, locale),
    });
  }
  if (showStandaloneImpactPreview) {
    workspaceTabs.push({
      key: 'impact',
      label: bindingText('impact', locale),
      meta: impactError
        ? bindingText('error', locale)
        : impact
          ? bindingText('referenceCount', locale, { count: impactCount(impact) })
          : bindingText('refreshPending', locale),
    });
  }
  if (showStandaloneTestRunner) {
    workspaceTabs.push({
      key: 'test',
      label: bindingText('test', locale),
      meta: testError
        ? bindingText('error', locale)
        : testResult
          ? resultStatusLabel(testResult, locale)
          : bindingText('notRun', locale),
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
    let cancelled = false;
    const loadDecisionDefinitions = async () => {
      const decisionApi = getDecisionApi();
      if (typeof decisionApi.listDefinitions !== 'function') {
        return [];
      }
      return decisionDefinitionsToOptions(
        await decisionApi.listDefinitions({ page: 1, size: 100 }),
      );
    };
    loadDecisionDefinitions()
      .then((nextOptions) => {
        if (!cancelled) setDefinitionDecisionOptions(nextOptions);
      })
      .catch(() => {
        if (!cancelled) setDefinitionDecisionOptions([]);
      });
    return () => {
      cancelled = true;
    };
  }, [getDecisionApi]);

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
    if (!field || ruleInputDisabledReason(field, locale)) return;
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
      setImpactFailure({ key: 'selectADecision' });
      return;
    }
    setImpactLoading(true);
    setImpactFailure(null);
    try {
      setImpact(await getDecisionApi().getDecisionImpact(binding.decisionCode));
    } catch (error) {
      setImpactFailure({ cause: error });
    } finally {
      setImpactLoading(false);
    }
  };

  const runDecisionTest = async () => {
    if (!binding.decisionCode) {
      setTestFailure({ key: 'selectADecision' });
      return;
    }
    setTestRunning(true);
    setTestFailure(null);
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
      setTestFailure({ cause: error });
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
              <strong>{bindingText('condition', locale)}</strong>
              <span>
                {condition.children.length} {bindingText('entries', locale)}
              </span>
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
                <strong>{bindingText('referenceRuleCenter', locale)}</strong>
                <span>{versionPolicyLabel(binding.versionPolicy, locale)}</span>
              </div>

              <div className="decision-rule-binding-grid">
                <label>
                  {bindingText('decision', locale)}
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
                        {decisionDisplayName(decision.code, decision.name, locale)}
                      </option>
                    ))}
                  </select>
                </label>

                <label>
                  {bindingText('versionPolicy', locale)}
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
                        {versionPolicyLabel(policy, locale)}
                      </option>
                    ))}
                  </select>
                </label>

                <label>
                  {bindingText('failurePolicy', locale)}
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
                    <option value="FAIL_CLOSED">{fallbackModeLabel('FAIL_CLOSED', locale)}</option>
                    <option value="FAIL_OPEN">{fallbackModeLabel('FAIL_OPEN', locale)}</option>
                    <option value="DEFAULT_VALUE">
                      {fallbackModeLabel('DEFAULT_VALUE', locale)}
                    </option>
                  </select>
                </label>
              </div>

              <div className="decision-rule-mapping-header">
                <strong>{bindingText('inputMappings', locale)}</strong>
                <button
                  type="button"
                  onClick={addInputMapping}
                  disabled={!canAddInputMapping}
                  title={
                    canAddInputMapping ? undefined : bindingText('noMappableInputFields', locale)
                  }
                >
                  {bindingText('addMapping', locale)}
                </button>
              </div>

              {binding.inputMappings.length === 0 && (
                <div className="decision-rule-empty" data-testid="decision-binding-empty">
                  {canAddInputMapping
                    ? bindingText('noInputMappingsYet', locale)
                    : bindingText('noMappableInputFields', locale)}
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
                    {bindingText('delete', locale)}
                  </button>
                </div>
              ))}

              <div className="decision-rule-mapping-header">
                <strong>{bindingText('outputMappings', locale)}</strong>
                <button type="button" onClick={addOutputMapping}>
                  {bindingText('addOutput', locale)}
                </button>
              </div>

              {binding.outputMappings.length === 0 && (
                <div className="decision-rule-empty" data-testid="decision-output-mapping-empty">
                  {bindingText('noOutputMappingsYet', locale)}
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
                        {outputTargetKindLabel(kind, locale)}
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
                    {bindingText('delete', locale)}
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
                  <strong>{bindingText('impactPreview', locale)}</strong>
                  <button
                    type="button"
                    aria-label="refresh-impact"
                    disabled={impactLoading}
                    onClick={refreshImpact}
                  >
                    {impactLoading
                      ? bindingText('loading', locale)
                      : bindingText('refresh', locale)}
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
                    <strong>
                      {impact.risk?.summary ?? bindingText('noImpactSummary', locale)}
                    </strong>
                    <span>
                      {impactCount(impact)} {bindingText('references', locale)}
                    </span>
                    <span>
                      {impact.risk?.blocking
                        ? bindingText('confirmationRequired', locale)
                        : bindingText('readyToContinue', locale)}
                    </span>
                  </div>
                ) : (
                  <div className="decision-rule-empty" data-testid="decision-impact-empty">
                    {bindingText('impactNotLoadedYet', locale)}
                  </div>
                )}
              </div>
            </div>
          )}

          {showStandaloneTestRunner && (
            <div {...panelAttrs('test')}>
              <div className="decision-rule-binding-section" data-testid="decision-test-runner">
                <div className="decision-rule-binding-heading">
                  <strong>{bindingText('testRun', locale)}</strong>
                  <button
                    type="button"
                    aria-label="run-decision-test"
                    disabled={testRunning}
                    onClick={runDecisionTest}
                  >
                    {testRunning ? bindingText('running', locale) : bindingText('run', locale)}
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
                    {bindingText('notRunYet', locale)}
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
