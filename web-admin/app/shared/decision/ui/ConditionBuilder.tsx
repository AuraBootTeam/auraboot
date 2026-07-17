import { useMemo, useState } from 'react';
import {
  type ConditionNode, type GroupNode, type CompareNode, type Operator, type DataType,
  type PathOperand, cmp, path, lit,
  not, operatorLabel, UNARY_OPERATORS,
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
  valueLabels?: Record<string, string>;
  modelCode?: string;
  modelName?: string;
  operators?: Operator[];
  dictCode?: string;
  reference?: {
    targetEntity?: string;
    valueField?: string;
    displayField?: string;
  };
  required?: boolean;
  visible?: boolean;
  editable?: boolean;
  masked?: boolean;
  permission?: string;
  sourceType?: string;
  sourceRef?: string;
  factKey?: string;
  entityCode?: string;
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

function optionLabel(field: FieldOption | undefined, value: unknown): string {
  const raw = String(value ?? '');
  if (!field) return raw;
  return field.valueLabels?.[raw] ?? raw;
}

function idFor(pathParts: number[]): string {
  return pathParts.length === 0 ? '' : `-${pathParts.join('-')}`;
}

const SCOPE_LABELS: Record<PathOperand['scope'], string> = {
  meta: '元数据',
  event: '事件',
  record: '业务记录',
  before: '变更前',
  after: '变更后',
  process: '流程',
  task: '任务',
  sla: 'SLA',
  actor: '操作者',
  tenant: '租户',
  time: '时间',
  env: '环境',
};

function fieldGroupLabel(field: FieldOption): string {
  return field.modelName || SCOPE_LABELS[field.scope] || field.scope;
}

function fieldSearchText(field: FieldOption): string {
  return [
    field.label,
    field.path,
    field.scope,
    field.modelCode,
    field.modelName,
    field.dataType,
  ]
    .filter(Boolean)
    .join(' ')
    .toLowerCase();
}

function filterFields(fields: FieldOption[], query: string): FieldOption[] {
  const normalized = query.trim().toLowerCase();
  if (!normalized) return fields;
  return fields.filter((field) => fieldSearchText(field).includes(normalized));
}

function groupFields(fields: FieldOption[]): Array<{ label: string; fields: FieldOption[] }> {
  const groups = new Map<string, FieldOption[]>();
  fields.forEach((field) => {
    const label = fieldGroupLabel(field);
    groups.set(label, [...(groups.get(label) ?? []), field]);
  });
  return Array.from(groups, ([label, groupFields]) => ({ label, fields: groupFields }));
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
  const fieldOpt = fieldByKey.get(curKey);
  const dt = left.dataType ?? 'string';
  const ops = fieldOpt?.operators?.length ? fieldOpt.operators : operatorsForDataType(dt);
  const selectableFields = useMemo(() => {
    const hasCurrent = fields.some((field) => fieldKey(field.scope, field.path) === curKey);
    return hasCurrent || !fieldOpt ? fields : [fieldOpt, ...fields];
  }, [curKey, fieldOpt, fields]);
  const groupedFields = useMemo(() => groupFields(selectableFields), [selectableFields]);
  const showValue = !UNARY.has(row.operator);
  const id = idPath.join('-');

  const onFieldChange = (key: string) => {
    const f = fieldByKey.get(key);
    if (!f) return;
    const nextOps = f.operators?.length ? f.operators : operatorsForDataType(f.dataType);
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
        {groupedFields.map((fieldGroup) => (
          <optgroup key={fieldGroup.label} label={fieldGroup.label}>
            {fieldGroup.fields.map((f) => (
              <option key={fieldKey(f.scope, f.path)} value={fieldKey(f.scope, f.path)}>
                {f.label}
              </option>
            ))}
          </optgroup>
        ))}
      </select>

      <select
        aria-label={`operator-${id}`}
        value={row.operator}
        onChange={(e) => onOperatorChange(e.target.value as Operator)}
      >
        {ops.map((op) => <option key={op} value={op}>{operatorLabel(op)}</option>)}
      </select>

      {showValue && (
        fieldOpt?.options ? (
          <select
            aria-label={`value-${id}`}
            value={String((row.right && 'value' in row.right ? row.right.value : '') ?? '')}
            onChange={(e) => onValueChange(e.target.value)}
          >
            <option value="">—</option>
            {fieldOpt.options.map((o) => (
              <option key={o} value={o}>
                {optionLabel(fieldOpt, o)}
              </option>
            ))}
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
        >全部满足</button>
        <button
          type="button"
          data-testid={idPath.length === 0 ? 'op-or' : `op-or${suffix}`}
          aria-pressed={node.op === 'OR'}
          onClick={() => updateNode({ ...node, op: 'OR' })}
        >任一满足</button>
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
        <button
          type="button"
          data-testid={idPath.length === 0 ? 'cb-add' : `cb-add${suffix}`}
          onClick={addRow}
          disabled={fields.length === 0}
        >
          添加条件
        </button>
        <button
          type="button"
          data-testid={idPath.length === 0 ? 'cb-add-group' : `cb-add-group${suffix}`}
          onClick={addGroup}
          disabled={fields.length === 0}
        >
          添加条件组
        </button>
        <button
          type="button"
          data-testid={idPath.length === 0 ? 'cb-add-not' : `cb-add-not${suffix}`}
          onClick={addNot}
          disabled={fields.length === 0}
        >
          添加 NOT
        </button>
      </div>
    </div>
  );
}

export function ConditionBuilder({ value, fields, onChange }: ConditionBuilderProps) {
  const [fieldQuery, setFieldQuery] = useState('');
  const filteredFields = useMemo(() => filterFields(fields, fieldQuery), [fieldQuery, fields]);
  const fieldByKey = useMemo(() => {
    const m = new Map<string, FieldOption>();
    fields.forEach((f) => m.set(fieldKey(f.scope, f.path), f));
    return m;
  }, [fields]);

  const labelOf = (o: PathOperand): string =>
    fieldByKey.get(fieldKey(o.scope, o.path))?.label ?? `${o.scope}.${o.path}`;

  const operandPreview = (operand: CompareNode['left'] | CompareNode['right'], leftField?: FieldOption): string => {
    if (!operand) return '';
    if (operand.type === 'path') return labelOf(operand);
    if (operand.type === 'literal') {
      if (Array.isArray(operand.value)) {
        return operand.value.map((item) => optionLabel(leftField, item)).join('、');
      }
      return optionLabel(leftField, operand.value);
    }
    return `${operand.name}(...)`;
  };

  const previewNode = (node: ConditionNode): string => {
    if (node.type === 'compare') {
      const leftField = node.left.type === 'path'
        ? fieldByKey.get(fieldKey(node.left.scope, node.left.path))
        : undefined;
      const left = operandPreview(node.left, leftField);
      const right = UNARY_OPERATORS.has(node.operator) ? '' : ` ${operandPreview(node.right, leftField)}`;
      return `【${left} ${operatorLabel(node.operator)}${right}】`;
    }
    if (node.type === 'not') return `非(${previewNode(node.child)})`;
    const parts = node.children
      .filter((child) => !(child.type === 'compare' && child.enabled === false))
      .map(previewNode);
    return `(${parts.join(node.op === 'AND' ? ' 并且 ' : ' 或 ')})`;
  };

  return (
    <div data-testid="condition-builder">
      <div className="cb-field-tools">
        <label>
          字段搜索
          <input
            aria-label="condition-field-search"
            value={fieldQuery}
            onChange={(event) => setFieldQuery(event.target.value)}
            placeholder="搜索字段、模型或路径"
          />
        </label>
        <span data-testid="cb-field-result-count">
          {filteredFields.length} / {fields.length}
        </span>
      </div>

      {fieldQuery.trim() && filteredFields.length === 0 && (
        <div className="cb-field-empty" data-testid="cb-field-empty">
          没有匹配字段
        </div>
      )}

      <GroupEditor
        node={value}
        idPath={[]}
        fields={filteredFields}
        fieldByKey={fieldByKey}
        updateNode={onChange}
      />

      <div data-testid="cb-preview" className="cb-preview">
        {value.children.length === 0 ? '—' : previewNode(value)}
      </div>
    </div>
  );
}

export default ConditionBuilder;
