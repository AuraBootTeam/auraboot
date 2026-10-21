import type { FieldConfig } from '~/framework/meta/schemas/types';
import type { ViewFilterConfig } from '~/framework/smart/types/savedView';
import { normalizeSavedViewFilterRange } from '~/framework/smart/utils/savedViewFilterRange';
import { resolveFieldMetaDataType } from './listFieldMetadata';

export function viewConfigFiltersToRuntimeFilters(
  filters: ViewFilterConfig[] | undefined,
): Record<string, any> {
  const restoredFilters: Record<string, any> = {};
  for (const filter of filters ?? []) {
    restoredFilters[filter.fieldCode] = filter.value;
  }
  return restoredFilters;
}

export interface SavedViewFilterExpressionContext {
  /** Public user PID used by reference fields such as an opportunity owner. */
  currentUserPid?: string;
}

const CURRENT_DEPARTMENT_OWNER_PIDS_RESOLVER = {
  $currentDepartmentOwnerPids: { includeSubDepartments: true },
} as const;

const CURRENT_SHARED_RECORD_PIDS_RESOLVER = {
  $currentSharedRecordPids: { action: 'read' },
} as const;

/**
 * Resolve the deliberately small, documented set of SavedView filter expressions.
 *
 * SavedView expressions are convenience filters, not an authorization boundary;
 * backend data-scope policies remain authoritative. Unknown expressions are left
 * inactive instead of accidentally reusing a stale static value.
 */
export function resolveSavedViewFilterExpressions(
  filters: ViewFilterConfig[] | undefined,
  context: SavedViewFilterExpressionContext,
): ViewFilterConfig[] {
  return (filters ?? []).map((filter) => {
    if (!filter.isExpression) return normalizeSavedViewFilterRange(filter);

    const expression = String(filter.expression ?? '').trim();
    const resolvedValue =
      expression === '#currentUser' || expression === '${system.currentUser}'
        ? context.currentUserPid?.trim() || undefined
        : expression === '#currentDepartmentOwners' ||
            expression === '${system.currentDepartmentOwners}'
          ? CURRENT_DEPARTMENT_OWNER_PIDS_RESOLVER
          : expression === '#currentSharedRecords' ||
              expression === '${system.currentSharedRecords}'
            ? CURRENT_SHARED_RECORD_PIDS_RESOLVER
            : undefined;

    return normalizeSavedViewFilterRange({ ...filter, value: resolvedValue });
  });
}

/**
 * URL params owned by the list runtime itself. A bare param with one of these
 * names is never promoted to a row filter, even when the schema declares a
 * field of the same name.
 */
const LIST_RESERVED_URL_PARAM_KEYS: ReadonlySet<string> = new Set([
  'sort',
  'keyword',
  'filters',
  'preset',
  'view',
  'tab',
  'pageNum',
  'pageSize',
  'locale',
]);

/**
 * Model data types that tolerate a SQL LIKE '%…%' predicate. Everything else
 * (date/datetime/time, numeric, boolean, ...) must use EQ: a LIKE against a
 * DATE or numeric column is rejected by the backend, which is exactly what
 * happened to drill-down params like /p/fin_cash_forecast_week?fin_cfw_week_start=…
 * when they were converted with the search field's default LIKE operator.
 */
const TEXT_LIKE_FILTER_DATA_TYPES: ReadonlySet<string> = new Set([
  'string',
  'text',
  'char',
  'varchar',
  'enum',
  'json',
  'jsonb',
  'uuid',
]);

/**
 * Field names declared by the list page schema: table columns (action columns
 * excluded), table search fields and filter-block fields. Drill-down bare URL
 * params are only promoted to row filters when they match one of these, so
 * foreign params can never leak into the query.
 */
export function collectListSchemaFieldNames(schema: unknown): string[] {
  const names = new Set<string>();
  const blocks = (schema as { blocks?: unknown })?.blocks;
  if (!Array.isArray(blocks)) return [];
  for (const block of blocks) {
    const record = block as {
      blockType?: unknown;
      columns?: unknown;
      searchFields?: unknown;
      fields?: unknown;
      table?: { columns?: unknown; searchFields?: unknown };
    };
    if (record.blockType === 'table') {
      const columns = Array.isArray(record.table?.columns) ? record.table?.columns : record.columns;
      if (Array.isArray(columns)) {
        for (const column of columns) {
          const col = column as { field?: unknown; isActionColumn?: unknown };
          if (typeof col?.field === 'string' && col.field && !col.isActionColumn) {
            names.add(col.field);
          }
        }
      }
      const searchFields = Array.isArray(record.table?.searchFields)
        ? record.table?.searchFields
        : record.searchFields;
      if (Array.isArray(searchFields)) {
        for (const field of searchFields) {
          if (typeof field === 'string' && field) names.add(field);
        }
      }
    }
    if (record.blockType === 'filters' || record.blockType === 'filter') {
      if (Array.isArray(record.fields)) {
        for (const field of record.fields) {
          const f = field as { field?: unknown };
          if (typeof f?.field === 'string' && f.field) names.add(f.field);
        }
      }
    }
  }
  return [...names];
}

/**
 * Collect drill-down bare URL params as row filters.
 *
 * Dashboard drill-down navigation writes mapped param names bare — e.g.
 * /p/fin_cash_forecast_week?fin_cfw_week_start=… via the widget drillDown
 * paramMapping — while the list runtime only consumed filter_* and filters=,
 * so the bare form was silently dropped and the target list rendered unfiltered.
 * A bare param becomes a row filter only when its name matches a field declared
 * by the page schema and is not a reserved runtime param; filter_* params keep
 * precedence over a bare param for the same field.
 */
export function collectListBareParamFilters(
  searchParams: URLSearchParams,
  fieldNames: Iterable<string>,
): Record<string, any> {
  const known = new Set(fieldNames);
  if (known.size === 0) return {};
  const filters: Record<string, any> = {};
  searchParams.forEach((value, key) => {
    if (!key || key.startsWith('filter_') || LIST_RESERVED_URL_PARAM_KEYS.has(key)) return;
    if (known.has(key) && value != null && value !== '') {
      filters[key] = value;
    }
  });
  return filters;
}

export interface ListQueryFilterCondition {
  fieldName: string;
  operator: string;
  value?: unknown;
  values?: unknown[];
}

/**
 * Convert one user filter (key -> raw value, sourced from URL filter_* params,
 * drill-down bare params or the quick filter bar) into its query conditions.
 *
 * SmartInput search fields default to LIKE, but a LIKE '%..%' against a
 * date/number column is a guaranteed backend error, and drill-down URL params
 * (e.g. /p/fin_cash_forecast_week?fin_cfw_week_start=...) flow through here with
 * the field's real model type. When the model metadata types the field as a
 * non-text scalar the operator therefore falls back to EQ; unknown types keep
 * the legacy heuristic (explicit operator > SmartInput LIKE > EQ).
 */
export function resolveUserFilterCondition(
  key: string,
  value: unknown,
  filterFields: FieldConfig[] | undefined,
  filterFieldMap?: Map<string, any>,
): ListQueryFilterCondition[] {
  if (value == null || value === '') return [];
  // Date range objects { start, end }
  if (typeof value === 'object' && ('start' in value || 'end' in value)) {
    const range = value as { start?: unknown; end?: unknown };
    const conditions: ListQueryFilterCondition[] = [];
    if (range.start)
      conditions.push({ fieldName: key, operator: 'gte', value: String(range.start) });
    if (range.end) conditions.push({ fieldName: key, operator: 'lte', value: String(range.end) });
    return conditions;
  }
  const fieldConfig = (filterFields || []).find((field) => field.field === key);
  const explicitOperator = (fieldConfig as any)?.operator || fieldConfig?.props?.operator;
  const dataType = resolveFieldMetaDataType(key, filterFieldMap);
  const supportsLike =
    dataType == null || TEXT_LIKE_FILTER_DATA_TYPES.has(dataType.split('(')[0].trim());
  const operator =
    !supportsLike && !explicitOperator
      ? 'EQ'
      : typeof explicitOperator === 'string' && explicitOperator.trim()
        ? explicitOperator.trim().toUpperCase()
        : fieldConfig?.component === 'SmartInput'
          ? 'LIKE'
          : 'EQ';
  const textValue = String(value);
  return [
    {
      fieldName: key,
      operator,
      value: operator === 'LIKE' ? `%${textValue}%` : textValue,
    },
  ];
}

/** Preserve array-valued IN/BETWEEN filters for the dynamic query contract. */
export function viewFilterToQueryCondition(
  input: ViewFilterConfig,
): ListQueryFilterCondition | null {
  const filter = normalizeSavedViewFilterRange(input);
  if (!filter.fieldCode) return null;
  const operator = (filter.operator || 'eq').toUpperCase();
  if (operator === 'ISNULL' || operator === 'ISNOTNULL') {
    return { fieldName: filter.fieldCode, operator, value: null };
  }
  if (filter.value == null || filter.value === '') return null;
  if (operator === 'IN' || operator === 'BETWEEN') {
    const values = Array.isArray(filter.value) ? filter.value : [filter.value];
    if (values.length === 0) return null;
    return { fieldName: filter.fieldCode, operator, values };
  }
  return {
    fieldName: filter.fieldCode,
    operator,
    value: operator === 'LIKE' ? `%${String(filter.value)}%` : filter.value,
  };
}

/** Normalize a DSL list-tab filter through the same array-aware query contract as saved views. */
export function tabFilterToQueryCondition(
  filter: any,
  currentUserPid?: string,
): ListQueryFilterCondition | null {
  if (!filter || typeof filter !== 'object') return null;
  const fieldCode = filter.fieldName || filter.field;
  if (!fieldCode) return null;
  return viewFilterToQueryCondition({
    fieldCode,
    operator: String(filter.operator || 'EQ').toLowerCase() as ViewFilterConfig['operator'],
    value: filter.value === '$currentUser' ? currentUserPid : filter.value,
  });
}

/** Convert the dynamic-list condition shape to the export endpoint shape. */
export function queryConditionToExportCondition(condition: ListQueryFilterCondition): {
  field: string;
  operator: string;
  value: unknown;
} {
  return {
    field: condition.fieldName,
    operator: condition.operator,
    value: condition.values ?? condition.value ?? null,
  };
}
