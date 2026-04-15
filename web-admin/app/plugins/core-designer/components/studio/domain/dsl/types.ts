/**
 * DSL V4 Type Definitions
 *
 * Core types for AuraBoot Page DSL version 4.0
 *
 * Design principles:
 * 1. Preserve areas structure - filters/toolbar/main for semantic regions
 * 2. Built-in handlers - common operations without explicit definition
 * 3. Model integration - fields inherit from Model, DSL only overrides
 * 4. Field shorthand - reduce configuration verbosity
 * 5. Multi-layout support - grid/flex/floor/canvas
 */

// =============================================================================
// Core Types
// =============================================================================

/**
 * Page schema version 4.0
 */
export interface PageSchema {
  // Meta information
  $schema: 'auraboot://schemas/page/v4';
  version: '4.0.0';
  id: string;
  kind: PageKind;
  modelCode: string;

  // Layout configuration
  layout: DslLayoutConfig;

  // State management
  state?: Record<string, unknown>;

  // Content structures (mutually exclusive based on kind)
  areas?: Record<string, DslArea>; // list/form pages
  floors?: DslFloor[]; // detail/home pages
  components?: DslComponent[]; // floor content components

  // Data sources
  dataSources?: Record<string, DslDataSource>;

  // Custom handlers (optional, standard actions are built-in)
  handlers?: Record<string, DslHandler>;

  // Multi-view support — show view type tabs (Table/Kanban/Calendar) on list pages
  enableMultiView?: boolean;
}

/**
 * Page kind determines the content structure
 */
export type PageKind = 'form' | 'list' | 'detail' | 'home' | 'composite';

// =============================================================================
// Layout Types
// =============================================================================

/**
 * Layout configuration - varies by type
 */
export type DslLayoutConfig =
  | DslGridLayout
  | DslFlexLayout
  | DslFloorLayout
  | DslCanvasLayout
  | DslAreasLayout;

export interface DslGridLayout {
  type: 'grid';
  columns: number;
  gap?: number;
  rows?: number | 'auto';
}

export interface DslFlexLayout {
  type: 'flex';
  direction?: 'row' | 'column';
  justify?: 'start' | 'end' | 'center' | 'space-between' | 'space-around';
  align?: 'start' | 'end' | 'center' | 'stretch' | 'flex-start' | 'flex-end';
  gap?: number;
}

export interface DslFloorLayout {
  type: 'floor';
  gap?: number;
}

export interface DslCanvasLayout {
  type: 'canvas';
  grid?: {
    columns: number;
    rows: string | number;
    gap: number;
  };
}

export interface DslAreasLayout {
  areas: string[];
  areasConfig?: Record<
    string,
    Omit<DslGridLayout | DslFlexLayout, 'type'> & { type: 'grid' | 'flex' }
  >;
}

// =============================================================================
// Area Types (for list/form pages)
// =============================================================================

/**
 * Predefined area names
 */
export type AreaName = 'filters' | 'toolbar' | 'main';

/**
 * Area contains a list of blocks
 */
export interface DslArea {
  blocks: DslBlock[];
}

/**
 * Block types supported in areas
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
 * Button configuration
 */
export interface DslButton {
  action: StandardAction | string;
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
// Floor Types (for detail/home pages)
// =============================================================================

/**
 * Floor - a vertical section in detail/home pages
 */
export interface DslFloor {
  id: string;
  title?: string;
  layout?: DslLayoutConfig;
  collapsible?: boolean;
  defaultCollapsed?: boolean;
  visible?: string; // SpEL expression

  // Content
  components?: DslComponent[];

  // Special floor types
  type?: 'TabsFloor';
  tabs?: DslTab[];
}

/**
 * Tab within a TabsFloor
 */
export interface DslTab {
  key: string;
  label: string;
  icon?: string;
  badge?: string; // expression for badge count
  visible?: string;
  content: DslComponent;
}

// =============================================================================
// Component Types (for floor content)
// =============================================================================

/**
 * Component - reusable UI element
 */
export interface DslComponent {
  id?: string;
  type: string;

  // Canvas positioning
  grid?: {
    column: string;
    row: string | number;
  };

  // Data binding
  dataSource?: string;

  // Generic props
  [key: string]: unknown;
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

