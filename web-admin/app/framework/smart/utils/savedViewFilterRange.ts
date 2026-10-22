import type { ViewFilterConfig } from '../types/savedView';

/** Adapt legacy preset ranges to the scalar values required by SavedView queries. */
export function normalizeSavedViewFilterRange(filter: ViewFilterConfig): ViewFilterConfig {
  const value = filter.value;
  if (String(filter.operator).toLowerCase() !== 'between' || value == null ||
      typeof value !== 'object' || Array.isArray(value) || !('start' in value || 'end' in value)) {
    return filter;
  }
  const { start, end } = value as { start?: unknown; end?: unknown };
  const hasStart = start != null && start !== '';
  const hasEnd = end != null && end !== '';
  if (hasStart && hasEnd) return { ...filter, value: [start, end] };
  if (hasStart) return { ...filter, operator: 'gte', value: start };
  if (hasEnd) return { ...filter, operator: 'lte', value: end };
  return { ...filter, value: undefined };
}
