/**
 * SmartFunnelChart Component
 *
 * A funnel chart component using ECharts.
 * Supports interactive drill-down and linkage.
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

export interface SmartFunnelChartProps {
  title?: string;
  dataSource: ChartDataSource;
  /** Sort order: ascending or descending */
  sort?: 'ascending' | 'descending' | 'none';
  /** Funnel alignment */
  funnelAlign?: 'left' | 'center' | 'right';
  /** Show labels */
  showLabel?: boolean;
  /** Label position */
  labelPosition?: 'inside' | 'left' | 'right';
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

export const SmartFunnelChart: React.FC<SmartFunnelChartProps> = ({
  title,
  dataSource,
  sort = 'descending',
  funnelAlign = 'center',
  showLabel = true,
  labelPosition = 'inside',
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
        series: [{ type: 'funnel', data: [] }],
      };
    }

    const dimensions = data.meta?.dimensions || [];
    const metrics = data.meta?.metrics || [];
    const dimensionKey = dimensions[0];
    const metricKey = metrics[0];

    const funnelData = data.rows.map((row) => ({
      name: String(row[dimensionKey] ?? ''),
      value: Number(row[metricKey]) || 0,
    }));

    const baseOptions: EChartsOption = {
      title: title
        ? { text: title, left: 'center', textStyle: { fontSize: 14, fontWeight: 500 } }
        : undefined,
      tooltip: { trigger: 'item', formatter: '{b}: {c}' },
      legend: { orient: 'horizontal', bottom: 0, type: 'scroll' },
      series: [
        {
          name: title || 'Funnel',
          type: 'funnel',
          left: '10%',
          top: 40,
          bottom: 40,
          width: '80%',
          sort,
          funnelAlign,
          data: funnelData,
          label: { show: showLabel, position: labelPosition, formatter: '{b}: {c}' },
          emphasis: { label: { fontSize: 14 } },
        },
      ],
    };

    return chartOptions ? { ...baseOptions, ...chartOptions } : baseOptions;
  }, [data, title, sort, funnelAlign, showLabel, labelPosition, chartOptions]);

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
          <div className="mb-3 text-4xl text-gray-400">📊</div>
          <div className="font-medium text-gray-500">{title || 'Funnel Chart'}</div>
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

export default SmartFunnelChart;
