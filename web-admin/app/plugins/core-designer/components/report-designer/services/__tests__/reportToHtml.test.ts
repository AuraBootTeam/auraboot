import { describe, expect, it } from 'vitest'
import { reportToHtml } from '../reportToHtml'
import type { ReportDsl, ReportBlock } from '../../types'

const baseReport = (overrides: Partial<ReportDsl> = {}): ReportDsl => ({
  $schema: 'auraboot://schemas/report/v1',
  version: '1.0.0',
  title: 'Unit Report',
  page: { size: 'A4', orientation: 'portrait', margin: { top: 10, right: 10, bottom: 10, left: 10 } },
  dataSources: {},
  body: [],
  ...overrides,
} as ReportDsl)

const salesRows = [
  { region: 'EMEA', product: 'Widget', amount: 100, qty: 2, day: '2026-01-05' },
  { region: 'EMEA', product: 'Sprocket', amount: 50, qty: 1, day: '2026-02-11' },
  { region: 'APAC', product: 'Widget', amount: 200, qty: 4, day: '2026-03-20' },
]

describe('reportToHtml — page chrome', () => {
  it('emits an A4 portrait @page with the configured margins', () => {
    const html = reportToHtml(baseReport(), {})
    expect(html).toContain('@page');
    expect(html).toContain('size: 210mm 297mm;');
    expect(html).toContain('margin: 10mm 10mm 10mm 10mm;');
  });

  it('maps A3 / letter / legal sizes and swaps dimensions in landscape', () => {
    const a3 = reportToHtml(baseReport({ page: { size: 'A3', orientation: 'portrait', margin: { top: 1, right: 2, bottom: 3, left: 4 } } } as ReportDsl), {});
    expect(a3).toContain('size: 297mm 420mm;');

    const letter = reportToHtml(baseReport({ page: { size: 'letter', orientation: 'portrait', margin: { top: 1, right: 2, bottom: 3, left: 4 } } } as ReportDsl), {});
    expect(letter).toContain('size: 8.5in 11in;');

    const legalLandscape = reportToHtml(baseReport({ page: { size: 'legal', orientation: 'landscape', margin: { top: 1, right: 2, bottom: 3, left: 4 } } } as ReportDsl), {});
    expect(legalLandscape).toContain('size: 8.5in 14in;'.replace('8.5in 14in', '14in 8.5in'));

    const a4Landscape = reportToHtml(baseReport({ page: { size: 'A4', orientation: 'landscape', margin: { top: 1, right: 2, bottom: 3, left: 4 } } } as ReportDsl), {});
    expect(a4Landscape).toContain('size: 297mm 210mm;');
  });

  it('renders header and footer bands with element types and inline styles', () => {
    const html = reportToHtml(
      baseReport({
        header: {
          height: 12,
          elements: [
            { type: 'text', content: 'Quarterly <Q1>', style: { fontSize: 14, fontWeight: 'bold', color: '#123456', fontFamily: 'Arial' }, align: 'center' },
            { type: 'page-number' },
            { type: 'date' },
            { type: 'image', content: 'logo<x>.png' },
            { type: 'mystery' },
          ] as never,
        },
        footer: { height: 8, elements: [{ type: 'text', content: 'footer text' }] } as never,
      }),
      {},
    )

    expect(html).toContain('class="report-header"');
    expect(html).toContain('height: 12mm;');
    expect(html).toContain('&lt;Q1&gt;');
    expect(html).toContain('font-size: 14pt');
    expect(html).toContain('font-weight: bold');
    expect(html).toContain('color: #123456');
    expect(html).toContain('font-family: Arial');
    expect(html).toContain('text-align: center');
    expect(html).toContain('class="page-number"');
    expect(html).toContain(new Date().toLocaleDateString());
    expect(html).toContain('logo&lt;x&gt;.png');
    expect(html).toContain('class="report-footer"');
    expect(html).toContain('footer text');
  });
});

describe('reportToHtml — table block', () => {
  it('renders headers, striped rows, alignment, widths, and escapes cell values', () => {
    const block = {
      blockType: 'table',
      dataSource: 'sales',
      title: 'Sales <2026>',
      border: true,
      stripe: true,
      showHeader: true,
      columns: [
        { field: 'region', label: 'Region' },
        { field: 'amount', label: 'Amount', align: 'right', width: 80, format: 'currency' },
        { field: 'note', label: 'Note' },
      ],
    } as ReportBlock

    const html = reportToHtml(baseReport({ body: [block] }), {
      sales: [
        { region: 'EMEA', amount: 1234.5, note: '<script>' },
        { region: 'APAC', amount: 10, note: null },
      ],
    })

    expect(html).toContain('Sales &lt;2026&gt;');
    expect(html).toContain('>Region</th>');
    expect(html).toContain('width: 80px;');
    expect(html).toContain('background: #fafafa;'); // second row stripe
    expect(html).toContain('¥1,234.50');
    expect(html).toContain('&lt;script&gt;');
    expect(html).not.toContain('<script>');
  });

  it('supports borderless tables, hidden headers, and every number format', () => {
    const block = {
      blockType: 'table',
      dataSource: 'sales',
      border: false,
      showHeader: false,
      stripe: false,
      columns: [
        { field: 'amount', label: 'A', format: 'number' },
        { field: 'qty', label: 'B', format: 'percent' },
        { field: 'day', label: 'C', format: 'date' },
        { field: 'region', label: 'D' },
      ],
    } as ReportBlock

    const html = reportToHtml(baseReport({ body: [block] }), {
      sales: [{ amount: 12345.6, qty: 0.5, day: '2026-01-05', region: 'EMEA' }],
    })

    expect(html).not.toContain('border: 1px solid #ddd;');
    expect(html).not.toContain('<thead>');
    expect(html).toContain('12,345.6');
    expect(html).toContain('50.0%');
    expect(html).toContain(new Date('2026-01-05').toLocaleDateString());
  });

  it('renders a summary row with every aggregation and the first-column label fallback', () => {
    const block = {
      blockType: 'table',
      dataSource: 'sales',
      columns: [
        { field: 'region', label: 'Region' },
        { field: 'amount', label: 'Amount', format: 'number' },
        { field: 'qty', label: 'Qty' },
      ],
      summary: {
        enabled: true,
        label: '合计',
        columns: [
          { field: 'amount', aggregation: 'sum', format: 'number' },
          { field: 'qty', aggregation: 'avg' },
        ],
      },
    } as ReportBlock

    const html = reportToHtml(baseReport({ body: [block] }), { sales: salesRows })

    expect(html).toContain('<tfoot>');
    expect(html).toContain('合计');
    expect(html).toContain('350'); // 100 + 50 + 200
    expect(html).toContain(String((2 + 1 + 4) / 3)); // avg qty = 2.333...
  });

  it('computes count / min / max aggregations and tolerates an empty summary list', () => {
    const block = {
      blockType: 'table',
      dataSource: 'sales',
      columns: [
        { field: 'region', label: 'Region' },
        { field: 'amount', label: 'Amount' },
      ],
      summary: {
        enabled: true,
        columns: [{ field: 'amount', aggregation: 'count' }, { field: 'amount', aggregation: 'min' }, { field: 'amount', aggregation: 'max' }],
      },
    } as ReportBlock

    const html = reportToHtml(baseReport({ body: [block] }), { sales: salesRows })
    expect(html).toContain('<tfoot>');
  });
});

describe('reportToHtml — grouped table block', () => {
  const block = {
    blockType: 'grouped-table',
    dataSource: 'sales',
    groupByField: 'region',
    columns: [
      { field: 'product', label: 'Product' },
      { field: 'amount', label: 'Amount', format: 'number' },
    ],
    groupSubtotal: { enabled: true, label: '小计', columns: [{ field: 'amount', aggregation: 'sum' }] },
    grandTotal: { enabled: true, label: '总计', columns: [{ field: 'amount', aggregation: 'sum' }] },
  } as ReportBlock

  it('groups rows by the key field, labels each group with its size, and adds subtotals', () => {
    const html = reportToHtml(baseReport({ body: [block] }), { sales: salesRows })

    expect(html).toContain('region: EMEA (2)');
    expect(html).toContain('region: APAC (1)');
    expect(html).toContain('小计');
    expect(html).toContain('总计');
    expect(html).toContain('>350<'); // grand total amount
    expect(html).toContain('>150<'); // EMEA subtotal
  });

  it('falls back to "Other" for missing group keys and drops borders when disabled', () => {
    const borderless = { ...block, border: false, groupSubtotal: undefined, grandTotal: undefined } as ReportBlock
    const html = reportToHtml(baseReport({ body: [borderless] }), {
      sales: [{ product: 'Thing', amount: 5 }],
    })

    expect(html).toContain('region: Other (1)');
    expect(html).not.toContain('border: 1px solid #ddd;');
  });
});

describe('reportToHtml — stat card / rich text blocks', () => {
  it('stat-card aggregates its field and applies the color and label', () => {
    const block = {
      blockType: 'stat-card',
      dataSource: 'sales',
      label: '总金额',
      valueField: 'amount',
      aggregation: 'sum',
      format: 'currency',
      color: '#aa0000',
    } as ReportBlock

    const html = reportToHtml(baseReport({ body: [block] }), { sales: salesRows })
    expect(html).toContain('总金额');
    expect(html).toContain('color: #aa0000');
    expect(html).toContain('¥350.00');
  });

  it('stat-card defaults the label to "Metric"', () => {
    const block = { blockType: 'stat-card', dataSource: 'sales', valueField: 'qty', aggregation: 'count' } as ReportBlock
    const html = reportToHtml(baseReport({ body: [block] }), { sales: salesRows })
    expect(html).toContain('Metric');
    expect(html).toContain('>3<');
  });

  it('rich-text renders one <p> per non-empty line with styles and escaping', () => {
    const block = {
      blockType: 'rich-text',
      content: 'Line <1>\n\nLine 2',
      align: 'center',
      style: { fontSize: 11, fontWeight: 'bold', color: '#333333' },
    } as ReportBlock

    const html = reportToHtml(baseReport({ body: [block] }), {})
    expect(html).toContain('Line &lt;1&gt;');
    expect((html.match(/<p/g) || []).length).toBe(2);
    expect(html).toContain('text-align: center');
    expect(html).toContain('font-size: 11pt');
  });
});

describe('reportToHtml — cross-tab block', () => {
  const block = {
    blockType: 'cross-tab',
    dataSource: 'sales',
    rowField: 'region',
    columnField: 'product',
    valueField: 'amount',
    aggregation: 'sum',
    showRowTotal: true,
    showColumnTotal: true,
    title: 'Pivot',
  } as ReportBlock

  it('pivots rows/columns with row, column, and grand totals', () => {
    const html = reportToHtml(baseReport({ body: [block] }), { sales: salesRows })

    expect(html).toContain('Pivot');
    expect(html).toContain('region \\ product');
    expect(html).toContain('EMEA');
    expect(html).toContain('Sprocket');
    expect(html).toContain('>350<'); // grand total
  });

  it('pivots without totals and falls back to "Other" for null keys', () => {
    const minimal = { ...block, showRowTotal: false, showColumnTotal: false, title: undefined } as ReportBlock
    const html = reportToHtml(baseReport({ body: [minimal] }), {
      sales: [{ region: null, product: 'Widget', amount: 7 }],
    })

    expect(html).toContain('Other');
    expect(html).not.toContain('>Total<');
  });
});

describe('reportToHtml — chart block', () => {
  it('renders a table-based pie legend with counts and percentages', () => {
    const block = {
      blockType: 'chart',
      dataSource: 'sales',
      chartType: 'pie',
      categoryField: 'region',
      valueField: 'amount',
      aggregation: 'sum',
      title: 'By Region',
    } as ReportBlock

    const html = reportToHtml(baseReport({ body: [block] }), { sales: salesRows })
    expect(html).toContain('By Region');
    expect(html).toContain('EMEA');
    expect(html).toContain('43%'); // 150/350 = 42.86 -> Math.round(42.86) = 43
  });

  it('renders a bar chart scaled to the max value', () => {
    const block = {
      blockType: 'chart',
      dataSource: 'sales',
      chartType: 'bar',
      categoryField: 'product',
      valueField: 'qty',
      aggregation: 'sum',
    } as ReportBlock

    const html = reportToHtml(baseReport({ body: [block] }), { sales: salesRows })
    expect(html).toContain('Widget');
    expect(html).toContain('width: 100%'); // max bar (6 qty) fills 100%
  });

  it('emits the no-data placeholder for empty data sets and unknown blocks render empty', () => {
    const empty = reportToHtml(
      baseReport({ body: [{ blockType: 'chart', dataSource: 'sales', chartType: 'bar', categoryField: 'region', valueField: 'amount' } as ReportBlock] }),
      { sales: [] },
    )
    expect(empty).toContain('No chart data');

    const unknown = reportToHtml(
      baseReport({ body: [{ blockType: 'hologram' } as unknown as ReportBlock] }),
      {},
    )
    expect(unknown).not.toContain('hologram');
  });
});

describe('reportToHtml — barcode block', () => {
  it('renders bars from a static value and shows the value by default', () => {
    const block = { blockType: 'barcode', staticValue: 'A1', title: 'Code' } as ReportBlock
    const html = reportToHtml(baseReport({ body: [block] }), {})
    expect(html).toContain('Code');
    expect(html).toContain('>A1<');
    expect(html).toContain('height: 60px;'); // default bar height
  });

  it('resolves the value from the first data row and honors displayValue=false', () => {
    const block = {
      blockType: 'barcode',
      field: 'sku',
      displayValue: false,
      height: 30,
      fontSize: 10,
    } as ReportBlock
    const html = reportToHtml(baseReport({ body: [block] }), { '': [{ sku: 'XYZ' }] })
    expect(html).not.toContain('>XYZ<');
    expect(html).toContain('height: 30px;');
  });

  it('falls back to the placeholder when no value can be resolved', () => {
    const html = reportToHtml(
      baseReport({ body: [{ blockType: 'barcode' } as ReportBlock] }),
      { '': [{}] },
    )
    expect(html).toContain('No barcode value');
  });
});

describe('reportToHtml — watermark block', () => {
  it('renders a repeated grid by default with escaped text and defaults', () => {
    const html = reportToHtml(
      baseReport({ body: [{ blockType: 'watermark', text: '机密' } as ReportBlock] }),
      {},
    )
    expect((html.match(/机密/g) || []).length).toBe(32); // 8 rows x 4 cols
    expect(html).toContain('rotate(-30deg)');
    expect(html).toContain('opacity: 0.1');
  });

  it('renders a single centered watermark when repeat is false, with custom styling', () => {
    const html = reportToHtml(
      baseReport({
        body: [{ blockType: 'watermark', text: 'DRAFT', repeat: false, rotation: -15, opacity: 0.4, fontSize: 20, color: '#ff0000' } as ReportBlock],
      }),
      {},
    )
    expect((html.match(/DRAFT/g) || []).length).toBe(1);
    expect(html).toContain('rotate(-15deg)');
    expect(html).toContain('opacity: 0.4');
    expect(html).toContain('font-size: 60pt'); // fontSize * 3 for single mode
    expect(html).toContain('color: #ff0000');
  });
});
