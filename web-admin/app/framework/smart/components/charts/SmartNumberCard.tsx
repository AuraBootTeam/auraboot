/**
 * SmartNumberCard Component
 *
 * A number card component for displaying single KPI metrics.
 * Supports number/currency/percent formatting with loading and error states.
 */

import React from 'react';
import { useChartData } from '~/framework/smart/hooks/useChartData';
import type {
  ChartDataSource,
  LinkageConfig,
  FilterConfig,
  DrillDownConfig,
} from '~/framework/smart/types/chart';
import { cn } from '~/utils/cn';

function hexToRgba(hex: string, alpha: number): string {
  const normalized = hex.replace('#', '').trim();
  const fullHex =
    normalized.length === 3
      ? normalized
          .split('')
          .map((char) => char + char)
          .join('')
      : normalized;

  if (!/^[0-9a-fA-F]{6}$/.test(fullHex)) {
    return `rgba(59, 130, 246, ${alpha})`;
  }

  const int = Number.parseInt(fullHex, 16);
  const r = (int >> 16) & 255;
  const g = (int >> 8) & 255;
  const b = int & 255;
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
}

/**
 * Props for SmartNumberCard component
 */
export interface SmartNumberCardProps {
  /** Card title */
  title: string;
  /** Optional icon (emoji or icon string) */
  icon?: string;
  /** Optional shorter label displayed as the metric eyebrow */
  label?: string;
  /** Data source configuration */
  dataSource: ChartDataSource;
  /** Value format type */
  format?: 'number' | 'currency' | 'percent';
  /** Decimal precision */
  precision?: number;
  /** Currency code for currency format (default: CNY) */
  currency?: string;
  /** Optional suffix appended to the formatted value */
  suffix?: string;
  /** Accent color used by the card chrome */
  color?: string;
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
  icon: _icon,
  label,
  dataSource,
  format = 'number',
  precision = 0,
  currency = 'cny',
  suffix,
  color = '#2563EB',
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
  const accentLine = hexToRgba(color, 0.22);
  const cardLabel = label || title;
  const isEmpty = !loading && !error && !data?.rows?.length;

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

  const formattedValue = `${formatValue(getValue())}${suffix || ''}`;

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
          'flex h-full flex-col items-center justify-center rounded-[22px] border border-dashed border-slate-300 bg-white/90 p-4 text-center shadow-sm',
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
        'flex h-full min-h-0 flex-col rounded-[22px] bg-transparent p-0',
        'transition duration-200',
        isClickable && 'cursor-pointer hover:border-sky-300',
        className,
      )}
      style={style}
      onClick={handleClick}
      data-card-style="metric"
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
      <div className="flex h-full flex-col p-5">
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <span className="block truncate text-[11px] font-semibold tracking-[0.16em] text-slate-500 uppercase">
              {cardLabel}
            </span>
            <span
              className="mt-3 block h-1 w-10 rounded-full"
              style={{ backgroundColor: accentLine }}
              aria-hidden="true"
            />
          </div>
        </div>

        <div className="mt-auto">
          {loading ? (
            <div className="space-y-3" aria-label="Loading">
              <div className="h-10 w-24 animate-pulse rounded-2xl bg-slate-200/80" />
              <div className="h-3 w-24 animate-pulse rounded-full bg-slate-200/60" />
            </div>
          ) : error ? (
            <div className="rounded-2xl border border-rose-200 bg-rose-50 px-3 py-2 text-sm text-rose-600" role="alert">
              <span className="font-medium">Error: </span>
              {error.message || 'Failed to load data'}
            </div>
          ) : isEmpty ? (
            <div className="space-y-3">
              <div className="text-4xl font-semibold tracking-tight text-slate-950 tabular-nums md:text-[2.65rem]">
                0{suffix || ''}
              </div>
              <div className="inline-flex items-center rounded-full border border-sky-100 bg-sky-50 px-2.5 py-1 text-[11px] font-medium text-sky-700">
                Waiting for first record
              </div>
              <div className="text-xs leading-5 text-slate-500">
                This KPI is ready. It will update automatically once matching business data is created.
              </div>
            </div>
          ) : (
            <>
              <div className="text-4xl font-semibold tracking-tight text-slate-950 tabular-nums md:text-[2.65rem]">
                {formattedValue}
              </div>
              <div className="mt-2 flex items-center gap-2 text-xs text-slate-500">
                <span
                  className="inline-block h-2 w-2 rounded-full"
                  style={{ backgroundColor: color }}
                  aria-hidden="true"
                />
                <span>{title}</span>
              </div>
            </>
          )}
        </div>

        {trend?.enabled && (
          <div className="mt-4 inline-flex w-fit items-center rounded-full border border-slate-200 bg-slate-50 px-2.5 py-1 text-xs text-slate-500">
            {trend.compareType === 'lastDay' && 'vs yesterday'}
            {trend.compareType === 'lastWeek' && 'vs last week'}
            {trend.compareType === 'lastMonth' && 'vs last month'}
            {!trend.compareType && 'vs previous period'}
            <span className="ml-1">--</span>
          </div>
        )}
      </div>
    </div>
  );
};

export default SmartNumberCard;
