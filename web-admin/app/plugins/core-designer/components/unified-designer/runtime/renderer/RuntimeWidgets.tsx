import type { DslBlockV3 } from '../../types';
import {
  normalizeRuntimeExecutionError,
  type RuntimeExecutionIssue,
  type RuntimeWidgetData,
} from '../runtimeExecution';
import { getChartComponent, normalizeChartType } from '~/framework/smart/charts/SharedChartFactory';
import { DESIGNER_I18N } from '~/shared/designer';
import React from 'react';
import { useRuntimeText, type RuntimeBlockProps } from './runtimeContexts';
import {
  getBlockLabel,
  getNumberProp,
  getRows,
  getRuntimeBlockContext,
  getSeries,
  getStringArray,
  getStringProp,
  getWidgetGridStyle,
  hasExecutableWidgetDataSource,
  mergeWidgetData,
  pickThresholdColor,
} from './runtimePresentation';





export function RuntimeWidget({ block, runtimeServices, pageContext, blockPath }: RuntimeBlockProps) {
  const [runtimeData, setRuntimeData] = React.useState<RuntimeWidgetData | null>(null);
  const [loading, setLoading] = React.useState(false);
  const [runtimeError, setRuntimeError] = React.useState<RuntimeExecutionIssue | null>(null);
  const dataSourceSignature = JSON.stringify(block.dataSource ?? {});
  const shouldLoadData = Boolean(
    runtimeServices?.loadWidgetData && hasExecutableWidgetDataSource(block),
  );

  React.useEffect(() => {
    if (!shouldLoadData || !runtimeServices?.loadWidgetData) {
      setRuntimeData(null);
      setRuntimeError(null);
      setLoading(false);
      return;
    }

    let cancelled = false;
    setLoading(true);
    setRuntimeError(null);
    void runtimeServices
      .loadWidgetData(block, getRuntimeBlockContext(block, pageContext, blockPath))
      .then((data) => {
        if (!cancelled) setRuntimeData(data);
      })
      .catch((loadError: unknown) => {
        if (!cancelled) {
          setRuntimeData(null);
          setRuntimeError(normalizeRuntimeExecutionError(loadError));
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [block, dataSourceSignature, runtimeServices, shouldLoadData]);

  const effectiveBlock = mergeWidgetData(block, runtimeData);
  const subtitle = getStringProp(block.props?.subtitle);
  // Body widgets (charts / table / markdown / progress) own their visual via
  // RuntimeWidgetBody. Progress in particular consumes props.value as a percentage,
  // so the number-card single-value box must not steal it; suppress it here.
  const value = isWidgetBodyType(block.widgetType)
    ? ''
    : getStringProp(effectiveBlock.props?.value);
  const emptyText = getStringProp(effectiveBlock.props?.emptyText);
  const staticErrorText = getStringProp(effectiveBlock.props?.errorText);
  const errorText = runtimeError?.message || staticErrorText;
  const drillDownTo = getStringProp(block.props?.drillDownTo);
  const model = getStringProp(block.dataSource?.model);
  const metric = getStringProp(block.dataSource?.metric);
  const source = runtimeData?.source;
  const meta = [source, model, metric].filter(Boolean).join(' / ');

  return (
    <div
      className="flex min-h-0 flex-col rounded-md border border-slate-200 bg-slate-50 p-3"
      data-testid={`runtime-widget-${block.id}`}
      style={getWidgetGridStyle(block)}
    >
      <div className="flex items-center justify-between gap-3">
        <div className="text-xs font-medium uppercase tracking-wide text-slate-500">
          {block.widgetType}
        </div>
        {meta ? (
          <div
            className="truncate text-xs text-slate-400"
            data-testid={`runtime-widget-meta-${block.id}`}
          >
            {meta}
          </div>
        ) : null}
      </div>
      <div className="mt-1 truncate text-lg font-semibold text-slate-900">
        {getBlockLabel(block)}
      </div>
      {subtitle ? (
        <div
          className="mt-0.5 truncate text-xs text-slate-500"
          data-testid={`runtime-widget-subtitle-${block.id}`}
        >
          {subtitle}
        </div>
      ) : null}
      {loading ? (
        <div
          className="mt-auto rounded-md border border-blue-200 bg-blue-50 px-3 py-2 text-xs font-medium text-blue-700"
          data-testid={`runtime-widget-loading-${block.id}`}
        >
          Loading data
        </div>
      ) : value ? (
        <div
          className="mt-auto pt-3 text-2xl font-semibold text-slate-950"
          data-testid={`runtime-widget-value-${block.id}`}
        >
          {value}
        </div>
      ) : errorText ? (
        <div
          className="mt-auto rounded-md border border-red-200 bg-red-50 px-3 py-2 text-xs font-medium text-red-700"
          data-testid={`runtime-widget-error-${block.id}`}
          data-error-kind={runtimeError?.kind || (staticErrorText ? 'static' : undefined)}
          data-error-code={runtimeError?.code || undefined}
        >
          {errorText}
          {runtimeError?.hint ? (
            <div
              className="mt-1 font-normal text-red-500"
              data-testid={`runtime-widget-error-hint-${block.id}`}
            >
              {runtimeError.hint}
            </div>
          ) : null}
        </div>
      ) : emptyText ? (
        <div
          className="mt-auto rounded-md border border-dashed border-slate-300 px-3 py-2 text-xs text-slate-500"
          data-testid={`runtime-widget-empty-${block.id}`}
        >
          {emptyText}
        </div>
      ) : (
        <RuntimeWidgetBody block={effectiveBlock} />
      )}
      {drillDownTo ? (
        <div
          className="mt-2 truncate text-xs font-medium text-blue-600"
          data-testid={`runtime-widget-drilldown-${block.id}`}
        >
          {drillDownTo}
        </div>
      ) : null}
    </div>
  );
}

// Widget types whose visual is rendered by RuntimeWidgetBody (a chart / table /
// markdown / progress bar) rather than the number-card value box. The RuntimeWidget
// wrapper consults this set so a body widget that happens to carry a `props.value`
// (e.g. progress reads props.value as its percentage) is not short-circuited into
// the number-card single-value display.
const WIDGET_BODY_TYPES = new Set([
  'bar-chart',
  'line-chart',
  'pie-chart',
  'area-chart',
  'progress',
  'table',
  'markdown',
]);

/**
 * Whether a widgetType owns its own visual body (chart / table / markdown /
 * progress) rather than the single-value number-card box. The 7 explicit body
 * types keep their hand-written representative previews; E1 extends this to ANY
 * SharedChartFactory chart type (radar / gauge / funnel / heatmap / …) so the
 * designer preview matches the live WidgetRenderer (which already supports all
 * types via getChartComponent). number-card is excluded — it IS the value box.
 */
function isChartWidgetType(widgetType: string): boolean {
  const normalized = normalizeChartType(widgetType);
  if (normalized === 'number-card') return false;
  return Boolean(getChartComponent(normalized));
}

function isWidgetBodyType(widgetType: string | undefined): boolean {
  if (typeof widgetType !== 'string') return false;
  return WIDGET_BODY_TYPES.has(widgetType) || isChartWidgetType(widgetType);
}

function RuntimeWidgetBody({ block }: { block: DslBlockV3 }) {
  if (block.widgetType === 'bar-chart') {
    return <RuntimeBarChart block={block} />;
  }
  if (block.widgetType === 'line-chart') {
    return <RuntimeLineChart block={block} />;
  }
  if (block.widgetType === 'pie-chart') {
    return <RuntimePieChart block={block} />;
  }
  if (block.widgetType === 'area-chart') {
    return <RuntimeAreaChart block={block} />;
  }
  if (block.widgetType === 'progress') {
    return <RuntimeProgressWidget block={block} />;
  }
  if (block.widgetType === 'table') {
    return <RuntimeWidgetTable block={block} />;
  }
  if (block.widgetType === 'markdown') {
    return <RuntimeMarkdownWidget block={block} />;
  }
  // E1 — any other SharedChartFactory chart type (radar / scatter / gauge /
  // funnel / heatmap / treemap / gantt / pareto / combo / …) gets a representative
  // generic chart preview instead of falling back to the number-card box. The live
  // page renders the real chart (WidgetRenderer → SharedChartFactory).
  if (block.widgetType && isChartWidgetType(block.widgetType)) {
    return <RuntimeGenericChartPreview block={block} />;
  }
  return null;
}

function RuntimeGenericChartPreview({ block }: { block: DslBlockV3 }) {
  const t = useRuntimeText();
  const chartType = normalizeChartType(block.widgetType ?? '');
  const dataSourceId = getStringProp(block.dataSource?.model) || getStringProp(block.dataSource?.metric);
  return (
    <div
      className="flex min-h-0 flex-1 flex-col rounded-md border border-slate-200 bg-white p-3"
      data-testid={`runtime-widget-chart-${block.id}`}
      data-chart-type={chartType}
    >
      <div className="mb-2 flex items-center gap-2">
        <span className="inline-flex items-center rounded-md bg-indigo-50 px-2 py-0.5 text-xs font-medium text-indigo-700">
          {chartType}
        </span>
        {dataSourceId ? (
          <span className="font-mono text-xs text-slate-500">{dataSourceId}</span>
        ) : null}
      </div>
      {/* Representative chart silhouette (the live page renders the real chart). */}
      <div className="flex h-16 items-end gap-1.5" aria-hidden="true">
        {[45, 70, 55, 85, 60, 75].map((h, i) => (
          <span key={i} className="flex-1 rounded-t bg-indigo-300" style={{ height: `${h}%` }} />
        ))}
      </div>
      <div className="mt-2 text-xs text-slate-400">
        {t(DESIGNER_I18N.unified.runtime.workbenchPreviewHint)}
      </div>
    </div>
  );
}

function RuntimeBarChart({ block }: { block: DslBlockV3 }) {
  const series = getSeries(block.props?.series);
  if (!series.length) return <RuntimeWidgetEmpty block={block} />;
  const maxValue = Math.max(...series.map((item) => item.value), 1);

  return (
    <div className="mt-auto flex items-end gap-2 pt-3">
      {series.map((item, index) => (
        <div
          key={`${item.label}-${index}`}
          className="flex min-w-0 flex-1 flex-col items-center gap-1"
          data-testid={`runtime-widget-bar-${block.id}-${index}`}
          data-value={String(item.value)}
        >
          <div
            className="w-full rounded-t bg-blue-500"
            style={{ height: `${Math.max(12, Math.round((item.value / maxValue) * 54))}px` }}
          />
          <div className="w-full truncate text-center text-[10px] text-slate-500">{item.label}</div>
        </div>
      ))}
    </div>
  );
}

function RuntimeLineChart({ block }: { block: DslBlockV3 }) {
  const series = getSeries(block.props?.series);
  if (!series.length) return <RuntimeWidgetEmpty block={block} />;
  const points = series.map((item) => item.value);
  const maxValue = Math.max(...points, 1);
  const width = 120;
  const height = 56;
  const polyline = points
    .map((value, index) => {
      const x = points.length === 1 ? 0 : (index / (points.length - 1)) * width;
      const y = height - (value / maxValue) * height;
      return `${x},${y}`;
    })
    .join(' ');

  return (
    <svg
      className="mt-auto h-16 w-full overflow-visible"
      viewBox={`0 0 ${width} ${height}`}
      data-testid={`runtime-widget-line-${block.id}`}
      data-points={points.join(',')}
      role="img"
      aria-label={getBlockLabel(block)}
    >
      <polyline fill="none" stroke="#2563eb" strokeWidth="3" points={polyline} />
    </svg>
  );
}

// Categorical palette for the pie slices. These map 1:1 to the slate/blue family
// already used by the bar/line mini-renderers so the widget stays on-token; pure
// SVG fills (no chart library) keep the runtime preview dependency-free.
const PIE_SLICE_COLORS = [
  'fill-blue-500',
  'fill-emerald-500',
  'fill-amber-500',
  'fill-violet-500',
  'fill-rose-500',
  'fill-cyan-500',
];

function polarToCartesian(cx: number, cy: number, r: number, angle: number): [number, number] {
  const rad = ((angle - 90) * Math.PI) / 180;
  return [cx + r * Math.cos(rad), cy + r * Math.sin(rad)];
}

function RuntimePieChart({ block }: { block: DslBlockV3 }) {
  const series = getSeries(block.props?.series);
  const total = series.reduce((sum, item) => sum + Math.max(item.value, 0), 0);
  if (!series.length || total <= 0) return <RuntimeWidgetEmpty block={block} />;

  const size = 64;
  const center = size / 2;
  const radius = center;
  let cursor = 0;

  return (
    <svg
      className="mt-auto h-16 w-full overflow-visible"
      viewBox={`0 0 ${size} ${size}`}
      data-testid={`runtime-widget-pie-${block.id}`}
      data-total={String(total)}
      data-slices={String(series.length)}
      role="img"
      aria-label={getBlockLabel(block)}
    >
      {series.map((item, index) => {
        const fraction = Math.max(item.value, 0) / total;
        const startAngle = cursor * 360;
        cursor += fraction;
        const endAngle = cursor * 360;
        // A single-slice pie (fraction === 1) cannot be drawn with one arc path
        // (start point === end point), so render it as a full circle instead.
        if (fraction >= 1) {
          return (
            <circle
              key={`${item.label}-${index}`}
              cx={center}
              cy={center}
              r={radius}
              className={PIE_SLICE_COLORS[index % PIE_SLICE_COLORS.length]}
              data-testid={`runtime-widget-pie-slice-${block.id}-${index}`}
              data-value={String(item.value)}
            />
          );
        }
        const [startX, startY] = polarToCartesian(center, center, radius, startAngle);
        const [endX, endY] = polarToCartesian(center, center, radius, endAngle);
        const largeArc = endAngle - startAngle > 180 ? 1 : 0;
        const path = `M ${center} ${center} L ${startX} ${startY} A ${radius} ${radius} 0 ${largeArc} 1 ${endX} ${endY} Z`;
        return (
          <path
            key={`${item.label}-${index}`}
            d={path}
            className={PIE_SLICE_COLORS[index % PIE_SLICE_COLORS.length]}
            data-testid={`runtime-widget-pie-slice-${block.id}-${index}`}
            data-value={String(item.value)}
          />
        );
      })}
    </svg>
  );
}

function RuntimeAreaChart({ block }: { block: DslBlockV3 }) {
  const series = getSeries(block.props?.series);
  if (!series.length) return <RuntimeWidgetEmpty block={block} />;
  const points = series.map((item) => item.value);
  const maxValue = Math.max(...points, 1);
  const width = 120;
  const height = 56;
  const coords = points.map((value, index) => {
    const x = points.length === 1 ? width / 2 : (index / (points.length - 1)) * width;
    const y = height - (value / maxValue) * height;
    return [x, y] as const;
  });
  const line = coords.map(([x, y]) => `${x},${y}`).join(' ');
  // Close the path back along the baseline to fill the area beneath the line.
  const firstX = coords[0][0];
  const lastX = coords[coords.length - 1][0];
  const areaPath = `M ${firstX},${height} L ${coords
    .map(([x, y]) => `${x},${y}`)
    .join(' L ')} L ${lastX},${height} Z`;

  return (
    <svg
      className="mt-auto h-16 w-full overflow-visible"
      viewBox={`0 0 ${width} ${height}`}
      data-testid={`runtime-widget-area-${block.id}`}
      data-points={points.join(',')}
      role="img"
      aria-label={getBlockLabel(block)}
    >
      <path
        d={areaPath}
        className="fill-blue-500/20 stroke-none"
        data-testid={`runtime-widget-area-fill-${block.id}`}
      />
      <polyline className="fill-none stroke-blue-500" strokeWidth="3" points={line} />
    </svg>
  );
}

function RuntimeProgressWidget({ block }: { block: DslBlockV3 }) {
  const raw = getNumberProp(block.props?.value);
  if (raw === null) return <RuntimeWidgetEmpty block={block} />;
  const percent = Math.max(0, Math.min(100, raw));
  // Optional thresholds re-colour the bar by band (e.g. green ≥80 / amber ≥50 /
  // red below). Thresholds are [{ value, color }] sorted high→low; the first one
  // the percentage meets wins. Without thresholds the bar stays accent blue.
  const band = pickThresholdColor(block.props?.thresholds, percent);

  return (
    <div className="mt-auto pt-3" data-testid={`runtime-widget-progress-${block.id}`}>
      <div className="mb-1 flex items-center justify-between text-xs text-slate-500">
        <span data-testid={`runtime-widget-progress-label-${block.id}`}>{getBlockLabel(block)}</span>
        <span
          className="font-semibold text-slate-700"
          data-testid={`runtime-widget-progress-value-${block.id}`}
        >
          {percent}%
        </span>
      </div>
      <div
        className="h-2 w-full overflow-hidden rounded-full bg-slate-200"
        role="progressbar"
        aria-valuenow={percent}
        aria-valuemin={0}
        aria-valuemax={100}
      >
        <div
          className={`h-full rounded-full ${band}`}
          data-testid={`runtime-widget-progress-bar-${block.id}`}
          data-percent={String(percent)}
          style={{ width: `${percent}%` }}
        />
      </div>
    </div>
  );
}

function RuntimeWidgetTable({ block }: { block: DslBlockV3 }) {
  const columns = getStringArray(block.props?.columns);
  const rows = getRows(block.props?.rows);
  if (!columns.length || !rows.length) return <RuntimeWidgetEmpty block={block} />;

  return (
    <table
      className="mt-3 w-full table-fixed border-collapse text-xs"
      data-testid={`runtime-widget-table-${block.id}`}
    >
      <thead>
        <tr>
          {columns.map((column) => (
            <th
              key={column}
              className="border-b border-slate-200 px-1 py-1 text-left font-semibold"
            >
              {column}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {rows.slice(0, 3).map((row, rowIndex) => (
          <tr key={rowIndex}>
            {columns.map((column, columnIndex) => (
              <td
                key={`${rowIndex}-${column}`}
                className="truncate border-b border-slate-100 px-1 py-1"
              >
                {row[columnIndex] ?? ''}
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function RuntimeMarkdownWidget({ block }: { block: DslBlockV3 }) {
  const markdown = getStringProp(block.props?.markdown);
  if (!markdown) return <RuntimeWidgetEmpty block={block} />;
  return (
    <div
      className="mt-3 line-clamp-4 whitespace-pre-wrap text-xs leading-5 text-slate-600"
      data-testid={`runtime-widget-markdown-${block.id}`}
    >
      {markdown}
    </div>
  );
}

function RuntimeWidgetEmpty({ block }: { block: DslBlockV3 }) {
  const t = useRuntimeText();
  const emptyText = getStringProp(block.props?.emptyText) || t(DESIGNER_I18N.unified.runtime.noData);
  return (
    <div
      className="mt-auto rounded-md border border-dashed border-slate-300 px-3 py-2 text-xs text-slate-500"
      data-testid={`runtime-widget-empty-${block.id}`}
    >
      {emptyText}
    </div>
  );
}
