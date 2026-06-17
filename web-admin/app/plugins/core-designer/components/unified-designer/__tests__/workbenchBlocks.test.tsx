import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { createDefaultBlockRegistryV3 } from '../registry/BlockRegistry';
import { defaultInspectorSchemaRegistry } from '../registry/InspectorSchemaRegistry';
import { isBlockTypeAllowedForKind } from '../registry/kindPolicy';
import { RecursiveBlockRenderer } from '../runtime/RecursiveBlockRenderer';
import type { DslBlockV3, PageSchemaV3 } from '../types';

/**
 * Workbench blocks (metric-strip / status-banner) designer support.
 *
 * The full data-bound rendering lives on the live /p/ page (platform meta
 * renderers). These unit tests cover the DESIGNER-side surface this slice adds:
 *   - block registry definitions + nesting (canContain / allowedChildren)
 *   - per-kind palette policy (detail + dashboard)
 *   - inspector schemas written to the BLOCK TOP LEVEL (the keys the live
 *     renderer reads: metrics / variant / dataSource / statusField / toneMap …)
 *   - the config-driven representative preview rendered inside the designer
 *     runtime (RuntimeMetricStripPreview / RuntimeStatusBannerPreview)
 */

function renderSingleBlock(block: DslBlockV3, kind: PageSchemaV3['kind'] = 'detail') {
  const schema: PageSchemaV3 = {
    schemaVersion: 3,
    kind,
    id: 'wb_preview_page',
    blocks: [block],
  };
  return render(<RecursiveBlockRenderer schema={schema} />);
}

describe('workbench blocks — designer registry + policy', () => {
  it('registers metric-strip and status-banner in the default block registry', () => {
    const registry = createDefaultBlockRegistryV3();

    expect(registry.get('metric-strip')).toMatchObject({
      blockType: 'metric-strip',
      category: 'workbench',
      layoutCapability: 'span',
    });
    expect(registry.get('status-banner')).toMatchObject({
      blockType: 'status-banner',
      category: 'workbench',
      layoutCapability: 'span',
    });
    // built-ins still intact (no regression)
    expect(registry.get('widget')).toBeDefined();
    expect(registry.get('form')).toBeDefined();
  });

  it('allows nesting the workbench blocks under dashboard / detail / columns / tab', () => {
    const registry = createDefaultBlockRegistryV3();

    for (const parent of ['dashboard', 'detail', 'columns', 'tab']) {
      expect(registry.canContain(parent, 'metric-strip')).toBe(true);
      expect(registry.canContain(parent, 'status-banner')).toBe(true);
    }
    // existing children preserved (e.g. dashboard still holds widget)
    expect(registry.canContain('dashboard', 'widget')).toBe(true);
    // not loosened where it should not be
    expect(registry.canContain('table', 'metric-strip')).toBe(false);
  });

  it('surfaces the workbench blocks in the detail and dashboard palette only', () => {
    expect(isBlockTypeAllowedForKind('detail', 'metric-strip')).toBe(true);
    expect(isBlockTypeAllowedForKind('detail', 'status-banner')).toBe(true);
    expect(isBlockTypeAllowedForKind('dashboard', 'metric-strip')).toBe(true);
    expect(isBlockTypeAllowedForKind('dashboard', 'status-banner')).toBe(true);
    // composite (escape hatch) allows everything
    expect(isBlockTypeAllowedForKind('composite', 'metric-strip')).toBe(true);
    // form/list kinds do NOT offer them
    expect(isBlockTypeAllowedForKind('form', 'metric-strip')).toBe(false);
    expect(isBlockTypeAllowedForKind('list', 'status-banner')).toBe(false);
  });
});

describe('workbench blocks — inspector schemas (top-level keys)', () => {
  it('exposes metric-strip props as bare top-level keys the live renderer reads', () => {
    const fields = defaultInspectorSchemaRegistry.getFields('metric-strip');
    const keys = fields.map((f) => f.key);
    // Real platform renderer reads block.dataSource / block.variant / block.metrics
    // (top level), NOT block.props.* — so the inspector keys must be bare.
    expect(keys).toContain('dataSource');
    expect(keys).toContain('variant');
    expect(keys).toContain('metrics');
    expect(keys).not.toContain('props.metrics');
    // metrics is JSON-authored (array editor)
    expect(fields.find((f) => f.key === 'metrics')?.type).toBe('json');
  });

  it('exposes status-banner props as bare top-level keys the live renderer reads', () => {
    const fields = defaultInspectorSchemaRegistry.getFields('status-banner');
    const keys = fields.map((f) => f.key);
    for (const key of [
      'dataSource',
      'statusField',
      'errorField',
      'toneMap',
      'titleMap',
      'descriptionMap',
      'summaryFields',
    ]) {
      expect(keys).toContain(key);
    }
    expect(keys).not.toContain('props.statusField');
    expect(fields.find((f) => f.key === 'toneMap')?.type).toBe('json');
  });
});

describe('workbench blocks — designer runtime representative preview', () => {
  it('renders metric labels with placeholder values from the authored metrics', () => {
    renderSingleBlock({
      id: 'wb_metrics',
      blockType: 'metric-strip',
      title: 'KPI strip',
      dataSource: 'andonStats',
      metrics: [
        { key: 'open_total', label: { 'en-US': 'Open', 'zh-CN': '未决' }, valueField: 'open_total', tone: 'blue' },
        { key: 'open_critical', label: { 'en-US': 'Critical' }, valueField: 'open_critical', tone: 'red' },
      ],
    } as unknown as DslBlockV3);

    const strip = screen.getByTestId('runtime-metric-strip-wb_metrics');
    expect(strip).toHaveTextContent('KPI strip');
    // metric labels render representatively. The designer runtime defaults to the
    // zh-CN locale, so a localized label resolves to its zh-CN value; a label that
    // only declares en-US falls back to that.
    expect(screen.getByTestId('runtime-metric-strip-item-open_total')).toHaveTextContent('未决');
    expect(screen.getByTestId('runtime-metric-strip-item-open_critical')).toHaveTextContent('Critical');
    // placeholder value (— ) is shown, not real data (live data renders on /p/)
    expect(screen.getByTestId('runtime-metric-strip-value-open_total')).toHaveTextContent('—');
    // hint clarifies this is a representative preview
    expect(screen.getByTestId('runtime-metric-strip-hint-wb_metrics')).toBeInTheDocument();
  });

  it('shows the empty state when no metrics are configured', () => {
    renderSingleBlock({
      id: 'wb_metrics_empty',
      blockType: 'metric-strip',
      title: 'Empty strip',
    } as unknown as DslBlockV3);

    expect(screen.getByTestId('runtime-metric-strip-empty-wb_metrics_empty')).toBeInTheDocument();
  });

  it('renders a representative status banner from the configured toneMap / titleMap', () => {
    renderSingleBlock({
      id: 'wb_status',
      blockType: 'status-banner',
      title: 'Task status',
      dataSource: 'taskSummary',
      statusField: 'bom_task_status',
      toneMap: { parsing: 'blue', failed: 'red' },
      titleMap: {
        parsing: { 'en-US': 'Parsing BOM', 'zh-CN': '正在解析' },
        failed: { 'en-US': 'Failed' },
      },
    } as unknown as DslBlockV3);

    const banner = screen.getByTestId('runtime-status-banner-wb_status');
    expect(banner).toHaveTextContent('Task status');
    const sample = screen.getByTestId('runtime-status-banner-sample-wb_status');
    // first configured status drives the representative sample. zh-CN locale
    // resolves the localized title to its zh-CN value.
    expect(sample).toHaveAttribute('data-status', 'parsing');
    expect(screen.getByTestId('runtime-status-banner-title-wb_status')).toHaveTextContent('正在解析');
    expect(screen.getByTestId('runtime-status-banner-hint-wb_status')).toBeInTheDocument();
  });

  it('shows the not-configured state for an empty status banner', () => {
    renderSingleBlock({
      id: 'wb_status_empty',
      blockType: 'status-banner',
      title: 'No mapping',
    } as unknown as DslBlockV3);

    expect(
      screen.getByTestId('runtime-status-banner-empty-wb_status_empty'),
    ).toBeInTheDocument();
  });
});
