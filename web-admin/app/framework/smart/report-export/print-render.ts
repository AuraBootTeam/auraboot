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
import * as echarts from 'echarts';
import {
  isChartSpecType,
  type ChartSpec,
  type ChartSpecType,
  type ChartDimension,
} from '../charts/chart-spec';
import { chartSpecToEChartsOption } from '../charts/chart-spec-echarts';

/**
 * The two legacy report chart-block shapes seen in report DSL (see Phase 3 spec
 * §2). Both carry a chart type, a category/x field and a value/y field — far
 * simpler than a full ChartConfig.
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
  /** dataSource id (resolved to rows elsewhere; not needed for rendering) */
  dataSource?: string;
}

/**
 * Map a legacy report chart block onto a platform `ChartSpec`. Unknown/illegal
 * chart types degrade to `bar` rather than producing a silently wrong chart.
 */
export function reportChartBlockToChartSpec(block: ReportChartBlock): ChartSpec {
  const rawType = block.chartType ?? block.config?.chartType ?? 'bar';
  const type: ChartSpecType = isChartSpecType(rawType) ? rawType : 'bar';

  const categoryField =
    block.chartConfig?.xField ?? block.config?.categoryField ?? block.categoryField ?? 'category';
  const valueField =
    block.chartConfig?.yField ?? block.config?.valueField ?? block.valueField ?? 'value';

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
