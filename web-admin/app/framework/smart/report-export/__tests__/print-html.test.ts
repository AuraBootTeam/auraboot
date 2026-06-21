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
