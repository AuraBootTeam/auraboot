/**
 * Reference widget definition
 *
 * Relation picker that lets users select records from another model.
 * Configures the target model, the field displayed in the picker, and
 * which fields are used for search.
 *
 * @since 4.3.0
 */

import type { WidgetDefinition } from '../../types';

export const referenceWidget: WidgetDefinition = {
  component: 'reference',
  name: 'Reference',
  icon: '⟹',
  category: 'input',
  description: 'Reference picker',
  schema: [
    {
      key: 'targetModel',
      label: 'Target Model',
      type: 'model-select',
      group: 'Reference',
      description: 'Model to select records from',
    },
    {
      key: 'displayField',
      label: 'Display Field',
      type: 'text',
      group: 'Reference',
      placeholder: 'name',
      description: 'Field to display in the picker (e.g. name, title)',
    },
    {
      key: 'searchFields',
      label: 'Search Fields',
      type: 'text',
      group: 'Reference',
      placeholder: 'name,code',
      description: 'Comma-separated fields for search (e.g. name,code)',
    },
  ],
};
