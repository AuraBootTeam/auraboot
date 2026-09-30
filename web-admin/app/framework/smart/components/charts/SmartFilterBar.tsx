/**
 * SmartFilterBar Component
 *
 * A standalone dashboard filter bar. Unlike chart-click linkage, this widget
 * presents persistent interactive controls (select / multi-select / date range)
 * that emit {@link FilterConfig}s into the dashboard linkage group, so any
 * widget configured with `linkage: { groupId, receiveFilter: true }` refetches
 * server-side (useChartData merges them into the chart-data request, where
 * AggregateQueryServiceImpl applies them against the Named Query field whitelist).
 */
import React, { useCallback, useMemo, useState } from 'react';
import type { FilterConfig } from '~/framework/smart/types/chart';

/** Bilingual label shape shared across dashboard configs. */
export interface SmartBilingualLabel {
  'zh-CN': string;
  en: string;
}

/** Filter control types supported by the bar. */
export type SmartFilterControlType = 'select' | 'multi-select' | 'date-range' | 'text';

/** A single filter field rendered by the bar. */
export interface SmartFilterFieldConfig {
  /** Stable id for state tracking */
  id: string;
  /** Control type */
  type: SmartFilterControlType;
  /** Target field name emitted in the FilterConfig */
  field: string;
  /** Static options for select/multi-select controls */
  options?: Array<{ value: string; label: string }>;
  /** Bilingual label (falls back to id) */
  label?: SmartBilingualLabel | string;
  /** Placeholder */
  placeholder?: SmartBilingualLabel | string;
}

export interface SmartFilterBarProps {
  /** Filter field definitions */
  filterFields?: SmartFilterFieldConfig[];
  /** Linkage group this bar emits into (same contract as charts) */
  linkage?: { groupId?: string; enabled?: boolean };
  /** Emitted on every change; receiving widgets refetch server-side */
  onLinkageEmit?: (filters: FilterConfig[]) => void;
  /** Bar title */
  title?: SmartBilingualLabel | string;
  /** Custom CSS class */
  className?: string;
  /** Custom inline styles */
  style?: React.CSSProperties;
}

type FieldValues = Record<string, string | string[] | { from?: string; to?: string }>;

const LABEL_SEP = ' · ';

function labelOf(value: SmartBilingualLabel | string | undefined, fallback: string): string {
  if (!value) return fallback;
  if (typeof value === 'string') return value;
  return `${value['zh-CN']}${LABEL_SEP}${value.en}`;
}

function isEmpty(v: unknown): boolean {
  if (v === undefined || v === null || v === '') return true;
  if (Array.isArray(v)) return v.length === 0;
  if (typeof v === 'object') {
    const range = v as { from?: string; to?: string };
    return !range.from && !range.to;
  }
  return false;
}

/**
 * SmartFilterBar — persistent filter controls that drive server-side refetch
 * of every linked widget on the dashboard.
 */
export const SmartFilterBar: React.FC<SmartFilterBarProps> = ({
  filterFields = [],
  linkage,
  onLinkageEmit,
  title,
  className,
  style,
}) => {
  const [values, setValues] = useState<FieldValues>({});

  const buildFilters = useCallback(
    (current: FieldValues): FilterConfig[] => {
      const filters: FilterConfig[] = [];
      for (const fieldDef of filterFields) {
        const v = current[fieldDef.id];
        if (isEmpty(v)) continue;
        if (fieldDef.type === 'multi-select') {
          filters.push({ field: fieldDef.field, operator: 'in', value: v as string[] });
        } else if (fieldDef.type === 'date-range') {
          const range = v as { from?: string; to?: string };
          if (range.from) filters.push({ field: fieldDef.field, operator: 'gte', value: range.from });
          if (range.to) filters.push({ field: fieldDef.field, operator: 'lte', value: range.to });
        } else {
          filters.push({ field: fieldDef.field, operator: 'eq', value: v as string });
        }
      }
      return filters;
    },
    [filterFields],
  );

  const emit = useCallback(
    (current: FieldValues) => {
      if (!linkage?.enabled && !linkage?.groupId) return;
      if (!onLinkageEmit) return;
      onLinkageEmit(buildFilters(current));
    },
    [linkage, onLinkageEmit, buildFilters],
  );

  const update = useCallback(
    (id: string, value: string | string[] | { from?: string; to?: string }) => {
      setValues((prev) => {
        const next = { ...prev, [id]: value };
        emit(next);
        return next;
      });
    },
    [emit],
  );

  const reset = useCallback(() => {
    setValues({});
    emit({});
  }, [emit]);

  const inputCls =
    'rounded border border-gray-300 bg-white px-2 py-1 text-sm text-gray-700 dark:border-gray-600 dark:bg-gray-800 dark:text-gray-200';
  const wrapCls = className ?? 'flex flex-wrap items-end gap-3 p-3';

  const items = useMemo(
    () =>
      filterFields.map((f) => {
        const v = values[f.id];
        const lbl = labelOf(f.label, f.id);
        if (f.type === 'select') {
          return (
            <label key={f.id} className="flex flex-col gap-1 text-xs text-gray-500 dark:text-gray-400">
              <span>{lbl}</span>
              <select
                className={inputCls}
                value={(v as string) ?? ''}
                onChange={(e) => update(f.id, e.target.value)}
              >
                <option value="">{labelOf(f.placeholder, '全部')}</option>
                {(f.options ?? []).map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
            </label>
          );
        }
        if (f.type === 'multi-select') {
          const selected = Array.isArray(v) ? v : [];
          return (
            <label key={f.id} className="flex flex-col gap-1 text-xs text-gray-500 dark:text-gray-400">
              <span>{lbl}</span>
              <div className="flex flex-wrap gap-1">
                {(f.options ?? []).map((o) => {
                  const active = selected.includes(o.value);
                  return (
                    <button
                      key={o.value}
                      type="button"
                      onClick={() =>
                        update(
                          f.id,
                          active ? selected.filter((s) => s !== o.value) : [...selected, o.value],
                        )
                      }
                      className={`rounded-full border px-2 py-0.5 text-xs ${
                        active
                          ? 'border-blue-500 bg-blue-500 text-white'
                          : 'border-gray-300 bg-white text-gray-600 dark:border-gray-600 dark:bg-gray-800 dark:text-gray-300'
                      }`}
                    >
                      {o.label}
                    </button>
                  );
                })}
              </div>
            </label>
          );
        }
        if (f.type === 'date-range') {
          const range = (v as { from?: string; to?: string }) ?? {};
          return (
            <label key={f.id} className="flex flex-col gap-1 text-xs text-gray-500 dark:text-gray-400">
              <span>{lbl}</span>
              <span className="flex items-center gap-1">
                <input
                  type="date"
                  className={inputCls}
                  value={range.from ?? ''}
                  onChange={(e) => update(f.id, { ...range, from: e.target.value })}
                />
                <span>→</span>
                <input
                  type="date"
                  className={inputCls}
                  value={range.to ?? ''}
                  onChange={(e) => update(f.id, { ...range, to: e.target.value })}
                />
              </span>
            </label>
          );
        }
        return (
          <label key={f.id} className="flex flex-col gap-1 text-xs text-gray-500 dark:text-gray-400">
            <span>{lbl}</span>
            <input
              type="text"
              className={inputCls}
              value={(v as string) ?? ''}
              placeholder={labelOf(f.placeholder, '')}
              onChange={(e) => update(f.id, e.target.value)}
            />
          </label>
        );
      }),
    [filterFields, values, update],
  );

  return (
    <div className={wrapCls} style={style} data-widget="smart-filter-bar">
      {title ? (
        <span className="text-sm font-medium text-gray-700 dark:text-gray-200">
          {labelOf(title, '')}
        </span>
      ) : null}
      {items}
      <button
        type="button"
        onClick={reset}
        className="rounded border border-gray-300 px-2 py-1 text-xs text-gray-500 hover:bg-gray-50 dark:border-gray-600 dark:text-gray-400 dark:hover:bg-gray-700"
      >
        重置 Reset
      </button>
    </div>
  );
};

export default SmartFilterBar;
