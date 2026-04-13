/**
 * DataSource Types
 *
 * Type definitions for data source configuration.
 */

/**
 * DataSource type
 */
export type DataSourceType = 'api' | 'static' | 'expression';

/**
 * HTTP method for API data source
 */
export type HttpMethod = 'get' | 'post' | 'put' | 'delete';

/**
 * Parameter value type
 */
export type ParamValueType = 'static' | 'expression';

/**
 * Parameter value configuration
 */
export interface ParamValue {
  type: ParamValueType;
  value: string;
}

/**
 * API data source configuration
 */
export interface ApiDataSourceConfig {
  /** API endpoint path */
  endpoint: string;
  /** HTTP method */
  method: HttpMethod;
  /** Request headers */
  headers?: Record<string, ParamValue>;
  /** Query parameters (for GET) */
  params?: Record<string, ParamValue>;
  /** Request body (for POST/PUT) */
  body?: Record<string, ParamValue>;
  /** Response data path (e.g., "data.items") */
  responsePath?: string;
}

/**
 * Static data source configuration
 */
export interface StaticDataSourceConfig {
  /** Static data array */
  data: any[];
}

/**
 * Expression data source configuration
 */
export interface ExpressionDataSourceConfig {
  /** Expression to evaluate */
  expression: string;
  /** Variables that this expression depends on */
  dependencies: string[];
}

/**
 * Response field mapping configuration
 */
export interface DataSourceMapping {
  /** Field to use as value */
  valueField?: string;
  /** Field to use as display label */
  labelField?: string;
  /** Field for children (tree data) */
  childrenField?: string;
  /** Field for disabled state */
  disabledField?: string;
  /** Transform expression applied after mapping */
  transform?: string;
}

/**
 * Cache configuration
 */
export interface DataSourceCache {
  /** Enable caching */
  enabled: boolean;
  /** Time-to-live in milliseconds */
  ttl: number;
  /** Cache key (defaults to endpoint + params hash) */
  key?: string;
}

/**
 * Complete data source configuration
 */
export interface DataSourceConfig {
  /** Unique identifier */
  id: string;
  /** Display name */
  name?: string;
  /** Data source type */
  type: DataSourceType;
  /** API configuration (when type is 'api') */
  api?: ApiDataSourceConfig;
  /** Static data (when type is 'static') */
  static?: StaticDataSourceConfig;
  /** Expression configuration (when type is 'expression') */
  expression?: ExpressionDataSourceConfig;
  /** Response field mapping */
  mapping?: DataSourceMapping;
  /** Cache configuration */
  cache?: DataSourceCache;
}

/**
 * Data source test request
 */
export interface DataSourceTestRequest {
  config: DataSourceConfig;
  /** Mock context for expression evaluation */
  context?: Record<string, any>;
}

/**
 * Data source test result
 */
export interface DataSourceTestResult {
  success: boolean;
  /** HTTP status code (for API) */
  status?: number;
  /** Response data */
  data?: any[];
  /** Mapped data (after applying mapping) */
  mappedData?: { value: any; label: string }[];
  /** Error message */
  error?: string;
  /** Execution time in ms */
  duration?: number;
}

/**
 * DataSource panel props
 */
export interface DataSourcePanelProps {
  /** Current configuration */
  value?: DataSourceConfig;
  /** Change handler */
  onChange: (config: DataSourceConfig) => void;
  /** Component context for expression variables */
  context?: Record<string, any>;
  /** Panel title */
  title?: string;
}

/**
 * DataSource editor props (for specific type editors)
 */
export interface DataSourceEditorProps<T> {
  value: T;
  onChange: (value: T) => void;
  context?: Record<string, any>;
}
