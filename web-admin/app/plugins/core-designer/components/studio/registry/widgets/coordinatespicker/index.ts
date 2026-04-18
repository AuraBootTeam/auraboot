/**
 * CoordinatesPicker widget definition
 *
 * Geographic coordinates picker. Stores as JSON string {latitude, longitude, address?}.
 * NOTE BUG-6: runtime is a mock with 3 hardcoded city buttons; mapType/defaultZoom
 * props are accepted but have no effect until real map integration is implemented.
 *
 * @since 4.4.0
 */

import type { WidgetDefinition } from '../../types';

export const coordinatespickerWidget: WidgetDefinition = {
  component: 'coordinatespicker',
  name: 'Coordinates Picker',
  icon: '📍',
  category: 'advanced',
  description: 'Geographic coordinates picker (map integration)',
  schema: [
    {
      key: 'placeholder',
      label: 'Placeholder',
      type: 'text',
      group: 'CoordinatesPicker',
      defaultValue: 'Select location',
    },
    {
      key: 'mapType',
      label: 'Map Provider',
      type: 'select',
      group: 'CoordinatesPicker',
      defaultValue: 'amap',
      options: [
        { label: 'AMap (高德)', value: 'amap' },
        { label: 'Google Maps', value: 'google' },
        { label: 'Baidu Maps (百度)', value: 'baidu' },
      ],
      description: 'Map provider (BUG-6: currently mock only, setting has no effect)',
    },
    {
      key: 'defaultZoom',
      label: 'Default Zoom Level',
      type: 'number',
      group: 'CoordinatesPicker',
      defaultValue: 15,
      description: 'Initial map zoom level 1-20 (BUG-6: currently mock only)',
    },
  ],
};
