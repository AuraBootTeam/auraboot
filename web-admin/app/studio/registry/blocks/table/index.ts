import type { BlockDefinition } from '../../types';

export const tableBlock: BlockDefinition = {
  type: 'table',
  name: 'Table',
  icon: '☰',
  description: 'Paginated list with built-in filter/sort',
  category: 'data',
  defaultColSpan: 12,
  schema: [
    // ── Data Source ──────────────────────────────────────────────
    {
      key: 'dataSource.modelCode',
      label: 'Model',
      type: 'model-select',
      group: 'Data Source',
    },
    {
      key: 'queryType',
      label: 'Query',
      type: 'select',
      group: 'Data Source',
      defaultValue: 'default',
      options: [
        { label: 'Default list', value: 'default' },
        { label: 'Named Query', value: 'namedQuery' },
      ],
    },
    {
      key: 'queryCode',
      label: 'Query Code',
      type: 'text',
      group: 'Data Source',
      placeholder: 'e.g. my_query',
      dependsOn: { field: 'queryType', value: 'namedQuery' },
    },
    {
      key: 'features.pagination.pageSize',
      label: 'Page Size',
      type: 'number',
      group: 'Data Source',
      defaultValue: 20,
    },
    {
      key: 'defaultSortField',
      label: 'Default Sort',
      type: 'text',
      group: 'Data Source',
      placeholder: 'Field code for default sort',
    },

    // ── Built-in Features ───────────────────────────────────────
    {
      key: 'features.search',
      label: 'Search',
      type: 'boolean',
      group: 'Built-in Features',
      defaultValue: false,
    },
    {
      key: 'features.filter',
      label: 'Filter',
      type: 'boolean',
      group: 'Built-in Features',
      defaultValue: false,
    },
    {
      key: 'features.sort',
      label: 'Sort',
      type: 'boolean',
      group: 'Built-in Features',
      defaultValue: false,
    },
    {
      key: 'features.create.enabled',
      label: 'Create button',
      type: 'boolean',
      group: 'Built-in Features',
      defaultValue: false,
    },
    {
      key: 'features.create.commandCode',
      label: 'Create Command',
      type: 'text',
      group: 'Built-in Features',
      placeholder: 'e.g. create_order',
      dependsOn: { field: 'features.create.enabled', value: true },
    },
    {
      key: 'features.batchActions',
      label: 'Batch actions',
      type: 'boolean',
      group: 'Built-in Features',
      defaultValue: false,
    },
    {
      key: 'features.export',
      label: 'Export',
      type: 'boolean',
      group: 'Built-in Features',
      defaultValue: false,
    },
    {
      key: 'features.pagination.enabled',
      label: 'Pagination',
      type: 'boolean',
      group: 'Built-in Features',
      defaultValue: true,
    },

    // ── Behavior ────────────────────────────────────────────────
    {
      key: 'rowClick',
      label: 'Row click',
      type: 'select',
      group: 'Behavior',
      defaultValue: 'drawer',
      options: [
        { label: 'Open drawer', value: 'drawer' },
        { label: 'New page', value: 'page' },
        { label: 'Expand inline', value: 'expand' },
        { label: 'None', value: 'none' },
      ],
    },
    {
      key: 'rowActionsEnabled',
      label: 'Row actions',
      type: 'boolean',
      group: 'Behavior',
      defaultValue: false,
    },
    {
      key: 'rowActions',
      label: 'Row Actions (JSON)',
      type: 'json',
      group: 'Behavior',
      description: 'Array of ButtonConfig objects for row-level actions (edit, delete, view, etc.)',
      dependsOn: { field: 'rowActionsEnabled', value: true },
    },
    {
      key: 'features.create.openMode',
      label: 'Create open mode',
      type: 'select',
      group: 'Behavior',
      defaultValue: 'modal',
      options: [
        { label: 'Modal', value: 'modal' },
        { label: 'New page', value: 'page' },
        { label: 'Inline', value: 'inline' },
      ],
    },

    // ── Default Filters ─────────────────────────────────────────
    {
      key: 'defaultFilters',
      label: 'Default Filters (JSON)',
      type: 'json',
      group: 'Default Filters',
      description: 'Array of {fieldName, operator, value} objects applied on page load',
    },

    // ── Summary / Aggregation ─────────────────────────────────
    {
      key: 'summary.enabled',
      label: 'Show summary row',
      type: 'boolean',
      group: 'Summary',
      defaultValue: false,
    },
    {
      key: 'summary.fields',
      label: 'Summary Fields (JSON)',
      type: 'json',
      group: 'Summary',
      description: 'Array of {field, aggregation} — aggregation: SUM/COUNT/AVG/MIN/MAX',
      dependsOn: { field: 'summary.enabled', value: true },
    },

    // ── Conditions ──────────────────────────────────────────────
    {
      key: 'visibleWhen',
      label: 'Visible when',
      type: 'expression',
      group: 'Conditions',
      description: 'Condition expression to control block visibility',
    },
    {
      key: 'className',
      label: 'CSS Class',
      type: 'text',
      group: 'Conditions',
      placeholder: 'e.g. compact-table',
      description: 'Custom CSS class name for styling',
    },
  ],
};
