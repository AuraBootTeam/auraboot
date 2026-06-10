/**
 * Client-side Decision Table model + preview, mirroring the backend
 * `com.auraboot.framework.decision.table` contract. The backend remains authoritative; this module
 * provides fast local feedback for DMN-style table editing.
 */
import {
  type Operator, type DataType, type Scope, type Truth, type ScopedContext,
  type ConditionNode, cmp, group, path, lit, evaluatePreview, UNARY_OPERATORS,
} from '../ast/conditionAst';

export type HitPolicy = 'FIRST' | 'UNIQUE' | 'COLLECT' | 'PRIORITY';
export type TableAggregation = 'NONE' | 'SUM' | 'MIN' | 'MAX' | 'COUNT';

export interface TableInput {
  id: string;
  label: string;
  scope: Scope;
  path: string;
  dataType: DataType;
  allowedValues?: unknown[];
}

export interface TableOutput {
  id: string;
  label: string;
  dataType: DataType;
  allowedValues?: unknown[];
}

export interface TableCell {
  operator: Operator;
  value: unknown;
  feel?: string;
}

export interface TableRule {
  ruleId: string;
  priority?: number;
  when: Record<string, TableCell>;
  then: Record<string, unknown>;
}

export interface DecisionTable {
  hitPolicy: HitPolicy;
  aggregation?: TableAggregation;
  inputs: TableInput[];
  outputs: TableOutput[];
  rules: TableRule[];
  defaultOutput?: Record<string, unknown>;
}

export type TableStatus = 'MATCHED' | 'NOT_MATCHED' | 'UNKNOWN' | 'ERROR';

export interface TablePreviewResult {
  status: TableStatus;
  matchedRuleId: string | null;
  outputs: Record<string, unknown>;
  errors: string[];
}

interface RowCondition {
  condition: ConditionNode | null;
  unknown: boolean;
}

interface ParsedFeelTest {
  operator: Operator;
  value?: unknown;
}

function parseLiteral(raw: string, dataType?: DataType): unknown {
  const text = raw.trim();
  if ((text.startsWith('"') && text.endsWith('"')) || (text.startsWith("'") && text.endsWith("'"))) {
    return text.slice(1, -1);
  }
  if (text.toLowerCase() === 'null') return null;
  if (text.toLowerCase() === 'true') return true;
  if (text.toLowerCase() === 'false') return false;
  if ((dataType === 'integer' || dataType === 'decimal') && text !== '' && !Number.isNaN(Number(text))) {
    return Number(text);
  }
  return text;
}

function parseFeelCell(text: string | undefined, dataType?: DataType): ParsedFeelTest[] | null {
  const feel = text?.trim() ?? '';
  if (!feel || feel === '-') return [];
  const lower = feel.toLowerCase();
  if (lower === 'null') return [{ operator: 'IS_NULL' }];
  if (lower === 'not(null)' || lower === 'not null') return [{ operator: 'IS_NOT_NULL' }];

  const range = /^\[\s*(.+?)\s*\.\.\s*(.+?)\s*]$/.exec(feel);
  if (range) {
    return [{ operator: 'BETWEEN', value: [parseLiteral(range[1], dataType), parseLiteral(range[2], dataType)] }];
  }

  const comparison = /^(>=|<=|>|<|!=|=)\s*(.+)$/.exec(feel);
  if (comparison) {
    const operator: Operator = comparison[1] === '>' ? 'GT'
      : comparison[1] === '>=' ? 'GTE'
        : comparison[1] === '<' ? 'LT'
          : comparison[1] === '<=' ? 'LTE'
            : comparison[1] === '!=' ? 'NE'
              : 'EQ';
    return [{ operator, value: parseLiteral(comparison[2], dataType) }];
  }

  if (feel.includes(',')) {
    const values = feel.split(',').map((item) => item.trim()).filter(Boolean).map((item) => parseLiteral(item, dataType));
    return values.length ? [{ operator: 'IN', value: values }] : null;
  }

  return [{ operator: 'EQ', value: parseLiteral(feel, dataType) }];
}

function rowToCondition(rule: TableRule, inputs: Map<string, TableInput>): RowCondition {
  const cells = Object.entries(rule.when);
  if (cells.length === 0) return { condition: null, unknown: false };

  const nodes: ConditionNode[] = [];
  for (const [inputId, cell] of cells) {
    const input = inputs.get(inputId);
    if (!input) return { condition: null, unknown: true };

    if (cell.feel?.trim()) {
      const parsed = parseFeelCell(cell.feel, input.dataType);
      if (parsed === null) return { condition: null, unknown: true };
      for (const test of parsed) {
        nodes.push(cmp(
          path(input.scope, input.path, input.dataType),
          test.operator,
          UNARY_OPERATORS.has(test.operator) ? undefined : lit(test.value, input.dataType),
        ));
      }
    } else {
      nodes.push(cmp(path(input.scope, input.path, input.dataType), cell.operator, lit(cell.value, input.dataType)));
    }
  }

  return { condition: nodes.length ? group('AND', nodes) : null, unknown: false };
}

function tableError(message: string): TablePreviewResult {
  return { status: 'ERROR', matchedRuleId: null, outputs: {}, errors: [message] };
}

const matchedRuleIds = (rules: TableRule[]): string => rules.map((rule) => rule.ruleId).join(',');

function collectOutputs(table: DecisionTable, matched: TableRule[]): TablePreviewResult {
  const aggregation = table.aggregation ?? 'NONE';
  const ids = matchedRuleIds(matched);

  if (aggregation === 'NONE') {
    const outputs: Record<string, unknown> = {};
    for (const output of table.outputs) {
      outputs[output.id] = matched.map((rule) => rule.then[output.id]);
    }
    return { status: 'MATCHED', matchedRuleId: ids, outputs, errors: [] };
  }

  if (table.outputs.length !== 1) return tableError('COLLECT aggregation requires exactly one output column');
  const output = table.outputs[0];
  if (aggregation === 'COUNT') {
    return { status: 'MATCHED', matchedRuleId: ids, outputs: { [output.id]: matched.length }, errors: [] };
  }
  if (output.dataType !== 'integer' && output.dataType !== 'decimal') {
    return tableError(`COLLECT ${aggregation} requires a numeric output column`);
  }

  const values = matched.map((rule) => Number(rule.then[output.id]));
  if (values.some((value) => Number.isNaN(value))) {
    return tableError(`COLLECT ${aggregation} output contains non-numeric values`);
  }
  const aggregate = aggregation === 'SUM'
    ? values.reduce((sum, value) => sum + value, 0)
    : aggregation === 'MIN'
      ? Math.min(...values)
      : Math.max(...values);
  return { status: 'MATCHED', matchedRuleId: ids, outputs: { [output.id]: aggregate }, errors: [] };
}

function priorityOutput(table: DecisionTable, matched: TableRule[]): TablePreviewResult {
  if (table.outputs.length !== 1) return tableError('PRIORITY hitPolicy requires exactly one output column');
  const output = table.outputs[0];
  if (!output.allowedValues?.length) return tableError('PRIORITY hitPolicy requires output allowedValues ordered highest-first');

  let winner: TableRule | null = null;
  let winnerRank = Number.POSITIVE_INFINITY;
  for (const rule of matched) {
    const rank = output.allowedValues.findIndex((allowed) => String(allowed) === String(rule.then[output.id]));
    if (rank >= 0 && rank < winnerRank) {
      winner = rule;
      winnerRank = rank;
    }
  }
  if (!winner) return tableError('PRIORITY hitPolicy matched rows without allowed output values');
  return { status: 'MATCHED', matchedRuleId: winner.ruleId, outputs: winner.then, errors: [] };
}

/** Evaluate a decision table against a context under its hit policy. */
export function evaluateTablePreview(table: DecisionTable, ctx: ScopedContext): TablePreviewResult {
  const inputs = new Map(table.inputs.map((i) => [i.id, i]));
  const sorted = table.rules.slice().sort((a, b) => (a.priority ?? 0) - (b.priority ?? 0));

  const matched: TableRule[] = [];
  let anyUnknown = false;
  for (const rule of sorted) {
    const row = rowToCondition(rule, inputs);
    const t: Truth = row.unknown
      ? 'UNKNOWN'
      : row.condition === null
        ? 'TRUE'
        : evaluatePreview(row.condition, ctx);
    if (t === 'TRUE') {
      matched.push(rule);
      if (table.hitPolicy === 'FIRST') break;
    } else if (t === 'UNKNOWN') {
      anyUnknown = true;
    }
  }

  if (matched.length === 0) {
    if (table.defaultOutput && Object.keys(table.defaultOutput).length > 0) {
      return { status: 'MATCHED', matchedRuleId: '__default__', outputs: table.defaultOutput, errors: [] };
    }
    return { status: anyUnknown ? 'UNKNOWN' : 'NOT_MATCHED', matchedRuleId: null, outputs: {}, errors: [] };
  }
  if (table.hitPolicy === 'UNIQUE' && matched.length > 1) {
    return {
      status: 'ERROR', matchedRuleId: null, outputs: {},
      errors: [`UNIQUE hitPolicy matched multiple rows: ${matched.map((r) => r.ruleId).join(', ')}`],
    };
  }
  if (table.hitPolicy === 'COLLECT') return collectOutputs(table, matched);
  if (table.hitPolicy === 'PRIORITY') return priorityOutput(table, matched);
  return { status: 'MATCHED', matchedRuleId: matched[0].ruleId, outputs: matched[0].then, errors: [] };
}

/** Static checks - client pre-validation before backend validate. */
export function validateTable(table: DecisionTable): string[] {
  const errors: string[] = [];
  if (table.inputs.length === 0) errors.push('decision table has no inputs');
  if (table.outputs.length === 0) errors.push('decision table has no outputs');
  if (table.rules.length === 0 && (!table.defaultOutput || Object.keys(table.defaultOutput).length === 0)) {
    errors.push('decision table has no rules and no default output');
  }
  if (table.hitPolicy === 'COLLECT' && table.aggregation && table.aggregation !== 'NONE') {
    if (table.outputs.length !== 1) errors.push('COLLECT aggregation requires exactly one output column');
    const output = table.outputs[0];
    if (output && table.aggregation !== 'COUNT' && output.dataType !== 'integer' && output.dataType !== 'decimal') {
      errors.push(`COLLECT ${table.aggregation} requires a numeric output column`);
    }
  }
  if (table.hitPolicy === 'PRIORITY') {
    if (table.outputs.length !== 1) errors.push('PRIORITY hitPolicy requires exactly one output column');
    if (!table.outputs[0]?.allowedValues?.length) errors.push('PRIORITY hitPolicy requires output allowedValues ordered highest-first');
  }

  const inputIds = new Set(table.inputs.map((i) => i.id));
  const outputIds = new Set(table.outputs.map((o) => o.id));
  for (const rule of table.rules) {
    Object.entries(rule.when).forEach(([id, cell]) => {
      if (!inputIds.has(id)) errors.push(`rule ${rule.ruleId} references unknown input '${id}'`);
      const input = table.inputs.find((candidate) => candidate.id === id);
      if (cell.feel?.trim() && parseFeelCell(cell.feel, input?.dataType) === null) {
        errors.push(`rule ${rule.ruleId} has invalid FEEL cell for input '${id}'`);
      }
    });
    Object.keys(rule.then).forEach((id) => {
      if (!outputIds.has(id)) errors.push(`rule ${rule.ruleId} references unknown output '${id}'`);
    });
  }
  return errors;
}
