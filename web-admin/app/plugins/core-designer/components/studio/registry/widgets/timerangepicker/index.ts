/**
 * TimeRangePicker widget definition
 *
 * Time range picker with 6 built-in Chinese presets (not configurable).
 * Stores as object {start, end}.
 *
 * @since 4.4.0
 */

import type { WidgetDefinition } from '../../types';

export const timerangepickerWidget: WidgetDefinition = {
  component: 'timerangepicker',
  name: 'Time Range Picker',
  icon: '⏱',
  category: 'input',
  description: 'Time range picker with preset shortcuts',
  schema: [
    {
      key: 'placeholder',
      label: 'Placeholder',
      type: 'text',
      group: 'TimeRangePicker',
      placeholder: 'Select time range',
    },
    {
      key: 'format',
      label: 'Format',
      type: 'select',
      group: 'TimeRangePicker',
      defaultValue: '24h',
      options: [
        { label: '24-hour', value: '24h' },
        { label: '12-hour (AM/PM)', value: '12h' },
      ],
    },
    {
      key: 'minuteStep',
      label: 'Minute Step',
      type: 'number',
      group: 'TimeRangePicker',
      defaultValue: 15,
      description: 'Minute increment for time selection',
    },
    {
      key: 'allowClear',
      label: 'Allow Clear',
      type: 'boolean',
      group: 'TimeRangePicker',
      defaultValue: true,
    },
  ],
};
