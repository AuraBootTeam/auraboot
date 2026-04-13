/**
 * Text Input widget definition
 *
 * Single-line plain-text field with optional max-length and pattern validation.
 *
 * @since 4.3.0
 */

import type { WidgetDefinition } from '../../types';

export const textWidget: WidgetDefinition = {
  component: 'text',
  name: 'Text Input',
  icon: 'Aa',
  category: 'input',
  description: 'Single-line text',
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
      key: 'pattern',
      label: 'Validation Pattern',
      type: 'text',
      group: 'Text',
      description: 'Regular expression for input validation',
    },
  ],
};
