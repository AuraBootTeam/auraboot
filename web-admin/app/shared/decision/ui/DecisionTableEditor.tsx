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
    emitRules([
      ...value.rules,
      { ruleId, priority: (value.rules.length + 1) * 10, when: {}, then: {} },
    ]);
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
    <div className="space-y-4" data-testid="decision-table-editor">
      <div className="flex flex-wrap items-center gap-3">
        <label className="text-sm font-medium text-slate-700" htmlFor="dt-hp">
          命中策略
        </label>
        <select
          id="dt-hp"
          aria-label="hit-policy"
          className="h-9 rounded-md border border-slate-200 bg-white px-3 text-sm text-slate-800 focus:border-blue-500 focus:ring-1 focus:ring-blue-500 focus:outline-none"
          value={value.hitPolicy}
          onChange={(e) => onChange({ ...value, hitPolicy: e.target.value as HitPolicy })}
        >
          <option value="FIRST">FIRST</option>
          <option value="UNIQUE">UNIQUE</option>
        </select>
      </div>

      <table className="min-w-full divide-y divide-slate-200 text-sm">
        <thead className="bg-slate-50 text-left text-xs font-semibold text-slate-500 uppercase">
          <tr>
            {value.inputs.map((i) => (
              <th className="px-3 py-2" key={i.id} data-testid={`dt-in-${i.id}`}>
                {i.label}
              </th>
            ))}
            {value.outputs.map((o) => (
              <th className="px-3 py-2" key={o.id} data-testid={`dt-out-${o.id}`}>
                {o.label}
              </th>
            ))}
            <th className="px-3 py-2">操作</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100 bg-white">
          {value.rules.length === 0 && (
            <tr>
              <td
                className="px-3 py-8 text-center text-sm text-slate-500"
                data-testid="dt-empty"
                colSpan={value.inputs.length + value.outputs.length + 1}
              >
                暂无规则行
              </td>
            </tr>
          )}
          {value.rules.map((rule, idx) => (
            <tr key={rule.ruleId} data-testid={`dt-row-${idx}`}>
              {value.inputs.map((input) => (
                <td className="min-w-48 px-3 py-2 align-top" key={input.id}>
                  <select
                    aria-label={`op-${idx}-${input.id}`}
                    className="mb-2 h-8 w-full rounded-md border border-slate-200 bg-white px-2 text-xs text-slate-700 focus:border-blue-500 focus:ring-1 focus:ring-blue-500 focus:outline-none"
                    value={rule.when[input.id]?.operator ?? 'EQ'}
                    onChange={(e) => setCellOperator(idx, input.id, e.target.value as Operator)}
                  >
                    {CELL_OPERATORS.map((op) => (
                      <option key={op} value={op}>
                        {op}
                      </option>
                    ))}
                  </select>
                  <input
                    aria-label={`val-${idx}-${input.id}`}
                    className="h-8 w-full rounded-md border border-slate-200 px-2 text-sm text-slate-800 focus:border-blue-500 focus:ring-1 focus:ring-blue-500 focus:outline-none"
                    value={String(rule.when[input.id]?.value ?? '')}
                    onChange={(e) => setCellValue(idx, input.id, e.target.value)}
                  />
                </td>
              ))}
              {value.outputs.map((output) => (
                <td className="min-w-40 px-3 py-2 align-top" key={output.id}>
                  <input
                    aria-label={`out-${idx}-${output.id}`}
                    className="h-8 w-full rounded-md border border-slate-200 px-2 text-sm text-slate-800 focus:border-blue-500 focus:ring-1 focus:ring-blue-500 focus:outline-none"
                    value={String(rule.then[output.id] ?? '')}
                    onChange={(e) => setOutputValue(idx, output.id, e.target.value)}
                  />
                </td>
              ))}
              <td className="px-3 py-2 align-top">
                <button
                  type="button"
                  className="rounded-md border border-red-200 px-3 py-1.5 text-sm font-medium text-red-700 hover:bg-red-50"
                  aria-label={`delete-row-${idx}`}
                  onClick={() => deleteRule(idx)}
                >
                  删除
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      <button
        type="button"
        className="inline-flex items-center rounded-md border border-slate-200 bg-white px-3 py-1.5 text-sm font-medium text-slate-700 hover:bg-slate-50"
        data-testid="dt-add-rule"
        onClick={addRule}
      >
        添加规则行
      </button>
    </div>
  );
}

export default DecisionTableEditor;
