import type { FilterConfig } from '../types/chart';

/** Keep unfinished editor rows distinct from valid queries and empty results. */
export function hasIncompleteChartFilters(filters: FilterConfig[] | undefined): boolean {
  return !!filters?.some((filter) => {
    if (filter.children?.length) return hasIncompleteChartFilters(filter.children);
    if (!filter.field?.trim() || !filter.operator) return true;
    if (['is_null', 'is_not_null', 'isNull', 'isNotNull'].includes(filter.operator)) return false;
    return (
      filter.value == null ||
      (typeof filter.value === 'string' && !filter.value.trim()) ||
      (Array.isArray(filter.value) && filter.value.length === 0)
    );
  });
}
