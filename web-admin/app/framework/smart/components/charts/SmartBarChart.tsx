/**
 * SmartBarChart Component
 *
 * A bar chart component using ECharts.
 * Supports horizontal/vertical orientation, stacked mode, and multiple Y-axis fields.
 */

import React, { useMemo, useCallback } from 'react';
import ReactECharts from 'echarts-for-react';
import type { EChartsOption } from 'echarts';
import { useChartData } from '~/framework/smart/hooks/useChartData';
import type {
  ChartDataSource,
  DrillDownConfig,
  LinkageConfig,
  FilterConfig,
} from '~/framework/smart/types/chart';
import { cn } from '~/utils/cn';
import { ChartEmptyState } from './ChartEmptyState';

/**
 * Props for SmartBarChart component
 */
export interface SmartBarChartProps {
  /** Chart title */
  title?: string;
  /** Data source configuration */
  dataSource: ChartDataSource;
  /** Bar orientation: vertical (default) or horizontal */
  orientation?: 'vertical' | 'horizontal';
  /** Enable stacked bars */
  stacked?: boolean;
  /** Show data labels on bars */
  showLabel?: boolean;
  /** Drill-down configuration */
  drillDown?: DrillDownConfig;
  /** Linkage configuration */
  linkage?: LinkageConfig;
  /** Callback when drill-down is triggered */
  onDrillDown?: (filters: FilterConfig[]) => void;
  /** Callback when linkage filter is emitted */
  onLinkageEmit?: (filters: FilterConfig[]) => void;
  /** Linkage filters from other charts */
  linkageFilters?: FilterConfig[];
  /** Custom CSS class */
  className?: string;
  /** Custom inline styles */
  style?: React.CSSProperties;
  /** Auto-refresh interval in milliseconds (0 = disabled) */
  refreshInterval?: number;
  /** Custom ECharts options to merge */
  chartOptions?: Partial<EChartsOption>;
}

/**
 * SmartBarChart - A bar chart component with ECharts
 *
 * @example
 * // Basic vertical bar chart
 * <SmartBarChart
 *   title="Orders by Status"
 *   dataSource={{
 *     type: 'aggregate',
 *     modelCode: 'order',
 *     dimensions: ['status'],
 *     metrics: [{ field: 'id', aggregation: 'count', alias: 'count' }],
 *   }}
 * />
 *
 * @example
 * // Horizontal stacked bar chart
 * <SmartBarChart
 *   title="Sales by Region"
 *   dataSource={{
 *     type: 'aggregate',
 *     modelCode: 'sales',
 *     dimensions: ['region'],
 *     metrics: [
 *       { field: 'online_sales', aggregation: 'sum', alias: 'online' },
 *       { field: 'offline_sales', aggregation: 'sum', alias: 'offline' },
 *     ],
 *   }}
 *   orientation="horizontal"
 *   stacked
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

export const SmartBarChart: React.FC<SmartBarChartProps> = ({
  title,
  dataSource,
  orientation = 'vertical',
  stacked = false,
  showLabel = false,
  drillDown,
  linkage,
  onDrillDown,
  onLinkageEmit,
  linkageFilters,
  refreshInterval,
  className,
  style,
  chartOptions,
}) => {
  const isConfigured = isDataSourceConfigured(dataSource);

  const { data, loading, error } = useChartData({
    dataSource,
    linkageFilters: linkage?.receiveFilter ? linkageFilters : undefined,
    refreshInterval,
    enabled: isConfigured,
  });
  const isEmpty = !loading && !error && !data?.rows?.length;

  /**
   * Handle chart click events for drill-down and linkage
   */
  const handleChartClick = useCallback(
    (params: { name?: string; seriesName?: string; data?: unknown }) => {
      if (!data?.meta?.dimensions?.length) return;

      const dimension = data.meta.dimensions[0];
      const clickedValue = params.name;

      if (!clickedValue) return;

      const filter: FilterConfig = {
        field: dimension,
        operator: 'eq',
        value: clickedValue,
      };

      // Handle drill-down
      if (drillDown?.enabled && onDrillDown) {
        onDrillDown([filter]);
      }

      // Handle linkage
      if (linkage?.enabled && linkage?.emitFilter && onLinkageEmit) {
        onLinkageEmit([filter]);
      }
    },
    [data, drillDown, linkage, onDrillDown, onLinkageEmit],
  );

  /**
   * Build ECharts options from data
   */
  const options: EChartsOption = useMemo(() => {
    if (!data?.rows?.length) {
      return {
        title: title ? { text: title, left: 'center', textStyle: { fontSize: 14 } } : undefined,
        xAxis: { type: orientation === 'vertical' ? 'category' : 'value', data: [] },
        yAxis: { type: orientation === 'vertical' ? 'value' : 'category', data: [] },
        series: [],
      };
    }

    const dimensions = data.meta?.dimensions || [];
    const metrics = data.meta?.metrics || [];
    const dimensionKey = dimensions[0];

    // Extract category labels from the dimension field
    const categories = data.rows.map((row) => String(row[dimensionKey] ?? ''));

    // Build series for each metric
    const series = metrics.map((metricKey) => ({
      name: metricKey,
      type: 'bar' as const,
      stack: stacked ? 'total' : undefined,
      data: data.rows.map((row) => Number(row[metricKey]) || 0),
      label: {
        show: showLabel,
        position: (orientation === 'vertical' ? 'top' : 'right') as 'top' | 'right',
      },
      emphasis: {
        focus: 'series' as const,
      },
    }));

    const baseOptions: EChartsOption = {
      title: title
        ? {
            text: title,
            left: 'center',
            textStyle: { fontSize: 14, fontWeight: 500 },
          }
        : undefined,
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'shadow' },
      },
      legend:
        metrics.length > 1
          ? {
              bottom: 0,
              type: 'scroll',
            }
          : undefined,
      grid: {
        left: '3%',
        right: '4%',
        bottom: metrics.length > 1 ? '15%' : '3%',
        top: title ? '15%' : '10%',
        containLabel: true,
      },
      xAxis:
        orientation === 'vertical'
          ? {
              type: 'category',
              data: categories,
              axisLabel: {
                rotate: categories.length > 10 ? 45 : 0,
                hideOverlap: true,
              },
            }
          : {
              type: 'value',
            },
      yAxis:
        orientation === 'vertical'
          ? {
              type: 'value',
            }
          : {
              type: 'category',
              data: categories,
            },
      series,
    };

    // Merge with custom options
    return chartOptions ? { ...baseOptions, ...chartOptions } : baseOptions;
  }, [data, title, orientation, stacked, showLabel, chartOptions]);

  /**
   * ECharts event handlers
   */
  const onEvents = useMemo(
    () => ({
      click: handleChartClick,
    }),
    [handleChartClick],
  );

  // Not configured state
  if (!isConfigured) {
    return (
      <div
        className={cn(
          'flex items-center justify-center rounded-lg border border-dashed border-gray-300 bg-white p-4',
          className,
        )}
        style={{ minHeight: 0, ...style }}
      >
        <div className="text-center">
          <div className="mb-3 text-4xl text-gray-400">📊</div>
          <div className="font-medium text-gray-500">{title || '柱状图'}</div>
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
        style={{ minHeight: 0, ...style }}
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
        style={{ minHeight: 0, ...style }}
        role="alert"
      >
        <div className="text-center">
          <div className="mb-2 text-lg text-red-500">Failed to load chart</div>
          <div className="text-sm text-gray-500">{error.message}</div>
        </div>
      </div>
    );
  }

  if (isEmpty) {
    return (
      <ChartEmptyState
        title={title || 'No bar data yet'}
        description="This chart will render once the first matching records are created."
        variant="bar"
        className={className}
      />
    );
  }

  return (
    <div className={cn('rounded-lg border border-gray-200 bg-white p-4', className)} style={style}>
      <ReactECharts
        option={options}
        style={{ height: '100%', minHeight: 0 }}
        onEvents={onEvents}
        notMerge
        lazyUpdate
      />
    </div>
  );
};

export default SmartBarChart;
