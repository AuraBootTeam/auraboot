/**
 * ChartSpec — renderer-agnostic chart semantic contract (B2a).
 *
 * DDR-2026-06-18-designer-kernel-boundary / backlog 2026-06-18-designer-layout-family-convergence §B2.
 *
 * ChartSpec is the SINGLE semantic description of a chart: WHAT to plot (type +
 * data binding + dimensions/measures + interaction intent), never HOW a specific
 * renderer draws it. It is consumed by multiple render targets:
 *   - B2b echarts adapter (interactive, screen)
 *   - B2c SVG adapter (print-safe, report/PDF)
 *
 * It deliberately reuses the existing, already renderer-agnostic data sub-types in
 * `../types/chart` (MetricConfig / FilterConfig / OrderByConfig / ChartDataSource /
 * DrillDownConfig / LinkageConfig) so this is a CONSOLIDATION, not a parallel DSL
 * (backlog B1 Forbidden: "禁新增第二套 ChartSpec-like DSL"). The one thing the legacy
 * `ChartConfig` does that ChartSpec forbids is `chartOptions` ("passed to ECharts") —
 * that renderer leak is surfaced by the adapter and removed in B2d.
 *
 * HARD CONSTRAINT (renderer-agnostic): a ChartSpec MUST NOT carry echarts `option`/
 * `series`, SVG `path`, openhtmltopdf / `printWidth`, or any renderer-native config.
 * Enforced by `assertRendererAgnostic()` + the unit test guard.
 */

import type {
  ChartDataSource,
  FilterConfig,
  OrderByConfig,
  DrillDownConfig,
  LinkageConfig,
} from '../types/chart';

/**
 * Chart-like types (data-series visualizations). This is the subset of the
 * SharedChartFactory registry that has a dimension/measure semantic model; pure
 * display widgets (number-card, progress, rich-text, image, iframe, calendar,
 * gallery, kanban, leaderboard, countdown, and workbench widgets) are NOT
 * ChartSpec types — they have no dimensions/measures contract.
 */
export type ChartSpecType =
  | 'bar'
  | 'line'
  | 'area'
  | 'pie'
  | 'scatter'
  | 'radar'
  | 'funnel'
  | 'gauge'
  | 'heatmap'
  | 'treemap'
  | 'map'
  | 'spc'
  | 'pareto'
  | 'gantt'
  | 'combo'
  | 'nps'
  | 'wordcloud'
  | 'table';

export const CHART_SPEC_TYPES: readonly ChartSpecType[] = [
  'bar', 'line', 'area', 'pie', 'scatter', 'radar', 'funnel', 'gauge', 'heatmap',
  'treemap', 'map', 'spc', 'pareto', 'gantt', 'combo', 'nps', 'wordcloud', 'table',
];

export function isChartSpecType(t: string): t is ChartSpecType {
  return (CHART_SPEC_TYPES as readonly string[]).includes(t);
}

/** A localized or plain title. */
export type ChartTitle = string | Record<string, string>;

/**
 * A dimension = a categorical axis of the chart. `role` disambiguates the
 * primary category axis from the series-splitting field and the slice-name field.
 */
export interface ChartDimension {
  /** Source field / column name from the resolved data rows. */
  field: string;
  /** How this dimension maps onto the chart. */
  role: 'category' | 'series' | 'name';
  /** Display label (defaults to field). */
  label?: string;
}

/**
 * A measure = a numeric value plotted. Mirrors `MetricConfig` from ../types/chart
 * but is the chart-facing view (aggregation is optional: pre-aggregated named
 * queries do not need it).
 */
export interface ChartMeasure {
  field: string;
  aggregation?: 'count' | 'count_distinct' | 'sum' | 'avg' | 'max' | 'min';
  label?: string;
}

/**
 * Renderer-NEUTRAL visual options. Declarative only — NO echarts option trees,
 * NO raw hex colors (use design-token names per ux-design-system). Each render
 * target interprets these in its own way (or ignores unsupported ones).
 */
export interface ChartVisualOptions {
  stacked?: boolean;
  orientation?: 'vertical' | 'horizontal';
  legend?: boolean | { position?: 'top' | 'right' | 'bottom' | 'left' };
  dataLabels?: boolean;
  smooth?: boolean;
  /**
   * Fill the area under a line (line/area family). Renderer-neutral intent; the
   * echarts adapter maps it to a per-series `areaStyle`, the SVG adapter to a filled
   * polygon. SmartLineChart's `areaStyle` prop maps here.
   */
  areaFill?: boolean;
  /**
   * Base opacity of the area fill (line/area family). Renderer-neutral intent for the
   * dedicated SmartAreaChart, whose `fillOpacity` prop maps here. When SET, the echarts
   * adapter derives the per-series area opacity as `max(0.1, fillOpacity - index*0.15)`
   * (the SmartAreaChart formula). When UNSET — the SmartLineChart area-fill case, which
   * has no `fillOpacity` knob — the adapter falls back to the legacy SmartLineChart
   * gradient `0.3 - index*0.1`. The two builders are therefore byte-equivalent on their
   * own inputs (each pinned by its own B2d equivalence gate), differing only because
   * SmartAreaChart exposes this configurable base opacity and SmartLineChart does not.
   */
  fillOpacity?: number;
  /**
   * Show the data-point symbols on a line series (line/area family). Renderer-neutral
   * intent; SmartLineChart's `showSymbol` prop maps here. Defaults to shown when
   * unset (matching the legacy SmartLineChart default).
   */
  showSymbol?: boolean;
  /** Design-token names (e.g. 'accent', 'chart-1'), NOT hex. */
  colorTokens?: string[];
}

/**
 * Scatter-family options. Renderer-NEUTRAL declarative intent specific to the
 * scatter/bubble family, which has no dimension→series fan-out (its measures map onto
 * AXIS ROLES inside a single series: measures[0]→X, measures[1]→Y, measures[2]→size).
 * These do not fit `ChartVisualOptions` (axis-label text and a numeric size range are
 * scatter-specific), so they live here. They are NOT renderer-native config — axis
 * labels and a size range are abstract semantic intents each target draws its own way.
 */
export interface ChartScatterOptions {
  /** Display label for the X (value) axis (defaults to the X measure field). */
  xAxisLabel?: string;
  /** Display label for the Y (value) axis (defaults to the Y measure field). */
  yAxisLabel?: string;
  /** Size the points by `measures[2]` (bubble chart) instead of a fixed symbol size. */
  bubbleMode?: boolean;
  /** [min, max] symbol-size range used when `bubbleMode` is on. */
  symbolSizeRange?: [number, number];
}

/**
 * Interaction intent. ALL of these are screen-affordances that print targets must
 * degrade explicitly (see CAPABILITY_MATRIX / validateChartSpecForTarget).
 */
export interface ChartInteraction {
  /** Hover tooltips. */
  tooltip?: boolean;
  /** Dashboard cross-filter linkage. */
  linkage?: LinkageConfig;
  /** Auto-refresh interval (ms). */
  refreshIntervalMs?: number;
}

/**
 * The renderer-agnostic chart contract.
 *
 * Backlog shape: `{ type, dataSource, dimensions[], measures[], filters?, sort?,
 * interaction?, drilldown? }` (+ neutral `visual`, + optional `title`).
 */
export interface ChartSpec {
  type: ChartSpecType;
  title?: ChartTitle;
  /** Data binding (reused, renderer-agnostic). */
  dataSource: ChartDataSource;
  dimensions: ChartDimension[];
  measures: ChartMeasure[];
  filters?: FilterConfig[];
  sort?: OrderByConfig[];
  /** Screen-only intent; print targets degrade. */
  interaction?: ChartInteraction;
  /** Screen-only intent; print targets degrade. */
  drilldown?: DrillDownConfig;
  /** Renderer-neutral declarative visual options. */
  visual?: ChartVisualOptions;
  /** Scatter/bubble-family declarative options (axis labels, bubble sizing). */
  scatter?: ChartScatterOptions;
}

// --- render targets ----------------------------------------------------------

export type ChartRenderTargetId = 'echarts' | 'svg-print';

export type ChartCapability =
  | 'tooltip'
  | 'drilldown'
  | 'animation'
  | 'theme'
  | 'largeDataset'
  | 'linkage';

/** full = native; degrade = supported with explicit loss; unsupported = must fall back. */
export type CapabilitySupport = 'full' | 'degrade' | 'unsupported';

export interface ChartRenderTarget {
  id: ChartRenderTargetId;
  label: string;
  /** Whether the target can sample/paginate/respond at render time (screen) vs.
   * being a fixed snapshot (print). Drives unbounded-dataset blocking. */
  interactive: boolean;
  /** '*' = all ChartSpec types. */
  supportedTypes: readonly ChartSpecType[] | '*';
  capabilities: Record<ChartCapability, CapabilitySupport>;
}

/**
 * Capability matrix per render target (backlog B2 §渲染目标能力降级). The print
 * target is intentionally conservative; anything it can't draw safely must be
 * surfaced at design time (validateChartSpecForTarget) rather than exported wrong.
 */
export const CAPABILITY_MATRIX: Record<ChartRenderTargetId, ChartRenderTarget> = {
  echarts: {
    id: 'echarts',
    label: 'ECharts (interactive, screen)',
    interactive: true,
    supportedTypes: '*',
    capabilities: {
      tooltip: 'full',
      drilldown: 'full',
      animation: 'full',
      theme: 'full',
      largeDataset: 'degrade', // sampling for very large series
      linkage: 'full',
    },
  },
  'svg-print': {
    id: 'svg-print',
    label: 'SVG (print-safe, report/PDF)',
    interactive: false,
    // wordcloud/map/gantt need a print fallback (table/image snapshot) for now.
    supportedTypes: [
      'bar', 'line', 'area', 'pie', 'scatter', 'radar', 'funnel', 'gauge',
      'heatmap', 'treemap', 'spc', 'pareto', 'combo', 'nps', 'table',
    ],
    capabilities: {
      tooltip: 'unsupported', // no hover on paper
      drilldown: 'degrade', // rendered as a footnote/link, not interactive
      animation: 'unsupported',
      theme: 'degrade', // flattened to static styles
      largeDataset: 'degrade', // sample / paginate / table fallback
      linkage: 'unsupported', // no cross-filter on paper
    },
  },
};

export function getRenderTarget(id: ChartRenderTargetId): ChartRenderTarget {
  return CAPABILITY_MATRIX[id];
}

// --- renderer-agnostic guard -------------------------------------------------

/**
 * Keys that would leak a specific renderer into a ChartSpec. Their presence
 * anywhere in the object is a hard violation of the renderer-agnostic contract.
 */
export const RENDERER_LEAK_KEYS: readonly string[] = [
  'option',
  'echartsOption',
  'chartOptions',
  'series', // echarts series array
  'xAxis',
  'yAxis',
  'path', // svg path
  'printWidth',
  'svg',
];

export interface RendererLeak {
  /** Dotted path to the offending key. */
  path: string;
  key: string;
}

/**
 * Walk an arbitrary object and collect any renderer-leak keys. Used by the unit
 * test guard and (optionally) at authoring time.
 */
export function findRendererLeaks(value: unknown, path = ''): RendererLeak[] {
  const leaks: RendererLeak[] = [];
  if (value === null || typeof value !== 'object') return leaks;
  if (Array.isArray(value)) {
    value.forEach((v, i) => leaks.push(...findRendererLeaks(v, `${path}[${i}]`)));
    return leaks;
  }
  for (const [key, v] of Object.entries(value as Record<string, unknown>)) {
    const childPath = path ? `${path}.${key}` : key;
    if (RENDERER_LEAK_KEYS.includes(key)) {
      leaks.push({ path: childPath, key });
    }
    leaks.push(...findRendererLeaks(v, childPath));
  }
  return leaks;
}

/** Throws if `spec` carries any renderer-native config. */
export function assertRendererAgnostic(spec: ChartSpec): void {
  const leaks = findRendererLeaks(spec);
  if (leaks.length) {
    throw new Error(
      `ChartSpec is not renderer-agnostic: leak keys [${leaks.map((l) => l.path).join(', ')}]`,
    );
  }
}
