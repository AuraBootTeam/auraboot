import type { BlockDefinition } from '../../types';

/**
 * form-buttons block — shares the same ActionDef schema as toolbar,
 * since form buttons are contextually bound action buttons within a form.
 */
export const formButtonsBlock: BlockDefinition = {
  type: 'form-buttons',
  name: 'Form Buttons',
  icon: '✓',
  description: 'Submit / reset / cancel buttons',
  category: 'form',
  defaultColSpan: 12,
  schema: [
    // ── Action (ActionDef schema) ────────────────────────────────
    {
      key: 'action.type',
      label: 'Action Type',
      type: 'select',
      group: 'Action',
      defaultValue: 'command',
      options: [
        { label: 'Command', value: 'command' },
        { label: 'State Transition', value: 'state_transition' },
        { label: 'Navigate', value: 'navigate' },
        { label: 'Builtin', value: 'builtin' },
        { label: 'Flow (Handler)', value: 'flow' },
        { label: 'Flow (Steps)', value: 'flow_steps' },
        { label: 'BPM (Start Process)', value: 'bpm' },
      ],
    },
    {
      key: 'action.command',
      label: 'Command',
      type: 'command-select',
      group: 'Action',
      dependsOn: { field: 'action.type', value: ['command', 'state_transition'] },
    },
    {
      key: 'action.to',
      label: 'URL',
      type: 'text',
      group: 'Action',
      placeholder: '/p/page_key',
      dependsOn: { field: 'action.type', value: 'navigate' },
    },
    {
      key: 'action.name',
      label: 'Builtin Action',
      type: 'select',
      group: 'Action',
      defaultValue: 'search',
      options: [
        { label: 'Search', value: 'search' },
        { label: 'Reset', value: 'reset' },
        { label: 'Refresh', value: 'refresh' },
        { label: 'Export', value: 'export' },
        { label: 'New', value: 'new' },
        { label: 'Edit', value: 'edit' },
        { label: 'View', value: 'view' },
        { label: 'Delete', value: 'delete' },
      ],
      dependsOn: { field: 'action.type', value: 'builtin' },
    },
    {
      key: 'action.handler',
      label: 'Handler',
      type: 'text',
      group: 'Action',
      placeholder: 'e.g. onSubmitOrder',
      dependsOn: { field: 'action.type', value: 'flow' },
    },
    {
      key: 'action.steps',
      label: 'Flow Steps (JSON)',
      type: 'json',
      group: 'Action',
      description: 'Array of FlowStep objects: [{type, action, condition, ...}]',
      dependsOn: { field: 'action.type', value: 'flow_steps' },
    },
    // ── BPM action fields (type=bpm) ────────────────────────────
    {
      key: 'action.processDefinitionKey',
      label: 'Process Definition',
      type: 'process-select',
      group: 'Action',
      required: true,
      description: 'BPMN process definition to start when button is clicked',
      dependsOn: { field: 'action.type', value: 'bpm' },
    },
    {
      key: 'action.businessKeyField',
      label: 'Business Key Field',
      type: 'field-select',
      group: 'Action',
      required: true,
      placeholder: 'e.g. orderNo',
      description: 'Record field whose value becomes the process businessKey',
      dependsOn: { field: 'action.type', value: 'bpm' },
    },
    {
      key: 'action.variables',
      label: 'Variables (JSON)',
      type: 'json',
      group: 'Action',
      description:
        'Map of variable name to JSONPath ($.field.sub) or literal, e.g. {"amount": "$.totalAmount"}',
      dependsOn: { field: 'action.type', value: 'bpm' },
    },

    // ── Appearance ──────────────────────────────────────────────
    {
      key: 'primary',
      label: 'Primary',
      type: 'boolean',
      group: 'Appearance',
    },
    {
      key: 'danger',
      label: 'Danger',
      type: 'boolean',
      group: 'Appearance',
    },
    {
      key: 'icon',
      label: 'Icon',
      type: 'text',
      group: 'Appearance',
      placeholder: 'e.g. Plus, Edit, Trash',
    },

    // ── Conditions ──────────────────────────────────────────────
    {
      key: 'visibleWhen',
      label: 'Visible when',
      type: 'expression',
      group: 'Conditions',
      description: 'Condition expression to control button visibility',
    },
    {
      key: 'enableWhen',
      label: 'Enable when',
      type: 'expression',
      group: 'Conditions',
      description: 'Condition expression to control button enabled state',
    },
    {
      key: 'confirm',
      label: 'Confirm prompt',
      type: 'text',
      group: 'Conditions',
      placeholder: 'Are you sure?',
      description: 'Show confirmation dialog before executing action',
    },
  ],
};
