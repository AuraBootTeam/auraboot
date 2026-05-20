import type { BlockDefinitionV3 } from '../types';
import { defaultInspectorSchemaRegistry } from './InspectorSchemaRegistry';

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
  return createBlockRegistryV3([
    {
      blockType: 'form',
      label: 'Form',
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
      label: 'Form section',
      icon: 'columns-3',
      category: 'form',
      allowedChildren: ['form-section', 'field', 'sub-table', 'repeater', 'subform'],
      inspector: toInspectorSchema('form-section'),
      layoutCapability: 'span',
    },
    {
      blockType: 'field',
      label: 'Field',
      icon: 'text-cursor-input',
      category: 'form',
      inspector: toInspectorSchema('field'),
      layoutCapability: 'span',
    },
    {
      blockType: 'list',
      label: 'List',
      icon: 'table',
      category: 'page',
      allowedChildren: ['tabs', 'filter-bar', 'action-bar', 'table', 'widget'],
      inspector: toInspectorSchema('list'),
      layoutCapability: 'span',
    },
    {
      blockType: 'filter-bar',
      label: 'Filter bar',
      icon: 'list-filter',
      category: 'list',
      allowedChildren: ['filter-field'],
      inspector: toInspectorSchema('filter-bar'),
      layoutCapability: 'span',
    },
    {
      blockType: 'filter-field',
      label: 'Filter field',
      icon: 'search',
      category: 'list',
      inspector: toInspectorSchema('filter-field'),
      layoutCapability: 'span',
    },
    {
      blockType: 'table',
      label: 'Table',
      icon: 'table-2',
      category: 'list',
      allowedChildren: ['column', 'action'],
      inspector: toInspectorSchema('table'),
      layoutCapability: 'span',
    },
    {
      blockType: 'column',
      label: 'Column',
      icon: 'panel-left',
      category: 'list',
      inspector: toInspectorSchema('column'),
      layoutCapability: 'none',
    },
    {
      blockType: 'action-bar',
      label: 'Action bar',
      icon: 'between-horizontal-start',
      category: 'action',
      allowedChildren: ['action'],
      inspector: toInspectorSchema('action-bar'),
      layoutCapability: 'span',
    },
    {
      blockType: 'action',
      label: 'Action',
      icon: 'mouse-pointer-click',
      category: 'action',
      inspector: toInspectorSchema('action'),
      layoutCapability: 'span',
    },
    {
      blockType: 'detail',
      label: 'Detail',
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
      label: 'Detail section',
      icon: 'rows-3',
      category: 'detail',
      allowedChildren: ['field', 'sub-table', 'repeater', 'subform'],
      inspector: toInspectorSchema('detail-section'),
      layoutCapability: 'span',
    },
    {
      blockType: 'dashboard',
      label: 'Dashboard',
      icon: 'layout-dashboard',
      category: 'page',
      allowedChildren: ['widget'],
      inspector: toInspectorSchema('dashboard'),
      layoutCapability: 'span',
    },
    {
      blockType: 'widget',
      label: 'Widget',
      icon: 'chart-no-axes-combined',
      category: 'dashboard',
      inspector: toInspectorSchema('widget'),
      layoutCapability: 'dashboard-widget',
    },
    {
      blockType: 'tabs',
      label: 'Tabs',
      icon: 'panel-top-open',
      category: 'layout',
      allowedChildren: ['tab'],
      inspector: toInspectorSchema('tabs'),
      layoutCapability: 'span',
    },
    {
      blockType: 'tab',
      label: 'Tab',
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
      label: 'Sub table',
      icon: 'table-properties',
      category: 'detail',
      allowedChildren: ['column', 'action'],
      inspector: toInspectorSchema('sub-table'),
      layoutCapability: 'span',
    },
    {
      blockType: 'repeater',
      label: 'Repeater',
      icon: 'list-plus',
      category: 'form',
      allowedChildren: ['field'],
      inspector: toInspectorSchema('repeater'),
      layoutCapability: 'span',
    },
    {
      blockType: 'subform',
      label: 'Subform',
      icon: 'clipboard-list',
      category: 'form',
      allowedChildren: ['form-section', 'field'],
      inspector: toInspectorSchema('subform'),
      layoutCapability: 'span',
    },
    {
      blockType: 'ai-fill-banner',
      label: 'AI fill banner',
      icon: 'sparkles',
      category: 'form',
      inspector: toInspectorSchema('ai-fill-banner'),
      layoutCapability: 'span',
    },
    {
      blockType: 'bpm-panel',
      label: 'BPM panel',
      icon: 'workflow',
      category: 'workflow',
      inspector: toInspectorSchema('bpm-panel'),
      layoutCapability: 'span',
    },
    {
      blockType: 'activity-timeline',
      label: 'Activity timeline',
      icon: 'history',
      category: 'workflow',
      inspector: toInspectorSchema('activity-timeline'),
      layoutCapability: 'span',
    },
    {
      blockType: 'field-history',
      label: 'Field history',
      icon: 'list-tree',
      category: 'workflow',
      inspector: toInspectorSchema('field-history'),
      layoutCapability: 'span',
    },
  ]);
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
