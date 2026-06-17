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

/**
 * Workbench-family batch 2 — the remaining six platform workbench blocks
 * (workbench-action-bar / review-drawer / evidence-panel / record-inspector /
 * candidate-list / artifact-timeline). Same designer-side surface as
 * metric-strip / status-banner: registry + nesting + per-kind palette + bare
 * top-level inspector keys + config-driven representative preview. Full data
 * binding lives on the live /p/ page (platform meta renderers).
 */

const BATCH2_BLOCKS = [
  'workbench-action-bar',
  'review-drawer',
  'evidence-panel',
  'record-inspector',
  'candidate-list',
  'artifact-timeline',
] as const;

describe('workbench blocks batch 2 — designer registry + policy', () => {
  it('registers all six batch-2 workbench blocks (category workbench, span layout)', () => {
    const registry = createDefaultBlockRegistryV3();
    for (const blockType of BATCH2_BLOCKS) {
      expect(registry.get(blockType), `${blockType} registered`).toMatchObject({
        blockType,
        category: 'workbench',
        layoutCapability: 'span',
      });
    }
    // built-ins + batch-1 still intact (no regression)
    expect(registry.get('metric-strip')).toBeDefined();
    expect(registry.get('status-banner')).toBeDefined();
    expect(registry.get('widget')).toBeDefined();
  });

  it('allows nesting every batch-2 workbench block under dashboard / detail / columns / tab', () => {
    const registry = createDefaultBlockRegistryV3();
    for (const parent of ['dashboard', 'detail', 'columns', 'tab']) {
      for (const blockType of BATCH2_BLOCKS) {
        expect(
          registry.canContain(parent, blockType),
          `${parent} can contain ${blockType}`,
        ).toBe(true);
      }
    }
    // not loosened where it should not be (list / form-only containers)
    for (const blockType of BATCH2_BLOCKS) {
      expect(registry.canContain('table', blockType)).toBe(false);
      expect(registry.canContain('form', blockType)).toBe(false);
    }
  });

  it('lets record-inspector nest detail/workbench children (composed inspector layout)', () => {
    const registry = createDefaultBlockRegistryV3();
    expect(registry.canContain('record-inspector', 'field')).toBe(true);
    expect(registry.canContain('record-inspector', 'evidence-panel')).toBe(true);
    expect(registry.canContain('record-inspector', 'metric-strip')).toBe(true);
    expect(registry.canContain('record-inspector', 'candidate-list')).toBe(true);
    // not a generic container — list-only blocks are not allowed
    expect(registry.canContain('record-inspector', 'filter-bar')).toBe(false);
  });

  it('surfaces all batch-2 workbench blocks in the detail and dashboard palette only', () => {
    for (const blockType of BATCH2_BLOCKS) {
      expect(isBlockTypeAllowedForKind('detail', blockType)).toBe(true);
      expect(isBlockTypeAllowedForKind('dashboard', blockType)).toBe(true);
      // composite (escape hatch) allows everything
      expect(isBlockTypeAllowedForKind('composite', blockType)).toBe(true);
      // form / list kinds do NOT offer them
      expect(isBlockTypeAllowedForKind('form', blockType)).toBe(false);
      expect(isBlockTypeAllowedForKind('list', blockType)).toBe(false);
    }
  });
});

describe('workbench blocks batch 2 — inspector schemas (bare top-level keys)', () => {
  // Each entry asserts the EXACT bare top-level keys the platform renderer reads,
  // plus that the keys are NOT namespaced under props.* (the slice's contract).
  const expectedKeys: Record<string, { required: string[]; json: string[] }> = {
    'workbench-action-bar': {
      required: ['title', 'surface', 'density', 'align', 'actions'],
      json: ['actions'],
    },
    'review-drawer': {
      required: [
        'context',
        'contextDataSource',
        'contextKeyField',
        'titleTemplate',
        'summaryBadges',
        'compare',
        'candidates',
        'exportImpact',
        'source',
      ],
      json: ['summaryBadges', 'compare', 'candidates', 'exportImpact', 'source'],
    },
    'evidence-panel': {
      required: ['dataSource', 'context', 'sections'],
      json: ['sections'],
    },
    'record-inspector': {
      required: ['context', 'fields'],
      json: ['fields'],
    },
    'candidate-list': {
      required: ['dataSource', 'item', 'selection', 'actions', 'maxHeight'],
      json: ['item', 'selection', 'actions'],
    },
    'artifact-timeline': {
      required: ['dataSource', 'item'],
      json: ['item'],
    },
  };

  for (const blockType of BATCH2_BLOCKS) {
    it(`${blockType} exposes the renderer's bare top-level props (no props.*)`, () => {
      const fields = defaultInspectorSchemaRegistry.getFields(blockType);
      const keys = fields.map((f) => f.key);
      const spec = expectedKeys[blockType];
      for (const key of spec.required) {
        expect(keys, `${blockType} should expose ${key}`).toContain(key);
        expect(keys, `${blockType} ${key} must be bare (no props.)`).not.toContain(
          `props.${key}`,
        );
      }
      for (const jsonKey of spec.json) {
        expect(
          fields.find((f) => f.key === jsonKey)?.type,
          `${blockType} ${jsonKey} is JSON-authored`,
        ).toBe('json');
      }
    });
  }
});

describe('workbench blocks batch 2 — designer runtime representative preview', () => {
  it('renders workbench-action-bar action labels with empty + configured states', () => {
    renderSingleBlock({
      id: 'wb_actions',
      blockType: 'workbench-action-bar',
      title: 'Reconcile actions',
      align: 'end',
      actions: [
        { code: 'confirm', label: { 'en-US': 'Confirm', 'zh-CN': '确认' }, variant: 'primary' },
        { code: 'reject', label: { 'en-US': 'Reject', 'zh-CN': '驳回' }, variant: 'danger' },
      ],
    } as unknown as DslBlockV3);

    const bar = screen.getByTestId('runtime-workbench-action-bar-wb_actions');
    expect(bar).toHaveTextContent('Reconcile actions');
    // zh-CN locale resolves the localized label to its zh-CN value
    expect(screen.getByTestId('runtime-workbench-action-bar-action-confirm')).toHaveTextContent(
      '确认',
    );
    expect(screen.getByTestId('runtime-workbench-action-bar-action-reject')).toHaveTextContent(
      '驳回',
    );
    expect(screen.getByTestId('runtime-workbench-action-bar-hint-wb_actions')).toBeInTheDocument();
  });

  it('shows the empty state for an action bar with no actions', () => {
    renderSingleBlock({
      id: 'wb_actions_empty',
      blockType: 'workbench-action-bar',
      title: 'No actions',
    } as unknown as DslBlockV3);
    expect(
      screen.getByTestId('runtime-workbench-action-bar-empty-wb_actions_empty'),
    ).toBeInTheDocument();
  });

  it('renders evidence-panel sections representatively and the empty state', () => {
    renderSingleBlock({
      id: 'wb_evidence',
      blockType: 'evidence-panel',
      title: 'Parse evidence',
      dataSource: 'ds_evidence',
      sections: [
        { key: 'raw', field: 'raw_payload', label: { 'en-US': 'Raw', 'zh-CN': '原始' }, format: 'json' },
        { key: 'note', field: 'note', label: { 'en-US': 'Note', 'zh-CN': '备注' } },
      ],
    } as unknown as DslBlockV3);

    expect(screen.getByTestId('runtime-evidence-panel-wb_evidence')).toHaveTextContent(
      'Parse evidence',
    );
    expect(screen.getByTestId('runtime-evidence-panel-section-raw')).toHaveTextContent('原始');
    expect(screen.getByTestId('runtime-evidence-panel-section-note')).toHaveTextContent('备注');
    expect(screen.getByTestId('runtime-evidence-panel-hint-wb_evidence')).toBeInTheDocument();

    renderSingleBlock({
      id: 'wb_evidence_empty',
      blockType: 'evidence-panel',
      title: 'No evidence',
    } as unknown as DslBlockV3);
    expect(
      screen.getByTestId('runtime-evidence-panel-empty-wb_evidence_empty'),
    ).toBeInTheDocument();
  });

  it('renders record-inspector fields + nested children and the empty state', () => {
    renderSingleBlock({
      id: 'wb_inspector',
      blockType: 'record-inspector',
      title: 'Record',
      context: '${state.selectedRow}',
      fields: [
        { field: 'name', label: { 'en-US': 'Name', 'zh-CN': '名称' } },
        { field: 'code', path: 'code', label: { 'en-US': 'Code', 'zh-CN': '编码' }, span: 2 },
      ],
      blocks: [
        {
          id: 'wb_inspector_child_metric',
          blockType: 'metric-strip',
          title: 'Nested KPI',
          metrics: [{ key: 'total', label: { 'en-US': 'Total' }, valueField: 'total' }],
        },
      ],
    } as unknown as DslBlockV3);

    expect(screen.getByTestId('runtime-record-inspector-wb_inspector')).toHaveTextContent('Record');
    expect(screen.getByTestId('runtime-record-inspector-field-name')).toHaveTextContent('名称');
    expect(screen.getByTestId('runtime-record-inspector-field-code')).toHaveTextContent('编码');
    // nested workbench child renders through the same recursive runtime
    expect(screen.getByTestId('runtime-metric-strip-wb_inspector_child_metric')).toBeInTheDocument();
    expect(screen.getByTestId('runtime-record-inspector-hint-wb_inspector')).toBeInTheDocument();

    renderSingleBlock({
      id: 'wb_inspector_empty',
      blockType: 'record-inspector',
      title: 'Nothing',
    } as unknown as DslBlockV3);
    expect(
      screen.getByTestId('runtime-record-inspector-empty-wb_inspector_empty'),
    ).toBeInTheDocument();
  });

  it('renders candidate-list item config + actions and the empty state', () => {
    renderSingleBlock({
      id: 'wb_candidates',
      blockType: 'candidate-list',
      title: 'Candidates',
      dataSource: 'ds_candidates',
      item: {
        titleField: 'material_code',
        detailFields: [
          { key: 'score', field: 'match_score', label: { 'en-US': 'Score', 'zh-CN': '分数' } },
        ],
      },
      actions: [
        { code: 'confirm', label: { 'en-US': 'Confirm', 'zh-CN': '确认' }, variant: 'primary' },
      ],
    } as unknown as DslBlockV3);

    expect(screen.getByTestId('runtime-candidate-list-sample-wb_candidates')).toHaveTextContent(
      'material_code',
    );
    expect(screen.getByTestId('runtime-candidate-list-field-score')).toHaveTextContent('分数');
    expect(screen.getByTestId('runtime-candidate-list-action-confirm')).toHaveTextContent('确认');
    expect(screen.getByTestId('runtime-candidate-list-hint-wb_candidates')).toBeInTheDocument();

    renderSingleBlock({
      id: 'wb_candidates_empty',
      blockType: 'candidate-list',
      title: 'No candidates',
    } as unknown as DslBlockV3);
    expect(
      screen.getByTestId('runtime-candidate-list-empty-wb_candidates_empty'),
    ).toBeInTheDocument();
  });

  it('renders artifact-timeline field bindings and the empty state', () => {
    renderSingleBlock({
      id: 'wb_artifacts',
      blockType: 'artifact-timeline',
      title: 'Artifacts',
      dataSource: 'ds_artifacts',
      item: {
        titleField: 'bom_er_filename',
        revisionField: 'bom_er_revision_no',
        statusField: 'bom_er_status',
        fileIdField: 'bom_er_file_id',
      },
    } as unknown as DslBlockV3);

    const sample = screen.getByTestId('runtime-artifact-timeline-sample-wb_artifacts');
    expect(sample).toBeInTheDocument();
    expect(screen.getByTestId('runtime-artifact-timeline-title-wb_artifacts')).toHaveTextContent(
      'bom_er_filename',
    );
    expect(
      screen.getByTestId('runtime-artifact-timeline-download-wb_artifacts'),
    ).toHaveTextContent('bom_er_file_id');
    expect(screen.getByTestId('runtime-artifact-timeline-hint-wb_artifacts')).toBeInTheDocument();

    renderSingleBlock({
      id: 'wb_artifacts_empty',
      blockType: 'artifact-timeline',
      title: 'No artifacts',
    } as unknown as DslBlockV3);
    expect(
      screen.getByTestId('runtime-artifact-timeline-empty-wb_artifacts_empty'),
    ).toBeInTheDocument();
  });

  it('renders a representative review-drawer from summaryBadges + compare and the empty state', () => {
    renderSingleBlock({
      id: 'wb_review',
      blockType: 'review-drawer',
      title: 'Row review',
      context: '${state.selectedRow}',
      contextDataSource: 'ds_rows',
      summaryBadges: [
        { key: 'status', label: { 'en-US': 'Status' }, valueField: 'status', tone: 'blue' },
      ],
      compare: {
        rawFields: [{ key: 'a', field: 'a' }],
        canonicalFields: [{ key: 'b', field: 'b' }],
      },
      candidates: { dataSource: 'ds_candidates', actions: [{ code: 'confirm' }] },
    } as unknown as DslBlockV3);

    expect(screen.getByTestId('runtime-review-drawer-wb_review')).toHaveTextContent('Row review');
    expect(screen.getByTestId('runtime-review-drawer-sample-wb_review')).toBeInTheDocument();
    expect(screen.getByTestId('runtime-review-drawer-badge-status')).toBeInTheDocument();
    expect(screen.getByTestId('runtime-review-drawer-compare-wb_review')).toHaveTextContent(
      'Raw 1 / Canonical 1',
    );
    expect(screen.getByTestId('runtime-review-drawer-hint-wb_review')).toBeInTheDocument();

    renderSingleBlock({
      id: 'wb_review_empty',
      blockType: 'review-drawer',
      title: 'No context',
    } as unknown as DslBlockV3);
    expect(
      screen.getByTestId('runtime-review-drawer-empty-wb_review_empty'),
    ).toBeInTheDocument();
  });
});
