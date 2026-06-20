/**
 * B2d — ChartSpec→ECharts adapter vs. live SmartBarChart EQUIVALENCE characterization.
 *
 * backlog 2026-06-18-designer-layout-family-convergence §B2d: "wire the ChartSpec→
 * ECharts adapter into SmartBarChart". This was written TDD-first as an EQUIVALENCE
 * gate: only migrate SmartBarChart onto `chartSpecToEChartsOption` if the adapter
 * produces an option equivalent to the one SmartBarChart hand-rolls today.
 *
 * VERDICT (recorded by this test): NOT EQUIVALENT. The adapter is a deliberately
 * minimal, consolidation-oriented mapping (it intentionally drops renderer config —
 * see chart-spec-adapter.ts "removing it is B2d"), while SmartBarChart's inline
 * builder carries layout/cosmetic config AND, critically, emits ONE SERIES PER
 * MEASURE whereas the adapter emits a single series from measures[0] only.
 *
 * This file therefore does NOT migrate the renderer. It pins down (characterizes)
 * the exact differences so a future reconciliation / browser golden can be scoped.
 * The legacy builder is `buildBarOptionLegacy` (extracted verbatim from
 * SmartBarChart, no behavior change); the adapter is `chartSpecToEChartsOption`.
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
 * inputs the SmartBarChart `data.meta` describes.
 */
function specFrom(opts: {
  dimensions: string[];
  metrics: string[];
  type?: ChartSpecType;
  title?: string;
  orientation?: 'vertical' | 'horizontal';
  stacked?: boolean;
}): ChartSpec {
  const { dimensions, metrics, type = 'bar', title, orientation, stacked } = opts;
  return {
    type,
    title,
    dataSource: { type: 'aggregate', modelCode: 'm', dimensions, metrics: metrics.map((field) => ({ field, aggregation: 'sum' })) },
    dimensions: dimensions.map((field, i) => ({
      field,
      role: i === 0 ? 'category' : 'series',
    })),
    measures: metrics.map((field) => ({ field, aggregation: 'sum' as const })),
    interaction: { tooltip: true },
    visual: { orientation, stacked },
  };
}

describe('B2d ChartSpec→ECharts adapter vs SmartBarChart legacy builder', () => {
  // ---------------------------------------------------------------------------
  // Sanity: the extracted legacy helper still reflects SmartBarChart's behavior.
  // ---------------------------------------------------------------------------
  describe('legacy builder characterization (must NOT change)', () => {
    it('simple vertical bar: tooltip + grid + single series with name/label/emphasis', () => {
      const data = barData(SIMPLE_ROWS, ['status'], ['count']);
      const props: BarOptionProps = { orientation: 'vertical' };
      const legacy = buildBarOptionLegacy(data, props) as any;

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
      const data = barData(MULTI_ROWS, ['region'], ['online', 'offline']);
      const legacy = buildBarOptionLegacy(data, { orientation: 'vertical' }) as any;

      expect(legacy.series).toHaveLength(2);
      expect(legacy.series.map((s: any) => s.name)).toEqual(['online', 'offline']);
      expect(legacy.series[0].data).toEqual([5, 9]);
      expect(legacy.series[1].data).toEqual([7, 3]);
      // legend only appears when metrics.length > 1
      expect(legacy.legend).toEqual({ bottom: 0, type: 'scroll' });
    });

    it('stacked: each series gets stack:total', () => {
      const data = barData(MULTI_ROWS, ['region'], ['online', 'offline']);
      const legacy = buildBarOptionLegacy(data, { stacked: true }) as any;
      expect(legacy.series.every((s: any) => s.stack === 'total')).toBe(true);
    });

    it('horizontal: value xAxis, category yAxis', () => {
      const data = barData(SIMPLE_ROWS, ['status'], ['count']);
      const legacy = buildBarOptionLegacy(data, { orientation: 'horizontal' }) as any;
      expect(legacy.xAxis).toEqual({ type: 'value' });
      expect(legacy.yAxis.type).toBe('category');
      expect(legacy.yAxis.data).toEqual(['open', 'closed']);
    });

    it('empty rows: empty series + empty-data axes', () => {
      const legacy = buildBarOptionLegacy(barData([], ['status'], ['count']), {}) as any;
      expect(legacy.series).toEqual([]);
      expect(legacy.xAxis).toEqual({ type: 'category', data: [] });
      expect(legacy.yAxis).toEqual({ type: 'value', data: [] });
    });
  });

  // ---------------------------------------------------------------------------
  // EQUIVALENCE VERDICT: NOT EQUIVALENT. The following assertions document the
  // exact material differences (not just cosmetic) that block a drop-in migration.
  // ---------------------------------------------------------------------------
  describe('EQUIVALENCE VERDICT = NOT EQUIVALENT (documented diffs)', () => {
    it('DIFF #1 (material): multi-measure → legacy emits N series, adapter emits 1', () => {
      const rows = MULTI_ROWS;
      const legacy = buildBarOptionLegacy(
        barData(rows, ['region'], ['online', 'offline']),
        {},
      ) as any;
      const adapted = chartSpecToEChartsOption(
        specFrom({ dimensions: ['region'], metrics: ['online', 'offline'] }),
        rows,
      ) as any;

      expect(legacy.series).toHaveLength(2); // one per measure
      expect(adapted.series).toHaveLength(1); // measures[0] only — DROPS 'offline'
      expect(legacy.series).not.toEqual(adapted.series);
    });

    it('DIFF #2 (material): legacy always emits tooltip+axisPointer; adapter omits axisPointer and gates tooltip on interaction', () => {
      const rows = SIMPLE_ROWS;
      const legacy = buildBarOptionLegacy(barData(rows, ['status'], ['count']), {}) as any;
      const adapted = chartSpecToEChartsOption(
        specFrom({ dimensions: ['status'], metrics: ['count'] }),
        rows,
      ) as any;

      // legacy: { trigger:'axis', axisPointer:{type:'shadow'} } ; adapter: { trigger:'axis' }
      expect(legacy.tooltip).toEqual({ trigger: 'axis', axisPointer: { type: 'shadow' } });
      expect(adapted.tooltip).toEqual({ trigger: 'axis' });
      expect(legacy.tooltip).not.toEqual(adapted.tooltip);
    });

    it('DIFF #3 (material): legacy emits grid; adapter has none', () => {
      const rows = SIMPLE_ROWS;
      const legacy = buildBarOptionLegacy(barData(rows, ['status'], ['count']), {}) as any;
      const adapted = chartSpecToEChartsOption(
        specFrom({ dimensions: ['status'], metrics: ['count'] }),
        rows,
      ) as any;
      expect(legacy.grid).toBeDefined();
      expect(adapted.grid).toBeUndefined();
    });

    it('DIFF #4 (material): legacy category axis carries axisLabel (rotate/hideOverlap); adapter does not', () => {
      const rows = SIMPLE_ROWS;
      const legacy = buildBarOptionLegacy(barData(rows, ['status'], ['count']), {}) as any;
      const adapted = chartSpecToEChartsOption(
        specFrom({ dimensions: ['status'], metrics: ['count'] }),
        rows,
      ) as any;
      expect(legacy.xAxis.axisLabel).toEqual({ rotate: 0, hideOverlap: true });
      expect(adapted.xAxis.axisLabel).toBeUndefined();
    });

    it('DIFF #5 (cosmetic, but proves non-equality): legacy series carry name/label/emphasis; adapter series are bare {type,data}', () => {
      const rows = SIMPLE_ROWS;
      const legacy = buildBarOptionLegacy(barData(rows, ['status'], ['count']), {}) as any;
      const adapted = chartSpecToEChartsOption(
        specFrom({ dimensions: ['status'], metrics: ['count'] }),
        rows,
      ) as any;

      // Same data values though — the bars themselves are the same heights.
      expect(legacy.series[0].data).toEqual(adapted.series[0].data);
      // ...but the series object shape differs.
      expect(legacy.series[0].name).toBe('count');
      expect(adapted.series[0].name).toBeUndefined();
      expect(legacy.series[0].emphasis).toEqual({ focus: 'series' });
      expect(adapted.series[0].emphasis).toBeUndefined();
      expect(legacy.series[0].label).toBeDefined();
      expect(adapted.series[0].label).toBeUndefined();
    });

    it('DIFF #6 (material): legacy title carries left/textStyle; adapter title is { text } only', () => {
      const rows = SIMPLE_ROWS;
      const legacy = buildBarOptionLegacy(barData(rows, ['status'], ['count']), {
        title: 'Sales',
      }) as any;
      const adapted = chartSpecToEChartsOption(
        specFrom({ dimensions: ['status'], metrics: ['count'], title: 'Sales' }),
        rows,
      ) as any;
      expect(legacy.title).toEqual({
        text: 'Sales',
        left: 'center',
        textStyle: { fontSize: 14, fontWeight: 500 },
      });
      expect(adapted.title).toEqual({ text: 'Sales' });
    });

    it('CONFIRMS non-equivalence end-to-end: full options are not deep-equal even for the simplest case', () => {
      const rows = SIMPLE_ROWS;
      const legacy = buildBarOptionLegacy(barData(rows, ['status'], ['count']), {}) as any;
      const adapted = chartSpecToEChartsOption(
        specFrom({ dimensions: ['status'], metrics: ['count'] }),
        rows,
      ) as any;
      expect(legacy).not.toEqual(adapted);
    });

    it('PARTIAL equivalence that DOES hold: axis types + category labels + bar heights match (single measure)', () => {
      // Documents WHAT survives a migration, so a future reconciliation knows the
      // delta is layout/series-shape/multi-measure, not the core data binding.
      const rows = SIMPLE_ROWS;
      const legacy = buildBarOptionLegacy(barData(rows, ['status'], ['count']), {}) as any;
      const adapted = chartSpecToEChartsOption(
        specFrom({ dimensions: ['status'], metrics: ['count'] }),
        rows,
      ) as any;
      expect(adapted.xAxis.type).toBe(legacy.xAxis.type);
      expect(adapted.xAxis.data).toEqual(legacy.xAxis.data);
      expect(adapted.yAxis.type).toBe(legacy.yAxis.type);
      expect(adapted.series[0].type).toBe(legacy.series[0].type);
      expect(adapted.series[0].data).toEqual(legacy.series[0].data);
    });
  });
});
