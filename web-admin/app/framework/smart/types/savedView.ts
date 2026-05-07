/**
 * SavedView Types
 *
 * Type definitions for SavedView feature.
 * These types align with the backend SavedView API.
 */

/**
 * View scope determines visibility and access permissions
 */
export type ViewScope = 'personal' | 'team' | 'global';

/**
 * View type determines the rendering mode
 */
export type ViewType =
  | 'table'
  | 'kanban'
  | 'calendar'
  | 'gallery'
  | 'gantt'
  | 'tree'
  | 'timeline'
  | 'form';

/**
 * View type configuration for UI rendering
 */
export interface ViewTypeConfig {
  type: ViewType;
  label: string;
  icon: string;
  enabled: boolean;
}

/**
 * Available view type configurations
 */
export const VIEW_TYPE_CONFIGS: ViewTypeConfig[] = [
  { type: 'table', label: 'Table', icon: 'table-cells', enabled: true },
  { type: 'kanban', label: 'Kanban', icon: 'view-columns', enabled: true },
  { type: 'calendar', label: 'Calendar', icon: 'calendar', enabled: true },
  { type: 'gallery', label: 'Gallery', icon: 'squares-2x2', enabled: true },
  { type: 'gantt', label: 'Gantt', icon: 'bars-3-bottom-left', enabled: true },
  { type: 'tree', label: 'Tree', icon: 'list-bullet', enabled: true },
  { type: 'timeline', label: 'Timeline', icon: 'clock', enabled: true },
  { type: 'form', label: 'Form', icon: 'document-text', enabled: true },
];

/**
 * Kanban card field display configuration
 */
export interface KanbanCardFieldConfig {
  field: string;
  label?: string;
  /** Display type: text, number, date, tag, avatar */
  type?: string;
}

/**
 * Kanban column aggregation configuration
 */
export interface KanbanAggregationConfig {
  field: string;
  /** Aggregation function: COUNT, SUM, AVG, MIN, MAX */
  function: 'count' | 'sum' | 'avg' | 'min' | 'max';
  label?: string;
}

/**
 * Column configuration for table display
 */
export interface ColumnConfig {
  /** Field code identifying the column */
  fieldCode: string;
  /** Whether column is visible */
  visible?: boolean;
  /** Column width in pixels */
  width?: number;
  /** Column display order (lower values appear first) */
  order?: number;
  /** Whether column is frozen (pinned) */
  frozen?: boolean;
  /** Frozen position when column is pinned */
  frozenPosition?: 'left' | 'right';
}

/**
 * Sort configuration for table ordering
 */
export interface SortConfig {
  /** Field code to sort by */
  fieldCode: string;
  /** Sort direction */
  direction: 'asc' | 'desc';
  /** Sort priority for multi-column sorting (lower values have higher priority) */
  priority?: number;
}

/**
 * Filter configuration for data filtering
 */
export interface ViewFilterConfig {
  /** Field code to filter */
  fieldCode: string;
  /** Filter operator */
  operator:
    | 'eq'
    | 'ne'
    | 'gt'
    | 'gte'
    | 'lt'
    | 'lte'
    | 'like'
    | 'in'
    | 'between'
    | 'isNull'
    | 'isNotNull';
  /** Filter value (can be single value, array, or range object) */
  value: unknown;
  /** Logic operator to combine with previous filter */
  logic?: 'and' | 'OR';
  /** Filter group for complex conditions */
  group?: string;
  /** Whether this filter uses an expression instead of a static value */
  isExpression?: boolean;
  /** Expression string (used when isExpression is true) */
  expression?: string;
}

/**
 * Toolbar action button configuration for user customization.
 */
export interface ToolbarActionConfig {
  code: string;
  visible: boolean;
  pinned: boolean;
  order: number;
}

/**
 * Aggregation function for group summaries
 */
export type AggregationFunction = 'count' | 'sum' | 'avg' | 'min' | 'max';

/**
 * Aggregation configuration for group summary
 */
export interface AggregationConfig {
  /** Field code to aggregate */
  fieldCode: string;
  /** Aggregation function to apply */
  function: AggregationFunction;
  /** Display label for aggregation result */
  label?: string;
}

/**
 * Group by configuration for data grouping
 */
export interface GroupByConfig {
  /** Field code to group by */
  fieldCode: string;
  /** Whether groups are collapsed by default */
  collapsed?: boolean;
  /** Aggregation functions for group summary */
  aggregations?: AggregationConfig[];
}

/**
 * Pagination configuration
 */
export interface PaginationConfig {
  /** Number of rows per page */
  pageSize?: number;
  /** Available page size options */
  pageSizeOptions?: number[];
}

/**
 * Table display density
 */
export type TableDensity = 'compact' | 'default' | 'comfortable';

/**
 * Row height preset for table views.
 * Maps to specific pixel heights for consistent rendering.
 */
export type RowHeight = 'short' | 'medium' | 'tall' | 'extra-tall';

/**
 * Row height configuration: preset label → pixel values
 */
export const ROW_HEIGHT_CONFIG: Record<RowHeight, { px: number; pyClass: string; label: string }> =
  {
    short: { px: 32, pyClass: 'py-1', label: 'Short' },
    medium: { px: 44, pyClass: 'py-2', label: 'Medium' },
    tall: { px: 60, pyClass: 'py-3.5', label: 'Tall' },
    'extra-tall': { px: 80, pyClass: 'py-5', label: 'Extra Tall' },
  };

/** Default row height when none is configured */
export const DEFAULT_ROW_HEIGHT: RowHeight = 'medium';

/**
 * Conditional formatting rule style
 */
export interface ConditionalFormatStyle {
  /** Background color (hex, e.g. '#ffebee') */
  backgroundColor?: string;
  /** Text color (hex, e.g. '#b71c1c') */
  textColor?: string;
  /** Bold text */
  bold?: boolean;
}

/**
 * Conditional formatting rule.
 * When the field value matches the condition, the style is applied to the row.
 */
export interface ConditionalFormatRule {
  /** Field code to evaluate */
  fieldCode: string;
  /** Comparison operator */
  operator: 'eq' | 'ne' | 'gt' | 'gte' | 'lt' | 'lte' | 'like' | 'isNull' | 'isNotNull';
  /** Comparison value (not needed for isNull/isNotNull) */
  value?: string;
  /** Style to apply when condition matches */
  style: ConditionalFormatStyle;
}

/** Preset color options for conditional formatting */
export const CONDITIONAL_FORMAT_PRESETS: Array<{ label: string; bg: string; text: string }> = [
  { label: 'Red', bg: '#ffebee', text: '#b71c1c' },
  { label: 'Orange', bg: '#fff3e0', text: '#e65100' },
  { label: 'Yellow', bg: '#fffde7', text: '#f57f17' },
  { label: 'Green', bg: '#e8f5e9', text: '#1b5e20' },
  { label: 'Blue', bg: '#e3f2fd', text: '#0d47a1' },
  { label: 'Purple', bg: '#f3e5f5', text: '#4a148c' },
  { label: 'Gray', bg: '#f5f5f5', text: '#424242' },
];

/**
 * Complete view configuration stored in JSONB
 */
export interface ViewConfig {
  /** Column configurations */
  columns?: ColumnConfig[];
  /** Sort configurations */
  sorts?: SortConfig[];
  /** Filter configurations */
  filters?: ViewFilterConfig[];
  /** Group by configurations */
  groupBy?: GroupByConfig[];
  /** Pagination configuration */
  pagination?: PaginationConfig;
  /** Whether to show row numbers */
  showRowNumbers?: boolean;
  /** Table display density (legacy, superseded by rowHeight) */
  density?: TableDensity;
  /** Row height preset: short (32px), medium (44px), tall (60px), extra-tall (80px) */
  rowHeight?: RowHeight;
  /** Conditional formatting rules (applied to TABLE view rows) */
  conditionalFormats?: ConditionalFormatRule[];
  /** Toolbar action button configuration (visibility, pinning, order) */
  toolbarActions?: ToolbarActionConfig[];

  // ==================== Kanban Fields ====================

  /** Field used to group cards into columns (for KANBAN view) */
  groupByField?: string;
  /** Field used as card ID (for KANBAN view) */
  idField?: string;
  /** Field used as card title (for KANBAN view) */
  titleField?: string;
  /** Field used as card description (for KANBAN view) */
  descriptionField?: string;
  /** Fields to display on kanban cards */
  cardFields?: KanbanCardFieldConfig[];
  /** Aggregation configurations for kanban columns */
  kanbanAggregations?: KanbanAggregationConfig[];
  /** Whether kanban cards can be dragged */
  draggable?: boolean;
  /** Whether to show card count in column headers */
  showCount?: boolean;
  /** Whether to show aggregation values */
  showAggregations?: boolean;
  /** dict code used to resolve column color/terminal extras. If absent, derived from modelCode + groupByField via dict binding. */
  groupByDictCode?: string;
  /** dict values marking terminal stages. Overrides dict extra.terminal. */
  terminalStages?: { won?: string[]; lost?: string[] };
  /** When set, drag persistence dispatches this command instead of PUT /api/dynamic/{pageKey}/{pid}. */
  moveCommand?: string;

  // ==================== Calendar Fields ====================

  /** Date field for calendar event start (for CALENDAR view) */
  calendarDateField?: string;
  /** Title field for calendar event display (for CALENDAR view) */
  calendarTitleField?: string;
  /** End date field for multi-day events (for CALENDAR view) */
  calendarEndDateField?: string;
  /** Color-by field for event color coding (for CALENDAR view) */
  calendarColorField?: string;
  /** Default calendar view: dayGridMonth, timeGridWeek, listWeek */
  calendarDefaultView?: string;

  // ==================== Gallery Fields ====================

  /** Field containing image URL (for GALLERY view) */
  galleryImageField?: string;
  /** Field used as card title (for GALLERY view) */
  galleryTitleField?: string;
  /** Field used as card description (for GALLERY view) */
  galleryDescriptionField?: string;
  /** Number of grid columns: 2, 3, 4, 6 (for GALLERY view) */
  galleryColumns?: number;
  /** Image aspect ratio (for GALLERY view) */
  galleryAspectRatio?: 'square' | '4:3' | '16:9' | 'auto';
  /** Whether to show title overlay on cards (for GALLERY view) */
  galleryShowTitle?: boolean;
  /** Whether to show description on cards (for GALLERY view) */
  galleryShowDescription?: boolean;
  /** Additional fields to display on gallery cards (for GALLERY view) */
  galleryDisplayFields?: string[];

  // ==================== Gantt Fields ====================

  /** Start date field (for GANTT view) */
  ganttStartDateField?: string;
  /** End date field (for GANTT view) */
  ganttEndDateField?: string;
  /** Title field for task bars (for GANTT view) */
  ganttTitleField?: string;
  /** Progress field 0-100 (for GANTT view) */
  ganttProgressField?: string;
  /** Dependency field - comma-separated IDs (for GANTT view) */
  ganttDependencyField?: string;
  /** Default view mode: Day, Week, Month (for GANTT view) */
  ganttDefaultView?: string;

  // ==================== Tree Fields ====================

  /** Parent ID field for building tree hierarchy */
  treeParentField?: string;
  /** Title/name field to display for each node */
  treeTitleField?: string;
  /** Fields to show in each tree node row */
  treeDisplayFields?: string[];

  // ==================== Timeline Fields ====================

  /** Start date/datetime field (for TIMELINE view) */
  timelineStartField?: string;
  /** End date/datetime field (for TIMELINE view) */
  timelineEndField?: string;
  /** Resource grouping field (for TIMELINE view) */
  timelineResourceField?: string;
  /** Title field for timeline bars (for TIMELINE view) */
  timelineTitleField?: string;

  // ==================== Form Fields ====================

  /** Field codes to include in form (empty = all fields) */
  formFields?: string[];
  /** Custom form title */
  formTitle?: string;
  /** Description text shown above form */
  formDescription?: string;
  /** Custom submit button label */
  formSubmitLabel?: string;
  /** Success message after submission */
  formSuccessMessage?: string;
}

/**
 * Field requirement for a specific view type
 */
export interface ViewFieldRequirement {
  /** Key in viewConfig (e.g. ganttStartDateField) */
  key: string;
  /** Display label */
  label: string;
  /** Accepted field dataType values */
  acceptedTypes: string[];
  /** Whether this field is required for the view to function */
  required: boolean;
  /** Auto-create configuration when field is missing */
  autoCreateConfig?: {
    code: string;
    dataType: string;
  };
}

/**
 * Maps view types to their required fields.
 * Used by ViewManagePanel to detect missing fields and offer auto-creation.
 */
export const VIEW_TYPE_FIELD_REQUIREMENTS: Record<string, ViewFieldRequirement[]> = {
  KANBAN: [
    {
      key: 'groupByField',
      label: 'Group By Field',
      acceptedTypes: ['text', 'dict', 'boolean', 'date', 'datetime', 'reference'],
      required: true,
      autoCreateConfig: { code: 'status', dataType: 'text' },
    },
    { key: 'titleField', label: 'Title Field', acceptedTypes: ['text'], required: true },
  ],
  CALENDAR: [
    {
      key: 'calendarDateField',
      label: 'Date Field',
      acceptedTypes: ['date', 'datetime'],
      required: true,
      autoCreateConfig: { code: 'event_date', dataType: 'date' },
    },
  ],
  GALLERY: [
    {
      key: 'galleryImageField',
      label: 'Image Field',
      acceptedTypes: ['text', 'image', 'file'],
      required: true,
      autoCreateConfig: { code: 'cover_image', dataType: 'text' },
    },
  ],
  GANTT: [
    {
      key: 'ganttStartDateField',
      label: 'Start Date',
      acceptedTypes: ['date', 'datetime'],
      required: true,
      autoCreateConfig: { code: 'start_date', dataType: 'date' },
    },
    {
      key: 'ganttEndDateField',
      label: 'End Date',
      acceptedTypes: ['date', 'datetime'],
      required: true,
      autoCreateConfig: { code: 'end_date', dataType: 'date' },
    },
  ],
  TREE: [
    {
      key: 'treeParentField',
      label: 'Parent Field',
      acceptedTypes: ['reference'],
      required: true,
      autoCreateConfig: { code: 'parent_id', dataType: 'reference' },
    },
  ],
  TIMELINE: [
    {
      key: 'timelineStartField',
      label: 'Start Date',
      acceptedTypes: ['date', 'datetime'],
      required: true,
      autoCreateConfig: { code: 'start_date', dataType: 'date' },
    },
    {
      key: 'timelineEndField',
      label: 'End Date',
      acceptedTypes: ['date', 'datetime'],
      required: true,
      autoCreateConfig: { code: 'end_date', dataType: 'date' },
    },
  ],
};

/**
 * SavedView entity representing a user's customized view
 */
export interface SavedView {
  /** Primary identifier (UUID) */
  pid: string;
  /** View name displayed to user */
  name: string;
  /** View description */
  description?: string;
  /** Associated model code */
  modelCode: string;
  /** Associated page key (optional - null means model-level view) */
  pageKey?: string;
  /** View scope determining visibility */
  scope: ViewScope;
  /** View type determining rendering mode */
  viewType?: ViewType;
  /** Owner user ID (for PERSONAL views) */
  ownerId?: string;
  /** Team ID (for TEAM views) */
  teamId?: string;
  /** View configuration */
  viewConfig?: ViewConfig;
  /** Whether to allow access to full model fields */
  allowFullModel?: boolean;
  /** Whether this is the default view */
  isDefault?: boolean;
  /** Sort order for view list display */
  sortOrder?: number;
  /** Creation timestamp */
  createdAt?: string;
  /** Last update timestamp */
  updatedAt?: string;
  /** Creator user ID */
  createdBy?: string;
  /** Last updater user ID */
  updatedBy?: string;
  /** Owner display name (populated by service) */
  ownerName?: string;
  /** Team display name (populated by service) */
  teamName?: string;
}

/**
 * Request payload for creating a SavedView
 */
export interface SavedViewCreateRequest {
  /** View name (required, max 100 characters) */
  name: string;
  /** View description (max 500 characters) */
  description?: string;
  /** Associated model code (required) */
  modelCode: string;
  /** Associated page key (optional) */
  pageKey?: string;
  /** View scope (defaults to PERSONAL) */
  scope?: ViewScope;
  /** View type (defaults to TABLE) */
  viewType?: ViewType;
  /** Team ID (required when scope is TEAM) */
  teamId?: string;
  /** View configuration */
  viewConfig?: ViewConfig;
  /** Whether to allow full model field access */
  allowFullModel?: boolean;
  /** Whether this is the default view */
  isDefault?: boolean;
  /** Sort order for display */
  sortOrder?: number;
}

/**
 * Request payload for updating a SavedView
 * All fields are optional for partial updates
 */
export interface SavedViewUpdateRequest {
  /** View name (max 100 characters) */
  name?: string;
  /** View description (max 500 characters) */
  description?: string;
  /** View scope */
  scope?: ViewScope;
  /** Team ID (required when scope is TEAM) */
  teamId?: string;
  /** View configuration */
  viewConfig?: ViewConfig;
  /** Whether to allow full model field access */
  allowFullModel?: boolean;
  /** Whether this is the default view */
  isDefault?: boolean;
  /** Sort order for display */
  sortOrder?: number;
}

/**
 * Query parameters for listing SavedViews
 */
export interface SavedViewQueryParams {
  /** Model code to filter by (required) */
  modelCode: string;
  /** Page key to filter by (optional) */
  pageKey?: string;
}
