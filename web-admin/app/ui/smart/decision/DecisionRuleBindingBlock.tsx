import { useMemo, useState } from 'react';
import { ConditionBuilder, type FieldOption } from '~/shared/decision/ui/ConditionBuilder';
import { group, type GroupNode } from '~/shared/decision/ast/conditionAst';

type DecisionVersionPolicy = 'LATEST_PUBLISHED' | 'FIXED_VERSION' | 'VERSION_TAG' | 'ROLLOUT';

interface DecisionOption {
  code: string;
  name?: string;
}

interface InputMapping {
  input: string;
  scope: FieldOption['scope'];
  path: string;
}

interface DecisionBindingDraft {
  decisionCode: string;
  versionPolicy: DecisionVersionPolicy;
  inputMappings: InputMapping[];
  fallbackMode: 'FAIL_CLOSED' | 'FAIL_OPEN' | 'DEFAULT_VALUE';
}

interface DecisionRuleBindingBlockProps {
  block?: {
    props?: {
      mode?: 'condition' | 'decision' | 'combined';
      fields?: FieldOption[];
      decisions?: DecisionOption[];
      initialDecisionCode?: string;
      initialVersionPolicy?: DecisionVersionPolicy;
    };
  };
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

function buildInitialBinding(
  decisions: DecisionOption[],
  initialDecisionCode?: string,
  initialVersionPolicy?: DecisionVersionPolicy,
): DecisionBindingDraft {
  return {
    decisionCode: initialDecisionCode || decisions[0]?.code || '',
    versionPolicy: initialVersionPolicy || 'LATEST_PUBLISHED',
    inputMappings: [],
    fallbackMode: 'FAIL_CLOSED',
  };
}

export function DecisionRuleBindingBlock({ block }: DecisionRuleBindingBlockProps) {
  const props = block?.props ?? {};
  const mode = props.mode ?? 'combined';
  const fields = props.fields && props.fields.length > 0 ? props.fields : DEFAULT_FIELDS;
  const decisions =
    props.decisions && props.decisions.length > 0 ? props.decisions : DEFAULT_DECISIONS;
  const [condition, setCondition] = useState<GroupNode>(() => defaultCondition());
  const [binding, setBinding] = useState<DecisionBindingDraft>(() =>
    buildInitialBinding(decisions, props.initialDecisionCode, props.initialVersionPolicy),
  );

  const fieldByKey = useMemo(() => {
    const map = new Map<string, FieldOption>();
    fields.forEach((field) => map.set(fieldKey(field), field));
    return map;
  }, [fields]);

  const addInputMapping = () => {
    const first = fields[0];
    if (!first) return;
    setBinding((current) => ({
      ...current,
      inputMappings: [
        ...current.inputMappings,
        { input: `input${current.inputMappings.length + 1}`, scope: first.scope, path: first.path },
      ],
    }));
  };

  const updateMapping = (index: number, patch: Partial<InputMapping>) => {
    setBinding((current) => {
      const next = current.inputMappings.slice();
      next[index] = { ...next[index], ...patch };
      return { ...current, inputMappings: next };
    });
  };

  const updateMappingField = (index: number, key: string) => {
    const field = fieldByKey.get(key);
    if (!field) return;
    updateMapping(index, { scope: field.scope, path: field.path });
  };

  const removeMapping = (index: number) => {
    setBinding((current) => ({
      ...current,
      inputMappings: current.inputMappings.filter((_, i) => i !== index),
    }));
  };

  const showCondition = mode === 'condition' || mode === 'combined';
  const showDecision = mode === 'decision' || mode === 'combined';

  return (
    <section className="decision-rule-binding-block" data-testid="decision-rule-binding-block">
      {showCondition && (
        <div className="decision-rule-binding-section">
          <div className="decision-rule-binding-heading">
            <strong>条件</strong>
            <span>{condition.children.length} 条</span>
          </div>
          <ConditionBuilder value={condition} fields={fields} onChange={setCondition} />
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
                  setBinding((current) => ({
                    ...current,
                    decisionCode: event.target.value,
                  }))
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
                  setBinding((current) => ({
                    ...current,
                    versionPolicy: event.target.value as DecisionVersionPolicy,
                  }))
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
                  setBinding((current) => ({
                    ...current,
                    fallbackMode: event.target.value as DecisionBindingDraft['fallbackMode'],
                  }))
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
                    source: { kind: 'field', scope: mapping.scope, path: mapping.path },
                  })),
                  fallbackPolicy: { mode: binding.fallbackMode },
                },
              },
              null,
              2,
            )}
          </pre>
        </div>
      )}
    </section>
  );
}

export default DecisionRuleBindingBlock;
