import type {
  ReportBand,
  BandElementStyle,
} from '../../../plugins/core-designer/components/report-designer/types';
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
  aggregateChartRows,
  aggregateNumbers,
  renderBarcodeSvg,
  reportChartBlockToChartSpec,
  renderReportChartSvg,
  type ReportChartBlock,
} from './print-render';

export type PrintRow = Record<string, unknown>;
export type PrintDataSets = Record<string, PrintRow[]>;

export interface ReportColumnDef {
  field: string;
  label?: string;
  format?: string;
  align?: string;
  width?: number;
}

export interface ReportPrintSummary {
  enabled?: boolean;
  label?: string;
  columns?: Array<{ field: string; aggregation?: string; format?: string }>;
}

export interface ReportPrintBlock extends ReportChartBlock {
  summary?: ReportPrintSummary;
  groupSubtotal?: ReportPrintSummary;
  grandTotal?: ReportPrintSummary;
  blockType: string;
  title?: string;
  dataSource?: string;
  columns?: ReportColumnDef[];
  /** rich-text / page-header / page-footer body */
  content?: string;
  /** watermark / page-header / page-footer fallback text */
  text?: string;
  /** stat-card label */
  label?: string;
  /** grouped-table group key */
  groupByField?: string;
  /** cross-tab pivot axes */
  rowField?: string;
  rowLabel?: string;
  columnField?: string;
  columnLabel?: string;
  totalLabel?: string;
  showRowTotal?: boolean;
  showColumnTotal?: boolean;
  /** barcode */
  staticValue?: string;
  field?: string;
  format?: string;
  width?: number;
  height?: number;
  displayValue?: boolean;
}

export interface ReportPrintModel {
  header?: ReportBand;
  footer?: ReportBand;
  page?: {
    size?: string;
    orientation?: 'portrait' | 'landscape';
    margin?: { top?: number; right?: number; bottom?: number; left?: number };
  };
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
  headerHeightMm?: number;
  footerHeightMm?: number;
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
  // Aggregate by category (matches the backend) before plotting, so an aggregated
  // chart over un-aggregated rows renders correct values, not duplicate bars.
  const rows = aggregateChartRows(block, rowsFor(block, dataSets));
  // The section owns the heading; honor the designer's physical chart dimensions.
  const svg = renderReportChartSvg({ ...spec, title: undefined }, rows, {
    width: block.width,
    height: block.height,
  }); // trusted, self-generated
  return `<section class="block chart">${renderBlockTitle(block.title)}<div class="chart-canvas">${svg}</div></section>`;
}

/** Explicit formats use the same currency/percent contract as the designer. */
function formattedValue(value: unknown, format?: string): string {
  if (value === null || value === undefined) return '';
  if (typeof value !== 'number' || !Number.isFinite(value)) return String(value);
  if (format === 'currency')
    return `¥${value.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
  if (format === 'percent') return `${(value * 100).toFixed(1)}%`;
  if (format === 'number') return value.toLocaleString('zh-CN');
  return String(value);
}

/** Only typed DSL values may become CSS; report text is always escaped separately. */
function alignment(value?: string): string {
  if (value !== undefined && !['left', 'center', 'right'].includes(value))
    throw new Error('Invalid report alignment');
  return value || 'left';
}

function columnAttributes(column: ReportColumnDef, header = false): string {
  const styles: string[] = [];
  if (column.align) styles.push(`text-align:${alignment(column.align)}`);
  if (header && column.width !== undefined) {
    if (typeof column.width !== 'number' || !Number.isFinite(column.width) || column.width <= 0)
      throw new Error('Invalid report column width');
    styles.push(`width:${column.width}px`);
  }
  return styles.length ? ` style="${styles.join(';')}"` : '';
}

function bandStyle(style: BandElementStyle = {}): string {
  const styles: string[] = [];
  if (style.fontSize !== undefined) {
    if (!Number.isFinite(style.fontSize) || style.fontSize < 6 || style.fontSize > 48)
      throw new Error('Invalid report band font size');
    styles.push(`font-size:${style.fontSize}pt`);
  }
  if (style.fontWeight) {
    if (!['normal', 'bold'].includes(style.fontWeight))
      throw new Error('Invalid report band font weight');
    styles.push(`font-weight:${style.fontWeight}`);
  }
  if (style.color) {
    if (!/^(#[0-9a-f]{3,8}|[a-z]+)$/i.test(style.color))
      throw new Error('Invalid report band color');
    styles.push(`color:${style.color}`);
  }
  if (style.fontFamily) {
    if (!/^[\p{L}\p{N} ,'-]+$/u.test(style.fontFamily))
      throw new Error('Invalid report band font family');
    styles.push(`font-family:${style.fontFamily}`);
  }
  return styles.join(';');
}

function renderBand(band: ReportBand, running: boolean): string {
  if (
    !Number.isFinite(band.height) ||
    band.height < 5 ||
    band.height > 100 ||
    !Array.isArray(band.elements)
  )
    throw new Error('Invalid report band');
  const elements = band.elements
    .map((element) => {
      let content: string;
      switch (element.type) {
        case 'text':
          content = escapeHtml(element.content);
          break;
        case 'date':
          content = escapeHtml(new Date().toLocaleDateString('zh-CN'));
          break;
        case 'page-number':
          content = running ? '第 <span class="pageNumber"></span> 页' : '第 1 页';
          break;
        case 'image':
          // The renderer is self-contained: never fetch an arbitrary URL from a report.
          if (
            !/^data:image\/(png|jpeg|gif|webp);base64,[A-Za-z0-9+/=]+$/.test(element.content || '')
          )
            throw new Error('Report band images must be embedded raster data');
          content = `<img alt="" style="max-height:${band.height}mm;max-width:100%" src="${escapeHtml(element.content)}" />`;
          break;
        default:
          throw new Error('Unsupported report band element');
      }
      return `<div style="flex:1;min-width:0;text-align:${alignment(element.align)};${escapeHtml(bandStyle(element.style))}">${content}</div>`;
    })
    .join('');
  return `<div style="display:flex;align-items:center;gap:16px;width:100%;height:${band.height}mm">${elements}</div>`;
}

function bandTemplate(band: ReportBand, margin?: ReportPrintModel['page']): string {
  const left = margin?.margin?.left ?? 14;
  const right = margin?.margin?.right ?? 14;
  for (const value of [left, right])
    if (!Number.isFinite(value) || value < 0 || value > 100)
      throw new Error('Invalid report band margin');
  return `<div style="width:100%;padding:0 ${right}mm 0 ${left}mm;font-family:Arial,sans-serif;font-size:10pt;color:#374151;box-sizing:border-box">${renderBand(band, true)}</div>`;
}

function renderSummary(
  summary: ReportPrintSummary | undefined,
  cols: ReportColumnDef[],
  rows: PrintRow[],
  css: string,
): string {
  if (!summary?.enabled || !cols.length) return '';
  const cells = cols
    .map((column, index) => {
      const selected = summary.columns?.find((item) => item.field === column.field);
      const value = selected
        ? formattedValue(
            aggregateNumbers(
              rows.map((row) => Number(row[column.field]) || 0),
              selected.aggregation || 'sum',
            ),
            selected.format || column.format,
          )
        : index === 0
          ? summary.label || 'Total'
          : '';
      return `<td${columnAttributes(column)}>${escapeHtml(value)}</td>`;
    })
    .join('');
  return `<tr class="summary-row ${css}">${cells}</tr>`;
}

function renderTable(block: ReportPrintBlock, dataSets: PrintDataSets): string {
  const rows = rowsFor(block, dataSets);
  const cols = resolveColumns(block, rows);
  const head = cols
    .map((c) => `<th${columnAttributes(c, true)}>${escapeHtml(c.label ?? c.field)}</th>`)
    .join('');
  const body = rows
    .map(
      (r) =>
        `<tr>${cols.map((c) => `<td${columnAttributes(c)}>${escapeHtml(formattedValue(r[c.field], c.format))}</td>`).join('')}</tr>`,
    )
    .join('');
  const empty = `<tr><td colspan="${Math.max(cols.length, 1)}">无数据</td></tr>`;
  return `<section class="block table">${renderBlockTitle(block.title)}<table><thead><tr>${head}</tr></thead><tbody>${body || empty}${renderSummary(block.summary, cols, rows, 'table-summary')}</tbody></table></section>`;
}

function formatNumber(n: number): string {
  return Number.isInteger(n) ? String(n) : String(parseFloat(n.toFixed(2)));
}

function numericValue(value: unknown): number {
  return typeof value === 'number' ? value : 0;
}

function renderStatCard(block: ReportPrintBlock, dataSets: PrintDataSets): string {
  const valueField = block.valueField ?? 'value';
  const aggregation = (block.aggregation ?? 'sum').toLowerCase();
  const value = aggregateNumbers(
    rowsFor(block, dataSets).map((r) => numericValue(r[valueField])),
    aggregation,
  );
  const label = block.label ?? block.title ?? '';
  return `<section class="block stat-card">${block.title && block.label && block.title !== block.label ? renderBlockTitle(block.title) : ''}<div class="stat-value">${escapeHtml(block.format ? formattedValue(value, block.format) : formatNumber(value))}</div><div class="stat-label">${escapeHtml(label)}</div></section>`;
}

function renderGroupedTable(block: ReportPrintBlock, dataSets: PrintDataSets): string {
  const rows = rowsFor(block, dataSets);
  const cols = resolveColumns(block, rows);
  const groupBy = block.groupByField ?? '';
  const span = Math.max(cols.length, 1);

  const groups = new Map<string, PrintRow[]>();
  for (const r of rows) {
    const key = groupBy ? String(r[groupBy] ?? 'Other') : 'All';
    const bucket = groups.get(key);
    if (bucket) {
      bucket.push(r);
    } else {
      groups.set(key, [r]);
    }
  }

  const head = cols
    .map((c) => `<th${columnAttributes(c, true)}>${escapeHtml(c.label ?? c.field)}</th>`)
    .join('');
  const groupFieldLabel = cols.find(
    (c) => c.field === groupBy && c.label && c.label !== c.field,
  )?.label;
  const body = [...groups.entries()]
    .map(([key, grp]) => {
      const groupHeader = `<tr class="group-header"><td colspan="${span}">${escapeHtml(groupFieldLabel ? `${groupFieldLabel}: ` : '')}${escapeHtml(key)} (${grp.length})</td></tr>`;
      const groupRows = grp
        .map(
          (r) =>
            `<tr>${cols.map((c) => `<td${columnAttributes(c)}>${escapeHtml(formattedValue(r[c.field], c.format))}</td>`).join('')}</tr>`,
        )
        .join('');
      return (
        groupHeader + groupRows + renderSummary(block.groupSubtotal, cols, grp, 'group-subtotal')
      );
    })
    .join('');
  return `<section class="block grouped-table">${renderBlockTitle(block.title)}<table><thead><tr>${head}</tr></thead><tbody>${body}${renderSummary(block.grandTotal, cols, rows, 'grand-total')}</tbody></table></section>`;
}

function renderCrossTab(block: ReportPrintBlock, dataSets: PrintDataSets): string {
  const cols: ReportColumnDef[] = block.columns ?? [];
  const rowField = block.rowField ?? '';
  const columnField = block.columnField ?? '';
  const valueField = block.valueField ?? '';
  const aggregation = (block.aggregation ?? 'sum').toLowerCase();
  const showRowTotal = block.showRowTotal !== false;
  const showColumnTotal = block.showColumnTotal !== false;

  const rowKeys = new Set<string>();
  const colKeys = new Set<string>();
  const cells = new Map<string, number[]>();
  for (const r of rowsFor(block, dataSets)) {
    const rk = String(r[rowField] ?? 'Other');
    const ck = String(r[columnField] ?? 'Other');
    rowKeys.add(rk);
    colKeys.add(ck);
    const key = `${rk}\0${ck}`;
    const bucket = cells.get(key);
    if (bucket) {
      bucket.push(numericValue(r[valueField]));
    } else {
      cells.set(key, [numericValue(r[valueField])]);
    }
  }
  const byLabel = (a: string, b: string) => (a < b ? -1 : a > b ? 1 : 0);
  const sortedRows = [...rowKeys].sort(byLabel);
  const sortedCols = [...colKeys].sort(byLabel);
  const agg = (rk: string, ck: string) =>
    aggregateNumbers(cells.get(`${rk}\0${ck}`) ?? [], aggregation);

  const axisLabel = (field: string) => cols.find((c) => c.field === field)?.label;
  const rowAxisLabel = block.rowLabel || axisLabel(rowField);
  const columnAxisLabel = block.columnLabel || axisLabel(columnField);
  const cornerLabel =
    rowAxisLabel && columnAxisLabel
      ? `${rowAxisLabel} \\ ${columnAxisLabel}`
      : (rowAxisLabel ?? columnAxisLabel ?? '');
  const headCells = [
    `<th>${escapeHtml(cornerLabel)}</th>`,
    ...sortedCols.map((c) => `<th>${escapeHtml(c)}</th>`),
    ...(showRowTotal ? [`<th>${escapeHtml(block.totalLabel || 'Total')}</th>`] : []),
  ].join('');

  const bodyRows = sortedRows
    .map((rk) => {
      const rowVals = sortedCols.map((ck) => agg(rk, ck));
      const cellsHtml = rowVals
        .map(
          (v) =>
            `<td>${escapeHtml(block.format ? formattedValue(v, block.format) : formatNumber(v))}</td>`,
        )
        .join('');
      const total = showRowTotal
        ? `<td>${escapeHtml(
            formattedValue(
              rowVals.reduce((a, b) => a + b, 0),
              block.format,
            ),
          )}</td>`
        : '';
      return `<tr><td>${escapeHtml(rk)}</td>${cellsHtml}${total}</tr>`;
    })
    .join('');

  let totalRow = '';
  if (showColumnTotal) {
    const colTotals = sortedCols.map((ck) => sortedRows.reduce((sum, rk) => sum + agg(rk, ck), 0));
    const cellsHtml = colTotals
      .map(
        (v) =>
          `<td>${escapeHtml(block.format ? formattedValue(v, block.format) : formatNumber(v))}</td>`,
      )
      .join('');
    const grand = showRowTotal
      ? `<td>${escapeHtml(
          formattedValue(
            colTotals.reduce((a, b) => a + b, 0),
            block.format,
          ),
        )}</td>`
      : '';
    totalRow = `<tr class="total-row"><td>${escapeHtml(block.totalLabel || 'Total')}</td>${cellsHtml}${grand}</tr>`;
  }

  return `<section class="block cross-tab">${renderBlockTitle(block.title)}<table><thead><tr>${headCells}</tr></thead><tbody>${bodyRows}${totalRow}</tbody></table></section>`;
}

function renderBarcode(block: ReportPrintBlock, dataSets: PrintDataSets): string {
  // value: staticValue, else the first row's `field` (mirrors backend resolveBarcodeValue)
  let value = block.staticValue ?? '';
  if (!value && block.field) {
    const rows = rowsFor(block, dataSets);
    value = rows.length ? String(rows[0][block.field] ?? '') : '';
  }
  const svg = renderBarcodeSvg(value, {
    format: block.format,
    moduleWidth: block.width,
    height: block.height,
    displayValue: block.displayValue,
  });
  return `<section class="block barcode">${renderBlockTitle(block.title)}<div class="barcode-canvas">${svg}</div></section>`;
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
  body { margin: 0; color: #1f2937; font-size:14px; line-height:1.5; }
  p { orphans:3; widows:3; }
  .report-band-screen { margin-bottom:18px; }
  @media print { .report-band-screen { display:none; } }
  .watermark { position: fixed; top: 40%; left: 18%; font-size: 64px; color: rgba(0,0,0,0.06); transform: rotate(-30deg); z-index: 0; pointer-events: none; }
  h1 { font-size: 20px; }
  .block { margin: 0 0 18px; position: relative; z-index: 1; }
  thead { display: table-header-group; }
  table { width: 100%; border-collapse: collapse; font-size: 12px; }
  th, td { border: 1px solid #d1d5db; padding: 6px 8px; text-align: left; }
  th { background: #f3f4f6; }
  h2 { break-after: avoid; page-break-after: avoid; }
  tr { break-inside: avoid; }
  .block.grouped-table, .block.cross-tab, .block.stat-card, .block.chart { break-inside: avoid; }
  .block.stat-card { display: inline-block; min-width: 180px; margin-right: 24px; vertical-align: top; }
  .stat-value { font-size: 24px; font-weight: 600; }
  .stat-label { font-size: 12px; color: #6b7280; }
  .chart-canvas svg { max-width: 100%; height: auto; }
.summary-row td { font-weight: 600; background: #f3f4f6; }
</style></head><body>
  ${watermark}
  ${model.header ? `<header class="report-band-screen">${renderBand(model.header, false)}</header>` : model.title ? `<h1>${escapeHtml(model.title)}</h1>` : ''}
  ${body}
  ${model.footer ? `<footer class="report-band-screen">${renderBand(model.footer, false)}</footer>` : ''}
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
  let headerTemplate = model.header ? bandTemplate(model.header, model.page) : undefined;
  let footerTemplate = model.footer ? bandTemplate(model.footer, model.page) : undefined;
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
      case 'stat-card':
        body.push(renderStatCard(block, dataSets));
        break;
      case 'grouped-table':
        body.push(renderGroupedTable(block, dataSets));
        break;
      case 'cross-tab':
        body.push(renderCrossTab(block, dataSets));
        break;
      case 'barcode':
        body.push(renderBarcode(block, dataSets));
        break;
      case 'rich-text':
        body.push(renderRichText(block));
        break;
      case 'page-header':
        if (!model.header) headerTemplate = runningHeader(block);
        break;
      case 'page-footer':
        if (!model.footer) footerTemplate = runningFooter(block);
        break;
      case 'watermark':
        watermark = renderWatermark(block);
        break;
      default:
        // any unknown block renders a visible placeholder rather than silently
        // dropping its content.
        body.push(
          `<section class="block unsupported" data-block-type="${escapeHtml(block.blockType)}"><em>[${escapeHtml(block.blockType)}] 渲染待补</em></section>`,
        );
        break;
    }
  }

  return {
    html: shell(model, body.join('\n'), watermark),
    headerTemplate,
    footerTemplate,
    headerHeightMm: model.header?.height,
    footerHeightMm: model.footer?.height,
  };
}
