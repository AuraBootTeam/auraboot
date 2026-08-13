import type { ViewFilterConfig } from '~/framework/smart/types/savedView';
import type { FilterConfig } from '~/framework/smart/types/chart';
import type { ListFilterFieldMetadata } from '../ListPageContent';

const NUMERIC_TYPES = new Set([
  'number',
  'decimal',
  'integer',
  'int',
  'long',
  'bigint',
  'smallint',
  'double',
  'float',
  'currency',
  'money',
]);
const NON_GROUPABLE_TYPES = new Set([
  'json',
  'jsonb',
  'blob',
  'binary',
  'image',
  'file',
  'richtext',
]);

export function isAnalysisNumericType(type: string): boolean {
  return NUMERIC_TYPES.has(type.toLowerCase());
}

export function analysisGroupFields(fields: ListFilterFieldMetadata[]): ListFilterFieldMetadata[] {
  return fields.filter((field) => !NON_GROUPABLE_TYPES.has(field.fieldType.toLowerCase()));
}

export function analysisMetricFields(fields: ListFilterFieldMetadata[]): ListFilterFieldMetadata[] {
  return fields.filter((field) => isAnalysisNumericType(field.fieldType));
}

export function viewFiltersToChartFilters(filters: ViewFilterConfig[]): FilterConfig[] {
  const result: FilterConfig[] = [];
  for (const filter of filters) {
    if (!filter.fieldCode) continue;
    const op = String(filter.operator || 'eq').toLowerCase();
    if (op === 'between') {
      const values = Array.isArray(filter.value) ? filter.value : [];
      if (values[0] != null && values[0] !== '')
        result.push({ field: filter.fieldCode, operator: 'gte', value: values[0] });
      if (values[1] != null && values[1] !== '')
        result.push({ field: filter.fieldCode, operator: 'lte', value: values[1] });
      continue;
    }
    if (op === 'isnull' || op === 'is_null') {
      result.push({ field: filter.fieldCode, operator: 'is_null', value: null });
      continue;
    }
    if (op === 'isnotnull' || op === 'is_not_null') {
      result.push({ field: filter.fieldCode, operator: 'is_not_null', value: null });
      continue;
    }
    if (filter.value == null || filter.value === '') continue;
    const operator =
      ({ neq: 'ne', notin: 'not_in' } as Record<string, FilterConfig['operator']>)[op] ??
      (op as FilterConfig['operator']);
    result.push({ field: filter.fieldCode, operator, value: filter.value });
  }
  return result;
}
