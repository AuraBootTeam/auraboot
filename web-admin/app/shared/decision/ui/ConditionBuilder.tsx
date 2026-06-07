import { useMemo } from 'react';
import {
  type ConditionNode, type GroupNode, type CompareNode, type Operator, type DataType,
  type PathOperand, cmp, path, lit, toNaturalLanguage,
} from '../ast/conditionAst';

/**
 * DecisionOps Condition Builder — a controlled UI for authoring a (flat) Condition AST group
 * (docs/1.md §14 / §21.2): pick field → operator (filtered by data type) → value, add/remove rows,
 * toggle AND/OR, and see a live natural-language preview. Output is the rich AST the backend
 * consumes; the backend stays authoritative (this is authoring + preview only).
 *
 * Nested groups + drag-reorder are a later slice (drag needs a real browser, jsdom has no geometry).
 */

export interface FieldOption {
  scope: PathOperand['scope'];
  path: string;
  label: string;
  dataType: DataType;
  options?: string[];
}

export interface ConditionBuilderProps {
  value: GroupNode;
  fields: FieldOption[];
  onChange: (next: GroupNode) => void;
}

const OPERATORS_BY_TYPE: Partial<Record<DataType, Operator[]>> = {
  string: ['EQ', 'NE', 'CONTAINS_TEXT', 'STARTS_WITH', 'ENDS_WITH', 'IS_EMPTY', 'IS_NOT_EMPTY'],
  text: ['CONTAINS_TEXT', 'IS_EMPTY', 'IS_NOT_EMPTY'],
  integer: ['EQ', 'NE', 'GT', 'GTE', 'LT', 'LTE', 'BETWEEN', 'IS_NULL', 'IS_NOT_NULL'],
  decimal: ['EQ', 'NE', 'GT', 'GTE', 'LT', 'LTE', 'BETWEEN', 'IS_NULL', 'IS_NOT_NULL'],
  enum: ['EQ', 'NE', 'IN', 'NOT_IN', 'IS_NULL', 'IS_NOT_NULL'],
  dict: ['EQ', 'NE', 'IN', 'NOT_IN', 'IS_NULL', 'IS_NOT_NULL'],
  department: ['EQ', 'NE', 'IN', 'NOT_IN'],
  user: ['EQ', 'NE', 'IN', 'NOT_IN'],
  collection: ['CONTAINS_ELEMENT', 'IS_EMPTY', 'IS_NOT_EMPTY'],
  boolean: ['EQ', 'NE'],
  datetime: ['GT', 'GTE', 'LT', 'LTE', 'BETWEEN', 'IS_NULL', 'IS_NOT_NULL'],
};

const UNARY: ReadonlySet<Operator> = new Set<Operator>(['IS_NULL', 'IS_NOT_NULL', 'IS_EMPTY', 'IS_NOT_EMPTY', 'CHANGED']);

export function operatorsForDataType(dt: DataType): Operator[] {
  return OPERATORS_BY_TYPE[dt] ?? ['EQ', 'NE'];
}

const fieldKey = (scope: string, p: string): string => `${scope}:${p}`;

export function ConditionBuilder({ value, fields, onChange }: ConditionBuilderProps) {
  const rows = value.children.filter((c): c is CompareNode => c.type === 'compare');
  const fieldByKey = useMemo(() => {
    const m = new Map<string, FieldOption>();
    fields.forEach((f) => m.set(fieldKey(f.scope, f.path), f));
    return m;
  }, [fields]);

  const labelOf = (o: PathOperand): string =>
    fieldByKey.get(fieldKey(o.scope, o.path))?.label ?? `${o.scope}.${o.path}`;

  const emit = (children: ConditionNode[]) => onChange({ ...value, children });

  const updateRow = (idx: number, next: CompareNode) => {
    const children = value.children.slice();
    let seen = -1;
    for (let i = 0; i < children.length; i += 1) {
      if (children[i].type === 'compare') {
        seen += 1;
        if (seen === idx) { children[i] = next; break; }
      }
    }
    emit(children);
  };

  const addRow = () => {
    const f = fields[0];
    const left = path(f.scope, f.path, f.dataType);
    emit([...value.children, cmp(left, operatorsForDataType(f.dataType)[0], lit('', f.dataType))]);
  };

  const deleteRow = (idx: number) => {
    let seen = -1;
    emit(value.children.filter((c) => {
      if (c.type !== 'compare') return true;
      seen += 1;
      return seen !== idx;
    }));
  };

  const onFieldChange = (idx: number, key: string) => {
    const f = fieldByKey.get(key);
    if (!f) return;
    const ops = operatorsForDataType(f.dataType);
    updateRow(idx, cmp(path(f.scope, f.path, f.dataType), ops[0], lit('', f.dataType)));
  };

  const onOperatorChange = (idx: number, op: Operator, row: CompareNode) => {
    updateRow(idx, { ...row, operator: op, right: UNARY.has(op) ? undefined : (row.right ?? lit('')) });
  };

  const onValueChange = (idx: number, val: string, row: CompareNode) => {
    const dt = (row.left as PathOperand).dataType;
    updateRow(idx, { ...row, right: lit(val, dt) });
  };

  return (
    <div data-testid="condition-builder">
      <div className="cb-group-op">
        <button
          type="button"
          data-testid="op-and"
          aria-pressed={value.op === 'AND'}
          onClick={() => onChange({ ...value, op: 'AND' })}
        >并且(AND)</button>
        <button
          type="button"
          data-testid="op-or"
          aria-pressed={value.op === 'OR'}
          onClick={() => onChange({ ...value, op: 'OR' })}
        >或(OR)</button>
      </div>

      {rows.length === 0 && <div data-testid="cb-empty">暂无条件</div>}

      {rows.map((row, idx) => {
        const left = row.left as PathOperand;
        const curKey = fieldKey(left.scope, left.path);
        const dt = left.dataType ?? 'string';
        const ops = operatorsForDataType(dt);
        const fieldOpt = fieldByKey.get(curKey);
        const showValue = !UNARY.has(row.operator);
        return (
          <div className="cb-row" data-testid={`cb-row-${idx}`} key={row.id ?? idx}>
            <select
              aria-label={`field-${idx}`}
              value={curKey}
              onChange={(e) => onFieldChange(idx, e.target.value)}
            >
              {fields.map((f) => (
                <option key={fieldKey(f.scope, f.path)} value={fieldKey(f.scope, f.path)}>{f.label}</option>
              ))}
            </select>

            <select
              aria-label={`operator-${idx}`}
              value={row.operator}
              onChange={(e) => onOperatorChange(idx, e.target.value as Operator, row)}
            >
              {ops.map((op) => <option key={op} value={op}>{op}</option>)}
            </select>

            {showValue && (
              fieldOpt?.options ? (
                <select
                  aria-label={`value-${idx}`}
                  value={String((row.right && 'value' in row.right ? row.right.value : '') ?? '')}
                  onChange={(e) => onValueChange(idx, e.target.value, row)}
                >
                  <option value="">—</option>
                  {fieldOpt.options.map((o) => <option key={o} value={o}>{o}</option>)}
                </select>
              ) : (
                <input
                  aria-label={`value-${idx}`}
                  value={String((row.right && 'value' in row.right ? row.right.value : '') ?? '')}
                  onChange={(e) => onValueChange(idx, e.target.value, row)}
                />
              )
            )}

            <button type="button" aria-label={`delete-${idx}`} onClick={() => deleteRow(idx)}>删除</button>
          </div>
        );
      })}

      <button type="button" data-testid="cb-add" onClick={addRow}>添加条件</button>

      <div data-testid="cb-preview" className="cb-preview">
        {rows.length === 0 ? '—' : toNaturalLanguage(value, labelOf)}
      </div>
    </div>
  );
}

export default ConditionBuilder;
