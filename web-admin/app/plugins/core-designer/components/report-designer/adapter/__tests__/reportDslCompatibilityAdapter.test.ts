/**
 * B1 Phase 1 — ReportDslCompatibilityAdapter tests.
 *
 * The adapter converts the legacy ReportDsl (v1) into a normalized,
 * block-tree-compatible PageSchemaV3 + a Layer-2 ReportLayoutProfile, and
 * expresses report charts as renderer-agnostic ChartSpec (consuming B2a).
 *
 * Contract goals (DDR-2026-06-18 / backlog B1 Phase 1 DoD):
 *  - report blocks become block-tree DslBlockV3 nodes (namespaced `report-*`)
 *  - paged-media concerns (page/bands/parameters) live in a Layer-2 profile,
 *    NOT as block-tree nodes (Forbidden: bands/page-break into Layer-1 kernel)
 *  - report chart is expressible as ChartSpec (renderer-agnostic)
 *  - page-level shared dataSources are preserved (round-trip fidelity)
 */
import { describe, expect, it } from 'vitest';
import { assertRendererAgnostic } from '~/framework/smart/charts/chart-spec';
import type { ReportDsl } from '../../types';
import { DEFAULT_PAGE_CONFIG } from '../../types';
import { reportDslToBlockTree } from '../reportDslCompatibilityAdapter';

const base = {
  $schema: 'auraboot://schemas/report/v1' as const,
  version: '1.0.0' as const,
};

function tableOnly(): ReportDsl {
  return {
    ...base,
    title: 'Sales Report',
    page: { ...DEFAULT_PAGE_CONFIG },
    dataSources: {
      orders: { type: 'static', data: [{ id: 1, amount: 10 }] },
    },
    body: [
      {
        id: 't1',
        blockType: 'table',
        title: 'Orders',
        dataSource: 'orders',
        columns: [{ field: 'id', label: 'ID' }, { field: 'amount', label: 'Amount' }],
        showHeader: true,
      },
    ],
  };
}

function chartReport(): ReportDsl {
  return {
    ...base,
    title: 'Chart Report',
    page: { ...DEFAULT_PAGE_CONFIG },
    dataSources: {
      sales: { type: 'model', modelCode: 'sl_order' },
    },
    body: [
      {
        id: 'c1',
        blockType: 'chart',
        title: 'By Category',
        dataSource: 'sales',
        chartType: 'horizontal-bar',
        categoryField: 'category',
        valueField: 'amount',
        aggregation: 'sum',
      },
    ],
  };
}

function bandedReport(): ReportDsl {
  return {
    ...base,
    title: 'Banded Report',
    page: { ...DEFAULT_PAGE_CONFIG },
    dataSources: { orders: { type: 'static', data: [] } },
    parameters: [{ name: 'month', type: 'date', label: 'Month' }],
    header: { height: 30, elements: [{ type: 'text', content: 'Monthly', align: 'center' }] },
    footer: { height: 20, elements: [{ type: 'page-number', align: 'right' }] },
    body: [
      { id: 't1', blockType: 'table', dataSource: 'orders', columns: [{ field: 'id' }] },
    ],
  };
}

describe('reportDslToBlockTree', () => {
  describe('table-only report', () => {
    it('emits a single namespaced report-table block-tree node', () => {
      const r = reportDslToBlockTree(tableOnly(), { pageId: 'p1' });
      expect(r.page.schemaVersion).toBe(3);
      expect(r.page.id).toBe('p1');
      expect(r.page.blocks).toHaveLength(1);
      expect(r.page.blocks[0].blockType).toBe('report-table');
      expect(r.page.blocks[0].id).toBe('t1');
    });

    it('references the page-level data source by ref (no inlining/duplication)', () => {
      const r = reportDslToBlockTree(tableOnly(), { pageId: 'p1' });
      expect(r.page.blocks[0].dataSource).toEqual({ ref: 'orders' });
    });

    it('carries report-specific table props (columns/showHeader)', () => {
      const r = reportDslToBlockTree(tableOnly(), { pageId: 'p1' });
      expect(r.page.blocks[0].props).toMatchObject({
        columns: [{ field: 'id', label: 'ID' }, { field: 'amount', label: 'Amount' }],
        showHeader: true,
      });
    });

    it('preserves the full page-level dataSources map for round-trip', () => {
      const dsl = tableOnly();
      const r = reportDslToBlockTree(dsl, { pageId: 'p1' });
      expect(r.page.extension?.reportDataSources).toEqual(dsl.dataSources);
      expect(r.page.extension?.surface).toBe('report');
    });

    it('puts the page config into the Layer-2 layout profile, not a block', () => {
      const r = reportDslToBlockTree(tableOnly(), { pageId: 'p1' });
      expect(r.layoutProfile.page).toEqual(DEFAULT_PAGE_CONFIG);
    });

    it('has no charts', () => {
      const r = reportDslToBlockTree(tableOnly(), { pageId: 'p1' });
      expect(r.charts).toEqual({});
    });
  });

  describe('chart report', () => {
    it('expresses a horizontal-bar chart as a renderer-agnostic ChartSpec', () => {
      const r = reportDslToBlockTree(chartReport(), { pageId: 'p1' });
      const spec = r.charts['c1'];
      expect(spec).toBeDefined();
      expect(spec.type).toBe('bar');
      expect(spec.visual?.orientation).toBe('horizontal');
      expect(spec.dimensions).toContainEqual({ field: 'category', role: 'category' });
      expect(spec.measures).toEqual([{ field: 'amount', aggregation: 'sum' }]);
      expect(() => assertRendererAgnostic(spec)).not.toThrow();
    });

    it('resolves the report data source into a ChartDataSource', () => {
      const r = reportDslToBlockTree(chartReport(), { pageId: 'p1' });
      expect(r.charts['c1'].dataSource).toMatchObject({ type: 'aggregate', modelCode: 'sl_order' });
    });

    it('embeds the ChartSpec on the report-chart block props', () => {
      const r = reportDslToBlockTree(chartReport(), { pageId: 'p1' });
      const block = r.page.blocks[0];
      expect(block.blockType).toBe('report-chart');
      expect(block.props?.chartSpec).toEqual(r.charts['c1']);
    });

    it('maps pie chart category to a name dimension', () => {
      const dsl = chartReport();
      (dsl.body[0] as { chartType: string }).chartType = 'pie';
      const r = reportDslToBlockTree(dsl, { pageId: 'p1' });
      const spec = r.charts['c1'];
      expect(spec.type).toBe('pie');
      expect(spec.dimensions).toEqual([{ field: 'category', role: 'name' }]);
      expect(spec.visual?.orientation).toBeUndefined();
    });
  });

  describe('banded report (header/footer/parameters)', () => {
    it('puts bands + parameters into the Layer-2 profile, never as block-tree nodes', () => {
      const r = reportDslToBlockTree(bandedReport(), { pageId: 'p1' });
      expect(r.layoutProfile.header?.elements[0]).toMatchObject({ type: 'text', content: 'Monthly' });
      expect(r.layoutProfile.footer?.elements[0]).toMatchObject({ type: 'page-number' });
      expect(r.layoutProfile.parameters).toEqual([{ name: 'month', type: 'date', label: 'Month' }]);
      // bands are NOT block-tree nodes — only the table block is in the tree
      expect(r.page.blocks).toHaveLength(1);
      expect(r.page.blocks.every((b) => !b.blockType.includes('band'))).toBe(true);
    });
  });

  describe('block-type namespacing (all 8 report block types)', () => {
    const cases: Array<[string, string]> = [
      ['table', 'report-table'],
      ['grouped-table', 'report-grouped-table'],
      ['stat-card', 'report-stat-card'],
      ['rich-text', 'report-rich-text'],
      ['cross-tab', 'report-cross-tab'],
      ['chart', 'report-chart'],
      ['barcode', 'report-barcode'],
      ['watermark', 'report-watermark'],
    ];
    it.each(cases)('maps report blockType %s to %s', (reportType, treeType) => {
      const dsl: ReportDsl = {
        ...base,
        title: 'T',
        page: { ...DEFAULT_PAGE_CONFIG },
        dataSources: { ds: { type: 'static', data: [] } },
        body: [{ id: 'b1', blockType: reportType, dataSource: 'ds' } as never],
      };
      const r = reportDslToBlockTree(dsl, { pageId: 'p1' });
      expect(r.page.blocks[0].blockType).toBe(treeType);
    });
  });

  describe('snapshot goldens (old DSL -> normalized block-tree schema)', () => {
    it('table-only', () => {
      expect(reportDslToBlockTree(tableOnly(), { pageId: 'fixed' })).toMatchSnapshot();
    });
    it('chart', () => {
      expect(reportDslToBlockTree(chartReport(), { pageId: 'fixed' })).toMatchSnapshot();
    });
    it('header/footer + paged band', () => {
      expect(reportDslToBlockTree(bandedReport(), { pageId: 'fixed' })).toMatchSnapshot();
    });
  });
});
