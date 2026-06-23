/**
 * B2d — ChartSpec→ECharts adapter ⇔ SmartScatterChart legacy builder EQUIVALENCE gate.
 *
 * backlog 2026-06-18-designer-layout-family-convergence §B2d: "wire the ChartSpec→
 * ECharts adapter into SmartScatterChart". This is a PROVABLY-NEUTRAL refactor: the
 * adapter's scatter branch (`chartSpecToEChartsOption`, spec.type === 'scatter') is now
 * the canonical option-builder, and it must be EQUIVALENT to the legacy
 * `buildScatterOptionLegacy` BASE option — i.e. legacy's output BEFORE the `chartOptions`
 * prop-merge (that renderer-leak stays a call-site merge in SmartScatterChart, NOT baked
 * into the adapter).
 *
 * VERDICT (recorded by this test): FULL EQUIVALENCE achieved. For every representative
 * scatter input (single point set, multi-row, empty, titled, with-labels, bubble-mode
 * sized points) the adapter equals the legacy base.
 *
 * NO multi-measure "drop" bug to guard against here: unlike bar/line (one series PER
 * measure), a scatter plot maps measures onto AXIS ROLES inside a SINGLE series
 * (measures[0]→X, measures[1]→Y, measures[2]→bubble size). The legacy builder already
 * emitted exactly one scatter series; the adapter matches it (asserted: series.length===1).
 *
 * LEGACY QUIRK — function members. `tooltip.formatter` and (in bubble mode) the series'
 * `symbolSize` are FUNCTIONS that close over the axis labels / data rows. Two
 * structurally-identical-but-distinct function instances are NOT reference-equal under
 * vitest `toEqual`, so a naive whole-object deep-equal is impossible WITHOUT changing
 * scatter's behavior (e.g. dropping the formatter — which we must NOT do). Instead the
 * gate proves equivalence the only faithful way:
 *   (1) the NON-FUNCTION structure is deep-equal (functions replaced by a sentinel), and
 *   (2) the FUNCTION members are BEHAVIORALLY identical — invoked with representative
 *       inputs, their outputs match legacy's exactly.
 * This is a stronger guarantee than byte-equivalence of inert config: it pins both the
 * shape AND the runtime behavior of the closures, with zero behavior change to scatter.
 *
 * `buildScatterOptionLegacy` is the ORACLE (extracted verbatim from SmartScatterChart, no
 * behavior change); the adapter is `chartSpecToEChartsOption`.
 */
import { describe, expect, it } from 'vitest';
import type { ChartSpec } from '../chart-spec';
import { chartSpecToEChartsOption } from '../chart-spec-echarts';
import {
  buildScatterOptionLegacy,
  type ScatterChartData,
  type ScatterOptionProps,
} from '../../components/charts/SmartScatterChart';

// --- representative inputs ----------------------------------------------------

/** x = revenue, y = profit, label = region. */
const SIMPLE_ROWS = [
  { region: 'North', revenue: 10, profit: 3 },
  { region: 'South', revenue: 30, profit: 9 },
];

/** Adds a size measure (units) → exercises bubble mode. */
const BUBBLE_ROWS = [
  { region: 'North', revenue: 10, profit: 3, units: 100 },
  { region: 'South', revenue: 30, profit: 9, units: 400 },
];

/** A live-SmartScatterChart `data` object (mirrors what useChartData returns). */
function scatterData(
  rows: Record<string, unknown>[],
  dimensions: string[],
  metrics: string[],
): ScatterChartData {
  return { rows, meta: { dimensions, metrics } };
}

/**
 * Build a ChartSpec the way the chart authoring surface would, for the same inputs the
 * SmartScatterChart `data.meta` describes. Scatter-specific intent (axis labels, bubble
 * sizing) maps onto the spec's `scatter` extension, NOT `visual` — exactly as
 * SmartScatterChart's `specFromScatterChartData` does.
 */
function specFromScatter(opts: {
  dimensions: string[];
  metrics: string[];
  title?: string;
  xAxisLabel?: string;
  yAxisLabel?: string;
  bubbleMode?: boolean;
  symbolSizeRange?: [number, number];
}): ChartSpec {
  const { dimensions, metrics, title, xAxisLabel, yAxisLabel, bubbleMode, symbolSizeRange } = opts;
  return {
    type: 'scatter',
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
    scatter: { xAxisLabel, yAxisLabel, bubbleMode, symbolSizeRange },
  };
}

/** The legacy BASE option = legacy output with NO `chartOptions` prop-merge. */
function legacyBase(data: ScatterChartData, props: Omit<ScatterOptionProps, 'chartOptions'> = {}) {
  return buildScatterOptionLegacy(data, { ...props, chartOptions: undefined });
}

const FN_SENTINEL = '<<fn>>';

/** Deep-clone replacing every function with a stable sentinel, so the structural part
 * can be deep-equaled while function identity (impossible across builders) is ignored. */
function stripFns(value: unknown): unknown {
  if (typeof value === 'function') return FN_SENTINEL;
  if (Array.isArray(value)) return value.map(stripFns);
  if (value && typeof value === 'object') {
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(value as Record<string, unknown>)) out[k] = stripFns(v);
    return out;
  }
  return value;
}

/** Assert adapter equals legacy base on the NON-FUNCTION structure (functions → sentinel). */
function expectStructurallyEqual(adapted: unknown, legacy: unknown) {
  expect(stripFns(adapted)).toEqual(stripFns(legacy));
}

describe('B2d ChartSpec→ECharts adapter ⇔ SmartScatterChart legacy builder', () => {
  // ---------------------------------------------------------------------------
  // Sanity: the extracted legacy helper still reflects SmartScatterChart's behavior.
  // (Characterizes the ORACLE — must NOT change.)
  // ---------------------------------------------------------------------------
  describe('legacy builder characterization (must NOT change — this is the oracle)', () => {
    it('value-vs-value axes, dashed splitLine, item tooltip, SINGLE scatter series', () => {
      const legacy = buildScatterOptionLegacy(
        scatterData(SIMPLE_ROWS, ['region'], ['revenue', 'profit']),
        {},
      ) as any;

      expect(legacy.tooltip.trigger).toBe('item');
      expect(typeof legacy.tooltip.formatter).toBe('function');
      expect(legacy.xAxis).toMatchObject({
        type: 'value',
        name: 'revenue',
        splitLine: { show: true, lineStyle: { type: 'dashed' } },
      });
      expect(legacy.yAxis).toMatchObject({
        type: 'value',
        name: 'profit',
        splitLine: { show: true, lineStyle: { type: 'dashed' } },
      });
      // ONE series; points are [x, y, label].
      expect(legacy.series).toHaveLength(1);
      expect(legacy.series[0].type).toBe('scatter');
      expect(legacy.series[0].data).toEqual([
        [10, 3, 'North'],
        [30, 9, 'South'],
      ]);
      // Fixed symbol size (no bubble mode).
      expect(legacy.series[0].symbolSize).toBe(14);
      expect(legacy.series[0].emphasis).toEqual({
        itemStyle: { shadowBlur: 10, shadowColor: 'rgba(0,0,0,0.3)' },
      });
    });

    it('empty rows → degenerate option keeps ONE empty scatter series (not series:[])', () => {
      const legacy = buildScatterOptionLegacy(scatterData([], ['region'], ['revenue']), {}) as any;
      expect(legacy.series).toEqual([{ type: 'scatter', data: [] }]);
      expect(legacy.xAxis).toEqual({ type: 'value' });
      expect(legacy.yAxis).toEqual({ type: 'value' });
    });

    it('bubble mode → symbolSize is a FUNCTION scaling by the third measure', () => {
      const legacy = buildScatterOptionLegacy(
        scatterData(BUBBLE_ROWS, ['region'], ['revenue', 'profit', 'units']),
        { bubbleMode: true, symbolSizeRange: [10, 60] },
      ) as any;
      expect(typeof legacy.series[0].symbolSize).toBe('function');
      // maxSize = 400; North units=100 → 10 + (100/400)*(60-10) = 22.5
      expect(legacy.series[0].symbolSize([10, 3])).toBeCloseTo(22.5);
      // South units=400 → 10 + (400/400)*50 = 60
      expect(legacy.series[0].symbolSize([30, 9])).toBeCloseTo(60);
    });
  });

  // ---------------------------------------------------------------------------
  // EQUIVALENCE GATE: adapter scatter branch === legacy BASE option.
  // Structure is deep-equaled (functions → sentinel); function members are then
  // asserted behaviorally identical (see the dedicated describe block below).
  // The adapter must NOT bake in `chartOptions` (call-site merge), so we compare
  // against legacyBase() (legacy sans chartOptions).
  // ---------------------------------------------------------------------------
  describe('STRUCTURAL EQUIVALENCE (adapter === legacy base, functions aside)', () => {
    it('simple scatter (x/y measures + label dimension)', () => {
      const rows = SIMPLE_ROWS;
      const adapted = chartSpecToEChartsOption(
        specFromScatter({ dimensions: ['region'], metrics: ['revenue', 'profit'] }),
        rows,
      ) as any;
      const legacy = legacyBase(scatterData(rows, ['region'], ['revenue', 'profit'])) as any;
      // Single series (no measure-drop bug possible — measures are axis roles).
      expect(adapted.series).toHaveLength(1);
      expectStructurallyEqual(adapted, legacy);
    });

    it('single measure (y falls back to x)', () => {
      const rows = [
        { region: 'North', revenue: 10 },
        { region: 'South', revenue: 30 },
      ];
      const adapted = chartSpecToEChartsOption(
        specFromScatter({ dimensions: ['region'], metrics: ['revenue'] }),
        rows,
      ) as any;
      const legacy = legacyBase(scatterData(rows, ['region'], ['revenue'])) as any;
      // y axis name defaults to the x measure (yKey = metrics[1] || metrics[0]).
      expect(adapted.yAxis.name).toBe('revenue');
      expect(adapted.series[0].data).toEqual([
        [10, 10, 'North'],
        [30, 30, 'South'],
      ]);
      expectStructurallyEqual(adapted, legacy);
    });

    it('no dimension (no label pushed into points)', () => {
      const rows = [
        { revenue: 10, profit: 3 },
        { revenue: 30, profit: 9 },
      ];
      const adapted = chartSpecToEChartsOption(
        specFromScatter({ dimensions: [], metrics: ['revenue', 'profit'] }),
        rows,
      ) as any;
      const legacy = legacyBase(scatterData(rows, [], ['revenue', 'profit'])) as any;
      expect(adapted.series[0].data).toEqual([
        [10, 3],
        [30, 9],
      ]);
      expectStructurallyEqual(adapted, legacy);
    });

    it('explicit axis labels (xAxisLabel/yAxisLabel override the measure field names)', () => {
      const rows = SIMPLE_ROWS;
      const adapted = chartSpecToEChartsOption(
        specFromScatter({
          dimensions: ['region'],
          metrics: ['revenue', 'profit'],
          xAxisLabel: 'Revenue ($)',
          yAxisLabel: 'Profit ($)',
        }),
        rows,
      ) as any;
      const legacy = legacyBase(scatterData(rows, ['region'], ['revenue', 'profit']), {
        xAxisLabel: 'Revenue ($)',
        yAxisLabel: 'Profit ($)',
      }) as any;
      expect(adapted.xAxis.name).toBe('Revenue ($)');
      expect(adapted.yAxis.name).toBe('Profit ($)');
      expectStructurallyEqual(adapted, legacy);
    });

    it('titled (centered + styled title)', () => {
      const rows = SIMPLE_ROWS;
      const adapted = chartSpecToEChartsOption(
        specFromScatter({ dimensions: ['region'], metrics: ['revenue', 'profit'], title: 'R vs P' }),
        rows,
      );
      const legacy = legacyBase(scatterData(rows, ['region'], ['revenue', 'profit']), {
        title: 'R vs P',
      });
      expect((adapted as any).title).toEqual({
        text: 'R vs P',
        left: 'center',
        textStyle: { fontSize: 14, fontWeight: 500 },
      });
      expectStructurallyEqual(adapted, legacy);
    });

    it('bubble mode (sized points — symbolSize becomes a function)', () => {
      const rows = BUBBLE_ROWS;
      const adapted = chartSpecToEChartsOption(
        specFromScatter({
          dimensions: ['region'],
          metrics: ['revenue', 'profit', 'units'],
          bubbleMode: true,
        }),
        rows,
      ) as any;
      const legacy = legacyBase(scatterData(rows, ['region'], ['revenue', 'profit', 'units']), {
        bubbleMode: true,
      }) as any;
      expect(typeof adapted.series[0].symbolSize).toBe('function');
      expectStructurallyEqual(adapted, legacy);
    });

    it('bubble mode with custom symbolSizeRange', () => {
      const rows = BUBBLE_ROWS;
      const adapted = chartSpecToEChartsOption(
        specFromScatter({
          dimensions: ['region'],
          metrics: ['revenue', 'profit', 'units'],
          bubbleMode: true,
          symbolSizeRange: [5, 25],
        }),
        rows,
      ) as any;
      const legacy = legacyBase(scatterData(rows, ['region'], ['revenue', 'profit', 'units']), {
        bubbleMode: true,
        symbolSizeRange: [5, 25],
      }) as any;
      expectStructurallyEqual(adapted, legacy);
    });

    it('empty rows (degenerate option: single empty scatter series, bare value axes)', () => {
      const adapted = chartSpecToEChartsOption(
        specFromScatter({ dimensions: ['region'], metrics: ['revenue'] }),
        [],
      );
      const legacy = legacyBase(scatterData([], ['region'], ['revenue']));
      expectStructurallyEqual(adapted, legacy);
    });

    it('empty rows, titled (empty-title fontSize-only)', () => {
      const adapted = chartSpecToEChartsOption(
        specFromScatter({ dimensions: ['region'], metrics: ['revenue'], title: 'T' }),
        [],
      ) as any;
      const legacy = legacyBase(scatterData([], ['region'], ['revenue']), { title: 'T' }) as any;
      // Empty-data title carries fontSize ONLY (no fontWeight), matching legacy.
      expect(adapted.title).toEqual({ text: 'T', left: 'center', textStyle: { fontSize: 14 } });
      expectStructurallyEqual(adapted, legacy);
    });
  });

  // ---------------------------------------------------------------------------
  // FUNCTION-MEMBER BEHAVIORAL EQUIVALENCE: the closures (tooltip.formatter and the
  // bubble-mode symbolSize) cannot be reference-equal across two builders, so we prove
  // they BEHAVE identically — same outputs for representative inputs.
  // ---------------------------------------------------------------------------
  describe('function members are behaviorally identical (closures)', () => {
    it('tooltip.formatter: default labels + label row → identical strings', () => {
      const rows = SIMPLE_ROWS;
      const spec = specFromScatter({ dimensions: ['region'], metrics: ['revenue', 'profit'] });
      const adapted = chartSpecToEChartsOption(spec, rows) as any;
      const legacy = legacyBase(scatterData(rows, ['region'], ['revenue', 'profit'])) as any;

      const point = { data: [10, 3, 'North'] as (number | string)[] };
      expect(adapted.tooltip.formatter(point)).toBe(legacy.tooltip.formatter(point));
      // Spot-check the exact legacy string shape (label<br/> + xKey + yKey).
      expect(adapted.tooltip.formatter(point)).toBe('North<br/>revenue: 10<br/>profit: 3');
    });

    it('tooltip.formatter: no data / no label / explicit axis labels', () => {
      const rows = SIMPLE_ROWS;
      const adapted = chartSpecToEChartsOption(
        specFromScatter({
          dimensions: ['region'],
          metrics: ['revenue', 'profit'],
          xAxisLabel: 'Rev',
          yAxisLabel: 'Pro',
        }),
        rows,
      ) as any;
      const legacy = legacyBase(scatterData(rows, ['region'], ['revenue', 'profit']), {
        xAxisLabel: 'Rev',
        yAxisLabel: 'Pro',
      }) as any;

      // No data → empty string.
      expect(adapted.tooltip.formatter({})).toBe(legacy.tooltip.formatter({}));
      expect(adapted.tooltip.formatter({})).toBe('');
      // No label (point without [2]) → no "<br/>" prefix.
      const noLabel = { data: [10, 3] as (number | string)[] };
      expect(adapted.tooltip.formatter(noLabel)).toBe(legacy.tooltip.formatter(noLabel));
      expect(adapted.tooltip.formatter(noLabel)).toBe('Rev: 10<br/>Pro: 3');
      // With label + explicit axis labels.
      const labeled = { data: [10, 3, 'North'] as (number | string)[] };
      expect(adapted.tooltip.formatter(labeled)).toBe(legacy.tooltip.formatter(labeled));
    });

    it('bubble-mode symbolSize: identical scaled sizes for every point', () => {
      const rows = BUBBLE_ROWS;
      const adapted = chartSpecToEChartsOption(
        specFromScatter({
          dimensions: ['region'],
          metrics: ['revenue', 'profit', 'units'],
          bubbleMode: true,
          symbolSizeRange: [10, 60],
        }),
        rows,
      ) as any;
      const legacy = legacyBase(scatterData(rows, ['region'], ['revenue', 'profit', 'units']), {
        bubbleMode: true,
        symbolSizeRange: [10, 60],
      }) as any;

      for (const val of [[10, 3], [30, 9]] as number[][]) {
        expect(adapted.series[0].symbolSize(val)).toBe(legacy.series[0].symbolSize(val));
      }
      // Pin the exact legacy scaling: North → 22.5, South → 60.
      expect(adapted.series[0].symbolSize([10, 3])).toBeCloseTo(22.5);
      expect(adapted.series[0].symbolSize([30, 9])).toBeCloseTo(60);
    });
  });

  // ---------------------------------------------------------------------------
  // The `chartOptions` renderer-leak is NOT the adapter's job: it stays a call-site
  // merge. Confirm the adapter never carries it, while the legacy WITH chartOptions is
  // reproduced by merging at the call site exactly as SmartScatterChart does.
  // ---------------------------------------------------------------------------
  describe('chartOptions stays a call-site merge (not baked into the adapter)', () => {
    it('adapter base + call-site spread === legacy WITH chartOptions (structurally)', () => {
      const rows = SIMPLE_ROWS;
      const chartOptions = { backgroundColor: '#fff', animation: false } as any;
      const adapterBase = chartSpecToEChartsOption(
        specFromScatter({ dimensions: ['region'], metrics: ['revenue', 'profit'] }),
        rows,
      );
      // This mirrors SmartScatterChart's call-site merge: chartOptions ? {...base, ...chartOptions} : base
      const merged = { ...adapterBase, ...chartOptions };
      const legacyWithOptions = buildScatterOptionLegacy(
        scatterData(rows, ['region'], ['revenue', 'profit']),
        { chartOptions },
      );
      expectStructurallyEqual(merged, legacyWithOptions);
      expect((merged as any).backgroundColor).toBe('#fff');
      expect((merged as any).animation).toBe(false);
    });
  });
});
