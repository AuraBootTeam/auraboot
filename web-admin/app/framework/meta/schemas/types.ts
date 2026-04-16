/**
 * 统一 DSL Schema 类型定义
 * 基于 final.v1.0 DSL 结构
 */

import type { LinkageRule } from '~/plugins/core-designer/components/studio/workbench/panels/linkage/types';

// 多语言文本类型
// 注意: 此类型应与 i18n-renderer.ts 中的定义保持一致
export interface LocalizedText {
  'zh-CN'?: string;
  'en-US'?: string;
  'ja-JP'?: string;
  'ko-KR'?: string;
  [locale: string]: string | undefined; // 支持动态 locale
}

// Layout 配置
export interface LayoutConfig {
  type?: 'grid' | 'stack'; // Layout mode: 'grid' (12-col) or 'stack' (vertical). Default: 'stack'
  cols?: number;            // Grid column count (default 12, only for type='grid')
  colGap?: number;          // Column gap in px (default 16)
  rowGap?: number;          // Row gap in px (default 16)
  gap?: number;             // Stack gap in px (only for type='stack')
}

// Block 配置 (新 DSL)
export interface BlockConfig {
  id: string;
  blockType: string; // block 类型: form-section, form-buttons, table, filters 等
  title?: string | LocalizedText;
  layout?: BlockLayoutConfig;
  visibleWhen?: string;
  className?: string;

  // 表单相关
  fields?: FieldConfig[];
  buttons?: ButtonConfig[];

  // 表格相关
  table?: TableConfig;
  columns?: number | ColumnConfig[];
  rowActions?: ButtonConfig[];
  dataSource?: string;

  // List tabs
  tabs?: ListTabConfig[] | DetailTabConfig[];

  // Sub-table (master-detail)
  subTable?: SubTableConfig;

  // Monthly grid (12-month pivot on child model)
  monthlyGrid?: MonthlyGridConfig;

  // Sort and summary
  defaultSort?: DefaultSortConfig;
  summary?: SummaryConfig;

  // 布局相关
  gap?: string | number;

  // 其他属性
  component?: string;
  [key: string]: any;
}

export interface BlockLayoutConfig {
  col?: number;       // Start column (0-based, 0..cols-1)
  colSpan?: number;   // Number of columns (1..cols, default 12)
  rowSpan?: number;   // Number of rows (>= 1, default 1)
  order?: number;     // Stable flow order for auto-layout
  row?: number;       // Locked row position (optional, advanced)
  columns?: number;   // Internal sub-column count (for form-section grids)
  colGap?: number;
  rowGap?: number;
}


// DataSource 配置
export interface DataSourceConfig {
  id?: string;
  type?: 'api' | 'static' | 'namedQuery'; // 'namedQuery' delegates to nq:{queryCode} format
  endpoint?: string; // Default: '/api/datasource/list'
  method?: 'get' | 'post' | 'put' | 'delete'; // Default: 'get'
  params?: string | Record<string, any>;
  body?: string | Record<string, any>;
  autoFetch?: boolean; // Default: true
  pagination?: boolean;
  adaptor?: string; // Default: 'optionList'
  valueField?: string; // Default: 'value'
  labelField?: string; // Default: 'name'
  data?: any[]; // For static type only
  dependOn?: string[];
  // NamedQuery-specific fields (when type = 'namedQuery')
  queryCode?: string; // Named query code
  searchField?: string; // Field for keyword search
  maxItems?: number; // Max items to return (default: 200)
}

// Handler 配置
export interface HandlerConfig {
  type: 'flow' | 'builtin' | 'script';
  name?: string;
  steps?: FlowStep[];
  code?: string;
}

export interface FlowStep {
  id?: string;
  type?: 'if' | 'loop' | 'action';
  action?: string;
  target?: string;
  args?: Record<string, any>;
  condition?: string;
  trueNext?: string;
  falseNext?: string;
  next?: string;
  method?: string;
  endpoint?: string;
  body?: any;
  params?: any;
  level?: 'success' | 'error' | 'warning' | 'info';
  content?: string;
  payload?: any;
  channel?: string;
  /** Request body template (supports variable interpolation) */
  bodyTemplate?: Record<string, unknown>;
  /** Success message to display after step execution */
  successMessage?: string;
}

// Field 配置（用于表单）
export interface FieldConfig {
  field: string;
  label?: string | LocalizedText;
  component?: string; // optional: inferred from dataType
  /** Field data type hint (e.g. 'number', 'text', 'date') */
  type?: string;
  readOnly?: boolean;
  required?: boolean;
  /** Inline options for select/radio components */
  options?: Array<{ value: string; label: any }>;
  layout?: BlockLayoutConfig;
  props?: Record<string, any>;
  validation?: ValidationRule[];
  dataSource?: string | DataSourceConfig; // 支持直接引用 dataSource ID
  dependOn?: string[];
  optionsWhen?: string;
  visibleWhen?: string;
  enableWhen?: string;
  disableWhen?: string;
  readOnlyWhen?: string;
  valueWhen?: string;
  onChangeSource?: string; // 监听哪个字段的变化
  autoFetch?: boolean; // 是否自动加载数据
  events?: Record<string, EventConfig>;
  span?: number;
  dictCode?: string; // 绑定的字典编码，自动使用 SmartSelect 并加载字典数据
  /** Grid column span for this field (shorthand for layout.colSpan) */
  colSpan?: number;
}

export interface ValidationRule {
  type: string;
  message: string | LocalizedText;
  pattern?: string;
  min?: number;
  max?: number;
}

// Event 配置
export interface EventConfig {
  handler: string;
  args?: Record<string, any>;
}

// Column 配置（用于列表）
export interface ColumnConfig {
  field: string;
  label?: string | LocalizedText; // optional: resolved by i18n Resolver from model displayName

  // Layout
  align?: 'left' | 'center' | 'right';
  width?: number | string;
  ellipsis?: boolean;
  fixed?: 'left' | 'right'; // sticky column

  // Format
  format?: string; // 'currency' | 'percent' | 'number'
  readOnly?: boolean;

  // 排序
  sortable?: boolean;
  sorter?: 'string' | 'number' | string;
  sorterKey?: string;

  // 筛选
  filters?: Array<{ text: string; value: any }>;
  filterMultiple?: boolean;

  // 值类型
  valueType?:
    | 'text'
    | 'boolean'
    | 'date'
    | 'datetime'
    | 'time'
    | 'currency'
    | 'tag'
    | 'progress'
    | 'image'
    | 'user_identity'
    | 'reference'
    | 'button'
    | 'url'
    | 'email'
    | 'color'
    | 'link';

  /**
   * Enable inline editing for this column.
   * [semantic requiresCommand:update]
   * [semantic incompatibleFieldRoles:status]
   */
  editable?: boolean;

  // Required marker (for sub-table add form validation)
  required?: boolean;

  // 自定义渲染
  render?: string;

  // 操作列
  isActionColumn?: boolean;
  buttons?: ButtonConfig[];

  // Tag map for valueType: 'tag' — maps raw value to { label, color }
  tagMap?: Record<string, { label: string; color: string }>;

  // 字典配置
  dictCode?: string; // 绑定的字典编码，自动显示字典标签而非原始值

  // Currency code for valueType: 'currency' (default: 'cny')
  currencyCode?: string;

  // Inline validation rules for sub-table columns
  validation?: ValidationRule[];

  // Min/max numeric constraints (shorthand for validation)
  min?: number;
  max?: number;
  pattern?: string;

  /** Visual render type hint (e.g. 'TAG', 'PROGRESS', 'BOOLEAN') */
  renderType?: string;
  /** Alternative render mode (e.g. 'tag') */
  renderAs?: string;
  /** Tag rendering configuration (boolean tag: trueLabel/falseLabel + colors) */
  tagConfig?: Record<string, unknown>;
}

// Action definition — unified button behavior
export type ActionDef =
  | { type: 'command'; command: string }
  | { type: 'state_transition'; command: string }
  | { type: 'navigate'; to: string; command?: string }
  | { type: 'builtin'; name: string }
  | { type: 'flow'; steps: FlowStep[] }
  | { type: 'flow'; handler: string };

// Button 配置
export interface ButtonConfig {
  code: string;
  /** @deprecated Use `label` for i18n display text. Kept for backward compatibility. */
  action?: string | ActionDef; // Legacy: i18n key string. New: ActionDef object.
  content?: string | LocalizedText;
  label?: string | LocalizedText;
  primary?: boolean;
  danger?: boolean;
  variant?: 'default' | 'primary' | 'danger';
  icon?: string;
  visibleWhen?: string;
  enableWhen?: string;
  disableWhen?: string;
  disabled?: boolean;
  /** @deprecated Use `action: { type: "flow", handler }` instead. */
  handler?: string;
  /** @deprecated Use `action: { type: "flow", handler }` for onClick handlers. */
  events?: Record<string, EventConfig>;

  // Unified action definition (new DSL format)
  /** Confirmation prompt i18n key — shown before executing the action. */
  confirm?: string;

  // Business shortcut properties (legacy — migrate to action: ActionDef)
  /** @deprecated Use `action: { type: "command", command }` instead. */
  commandCode?: string;
  /** @deprecated Use `action: { type: "navigate", to }` instead. */
  navigateTo?: string;
  /** @deprecated Use `confirm` instead. */
  confirmMessageKey?: string;
  permissionCode?: string; // Permission control
  /** Custom report code (for report generation buttons) */
  reportCode?: string;

  /** @deprecated Use `action: { type: "flow", steps: [{ action: "api.request", ... }] }` instead. */
  apiAction?: {
    endpoint: string; // URL template, supports {pid} placeholder
    method?: 'post' | 'put' | 'delete' | 'patch'; // defaults to POST
    successMessage?: string | LocalizedText;
  };
}

// Table 配置
export interface TableConfig {
  rowKey: string;
  dataSource: string;
  pagination?: PaginationConfig;
  selection?: SelectionConfig;
  columns: ColumnConfig[];
  /** Row-level action buttons (displayed in each row) */
  rowActions?: ButtonConfig[];
  /** Tree configuration — enables hierarchical expandable rows */
  treeConfig?: TreeConfig;
}

export interface PaginationConfig {
  pageSize?: number;
  pageSizeOptions?: number[];
  showTotal?: boolean;
  showSizeChanger?: boolean;
  showQuickJumper?: boolean;
}

export interface SelectionConfig {
  mode: 'single' | 'multiple';
  bind: string;
}

// List Tabs filter configuration
export interface ListTabConfig {
  key: string;
  label: string | LocalizedText;
  filter: TabFilterExpression | null;
  /** Detail tab blocks (present only when tab is a DetailTabConfig in a union context) */
  blocks?: BlockConfig[];
}

export interface TabFilterExpression {
  field: string;
  operator: 'EQ' | 'NE' | 'IN' | 'not_in';
  value: any;
}

// Default sort configuration
export interface DefaultSortConfig {
  field: string;
  order: 'asc' | 'desc';
}

// Summary/aggregation configuration
export interface SummaryConfig {
  position?: 'top' | 'bottom';
  fields: SummaryFieldConfig[];
}

export interface SummaryFieldConfig {
  field: string;
  aggregation: 'sum' | 'avg' | 'count' | 'min' | 'max';
  label?: string | LocalizedText;
}

// Sub-table configuration (for master-detail forms)
export interface SubTableConfig {
  childModel: string;
  parentField: string;
  readOnly?: boolean;
  editableWhen?: string;
  columns: ColumnConfig[];
  actions?: ButtonConfig[];
  summary?: SummaryConfig;
  resolveVia?: ResolveViaConfig;
  addCommandCode?: string;
  /** Custom add button configuration */
  addButton?: ButtonConfig | boolean;
  /** Default sort for sub-table rows */
  defaultSort?: DefaultSortConfig;
  commands?: {
    create?: string;
    delete?: string;
    update?: string;
  };
  /** Enable DnD drag sorting */
  sortable?: boolean;
  /** Sort order field name (default: 'sort_order') */
  sortField?: string;
  /** Tree configuration — enables hierarchical display + drag reparent */
  treeConfig?: TreeConfig;
  /**
   * Enable inline editing of existing rows (default: true when commands.update exists).
   * [semantic requiresCommand:update]
   */
  allowInlineEdit?: boolean;
  /** If specified, only these column fields are editable inline */
  editableColumns?: string[];
  /** Enable row-level validation on inline edit (default: false) */
  rowValidation?: boolean;
  /**
   * Cross-row validation rules (e.g., total amount <= budget).
   * [semantic requiresInlineEdit:true]
   */
  crossRowRules?: CrossRowValidationRule[];
}

/** Cross-row validation rule for sub-tables */
export interface CrossRowValidationRule {
  /** Unique identifier */
  id: string;
  /** Field to aggregate */
  field: string;
  /** Aggregation function */
  aggregation: 'sum' | 'avg' | 'count' | 'min' | 'max';
  /** Comparison operator */
  operator: 'lte' | 'gte' | 'LT' | 'GT' | 'EQ';
  /** Threshold value or field reference from parent record (e.g., "${record.budget}") */
  value: number | string;
  /** Error message (i18n key or LocalizedText) */
  message: string | LocalizedText;
}

export interface TreeConfig {
  /** Self-referencing field (e.g., 'parent_id') */
  parentField: string;
  /** Max nesting depth (default: 5) */
  maxDepth?: number;
  /** Initially expand all nodes (default: true) */
  defaultExpanded?: boolean;
}

export interface ResolveViaConfig {
  model: string;
  parentField: string;
  filterField: string;
  filterValue: string;
  /** Intermediate (junction) model code */
  intermediateModel?: string;
  /** Foreign key on intermediate model pointing to parent */
  intermediateParentField?: string;
  /** Additional filter condition on intermediate model */
  filterCondition?: {
    field: string;
    operator: string;
    value: unknown;
  };
}

export interface MonthlyGridMetricConfig {
  field: string;
  label?: string | LocalizedText;
}

export interface MonthlyGridConfig {
  parentModel: string;
  parentField: string;
  parentDisplayField?: string;
  parentSortField?: string;
  childModel: string;
  childParentField: string;
  monthField?: string;
  metrics: MonthlyGridMetricConfig[];
  resolveVia?: {
    intermediateModel: string;
    intermediateParentField: string;
    filterCondition?: {
      field: string;
      operator: string;
      value: any;
    };
  };
  editableWhen?: string;
  summary?: SummaryConfig;
}

// Detail tabs configuration (for detail page tab navigation)
export interface DetailTabConfig {
  key: string;
  label: string | LocalizedText;
  blocks: BlockConfig[];
  system?: boolean;
  /** List tab filter (present only when tab is a ListTabConfig in a union context) */
  filter?: TabFilterExpression | null;
}

// Page-level data source configuration (for namedQuery-backed pages)
export interface PageDataSourceConfig {
  /** Data source type: "table" (default), "namedQuery", or "api" */
  type: 'table' | 'namedQuery' | 'api';
  /** Named query code (when type = "namedQuery") */
  queryCode?: string;
  /** Optional version (null = latest) */
  version?: number | null;
  /** API endpoint (when type = "api") */
  endpoint?: string;
  /** HTTP method (when type = "api", default: "get") */
  method?: 'get' | 'post';
  /** Whether the API supports pagination (default: true) */
  pagination?: boolean;
}

// 统一 Schema 接口
export interface UnifiedSchema {
  kind: 'page' | 'list' | 'form' | 'detail' | 'page_layout';
  version: string;
  /** DSL schema format version (single integer, default 1). */
  schemaVersion?: number;
  id: string;
  title: string | LocalizedText;
  description?: string | LocalizedText;

  /** DSL Profile — "admin" | "storefront" | "portal" (default: "admin") */
  profile?: string;

  // Model Code (e.g., "store", "device")
  modelCode?: string;

  // Model Category (DOCUMENT, MASTER, TRANSACTION, REFERENCE, ENTITY, ACTIVITY)
  modelCategory?: string;

  // Page-level data source (overrides default model table query)
  dataSource?: PageDataSourceConfig;

  // State Binding
  stateBinding?: Record<string, string>;

  // Layout
  layout: LayoutConfig;
  blocks: BlockConfig[];

  // Field-level Data Sources
  dataSources?: Record<string, DataSourceConfig>;

  // Event Handlers
  handlers?: Record<string, HandlerConfig>;

  // Events
  events?: Record<string, EventConfig>;

  // Theme
  theme?: ThemeConfig;

  // Component Registry
  components?: Record<string, string>;

  // Initial State
  state?: Record<string, any>;

  // Linkage Rules
  linkageRules?: LinkageRule[];

  // Cross-field validation rules (model-level baseline)
  rules?: import('~/framework/meta/validation/crossFieldRuleTypes').CrossFieldRule[];

  // Command-level rule overrides (loaded per command)
  ruleOverrides?: import('~/framework/meta/validation/crossFieldRuleTypes').RuleOverride[];

  // Multi-view support — when true, show view type tabs (Table/Kanban/Calendar/etc.)
  // Default: false (only table view, no view switcher)
  enableMultiView?: boolean;

  // Page-level extension properties (e.g., showShare, showReport for detail pages)
  extension?: Record<string, any>;
}

export interface ThemeConfig {
  tokens?: Record<string, string>;
}

// 表单专用 Schema
export interface FormSchema extends UnifiedSchema {
  kind: 'form';
}

// 列表专用 Schema
export interface ListSchema extends UnifiedSchema {
  kind: 'list';
}

// 页面专用 Schema
export interface PageSchema extends UnifiedSchema {
  kind: 'page';
}

// 详情专用 Schema
export interface DetailSchema extends UnifiedSchema {
  kind: 'detail';
}

// 页面布局专用 Schema
export interface PageLayoutSchema extends UnifiedSchema {
  kind: 'page_layout';
}
