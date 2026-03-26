/**
 * ChartWrapper
 *
 * A wrapper component that handles common concerns for all chart widgets:
 * - Checks if data source is configured
 * - Calls useChartData() hook
 * - Shows unified loading / error / not-configured / empty placeholder UI
 * - Delegates actual chart rendering to the children render-prop
 */

import React from 'react';
import { useChartData } from '~/smart/hooks/useChartData';
import type { ChartDataSource, FilterConfig, AggregateQueryResponse } from '~/smart/types/chart';

interface ChartWrapperProps {
  /** Chart title shown in the not-configured placeholder */
  title: string;
  /** Icon shown in the not-configured placeholder (emoji or symbol) */
  icon?: string;
  /** Data source configuration */
  dataSource?: ChartDataSource;
  /** Linkage filters from other charts */
  linkageFilters?: FilterConfig[];
  /** Auto-refresh interval in milliseconds (0 or undefined to disable) */
  refreshInterval?: number;
  /** Additional CSS class */
  className?: string;
  /** Inline styles */
  style?: React.CSSProperties;
  /**
   * Render-prop that receives the loaded data.
   * Called only when data is successfully loaded and non-empty.
   */
  children: (data: {
    rows: AggregateQueryResponse['rows'];
    meta: AggregateQueryResponse['meta'];
    summary: AggregateQueryResponse['summary'];
  }) => React.ReactNode;
}

/**
 * Check whether a data source has enough configuration to fetch data.
 */
function isDataSourceConfigured(ds?: ChartDataSource): boolean {
  if (!ds) return false;
  switch (ds.type) {
    case 'aggregate':
      return !!(ds.modelCode && ds.metrics?.length);
    case 'namedQuery':
      return !!ds.queryCode;
    case 'static':
      return Array.isArray(ds.staticData) && ds.staticData.length > 0;
    default:
      return false;
  }
}

export function ChartWrapper({
  title,
  icon,
  dataSource,
  linkageFilters,
  refreshInterval,
  className,
  style,
  children,
}: ChartWrapperProps) {
  const configured = isDataSourceConfigured(dataSource);

  const { data, loading, error } = useChartData({
    dataSource: dataSource ?? { type: 'static' },
    linkageFilters,
    refreshInterval,
    enabled: configured,
  });

  // Not configured
  if (!configured) {
    return (
      <div
        className={`flex h-full flex-col items-center justify-center rounded-lg border-2 border-dashed border-gray-200 bg-gray-50/50 ${className || ''}`}
        style={style}
      >
        <div className="mb-2 text-4xl text-gray-400">{icon || '📊'}</div>
        <div className="text-sm font-medium text-gray-500">{title}</div>
        <div className="mt-1 text-xs text-gray-400">请在右侧配置数据源</div>
      </div>
    );
  }

  // Loading
  if (loading) {
    return (
      <div className={`flex h-full items-center justify-center ${className || ''}`} style={style}>
        <div className="flex flex-col items-center gap-2">
          <div className="h-6 w-6 animate-spin rounded-full border-2 border-blue-500 border-t-transparent" />
          <div className="text-xs text-gray-400">Loading...</div>
        </div>
      </div>
    );
  }

  // Error
  if (error) {
    return (
      <div
        className={`flex h-full flex-col items-center justify-center ${className || ''}`}
        style={style}
      >
        <div className="mb-2 text-2xl text-red-400">&#9888;</div>
        <div className="text-sm text-red-500">数据加载失败</div>
        <div className="mt-1 text-xs text-red-400">{error.message}</div>
      </div>
    );
  }

  // Empty data
  if (!data?.rows?.length) {
    return (
      <div
        className={`flex h-full flex-col items-center justify-center ${className || ''}`}
        style={style}
      >
        <div className="mb-2 text-2xl text-gray-400">📭</div>
        <div className="text-sm text-gray-500">暂无数据</div>
      </div>
    );
  }

  // Render children with loaded data
  return (
    <>
      {children({
        rows: data.rows,
        meta: data.meta,
        summary: data.summary,
      })}
    </>
  );
}

export default ChartWrapper;
