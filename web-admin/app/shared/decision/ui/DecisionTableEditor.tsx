import { useState } from 'react';
import {
  type DecisionTable, type TableRule, type HitPolicy, type TableAggregation,
  type TableInput, type TableOutput,
} from '../table/decisionTable';
import { type DataType, type Operator, type Scope } from '../ast/conditionAst';
import type { DecisionTableAnalysis, DecisionTableAnalysisIssue } from '../api/decisionApi';
import type { FieldOption } from './ConditionBuilder';
import {
  aggregationLabel,
  analysisIssueLabel,
  analysisMetadataLabel,
  allowedValueOptions,
  dataTypeLabel,
  decisionOperatorLabel,
  hitPolicyLabel,
  scopeLabel,
} from './displayLabels';

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
  fieldOptions?: FieldOption[];
}

const CELL_OPERATORS: Operator[] = ['EQ', 'NE', 'GT', 'GTE', 'LT', 'LTE', 'IN', 'NOT_IN', 'BETWEEN'];
const COLLECTION_OPERATORS: ReadonlySet<Operator> = new Set<Operator>(['IN', 'NOT_IN']);
const HIT_POLICIES: HitPolicy[] = ['FIRST', 'UNIQUE', 'COLLECT', 'PRIORITY'];
const AGGREGATIONS: TableAggregation[] = ['NONE', 'SUM', 'MIN', 'MAX', 'COUNT'];
const DATA_TYPES: DataType[] = [
  'string', 'text', 'integer', 'decimal', 'boolean', 'date', 'time',
  'datetime', 'duration', 'enum', 'dict', 'user', 'role', 'group',
  'department', 'collection', 'object',
];
const SCOPES: Scope[] = [
  'record', 'event', 'meta', 'before', 'after', 'process',
  'task', 'sla', 'actor', 'tenant', 'time', 'env',
];

const splitValues = (raw: string): string[] =>
  raw.split(',').map((item) => item.trim()).filter(Boolean);

function cellArrayValue(value: unknown): string[] {
  if (Array.isArray(value)) return value.map((item) => String(item));
  if (value === undefined || value === null || value === '') return [];
  return [String(value)];
}

function cellScalarValue(value: unknown): string {
  if (Array.isArray(value)) return String(value[0] ?? '');
  return String(value ?? '');
}

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
  return entries.map(([key, val]) => `${analysisMetadataLabel(key)}: ${formatMetadataValue(val)}`).join(' · ');
};

function sanitizeInputIdSegment(value: string): string {
  return value.replace(/[^A-Za-z0-9]+/g, '_').replace(/^_+|_+$/g, '') || 'field';
}

function fieldInputId(field: FieldOption): string {
  return `${field.scope}_${sanitizeInputIdSegment(field.path)}`;
}

function fieldKey(field: FieldOption): string {
  return `${field.scope}:${field.path}`;
}

function uniqueInputId(baseId: string, inputs: TableInput[], currentIndex: number): string {
  const used = new Set(
    inputs
      .filter((_, index) => index !== currentIndex)
      .map((input) => input.id),
  );
  if (!used.has(baseId)) return baseId;
  let index = 2;
  while (used.has(`${baseId}_${index}`)) index += 1;
  return `${baseId}_${index}`;
}

function stripRuntimeExpr(input: TableInput): TableInput {
  const { expr: _expr, ...rest } = input as TableInput & { expr?: unknown };
  return rest;
}

function normalizedDataType(dataType: DataType): DataType {
  return DATA_TYPES.includes(dataType) ? dataType : 'string';
}

function fieldTestId(field: FieldOption): string {
  return `${field.scope}-${sanitizeInputIdSegment(field.path)}`;
}

function formatFieldRef(field: FieldOption): string {
  return `${field.scope}.${field.path}`;
}

function fieldGroupLabel(field: FieldOption): string {
  if (field.scope === 'sla') return 'SLA 上下文';
  if (field.modelName) return field.modelName;
  return scopeLabel(field.scope);
}

function fieldPickerMeta(field: FieldOption): string {
  return `${fieldGroupLabel(field)} · ${dataTypeLabel(normalizedDataType(field.dataType))}`;
}

function fieldMatchesQuery(field: FieldOption, query: string): boolean {
  const normalized = query.trim().toLowerCase();
  if (!normalized) return true;
  return [
    field.label,
    field.path,
    field.scope,
    field.modelCode,
    field.modelName,
    field.dataType,
  ]
    .filter(Boolean)
    .some((item) => String(item).toLowerCase().includes(normalized));
}

function groupedFieldOptions(fields: FieldOption[], query: string): Array<[string, FieldOption[]]> {
  const grouped = new Map<string, FieldOption[]>();
  fields
    .filter((field) => fieldMatchesQuery(field, query))
    .forEach((field) => {
      const label = fieldGroupLabel(field);
      grouped.set(label, [...(grouped.get(label) ?? []), field]);
    });
  return Array.from(grouped.entries());
}

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
  fieldOptions = [],
}: DecisionTableEditorProps) {
  const [openFieldPicker, setOpenFieldPicker] = useState<string | null>(null);
  const [fieldQueries, setFieldQueries] = useState<Record<string, string>>({});
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
    const currentCell = r.when[inputId];
    const currentValue = currentCell?.value ?? '';
    r.when[inputId] = {
      operator: op,
      value: COLLECTION_OPERATORS.has(op)
        ? cellArrayValue(currentValue)
        : Array.isArray(currentValue) ? (currentValue[0] ?? '') : currentValue,
      feel: currentCell?.feel,
    };
    rules[idx] = r;
    emitRules(rules);
  };

  const setCellValue = (idx: number, inputId: string, val: unknown) => {
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
    emitInputs(value.inputs.map((input, i) => (i === idx ? { ...stripRuntimeExpr(input), ...patch } : input)));
  };

  const selectInputField = (idx: number, field: FieldOption) => {
    const currentInput = value.inputs[idx];
    if (!currentInput) return;

    const nextId = uniqueInputId(fieldInputId(field), value.inputs, idx);
    const nextInput: TableInput = {
      ...stripRuntimeExpr(currentInput),
      id: nextId,
      label: field.label,
      scope: field.scope,
      path: field.path,
      dataType: normalizedDataType(field.dataType),
      allowedValues: field.options,
      valueLabels: field.valueLabels,
    };
    const inputs = value.inputs.map((input, index) => (index === idx ? nextInput : input));
    const rules = value.rules.map((rule) => {
      if (currentInput.id === nextId || !(currentInput.id in rule.when)) return rule;
      const when = { ...rule.when };
      when[nextId] = when[currentInput.id];
      delete when[currentInput.id];
      return { ...rule, when };
    });

    onChange({ ...value, inputs, rules });
    setOpenFieldPicker(null);
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

  const renderInputCell = (rule: TableRule, idx: number, input: TableInput) => {
    const options = allowedValueOptions(input.allowedValues, input.valueLabels);
    const cell = rule.when[input.id];
    const isCollection = COLLECTION_OPERATORS.has(cell?.operator ?? 'EQ');
    return (
      <>
        <select
          aria-label={`op-${idx}-${input.id}`}
          value={cell?.operator ?? 'EQ'}
          onChange={(e) => setCellOperator(idx, input.id, e.target.value as Operator)}
        >
          {CELL_OPERATORS.map((op) => (
            <option key={op} value={op}>{decisionOperatorLabel(op)}</option>
          ))}
        </select>
        {options.length > 0 ? (
          isCollection ? (
            <select
              aria-label={`val-${idx}-${input.id}`}
              multiple
              size={Math.min(4, Math.max(2, options.length))}
              value={cellArrayValue(cell?.value)}
              onChange={(e) =>
                setCellValue(
                  idx,
                  input.id,
                  Array.from(e.currentTarget.selectedOptions).map((option) => option.value),
                )
              }
            >
              {options.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          ) : (
            <select
              aria-label={`val-${idx}-${input.id}`}
              value={cellScalarValue(cell?.value)}
              onChange={(e) => setCellValue(idx, input.id, e.target.value)}
            >
              <option value="">—</option>
              {options.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          )
        ) : (
          <input
            aria-label={`val-${idx}-${input.id}`}
            value={isCollection ? cellArrayValue(cell?.value).join(', ') : cellScalarValue(cell?.value)}
            onChange={(e) => setCellValue(idx, input.id, isCollection ? splitValues(e.target.value) : e.target.value)}
          />
        )}
        <input
          aria-label={`feel-${idx}-${input.id}`}
          value={cell?.feel ?? ''}
          onChange={(e) => setCellFeel(idx, input.id, e.target.value)}
        />
      </>
    );
  };

  const renderOutputCell = (rule: TableRule, idx: number, output: TableOutput) => {
    const options = allowedValueOptions(output.allowedValues, output.valueLabels);
    return options.length > 0 ? (
      <select
        aria-label={`out-${idx}-${output.id}`}
        value={String(rule.then[output.id] ?? '')}
        onChange={(e) => setOutputValue(idx, output.id, e.target.value)}
      >
        <option value="">—</option>
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    ) : (
      <input
        aria-label={`out-${idx}-${output.id}`}
        value={String(rule.then[output.id] ?? '')}
        onChange={(e) => setOutputValue(idx, output.id, e.target.value)}
      />
    );
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
          {HIT_POLICIES.map((policy) => (
            <option key={policy} value={policy}>{hitPolicyLabel(policy)}</option>
          ))}
        </select>
        {value.hitPolicy === 'COLLECT' && (
          <select
            aria-label="collect-aggregation"
            value={value.aggregation ?? 'NONE'}
            onChange={(e) => onChange({ ...value, aggregation: e.target.value as TableAggregation })}
          >
            {AGGREGATIONS.map((aggregation) => (
              <option key={aggregation} value={aggregation}>{aggregationLabel(aggregation)}</option>
            ))}
          </select>
        )}
      </div>

      <section className="dt-column-manager" aria-label="decision-table-columns">
        <div>
          <h4>输入列</h4>
          {value.inputs.map((input, idx) => (
            <div key={input.id} className="dt-column-row dt-column-row--input">
              <label className="dt-column-field">
                <span>名称</span>
                <input
                  aria-label={`input-label-${idx}`}
                  value={input.label}
                  onChange={(e) => updateInput(idx, { label: e.target.value })}
                />
              </label>
              <label className="dt-column-field">
                <span>作用域</span>
                <select
                  aria-label={`input-scope-${idx}`}
                  value={input.scope}
                  onChange={(e) => updateInput(idx, { scope: e.target.value as Scope })}
                >
                  {SCOPES.map((scope) => (
                    <option key={scope} value={scope}>{scopeLabel(scope)}</option>
                  ))}
                </select>
              </label>
              <label className="dt-column-field">
                <span>字段路径</span>
                <input
                  aria-label={`input-path-${idx}`}
                  value={input.path}
                  onChange={(e) => updateInput(idx, { path: e.target.value })}
                />
              </label>
              {fieldOptions.length > 0 && (
                <div className="dt-field-picker-cell">
                  <span>字段目录</span>
                  <button
                    type="button"
                    data-testid={`dt-input-field-picker-${idx}`}
                    onClick={() => setOpenFieldPicker((current) => (current === input.id ? null : input.id))}
                  >
                    选择字段
                  </button>
                  {openFieldPicker === input.id && (
                    <div
                      className="dt-field-picker"
                      data-testid={`dt-input-field-picker-panel-${idx}`}
                    >
                      <input
                        aria-label={`input-field-search-${idx}`}
                        value={fieldQueries[input.id] ?? ''}
                        placeholder="搜索字段"
                        onChange={(e) =>
                          setFieldQueries((current) => ({
                            ...current,
                            [input.id]: e.target.value,
                          }))
                        }
                      />
                      <div className="dt-field-picker-list">
                        {groupedFieldOptions(fieldOptions, fieldQueries[input.id] ?? '').map(([label, groupFields]) => (
                          <div key={label} className="dt-field-picker-group">
                            <strong>{label}</strong>
                            {groupFields.map((field) => (
                              <button
                                key={fieldKey(field)}
                                type="button"
                                data-testid={`dt-input-field-option-${idx}-${fieldTestId(field)}`}
                                onClick={() => selectInputField(idx, field)}
                              >
                                <span>{field.label}</span>
                                <small title={formatFieldRef(field)}>{fieldPickerMeta(field)}</small>
                              </button>
                            ))}
                          </div>
                        ))}
                        {groupedFieldOptions(fieldOptions, fieldQueries[input.id] ?? '').length === 0 && (
                          <span className="dt-field-picker-empty">没有匹配字段</span>
                        )}
                      </div>
                    </div>
                  )}
                </div>
              )}
              <label className="dt-column-field">
                <span>类型</span>
                <select
                  aria-label={`input-data-type-${idx}`}
                  value={input.dataType}
                  onChange={(e) => updateInput(idx, { dataType: e.target.value as DataType })}
                >
                  {DATA_TYPES.map((dt) => (
                    <option key={dt} value={dt}>{dataTypeLabel(dt)}</option>
                  ))}
                </select>
              </label>
              <div className="dt-column-actions">
                <button type="button" aria-label={`move-input-up-${idx}`} onClick={() => emitInputs(move(value.inputs, idx, idx - 1))}>↑</button>
                <button type="button" aria-label={`move-input-down-${idx}`} onClick={() => emitInputs(move(value.inputs, idx, idx + 1))}>↓</button>
                <button type="button" aria-label={`delete-input-${idx}`} onClick={() => deleteInput(idx)}>删除</button>
              </div>
            </div>
          ))}
          <button type="button" data-testid="dt-add-input" onClick={addInput}>添加输入列</button>
        </div>

        <div>
          <h4>输出列</h4>
          {value.outputs.map((output, idx) => (
            <div key={output.id} className="dt-column-row dt-column-row--output">
              <label className="dt-column-field">
                <span>名称</span>
                <input
                  aria-label={`output-label-${idx}`}
                  value={output.label}
                  onChange={(e) => updateOutput(idx, { label: e.target.value })}
                />
              </label>
              <label className="dt-column-field">
                <span>类型</span>
                <select
                  aria-label={`output-data-type-${idx}`}
                  value={output.dataType}
                  onChange={(e) => updateOutput(idx, { dataType: e.target.value as DataType })}
                >
                  {DATA_TYPES.map((dt) => (
                    <option key={dt} value={dt}>{dataTypeLabel(dt)}</option>
                  ))}
                </select>
              </label>
              <label className="dt-column-field">
                <span>候选值</span>
                <input
                  aria-label={`output-allowed-values-${idx}`}
                  value={(output.allowedValues ?? []).join(',')}
                  onChange={(e) => updateOutput(idx, { allowedValues: splitValues(e.target.value), valueLabels: undefined })}
                />
              </label>
              <div className="dt-column-actions">
                <button type="button" aria-label={`move-output-up-${idx}`} onClick={() => emitOutputs(move(value.outputs, idx, idx - 1))}>↑</button>
                <button type="button" aria-label={`move-output-down-${idx}`} onClick={() => emitOutputs(move(value.outputs, idx, idx + 1))}>↓</button>
                <button type="button" aria-label={`delete-output-${idx}`} onClick={() => deleteOutput(idx)}>删除</button>
              </div>
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
            ? `规则 ${analysis.metrics.ruleCount} · 缺口 ${analysis.metrics.gapCount} · 重叠 ${analysis.metrics.overlapCount} · 冲突 ${analysis.metrics.conflictCount}`
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
              连续输入 {analysis.metrics.continuousInputCount ?? 0} ·
              不可达 {analysis.metrics.unreachableRuleCount}
              {typeof analysis.metrics.analysisDurationMs === 'number'
                ? ` · ${analysis.metrics.analysisDurationMs}ms`
                : ''}
            </span>
          </div>
          <div className="dt-analysis-metrics">
            <span data-testid="dt-metric-gap">缺口 {analysis.metrics.gapCount}</span>
            <span data-testid="dt-metric-overlap">重叠 {analysis.metrics.overlapCount}</span>
            <span data-testid="dt-metric-conflict">冲突 {analysis.metrics.conflictCount}</span>
            <span data-testid="dt-metric-unreachable">不可达 {analysis.metrics.unreachableRuleCount}</span>
          </div>
          {(analysis.errors.length > 0 || analysis.warnings.length > 0) && (
            <ul className="dt-analysis-issues">
              {[...analysis.errors, ...analysis.warnings].map((issue, idx) => (
                <li key={`${issue.code}-${idx}`} data-testid={`dt-analysis-issue-${idx}`} data-severity={issue.severity}>
                  <strong title={issue.code}>{analysisIssueLabel(issue.code)}</strong>
                  <span>{issue.message ?? '暂无说明'}</span>
                  <small>
                    {formatCombination(issue)}
                    {issue.ruleIds && issue.ruleIds.length > 0 ? ` · 规则 ${issue.ruleIds.join(',')}` : ''}
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
            往返校验
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

      <div className="dt-grid-scroll">
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
                    {renderInputCell(rule, idx, input)}
                  </td>
                ))}
                {value.outputs.map((output) => (
                  <td key={output.id}>
                    {renderOutputCell(rule, idx, output)}
                  </td>
                ))}
                <td>
                  <button type="button" aria-label={`delete-row-${idx}`} onClick={() => deleteRule(idx)}>删除</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <button type="button" data-testid="dt-add-rule" onClick={addRule}>添加规则行</button>
    </div>
  );
}

export default DecisionTableEditor;
