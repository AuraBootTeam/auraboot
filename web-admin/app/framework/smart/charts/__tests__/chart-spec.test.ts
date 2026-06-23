import { describe, it, expect } from 'vitest';
import type { ChartConfig } from '../../types/chart';
import {
  type ChartSpec,
  CHART_SPEC_TYPES,
  CAPABILITY_MATRIX,
  isChartSpecType,
  assertRendererAgnostic,
  findRendererLeaks,
  getRenderTarget,
} from '../chart-spec';
import { validateChartSpecForTarget } from '../chart-spec-validation';
import { chartConfigToSpec, bindingFromFields } from '../chart-spec-adapter';

// --- helpers -----------------------------------------------------------------

function aggregatedBarSpec(): ChartSpec {
  return {
    type: 'bar',
    title: 'Revenue by month',
    dataSource: { type: 'aggregate', modelCode: 'sale', limit: 100 },
    dimensions: [{ field: 'month', role: 'category' }],
    measures: [{ field: 'amount', aggregation: 'sum' }],
    interaction: { tooltip: true },
  };
}

// --- type registry -----------------------------------------------------------

describe('ChartSpecType registry', () => {
  it('accepts chart-like types and rejects display widgets', () => {
    expect(isChartSpecType('bar')).toBe(true);
    expect(isChartSpecType('table')).toBe(true);
    expect(isChartSpecType('number-card')).toBe(false);
    expect(isChartSpecType('rich-text')).toBe(false);
    expect(isChartSpecType('inbox')).toBe(false);
  });
  it('CHART_SPEC_TYPES has no duplicates', () => {
    expect(new Set(CHART_SPEC_TYPES).size).toBe(CHART_SPEC_TYPES.length);
  });
});

// --- renderer-agnostic guard -------------------------------------------------

describe('renderer-agnostic guard', () => {
  it('a clean ChartSpec passes', () => {
    expect(() => assertRendererAgnostic(aggregatedBarSpec())).not.toThrow();
    expect(findRendererLeaks(aggregatedBarSpec())).toEqual([]);
  });

  it('detects an echarts option leak at any depth', () => {
    const leaky = { ...aggregatedBarSpec(), visual: { nested: { option: { series: [] } } } } as unknown as ChartSpec;
    const leaks = findRendererLeaks(leaky);
    expect(leaks.map((l) => l.key)).toContain('option');
    expect(leaks.map((l) => l.key)).toContain('series');
    expect(() => assertRendererAgnostic(leaky)).toThrow(/not renderer-agnostic/);
  });

  it('detects a chartOptions / svg path leak', () => {
    const leaky = { ...aggregatedBarSpec(), chartOptions: { color: '#f00' }, foo: { path: 'M0 0' } } as unknown as ChartSpec;
    const keys = findRendererLeaks(leaky).map((l) => l.key);
    expect(keys).toContain('chartOptions');
    expect(keys).toContain('path');
  });
});

// --- capability matrix -------------------------------------------------------

describe('CAPABILITY_MATRIX', () => {
  it('echarts is interactive and supports all types', () => {
    const t = getRenderTarget('echarts');
    expect(t.interactive).toBe(true);
    expect(t.supportedTypes).toBe('*');
    expect(t.capabilities.tooltip).toBe('full');
  });
  it('svg-print is non-interactive, no tooltip/animation/linkage, subset of types', () => {
    const t = getRenderTarget('svg-print');
    expect(t.interactive).toBe(false);
    expect(t.capabilities.tooltip).toBe('unsupported');
    expect(t.capabilities.animation).toBe('unsupported');
    expect(t.capabilities.linkage).toBe('unsupported');
    expect(Array.isArray(t.supportedTypes)).toBe(true);
    expect(t.supportedTypes).not.toContain('wordcloud');
    expect(t.supportedTypes).toContain('bar');
  });
  it('every target id matches its key', () => {
    for (const [id, target] of Object.entries(CAPABILITY_MATRIX)) {
      expect(target.id).toBe(id);
    }
  });
});

// --- validation --------------------------------------------------------------

describe('validateChartSpecForTarget', () => {
  it('clean aggregated bar passes on echarts (no errors)', () => {
    const r = validateChartSpecForTarget(aggregatedBarSpec(), 'echarts');
    expect(r.ok).toBe(true);
    expect(r.errors).toEqual([]);
  });

  it('clean aggregated bar passes on svg-print (bounded + supported)', () => {
    const r = validateChartSpecForTarget(aggregatedBarSpec(), 'svg-print');
    expect(r.ok).toBe(true);
  });

  it('unsupported type on svg-print → UNSUPPORTED_TYPE error', () => {
    const spec = { ...aggregatedBarSpec(), type: 'wordcloud' as const };
    const r = validateChartSpecForTarget(spec, 'svg-print');
    expect(r.ok).toBe(false);
    expect(r.errors.map((e) => e.code)).toContain('UNSUPPORTED_TYPE');
  });

  it('unbounded dataset (no agg, no limit) blocks on svg-print but degrades on echarts', () => {
    const unbounded: ChartSpec = {
      type: 'line',
      dataSource: { type: 'api', url: '/x' }, // no limit, no aggregation
      dimensions: [{ field: 'ts', role: 'category' }],
      measures: [{ field: 'v' }], // no aggregation
    };
    const print = validateChartSpecForTarget(unbounded, 'svg-print');
    expect(print.ok).toBe(false);
    expect(print.errors.map((e) => e.code)).toContain('UNBOUNDED_DATASET');
    expect(print.errors.find((e) => e.code === 'UNBOUNDED_DATASET')?.fallback).toBe('aggregation');

    const screen = validateChartSpecForTarget(unbounded, 'echarts');
    expect(screen.ok).toBe(true);
    expect(screen.degradations.map((d) => d.capability)).toContain('largeDataset');
  });

  it('static data is never unbounded', () => {
    const spec: ChartSpec = {
      type: 'bar',
      dataSource: { type: 'static', staticData: [{ a: 1 }] },
      dimensions: [{ field: 'a', role: 'category' }],
      measures: [{ field: 'a' }],
    };
    expect(validateChartSpecForTarget(spec, 'svg-print').ok).toBe(true);
  });

  it('interactive features degrade (not error) on svg-print', () => {
    const spec: ChartSpec = {
      ...aggregatedBarSpec(),
      interaction: { tooltip: true, refreshIntervalMs: 5000, linkage: { enabled: true } },
      drilldown: { enabled: true, action: 'filter' },
      visual: { colorTokens: ['accent'] },
    };
    const r = validateChartSpecForTarget(spec, 'svg-print');
    expect(r.ok).toBe(true); // all degradable, none blocking
    const caps = r.degradations.map((d) => d.capability);
    expect(caps).toContain('tooltip');
    expect(caps).toContain('drilldown');
    expect(caps).toContain('linkage');
    expect(caps).toContain('animation'); // refresh
    expect(caps).toContain('theme');
  });
});

// --- adapter -----------------------------------------------------------------

describe('chartConfigToSpec (coverage proof + leak surfacing)', () => {
  const baseConfig: ChartConfig = {
    id: 'c1',
    type: 'bar',
    title: 'Sales',
    dataSource: {
      type: 'aggregate',
      modelCode: 'sale',
      dimensions: ['region', 'channel'],
      metrics: [{ field: 'amount', aggregation: 'sum', alias: 'total' }],
      filters: [{ field: 'year', operator: 'eq', value: 2026 }],
      limit: 50,
    },
    refreshInterval: 30000,
  };

  it('maps dataSource dimensions/metrics to ChartSpec dimensions/measures', () => {
    const { spec } = chartConfigToSpec(baseConfig);
    expect(spec.type).toBe('bar');
    expect(spec.dimensions).toEqual([
      { field: 'region', role: 'category' },
      { field: 'channel', role: 'series' },
    ]);
    expect(spec.measures).toEqual([{ field: 'amount', aggregation: 'sum', label: 'total' }]);
    expect(spec.filters).toHaveLength(1);
    expect(spec.interaction?.refreshIntervalMs).toBe(30000);
  });

  it('the produced spec is renderer-agnostic', () => {
    const { spec } = chartConfigToSpec(baseConfig);
    expect(() => assertRendererAgnostic(spec)).not.toThrow();
  });

  it('surfaces chartOptions as a dropped renderer leak, and the spec stays clean', () => {
    const { spec, warnings } = chartConfigToSpec({
      ...baseConfig,
      chartOptions: { series: [{ type: 'bar' }], color: ['#f00'] },
    });
    expect(warnings.map((w) => w.code)).toContain('RENDERER_LEAK_DROPPED');
    expect(findRendererLeaks(spec)).toEqual([]); // leak did NOT bleed into the spec
  });

  it('pie first dimension is a name role', () => {
    const { spec } = chartConfigToSpec({
      ...baseConfig,
      type: 'pie',
      dataSource: { ...baseConfig.dataSource, dimensions: ['region'] },
    });
    expect(spec.dimensions[0]).toEqual({ field: 'region', role: 'name' });
  });

  it('throws for non-chart display widget types', () => {
    expect(() => chartConfigToSpec({ ...baseConfig, type: 'number' as never })).toThrow(/not a ChartSpec type/);
  });
});

describe('bindingFromFields (DSL chartConfig field binding)', () => {
  it('categoryField + valueField → category dimension + measure', () => {
    expect(bindingFromFields({ categoryField: 'month', valueField: 'amt' })).toEqual({
      dimensions: [{ field: 'month', role: 'category' }],
      measures: [{ field: 'amt' }],
    });
  });

  it('legacy xField/yField with groupField → category + series + measure', () => {
    const r = bindingFromFields({ xField: 'day', yField: ['a', 'b'], groupField: 'team' });
    expect(r.dimensions).toEqual([
      { field: 'day', role: 'category' },
      { field: 'team', role: 'series' },
    ]);
    expect(r.measures).toEqual([{ field: 'a' }, { field: 'b' }]);
  });

  it('pie nameField → name dimension', () => {
    const r = bindingFromFields({ nameField: 'region', valueField: 'sales' }, 'pie');
    expect(r.dimensions).toEqual([{ field: 'region', role: 'name' }]);
    expect(r.measures).toEqual([{ field: 'sales' }]);
  });
});
