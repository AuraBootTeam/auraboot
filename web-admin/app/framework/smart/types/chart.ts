/**
 * Chart Data Types
 *
 * Type definitions for chart data fetching and configuration.
 * These types align with the backend AggregateQuery API.
 */

/**
 * Metric configuration for aggregation queries
 */
export interface MetricConfig {
  /** Field name to aggregate */
  field: string;
  /** Aggregation function */
  aggregation: 'count' | 'count_distinct' | 'sum' | 'avg' | 'max' | 'min';
  /** Optional alias for the result column */
  alias?: string;
}

/**
 * Filter configuration for queries
 */
export interface FilterConfig {
  /** Field name to filter on */
  field: string;
  /** Filter operator */
  operator: 'eq' | 'ne' | 'gt' | 'gte' | 'lt' | 'lte' | 'like' | 'in';
  /** Filter value */
  value: unknown;
  /** Logical operator for combining with other filters */
  logic?: 'and' | 'OR';
}

/**
 * Order by configuration
 */
export interface OrderByConfig {
  /** Field name to order by */
  field: string;
  /** Sort direction */
  direction: 'asc' | 'desc';
}

/**
 * Request payload for aggregate queries
 */
export interface AggregateQueryRequest {
  /** Query type: aggregate for dynamic queries, namedQuery for predefined queries */
  type: 'aggregate' | 'namedQuery';
  /** Model code for aggregate queries */
  modelCode?: string;
  /** Query code for named queries */
  queryCode?: string;
  /** Dimension fields for grouping */
  dimensions?: string[];
  /** Metric configurations */
  metrics?: MetricConfig[];
  /** Filter conditions */
  filters?: FilterConfig[];
  /** Group by fields (usually same as dimensions) */
  groupBy?: string[];
  /** Order by configurations */
  orderBy?: OrderByConfig[];
  /** Maximum number of rows to return */
  limit?: number;
  /** Drill-down filter conditions */
  drillFilters?: FilterConfig[];
  /** Additional parameters for named queries */
  parameters?: Record<string, unknown>;
}

/**
 * Metadata about the query result
 */
export interface QueryMeta {
  /** Dimension field names in the result */
  dimensions: string[];
  /** Metric field names in the result */
  metrics: string[];
  /** Available drill-down path */
  drillPath?: string[];
}

/**
 * Response from aggregate queries
 */
export interface AggregateQueryResponse {
  /** Data rows */
  rows: Record<string, unknown>[];
  /** Summary row (totals, averages, etc.) */
  summary: Record<string, unknown>;
  /** Query metadata */
  meta: QueryMeta;
}

/**
 * Chart data source configuration
 */
export interface ChartDataSource {
  /** Data source type */
  type: 'aggregate' | 'namedQuery' | 'static';
  /** Model code for aggregate queries */
  modelCode?: string;
  /** Query code for named queries */
  queryCode?: string;
  /** Dimension fields */
  dimensions?: string[];
  /** Metric configurations */
  metrics?: MetricConfig[];
  /** Filter conditions */
  filters?: FilterConfig[];
  /** Parameters for named queries */
  parameters?: Record<string, unknown>;
  /** Maximum rows to return */
  limit?: number;
  /** Static data (when type is 'static') */
  staticData?: Record<string, unknown>[];
}

/**
 * Drill-down level configuration
 */
export interface DrillDownLevel {
  /** Level number (0-based) */
  level: number;
  /** Dimension field for this level */
  dimension: string;
  /** Next dimension to drill into */
  nextDimension?: string;
}

/**
 * Drill-down behavior configuration
 */
export interface DrillDownConfig {
  /** Whether drill-down is enabled */
  enabled: boolean;
  /** Drill-down path configuration */
  path?: DrillDownLevel[];
  /** Action to perform on drill-down */
  action: 'filter' | 'navigate' | 'modal' | 'dashboard';
  /** Target page for navigate action */
  targetPage?: string;
  /** Target dashboard for dashboard action */
  targetDashboard?: string;
  /** Parameter mapping for navigation */
  paramMapping?: Record<string, string>;
}

/**
 * Chart linkage configuration for dashboard interactivity
 */
export interface LinkageConfig {
  /** Whether linkage is enabled */
  enabled: boolean;
  /** Whether this chart emits filter events */
  emitFilter?: boolean;
  /** Whether this chart receives filter events */
  receiveFilter?: boolean;
  /** Linkage group ID for coordinating multiple charts */
  groupId?: string;
}

/**
 * Complete chart configuration
 */
export interface ChartConfig {
  /** Unique chart identifier */
  id: string;
  /** Chart type */
  type: 'number' | 'bar' | 'line' | 'pie' | 'table' | 'gauge';
  /** Chart title */
  title: string;
  /** Data source configuration */
  dataSource: ChartDataSource;
  /** Drill-down configuration */
  drillDown?: DrillDownConfig;
  /** Linkage configuration */
  linkage?: LinkageConfig;
  /** Auto-refresh interval in milliseconds */
  refreshInterval?: number;
  /** Chart-specific options (passed to ECharts) */
  chartOptions?: Record<string, unknown>;
}
