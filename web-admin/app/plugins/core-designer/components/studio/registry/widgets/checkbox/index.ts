/**
 * Checkbox widget definition
 *
 * Boolean checkbox with configurable checked/unchecked display labels.
 *
 * @since 4.3.0
 */

import type { WidgetDefinition } from '../../types';

export const checkboxWidget: WidgetDefinition = {
  component: 'checkbox',
  name: 'Checkbox',
  icon: '☐',
  category: 'input',
  description: 'Boolean checkbox',
  schema: [
    {
      key: 'checkedLabel',
      label: 'Checked Label',
      type: 'text',
      group: 'Toggle',
      placeholder: 'Yes',
    },
    {
      key: 'uncheckedLabel',
      label: 'Unchecked Label',
      type: 'text',
      group: 'Toggle',
      placeholder: 'No',
    },
  ],
};
