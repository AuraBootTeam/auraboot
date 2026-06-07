/**
 * Client-side Decision Table model + preview, mirroring the backend
 * `com.auraboot.framework.decision.table` contract (docs/1.md §15). Each rule row is an AND of
 * per-cell comparisons; cells reuse the Condition AST preview semantics (three-valued) via the
 * shared {@link evaluatePreview}, so a row evaluates identically front-end and back-end.
 * Preview only — the backend table adapter stays authoritative.
 */
import {
  type Operator, type DataType, type Scope, type Truth, type ScopedContext,
  type ConditionNode, cmp, group, path, lit, evaluatePreview,
} from '../ast/conditionAst';

export type HitPolicy = 'FIRST' | 'UNIQUE';

export interface TableInput { id: string; label: string; scope: Scope; path: string; dataType: DataType }
export interface TableOutput { id: string; label: string; dataType: DataType }
export interface TableCell { operator: Operator; value: unknown }
export interface TableRule { ruleId: string; priority?: number; when: Record<string, TableCell>; then: Record<string, unknown> }

export interface DecisionTable {
  hitPolicy: HitPolicy;
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

function rowToCondition(rule: TableRule, inputs: Map<string, TableInput>): ConditionNode | null {
  const cells = Object.entries(rule.when);
  if (cells.length === 0) return null; // empty row = catch-all
  const nodes: ConditionNode[] = [];
  for (const [inputId, cell] of cells) {
    const input = inputs.get(inputId);
    if (!input) return null; // references unknown input -> treat row as UNKNOWN
    nodes.push(cmp(path(input.scope, input.path, input.dataType), cell.operator, lit(cell.value, input.dataType)));
  }
  return group('AND', nodes);
}

/** Evaluate a decision table against a context under its hit policy (mirrors backend §15.3). */
export function evaluateTablePreview(table: DecisionTable, ctx: ScopedContext): TablePreviewResult {
  const inputs = new Map(table.inputs.map((i) => [i.id, i]));
  const sorted = table.rules.slice().sort((a, b) => (a.priority ?? 0) - (b.priority ?? 0));

  const matched: TableRule[] = [];
  let anyUnknown = false;
  for (const rule of sorted) {
    const cond = rowToCondition(rule, inputs);
    const t: Truth = cond === null && Object.keys(rule.when).length > 0
      ? 'UNKNOWN' // unknown-input reference
      : cond === null
        ? 'TRUE' // genuine catch-all (empty when)
        : evaluatePreview(cond, ctx);
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
  return { status: 'MATCHED', matchedRuleId: matched[0].ruleId, outputs: matched[0].then, errors: [] };
}

/** Static checks (input/output ref integrity) — client pre-validation before backend validate. */
export function validateTable(table: DecisionTable): string[] {
  const errors: string[] = [];
  if (table.inputs.length === 0) errors.push('decision table has no inputs');
  if (table.outputs.length === 0) errors.push('decision table has no outputs');
  if (table.rules.length === 0 && (!table.defaultOutput || Object.keys(table.defaultOutput).length === 0)) {
    errors.push('decision table has no rules and no default output');
  }
  const inputIds = new Set(table.inputs.map((i) => i.id));
  const outputIds = new Set(table.outputs.map((o) => o.id));
  for (const rule of table.rules) {
    Object.keys(rule.when).forEach((id) => { if (!inputIds.has(id)) errors.push(`rule ${rule.ruleId} references unknown input '${id}'`); });
    Object.keys(rule.then).forEach((id) => { if (!outputIds.has(id)) errors.push(`rule ${rule.ruleId} references unknown output '${id}'`); });
  }
  return errors;
}
