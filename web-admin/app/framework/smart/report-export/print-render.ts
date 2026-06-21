/**
 * Phase 3 report-export print renderer (Option A', see
 * DDR-2026-06-21-report-export-rendering-source-of-truth).
 *
 * Single rendering source of truth: report chart blocks are mapped to the
 * platform `ChartSpec` and rendered by the SAME `chartSpecToEChartsOption`
 * builder the on-screen charts use, then rasterised to a static SVG via
 * echarts server-side rendering (ssr:true) — no browser, no second renderer,
 * no drift. The SVG is later embedded into a self-contained print HTML and
 * painted to PDF by headless Chromium (slices 2-3).
 *
 * This module is the server-side render entrypoint; it must stay free of
 * browser-only globals so it can run inside a Node renderer process.
 */
import { createRequire } from 'node:module';
import * as echarts from 'echarts';
import {
  isChartSpecType,
  type ChartSpec,
  type ChartSpecType,
  type ChartDimension,
} from '../charts/chart-spec';
import { chartSpecToEChartsOption } from '../charts/chart-spec-echarts';

/**
 * The report chart-block shapes seen in report DSL (see Phase 3 spec §2). All
 * carry a chart type, a category/x field and a value/y field — far simpler than
 * a full ChartConfig.
 */
export interface ReportChartBlock {
  blockType?: string;
  title?: string;
  /** shape A: top-level chartType + chartConfig */
  chartType?: string;
  chartConfig?: { xField?: string; yField?: string; seriesField?: string };
  /** shape B: nested config */
  config?: { chartType?: string; categoryField?: string; valueField?: string };
  /** shape C (canonical report DSL): top-level category/value fields */
  categoryField?: string;
  valueField?: string;
  /** aggregation over valueField per category (sum|avg|count|min|max; default sum) */
  aggregation?: string;
  /** dataSource id (resolved to rows elsewhere; not needed for rendering) */
  dataSource?: string;
}

/** Resolve the category field across the three report chart-block shapes. */
function categoryFieldOf(block: ReportChartBlock): string {
  return (
    block.chartConfig?.xField ?? block.config?.categoryField ?? block.categoryField ?? 'category'
  );
}

/** Resolve the value field across the three report chart-block shapes. */
function valueFieldOf(block: ReportChartBlock): string {
  return block.chartConfig?.yField ?? block.config?.valueField ?? block.valueField ?? 'value';
}

/**
 * Map a legacy report chart block onto a platform `ChartSpec`. Unknown/illegal
 * chart types degrade to `bar` rather than producing a silently wrong chart.
 */
export function reportChartBlockToChartSpec(block: ReportChartBlock): ChartSpec {
  const rawType = block.chartType ?? block.config?.chartType ?? 'bar';
  const type: ChartSpecType = isChartSpecType(rawType) ? rawType : 'bar';

  const categoryField = categoryFieldOf(block);
  const valueField = valueFieldOf(block);

  const dimensions: ChartDimension[] = [
    // pie's first dimension is a slice name, every other type is an axis category.
    { field: categoryField, role: type === 'pie' ? 'name' : 'category' },
  ];

  return {
    type,
    title: block.title,
    // dataSource is unused by the renderer; rows are resolved server-side and
    // passed to renderReportChartSvg directly. A minimal static binding keeps
    // the ChartSpec contract satisfied.
    dataSource: { type: 'static' },
    dimensions,
    measures: [{ field: valueField }],
    visual: { dataLabels: true },
  };
}

/** Mirror of the backend aggregateNumbers (sum|avg|count|min|max; empty -> 0). */
export function aggregateNumbers(values: number[], aggregation: string): number {
  if (values.length === 0) {
    return 0;
  }
  switch (aggregation) {
    case 'avg':
      return values.reduce((a, b) => a + b, 0) / values.length;
    case 'count':
      return values.length;
    case 'min':
      return Math.min(...values);
    case 'max':
      return Math.max(...values);
    default:
      return values.reduce((a, b) => a + b, 0);
  }
}

/**
 * Aggregate report chart rows by category, mirroring the backend
 * `aggregateChartMetrics` so the WYSIWYG chart matches the legacy data export
 * (single aggregation contract). Groups by categoryField (missing -> "Other"),
 * aggregates valueField (non-number -> 0) with the block's aggregation (default
 * sum), sorted by category. Without this, an aggregated chart (e.g. sum cases by
 * status over un-aggregated rows) would plot duplicate categories / wrong values.
 */
export function aggregateChartRows(
  block: ReportChartBlock,
  rows: Record<string, unknown>[],
): Record<string, unknown>[] {
  const categoryField = categoryFieldOf(block);
  const valueField = valueFieldOf(block);
  const aggregation = (block.aggregation ?? 'sum').toLowerCase();

  const groups = new Map<string, number[]>();
  for (const row of rows) {
    const rawCategory = row[categoryField];
    const category =
      rawCategory === null || rawCategory === undefined || rawCategory === ''
        ? 'Other'
        : String(rawCategory);
    const rawValue = row[valueField];
    const value = typeof rawValue === 'number' ? rawValue : 0;
    const bucket = groups.get(category);
    if (bucket) {
      bucket.push(value);
    } else {
      groups.set(category, [value]);
    }
  }

  return (
    [...groups.entries()]
      .map(([category, values]) => ({
        [categoryField]: category,
        [valueField]: aggregateNumbers(values, aggregation),
      }))
      // backend sorts by category label (String.compareTo == UTF-16 code-unit order)
      .sort((a, b) => {
        const x = String(a[categoryField]);
        const y = String(b[categoryField]);
        return x < y ? -1 : x > y ? 1 : 0;
      })
  );
}

export interface RenderChartSvgOptions {
  width?: number;
  height?: number;
}

/**
 * Render a `ChartSpec` + resolved rows to a static SVG string using the real
 * on-screen echarts option builder via server-side rendering. Zero DOM, zero
 * interaction — exactly what a print/PDF target needs.
 */
export function renderReportChartSvg(
  spec: ChartSpec,
  rows: Record<string, unknown>[],
  opts: RenderChartSvgOptions = {},
): string {
  const option = chartSpecToEChartsOption(spec, rows);
  const chart = echarts.init(null as unknown as HTMLElement, null, {
    renderer: 'svg',
    ssr: true,
    width: opts.width ?? 680,
    height: opts.height ?? 360,
  });
  try {
    chart.setOption(option as Parameters<typeof chart.setOption>[0]);
    return chart.renderToSVGString();
  } finally {
    chart.dispose();
  }
}

function escapeXml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

export interface RenderBarcodeOptions {
  format?: string;
  /** module (narrow bar) width in px */
  moduleWidth?: number;
  height?: number;
  /** render the human-readable value below the bars (default true) */
  displayValue?: boolean;
}

/**
 * Render a barcode value to a static SVG using jsbarcode's encoder (no DOM), so
 * the export shows a real scannable barcode instead of the legacy text line.
 * CODE128 (the default + most common for internal report codes) is drawn
 * natively; any other format or an invalid value degrades gracefully to a
 * monospace text label (never throws / never a wrong barcode).
 */
export function renderBarcodeSvg(value: string, opts: RenderBarcodeOptions = {}): string {
  const format = (opts.format ?? 'code128').toLowerCase();
  const moduleWidth = opts.moduleWidth ?? 2;
  const height = opts.height ?? 60;
  const showText = opts.displayValue !== false;
  const textBlock = (v: string) => `<span class="barcode-text">${escapeXml(v)}</span>`;

  if (!value || format !== 'code128') {
    return textBlock(value);
  }

  try {
    const require = createRequire(import.meta.url);
    const encoders: {
      CODE128: new (text: string, options: object) => { encode(): { data: string } };
    } = require('jsbarcode/bin/barcodes/CODE128/index.js');
    const binary = new encoders.CODE128(value, {}).encode().data;
    if (!binary || !/[01]/.test(binary)) {
      return textBlock(value);
    }

    const totalWidth = binary.length * moduleWidth;
    const textHeight = showText ? 18 : 0;
    let x = 0;
    let bars = '';
    for (const bit of binary) {
      if (bit === '1') {
        bars += `<rect x="${x}" y="0" width="${moduleWidth}" height="${height}" fill="#000000"/>`;
      }
      x += moduleWidth;
    }
    const label = showText
      ? `<text x="${totalWidth / 2}" y="${height + 14}" text-anchor="middle" font-family="monospace" font-size="12" fill="#000000">${escapeXml(value)}</text>`
      : '';
    return `<svg class="barcode" xmlns="http://www.w3.org/2000/svg" width="${totalWidth}" height="${height + textHeight}" viewBox="0 0 ${totalWidth} ${height + textHeight}">${bars}${label}</svg>`;
  } catch {
    // encoder unavailable or value invalid for the format -> graceful text label
    return textBlock(value);
  }
}
