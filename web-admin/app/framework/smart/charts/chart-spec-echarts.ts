/**
 * ChartSpec → ECharts option adapter (B2b).
 *
 * DDR-2026-06-18 / backlog 2026-06-18 §B2b "echarts renderer adapter (interactive,
 * screen)". Maps a renderer-agnostic ChartSpec + data rows to a standard ECharts
 * `option`. The OUTPUT is renderer-specific by design (it contains series / xAxis /
 * tooltip — the very keys forbidden INSIDE a ChartSpec); only the input ChartSpec
 * stays agnostic.
 *
 * Pure: builds a plain option object; it does not import or construct echarts.
 *
 * B2d (backlog 2026-06-18-designer-layout-family-convergence §B2d): the BAR, the
 * LINE/AREA and the SCATTER branches are the canonical option-builders for
 * SmartBarChart, SmartLineChart and SmartScatterChart respectively. Each is equivalent
 * to its legacy builder's BASE option (`buildBarOptionLegacy` / `buildLineOptionLegacy`
 * / `buildScatterOptionLegacy`), i.e. before the `chartOptions` renderer-leak prop-merge
 * (which stays a call-site concern). For bar/line the headline fix vs. the old minimal
 * mapping is identical: they now emit ONE SERIES PER MEASURE (the previous code silently
 * dropped every measure after `measures[0]`), plus the legacy layout/cosmetic config
 * (grid margins, the type-specific `tooltip.axisPointer` — `'shadow'` for bar, `'cross'`
 * for line —, `xAxis.axisLabel` rotation/hideOverlap, line's `xAxis.boundaryGap:false`
 * and per-series smooth/showSymbol/areaStyle, multi-measure legend, centered/styled
 * title, and per-series name/label/emphasis). SCATTER is structurally different: its
 * measures are AXIS ROLES (X / Y / size) inside a SINGLE series, so there is no
 * measure-drop bug; the scatter branch instead recovers the legacy value-vs-value axes,
 * dashed splitLines, item tooltip `formatter`, and bubble-mode `symbolSize` (both
 * FUNCTIONS reproduced byte-for-byte). The remaining non-bar/non-line/non-scatter
 * branches stay the deliberately minimal, consolidation-oriented mapping.
 */
import type { ChartSpec } from './chart-spec';

export type EChartsOption = Record<string, unknown>;

function dimField(spec: ChartSpec, role: 'category' | 'name'): string | undefined {
  return spec.dimensions.find((d) => d.role === role)?.field ?? spec.dimensions[0]?.field;
}

function titleText(spec: ChartSpec): string | undefined {
  if (!spec.title) return undefined;
  return typeof spec.title === 'string' ? spec.title : Object.values(spec.title)[0];
}

/**
 * BAR branch — byte-equivalent to SmartBarChart's legacy `buildBarOptionLegacy`
 * BASE option (sans the `chartOptions` prop-merge, which stays a call-site merge).
 * Must match legacy EXACTLY; covered by the B2d equivalence test.
 */
function barOption(spec: ChartSpec, rows: Record<string, unknown>[]): EChartsOption {
  const title = titleText(spec);
  const orientation = spec.visual?.orientation ?? 'vertical';
  const stacked = spec.visual?.stacked ?? false;
  const showLabel = spec.visual?.dataLabels ?? false;
  // Series name + values keyed by the measure field, mirroring legacy `data.meta.metrics`.
  const metrics = spec.measures.map((m) => m.field);

  // Empty data → the legacy degenerate option (note: title here carries fontSize
  // only, NO fontWeight; both axes carry an empty `data:[]`; no tooltip/grid/legend).
  if (!rows.length) {
    return {
      title: title ? { text: title, left: 'center', textStyle: { fontSize: 14 } } : undefined,
      xAxis: { type: orientation === 'vertical' ? 'category' : 'value', data: [] },
      yAxis: { type: orientation === 'vertical' ? 'value' : 'category', data: [] },
      series: [],
    };
  }

  const dimensionKey = dimField(spec, 'category');
  const categories = rows.map((row) => String(row[dimensionKey as string] ?? ''));

  const series = metrics.map((metricKey) => ({
    name: metricKey,
    type: 'bar' as const,
    stack: stacked ? 'total' : undefined,
    data: rows.map((row) => Number(row[metricKey]) || 0),
    label: {
      show: showLabel,
      position: (orientation === 'vertical' ? 'top' : 'right') as 'top' | 'right',
    },
    emphasis: {
      focus: 'series' as const,
    },
  }));

  return {
    title: title
      ? {
          text: title,
          left: 'center',
          textStyle: { fontSize: 14, fontWeight: 500 },
        }
      : undefined,
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
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
    xAxis:
      orientation === 'vertical'
        ? {
            type: 'category',
            data: categories,
            axisLabel: {
              rotate: categories.length > 10 ? 45 : 0,
              hideOverlap: true,
            },
          }
        : {
            type: 'value',
          },
    yAxis:
      orientation === 'vertical'
        ? {
            type: 'value',
          }
        : {
            type: 'category',
            data: categories,
          },
    series,
  };
}

/**
 * LINE / AREA branch — byte-equivalent to SmartLineChart's legacy
 * `buildLineOptionLegacy` BASE option (sans the `chartOptions` prop-merge, which stays
 * a call-site merge). Must match legacy EXACTLY; covered by the B2d line equivalence
 * test.
 *
 * SmartLineChart renders BOTH plain lines and area-filled lines via a single component
 * (its `areaStyle` boolean prop), so `area` is handled here too. Area-fill is carried
 * by the renderer-neutral `visual.areaFill` flag (← legacy `areaStyle` prop), NOT by
 * `spec.type` — so a `line`-typed spec with `visual.areaFill` and an `area`-typed spec
 * produce the same area option, matching the single legacy builder.
 */
function lineOption(spec: ChartSpec, rows: Record<string, unknown>[]): EChartsOption {
  const title = titleText(spec);
  const smooth = spec.visual?.smooth ?? false;
  // Area fill: either the explicit neutral flag, or an `area`-typed spec.
  const areaFill = spec.visual?.areaFill ?? spec.type === 'area';
  // showSymbol defaults to true (legacy SmartLineChart default), only false when set so.
  const showSymbol = spec.visual?.showSymbol ?? true;
  const showLabel = spec.visual?.dataLabels ?? false;
  // Series name + values keyed by the measure field, mirroring legacy `data.meta.metrics`.
  const metrics = spec.measures.map((m) => m.field);

  // Empty data → the legacy degenerate option (note: title here carries fontSize only,
  // NO fontWeight; xAxis carries an empty `data:[]`, yAxis is a bare value axis with NO
  // `data` key; no tooltip/grid/legend).
  if (!rows.length) {
    return {
      title: title ? { text: title, left: 'center', textStyle: { fontSize: 14 } } : undefined,
      xAxis: { type: 'category', data: [] },
      yAxis: { type: 'value' },
      series: [],
    };
  }

  const dimensionKey = dimField(spec, 'category');
  const categories = rows.map((row) => String(row[dimensionKey as string] ?? ''));

  const series = metrics.map((metricKey, index) => ({
    name: metricKey,
    type: 'line' as const,
    data: rows.map((row) => Number(row[metricKey]) || 0),
    smooth,
    showSymbol,
    symbol: 'circle' as const,
    symbolSize: 6,
    areaStyle: areaFill
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

  return {
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
}

/**
 * SCATTER / BUBBLE branch — equivalent to SmartScatterChart's legacy
 * `buildScatterOptionLegacy` BASE option (sans the `chartOptions` prop-merge, which
 * stays a call-site merge). Covered by the B2d scatter equivalence test.
 *
 * Unlike bar/line (one series PER measure), scatter maps measures onto AXIS ROLES in a
 * SINGLE series: `measures[0]` → X, `measures[1]` → Y (falling back to `measures[0]`),
 * `measures[2]` → bubble size; the category dimension is the per-point label. So there
 * is NO multi-measure "drop" to fix — the measures were never independent series.
 *
 * The X/Y axes are value-vs-value (`type:'value'`, not category), each carrying a `name`
 * (← scatter.xAxisLabel/yAxisLabel, defaulting to the measure field) and a dashed
 * `splitLine`. `tooltip.formatter` and (bubble mode) the series `symbolSize` are
 * FUNCTIONS that close over the axis labels / data rows — they are reproduced here
 * byte-for-byte from the legacy source (the equivalence gate compares the non-function
 * structure deep-equal and proves the closures behave identically).
 */
function scatterOption(spec: ChartSpec, rows: Record<string, unknown>[]): EChartsOption {
  const title = titleText(spec);
  const xAxisLabel = spec.scatter?.xAxisLabel;
  const yAxisLabel = spec.scatter?.yAxisLabel;
  const bubbleMode = spec.scatter?.bubbleMode ?? false;
  const symbolSizeRange = spec.scatter?.symbolSizeRange ?? [10, 60];
  // Measure roles, mirroring legacy `data.meta.metrics`.
  const metrics = spec.measures.map((m) => m.field);

  // Empty data → the legacy degenerate option (title carries fontSize ONLY, no
  // fontWeight; both axes are bare value axes; the series is a single empty scatter —
  // NOT an empty series[] array, unlike bar/line).
  if (!rows.length) {
    return {
      title: title ? { text: title, left: 'center', textStyle: { fontSize: 14 } } : undefined,
      xAxis: { type: 'value' },
      yAxis: { type: 'value' },
      series: [{ type: 'scatter', data: [] }],
    };
  }

  const dimensions = spec.dimensions.map((d) => d.field);
  const xKey = metrics[0];
  const yKey = metrics[1] || metrics[0];
  const sizeKey = metrics[2];
  const labelKey = dimensions[0];

  // Calculate size range for bubble mode
  let maxSize = 1;
  if (bubbleMode && sizeKey) {
    maxSize = Math.max(...rows.map((r) => Number(r[sizeKey]) || 0), 1);
  }

  const scatterData = rows.map((row) => {
    const point: (number | string)[] = [Number(row[xKey]) || 0, Number(row[yKey]) || 0];
    if (labelKey) point.push(String(row[labelKey] ?? ''));
    return point;
  });

  return {
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
                    rows.find((r) => Number(r[xKey]) === val[0] && Number(r[yKey]) === val[1])?.[
                      sizeKey
                    ],
                  ) || 0;
                return (
                  symbolSizeRange[0] + (size / maxSize) * (symbolSizeRange[1] - symbolSizeRange[0])
                );
              }
            : 14,
        emphasis: { itemStyle: { shadowBlur: 10, shadowColor: 'rgba(0,0,0,0.3)' } },
      },
    ],
  };
}

export function chartSpecToEChartsOption(
  spec: ChartSpec,
  rows: Record<string, unknown>[],
): EChartsOption {
  // BAR is the canonical SmartBarChart builder (B2d): legacy-equivalent base option.
  if (spec.type === 'bar') {
    return barOption(spec, rows);
  }

  // LINE / AREA is the canonical SmartLineChart builder (B2d): legacy-equivalent base.
  if (spec.type === 'line' || spec.type === 'area') {
    return lineOption(spec, rows);
  }

  // SCATTER is the canonical SmartScatterChart builder (B2d): legacy-equivalent base.
  if (spec.type === 'scatter') {
    return scatterOption(spec, rows);
  }

  const labelField = dimField(spec, spec.type === 'pie' ? 'name' : 'category');
  const valueField = spec.measures[0]?.field;
  const labels = rows.map((r) => (labelField ? String(r[labelField] ?? '') : ''));
  const values = rows.map((r) => (valueField ? Number(r[valueField] ?? 0) || 0 : 0));

  const opt: EChartsOption = {};
  const title = titleText(spec);
  if (title) opt.title = { text: title };
  if (spec.interaction?.tooltip) {
    opt.tooltip = { trigger: spec.type === 'pie' ? 'item' : 'axis' };
  }
  if (spec.visual?.legend) {
    const legend = spec.visual.legend;
    opt.legend = typeof legend === 'object' ? legend : {};
  }

  if (spec.type === 'pie') {
    opt.series = [
      {
        type: 'pie',
        data: rows.map((r, i) => ({ name: labels[i], value: values[i] })),
      },
    ];
    return opt;
  }

  // Remaining minimal series (radar/funnel/heatmap/...) are always category-x /
  // value-y here; bar (the only type with an `orientation` swap) is handled by
  // `barOption`, line/area by `lineOption`, and scatter by `scatterOption`, above — so
  // `spec.type` here is none of bar / line / area / scatter / pie.
  opt.xAxis = { type: 'category', data: labels };
  opt.yAxis = { type: 'value' };

  const series: Record<string, unknown> = { type: spec.type, data: values };
  if (spec.visual?.smooth) series.smooth = true;
  if (spec.visual?.stacked) series.stack = 'total';
  opt.series = [series];

  return opt;
}
