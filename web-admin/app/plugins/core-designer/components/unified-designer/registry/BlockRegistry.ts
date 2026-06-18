import type { BlockDefinitionV3 } from '../types';
import { defaultInspectorSchemaRegistry } from './InspectorSchemaRegistry';
import {
  getCustomDesignerBlockDefinitions,
  getCustomDesignerBlockEntries,
} from './customBlockRegistry';

export class BlockRegistryV3 {
  private readonly definitions = new Map<string, BlockDefinitionV3>();

  register(definition: BlockDefinitionV3): void {
    this.definitions.set(definition.blockType, definition);
  }

  registerAll(definitions: BlockDefinitionV3[]): void {
    definitions.forEach((definition) => this.register(definition));
  }

  get(blockType: string): BlockDefinitionV3 | undefined {
    return this.definitions.get(blockType);
  }

  getAll(): BlockDefinitionV3[] {
    return Array.from(this.definitions.values());
  }

  canContain(parentBlockType: string, childBlockType: string): boolean {
    const definition = this.get(parentBlockType);
    if (!definition?.allowedChildren?.length) return false;
    return definition.allowedChildren.includes(childBlockType);
  }
}

export function createBlockRegistryV3(definitions: BlockDefinitionV3[] = []): BlockRegistryV3 {
  const registry = new BlockRegistryV3();
  registry.registerAll(definitions);
  return registry;
}

export function createDefaultBlockRegistryV3(): BlockRegistryV3 {
  const registry = createBlockRegistryV3([
    {
      blockType: 'form',
      label: { 'en-US': 'Form', 'zh-CN': '表单' },
      icon: 'square-pen',
      category: 'page',
      allowedChildren: [
        'ai-fill-banner',
        'columns',
        'form-section',
        'tabs',
        'sub-table',
        'repeater',
        'subform',
        'action-bar',
        'form-wizard',
        'form-buttons',
        'divider',
      ],
      inspector: toInspectorSchema('form'),
      layoutCapability: 'span',
    },
    {
      blockType: 'form-section',
      label: { 'en-US': 'Form section', 'zh-CN': '表单分组' },
      icon: 'columns-3',
      category: 'form',
      allowedChildren: ['form-section', 'field', 'sub-table', 'repeater', 'subform'],
      inspector: toInspectorSchema('form-section'),
      layoutCapability: 'span',
    },
    {
      blockType: 'field',
      label: { 'en-US': 'Field', 'zh-CN': '字段' },
      icon: 'text-cursor-input',
      category: 'form',
      inspector: toInspectorSchema('field'),
      layoutCapability: 'span',
    },
    {
      blockType: 'list',
      label: { 'en-US': 'List', 'zh-CN': '列表' },
      icon: 'table',
      category: 'page',
      allowedChildren: [
        'tabs',
        'columns',
        'filter-bar',
        'action-bar',
        'table',
        'widget',
        'filters',
        'toolbar',
        'divider',
      ],
      inspector: toInspectorSchema('list'),
      layoutCapability: 'span',
    },
    {
      blockType: 'filter-bar',
      label: { 'en-US': 'Filter bar', 'zh-CN': '筛选栏' },
      icon: 'list-filter',
      category: 'list',
      allowedChildren: ['filter-field'],
      inspector: toInspectorSchema('filter-bar'),
      layoutCapability: 'span',
    },
    {
      blockType: 'filter-field',
      label: { 'en-US': 'Filter field', 'zh-CN': '筛选字段' },
      icon: 'search',
      category: 'list',
      inspector: toInspectorSchema('filter-field'),
      layoutCapability: 'span',
    },
    {
      blockType: 'table',
      label: { 'en-US': 'Table', 'zh-CN': '表格' },
      icon: 'table-2',
      category: 'list',
      allowedChildren: ['column', 'action'],
      inspector: toInspectorSchema('table'),
      layoutCapability: 'span',
    },
    {
      blockType: 'column',
      label: { 'en-US': 'Column', 'zh-CN': '列' },
      icon: 'panel-left',
      category: 'list',
      inspector: toInspectorSchema('column'),
      layoutCapability: 'none',
    },
    {
      blockType: 'action-bar',
      label: { 'en-US': 'Action bar', 'zh-CN': '操作栏' },
      icon: 'between-horizontal-start',
      category: 'action',
      allowedChildren: ['action'],
      inspector: toInspectorSchema('action-bar'),
      layoutCapability: 'span',
    },
    {
      blockType: 'action',
      label: { 'en-US': 'Action', 'zh-CN': '操作' },
      icon: 'mouse-pointer-click',
      category: 'action',
      inspector: toInspectorSchema('action'),
      layoutCapability: 'span',
    },
    {
      blockType: 'detail',
      label: { 'en-US': 'Detail', 'zh-CN': '详情' },
      icon: 'panel-top',
      category: 'page',
      allowedChildren: [
        'tabs',
        'columns',
        'detail-section',
        'sub-table',
        'repeater',
        'subform',
        'action-bar',
        'widget',
        'metric-strip',
        'status-banner',
        'workbench-action-bar',
        'review-drawer',
        'evidence-panel',
        'record-inspector',
        'candidate-list',
        'artifact-timeline',
        'stat-card',
        'description',
        'record-comments',
        'embedded-list',
        'chart',
        'rich-text',
        'divider',
        'toolbar',
        'trace-graph',
        'selection-info',
        'gerber-viewer',
      ],
      inspector: toInspectorSchema('detail'),
      layoutCapability: 'span',
    },
    {
      blockType: 'detail-section',
      label: { 'en-US': 'Detail section', 'zh-CN': '详情分组' },
      icon: 'rows-3',
      category: 'detail',
      allowedChildren: ['field', 'sub-table', 'repeater', 'subform'],
      inspector: toInspectorSchema('detail-section'),
      layoutCapability: 'span',
    },
    {
      blockType: 'dashboard',
      label: { 'en-US': 'Dashboard', 'zh-CN': '仪表盘' },
      icon: 'layout-dashboard',
      category: 'page',
      allowedChildren: [
        'widget',
        'metric-strip',
        'status-banner',
        'workbench-action-bar',
        'review-drawer',
        'evidence-panel',
        'record-inspector',
        'candidate-list',
        'artifact-timeline',
        'stat-card',
        'description',
        'chart',
        'rich-text',
        'divider',
        'trace-graph',
        'selection-info',
        'gerber-viewer',
      ],
      inspector: toInspectorSchema('dashboard'),
      layoutCapability: 'span',
    },
    {
      blockType: 'widget',
      label: { 'en-US': 'Widget', 'zh-CN': '组件' },
      icon: 'chart-no-axes-combined',
      category: 'dashboard',
      inspector: toInspectorSchema('widget'),
      layoutCapability: 'dashboard-widget',
    },
    {
      blockType: 'tabs',
      label: { 'en-US': 'Tabs', 'zh-CN': '标签页' },
      icon: 'panel-top-open',
      category: 'layout',
      allowedChildren: ['tab'],
      inspector: toInspectorSchema('tabs'),
      layoutCapability: 'span',
    },
    {
      blockType: 'columns',
      label: { 'en-US': 'Columns', 'zh-CN': '分栏' },
      icon: 'columns-3',
      category: 'layout',
      allowedChildren: [
        'ai-fill-banner',
        'form-section',
        'detail-section',
        'field',
        'filter-bar',
        'table',
        'sub-table',
        'repeater',
        'subform',
        'action-bar',
        'widget',
        'bpm-panel',
        'activity-timeline',
        'field-history',
        'metric-strip',
        'status-banner',
        'workbench-action-bar',
        'review-drawer',
        'evidence-panel',
        'record-inspector',
        'candidate-list',
        'artifact-timeline',
        'stat-card',
        'description',
        'chart',
        'rich-text',
        'divider',
        'toolbar',
        'trace-graph',
        'selection-info',
        'gerber-viewer',
      ],
      inspector: toInspectorSchema('columns'),
      layoutCapability: 'span',
    },
    {
      blockType: 'tab',
      label: { 'en-US': 'Tab', 'zh-CN': '标签' },
      icon: 'panel-top',
      category: 'layout',
      allowedChildren: [
        'ai-fill-banner',
        'columns',
        'form-section',
        'detail-section',
        'filter-bar',
        'action-bar',
        'table',
        'sub-table',
        'repeater',
        'subform',
        'widget',
        'bpm-panel',
        'activity-timeline',
        'field-history',
        'metric-strip',
        'status-banner',
        'workbench-action-bar',
        'review-drawer',
        'evidence-panel',
        'record-inspector',
        'candidate-list',
        'artifact-timeline',
        'stat-card',
        'description',
        'chart',
        'rich-text',
        'divider',
        'toolbar',
        'trace-graph',
        'selection-info',
        'gerber-viewer',
      ],
      inspector: toInspectorSchema('tab'),
      layoutCapability: 'span',
    },
    {
      blockType: 'sub-table',
      label: { 'en-US': 'Sub table', 'zh-CN': '子表' },
      icon: 'table-properties',
      category: 'detail',
      allowedChildren: ['column', 'action'],
      inspector: toInspectorSchema('sub-table'),
      layoutCapability: 'span',
    },
    {
      blockType: 'repeater',
      label: { 'en-US': 'Repeater', 'zh-CN': '重复项' },
      icon: 'list-plus',
      category: 'form',
      allowedChildren: ['field'],
      inspector: toInspectorSchema('repeater'),
      layoutCapability: 'span',
    },
    {
      blockType: 'subform',
      label: { 'en-US': 'Subform', 'zh-CN': '子表单' },
      icon: 'clipboard-list',
      category: 'form',
      allowedChildren: ['form-section', 'field'],
      inspector: toInspectorSchema('subform'),
      layoutCapability: 'span',
    },
    {
      blockType: 'ai-fill-banner',
      label: { 'en-US': 'AI fill banner', 'zh-CN': 'AI 填充入口' },
      icon: 'sparkles',
      category: 'form',
      inspector: toInspectorSchema('ai-fill-banner'),
      layoutCapability: 'span',
    },
    {
      blockType: 'bpm-panel',
      label: { 'en-US': 'BPM panel', 'zh-CN': '流程面板' },
      icon: 'workflow',
      category: 'workflow',
      inspector: toInspectorSchema('bpm-panel'),
      layoutCapability: 'span',
    },
    {
      blockType: 'activity-timeline',
      label: { 'en-US': 'Activity timeline', 'zh-CN': '活动时间线' },
      icon: 'history',
      category: 'workflow',
      inspector: toInspectorSchema('activity-timeline'),
      layoutCapability: 'span',
    },
    {
      blockType: 'field-history',
      label: { 'en-US': 'Field history', 'zh-CN': '字段历史' },
      icon: 'list-tree',
      category: 'workflow',
      inspector: toInspectorSchema('field-history'),
      layoutCapability: 'span',
    },
    // Workbench blocks (metric-strip / status-banner). These are backed by the
    // platform meta-rendering renderers (framework/meta/rendering/blocks/
    // MetricStripBlockRenderer + StatusBannerBlockRenderer) on the live /p/ page;
    // the designer adds palette + inspector + a config-driven representative
    // preview here (RecursiveBlockRenderer.RuntimeMetricStripPreview /
    // RuntimeStatusBannerPreview). The full data-bound rendering happens on the
    // live page, not inside the designer canvas. The authored props live at the
    // block top level (dataSource / metrics / variant / statusField / toneMap …)
    // exactly where the platform renderers read them — see InspectorSchemaRegistry
    // (bare-key inspector fields) and the real authored pages (mfg_andon_workbench
    // metric-strip, bom-standardization status-banner).
    {
      blockType: 'metric-strip',
      label: { 'en-US': 'Metric strip', 'zh-CN': '指标条' },
      icon: 'layout-grid',
      category: 'workbench',
      inspector: toInspectorSchema('metric-strip'),
      layoutCapability: 'span',
    },
    {
      blockType: 'status-banner',
      label: { 'en-US': 'Status banner', 'zh-CN': '状态横幅' },
      icon: 'badge-info',
      category: 'workbench',
      inspector: toInspectorSchema('status-banner'),
      layoutCapability: 'span',
    },
    // Workbench-family batch 2 — the remaining six platform workbench blocks
    // (framework/meta/rendering/blocks/WorkbenchActionBarBlockRenderer,
    // ReviewDrawerBlockRenderer, EvidencePanelBlockRenderer,
    // RecordInspectorBlockRenderer, CandidateListBlockRenderer,
    // ArtifactTimelineBlockRenderer). Same architecture as metric-strip /
    // status-banner: palette + inspector + a config-driven representative preview
    // here; the live /p/ page renders the fully data-bound platform renderer. The
    // authored props live at the BLOCK TOP LEVEL (actions / dataSource / sections /
    // fields / item / context / …) exactly where the platform renderers read them
    // (see InspectorSchemaRegistry — bare-key fields). record-inspector also nests
    // arbitrary child blocks, so it declares allowedChildren mirroring detail.
    {
      blockType: 'workbench-action-bar',
      label: { 'en-US': 'Workbench action bar', 'zh-CN': '工作台操作栏' },
      icon: 'square-mouse-pointer',
      category: 'workbench',
      inspector: toInspectorSchema('workbench-action-bar'),
      layoutCapability: 'span',
    },
    {
      blockType: 'review-drawer',
      label: { 'en-US': 'Review drawer', 'zh-CN': '复核浮层' },
      icon: 'panel-right',
      category: 'workbench',
      inspector: toInspectorSchema('review-drawer'),
      layoutCapability: 'span',
    },
    {
      blockType: 'evidence-panel',
      label: { 'en-US': 'Evidence panel', 'zh-CN': '证据面板' },
      icon: 'file-search',
      category: 'workbench',
      inspector: toInspectorSchema('evidence-panel'),
      layoutCapability: 'span',
    },
    {
      blockType: 'record-inspector',
      label: { 'en-US': 'Record inspector', 'zh-CN': '记录检视器' },
      icon: 'scan-search',
      category: 'workbench',
      allowedChildren: [
        'detail-section',
        'field',
        'sub-table',
        'columns',
        'metric-strip',
        'status-banner',
        'evidence-panel',
        'artifact-timeline',
        'candidate-list',
      ],
      inspector: toInspectorSchema('record-inspector'),
      layoutCapability: 'span',
    },
    {
      blockType: 'candidate-list',
      label: { 'en-US': 'Candidate list', 'zh-CN': '候选列表' },
      icon: 'list-checks',
      category: 'workbench',
      inspector: toInspectorSchema('candidate-list'),
      layoutCapability: 'span',
    },
    {
      blockType: 'artifact-timeline',
      label: { 'en-US': 'Artifact timeline', 'zh-CN': '产物时间线' },
      icon: 'git-commit-horizontal',
      category: 'workbench',
      inspector: toInspectorSchema('artifact-timeline'),
      layoutCapability: 'span',
    },
    // Display / data blocks (non workbench-family). These are backed by the
    // platform meta-rendering renderers on the live /p/ page:
    //   stat-card      ← StatCardBlockRenderer       (single-metric KPI card)
    //   description    ← DescriptionBlockRenderer     (static rich-text panel)
    //   record-comments← RecordComments (via DetailPageContent) (comment thread)
    //   embedded-list  ← EmbeddedListBlockRenderer    (in-page filterable list)
    // Same architecture as the workbench family: the designer adds palette +
    // inspector + a config-driven representative preview here; full data binding
    // renders on the live page. Inspector keys mirror the EXACT paths the platform
    // renderers read — verified against each renderer source:
    //   - stat-card: cfg = { ...block.props, ...block.statCard }; so the metric
    //     object lives at block.statCard (bare), value/unit/trend/trendDirection/
    //     valueField inside it. dataSource is a bare string id (named data source).
    //   - description: reads block.content ?? props.content ?? props.text — a
    //     BARE+props mixed path; the inspector exposes the bare `content`.
    //   - record-comments: reads NO block-level data config — modelCode/recordPid
    //     are derived from the surrounding detail page + current record. So its
    //     only authorable surface is the designer title (+ the universal AI lock).
    //   - embedded-list: bare top-level modelCode / parentField / columns / title /
    //     pageSize / searchable / filterable; resolves the parent record id from the
    //     detail route, so it is a DETAIL-only block.
    {
      blockType: 'stat-card',
      label: { 'en-US': 'Stat card', 'zh-CN': '指标卡' },
      icon: 'gauge',
      category: 'dashboard',
      inspector: toInspectorSchema('stat-card'),
      layoutCapability: 'span',
    },
    {
      blockType: 'description',
      label: { 'en-US': 'Description', 'zh-CN': '描述文本' },
      icon: 'text',
      category: 'detail',
      inspector: toInspectorSchema('description'),
      layoutCapability: 'span',
    },
    {
      blockType: 'record-comments',
      label: { 'en-US': 'Record comments', 'zh-CN': '记录评论' },
      icon: 'message-square',
      category: 'detail',
      inspector: toInspectorSchema('record-comments'),
      layoutCapability: 'span',
    },
    {
      blockType: 'embedded-list',
      label: { 'en-US': 'Embedded list', 'zh-CN': '内嵌列表' },
      icon: 'list',
      category: 'list',
      inspector: toInspectorSchema('embedded-list'),
      layoutCapability: 'span',
    },
    // E2 batch — non-family display / chart / graph / layout / form / list blocks.
    // Each is backed by a platform meta-rendering renderer wired into the runtime
    // ui/schema-renderer/BlockRegistry (so the live /p/ page renders the real,
    // fully data-bound component). The designer adds palette + inspector
    // (bare-path keys mirroring each renderer source) + a config-driven
    // representative preview in RecursiveBlockRenderer. Verified prop paths per
    // renderer — no invented fields. icon is cosmetic (the palette renders
    // label + blockType, not the icon).
    {
      blockType: 'chart',
      label: { 'en-US': 'Chart', 'zh-CN': '图表' },
      icon: 'chart-column',
      category: 'dashboard',
      inspector: toInspectorSchema('chart'),
      layoutCapability: 'span',
    },
    {
      blockType: 'rich-text',
      label: { 'en-US': 'Rich text', 'zh-CN': '富文本' },
      icon: 'file-text',
      category: 'detail',
      inspector: toInspectorSchema('rich-text'),
      layoutCapability: 'span',
    },
    {
      blockType: 'divider',
      label: { 'en-US': 'Divider', 'zh-CN': '分隔线' },
      icon: 'minus',
      category: 'layout',
      inspector: toInspectorSchema('divider'),
      layoutCapability: 'span',
    },
    {
      blockType: 'toolbar',
      label: { 'en-US': 'Toolbar', 'zh-CN': '工具栏' },
      icon: 'wrench',
      category: 'list',
      inspector: toInspectorSchema('toolbar'),
      layoutCapability: 'span',
    },
    {
      blockType: 'form-buttons',
      label: { 'en-US': 'Form buttons', 'zh-CN': '表单按钮' },
      icon: 'rectangle-horizontal',
      category: 'form',
      inspector: toInspectorSchema('form-buttons'),
      layoutCapability: 'span',
    },
    {
      blockType: 'filters',
      label: { 'en-US': 'Filters', 'zh-CN': '筛选器' },
      icon: 'filter',
      category: 'list',
      inspector: toInspectorSchema('filters'),
      layoutCapability: 'span',
    },
    {
      blockType: 'form-wizard',
      label: { 'en-US': 'Form wizard', 'zh-CN': '分步表单' },
      icon: 'list-ordered',
      category: 'form',
      inspector: toInspectorSchema('form-wizard'),
      layoutCapability: 'span',
    },
    {
      blockType: 'trace-graph',
      label: { 'en-US': 'Trace graph', 'zh-CN': '追溯图' },
      icon: 'git-fork',
      category: 'dashboard',
      inspector: toInspectorSchema('trace-graph'),
      layoutCapability: 'span',
    },
    {
      blockType: 'selection-info',
      label: { 'en-US': 'Selection info', 'zh-CN': '选择信息' },
      icon: 'square-check-big',
      category: 'workbench',
      inspector: toInspectorSchema('selection-info'),
      layoutCapability: 'span',
    },
    {
      blockType: 'gerber-viewer',
      label: { 'en-US': 'Gerber viewer', 'zh-CN': 'Gerber 视图' },
      icon: 'circuit-board',
      category: 'dashboard',
      inspector: toInspectorSchema('gerber-viewer'),
      layoutCapability: 'span',
    },
  ]);

  // Merge plugin-contributed custom blocks (additive; see customBlockRegistry).
  // Built-in blocks are registered first so a plugin can intentionally override
  // one by re-using its blockType, but the common case is net-new block types.
  registry.registerAll(getCustomDesignerBlockDefinitions());

  // Wire each custom block into its declared parents' allowedChildren so the
  // canvas drop logic (canContain) lets it nest there.
  for (const { definition, options } of getCustomDesignerBlockEntries()) {
    for (const parentType of options.allowedParents ?? []) {
      const parent = registry.get(parentType);
      if (!parent) continue;
      const children = new Set(parent.allowedChildren ?? []);
      children.add(definition.blockType);
      parent.allowedChildren = Array.from(children);
    }
  }

  return registry;
}

function toInspectorSchema(blockType: string): BlockDefinitionV3['inspector'] {
  return {
    tabs: [
      {
        key: 'basic',
        label: 'Basic',
        groups: [
          {
            key: 'main',
            label: 'Main',
            fields: defaultInspectorSchemaRegistry.getFields(blockType),
          },
        ],
      },
    ],
  };
}
