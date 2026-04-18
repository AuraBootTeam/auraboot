/**
 * CascadeSelect widget definition
 *
 * Multi-level cascading select. Data source priority: dictCode > options > loadChildren.
 * Stores as JSON-stringified array.
 *
 * @since 4.4.0
 */

import type { WidgetDefinition } from '../../types';

export const cascadeselectWidget: WidgetDefinition = {
  component: 'cascadeselect',
  name: 'Cascade Select',
  icon: '🔽',
  category: 'selection',
  description: 'Multi-level cascading dropdown selection',
  schema: [
    {
      key: 'placeholder',
      label: 'Placeholder',
      type: 'text',
      group: 'CascadeSelect',
    },
    {
      key: 'dictCode',
      label: 'Dictionary Code',
      type: 'text',
      group: 'Data Source',
      description: 'System dictionary code for hierarchical data (highest priority)',
    },
    {
      key: 'options',
      label: 'Static Options (JSON)',
      type: 'json',
      group: 'Data Source',
      description: 'Static tree options array. Used when dictCode is not set.',
    },
    {
      key: 'levels',
      label: 'Number of Levels',
      type: 'number',
      group: 'CascadeSelect',
      defaultValue: 3,
    },
    {
      key: 'levelLabels',
      label: 'Level Labels (JSON)',
      type: 'json',
      group: 'CascadeSelect',
      description: 'Array of label strings for each level, e.g. ["Province","City","District"]',
    },
    {
      key: 'allowPartial',
      label: 'Allow Partial Selection',
      type: 'boolean',
      group: 'CascadeSelect',
      defaultValue: false,
      description: 'Allow selecting intermediate (non-leaf) nodes',
    },
  ],
};
