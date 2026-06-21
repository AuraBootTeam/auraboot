/**
 * B2d — ChartSpec→ECharts adapter ⇔ SmartBarChart legacy builder EQUIVALENCE gate.
 *
 * backlog 2026-06-18-designer-layout-family-convergence §B2d: "wire the ChartSpec→
 * ECharts adapter into SmartBarChart". This is a PROVABLY-NEUTRAL refactor: the
 * adapter's bar branch (`chartSpecToEChartsOption`, spec.type === 'bar') is now the
 * canonical option-builder, and it must be BYTE-EQUIVALENT to the legacy
 * `buildBarOptionLegacy` BASE option — i.e. legacy's output BEFORE the `chartOptions`
 * prop-merge (that renderer-leak stays a call-site merge in SmartBarChart, NOT baked
 * into the adapter).
 *
 * VERDICT (recorded by this test): FULL BYTE-EQUIVALENCE achieved. For every
 * representative bar input (simple vertical, stacked, horizontal, MULTI-measure,
 * empty, titled, with-labels) the adapter deep-equals the legacy base. The headline
 * fix this unblocks: the adapter previously rendered `measures[0]` ONLY (silently
 * dropping every other measure); it now emits one series per measure, matching legacy.
 *
 * `buildBarOptionLegacy` is the ORACLE (extracted verbatim from SmartBarChart, no
 * behavior change); the adapter is `chartSpecToEChartsOption`.
 */
import { describe, expect, it } from 'vitest';
import type { ChartSpec, ChartSpecType } from '../chart-spec';
import { chartSpecToEChartsOption } from '../chart-spec-echarts';
import {
  buildBarOptionLegacy,
  type BarChartData,
  type BarOptionProps,
} from '../../components/charts/SmartBarChart';

// --- representative inputs ----------------------------------------------------

const SIMPLE_ROWS = [
  { status: 'open', count: 10 },
  { status: 'closed', count: 30 },
];

const MULTI_ROWS = [
  { region: 'North', online: 5, offline: 7 },
  { region: 'South', online: 9, offline: 3 },
];

/** Many categories → exercises the legacy axisLabel rotate>10 branch. */
const MANY_ROWS = Array.from({ length: 12 }, (_, i) => ({ status: `s${i}`, count: i }));

/** A live-SmartBarChart `data` object (mirrors what useChartData returns). */
function barData(
  rows: Record<string, unknown>[],
  dimensions: string[],
  metrics: string[],
): BarChartData {
  return { rows, meta: { dimensions, metrics } };
}

/**
 * Build a ChartSpec the way the chart authoring surface would, for the same
 * inputs the SmartBarChart `data.meta` describes. Renderer-neutral visual intent
 * (orientation/stacked/dataLabels) maps onto the adapter's bar branch.
 */
function specFrom(opts: {
  dimensions: string[];
  metrics: string[];
  type?: ChartSpecType;
  title?: string;
  orientation?: 'vertical' | 'horizontal';
  stacked?: boolean;
  dataLabels?: boolean;
}): ChartSpec {
  const { dimensions, metrics, type = 'bar', title, orientation, stacked, dataLabels } = opts;
  return {
    type,
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
    visual: { orientation, stacked, dataLabels },
  };
}

/** The legacy BASE option = legacy output with NO `chartOptions` prop-merge. */
function legacyBase(data: BarChartData, props: Omit<BarOptionProps, 'chartOptions'> = {}) {
  return buildBarOptionLegacy(data, { ...props, chartOptions: undefined });
}

describe('B2d ChartSpec→ECharts adapter ⇔ SmartBarChart legacy builder', () => {
  // ---------------------------------------------------------------------------
  // Sanity: the extracted legacy helper still reflects SmartBarChart's behavior.
  // (Characterizes the ORACLE — must NOT change.)
  // ---------------------------------------------------------------------------
  describe('legacy builder characterization (must NOT change — this is the oracle)', () => {
    it('simple vertical bar: tooltip + grid + single series with name/label/emphasis', () => {
      const legacy = buildBarOptionLegacy(barData(SIMPLE_ROWS, ['status'], ['count']), {
        orientation: 'vertical',
      }) as any;

      expect(legacy.tooltip).toEqual({ trigger: 'axis', axisPointer: { type: 'shadow' } });
      expect(legacy.grid).toBeDefined();
      expect(legacy.xAxis.type).toBe('category');
      expect(legacy.xAxis.data).toEqual(['open', 'closed']);
      expect(legacy.xAxis.axisLabel).toEqual({ rotate: 0, hideOverlap: true });
      expect(legacy.yAxis.type).toBe('value');
      expect(legacy.series).toHaveLength(1);
      expect(legacy.series[0]).toMatchObject({
        name: 'count',
        type: 'bar',
        data: [10, 30],
        emphasis: { focus: 'series' },
      });
      expect(legacy.series[0].label).toEqual({ show: false, position: 'top' });
    });

    it('multiple measures: ONE SERIES PER MEASURE + legend appears', () => {
      const legacy = buildBarOptionLegacy(barData(MULTI_ROWS, ['region'], ['online', 'offline']), {
        orientation: 'vertical',
      }) as any;

      expect(legacy.series).toHaveLength(2);
      expect(legacy.series.map((s: any) => s.name)).toEqual(['online', 'offline']);
      expect(legacy.series[0].data).toEqual([5, 9]);
      expect(legacy.series[1].data).toEqual([7, 3]);
      expect(legacy.legend).toEqual({ bottom: 0, type: 'scroll' });
    });
  });

  // ---------------------------------------------------------------------------
  // EQUIVALENCE GATE: adapter bar branch === legacy BASE option, deep-equal.
  // The adapter must NOT bake in `chartOptions` (that's a call-site merge), so we
  // compare against legacyBase() (legacy sans chartOptions).
  // ---------------------------------------------------------------------------
  describe('FULL BYTE-EQUIVALENCE (adapter === legacy base)', () => {
    it('simple vertical bar (single measure)', () => {
      const rows = SIMPLE_ROWS;
      const adapted = chartSpecToEChartsOption(
        specFrom({ dimensions: ['status'], metrics: ['count'], orientation: 'vertical' }),
        rows,
      );
      const legacy = legacyBase(barData(rows, ['status'], ['count']), { orientation: 'vertical' });
      expect(adapted).toEqual(legacy);
    });

    it('default orientation (no visual.orientation) === legacy default vertical', () => {
      const rows = SIMPLE_ROWS;
      const adapted = chartSpecToEChartsOption(
        specFrom({ dimensions: ['status'], metrics: ['count'] }),
        rows,
      );
      const legacy = legacyBase(barData(rows, ['status'], ['count']), {});
      expect(adapted).toEqual(legacy);
    });

    it('MULTI-MEASURE (headline fix): adapter now emits N series, deep-equal to legacy', () => {
      const rows = MULTI_ROWS;
      const adapted = chartSpecToEChartsOption(
        specFrom({ dimensions: ['region'], metrics: ['online', 'offline'] }),
        rows,
      ) as any;
      const legacy = legacyBase(barData(rows, ['region'], ['online', 'offline']), {}) as any;

      // Regression guard for the dropped-measure bug: BOTH measures present.
      expect(adapted.series).toHaveLength(2);
      expect(adapted.series.map((s: any) => s.name)).toEqual(['online', 'offline']);
      // And byte-equal to legacy overall (legend, grid bottom 15%, etc.).
      expect(adapted).toEqual(legacy);
    });

    it('stacked multi-measure', () => {
      const rows = MULTI_ROWS;
      const adapted = chartSpecToEChartsOption(
        specFrom({ dimensions: ['region'], metrics: ['online', 'offline'], stacked: true }),
        rows,
      );
      const legacy = legacyBase(barData(rows, ['region'], ['online', 'offline']), {
        stacked: true,
      });
      expect(adapted).toEqual(legacy);
    });

    it('horizontal (value xAxis / category yAxis)', () => {
      const rows = SIMPLE_ROWS;
      const adapted = chartSpecToEChartsOption(
        specFrom({ dimensions: ['status'], metrics: ['count'], orientation: 'horizontal' }),
        rows,
      );
      const legacy = legacyBase(barData(rows, ['status'], ['count']), {
        orientation: 'horizontal',
      });
      expect(adapted).toEqual(legacy);
    });

    it('titled (centered + styled title, grid top 15%)', () => {
      const rows = SIMPLE_ROWS;
      const adapted = chartSpecToEChartsOption(
        specFrom({ dimensions: ['status'], metrics: ['count'], title: 'Sales' }),
        rows,
      );
      const legacy = legacyBase(barData(rows, ['status'], ['count']), { title: 'Sales' });
      expect(adapted).toEqual(legacy);
    });

    it('with data labels (visual.dataLabels → series.label.show)', () => {
      const rows = SIMPLE_ROWS;
      const adapted = chartSpecToEChartsOption(
        specFrom({ dimensions: ['status'], metrics: ['count'], dataLabels: true }),
        rows,
      );
      const legacy = legacyBase(barData(rows, ['status'], ['count']), { showLabel: true });
      expect(adapted).toEqual(legacy);
    });

    it('many categories → axisLabel rotate 45', () => {
      const rows = MANY_ROWS;
      const adapted = chartSpecToEChartsOption(
        specFrom({ dimensions: ['status'], metrics: ['count'] }),
        rows,
      ) as any;
      const legacy = legacyBase(barData(rows, ['status'], ['count']), {}) as any;
      expect(adapted.xAxis.axisLabel).toEqual({ rotate: 45, hideOverlap: true });
      expect(adapted).toEqual(legacy);
    });

    it('empty rows (degenerate option: empty series + empty-data axes, no tooltip/grid)', () => {
      const adapted = chartSpecToEChartsOption(
        specFrom({ dimensions: ['status'], metrics: ['count'] }),
        [],
      );
      const legacy = legacyBase(barData([], ['status'], ['count']), {});
      expect(adapted).toEqual(legacy);
    });

    it('empty rows, horizontal + titled (empty-title fontSize-only, swapped empty axes)', () => {
      const adapted = chartSpecToEChartsOption(
        specFrom({
          dimensions: ['status'],
          metrics: ['count'],
          orientation: 'horizontal',
          title: 'T',
        }),
        [],
      );
      const legacy = legacyBase(barData([], ['status'], ['count']), {
        orientation: 'horizontal',
        title: 'T',
      });
      expect(adapted).toEqual(legacy);
    });
  });

  // ---------------------------------------------------------------------------
  // The `chartOptions` renderer-leak is NOT the adapter's job: it stays a call-site
  // merge. Confirm the adapter never carries it, while the legacy WITH chartOptions
  // is reproduced by merging at the call site exactly as SmartBarChart does.
  // ---------------------------------------------------------------------------
  describe('chartOptions stays a call-site merge (not baked into the adapter)', () => {
    it('adapter base + call-site spread === legacy WITH chartOptions', () => {
      const rows = SIMPLE_ROWS;
      const chartOptions = { backgroundColor: '#fff', animation: false } as any;
      const adapterBase = chartSpecToEChartsOption(
        specFrom({ dimensions: ['status'], metrics: ['count'] }),
        rows,
      );
      // This mirrors SmartBarChart's call-site merge: chartOptions ? {...base, ...chartOptions} : base
      const merged = { ...adapterBase, ...chartOptions };
      const legacyWithOptions = buildBarOptionLegacy(barData(rows, ['status'], ['count']), {
        chartOptions,
      });
      expect(merged).toEqual(legacyWithOptions);
    });
  });
});
