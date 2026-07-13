/**
 * SmartLineChart Component
 *
 * A line chart component using ECharts.
 * Supports smooth curves, area fill, and multiple lines.
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
import type { ChartSpec } from '~/framework/smart/charts/chart-spec';
import type { MetricLabels } from '~/framework/smart/utils/chartLabels';
import { chartSpecToEChartsOption } from '~/framework/smart/charts/chart-spec-echarts';
import { cn } from '~/utils/cn';
import { ChartEmptyState } from './ChartEmptyState';

/**
 * Props for SmartLineChart component
 */
export interface SmartLineChartProps {
  /**
   * Metric alias -> series display name: `{ won_amount: '赢单金额' }`.
   *
   * Aliases are constrained to ASCII identifiers by the backend, so without this
   * the legend shows the raw column name.
   */
  metricLabels?: MetricLabels;
  /** Chart title */
  title?: string;
  /** Data source configuration */
  dataSource: ChartDataSource;
  /** Enable smooth curves */
  smooth?: boolean;
  /** Enable area fill under the line */
  areaStyle?: boolean;
  /** Show data points on the line */
  showSymbol?: boolean;
  /** Show data labels */
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
 * SmartLineChart - A line chart component with ECharts
 *
 * @example
 * // Basic line chart
 * <SmartLineChart
 *   title="Daily Orders"
 *   dataSource={{
 *     type: 'aggregate',
 *     modelCode: 'order',
 *     dimensions: ['created_date'],
 *     metrics: [{ field: 'id', aggregation: 'count', alias: 'count' }],
 *   }}
 * />
 *
 * @example
 * // Smooth area chart with multiple lines
 * <SmartLineChart
 *   title="Revenue Trend"
 *   dataSource={{
 *     type: 'aggregate',
 *     modelCode: 'order',
 *     dimensions: ['month'],
 *     metrics: [
 *       { field: 'revenue', aggregation: 'sum', alias: 'revenue' },
 *       { field: 'cost', aggregation: 'sum', alias: 'cost' },
 *     ],
 *   }}
 *   smooth
 *   areaStyle
 * />
 */
/**
 * Subset of the resolved chart data this component reads when building options.
 * (Mirrors the shape returned by `useChartData`.)
 */
export interface LineChartData {
  rows: Record<string, unknown>[];
  meta?: {
    dimensions?: string[];
    metrics?: string[];
    /** Dict labels for dimension values, resolved by useChartData. */
    dimensionLabels?: Record<string, Record<string, string>>;
  };
}

/** Visual props that influence option building (subset of SmartLineChartProps). */
export interface LineOptionProps {
  /**
   * Metric alias -> series display name: `{ won_amount: '赢单金额' }`.
   *
   * Aliases are constrained to ASCII identifiers by the backend, so without
   * this the legend shows the raw column name.
   */
  metricLabels?: MetricLabels;
  title?: string;
  smooth?: boolean;
  areaStyle?: boolean;
  showSymbol?: boolean;
  showLabel?: boolean;
  chartOptions?: Partial<EChartsOption>;
}

/**
 * Build a renderer-agnostic `ChartSpec` from SmartLineChart's runtime data + visual
 * props. The adapter's line/area branch reads only `title`, `visual.{smooth,areaFill,
 * showSymbol,dataLabels}`, `measures[].field` (← `data.meta.metrics`), and the category
 * dimension (← `data.meta.dimensions[0]`); `dataSource` is required by the ChartSpec
 * type but unused by the line option-builder, so a minimal aggregate placeholder is
 * supplied.
 *
 * Area-fill is carried by the neutral `visual.areaFill` flag (← the `areaStyle` prop),
 * NOT by `spec.type`, because SmartLineChart renders both plain and area-filled lines
 * from one component; the spec type stays `'line'`.
 */
function specFromLineChartData(
  data: LineChartData | null | undefined,
  props: LineOptionProps,
): ChartSpec {
  const {
    title,
    smooth = false,
    areaStyle = false,
    showSymbol = true,
    showLabel = false,
  } = props;
  const dimensions = data?.meta?.dimensions ?? [];
  const metrics = data?.meta?.metrics ?? [];
  return {
    type: 'line',
    title,
    dataSource: { type: 'aggregate', modelCode: '', dimensions, metrics: [] },
    dimensions: dimensions.map((field, i) => ({
      field,
      role: i === 0 ? 'category' : 'series',
      valueLabels: data?.meta?.dimensionLabels?.[field],
    })),
    measures: metrics.map((field) => ({ field, label: props.metricLabels?.[field] })),
    visual: { smooth, areaFill: areaStyle, showSymbol, dataLabels: showLabel },
  };
}

/**
 * Build the ECharts `option` exactly the way SmartLineChart historically built it
 * inline. Extracted verbatim (no behavior change) as a pure, exported helper.
 *
 * As of B2d this is NO LONGER on SmartLineChart's render path — the component now
 * builds the option via the shared `chartSpecToEChartsOption` adapter (which the
 * `chart-spec-echarts-smartlinechart-equivalence.test.ts` gate proves byte-equivalent
 * to this builder's BASE option). This helper is retained as the equivalence test's
 * ORACLE; its output MUST stay byte-for-byte identical to the pre-B2d inline builder.
 */
export function buildLineOptionLegacy(
  data: LineChartData | null | undefined,
  props: LineOptionProps,
): EChartsOption {
  const {
    title,
    smooth = false,
    areaStyle = false,
    showSymbol = true,
    showLabel = false,
    chartOptions,
  } = props;

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

  // Extract category labels from the dimension field
  const categories = data.rows.map((row) => String(row[dimensionKey] ?? ''));

  // Build series for each metric
  const series = metrics.map((metricKey, index) => ({
    name: metricKey,
    type: 'line' as const,
    data: data.rows.map((row) => Number(row[metricKey]) || 0),
    smooth,
    showSymbol,
    symbol: 'circle' as const,
    symbolSize: 6,
    areaStyle: areaStyle
      ? {
          opacity: 0.3 - index * 0.1, // Gradient opacity for multiple areas
        }
      : undefined,
    label: {
      show: showLabel,
      position: 'top' as const,
    },
    emphasis: {
      focus: 'series' as const,
    },
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
      trigger: 'axis',
      axisPointer: { type: 'cross' },
    },
    legend:
      metrics.length > 1
        ? {
            bottom: 0,
            type: 'scroll',
          }
        : undefined,
    grid: {
      left: '3%',
      right: '4%',
      bottom: metrics.length > 1 ? '15%' : '3%',
      top: title ? '15%' : '10%',
      containLabel: true,
    },
    xAxis: {
      type: 'category',
      data: categories,
      boundaryGap: false,
      axisLabel: {
        rotate: categories.length > 10 ? 45 : 0,
        hideOverlap: true,
      },
    },
    yAxis: {
      type: 'value',
    },
    series,
  };

  // Merge with custom options
  return chartOptions ? { ...baseOptions, ...chartOptions } : baseOptions;
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

export const SmartLineChart: React.FC<SmartLineChartProps> = ({
  title,
  dataSource,
  metricLabels,
  smooth = false,
  areaStyle = false,
  showSymbol = true,
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
    (params: { name?: string; dataIndex?: number }) => {
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
   * Build ECharts options from data via the shared ChartSpec→ECharts adapter (B2d).
   *
   * Provably-neutral refactor: `chartSpecToEChartsOption` produces an option
   * byte-equivalent to the legacy `buildLineOptionLegacy` BASE option (pinned by
   * chart-spec-echarts-smartlinechart-equivalence.test.ts). The `chartOptions`
   * renderer-leak is applied HERE at the call site exactly as before — it is NOT
   * baked into the renderer-agnostic adapter.
   */
  const options: EChartsOption = useMemo(() => {
    const spec = specFromLineChartData(data, {
      title,
      smooth,
      areaStyle,
      showSymbol,
      showLabel,
      metricLabels,
    });
    const adapterOption = chartSpecToEChartsOption(spec, data?.rows ?? []) as EChartsOption;
    return chartOptions ? { ...adapterOption, ...chartOptions } : adapterOption;
  }, [data, title, smooth, areaStyle, showSymbol, showLabel, metricLabels, chartOptions]);

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
          <div className="font-medium text-gray-500">{title || '折线图'}</div>
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

  if (isEmpty) {
    return (
      <ChartEmptyState
        title={title || 'No trend data yet'}
        description="Trends appear automatically after incoming records create a timeline."
        variant="line"
        className={className}
      />
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

export default SmartLineChart;
