import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ConditionBuilder, type FieldOption } from '~/shared/decision/ui/ConditionBuilder';
import { group, type GroupNode } from '~/shared/decision/ast/conditionAst';
import { getApiService } from '~/shared/services/ApiService';
import {
  createDecisionApi,
  type DecisionImpact,
  type DecisionModelField,
  type DecisionResult,
  type HttpClient,
  type ScopedContext,
} from '~/shared/decision/api/decisionApi';

export type DecisionVersionPolicy = 'LATEST_PUBLISHED' | 'FIXED_VERSION' | 'VERSION_TAG' | 'ROLLOUT';

export interface DecisionOption {
  code: string;
  name?: string;
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
  };
}

interface RuleBindingDecisionApi {
  getDecisionImpact: (decisionCode: string) => Promise<DecisionImpact>;
  evaluate: (request: {
    decisionCode: string;
    binding?: 'LATEST' | 'FIXED_VERSION' | 'VERSION_TAG' | 'ROLLOUT';
    callerType?: string;
    callerRef?: string;
    routingKey?: string;
    context: ScopedContext;
  }) => Promise<DecisionResult>;
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
      consumerNodeId?: string;
      readOnly?: boolean;
      variant?: 'editor' | 'summary';
      showImpactPreview?: boolean;
      showTestRunner?: boolean;
      initialContextJson?: string;
      fields?: FieldOption[];
      fieldCatalogMode?: 'disabled' | 'fallback' | 'merge';
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
];

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

const SUPPORTED_SCOPES = new Set<FieldOption['scope']>([
  'meta',
  'event',
  'record',
  'before',
  'after',
  'process',
  'task',
  'sla',
  'actor',
  'tenant',
  'time',
  'env',
]);

const SUPPORTED_DATA_TYPES = new Set<FieldOption['dataType']>([
  'string',
  'text',
  'integer',
  'decimal',
  'boolean',
  'date',
  'time',
  'datetime',
  'duration',
  'enum',
  'dict',
  'user',
  'role',
  'group',
  'department',
  'collection',
  'object',
]);

function fieldKey(field: Pick<FieldOption, 'scope' | 'path'>): string {
  return `${field.scope}:${field.path}`;
}

function toFieldOption(field: DecisionModelField): FieldOption | null {
  const scope = String(field.entityCode ?? 'record') as FieldOption['scope'];
  const path = String(field.path ?? '');
  if (!SUPPORTED_SCOPES.has(scope) || !path) return null;
  const dataType = String(field.dataType ?? 'object').toLowerCase() as FieldOption['dataType'];
  return {
    scope,
    path,
    label: field.label || `${scope}.${path}`,
    dataType: SUPPORTED_DATA_TYPES.has(dataType) ? dataType : 'object',
  };
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

function defaultCondition(): GroupNode {
  return group('AND', []);
}

function defaultDecisionApi(): RuleBindingDecisionApi {
  const service = getApiService();
  const http: HttpClient = {
    get: <T,>(endpoint: string, params?: Record<string, unknown>) => service.get<T>(endpoint, params),
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
      return parsed && typeof parsed === 'object' ? (parsed as RuleConsumerBindingDraft) : undefined;
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

function buildInitialContextJson(runtime?: RuleBindingRuntime, initialContextJson?: string): string {
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

function impactCount(impact: DecisionImpact | null): number {
  return (impact?.incoming?.length ?? 0) + (impact?.outgoing?.length ?? 0);
}

function getDecisionName(decisions: DecisionOption[], decisionCode: string): string {
  const decision = decisions.find((candidate) => candidate.code === decisionCode);
  return decision?.name || decisionCode || '未选择决策';
}

function formatInputMapping(mapping: InputMapping): string {
  return `${mapping.scope}.${mapping.path}`;
}

function formatOutputMapping(mapping: OutputMapping): string {
  return `${mapping.targetKind}.${mapping.targetPath}`;
}

function DecisionBindingSummary({
  binding,
  decisions,
  showImpactPreview,
  impact,
  impactLoading,
  impactError,
  onRefreshImpact,
}: {
  binding: DecisionBindingDraft;
  decisions: DecisionOption[];
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
          <p>{binding.decisionCode}</p>
        </div>
        <div className="decision-rule-summary-badges">
          <span>{binding.versionPolicy}</span>
          <span>{binding.fallbackMode}</span>
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
                  <code>{formatInputMapping(mapping)}</code>
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
                  <span>{mapping.output}</span>
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
  const readOnly = props.readOnly === true || props.variant === 'summary';
  const [catalogFields, setCatalogFields] = useState<FieldOption[]>([]);
  const fields =
    fieldCatalogMode === 'merge'
      ? mergeFieldOptions(catalogFields, configuredFields ?? DEFAULT_FIELDS)
      : configuredFields ?? mergeFieldOptions(catalogFields, DEFAULT_FIELDS);
  const decisions =
    props.decisions && props.decisions.length > 0 ? props.decisions : DEFAULT_DECISIONS;
  const defaultApiRef = useRef<RuleBindingDecisionApi | null>(null);
  const incomingRawBindingValue =
    value ??
    props.value ??
    (props.valueField ? runtime?.getFieldValue?.(props.valueField) : undefined) ??
    props.initialValue;
  const initialRuleBinding = parseBindingValue(incomingRawBindingValue);
  const [condition, setCondition] = useState<GroupNode>(() =>
    initialRuleBinding?.conditionSpec?.root ?? defaultCondition(),
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

  const fieldByKey = useMemo(() => {
    const map = new Map<string, FieldOption>();
    fields.forEach((field) => map.set(fieldKey(field), field));
    return map;
  }, [fields]);

  const showCondition = mode === 'condition' || mode === 'combined';
  const showDecision = mode === 'decision' || mode === 'combined';
  const showImpactPreview = showDecision && props.showImpactPreview !== false;
  const showTestRunner = showDecision && props.showTestRunner !== false;

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
    getDecisionApi()
      .getModelFields?.()
      .then((rows) => {
        if (cancelled) return;
        setCatalogFields(
          rows.map(toFieldOption).filter((field): field is FieldOption => Boolean(field)),
        );
      })
      .catch(() => {
        if (!cancelled) setCatalogFields([]);
      });
    return () => {
      cancelled = true;
    };
  }, [configuredFields, fieldCatalogMode, getDecisionApi]);

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
  }, [
    decisions,
    incomingRawBindingValue,
    props.initialDecisionCode,
    props.initialVersionPolicy,
  ]);

  const emitChange = (nextCondition: GroupNode, nextBinding: DecisionBindingDraft) => {
    const nextValue = buildRuleConsumerBinding(nextCondition, nextBinding, {
      showCondition,
      showDecision,
      consumerType: props.consumerType ?? initialRuleBinding?.consumerType,
      consumerCode: props.consumerCode ?? initialRuleBinding?.consumerCode,
      consumerNodeId: props.consumerNodeId ?? initialRuleBinding?.consumerNodeId,
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
    const first = fields[0];
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
    if (!field) return;
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
      const next = {
        ...current,
        outputMappings: [
          ...current.outputMappings,
          {
            output: `output${current.outputMappings.length + 1}`,
            targetKind: 'ACTION_PARAM' as const,
            targetPath: `result.output${current.outputMappings.length + 1}`,
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
        callerType: props.consumerType ?? 'RULE_BINDING_PREVIEW',
        callerRef: props.consumerCode,
        context: parseContextJson(contextJson),
      });
      setTestResult(result);
    } catch (error) {
      setTestError(errorMessage(error));
    } finally {
      setTestRunning(false);
    }
  };

  return (
    <section className="decision-rule-binding-block" data-testid="decision-rule-binding-block">
      {showCondition && (
        <div className="decision-rule-binding-section">
          <div className="decision-rule-binding-heading">
            <strong>条件</strong>
            <span>{condition.children.length} 条</span>
          </div>
          <ConditionBuilder value={condition} fields={fields} onChange={updateCondition} />
        </div>
      )}

      {showDecision && readOnly ? (
        <DecisionBindingSummary
          binding={binding}
          decisions={decisions}
          showImpactPreview={showImpactPreview}
          impact={impact}
          impactLoading={impactLoading}
          impactError={impactError}
          onRefreshImpact={refreshImpact}
        />
      ) : showDecision ? (
        <div className="decision-rule-binding-section" data-testid="decision-binding-editor">
          <div className="decision-rule-binding-heading">
            <strong>引用规则中心</strong>
            <span>{binding.versionPolicy}</span>
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
                  <option key={decision.code} value={decision.code}>
                    {decision.name ? `${decision.name} (${decision.code})` : decision.code}
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
                    {policy}
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
                <option value="FAIL_CLOSED">FAIL_CLOSED</option>
                <option value="FAIL_OPEN">FAIL_OPEN</option>
                <option value="DEFAULT_VALUE">DEFAULT_VALUE</option>
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
              <select
                aria-label={`mapping-field-${index}`}
                value={fieldKey(mapping)}
                onChange={(event) => updateMappingField(index, event.target.value)}
              >
                {fields.map((field) => (
                  <option key={fieldKey(field)} value={fieldKey(field)}>
                    {field.label}
                  </option>
                ))}
              </select>
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
                    {kind}
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
            </div>
          ))}

          <pre className="decision-rule-binding-preview" data-testid="decision-binding-preview">
            {JSON.stringify(
              {
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
              },
              null,
              2,
            )}
          </pre>

          {showImpactPreview && (
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

          {showTestRunner && (
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
              <label className="decision-rule-context-editor">
                Context JSON
                <textarea
                  aria-label="test-run-context"
                  value={contextJson}
                  onChange={(event) => setContextJson(event.target.value)}
                />
              </label>
              {testError ? (
                <div className="decision-rule-error" data-testid="decision-test-error">
                  {testError}
                </div>
              ) : testResult ? (
                <pre className="decision-rule-binding-preview" data-testid="decision-test-result">
                  {JSON.stringify(
                    {
                      status: testResult.status,
                      matched: testResult.matched,
                      traceId: testResult.traceId,
                      outputs: testResult.outputs ?? {},
                    },
                    null,
                    2,
                  )}
                </pre>
              ) : (
                <div className="decision-rule-empty" data-testid="decision-test-empty">
                  尚未运行
                </div>
              )}
            </div>
          )}
        </div>
      ) : null}
    </section>
  );
}

export default DecisionRuleBindingBlock;
