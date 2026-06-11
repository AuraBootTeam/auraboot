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
        'form-section',
        'tabs',
        'sub-table',
        'repeater',
        'subform',
        'action-bar',
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
      allowedChildren: ['tabs', 'filter-bar', 'action-bar', 'table', 'widget'],
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
        'detail-section',
        'sub-table',
        'repeater',
        'subform',
        'action-bar',
        'widget',
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
      allowedChildren: ['widget'],
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
      blockType: 'tab',
      label: { 'en-US': 'Tab', 'zh-CN': '标签' },
      icon: 'panel-top',
      category: 'layout',
      allowedChildren: [
        'ai-fill-banner',
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
