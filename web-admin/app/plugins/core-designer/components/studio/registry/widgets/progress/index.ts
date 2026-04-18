/**
 * Progress widget definition
 *
 * Visual progress bar for integer/decimal values (0-100).
 * Color thresholds are hardcoded: <30 red / 30-70 yellow / >=70 green.
 *
 * @since 4.4.0
 */

import type { WidgetDefinition } from '../../types';

export const progressWidget: WidgetDefinition = {
  component: 'progress',
  name: 'Progress',
  icon: '▬',
  category: 'display',
  description: 'Progress bar for numeric values (0-100)',
  schema: [
    {
      key: 'showLabel',
      label: 'Show Percentage Label',
      type: 'boolean',
      group: 'Progress',
      defaultValue: true,
    },
  ],
};
