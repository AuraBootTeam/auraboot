import { type DecisionTable, type TableRule, type HitPolicy } from '../table/decisionTable';
import { type Operator } from '../ast/conditionAst';

/**
 * DecisionOps Decision Table editor (docs/1.md §15): edit the hit policy and the rule rows
 * (per-input operator+value cells, per-output value cells), add/remove rows. Emits the backend
 * DecisionTable JSON; backend stays authoritative. Inputs/outputs schema is supplied (column edit
 * is a later slice). Cell operators are a small numeric/equality set common to tables.
 */

export interface DecisionTableEditorProps {
  value: DecisionTable;
  onChange: (next: DecisionTable) => void;
}

const CELL_OPERATORS: Operator[] = ['EQ', 'NE', 'GT', 'GTE', 'LT', 'LTE', 'IN', 'BETWEEN'];

export function DecisionTableEditor({ value, onChange }: DecisionTableEditorProps) {
  const emitRules = (rules: TableRule[]) => onChange({ ...value, rules });

  const addRule = () => {
    const ruleId = `row-${value.rules.length + 1}-${Date.now()}`;
    emitRules([...value.rules, { ruleId, priority: (value.rules.length + 1) * 10, when: {}, then: {} }]);
  };

  const deleteRule = (idx: number) => emitRules(value.rules.filter((_, i) => i !== idx));

  const setCellOperator = (idx: number, inputId: string, op: Operator) => {
    const rules = value.rules.slice();
    const r = { ...rules[idx], when: { ...rules[idx].when } };
    r.when[inputId] = { operator: op, value: r.when[inputId]?.value ?? '' };
    rules[idx] = r;
    emitRules(rules);
  };

  const setCellValue = (idx: number, inputId: string, val: string) => {
    const rules = value.rules.slice();
    const r = { ...rules[idx], when: { ...rules[idx].when } };
    r.when[inputId] = { operator: r.when[inputId]?.operator ?? 'EQ', value: val };
    rules[idx] = r;
    emitRules(rules);
  };

  const setOutputValue = (idx: number, outputId: string, val: string) => {
    const rules = value.rules.slice();
    rules[idx] = { ...rules[idx], then: { ...rules[idx].then, [outputId]: val } };
    emitRules(rules);
  };

  return (
    <div data-testid="decision-table-editor">
      <div className="dt-hitpolicy">
        <label htmlFor="dt-hp">命中策略</label>
        <select
          id="dt-hp"
          aria-label="hit-policy"
          value={value.hitPolicy}
          onChange={(e) => onChange({ ...value, hitPolicy: e.target.value as HitPolicy })}
        >
          <option value="FIRST">FIRST</option>
          <option value="UNIQUE">UNIQUE</option>
        </select>
      </div>

      <table className="dt-grid">
        <thead>
          <tr>
            {value.inputs.map((i) => <th key={i.id} data-testid={`dt-in-${i.id}`}>{i.label}</th>)}
            {value.outputs.map((o) => <th key={o.id} data-testid={`dt-out-${o.id}`}>{o.label}</th>)}
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          {value.rules.length === 0 && (
            <tr><td data-testid="dt-empty" colSpan={value.inputs.length + value.outputs.length + 1}>暂无规则行</td></tr>
          )}
          {value.rules.map((rule, idx) => (
            <tr key={rule.ruleId} data-testid={`dt-row-${idx}`}>
              {value.inputs.map((input) => (
                <td key={input.id}>
                  <select
                    aria-label={`op-${idx}-${input.id}`}
                    value={rule.when[input.id]?.operator ?? 'EQ'}
                    onChange={(e) => setCellOperator(idx, input.id, e.target.value as Operator)}
                  >
                    {CELL_OPERATORS.map((op) => <option key={op} value={op}>{op}</option>)}
                  </select>
                  <input
                    aria-label={`val-${idx}-${input.id}`}
                    value={String(rule.when[input.id]?.value ?? '')}
                    onChange={(e) => setCellValue(idx, input.id, e.target.value)}
                  />
                </td>
              ))}
              {value.outputs.map((output) => (
                <td key={output.id}>
                  <input
                    aria-label={`out-${idx}-${output.id}`}
                    value={String(rule.then[output.id] ?? '')}
                    onChange={(e) => setOutputValue(idx, output.id, e.target.value)}
                  />
                </td>
              ))}
              <td>
                <button type="button" aria-label={`delete-row-${idx}`} onClick={() => deleteRule(idx)}>删除</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      <button type="button" data-testid="dt-add-rule" onClick={addRule}>添加规则行</button>
    </div>
  );
}

export default DecisionTableEditor;
