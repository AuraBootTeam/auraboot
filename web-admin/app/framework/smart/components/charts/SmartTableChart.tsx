/**
 * SmartTableChart Component
 *
 * A data table component for dashboards.
 * Displays aggregated data in a clean tabular format with sorting and pagination.
 */

import React, { useMemo, useState, useCallback } from 'react';
import { useChartData } from '~/framework/smart/hooks/useChartData';
import type {
  ChartDataSource,
  DrillDownConfig,
  LinkageConfig,
  FilterConfig,
} from '~/framework/smart/types/chart';
import { cn } from '~/utils/cn';

export interface SmartTableChartProps {
  title?: string;
  dataSource: ChartDataSource;
  /**
   * Optional explicit column configuration. When provided, the table renders
   * exactly these columns in this order with the given header labels.
   * When omitted, falls back to auto-deriving from `data.meta.dimensions+metrics`.
   */
  columns?: Array<{ field: string; label?: string }>;
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

function isDataSourceConfigured(ds: ChartDataSource): boolean {
  if (!ds) return false;
  if (ds.type === 'aggregate') return !!(ds.modelCode && ds.metrics?.length);
  if (ds.type === 'namedQuery') return !!ds.queryCode;
  return ds.type === 'static';
}

export const SmartTableChart: React.FC<SmartTableChartProps> = ({
  title,
  dataSource,
  columns: columnsConfig,   // explicit config from caller
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
  const isConfigured = isDataSourceConfigured(dataSource);

  const { data, loading, error } = useChartData({
    dataSource,
    linkageFilters: linkage?.receiveFilter ? linkageFilters : undefined,
    refreshInterval,
    enabled: isConfigured,
  });

  const [currentPage, setCurrentPage] = useState(0);
  const [sortKey, setSortKey] = useState<string | null>(null);
  const [sortDirection, setSortDirection] = useState<'asc' | 'desc'>('asc');

  const columns = useMemo<Array<{ field: string; label: string }>>(() => {
    // Explicit config wins
    if (columnsConfig && columnsConfig.length > 0) {
      return columnsConfig.map((c) => ({ field: c.field, label: c.label || c.field }));
    }
    if (!data?.rows?.length) return [];
    const allKeys = [...(data.meta?.dimensions || []), ...(data.meta?.metrics || [])];
    const keys = allKeys.length > 0 ? allKeys : Object.keys(data.rows[0]);
    return keys.map((k) => ({ field: k, label: k }));
  }, [columnsConfig, data]);

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
      if (!data?.meta?.dimensions?.length) return;
      const dimension = data.meta.dimensions[0];
      const filter: FilterConfig = { field: dimension, operator: 'eq', value: row[dimension] };
      if (drillDown?.enabled && onDrillDown) onDrillDown([filter]);
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
          <div className="font-medium text-gray-500">{title || 'Data Table'}</div>
          <div className="mt-1 text-sm text-gray-400">Please configure data source</div>
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
          <span className="text-sm text-gray-500">Loading...</span>
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
          <div className="mb-2 text-lg text-red-500">Failed to load data</div>
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
                  className={cn(
                    'px-4 py-2.5 text-left text-xs font-medium tracking-wider whitespace-nowrap text-gray-500 uppercase',
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
                  <td key={col.field} className="px-4 py-2.5 whitespace-nowrap text-gray-700">
                    {formatCellValue(row[col.field])}
                  </td>
                ))}
              </tr>
            ))}
            {paginatedRows.length === 0 && (
              <tr>
                <td colSpan={columns.length} className="px-4 py-8 text-center text-gray-400">
                  No data available
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {showPagination && totalPages > 1 && (
        <div className="flex items-center justify-between border-t border-gray-200 px-4 py-2.5 text-sm text-gray-500">
          <span>{sortedRows.length} rows</span>
          <div className="flex items-center gap-2">
            <button
              type="button"
              disabled={currentPage === 0}
              onClick={() => setCurrentPage((p) => p - 1)}
              className="rounded px-2 py-1 hover:bg-gray-100 disabled:cursor-not-allowed disabled:opacity-50"
            >
              Prev
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
              Next
            </button>
          </div>
        </div>
      )}
    </div>
  );
};

function formatCellValue(value: unknown): string {
  if (value === null || value === undefined) return '-';
  if (typeof value === 'number') {
    return Number.isInteger(value) ? String(value) : value.toFixed(2);
  }
  return String(value);
}

export default SmartTableChart;
