/**
 * MemberPicker widget definition
 *
 * Admin user picker. Data source is hardcoded GET /api/admin/users/search.
 * NOTE BUG-3: runtime component lacks name prop / FieldBase integration —
 * cannot submit as a standalone form field. Schema exposed for completeness.
 *
 * @since 4.4.0
 */

import type { WidgetDefinition } from '../../types';

export const memberpickerWidget: WidgetDefinition = {
  component: 'memberpicker',
  name: 'Member Picker',
  icon: '👥',
  category: 'selection',
  description: 'Admin user/member picker',
  schema: [
    {
      key: 'placeholder',
      label: 'Placeholder',
      type: 'text',
      group: 'MemberPicker',
      defaultValue: 'Select member',
    },
    {
      key: 'multiple',
      label: 'Multiple Selection',
      type: 'boolean',
      group: 'MemberPicker',
      defaultValue: false,
    },
    {
      key: 'readOnly',
      label: 'Read Only',
      type: 'boolean',
      group: 'MemberPicker',
      defaultValue: false,
    },
  ],
};
