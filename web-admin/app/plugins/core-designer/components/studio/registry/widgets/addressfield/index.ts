/**
 * AddressField widget definition
 *
 * Province/city/district cascade + detail address input.
 * NOTE BUG-8: runtime source file (AddressField.tsx) does not exist; widget is
 * registered in the manifest but will throw at runtime until the component is
 * implemented.
 *
 * @since 4.4.0
 */

import type { WidgetDefinition } from '../../types';

export const addressfieldWidget: WidgetDefinition = {
  component: 'addressfield',
  name: 'Address Field',
  icon: '🏠',
  category: 'advanced',
  description: 'Province/city/district cascade + detail address input (BUG-8: runtime not implemented)',
  schema: [
    {
      key: 'placeholder',
      label: 'Placeholder',
      type: 'text',
      group: 'AddressField',
      placeholder: 'Enter address',
    },
    {
      key: 'showDetailAddress',
      label: 'Show Detail Address',
      type: 'boolean',
      group: 'AddressField',
      defaultValue: true,
      description: 'Show free-text detail address input below the cascade selector',
    },
    {
      key: 'detailMaxLength',
      label: 'Detail Max Length',
      type: 'number',
      group: 'AddressField',
      defaultValue: 200,
      description: 'Maximum character length for detail address input',
    },
    {
      key: 'requireDistrict',
      label: 'Require District',
      type: 'boolean',
      group: 'AddressField',
      defaultValue: true,
      description: 'Require selection down to district level (3rd level)',
    },
    {
      key: 'levels',
      label: 'Cascade Levels',
      type: 'select',
      group: 'AddressField',
      defaultValue: '3',
      options: [
        { label: 'Province only', value: '1' },
        { label: 'Province + City', value: '2' },
        { label: 'Province + City + District', value: '3' },
      ],
    },
    {
      key: 'dictCode',
      label: 'Region Dictionary Code',
      type: 'text',
      group: 'AddressField',
      description: 'Dictionary code for region data (defaults to built-in administrative region)',
    },
  ],
};
