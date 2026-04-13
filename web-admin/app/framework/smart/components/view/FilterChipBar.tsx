import React, { useCallback, useMemo } from 'react';
import type { ViewFilterConfig, SortConfig } from '~/framework/smart/types/savedView';

/** Metadata for resolving field labels and display hints */
interface FieldMeta {
  fieldCode: string;
  label: string;
  fieldType: string;
  dictCode?: string;
}

interface FilterChipBarProps {
  filters: ViewFilterConfig[];
  sorts: SortConfig[];
  fieldMetadata: FieldMeta[];
  onFiltersChange: (filters: ViewFilterConfig[]) => void;
  onSortsChange: (sorts: SortConfig[]) => void;
  onAddFilter: (e?: React.MouseEvent) => void;
  onChipClick?: (index: number, e: React.MouseEvent) => void;
  onClearAll: () => void;
  locale?: string;
  t?: (key: string) => string;
}

/** Map filter operators to concise symbols */
const OPERATOR_SYMBOLS: Record<string, string> = {
  eq: '=',
  ne: '\u2260',
  gt: '>',
  gte: '\u2265',
  lt: '<',
  lte: '\u2264',
  like: 'contains',
  in: 'in',
  between: 'between',
  isNull: 'is empty',
  isNotNull: 'not empty',
};

/**
 * Format a filter value for display.
 * Arrays are joined with commas; objects are JSON-serialised; primitives are stringified.
 */
function formatDisplayValue(value: unknown): string {
  if (value === null || value === undefined || value === '') return '';
  if (Array.isArray(value)) return value.map(String).join(', ');
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}

/* ------------------------------------------------------------------ */
/*  Sub-components                                                     */
/* ------------------------------------------------------------------ */

interface FilterChipProps {
  filter: ViewFilterConfig;
  label: string;
  onRemove: () => void;
}

const FilterChip = React.memo<FilterChipProps>(function FilterChip({ filter, label, onRemove }) {
  const operatorSymbol = OPERATOR_SYMBOLS[filter.operator] ?? filter.operator;
  const displayValue = formatDisplayValue(filter.value);
  const hasValue =
    filter.value !== null &&
    filter.value !== undefined &&
    filter.value !== '' &&
    !(Array.isArray(filter.value) && filter.value.length === 0);

  const isUnary = filter.operator === 'isNull' || filter.operator === 'isNotNull';

  return (
    <span
      className={`inline-flex items-center gap-1 rounded-md px-2 py-1 text-sm leading-5 ${
        hasValue || isUnary
          ? 'border border-blue-200 bg-blue-50 text-blue-800'
          : 'border border-gray-200 bg-white text-gray-600'
      }`}
    >
      <span className="font-medium">{label}</span>
      <span className="text-gray-500">{operatorSymbol}</span>
      {!isUnary && displayValue && <span className="max-w-[160px] truncate">{displayValue}</span>}
      <button
        type="button"
        onClick={(e) => {
          e.stopPropagation();
          onRemove();
        }}
        className="ml-0.5 inline-flex h-4 w-4 items-center justify-center rounded text-blue-400 transition-colors hover:bg-blue-200/60 hover:text-blue-700"
        aria-label={`Remove filter ${label}`}
      >
        &times;
      </button>
    </span>
  );
});

interface SortChipProps {
  sort: SortConfig;
  label: string;
  onRemove: () => void;
  onToggle: () => void;
}

const SortChip = React.memo<SortChipProps>(function SortChip({ sort, label, onRemove, onToggle }) {
  const dirLabel = sort.direction === 'asc' ? '\u2191' : '\u2193';

  return (
    <span
      className="inline-flex cursor-pointer items-center gap-1 rounded-md border border-amber-200 bg-amber-50 px-2 py-1 text-sm leading-5 text-amber-800 select-none"
      onClick={onToggle}
      role="button"
      tabIndex={0}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          onToggle();
        }
      }}
    >
      <span className="text-amber-500">{'\u2195'}</span>
      <span className="font-medium">{label}</span>
      <span>{dirLabel}</span>
      <button
        type="button"
        onClick={(e) => {
          e.stopPropagation();
          onRemove();
        }}
        className="ml-0.5 inline-flex h-4 w-4 items-center justify-center rounded text-amber-400 transition-colors hover:bg-amber-200/60 hover:text-amber-700"
        aria-label={`Remove sort ${label}`}
      >
        &times;
      </button>
    </span>
  );
});

/* ------------------------------------------------------------------ */
/*  Main component                                                     */
/* ------------------------------------------------------------------ */

export const FilterChipBar = React.memo<FilterChipBarProps>(function FilterChipBar({
  filters,
  sorts,
  fieldMetadata,
  onFiltersChange,
  onSortsChange,
  onAddFilter,
  onChipClick,
  onClearAll,
}) {
  // Build a lookup map: fieldCode → label
  const labelMap = useMemo(() => {
    const map = new Map<string, string>();
    for (const fm of fieldMetadata) {
      map.set(fm.fieldCode, fm.label);
    }
    return map;
  }, [fieldMetadata]);

  const resolveLabel = useCallback((code: string) => labelMap.get(code) ?? code, [labelMap]);

  const handleRemoveFilter = useCallback(
    (index: number) => {
      onFiltersChange(filters.filter((_, i) => i !== index));
    },
    [filters, onFiltersChange],
  );

  const handleRemoveSort = useCallback(
    (index: number) => {
      onSortsChange(sorts.filter((_, i) => i !== index));
    },
    [sorts, onSortsChange],
  );

  const handleToggleSortDirection = useCallback(
    (index: number) => {
      const updated = sorts.map((s, i) =>
        i === index
          ? { ...s, direction: s.direction === 'asc' ? ('desc' as const) : ('asc' as const) }
          : s,
      );
      onSortsChange(updated);
    },
    [sorts, onSortsChange],
  );

  const hasFilters = filters.length > 0;
  const hasSorts = sorts.length > 0;

  // Always render — "Add Filter" button must be accessible even when no filters/sorts active

  return (
    <div className="flex flex-wrap items-center gap-2 border-b border-gray-100 bg-gray-50/50 px-6 py-2">
      {/* Filter chips */}
      {filters.map((f, idx) => (
        <span
          key={`filter-${f.fieldCode}-${idx}`}
          className="cursor-pointer"
          onClick={(e) => onChipClick?.(idx, e)}
        >
          <FilterChip
            filter={f}
            label={resolveLabel(f.fieldCode)}
            onRemove={() => handleRemoveFilter(idx)}
          />
        </span>
      ))}

      {/* Separator between filters and sorts */}
      {hasFilters && hasSorts && <div className="mx-1 h-6 w-px bg-gray-200" />}

      {/* Sort chips */}
      {sorts.map((s, idx) => (
        <SortChip
          key={`sort-${s.fieldCode}-${idx}`}
          sort={s}
          label={resolveLabel(s.fieldCode)}
          onRemove={() => handleRemoveSort(idx)}
          onToggle={() => handleToggleSortDirection(idx)}
        />
      ))}

      {/* Separator before actions */}
      {(hasFilters || hasSorts) && <div className="mx-1 h-6 w-px bg-gray-200" />}

      {/* Add Filter button */}
      <button
        type="button"
        onClick={(e) => onAddFilter(e)}
        className="inline-flex items-center gap-0.5 rounded-md border border-dashed border-blue-300 bg-white px-2 py-1 text-sm text-blue-600 transition-colors hover:border-blue-400 hover:bg-blue-50"
      >
        + Add Filter
      </button>

      {/* Clear All */}
      {(hasFilters || hasSorts) && (
        <button
          type="button"
          onClick={onClearAll}
          className="ml-1 text-sm text-gray-400 transition-colors hover:text-red-500"
        >
          Clear All
        </button>
      )}
    </div>
  );
});

export default FilterChipBar;
