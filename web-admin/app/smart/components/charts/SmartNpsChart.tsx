/**
 * SmartNpsChart Component
 *
 * Net Promoter Score gauge with ring breakdown.
 * Auto-classifies scores (0-10) into Promoter/Passive/Detractor.
 * Center displays NPS score (-100 to +100).
 */

import React, { useMemo } from 'react';
import ReactECharts from 'echarts-for-react';
import type { EChartsOption, GraphicComponentOption } from 'echarts';
import { useChartData } from '~/smart/hooks/useChartData';
import type { ChartDataSource, LinkageConfig, FilterConfig } from '~/smart/types/chart';
import { cn } from '~/utils/cn';

export interface SmartNpsChartProps {
  title?: string;
  dataSource: ChartDataSource;
  scoreField?: string;
  showPercentage?: boolean;
  showLegend?: boolean;
  ringWidth?: number;
  centerFontSize?: number;
  linkage?: LinkageConfig;
  onLinkageEmit?: (filters: FilterConfig[]) => void;
  linkageFilters?: FilterConfig[];
  refreshInterval?: number;
  className?: string;
  style?: React.CSSProperties;
}

interface NpsBreakdown {
  promoters: number;
  passives: number;
  detractors: number;
  total: number;
  score: number;
}

const NPS_COLORS = {
  promoter: '#52c41a',
  passive: '#faad14',
  detractor: '#ff4d4f',
};

function classifyScores(rows: Record<string, unknown>[], scoreKey: string): NpsBreakdown {
  let promoters = 0;
  let passives = 0;
  let detractors = 0;

  for (const row of rows) {
    const score = Number(row[scoreKey]);
    if (isNaN(score)) continue;
    if (score >= 9) promoters++;
    else if (score >= 7) passives++;
    else detractors++;
  }

  const total = promoters + passives + detractors;
  const npsScore = total > 0
    ? Math.round((promoters / total) * 100 - (detractors / total) * 100)
    : 0;

  return { promoters, passives, detractors, total, score: npsScore };
}

function getScoreColor(score: number): string {
  if (score > 50) return NPS_COLORS.promoter;
  if (score >= 0) return NPS_COLORS.passive;
  return NPS_COLORS.detractor;
}

function isDataSourceConfigured(ds: ChartDataSource): boolean {
  if (!ds) return false;
  if (ds.type === 'aggregate') return !!(ds.modelCode && ds.metrics?.length);
  if (ds.type === 'namedQuery') return !!ds.queryCode;
  return ds.type === 'static';
}

export const SmartNpsChart: React.FC<SmartNpsChartProps> = ({
  title,
  dataSource,
  scoreField,
  showPercentage = true,
  showLegend = true,
  ringWidth = 30,
  centerFontSize = 36,
  linkage,
  onLinkageEmit,
  linkageFilters,
  refreshInterval,
  className,
  style,
}) => {
  const isConfigured = isDataSourceConfigured(dataSource);

  const { data, loading, error } = useChartData({
    dataSource,
    linkageFilters: linkage?.receiveFilter ? linkageFilters : undefined,
    refreshInterval,
    enabled: isConfigured,
  });

  const nps = useMemo<NpsBreakdown | null>(() => {
    if (!data?.rows?.length) return null;

    const metrics = data.meta?.metrics || [];
    const dimensions = data.meta?.dimensions || [];
    const key = scoreField || metrics[0] || dimensions[0];
    if (!key) return null;

    return classifyScores(data.rows, key);
  }, [data, scoreField]);

  const options: EChartsOption = useMemo(() => {
    if (!nps || nps.total === 0) {
      return {
        title: title ? { text: title, left: 'center', textStyle: { fontSize: 14 } } : undefined,
        series: [{ type: 'pie', data: [], radius: ['50%', '70%'] }],
      };
    }

    const pPct = Math.round((nps.promoters / nps.total) * 100);
    const paPct = Math.round((nps.passives / nps.total) * 100);
    const dPct = 100 - pPct - paPct;

    const pieData = [
      {
        name: `Promoters (${pPct}%)`,
        value: nps.promoters,
        itemStyle: { color: NPS_COLORS.promoter },
      },
      {
        name: `Passives (${paPct}%)`,
        value: nps.passives,
        itemStyle: { color: NPS_COLORS.passive },
      },
      {
        name: `Detractors (${dPct}%)`,
        value: nps.detractors,
        itemStyle: { color: NPS_COLORS.detractor },
      },
    ];

    const scoreColor = getScoreColor(nps.score);
    const scoreSign = nps.score > 0 ? '+' : '';

    const graphic: GraphicComponentOption[] = [
      {
        type: 'text',
        left: 'center',
        top: 'center',
        style: {
          text: `${scoreSign}${nps.score}`,
          fontSize: centerFontSize,
          fontWeight: 'bold',
          fill: scoreColor,
        },
      },
      {
        type: 'text',
        left: 'center',
        top: '56%',
        style: {
          text: 'NPS',
          fontSize: 12,
          fill: '#999',
        },
      },
    ];

    const outerRadiusPct = '70%';
    const innerRadiusPct = `${70 - (ringWidth / 2)}%`;

    return {
      title: title
        ? { text: title, left: 'center', textStyle: { fontSize: 14, fontWeight: 500 } }
        : undefined,
      tooltip: {
        trigger: 'item',
        formatter: (params: any) => {
          const pct = Math.round((params.value / nps.total) * 100);
          return `${params.name}<br/>Count: ${params.value} (${pct}%)`;
        },
      },
      legend: showLegend
        ? { bottom: 0, orient: 'horizontal' as const, type: 'scroll' as const }
        : undefined,
      graphic,
      series: [
        {
          type: 'pie',
          radius: [innerRadiusPct, outerRadiusPct],
          center: ['50%', '48%'],
          data: pieData,
          label: showPercentage
            ? { show: true, position: 'outside', formatter: '{d}%' }
            : { show: false },
          labelLine: showPercentage ? { show: true } : { show: false },
          emphasis: {
            itemStyle: { shadowBlur: 10, shadowOffsetX: 0, shadowColor: 'rgba(0, 0, 0, 0.2)' },
          },
          startAngle: 90,
          animationType: 'scale',
        },
      ],
    } as EChartsOption;
  }, [nps, title, showPercentage, showLegend, ringWidth, centerFontSize]);

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
          <div className="font-medium text-gray-500">{title || 'NPS Chart'}</div>
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
        notMerge
        lazyUpdate
      />
    </div>
  );
};

export default SmartNpsChart;
