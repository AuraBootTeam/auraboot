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
import { getChartComponent, getSupportedChartTypes } from '~/framework/smart/charts/SharedChartFactory';

export interface ChartBlockRendererProps {
  block: BlockConfig;
  runtime: SchemaRuntime;
}

const ChartLoadingFallback: React.FC = () => (
  <div className="flex h-64 items-center justify-center rounded-lg border border-gray-200 bg-gray-50">
    <div className="flex items-center gap-2 text-gray-400">
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

export const ChartBlockRenderer: React.FC<ChartBlockRendererProps> = ({ block, runtime }) => {
  const chartType = (block.chartType as string) || 'bar';
  const ChartComponent = getChartComponent(chartType);

  // Build chart props from DSL block config
  const chartProps = useMemo(() => {
    const config = (block as any).chartConfig || {};
    const visualization = (block as any).visualization || {};
    const dataSourceId = block.dataSource;

    // Build dataSource config for Smart chart component
    const dataSource = dataSourceId
      ? runtime.getDataSourceManager().getConfig(dataSourceId)
      : config.dataSource;

    return {
      title: typeof block.title === 'string' ? block.title : undefined,
      dataSource: dataSource || { type: 'static' as const, staticData: [] },
      // Visualization props (new unified format)
      ...visualization,
      // Legacy chartConfig (backward compat)
      ...config,
      // Advanced features (previously Dashboard-only, now available in DSL)
      linkage: (block as any).linkage,
      drillDown: (block as any).drillDown,
      refreshInterval: (block as any).refreshInterval,
      className: block.className,
    };
  }, [block, runtime]);

  if (!ChartComponent) {
    return (
      <div className="flex h-64 items-center justify-center rounded-lg border border-yellow-200 bg-yellow-50">
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
