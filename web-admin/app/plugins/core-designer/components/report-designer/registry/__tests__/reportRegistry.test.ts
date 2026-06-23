/**
 * B1 Phase 1 (PR-1b) — report BlockRegistry + InspectorSchemaRegistry tests.
 *
 * The report surface gets its OWN Layer-2 registry instances (reusing the shared
 * Layer-0 BlockRegistryV3 / InspectorSchemaRegistry / PropertySchema classes),
 * keeping the report-* block vocabulary isolated from the default unified
 * registry (no pollution of page/form/list). Inspector field keys mirror the
 * adapter's prop placement (props.* / dataSource.ref) so an adapter-produced
 * block round-trips through the inspector.
 */
import { describe, expect, it } from 'vitest';
import { reportDslToBlockTree } from '../../adapter/reportDslCompatibilityAdapter';
import { DEFAULT_PAGE_CONFIG, type ReportDsl } from '../../types';
import { createReportBlockRegistry, REPORT_BLOCK_TYPES } from '../reportBlockRegistry';
import { reportInspectorSchemaRegistry } from '../reportInspectorSchemas';

describe('createReportBlockRegistry', () => {
  const registry = createReportBlockRegistry();

  it('registers exactly the 8 namespaced report block types', () => {
    const types = registry.getAll().map((d) => d.blockType).sort();
    expect(types).toEqual(
      [
        'report-barcode',
        'report-chart',
        'report-cross-tab',
        'report-grouped-table',
        'report-rich-text',
        'report-stat-card',
        'report-table',
        'report-watermark',
      ].sort(),
    );
    expect(types).toEqual([...REPORT_BLOCK_TYPES].sort());
  });

  it('does NOT register the generic unified block types (isolation, no pollution)', () => {
    expect(registry.get('table')).toBeUndefined();
    expect(registry.get('form')).toBeUndefined();
    expect(registry.get('chart')).toBeUndefined();
    expect(registry.get('list')).toBeUndefined();
  });

  it('each definition has label, icon, category and an inspector with fields', () => {
    for (const def of registry.getAll()) {
      expect(def.label).toBeTruthy();
      expect(def.icon).toBeTruthy();
      expect(def.category).toBe('report');
      expect(def.inspector?.tabs?.[0]?.groups?.[0]?.fields?.length).toBeGreaterThan(0);
    }
  });
});

describe('reportInspectorSchemaRegistry', () => {
  const keysFor = (blockType: string) =>
    reportInspectorSchemaRegistry.getFields(blockType).map((f) => f.key);

  it('report-table inspector exposes data source ref + table props', () => {
    const keys = keysFor('report-table');
    expect(keys).toContain('dataSource.ref');
    expect(keys).toContain('props.columns');
    expect(keys).toContain('props.showHeader');
  });

  it('report-chart inspector exposes the chartSpec', () => {
    expect(keysFor('report-chart')).toContain('props.chartSpec');
  });

  it('report-stat-card inspector exposes valueField + aggregation select', () => {
    const fields = reportInspectorSchemaRegistry.getFields('report-stat-card');
    const agg = fields.find((f) => f.key === 'props.aggregation');
    expect(agg?.type).toBe('select');
    expect(agg?.options?.map((o) => o.value)).toEqual(['sum', 'avg', 'count', 'min', 'max']);
    expect(fields.map((f) => f.key)).toContain('props.valueField');
  });

  it('report-watermark inspector exposes text + visual props (no dataSource)', () => {
    const keys = keysFor('report-watermark');
    expect(keys).toContain('props.text');
    expect(keys).not.toContain('dataSource.ref');
  });
});

describe('adapter ↔ registry vocabulary agreement (round-trip)', () => {
  it('every block the adapter emits is a registered report block type', () => {
    const dsl: ReportDsl = {
      $schema: 'auraboot://schemas/report/v1',
      version: '1.0.0',
      title: 'All blocks',
      page: { ...DEFAULT_PAGE_CONFIG },
      dataSources: { ds: { type: 'static', data: [] } },
      body: [
        { id: 'b1', blockType: 'table', dataSource: 'ds', columns: [] },
        { id: 'b2', blockType: 'grouped-table', dataSource: 'ds', groupByField: 'g', columns: [] },
        { id: 'b3', blockType: 'stat-card', dataSource: 'ds', valueField: 'v', aggregation: 'sum', label: 'L' },
        { id: 'b4', blockType: 'rich-text', content: 'hi' },
        { id: 'b5', blockType: 'cross-tab', dataSource: 'ds', rowField: 'r', columnField: 'c', valueField: 'v', aggregation: 'sum' },
        { id: 'b6', blockType: 'chart', dataSource: 'ds', chartType: 'bar', categoryField: 'c', valueField: 'v' },
        { id: 'b7', blockType: 'barcode', format: 'code128', staticValue: 'X' },
        { id: 'b8', blockType: 'watermark', text: 'DRAFT' },
      ] as never,
    };
    const registry = createReportBlockRegistry();
    const { page } = reportDslToBlockTree(dsl, { pageId: 'p1' });
    expect(page.blocks).toHaveLength(8);
    for (const block of page.blocks) {
      expect(registry.get(block.blockType)).toBeDefined();
    }
  });
});
