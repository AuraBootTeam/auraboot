/**
 * DateRange widget definition
 *
 * Date range picker with optional preset ranges (this week, this month, etc.).
 * Stores as object {start, end} or string depending on field config.
 *
 * @since 4.4.0
 */

import type { WidgetDefinition } from '../../types';

export const daterangeWidget: WidgetDefinition = {
  component: 'daterange',
  name: 'Date Range',
  icon: '📅',
  category: 'input',
  description: 'Date range picker with preset shortcuts',
  schema: [
    {
      key: 'placeholder',
      label: 'Placeholder',
      type: 'text',
      group: 'DateRange',
    },
    {
      key: 'defaultRange',
      label: 'Default Range',
      type: 'select',
      group: 'DateRange',
      description: 'Pre-fill with a relative date range on mount if no value is set',
      options: [
        { label: 'None', value: '' },
        { label: 'Today', value: 'today' },
        { label: 'Yesterday', value: 'yesterday' },
        { label: 'This Week', value: 'this_week' },
        { label: 'Last Week', value: 'last_week' },
        { label: 'This Month', value: 'this_month' },
        { label: 'Last Month', value: 'last_month' },
        { label: 'This Quarter', value: 'this_quarter' },
        { label: 'This Year', value: 'this_year' },
      ],
    },
    {
      key: 'minDate',
      label: 'Min Date',
      type: 'text',
      group: 'DateRange',
      placeholder: '2020-01-01',
    },
    {
      key: 'maxDate',
      label: 'Max Date',
      type: 'text',
      group: 'DateRange',
      placeholder: '2030-12-31',
    },
    {
      key: 'clearable',
      label: 'Clearable',
      type: 'boolean',
      group: 'DateRange',
      defaultValue: true,
    },
    {
      key: 'inline',
      label: 'Inline Calendar',
      type: 'boolean',
      group: 'DateRange',
      defaultValue: true,
    },
    {
      key: 'size',
      label: 'Size',
      type: 'select',
      group: 'DateRange',
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
      group: 'DateRange',
      defaultValue: 'default',
      options: [
        { label: 'Default', value: 'default' },
        { label: 'Outlined', value: 'outlined' },
        { label: 'Filled', value: 'filled' },
      ],
    },
  ],
};
