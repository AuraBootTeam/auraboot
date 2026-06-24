/**
 * ChartBlockRenderer — bridges DSL chart blocks to Smart chart components
 *
 * Now uses SharedChartFactory (unified registry) instead of local CHART_MAP.
 * Supports all 23 chart types. Passes through linkage, drillDown, refreshInterval.
 *
 * DSL config:
 * { "blockType": "chart", "chartType": "bar", "dataSource": "ds_revenue",
 *   "chartConfig": { "xField": "month", "yField": "amount" },
 *   "visualization": { "stacked": true },
 *   "linkage": { ... }, "drillDown": { ... }, "refreshInterval": 60 }
 */

import React, { Suspense, useMemo } from 'react';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import {
  getChartComponent,
  getSupportedChartTypes,
} from '~/framework/smart/charts/SharedChartFactory';

export interface ChartBlockRendererProps {
  block: BlockConfig;
  runtime: SchemaRuntime;
}

const ChartLoadingFallback: React.FC = () => (
  <div className="rounded-card border-border bg-subtle flex h-64 items-center justify-center border">
    <div className="text-text-3 flex items-center gap-2">
      <svg className="h-5 w-5 animate-spin" viewBox="0 0 24 24">
        <circle
          className="opacity-25"
          cx="12"
          cy="12"
          r="10"
          stroke="currentColor"
          strokeWidth="4"
          fill="none"
        />
        <path
          className="opacity-75"
          fill="currentColor"
          d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"
        />
      </svg>
      <span className="text-sm">Loading chart...</span>
    </div>
  </div>
);

/**
 * Interpolate `${record.<field>}`, `${recordPid}`, and `${<field>}` placeholders in a
 * named-query params object against the current record. Returns a new object with string
 * values resolved; non-string values pass through unchanged. Exported for unit testing.
 *
 * Mirrors the SubTableViewer convention so a detail-page chart fed by a record-scoped
 * namedQuery (e.g. an SPC control chart filtered by `${record.pid}`) receives its params.
 */
export function resolveRecordParams(
  params: Record<string, unknown> | undefined,
  record: Record<string, unknown> | undefined,
  recordPid: unknown,
): Record<string, unknown> | undefined {
  if (!params || typeof params !== 'object') return params;
  const out: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(params)) {
    if (typeof value !== 'string') {
      out[key] = value;
      continue;
    }
    out[key] = value
      .replace(/\$\{recordPid\}/g, String(recordPid ?? ''))
      .replace(/\$\{record\.(\w+)\}/g, (_m, f: string) => String(record?.[f] ?? ''))
      .replace(/\$\{(\w+)\}/g, (_m, f: string) => String(record?.[f] ?? ''));
  }
  return out;
}

export const ChartBlockRenderer: React.FC<ChartBlockRendererProps> = ({ block, runtime }) => {
  const props = (block as any).props || {};
  const chartType = (block.chartType as string) || props.chartType || 'bar';
  const ChartComponent = getChartComponent(chartType);

  // Build chart props from DSL block config
  const chartProps = useMemo(() => {
    const config = (block as any).chartConfig || {
      ...(props.xField ? { xField: props.xField } : {}),
      ...(props.yField ? { yField: props.yField } : {}),
      ...(props.height ? { height: props.height } : {}),
    };
    const visualization = (block as any).visualization || {};
    const dataSourceId = typeof block.dataSource === 'string' ? block.dataSource : undefined;

    // Build dataSource config for Smart chart component
    const dataSource = dataSourceId
      ? runtime.getDataSourceManager().getConfig(dataSourceId)
      : config.dataSource;

    // Normalize legacy `params` → `parameters` (the key useChartData/the backend read).
    // Record-scoped `${record.*}`/`${recordPid}` templates are resolved upstream by the page
    // renderer (DetailBlockRenderer), where the current record is available.
    let resolvedDataSource = dataSource;
    if (dataSource && (dataSource as any).params && !(dataSource as any).parameters) {
      resolvedDataSource = { ...dataSource, parameters: (dataSource as any).params };
    }

    return {
      title: typeof block.title === 'string' ? block.title : undefined,
      // Visualization props (new unified format)
      ...visualization,
      // Legacy chartConfig (backward compat)
      ...config,
      // Resolved dataSource MUST come after ...config, which otherwise re-injects the raw
      // (unresolved) config.dataSource and drops the per-record parameters.
      dataSource: resolvedDataSource || { type: 'static' as const, staticData: [] },
      // Advanced features (previously Dashboard-only, now available in DSL)
      linkage: (block as any).linkage,
      drillDown: (block as any).drillDown,
      refreshInterval: (block as any).refreshInterval ?? props.refreshInterval,
      className: block.className,
    };
  }, [
    block,
    props.chartType,
    props.xField,
    props.yField,
    props.height,
    props.refreshInterval,
    runtime,
  ]);

  if (!ChartComponent) {
    return (
      <div className="rounded-card flex h-64 items-center justify-center border border-yellow-200 bg-yellow-50">
        <div className="text-center">
          <p className="font-medium text-yellow-800">Unsupported chart type</p>
          <p className="mt-1 text-sm text-yellow-600">
            <code className="rounded bg-yellow-100 px-1 py-0.5">{chartType}</code>
          </p>
          <p className="mt-2 text-xs text-yellow-500">
            Supported: {getSupportedChartTypes().join(', ')}
          </p>
        </div>
      </div>
    );
  }

  return (
    <Suspense fallback={<ChartLoadingFallback />}>
      <ChartComponent {...chartProps} />
    </Suspense>
  );
};

export default ChartBlockRenderer;
