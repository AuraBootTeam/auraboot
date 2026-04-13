/**
 * DateTime widget definition
 *
 * Combined date + time picker with optional time panel toggle.
 *
 * @since 4.3.0
 */

import type { WidgetDefinition } from '../../types';

export const datetimeWidget: WidgetDefinition = {
  component: 'datetime',
  name: 'DateTime',
  icon: 'DT',
  category: 'input',
  description: 'Date + time picker',
  schema: [
    {
      key: 'dateFormat',
      label: 'Date Format',
      type: 'text',
      group: 'DateTime',
      placeholder: 'YYYY-MM-DD HH:mm',
    },
    {
      key: 'minDate',
      label: 'Min Date',
      type: 'text',
      group: 'DateTime',
      placeholder: '2020-01-01',
    },
    {
      key: 'maxDate',
      label: 'Max Date',
      type: 'text',
      group: 'DateTime',
      placeholder: '2030-12-31',
    },
    {
      key: 'showTime',
      label: 'Show Time Picker',
      type: 'boolean',
      group: 'DateTime',
    },
  ],
};
