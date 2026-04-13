import type { BlockDefinition } from '../../types';

export const subTableBlock: BlockDefinition = {
  type: 'sub-table',
  name: 'Sub-table',
  icon: '⊞',
  description: 'Related data detail table',
  category: 'data',
  defaultColSpan: 12,
  schema: [
    // ── Data Source ──────────────────────────────────────────────
    {
      key: 'relationMode',
      label: 'Relation mode',
      type: 'select',
      group: 'Data Source',
      defaultValue: 'foreignKey',
      options: [
        { label: 'Foreign Key (direct)', value: 'foreignKey' },
        { label: 'Resolve via (indirect)', value: 'resolveVia' },
        { label: 'Data Source (API)', value: 'dataSource' },
      ],
    },
    {
      key: 'dataSource.modelCode',
      label: 'Child model',
      type: 'model-select',
      group: 'Data Source',
    },

    // Foreign Key mode
    {
      key: 'foreignKeyField',
      label: 'Foreign key field',
      type: 'text',
      group: 'Data Source',
      placeholder: 'e.g. order_id',
      description: 'Field in the child model that references the parent',
      dependsOn: { field: 'relationMode', value: 'foreignKey' },
    },

    // Resolve Via mode
    {
      key: 'junctionModel',
      label: 'Junction model',
      type: 'model-select',
      group: 'Data Source',
      dependsOn: { field: 'relationMode', value: 'resolveVia' },
    },
    {
      key: 'parentFkInJunction',
      label: 'Parent FK in junction',
      type: 'text',
      group: 'Data Source',
      placeholder: 'e.g. parent_id',
      dependsOn: { field: 'relationMode', value: 'resolveVia' },
    },
    {
      key: 'childFkInJunction',
      label: 'Child FK in junction',
      type: 'text',
      group: 'Data Source',
      placeholder: 'e.g. child_id',
      dependsOn: { field: 'relationMode', value: 'resolveVia' },
    },

    // Data Source (API) mode
    {
      key: 'dataSource.endpoint',
      label: 'API endpoint',
      type: 'text',
      group: 'Data Source',
      placeholder: '/api/children/{parentId}',
      dependsOn: { field: 'relationMode', value: 'dataSource' },
    },

    // ── Display ─────────────────────────────────────────────────
    {
      key: 'tabLabel',
      label: 'Tab label',
      type: 'text',
      group: 'Display',
      placeholder: 'e.g. Order Items',
    },
    {
      key: 'maxRows',
      label: 'Max rows shown',
      type: 'number',
      group: 'Display',
      defaultValue: 20,
    },

    // ── Conditions ──────────────────────────────────────────────
    {
      key: 'visibleWhen',
      label: 'Visible when',
      type: 'expression',
      group: 'Conditions',
      description: 'Condition expression to control block visibility',
    },
  ],
};
