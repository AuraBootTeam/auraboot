/**
 * Phase 3 report-export print-HTML assembler (Option A', slice 2 — see
 * DDR-2026-06-21-report-export-rendering-source-of-truth).
 *
 * Walks a report block-tree + resolved data sets and produces a SELF-CONTAINED
 * print HTML document (data inlined, no auth, no live route) plus the Chromium
 * running header/footer templates. The document is later painted to PDF by
 * headless Chromium (slice 2b: renderHtmlToPdf).
 *
 * Competitive differentiators that the legacy PDFBox text-line path cannot do
 * and that Option B (Java SVG port) would also miss:
 *   - real vector charts (echarts-SSR, via print-render.ts — single source of truth)
 *   - running page-header / page-footer with computed page numbers
 *   - per-page watermark
 *   - real <table> with column labels (no raw field-code leak), HTML-escaped cells
 */
import {
  reportChartBlockToChartSpec,
  renderReportChartSvg,
  type ReportChartBlock,
} from './print-render';

export type PrintRow = Record<string, unknown>;
export type PrintDataSets = Record<string, PrintRow[]>;

export interface ReportColumnDef {
  field: string;
  label?: string;
}

export interface ReportPrintBlock extends ReportChartBlock {
  blockType: string;
  title?: string;
  dataSource?: string;
  columns?: ReportColumnDef[];
  /** rich-text / page-header / page-footer body */
  content?: string;
  /** watermark / page-header / page-footer fallback text */
  text?: string;
}

export interface ReportPrintModel {
  title?: string;
  /** block list. The canonical report DSL names this `body`; `blocks` is accepted too. */
  blocks?: ReportPrintBlock[];
  body?: ReportPrintBlock[];
}

export interface PrintDocument {
  /** Full self-contained <html> document with watermark + body blocks. */
  html: string;
  /** Chromium running header fragment (from a page-header block), if any. */
  headerTemplate?: string;
  /** Chromium running footer fragment (from a page-footer block), if any. */
  footerTemplate?: string;
}

function escapeHtml(value: unknown): string {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function rowsFor(block: ReportPrintBlock, dataSets: PrintDataSets): PrintRow[] {
  return block.dataSource ? (dataSets[block.dataSource] ?? []) : [];
}

/** Mirror of the backend resolveColumns: declared columns, else derive from row keys. */
function resolveColumns(block: ReportPrintBlock, rows: PrintRow[]): ReportColumnDef[] {
  const declared = (block.columns ?? []).filter((c) => c?.field);
  if (declared.length || rows.length === 0) {
    return declared;
  }
  return Object.keys(rows[0]).map((field) => ({ field }));
}

/** page-header / page-footer / watermark text: content -> text -> title. */
function artifactText(block: ReportPrintBlock): string {
  return block.content ?? block.text ?? block.title ?? '';
}

function renderBlockTitle(title?: string): string {
  return title ? `<h2>${escapeHtml(title)}</h2>` : '';
}

function renderChart(block: ReportPrintBlock, dataSets: PrintDataSets): string {
  const spec = reportChartBlockToChartSpec(block);
  const svg = renderReportChartSvg(spec, rowsFor(block, dataSets)); // trusted, self-generated
  return `<section class="block chart">${renderBlockTitle(block.title)}<div class="chart-canvas">${svg}</div></section>`;
}

function renderTable(block: ReportPrintBlock, dataSets: PrintDataSets): string {
  const rows = rowsFor(block, dataSets);
  const cols = resolveColumns(block, rows);
  const head = cols.map((c) => `<th>${escapeHtml(c.label ?? c.field)}</th>`).join('');
  const body = rows
    .map((r) => `<tr>${cols.map((c) => `<td>${escapeHtml(r[c.field])}</td>`).join('')}</tr>`)
    .join('');
  const empty = `<tr><td colspan="${Math.max(cols.length, 1)}">无数据</td></tr>`;
  return `<section class="block table">${renderBlockTitle(block.title)}<table><thead><tr>${head}</tr></thead><tbody>${body || empty}</tbody></table></section>`;
}

function renderRichText(block: ReportPrintBlock): string {
  const paras = (block.content ?? '')
    .split(/\r?\n/)
    .filter((p) => p.trim())
    .map((p) => `<p>${escapeHtml(p)}</p>`)
    .join('');
  return `<section class="block rich-text">${renderBlockTitle(block.title)}${paras}</section>`;
}

function renderWatermark(block: ReportPrintBlock): string {
  return `<div class="watermark">${escapeHtml(artifactText(block))}</div>`;
}

function runningHeader(block: ReportPrintBlock): string {
  return `<div class="print-running" style="font-size:9px;width:100%;padding:0 14mm;color:#6b7280;">${escapeHtml(
    artifactText(block),
  )}</div>`;
}

function runningFooter(block: ReportPrintBlock): string {
  const text = escapeHtml(artifactText(block));
  // Chromium replaces pageNumber/totalPages spans -> a true running footer.
  return `<div class="print-running" style="font-size:9px;width:100%;padding:0 14mm;color:#6b7280;display:flex;justify-content:space-between;"><span>${text}</span><span>第 <span class="pageNumber"></span> / <span class="totalPages"></span> 页</span></div>`;
}

function shell(model: ReportPrintModel, body: string, watermark: string): string {
  return `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><style>
  * { font-family: -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif; box-sizing: border-box; }
  body { margin: 0; color: #1f2937; }
  .watermark { position: fixed; top: 40%; left: 18%; font-size: 64px; color: rgba(0,0,0,0.06); transform: rotate(-30deg); z-index: 0; pointer-events: none; }
  h1 { font-size: 20px; }
  .block { margin: 0 0 18px; position: relative; z-index: 1; }
  table { width: 100%; border-collapse: collapse; font-size: 12px; }
  th, td { border: 1px solid #d1d5db; padding: 6px 8px; text-align: left; }
  th { background: #f3f4f6; }
  tr { break-inside: avoid; }
  .chart-canvas svg { max-width: 100%; height: auto; }
</style></head><body>
  ${watermark}
  ${model.title ? `<h1>${escapeHtml(model.title)}</h1>` : ''}
  ${body}
</body></html>`;
}

/**
 * Assemble a report model + resolved data sets into a print-ready document.
 * Charts, tables, rich-text render into the body; watermark is a fixed element;
 * page-header / page-footer are lifted into Chromium running header/footer
 * templates (NOT the body).
 */
export function renderReportToPrintDocument(
  model: ReportPrintModel,
  dataSets: PrintDataSets,
): PrintDocument {
  let headerTemplate: string | undefined;
  let footerTemplate: string | undefined;
  let watermark = '';
  const body: string[] = [];

  for (const block of model.blocks ?? model.body ?? []) {
    switch (block.blockType) {
      case 'chart':
        body.push(renderChart(block, dataSets));
        break;
      case 'table':
        body.push(renderTable(block, dataSets));
        break;
      case 'rich-text':
        body.push(renderRichText(block));
        break;
      case 'page-header':
        headerTemplate = runningHeader(block);
        break;
      case 'page-footer':
        footerTemplate = runningFooter(block);
        break;
      case 'watermark':
        watermark = renderWatermark(block);
        break;
      default:
        // grouped-table / cross-tab / stat-card / barcode are slice 2b: render a
        // visible placeholder rather than silently dropping the block's content.
        body.push(
          `<section class="block unsupported" data-block-type="${escapeHtml(block.blockType)}"><em>[${escapeHtml(block.blockType)}] 渲染待补(slice 2b)</em></section>`,
        );
        break;
    }
  }

  return { html: shell(model, body.join('\n'), watermark), headerTemplate, footerTemplate };
}
