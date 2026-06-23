/**
 * B2d-area — ChartSpec→ECharts adapter ⇔ SmartAreaChart legacy builder EQUIVALENCE gate.
 *
 * backlog 2026-06-18-designer-layout-family-convergence §B2d: "consolidate SmartAreaChart
 * onto the shared ChartSpec→ECharts adapter". This is a PROVABLY-NEUTRAL refactor: the
 * adapter's line/area branch (`chartSpecToEChartsOption`, spec.type === 'area') is now the
 * canonical option-builder for SmartAreaChart too, and it must be BYTE-EQUIVALENT to the
 * legacy `buildAreaOptionLegacy` BASE option — i.e. legacy's output BEFORE the
 * `chartOptions` prop-merge (that renderer-leak stays a call-site merge in SmartAreaChart,
 * NOT baked into the adapter).
 *
 * AREA-BRANCH VERDICT (recorded by this test): SmartAreaChart is byte-equivalent to a
 * SmartLineChart area-fill EXCEPT for the per-series area opacity. SmartLineChart's
 * area-fill has no opacity knob and uses the gradient `0.3 - index*0.1`; SmartAreaChart
 * exposes a configurable `fillOpacity` prop (default 0.6) and uses
 * `max(0.1, fillOpacity - index*0.15)`. Rather than force either to change, the adapter's
 * line/area branch was EXTENDED to read the optional, renderer-neutral `visual.fillOpacity`
 * — when SET it uses SmartAreaChart's formula, when UNSET it keeps SmartLineChart's
 * gradient. A SINGLE option shape now satisfies BOTH legacy builders byte-equivalently;
 * the SmartLineChart line/area gate (chart-spec-echarts-smartlinechart-equivalence.test.ts)
 * still passes unchanged, and this gate pins the SmartAreaChart side.
 *
 * MULTI-MEASURE: SmartAreaChart already fanned out one series PER measure (legacy line
 * `metrics.map(...)`), so there was no dropped-measure bug here; the adapter's line/area
 * branch preserves that fan-out. A regression guard below pins N series for N measures.
 *
 * `buildAreaOptionLegacy` is the ORACLE (extracted verbatim from SmartAreaChart, no
 * behavior change); the adapter is `chartSpecToEChartsOption`.
 */
import { describe, expect, it } from 'vitest';
import type { ChartSpec } from '../chart-spec';
import { chartSpecToEChartsOption } from '../chart-spec-echarts';
import {
  buildAreaOptionLegacy,
  type AreaChartData,
  type AreaOptionProps,
} from '../../components/charts/SmartAreaChart';

// --- representative inputs ----------------------------------------------------

const SIMPLE_ROWS = [
  { day: 'Mon', count: 10 },
  { day: 'Tue', count: 30 },
];

const MULTI_ROWS = [
  { month: 'Jan', revenue: 5, cost: 7 },
  { month: 'Feb', revenue: 9, cost: 3 },
];

/** Three measures → exercises the floor `max(0.1, ...)` on series[2] (0.6 - 0.30 = 0.3). */
const TRIPLE_ROWS = [
  { month: 'Jan', a: 1, b: 2, c: 3 },
  { month: 'Feb', a: 4, b: 5, c: 6 },
];

/** Many categories → exercises the legacy axisLabel rotate>10 branch. */
const MANY_ROWS = Array.from({ length: 12 }, (_, i) => ({ day: `d${i}`, count: i }));

/** A live-SmartAreaChart `data` object (mirrors what useChartData returns). */
function areaData(
  rows: Record<string, unknown>[],
  dimensions: string[],
  metrics: string[],
): AreaChartData {
  return { rows, meta: { dimensions, metrics } };
}

/**
 * Build a ChartSpec the way SmartAreaChart's `specFromAreaChartData` does, for the same
 * inputs the SmartAreaChart `data.meta` describes. SmartAreaChart is always area-filled,
 * so the spec type is `'area'`, `visual.areaFill` is always true, and the configurable
 * base opacity is carried on `visual.fillOpacity` (defaulting to 0.6).
 */
function specFromArea(opts: {
  dimensions: string[];
  metrics: string[];
  title?: string;
  smooth?: boolean;
  fillOpacity?: number;
  showSymbol?: boolean;
  dataLabels?: boolean;
}): ChartSpec {
  const {
    dimensions,
    metrics,
    title,
    smooth = true,
    fillOpacity = 0.6,
    showSymbol = true,
    dataLabels = false,
  } = opts;
  return {
    type: 'area',
    title,
    dataSource: {
      type: 'aggregate',
      modelCode: 'm',
      dimensions,
      metrics: metrics.map((field) => ({ field, aggregation: 'sum' })),
    },
    dimensions: dimensions.map((field, i) => ({
      field,
      role: i === 0 ? 'category' : 'series',
    })),
    measures: metrics.map((field) => ({ field, aggregation: 'sum' as const })),
    interaction: { tooltip: true },
    visual: { smooth, areaFill: true, fillOpacity, showSymbol, dataLabels },
  };
}

/** The legacy BASE option = legacy output with NO `chartOptions` prop-merge. */
function legacyBase(data: AreaChartData, props: Omit<AreaOptionProps, 'chartOptions'> = {}) {
  return buildAreaOptionLegacy(data, { ...props, chartOptions: undefined });
}

describe('B2d-area ChartSpec→ECharts adapter ⇔ SmartAreaChart legacy builder', () => {
  // ---------------------------------------------------------------------------
  // Sanity: the extracted legacy helper still reflects SmartAreaChart's behavior.
  // (Characterizes the ORACLE — must NOT change.)
  // ---------------------------------------------------------------------------
  describe('legacy builder characterization (must NOT change — this is the oracle)', () => {
    it('simple area: cross axisPointer + boundaryGap:false + single area series', () => {
      const legacy = buildAreaOptionLegacy(areaData(SIMPLE_ROWS, ['day'], ['count']), {}) as any;

      expect(legacy.tooltip).toEqual({ trigger: 'axis', axisPointer: { type: 'cross' } });
      expect(legacy.grid).toBeDefined();
      expect(legacy.xAxis.type).toBe('category');
      expect(legacy.xAxis.data).toEqual(['Mon', 'Tue']);
      expect(legacy.xAxis.boundaryGap).toBe(false);
      expect(legacy.xAxis.axisLabel).toEqual({ rotate: 0, hideOverlap: true });
      expect(legacy.yAxis.type).toBe('value');
      expect(legacy.series).toHaveLength(1);
      expect(legacy.series[0]).toMatchObject({
        name: 'count',
        type: 'line',
        data: [10, 30],
        smooth: true, // SmartAreaChart default smooth = true
        showSymbol: true,
        symbol: 'circle',
        symbolSize: 6,
        emphasis: { focus: 'series' },
      });
      // Area chart always fills; default fillOpacity 0.6 → series[0] opacity 0.6.
      expect(legacy.series[0].areaStyle).toEqual({ opacity: 0.6 });
      expect(legacy.series[0].label).toEqual({ show: false, position: 'top' });
    });

    it('multiple measures: ONE SERIES PER MEASURE + legend + per-index opacity', () => {
      const legacy = buildAreaOptionLegacy(
        areaData(MULTI_ROWS, ['month'], ['revenue', 'cost']),
        {},
      ) as any;

      expect(legacy.series).toHaveLength(2);
      expect(legacy.series.map((s: any) => s.name)).toEqual(['revenue', 'cost']);
      expect(legacy.series[0].data).toEqual([5, 9]);
      expect(legacy.series[1].data).toEqual([7, 3]);
      // SmartAreaChart formula: max(0.1, fillOpacity - index*0.15), fillOpacity 0.6.
      expect(legacy.series[0].areaStyle).toEqual({ opacity: 0.6 });
      expect(legacy.series[1].areaStyle).toEqual({ opacity: Math.max(0.1, 0.6 - 0.15) });
      expect(legacy.legend).toEqual({ bottom: 0, type: 'scroll' });
    });

    it('opacity floor: many series clamp at 0.1 (NOT SmartLineChart 0.3-index*0.1)', () => {
      const legacy = buildAreaOptionLegacy(
        areaData(TRIPLE_ROWS, ['month'], ['a', 'b', 'c']),
        { fillOpacity: 0.3 },
      ) as any;
      // 0.3 - 0*0.15 = 0.3 ; 0.3 - 0.15 = 0.15 ; max(0.1, 0.3 - 0.30 = 0.0) = 0.1
      expect(legacy.series[0].areaStyle.opacity).toBeCloseTo(0.3, 10);
      expect(legacy.series[1].areaStyle.opacity).toBeCloseTo(0.15, 10);
      expect(legacy.series[2].areaStyle.opacity).toBe(0.1);
    });
  });

  // ---------------------------------------------------------------------------
  // EQUIVALENCE GATE: adapter area branch === legacy BASE option, deep-equal.
  // The adapter must NOT bake in `chartOptions` (that's a call-site merge), so we
  // compare against legacyBase() (legacy sans chartOptions).
  // ---------------------------------------------------------------------------
  describe('FULL BYTE-EQUIVALENCE (adapter === legacy base)', () => {
    it('simple area (single measure, default fillOpacity 0.6)', () => {
      const rows = SIMPLE_ROWS;
      const adapted = chartSpecToEChartsOption(
        specFromArea({ dimensions: ['day'], metrics: ['count'] }),
        rows,
      );
      const legacy = legacyBase(areaData(rows, ['day'], ['count']), {});
      expect(adapted).toEqual(legacy);
    });

    it('MULTI-measure area: adapter emits N series, deep-equal to legacy (per-index opacity)', () => {
      const rows = MULTI_ROWS;
      const adapted = chartSpecToEChartsOption(
        specFromArea({ dimensions: ['month'], metrics: ['revenue', 'cost'] }),
        rows,
      ) as any;
      const legacy = legacyBase(areaData(rows, ['month'], ['revenue', 'cost']), {}) as any;

      // Regression guard: BOTH measures present (no dropped-measure bug).
      expect(adapted.series).toHaveLength(2);
      expect(adapted.series.map((s: any) => s.name)).toEqual(['revenue', 'cost']);
      // SmartAreaChart opacity formula preserved end-to-end (NOT the line 0.3 gradient).
      expect(adapted.series[0].areaStyle).toEqual({ opacity: 0.6 });
      expect(adapted.series[1].areaStyle).toEqual({ opacity: Math.max(0.1, 0.6 - 0.15) });
      expect(adapted).toEqual(legacy);
    });

    it('THREE measures (regression guard: N series, opacity floor at 0.1)', () => {
      const rows = TRIPLE_ROWS;
      const adapted = chartSpecToEChartsOption(
        specFromArea({ dimensions: ['month'], metrics: ['a', 'b', 'c'], fillOpacity: 0.3 }),
        rows,
      ) as any;
      const legacy = legacyBase(areaData(rows, ['month'], ['a', 'b', 'c']), {
        fillOpacity: 0.3,
      }) as any;
      expect(adapted.series).toHaveLength(3);
      expect(adapted.series.map((s: any) => s.name)).toEqual(['a', 'b', 'c']);
      expect(adapted).toEqual(legacy);
    });

    it('non-smooth area (smooth:false)', () => {
      const rows = SIMPLE_ROWS;
      const adapted = chartSpecToEChartsOption(
        specFromArea({ dimensions: ['day'], metrics: ['count'], smooth: false }),
        rows,
      ) as any;
      const legacy = legacyBase(areaData(rows, ['day'], ['count']), { smooth: false }) as any;
      expect(adapted.series[0].smooth).toBe(false);
      expect(adapted).toEqual(legacy);
    });

    it('custom fillOpacity (0.9)', () => {
      const rows = SIMPLE_ROWS;
      const adapted = chartSpecToEChartsOption(
        specFromArea({ dimensions: ['day'], metrics: ['count'], fillOpacity: 0.9 }),
        rows,
      ) as any;
      const legacy = legacyBase(areaData(rows, ['day'], ['count']), { fillOpacity: 0.9 }) as any;
      expect(adapted.series[0].areaStyle).toEqual({ opacity: 0.9 });
      expect(adapted).toEqual(legacy);
    });

    it('showSymbol:false hides data points', () => {
      const rows = SIMPLE_ROWS;
      const adapted = chartSpecToEChartsOption(
        specFromArea({ dimensions: ['day'], metrics: ['count'], showSymbol: false }),
        rows,
      ) as any;
      const legacy = legacyBase(areaData(rows, ['day'], ['count']), { showSymbol: false }) as any;
      expect(adapted.series[0].showSymbol).toBe(false);
      expect(adapted).toEqual(legacy);
    });

    it('titled (centered + styled title, grid top 15%)', () => {
      const rows = SIMPLE_ROWS;
      const adapted = chartSpecToEChartsOption(
        specFromArea({ dimensions: ['day'], metrics: ['count'], title: 'Daily Orders' }),
        rows,
      );
      const legacy = legacyBase(areaData(rows, ['day'], ['count']), { title: 'Daily Orders' });
      expect(adapted).toEqual(legacy);
    });

    it('with data labels (visual.dataLabels → series.label.show)', () => {
      const rows = SIMPLE_ROWS;
      const adapted = chartSpecToEChartsOption(
        specFromArea({ dimensions: ['day'], metrics: ['count'], dataLabels: true }),
        rows,
      );
      const legacy = legacyBase(areaData(rows, ['day'], ['count']), { showLabel: true });
      expect(adapted).toEqual(legacy);
    });

    it('many categories → axisLabel rotate 45', () => {
      const rows = MANY_ROWS;
      const adapted = chartSpecToEChartsOption(
        specFromArea({ dimensions: ['day'], metrics: ['count'] }),
        rows,
      ) as any;
      const legacy = legacyBase(areaData(rows, ['day'], ['count']), {}) as any;
      expect(adapted.xAxis.axisLabel).toEqual({ rotate: 45, hideOverlap: true });
      expect(adapted).toEqual(legacy);
    });

    it('empty rows (degenerate option: empty series + empty-data xAxis, bare value yAxis)', () => {
      const adapted = chartSpecToEChartsOption(
        specFromArea({ dimensions: ['day'], metrics: ['count'] }),
        [],
      );
      const legacy = legacyBase(areaData([], ['day'], ['count']), {});
      expect(adapted).toEqual(legacy);
    });

    it('empty rows, titled (empty-title fontSize-only)', () => {
      const adapted = chartSpecToEChartsOption(
        specFromArea({ dimensions: ['day'], metrics: ['count'], title: 'T' }),
        [],
      );
      const legacy = legacyBase(areaData([], ['day'], ['count']), { title: 'T' });
      expect(adapted).toEqual(legacy);
    });
  });

  // ---------------------------------------------------------------------------
  // SmartAreaChart and SmartLineChart area-fill DIFFER ONLY in area opacity — this
  // pins that the adapter keeps them distinct (no accidental convergence onto the
  // line gradient when fillOpacity is set).
  // ---------------------------------------------------------------------------
  describe('SmartAreaChart opacity is distinct from SmartLineChart area-fill', () => {
    it('area spec WITH fillOpacity ≠ line area-fill gradient on series[0]', () => {
      const rows = MULTI_ROWS;
      const areaSpec = specFromArea({ dimensions: ['month'], metrics: ['revenue', 'cost'] });
      const adaptedArea = chartSpecToEChartsOption(areaSpec, rows) as any;
      // SmartAreaChart: 0.6 / 0.45 ; SmartLineChart area-fill gradient would be 0.3 / 0.2.
      expect(adaptedArea.series[0].areaStyle.opacity).toBe(0.6);
      expect(adaptedArea.series[1].areaStyle.opacity).toBeCloseTo(0.45, 10);
      expect(adaptedArea.series[0].areaStyle.opacity).not.toBe(0.3);
    });
  });

  // ---------------------------------------------------------------------------
  // The `chartOptions` renderer-leak is NOT the adapter's job: it stays a call-site
  // merge. Confirm the adapter never carries it, while the legacy WITH chartOptions
  // is reproduced by merging at the call site exactly as SmartAreaChart does.
  // ---------------------------------------------------------------------------
  describe('chartOptions stays a call-site merge (not baked into the adapter)', () => {
    it('adapter base + call-site spread === legacy WITH chartOptions', () => {
      const rows = SIMPLE_ROWS;
      const chartOptions = { backgroundColor: '#fff', animation: false } as any;
      const adapterBase = chartSpecToEChartsOption(
        specFromArea({ dimensions: ['day'], metrics: ['count'] }),
        rows,
      );
      // Mirrors SmartAreaChart's call-site merge: chartOptions ? {...base, ...chartOptions} : base
      const merged = { ...adapterBase, ...chartOptions };
      const legacyWithOptions = buildAreaOptionLegacy(areaData(rows, ['day'], ['count']), {
        chartOptions,
      });
      expect(merged).toEqual(legacyWithOptions);
    });
  });
});
