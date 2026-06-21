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
 * B2d (backlog 2026-06-18-designer-layout-family-convergence §B2d): the BAR branch
 * is the canonical option-builder for SmartBarChart. It is byte-equivalent to the
 * legacy `buildBarOptionLegacy` BASE option (i.e. before the `chartOptions`
 * renderer-leak prop-merge, which stays a call-site concern). The headline fix vs.
 * the old minimal mapping: bar now emits ONE SERIES PER MEASURE (the previous code
 * silently dropped every measure after `measures[0]`), plus the legacy layout/
 * cosmetic config (grid margins, `tooltip.axisPointer:'shadow'`, `xAxis.axisLabel`
 * rotation/hideOverlap, multi-measure legend, centered/styled title, and per-series
 * name/label/emphasis). The non-bar branches stay the deliberately minimal,
 * consolidation-oriented mapping.
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

export function chartSpecToEChartsOption(
  spec: ChartSpec,
  rows: Record<string, unknown>[],
): EChartsOption {
  // BAR is the canonical SmartBarChart builder (B2d): legacy-equivalent base option.
  if (spec.type === 'bar') {
    return barOption(spec, rows);
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

  // Non-bar series (line/area/scatter/...) are always category-x / value-y here;
  // bar (the only type with an `orientation` swap) is handled by `barOption` above.
  opt.xAxis = { type: 'category', data: labels };
  opt.yAxis = { type: 'value' };

  const seriesType = spec.type === 'area' ? 'line' : spec.type;
  const series: Record<string, unknown> = { type: seriesType, data: values };
  if (spec.type === 'area') series.areaStyle = {};
  if (spec.visual?.smooth) series.smooth = true;
  if (spec.visual?.stacked) series.stack = 'total';
  opt.series = [series];

  return opt;
}
