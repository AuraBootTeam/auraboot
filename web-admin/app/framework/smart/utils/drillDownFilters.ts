import type { DrillDownConfig, FilterConfig } from '~/framework/smart/types/chart';

export function enrichDrillDownFilters(
  primary: FilterConfig,
  row: Record<string, unknown> | undefined,
  drillDown: DrillDownConfig | undefined,
): FilterConfig[] {
  const filters = [primary];
  if (!row) return filters;

  for (const rule of drillDown?.filters ?? []) {
    const sourceField = rule.sourceField;
    if (!sourceField || sourceField === primary.field) continue;
    if (!Object.prototype.hasOwnProperty.call(row, sourceField)) continue;
    const value = row[sourceField];
    if (value == null || value === '') continue;
    filters.push({ field: sourceField, operator: 'eq', value });
  }
  return filters;
}
