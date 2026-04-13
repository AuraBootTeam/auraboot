/**
 * Number widget definition
 *
 * Numeric input with min/max bounds, step increment, and decimal precision.
 *
 * @since 4.3.0
 */

import type { WidgetDefinition } from '../../types';

export const numberWidget: WidgetDefinition = {
  component: 'number',
  name: 'Number',
  icon: '#',
  category: 'input',
  description: 'Number input',
  schema: [
    {
      key: 'placeholder',
      label: 'Placeholder',
      type: 'text',
      group: 'Number',
    },
    {
      key: 'min',
      label: 'Min Value',
      type: 'number',
      group: 'Number',
    },
    {
      key: 'max',
      label: 'Max Value',
      type: 'number',
      group: 'Number',
    },
    {
      key: 'step',
      label: 'Step',
      type: 'number',
      group: 'Number',
      description: 'Increment step (e.g. 0.01 for currency)',
    },
    {
      key: 'precision',
      label: 'Decimal Precision',
      type: 'number',
      group: 'Number',
      description: 'Number of decimal places',
    },
  ],
};
