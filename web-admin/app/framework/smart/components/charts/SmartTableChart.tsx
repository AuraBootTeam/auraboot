/**
 * SmartTableChart Component
 *
 * A data table component for dashboards.
 * Displays aggregated data in a clean tabular format with sorting and pagination.
 */

import React, { useEffect, useMemo, useState, useCallback } from 'react';
import { useChartData } from '~/framework/smart/hooks/useChartData';
import { useDimensionLabels } from '~/framework/smart/hooks/useDimensionLabels';
import type {
  ChartDataSource,
  DrillDownConfig,
  LinkageConfig,
  FilterConfig,
} from '~/framework/smart/types/chart';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useI18n } from '~/contexts/I18nContext';
import { getLocalizedText } from '~/framework/meta/runtime/expression/i18n-renderer';
import type { LocalizedText } from '~/framework/meta/schemas/types';
import { cn } from '~/utils/cn';
import { enrichDrillDownFilters } from '~/framework/smart/utils/drillDownFilters';

/**
 * Per-column override delivered via `widget.config.table.columns`. Lets the
 * dashboard JSON name each column in `LocalizedText` form (or via `$i18n:`)
 * so headers respect the active locale instead of leaking raw field codes.
 */
export interface SmartTableChartColumn {
  /** Field code in the row data. */
  field: string;
  /** Optional explicit header label. Falls back to i18n / fieldCode. */
  label?: string | LocalizedText;
  /** Reserved for future use (column width). */
  width?: number;
  /** Reserved for future use (cell renderer hint). */
  renderType?: string;
  /** Reserved for future use (dict-driven tag rendering). */
  dictCode?: string;
  /** Optional per-column align: 'left' | 'right' | 'center'. */
  align?: 'left' | 'right' | 'center';
  /** Business display formatting for model/API table values. */
  format?: 'date' | 'datetime' | 'number' | 'currency' | 'percent';
  currency?: string;
  precision?: number;
  prefix?: string;
  suffix?: string;
}

export interface SmartTableChartProps {
  title?: string;
  /**
   * Optional. Standard chart datasource (aggregate/namedQuery/static). When
   * omitted, the component falls back to `modelCode` + `table.columns` for the
   * "list latest N rows of a model" dashboard pattern.
   */
  dataSource?: ChartDataSource;
  /**
   * Optional shorthand: render the latest rows of a model. When provided
   * without an explicit dataSource, the component fetches from
   * `/api/dynamic/{modelCode}/list` so dashboards can show recent records
   * without authoring a full aggregate query.
   */
  modelCode?: string;
  /**
   * Optional widget-level table config. `columns[]` controls header labels
   * and order; when omitted, behaviour falls back to the legacy data-derived
   * column inference.
   */
  table?: {
    columns?: SmartTableChartColumn[];
  };
  /** Record filters applied by the model-table shorthand branch. */
  filters?: FilterConfig[];
  /** Default server/client sort for model-table rows. */
  defaultSort?: { field: string; order: 'asc' | 'desc' };
  /** Page size for pagination */
  pageSize?: number;
  /** Show pagination controls */
  showPagination?: boolean;
  /** Enable sorting */
  sortable?: boolean;
  /** Stripe rows */
  striped?: boolean;
  drillDown?: DrillDownConfig;
  linkage?: LinkageConfig;
  onDrillDown?: (filters: FilterConfig[]) => void;
  onLinkageEmit?: (filters: FilterConfig[]) => void;
  linkageFilters?: FilterConfig[];
  refreshInterval?: number;
  className?: string;
  style?: React.CSSProperties;
}

function isDataSourceConfigured(
  ds: ChartDataSource | undefined,
  modelCode: string | undefined,
  tableColumns: SmartTableChartColumn[] | undefined,
): boolean {
  if (ds) {
    if (ds.type === 'aggregate') return !!(ds.modelCode && ds.metrics?.length);
    if (ds.type === 'namedQuery') return !!ds.queryCode;
    if (ds.type === 'api') return !!ds.url;
    if (ds.type === 'static') return true;
  }
  // Model-driven shorthand: modelCode + table.columns is enough to render a
  // "recent rows" table without an aggregate dataSource being authored.
  if (modelCode && tableColumns && tableColumns.length > 0) return true;
  return false;
}

export const SmartTableChart: React.FC<SmartTableChartProps> = ({
  title,
  dataSource,
  modelCode,
  table,
  filters,
  defaultSort,
  pageSize = 10,
  showPagination = true,
  sortable = true,
  striped = true,
  drillDown,
  linkage,
  onDrillDown,
  onLinkageEmit,
  linkageFilters,
  refreshInterval,
  className,
  style,
}) => {
  const { locale } = useI18n();
  const l = useCallback(
    (zh: string, en: string) => (locale === 'zh-CN' ? zh : en),
    [locale],
  );
  const tableColumns = table?.columns;
  const isConfigured = isDataSourceConfigured(dataSource, modelCode, tableColumns);

  // Standard chart datasource branch (aggregate / namedQuery / static).
  const useApiBranch = dataSource?.type === 'api' && !!dataSource.url;
  const useChartBranch = !!dataSource && !useApiBranch;
  const {
    data: chartData,
    loading: chartLoading,
    error: chartError,
  } = useChartData({
    dataSource: dataSource ?? ({ type: 'static', staticData: [] } as ChartDataSource),
    linkageFilters: linkage?.receiveFilter ? linkageFilters : undefined,
    refreshInterval,
    enabled: useChartBranch && isConfigured,
  });

  // Model-table fallback branch: dashboards that author `modelCode +
  // table.columns` without a full dataSource. We pull the latest rows from
  // the dynamic list API and synthesise a chart-data-shaped payload so the
  // rest of the component can stay agnostic.
  const [modelRows, setModelRows] = useState<Record<string, unknown>[]>([]);
  const [modelLoading, setModelLoading] = useState(false);
  const [modelError, setModelError] = useState<Error | null>(null);
  const useModelBranch = !useChartBranch && !!modelCode && !!tableColumns?.length;
  const [apiRows, setApiRows] = useState<Record<string, unknown>[]>([]);
  const [apiLoading, setApiLoading] = useState(false);
  const [apiError, setApiError] = useState<Error | null>(null);
  const modelFiltersKey = useMemo(() => JSON.stringify(filters ?? []), [filters]);
  const modelSortKey = `${defaultSort?.field ?? ''}:${defaultSort?.order ?? ''}`;

  // Stable value-key for the api params: dataSource (and its params object) is a fresh object
  // on every parent render, so depending on the object identity re-runs the fetch effect every
  // render. With >=2 api widgets each fetch's setState re-renders the dashboard, re-creating the
  // params objects, and the widgets fetch each other into an infinite refetch loop. Depend on the
  // serialized value instead so the effect only re-runs when the params actually change.
  const apiParamsKey = useMemo(
    () => (useApiBranch ? JSON.stringify(dataSource?.params ?? {}) : ''),
    [useApiBranch, dataSource?.params],
  );

  useEffect(() => {
    if (!useApiBranch || !dataSource?.url) return;
    let cancelled = false;
    setApiLoading(true);
    setApiError(null);
    const params = Object.fromEntries(
      Object.entries(dataSource.params ?? {}).map(([key, value]) => [key, String(value)]),
    );
    fetchResult<{ records?: Record<string, unknown>[]; rows?: Record<string, unknown>[] }>(
      dataSource.url,
      { method: 'get', params },
    )
      .then((result) => {
        if (cancelled) return;
        if (ResultHelper.isSuccess(result)) {
          setApiRows(result.data?.records ?? result.data?.rows ?? []);
        } else {
          setApiRows([]);
        }
        setApiLoading(false);
      })
      .catch((err) => {
        if (cancelled) return;
        setApiError(err instanceof Error ? err : new Error(String(err)));
        setApiRows([]);
        setApiLoading(false);
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- apiParamsKey is the stable value-key for dataSource.params
  }, [useApiBranch, dataSource?.url, apiParamsKey]);

  useEffect(() => {
    if (!useModelBranch || !modelCode) return;
    let cancelled = false;
    setModelLoading(true);
    setModelError(null);
    const params: Record<string, string> = {
      pageNum: '1',
      pageSize: String(pageSize * 5), // headroom for sort/pagination on client
    };
    if (filters?.length) {
      params.filters = JSON.stringify(
        filters.map((filter) => ({
          fieldName: filter.field,
          operator: String(filter.operator || 'eq').toUpperCase(),
          value: filter.value,
        })),
      );
    }
    if (defaultSort?.field) {
      params.sortField = defaultSort.field;
      params.sortOrder = defaultSort.order;
    }
    fetchResult<{ records?: Record<string, unknown>[] }>(`/api/dynamic/${modelCode}/list`, {
      method: 'get',
      params,
    })
      .then((result) => {
        if (cancelled) return;
        if (ResultHelper.isSuccess(result) && result.data?.records) {
          setModelRows(result.data.records);
        } else {
          setModelRows([]);
        }
        setModelLoading(false);
      })
      .catch((err) => {
        if (cancelled) return;
        setModelError(err instanceof Error ? err : new Error(String(err)));
        setModelRows([]);
        setModelLoading(false);
      });
    return () => {
      cancelled = true;
    };
  // `modelFiltersKey` / `modelSortKey` are stable value keys. Dashboard config
  // objects may be reconstructed by the renderer without changing semantics.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [useModelBranch, modelCode, pageSize, modelFiltersKey, modelSortKey]);

  const data = useChartBranch
    ? chartData
    : useApiBranch
      ? {
          rows: apiRows,
          summary: {},
          meta: {
            dimensions: tableColumns?.map((c) => c.field) ?? [],
            metrics: [] as string[],
          },
        }
      : useModelBranch
        ? {
            rows: modelRows,
            summary: {},
            meta: {
              dimensions: tableColumns?.map((c) => c.field) ?? [],
              metrics: [] as string[],
            },
          }
        : null;
  const loading = useChartBranch ? chartLoading : useApiBranch ? apiLoading : modelLoading;
  const error = useChartBranch ? chartError : useApiBranch ? apiError : modelError;

  const [currentPage, setCurrentPage] = useState(0);
  const [sortKey, setSortKey] = useState<string | null>(defaultSort?.field ?? null);
  const [sortDirection, setSortDirection] = useState<'asc' | 'desc'>(
    defaultSort?.order ?? 'asc',
  );

  /**
   * Resolved column descriptors. Priority:
   *   1. `table.columns[]` from widget config (controls order + label + meta)
   *   2. `data.meta.{dimensions,metrics}` from chart datasource
   *   3. Keys from the first row (pure fallback)
   *
   * Each entry carries the field code plus a pre-resolved header label so the
   * `<th>` render path can stay declarative.
   */
  const columns = useMemo<
    Array<SmartTableChartColumn & { label: string }>
  >(() => {
    if (tableColumns && tableColumns.length > 0) {
      return tableColumns.map((col) => {
        const resolvedLabel = col.label ? getLocalizedText(col.label, locale) : col.field;
        return {
          ...col,
          label: resolvedLabel || col.field,
        };
      });
    }
    if (!data?.rows?.length) return [];
    const allKeys = [...(data.meta?.dimensions || []), ...(data.meta?.metrics || [])];
    const keys = allKeys.length > 0 ? allKeys : Object.keys(data.rows[0]);
    return keys.map((k) => ({ field: k, label: k }));
  }, [data, tableColumns, locale]);

  // Dict labels for the columns. The table renders stored values verbatim, so a
  // stage column showed `proposal` and an activity type `call`. The model-list branch
  // never builds a QueryMeta, hence resolving straight from the column fields.
  const columnFields = useMemo(() => columns.map((c) => c.field), [columns]);
  const cellLabels = useDimensionLabels(
    modelCode ? { type: 'aggregate', modelCode } : dataSource,
    columnFields,
  );

  const sortedRows = useMemo(() => {
    if (!data?.rows) return [];
    const rows = [...data.rows];
    if (sortKey) {
      rows.sort((a, b) => {
        const va = a[sortKey];
        const vb = b[sortKey];
        const numA = Number(va);
        const numB = Number(vb);
        if (!isNaN(numA) && !isNaN(numB)) {
          return sortDirection === 'asc' ? numA - numB : numB - numA;
        }
        const strA = String(va ?? '');
        const strB = String(vb ?? '');
        return sortDirection === 'asc' ? strA.localeCompare(strB) : strB.localeCompare(strA);
      });
    }
    return rows;
  }, [data?.rows, sortKey, sortDirection]);

  const paginatedRows = useMemo(() => {
    if (!showPagination) return sortedRows;
    const start = currentPage * pageSize;
    return sortedRows.slice(start, start + pageSize);
  }, [sortedRows, currentPage, pageSize, showPagination]);

  const totalPages = Math.ceil(sortedRows.length / pageSize);

  const handleSort = useCallback(
    (key: string) => {
      if (!sortable) return;
      if (sortKey === key) {
        setSortDirection((d) => (d === 'asc' ? 'desc' : 'asc'));
      } else {
        setSortKey(key);
        setSortDirection('asc');
      }
    },
    [sortKey, sortable],
  );

  const handleRowClick = useCallback(
    (row: Record<string, unknown>) => {
      const dimension =
        data?.meta?.dimensions?.[0] ||
        Object.keys(drillDown?.paramMapping || {}).find((field) =>
          Object.prototype.hasOwnProperty.call(row, field),
        );
      if (!dimension) return;
      const filter: FilterConfig = { field: dimension, operator: 'eq', value: row[dimension] };
      if (drillDown?.enabled && onDrillDown) {
        onDrillDown(enrichDrillDownFilters(filter, row, drillDown));
      }
      if (linkage?.enabled && linkage?.emitFilter && onLinkageEmit) onLinkageEmit([filter]);
    },
    [data, drillDown, linkage, onDrillDown, onLinkageEmit],
  );

  if (!isConfigured) {
    return (
      <div
        className={cn(
          'flex items-center justify-center rounded-lg border border-dashed border-gray-300 bg-white p-4',
          className,
        )}
        style={{ minHeight: 200, ...style }}
      >
        <div className="text-center">
          <div className="mb-3 text-4xl text-gray-400">📋</div>
          <div className="font-medium text-gray-500">{title || l('数据表格', 'Data Table')}</div>
          <div className="mt-1 text-sm text-gray-400">
            {l('请配置数据源', 'Please configure data source')}
          </div>
        </div>
      </div>
    );
  }

  if (loading) {
    return (
      <div
        className={cn(
          'flex items-center justify-center rounded-lg border border-gray-200 bg-white p-4',
          className,
        )}
        style={{ minHeight: 200, ...style }}
      >
        <div className="flex flex-col items-center gap-2">
          <div className="h-8 w-8 animate-spin rounded-full border-2 border-blue-500 border-t-transparent" />
          <span className="text-sm text-gray-500">{l('加载中...', 'Loading...')}</span>
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div
        className={cn(
          'flex items-center justify-center rounded-lg border border-red-200 bg-white p-4',
          className,
        )}
        style={{ minHeight: 200, ...style }}
        role="alert"
      >
        <div className="text-center">
          <div className="mb-2 text-lg text-red-500">
            {l('数据加载失败', 'Failed to load data')}
          </div>
          <div className="text-sm text-gray-500">{error.message}</div>
        </div>
      </div>
    );
  }

  return (
    <div
      className={cn('flex flex-col rounded-lg border border-gray-200 bg-white', className)}
      style={style}
    >
      {title && (
        <div className="border-b border-gray-200 px-4 py-3">
          <h3 className="text-sm font-medium text-gray-700">{title}</h3>
        </div>
      )}

      <div className="flex-1 overflow-auto">
        <table className="min-w-full divide-y divide-gray-200 text-sm">
          <thead className="bg-gray-50">
            <tr>
              {columns.map((col) => (
                <th
                  key={col.field}
                  onClick={() => handleSort(col.field)}
                  style={col.width ? { width: col.width, maxWidth: col.width } : undefined}
                  className={cn(
                    'px-4 py-2.5 text-xs font-medium tracking-wider whitespace-nowrap text-gray-500 uppercase',
                    col.align === 'right' && 'text-right',
                    col.align === 'center' && 'text-center',
                    (!col.align || col.align === 'left') && 'text-left',
                    sortable && 'cursor-pointer select-none hover:bg-gray-100',
                  )}
                >
                  <span className="inline-flex items-center gap-1">
                    {col.label}
                    {sortKey === col.field && (
                      <span className="text-blue-500">{sortDirection === 'asc' ? '↑' : '↓'}</span>
                    )}
                  </span>
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-200">
            {paginatedRows.map((row, idx) => (
              <tr
                key={idx}
                onClick={() => handleRowClick(row)}
                className={cn(
                  'cursor-pointer transition-colors hover:bg-blue-50',
                  striped && idx % 2 === 1 && 'bg-gray-50/50',
                )}
              >
                {columns.map((col) => (
                  <td
                    key={col.field}
                    style={col.width ? { width: col.width, maxWidth: col.width } : undefined}
                    className={cn(
                      'overflow-hidden px-4 py-2.5 text-ellipsis whitespace-nowrap text-gray-700',
                      col.align === 'right' && 'text-right',
                      col.align === 'center' && 'text-center',
                    )}
                  >
                    <span title={formatCell(cellLabels, col, row[col.field], locale)}>
                      {formatCell(cellLabels, col, row[col.field], locale)}
                    </span>
                  </td>
                ))}
              </tr>
            ))}
            {paginatedRows.length === 0 && (
              <tr>
                <td colSpan={columns.length} className="px-4 py-8 text-center text-gray-400">
                  {l('暂无数据', 'No data available')}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {showPagination && totalPages > 1 && (
        <div className="flex items-center justify-between border-t border-gray-200 px-4 py-2.5 text-sm text-gray-500">
          <span>{l(`共 ${sortedRows.length} 条`, `${sortedRows.length} rows`)}</span>
          <div className="flex items-center gap-2">
            <button
              type="button"
              disabled={currentPage === 0}
              onClick={() => setCurrentPage((p) => p - 1)}
              className="rounded px-2 py-1 hover:bg-gray-100 disabled:cursor-not-allowed disabled:opacity-50"
            >
              {l('上一页', 'Prev')}
            </button>
            <span>
              {currentPage + 1} / {totalPages}
            </span>
            <button
              type="button"
              disabled={currentPage >= totalPages - 1}
              onClick={() => setCurrentPage((p) => p + 1)}
              className="rounded px-2 py-1 hover:bg-gray-100 disabled:cursor-not-allowed disabled:opacity-50"
            >
              {l('下一页', 'Next')}
            </button>
          </div>
        </div>
      )}
    </div>
  );
};

/**
 * Render a cell: the dict label when the column is dict-coded, else the formatted raw
 * value.
 *
 * Kept separate from `formatCellValue` on purpose — routing every cell through the
 * label lookup would stringify numbers and lose the decimal formatting below.
 */
function formatCell(
  labels: Record<string, Record<string, string>>,
  column: SmartTableChartColumn,
  value: unknown,
  locale: string,
): string {
  const label = value == null ? undefined : labels[column.field]?.[String(value)];
  return label ?? formatCellValue(value, column, locale);
}

function formatCellValue(
  value: unknown,
  column: SmartTableChartColumn,
  locale: string,
): string {
  if (value === null || value === undefined) return '-';
  const activeLocale = locale === 'zh-CN' ? 'zh-CN' : 'en-US';
  if (column.format === 'date' || column.format === 'datetime') {
    const date = new Date(String(value));
    if (!Number.isNaN(date.getTime())) {
      return new Intl.DateTimeFormat(activeLocale, {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        ...(column.format === 'datetime'
          ? { hour: '2-digit', minute: '2-digit', hour12: false }
          : {}),
      }).format(date);
    }
  }
  if (column.format === 'currency') {
    return `${column.prefix ?? ''}${new Intl.NumberFormat(activeLocale, {
      style: 'currency',
      currency: column.currency ?? 'CNY',
      minimumFractionDigits: column.precision ?? 0,
      maximumFractionDigits: column.precision ?? 0,
    }).format(Number(value) || 0)}${column.suffix ?? ''}`;
  }
  if (column.format === 'percent') {
    const raw = Number(value) || 0;
    const normalized = Math.abs(raw) <= 1 ? raw * 100 : raw;
    return `${column.prefix ?? ''}${new Intl.NumberFormat(activeLocale, {
      minimumFractionDigits: column.precision ?? 0,
      maximumFractionDigits: column.precision ?? 0,
    }).format(normalized)}%${column.suffix ?? ''}`;
  }
  if (typeof value === 'number' || column.format === 'number') {
    return `${column.prefix ?? ''}${new Intl.NumberFormat(activeLocale, {
      minimumFractionDigits: column.precision ?? 0,
      maximumFractionDigits: column.precision ?? (Number.isInteger(Number(value)) ? 0 : 2),
    }).format(Number(value) || 0)}${column.suffix ?? ''}`;
  }
  return String(value);
}

export default SmartTableChart;
