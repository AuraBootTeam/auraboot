import type { FieldOption } from './types';

/**
 * A $-prefixed variable with optional children for dot-access completion.
 */
export interface DollarVariable {
  /** Variable name including $ prefix, e.g. '$user' */
  name: string;
  /** Human-readable description */
  description: string;
  /** Value type */
  type: 'object' | 'string' | 'array';
  /** Static child properties (empty for dynamic variables like $form/$record) */
  children: DollarVariableChild[];
}

export interface DollarVariableChild {
  /** Property name without parent prefix, e.g. 'id' */
  name: string;
  /** Value type */
  type: string;
  /** Human-readable description */
  description: string;
}

/**
 * Hierarchical definition of all $ variables for Monaco auto-completion.
 * Static children are listed directly; dynamic variables ($form, $record)
 * have empty children — populated from ExpressionContext at runtime.
 */
export const DOLLAR_VARIABLES: DollarVariable[] = [
  {
    name: '$user',
    description: 'Current authenticated user',
    type: 'object',
    children: [
      { name: 'id', type: 'string', description: 'User ID' },
      { name: 'name', type: 'string', description: 'Display name' },
      { name: 'email', type: 'string', description: 'Email address' },
      { name: 'roles', type: 'array', description: 'Assigned roles' },
    ],
  },
  {
    name: '$form',
    description: 'Current form field values',
    type: 'object',
    children: [], // Dynamic — populated from model fields at runtime
  },
  {
    name: '$state',
    description: 'Page state (filters, selections)',
    type: 'object',
    children: [
      { name: 'filters', type: 'object', description: 'Active filter values' },
      { name: 'selectedIds', type: 'array', description: 'Selected record IDs' },
    ],
  },
  {
    name: '$record',
    description: 'Current row/record data',
    type: 'object',
    children: [], // Dynamic — populated from model fields at runtime
  },
  {
    name: '$page',
    description: 'Page metadata',
    type: 'object',
    children: [
      { name: 'kind', type: 'string', description: 'Page kind (list/form/detail/dashboard)' },
      { name: 'modelCode', type: 'string', description: 'Bound model code' },
      { name: 'pageKey', type: 'string', description: 'Page key' },
    ],
  },
];

/**
 * Standard $-prefixed context variables available in all expressions.
 * These map to the aliases injected by createExpressionContext().
 *
 * @see app/meta/runtime/expression/context.ts
 */
export const CONTEXT_VARIABLES: FieldOption[] = [
  // $user — current authenticated user
  { code: '$user.id', name: 'User ID', category: 'string', group: '$user' },
  { code: '$user.name', name: 'User Name', category: 'string', group: '$user' },
  { code: '$user.email', name: 'User Email', category: 'string', group: '$user' },
  { code: '$user.roles', name: 'User Roles', category: 'array', group: '$user' },
  { code: '$user.permissions', name: 'User Permissions', category: 'array', group: '$user' },

  // $form — current form data (form pages only)
  { code: '$form.mode', name: 'Form Mode (create/edit/view)', category: 'string', group: '$form' },

  // $page — page metadata
  { code: '$page.kind', name: 'Page Kind (list/form/detail)', category: 'string', group: '$page' },
  { code: '$page.modelCode', name: 'Page Model Code', category: 'string', group: '$page' },
  { code: '$page.pageKey', name: 'Page Key', category: 'string', group: '$page' },
  { code: '$page.mode', name: 'Page Mode (create/edit/view)', category: 'string', group: '$page' },
  { code: '$page.recordId', name: 'Current Record ID', category: 'string', group: '$page' },

  // $record — current row data (table row actions, detail pages)
  { code: '$record.id', name: 'Record ID', category: 'string', group: '$record' },
  { code: '$record.pid', name: 'Record PID', category: 'string', group: '$record' },

  // $state — page state
  { code: '$state.filters', name: 'Active Filters', category: 'string', group: '$state' },
  { code: '$state.selectedIds', name: 'Selected Row IDs', category: 'array', group: '$state' },
];
