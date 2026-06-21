/**
 * B2b — ChartSpec → ECharts option adapter tests.
 *
 * The echarts (interactive, screen) render target. Produces a standard ECharts
 * `option` object from a renderer-agnostic ChartSpec + data rows. The OUTPUT is
 * renderer-specific (it legitimately contains series/xAxis/tooltip) — only the
 * INPUT ChartSpec must stay renderer-agnostic.
 */
import { describe, expect, it } from 'vitest';
import type { ChartSpec } from '../chart-spec';
import { chartSpecToEChartsOption } from '../chart-spec-echarts';

const rows = [
  { category: 'A', amount: 10 },
  { category: 'B', amount: 30 },
];

function spec(overrides: Partial<ChartSpec> = {}): ChartSpec {
  return {
    type: 'bar',
    dataSource: { type: 'static', staticData: rows },
    dimensions: [{ field: 'category', role: 'category' }],
    measures: [{ field: 'amount', aggregation: 'sum' }],
    ...overrides,
  };
}

describe('chartSpecToEChartsOption', () => {
  it('maps a bar chart to category xAxis + value yAxis + bar series', () => {
    const opt = chartSpecToEChartsOption(spec(), rows) as any;
    expect(opt.series[0].type).toBe('bar');
    expect(opt.xAxis.type).toBe('category');
    expect(opt.xAxis.data).toEqual(['A', 'B']);
    expect(opt.yAxis.type).toBe('value');
    expect(opt.series[0].data).toEqual([10, 30]);
  });

  it('swaps axes for a horizontal bar (value xAxis, category yAxis)', () => {
    const opt = chartSpecToEChartsOption(spec({ visual: { orientation: 'horizontal' } }), rows) as any;
    expect(opt.xAxis.type).toBe('value');
    expect(opt.yAxis.type).toBe('category');
    expect(opt.yAxis.data).toEqual(['A', 'B']);
  });

  it('maps a pie chart to name/value data objects', () => {
    const opt = chartSpecToEChartsOption(
      spec({ type: 'pie', dimensions: [{ field: 'category', role: 'name' }] }),
      rows,
    ) as any;
    expect(opt.series[0].type).toBe('pie');
    expect(opt.series[0].data).toEqual([
      { name: 'A', value: 10 },
      { name: 'B', value: 30 },
    ]);
  });

  it('maps area to a line series with areaStyle (legacy-faithful SmartLineChart branch)', () => {
    // As of B2d, LINE/AREA is the legacy-faithful SmartLineChart builder (one series
    // per measure, smooth/showSymbol/symbol, cross axisPointer, boundaryGap:false, etc.);
    // its full contract is pinned by chart-spec-echarts-smartlinechart-equivalence.test.ts.
    // Here we just confirm the high-level routing: area → line series carrying an areaStyle.
    const opt = chartSpecToEChartsOption(spec({ type: 'area' }), rows) as any;
    expect(opt.series[0].type).toBe('line');
    expect(opt.series[0].areaStyle).toBeDefined();
  });

  it('encodes interaction.tooltip into option.tooltip (minimal non-bar/non-line branch)', () => {
    // The minimal mapping gates tooltip on interaction. NOTE: the BAR and LINE/AREA
    // branches are the legacy-faithful SmartBarChart / SmartLineChart builders (B2d)
    // which ALWAYS emit tooltip — their tooltip contracts are pinned by their own
    // equivalence tests. So this case uses 'scatter' to exercise the minimal mapping.
    const withTip = chartSpecToEChartsOption(
      spec({ type: 'scatter', interaction: { tooltip: true } }),
      rows,
    ) as any;
    expect(withTip.tooltip).toBeDefined();
    const noTip = chartSpecToEChartsOption(spec({ type: 'scatter' }), rows) as any;
    expect(noTip.tooltip).toBeUndefined();
  });

  it('encodes visual.legend and visual.stacked (minimal non-bar/non-line branch)', () => {
    // Legend on the minimal branch is gated on visual.legend. The BAR and LINE/AREA
    // branches instead emit a legend only for multi-measure (legacy parity), pinned by
    // their B2d equivalence tests — so this case uses 'scatter' for the minimal mapping.
    const opt = chartSpecToEChartsOption(
      spec({ type: 'scatter', visual: { legend: true, stacked: true } }),
      rows,
    ) as any;
    expect(opt.legend).toBeDefined();
    expect(opt.series[0].stack).toBeTruthy();
  });

  it('encodes the title', () => {
    const opt = chartSpecToEChartsOption(spec({ title: 'Sales' }), rows) as any;
    expect(opt.title.text).toBe('Sales');
  });
});
