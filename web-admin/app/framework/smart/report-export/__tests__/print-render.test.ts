// echarts-SSR (ssr:true) renders to an SVG string without touching the DOM, so it
// runs fine under the project-default jsdom env (which the shared setup file needs).
import { describe, it, expect } from 'vitest';
import { reportChartBlockToChartSpec, renderReportChartSvg } from '../print-render';

describe('reportChartBlockToChartSpec', () => {
  it('maps report chart block shape A (chartType + chartConfig.xField/yField)', () => {
    const spec = reportChartBlockToChartSpec({
      blockType: 'chart',
      chartType: 'bar',
      title: '各月营收',
      chartConfig: { xField: 'month', yField: 'amount' },
    });
    expect(spec.type).toBe('bar');
    expect(spec.dimensions[0]).toMatchObject({ field: 'month', role: 'category' });
    expect(spec.measures[0].field).toBe('amount');
  });

  it('maps report chart block shape B (config.chartType/categoryField/valueField)', () => {
    const spec = reportChartBlockToChartSpec({
      blockType: 'chart',
      config: { chartType: 'pie', categoryField: 'region', valueField: 'sales' },
    });
    expect(spec.type).toBe('pie');
    // pie's first dimension is a slice name, not an axis category.
    expect(spec.dimensions[0]).toMatchObject({ field: 'region', role: 'name' });
    expect(spec.measures[0].field).toBe('sales');
  });

  it('falls back to bar for an unknown/illegal chartType (never a silently wrong chart)', () => {
    const spec = reportChartBlockToChartSpec({ chartType: 'definitely-not-a-chart' });
    expect(spec.type).toBe('bar');
  });
});

describe('renderReportChartSvg (echarts-SSR via the real frontend renderer)', () => {
  it('produces a vector SVG (path), not the legacy Category/Value data table', () => {
    const spec = reportChartBlockToChartSpec({
      chartType: 'bar',
      title: '季度营收',
      chartConfig: { xField: 'q', yField: 'rev' },
    });
    const rows = [
      { q: 'Q1', rev: 100 },
      { q: 'Q2', rev: 140 },
      { q: 'Q3', rev: 120 },
    ];
    const svg = renderReportChartSvg(spec, rows, { width: 400, height: 240 });
    expect(svg).toContain('<svg');
    expect(svg).toContain('<path');
    // The whole point of Phase 3: this is a real chart, not the "Category | Value" dump.
    expect(svg).not.toContain('Category');
  });
});
