/**
 * SmartSPCChart Component
 *
 * A Statistical Process Control (SPC) chart that displays measurement values
 * over time/sequence with control limits (UCL/CL/LCL), optional warning limits
 * (UWL/LWL at +/-2 sigma), and highlights out-of-control points in red.
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
 * Props for SmartSPCChart component
 */
export interface SmartSPCChartProps {
  /** Chart title */
  title?: string;
  /** Data source configuration (time series data) */
  dataSource: ChartDataSource;
  /** Show warning limits at +/- 2 sigma (default: true) */
  showWarningLimits?: boolean;
  /** Manual Upper Control Limit override (default: auto-calculated mean + 3 sigma) */
  ucl?: number;
  /** Manual Lower Control Limit override (default: auto-calculated mean - 3 sigma) */
  lcl?: number;
  /** Manual Center Line override (default: auto-calculated mean) */
  cl?: number;
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
 * Calculate the mean (average) of an array of numbers
 */
function calcMean(values: number[]): number {
  if (values.length === 0) return 0;
  return values.reduce((sum, v) => sum + v, 0) / values.length;
}

/**
 * Calculate the standard deviation of an array of numbers
 */
function calcStdDev(values: number[], mean: number): number {
  if (values.length < 2) return 0;
  const sumSquaredDiffs = values.reduce((sum, v) => sum + (v - mean) ** 2, 0);
  return Math.sqrt(sumSquaredDiffs / (values.length - 1));
}

/**
 * SmartSPCChart - A Statistical Process Control chart with ECharts
 *
 * @example
 * // Basic SPC chart with auto-calculated control limits
 * <SmartSPCChart
 *   title="Process Control - Thickness"
 *   dataSource={{
 *     type: 'aggregate',
 *     modelCode: 'measurement',
 *     dimensions: ['sample_no'],
 *     metrics: [{ field: 'thickness', aggregation: 'avg', alias: 'avg_thickness' }],
 *   }}
 * />
 *
 * @example
 * // SPC chart with manual control limits
 * <SmartSPCChart
 *   title="Weight Control"
 *   dataSource={{
 *     type: 'aggregate',
 *     modelCode: 'weight_check',
 *     dimensions: ['batch_no'],
 *     metrics: [{ field: 'weight', aggregation: 'avg', alias: 'avg_weight' }],
 *   }}
 *   ucl={105}
 *   cl={100}
 *   lcl={95}
 *   showWarningLimits={false}
 * />
 */
export const SmartSPCChart: React.FC<SmartSPCChartProps> = ({
  title,
  dataSource,
  showWarningLimits = true,
  ucl: manualUcl,
  lcl: manualLcl,
  cl: manualCl,
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
    (params: { name?: string; seriesName?: string; dataIndex?: number; data?: unknown }) => {
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
   * 1. Extract measurement values from the first metric field
   * 2. Calculate mean and standard deviation (or use manual overrides)
   * 3. Determine UCL, LCL, UWL, LWL
   * 4. Identify out-of-control points (beyond UCL/LCL)
   * 5. Build line chart with markLines for control/warning limits
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
    const metricKey = metrics[0];

    // Extract categories (x-axis labels) and metric values
    const categories = data.rows.map((row) => String(row[dimensionKey] ?? ''));
    const values = data.rows.map((row) => Number(row[metricKey]) || 0);

    // Calculate statistical control limits
    const mean = manualCl ?? calcMean(values);
    const stdDev = calcStdDev(values, mean);

    const uclValue = manualUcl ?? mean + 3 * stdDev;
    const lclValue = manualLcl ?? mean - 3 * stdDev;
    const uwlValue = mean + 2 * stdDev;
    const lwlValue = mean - 2 * stdDev;

    // Build data points; highlight out-of-control points in red
    const dataPoints = values.map((v) => {
      const outOfControl = v > uclValue || v < lclValue;
      if (outOfControl) {
        return {
          value: v,
          itemStyle: { color: '#ee6666' },
          symbolSize: 10,
          symbol: 'circle',
        };
      }
      return v;
    });

    // Build markLine data array for control limits
    // Use 'as const' on lineStyle.type and label.position to satisfy ECharts union types
    const markLineData = [
      {
        yAxis: uclValue,
        name: 'ucl',
        lineStyle: { type: 'dashed' as const, color: '#ee6666', width: 1.5 },
        label: { formatter: `UCL: ${uclValue.toFixed(2)}`, position: 'end' as const },
      },
      {
        yAxis: mean,
        name: 'CL',
        lineStyle: { type: 'solid' as const, color: '#91cc75', width: 2 },
        label: { formatter: `CL: ${mean.toFixed(2)}`, position: 'end' as const },
      },
      {
        yAxis: lclValue,
        name: 'lcl',
        lineStyle: { type: 'dashed' as const, color: '#ee6666', width: 1.5 },
        label: { formatter: `LCL: ${lclValue.toFixed(2)}`, position: 'end' as const },
      },
    ];

    // Add warning limit lines at +/- 2 sigma if enabled
    if (showWarningLimits) {
      markLineData.push(
        {
          yAxis: uwlValue,
          name: 'uwl',
          lineStyle: { type: [2, 4] as unknown as 'dashed', color: '#fac858', width: 1 },
          label: { formatter: `UWL: ${uwlValue.toFixed(2)}`, position: 'end' as const },
        },
        {
          yAxis: lwlValue,
          name: 'lwl',
          lineStyle: { type: [2, 4] as unknown as 'dashed', color: '#fac858', width: 1 },
          label: { formatter: `LWL: ${lwlValue.toFixed(2)}`, position: 'end' as const },
        },
      );
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
        formatter: (params: unknown) => {
          if (!Array.isArray(params) || params.length === 0) return '';
          const p = params[0] as {
            name?: string;
            value?: number | { value?: number };
            marker?: string;
          };
          const category = p.name ?? '';
          const val = typeof p.value === 'object' ? (p.value?.value ?? 0) : (p.value ?? 0);
          const outOfControl = (val as number) > uclValue || (val as number) < lclValue;
          let result = `<strong>${category}</strong><br/>`;
          result += `${p.marker ?? ''} ${metricKey}: ${(val as number).toFixed(2)}`;
          if (outOfControl) {
            result += ` <span style="color:#ee6666;font-weight:bold;">(Out of control)</span>`;
          }
          return result;
        },
      },
      grid: {
        left: '3%',
        right: '12%',
        bottom: '3%',
        top: title ? '15%' : '10%',
        containLabel: true,
      },
      xAxis: {
        type: 'category',
        data: categories,
        axisLabel: {
          rotate: categories.length > 15 ? 45 : 0,
          hideOverlap: true,
        },
      },
      yAxis: {
        type: 'value',
      },
      series: [
        {
          name: metricKey,
          type: 'line',
          data: dataPoints,
          symbol: 'circle',
          symbolSize: 6,
          lineStyle: { color: '#5470c6', width: 2 },
          itemStyle: { color: '#5470c6' },
          emphasis: { focus: 'series' as const },
          markLine: {
            silent: true,
            symbol: 'none',
            data: markLineData,
          },
        },
      ],
    };

    // Merge with custom options
    return chartOptions ? { ...baseOptions, ...chartOptions } : baseOptions;
  }, [data, title, showWarningLimits, manualUcl, manualLcl, manualCl, chartOptions]);

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
          <div className="font-medium text-gray-500">{title || 'SPC Chart'}</div>
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

export default SmartSPCChart;
