import {
  useDecisionConditionText,
  decisionConditionText,
  decisionConditionOperatorLabel,
  type DecisionConditionTextKey,
} from './decisionConditionText';
import { createContext, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { ArrowLeft, ArrowRight, Layers, Plus, Play, X as CloseIcon } from 'lucide-react';
import {
  compatibleFields,
  modelKey,
  nodeKey,
  nodeAt,
  replaceAt,
  wrapSelection,
  conditionLeaves,
} from './conditionTreeEditing';
import { ConditionServerTrial } from './ConditionServerTrial';
import type { ConditionTrace } from '../api/decisionApi';
import './condition-expression.css';
import { Database, Search, UserRound, X } from 'lucide-react';
import {
  type ConditionNode,
  type GroupNode,
  type CompareNode,
  type Operator,
  type DataType,
  type PathOperand,
  cmp,
  path,
  lit,
  not,
  UNARY_OPERATORS,
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
  readOnly?: boolean;
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
  date: ['EQ', 'NE', 'GT', 'GTE', 'LT', 'LTE', 'BETWEEN', 'IS_NULL', 'IS_NOT_NULL'],
  time: ['EQ', 'NE', 'GT', 'GTE', 'LT', 'LTE', 'BETWEEN', 'IS_NULL', 'IS_NOT_NULL'],
  datetime: ['GT', 'GTE', 'LT', 'LTE', 'BETWEEN', 'IS_NULL', 'IS_NOT_NULL'],
};

const UNARY: ReadonlySet<Operator> = new Set<Operator>([
  'IS_NULL',
  'IS_NOT_NULL',
  'IS_EMPTY',
  'IS_NOT_EMPTY',
  'CHANGED',
]);
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

const SCOPE_LABEL_KEYS: Record<PathOperand['scope'], DecisionConditionTextKey> = {
  meta: 'scopeMeta',
  event: 'scopeEvent',
  record: 'scopeRecord',
  before: 'scopeBefore',
  after: 'scopeAfter',
  process: 'scopeProcess',
  task: 'scopeTask',
  sla: 'scopeSLA',
  actor: 'scopeActor',
  tenant: 'scopeTenant',
  time: 'scopeTime',
  env: 'scopeEnvironment',
};

function fieldGroupLabel(field: FieldOption, locale: string): string {
  return (
    field.modelName || decisionConditionText(SCOPE_LABEL_KEYS[field.scope], locale) || field.scope
  );
}

function fieldSearchText(field: FieldOption): string {
  return [field.label, field.path, field.scope, field.modelCode, field.modelName, field.dataType]
    .filter(Boolean)
    .join(' ')
    .toLowerCase();
}

function filterFields(fields: FieldOption[], query: string): FieldOption[] {
  const normalized = query.trim().toLowerCase();
  if (!normalized) return fields;
  return fields.filter((field) => fieldSearchText(field).includes(normalized));
}

function groupFields(
  fields: FieldOption[],
  locale: string,
): Array<{ label: string; fields: FieldOption[] }> {
  const groups = new Map<string, FieldOption[]>();
  fields.forEach((field) => {
    const label = fieldGroupLabel(field, locale);
    groups.set(label, [...(groups.get(label) ?? []), field]);
  });
  return Array.from(groups, ([label, groupFields]) => ({ label, fields: groupFields }));
}

function firstCompare(fields: FieldOption[]): CompareNode | null {
  const f = fields[0];
  if (!f) return null;
  return cmp(
    path(f.scope, f.path, f.dataType),
    operatorsForDataType(f.dataType)[0],
    lit('', f.dataType),
  );
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
  return value
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean);
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}

function firstString(
  record: Record<string, unknown> | null | undefined,
  keys: string[],
): string | undefined {
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
  const optionValue =
    firstString(record, ['pid', 'id', 'userPid']) ?? firstString(user, ['pid', 'id']);
  if (!optionValue) return null;
  const label =
    firstString(record, ['displayName', 'name', 'realName', 'username', 'email']) ??
    firstString(user, ['displayName', 'name', 'realName', 'username', 'email']) ??
    optionValue;
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
  return (
    field.dataType === 'user' ||
    target.includes('user') ||
    target === 'sys_user' ||
    target === 'ab_user'
  );
}

function isReferenceValueField(field: FieldOption | undefined): field is FieldOption {
  return Boolean(field && !field.options?.length && (field.reference || field.dataType === 'user'));
}

function fieldCodeFromPath(fieldPath: string): string {
  if (fieldPath.startsWith('data.')) return fieldPath.slice('data.'.length);
  const parts = fieldPath.split('.');
  return parts[parts.length - 1] || fieldPath;
}

function referenceTargetLabel(field: FieldOption, locale: string): string {
  if (isUserReference(field)) return decisionConditionText('user', locale);
  return (
    field.reference?.targetEntity || field.modelName || decisionConditionText('reference', locale)
  );
}

function mergeLabelsFromOptions(options: ReferenceOption[]): ValueLabelMap {
  return Object.fromEntries(options.map((option) => [option.value, option.label]));
}

async function loadReferenceOptions(field: FieldOption, query: string): Promise<ReferenceOption[]> {
  if (isUserReference(field)) {
    const payload = await fetchJson(
      `/api/admin/users/search?keyword=${encodeURIComponent(query)}&size=20`,
    );
    return dataArray(payload)
      .map(normalizeUserOption)
      .filter((item): item is ReferenceOption => item !== null);
  }
  if (!field.modelCode) return [];
  const fieldName = fieldCodeFromPath(field.path);
  const suffix = query.trim() ? `?keyword=${encodeURIComponent(query.trim())}` : '';
  const payload = await fetchJson(
    `/api/dynamic/${encodeURIComponent(field.modelCode)}/field-options/${encodeURIComponent(fieldName)}${suffix}`,
  );
  return dataArray(payload)
    .map(normalizeDynamicOption)
    .filter((item): item is ReferenceOption => item !== null);
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
  const label =
    displayField && record[displayField] != null
      ? String(record[displayField])
      : (firstString(record, ['displayName', 'name', 'title', 'code', 'pid', 'id']) ??
        fallbackValue);
  return { value: fallbackValue, label };
}

async function resolveReferenceOption(
  field: FieldOption,
  value: string,
): Promise<ReferenceOption | null> {
  if (!value) return null;
  if (isUserReference(field)) {
    return optionFromUserPayload(
      await fetchJson(`/api/admin/users/${encodeURIComponent(value)}`),
      value,
    );
  }
  const targetEntity = field.reference?.targetEntity;
  if (!targetEntity) return { value, label: value };
  return optionFromDynamicRecord(
    await fetchJson(
      `/api/dynamic/${encodeURIComponent(targetEntity)}/${encodeURIComponent(value)}`,
    ),
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
  const { locale, text } = useDecisionConditionText();
  const selectedValues = useMemo(
    () => (Array.isArray(value) ? value.map(String) : value ? [String(value)] : []),
    [value],
  );
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(false);
  const [loadFailed, setLoadFailed] = useState(false);
  const [options, setOptions] = useState<ReferenceOption[]>([]);
  const selectedKey = selectedValues.join('\u0001');
  const mergedLabels = useMemo<ValueLabelMap>(
    () => ({
      ...(field.valueLabels ?? {}),
      ...(runtimeValueLabels ?? {}),
      ...mergeLabelsFromOptions(options),
    }),
    [field.valueLabels, options, runtimeValueLabels],
  );

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
    setLoadFailed(false);
    loadReferenceOptions(field, query)
      .then((loaded) => {
        if (cancelled) return;
        setOptions(loaded);
        rememberOptions(loaded);
      })
      .catch(() => {
        if (!cancelled) {
          setOptions([]);
          setLoadFailed(true);
        }
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
  const targetLabel = referenceTargetLabel(field, locale);

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
            {selectedValues.length > 0
              ? selectedValues.map(labelOf).join(text('listSeparator'))
              : text('selectReference', { target: targetLabel })}
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
              <button
                type="button"
                aria-label={`remove-value-${id}-${item}`}
                onClick={() => remove(item)}
              >
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
              placeholder={text('searchReference', { target: targetLabel })}
            />
          </label>
          <div className="cb-reference-meta" data-testid={`reference-value-meta-${id}`}>
            {targetLabel}
            {field.reference?.valueField ? ` · ${field.reference.valueField}` : ''}
            {field.reference?.displayField ? ` / ${field.reference.displayField}` : ''}
          </div>
          <div className="cb-reference-options">
            {loading ? (
              <div className="cb-reference-empty">{text('loading')}</div>
            ) : loadFailed ? (
              <div className="cb-reference-empty is-error" role="alert">
                {text('loadFailed')}
              </div>
            ) : options.length === 0 ? (
              <div className="cb-reference-empty">{text('noMatches')}</div>
            ) : (
              options.map((option) => (
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
              ))
            )}
          </div>
        </div>
      )}
    </div>
  );
}

function replaceChild(
  children: ConditionNode[],
  index: number,
  next: ConditionNode,
): ConditionNode[] {
  return children.map((child, childIndex) => (childIndex === index ? next : child));
}

interface EditingContextValue {
  selected: string[];
  toggle: (parts: number[]) => void;
  removeGroup: (parts: number[], node: ConditionNode) => void;
  toggleNot: (parts: number[]) => void;
  trace?: ConditionTrace;
}
const EditingContext = createContext<EditingContextValue>({
  selected: [],
  toggle: () => undefined,
  removeGroup: () => undefined,
  toggleNot: () => undefined,
});
function Selection({ parts }: { parts: number[] }) {
  const edit = useContext(EditingContext);
  return (
    <input
      className="ce-select"
      type="checkbox"
      aria-label={`select-${parts.join('-')}`}
      checked={edit.selected.includes(parts.join('-'))}
      onChange={() => edit.toggle(parts)}
    />
  );
}
function NodeResult({ parts }: { parts: number[] }) {
  const { trace } = useContext(EditingContext);
  const { text } = useDecisionConditionText();
  const result = trace?.nodeResults[nodeKey(parts)];
  return result ? (
    <span
      className={`ce-node-result ce-${result.toLowerCase()}`}
      data-testid={`node-result-${parts.join('-') || 'root'}`}
    >
      {text(
        result === 'TRUE'
          ? 'trialMatched'
          : result === 'FALSE'
            ? 'trialNotMatched'
            : 'trialUnknown',
      )}
    </span>
  ) : null;
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
  const { locale, text } = useDecisionConditionText();
  const left = row.left as PathOperand;
  const curKey = fieldKey(left.scope, left.path);
  const fieldOpt = fieldByKey.get(curKey);
  const dt = left.dataType ?? 'string';
  const ops = fieldOpt?.operators?.length ? fieldOpt.operators : operatorsForDataType(dt);
  const selectableFields = useMemo(() => {
    const hasCurrent = fields.some((field) => fieldKey(field.scope, field.path) === curKey);
    return hasCurrent || !fieldOpt ? fields : [fieldOpt, ...fields];
  }, [curKey, fieldOpt, fields]);
  const groupedFields = useMemo(
    () => groupFields(selectableFields, locale),
    [selectableFields, locale],
  );
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

  const onValueChange = (val: unknown) => updateRow({ ...row, right: lit(val, dt) });
  const currentModel = fieldOpt ? modelKey(fieldOpt) : '';
  const models = Array.from(
    new Map(selectableFields.map((field) => [modelKey(field), field])).values(),
  );
  const rhs = row.right?.type === 'path' ? row.right : undefined;
  const rhsField = rhs ? fieldByKey.get(fieldKey(rhs.scope, rhs.path)) : undefined;
  const rhsFields = fieldOpt ? fields.filter((field) => compatibleFields(fieldOpt, field)) : [];
  const rhsModels = Array.from(
    new Map(rhsFields.map((field) => [modelKey(field), field])).values(),
  );
  const asField = Boolean(rhs);
  const allowField = !COLLECTION_OPERATORS.has(row.operator) && row.operator !== 'BETWEEN';
  const changeRhsField = (key: string) => {
    const field = rhsFields.find((candidate) => fieldOptionKey(candidate) === key);
    if (field) updateRow({ ...row, right: path(field.scope, field.path, field.dataType) });
  };
  const valueControl =
    row.operator === 'BETWEEN' ? (
      <span className="ce-range">
        {[0, 1].map((index) => (
          <input
            key={index}
            aria-label={`${index === 0 ? 'range-start' : 'range-end'}-${id}`}
            type={
              dt === 'date'
                ? 'date'
                : dt === 'datetime'
                  ? 'datetime-local'
                  : dt === 'time'
                    ? 'time'
                    : 'number'
            }
            value={String(
              (Array.isArray(literalValue(row)) ? (literalValue(row) as unknown[]) : [])[index] ??
                '',
            )}
            onChange={(event) => {
              const values = Array.isArray(literalValue(row))
                ? [...(literalValue(row) as unknown[])]
                : ['', ''];
              values[index] =
                ['integer', 'decimal'].includes(dt) && event.target.value !== ''
                  ? Number(event.target.value)
                  : event.target.value;
              onValueChange(values);
            }}
          />
        ))}
      </span>
    ) : dt === 'boolean' ? (
      <select
        aria-label={`value-${id}`}
        value={literalScalarValue(row)}
        onChange={(event) =>
          onValueChange(event.target.value === '' ? '' : event.target.value === 'true')
        }
      >
        <option value="">{text('chooseValue')}</option>
        <option value="true">{text('trueValue')}</option>
        <option value="false">{text('falseValue')}</option>
      </select>
    ) : fieldOpt?.options ? (
      COLLECTION_OPERATORS.has(row.operator) ? (
        <details className="ce-enum-values">
          <summary>
            {literalArrayValue(row)
              .map((value) => optionLabel(fieldOpt, value))
              .join(text('listSeparator')) || text('chooseValue')}
          </summary>
          <div>
            {fieldOpt.options.map((option) => (
              <label key={option}>
                <input
                  type="checkbox"
                  aria-label={`value-${id}-${option}`}
                  checked={literalArrayValue(row).includes(option)}
                  onChange={(event) =>
                    onValueChange(
                      event.target.checked
                        ? [...literalArrayValue(row), option]
                        : literalArrayValue(row).filter((value) => value !== option),
                    )
                  }
                />
                {optionLabel(fieldOpt, option)}
              </label>
            ))}
          </div>
        </details>
      ) : (
        <select
          aria-label={`value-${id}`}
          value={literalScalarValue(row)}
          onChange={(event) => onValueChange(event.target.value)}
        >
          <option value="">{text('chooseValue')}</option>
          {fieldOpt.options.map((option) => (
            <option key={option} value={option}>
              {optionLabel(fieldOpt, option, rowValueLabels)}
            </option>
          ))}
        </select>
      )
    ) : isReferenceValueField(fieldOpt) ? (
      <ReferenceValuePicker
        id={id}
        field={fieldOpt}
        value={
          COLLECTION_OPERATORS.has(row.operator) ? literalArrayValue(row) : literalScalarValue(row)
        }
        multiple={COLLECTION_OPERATORS.has(row.operator)}
        runtimeValueLabels={rowValueLabels}
        onChange={onValueChange}
        onValueLabelsChange={(labels) => rememberValueLabels(curKey, labels)}
      />
    ) : (
      <input
        aria-label={`value-${id}`}
        type={
          ['integer', 'decimal'].includes(dt) && !COLLECTION_OPERATORS.has(row.operator)
            ? 'number'
            : dt === 'date'
              ? 'date'
              : dt === 'datetime'
                ? 'datetime-local'
                : dt === 'time'
                  ? 'time'
                  : 'text'
        }
        step={dt === 'integer' ? '1' : 'any'}
        value={
          COLLECTION_OPERATORS.has(row.operator)
            ? literalArrayValue(row).join(', ')
            : literalScalarValue(row)
        }
        onChange={(event) =>
          onValueChange(
            COLLECTION_OPERATORS.has(row.operator)
              ? splitCollectionInput(event.target.value)
              : ['integer', 'decimal'].includes(dt) && event.target.value !== ''
                ? Number(event.target.value)
                : event.target.value,
          )
        }
      />
    );
  return (
    <div className="cb-row ce-row" data-testid={`cb-row-${id}`}>
      <Selection parts={idPath} />
      <span className="ce-row-number">{idPath.map((index) => index + 1).join('.')}</span>
      <div className="ce-tokens">
        <select
          className="ce-model"
          aria-label={`model-${id}`}
          value={currentModel}
          onChange={(event) => {
            const field = selectableFields.find(
              (candidate) => modelKey(candidate) === event.target.value,
            );
            if (field) onFieldChange(fieldOptionKey(field));
          }}
        >
          <option value="" disabled>
            {text('modelSelect')}
          </option>
          {models.map((field) => (
            <option key={modelKey(field)} value={modelKey(field)}>
              {fieldGroupLabel(field, locale)}
            </option>
          ))}
        </select>
        <span className="ce-dot">.</span>
        <select
          className="ce-property"
          aria-label={`field-${id}`}
          value={fieldOpt ? curKey : ''}
          onChange={(event) => onFieldChange(event.target.value)}
        >
          <option value="" disabled>
            {text('fieldSelect')}
          </option>
          {selectableFields
            .filter((field) => modelKey(field) === currentModel)
            .map((field) => (
              <option key={fieldOptionKey(field)} value={fieldOptionKey(field)}>
                {field.label}
              </option>
            ))}
        </select>
        <select
          className="ce-operator"
          aria-label={`operator-${id}`}
          value={row.operator}
          onChange={(event) => onOperatorChange(event.target.value as Operator)}
        >
          {ops.map((op) => (
            <option key={op} value={op}>
              {decisionConditionOperatorLabel(op, locale)}
            </option>
          ))}
        </select>
        {showValue && (
          <>
            <select
              className="ce-value-kind"
              aria-label={`value-kind-${id}`}
              value={asField ? 'field' : 'literal'}
              disabled={!allowField}
              onChange={(event) => {
                if (event.target.value === 'field') {
                  const field = rhsFields[0];
                  if (field)
                    updateRow({ ...row, right: path(field.scope, field.path, field.dataType) });
                } else onValueChange('');
              }}
            >
              <option value="literal">{text('literalKind')}</option>
              <option value="field" disabled={!rhsFields.length}>
                {text('fieldKind')}
              </option>
            </select>
            {asField ? (
              <>
                <select
                  className="ce-model"
                  aria-label={`right-model-${id}`}
                  value={rhsField ? modelKey(rhsField) : ''}
                  onChange={(event) => {
                    const field = rhsFields.find(
                      (candidate) => modelKey(candidate) === event.target.value,
                    );
                    if (field) changeRhsField(fieldOptionKey(field));
                  }}
                >
                  <option value="" disabled>
                    {text('modelSelect')}
                  </option>
                  {rhsModels.map((field) => (
                    <option key={modelKey(field)} value={modelKey(field)}>
                      {fieldGroupLabel(field, locale)}
                    </option>
                  ))}
                </select>
                <span className="ce-dot">.</span>
                <select
                  className="ce-property"
                  aria-label={`right-field-${id}`}
                  value={rhsField ? fieldOptionKey(rhsField) : ''}
                  onChange={(event) => changeRhsField(event.target.value)}
                >
                  <option value="" disabled>
                    {text('fieldSelect')}
                  </option>
                  {rhsFields
                    .filter((field) => rhsField && modelKey(field) === modelKey(rhsField))
                    .map((field) => (
                      <option key={fieldOptionKey(field)} value={fieldOptionKey(field)}>
                        {field.label}
                      </option>
                    ))}
                </select>
              </>
            ) : (
              valueControl
            )}
          </>
        )}
      </div>
      <NodeResult parts={idPath} />
      <button
        className="ce-delete"
        type="button"
        aria-label={`delete-${id}`}
        title={text('delete')}
        onClick={deleteRow}
      >
        <CloseIcon size={14} />
      </button>
      {(!fieldOpt ||
        (asField && (!rhsField || !fieldOpt || !compatibleFields(fieldOpt, rhsField)))) && (
        <span role="alert" className="ce-row-error">
          {text('incompatibleOperand')}
        </span>
      )}
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
  const { locale, text } = useDecisionConditionText();
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
    const child = node.child;
    if (child.type === 'group')
      return (
        <div className="ce-not-wrapper" data-testid={`cb-not-${idPath.join('-')}`}>
          <GroupEditor
            node={child}
            idPath={[...idPath, 0]}
            fields={fields}
            fieldByKey={fieldByKey}
            runtimeValueLabels={runtimeValueLabels}
            rememberValueLabels={rememberValueLabels}
            updateNode={(next) => updateNode({ ...node, child: next })}
            deleteNode={deleteNode}
            negated
            onNegate={() => updateNode(child)}
            selectionPath={idPath}
          />
        </div>
      );
    return (
      <div className="cb-not ce-not-wrapper" data-testid={`cb-not-${idPath.join('-')}`}>
        <div className="ce-not-leaf">
          <Selection parts={idPath} />
          <strong>NOT (</strong>
          <button
            type="button"
            aria-label={`remove-not-${idPath.join('-')}`}
            onClick={() => updateNode(child)}
          >
            {text('unwrapGroup')}
          </button>
          <NodeResult parts={idPath} />
        </div>
        <NodeEditor
          node={child}
          idPath={[...idPath, 0]}
          fields={fields}
          fieldByKey={fieldByKey}
          runtimeValueLabels={runtimeValueLabels}
          rememberValueLabels={rememberValueLabels}
          updateNode={(next) => updateNode({ ...node, child: next })}
        />
        <span className="ce-paren">)</span>
        {deleteNode && (
          <button type="button" aria-label={`delete-not-${idPath.join('-')}`} onClick={deleteNode}>
            {text('delete')}
          </button>
        )}
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
  negated?: boolean;
  onNegate?: () => void;
  selectionPath?: number[];
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
  negated = false,
  onNegate,
  selectionPath = idPath,
}: GroupEditorProps) {
  const { locale, text } = useDecisionConditionText();
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

  const edit = useContext(EditingContext);
  return (
    <div
      className={`cb-group ce-group ${idPath.length === 0 ? 'ce-root' : ''}`}
      data-testid={idPath.length === 0 ? 'cb-group-root' : `cb-group${suffix}`}
    >
      <div className="ce-group-head">
        {idPath.length > 0 && <Selection parts={selectionPath} />}
        <span className="ce-paren">(</span>
        <select
          aria-label={`group-op${suffix}`}
          value={node.op}
          onChange={(event) => updateNode({ ...node, op: event.target.value as GroupNode['op'] })}
        >
          <option value="AND">{text('allMatchExpression')}</option>
          <option value="OR">{text('anyMatchExpression')}</option>
        </select>
        {idPath.length > 0 && (
          <label className="ce-negate">
            <input
              type="checkbox"
              aria-label={`group-not${suffix}`}
              checked={negated}
              onChange={() => (onNegate ? onNegate() : edit.toggleNot(selectionPath))}
            />
            {text('negateGroup')}
          </label>
        )}
        <small>{text('groupItems', { n: node.children.length })}</small>
        <NodeResult parts={selectionPath} />
        {deleteNode && (
          <button
            type="button"
            className="ce-delete"
            aria-label={`delete-group-${selectionPath.join('-')}`}
            onClick={() =>
              edit.removeGroup(selectionPath, negated ? { type: 'not', child: node } : node)
            }
          >
            <CloseIcon size={14} />
          </button>
        )}
      </div>
      {node.children.length === 0 && (
        <div
          className="cb-empty"
          data-testid={idPath.length === 0 ? 'cb-empty' : `cb-empty${suffix}`}
        >
          {text('noConditions')}
        </div>
      )}
      <div className="ce-group-body">
        {node.children.map((child, childIndex) => (
          <div key={`${child.type}-${childIndex}`}>
            {childIndex > 0 && <div className="ce-connector">{node.op}</div>}
            <NodeEditor
              node={child}
              idPath={[...idPath, childIndex]}
              fields={fields}
              fieldByKey={fieldByKey}
              runtimeValueLabels={runtimeValueLabels}
              rememberValueLabels={rememberValueLabels}
              updateNode={(nextChild) =>
                updateNode({
                  ...node,
                  children: replaceChild(node.children, childIndex, nextChild),
                })
              }
              deleteNode={() =>
                updateNode({
                  ...node,
                  children: node.children.filter((_, index) => index !== childIndex),
                })
              }
            />
          </div>
        ))}
      </div>
      <div className="cb-actions ce-add-actions">
        <button
          type="button"
          data-testid={idPath.length === 0 ? 'cb-add' : `cb-add${suffix}`}
          onClick={addRow}
          disabled={fields.length === 0}
        >
          <Plus size={13} />
          {text('addCondition')}
        </button>
        <button
          type="button"
          data-testid={idPath.length === 0 ? 'cb-add-group' : `cb-add-group${suffix}`}
          onClick={addGroup}
          disabled={fields.length === 0}
        >
          <Layers size={13} />
          {text('addGroup')}
        </button>
        <button
          type="button"
          data-testid={idPath.length === 0 ? 'cb-add-not' : `cb-add-not${suffix}`}
          onClick={addNot}
          disabled={fields.length === 0}
        >
          {text('addNot')}
        </button>
      </div>
      <span className="ce-paren ce-close-paren">)</span>
      {negated && <small>{text('groupNegatedResult')}</small>}
    </div>
  );
}

export function ConditionBuilder({
  value,
  fields: suppliedFields,
  onChange,
  readOnly = false,
}: ConditionBuilderProps) {
  const { locale, text } = useDecisionConditionText();
  const fields = useMemo(
    () => suppliedFields.filter((field) => field.visible !== false && !field.masked),
    [suppliedFields],
  );
  const [fieldQuery, setFieldQuery] = useState('');
  const [selected, setSelected] = useState<string[]>([]);
  const [history, setHistory] = useState<GroupNode[]>([]);
  const [future, setFuture] = useState<GroupNode[]>([]);
  const [view, setView] = useState<'visual' | 'expression'>('visual');
  const [trial, setTrial] = useState(false);
  const [trace, setTrace] = useState<ConditionTrace>();
  const [notice, setNotice] = useState('');
  const [removal, setRemoval] = useState<{ parts: number[]; node: ConditionNode }>();
  const previous = useRef(value);
  useEffect(() => {
    if (previous.current !== value) {
      setTrace(undefined);
      setSelected([]);
    }
    previous.current = value;
  }, [value]);
  const commit = (next: GroupNode) => {
    if (readOnly) return;
    setHistory((current) => [...current.slice(-49), value]);
    setFuture([]);
    setSelected([]);
    setTrace(undefined);
    setNotice('');
    onChange(next);
  };
  const toggleNot = (parts: number[]) => {
    const target = nodeAt(value, parts);
    if (target)
      commit(
        replaceAt(
          value,
          parts,
          target.type === 'not' ? target.child : { type: 'not', child: target },
        ) as GroupNode,
      );
  };
  const undo = () => {
    const target = history[history.length - 1];
    if (!target) return;
    setHistory(history.slice(0, -1));
    setFuture([value, ...future]);
    setTrace(undefined);
    onChange(target);
  };
  const redo = () => {
    const target = future[0];
    if (!target) return;
    setFuture(future.slice(1));
    setHistory([...history, value]);
    setTrace(undefined);
    onChange(target);
  };
  const remove = (keep: boolean) => {
    if (!removal) return;
    const parentPath = removal.parts.slice(0, -1);
    const parent = nodeAt(value, parentPath);
    if (parent?.type !== 'group') return;
    const target = removal.node.type === 'not' ? removal.node.child : removal.node;
    const children = [...parent.children];
    children.splice(
      removal.parts[removal.parts.length - 1],
      1,
      ...(keep ? (target.type === 'group' ? target.children : [target]) : []),
    );
    commit(replaceAt(value, parentPath, { ...parent, children }) as GroupNode);
    setRemoval(undefined);
  };

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

  const operandPreview = (
    operand: CompareNode['left'] | CompareNode['right'],
    leftField?: FieldOption,
  ): string => {
    if (!operand) return '';
    if (operand.type === 'path') return labelOf(operand);
    if (operand.type === 'literal') {
      if (Array.isArray(operand.value)) {
        const labels = leftField ? runtimeValueLabels[fieldOptionKey(leftField)] : undefined;
        return operand.value
          .map((item) => optionLabel(leftField, item, labels))
          .join(text('listSeparator'));
      }
      const labels = leftField ? runtimeValueLabels[fieldOptionKey(leftField)] : undefined;
      return optionLabel(leftField, operand.value, labels);
    }
    return `${operand.name}(...)`;
  };

  const previewNode = (node: ConditionNode): string => {
    if (node.type === 'compare') {
      const leftField =
        node.left.type === 'path'
          ? fieldByKey.get(fieldKey(node.left.scope, node.left.path))
          : undefined;
      const left = operandPreview(node.left, leftField);
      const right = UNARY_OPERATORS.has(node.operator)
        ? ''
        : ` ${operandPreview(node.right, leftField)}`;
      return `【${left} ${decisionConditionOperatorLabel(node.operator, locale)}${right}】`;
    }
    if (node.type === 'not') return text('negated', { condition: previewNode(node.child) });
    const parts = node.children
      .filter((child) => !(child.type === 'compare' && child.enabled === false))
      .map(previewNode);
    return `(${parts.join(node.op === 'AND' ? text('andSeparator') : text('orSeparator'))})`;
  };

  return (
    <EditingContext.Provider
      value={{
        selected,
        toggle: (parts) => {
          const key = parts.join('-');
          setSelected((current) =>
            current.includes(key) ? current.filter((item) => item !== key) : [...current, key],
          );
        },
        removeGroup: (parts, node) => setRemoval({ parts, node }),
        toggleNot,
        trace,
      }}
    >
      <div
        data-testid="condition-builder"
        className={`condition-expression ${trial ? 'ce-with-trial' : ''}`}
      >
        <div className="ce-header">
          <div className="ce-view-tabs">
            <button
              type="button"
              aria-pressed={view === 'visual'}
              onClick={() => setView('visual')}
            >
              {text('visualEdit')}
            </button>
            <button
              type="button"
              aria-pressed={view === 'expression'}
              onClick={() => setView('expression')}
            >
              {text('expressionPreview')}
            </button>
          </div>
          <span>{text('conditionCount', { n: conditionLeaves(value).length })}</span>
          {!readOnly && (
            <button
              type="button"
              className="ce-primary"
              data-testid="condition-trial-toggle"
              onClick={() => setTrial(!trial)}
            >
              <Play size={14} />
              {text(trial ? 'closeTrial' : 'openTrial')}
            </button>
          )}
        </div>
        <div className="ce-workspace">
          <div className="ce-main">
            <div className="ce-toolbar">
              <button
                type="button"
                aria-label={text('undo')}
                disabled={readOnly || !history.length}
                onClick={undo}
              >
                <ArrowLeft size={14} />
              </button>
              <button
                type="button"
                aria-label={text('redo')}
                disabled={readOnly || !future.length}
                onClick={redo}
              >
                <ArrowRight size={14} />
              </button>
              <button
                type="button"
                data-testid="condition-wrap"
                disabled={readOnly}
                onClick={() => {
                  const next = wrapSelection(
                    value,
                    selected.map((key) => key.split('-').map(Number)),
                  );
                  if (next) commit(next);
                  else setNotice(text('wrapError'));
                }}
              >
                <Layers size={14} />
                {text('wrapSelected')}
              </button>
              <small>{text('wrapHint')}</small>
              <input
                className="ce-field-search"
                aria-label="condition-field-search"
                value={fieldQuery}
                onChange={(event) => setFieldQuery(event.target.value)}
                placeholder={text('fieldSearchHint')}
              />
            </div>
            {notice && (
              <p className="ce-notice" role="alert">
                {notice}
              </p>
            )}
            {readOnly && <p className="ce-notice">{text('readOnlyCondition')}</p>}
            {view === 'visual' ? (
              <fieldset disabled={readOnly}>
                <GroupEditor
                  node={value}
                  idPath={[]}
                  fields={filteredFields}
                  fieldByKey={fieldByKey}
                  runtimeValueLabels={runtimeValueLabels}
                  rememberValueLabels={rememberValueLabels}
                  updateNode={commit}
                />
              </fieldset>
            ) : (
              <div className="ce-expression-preview">{previewNode(value)}</div>
            )}
            <div data-testid="cb-preview" className="cb-preview ce-preview">
              {value.children.length === 0 ? '—' : previewNode(value)}
            </div>
          </div>
          {trial && <ConditionServerTrial condition={value} fields={fields} onTrace={setTrace} />}
        </div>
        {removal && (
          <div className="ce-modal-overlay">
            <div
              className="ce-modal"
              role="dialog"
              aria-modal="true"
              aria-label={text('deleteGroupTitle')}
            >
              <h3>{text('deleteGroupTitle')}</h3>
              <p>{text('deleteGroupHint')}</p>
              <footer>
                <button type="button" onClick={() => setRemoval(undefined)}>
                  {text('cancelEdit')}
                </button>
                <button type="button" onClick={() => remove(true)}>
                  {text('unwrapGroup')}
                </button>
                <button type="button" className="ce-destructive" onClick={() => remove(false)}>
                  {text('deleteWholeGroup')}
                </button>
              </footer>
            </div>
          </div>
        )}
      </div>
    </EditingContext.Provider>
  );
}

export default ConditionBuilder;
