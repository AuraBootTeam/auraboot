/**
 * Toolbar block configSchema
 *
 * Toolbar blocks contain action buttons. Each button has an ActionDef
 * that defines what happens when clicked. The action configuration is
 * expressed as PropertySchema with dependsOn-based conditional fields.
 *
 * ActionDef UI types (4 simplified from 6 DSL types):
 *   - command: executes a command (covers both 'command' and 'state_transition')
 *   - navigate: navigates to a URL
 *   - builtin: built-in UI action (search/reset/refresh/export/etc.)
 *   - flow: client-side flow handler
 *
 * @since 4.0.0
 */

import type { PropertySchema } from '~/shared/designer/types';

/**
 * ActionDef property schema — reusable across toolbar, form-buttons, rowActions.
 * Uses dependsOn with array values to show/hide type-specific fields.
 */
export const ACTION_DEF_SCHEMA: PropertySchema<string>[] = [
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
    ],
  },
  // type = command or state_transition → show command select
  {
    key: 'action.command',
    label: 'Command',
    type: 'command-select',
    group: 'Action',
    dependsOn: { field: 'action.type', value: ['command', 'state_transition'] },
  },
  // type = navigate → show URL input
  {
    key: 'action.to',
    label: 'URL',
    type: 'text',
    group: 'Action',
    placeholder: '/p/page_key',
    dependsOn: { field: 'action.type', value: 'navigate' },
  },
  // type = builtin → show builtin action select
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
  // type = flow → show handler input
  {
    key: 'action.handler',
    label: 'Handler',
    type: 'text',
    group: 'Action',
    placeholder: 'e.g. onSubmitOrder',
    dependsOn: { field: 'action.type', value: 'flow' },
  },
  // type = flow_steps → show steps JSON editor
  {
    key: 'action.steps',
    label: 'Flow Steps (JSON)',
    type: 'json',
    group: 'Action',
    description: 'Array of FlowStep objects: [{type, action, condition, ...}]',
    dependsOn: { field: 'action.type', value: 'flow_steps' },
  },
];

export const TOOLBAR_CONFIG_SCHEMA: PropertySchema<string>[] = [
  // ── Action (from ActionDef schema) ──────────────────────────
  ...ACTION_DEF_SCHEMA,

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
];
