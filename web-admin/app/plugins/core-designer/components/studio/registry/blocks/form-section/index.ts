import type { BlockDefinition } from '../../types';

export const formSectionBlock: BlockDefinition = {
  type: 'form-section',
  name: 'Form Section',
  icon: '📝',
  description: 'Display / create / edit modes',
  category: 'form',
  defaultColSpan: 12,
  schema: [
    // ── Section Info ────────────────────────────────────────────
    {
      key: 'title',
      label: 'Section Title',
      type: 'text',
      group: 'Section',
      placeholder: 'e.g. Basic Information',
      description: 'Display name shown in the section header',
    },

    // ── Data Source ──────────────────────────────────────────────
    {
      key: 'dataSource.modelCode',
      label: 'Model',
      type: 'model-select',
      group: 'Data Source',
    },

    // ── Mode ────────────────────────────────────────────────────
    {
      key: 'mode',
      label: 'Mode',
      type: 'select',
      group: 'Mode',
      defaultValue: 'display',
      options: [
        { label: 'Display', value: 'display' },
        { label: 'Create', value: 'create' },
        { label: 'Edit', value: 'edit' },
      ],
    },

    // ── Command (when mode != display) ──────────────────────────
    {
      key: 'commandCode',
      label: 'Submit command',
      type: 'text',
      group: 'Command',
      placeholder: 'e.g. create_order',
      dependsOn: { field: 'mode', value: 'create' },
      description: 'Also shown when mode = edit',
    },

    // ── After Submit ────────────────────────────────────────────
    {
      key: 'afterSubmit',
      label: 'After Submit',
      type: 'select',
      group: 'After Submit',
      defaultValue: 'toast',
      options: [
        { label: 'Show toast', value: 'toast' },
        { label: 'Refresh page', value: 'refresh' },
        { label: 'Navigate away', value: 'navigate' },
        { label: 'Clear form', value: 'clearForm' },
      ],
      dependsOn: { field: 'mode', value: 'create' },
      description: 'Also shown when mode = edit',
    },

    // ── Layout ──────────────────────────────────────────────────
    {
      key: 'colCount',
      label: 'Columns',
      type: 'select',
      group: 'Layout',
      defaultValue: '2',
      options: [
        { label: '2', value: '2' },
        { label: '3', value: '3' },
        { label: '4', value: '4' },
      ],
    },

    // ── Buttons ─────────────────────────────────────────────────
    {
      key: 'submitText',
      label: 'Submit button text',
      type: 'text',
      group: 'Buttons',
      defaultValue: 'Submit',
      placeholder: 'Submit',
    },
    {
      key: 'showCancel',
      label: 'Show cancel button',
      type: 'boolean',
      group: 'Buttons',
      defaultValue: false,
    },
    {
      key: 'showReset',
      label: 'Show reset button',
      type: 'boolean',
      group: 'Buttons',
      defaultValue: false,
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
