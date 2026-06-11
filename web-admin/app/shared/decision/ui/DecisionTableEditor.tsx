import {
  type DecisionTable, type TableRule, type HitPolicy, type TableAggregation,
  type TableInput, type TableOutput,
} from '../table/decisionTable';
import { type DataType, type Operator, type Scope } from '../ast/conditionAst';
import type { DecisionTableAnalysis, DecisionTableAnalysisIssue } from '../api/decisionApi';

export interface DecisionTableEditorProps {
  value: DecisionTable;
  onChange: (next: DecisionTable) => void;
  analysis?: DecisionTableAnalysis | null;
  analyzing?: boolean;
  analysisError?: string | null;
  onAnalyze?: () => void | Promise<void>;
  dmnXml?: string;
  dmnBusy?: boolean;
  dmnError?: string | null;
  dmnStatus?: string | null;
  onDmnXmlChange?: (xml: string) => void;
  onExportDmnXml?: () => void | Promise<void>;
  onImportDmnXml?: () => void | Promise<void>;
  onRoundTripDmnXml?: () => void | Promise<void>;
}

const CELL_OPERATORS: Operator[] = ['EQ', 'NE', 'GT', 'GTE', 'LT', 'LTE', 'IN', 'BETWEEN'];
const HIT_POLICIES: HitPolicy[] = ['FIRST', 'UNIQUE', 'COLLECT', 'PRIORITY'];
const AGGREGATIONS: TableAggregation[] = ['NONE', 'SUM', 'MIN', 'MAX', 'COUNT'];
const DATA_TYPES: DataType[] = ['string', 'integer', 'decimal', 'boolean', 'date', 'time', 'datetime', 'duration', 'enum'];
const SCOPES: Scope[] = ['record', 'event', 'meta', 'process', 'task', 'sla'];

const splitValues = (raw: string): string[] =>
  raw.split(',').map((item) => item.trim()).filter(Boolean);

const formatCombination = (issue: DecisionTableAnalysisIssue): string => {
  const combination = issue.inputCombination ?? {};
  const entries = Object.entries(combination);
  if (entries.length === 0) return '所有输入组合';
  return entries.map(([key, val]) => `${key}=${String(val)}`).join(', ');
};

const formatMetadataValue = (value: unknown): string => {
  if (Array.isArray(value)) return value.map((item) => String(item)).join(', ');
  if (value && typeof value === 'object') return JSON.stringify(value);
  return String(value);
};

const formatMetadata = (issue: DecisionTableAnalysisIssue): string => {
  const metadata = issue.metadata ?? {};
  const entries = Object.entries(metadata);
  if (entries.length === 0) return '';
  return entries.map(([key, val]) => `${key}: ${formatMetadataValue(val)}`).join(' · ');
};

function move<T>(items: T[], from: number, to: number): T[] {
  if (to < 0 || to >= items.length) return items;
  const next = items.slice();
  const [item] = next.splice(from, 1);
  next.splice(to, 0, item);
  return next;
}

export function DecisionTableEditor({
  value,
  onChange,
  analysis,
  analyzing = false,
  analysisError = null,
  onAnalyze,
  dmnXml = '',
  dmnBusy = false,
  dmnError = null,
  dmnStatus = null,
  onDmnXmlChange,
  onExportDmnXml,
  onImportDmnXml,
  onRoundTripDmnXml,
}: DecisionTableEditorProps) {
  const emitRules = (rules: TableRule[]) => onChange({ ...value, rules });

  const addRule = () => {
    const ruleId = `row-${value.rules.length + 1}-${Date.now()}`;
    emitRules([...value.rules, { ruleId, priority: (value.rules.length + 1) * 10, when: {}, then: {} }]);
  };

  const deleteRule = (idx: number) => emitRules(value.rules.filter((_, i) => i !== idx));

  const updateHitPolicy = (hitPolicy: HitPolicy) => {
    onChange({ ...value, hitPolicy, aggregation: hitPolicy === 'COLLECT' ? value.aggregation ?? 'NONE' : value.aggregation });
  };

  const setCellOperator = (idx: number, inputId: string, op: Operator) => {
    const rules = value.rules.slice();
    const r = { ...rules[idx], when: { ...rules[idx].when } };
    r.when[inputId] = { operator: op, value: r.when[inputId]?.value ?? '', feel: r.when[inputId]?.feel };
    rules[idx] = r;
    emitRules(rules);
  };

  const setCellValue = (idx: number, inputId: string, val: string) => {
    const rules = value.rules.slice();
    const r = { ...rules[idx], when: { ...rules[idx].when } };
    r.when[inputId] = { operator: r.when[inputId]?.operator ?? 'EQ', value: val, feel: r.when[inputId]?.feel };
    rules[idx] = r;
    emitRules(rules);
  };

  const setCellFeel = (idx: number, inputId: string, feel: string) => {
    const rules = value.rules.slice();
    const r = { ...rules[idx], when: { ...rules[idx].when } };
    r.when[inputId] = { operator: r.when[inputId]?.operator ?? 'EQ', value: r.when[inputId]?.value ?? '', feel };
    rules[idx] = r;
    emitRules(rules);
  };

  const setOutputValue = (idx: number, outputId: string, val: string) => {
    const rules = value.rules.slice();
    rules[idx] = { ...rules[idx], then: { ...rules[idx].then, [outputId]: val } };
    emitRules(rules);
  };

  const emitInputs = (inputs: TableInput[]) => onChange({ ...value, inputs });
  const emitOutputs = (outputs: TableOutput[]) => onChange({ ...value, outputs });

  const updateInput = (idx: number, patch: Partial<TableInput>) => {
    emitInputs(value.inputs.map((input, i) => (i === idx ? { ...input, ...patch } : input)));
  };

  const addInput = () => {
    const id = `input_${Date.now()}`;
    emitInputs([...value.inputs, { id, label: 'New input', scope: 'record', path: 'data.newField', dataType: 'string' }]);
  };

  const deleteInput = (idx: number) => {
    const inputId = value.inputs[idx]?.id;
    if (!inputId) return;
    const inputs = value.inputs.filter((_, i) => i !== idx);
    const rules = value.rules.map((rule) => {
      const when = { ...rule.when };
      delete when[inputId];
      return { ...rule, when };
    });
    onChange({ ...value, inputs, rules });
  };

  const updateOutput = (idx: number, patch: Partial<TableOutput>) => {
    emitOutputs(value.outputs.map((output, i) => (i === idx ? { ...output, ...patch } : output)));
  };

  const addOutput = () => {
    const id = `output_${Date.now()}`;
    emitOutputs([...value.outputs, { id, label: 'New output', dataType: 'string' }]);
  };

  const deleteOutput = (idx: number) => {
    const outputId = value.outputs[idx]?.id;
    if (!outputId) return;
    const outputs = value.outputs.filter((_, i) => i !== idx);
    const rules = value.rules.map((rule) => {
      const then = { ...rule.then };
      delete then[outputId];
      return { ...rule, then };
    });
    onChange({ ...value, outputs, rules });
  };

  return (
    <div data-testid="decision-table-editor">
      <div className="dt-hitpolicy">
        <label htmlFor="dt-hp">命中策略</label>
        <select
          id="dt-hp"
          aria-label="hit-policy"
          value={value.hitPolicy}
          onChange={(e) => updateHitPolicy(e.target.value as HitPolicy)}
        >
          {HIT_POLICIES.map((policy) => <option key={policy} value={policy}>{policy}</option>)}
        </select>
        {value.hitPolicy === 'COLLECT' && (
          <select
            aria-label="collect-aggregation"
            value={value.aggregation ?? 'NONE'}
            onChange={(e) => onChange({ ...value, aggregation: e.target.value as TableAggregation })}
          >
            {AGGREGATIONS.map((aggregation) => <option key={aggregation} value={aggregation}>{aggregation}</option>)}
          </select>
        )}
      </div>

      <section className="dt-column-manager" aria-label="decision-table-columns">
        <div>
          <h4>输入列</h4>
          {value.inputs.map((input, idx) => (
            <div key={input.id} className="dt-column-row">
              <input
                aria-label={`input-label-${idx}`}
                value={input.label}
                onChange={(e) => updateInput(idx, { label: e.target.value })}
              />
              <select
                aria-label={`input-scope-${idx}`}
                value={input.scope}
                onChange={(e) => updateInput(idx, { scope: e.target.value as Scope })}
              >
                {SCOPES.map((scope) => <option key={scope} value={scope}>{scope}</option>)}
              </select>
              <input
                aria-label={`input-path-${idx}`}
                value={input.path}
                onChange={(e) => updateInput(idx, { path: e.target.value })}
              />
              <select
                aria-label={`input-data-type-${idx}`}
                value={input.dataType}
                onChange={(e) => updateInput(idx, { dataType: e.target.value as DataType })}
              >
                {DATA_TYPES.map((dt) => <option key={dt} value={dt}>{dt}</option>)}
              </select>
              <button type="button" aria-label={`move-input-up-${idx}`} onClick={() => emitInputs(move(value.inputs, idx, idx - 1))}>↑</button>
              <button type="button" aria-label={`move-input-down-${idx}`} onClick={() => emitInputs(move(value.inputs, idx, idx + 1))}>↓</button>
              <button type="button" aria-label={`delete-input-${idx}`} onClick={() => deleteInput(idx)}>删除</button>
            </div>
          ))}
          <button type="button" data-testid="dt-add-input" onClick={addInput}>添加输入列</button>
        </div>

        <div>
          <h4>输出列</h4>
          {value.outputs.map((output, idx) => (
            <div key={output.id} className="dt-column-row">
              <input
                aria-label={`output-label-${idx}`}
                value={output.label}
                onChange={(e) => updateOutput(idx, { label: e.target.value })}
              />
              <select
                aria-label={`output-data-type-${idx}`}
                value={output.dataType}
                onChange={(e) => updateOutput(idx, { dataType: e.target.value as DataType })}
              >
                {DATA_TYPES.map((dt) => <option key={dt} value={dt}>{dt}</option>)}
              </select>
              <input
                aria-label={`output-allowed-values-${idx}`}
                value={(output.allowedValues ?? []).join(',')}
                onChange={(e) => updateOutput(idx, { allowedValues: splitValues(e.target.value) })}
              />
              <button type="button" aria-label={`move-output-up-${idx}`} onClick={() => emitOutputs(move(value.outputs, idx, idx - 1))}>↑</button>
              <button type="button" aria-label={`move-output-down-${idx}`} onClick={() => emitOutputs(move(value.outputs, idx, idx + 1))}>↓</button>
              <button type="button" aria-label={`delete-output-${idx}`} onClick={() => deleteOutput(idx)}>删除</button>
            </div>
          ))}
          <button type="button" data-testid="dt-add-output" onClick={addOutput}>添加输出列</button>
        </div>
      </section>

      <div className="dt-toolbar">
        <button
          type="button"
          data-testid="dt-analyze"
          disabled={analyzing || !onAnalyze}
          onClick={() => { void onAnalyze?.(); }}
        >
          {analyzing ? '分析中...' : '分析完整性/冲突'}
        </button>
        <span data-testid="dt-analysis-summary">
          {analysis
            ? `规则 ${analysis.metrics.ruleCount} · gap ${analysis.metrics.gapCount} · overlap ${analysis.metrics.overlapCount} · conflict ${analysis.metrics.conflictCount}`
            : '尚未分析'}
        </span>
      </div>

      {analysisError && <div className="dt-analysis-error" data-testid="dt-analysis-error">{analysisError}</div>}

      {analysis && (
        <section className="dt-analysis-panel" data-testid="dt-analysis-panel" aria-label="decision-table-analysis">
          <div className="dt-analysis-status" data-valid={analysis.valid ? 'true' : 'false'}>
            <strong>{analysis.valid ? '校验通过' : '存在阻断问题'}</strong>
            <span>
              有限域 {analysis.metrics.finiteDomainComplete ? '完整' : '不完整'} ·
              组合 {analysis.metrics.finiteCombinationCount} ·
              continuous inputs {analysis.metrics.continuousInputCount ?? 0} ·
              unreachable {analysis.metrics.unreachableRuleCount}
              {typeof analysis.metrics.analysisDurationMs === 'number'
                ? ` · ${analysis.metrics.analysisDurationMs}ms`
                : ''}
            </span>
          </div>
          <div className="dt-analysis-metrics">
            <span data-testid="dt-metric-gap">Gap {analysis.metrics.gapCount}</span>
            <span data-testid="dt-metric-overlap">Overlap {analysis.metrics.overlapCount}</span>
            <span data-testid="dt-metric-conflict">Conflict {analysis.metrics.conflictCount}</span>
            <span data-testid="dt-metric-unreachable">Unreachable {analysis.metrics.unreachableRuleCount}</span>
          </div>
          {(analysis.errors.length > 0 || analysis.warnings.length > 0) && (
            <ul className="dt-analysis-issues">
              {[...analysis.errors, ...analysis.warnings].map((issue, idx) => (
                <li key={`${issue.code}-${idx}`} data-testid={`dt-analysis-issue-${idx}`} data-severity={issue.severity}>
                  <strong>{issue.code}</strong>
                  <span>{issue.message ?? 'No message'}</span>
                  <small>
                    {formatCombination(issue)}
                    {issue.ruleIds && issue.ruleIds.length > 0 ? ` · rules ${issue.ruleIds.join(',')}` : ''}
                  </small>
                  {formatMetadata(issue) && (
                    <small data-testid={`dt-analysis-metadata-${idx}`}>{formatMetadata(issue)}</small>
                  )}
                </li>
              ))}
            </ul>
          )}
        </section>
      )}

      <section className="dt-dmn-panel" data-testid="dt-dmn-panel" aria-label="decision-table-xml-tools">
        <div className="dt-dmn-actions">
          <button
            type="button"
            data-testid="dt-export-dmn"
            disabled={dmnBusy || !onExportDmnXml}
            onClick={() => { void onExportDmnXml?.(); }}
          >
            {dmnBusy ? '处理中...' : '导出 DMN XML'}
          </button>
          <button
            type="button"
            data-testid="dt-import-dmn"
            disabled={dmnBusy || !onImportDmnXml || !dmnXml.trim()}
            onClick={() => { void onImportDmnXml?.(); }}
          >
            导入 XML
          </button>
          <button
            type="button"
            data-testid="dt-roundtrip-dmn"
            disabled={dmnBusy || !onRoundTripDmnXml}
            onClick={() => { void onRoundTripDmnXml?.(); }}
          >
            Round-trip 验证
          </button>
          <span data-testid="dt-dmn-status">{dmnStatus ?? 'DMN XML 未生成'}</span>
        </div>
        {dmnError && <div className="dt-dmn-error" data-testid="dt-dmn-error">{dmnError}</div>}
        <textarea
          aria-label="dmn-xml"
          data-testid="dt-dmn-xml"
          value={dmnXml}
          onChange={(e) => onDmnXmlChange?.(e.target.value)}
        />
      </section>

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
                  <input
                    aria-label={`feel-${idx}-${input.id}`}
                    value={rule.when[input.id]?.feel ?? ''}
                    onChange={(e) => setCellFeel(idx, input.id, e.target.value)}
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
