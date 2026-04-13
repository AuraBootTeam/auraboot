/**
 * SmartGanttChart Component
 *
 * A Gantt chart component using ECharts for production scheduling,
 * project timelines, and MRP planning visualization.
 * Renders tasks as horizontal bars on a time axis with optional progress overlay.
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

/**
 * Props for SmartGanttChart component
 */
export interface SmartGanttChartProps {
  /** Chart title */
  title?: string;
  /** Data source configuration */
  dataSource: ChartDataSource;
  /** Field containing task name (default: first dimension) */
  taskField?: string;
  /** Field containing start date */
  startField?: string;
  /** Field containing end date */
  endField?: string;
  /** Field containing progress value (0-100) */
  progressField?: string;
  /** Field for grouping / color-coding tasks */
  categoryField?: string;
  /** Show progress bars (default: true) */
  showProgress?: boolean;
  /** Height of gantt bars in pixels (default: 24) */
  barHeight?: number;
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
}

// Color palette for category-based coloring
const CATEGORY_COLORS = [
  '#5470c6',
  '#91cc75',
  '#fac858',
  '#ee6666',
  '#73c0de',
  '#3ba272',
  '#fc8452',
  '#9a60b4',
  '#ea7ccc',
  '#4dc9f6',
];

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

/**
 * Parse a value to a Date object, returns null on failure
 */
function parseDate(value: unknown): Date | null {
  if (!value) return null;
  const d = new Date(value as string | number);
  return isNaN(d.getTime()) ? null : d;
}

/**
 * Format a date to "YYYY-MM-DD" string
 */
function formatDate(d: Date): string {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

/**
 * Compute duration in days between two dates (at least 1)
 */
function durationDays(start: Date, end: Date): number {
  const ms = end.getTime() - start.getTime();
  return Math.max(1, Math.round(ms / 86_400_000));
}

/**
 * SmartGanttChart - A Gantt chart component with ECharts
 *
 * @example
 * // Basic Gantt chart from named query
 * <SmartGanttChart
 *   title="生产排程"
 *   dataSource={{
 *     type: 'namedQuery',
 *     queryCode: 'production_schedule',
 *   }}
 *   taskField="order_name"
 *   startField="planned_start"
 *   endField="planned_end"
 *   progressField="progress"
 *   categoryField="status"
 * />
 */
export const SmartGanttChart: React.FC<SmartGanttChartProps> = ({
  title,
  dataSource,
  taskField,
  startField,
  endField,
  progressField,
  categoryField,
  showProgress = true,
  barHeight = 24,
  drillDown,
  linkage,
  onDrillDown,
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

  /**
   * Handle chart click events for drill-down and linkage
   */
  const handleChartClick = useCallback(
    (params: { name?: string; seriesName?: string; data?: unknown }) => {
      const clickedValue = params.name;
      if (!clickedValue) return;

      const fieldName = taskField || data?.meta?.dimensions?.[0] || 'task';
      const filter: FilterConfig = {
        field: fieldName,
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
    [data, taskField, drillDown, linkage, onDrillDown, onLinkageEmit],
  );

  /**
   * Resolve field names: use explicit props, or fall back to data meta
   */
  const resolvedFields = useMemo(() => {
    const dims = data?.meta?.dimensions || [];
    const mets = data?.meta?.metrics || [];
    const allFields = [...dims, ...mets];

    // Smart field resolution: explicit prop > heuristic by name > positional fallback
    const resolveByHint = (hints: string[], fallback?: string) => {
      for (const hint of hints) {
        const found = allFields.find((f) => f.toLowerCase().includes(hint));
        if (found) return found;
      }
      return fallback;
    };

    return {
      task: taskField || dims[0] || 'task',
      start: startField || resolveByHint(['start', 'begin', 'plan_start'], allFields[1]) || 'start',
      end: endField || resolveByHint(['end', 'finish', 'plan_end', 'due'], allFields[2]) || 'end',
      progress: progressField || resolveByHint(['progress', 'percent', 'completion'], undefined),
      category: categoryField || resolveByHint(['category', 'status', 'type', 'group'], undefined),
    };
  }, [data, taskField, startField, endField, progressField, categoryField]);

  /**
   * Build ECharts options from data
   */
  const options: EChartsOption = useMemo(() => {
    if (!data?.rows?.length) {
      return {
        title: title ? { text: title, left: 'center', textStyle: { fontSize: 14 } } : undefined,
        xAxis: { type: 'time' },
        yAxis: { type: 'category', data: [] },
        series: [],
      };
    }

    const { task, start, end, progress, category } = resolvedFields;

    // Build task list sorted by start date
    interface GanttTask {
      name: string;
      start: Date;
      end: Date;
      progress: number;
      category: string;
      raw: Record<string, unknown>;
    }

    const tasks: GanttTask[] = data.rows
      .map((row) => {
        const startDate = parseDate(row[start]);
        const endDate = parseDate(row[end]);
        if (!startDate || !endDate) return null;

        return {
          name: String(row[task] ?? ''),
          start: startDate,
          end: endDate,
          progress: progress ? Math.min(100, Math.max(0, Number(row[progress]) || 0)) : 0,
          category: category ? String(row[category] ?? '') : '',
          raw: row,
        };
      })
      .filter(Boolean) as GanttTask[];

    // Sort by start date
    tasks.sort((a, b) => a.start.getTime() - b.start.getTime());

    // Task names for Y axis (reversed so first task appears at top)
    const taskNames = tasks.map((t) => t.name);
    const reversedNames = [...taskNames].reverse();

    // Build category→color mapping
    const categories = [...new Set(tasks.map((t) => t.category).filter(Boolean))];
    const colorMap: Record<string, string> = {};
    categories.forEach((cat, i) => {
      colorMap[cat] = CATEGORY_COLORS[i % CATEGORY_COLORS.length];
    });

    // Compute time range for today-line
    const allStarts = tasks.map((t) => t.start.getTime());
    const allEnds = tasks.map((t) => t.end.getTime());
    const minTime = Math.min(...allStarts);
    const maxTime = Math.max(...allEnds);
    // Add 5% padding on each side
    const rangePad = (maxTime - minTime) * 0.05 || 86_400_000;

    const today = new Date();
    const todayTimestamp = today.getTime();
    const showTodayLine =
      todayTimestamp >= minTime - rangePad && todayTimestamp <= maxTime + rangePad;

    // Build bar series data: each item is [startTime, endTime, yIndex]
    // ECharts custom renderItem for Gantt bars
    const barData = tasks.map((t, idx) => ({
      value: [t.start.getTime(), t.end.getTime(), taskNames.length - 1 - idx],
      itemStyle: {
        color: t.category && colorMap[t.category] ? colorMap[t.category] : CATEGORY_COLORS[0],
      },
      name: t.name,
    }));

    // Progress overlay data
    const progressData = showProgress
      ? tasks.map((t, idx) => {
          const dur = t.end.getTime() - t.start.getTime();
          const progressEnd = t.start.getTime() + dur * (t.progress / 100);
          return {
            value: [t.start.getTime(), progressEnd, taskNames.length - 1 - idx],
            itemStyle: {
              color: 'rgba(0,0,0,0.15)',
            },
            name: t.name,
          };
        })
      : [];

    // Calculate dynamic height: barHeight per task + padding
    const BAR_GAP = 8;
    const chartContentHeight = Math.max(200, tasks.length * (barHeight + BAR_GAP) + 60);

    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const renderGanttItem = (params: any, api: any) => {
      const startVal = api.value(0);
      const endVal = api.value(1);
      const yIdx = api.value(2);

      const start = api.coord([startVal, yIdx]);
      const end = api.coord([endVal, yIdx]);

      const barHeightActual = Math.min(barHeight, api.size([0, 1])[1] * 0.6);

      return {
        type: 'rect',
        shape: {
          x: start[0],
          y: start[1] - barHeightActual / 2,
          width: Math.max(end[0] - start[0], 2),
          height: barHeightActual,
          r: 3,
        },
        style: api.style(),
      };
    };

    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const seriesList: any[] = [
      {
        type: 'custom',
        renderItem: renderGanttItem,
        encode: {
          x: [0, 1],
          y: 2,
        },
        data: barData,
        clip: true,
        z: 1,
      },
    ];

    // Progress overlay series
    if (showProgress && progressData.length) {
      seriesList.push({
        type: 'custom',
        renderItem: renderGanttItem,
        encode: {
          x: [0, 1],
          y: 2,
        },
        data: progressData,
        clip: true,
        z: 2,
        silent: true,
      });
    }

    // Today line as markLine
    if (showTodayLine) {
      seriesList[0].markLine = {
        silent: true,
        symbol: 'none',
        data: [
          {
            xAxis: todayTimestamp,
            label: {
              formatter: '今天',
              position: 'end',
              fontSize: 10,
              color: '#ef4444',
            },
            lineStyle: {
              color: '#ef4444',
              type: 'dashed',
              width: 1.5,
            },
          },
        ],
      };
    }

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
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        formatter: (params: any) => {
          if (!params?.value) return '';
          const [startTs, endTs, yIdx] = params.value as [number, number, number];
          const taskIdx = taskNames.length - 1 - yIdx;
          const t = tasks[taskIdx];
          if (!t) return '';

          const dur = durationDays(t.start, t.end);
          let html = `<strong>${t.name}</strong><br/>`;
          html += `开始: ${formatDate(t.start)}<br/>`;
          html += `结束: ${formatDate(t.end)}<br/>`;
          html += `工期: ${dur} 天<br/>`;
          if (showProgress && progress) {
            html += `进度: ${t.progress}%`;
          }
          if (t.category) {
            html += `<br/>分类: ${t.category}`;
          }
          return html;
        },
      },
      grid: {
        left: '3%',
        right: '5%',
        top: title ? '40' : '20',
        bottom: categories.length > 0 ? '50' : '20',
        containLabel: true,
      },
      xAxis: {
        type: 'time',
        min: minTime - rangePad,
        max: maxTime + rangePad,
        axisLabel: {
          hideOverlap: true,
          fontSize: 11,
        },
        splitLine: {
          show: true,
          lineStyle: { type: 'dashed', color: '#e5e7eb' },
        },
      },
      yAxis: {
        type: 'category',
        data: reversedNames,
        axisLabel: {
          fontSize: 11,
          width: 120,
          overflow: 'truncate',
          ellipsis: '...',
        },
        axisTick: { show: false },
        splitLine: {
          show: true,
          lineStyle: { type: 'dashed', color: '#f3f4f6' },
        },
      },
      legend:
        categories.length > 0
          ? {
              bottom: 0,
              type: 'scroll',
              data: categories.map((cat) => ({
                name: cat,
                itemStyle: { color: colorMap[cat] },
              })),
            }
          : undefined,
      dataZoom: [
        {
          type: 'inside',
          xAxisIndex: 0,
          filterMode: 'weakFilter',
        },
        {
          type: 'slider',
          xAxisIndex: 0,
          height: 16,
          bottom: categories.length > 0 ? 30 : 5,
          filterMode: 'weakFilter',
          showDetail: false,
        },
      ],
      series: seriesList,
    } as EChartsOption;

    // Store chartContentHeight for use in rendering
    (baseOptions as unknown as { _ganttHeight: number })._ganttHeight = chartContentHeight;

    return baseOptions;
  }, [data, title, resolvedFields, showProgress, barHeight]);

  /**
   * ECharts event handlers
   */
  const onEvents = useMemo(
    () => ({
      click: handleChartClick,
    }),
    [handleChartClick],
  );

  // Compute chart height dynamically based on task count
  const chartHeight = useMemo(() => {
    const ganttHeight = (options as unknown as { _ganttHeight?: number })._ganttHeight;
    return ganttHeight || 300;
  }, [options]);

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
          <div className="mb-3 text-4xl text-gray-400">📅</div>
          <div className="font-medium text-gray-500">{title || '甘特图'}</div>
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
        style={{ height: Math.max(300, chartHeight), minHeight: 0 }}
        onEvents={onEvents}
        notMerge
        lazyUpdate
      />
    </div>
  );
};

export default SmartGanttChart;
