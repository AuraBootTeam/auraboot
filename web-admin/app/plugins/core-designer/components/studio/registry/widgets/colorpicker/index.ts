/**
 * ColorPicker widget definition
 *
 * Hex color picker with 11 preset swatches + native color input.
 * NOTE BUG-1: name/label/required declared in interface but not rendered by runtime.
 *
 * @since 4.4.0
 */

import type { WidgetDefinition } from '../../types';

export const colorpickerWidget: WidgetDefinition = {
  component: 'colorpicker',
  name: 'Color Picker',
  icon: '🎨',
  category: 'input',
  description: 'Hex color picker with preset swatches',
  schema: [
    {
      key: 'defaultValue',
      label: 'Default Color',
      type: 'text',
      group: 'Color',
      defaultValue: '#3b82f6',
      placeholder: '#3b82f6',
      description: 'Default hex color value',
    },
  ],
};
