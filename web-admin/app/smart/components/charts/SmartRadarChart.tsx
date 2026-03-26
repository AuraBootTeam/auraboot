/**
 * SmartRadarChart Component
 *
 * A radar chart component using ECharts.
 * Supports multi-dimensional comparison with interactive drill-down/linkage.
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

export interface SmartRadarChartProps {
  title?: string;
  dataSource: ChartDataSource;
  /** Shape of the radar: polygon or circle */
  shape?: 'polygon' | 'circle';
  /** Show area fill */
  showArea?: boolean;
  /** Area opacity */
  areaOpacity?: number;
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

function isDataSourceConfigured(ds: ChartDataSource): boolean {
  if (!ds) return false;
  if (ds.type === 'aggregate') return !!(ds.modelCode && ds.metrics?.length);
  if (ds.type === 'namedQuery') return !!ds.queryCode;
  return ds.type === 'static';
}

export const SmartRadarChart: React.FC<SmartRadarChartProps> = ({
  title,
  dataSource,
  shape = 'polygon',
  showArea = true,
  areaOpacity = 0.3,
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
        radar: { indicator: [] },
        series: [{ type: 'radar', data: [] }],
      };
    }

    const dimensions = data.meta?.dimensions || [];
    const metrics = data.meta?.metrics || [];
    const dimensionKey = dimensions[0];

    // Build radar indicators from metrics (each metric = one axis)
    // If multiple metrics and one dimension row, treat metrics as axes
    // If one metric and multiple dimension rows, treat dimension values as axes
    let indicators: { name: string; max?: number }[];
    let seriesData: { name: string; value: number[] }[];

    if (metrics.length > 1) {
      // Multiple metrics: each metric is a radar axis
      const maxValues = metrics.map((m) => Math.max(...data.rows.map((r) => Number(r[m]) || 0), 1));
      indicators = metrics.map((m, i) => ({ name: m, max: Math.ceil(maxValues[i] * 1.2) }));
      seriesData = data.rows.map((row) => ({
        name: dimensionKey ? String(row[dimensionKey] ?? '') : 'Data',
        value: metrics.map((m) => Number(row[m]) || 0),
      }));
    } else {
      // Single metric: each dimension value is a radar axis
      const metricKey = metrics[0];
      const maxVal = Math.max(...data.rows.map((r) => Number(r[metricKey]) || 0), 1);
      indicators = data.rows.map((row) => ({
        name: String(row[dimensionKey] ?? ''),
        max: Math.ceil(maxVal * 1.2),
      }));
      seriesData = [
        { name: metricKey, value: data.rows.map((row) => Number(row[metricKey]) || 0) },
      ];
    }

    const baseOptions: EChartsOption = {
      title: title
        ? { text: title, left: 'center', textStyle: { fontSize: 14, fontWeight: 500 } }
        : undefined,
      tooltip: { trigger: 'item' },
      legend: { bottom: 0, type: 'scroll' },
      radar: { shape, indicator: indicators },
      series: [
        {
          type: 'radar',
          data: seriesData.map((d) => ({
            ...d,
            areaStyle: showArea ? { opacity: areaOpacity } : undefined,
          })),
        },
      ],
    };

    return chartOptions ? { ...baseOptions, ...chartOptions } : baseOptions;
  }, [data, title, shape, showArea, areaOpacity, chartOptions]);

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
          <div className="mb-3 text-4xl text-gray-400">🕸</div>
          <div className="font-medium text-gray-500">{title || 'Radar Chart'}</div>
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

export default SmartRadarChart;
