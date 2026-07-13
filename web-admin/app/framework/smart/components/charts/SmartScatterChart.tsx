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
import type { ChartSpec } from '~/framework/smart/charts/chart-spec';
import type { MetricLabels } from '~/framework/smart/utils/chartLabels';
import { chartSpecToEChartsOption } from '~/framework/smart/charts/chart-spec-echarts';
import { cn } from '~/utils/cn';

export interface SmartScatterChartProps {
  /**
   * Metric alias -> series display name: `{ won_amount: '赢单金额' }`.
   *
   * Aliases are constrained to ASCII identifiers by the backend, so without this
   * the legend shows the raw column name.
   */
  metricLabels?: MetricLabels;
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

/**
 * Subset of the resolved chart data this component reads when building options.
 * (Mirrors the shape returned by `useChartData`.)
 */
export interface ScatterChartData {
  rows: Record<string, unknown>[];
  meta?: {
    dimensions?: string[];
    metrics?: string[];
    /** Dict labels for dimension values, resolved by useChartData. */
    dimensionLabels?: Record<string, Record<string, string>>;
  };
}

/** Visual props that influence option building (subset of SmartScatterChartProps). */
export interface ScatterOptionProps {
  /**
   * Metric alias -> series display name: `{ won_amount: '赢单金额' }`.
   *
   * Aliases are constrained to ASCII identifiers by the backend, so without
   * this the legend shows the raw column name.
   */
  metricLabels?: MetricLabels;
  title?: string;
  xAxisLabel?: string;
  yAxisLabel?: string;
  bubbleMode?: boolean;
  symbolSizeRange?: [number, number];
  chartOptions?: Partial<EChartsOption>;
}

/**
 * Build a renderer-agnostic `ChartSpec` from SmartScatterChart's runtime data + visual
 * props. Unlike bar/line (one series PER measure), a scatter plot maps measures onto
 * AXIS ROLES within a SINGLE series: `measures[0]` → X, `measures[1]` → Y (falling back
 * to `measures[0]` when absent), `measures[2]` → bubble size; the category dimension
 * (← `data.meta.dimensions[0]`) is the per-point label. So there is NO multi-measure
 * "drop" bug to fix here — the measures are never independent series.
 *
 * The axis labels (`xAxisLabel`/`yAxisLabel`) and bubble sizing (`bubbleMode`/
 * `symbolSizeRange`) are scatter-specific renderer affordances that have no neutral
 * `ChartVisualOptions` field; they are carried on the spec's `scatter` extension below
 * and consumed only by the echarts adapter's scatter branch. `dataSource` is required by
 * the ChartSpec type but unused by the scatter option-builder, so a minimal aggregate
 * placeholder is supplied.
 */
function specFromScatterChartData(
  data: ScatterChartData | null | undefined,
  props: ScatterOptionProps,
): ChartSpec {
  const {
    title,
    xAxisLabel,
    yAxisLabel,
    bubbleMode = false,
    symbolSizeRange = [10, 60],
  } = props;
  const dimensions = data?.meta?.dimensions ?? [];
  const metrics = data?.meta?.metrics ?? [];
  return {
    type: 'scatter',
    title,
    dataSource: { type: 'aggregate', modelCode: '', dimensions, metrics: [] },
    dimensions: dimensions.map((field, i) => ({
      field,
      role: i === 0 ? 'category' : 'series',
      valueLabels: data?.meta?.dimensionLabels?.[field],
    })),
    measures: metrics.map((field) => ({ field, label: props.metricLabels?.[field] })),
    scatter: { xAxisLabel, yAxisLabel, bubbleMode, symbolSizeRange },
  };
}

/**
 * Build the ECharts `option` exactly the way SmartScatterChart historically built it
 * inline. Extracted verbatim (no behavior change) as a pure, exported helper.
 *
 * As of B2d this is NO LONGER on SmartScatterChart's render path — the component now
 * builds the option via the shared `chartSpecToEChartsOption` adapter (which the
 * `chart-spec-echarts-smartscatterchart-equivalence.test.ts` gate proves equivalent to
 * this builder's BASE option). This helper is retained as the equivalence test's ORACLE;
 * its output MUST stay identical to the pre-B2d inline builder.
 *
 * NOTE on the closure members: `tooltip.formatter` and (in bubble mode) the single
 * series' `symbolSize` are FUNCTIONS — they capture the axis labels / data rows. Two
 * structurally identical function instances are NOT reference-equal under vitest
 * `toEqual`, so the equivalence gate compares the non-function structure deep-equal AND
 * proves the function members behave identically (invoking them with representative
 * inputs). No scatter behavior is changed; the functions are byte-identical in source.
 */
export function buildScatterOptionLegacy(
  data: ScatterChartData | null | undefined,
  props: ScatterOptionProps,
): EChartsOption {
  const {
    title,
    xAxisLabel,
    yAxisLabel,
    bubbleMode = false,
    symbolSizeRange = [10, 60],
    chartOptions,
  } = props;

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

  // Merge with custom options
  return chartOptions ? { ...baseOptions, ...chartOptions } : baseOptions;
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
  metricLabels,
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

  /**
   * Build ECharts options from data via the shared ChartSpec→ECharts adapter (B2d).
   *
   * Provably-neutral refactor: `chartSpecToEChartsOption` produces an option equivalent
   * to the legacy `buildScatterOptionLegacy` BASE option (pinned by
   * chart-spec-echarts-smartscatterchart-equivalence.test.ts — non-function structure
   * deep-equal + function members behavior-equal). The `chartOptions` renderer-leak is
   * applied HERE at the call site exactly as before — it is NOT baked into the
   * renderer-agnostic adapter.
   */
  const options: EChartsOption = useMemo(() => {
    const spec = specFromScatterChartData(data, {
      metricLabels,
      title,
      xAxisLabel,
      yAxisLabel,
      bubbleMode,
      symbolSizeRange,
    });
    const adapterOption = chartSpecToEChartsOption(spec, data?.rows ?? []) as EChartsOption;
    return chartOptions ? { ...adapterOption, ...chartOptions } : adapterOption;
  }, [data, title, xAxisLabel, yAxisLabel, bubbleMode, symbolSizeRange, metricLabels, chartOptions]);

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
