/**
 * SmartPieChart Component
 *
 * A pie/donut chart component using ECharts.
 * Supports ring (donut) mode and interactive drill-down/linkage.
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

/**
 * Props for SmartPieChart component
 */
export interface SmartPieChartProps {
  /** Chart title */
  title?: string;
  /** Data source configuration */
  dataSource: ChartDataSource;
  /** Enable donut/ring mode */
  ring?: boolean;
  /** Inner radius for ring mode (percentage or pixels) */
  innerRadius?: string | number;
  /** Outer radius (percentage or pixels) */
  outerRadius?: string | number;
  /** Show percentage labels */
  showLabel?: boolean;
  /** Label position */
  labelPosition?: 'inside' | 'outside';
  /** Enable rose (nightingale) chart mode */
  roseType?: boolean | 'radius' | 'area';
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
  /** Auto-refresh interval in milliseconds (0 = disabled) */
  refreshInterval?: number;
  /** Custom CSS class */
  className?: string;
  /** Custom inline styles */
  style?: React.CSSProperties;
  /** Custom ECharts options to merge */
  chartOptions?: Partial<EChartsOption>;
}

/**
 * SmartPieChart - A pie/donut chart component with ECharts
 *
 * @example
 * // Basic pie chart
 * <SmartPieChart
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
 * // Donut chart with labels
 * <SmartPieChart
 *   title="Revenue by Category"
 *   dataSource={{
 *     type: 'aggregate',
 *     modelCode: 'product',
 *     dimensions: ['category'],
 *     metrics: [{ field: 'revenue', aggregation: 'sum', alias: 'revenue' }],
 *   }}
 *   ring
 *   showLabel
 *   labelPosition="outside"
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

export const SmartPieChart: React.FC<SmartPieChartProps> = ({
  title,
  dataSource,
  ring = false,
  innerRadius = '50%',
  outerRadius = '70%',
  showLabel = true,
  labelPosition = 'outside',
  roseType = false,
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
    (params: { name?: string; data?: { name?: string; value?: number } }) => {
      if (!data?.meta?.dimensions?.length) return;

      const dimension = data.meta.dimensions[0];
      const clickedValue = params.name || params.data?.name;

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
        series: [
          {
            type: 'pie',
            data: [],
          },
        ],
      };
    }

    const dimensions = data.meta?.dimensions || [];
    const metrics = data.meta?.metrics || [];
    const dimensionKey = dimensions[0];
    const metricKey = metrics[0];

    // Build pie data: { name, value } pairs
    const pieData = data.rows.map((row) => ({
      name: String(row[dimensionKey] ?? ''),
      value: Number(row[metricKey]) || 0,
    }));

    // Calculate rose type value
    const roseTypeValue = roseType === true ? 'radius' : roseType || undefined;

    const baseOptions: EChartsOption = {
      title: title
        ? {
            text: title,
            left: 'center',
            textStyle: { fontSize: 14, fontWeight: 500 },
          }
        : undefined,
      tooltip: {
        trigger: 'item',
        formatter: '{b}: {c} ({d}%)',
      },
      legend: {
        orient: 'horizontal',
        bottom: 0,
        type: 'scroll',
      },
      series: [
        {
          name: title || 'Data',
          type: 'pie',
          radius: ring ? [innerRadius, outerRadius] : outerRadius,
          center: ['50%', '50%'],
          roseType: roseTypeValue,
          data: pieData,
          label: {
            show: showLabel,
            position: labelPosition,
            formatter: labelPosition === 'inside' ? '{d}%' : '{b}: {d}%',
          },
          labelLine: {
            show: showLabel && labelPosition === 'outside',
          },
          emphasis: {
            itemStyle: {
              shadowBlur: 10,
              shadowOffsetX: 0,
              shadowColor: 'rgba(0, 0, 0, 0.5)',
            },
          },
          animationType: 'scale',
          animationEasing: 'elasticOut',
        },
      ],
    };

    // Merge with custom options
    return chartOptions ? { ...baseOptions, ...chartOptions } : baseOptions;
  }, [
    data,
    title,
    ring,
    innerRadius,
    outerRadius,
    showLabel,
    labelPosition,
    roseType,
    chartOptions,
  ]);

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
          <div className="mb-3 text-4xl text-gray-400">🥧</div>
          <div className="font-medium text-gray-500">{title || '饼图'}</div>
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

export default SmartPieChart;
