// echarts-SSR (ssr:true) renders to an SVG string without touching the DOM, so it
// runs fine under the project-default jsdom env (which the shared setup file needs).
import { describe, it, expect } from 'vitest';
import {
  aggregateChartRows,
  renderBarcodeSvg,
  reportChartBlockToChartSpec,
  renderReportChartSvg,
} from '../print-render';

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

  it('maps canonical report DSL shape C (top-level chartType/categoryField/valueField)', () => {
    const spec = reportChartBlockToChartSpec({
      blockType: 'chart',
      chartType: 'bar',
      categoryField: 'status',
      valueField: 'cases',
    });
    expect(spec.type).toBe('bar');
    expect(spec.dimensions[0].field).toBe('status');
    expect(spec.measures[0].field).toBe('cases');
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

describe('aggregateChartRows (matches backend aggregateChartMetrics)', () => {
  const statusChart = {
    blockType: 'chart',
    chartType: 'bar',
    categoryField: 'status',
    valueField: 'cases',
    aggregation: 'sum',
  };
  const rows = [
    { region: 'North', status: 'Open', cases: 12 },
    { region: 'North', status: 'Closed', cases: 3 },
    { region: 'South', status: 'Open', cases: 9 },
  ];

  it('sums valueField grouped by category, sorted by category', () => {
    expect(aggregateChartRows(statusChart, rows)).toEqual([
      { status: 'Closed', cases: 3 },
      { status: 'Open', cases: 21 },
    ]);
  });

  it('supports avg / count / min / max', () => {
    expect(aggregateChartRows({ ...statusChart, aggregation: 'avg' }, rows)).toEqual([
      { status: 'Closed', cases: 3 },
      { status: 'Open', cases: 10.5 },
    ]);
    expect(aggregateChartRows({ ...statusChart, aggregation: 'count' }, rows)).toEqual([
      { status: 'Closed', cases: 1 },
      { status: 'Open', cases: 2 },
    ]);
    expect(aggregateChartRows({ ...statusChart, aggregation: 'min' }, rows)).toEqual([
      { status: 'Closed', cases: 3 },
      { status: 'Open', cases: 9 },
    ]);
    expect(aggregateChartRows({ ...statusChart, aggregation: 'max' }, rows)).toEqual([
      { status: 'Closed', cases: 3 },
      { status: 'Open', cases: 12 },
    ]);
  });

  it('defaults to sum, maps missing category to "Other" and non-number value to 0', () => {
    const result = aggregateChartRows(
      { blockType: 'chart', categoryField: 'status', valueField: 'cases' },
      [{ status: 'Open', cases: 5 }, { cases: 7 }, { status: 'Open', cases: 'n/a' }],
    );
    expect(result).toEqual([
      { status: 'Open', cases: 5 }, // 5 + 0 (non-number 'n/a')
      { status: 'Other', cases: 7 }, // missing category -> Other
    ]);
  });
});

describe('renderBarcodeSvg (real CODE128 barcode, no DOM)', () => {
  it('renders a CODE128 barcode as an SVG with bars + the human-readable value', () => {
    const svg = renderBarcodeSvg('OPS-2026-EXPORT', { format: 'code128' });
    expect(svg).toContain('<svg class="barcode"');
    expect(svg).toContain('<rect'); // real bars
    expect(svg).toContain('OPS-2026-EXPORT'); // displayValue label
  });

  it('omits the value label when displayValue is false', () => {
    const svg = renderBarcodeSvg('ABC123', { displayValue: false });
    expect(svg).toContain('<svg class="barcode"');
    expect(svg).not.toContain('<text');
  });

  it('degrades to a text label for an empty value or a non-code128 format', () => {
    expect(renderBarcodeSvg('')).toContain('barcode-text');
    const ean = renderBarcodeSvg('5901234123457', { format: 'ean13' });
    expect(ean).toContain('barcode-text');
    expect(ean).toContain('5901234123457');
  });
});
