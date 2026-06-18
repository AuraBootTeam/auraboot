import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { createDefaultBlockRegistryV3 } from '../registry/BlockRegistry';
import { defaultInspectorSchemaRegistry } from '../registry/InspectorSchemaRegistry';
import { isBlockTypeAllowedForKind } from '../registry/kindPolicy';
import { RecursiveBlockRenderer } from '../runtime/RecursiveBlockRenderer';
import type { DslBlockV3, PageSchemaV3 } from '../types';

/**
 * E2 batch — the remaining non-family display / chart / graph / layout / form /
 * list blocks added to the unified designer. Each is backed by a platform
 * meta-rendering renderer wired into the runtime ui/schema-renderer/BlockRegistry
 * (so the live /p/ page renders the real, fully data-bound component):
 *   chart          ← ChartBlockRenderer        (SharedChartFactory, 28 types)
 *   rich-text      ← RichTextBlockRenderer      (sanitized HTML)
 *   divider        ← DividerBlockRenderer       (rule / labeled separator)
 *   toolbar        ← ToolbarBlockRenderer       (button group)
 *   form-buttons   ← FormButtonsBlockRenderer   (form footer buttons)
 *   filters        ← FiltersBlockRenderer       (filter field panel)
 *   form-wizard    ← FormWizardBlockRenderer     (multi-step form)
 *   trace-graph    ← TraceGraphBlockRenderer     (@xyflow lineage canvas)
 *   selection-info ← SelectionInfoBlockRenderer  (bound multi-select summary)
 *   gerber-viewer  ← GerberViewerBlockRenderer   (PCB board + CPL inspection)
 *
 * These unit tests cover the DESIGNER-side surface this batch adds:
 *   - block registry definitions (category + span layout) + nesting (canContain)
 *   - per-kind palette policy (isBlockTypeAllowedForKind)
 *   - inspector schemas written to the EXACT bare-path each live renderer reads
 *     (verified against the renderer source — no invented fields)
 *   - the config-driven representative preview rendered inside the designer runtime
 * The full data-bound rendering is covered by the browser golden on the live /p/.
 */

function renderSingleBlock(block: DslBlockV3, kind: PageSchemaV3['kind'] = 'detail') {
  const schema: PageSchemaV3 = {
    schemaVersion: 3,
    kind,
    id: 'e2_preview_page',
    blocks: [block],
  };
  return render(<RecursiveBlockRenderer schema={schema} />);
}

const E2_BLOCKS = [
  'chart',
  'rich-text',
  'divider',
  'toolbar',
  'form-buttons',
  'filters',
  'form-wizard',
  'trace-graph',
  'selection-info',
  'gerber-viewer',
] as const;

describe('E2 batch — designer registry definitions', () => {
  it('registers all ten blocks with span layout (+ no regression to existing blocks)', () => {
    const registry = createDefaultBlockRegistryV3();
    for (const blockType of E2_BLOCKS) {
      expect(registry.get(blockType), `${blockType} registered`).toMatchObject({
        blockType,
        layoutCapability: 'span',
      });
    }
    // categories surfaced in the palette grouping
    expect(registry.get('chart')?.category).toBe('dashboard');
    expect(registry.get('rich-text')?.category).toBe('detail');
    expect(registry.get('divider')?.category).toBe('layout');
    expect(registry.get('toolbar')?.category).toBe('list');
    expect(registry.get('form-buttons')?.category).toBe('form');
    expect(registry.get('filters')?.category).toBe('list');
    expect(registry.get('form-wizard')?.category).toBe('form');
    // prior families intact
    expect(registry.get('metric-strip')).toBeDefined();
    expect(registry.get('stat-card')).toBeDefined();
    expect(registry.get('widget')).toBeDefined();
    expect(registry.get('form')).toBeDefined();
  });

  it('wires nesting into the right containers (canContain)', () => {
    const registry = createDefaultBlockRegistryV3();
    // form composition + generic separator under form
    expect(registry.canContain('form', 'form-buttons')).toBe(true);
    expect(registry.canContain('form', 'form-wizard')).toBe(true);
    expect(registry.canContain('form', 'divider')).toBe(true);
    // list tooling + separator under list
    expect(registry.canContain('list', 'filters')).toBe(true);
    expect(registry.canContain('list', 'toolbar')).toBe(true);
    expect(registry.canContain('list', 'divider')).toBe(true);
    // viz/display + separator under detail
    for (const blockType of ['chart', 'rich-text', 'divider', 'toolbar', 'trace-graph', 'selection-info', 'gerber-viewer'] as const) {
      expect(registry.canContain('detail', blockType), `detail ⊃ ${blockType}`).toBe(true);
    }
    // viz/display under dashboard (NOT toolbar — toolbar is list/detail tooling)
    for (const blockType of ['chart', 'rich-text', 'divider', 'trace-graph', 'selection-info', 'gerber-viewer'] as const) {
      expect(registry.canContain('dashboard', blockType), `dashboard ⊃ ${blockType}`).toBe(true);
    }
    // layout containers (columns / tab) accept the viz/display set
    for (const parent of ['columns', 'tab'] as const) {
      expect(registry.canContain(parent, 'chart')).toBe(true);
      expect(registry.canContain(parent, 'divider')).toBe(true);
      expect(registry.canContain(parent, 'trace-graph')).toBe(true);
    }
    // negative: form-composition blocks are NOT offered under list/dashboard
    expect(registry.canContain('list', 'form-buttons')).toBe(false);
    expect(registry.canContain('dashboard', 'filters')).toBe(false);
  });
});

describe('E2 batch — per-kind palette policy', () => {
  it('chart / rich-text / trace-graph / selection-info / gerber-viewer = cockpit kinds (detail + dashboard)', () => {
    for (const blockType of ['chart', 'rich-text', 'trace-graph', 'selection-info', 'gerber-viewer'] as const) {
      expect(isBlockTypeAllowedForKind('detail', blockType), `detail ${blockType}`).toBe(true);
      expect(isBlockTypeAllowedForKind('dashboard', blockType), `dashboard ${blockType}`).toBe(true);
      expect(isBlockTypeAllowedForKind('composite', blockType)).toBe(true);
      expect(isBlockTypeAllowedForKind('form', blockType), `form !${blockType}`).toBe(false);
      expect(isBlockTypeAllowedForKind('list', blockType), `list !${blockType}`).toBe(false);
    }
  });

  it('form-buttons / form-wizard = form kind only', () => {
    for (const blockType of ['form-buttons', 'form-wizard'] as const) {
      expect(isBlockTypeAllowedForKind('form', blockType)).toBe(true);
      expect(isBlockTypeAllowedForKind('list', blockType)).toBe(false);
      expect(isBlockTypeAllowedForKind('detail', blockType)).toBe(false);
      expect(isBlockTypeAllowedForKind('dashboard', blockType)).toBe(false);
    }
  });

  it('filters = list kind; toolbar = list + detail', () => {
    expect(isBlockTypeAllowedForKind('list', 'filters')).toBe(true);
    expect(isBlockTypeAllowedForKind('form', 'filters')).toBe(false);
    expect(isBlockTypeAllowedForKind('dashboard', 'filters')).toBe(false);

    expect(isBlockTypeAllowedForKind('list', 'toolbar')).toBe(true);
    expect(isBlockTypeAllowedForKind('detail', 'toolbar')).toBe(true);
    expect(isBlockTypeAllowedForKind('form', 'toolbar')).toBe(false);
    expect(isBlockTypeAllowedForKind('dashboard', 'toolbar')).toBe(false);
  });

  it('divider = generic separator on every concrete kind', () => {
    for (const kind of ['form', 'list', 'detail', 'dashboard'] as const) {
      expect(isBlockTypeAllowedForKind(kind, 'divider'), `divider on ${kind}`).toBe(true);
    }
  });
});

describe('E2 batch — inspector schemas (exact renderer paths, no invented fields)', () => {
  const keysFor = (blockType: string) =>
    defaultInspectorSchemaRegistry.getFields(blockType).map((f) => f.key);

  it('chart exposes bare chartType / dataSource / chartConfig / visualization (no props.*)', () => {
    const keys = keysFor('chart');
    for (const key of ['chartType', 'dataSource', 'chartConfig', 'visualization', 'linkage', 'drillDown', 'refreshInterval']) {
      expect(keys, `chart ${key}`).toContain(key);
      expect(keys).not.toContain(`props.${key}`);
    }
    const chartTypeField = defaultInspectorSchemaRegistry.getFields('chart').find((f) => f.key === 'chartType');
    expect(chartTypeField?.type).toBe('select');
    // options are real SharedChartFactory types (no invented values)
    const values = (chartTypeField?.options ?? []).map((o) => o.value);
    expect(values).toContain('bar');
    expect(values).toContain('pie');
    expect(values).toContain('gantt');
  });

  it('rich-text exposes the bare content path (no props.content/text)', () => {
    const keys = keysFor('rich-text');
    expect(keys).toContain('content');
    expect(keys).not.toContain('props.content');
    expect(keys).not.toContain('props.text');
  });

  it('divider exposes only the optional title label', () => {
    const keys = keysFor('divider');
    expect(keys).toContain('title');
    expect(keys).not.toContain('props.title');
  });

  it('toolbar / form-buttons expose bare buttons JSON', () => {
    for (const blockType of ['toolbar', 'form-buttons']) {
      const fields = defaultInspectorSchemaRegistry.getFields(blockType);
      expect(fields.map((f) => f.key)).toContain('buttons');
      expect(fields.find((f) => f.key === 'buttons')?.type).toBe('json');
      expect(fields.map((f) => f.key)).not.toContain('props.buttons');
    }
  });

  it('filters exposes bare fields JSON + onSearch / onReset handler refs', () => {
    const fields = defaultInspectorSchemaRegistry.getFields('filters');
    const keys = fields.map((f) => f.key);
    expect(keys).toContain('fields');
    expect(keys).toContain('onSearch');
    expect(keys).toContain('onReset');
    expect(fields.find((f) => f.key === 'fields')?.type).toBe('json');
  });

  it('form-wizard exposes bare steps JSON', () => {
    const fields = defaultInspectorSchemaRegistry.getFields('form-wizard');
    expect(fields.map((f) => f.key)).toContain('steps');
    expect(fields.find((f) => f.key === 'steps')?.type).toBe('json');
  });

  it('trace-graph exposes bare dataSource + mode (consumption/genealogy)', () => {
    const fields = defaultInspectorSchemaRegistry.getFields('trace-graph');
    const keys = fields.map((f) => f.key);
    expect(keys).toContain('dataSource');
    expect(keys).toContain('mode');
    const modeValues = (fields.find((f) => f.key === 'mode')?.options ?? []).map((o) => o.value);
    expect(modeValues).toEqual(expect.arrayContaining(['consumption', 'genealogy']));
  });

  it('selection-info exposes bare title + bind (state key)', () => {
    const keys = keysFor('selection-info');
    expect(keys).toContain('title');
    expect(keys).toContain('bind');
  });

  it('gerber-viewer exposes the bare keys the live renderer reads', () => {
    const keys = keysFor('gerber-viewer');
    for (const key of ['title', 'dataSource', 'inspection', 'inspectionUrl', 'lineContext', 'lineInspectionField', 'empty']) {
      expect(keys, `gerber-viewer ${key}`).toContain(key);
      expect(keys).not.toContain(`props.${key}`);
    }
  });
});

describe('E2 batch — designer runtime representative previews', () => {
  it('chart: renders chart type + data-source binding (and the empty state)', () => {
    renderSingleBlock({
      id: 'e2_chart',
      blockType: 'chart',
      title: 'Revenue',
      chartType: 'line',
      dataSource: 'ds_revenue',
      chartConfig: { xField: 'month', yField: 'amount' },
    } as unknown as DslBlockV3);
    expect(screen.getByTestId('runtime-chart-e2_chart')).toHaveTextContent('Revenue');
    expect(screen.getByTestId('runtime-chart-type-e2_chart')).toHaveTextContent('line');
    expect(screen.getByTestId('runtime-chart-binding-e2_chart')).toHaveTextContent('ds_revenue');

    renderSingleBlock({ id: 'e2_chart_empty', blockType: 'chart' } as unknown as DslBlockV3);
    expect(screen.getByTestId('runtime-chart-empty-e2_chart_empty')).toBeInTheDocument();
  });

  it('rich-text: renders resolved content + empty state', () => {
    renderSingleBlock({
      id: 'e2_rt',
      blockType: 'rich-text',
      content: { 'en-US': 'Read me', 'zh-CN': '请阅读' },
    } as unknown as DslBlockV3);
    expect(screen.getByTestId('runtime-rich-text-content-e2_rt')).toHaveTextContent('请阅读');

    renderSingleBlock({ id: 'e2_rt_empty', blockType: 'rich-text' } as unknown as DslBlockV3);
    expect(screen.getByTestId('runtime-rich-text-empty-e2_rt_empty')).toBeInTheDocument();
  });

  it('divider: labeled separator when title is set, plain rule otherwise', () => {
    renderSingleBlock({ id: 'e2_div', blockType: 'divider', title: 'Section B' } as unknown as DslBlockV3);
    expect(screen.getByTestId('runtime-divider-label-e2_div')).toHaveTextContent('Section B');

    renderSingleBlock({ id: 'e2_div_plain', blockType: 'divider' } as unknown as DslBlockV3);
    expect(screen.getByTestId('runtime-divider-e2_div_plain')).toBeInTheDocument();
    expect(screen.queryByTestId('runtime-divider-label-e2_div_plain')).toBeNull();
  });

  it('toolbar: renders button chips keyed by code + empty state', () => {
    renderSingleBlock(
      {
        id: 'e2_tb',
        blockType: 'toolbar',
        buttons: [
          { code: 'export', label: 'Export', variant: 'primary' },
          { code: 'archive', label: 'Archive' },
        ],
      } as unknown as DslBlockV3,
      'list',
    );
    expect(screen.getByTestId('runtime-toolbar-button-export')).toHaveTextContent('Export');
    expect(screen.getByTestId('runtime-toolbar-button-archive')).toHaveTextContent('Archive');

    renderSingleBlock({ id: 'e2_tb_empty', blockType: 'toolbar' } as unknown as DslBlockV3, 'list');
    expect(screen.getByTestId('runtime-toolbar-empty-e2_tb_empty')).toBeInTheDocument();
  });

  it('form-buttons: renders button chips keyed by code', () => {
    renderSingleBlock(
      {
        id: 'e2_fb',
        blockType: 'form-buttons',
        buttons: [
          { code: 'submit', content: 'Submit', primary: true },
          { code: 'cancel', content: 'Cancel' },
        ],
      } as unknown as DslBlockV3,
      'form',
    );
    expect(screen.getByTestId('runtime-form-buttons-button-submit')).toHaveTextContent('Submit');
    expect(screen.getByTestId('runtime-form-buttons-button-cancel')).toHaveTextContent('Cancel');
  });

  it('filters: renders filter-field chips + empty state', () => {
    renderSingleBlock(
      {
        id: 'e2_flt',
        blockType: 'filters',
        fields: [
          { field: 'status', label: 'Status' },
          { field: 'owner', label: 'Owner' },
        ],
      } as unknown as DslBlockV3,
      'list',
    );
    expect(screen.getByTestId('runtime-filters-field-status')).toHaveTextContent('Status');
    expect(screen.getByTestId('runtime-filters-field-owner')).toHaveTextContent('Owner');

    renderSingleBlock({ id: 'e2_flt_empty', blockType: 'filters' } as unknown as DslBlockV3, 'list');
    expect(screen.getByTestId('runtime-filters-empty-e2_flt_empty')).toBeInTheDocument();
  });

  it('form-wizard: renders step rail keyed by step key + empty state', () => {
    renderSingleBlock(
      {
        id: 'e2_fw',
        blockType: 'form-wizard',
        steps: [
          { key: 'base', label: 'Basics', blocks: [] },
          { key: 'review', label: 'Review', blocks: [] },
        ],
      } as unknown as DslBlockV3,
      'form',
    );
    expect(screen.getByTestId('runtime-form-wizard-step-base')).toHaveTextContent('Basics');
    expect(screen.getByTestId('runtime-form-wizard-step-review')).toHaveTextContent('Review');

    renderSingleBlock({ id: 'e2_fw_empty', blockType: 'form-wizard' } as unknown as DslBlockV3, 'form');
    expect(screen.getByTestId('runtime-form-wizard-empty-e2_fw_empty')).toBeInTheDocument();
  });

  it('trace-graph: renders mode + data-source binding (no @xyflow canvas in preview)', () => {
    renderSingleBlock({
      id: 'e2_tg',
      blockType: 'trace-graph',
      dataSource: 'pe_consumption_trace_by_lot',
      mode: 'consumption',
    } as unknown as DslBlockV3);
    expect(screen.getByTestId('runtime-trace-graph-mode-e2_tg')).toHaveTextContent('consumption');
    expect(screen.getByTestId('runtime-trace-graph-binding-e2_tg')).toHaveTextContent(
      'pe_consumption_trace_by_lot',
    );

    renderSingleBlock({ id: 'e2_tg_empty', blockType: 'trace-graph' } as unknown as DslBlockV3);
    expect(screen.getByTestId('runtime-trace-graph-empty-e2_tg_empty')).toBeInTheDocument();
  });

  it('selection-info: surfaces the bound state key', () => {
    renderSingleBlock({
      id: 'e2_si',
      blockType: 'selection-info',
      title: 'Picked rows',
      bind: 'selectedOrders',
    } as unknown as DslBlockV3);
    expect(screen.getByTestId('runtime-selection-info-e2_si')).toHaveTextContent('Picked rows');
    expect(screen.getByTestId('runtime-selection-info-bind-e2_si')).toHaveTextContent('selectedOrders');
  });

  it('selection-info: falls back to selection.bind then default selectedRows', () => {
    renderSingleBlock({
      id: 'e2_si_nested',
      blockType: 'selection-info',
      selection: { bind: 'chosenLots' },
    } as unknown as DslBlockV3);
    expect(screen.getByTestId('runtime-selection-info-bind-e2_si_nested')).toHaveTextContent('chosenLots');

    renderSingleBlock({ id: 'e2_si_default', blockType: 'selection-info' } as unknown as DslBlockV3);
    expect(screen.getByTestId('runtime-selection-info-bind-e2_si_default')).toHaveTextContent('selectedRows');
  });

  it('gerber-viewer: renders board placeholder + binding (and empty state)', () => {
    renderSingleBlock({
      id: 'e2_gv',
      blockType: 'gerber-viewer',
      title: 'Top side',
      dataSource: 'ds_pcb_inspection',
      lineInspectionField: 'line_result',
    } as unknown as DslBlockV3);
    expect(screen.getByTestId('runtime-gerber-viewer-e2_gv')).toHaveTextContent('Top side');
    expect(screen.getByTestId('runtime-gerber-viewer-binding-e2_gv')).toHaveTextContent('ds_pcb_inspection');

    renderSingleBlock({ id: 'e2_gv_empty', blockType: 'gerber-viewer' } as unknown as DslBlockV3);
    expect(screen.getByTestId('runtime-gerber-viewer-empty-e2_gv_empty')).toBeInTheDocument();
  });
});
