import { useMemo } from 'react';
import {
  type ConditionNode, type GroupNode, type CompareNode, type Operator, type DataType,
  type PathOperand, cmp, path, lit, toNaturalLanguage,
  not,
} from '../ast/conditionAst';

/**
 * DecisionOps Condition Builder — a controlled UI for authoring a Condition AST
 * (docs/1.md §14 / §21.2): compare rows, nested AND/OR groups, NOT wrappers,
 * and a live natural-language preview. Output is the rich AST the backend consumes;
 * the backend stays authoritative (this is authoring + preview only).
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

function idFor(pathParts: number[]): string {
  return pathParts.length === 0 ? '' : `-${pathParts.join('-')}`;
}

function firstCompare(fields: FieldOption[]): CompareNode | null {
  const f = fields[0];
  if (!f) return null;
  return cmp(path(f.scope, f.path, f.dataType), operatorsForDataType(f.dataType)[0], lit('', f.dataType));
}

function firstGroup(fields: FieldOption[]): GroupNode | null {
  const initial = firstCompare(fields);
  return initial ? { type: 'group', op: 'AND', children: [initial] } : null;
}

function firstNot(fields: FieldOption[]): ConditionNode | null {
  const initial = firstCompare(fields);
  return initial ? not(initial) : null;
}

function replaceChild(children: ConditionNode[], index: number, next: ConditionNode): ConditionNode[] {
  return children.map((child, childIndex) => (childIndex === index ? next : child));
}

interface CompareEditorProps {
  row: CompareNode;
  idPath: number[];
  fields: FieldOption[];
  fieldByKey: Map<string, FieldOption>;
  updateRow: (next: CompareNode) => void;
  deleteRow: () => void;
}

function CompareEditor({
  row,
  idPath,
  fields,
  fieldByKey,
  updateRow,
  deleteRow,
}: CompareEditorProps) {
  const left = row.left as PathOperand;
  const curKey = fieldKey(left.scope, left.path);
  const dt = left.dataType ?? 'string';
  const ops = operatorsForDataType(dt);
  const fieldOpt = fieldByKey.get(curKey);
  const showValue = !UNARY.has(row.operator);
  const id = idPath.join('-');

  const onFieldChange = (key: string) => {
    const f = fieldByKey.get(key);
    if (!f) return;
    const nextOps = operatorsForDataType(f.dataType);
    updateRow(cmp(path(f.scope, f.path, f.dataType), nextOps[0], lit('', f.dataType)));
  };

  const onOperatorChange = (op: Operator) => {
    updateRow({ ...row, operator: op, right: UNARY.has(op) ? undefined : (row.right ?? lit('')) });
  };

  const onValueChange = (val: string) => {
    const nextDt = (row.left as PathOperand).dataType;
    updateRow({ ...row, right: lit(val, nextDt) });
  };

  return (
    <div className="cb-row" data-testid={`cb-row-${id}`} key={id}>
      <select
        aria-label={`field-${id}`}
        value={curKey}
        onChange={(e) => onFieldChange(e.target.value)}
      >
        {fields.map((f) => (
          <option key={fieldKey(f.scope, f.path)} value={fieldKey(f.scope, f.path)}>
            {f.label}
          </option>
        ))}
      </select>

      <select
        aria-label={`operator-${id}`}
        value={row.operator}
        onChange={(e) => onOperatorChange(e.target.value as Operator)}
      >
        {ops.map((op) => <option key={op} value={op}>{op}</option>)}
      </select>

      {showValue && (
        fieldOpt?.options ? (
          <select
            aria-label={`value-${id}`}
            value={String((row.right && 'value' in row.right ? row.right.value : '') ?? '')}
            onChange={(e) => onValueChange(e.target.value)}
          >
            <option value="">—</option>
            {fieldOpt.options.map((o) => <option key={o} value={o}>{o}</option>)}
          </select>
        ) : (
          <input
            aria-label={`value-${id}`}
            value={String((row.right && 'value' in row.right ? row.right.value : '') ?? '')}
            onChange={(e) => onValueChange(e.target.value)}
          />
        )
      )}

      <button type="button" aria-label={`delete-${id}`} onClick={deleteRow}>删除</button>
    </div>
  );
}

interface NodeEditorProps {
  node: ConditionNode;
  idPath: number[];
  fields: FieldOption[];
  fieldByKey: Map<string, FieldOption>;
  updateNode: (next: ConditionNode) => void;
  deleteNode?: () => void;
}

function NodeEditor({
  node,
  idPath,
  fields,
  fieldByKey,
  updateNode,
  deleteNode,
}: NodeEditorProps) {
  if (node.type === 'compare') {
    return (
      <CompareEditor
        row={node}
        idPath={idPath}
        fields={fields}
        fieldByKey={fieldByKey}
        updateRow={updateNode}
        deleteRow={deleteNode ?? (() => undefined)}
      />
    );
  }

  if (node.type === 'not') {
    const id = idPath.join('-');
    return (
      <div className="cb-not" data-testid={`cb-not-${id}`}>
        <div className="cb-not-header">
          <strong>非(NOT)</strong>
          {deleteNode && (
            <button type="button" aria-label={`delete-not-${id}`} onClick={deleteNode}>
              删除
            </button>
          )}
        </div>
        <NodeEditor
          node={node.child}
          idPath={[...idPath, 0]}
          fields={fields}
          fieldByKey={fieldByKey}
          updateNode={(nextChild) => updateNode({ ...node, child: nextChild })}
        />
      </div>
    );
  }

  return (
    <GroupEditor
      node={node}
      idPath={idPath}
      fields={fields}
      fieldByKey={fieldByKey}
      updateNode={updateNode}
      deleteNode={deleteNode}
    />
  );
}

interface GroupEditorProps {
  node: GroupNode;
  idPath: number[];
  fields: FieldOption[];
  fieldByKey: Map<string, FieldOption>;
  updateNode: (next: GroupNode) => void;
  deleteNode?: () => void;
}

function GroupEditor({
  node,
  idPath,
  fields,
  fieldByKey,
  updateNode,
  deleteNode,
}: GroupEditorProps) {
  const suffix = idFor(idPath);
  const addRow = () => {
    const next = firstCompare(fields);
    if (!next) return;
    updateNode({ ...node, children: [...node.children, next] });
  };
  const addGroup = () => {
    const next = firstGroup(fields);
    if (!next) return;
    updateNode({ ...node, children: [...node.children, next] });
  };
  const addNot = () => {
    const next = firstNot(fields);
    if (!next) return;
    updateNode({ ...node, children: [...node.children, next] });
  };

  return (
    <div className="cb-group" data-testid={idPath.length === 0 ? 'cb-group-root' : `cb-group${suffix}`}>
      {idPath.length > 0 && (
        <div className="cb-group-header">
          <strong>条件组</strong>
          {deleteNode && (
            <button type="button" aria-label={`delete-group-${idPath.join('-')}`} onClick={deleteNode}>
              删除
            </button>
          )}
        </div>
      )}

      <div className="cb-group-op">
        <button
          type="button"
          data-testid={idPath.length === 0 ? 'op-and' : `op-and${suffix}`}
          aria-pressed={node.op === 'AND'}
          onClick={() => updateNode({ ...node, op: 'AND' })}
        >并且(AND)</button>
        <button
          type="button"
          data-testid={idPath.length === 0 ? 'op-or' : `op-or${suffix}`}
          aria-pressed={node.op === 'OR'}
          onClick={() => updateNode({ ...node, op: 'OR' })}
        >或(OR)</button>
      </div>

      {node.children.length === 0 && (
        <div data-testid={idPath.length === 0 ? 'cb-empty' : `cb-empty${suffix}`}>暂无条件</div>
      )}

      {node.children.map((child, childIndex) => (
        <NodeEditor
          key={`${child.type}-${childIndex}`}
          node={child}
          idPath={[...idPath, childIndex]}
          fields={fields}
          fieldByKey={fieldByKey}
          updateNode={(nextChild) =>
            updateNode({ ...node, children: replaceChild(node.children, childIndex, nextChild) })
          }
          deleteNode={() =>
            updateNode({
              ...node,
              children: node.children.filter((_, index) => index !== childIndex),
            })
          }
        />
      ))}

      <div className="cb-actions">
        <button type="button" data-testid={idPath.length === 0 ? 'cb-add' : `cb-add${suffix}`} onClick={addRow}>
          添加条件
        </button>
        <button
          type="button"
          data-testid={idPath.length === 0 ? 'cb-add-group' : `cb-add-group${suffix}`}
          onClick={addGroup}
        >
          添加条件组
        </button>
        <button
          type="button"
          data-testid={idPath.length === 0 ? 'cb-add-not' : `cb-add-not${suffix}`}
          onClick={addNot}
        >
          添加 NOT
        </button>
      </div>
    </div>
  );
}

export function ConditionBuilder({ value, fields, onChange }: ConditionBuilderProps) {
  const fieldByKey = useMemo(() => {
    const m = new Map<string, FieldOption>();
    fields.forEach((f) => m.set(fieldKey(f.scope, f.path), f));
    return m;
  }, [fields]);

  const labelOf = (o: PathOperand): string =>
    fieldByKey.get(fieldKey(o.scope, o.path))?.label ?? `${o.scope}.${o.path}`;

  return (
    <div data-testid="condition-builder">
      <GroupEditor
        node={value}
        idPath={[]}
        fields={fields}
        fieldByKey={fieldByKey}
        updateNode={onChange}
      />

      <div data-testid="cb-preview" className="cb-preview">
        {value.children.length === 0 ? '—' : toNaturalLanguage(value, labelOf)}
      </div>
    </div>
  );
}

export default ConditionBuilder;
