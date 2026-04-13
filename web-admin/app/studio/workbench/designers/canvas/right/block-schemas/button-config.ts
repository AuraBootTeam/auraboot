/**
 * ButtonConfig PropertySchema — 14 properties for button-level editing
 *
 * Used when a user clicks a button inside a toolbar/form-buttons canvas preview.
 * The right panel switches from block-level config to button-level config.
 *
 * @since 4.0.0
 */

import type { PropertySchema } from '~/shared/designer/types';

export const BUTTON_CONFIG_SCHEMA: PropertySchema<string>[] = [
  // ── Basic ──
  { key: 'code', label: 'Code', type: 'text', group: 'Basic', required: true },
  { key: 'label', label: 'Label', type: 'text', group: 'Basic' },
  { key: 'primary', label: 'Primary', type: 'boolean', group: 'Basic' },
  { key: 'danger', label: 'Danger', type: 'boolean', group: 'Basic' },
  { key: 'icon', label: 'Icon', type: 'text', group: 'Basic', placeholder: 'e.g. Plus, Edit, Trash' },

  // ── Action (same structure as toolbar ACTION_DEF_SCHEMA) ──
  { key: 'action.type', label: 'Action Type', type: 'select', group: 'Action', defaultValue: 'command',
    options: [
      { label: 'Command', value: 'command' },
      { label: 'Navigate', value: 'navigate' },
      { label: 'Builtin', value: 'builtin' },
      { label: 'Flow', value: 'flow' },
    ] },
  { key: 'action.command', label: 'Command', type: 'command-select', group: 'Action',
    dependsOn: { field: 'action.type', value: ['command', 'state_transition'] } },
  { key: 'action.to', label: 'URL', type: 'text', group: 'Action', placeholder: '/p/page_key',
    dependsOn: { field: 'action.type', value: 'navigate' } },
  { key: 'action.name', label: 'Builtin Action', type: 'select', group: 'Action', defaultValue: 'search',
    options: [
      { label: 'Search', value: 'search' }, { label: 'Reset', value: 'reset' },
      { label: 'Refresh', value: 'refresh' }, { label: 'Export', value: 'export' },
      { label: 'New', value: 'new' }, { label: 'Edit', value: 'edit' },
      { label: 'View', value: 'view' }, { label: 'Delete', value: 'delete' },
    ],
    dependsOn: { field: 'action.type', value: 'builtin' } },
  { key: 'action.handler', label: 'Handler', type: 'text', group: 'Action', placeholder: 'e.g. onSubmitOrder',
    dependsOn: { field: 'action.type', value: 'flow' } },

  // ── Conditions ──
  { key: 'visibleWhen', label: 'Visible When', type: 'expression', group: 'Conditions' },
  { key: 'enableWhen', label: 'Enable When', type: 'expression', group: 'Conditions' },
  { key: 'confirm', label: 'Confirm Prompt', type: 'text', group: 'Conditions', placeholder: 'Are you sure?' },
];
