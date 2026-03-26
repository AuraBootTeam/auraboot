/**
 * SmartNumberCard Component
 *
 * A number card component for displaying single KPI metrics.
 * Supports number/currency/percent formatting with loading and error states.
 */

import React from 'react';
import { useChartData } from '~/smart/hooks/useChartData';
import type {
  ChartDataSource,
  LinkageConfig,
  FilterConfig,
  DrillDownConfig,
} from '~/smart/types/chart';
import { cn } from '~/utils/cn';

/**
 * Props for SmartNumberCard component
 */
export interface SmartNumberCardProps {
  /** Card title */
  title: string;
  /** Optional icon (emoji or icon string) */
  icon?: string;
  /** Data source configuration */
  dataSource: ChartDataSource;
  /** Value format type */
  format?: 'number' | 'currency' | 'percent';
  /** Decimal precision */
  precision?: number;
  /** Currency code for currency format (default: CNY) */
  currency?: string;
  /** Trend comparison configuration */
  trend?: {
    enabled: boolean;
    compareType?: 'lastDay' | 'lastWeek' | 'lastMonth';
  };
  /** Linkage configuration */
  linkage?: LinkageConfig;
  /** Linkage filters from other charts */
  linkageFilters?: FilterConfig[];
  /** Callback when linkage filter is emitted */
  onLinkageEmit?: (filters: FilterConfig[]) => void;
  /** Auto-refresh interval in milliseconds (0 = disabled) */
  refreshInterval?: number;
  /** Custom CSS class */
  className?: string;
  /** Custom inline styles */
  style?: React.CSSProperties;
  /** Drill-down navigation configuration */
  drillDown?: DrillDownConfig;
  /** Callback when drill-down is triggered */
  onDrillDown?: (config: DrillDownConfig) => void;
}

/**
 * SmartNumberCard - A KPI number display card component
 *
 * @example
 * // Basic usage
 * <SmartNumberCard
 *   title="Total Orders"
 *   icon="📦"
 *   dataSource={{
 *     type: 'aggregate',
 *     modelCode: 'order',
 *     metrics: [{ field: 'id', aggregation: 'count', alias: 'count' }],
 *   }}
 * />
 *
 * @example
 * // Currency format with precision
 * <SmartNumberCard
 *   title="Revenue"
 *   icon="💰"
 *   dataSource={{
 *     type: 'aggregate',
 *     modelCode: 'order',
 *     metrics: [{ field: 'amount', aggregation: 'sum', alias: 'total' }],
 *   }}
 *   format="currency"
 *   precision={2}
 * />
 */
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

export const SmartNumberCard: React.FC<SmartNumberCardProps> = ({
  title,
  icon,
  dataSource,
  format = 'number',
  precision = 0,
  currency = 'cny',
  trend,
  linkage,
  linkageFilters,
  onLinkageEmit,
  refreshInterval,
  className,
  style,
  drillDown,
  onDrillDown,
}) => {
  const isConfigured = isDataSourceConfigured(dataSource);

  const { data, loading, error } = useChartData({
    dataSource,
    linkageFilters: linkage?.receiveFilter ? linkageFilters : undefined,
    refreshInterval,
    enabled: isConfigured,
  });

  /**
   * Extract the metric value from the data
   */
  const getValue = (): number => {
    if (!data?.rows?.length) return 0;
    const firstRow = data.rows[0];
    const metricKey = data.meta?.metrics?.[0];
    if (!metricKey) return 0;
    return Number(firstRow[metricKey]) || 0;
  };

  /**
   * Format the value based on format type
   */
  const formatValue = (value: number): string => {
    switch (format) {
      case 'currency':
        return new Intl.NumberFormat('zh-CN', {
          style: 'currency',
          currency,
          minimumFractionDigits: precision,
          maximumFractionDigits: precision,
        }).format(value);
      case 'percent':
        return `${(value * 100).toFixed(precision)}%`;
      default:
        return new Intl.NumberFormat('zh-CN', {
          minimumFractionDigits: precision,
          maximumFractionDigits: precision,
        }).format(value);
    }
  };

  /**
   * Handle click event for linkage
   */
  const handleClick = () => {
    // Drill-down navigation takes priority
    if (drillDown?.enabled && onDrillDown) {
      onDrillDown(drillDown);
      return;
    }

    if (!linkage?.enabled || !linkage?.emitFilter || !onLinkageEmit) return;

    // Emit the current value as a filter if there's a dimension
    const dimension = data?.meta?.dimensions?.[0];
    const firstRow = data?.rows?.[0];
    if (dimension && firstRow) {
      onLinkageEmit([
        {
          field: dimension,
          operator: 'eq',
          value: firstRow[dimension],
        },
      ]);
    }
  };

  const isClickable =
    (linkage?.enabled && linkage?.emitFilter) || (drillDown?.enabled && onDrillDown);

  // Not configured state
  if (!isConfigured) {
    return (
      <div
        className={cn(
          'flex h-full flex-col items-center justify-center rounded-lg border border-dashed border-gray-300 bg-white p-4',
          className,
        )}
        style={style}
      >
        <div className="mb-2 text-3xl text-gray-400">🔢</div>
        <div className="text-sm font-medium text-gray-500">{title || '数字卡片'}</div>
        <div className="mt-1 text-xs text-gray-400">请配置数据源</div>
      </div>
    );
  }

  return (
    <div
      className={cn(
        'flex h-full flex-col justify-between rounded-lg border border-gray-200 bg-white p-4',
        'shadow-sm transition-shadow duration-200 hover:shadow-md',
        isClickable && 'cursor-pointer hover:border-blue-300',
        className,
      )}
      style={style}
      onClick={handleClick}
      role={isClickable ? 'button' : undefined}
      tabIndex={isClickable ? 0 : undefined}
      onKeyDown={
        isClickable
          ? (e) => {
              if (e.key === 'Enter' || e.key === ' ') {
                handleClick();
              }
            }
          : undefined
      }
    >
      {/* Header */}
      <div className="flex items-center justify-between">
        <span className="truncate text-sm font-medium text-gray-500">{title}</span>
        {icon && (
          <span className="ml-2 flex-shrink-0 text-xl" aria-hidden="true">
            {icon}
          </span>
        )}
      </div>

      {/* Value */}
      <div className="mt-3">
        {loading ? (
          <div className="h-8 animate-pulse rounded bg-gray-200" aria-label="Loading" />
        ) : error ? (
          <div className="text-sm text-red-500" role="alert">
            <span className="font-medium">Error: </span>
            {error.message || 'Failed to load data'}
          </div>
        ) : (
          <div className="text-2xl font-semibold text-gray-900 tabular-nums">
            {formatValue(getValue())}
          </div>
        )}
      </div>

      {/* Trend indicator */}
      {trend?.enabled && (
        <div className="mt-2 text-xs text-gray-400">
          <span className="inline-flex items-center">
            {trend.compareType === 'lastDay' && 'vs yesterday'}
            {trend.compareType === 'lastWeek' && 'vs last week'}
            {trend.compareType === 'lastMonth' && 'vs last month'}
            {!trend.compareType && 'vs previous period'}
            <span className="ml-1">--</span>
          </span>
        </div>
      )}
    </div>
  );
};

export default SmartNumberCard;
