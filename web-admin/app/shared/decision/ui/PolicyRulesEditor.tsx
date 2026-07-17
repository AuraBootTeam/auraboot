import { ConditionBuilder, type FieldOption } from './ConditionBuilder';
import { group, type GroupNode } from '../ast/conditionAst';
import type { RuleConsumerBindingDraft } from '~/ui/smart/decision/DecisionRuleBindingBlock';

/**
 * DecisionOps EventPolicy rules editor (mockup 策略设计器 规则集 / F2-F3, docs/2.md §X.4): edit a
 * policy's matchMode and its rule set — each rule has code/name/priority/enabled + an embedded
 * ConditionBuilder for its condition. Emits the EventPolicy rules shape the backend consumes.
 * (Per-rule action editing + drag-reorder are later slices.)
 */

export type MatchMode = 'FIRST_MATCH' | 'COLLECT_ALL' | 'UNIQUE' | 'PRIORITY_FIRST';

export interface PolicyRuleDraft {
  ruleCode: string;
  ruleName: string;
  priority: number;
  enabled: boolean;
  condition: GroupNode;
  actions?: unknown[];
  decisionBinding?: RuleConsumerBindingDraft['decisionBinding'];
}

export interface PolicyRulesValue {
  matchMode: MatchMode;
  rules: PolicyRuleDraft[];
}

export interface PolicyRulesEditorProps {
  value: PolicyRulesValue;
  fields: FieldOption[];
  onChange: (next: PolicyRulesValue) => void;
}

const MATCH_MODES: MatchMode[] = ['COLLECT_ALL', 'FIRST_MATCH', 'UNIQUE', 'PRIORITY_FIRST'];

export function PolicyRulesEditor({ value, fields, onChange }: PolicyRulesEditorProps) {
  const setRules = (rules: PolicyRuleDraft[]) => onChange({ ...value, rules });

  const updateRule = (idx: number, patch: Partial<PolicyRuleDraft>) => {
    const rules = value.rules.slice();
    rules[idx] = { ...rules[idx], ...patch };
    setRules(rules);
  };

  const addRule = () => {
    const n = value.rules.length + 1;
    setRules([...value.rules, {
      ruleCode: `R-${n}`, ruleName: `规则 ${n}`, priority: n * 100, enabled: true, condition: group('AND', []), actions: [],
    }]);
  };

  const deleteRule = (idx: number) => setRules(value.rules.filter((_, i) => i !== idx));

  return (
    <div data-testid="policy-rules-editor">
      <div className="pre-header">
        <label htmlFor="pre-mm">匹配模式</label>
        <select id="pre-mm" aria-label="match-mode" value={value.matchMode}
          onChange={(e) => onChange({ ...value, matchMode: e.target.value as MatchMode })}>
          {MATCH_MODES.map((m) => <option key={m} value={m}>{m}</option>)}
        </select>
      </div>

      {value.rules.length === 0 && <div data-testid="pre-empty">暂无规则</div>}

      {value.rules.map((rule, idx) => (
        <div className="pre-rule" data-testid={`pre-rule-${idx}`} key={rule.ruleCode}>
          <div className="pre-rule-head">
            <input aria-label={`rule-name-${idx}`} value={rule.ruleName}
              onChange={(e) => updateRule(idx, { ruleName: e.target.value })} />
            <input aria-label={`rule-priority-${idx}`} type="number" value={rule.priority}
              onChange={(e) => updateRule(idx, { priority: Number(e.target.value) })} />
            <button type="button" aria-label={`rule-enabled-${idx}`} aria-pressed={rule.enabled}
              onClick={() => updateRule(idx, { enabled: !rule.enabled })}>
              {rule.enabled ? '启用' : '停用'}
            </button>
            <button type="button" aria-label={`rule-delete-${idx}`} onClick={() => deleteRule(idx)}>删除</button>
          </div>
          <ConditionBuilder value={rule.condition} fields={fields}
            onChange={(cond) => updateRule(idx, { condition: cond })} />
        </div>
      ))}

      <button type="button" data-testid="pre-add-rule" onClick={addRule}>添加规则</button>
    </div>
  );
}

export default PolicyRulesEditor;
