/**
 * SmartGaugeChart Component
 *
 * A gauge chart component using ECharts.
 * Displays a single metric value as a gauge dial with configurable ranges and colors.
 */

import React, { useMemo } from 'react';
import ReactECharts from 'echarts-for-react';
import type { EChartsOption } from 'echarts';
import { useChartData } from '~/smart/hooks/useChartData';
import type { ChartDataSource, FilterConfig, LinkageConfig } from '~/smart/types/chart';
import { cn } from '~/utils/cn';

/**
 * Color segment for gauge ranges
 */
interface ColorSegment {
  /** Value threshold (0-1 ratio of max) */
  threshold: number;
  /** Color for this segment */
  color: string;
}

/**
 * Props for SmartGaugeChart component
 */
export interface SmartGaugeChartProps {
  /** Chart title */
  title?: string;
  /** Data source configuration */
  dataSource: ChartDataSource;
  /** Minimum value */
  min?: number;
  /** Maximum value */
  max?: number;
  /** Number of split sections */
  splitNumber?: number;
  /** Color segments for the gauge */
  colorSegments?: ColorSegment[];
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
 * Default color segments: green -> yellow -> red
 */
const defaultColorSegments: ColorSegment[] = [
  { threshold: 0.3, color: '#67e0e3' },
  { threshold: 0.7, color: '#37a2da' },
  { threshold: 1, color: '#fd666d' },
];

export const SmartGaugeChart: React.FC<SmartGaugeChartProps> = ({
  title,
  dataSource,
  min = 0,
  max = 100,
  splitNumber = 10,
  colorSegments = defaultColorSegments,
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

  /**
   * Build ECharts options
   */
  const options: EChartsOption = useMemo(() => {
    const axisLineColors = colorSegments.map(
      (seg) => [seg.threshold, seg.color] as [number, string],
    );

    return {
      title: title
        ? {
            text: title,
            left: 'center',
            textStyle: { fontSize: 14, fontWeight: 500 },
          }
        : undefined,
      series: [
        {
          type: 'gauge',
          min,
          max,
          splitNumber,
          progress: {
            show: true,
            width: 18,
          },
          axisLine: {
            lineStyle: {
              width: 18,
              color: axisLineColors,
            },
          },
          axisTick: {
            show: true,
            distance: -30,
            length: 8,
            lineStyle: { color: '#999', width: 1 },
          },
          splitLine: {
            distance: -30,
            length: 14,
            lineStyle: { color: '#999', width: 2 },
          },
          axisLabel: {
            distance: 25,
            color: '#999',
            fontSize: 12,
          },
          pointer: {
            show: true,
            length: '60%',
            width: 6,
          },
          anchor: {
            show: true,
            showAbove: true,
            size: 20,
            itemStyle: {
              borderWidth: 8,
            },
          },
          detail: {
            valueAnimation: true,
            fontSize: 24,
            fontWeight: 'bold',
            offsetCenter: [0, '70%'],
            formatter: '{value}',
          },
          data: [{ value }],
        },
      ],
    };
  }, [title, value, min, max, splitNumber, colorSegments]);

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
          <div className="mb-3 text-4xl text-gray-400">🎯</div>
          <div className="font-medium text-gray-500">{title || '仪表盘'}</div>
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
        notMerge
        lazyUpdate
      />
    </div>
  );
};

export default SmartGaugeChart;
