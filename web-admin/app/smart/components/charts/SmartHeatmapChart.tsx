/**
 * SmartHeatmapChart Component
 *
 * A heatmap chart component using ECharts.
 * Requires 2 dimensions (x-axis, y-axis) and 1 metric (value/color intensity).
 */

import React, { useMemo } from 'react';
import ReactECharts from 'echarts-for-react';
import type { EChartsOption } from 'echarts';
import { useChartData } from '~/smart/hooks/useChartData';
import type { ChartDataSource, FilterConfig, LinkageConfig } from '~/smart/types/chart';
import { cn } from '~/utils/cn';

/**
 * Props for SmartHeatmapChart component
 */
export interface SmartHeatmapChartProps {
  /** Chart title */
  title?: string;
  /** Data source configuration */
  dataSource: ChartDataSource;
  /** Field for x-axis categories */
  xField?: string;
  /** Field for y-axis categories */
  yField?: string;
  /** Field for cell values */
  valueField?: string;
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

export const SmartHeatmapChart: React.FC<SmartHeatmapChartProps> = ({
  title,
  dataSource,
  xField,
  yField,
  valueField,
  linkage,
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
   * Build ECharts options from data
   */
  const options: EChartsOption = useMemo(() => {
    if (!data?.rows?.length) {
      return {
        title: title ? { text: title, left: 'center', textStyle: { fontSize: 14 } } : undefined,
        xAxis: { type: 'category', data: [] },
        yAxis: { type: 'category', data: [] },
        series: [],
      };
    }

    const dimensions = data.meta?.dimensions || [];
    const metrics = data.meta?.metrics || [];

    // Use provided fields or fall back to auto-detect from metadata
    const xKey = xField || dimensions[0];
    const yKey = yField || dimensions[1] || dimensions[0];
    const valKey = valueField || metrics[0];

    // Extract unique categories for each axis
    const xCategories = [...new Set(data.rows.map((row) => String(row[xKey] ?? '')))];
    const yCategories = [...new Set(data.rows.map((row) => String(row[yKey] ?? '')))];

    // Build heatmap data: [xIndex, yIndex, value]
    const heatmapData = data.rows.map((row) => {
      const xIdx = xCategories.indexOf(String(row[xKey] ?? ''));
      const yIdx = yCategories.indexOf(String(row[yKey] ?? ''));
      const val = Number(row[valKey]) || 0;
      return [xIdx, yIdx, val];
    });

    // Calculate min/max for visual map
    const values = heatmapData.map((d) => d[2]);
    const minVal = Math.min(...values);
    const maxVal = Math.max(...values);

    const baseOptions: EChartsOption = {
      title: title
        ? {
            text: title,
            left: 'center',
            textStyle: { fontSize: 14, fontWeight: 500 },
          }
        : undefined,
      tooltip: {
        position: 'top',
        formatter: (params: unknown) => {
          const p = params as { data: number[] };
          const x = xCategories[p.data[0]] || '';
          const y = yCategories[p.data[1]] || '';
          return `${x} / ${y}: <strong>${p.data[2]}</strong>`;
        },
      },
      grid: {
        left: '3%',
        right: '8%',
        bottom: '3%',
        top: title ? '15%' : '10%',
        containLabel: true,
      },
      xAxis: {
        type: 'category',
        data: xCategories,
        splitArea: { show: true },
        axisLabel: {
          rotate: xCategories.length > 10 ? 45 : 0,
          hideOverlap: true,
        },
      },
      yAxis: {
        type: 'category',
        data: yCategories,
        splitArea: { show: true },
      },
      visualMap: {
        min: minVal,
        max: maxVal || 1,
        calculable: true,
        orient: 'vertical',
        right: 0,
        top: 'center',
        inRange: {
          color: ['#e0f3f8', '#abd9e9', '#74add1', '#4575b4', '#313695'],
        },
      },
      series: [
        {
          name: title || 'Heatmap',
          type: 'heatmap',
          data: heatmapData,
          label: {
            show: heatmapData.length <= 100,
          },
          emphasis: {
            itemStyle: {
              shadowBlur: 10,
              shadowColor: 'rgba(0, 0, 0, 0.5)',
            },
          },
        },
      ],
    };

    return chartOptions ? { ...baseOptions, ...chartOptions } : baseOptions;
  }, [data, title, xField, yField, valueField, chartOptions]);

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
          <div className="mb-3 text-4xl text-gray-400">🗺️</div>
          <div className="font-medium text-gray-500">{title || '热力图'}</div>
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

export default SmartHeatmapChart;
