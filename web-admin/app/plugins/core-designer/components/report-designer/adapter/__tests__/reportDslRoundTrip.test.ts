/**
 * B1 Phase 2 (groundwork) — block-tree → ReportDsl reverse adapter round-trip.
 *
 * Phase 2 swaps ReportCanvas onto the unified CanvasHost, which edits the
 * block-tree (PageSchemaV3). On SAVE the edited block-tree must serialize back to
 * the persisted ReportDsl. `blockTreeToReportDsl` is that save path; this proves
 * it is the exact inverse of `reportDslToBlockTree` (forward) — `dsl → tree → dsl`
 * is the identity for every block type, so the canvas swap cannot silently lose
 * authored data on save.
 */
import { describe, expect, it } from 'vitest';
import {
  blockTreeToReportDsl,
  reportDslToBlockTree,
} from '../reportDslCompatibilityAdapter';
import { DEFAULT_PAGE_CONFIG, type ReportDsl } from '../../types';

const base = {
  $schema: 'auraboot://schemas/report/v1' as const,
  version: '1.0.0' as const,
};

function roundTrip(dsl: ReportDsl): ReportDsl {
  return blockTreeToReportDsl(reportDslToBlockTree(dsl, { pageId: 'p1' }).page);
}

describe('blockTreeToReportDsl round-trip (dsl → tree → dsl === dsl)', () => {
  it('table-only', () => {
    const dsl: ReportDsl = {
      ...base,
      title: 'Sales',
      page: { ...DEFAULT_PAGE_CONFIG },
      dataSources: { orders: { type: 'static', data: [{ id: 1 }] } },
      body: [
        {
          id: 't1',
          blockType: 'table',
          title: 'Orders',
          dataSource: 'orders',
          columns: [{ field: 'id', label: 'ID' }],
          showHeader: true,
        },
      ],
    };
    expect(roundTrip(dsl)).toEqual(dsl);
  });

  it('chart with width/height/colors is lossless', () => {
    const dsl: ReportDsl = {
      ...base,
      title: 'C',
      page: { ...DEFAULT_PAGE_CONFIG },
      dataSources: { s: { type: 'model', modelCode: 'm' } },
      body: [
        {
          id: 'c1',
          blockType: 'chart',
          title: 'By Cat',
          dataSource: 's',
          chartType: 'horizontal-bar',
          categoryField: 'cat',
          valueField: 'amt',
          aggregation: 'sum',
          width: 300,
          height: 200,
          colors: ['accent', 'chart-1'],
        },
      ],
    };
    expect(roundTrip(dsl)).toEqual(dsl);
  });

  it('pie chart', () => {
    const dsl: ReportDsl = {
      ...base,
      title: 'P',
      page: { ...DEFAULT_PAGE_CONFIG },
      dataSources: { s: { type: 'namedQuery', queryCode: 'q' } },
      body: [
        {
          id: 'c1',
          blockType: 'chart',
          title: 'Pie',
          dataSource: 's',
          chartType: 'pie',
          categoryField: 'region',
          valueField: 'amt',
          aggregation: 'count',
        },
      ],
    };
    expect(roundTrip(dsl)).toEqual(dsl);
  });

  it('banded report (header / footer / parameters)', () => {
    const dsl: ReportDsl = {
      ...base,
      title: 'B',
      page: { ...DEFAULT_PAGE_CONFIG },
      dataSources: { orders: { type: 'static', data: [] } },
      parameters: [{ name: 'month', type: 'date', label: 'Month' }],
      header: { height: 30, elements: [{ type: 'text', content: 'H', align: 'center' }] },
      footer: { height: 20, elements: [{ type: 'page-number', align: 'right' }] },
      body: [{ id: 't1', blockType: 'table', dataSource: 'orders', columns: [{ field: 'id' }] }],
    };
    expect(roundTrip(dsl)).toEqual(dsl);
  });

  it('all 8 block types', () => {
    const dsl: ReportDsl = {
      ...base,
      title: 'All',
      page: { ...DEFAULT_PAGE_CONFIG },
      dataSources: { ds: { type: 'static', data: [] } },
      body: [
        { id: 'b1', blockType: 'table', dataSource: 'ds', columns: [{ field: 'a' }] },
        { id: 'b2', blockType: 'grouped-table', dataSource: 'ds', groupByField: 'g', columns: [{ field: 'a' }] },
        { id: 'b3', blockType: 'stat-card', dataSource: 'ds', valueField: 'v', aggregation: 'sum', label: 'L' },
        { id: 'b4', blockType: 'rich-text', content: 'hi', align: 'left' },
        { id: 'b5', blockType: 'cross-tab', dataSource: 'ds', rowField: 'r', columnField: 'c', valueField: 'v', aggregation: 'sum' },
        { id: 'b6', blockType: 'chart', dataSource: 'ds', chartType: 'bar', categoryField: 'c', valueField: 'v' },
        { id: 'b7', blockType: 'barcode', format: 'code128', staticValue: 'X' },
        { id: 'b8', blockType: 'watermark', text: 'DRAFT' },
      ] as never,
    };
    expect(roundTrip(dsl)).toEqual(dsl);
  });
});
