/**
 * SmartComboChart Component
 *
 * Multi-series combo chart supporting bar + line + area + scatter on dual Y-axes.
 * Each metric can be configured independently for chart type and Y-axis binding.
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

interface SeriesConfig {
  metricIndex: number;
  chartType: 'bar' | 'line' | 'area' | 'scatter';
  yAxisIndex: 0 | 1;
  color?: string;
  showLabel?: boolean;
}

interface YAxisConfig {
  name?: string;
  min?: number | 'auto';
  max?: number | 'auto';
  formatter?: string;
}

export interface SmartComboChartProps {
  title?: string;
  dataSource: ChartDataSource;
  seriesConfig?: SeriesConfig[];
  yAxisLeft?: YAxisConfig;
  yAxisRight?: YAxisConfig;
  showDataZoom?: boolean;
  smooth?: boolean;
  stack?: boolean;
  drillDown?: DrillDownConfig;
  linkage?: LinkageConfig;
  onDrillDown?: (filters: FilterConfig[]) => void;
  onLinkageEmit?: (filters: FilterConfig[]) => void;
  linkageFilters?: FilterConfig[];
  refreshInterval?: number;
  className?: string;
  style?: React.CSSProperties;
  chartOptions?: Partial<EChartsOption>;
}

const DEFAULT_COLORS = ['#5470c6', '#91cc75', '#fac858', '#ee6666', '#73c0de', '#3ba272', '#fc8452', '#9a60b4'];

function isDataSourceConfigured(ds: ChartDataSource): boolean {
  if (!ds) return false;
  if (ds.type === 'aggregate') return !!(ds.modelCode && ds.metrics?.length);
  if (ds.type === 'namedQuery') return !!ds.queryCode;
  return ds.type === 'static';
}

function buildYAxis(left?: YAxisConfig, right?: YAxisConfig, hasRight?: boolean): any[] {
  const axes: any[] = [
    {
      type: 'value',
      name: left?.name,
      min: left?.min === 'auto' ? undefined : left?.min,
      max: left?.max === 'auto' ? undefined : left?.max,
      axisLabel: left?.formatter ? { formatter: left.formatter } : undefined,
    },
  ];
  if (hasRight) {
    axes.push({
      type: 'value',
      name: right?.name,
      min: right?.min === 'auto' ? undefined : right?.min,
      max: right?.max === 'auto' ? undefined : right?.max,
      axisLabel: right?.formatter ? { formatter: right.formatter } : undefined,
    });
  }
  return axes;
}

export const SmartComboChart: React.FC<SmartComboChartProps> = ({
  title,
  dataSource,
  seriesConfig,
  yAxisLeft,
  yAxisRight,
  showDataZoom = false,
  smooth = false,
  stack = false,
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

  const handleChartClick = useCallback(
    (params: { name?: string }) => {
      if (!data?.meta?.dimensions?.length || !params.name) return;
      const filter: FilterConfig = {
        field: data.meta.dimensions[0],
        operator: 'eq',
        value: params.name,
      };
      if (drillDown?.enabled && onDrillDown) onDrillDown([filter]);
      if (linkage?.enabled && linkage?.emitFilter && onLinkageEmit) onLinkageEmit([filter]);
    },
    [data, drillDown, linkage, onDrillDown, onLinkageEmit],
  );

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

    const categories = data.rows.map((row) => String(row[dimensionKey] ?? ''));
    const hasRightAxis = seriesConfig?.some((s) => s.yAxisIndex === 1) ?? false;

    const series = metrics.map((metricKey, idx) => {
      const cfg = seriesConfig?.find((s) => s.metricIndex === idx) ?? {
        metricIndex: idx,
        chartType: idx === 0 ? 'bar' : 'line' as const,
        yAxisIndex: 0 as const,
      };

      const seriesData = data.rows.map((row) => Number(row[metricKey]) || 0);
      const color = cfg.color || DEFAULT_COLORS[idx % DEFAULT_COLORS.length];

      const baseSeries: any = {
        name: metricKey,
        type: cfg.chartType === 'area' ? 'line' : cfg.chartType,
        yAxisIndex: cfg.yAxisIndex,
        data: seriesData,
        itemStyle: { color },
        label: cfg.showLabel ? { show: true, position: 'top' } : undefined,
      };

      if (cfg.chartType === 'area') {
        baseSeries.areaStyle = { opacity: 0.3 };
      }

      if (cfg.chartType === 'line' || cfg.chartType === 'area') {
        baseSeries.smooth = smooth;
      }

      if (stack && (cfg.chartType === 'bar' || cfg.chartType === 'area')) {
        baseSeries.stack = cfg.chartType;
      }

      return baseSeries;
    });

    const baseOptions: EChartsOption = {
      title: title
        ? { text: title, left: 'center', textStyle: { fontSize: 14, fontWeight: 500 } }
        : undefined,
      tooltip: { trigger: 'axis', axisPointer: { type: 'cross' } },
      legend: { bottom: 0, type: 'scroll' },
      grid: { left: '3%', right: hasRightAxis ? '3%' : '3%', bottom: 40, top: title ? 50 : 20, containLabel: true },
      xAxis: { type: 'category', data: categories, axisLabel: { rotate: categories.length > 10 ? 30 : 0 } },
      yAxis: buildYAxis(yAxisLeft, yAxisRight, hasRightAxis),
      dataZoom: showDataZoom ? [{ type: 'slider', bottom: 0 }] : undefined,
      series,
    };

    return chartOptions ? { ...baseOptions, ...chartOptions } : baseOptions;
  }, [data, title, seriesConfig, yAxisLeft, yAxisRight, showDataZoom, smooth, stack, chartOptions]);

  const onEvents = useMemo(() => ({ click: handleChartClick }), [handleChartClick]);

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
          <div className="font-medium text-gray-500">{title || 'Combo Chart'}</div>
          <div className="mt-1 text-sm text-gray-400">Please configure data source</div>
        </div>
      </div>
    );
  }

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

export default SmartComboChart;
