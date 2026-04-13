/**
 * SmartProgress Component
 *
 * A progress indicator component using pure CSS/SVG.
 * Supports bar and circle shapes with percentage or fraction display.
 */

import React, { useMemo } from 'react';
import { useChartData } from '~/framework/smart/hooks/useChartData';
import type { ChartDataSource, FilterConfig, LinkageConfig } from '~/framework/smart/types/chart';
import { cn } from '~/utils/cn';

/**
 * Props for SmartProgress component
 */
export interface SmartProgressProps {
  /** Component title */
  title?: string;
  /** Data source configuration */
  dataSource: ChartDataSource;
  /** Target value (denominator) */
  target?: number;
  /** Display format */
  format?: 'percent' | 'fraction';
  /** Progress shape */
  shape?: 'bar' | 'circle';
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
 * Get color based on progress percentage
 */
function getProgressColor(percent: number): string {
  if (percent >= 80) return '#52c41a';
  if (percent >= 50) return '#1890ff';
  if (percent >= 30) return '#faad14';
  return '#ff4d4f';
}

/**
 * Circular progress SVG component
 */
const CircleProgress: React.FC<{ percent: number; label: string }> = ({ percent, label }) => {
  const radius = 60;
  const strokeWidth = 10;
  const circumference = 2 * Math.PI * radius;
  const offset = circumference - (Math.min(percent, 100) / 100) * circumference;
  const color = getProgressColor(percent);

  return (
    <div className="flex flex-col items-center justify-center">
      <svg width="160" height="160" viewBox="0 0 160 160">
        {/* Background circle */}
        <circle cx="80" cy="80" r={radius} fill="none" stroke="#f0f0f0" strokeWidth={strokeWidth} />
        {/* Progress circle */}
        <circle
          cx="80"
          cy="80"
          r={radius}
          fill="none"
          stroke={color}
          strokeWidth={strokeWidth}
          strokeLinecap="round"
          strokeDasharray={circumference}
          strokeDashoffset={offset}
          transform="rotate(-90 80 80)"
          style={{ transition: 'stroke-dashoffset 0.6s ease' }}
        />
        {/* Center text */}
        <text
          x="80"
          y="80"
          textAnchor="middle"
          dominantBaseline="central"
          className="text-2xl font-semibold"
          fill="#333"
        >
          {label}
        </text>
      </svg>
    </div>
  );
};

/**
 * Bar progress component
 */
const BarProgress: React.FC<{ percent: number; label: string }> = ({ percent, label }) => {
  const color = getProgressColor(percent);

  return (
    <div className="w-full px-4">
      <div className="mb-2 flex items-center justify-between">
        <span className="text-2xl font-semibold text-gray-900">{label}</span>
      </div>
      <div className="h-4 w-full overflow-hidden rounded-full bg-gray-100">
        <div
          className="h-full rounded-full transition-all duration-600 ease-out"
          style={{
            width: `${Math.min(percent, 100)}%`,
            backgroundColor: color,
          }}
        />
      </div>
    </div>
  );
};

export const SmartProgress: React.FC<SmartProgressProps> = ({
  title,
  dataSource,
  target = 100,
  format = 'percent',
  shape = 'bar',
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
   * Extract the metric value from the data
   */
  const value = useMemo(() => {
    if (!data?.rows?.length) return 0;
    const firstRow = data.rows[0];
    const metricKey = data.meta?.metrics?.[0];
    if (!metricKey) return 0;
    return Number(firstRow[metricKey]) || 0;
  }, [data]);

  const percent = target > 0 ? (value / target) * 100 : 0;

  const label = format === 'fraction' ? `${value} / ${target}` : `${Math.round(percent)}%`;

  // Not configured state
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
          <div className="mb-3 text-4xl text-gray-400">📈</div>
          <div className="font-medium text-gray-500">{title || '进度'}</div>
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

  // Error state
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
      className={cn(
        'flex h-full flex-col items-center justify-center rounded-lg border border-gray-200 bg-white p-4',
        className,
      )}
      style={style}
    >
      {title && <div className="mb-3 text-sm font-medium text-gray-500">{title}</div>}
      {shape === 'circle' ? (
        <CircleProgress percent={percent} label={label} />
      ) : (
        <BarProgress percent={percent} label={label} />
      )}
    </div>
  );
};

export default SmartProgress;
