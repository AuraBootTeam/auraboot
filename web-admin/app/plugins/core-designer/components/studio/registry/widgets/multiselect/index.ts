/**
 * MultiSelect widget definition
 *
 * Multi-value dropdown with static options or dictionary source.
 * Stores selected values as comma-separated string.
 *
 * @since 4.4.0
 */

import type { WidgetDefinition } from '../../types';

export const multiselectWidget: WidgetDefinition = {
  component: 'multiselect',
  name: 'Multi Select',
  icon: '☑',
  category: 'selection',
  description: 'Multi-value dropdown selection',
  schema: [
    {
      key: 'placeholder',
      label: 'Placeholder',
      type: 'text',
      group: 'MultiSelect',
      placeholder: 'Please select',
    },
    {
      key: 'options',
      label: 'Options (JSON)',
      type: 'json',
      group: 'Options',
      description: 'Array of {label, value} objects',
    },
    {
      key: 'defaultValue',
      label: 'Default Value (JSON)',
      type: 'json',
      group: 'Options',
      description: 'Array of default selected values, e.g. ["a","b"]',
    },
    {
      key: 'maxSelection',
      label: 'Max Selection',
      type: 'number',
      group: 'Options',
      description: 'Maximum number of items that can be selected (0 = unlimited)',
    },
    {
      key: 'searchable',
      label: 'Searchable',
      type: 'boolean',
      group: 'Options',
      defaultValue: false,
    },
    {
      key: 'inline',
      label: 'Inline Display',
      type: 'boolean',
      group: 'Options',
      defaultValue: false,
    },
  ],
};
