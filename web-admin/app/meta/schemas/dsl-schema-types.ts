/**
 * DSL Schema Generation Entry
 *
 * Re-exports serializable types from ./types with two critical changes:
 * 1. StrictBlockConfig — mirrors BlockConfig WITHOUT [key: string]: any index signature
 * 2. DslSchema — mirrors UnifiedSchema with inlined LinkageRule/CrossFieldRule
 *    (avoids path-alias imports that ts-json-schema-generator cannot resolve)
 *
 * Usage: ts-json-schema-generator --path this-file --type DslSchema
 */

// Re-export all serializable types from types.ts
export type {
  LocalizedText,
  LayoutConfig,
  AreaLayoutConfig,
  BlockLayoutConfig,
  DataSourceConfig,
  HandlerConfig,
  FlowStep,
  FieldConfig,
  ValidationRule,
  EventConfig,
  ColumnConfig,
  ActionDef,
  ButtonConfig,
  TableConfig,
  PaginationConfig,
  SelectionConfig,
  ListTabConfig,
  TabFilterExpression,
  DefaultSortConfig,
  SummaryConfig,
  SummaryFieldConfig,
  SubTableConfig,
  CrossRowValidationRule,
  TreeConfig,
  ResolveViaConfig,
  MonthlyGridMetricConfig,
  MonthlyGridConfig,
  DetailTabConfig,
  PageDataSourceConfig,
  ThemeConfig,
} from './types';

import type {
  LocalizedText,
  LayoutConfig,
  AreaConfig,
  BlockLayoutConfig,
  FieldConfig,
  ButtonConfig,
  TableConfig,
  ColumnConfig,
  ListTabConfig,
  DetailTabConfig,
  SubTableConfig,
  MonthlyGridConfig,
  DefaultSortConfig,
  SummaryConfig,
  DataSourceConfig,
  HandlerConfig,
  EventConfig,
  ThemeConfig,
  PageDataSourceConfig,
} from './types';

// ---------------------------------------------------------------------------
// StrictBlockConfig — all properties from BlockConfig, NO index signature
// ---------------------------------------------------------------------------

export interface StrictBlockConfig {
  id: string;
  blockType: string;
  title?: string | LocalizedText;
  layout?: BlockLayoutConfig;
  visibleWhen?: string;
  className?: string;

  // Form
  fields?: FieldConfig[];
  buttons?: ButtonConfig[];

  // Table
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

  // Layout
  gap?: string | number;

  // Component override
  component?: string;

  // Dashboard blocks — stat cards, charts, embedded lists
  cards?: Array<Record<string, unknown>>;
  chartType?: string;
  chartConfig?: Record<string, unknown>;
  modelCode?: string;
  searchFields?: string[];
  valueField?: string;
  icon?: string;
  color?: string;
  format?: string;
  pageSize?: number;
  filters?: Array<Record<string, unknown>>;
  defaultFilters?: Array<Record<string, unknown>>;
  foreignKey?: string;
  actions?: ButtonConfig[];
  readOnly?: boolean;
  childModel?: string;
  parentField?: string;
  onRowClick?: string;
  detailUrl?: string;
  addCommandCode?: string;
}

// ---------------------------------------------------------------------------
// StrictAreaConfig — uses StrictBlockConfig instead of BlockConfig
// ---------------------------------------------------------------------------

export interface StrictAreaConfig {
  blocks: StrictBlockConfig[];
  visibleWhen?: string;
  className?: string;
}

// ---------------------------------------------------------------------------
// Inlined Linkage types (from ~/studio/workbench/panels/linkage/types)
// ---------------------------------------------------------------------------

export type LinkageTriggerEvent = 'change' | 'blur' | 'focus';

export interface LinkageTrigger {
  fieldCode: string;
  event: LinkageTriggerEvent;
  condition?: string;
}

export interface LinkageDataSourceConfig {
  type: 'dict' | 'api' | 'parent';
  dictCode?: string;
  apiUrl?: string;
  parentFieldCode?: string;
  labelField?: string;
  valueField?: string;
}

export interface LinkageValidationRule {
  type: 'required' | 'minLength' | 'maxLength' | 'pattern' | 'custom';
  value?: string | number;
  message: string;
}

export type LinkageAction =
  | { type: 'show'; targets: string[] }
  | { type: 'hide'; targets: string[] }
  | { type: 'enable'; targets: string[] }
  | { type: 'disable'; targets: string[] }
  | { type: 'setRequired'; targets: string[]; required: boolean }
  | { type: 'setValue'; target: string; value: string }
  | { type: 'setOptions'; target: string; dataSource: LinkageDataSourceConfig }
  | { type: 'validate'; targets: string[]; rules: LinkageValidationRule[] };

export interface LinkageRule {
  id: string;
  name?: string;
  trigger: LinkageTrigger;
  actions: LinkageAction[];
  enabled: boolean;
}

// ---------------------------------------------------------------------------
// Inlined Cross-field validation types (from ~/meta/validation/crossFieldRuleTypes)
// ---------------------------------------------------------------------------

export interface RuleCondition {
  field?: string;
  eq?: unknown;
  neq?: unknown;
  gt?: unknown;
  gte?: unknown;
  lt?: unknown;
  lte?: unknown;
  in?: unknown[];
  notIn?: unknown[];
  expr?: string;
  and?: RuleCondition[];
  or?: RuleCondition[];
  not?: RuleCondition;
}

export interface RuleAssert {
  field?: string;
  eq?: unknown;
  neq?: unknown;
  gt?: unknown;
  gte?: unknown;
  lt?: unknown;
  lte?: unknown;
  in?: unknown[];
  notIn?: unknown[];
  required?: boolean;
  maxLength?: number;
  minLength?: number;
  pattern?: string;
  expr?: string;
}

export interface CrossFieldRule {
  id: string;
  when?: RuleCondition;
  assert: RuleAssert;
  message: string;
  severity?: 'error' | 'warning';
  targetField?: string;
  dependsOn?: string[];
}

export interface RuleOverride extends CrossFieldRule {
  disabled?: boolean;
}

// ---------------------------------------------------------------------------
// DslSchema — mirrors UnifiedSchema with inlined external types
// ---------------------------------------------------------------------------

export interface DslSchema {
  kind: 'Page' | 'List' | 'Form' | 'Detail' | 'PageLayout' | 'Dashboard' | 'Record' | 'Transaction';
  version: string;
  schemaVersion?: number;
  id: string;
  title: string | LocalizedText;
  description?: string | LocalizedText;

  profile?: string;
  modelCode?: string;
  modelCategory?: string;

  dataSource?: PageDataSourceConfig;
  stateBinding?: Record<string, string>;

  layout: LayoutConfig;
  areas: Record<string, StrictAreaConfig>;

  dataSources?: Record<string, DataSourceConfig>;
  handlers?: Record<string, HandlerConfig>;
  events?: Record<string, EventConfig>;

  theme?: ThemeConfig;
  components?: Record<string, string>;
  state?: Record<string, unknown>;

  linkageRules?: LinkageRule[];
  rules?: CrossFieldRule[];
  ruleOverrides?: RuleOverride[];

  enableMultiView?: boolean;

  // Multi-view configuration
  savedViews?: Array<Record<string, unknown>>;
  views?: Array<Record<string, unknown>>;
  kanbanConfig?: Record<string, unknown>;
  calendarConfig?: Record<string, unknown>;
  galleryConfig?: Record<string, unknown>;

  // Record-page shorthand properties
  enableSearch?: boolean;
  enableCreate?: boolean;
  columns?: ColumnConfig[];
  options?: Record<string, unknown>;
  defaultFilters?: Array<Record<string, unknown>>;

  /** Open extension point for custom properties */
  extension?: Record<string, unknown>;
}
