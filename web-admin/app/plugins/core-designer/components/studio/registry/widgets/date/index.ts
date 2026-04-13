/**
 * Date widget definition
 *
 * Date-only picker with format string and min/max bounds.
 *
 * @since 4.3.0
 */

import type { WidgetDefinition } from '../../types';

export const dateWidget: WidgetDefinition = {
  component: 'date',
  name: 'Date',
  icon: 'D',
  category: 'input',
  description: 'Date picker',
  schema: [
    {
      key: 'dateFormat',
      label: 'Date Format',
      type: 'text',
      group: 'Date',
      placeholder: 'YYYY-MM-DD',
    },
    {
      key: 'minDate',
      label: 'Min Date',
      type: 'text',
      group: 'Date',
      placeholder: '2020-01-01',
    },
    {
      key: 'maxDate',
      label: 'Max Date',
      type: 'text',
      group: 'Date',
      placeholder: '2030-12-31',
    },
  ],
};
