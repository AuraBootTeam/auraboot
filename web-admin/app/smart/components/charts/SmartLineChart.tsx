/**
 * SmartLineChart Component
 *
 * A line chart component using ECharts.
 * Supports smooth curves, area fill, and multiple lines.
 */

import React, { useMemo, useCallback } from 'react';
import ReactECharts from 'echarts-for-react';
import type { EChartsOption } from 'echarts';
import { useChartData } from '~/smart/hooks/useChartData';
import type {
  ChartDataSource,
  DrillDownConfig,
  LinkageConfig,
  FilterConfig,
} from '~/smart/types/chart';
import { cn } from '~/utils/cn';

/**
 * Props for SmartLineChart component
 */
export interface SmartLineChartProps {
  /** Chart title */
  title?: string;
  /** Data source configuration */
  dataSource: ChartDataSource;
  /** Enable smooth curves */
  smooth?: boolean;
  /** Enable area fill under the line */
  areaStyle?: boolean;
  /** Show data points on the line */
  showSymbol?: boolean;
  /** Show data labels */
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
 * SmartLineChart - A line chart component with ECharts
 *
 * @example
 * // Basic line chart
 * <SmartLineChart
 *   title="Daily Orders"
 *   dataSource={{
 *     type: 'aggregate',
 *     modelCode: 'order',
 *     dimensions: ['created_date'],
 *     metrics: [{ field: 'id', aggregation: 'count', alias: 'count' }],
 *   }}
 * />
 *
 * @example
 * // Smooth area chart with multiple lines
 * <SmartLineChart
 *   title="Revenue Trend"
 *   dataSource={{
 *     type: 'aggregate',
 *     modelCode: 'order',
 *     dimensions: ['month'],
 *     metrics: [
 *       { field: 'revenue', aggregation: 'sum', alias: 'revenue' },
 *       { field: 'cost', aggregation: 'sum', alias: 'cost' },
 *     ],
 *   }}
 *   smooth
 *   areaStyle
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

export const SmartLineChart: React.FC<SmartLineChartProps> = ({
  title,
  dataSource,
  smooth = false,
  areaStyle = false,
  showSymbol = true,
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

  /**
   * Handle chart click events for drill-down and linkage
   */
  const handleChartClick = useCallback(
    (params: { name?: string; dataIndex?: number }) => {
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
        xAxis: { type: 'category', data: [] },
        yAxis: { type: 'value' },
        series: [],
      };
    }

    const dimensions = data.meta?.dimensions || [];
    const metrics = data.meta?.metrics || [];
    const dimensionKey = dimensions[0];

    // Extract category labels from the dimension field
    const categories = data.rows.map((row) => String(row[dimensionKey] ?? ''));

    // Build series for each metric
    const series = metrics.map((metricKey, index) => ({
      name: metricKey,
      type: 'line' as const,
      data: data.rows.map((row) => Number(row[metricKey]) || 0),
      smooth,
      showSymbol,
      symbol: 'circle' as const,
      symbolSize: 6,
      areaStyle: areaStyle
        ? {
            opacity: 0.3 - index * 0.1, // Gradient opacity for multiple areas
          }
        : undefined,
      label: {
        show: showLabel,
        position: 'top' as const,
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
        axisPointer: { type: 'cross' },
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
      xAxis: {
        type: 'category',
        data: categories,
        boundaryGap: false,
        axisLabel: {
          rotate: categories.length > 10 ? 45 : 0,
          hideOverlap: true,
        },
      },
      yAxis: {
        type: 'value',
      },
      series,
    };

    // Merge with custom options
    return chartOptions ? { ...baseOptions, ...chartOptions } : baseOptions;
  }, [data, title, smooth, areaStyle, showSymbol, showLabel, chartOptions]);

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
          <div className="mb-3 text-4xl text-gray-400">📈</div>
          <div className="font-medium text-gray-500">{title || '折线图'}</div>
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

export default SmartLineChart;
