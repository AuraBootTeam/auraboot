/**
 * Dashboard Designer Types
 */

import type { EnhancedGridCellConfig, EnhancedGridConfig } from '~/smart/types/dashboard';
import type { DrillDownConfig } from '~/smart/types/chart';
import type {
  PropertySchema as SharedPropertySchema,
  ValidationResult as SharedValidationResult,
  ValidationError as SharedValidationError,
} from '~/shared/designer';

/**
 * Dashboard scope
 */
export type DashboardScope = 'personal' | 'team' | 'global';

/**
 * Dashboard status
 */
export type DashboardStatus = 'draft' | 'published';

/**
 * Widget type
 */
export type WidgetType =
  | 'smart-number-card'
  | 'smart-bar-chart'
  | 'smart-line-chart'
  | 'smart-pie-chart'
  | 'smart-area-chart'
  | 'smart-funnel-chart'
  | 'smart-scatter-chart'
  | 'smart-radar-chart'
  | 'smart-table-chart'
  | 'smart-gauge-chart'
  | 'smart-progress'
  | 'smart-heatmap-chart'
  | 'smart-treemap-chart'
  | 'smart-map-chart'
  | 'smart-rich-text'
  | 'smart-image'
  | 'smart-iframe'
  | 'smart-countdown'
  | 'smart-leaderboard'
  | 'smart-pareto-chart'
  | 'smart-spc-chart'
  | 'smart-gantt-chart'
  | 'smart-calendar'
  // New widgets
  | 'smart-gallery'
  | 'smart-kanban'
  | 'smart-wordcloud-chart'
  | 'smart-combo-chart'
  | 'smart-nps-chart'
  // Workbench widgets
  | 'smart-inbox'
  | 'smart-recent'
  | 'smart-shortcuts'
  | 'smart-stats-row'
  | 'smart-my-process'
  | 'smart-process-stats'
  | 'smart-pipeline'
  | 'smart-leads'
  | 'smart-activities'
  | 'smart-calendar'
  | 'smart-announcement'
  | 'smart-quick-note'
  | 'smart-stats-card';

/**
 * Data source type for widgets
 */
export type DataSourceType = 'aggregate' | 'namedQuery' | 'static';

/**
 * Metric aggregation type
 */
export type AggregationType = 'count' | 'sum' | 'avg' | 'min' | 'max';

/**
 * Metric configuration
 */
export interface MetricConfig {
  field: string;
  aggregation: AggregationType;
  alias?: string;
}

/**
 * Filter configuration
 */
export interface FilterConfig {
  field: string;
  operator: string;
  value: unknown;
}

/**
 * Data source configuration
 */
export interface DataSourceConfig {
  type: DataSourceType;
  // For aggregate type
  modelCode?: string;
  dimensions?: string[];
  metrics?: MetricConfig[];
  filters?: FilterConfig[];
  // For namedQuery type
  queryCode?: string;
  parameters?: Record<string, unknown>;
  // For static type (inline data, no API fetch)
  staticData?: Record<string, unknown>[];
}

/**
 * Linkage configuration for chart interaction
 */
export interface LinkageConfig {
  /** Whether linkage is enabled */
  enabled?: boolean;
  /** Linkage group ID - widgets in the same group can communicate */
  groupId?: string;
  /** Whether this widget receives filters from other widgets */
  receiveFilter?: boolean;
  /** Whether this widget emits filters when clicked */
  emitFilter?: boolean;
  /** Field to use for emitting filter (dimension field) */
  emitField?: string;
}

/**
 * Style settings for chart visualization
 */
export interface StyleSettings {
  colorTheme?: string;
  showTitle?: boolean;
  showLegend?: boolean;
  legendPosition?: 'top' | 'bottom' | 'left' | 'right';
  showLabel?: boolean;
  labelPosition?: 'inside' | 'outside' | 'top';
  borderRadius?: number;
  backgroundColor?: string;
}

/**
 * Widget configuration
 */
export interface WidgetConfig {
  title: string;
  dataSource: DataSourceConfig;
  visualization?: Record<string, unknown>;
  style?: StyleSettings;
  linkage?: LinkageConfig;
  drillDown?: DrillDownConfig;
  refreshInterval?: number;
}

/**
 * Dashboard widget
 */
export interface Widget extends EnhancedGridCellConfig {
  id: string;
  type: WidgetType;
  config: WidgetConfig;
}

/**
 * Layout configuration
 */
export interface LayoutConfig {
  columns: 12 | 24;
  rowHeight: number;
  gap: number;
  compactType?: 'vertical' | 'horizontal' | null;
}

/**
 * Dashboard definition
 */
export interface Dashboard {
  id?: string;
  pid?: string;
  tenantId?: number;
  code?: string;
  title: string;
  description?: string;
  scope: DashboardScope;
  ownerId?: string;
  teamId?: string;
  layoutConfig: LayoutConfig;
  widgets: Widget[];
  status: DashboardStatus;
  isDefault?: boolean;
  sortOrder?: number;
  extension?: Record<string, unknown>;
  createdAt?: string;
  updatedAt?: string;
  createdBy?: string;
  updatedBy?: string;
}

/**
 * Dashboard create request
 */
export interface DashboardCreateRequest {
  code?: string;
  title: string;
  description?: string;
  scope?: DashboardScope;
  teamId?: string;
  layoutConfig?: LayoutConfig;
  widgets?: Widget[];
  isDefault?: boolean;
  sortOrder?: number;
  extension?: Record<string, unknown>;
}

/**
 * Dashboard update request
 */
export interface DashboardUpdateRequest {
  title?: string;
  description?: string;
  scope?: DashboardScope;
  teamId?: string;
  layoutConfig?: LayoutConfig;
  widgets?: Widget[];
  isDefault?: boolean;
  sortOrder?: number;
  extension?: Record<string, unknown>;
}

/**
 * Dashboard query request
 */
export interface DashboardQueryRequest {
  title?: string;
  scope?: DashboardScope;
  status?: DashboardStatus;
  pageNum?: number;
  pageSize?: number;
}

/**
 * Widget definition for registry
 */
export interface WidgetDefinition {
  type: WidgetType;
  label: string;
  icon: string;
  category: string;
  description?: string;
  defaultConfig: Partial<WidgetConfig>;
  defaultSize: {
    w: number;
    h: number;
    minW?: number;
    minH?: number;
    maxW?: number;
    maxH?: number;
  };
  configSchema?: PropertySchema[];
}

/**
 * Property schema for widget configuration.
 * Dashboard uses plain string labels (no i18n).
 */
export type PropertySchema = SharedPropertySchema<string>;

/**
 * Validation result
 */
export type ValidationResult = SharedValidationResult;

/**
 * Validation error (widgetId is an alias for elementId)
 */
export interface ValidationError extends SharedValidationError {
  widgetId?: string;
}
