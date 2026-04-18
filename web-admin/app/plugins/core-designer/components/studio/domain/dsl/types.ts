/**
 * Page Schema V2 — aligned with ab_page_schema (schemaVersion = 2).
 *
 * V2 flat shape: kind/blocks/layout/profile — no areas/floors/components.
 * Dashboard pages are handled by Dashboard Designer (separate track).
 *
 * Authoritative spec: docs/plans/2026-03/2026-03-30-page-type-unification-design.md
 */

// =============================================================================
// Core Types
// =============================================================================

export type LocalizedText = string | Record<string, string>;

/**
 * Page layout — stack (vertical sections) or grid (12-col).
 * Replaces legacy DslLayoutConfig union (flex/floor/canvas/areas variants removed).
 */
export type PageLayout =
  | { type: 'stack' }
  | { type: 'grid'; cols: number };

/**
 * Page kind — list/form/detail only.
 * Dashboard is handled by Dashboard Designer (separate track).
 * Home/composite were V4 concepts; home is deleted, composite is evaluated in Task 4.6.
 */
export type PageKind = 'list' | 'form' | 'detail';

/**
 * Typed extension bag for PageSchema.
 * All page-level feature flags and runtime hints live here.
 */
export interface PageSchemaExtension {
  viewModelCode?: string;
  viewModel?: {
    mode?: 'entity' | 'namedQuery';
    baseEntityCode?: string;
    namedQueryCode?: string;
  };
  customApi?: {
    listEndpoint?: string;
    detailEndpoint?: string;
    method?: 'GET' | 'POST';
  };
  /**
   * Multi-view tabs for list pages (Table/Kanban/Calendar/Gallery).
   * Flat key on extension per DSL contract (see dsl-schema.generated.json,
   * plugins/test-fixtures/config/pages.json, docs/use-cases/crm.md).
   */
  enableMultiView?: boolean;
  afterSubmitRedirect?: string;
  plugin?: Record<string, unknown>;
}

/**
 * Page Schema V2 — flat shape stored in ab_page_schema.blocks (JSONB).
 */
export interface PageSchema {
  schemaVersion: 2;
  kind: PageKind;
  id: string;
  pageKey?: string;
  modelCode?: string;
  title?: LocalizedText;
  profile?: 'admin' | 'report';
  layout: PageLayout;
  blocks: DslBlock[];
  extension?: PageSchemaExtension;
}

// =============================================================================
// Block Types
// =============================================================================

/**
 * Block types supported in a page
 */
export type BlockType =
  | 'filters'
  | 'form-section'
  | 'detail-section'
  | 'form-buttons'
  | 'toolbar'
  | 'selection-info'
  | 'table'
  | 'stat-card'
  | 'chart-card'
  | 'text';

/**
 * Block definition within an area
 */
export interface DslBlock {
  id: string;
  blockType: BlockType;

  // Layout
  span?: number;

  // Visibility
  visible?: string; // SpEL expression

  // Content - varies by blockType
  title?: string;
  fields?: DslFieldRef[]; // filters, form-section
  columns?: DslColumnRef[]; // table
  buttons?: DslButton[]; // toolbar, form-buttons
  actions?: string[]; // shorthand for buttons

  // Block-specific props
  props?: Record<string, unknown>;

  // Data binding
  dataSource?: string;
  bind?: string;

  // Data table selection
  selection?: {
    bind?: string;
    type?: 'checkbox' | 'radio';
  };

  // Form section specific
  collapsible?: boolean;
  defaultCollapsed?: boolean;
}

// =============================================================================
// Field Types
// =============================================================================

/**
 * Field reference - can be shorthand string or full override
 */
export type DslFieldRef = string | DslFieldOverride;

/**
 * Field override configuration
 */
export interface DslFieldOverride {
  field: string;
  span?: number;
  visible?: string; // SpEL expression
  disabled?: string; // SpEL expression
  required?: boolean;
  component?: string;
  placeholder?: string;
  advanced?: boolean; // for filters, show in advanced section
  props?: Record<string, unknown>;
}

/**
 * Parse field shorthand syntax
 * Example: "code|sortable|width:120" -> { field: "code", sortable: true, width: 120 }
 */
export function parseFieldShorthand(ref: DslFieldRef): DslFieldOverride {
  if (typeof ref !== 'string') {
    return ref;
  }

  const parts = ref.split('|');
  const fieldCode = parts[0];
  const result: DslFieldOverride & Record<string, unknown> = { field: fieldCode };

  for (let i = 1; i < parts.length; i++) {
    const part = parts[i];
    if (part.includes(':')) {
      const [key, value] = part.split(':');
      const numValue = Number(value);
      result[key] = isNaN(numValue) ? value : numValue;
    } else {
      result[part] = true;
    }
  }

  return result;
}

/**
 * Serialize field override back to DSL format
 * Chooses between shorthand string and object format based on complexity
 */
export function serializeFieldOverride(field: DslFieldOverride): DslFieldRef {
  // Simple field: just the field name
  const keys = Object.keys(field).filter((k) => field[k as keyof DslFieldOverride] !== undefined);
  if (keys.length === 1 && keys[0] === 'field') {
    return field.field;
  }

  // Boolean flags that can be used in shorthand format
  const boolFlags = ['sortable', 'copyable', 'ellipsis', 'required', 'advanced', 'readonly'];
  // Key-value pairs that can be used in shorthand format
  const kvPairs = ['width', 'span', 'fixed', 'render'];

  // Complex properties that require object format
  const complexProps = [
    'visible',
    'disabled',
    'component',
    'props',
    'label',
    'placeholder',
    'pattern',
    'maxLength',
    'minLength',
    'minValue',
    'maxValue',
  ];
  const hasComplexProps = complexProps.some((key) => {
    const val = (field as any)[key];
    return val !== undefined && val !== null && val !== '';
  });

  // If has complex properties, use object format
  if (hasComplexProps) {
    // Clean up undefined values
    const cleaned: DslFieldOverride = { field: field.field };
    for (const [key, value] of Object.entries(field)) {
      if (value !== undefined && value !== null && value !== '') {
        (cleaned as any)[key] = value;
      }
    }
    return cleaned;
  }

  // Build shorthand format
  const parts = [field.field];
  for (const flag of boolFlags) {
    if ((field as any)[flag] === true) {
      parts.push(flag);
    }
  }
  for (const key of kvPairs) {
    const val = (field as any)[key];
    if (val !== undefined && val !== null && val !== '') {
      parts.push(`${key}:${val}`);
    }
  }

  return parts.length > 1 ? parts.join('|') : field.field;
}

// =============================================================================
// Column Types (for table)
// =============================================================================

/**
 * Column reference - can be shorthand string or full config
 */
export type DslColumnRef = string | DslColumnConfig;

/**
 * Column configuration
 */
export interface DslColumnConfig {
  field: string;
  width?: number;
  sortable?: boolean;
  copyable?: boolean;
  ellipsis?: boolean;
  fixed?: 'left' | 'right';
  render?: 'tag' | 'datetime' | 'currency' | 'link' | 'image';
  align?: 'left' | 'center' | 'right';
  actions?: DslButton[]; // for $actions column
}

/**
 * Parse column shorthand syntax
 */
export function parseColumnShorthand(ref: DslColumnRef): DslColumnConfig {
  if (typeof ref !== 'string') {
    return ref;
  }

  const parts = ref.split('|');
  const fieldCode = parts[0];
  const result: DslColumnConfig & Record<string, unknown> = { field: fieldCode };

  for (let i = 1; i < parts.length; i++) {
    const part = parts[i];
    if (part.includes(':')) {
      const [key, value] = part.split(':');
      const numValue = Number(value);
      result[key] = isNaN(numValue) ? value : numValue;
    } else {
      result[part] = true;
    }
  }

  return result;
}

// =============================================================================
// Button/Action Types
// =============================================================================

/**
 * Standard built-in actions
 */
export type StandardAction =
  | 'create'
  | 'view'
  | 'edit'
  | 'delete'
  | 'batchDelete'
  | 'search'
  | 'reset'
  | 'export'
  | 'import'
  | 'submit'
  | 'cancel';

/**
 * Localized text map (e.g. { "zh-CN": "提交", "en": "Submit" }).
 */
export type DslLocalizedText = string | Record<string, string>;

/**
 * Full-object action descriptor (runtime contract).
 *
 * Examples:
 *   { type: 'command', command: 'sc:update_showcase' }
 *   { type: 'navigate', url: '/p/foo' }
 *   { type: 'submit' } / { type: 'cancel' }
 */
export interface DslActionDescriptor {
  type: 'command' | 'navigate' | 'submit' | 'cancel' | 'close' | string;
  command?: string;
  url?: string;
  params?: Record<string, unknown>;
  [k: string]: unknown;
}

/**
 * Button configuration.
 *
 * Two shapes are supported by the runtime:
 *   1. Shorthand — `{ action: 'submit', type: 'primary' }`
 *      where `action` is a StandardAction code and `type` is the AntD button style.
 *   2. Full object — `{ code: 'submit', primary: true, label: {...}, action: { type, command } }`
 *      where `code` identifies the button semantically, `primary` is a boolean style flag,
 *      `label` is localized text, and `action` is a DslActionDescriptor.
 *
 * Fields are intentionally permissive so a button can start as shorthand and be
 * "promoted" into full form without losing data.
 */
export interface DslButton {
  /** Shorthand action code; optional when using full-object form with `code`. */
  action?: StandardAction | string | DslActionDescriptor;
  /** Full-object semantic code (mirrors `action` when used as shorthand). */
  code?: string;
  /** Full-object style flag: boolean primary highlight. */
  primary?: boolean;
  /** Full-object localized label. */
  label?: DslLocalizedText;
  visible?: string; // SpEL expression
  disabled?: string; // SpEL expression
  type?: 'primary' | 'default' | 'dashed' | 'text' | 'link';
  danger?: boolean;
  icon?: string;
  mode?: 'drawer' | 'modal' | 'page'; // for create/edit
  confirm?: boolean | string; // confirmation message
  id?: string; // for row actions
}

// =============================================================================
// DataSource Types
// =============================================================================

/**
 * Data source configuration
 */
export interface DslDataSource {
  url: string;
  method?: 'get' | 'post' | 'put' | 'delete';
  params?: string | Record<string, unknown>; // can be expression
  body?: unknown;
  auto?: boolean | string; // auto-load, can be conditional
  lazy?: boolean; // load on demand
  target?: string; // state path to store result
  adaptor?: 'table' | 'form' | 'raw';
  polling?: number; // polling interval in ms
}

// =============================================================================
// Handler Types
// =============================================================================

/**
 * Custom handler definition
 */
export interface DslHandler {
  type: 'flow';
  steps: DslStep[];
}

/**
 * Step in a handler flow
 */
export interface DslStep {
  id?: string;
  action: string;
  target?: string;
  args?: Record<string, unknown>;
  url?: string;
  method?: string;
  body?: unknown;
  messageKey?: string;
  message?: string;
  next?: string | Record<string, string>;
  terminal?: boolean;
}

