import { useEffect, useMemo, useState } from 'react';
import { Database, Search, UserRound, X } from 'lucide-react';
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
const COLLECTION_OPERATORS: ReadonlySet<Operator> = new Set<Operator>(['IN', 'NOT_IN']);

type ValueLabelMap = Record<string, string>;

interface ReferenceOption {
  value: string;
  label: string;
  subtitle?: string;
  disabled?: boolean;
}

export function operatorsForDataType(dt: DataType): Operator[] {
  return OPERATORS_BY_TYPE[dt] ?? ['EQ', 'NE'];
}

const fieldKey = (scope: string, p: string): string => `${scope}:${p}`;
const fieldOptionKey = (field: FieldOption): string => fieldKey(field.scope, field.path);

function optionLabel(
  field: FieldOption | undefined,
  value: unknown,
  runtimeValueLabels?: ValueLabelMap,
): string {
  const raw = String(value ?? '');
  if (!field) return raw;
  return runtimeValueLabels?.[raw] ?? field.valueLabels?.[raw] ?? raw;
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

function literalValue(row: CompareNode): unknown {
  return row.right && 'value' in row.right ? row.right.value : '';
}

function literalArrayValue(row: CompareNode): string[] {
  const value = literalValue(row);
  if (Array.isArray(value)) return value.map((item) => String(item));
  if (value === undefined || value === null || value === '') return [];
  return [String(value)];
}

function literalScalarValue(row: CompareNode): string {
  const value = literalValue(row);
  if (Array.isArray(value)) return String(value[0] ?? '');
  return String(value ?? '');
}

function splitCollectionInput(value: string): string[] {
  return value.split(',').map((item) => item.trim()).filter(Boolean);
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

function firstString(record: Record<string, unknown> | null | undefined, keys: string[]): string | undefined {
  if (!record) return undefined;
  for (const key of keys) {
    const value = record[key];
    if (value !== undefined && value !== null && String(value).trim() !== '') {
      return String(value);
    }
  }
  return undefined;
}

function dataArray(payload: unknown): unknown[] {
  const root = asRecord(payload);
  const data = root?.data;
  if (Array.isArray(data)) return data;
  const dataRecord = asRecord(data);
  const content = dataRecord?.content;
  if (Array.isArray(content)) return content;
  const records = dataRecord?.records;
  if (Array.isArray(records)) return records;
  return [];
}

async function fetchJson(url: string): Promise<unknown | null> {
  const response = await fetch(url, { credentials: 'same-origin' });
  if (!response.ok) return null;
  return response.json() as Promise<unknown>;
}

function normalizeUserOption(value: unknown): ReferenceOption | null {
  const record = asRecord(value);
  const user = asRecord(record?.user);
  const optionValue = firstString(record, ['pid', 'id', 'userPid'])
    ?? firstString(user, ['pid', 'id']);
  if (!optionValue) return null;
  const label = firstString(record, ['displayName', 'name', 'realName', 'username', 'email'])
    ?? firstString(user, ['displayName', 'name', 'realName', 'username', 'email'])
    ?? optionValue;
  const email = firstString(record, ['email']) ?? firstString(user, ['email']);
  const department = firstString(record, ['department']) ?? firstString(user, ['department']);
  return {
    value: optionValue,
    label,
    subtitle: [email, department].filter(Boolean).join(' · ') || undefined,
  };
}

function normalizeDynamicOption(value: unknown): ReferenceOption | null {
  const record = asRecord(value);
  const optionValue = firstString(record, ['value', 'pid', 'id']);
  if (!optionValue) return null;
  return {
    value: optionValue,
    label: firstString(record, ['label', 'name', 'displayName', 'title', 'code']) ?? optionValue,
    disabled: record?.disabled === true,
  };
}

function isUserReference(field: FieldOption | undefined): boolean {
  if (!field) return false;
  const target = String(field.reference?.targetEntity ?? '').toLowerCase();
  return field.dataType === 'user'
    || target.includes('user')
    || target === 'sys_user'
    || target === 'ab_user';
}

function isReferenceValueField(field: FieldOption | undefined): field is FieldOption {
  return Boolean(field && !field.options?.length && (field.reference || field.dataType === 'user'));
}

function fieldCodeFromPath(fieldPath: string): string {
  if (fieldPath.startsWith('data.')) return fieldPath.slice('data.'.length);
  const parts = fieldPath.split('.');
  return parts[parts.length - 1] || fieldPath;
}

function referenceTargetLabel(field: FieldOption): string {
  if (isUserReference(field)) return '用户';
  return field.reference?.targetEntity || field.modelName || '引用对象';
}

function mergeLabelsFromOptions(options: ReferenceOption[]): ValueLabelMap {
  return Object.fromEntries(options.map((option) => [option.value, option.label]));
}

async function loadReferenceOptions(field: FieldOption, query: string): Promise<ReferenceOption[]> {
  if (isUserReference(field)) {
    const payload = await fetchJson(
      `/api/admin/users/search?keyword=${encodeURIComponent(query)}&size=20`,
    );
    return dataArray(payload).map(normalizeUserOption).filter((item): item is ReferenceOption => item !== null);
  }
  if (!field.modelCode) return [];
  const fieldName = fieldCodeFromPath(field.path);
  const suffix = query.trim() ? `?keyword=${encodeURIComponent(query.trim())}` : '';
  const payload = await fetchJson(
    `/api/dynamic/${encodeURIComponent(field.modelCode)}/field-options/${encodeURIComponent(fieldName)}${suffix}`,
  );
  return dataArray(payload).map(normalizeDynamicOption).filter((item): item is ReferenceOption => item !== null);
}

function optionFromUserPayload(payload: unknown, fallbackValue: string): ReferenceOption | null {
  const data = asRecord(payload)?.data;
  return normalizeUserOption(data) ?? { value: fallbackValue, label: fallbackValue };
}

function optionFromDynamicRecord(
  payload: unknown,
  field: FieldOption,
  fallbackValue: string,
): ReferenceOption | null {
  const data = asRecord(payload)?.data;
  const record = asRecord(data);
  if (!record) return { value: fallbackValue, label: fallbackValue };
  const displayField = field.reference?.displayField;
  const label = displayField && record[displayField] != null
    ? String(record[displayField])
    : firstString(record, ['displayName', 'name', 'title', 'code', 'pid', 'id']) ?? fallbackValue;
  return { value: fallbackValue, label };
}

async function resolveReferenceOption(field: FieldOption, value: string): Promise<ReferenceOption | null> {
  if (!value) return null;
  if (isUserReference(field)) {
    return optionFromUserPayload(await fetchJson(`/api/admin/users/${encodeURIComponent(value)}`), value);
  }
  const targetEntity = field.reference?.targetEntity;
  if (!targetEntity) return { value, label: value };
  return optionFromDynamicRecord(
    await fetchJson(`/api/dynamic/${encodeURIComponent(targetEntity)}/${encodeURIComponent(value)}`),
    field,
    value,
  );
}

interface ReferenceValuePickerProps {
  id: string;
  field: FieldOption;
  value: string | string[];
  multiple: boolean;
  runtimeValueLabels?: ValueLabelMap;
  onChange: (value: string | string[]) => void;
  onValueLabelsChange: (labels: ValueLabelMap) => void;
}

function ReferenceValuePicker({
  id,
  field,
  value,
  multiple,
  runtimeValueLabels,
  onChange,
  onValueLabelsChange,
}: ReferenceValuePickerProps) {
  const selectedValues = useMemo(() => (
    Array.isArray(value) ? value.map(String) : value ? [String(value)] : []
  ), [value]);
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(false);
  const [options, setOptions] = useState<ReferenceOption[]>([]);
  const selectedKey = selectedValues.join('\u0001');
  const mergedLabels = useMemo<ValueLabelMap>(() => ({
    ...(field.valueLabels ?? {}),
    ...(runtimeValueLabels ?? {}),
    ...mergeLabelsFromOptions(options),
  }), [field.valueLabels, options, runtimeValueLabels]);

  const rememberOptions = (nextOptions: ReferenceOption[]) => {
    const labels = mergeLabelsFromOptions(nextOptions);
    if (Object.keys(labels).length > 0) {
      onValueLabelsChange(labels);
    }
  };

  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    setLoading(true);
    loadReferenceOptions(field, query)
      .then((loaded) => {
        if (cancelled) return;
        setOptions(loaded);
        rememberOptions(loaded);
      })
      .catch(() => {
        if (!cancelled) setOptions([]);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [field, open, query]);

  useEffect(() => {
    const missingValues = selectedValues.filter((item) => !mergedLabels[item]);
    if (missingValues.length === 0) return;
    let cancelled = false;
    Promise.all(missingValues.map((item) => resolveReferenceOption(field, item)))
      .then((resolved) => {
        if (cancelled) return;
        const labels = mergeLabelsFromOptions(
          resolved.filter((item): item is ReferenceOption => item !== null),
        );
        if (Object.keys(labels).length > 0) {
          onValueLabelsChange(labels);
        }
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [field, mergedLabels, onValueLabelsChange, selectedKey, selectedValues]);

  const labelOf = (item: string) => mergedLabels[item] ?? item;
  const selectedSet = new Set(selectedValues);
  const targetLabel = referenceTargetLabel(field);

  const commit = (option: ReferenceOption) => {
    if (option.disabled) return;
    onValueLabelsChange({ [option.value]: option.label });
    if (multiple) {
      const next = selectedSet.has(option.value)
        ? selectedValues.filter((item) => item !== option.value)
        : [...selectedValues, option.value];
      onChange(next);
      return;
    }
    onChange(option.value);
    setOpen(false);
  };

  const remove = (target: string) => {
    const next = selectedValues.filter((item) => item !== target);
    onChange(multiple ? next : '');
  };

  return (
    <div className="cb-reference-picker" data-testid={`reference-value-picker-${id}`}>
      <div className="cb-reference-control">
        <button
          type="button"
          aria-label={`value-${id}`}
          data-testid={`reference-value-trigger-${id}`}
          className="cb-reference-trigger"
          onClick={() => setOpen((next) => !next)}
        >
          <span className="cb-reference-icon" aria-hidden="true">
            {isUserReference(field) ? <UserRound size={14} /> : <Database size={14} />}
          </span>
          <span className="cb-reference-values">
            {selectedValues.length > 0 ? selectedValues.map(labelOf).join('、') : `选择${targetLabel}`}
          </span>
        </button>
        {selectedValues.length > 0 && (
          <button
            type="button"
            className="cb-reference-clear"
            aria-label={`clear-value-${id}`}
            onClick={() => onChange(multiple ? [] : '')}
          >
            <X size={12} />
          </button>
        )}
      </div>

      {selectedValues.length > 0 && multiple && (
        <div className="cb-reference-chips" data-testid={`reference-value-selected-${id}`}>
          {selectedValues.map((item) => (
            <span key={item} className="cb-reference-chip">
              {labelOf(item)}
              <button type="button" aria-label={`remove-value-${id}-${item}`} onClick={() => remove(item)}>
                <X size={10} />
              </button>
            </span>
          ))}
        </div>
      )}

      {open && (
        <div className="cb-reference-menu" data-testid={`reference-value-menu-${id}`}>
          <label className="cb-reference-search">
            <Search size={14} aria-hidden="true" />
            <input
              aria-label={`reference-search-${id}`}
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder={`搜索${targetLabel}`}
            />
          </label>
          <div className="cb-reference-meta" data-testid={`reference-value-meta-${id}`}>
            {targetLabel}
            {field.reference?.valueField ? ` · ${field.reference.valueField}` : ''}
            {field.reference?.displayField ? ` / ${field.reference.displayField}` : ''}
          </div>
          <div className="cb-reference-options">
            {loading ? (
              <div className="cb-reference-empty">加载中...</div>
            ) : options.length === 0 ? (
              <div className="cb-reference-empty">没有匹配项</div>
            ) : options.map((option) => (
              <button
                key={option.value}
                type="button"
                disabled={option.disabled}
                data-testid={`reference-value-option-${id}-${option.value}`}
                className="cb-reference-option"
                aria-pressed={selectedSet.has(option.value)}
                onClick={() => commit(option)}
              >
                <span>{option.label}</span>
                {option.subtitle && <small>{option.subtitle}</small>}
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

function replaceChild(children: ConditionNode[], index: number, next: ConditionNode): ConditionNode[] {
  return children.map((child, childIndex) => (childIndex === index ? next : child));
}

interface CompareEditorProps {
  row: CompareNode;
  idPath: number[];
  fields: FieldOption[];
  fieldByKey: Map<string, FieldOption>;
  runtimeValueLabels: Record<string, ValueLabelMap>;
  rememberValueLabels: (fieldKey: string, labels: ValueLabelMap) => void;
  updateRow: (next: CompareNode) => void;
  deleteRow: () => void;
}

function CompareEditor({
  row,
  idPath,
  fields,
  fieldByKey,
  runtimeValueLabels,
  rememberValueLabels,
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
  const rowValueLabels = runtimeValueLabels[curKey];

  const onFieldChange = (key: string) => {
    const f = fieldByKey.get(key);
    if (!f) return;
    const nextOps = f.operators?.length ? f.operators : operatorsForDataType(f.dataType);
    updateRow(cmp(path(f.scope, f.path, f.dataType), nextOps[0], lit('', f.dataType)));
  };

  const onOperatorChange = (op: Operator) => {
    const nextDt = (row.left as PathOperand).dataType;
    const currentValue = literalValue(row);
    const nextRight = UNARY.has(op)
      ? undefined
      : COLLECTION_OPERATORS.has(op)
        ? lit(literalArrayValue(row), nextDt)
        : lit(Array.isArray(currentValue) ? (currentValue[0] ?? '') : (currentValue ?? ''), nextDt);
    updateRow({ ...row, operator: op, right: nextRight });
  };

  const onValueChange = (val: string | string[]) => {
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
          COLLECTION_OPERATORS.has(row.operator) ? (
            <select
              aria-label={`value-${id}`}
              multiple
              size={Math.min(4, Math.max(2, fieldOpt.options.length))}
              value={literalArrayValue(row)}
              onChange={(e) =>
                onValueChange(Array.from(e.currentTarget.selectedOptions).map((option) => option.value))
              }
            >
              {fieldOpt.options.map((o) => (
                <option key={o} value={o}>
                  {optionLabel(fieldOpt, o, rowValueLabels)}
                </option>
              ))}
            </select>
          ) : (
            <select
              aria-label={`value-${id}`}
              value={literalScalarValue(row)}
              onChange={(e) => onValueChange(e.target.value)}
            >
              <option value="">—</option>
              {fieldOpt.options.map((o) => (
                <option key={o} value={o}>
                  {optionLabel(fieldOpt, o, rowValueLabels)}
                </option>
              ))}
            </select>
          )
        ) : isReferenceValueField(fieldOpt) ? (
          <ReferenceValuePicker
            id={id}
            field={fieldOpt}
            value={COLLECTION_OPERATORS.has(row.operator)
              ? literalArrayValue(row)
              : literalScalarValue(row)}
            multiple={COLLECTION_OPERATORS.has(row.operator)}
            runtimeValueLabels={rowValueLabels}
            onChange={onValueChange}
            onValueLabelsChange={(labels) => rememberValueLabels(curKey, labels)}
          />
        ) : (
          <input
            aria-label={`value-${id}`}
            value={COLLECTION_OPERATORS.has(row.operator)
              ? literalArrayValue(row).join(', ')
              : literalScalarValue(row)}
            onChange={(e) =>
              onValueChange(COLLECTION_OPERATORS.has(row.operator)
                ? splitCollectionInput(e.target.value)
                : e.target.value)
            }
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
  runtimeValueLabels: Record<string, ValueLabelMap>;
  rememberValueLabels: (fieldKey: string, labels: ValueLabelMap) => void;
  updateNode: (next: ConditionNode) => void;
  deleteNode?: () => void;
}

function NodeEditor({
  node,
  idPath,
  fields,
  fieldByKey,
  runtimeValueLabels,
  rememberValueLabels,
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
        runtimeValueLabels={runtimeValueLabels}
        rememberValueLabels={rememberValueLabels}
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
          runtimeValueLabels={runtimeValueLabels}
          rememberValueLabels={rememberValueLabels}
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
      runtimeValueLabels={runtimeValueLabels}
      rememberValueLabels={rememberValueLabels}
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
  runtimeValueLabels: Record<string, ValueLabelMap>;
  rememberValueLabels: (fieldKey: string, labels: ValueLabelMap) => void;
  updateNode: (next: GroupNode) => void;
  deleteNode?: () => void;
}

function GroupEditor({
  node,
  idPath,
  fields,
  fieldByKey,
  runtimeValueLabels,
  rememberValueLabels,
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
          runtimeValueLabels={runtimeValueLabels}
          rememberValueLabels={rememberValueLabels}
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
  const [runtimeValueLabels, setRuntimeValueLabels] = useState<Record<string, ValueLabelMap>>({});
  const filteredFields = useMemo(() => filterFields(fields, fieldQuery), [fieldQuery, fields]);
  const fieldByKey = useMemo(() => {
    const m = new Map<string, FieldOption>();
    fields.forEach((f) => m.set(fieldKey(f.scope, f.path), f));
    return m;
  }, [fields]);

  const rememberValueLabels = (key: string, labels: ValueLabelMap) => {
    if (Object.keys(labels).length === 0) return;
    setRuntimeValueLabels((current) => ({
      ...current,
      [key]: {
        ...(current[key] ?? {}),
        ...labels,
      },
    }));
  };

  const labelOf = (o: PathOperand): string =>
    fieldByKey.get(fieldKey(o.scope, o.path))?.label ?? `${o.scope}.${o.path}`;

  const operandPreview = (operand: CompareNode['left'] | CompareNode['right'], leftField?: FieldOption): string => {
    if (!operand) return '';
    if (operand.type === 'path') return labelOf(operand);
    if (operand.type === 'literal') {
      if (Array.isArray(operand.value)) {
        const labels = leftField ? runtimeValueLabels[fieldOptionKey(leftField)] : undefined;
        return operand.value.map((item) => optionLabel(leftField, item, labels)).join('、');
      }
      const labels = leftField ? runtimeValueLabels[fieldOptionKey(leftField)] : undefined;
      return optionLabel(leftField, operand.value, labels);
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
        runtimeValueLabels={runtimeValueLabels}
        rememberValueLabels={rememberValueLabels}
        updateNode={onChange}
      />

      <div data-testid="cb-preview" className="cb-preview">
        {value.children.length === 0 ? '—' : previewNode(value)}
      </div>
    </div>
  );
}

export default ConditionBuilder;
