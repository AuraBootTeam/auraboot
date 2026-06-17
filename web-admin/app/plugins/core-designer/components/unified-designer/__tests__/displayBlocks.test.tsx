import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { createDefaultBlockRegistryV3 } from '../registry/BlockRegistry';
import { defaultInspectorSchemaRegistry } from '../registry/InspectorSchemaRegistry';
import { isBlockTypeAllowedForKind } from '../registry/kindPolicy';
import { RecursiveBlockRenderer } from '../runtime/RecursiveBlockRenderer';
import type { DslBlockV3, PageSchemaV3 } from '../types';

/**
 * Display / data blocks (non workbench-family) designer support:
 *   stat-card       ← StatCardBlockRenderer
 *   description     ← DescriptionBlockRenderer
 *   record-comments ← RecordComments (DetailPageContent dispatch)
 *   embedded-list   ← EmbeddedListBlockRenderer
 *
 * The full data-bound rendering lives on the live /p/ page (platform meta
 * renderers). These unit tests cover the DESIGNER-side surface this slice adds:
 *   - block registry definitions + nesting (canContain / allowedChildren)
 *   - per-kind palette policy (stat-card/description on detail+dashboard;
 *     record-comments/embedded-list on detail only)
 *   - inspector schemas written to the EXACT path the live renderer reads
 *     (bare statCard / content / modelCode / columns; record-comments has no
 *     data fields because the live renderer derives them from page context)
 *   - the config-driven representative preview rendered inside the designer
 *     runtime (RuntimeStatCardPreview / RuntimeDescriptionPreview /
 *     RuntimeRecordCommentsPreview / RuntimeEmbeddedListPreview)
 */

function renderSingleBlock(block: DslBlockV3, kind: PageSchemaV3['kind'] = 'detail') {
  const schema: PageSchemaV3 = {
    schemaVersion: 3,
    kind,
    id: 'display_preview_page',
    blocks: [block],
  };
  return render(<RecursiveBlockRenderer schema={schema} />);
}

const DISPLAY_BLOCKS = ['stat-card', 'description', 'record-comments', 'embedded-list'] as const;

describe('display blocks — designer registry + policy', () => {
  it('registers all four display blocks with the expected category + span layout', () => {
    const registry = createDefaultBlockRegistryV3();

    expect(registry.get('stat-card')).toMatchObject({
      blockType: 'stat-card',
      category: 'dashboard',
      layoutCapability: 'span',
    });
    expect(registry.get('description')).toMatchObject({
      blockType: 'description',
      category: 'detail',
      layoutCapability: 'span',
    });
    expect(registry.get('record-comments')).toMatchObject({
      blockType: 'record-comments',
      category: 'detail',
      layoutCapability: 'span',
    });
    expect(registry.get('embedded-list')).toMatchObject({
      blockType: 'embedded-list',
      category: 'list',
      layoutCapability: 'span',
    });
    // built-ins + workbench family still intact (no regression)
    expect(registry.get('metric-strip')).toBeDefined();
    expect(registry.get('widget')).toBeDefined();
    expect(registry.get('form')).toBeDefined();
  });

  it('allows nesting every display block under detail; stat-card/description also under dashboard/columns/tab', () => {
    const registry = createDefaultBlockRegistryV3();

    for (const blockType of DISPLAY_BLOCKS) {
      expect(registry.canContain('detail', blockType), `detail can contain ${blockType}`).toBe(
        true,
      );
    }
    // stat-card + description are generic display blocks usable in cockpit layouts.
    for (const parent of ['dashboard', 'columns', 'tab']) {
      expect(registry.canContain(parent, 'stat-card'), `${parent} can contain stat-card`).toBe(
        true,
      );
      expect(registry.canContain(parent, 'description'), `${parent} can contain description`).toBe(
        true,
      );
    }
    // record-comments + embedded-list are detail-scoped (resolve the surrounding
    // record from the detail route) — NOT offered on dashboard.
    expect(registry.canContain('dashboard', 'record-comments')).toBe(false);
    expect(registry.canContain('dashboard', 'embedded-list')).toBe(false);
    // existing children preserved + not loosened where it should not be
    expect(registry.canContain('dashboard', 'widget')).toBe(true);
    for (const blockType of DISPLAY_BLOCKS) {
      expect(registry.canContain('table', blockType)).toBe(false);
      expect(registry.canContain('form', blockType)).toBe(false);
    }
  });

  it('surfaces display blocks in the correct kind palettes', () => {
    // stat-card + description: detail AND dashboard
    for (const blockType of ['stat-card', 'description'] as const) {
      expect(isBlockTypeAllowedForKind('detail', blockType)).toBe(true);
      expect(isBlockTypeAllowedForKind('dashboard', blockType)).toBe(true);
      expect(isBlockTypeAllowedForKind('composite', blockType)).toBe(true);
      expect(isBlockTypeAllowedForKind('form', blockType)).toBe(false);
      expect(isBlockTypeAllowedForKind('list', blockType)).toBe(false);
    }
    // record-comments + embedded-list: detail ONLY (not dashboard)
    for (const blockType of ['record-comments', 'embedded-list'] as const) {
      expect(isBlockTypeAllowedForKind('detail', blockType)).toBe(true);
      expect(isBlockTypeAllowedForKind('dashboard', blockType)).toBe(false);
      expect(isBlockTypeAllowedForKind('composite', blockType)).toBe(true);
      expect(isBlockTypeAllowedForKind('form', blockType)).toBe(false);
      expect(isBlockTypeAllowedForKind('list', blockType)).toBe(false);
    }
  });
});

describe('display blocks — inspector schemas (exact renderer paths)', () => {
  it('stat-card exposes bare dataSource + statCard (JSON) the live renderer reads', () => {
    const fields = defaultInspectorSchemaRegistry.getFields('stat-card');
    const keys = fields.map((f) => f.key);
    // StatCardBlockRenderer reads block.dataSource (string id) + block.statCard
    // (object spread over props) — bare top-level, NOT props.*.
    expect(keys).toContain('dataSource');
    expect(keys).toContain('statCard');
    expect(keys).not.toContain('props.statCard');
    expect(fields.find((f) => f.key === 'statCard')?.type).toBe('json');
    // dataSource is a string id (text), not the model selector.
    expect(fields.find((f) => f.key === 'dataSource')?.type).toBe('text');
  });

  it('description exposes the bare content path the live renderer reads first', () => {
    const fields = defaultInspectorSchemaRegistry.getFields('description');
    const keys = fields.map((f) => f.key);
    // DescriptionBlockRenderer reads block.content ?? props.content ?? props.text;
    // the bare `content` wins, so the inspector exposes it (not props.content).
    expect(keys).toContain('content');
    expect(keys).not.toContain('props.content');
    expect(keys).not.toContain('props.text');
  });

  it('record-comments exposes only the title (live renderer derives data from page context)', () => {
    const fields = defaultInspectorSchemaRegistry.getFields('record-comments');
    const keys = fields.map((f) => f.key);
    // RecordComments derives modelCode + recordPid from the surrounding detail
    // page — it has NO block-level data config. Surfacing modelCode/recordPid here
    // would be invented fields, so the inspector deliberately exposes none.
    expect(keys).toContain('title');
    expect(keys).not.toContain('modelCode');
    expect(keys).not.toContain('recordPid');
    expect(keys).not.toContain('dataSource');
  });

  it('embedded-list exposes the bare top-level keys the live renderer reads', () => {
    const fields = defaultInspectorSchemaRegistry.getFields('embedded-list');
    const keys = fields.map((f) => f.key);
    for (const key of ['modelCode', 'parentField', 'columns', 'pageSize', 'searchable', 'filterable']) {
      expect(keys, `embedded-list should expose ${key}`).toContain(key);
      expect(keys, `embedded-list ${key} must be bare (no props.)`).not.toContain(`props.${key}`);
    }
    // columns is JSON-authored (array editor)
    expect(fields.find((f) => f.key === 'columns')?.type).toBe('json');
  });
});

describe('display blocks — designer runtime representative preview', () => {
  it('renders stat-card inline value / unit / trend / binding and the empty state', () => {
    renderSingleBlock({
      id: 'disp_stat',
      blockType: 'stat-card',
      title: 'Orders today',
      dataSource: 'andonStats',
      statCard: { value: 42, unit: 'orders', trend: '+12%', trendDirection: 'up', valueField: 'open_total' },
    } as unknown as DslBlockV3);

    const card = screen.getByTestId('runtime-stat-card-disp_stat');
    expect(card).toHaveTextContent('Orders today');
    // inline value + unit + trend render representatively
    expect(screen.getByTestId('runtime-stat-card-value-disp_stat')).toHaveTextContent('42');
    expect(screen.getByTestId('runtime-stat-card-trend-disp_stat')).toHaveTextContent('+12%');
    // the binding (dataSource · valueField) is surfaced so the author sees the wiring
    expect(screen.getByTestId('runtime-stat-card-binding-disp_stat')).toHaveTextContent('andonStats');
    expect(screen.getByTestId('runtime-stat-card-binding-disp_stat')).toHaveTextContent('open_total');
    expect(screen.getByTestId('runtime-stat-card-hint-disp_stat')).toBeInTheDocument();
  });

  it('renders a stat-card value placeholder when only a data source is bound (no inline value)', () => {
    renderSingleBlock({
      id: 'disp_stat_bound',
      blockType: 'stat-card',
      title: 'Bound only',
      dataSource: 'ds_metrics',
      statCard: { valueField: 'total' },
    } as unknown as DslBlockV3);

    // placeholder value (— ) is shown, not real data (live data renders on /p/)
    expect(screen.getByTestId('runtime-stat-card-value-disp_stat_bound')).toHaveTextContent('—');
    expect(screen.getByTestId('runtime-stat-card-binding-disp_stat_bound')).toHaveTextContent('total');
  });

  it('renders description content and the empty state', () => {
    renderSingleBlock({
      id: 'disp_desc',
      blockType: 'description',
      title: 'Notes',
      content: { 'en-US': 'Read before submitting', 'zh-CN': '提交前请阅读' },
    } as unknown as DslBlockV3);

    expect(screen.getByTestId('runtime-description-disp_desc')).toHaveTextContent('Notes');
    // zh-CN locale (designer default) resolves the localized content to its zh-CN value
    expect(screen.getByTestId('runtime-description-content-disp_desc')).toHaveTextContent('提交前请阅读');
    expect(screen.getByTestId('runtime-description-hint-disp_desc')).toBeInTheDocument();

    renderSingleBlock({
      id: 'disp_desc_empty',
      blockType: 'description',
      title: 'No content',
    } as unknown as DslBlockV3);
    expect(screen.getByTestId('runtime-description-empty-disp_desc_empty')).toBeInTheDocument();
  });

  it('falls back to props.content / props.text for description (mirrors the renderer read order)', () => {
    renderSingleBlock({
      id: 'disp_desc_props',
      blockType: 'description',
      title: 'From props',
      props: { text: 'Legacy props.text content' },
    } as unknown as DslBlockV3);
    expect(screen.getByTestId('runtime-description-content-disp_desc_props')).toHaveTextContent(
      'Legacy props.text content',
    );
  });

  it('renders the record-comments representative scaffold (live thread loads on the detail page)', () => {
    renderSingleBlock({
      id: 'disp_comments',
      blockType: 'record-comments',
      title: 'Discussion',
    } as unknown as DslBlockV3);

    expect(screen.getByTestId('runtime-record-comments-disp_comments')).toHaveTextContent('Discussion');
    expect(screen.getByTestId('runtime-record-comments-sample-disp_comments')).toBeInTheDocument();
    expect(screen.getByTestId('runtime-record-comments-hint-disp_comments')).toBeInTheDocument();
  });

  it('renders embedded-list model binding + column headers and the not-configured state', () => {
    renderSingleBlock({
      id: 'disp_embedded',
      blockType: 'embedded-list',
      title: 'Line items',
      modelCode: 'order_line',
      parentField: 'order_id',
      columns: [
        { field: 'sku', label: { 'en-US': 'SKU', 'zh-CN': '物料编码' } },
        { field: 'qty', label: { 'en-US': 'Qty', 'zh-CN': '数量' } },
      ],
    } as unknown as DslBlockV3);

    expect(screen.getByTestId('runtime-embedded-list-disp_embedded')).toHaveTextContent('Line items');
    expect(screen.getByTestId('runtime-embedded-list-binding-disp_embedded')).toHaveTextContent('order_line');
    expect(screen.getByTestId('runtime-embedded-list-binding-disp_embedded')).toHaveTextContent('order_id');
    // column headers render representatively (zh-CN locale resolves localized labels)
    expect(screen.getByTestId('runtime-embedded-list-column-sku')).toHaveTextContent('物料编码');
    expect(screen.getByTestId('runtime-embedded-list-column-qty')).toHaveTextContent('数量');
    expect(screen.getByTestId('runtime-embedded-list-hint-disp_embedded')).toBeInTheDocument();

    // not-configured when modelCode is missing (the live renderer's hard requirement)
    renderSingleBlock({
      id: 'disp_embedded_empty',
      blockType: 'embedded-list',
      title: 'Unbound list',
    } as unknown as DslBlockV3);
    expect(screen.getByTestId('runtime-embedded-list-empty-disp_embedded_empty')).toBeInTheDocument();
  });
});
