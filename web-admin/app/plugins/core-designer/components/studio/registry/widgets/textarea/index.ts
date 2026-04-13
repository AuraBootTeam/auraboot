/**
 * Textarea widget definition
 *
 * Multi-line text field with configurable row count.
 *
 * @since 4.3.0
 */

import type { WidgetDefinition } from '../../types';

export const textareaWidget: WidgetDefinition = {
  component: 'textarea',
  name: 'Textarea',
  icon: 'Tx',
  category: 'input',
  description: 'Multi-line text',
  schema: [
    {
      key: 'placeholder',
      label: 'Placeholder',
      type: 'text',
      group: 'Text',
    },
    {
      key: 'maxLength',
      label: 'Max Length',
      type: 'number',
      group: 'Text',
    },
    {
      key: 'rows',
      label: 'Rows',
      type: 'number',
      group: 'Text',
      description: 'Number of visible text rows',
    },
  ],
};
