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

export function chartSpecToEChartsOption(
  spec: ChartSpec,
  rows: Record<string, unknown>[],
): EChartsOption {
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

  const horizontal = spec.type === 'bar' && spec.visual?.orientation === 'horizontal';
  const categoryAxis = { type: 'category', data: labels };
  const valueAxis = { type: 'value' };
  if (horizontal) {
    opt.xAxis = valueAxis;
    opt.yAxis = categoryAxis;
  } else {
    opt.xAxis = categoryAxis;
    opt.yAxis = valueAxis;
  }

  const seriesType = spec.type === 'area' ? 'line' : spec.type;
  const series: Record<string, unknown> = { type: seriesType, data: values };
  if (spec.type === 'area') series.areaStyle = {};
  if (spec.visual?.smooth) series.smooth = true;
  if (spec.visual?.stacked) series.stack = 'total';
  opt.series = [series];

  return opt;
}
