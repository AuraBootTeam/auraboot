/**
 * SmartAreaChart Component
 *
 * An area chart component using ECharts.
 * Based on SmartLineChart with area fill enabled by default.
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
import { chartSpecToEChartsOption } from '~/framework/smart/charts/chart-spec-echarts';
import { cn } from '~/utils/cn';

/**
 * Props for SmartAreaChart component
 */
export interface SmartAreaChartProps {
  /** Chart title */
  title?: string;
  /** Data source configuration */
  dataSource: ChartDataSource;
  /** Enable smooth curves (default: true) */
  smooth?: boolean;
  /** Area fill opacity (0-1, default: 0.6) */
  fillOpacity?: number;
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
 * Subset of the resolved chart data this component reads when building options.
 * (Mirrors the shape returned by `useChartData`.)
 */
export interface AreaChartData {
  rows: Record<string, unknown>[];
  meta?: {
    dimensions?: string[];
    metrics?: string[];
  };
}

/** Visual props that influence option building (subset of SmartAreaChartProps). */
export interface AreaOptionProps {
  title?: string;
  smooth?: boolean;
  fillOpacity?: number;
  showSymbol?: boolean;
  showLabel?: boolean;
  chartOptions?: Partial<EChartsOption>;
}

/**
 * Build a renderer-agnostic `ChartSpec` from SmartAreaChart's runtime data + visual
 * props. The adapter's line/area branch reads only `title`, `visual.{smooth,areaFill,
 * fillOpacity,showSymbol,dataLabels}`, `measures[].field` (← `data.meta.metrics`), and
 * the category dimension (← `data.meta.dimensions[0]`); `dataSource` is required by the
 * ChartSpec type but unused by the option-builder, so a minimal aggregate placeholder is
 * supplied.
 *
 * SmartAreaChart is always area-filled, so `visual.areaFill` is always true and the spec
 * type is `'area'`. Its configurable base opacity maps onto `visual.fillOpacity`, which
 * switches the adapter's per-series opacity onto SmartAreaChart's formula
 * (`max(0.1, fillOpacity - index*0.15)`) — distinct from SmartLineChart's area-fill
 * gradient, by design.
 */
function specFromAreaChartData(
  data: AreaChartData | null | undefined,
  props: AreaOptionProps,
): ChartSpec {
  const {
    title,
    smooth = true,
    fillOpacity = 0.6,
    showSymbol = true,
    showLabel = false,
  } = props;
  const dimensions = data?.meta?.dimensions ?? [];
  const metrics = data?.meta?.metrics ?? [];
  return {
    type: 'area',
    title,
    dataSource: { type: 'aggregate', modelCode: '', dimensions, metrics: [] },
    dimensions: dimensions.map((field, i) => ({
      field,
      role: i === 0 ? 'category' : 'series',
    })),
    measures: metrics.map((field) => ({ field })),
    visual: { smooth, areaFill: true, fillOpacity, showSymbol, dataLabels: showLabel },
  };
}

/**
 * Build the ECharts `option` exactly the way SmartAreaChart historically built it
 * inline. Extracted verbatim (no behavior change) as a pure, exported helper.
 *
 * As of B2d-area this is NO LONGER on SmartAreaChart's render path — the component now
 * builds the option via the shared `chartSpecToEChartsOption` adapter (which the
 * `chart-spec-echarts-smartareachart-equivalence.test.ts` gate proves byte-equivalent to
 * this builder's BASE option). This helper is retained as the equivalence test's ORACLE;
 * its output MUST stay byte-for-byte identical to the pre-B2d inline builder.
 */
export function buildAreaOptionLegacy(
  data: AreaChartData | null | undefined,
  props: AreaOptionProps,
): EChartsOption {
  const {
    title,
    smooth = true,
    fillOpacity = 0.6,
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

  const categories = data.rows.map((row) => String(row[dimensionKey] ?? ''));

  const series = metrics.map((metricKey, index) => ({
    name: metricKey,
    type: 'line' as const,
    data: data.rows.map((row) => Number(row[metricKey]) || 0),
    smooth,
    showSymbol,
    symbol: 'circle' as const,
    symbolSize: 6,
    areaStyle: {
      opacity: Math.max(0.1, fillOpacity - index * 0.15),
    },
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

export const SmartAreaChart: React.FC<SmartAreaChartProps> = ({
  title,
  dataSource,
  smooth = true,
  fillOpacity = 0.6,
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

      if (drillDown?.enabled && onDrillDown) {
        onDrillDown([filter]);
      }

      if (linkage?.enabled && linkage?.emitFilter && onLinkageEmit) {
        onLinkageEmit([filter]);
      }
    },
    [data, drillDown, linkage, onDrillDown, onLinkageEmit],
  );

  /**
   * Build ECharts options from data via the shared ChartSpec→ECharts adapter (B2d-area).
   *
   * Provably-neutral refactor: `chartSpecToEChartsOption` produces an option
   * byte-equivalent to the legacy `buildAreaOptionLegacy` BASE option (pinned by
   * chart-spec-echarts-smartareachart-equivalence.test.ts). The adapter's line/area
   * branch reads `visual.fillOpacity` to reproduce SmartAreaChart's per-series opacity
   * formula (distinct from SmartLineChart's area-fill gradient). The `chartOptions`
   * renderer-leak is applied HERE at the call site exactly as before — it is NOT baked
   * into the renderer-agnostic adapter.
   */
  const options: EChartsOption = useMemo(() => {
    const spec = specFromAreaChartData(data, {
      title,
      smooth,
      fillOpacity,
      showSymbol,
      showLabel,
    });
    const adapterOption = chartSpecToEChartsOption(spec, data?.rows ?? []) as EChartsOption;
    return chartOptions ? { ...adapterOption, ...chartOptions } : adapterOption;
  }, [data, title, smooth, fillOpacity, showSymbol, showLabel, chartOptions]);

  /**
   * ECharts event handlers
   */
  const onEvents = useMemo(
    () => ({
      click: handleChartClick,
    }),
    [handleChartClick],
  );

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
          <div className="mb-3 text-4xl text-gray-400">
            <svg
              className="mx-auto h-10 w-10"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={1.5}
                d="M3 17l4-4 4 4 4-8 4 4V3H3v14z"
              />
            </svg>
          </div>
          <div className="font-medium text-gray-500">{title || 'Area Chart'}</div>
          <div className="mt-1 text-sm text-gray-400">Please configure the data source</div>
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

export default SmartAreaChart;
