/**
 * TimePicker widget definition
 *
 * Time-only picker (HH:mm or HH:mm:ss). Stores as string.
 * NOTE BUG-2: use12Hours/hourStep/minuteStep/secondStep accepted but ignored at runtime.
 *
 * @since 4.4.0
 */

import type { WidgetDefinition } from '../../types';

export const timepickerWidget: WidgetDefinition = {
  component: 'timepicker',
  name: 'Time Picker',
  icon: '🕐',
  category: 'input',
  description: 'Time-only picker (HH:mm or HH:mm:ss)',
  schema: [
    {
      key: 'placeholder',
      label: 'Placeholder',
      type: 'text',
      group: 'TimePicker',
    },
    {
      key: 'format',
      label: 'Format',
      type: 'select',
      group: 'TimePicker',
      defaultValue: 'HH:mm',
      options: [
        { label: 'HH:mm', value: 'HH:mm' },
        { label: 'HH:mm:ss', value: 'HH:mm:ss' },
      ],
    },
    {
      key: 'showSecond',
      label: 'Show Seconds',
      type: 'boolean',
      group: 'TimePicker',
      defaultValue: false,
    },
    {
      key: 'use12Hours',
      label: 'Use 12-Hour Format',
      type: 'boolean',
      group: 'TimePicker',
      defaultValue: false,
      description: 'Note: currently accepted but not applied at runtime (BUG-2)',
    },
    {
      key: 'hourStep',
      label: 'Hour Step',
      type: 'number',
      group: 'TimePicker',
      defaultValue: 1,
      description: 'Note: currently accepted but not applied at runtime (BUG-2)',
    },
    {
      key: 'minuteStep',
      label: 'Minute Step',
      type: 'number',
      group: 'TimePicker',
      defaultValue: 1,
      description: 'Note: currently accepted but not applied at runtime (BUG-2)',
    },
    {
      key: 'secondStep',
      label: 'Second Step',
      type: 'number',
      group: 'TimePicker',
      defaultValue: 1,
      description: 'Note: currently accepted but not applied at runtime (BUG-2)',
    },
    {
      key: 'clearable',
      label: 'Clearable',
      type: 'boolean',
      group: 'TimePicker',
      defaultValue: true,
    },
    {
      key: 'size',
      label: 'Size',
      type: 'select',
      group: 'TimePicker',
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
      group: 'TimePicker',
      defaultValue: 'default',
      options: [
        { label: 'Default', value: 'default' },
        { label: 'Outlined', value: 'outlined' },
        { label: 'Filled', value: 'filled' },
      ],
    },
  ],
};
