/**
 * TreeSelect widget definition
 *
 * Tree-structured dropdown selection. Supports single/multiple with checkbox mode.
 * Data can come from dictCode (useDictTree) or static treeData.
 *
 * @since 4.4.0
 */

import type { WidgetDefinition } from '../../types';

export const treeselectWidget: WidgetDefinition = {
  component: 'treeselect',
  name: 'Tree Select',
  icon: '🌲',
  category: 'selection',
  description: 'Hierarchical tree dropdown selection',
  schema: [
    {
      key: 'placeholder',
      label: 'Placeholder',
      type: 'text',
      group: 'TreeSelect',
      defaultValue: 'Please select',
    },
    {
      key: 'dictCode',
      label: 'Dictionary Code',
      type: 'text',
      group: 'Data Source',
      description: 'System dictionary code for auto-loading tree data',
    },
    {
      key: 'treeData',
      label: 'Static Tree Data (JSON)',
      type: 'json',
      group: 'Data Source',
      description: 'Static tree nodes array. Used when dictCode is not set.',
    },
    {
      key: 'multiple',
      label: 'Multiple Selection',
      type: 'boolean',
      group: 'TreeSelect',
      defaultValue: false,
    },
    {
      key: 'checkable',
      label: 'Checkbox Mode',
      type: 'boolean',
      group: 'TreeSelect',
      defaultValue: false,
    },
    {
      key: 'leafOnly',
      label: 'Leaf Nodes Only',
      type: 'boolean',
      group: 'TreeSelect',
      defaultValue: true,
      description: 'Only allow selecting leaf nodes',
    },
    {
      key: 'cascade',
      label: 'Cascade Check',
      type: 'boolean',
      group: 'TreeSelect',
      defaultValue: false,
      description: 'Parent/child checkbox cascade in checkable mode',
    },
    {
      key: 'searchable',
      label: 'Searchable',
      type: 'boolean',
      group: 'TreeSelect',
      defaultValue: false,
    },
    {
      key: 'clearable',
      label: 'Clearable',
      type: 'boolean',
      group: 'TreeSelect',
      defaultValue: false,
    },
    {
      key: 'maxTagCount',
      label: 'Max Tag Count',
      type: 'number',
      group: 'TreeSelect',
      defaultValue: 3,
      description: 'Max tags shown before collapsing to "+N more"',
    },
    {
      key: 'size',
      label: 'Size',
      type: 'select',
      group: 'TreeSelect',
      defaultValue: 'medium',
      options: [
        { label: 'Small', value: 'small' },
        { label: 'Medium', value: 'medium' },
        { label: 'Large', value: 'large' },
      ],
    },
    {
      key: 'variant',
      label: 'Variant',
      type: 'select',
      group: 'TreeSelect',
      defaultValue: 'default',
      options: [
        { label: 'Default', value: 'default' },
        { label: 'Outlined', value: 'outlined' },
        { label: 'Filled', value: 'filled' },
      ],
    },
    {
      key: 'inline',
      label: 'Inline Display',
      type: 'boolean',
      group: 'TreeSelect',
      defaultValue: false,
    },
  ],
};
