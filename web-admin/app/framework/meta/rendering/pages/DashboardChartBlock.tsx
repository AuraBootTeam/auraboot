/**
 * @deprecated Use SharedChartFactory + ChartBlockRenderer instead.
 * This legacy bridge only supports 5 chart types. New code should use
 * ChartBlockRenderer which supports all 23 types via SharedChartFactory.
 * Kept temporarily for backward compat in ListPageContent dashboard rendering.
 *
 * DashboardChartBlock — Renders chart-type blocks inside Dashboard pages
 *
 * Bridges DSL chart block config to Smart chart components (SmartBarChart,
 * SmartLineChart, SmartNumberCard, etc.) by converting the block's
 * chartConfig.dataSource into the ChartDataSource format.
 *
 * Supported chartTypes: bar, line, number-card (and all others via ChartBlockRenderer).
 */

import React, { Suspense, useMemo } from 'react';
import type { ChartDataSource, DrillDownConfig } from '~/framework/smart/types/chart';

interface DashboardChartBlockProps {
  block: {
    id: string;
    blockType: string;
    chartType: string;
    title?: string | Record<string, string>;
    chartConfig?: {
      dataSource?: ChartDataSource;
      [key: string]: unknown;
    };
    layout?: { colSpan?: number; rowSpan?: number };
    [key: string]: unknown;
  };
  locale?: string;
  onDrillDown?: (config: DrillDownConfig) => void;
}

/** Lazy-loaded chart components */
const SmartBarChart = React.lazy(() =>
  import('~/framework/smart/components/charts/SmartBarChart').then((m) => ({ default: m.SmartBarChart })),
);
const SmartLineChart = React.lazy(() =>
  import('~/framework/smart/components/charts/SmartLineChart').then((m) => ({ default: m.SmartLineChart })),
);
const SmartNumberCard = React.lazy(() =>
  import('~/framework/smart/components/charts/SmartNumberCard').then((m) => ({ default: m.SmartNumberCard })),
);
const SmartPieChart = React.lazy(() =>
  import('~/framework/smart/components/charts/SmartPieChart').then((m) => ({ default: m.SmartPieChart })),
);
const SmartAreaChart = React.lazy(() =>
  import('~/framework/smart/components/charts/SmartAreaChart').then((m) => ({ default: m.SmartAreaChart })),
);

function getLocalizedTitle(
  title: string | Record<string, string> | undefined,
  locale: string = 'zh-CN',
): string {
  if (!title) return '';
  if (typeof title === 'string') return title;
  return title[locale] || title['zh-CN'] || title['en'] || '';
}

const ChartFallback: React.FC = () => (
  <div className="flex h-64 items-center justify-center rounded-lg border border-gray-200 bg-gray-50">
    <div className="flex items-center gap-2 text-gray-400">
      <div className="h-5 w-5 animate-spin rounded-full border-2 border-blue-500 border-t-transparent" />
      <span className="text-sm">Loading chart...</span>
    </div>
  </div>
);

export const DashboardChartBlock: React.FC<DashboardChartBlockProps> = ({
  block,
  locale = 'zh-CN',
  onDrillDown,
}) => {
  const chartType = block.chartType || 'bar';
  const title = getLocalizedTitle(block.title, locale);
  const config = block.chartConfig || {};
  const dataSource = config.dataSource || { type: 'static' as const, staticData: [] };

  // Extract chart-specific props from chartConfig (excluding dataSource)
  const chartProps = useMemo(() => {
    const { dataSource: _ds, ...rest } = config;
    return rest;
  }, [config]);

  if (chartType === 'number-card') {
    return (
      <Suspense fallback={<ChartFallback />}>
        <SmartNumberCard
          title={title}
          dataSource={dataSource}
          icon={chartProps.icon as string}
          format={chartProps.format as 'number' | 'currency' | 'percent'}
          precision={chartProps.precision as number}
          currency={chartProps.currency as string}
          className={chartProps.className as string}
          drillDown={chartProps.drillDown as DrillDownConfig}
          onDrillDown={onDrillDown}
        />
      </Suspense>
    );
  }

  if (chartType === 'line') {
    return (
      <Suspense fallback={<ChartFallback />}>
        <SmartLineChart
          title={title}
          dataSource={dataSource}
          smooth={chartProps.smooth as boolean}
          areaStyle={chartProps.areaStyle as boolean}
          showLabel={chartProps.showLabel as boolean}
          className={chartProps.className as string}
        />
      </Suspense>
    );
  }

  if (chartType === 'bar') {
    return (
      <Suspense fallback={<ChartFallback />}>
        <SmartBarChart
          title={title}
          dataSource={dataSource}
          orientation={chartProps.orientation as 'vertical' | 'horizontal'}
          stacked={chartProps.stacked as boolean}
          showLabel={chartProps.showLabel as boolean}
          className={chartProps.className as string}
        />
      </Suspense>
    );
  }

  if (chartType === 'pie') {
    return (
      <Suspense fallback={<ChartFallback />}>
        <SmartPieChart
          title={title}
          dataSource={dataSource}
          className={chartProps.className as string}
        />
      </Suspense>
    );
  }

  if (chartType === 'area') {
    return (
      <Suspense fallback={<ChartFallback />}>
        <SmartAreaChart
          title={title}
          dataSource={dataSource}
          className={chartProps.className as string}
        />
      </Suspense>
    );
  }

  // Unsupported chart type
  return (
    <div className="flex h-64 items-center justify-center rounded-lg border border-yellow-200 bg-yellow-50">
      <div className="text-center">
        <p className="font-medium text-yellow-800">Unsupported chart type</p>
        <p className="mt-1 text-sm text-yellow-600">
          <code className="rounded bg-yellow-100 px-1 py-0.5">{chartType}</code>
        </p>
      </div>
    </div>
  );
};

export default DashboardChartBlock;
