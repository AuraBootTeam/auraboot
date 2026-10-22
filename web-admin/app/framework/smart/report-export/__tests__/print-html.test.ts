import { describe, it, expect } from 'vitest';
import {
  renderReportToPrintDocument,
  type ReportPrintModel,
  type PrintDataSets,
} from '../print-html';

function doc(model: ReportPrintModel, dataSets: PrintDataSets = {}) {
  return renderReportToPrintDocument(model, dataSets);
}

describe('renderReportToPrintDocument — chart block', () => {
  it('embeds a real vector SVG (not the legacy Category/Value table)', () => {
    const { html } = doc(
      {
        blocks: [
          {
            blockType: 'chart',
            title: '季度营收',
            dataSource: 'ds1',
            chartType: 'bar',
            chartConfig: { xField: 'q', yField: 'rev' },
          },
        ],
      },
      {
        ds1: [
          { q: 'Q1', rev: 100 },
          { q: 'Q2', rev: 140 },
        ],
      },
    );
    expect(html).toContain('<svg');
    expect(html).toContain('<path');
    expect(html).not.toContain('Category');
  });
});

describe('renderReportToPrintDocument — table block', () => {
  it('renders a real <table> using column LABELS (no raw field-code leak)', () => {
    const { html } = doc(
      {
        blocks: [
          {
            blockType: 'table',
            dataSource: 'ds1',
            columns: [
              { field: 'sc_name', label: '名称' },
              { field: 'amt', label: '金额' },
            ],
          },
        ],
      },
      { ds1: [{ sc_name: '产品 A', amt: 1200 }] },
    );
    expect(html).toContain('<table');
    expect(html).toContain('名称');
    expect(html).toContain('金额');
    expect(html).toContain('产品 A');
    // raw field code must not leak into a header cell
    expect(html).not.toContain('<th>sc_name</th>');
  });

  it('derives headers from row keys when no columns declared', () => {
    const { html } = doc(
      { blocks: [{ blockType: 'table', dataSource: 'ds1' }] },
      { ds1: [{ region: '华东', total: 5 }] },
    );
    expect(html).toContain('region');
    expect(html).toContain('total');
    expect(html).toContain('华东');
  });

  it('escapes HTML in cell values (no injection)', () => {
    const { html } = doc(
      { blocks: [{ blockType: 'table', dataSource: 'ds1', columns: [{ field: 'x' }] }] },
      { ds1: [{ x: '<script>alert(1)</script>' }] },
    );
    expect(html).not.toContain('<script>alert(1)</script>');
    expect(html).toContain('&lt;script&gt;');
  });
});

describe('renderReportToPrintDocument — running header/footer (the competitive differentiator)', () => {
  it('lifts page-header into a running headerTemplate, NOT the body', () => {
    const { html, headerTemplate } = doc({
      blocks: [{ blockType: 'page-header', content: '2026 年度销售报表' }],
    });
    expect(headerTemplate).toContain('2026 年度销售报表');
    expect(html).not.toContain('2026 年度销售报表');
  });

  it('lifts page-footer into a running footerTemplate with page numbers', () => {
    const { footerTemplate } = doc({
      blocks: [{ blockType: 'page-footer', text: '内部资料' }],
    });
    expect(footerTemplate).toContain('内部资料');
    // Chromium page-number tokens make this a true running footer.
    expect(footerTemplate).toContain('pageNumber');
    expect(footerTemplate).toContain('totalPages');
  });
});

describe('renderReportToPrintDocument — watermark', () => {
  it('emits a fixed-position watermark element (repeats per printed page)', () => {
    const { html } = doc({ blocks: [{ blockType: 'watermark', text: 'AuraBoot 机密' }] });
    expect(html).toContain('AuraBoot 机密');
    expect(html).toContain('watermark');
    expect(html).toContain('position: fixed');
  });
});

describe('renderReportToPrintDocument — canonical report DSL shape (body + shape-C chart)', () => {
  it('reads the `body` block list and a top-level categoryField/valueField chart', () => {
    const { html } = doc(
      {
        title: '运营报表',
        body: [
          {
            blockType: 'chart',
            title: '状态分布',
            dataSource: 'ops',
            chartType: 'bar',
            categoryField: 'status',
            valueField: 'cases',
          },
        ],
      },
      {
        ops: [
          { status: 'Open', cases: 12 },
          { status: 'Closed', cases: 3 },
        ],
      },
    );
    expect(html).toContain('<svg');
    expect(html).toContain('<path');
    expect(html).toContain('运营报表');
  });
});

describe('renderReportToPrintDocument — rich-text + document shell', () => {
  it('renders rich-text paragraphs and a self-contained html shell', () => {
    const { html } = doc({
      title: '月报',
      blocks: [{ blockType: 'rich-text', content: '第一段\n第二段' }],
    });
    expect(html).toContain('<!doctype html>');
    expect(html).toContain('第一段');
    expect(html).toContain('第二段');
    expect(html).toContain('月报');
  });
});

describe('renderReportToPrintDocument — stat-card / grouped-table / cross-tab', () => {
  const ops = [
    { region: 'North', status: 'Open', owner: 'Ops-A', cases: 12 },
    { region: 'North', status: 'Closed', owner: 'Ops-A', cases: 3 },
    { region: 'South', status: 'Open', owner: 'Ops-B', cases: 9 },
  ];

  it('stat-card aggregates valueField and shows the label', () => {
    const { html } = doc(
      {
        body: [
          {
            blockType: 'stat-card',
            dataSource: 'ops',
            valueField: 'cases',
            aggregation: 'sum',
            label: 'Total Cases',
          },
        ],
      },
      { ops },
    );
    expect(html).toContain('Total Cases');
    expect(html).toContain('stat-value');
    expect(html).toContain('>24<'); // 12 + 3 + 9
  });

  it('grouped-table renders group headers with counts then the group rows', () => {
    const { html } = doc(
      {
        body: [
          {
            blockType: 'grouped-table',
            dataSource: 'ops',
            groupByField: 'owner',
            columns: [
              { field: 'owner', label: 'Owner' },
              { field: 'region', label: 'Region' },
              { field: 'cases', label: 'Cases' },
            ],
          },
        ],
      },
      { ops },
    );
    // Labeled group field: the label replaces the raw field key (no code leak).
    expect(html).toContain('Owner: Ops-A (2)');
    expect(html).toContain('Owner: Ops-B (1)');
    expect(html).not.toContain('owner:');
    expect(html).toContain('group-header');
  });

  it('cross-tab pivots row x column with row + column totals', () => {
    const { html } = doc(
      {
        body: [
          {
            blockType: 'cross-tab',
            dataSource: 'ops',
            rowField: 'region',
            columnField: 'status',
            valueField: 'cases',
            aggregation: 'sum',
            showRowTotal: true,
            showColumnTotal: true,
            columns: [
              { field: 'region', label: '区域' },
              { field: 'status', label: '状态' },
            ],
          },
        ],
      },
      { ops },
    );
    // Axis labels come from the block column defs; unlabeled axes render an empty corner
    // instead of leaking raw field keys.
    expect(html).toContain('区域 \\ 状态');
    expect(html).not.toContain('region \\');
    const text = html.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ');
    expect(text).toContain('North 3 12 15'); // Closed, Open, row total
    expect(text).toContain('South 0 9 9');
    expect(text).toContain('Total 3 21 24'); // column totals + grand total
  });
});

describe('renderReportToPrintDocument — barcode block', () => {
  it('renders a real barcode SVG from staticValue', () => {
    const { html } = doc({
      body: [{ blockType: 'barcode', format: 'code128', staticValue: 'OPS-2026-EXPORT' }],
    });
    expect(html).toContain('<svg class="barcode"');
    expect(html).toContain('<rect');
    expect(html).toContain('OPS-2026-EXPORT');
  });

  it('resolves the barcode value from the first row field when no staticValue', () => {
    const { html } = doc(
      { body: [{ blockType: 'barcode', field: 'sku', dataSource: 'items' }] },
      { items: [{ sku: 'SKU-9988' }] },
    );
    expect(html).toContain('<svg class="barcode"');
    expect(html).toContain('SKU-9988');
  });
});

it('prints business total labels and explicit currency in tables and pivot totals', () => {
  const model = {
    body: [
      {
        blockType: 'table',
        dataSource: 'delivery',
        columns: [{ field: 'amount', label: '验收金额', format: 'currency' }],
      },
      {
        blockType: 'cross-tab',
        dataSource: 'delivery',
        rowField: 'project',
        columnField: 'status',
        valueField: 'amount',
        aggregation: 'sum',
        format: 'currency',
        totalLabel: '验收合计 <含税>',
      },
    ],
  };
  const result = renderReportToPrintDocument(model, {
    delivery: [{ project: '华辰', status: '已通过', amount: 68000.5 }],
  });
  expect(result.html).toContain('验收合计 &lt;含税&gt;');
  expect(result.html).not.toContain('>Total<');
  expect(result.html.match(/¥68,000.50/g)?.length).toBeGreaterThanOrEqual(5);
});

it('honors configured chart dimensions and prints its title once', () => {
  const result = renderReportToPrintDocument(
    {
      body: [
        {
          blockType: 'chart',
          title: '各项目验收金额',
          chartType: 'horizontal-bar',
          dataSource: 'delivery',
          categoryField: 'project',
          valueField: 'amount',
          width: 600,
          height: 180,
        },
      ],
    },
    { delivery: [{ project: '华辰', amount: 68000 }] },
  );
  expect(result.html).toContain('width="600"');
  expect(result.html).toContain('height="180"');
  expect(result.html.match(/各项目验收金额/g)).toHaveLength(1);
});

describe('configured report summary rows', () => {
  const columns = [
    { field: 'name', label: 'Item' },
    { field: 'amount', label: 'Amount', format: 'currency' },
  ];
  const rows = [
    { name: 'Alpha', team: 'A', amount: 10 },
    { name: 'Beta', team: 'A', amount: 2.5 },
    { name: 'Gamma', team: 'B', amount: 5 },
  ];
  it.each(['sum', 'avg', 'count', 'min', 'max'])(
    'renders table %s using the same summary contract as the designer',
    (aggregation) => {
      const { html } = doc(
        {
          body: [
            {
              blockType: 'table',
              dataSource: 'main',
              columns,
              summary: {
                enabled: true,
                label: 'Review total',
                columns: [{ field: 'amount', aggregation, format: 'currency' }],
              },
            },
          ],
        },
        { main: rows },
      );
      const expected = { sum: '17.50', avg: '5.83', count: '3.00', min: '2.50', max: '10.00' }[
        aggregation
      ];
      expect(html).toContain('class="summary-row table-summary"');
      expect(html).toContain('Review total');
      expect(html).toContain('¥' + expected);
    },
  );
  it('renders independent group subtotals and the grand total with escaped labels', () => {
    const { html } = doc(
      {
        body: [
          {
            blockType: 'grouped-table',
            dataSource: 'main',
            groupByField: 'team',
            columns,
            groupSubtotal: {
              enabled: true,
              label: '<Group>',
              columns: [{ field: 'amount', aggregation: 'sum', format: 'currency' }],
            },
            grandTotal: {
              enabled: true,
              label: 'Grand total',
              columns: [{ field: 'amount', aggregation: 'sum', format: 'currency' }],
            },
          },
        ],
      },
      { main: rows },
    );
    expect(html.match(/class="summary-row group-subtotal"/g)).toHaveLength(2);
    expect(html).toContain('&lt;Group&gt;');
    expect(html).toContain('¥12.50');
    expect(html).toContain('¥5.00');
    expect(html).toContain('¥17.50');
    expect(html).toContain('Grand total');
  });
  it('omits disabled summaries and tolerates the initial empty summary configuration', () => {
    const { html } = doc(
      {
        body: [
          { blockType: 'table', dataSource: 'main', columns, summary: { enabled: false } },
          {
            blockType: 'grouped-table',
            dataSource: 'main',
            columns,
            groupByField: 'team',
            groupSubtotal: { enabled: true },
            grandTotal: { enabled: false },
          },
        ],
      },
      { main: rows },
    );
    expect(html).not.toContain('class="summary-row table-summary"');
    expect(html).not.toContain('class="summary-row grand-total"');
    expect(html.match(/class="summary-row group-subtotal"/g)).toHaveLength(2);
  });
});

it('rounds currency summaries to two decimals and keeps null or text numeric input consistent with the designer', () => {
  const { html } = doc(
    {
      body: [
        {
          blockType: 'table',
          dataSource: 'main',
          columns: [{ field: 'name' }, { field: 'amount', format: 'currency' }],
          summary: {
            enabled: true,
            label: 'Average',
            columns: [{ field: 'amount', aggregation: 'avg', format: '' }],
          },
        },
      ],
    },
    {
      main: [
        { name: 'Null', amount: null },
        { name: 'Text', amount: '2.50' },
        { name: 'Invalid', amount: 'not-number' },
      ],
    },
  );
  expect(html).toContain('Average');
  expect(html).toContain('¥0.83');
  expect(html).not.toContain('¥0.833');
});

it('renders an explicit metric section title separately and escapes it without duplicating fallback labels', () => {
  const block = {
    blockType: 'stat-card',
    dataSource: 'main',
    valueField: 'amount',
    aggregation: 'sum',
    title: '<Executive>',
    label: 'Accepted amount',
  };
  const { html } = doc({ body: [block] }, { main: [{ amount: 10 }] });
  expect(html).toContain('<h2>&lt;Executive&gt;</h2>');
  expect(html).toContain('Accepted amount');
  expect(html).not.toContain('<Executive>');
  const fallback = doc({ body: [{ ...block, label: undefined }] }, { main: [] }).html;
  expect(fallback.match(/&lt;Executive&gt;/g)).toHaveLength(1);
});

describe('canonical designer print layout', () => {
  it('preserves widths and header, body and summary alignment', () => {
    const result = doc(
      {
        body: [
          {
            blockType: 'table',
            dataSource: 'rows',
            columns: [
              { field: 'name', label: '项目', width: 340, align: 'left' },
              { field: 'budget', label: '预算', width: 150, align: 'right', format: 'currency' },
            ],
            summary: {
              enabled: true,
              label: '合计',
              columns: [{ field: 'budget', aggregation: 'sum' }],
            },
          },
        ],
      },
      { rows: [{ name: '供应链协同', budget: 132000.5 }] },
    );
    expect(result.html).toContain('style="text-align:left;width:340px"');
    expect(result.html).toContain('style="text-align:right;width:150px"');
    expect(result.html.match(/<td style="text-align:right">¥132,000.50<\/td>/g)).toHaveLength(2);
  });
  it('shares column layout with grouped-table details and subtotals', () => {
    const result = doc(
      {
        body: [
          {
            blockType: 'grouped-table',
            dataSource: 'rows',
            groupByField: 'name',
            columns: [
              { field: 'name', label: '项目', width: 340 },
              { field: 'budget', label: '预算', width: 150, align: 'right', format: 'currency' },
            ],
            groupSubtotal: { enabled: true, columns: [{ field: 'budget', aggregation: 'sum' }] },
          },
        ],
      },
      { rows: [{ name: '供应链', budget: 1.5 }] },
    );
    expect(result.html).toContain('style="text-align:right;width:150px"');
    expect(result.html.match(/<td style="text-align:right">¥1.50<\/td>/g)).toHaveLength(2);
  });
  it('renders canonical bands as running templates and HTML chrome', () => {
    const result = doc({
      title: '标题',
      page: { margin: { left: 15, right: 18 } },
      header: {
        height: 15,
        elements: [
          {
            type: 'text',
            content: '交付简报',
            align: 'center',
            style: { fontSize: 14, fontWeight: 'bold' },
          },
        ],
      },
      footer: {
        height: 10,
        elements: [
          { type: 'text', content: '内部 <资料>' },
          { type: 'page-number', align: 'right' },
          { type: 'date' },
        ],
      },
      body: [],
    });
    expect(result.headerTemplate).toContain('交付简报');
    expect(result.headerTemplate).toContain('padding:0 18mm 0 15mm');
    expect(result.headerTemplate).toContain('font-size:14pt;font-weight:bold');
    expect(result.footerTemplate).toContain('第 <span class="pageNumber"></span> 页');
    expect(result.footerTemplate).toContain('内部 &lt;资料&gt;');
    expect(result.headerHeightMm).toBe(15);
    expect(result.footerHeightMm).toBe(10);
    expect(result.html).toContain('class="report-band-screen"');
    expect(result.html).toContain('第 1 页');
    expect(result.html).not.toContain('<h1>标题</h1>');
  });
  it('keeps canonical bands authoritative over legacy band blocks', () => {
    const result = doc({
      header: { height: 10, elements: [{ type: 'text', content: 'canonical' }] },
      body: [{ blockType: 'page-header', content: 'legacy' }],
    });
    expect(result.headerTemplate).toContain('canonical');
    expect(result.headerTemplate).not.toContain('legacy');
  });
  it.each([0, -1, Infinity, NaN])('rejects invalid width %s', (width) => {
    expect(() =>
      doc({ body: [{ blockType: 'table', columns: [{ field: 'name', width }] }] }),
    ).toThrow('Invalid report column width');
  });
  it('rejects arbitrary styles and remote band images', () => {
    expect(() =>
      doc({
        body: [
          {
            blockType: 'table',
            columns: [{ field: 'name', align: 'right; background:url(https://example.test)' }],
          },
        ],
      }),
    ).toThrow('Invalid report alignment');
    expect(() =>
      doc({
        header: {
          height: 15,
          elements: [{ type: 'image', content: 'https://example.test/image.png' }],
        },
        body: [],
      }),
    ).toThrow('embedded raster data');
  });
});
