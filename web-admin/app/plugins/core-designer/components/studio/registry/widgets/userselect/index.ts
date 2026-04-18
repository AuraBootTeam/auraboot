/**
 * UserSelect widget definition
 *
 * Tenant member picker. Data source is hardcoded POST /api/tenant/members/search
 * (pageSize=50, status=active). No configurable API endpoint.
 *
 * @since 4.4.0
 */

import type { WidgetDefinition } from '../../types';

export const userselectWidget: WidgetDefinition = {
  component: 'userselect',
  name: 'User Select',
  icon: '👤',
  category: 'selection',
  description: 'Tenant member picker (active members)',
  schema: [
    {
      key: 'placeholder',
      label: 'Placeholder',
      type: 'text',
      group: 'UserSelect',
      defaultValue: 'Select user',
    },
    {
      key: 'multiple',
      label: 'Multiple Selection',
      type: 'boolean',
      group: 'UserSelect',
      defaultValue: false,
    },
    {
      key: 'allowClear',
      label: 'Allow Clear',
      type: 'boolean',
      group: 'UserSelect',
      defaultValue: true,
    },
  ],
};
