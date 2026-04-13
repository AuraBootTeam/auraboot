/**
 * SmartScatterChart Component
 *
 * A scatter/bubble chart component using ECharts.
 * Supports X/Y dimensions, bubble size, and interactive drill-down/linkage.
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

export interface SmartScatterChartProps {
  title?: string;
  dataSource: ChartDataSource;
  /** X-axis label */
  xAxisLabel?: string;
  /** Y-axis label */
  yAxisLabel?: string;
  /** Enable bubble mode (third metric controls size) */
  bubbleMode?: boolean;
  /** Symbol size range for bubble mode */
  symbolSizeRange?: [number, number];
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

export const SmartScatterChart: React.FC<SmartScatterChartProps> = ({
  title,
  dataSource,
  xAxisLabel,
  yAxisLabel,
  bubbleMode = false,
  symbolSizeRange = [10, 60],
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
    (params: { data?: unknown[] }) => {
      if (!data?.meta?.dimensions?.length) return;
      const dimension = data.meta.dimensions[0];
      const clickedRow = data.rows.find((row) => {
        const metrics = data.meta?.metrics || [];
        return (
          metrics.length >= 2 &&
          Number(row[metrics[0]]) === (params.data as number[])?.[0] &&
          Number(row[metrics[1]]) === (params.data as number[])?.[1]
        );
      });
      if (!clickedRow) return;
      const filter: FilterConfig = {
        field: dimension,
        operator: 'eq',
        value: clickedRow[dimension],
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
        xAxis: { type: 'value' },
        yAxis: { type: 'value' },
        series: [{ type: 'scatter', data: [] }],
      };
    }

    const metrics = data.meta?.metrics || [];
    const dimensions = data.meta?.dimensions || [];
    const xKey = metrics[0];
    const yKey = metrics[1] || metrics[0];
    const sizeKey = metrics[2];
    const labelKey = dimensions[0];

    // Calculate size range for bubble mode
    let maxSize = 1;
    if (bubbleMode && sizeKey) {
      maxSize = Math.max(...data.rows.map((r) => Number(r[sizeKey]) || 0), 1);
    }

    const scatterData = data.rows.map((row) => {
      const point: (number | string)[] = [Number(row[xKey]) || 0, Number(row[yKey]) || 0];
      if (labelKey) point.push(String(row[labelKey] ?? ''));
      return point;
    });

    const baseOptions: EChartsOption = {
      title: title
        ? { text: title, left: 'center', textStyle: { fontSize: 14, fontWeight: 500 } }
        : undefined,
      tooltip: {
        trigger: 'item',
        formatter: (params: unknown) => {
          const p = params as { data?: (number | string)[] };
          if (!p.data) return '';
          const label = p.data[2] ? `${p.data[2]}<br/>` : '';
          return `${label}${xAxisLabel || xKey}: ${p.data[0]}<br/>${yAxisLabel || yKey}: ${p.data[1]}`;
        },
      },
      xAxis: {
        type: 'value',
        name: xAxisLabel || xKey,
        splitLine: { show: true, lineStyle: { type: 'dashed' } },
      },
      yAxis: {
        type: 'value',
        name: yAxisLabel || yKey,
        splitLine: { show: true, lineStyle: { type: 'dashed' } },
      },
      series: [
        {
          type: 'scatter',
          data: scatterData,
          symbolSize:
            bubbleMode && sizeKey
              ? (val: number[]) => {
                  const size =
                    Number(
                      data.rows.find(
                        (r) => Number(r[xKey]) === val[0] && Number(r[yKey]) === val[1],
                      )?.[sizeKey],
                    ) || 0;
                  return (
                    symbolSizeRange[0] +
                    (size / maxSize) * (symbolSizeRange[1] - symbolSizeRange[0])
                  );
                }
              : 14,
          emphasis: { itemStyle: { shadowBlur: 10, shadowColor: 'rgba(0,0,0,0.3)' } },
        },
      ],
    };

    return chartOptions ? { ...baseOptions, ...chartOptions } : baseOptions;
  }, [data, title, xAxisLabel, yAxisLabel, bubbleMode, symbolSizeRange, chartOptions]);

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
          <div className="mb-3 text-4xl text-gray-400">⚬</div>
          <div className="font-medium text-gray-500">{title || 'Scatter Chart'}</div>
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

export default SmartScatterChart;
