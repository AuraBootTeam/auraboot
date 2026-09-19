/**
 * SmartWaterfallChart Component
 *
 * A waterfall (bridge) chart component using ECharts — AMOS cockpit gap G05.
 * Rows stack deltas into a running total; rows classified as totals by
 * `totalField`/`totalValues` render as absolute bars that restart the running
 * sum (quote baseline / subtotal / total rows of a profit bridge).
 */

import React, { useMemo, useCallback } from 'react';
import ReactECharts from './EChartsHost';
import type { EChartsOption } from 'echarts';
import { useChartData } from '~/framework/smart/hooks/useChartData';
import type {
  ChartDataSource,
  DrillDownConfig,
  LinkageConfig,
  FilterConfig,
} from '~/framework/smart/types/chart';
import type { ChartSpec } from '~/framework/smart/charts/chart-spec';
import type { MetricLabels } from '~/framework/smart/utils/chartLabels';
import { chartSpecToEChartsOption } from '~/framework/smart/charts/chart-spec-echarts';
import { cn } from '~/utils/cn';
import { ChartEmptyState } from './ChartEmptyState';
import { enrichDrillDownFilters } from '~/framework/smart/utils/drillDownFilters';

/**
 * Props for SmartWaterfallChart component
 */
export interface SmartWaterfallChartProps {
  /**
   * Metric alias -> series display name: `{ variance_amount: '差异金额' }`.
   */
  metricLabels?: MetricLabels;
  /** Chart title */
  title?: string;
  /** Data source configuration */
  dataSource: ChartDataSource;
  /**
   * Row column classifying absolute-total rows (e.g. `bridge_kind`).
   * Unset = every row is a delta stacked into the running total.
   */
  totalField?: string;
  /**
   * Values of `totalField` marking absolute bars. A comma-separated string is
   * normalized (designer text inputs author it as `total,合计`).
   * Default: `['total', 'subtotal', '合计', '小计']`.
   */
  totalValues?: string[] | string;
  /** Show data labels on bars */
  showLabel?: boolean;
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
 * Subset of the resolved chart data this component reads when building options.
 * (Mirrors the shape returned by `useChartData`.)
 */
export interface WaterfallChartData {
  rows: Record<string, unknown>[];
  meta?: {
    dimensions?: string[];
    metrics?: string[];
    /** Dict labels for dimension values, resolved by useChartData. */
    dimensionLabels?: Record<string, Record<string, string>>;
  };
}

/** Visual props that influence option building (subset of SmartWaterfallChartProps). */
export interface WaterfallOptionProps {
  metricLabels?: MetricLabels;
  title?: string;
  totalField?: string;
  totalValues?: string[] | string;
  showLabel?: boolean;
}

function normalizeTotalValues(value: string[] | string | undefined): string[] | undefined {
  if (value == null) return undefined;
  const list = Array.isArray(value)
    ? value
    : String(value)
        .split(',')
        .map((item) => item.trim())
        .filter(Boolean);
  return list.length ? list : undefined;
}

/**
 * Build a renderer-agnostic `ChartSpec` from the component's runtime data +
 * visual props. The adapter's waterfall branch reads `title`,
 * `visual.dataLabels`, `waterfall.{totalField,totalValues}`, `measures[0]` and
 * the category dimension.
 */
function specFromWaterfallChartData(
  data: WaterfallChartData | null | undefined,
  props: WaterfallOptionProps,
): ChartSpec {
  const { title, totalField, totalValues, showLabel = false } = props;
  const dimensions = data?.meta?.dimensions ?? [];
  const metrics = data?.meta?.metrics ?? [];
  return {
    type: 'waterfall',
    title,
    dataSource: { type: 'aggregate', modelCode: '', dimensions, metrics: [] },
    dimensions: dimensions.map((field, i) => ({
      field,
      role: i === 0 ? 'category' : 'series',
      valueLabels: data?.meta?.dimensionLabels?.[field],
    })),
    measures: metrics.map((field) => ({ field, label: props.metricLabels?.[field] })),
    visual: { dataLabels: showLabel },
    waterfall:
      totalField != null
        ? {
            totalField,
            totalValues:
              normalizeTotalValues(totalValues) ?? ['total', 'subtotal', '合计', '小计'],
          }
        : undefined,
  };
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

export const SmartWaterfallChart: React.FC<SmartWaterfallChartProps> = ({
  title,
  dataSource,
  metricLabels,
  totalField,
  totalValues,
  showLabel = false,
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
  const isEmpty = !loading && !error && !data?.rows?.length;

  /**
   * Handle chart click events for drill-down and linkage
   */
  const handleChartClick = useCallback(
    (params: { name?: string; seriesName?: string; data?: unknown; dataIndex?: number }) => {
      if (!data?.meta?.dimensions?.length) return;

      const dimension = data.meta.dimensions[0];
      const clickedRow = params.dataIndex == null ? undefined : data.rows[params.dataIndex];
      const clickedValue = clickedRow?.[dimension] ?? params.name;

      if (!clickedValue) return;

      const filter: FilterConfig = {
        field: dimension,
        operator: 'eq',
        value: clickedValue,
      };

      // Handle drill-down
      if (drillDown?.enabled && onDrillDown) {
        const row =
          clickedRow ?? data.rows.find((candidate) => candidate[dimension] === clickedValue);
        onDrillDown(enrichDrillDownFilters(filter, row, drillDown));
      }

      // Handle linkage
      if (linkage?.enabled && linkage?.emitFilter && onLinkageEmit) {
        onLinkageEmit([filter]);
      }
    },
    [data, drillDown, linkage, onDrillDown, onLinkageEmit],
  );

  /**
   * Build ECharts options from data via the shared ChartSpec→ECharts adapter.
   * The `chartOptions` renderer-leak is applied HERE at the call site — it is
   * NOT baked into the renderer-agnostic adapter.
   */
  const options: EChartsOption = useMemo(() => {
    const spec = specFromWaterfallChartData(data, {
      title,
      totalField,
      totalValues,
      showLabel,
      metricLabels,
    });
    const adapterOption = chartSpecToEChartsOption(spec, data?.rows ?? []) as EChartsOption;
    return chartOptions ? { ...adapterOption, ...chartOptions } : adapterOption;
  }, [data, title, totalField, totalValues, showLabel, metricLabels, chartOptions]);

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
          <div className="font-medium text-gray-500">{title || '瀑布图'}</div>
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

  // Empty state
  if (isEmpty) {
    return (
      <ChartEmptyState
        title={title || 'No waterfall data yet'}
        description="This chart will render once the first matching records are created."
        variant="bar"
        className={className}
      />
    );
  }

  return (
    <div
      className={cn('rounded-lg border border-gray-200 bg-white p-4', className)}
      style={{ minHeight: 0, ...style }}
      data-chart-type="waterfall"
    >
      <ReactECharts
        option={options}
        notMerge
        style={{ height: '100%', width: '100%' }}
        onEvents={onEvents}
      />
    </div>
  );
};

export default SmartWaterfallChart;
