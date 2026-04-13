/**
 * Report DSL → HTML Converter
 *
 * Generates table-based HTML layout compatible with openhtmltopdf.
 * Only uses: tables, basic CSS, position:running() for headers/footers.
 * No flexbox/grid (not supported by openhtmltopdf).
 */

import type {
  ReportDsl,
  DataTableBlock,
  GroupedTableBlock,
  StatCardBlock,
  RichTextBlock,
  CrossTabBlock,
  ChartBlock,
  BarcodeBlock,
  WatermarkBlock,
  ReportBand,
  BandElement,
  ReportColumn,
  SummaryColumnConfig,
} from '../types';

function escapeHtml(str: string): string {
  return str
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function renderBandElement(el: BandElement): string {
  const style: string[] = [];
  if (el.style?.fontSize) style.push(`font-size: ${el.style.fontSize}pt`);
  if (el.style?.fontWeight) style.push(`font-weight: ${el.style.fontWeight}`);
  if (el.style?.color) style.push(`color: ${el.style.color}`);
  if (el.style?.fontFamily) style.push(`font-family: ${el.style.fontFamily}`);
  if (el.align) style.push(`text-align: ${el.align}`);

  const styleAttr = style.length > 0 ? ` style="${style.join('; ')}"` : '';

  switch (el.type) {
    case 'text':
      return `<div${styleAttr}>${escapeHtml(el.content || '')}</div>`;
    case 'page-number':
      return `<div${styleAttr}>Page <span class="page-number"></span></div>`;
    case 'date':
      return `<div${styleAttr}>${new Date().toLocaleDateString()}</div>`;
    case 'image':
      return `<div${styleAttr}><img src="${escapeHtml(el.content || '')}" style="max-height: 40px;" /></div>`;
    default:
      return '';
  }
}

function renderBand(band: ReportBand, position: 'header' | 'footer'): string {
  const elements = band.elements.map(renderBandElement).join('\n');
  return `
    <div class="report-${position}" style="height: ${band.height}mm;">
      ${elements}
    </div>
  `;
}

function formatCellValue(value: unknown, format?: string): string {
  if (value === null || value === undefined) return '';
  if (!format) return escapeHtml(String(value));

  if (format === 'number' && typeof value === 'number') {
    return value.toLocaleString();
  }
  if (format === 'currency' && typeof value === 'number') {
    return `¥${value.toLocaleString('zh-CN', { minimumFractionDigits: 2 })}`;
  }
  if (format === 'percent' && typeof value === 'number') {
    return `${(value * 100).toFixed(1)}%`;
  }
  if (format === 'date' && value) {
    return new Date(String(value)).toLocaleDateString();
  }

  return escapeHtml(String(value));
}

function renderDataTable(block: DataTableBlock, data: Record<string, unknown>[]): string {
  const borderStyle =
    block.border !== false
      ? 'border: 1px solid #ddd; border-collapse: collapse;'
      : 'border-collapse: collapse;';
  const cellBorder = block.border !== false ? 'border: 1px solid #ddd;' : '';

  let html = `
    <table style="width: 100%; ${borderStyle} margin-bottom: 16px; font-size: 10pt;">
  `;

  // Title row
  if (block.title) {
    html += `
      <caption style="text-align: left; font-weight: bold; font-size: 12pt; margin-bottom: 8px; caption-side: top;">
        ${escapeHtml(block.title)}
      </caption>
    `;
  }

  // Header
  if (block.showHeader !== false) {
    html += '<thead><tr>';
    for (const col of block.columns) {
      const align = col.align || 'left';
      const width = col.width ? `width: ${col.width}px;` : '';
      html += `<th style="${cellBorder} padding: 6px 8px; background: #f5f5f5; text-align: ${align}; font-weight: bold; ${width}">`;
      html += escapeHtml(col.label || col.field);
      html += '</th>';
    }
    html += '</tr></thead>';
  }

  // Body
  html += '<tbody>';
  data.forEach((row, rowIdx) => {
    const bgColor = block.stripe !== false && rowIdx % 2 === 1 ? 'background: #fafafa;' : '';
    html += `<tr style="${bgColor}">`;
    for (const col of block.columns) {
      const align = col.align || 'left';
      html += `<td style="${cellBorder} padding: 4px 8px; text-align: ${align};">`;
      html += formatCellValue(row[col.field], col.format);
      html += '</td>';
    }
    html += '</tr>';
  });
  // Summary row
  if (block.summary?.enabled && block.summary.columns.length > 0) {
    html += '<tfoot><tr>';
    for (const col of block.columns) {
      const sc = block.summary.columns.find((c) => c.field === col.field);
      const align = col.align || 'left';
      html += `<td style="${cellBorder} padding: 6px 8px; text-align: ${align}; font-weight: bold; background: #e5e7eb;">`;
      if (sc) {
        const val = computeAgg(data, sc.field, sc.aggregation);
        html += formatCellValue(val, sc.format || col.format);
      } else if (col === block.columns[0]) {
        html += escapeHtml(block.summary.label || 'Total');
      }
      html += '</td>';
    }
    html += '</tr></tfoot>';
  }

  html += '</tbody></table>';

  return html;
}

function aggNumbers(values: number[], agg: string): number {
  if (values.length === 0) return 0;
  switch (agg) {
    case 'sum':
      return values.reduce((a, b) => a + b, 0);
    case 'avg':
      return values.reduce((a, b) => a + b, 0) / values.length;
    case 'count':
      return values.length;
    case 'min':
      return Math.min(...values);
    case 'max':
      return Math.max(...values);
    default:
      return 0;
  }
}

function computeAgg(rows: Record<string, unknown>[], field: string, agg: string): number {
  const values = rows.map((r) => Number(r[field]) || 0);
  if (values.length === 0) return 0;
  switch (agg) {
    case 'sum':
      return values.reduce((a, b) => a + b, 0);
    case 'avg':
      return values.reduce((a, b) => a + b, 0) / values.length;
    case 'count':
      return values.length;
    case 'min':
      return Math.min(...values);
    case 'max':
      return Math.max(...values);
    default:
      return 0;
  }
}

function renderGroupedTable(block: GroupedTableBlock, data: Record<string, unknown>[]): string {
  const cellBorder = block.border !== false ? 'border: 1px solid #ddd;' : '';
  const borderStyle =
    block.border !== false
      ? 'border: 1px solid #ddd; border-collapse: collapse;'
      : 'border-collapse: collapse;';

  let html = `<table style="width: 100%; ${borderStyle} margin-bottom: 16px; font-size: 10pt;">`;

  if (block.title) {
    html += `<caption style="text-align: left; font-weight: bold; font-size: 12pt; margin-bottom: 8px; caption-side: top;">${escapeHtml(block.title)}</caption>`;
  }

  if (block.showHeader !== false) {
    html += '<thead><tr>';
    for (const col of block.columns) {
      html += `<th style="${cellBorder} padding: 6px 8px; background: #f5f5f5; text-align: ${col.align || 'left'}; font-weight: bold;">${escapeHtml(col.label || col.field)}</th>`;
    }
    html += '</tr></thead>';
  }

  // Group data
  const groups = new Map<string, Record<string, unknown>[]>();
  for (const row of data) {
    const key = String(row[block.groupByField] ?? 'Other');
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key)!.push(row);
  }

  html += '<tbody>';
  for (const [groupKey, rows] of groups.entries()) {
    html += `<tr><td colspan="${block.columns.length}" style="${cellBorder} padding: 6px 8px; background: #dbeafe; font-weight: bold; color: #1e40af;">${escapeHtml(block.groupByField)}: ${escapeHtml(groupKey)} (${rows.length})</td></tr>`;

    for (const row of rows) {
      html += '<tr>';
      for (const col of block.columns) {
        html += `<td style="${cellBorder} padding: 4px 8px; text-align: ${col.align || 'left'};">${formatCellValue(row[col.field], col.format)}</td>`;
      }
      html += '</tr>';
    }

    if (block.groupSubtotal?.enabled) {
      html += '<tr>';
      for (const col of block.columns) {
        const sc = block.groupSubtotal.columns.find((c) => c.field === col.field);
        html += `<td style="${cellBorder} padding: 4px 8px; background: #f3f4f6; font-weight: 600; text-align: ${col.align || 'right'};">`;
        if (col === block.columns[0] && !sc)
          html += escapeHtml(block.groupSubtotal.label || 'Subtotal');
        else if (sc)
          html += formatCellValue(
            computeAgg(rows, sc.field, sc.aggregation),
            sc.format || col.format,
          );
        html += '</td>';
      }
      html += '</tr>';
    }
  }

  if (block.grandTotal?.enabled) {
    html += '<tr>';
    for (const col of block.columns) {
      const sc = block.grandTotal.columns.find((c) => c.field === col.field);
      html += `<td style="${cellBorder} padding: 6px 8px; background: #d1d5db; font-weight: bold; text-align: ${col.align || 'right'};">`;
      if (col === block.columns[0] && !sc)
        html += escapeHtml(block.grandTotal.label || 'Grand Total');
      else if (sc)
        html += formatCellValue(
          computeAgg(data, sc.field, sc.aggregation),
          sc.format || col.format,
        );
      html += '</td>';
    }
    html += '</tr>';
  }

  html += '</tbody></table>';
  return html;
}

function renderStatCard(block: StatCardBlock, data: Record<string, unknown>[]): string {
  const value = computeAgg(data, block.valueField, block.aggregation);
  const formatted = formatCellValue(value, block.format);

  return `
    <div style="display: inline-block; border: 1px solid #e5e7eb; border-radius: 8px; padding: 12px 20px; margin: 4px 8px 4px 0; min-width: 120px; background: #f9fafb;">
      <div style="font-size: 8pt; color: #6b7280; text-transform: uppercase; letter-spacing: 0.05em; margin-bottom: 4px;">${escapeHtml(block.label || 'Metric')}</div>
      <div style="font-size: 18pt; font-weight: bold; color: ${block.color || '#1d4ed8'};">${formatted}</div>
    </div>
  `;
}

function renderRichText(block: RichTextBlock): string {
  const style: string[] = [];
  if (block.align) style.push(`text-align: ${block.align}`);
  if (block.style?.fontSize) style.push(`font-size: ${block.style.fontSize}pt`);
  if (block.style?.fontWeight) style.push(`font-weight: ${block.style.fontWeight}`);
  if (block.style?.color) style.push(`color: ${block.style.color}`);
  const styleAttr = style.length > 0 ? ` style="${style.join('; ')}"` : '';

  const paragraphs = (block.content || '').split('\n').filter(Boolean);
  return paragraphs
    .map(
      (p) => `<p${styleAttr} style="margin-bottom: 8px; ${style.join('; ')}">${escapeHtml(p)}</p>`,
    )
    .join('\n');
}

function renderCrossTab(block: CrossTabBlock, data: Record<string, unknown>[]): string {
  const rowKeys = new Set<string>();
  const colKeys = new Set<string>();
  const cells = new Map<string, number[]>();

  for (const row of data) {
    const rk = String(row[block.rowField] ?? 'Other');
    const ck = String(row[block.columnField] ?? 'Other');
    const val = Number(row[block.valueField]) || 0;
    rowKeys.add(rk);
    colKeys.add(ck);
    const key = `${rk}|${ck}`;
    if (!cells.has(key)) cells.set(key, []);
    cells.get(key)!.push(val);
  }

  const rows = Array.from(rowKeys).sort();
  const cols = Array.from(colKeys).sort();
  const getVal = (rk: string, ck: string) =>
    aggNumbers(cells.get(`${rk}|${ck}`) || [], block.aggregation);
  const getRowTotal = (rk: string) => cols.reduce((s, ck) => s + getVal(rk, ck), 0);
  const getColTotal = (ck: string) => rows.reduce((s, rk) => s + getVal(rk, ck), 0);

  let html = `<table style="border: 1px solid #ddd; border-collapse: collapse; margin-bottom: 16px; font-size: 10pt;">`;
  if (block.title)
    html += `<caption style="text-align: left; font-weight: bold; font-size: 12pt; margin-bottom: 8px; caption-side: top;">${escapeHtml(block.title)}</caption>`;

  // Header
  html += `<thead><tr><th style="border: 1px solid #ddd; padding: 6px 8px; background: #e5e7eb;">${escapeHtml(block.rowField)} \\ ${escapeHtml(block.columnField)}</th>`;
  for (const ck of cols)
    html += `<th style="border: 1px solid #ddd; padding: 6px 8px; background: #dbeafe; text-align: right;">${escapeHtml(ck)}</th>`;
  if (block.showRowTotal)
    html += `<th style="border: 1px solid #ddd; padding: 6px 8px; background: #f3f4f6; text-align: right; font-weight: bold;">Total</th>`;
  html += '</tr></thead><tbody>';

  for (const rk of rows) {
    html += `<tr><td style="border: 1px solid #ddd; padding: 4px 8px; background: #f9fafb; font-weight: 600;">${escapeHtml(rk)}</td>`;
    for (const ck of cols)
      html += `<td style="border: 1px solid #ddd; padding: 4px 8px; text-align: right;">${formatCellValue(getVal(rk, ck), block.format)}</td>`;
    if (block.showRowTotal)
      html += `<td style="border: 1px solid #ddd; padding: 4px 8px; text-align: right; background: #f3f4f6; font-weight: 600;">${formatCellValue(getRowTotal(rk), block.format)}</td>`;
    html += '</tr>';
  }

  if (block.showColumnTotal) {
    html += `<tr><td style="border: 1px solid #ddd; padding: 6px 8px; background: #d1d5db; font-weight: bold;">Total</td>`;
    for (const ck of cols)
      html += `<td style="border: 1px solid #ddd; padding: 6px 8px; text-align: right; background: #d1d5db; font-weight: bold;">${formatCellValue(getColTotal(ck), block.format)}</td>`;
    if (block.showRowTotal)
      html += `<td style="border: 1px solid #ddd; padding: 6px 8px; text-align: right; background: #9ca3af; font-weight: bold;">${formatCellValue(
        rows.reduce((s, rk) => s + getRowTotal(rk), 0),
        block.format,
      )}</td>`;
    html += '</tr>';
  }

  html += '</tbody></table>';
  return html;
}

function renderChart(block: ChartBlock, data: Record<string, unknown>[]): string {
  // Aggregate data
  const groups = new Map<string, number[]>();
  for (const row of data) {
    const cat = String(row[block.categoryField] ?? 'Other');
    const val = Number(row[block.valueField]) || 0;
    if (!groups.has(cat)) groups.set(cat, []);
    groups.get(cat)!.push(val);
  }
  const items = Array.from(groups.entries()).map(([cat, vals]) => ({
    category: cat,
    value: aggNumbers(vals, block.aggregation || 'sum'),
  }));

  if (items.length === 0) return '<p style="color: #999;">No chart data</p>';

  const colors = [
    '#3b82f6',
    '#ef4444',
    '#22c55e',
    '#f59e0b',
    '#8b5cf6',
    '#ec4899',
    '#06b6d4',
    '#f97316',
  ];
  const w = block.width || 400;
  const h = block.height || 240;

  // For PDF: render as HTML table-based bar chart (openhtmltopdf doesn't support SVG well)
  let html = '';
  if (block.title)
    html += `<div style="font-weight: bold; font-size: 12pt; margin-bottom: 8px;">${escapeHtml(block.title)}</div>`;

  if (block.chartType === 'pie') {
    // Pie as legend table (SVG not reliable in openhtmltopdf)
    const total = items.reduce((s, i) => s + i.value, 0) || 1;
    html += '<table style="font-size: 9pt; border-collapse: collapse;">';
    items.forEach((item, i) => {
      const pct = Math.round((item.value / total) * 100);
      html += `<tr>
        <td style="padding: 2px 8px;"><div style="width: 12px; height: 12px; background: ${colors[i % colors.length]}; display: inline-block;"></div></td>
        <td style="padding: 2px 8px;">${escapeHtml(item.category)}</td>
        <td style="padding: 2px 8px; text-align: right;">${formatCellValue(item.value, block.aggregation === 'count' ? undefined : 'number')}</td>
        <td style="padding: 2px 8px; text-align: right; color: #6b7280;">${pct}%</td>
      </tr>`;
    });
    html += '</table>';
  } else {
    // Bar chart as horizontal table bars
    const maxVal = Math.max(...items.map((i) => i.value), 1);
    html += '<table style="font-size: 9pt; border-collapse: collapse; width: 100%;">';
    items.forEach((item, i) => {
      const barPct = Math.round((item.value / maxVal) * 100);
      html += `<tr>
        <td style="padding: 3px 8px; width: 100px; white-space: nowrap;">${escapeHtml(item.category)}</td>
        <td style="padding: 3px 4px;">
          <div style="background: ${colors[i % colors.length]}; height: 16px; width: ${barPct}%; border-radius: 2px;"></div>
        </td>
        <td style="padding: 3px 8px; width: 60px; text-align: right;">${formatCellValue(item.value, 'number')}</td>
      </tr>`;
    });
    html += '</table>';
  }

  return html;
}

function renderBarcode(block: BarcodeBlock, data: Record<string, unknown>[]): string {
  // Resolve value from static or data source
  let value = block.staticValue || '';
  if (!value && block.field && data.length > 0) {
    value = String(data[0][block.field] ?? '');
  }
  if (!value) return '<p style="color: #999;">No barcode value</p>';

  let html = '';
  if (block.title)
    html += `<div style="font-weight: bold; font-size: 12pt; margin-bottom: 8px;">${escapeHtml(block.title)}</div>`;

  // For PDF rendering, generate inline SVG via a simplified barcode representation
  // Since openhtmltopdf has limited SVG support, render as a table-based visual
  const displayValue = block.displayValue !== false;
  const fontSize = block.fontSize || 14;

  // Render barcode as a series of thin table cells (works in openhtmltopdf)
  const barHeight = block.height || 60;
  html += `<div style="text-align: center; margin-bottom: 16px;">`;
  html += `<div style="display: inline-block; border: 1px solid #e5e7eb; padding: 8px;">`;

  // Simple visual representation for PDF: alternating black/white bars
  html += `<table style="border-collapse: collapse; margin: 0 auto;"><tr>`;
  const chars = value.split('');
  for (let i = 0; i < chars.length; i++) {
    const code = chars[i].charCodeAt(0);
    // Generate pseudo-random bar pattern based on char code
    const bars = [code & 1 ? 2 : 1, code & 2 ? 1 : 2, code & 4 ? 2 : 1, code & 8 ? 1 : 2];
    for (const w of bars) {
      const isBlack = bars.indexOf(w) % 2 === 0;
      html += `<td style="width: ${w}px; height: ${barHeight}px; background: ${isBlack ? '#000' : '#fff'}; padding: 0;"></td>`;
    }
    // Gap between characters
    html += `<td style="width: 1px; height: ${barHeight}px; background: #fff; padding: 0;"></td>`;
  }
  html += `</tr></table>`;

  if (displayValue) {
    html += `<div style="text-align: center; font-size: ${fontSize}pt; font-family: monospace; margin-top: 4px;">${escapeHtml(value)}</div>`;
  }

  html += `</div></div>`;
  return html;
}

function renderWatermark(block: WatermarkBlock): string {
  const text = block.text || 'watermark';
  const rotation = block.rotation ?? -30;
  const opacity = block.opacity ?? 0.1;
  const fontSize = block.fontSize ?? 16;
  const color = block.color ?? '#000000';
  const repeat = block.repeat !== false;

  // For openhtmltopdf: use fixed-position div overlay
  let html = `<div style="position: fixed; top: 0; left: 0; width: 100%; height: 100%; pointer-events: none; z-index: 1000;">`;

  if (repeat) {
    // Generate a grid of watermark text
    const rows = 8;
    const cols = 4;
    html += `<table style="width: 100%; height: 100%; border-collapse: collapse;">`;
    for (let r = 0; r < rows; r++) {
      html += '<tr>';
      for (let c = 0; c < cols; c++) {
        html += `<td style="text-align: center; vertical-align: middle;">`;
        html += `<span style="transform: rotate(${rotation}deg); display: inline-block; opacity: ${opacity}; font-size: ${fontSize}pt; color: ${color}; font-weight: bold;">${escapeHtml(text)}</span>`;
        html += '</td>';
      }
      html += '</tr>';
    }
    html += '</table>';
  } else {
    // Single centered watermark
    html += `<table style="width: 100%; height: 100%;"><tr><td style="text-align: center; vertical-align: middle;">`;
    html += `<span style="transform: rotate(${rotation}deg); display: inline-block; opacity: ${opacity}; font-size: ${fontSize * 3}pt; color: ${color}; font-weight: bold;">${escapeHtml(text)}</span>`;
    html += '</td></tr></table>';
  }

  html += '</div>';
  return html;
}

/**
 * Convert ReportDsl + fetched data into a complete HTML document
 * suitable for openhtmltopdf rendering.
 */
export function reportToHtml(
  report: ReportDsl,
  dataSets: Record<string, Record<string, unknown>[]>,
): string {
  const { page } = report;
  const margin = page.margin;

  // Page dimensions for CSS @page
  const pageSizeCSS =
    page.size === 'A4'
      ? '210mm 297mm'
      : page.size === 'A3'
        ? '297mm 420mm'
        : page.size === 'letter'
          ? '8.5in 11in'
          : '8.5in 14in'; // LEGAL

  const orientedPageSize =
    page.orientation === 'landscape'
      ? `${pageSizeCSS.split(' ').reverse().join(' ')}`
      : pageSizeCSS;

  // Header/Footer HTML
  const headerHtml = report.header ? renderBand(report.header, 'header') : '';
  const footerHtml = report.footer ? renderBand(report.footer, 'footer') : '';

  // Body blocks
  const bodyHtml = report.body
    .map((block) => {
      switch (block.blockType) {
        case 'table':
          return renderDataTable(block, dataSets[block.dataSource] || []);
        case 'grouped-table':
          return renderGroupedTable(block, dataSets[block.dataSource] || []);
        case 'stat-card':
          return renderStatCard(block, dataSets[block.dataSource] || []);
        case 'rich-text':
          return renderRichText(block);
        case 'cross-tab':
          return renderCrossTab(block, dataSets[block.dataSource] || []);
        case 'chart':
          return renderChart(block, dataSets[block.dataSource] || []);
        case 'barcode':
          return renderBarcode(block, dataSets[block.dataSource || ''] || []);
        case 'watermark':
          return renderWatermark(block);
        default:
          return '';
      }
    })
    .join('\n');

  return `<!DOCTYPE html>
<html>
<head>
  <meta charset="UTF-8" />
  <style>
    @page {
      size: ${orientedPageSize};
      margin: ${margin.top}mm ${margin.right}mm ${margin.bottom}mm ${margin.left}mm;
      @top-center {
        content: element(report-header);
      }
      @bottom-center {
        content: element(report-footer);
      }
    }
    body {
      font-family: "Microsoft YaHei", "SimSun", "Helvetica Neue", Arial, sans-serif;
      font-size: 10pt;
      color: #333;
      margin: 0;
      padding: 0;
    }
    .report-header {
      position: running(report-header);
      width: 100%;
    }
    .report-footer {
      position: running(report-footer);
      width: 100%;
    }
    table {
      page-break-inside: auto;
    }
    tr {
      page-break-inside: avoid;
    }
  </style>
</head>
<body>
  ${headerHtml}
  ${footerHtml}
  ${bodyHtml}
</body>
</html>`;
}
