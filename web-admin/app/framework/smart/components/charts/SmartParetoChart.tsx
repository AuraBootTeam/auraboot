/**
 * SmartParetoChart Component
 *
 * A Pareto chart combining a bar series (sorted by frequency/impact descending)
 * on the primary Y-axis with a cumulative percentage line on the secondary Y-axis.
 * Supports an 80% threshold reference line, drill-down, and linkage.
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
 * Props for SmartParetoChart component
 */
export interface SmartParetoChartProps {
  /** Chart title */
  title?: string;
  /** Data source configuration (aggregate query returning category + count) */
  dataSource: ChartDataSource;
  /** Show the threshold reference line (default: true) */
  showThreshold?: boolean;
  /** Threshold percentage value (default: 80) */
  thresholdValue?: number;
  /** Bar color (default: '#5470c6') */
  barColor?: string;
  /** Cumulative percentage line color (default: '#ee6666') */
  lineColor?: string;
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
 * SmartParetoChart - A Pareto chart component with ECharts
 *
 * @example
 * // Defect Pareto analysis
 * <SmartParetoChart
 *   title="Defect Pareto Analysis"
 *   dataSource={{
 *     type: 'aggregate',
 *     modelCode: 'defect',
 *     dimensions: ['defect_type'],
 *     metrics: [{ field: 'id', aggregation: 'count', alias: 'count' }],
 *   }}
 * />
 *
 * @example
 * // With custom threshold and colors
 * <SmartParetoChart
 *   title="Cost Breakdown"
 *   dataSource={{
 *     type: 'aggregate',
 *     modelCode: 'expense',
 *     dimensions: ['category'],
 *     metrics: [{ field: 'amount', aggregation: 'sum', alias: 'total' }],
 *   }}
 *   thresholdValue={90}
 *   barColor="#91cc75"
 *   lineColor="#fc8452"
 * />
 */
export const SmartParetoChart: React.FC<SmartParetoChartProps> = ({
  title,
  dataSource,
  showThreshold = true,
  thresholdValue = 80,
  barColor = '#5470c6',
  lineColor = '#ee6666',
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
   *
   * Steps:
   * 1. Sort rows by the first metric field descending
   * 2. Compute cumulative percentages
   * 3. Build dual Y-axis config (value left, percentage right)
   */
  const options: EChartsOption = useMemo(() => {
    if (!data?.rows?.length) {
      return {
        title: title ? { text: title, left: 'center', textStyle: { fontSize: 14 } } : undefined,
        xAxis: { type: 'category', data: [] },
        yAxis: [{ type: 'value' }, { type: 'value', max: 100 }],
        series: [],
      };
    }

    const dimensions = data.meta?.dimensions || [];
    const metrics = data.meta?.metrics || [];
    const dimensionKey = dimensions[0];
    const metricKey = metrics[0];

    // Sort rows by the first metric descending
    const sortedRows = [...data.rows].sort((a, b) => {
      const va = Number(a[metricKey]) || 0;
      const vb = Number(b[metricKey]) || 0;
      return vb - va;
    });

    // Extract categories and values
    const categories = sortedRows.map((row) => String(row[dimensionKey] ?? ''));
    const values = sortedRows.map((row) => Number(row[metricKey]) || 0);

    // Calculate cumulative percentages
    const total = values.reduce((sum, v) => sum + v, 0);
    const cumulativePercentages: number[] = [];
    let cumulative = 0;
    for (const v of values) {
      cumulative += v;
      cumulativePercentages.push(total > 0 ? Math.round((cumulative / total) * 10000) / 100 : 0);
    }

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
        formatter: (params: unknown) => {
          if (!Array.isArray(params) || params.length === 0) return '';
          const category = (params[0] as { name?: string }).name ?? '';
          let result = `<strong>${category}</strong><br/>`;
          for (const p of params as Array<{
            marker?: string;
            seriesName?: string;
            value?: number;
            seriesIndex?: number;
          }>) {
            const val = p.value ?? 0;
            const unit = p.seriesIndex === 1 ? '%' : '';
            result += `${p.marker ?? ''} ${p.seriesName ?? ''}: ${val}${unit}<br/>`;
          }
          return result;
        },
      },
      legend: {
        bottom: 0,
        data: [metricKey, 'Cumulative %'],
      },
      grid: {
        left: '3%',
        right: '4%',
        bottom: '15%',
        top: title ? '15%' : '10%',
        containLabel: true,
      },
      xAxis: {
        type: 'category',
        data: categories,
        axisLabel: {
          rotate: categories.length > 8 ? 45 : 0,
          hideOverlap: true,
        },
      },
      yAxis: [
        {
          type: 'value',
          name: metricKey,
          nameTextStyle: { padding: [0, 0, 0, 50] },
        },
        {
          type: 'value',
          name: '%',
          min: 0,
          max: 100,
          axisLabel: { formatter: '{value}%' },
        },
      ],
      series: [
        // Bar series on the primary Y-axis
        {
          name: metricKey,
          type: 'bar',
          data: values,
          yAxisIndex: 0,
          itemStyle: { color: barColor },
          emphasis: { focus: 'series' as const },
          label: {
            show: false,
          },
        },
        // Cumulative percentage line on the secondary Y-axis
        {
          name: 'Cumulative %',
          type: 'line',
          data: cumulativePercentages,
          yAxisIndex: 1,
          smooth: true,
          symbol: 'circle',
          symbolSize: 6,
          lineStyle: { color: lineColor, width: 2 },
          itemStyle: { color: lineColor },
          // Threshold reference line (horizontal dashed line at thresholdValue %)
          markLine: showThreshold
            ? {
                silent: true,
                symbol: 'none',
                lineStyle: {
                  type: 'dashed',
                  color: '#999',
                  width: 1,
                },
                label: {
                  formatter: `${thresholdValue}%`,
                  position: 'end',
                },
                data: [
                  {
                    yAxis: thresholdValue,
                  },
                ],
              }
            : undefined,
        },
      ],
    };

    // Merge with custom options
    return chartOptions ? { ...baseOptions, ...chartOptions } : baseOptions;
  }, [data, title, showThreshold, thresholdValue, barColor, lineColor, chartOptions]);

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
          <div className="font-medium text-gray-500">{title || 'Pareto Chart'}</div>
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

export default SmartParetoChart;
