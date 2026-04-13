/**
 * SmartLeaderboard Component
 *
 * A ranked list display using data from useChartData.
 * Shows a numbered list with horizontal bars representing relative values.
 */

import React, { useMemo } from 'react';
import { useChartData } from '~/framework/smart/hooks/useChartData';
import type { ChartDataSource, FilterConfig, LinkageConfig } from '~/framework/smart/types/chart';
import { cn } from '~/utils/cn';

/**
 * Props for SmartLeaderboard component
 */
export interface SmartLeaderboardProps {
  /** Widget title */
  title?: string;
  /** Data source configuration */
  dataSource: ChartDataSource;
  /** Field for rank names */
  rankField?: string;
  /** Field for values */
  valueField?: string;
  /** Maximum number of items to display */
  maxItems?: number;
  /** Linkage configuration */
  linkage?: LinkageConfig;
  /** Linkage filters from other charts */
  linkageFilters?: FilterConfig[];
  /** Auto-refresh interval in milliseconds (0 = disabled) */
  refreshInterval?: number;
  /** Custom CSS class */
  className?: string;
  /** Custom inline styles */
  style?: React.CSSProperties;
}

/**
 * Check if data source is configured enough to fetch data
 */
function isDataSourceConfigured(dataSource: ChartDataSource): boolean {
  if (!dataSource) return false;
  switch (dataSource.type) {
    case 'aggregate':
      return !!(dataSource.modelCode && dataSource.metrics?.length);
    case 'namedQuery':
      return !!dataSource.queryCode;
    case 'static':
      return true;
    default:
      return false;
  }
}

/**
 * Colors for top 3 ranks
 */
const rankColors = ['#f59e0b', '#9ca3af', '#cd7f32'];

/**
 * Format large numbers for display
 */
function formatNumber(num: number): string {
  if (num >= 1_000_000) return `${(num / 1_000_000).toFixed(1)}M`;
  if (num >= 1_000) return `${(num / 1_000).toFixed(1)}K`;
  return String(num);
}

export const SmartLeaderboard: React.FC<SmartLeaderboardProps> = ({
  title,
  dataSource,
  rankField,
  valueField,
  maxItems = 10,
  linkage,
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

  /**
   * Process data into sorted ranked items
   */
  const items = useMemo(() => {
    if (!data?.rows?.length) return [];

    const dimensions = data.meta?.dimensions || [];
    const metrics = data.meta?.metrics || [];

    const nameKey = rankField || dimensions[0];
    const valKey = valueField || metrics[0];

    if (!nameKey || !valKey) return [];

    const sorted = [...data.rows]
      .map((row) => ({
        name: String(row[nameKey] ?? ''),
        value: Number(row[valKey]) || 0,
      }))
      .sort((a, b) => b.value - a.value)
      .slice(0, maxItems);

    return sorted;
  }, [data, rankField, valueField, maxItems]);

  const maxValue = useMemo(() => Math.max(...items.map((i) => i.value), 1), [items]);

  // Not configured state
  if (!isConfigured) {
    return (
      <div
        className={cn(
          'flex h-full items-center justify-center rounded-lg border border-dashed border-gray-300 bg-white p-4',
          className,
        )}
        style={style}
      >
        <div className="text-center">
          <div className="mb-3 text-4xl text-gray-400">🏆</div>
          <div className="font-medium text-gray-500">{title || '排行榜'}</div>
          <div className="mt-1 text-sm text-gray-400">请在右侧配置数据源</div>
        </div>
      </div>
    );
  }

  // Loading state
  if (loading) {
    return (
      <div
        className={cn(
          'flex h-full items-center justify-center rounded-lg border border-gray-200 bg-white p-4',
          className,
        )}
        style={style}
      >
        <div className="flex flex-col items-center gap-2">
          <div className="h-8 w-8 animate-spin rounded-full border-2 border-blue-500 border-t-transparent" />
          <span className="text-sm text-gray-500">Loading...</span>
        </div>
      </div>
    );
  }

  // Error state
  if (error) {
    return (
      <div
        className={cn(
          'flex h-full items-center justify-center rounded-lg border border-red-200 bg-white p-4',
          className,
        )}
        style={style}
        role="alert"
      >
        <div className="text-center">
          <div className="mb-2 text-lg text-red-500">Failed to load data</div>
          <div className="text-sm text-gray-500">{error.message}</div>
        </div>
      </div>
    );
  }

  // Empty data state
  if (items.length === 0) {
    return (
      <div
        className={cn(
          'flex h-full items-center justify-center rounded-lg border border-gray-200 bg-white p-4',
          className,
        )}
        style={style}
      >
        <div className="text-center text-gray-400">
          <div className="text-sm">暂无数据</div>
        </div>
      </div>
    );
  }

  return (
    <div
      className={cn(
        'h-full overflow-auto rounded-lg border border-gray-200 bg-white p-4',
        className,
      )}
      style={style}
    >
      {title && (
        <div className="mb-3 border-b border-gray-100 pb-2 text-sm font-medium text-gray-500">
          {title}
        </div>
      )}
      <div className="space-y-2">
        {items.map((item, index) => {
          const barWidth = (item.value / maxValue) * 100;
          const isTop3 = index < 3;

          return (
            <div key={`${item.name}-${index}`} className="flex items-center gap-3">
              {/* Rank number */}
              <div
                className={cn(
                  'flex h-6 w-6 flex-shrink-0 items-center justify-center rounded-full text-xs font-bold',
                  isTop3 ? 'text-white' : 'bg-gray-100 text-gray-500',
                )}
                style={isTop3 ? { backgroundColor: rankColors[index] } : undefined}
              >
                {index + 1}
              </div>

              {/* Name and bar */}
              <div className="min-w-0 flex-1">
                <div className="mb-1 flex items-center justify-between">
                  <span className="truncate text-sm text-gray-700">{item.name}</span>
                  <span className="ml-2 flex-shrink-0 text-sm font-medium text-gray-900 tabular-nums">
                    {formatNumber(item.value)}
                  </span>
                </div>
                <div className="h-2 w-full overflow-hidden rounded-full bg-gray-100">
                  <div
                    className="h-full rounded-full transition-all duration-500"
                    style={{
                      width: `${barWidth}%`,
                      backgroundColor: isTop3 ? rankColors[index] : '#60a5fa',
                    }}
                  />
                </div>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};

export default SmartLeaderboard;
