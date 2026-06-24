import React, { useCallback, useMemo } from 'react';
import type { ViewFilterConfig, SortConfig } from '~/framework/smart/types/savedView';
import { useI18n } from '~/contexts/I18nContext';

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
  /**
   * Optional resolver turning a filter's raw value into a human-readable label
   * (e.g. a dict code → localized label) so enum chips don't leak raw codes.
   * Returns undefined to fall back to the raw formatted value.
   */
  resolveValueLabel?: (filter: ViewFilterConfig) => string | undefined;
}

/** Map filter operators to concise symbols */
const OPERATOR_LABELS: Record<
  string,
  { symbol?: string; key?: string; fallbackZh?: string; fallbackEn?: string }
> = {
  eq: { symbol: '=' },
  ne: { symbol: '\u2260' },
  gt: { symbol: '>' },
  gte: { symbol: '\u2265' },
  lt: { symbol: '<' },
  lte: { symbol: '\u2264' },
  like: { key: 'filter.operator.like', fallbackZh: '包含', fallbackEn: 'contains' },
  in: { key: 'filter.operator.in', fallbackZh: '属于', fallbackEn: 'in' },
  between: { key: 'filter.operator.between', fallbackZh: '介于', fallbackEn: 'between' },
  isNull: { key: 'filter.operator.isNull', fallbackZh: '为空', fallbackEn: 'is empty' },
  isNotNull: { key: 'filter.operator.isNotNull', fallbackZh: '不为空', fallbackEn: 'not empty' },
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
  valueLabel?: string;
  onRemove: () => void;
}

const FilterChip = React.memo<FilterChipProps>(function FilterChip({
  filter,
  label,
  valueLabel,
  onRemove,
}) {
  const { t, locale } = useI18n();
  const operatorLabel = OPERATOR_LABELS[filter.operator] ?? { symbol: filter.operator };
  const operatorSymbol =
    operatorLabel.symbol ??
    t(
      operatorLabel.key ?? filter.operator,
      undefined,
      locale.startsWith('zh') ? operatorLabel.fallbackZh : operatorLabel.fallbackEn,
    );
  const displayValue = valueLabel ?? formatDisplayValue(filter.value);
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
          ? 'border-accent/30 bg-accent-weak text-accent border'
          : 'border-border bg-panel text-text-2 border'
      }`}
    >
      <span className="font-medium">{label}</span>
      <span className="text-text-3">{operatorSymbol}</span>
      {!isUnary && displayValue && <span className="max-w-[160px] truncate">{displayValue}</span>}
      <button
        type="button"
        onClick={(e) => {
          e.stopPropagation();
          onRemove();
        }}
        className="text-accent hover:bg-accent-weak hover:text-accent-hover ml-0.5 inline-flex h-4 w-4 items-center justify-center rounded transition-colors"
        aria-label={t('filter.remove_filter', { label }, `Remove filter ${label}`)}
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
  const { t } = useI18n();
  const dirLabel = sort.direction === 'asc' ? '\u2191' : '\u2193';

  return (
    <span
      className="border-status-amber bg-status-amber-bg text-status-amber inline-flex cursor-pointer items-center gap-1 rounded-md border px-2 py-1 text-sm leading-5 select-none"
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
      <span className="text-status-amber">{'\u2195'}</span>
      <span className="font-medium">{label}</span>
      <span>{dirLabel}</span>
      <button
        type="button"
        onClick={(e) => {
          e.stopPropagation();
          onRemove();
        }}
        className="text-status-amber hover:bg-status-amber-bg ml-0.5 inline-flex h-4 w-4 items-center justify-center rounded transition-colors"
        aria-label={t('filter.remove_sort', { label }, `Remove sort ${label}`)}
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
  resolveValueLabel,
}) {
  const { t, locale } = useI18n();
  const zh = locale.startsWith('zh');
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
    <div className="border-border bg-subtle flex flex-wrap items-center gap-2 border-b px-6 py-2">
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
            valueLabel={resolveValueLabel?.(f)}
            onRemove={() => handleRemoveFilter(idx)}
          />
        </span>
      ))}

      {/* Separator between filters and sorts */}
      {hasFilters && hasSorts && <div className="bg-border mx-1 h-6 w-px" />}

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
      {(hasFilters || hasSorts) && <div className="bg-border mx-1 h-6 w-px" />}

      {/* Add Filter button */}
      <button
        type="button"
        onClick={(e) => onAddFilter(e)}
        className="border-accent/40 bg-panel text-accent hover:bg-accent-weak hover:border-accent inline-flex items-center gap-0.5 rounded-md border border-dashed px-2 py-1 text-sm transition-colors"
      >
        + {t('common.add_filter', undefined, zh ? '添加筛选' : 'Add Filter')}
      </button>

      {/* Clear All */}
      {(hasFilters || hasSorts) && (
        <button
          type="button"
          onClick={onClearAll}
          className="text-text-3 hover:text-status-red ml-1 text-sm transition-colors"
        >
          {t('common.clear_all', undefined, zh ? '清除全部' : 'Clear All')}
        </button>
      )}
    </div>
  );
});

export default FilterChipBar;
