import { useMemo, useRef, useState } from 'react';
import { ConditionBuilder, type FieldOption } from '~/shared/decision/ui/ConditionBuilder';
import { group, type GroupNode } from '~/shared/decision/ast/conditionAst';
import { getApiService } from '~/shared/services/ApiService';
import {
  createDecisionApi,
  type DecisionImpact,
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

export interface RuleValueSourceDraft {
  kind: 'FIELD' | 'LITERAL';
  scope?: FieldOption['scope'];
  path?: string;
  value?: unknown;
}

export interface DecisionBindingDraft {
  decisionCode: string;
  versionPolicy: DecisionVersionPolicy;
  inputMappings: InputMapping[];
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
    outputMappings: unknown[];
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
}

interface DecisionRuleBindingBlockProps {
  block?: {
    props?: {
      mode?: 'condition' | 'decision' | 'combined';
      valueField?: string;
      value?: RuleConsumerBindingDraft | string;
      initialValue?: RuleConsumerBindingDraft | string;
      consumerType?: string;
      consumerCode?: string;
      consumerNodeId?: string;
      showImpactPreview?: boolean;
      showTestRunner?: boolean;
      initialContextJson?: string;
      fields?: FieldOption[];
      decisions?: DecisionOption[];
      initialDecisionCode?: string;
      initialVersionPolicy?: DecisionVersionPolicy;
    };
  };
  runtime?: RuleBindingRuntime;
  value?: RuleConsumerBindingDraft | string;
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

function fieldKey(field: Pick<FieldOption, 'scope' | 'path'>): string {
  return `${field.scope}:${field.path}`;
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
  return typeof raw === 'object' ? (raw as RuleConsumerBindingDraft) : undefined;
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
          outputMappings: [],
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

export function DecisionRuleBindingBlock({
  block,
  runtime,
  value,
  onChange,
  api,
}: DecisionRuleBindingBlockProps) {
  const props = block?.props ?? {};
  const mode = props.mode ?? 'combined';
  const fields = props.fields && props.fields.length > 0 ? props.fields : DEFAULT_FIELDS;
  const decisions =
    props.decisions && props.decisions.length > 0 ? props.decisions : DEFAULT_DECISIONS;
  const defaultApiRef = useRef<RuleBindingDecisionApi | null>(null);
  const initialRuleBinding = parseBindingValue(
    value ??
      props.value ??
      (props.valueField ? runtime?.getFieldValue?.(props.valueField) : undefined) ??
      props.initialValue,
  );
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

  const fieldByKey = useMemo(() => {
    const map = new Map<string, FieldOption>();
    fields.forEach((field) => map.set(fieldKey(field), field));
    return map;
  }, [fields]);

  const showCondition = mode === 'condition' || mode === 'combined';
  const showDecision = mode === 'decision' || mode === 'combined';
  const showImpactPreview = showDecision && props.showImpactPreview !== false;
  const showTestRunner = showDecision && props.showTestRunner !== false;

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

  const updateCondition = (nextCondition: GroupNode) => {
    setCondition(nextCondition);
    emitChange(nextCondition, binding);
  };

  const getDecisionApi = () => {
    if (api) return api;
    if (!defaultApiRef.current) {
      defaultApiRef.current = defaultDecisionApi();
    }
    return defaultApiRef.current;
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

      {showDecision && (
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
      )}
    </section>
  );
}

export default DecisionRuleBindingBlock;
