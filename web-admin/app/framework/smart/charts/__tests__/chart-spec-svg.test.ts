/**
 * B2c — ChartSpec → print-safe SVG renderer tests.
 *
 * The SVG print target (report/PDF) renders a ChartSpec to a STATIC, dependency-
 * free SVG string: no <script>, no animation, no interactivity. Unsupported types
 * / unbounded datasets degrade to a labeled table fallback (never a silent wrong
 * chart — see validateChartSpecForTarget).
 */
import { describe, expect, it } from 'vitest';
import type { ChartSpec } from '../chart-spec';
import { renderChartSpecToSvg } from '../chart-spec-svg';

const rows = [
  { category: 'A', amount: 10 },
  { category: 'B', amount: 30 },
  { category: 'C', amount: 20 },
];

function barSpec(overrides: Partial<ChartSpec> = {}): ChartSpec {
  return {
    type: 'bar',
    dataSource: { type: 'static', staticData: rows },
    dimensions: [{ field: 'category', role: 'category' }],
    measures: [{ field: 'amount', aggregation: 'sum' }],
    ...overrides,
  };
}

describe('renderChartSpecToSvg', () => {
  it('renders a vertical bar chart with one rect per row and category labels', () => {
    const { svg, fallback } = renderChartSpecToSvg(barSpec(), rows);
    expect(fallback).toBeNull();
    expect(svg.startsWith('<svg')).toBe(true);
    expect((svg.match(/<rect/g) ?? []).length).toBeGreaterThanOrEqual(rows.length);
    expect(svg).toContain('A');
    expect(svg).toContain('B');
    expect(svg).toContain('C');
  });

  it('is print-safe: no script / event handlers / animation', () => {
    const { svg } = renderChartSpecToSvg(barSpec(), rows);
    expect(svg).not.toMatch(/<script/i);
    expect(svg).not.toMatch(/onclick|onload|onmouse/i);
    expect(svg).not.toMatch(/<animate|@keyframes|transition:/i);
  });

  it('honors horizontal orientation (visual.orientation=horizontal)', () => {
    const v = renderChartSpecToSvg(barSpec(), rows).svg;
    const h = renderChartSpecToSvg(barSpec({ visual: { orientation: 'horizontal' } }), rows).svg;
    // horizontal vs vertical produce different bar geometry
    expect(h).not.toEqual(v);
    expect(h).toContain('data-orientation="horizontal"');
  });

  it('renders a pie chart with one path slice per row', () => {
    const spec = barSpec({ type: 'pie', dimensions: [{ field: 'category', role: 'name' }] });
    const { svg, fallback } = renderChartSpecToSvg(spec, rows);
    expect(fallback).toBeNull();
    expect((svg.match(/<path/g) ?? []).length).toBe(rows.length);
  });

  it('renders a line chart with a polyline', () => {
    const { svg } = renderChartSpecToSvg(barSpec({ type: 'line' }), rows);
    expect(svg).toMatch(/<polyline|<path/);
  });

  it('renders a table when type=table', () => {
    const { svg } = renderChartSpecToSvg(barSpec({ type: 'table' }), rows);
    expect(svg).toContain('data-render="table"');
  });

  it('degrades an unsupported (for svg-print) type to a table fallback, not a wrong chart', () => {
    // wordcloud is NOT in the svg-print supportedTypes
    const { svg, fallback } = renderChartSpecToSvg(barSpec({ type: 'wordcloud' }), rows);
    expect(fallback).toBe('table');
    expect(svg).toContain('data-render="table"');
  });

  it('blocks an unbounded dataset (no aggregation + no limit, non-static) with a table fallback', () => {
    const spec = barSpec({
      dataSource: { type: 'aggregate', modelCode: 'm' },
      measures: [{ field: 'amount' }], // no aggregation
    });
    const { fallback } = renderChartSpecToSvg(spec, rows);
    expect(fallback).toBe('table');
  });

  it('reports degradations (e.g. tooltip omitted) without blocking', () => {
    const spec = barSpec({ interaction: { tooltip: true } });
    const { degradations, fallback } = renderChartSpecToSvg(spec, rows);
    expect(fallback).toBeNull();
    expect(degradations.some((d) => d.capability === 'tooltip')).toBe(true);
  });

  describe('snapshot goldens', () => {
    it('bar', () => {
      expect(renderChartSpecToSvg(barSpec(), rows, { width: 400, height: 240 }).svg).toMatchSnapshot();
    });
    it('pie', () => {
      const spec = barSpec({ type: 'pie', dimensions: [{ field: 'category', role: 'name' }] });
      expect(renderChartSpecToSvg(spec, rows, { width: 300, height: 300 }).svg).toMatchSnapshot();
    });
  });
});
