/**
 * B2d — ChartSpec→ECharts adapter ⇔ SmartLineChart legacy builder EQUIVALENCE gate.
 *
 * backlog 2026-06-18-designer-layout-family-convergence §B2d: "wire the ChartSpec→
 * ECharts adapter into SmartLineChart". This is a PROVABLY-NEUTRAL refactor: the
 * adapter's line/area branch (`chartSpecToEChartsOption`, spec.type === 'line' | 'area')
 * is now the canonical option-builder, and it must be BYTE-EQUIVALENT to the legacy
 * `buildLineOptionLegacy` BASE option — i.e. legacy's output BEFORE the `chartOptions`
 * prop-merge (that renderer-leak stays a call-site merge in SmartLineChart, NOT baked
 * into the adapter).
 *
 * VERDICT (recorded by this test): FULL BYTE-EQUIVALENCE achieved. For every
 * representative line input (single line, MULTI-line, smooth, area-fill, no-symbol,
 * empty, titled, with-labels, many-categories) the adapter deep-equals the legacy base.
 * The headline fix this unblocks: the adapter previously rendered `measures[0]` ONLY
 * (silently dropping every other measure — same family of gaps the bar branch had); it
 * now emits one series per measure, matching legacy.
 *
 * `buildLineOptionLegacy` is the ORACLE (extracted verbatim from SmartLineChart, no
 * behavior change); the adapter is `chartSpecToEChartsOption`.
 */
import { describe, expect, it } from 'vitest';
import type { ChartSpec } from '../chart-spec';
import { chartSpecToEChartsOption } from '../chart-spec-echarts';
import {
  buildLineOptionLegacy,
  type LineChartData,
  type LineOptionProps,
} from '../../components/charts/SmartLineChart';

// --- representative inputs ----------------------------------------------------

const SIMPLE_ROWS = [
  { day: 'Mon', count: 10 },
  { day: 'Tue', count: 30 },
];

const MULTI_ROWS = [
  { month: 'Jan', revenue: 5, cost: 7 },
  { month: 'Feb', revenue: 9, cost: 3 },
];

/** Many categories → exercises the legacy axisLabel rotate>10 branch. */
const MANY_ROWS = Array.from({ length: 12 }, (_, i) => ({ day: `d${i}`, count: i }));

/** A live-SmartLineChart `data` object (mirrors what useChartData returns). */
function lineData(
  rows: Record<string, unknown>[],
  dimensions: string[],
  metrics: string[],
): LineChartData {
  return { rows, meta: { dimensions, metrics } };
}

/**
 * Build a ChartSpec the way the chart authoring surface would, for the same inputs the
 * SmartLineChart `data.meta` describes. Renderer-neutral visual intent (smooth/areaFill/
 * showSymbol/dataLabels) maps onto the adapter's line branch. Area-fill is carried by the
 * neutral `visual.areaFill` flag (← the legacy `areaStyle` prop), NOT by `spec.type`, so
 * the type stays `'line'` — exactly as SmartLineChart's `specFromLineChartData` does.
 */
function specFromLine(opts: {
  dimensions: string[];
  metrics: string[];
  title?: string;
  smooth?: boolean;
  areaFill?: boolean;
  showSymbol?: boolean;
  dataLabels?: boolean;
}): ChartSpec {
  const { dimensions, metrics, title, smooth, areaFill, showSymbol, dataLabels } = opts;
  return {
    type: 'line',
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
    visual: { smooth, areaFill, showSymbol, dataLabels },
  };
}

/** The legacy BASE option = legacy output with NO `chartOptions` prop-merge. */
function legacyBase(data: LineChartData, props: Omit<LineOptionProps, 'chartOptions'> = {}) {
  return buildLineOptionLegacy(data, { ...props, chartOptions: undefined });
}

describe('B2d ChartSpec→ECharts adapter ⇔ SmartLineChart legacy builder', () => {
  // ---------------------------------------------------------------------------
  // Sanity: the extracted legacy helper still reflects SmartLineChart's behavior.
  // (Characterizes the ORACLE — must NOT change.)
  // ---------------------------------------------------------------------------
  describe('legacy builder characterization (must NOT change — this is the oracle)', () => {
    it('simple line: cross axisPointer + boundaryGap:false + single series w/ symbol', () => {
      const legacy = buildLineOptionLegacy(lineData(SIMPLE_ROWS, ['day'], ['count']), {}) as any;

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
        smooth: false,
        showSymbol: true,
        symbol: 'circle',
        symbolSize: 6,
        emphasis: { focus: 'series' },
      });
      expect(legacy.series[0].areaStyle).toBeUndefined();
      expect(legacy.series[0].label).toEqual({ show: false, position: 'top' });
    });

    it('multiple measures: ONE SERIES PER MEASURE + legend appears', () => {
      const legacy = buildLineOptionLegacy(lineData(MULTI_ROWS, ['month'], ['revenue', 'cost']), {
        smooth: true,
        areaStyle: true,
      }) as any;

      expect(legacy.series).toHaveLength(2);
      expect(legacy.series.map((s: any) => s.name)).toEqual(['revenue', 'cost']);
      expect(legacy.series[0].data).toEqual([5, 9]);
      expect(legacy.series[1].data).toEqual([7, 3]);
      expect(legacy.series[0].smooth).toBe(true);
      // Gradient opacity per area index: 0.3 - index*0.1
      expect(legacy.series[0].areaStyle).toEqual({ opacity: 0.3 });
      expect(legacy.series[1].areaStyle).toEqual({ opacity: 0.3 - 0.1 });
      expect(legacy.legend).toEqual({ bottom: 0, type: 'scroll' });
    });
  });

  // ---------------------------------------------------------------------------
  // EQUIVALENCE GATE: adapter line/area branch === legacy BASE option, deep-equal.
  // The adapter must NOT bake in `chartOptions` (that's a call-site merge), so we
  // compare against legacyBase() (legacy sans chartOptions).
  // ---------------------------------------------------------------------------
  describe('FULL BYTE-EQUIVALENCE (adapter === legacy base)', () => {
    it('simple line (single measure)', () => {
      const rows = SIMPLE_ROWS;
      const adapted = chartSpecToEChartsOption(
        specFromLine({ dimensions: ['day'], metrics: ['count'] }),
        rows,
      );
      const legacy = legacyBase(lineData(rows, ['day'], ['count']), {});
      expect(adapted).toEqual(legacy);
    });

    it('MULTI-LINE (headline fix): adapter now emits N series, deep-equal to legacy', () => {
      const rows = MULTI_ROWS;
      const adapted = chartSpecToEChartsOption(
        specFromLine({ dimensions: ['month'], metrics: ['revenue', 'cost'] }),
        rows,
      ) as any;
      const legacy = legacyBase(lineData(rows, ['month'], ['revenue', 'cost']), {}) as any;

      // Regression guard for the dropped-measure bug: BOTH measures present.
      expect(adapted.series).toHaveLength(2);
      expect(adapted.series.map((s: any) => s.name)).toEqual(['revenue', 'cost']);
      // And byte-equal to legacy overall (legend, grid bottom 15%, etc.).
      expect(adapted).toEqual(legacy);
    });

    it('smooth lines', () => {
      const rows = SIMPLE_ROWS;
      const adapted = chartSpecToEChartsOption(
        specFromLine({ dimensions: ['day'], metrics: ['count'], smooth: true }),
        rows,
      );
      const legacy = legacyBase(lineData(rows, ['day'], ['count']), { smooth: true });
      expect(adapted).toEqual(legacy);
    });

    it('area-fill single line (areaStyle opacity 0.3)', () => {
      const rows = SIMPLE_ROWS;
      const adapted = chartSpecToEChartsOption(
        specFromLine({ dimensions: ['day'], metrics: ['count'], areaFill: true }),
        rows,
      );
      const legacy = legacyBase(lineData(rows, ['day'], ['count']), { areaStyle: true });
      expect(adapted).toEqual(legacy);
    });

    it('area-fill MULTI-line (gradient opacity per index)', () => {
      const rows = MULTI_ROWS;
      const adapted = chartSpecToEChartsOption(
        specFromLine({
          dimensions: ['month'],
          metrics: ['revenue', 'cost'],
          areaFill: true,
          smooth: true,
        }),
        rows,
      );
      const legacy = legacyBase(lineData(rows, ['month'], ['revenue', 'cost']), {
        areaStyle: true,
        smooth: true,
      });
      expect(adapted).toEqual(legacy);
    });

    it('showSymbol:false hides data points', () => {
      const rows = SIMPLE_ROWS;
      const adapted = chartSpecToEChartsOption(
        specFromLine({ dimensions: ['day'], metrics: ['count'], showSymbol: false }),
        rows,
      ) as any;
      const legacy = legacyBase(lineData(rows, ['day'], ['count']), { showSymbol: false }) as any;
      expect(adapted.series[0].showSymbol).toBe(false);
      expect(adapted).toEqual(legacy);
    });

    it('titled (centered + styled title, grid top 15%)', () => {
      const rows = SIMPLE_ROWS;
      const adapted = chartSpecToEChartsOption(
        specFromLine({ dimensions: ['day'], metrics: ['count'], title: 'Daily Orders' }),
        rows,
      );
      const legacy = legacyBase(lineData(rows, ['day'], ['count']), { title: 'Daily Orders' });
      expect(adapted).toEqual(legacy);
    });

    it('with data labels (visual.dataLabels → series.label.show)', () => {
      const rows = SIMPLE_ROWS;
      const adapted = chartSpecToEChartsOption(
        specFromLine({ dimensions: ['day'], metrics: ['count'], dataLabels: true }),
        rows,
      );
      const legacy = legacyBase(lineData(rows, ['day'], ['count']), { showLabel: true });
      expect(adapted).toEqual(legacy);
    });

    it('many categories → axisLabel rotate 45', () => {
      const rows = MANY_ROWS;
      const adapted = chartSpecToEChartsOption(
        specFromLine({ dimensions: ['day'], metrics: ['count'] }),
        rows,
      ) as any;
      const legacy = legacyBase(lineData(rows, ['day'], ['count']), {}) as any;
      expect(adapted.xAxis.axisLabel).toEqual({ rotate: 45, hideOverlap: true });
      expect(adapted).toEqual(legacy);
    });

    it('empty rows (degenerate option: empty series + empty-data xAxis, bare value yAxis)', () => {
      const adapted = chartSpecToEChartsOption(
        specFromLine({ dimensions: ['day'], metrics: ['count'] }),
        [],
      );
      const legacy = legacyBase(lineData([], ['day'], ['count']), {});
      expect(adapted).toEqual(legacy);
    });

    it('empty rows, titled (empty-title fontSize-only)', () => {
      const adapted = chartSpecToEChartsOption(
        specFromLine({ dimensions: ['day'], metrics: ['count'], title: 'T' }),
        [],
      );
      const legacy = legacyBase(lineData([], ['day'], ['count']), { title: 'T' });
      expect(adapted).toEqual(legacy);
    });

    it('area-typed spec === line-typed spec with areaFill (single SmartLineChart builder)', () => {
      const rows = SIMPLE_ROWS;
      // A `line` spec with visual.areaFill and an `area` spec must produce the same
      // option, because SmartLineChart renders both from one component/builder.
      const lineWithFill = chartSpecToEChartsOption(
        specFromLine({ dimensions: ['day'], metrics: ['count'], areaFill: true }),
        rows,
      );
      const areaSpec: ChartSpec = {
        ...specFromLine({ dimensions: ['day'], metrics: ['count'] }),
        type: 'area',
      };
      const areaTyped = chartSpecToEChartsOption(areaSpec, rows);
      expect(areaTyped).toEqual(lineWithFill);
    });
  });

  // ---------------------------------------------------------------------------
  // The `chartOptions` renderer-leak is NOT the adapter's job: it stays a call-site
  // merge. Confirm the adapter never carries it, while the legacy WITH chartOptions
  // is reproduced by merging at the call site exactly as SmartLineChart does.
  // ---------------------------------------------------------------------------
  describe('chartOptions stays a call-site merge (not baked into the adapter)', () => {
    it('adapter base + call-site spread === legacy WITH chartOptions', () => {
      const rows = SIMPLE_ROWS;
      const chartOptions = { backgroundColor: '#fff', animation: false } as any;
      const adapterBase = chartSpecToEChartsOption(
        specFromLine({ dimensions: ['day'], metrics: ['count'] }),
        rows,
      );
      // This mirrors SmartLineChart's call-site merge: chartOptions ? {...base, ...chartOptions} : base
      const merged = { ...adapterBase, ...chartOptions };
      const legacyWithOptions = buildLineOptionLegacy(lineData(rows, ['day'], ['count']), {
        chartOptions,
      });
      expect(merged).toEqual(legacyWithOptions);
    });
  });
});
