import type { BlockDefinition } from '../../types';

export const monthlyGridBlock: BlockDefinition = {
  type: 'monthly-grid',
  name: 'Monthly Grid',
  icon: '📅',
  description: '12-month pivot table',
  category: 'data',
  defaultColSpan: 12,
  schema: [
    // ── Data Source ──────────────────────────────────────────────
    {
      key: 'monthlyGrid.parentModel',
      label: 'Parent model',
      type: 'model-select',
      group: 'Data Source',
      required: true,
      description: 'Model for the left-side row headers',
    },
    {
      key: 'monthlyGrid.parentField',
      label: 'Parent key field',
      type: 'text',
      group: 'Data Source',
      required: true,
      placeholder: 'e.g. id',
      description: 'Primary key or unique field in parent model',
    },
    {
      key: 'monthlyGrid.parentDisplayField',
      label: 'Parent display field',
      type: 'text',
      group: 'Data Source',
      placeholder: 'e.g. name',
      description: 'Field shown as row label',
    },
    {
      key: 'monthlyGrid.parentSortField',
      label: 'Parent sort field',
      type: 'text',
      group: 'Data Source',
      placeholder: 'e.g. sort_order',
    },
    {
      key: 'monthlyGrid.childModel',
      label: 'Child model',
      type: 'model-select',
      group: 'Data Source',
      required: true,
      description: 'Model containing monthly data records',
    },
    {
      key: 'monthlyGrid.childParentField',
      label: 'Child → Parent FK',
      type: 'text',
      group: 'Data Source',
      required: true,
      placeholder: 'e.g. parent_id',
      description: 'Foreign key in child model referencing parent',
    },
    {
      key: 'monthlyGrid.monthField',
      label: 'Month field',
      type: 'text',
      group: 'Data Source',
      placeholder: 'e.g. month',
      description: 'Integer field (1-12) for month grouping',
    },

    // ── Resolve Via (optional junction) ─────────────────────────
    {
      key: 'monthlyGrid.resolveVia.intermediateModel',
      label: 'Junction model',
      type: 'model-select',
      group: 'Resolve Via',
      description: 'Intermediate model for indirect relationship (optional)',
    },
    {
      key: 'monthlyGrid.resolveVia.intermediateParentField',
      label: 'Parent FK in junction',
      type: 'text',
      group: 'Resolve Via',
      placeholder: 'e.g. parent_id',
    },

    // ── Metrics ─────────────────────────────────────────────────
    {
      key: 'monthlyGrid.metrics',
      label: 'Metrics (JSON)',
      type: 'json',
      group: 'Metrics',
      description: 'Array of { field, label } objects to aggregate per month cell',
    },

    // ── Display ─────────────────────────────────────────────────
    {
      key: 'monthlyGrid.editableWhen',
      label: 'Editable when',
      type: 'expression',
      group: 'Display',
      description: 'Condition for inline editing of monthly cells',
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
