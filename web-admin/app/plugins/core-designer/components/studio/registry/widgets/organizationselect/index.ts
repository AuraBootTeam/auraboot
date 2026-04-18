/**
 * OrganizationSelect widget definition
 *
 * Organization unit picker (company/department/team).
 * NOTE BUG-5: runtime uses setTimeout + mock data; real org API not connected.
 *
 * @since 4.4.0
 */

import type { WidgetDefinition } from '../../types';

export const organizationselectWidget: WidgetDefinition = {
  component: 'organizationselect',
  name: 'Organization Select',
  icon: '🏢',
  category: 'selection',
  description: 'Organization unit picker (company/department/team)',
  schema: [
    {
      key: 'placeholder',
      label: 'Placeholder',
      type: 'text',
      group: 'OrganizationSelect',
      defaultValue: 'Select organization',
    },
    {
      key: 'multiple',
      label: 'Multiple Selection',
      type: 'boolean',
      group: 'OrganizationSelect',
      defaultValue: false,
    },
    {
      key: 'allowClear',
      label: 'Allow Clear',
      type: 'boolean',
      group: 'OrganizationSelect',
      defaultValue: true,
    },
    {
      key: 'showHierarchy',
      label: 'Show Hierarchy',
      type: 'boolean',
      group: 'OrganizationSelect',
      defaultValue: true,
      description: 'Display full path breadcrumb for each node',
    },
    {
      key: 'selectableTypes',
      label: 'Selectable Types (JSON)',
      type: 'json',
      group: 'OrganizationSelect',
      description: 'Array of org types that can be selected, e.g. ["company","department","team"]',
      defaultValue: ['company', 'department', 'team'],
    },
  ],
};
