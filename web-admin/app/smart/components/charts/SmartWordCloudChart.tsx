/**
 * SmartWordCloudChart Component
 *
 * Word cloud visualization using echarts-wordcloud extension.
 * Displays dimension values sized by their aggregated metric value.
 */

import React, { useMemo, useCallback } from 'react';
import ReactECharts from 'echarts-for-react';
import type { EChartsOption } from 'echarts';
import 'echarts-wordcloud';
import { useChartData } from '~/smart/hooks/useChartData';
import type {
  ChartDataSource,
  DrillDownConfig,
  LinkageConfig,
  FilterConfig,
} from '~/smart/types/chart';
import { cn } from '~/utils/cn';

export interface SmartWordCloudChartProps {
  title?: string;
  dataSource: ChartDataSource;
  shape?: 'circle' | 'rect' | 'diamond' | 'triangle';
  fontSizeRange?: [number, number];
  colorTheme?: 'random' | 'warm' | 'cool' | 'brand';
  rotationRange?: [number, number];
  rotationStep?: number;
  gridSize?: number;
  drillDown?: DrillDownConfig;
  linkage?: LinkageConfig;
  onDrillDown?: (filters: FilterConfig[]) => void;
  onLinkageEmit?: (filters: FilterConfig[]) => void;
  linkageFilters?: FilterConfig[];
  refreshInterval?: number;
  className?: string;
  style?: React.CSSProperties;
}

const COLOR_THEMES: Record<string, string[]> = {
  random: ['#5470c6', '#91cc75', '#fac858', '#ee6666', '#73c0de', '#3ba272', '#fc8452', '#9a60b4'],
  warm: ['#ff4d4f', '#ff7a45', '#ffa940', '#ffc53d', '#ff9c6e', '#ff85c0', '#f759ab'],
  cool: ['#1890ff', '#13c2c2', '#52c41a', '#2f54eb', '#722ed1', '#36cfc9', '#597ef7'],
  brand: ['#4e79a7', '#f28e2b', '#e15759', '#76b7b2', '#59a14f', '#edc948', '#b07aa1'],
};

function isDataSourceConfigured(ds: ChartDataSource): boolean {
  if (!ds) return false;
  if (ds.type === 'aggregate') return !!(ds.modelCode && ds.metrics?.length);
  if (ds.type === 'namedQuery') return !!ds.queryCode;
  return ds.type === 'static';
}

export const SmartWordCloudChart: React.FC<SmartWordCloudChartProps> = ({
  title,
  dataSource,
  shape = 'circle',
  fontSizeRange = [14, 60],
  colorTheme = 'random',
  rotationRange = [-45, 45],
  rotationStep = 45,
  gridSize = 8,
  drillDown,
  linkage,
  onDrillDown,
  onLinkageEmit,
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

  const colors = COLOR_THEMES[colorTheme] || COLOR_THEMES.random;

  const options: EChartsOption = useMemo(() => {
    if (!data?.rows?.length) {
      return {
        title: title ? { text: title, left: 'center', textStyle: { fontSize: 14 } } : undefined,
        series: [{ type: 'wordCloud' as any, data: [] }],
      };
    }

    const dimensions = data.meta?.dimensions || [];
    const metrics = data.meta?.metrics || [];
    const dimensionKey = dimensions[0];
    const metricKey = metrics[0];

    const wordData = data.rows.map((row) => ({
      name: String(row[dimensionKey] ?? ''),
      value: Number(row[metricKey]) || 0,
      textStyle: {
        color: colors[Math.floor(Math.random() * colors.length)],
      },
    }));

    return {
      title: title
        ? { text: title, left: 'center', textStyle: { fontSize: 14, fontWeight: 500 } }
        : undefined,
      tooltip: { show: true, formatter: (params: any) => `${params.name}: ${params.value}` },
      series: [
        {
          type: 'wordCloud' as any,
          shape,
          sizeRange: fontSizeRange,
          rotationRange,
          rotationStep,
          gridSize,
          drawOutOfBound: false,
          layoutAnimation: true,
          textStyle: {
            fontFamily: 'sans-serif',
            fontWeight: 'bold',
          },
          emphasis: {
            textStyle: {
              shadowBlur: 10,
              shadowColor: 'rgba(0, 0, 0, 0.25)',
            },
          },
          left: 'center',
          top: title ? 40 : 'center',
          width: '90%',
          height: title ? '80%' : '90%',
          data: wordData,
        },
      ],
    } as EChartsOption;
  }, [data, title, shape, fontSizeRange, colorTheme, rotationRange, rotationStep, gridSize, colors]);

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
          <div className="mb-3 text-4xl text-gray-400">☁️</div>
          <div className="font-medium text-gray-500">{title || 'Word Cloud'}</div>
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

export default SmartWordCloudChart;
