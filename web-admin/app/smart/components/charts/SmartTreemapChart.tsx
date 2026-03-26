/**
 * SmartTreemapChart Component
 *
 * A treemap chart component using ECharts.
 * Uses 1 dimension for names and 1 metric for values.
 */

import React, { useMemo } from 'react';
import ReactECharts from 'echarts-for-react';
import type { EChartsOption } from 'echarts';
import { useChartData } from '~/smart/hooks/useChartData';
import type {
  ChartDataSource,
  DrillDownConfig,
  FilterConfig,
  LinkageConfig,
} from '~/smart/types/chart';
import { cn } from '~/utils/cn';

/**
 * Props for SmartTreemapChart component
 */
export interface SmartTreemapChartProps {
  /** Chart title */
  title?: string;
  /** Data source configuration */
  dataSource: ChartDataSource;
  /** Field for node names */
  nameField?: string;
  /** Field for node values */
  valueField?: string;
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

export const SmartTreemapChart: React.FC<SmartTreemapChartProps> = ({
  title,
  dataSource,
  nameField,
  valueField,
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
  const handleChartClick = React.useCallback(
    (params: { name?: string }) => {
      if (!params.name) return;

      const dimensions = data?.meta?.dimensions || [];
      const dimension = nameField || dimensions[0];
      if (!dimension) return;

      const filter: FilterConfig = {
        field: dimension,
        operator: 'eq',
        value: params.name,
      };

      if (drillDown?.enabled && onDrillDown) {
        onDrillDown([filter]);
      }

      if (linkage?.enabled && linkage?.emitFilter && onLinkageEmit) {
        onLinkageEmit([filter]);
      }
    },
    [data, nameField, drillDown, linkage, onDrillDown, onLinkageEmit],
  );

  /**
   * Build ECharts options from data
   */
  const options: EChartsOption = useMemo(() => {
    if (!data?.rows?.length) {
      return {
        title: title ? { text: title, left: 'center', textStyle: { fontSize: 14 } } : undefined,
        series: [{ type: 'treemap', data: [] }],
      };
    }

    const dimensions = data.meta?.dimensions || [];
    const metrics = data.meta?.metrics || [];

    const nKey = nameField || dimensions[0];
    const vKey = valueField || metrics[0];

    // Build treemap data
    const treemapData = data.rows.map((row) => ({
      name: String(row[nKey] ?? ''),
      value: Number(row[vKey]) || 0,
    }));

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
        formatter: '{b}: {c}',
      },
      series: [
        {
          type: 'treemap',
          data: treemapData,
          top: title ? '12%' : '5%',
          bottom: '2%',
          left: '2%',
          right: '2%',
          roam: false,
          nodeClick: 'zoomToNode',
          breadcrumb: {
            show: true,
            bottom: 0,
          },
          label: {
            show: true,
            formatter: '{b}\n{c}',
            fontSize: 12,
          },
          itemStyle: {
            borderColor: '#fff',
            borderWidth: 2,
            gapWidth: 2,
          },
          levels: [
            {
              itemStyle: {
                borderColor: '#555',
                borderWidth: 4,
                gapWidth: 4,
              },
            },
            {
              colorSaturation: [0.3, 0.6],
              itemStyle: {
                borderColorSaturation: 0.7,
                gapWidth: 2,
                borderWidth: 2,
              },
            },
          ],
        },
      ],
    };

    return chartOptions ? { ...baseOptions, ...chartOptions } : baseOptions;
  }, [data, title, nameField, valueField, chartOptions]);

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
          <div className="mb-3 text-4xl text-gray-400">🌳</div>
          <div className="font-medium text-gray-500">{title || '矩形树图'}</div>
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

export default SmartTreemapChart;
