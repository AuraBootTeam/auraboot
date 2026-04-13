/**
 * Switch widget definition
 *
 * Boolean toggle (on/off) with configurable checked/unchecked display labels.
 * Shares the same schema as Checkbox — both represent a boolean value.
 *
 * @since 4.3.0
 */

import type { WidgetDefinition } from '../../types';

export const switchWidget: WidgetDefinition = {
  component: 'switch',
  name: 'Switch',
  icon: '⊘',
  category: 'input',
  description: 'Boolean toggle',
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
