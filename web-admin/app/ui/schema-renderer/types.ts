/**
 * Schema Renderer Type Definitions
 *
 * Shared types for the schema-driven page rendering system.
 */

import type { PageSchema } from '~/shared/services/schemaService';
import type { DynamicEntity } from '~/types/dynamic';

/**
 * Localized text - can be a simple string or i18n object
 */
export type LocalizedText = string | Record<string, string>;

/**
 * Filter field definition
 */
export interface FilterField {
  code: string;
  type: 'input' | 'select' | 'dateRange' | string;
  label?: LocalizedText;
  props?: {
    placeholder?: LocalizedText;
    maxLength?: number;
    options?: Array<{
      value: string;
      label: LocalizedText;
    }>;
  };
}

/**
 * Action button definition
 */
export interface ActionDefinition {
  code: string;
  label: LocalizedText;
  type?: 'primary' | 'default';
  icon?: string;
}

/**
 * Table column definition
 */
export interface ColumnDefinition {
  code?: string;
  field?: string;
  dataIndex?: string;
  label: LocalizedText;
  type?: 'date' | 'datetime' | 'boolean' | 'status' | 'link' | 'actions' | string;
  render?: string;
  width?: number | string;
  actions?: ActionDefinition[];
}

/**
 * Region definition - a section of the page
 */
export interface RegionDefinition {
  type: 'filters' | 'action' | 'table' | string;
  fields?: FilterField[];
  actions?: ActionDefinition[];
  columns?: ColumnDefinition[];
}

/**
 * Pagination state
 */
export interface PaginationState {
  current: number;
  pageSize: number;
  total: number;
}

/**
 * Filter values - key-value pairs for filter form
 */
export type FilterValues = Record<string, unknown>;

/**
 * Date range filter value
 */
export interface DateRangeValue {
  start?: string;
  end?: string;
}

/**
 * Props for the main SchemaRenderer component
 */
export interface SchemaRendererProps {
  schema: PageSchema;
  data?: DynamicEntity[];
  loading?: boolean;
  pagination?: PaginationState;
  onSearch?: (filters: FilterValues) => void;
  onPageChange?: (page: number) => void;
  onRowClick?: (record: DynamicEntity) => void;
  onAction?: (action: ActionDefinition) => void;
  onRowAction?: (action: ActionDefinition, record: DynamicEntity) => void;
}

/**
 * Props for filter region renderer
 */
export interface FilterRendererProps {
  region: RegionDefinition;
  filters: FilterValues;
  onFilterChange: (key: string, value: unknown) => void;
  onSearch: () => void;
  onReset: () => void;
  getLocalizedText: (text: LocalizedText, fallback?: string) => string;
}

/**
 * Props for table region renderer
 */
export interface TableRendererProps {
  region: RegionDefinition;
  data: DynamicEntity[];
  loading: boolean;
  schema: PageSchema;
  onRowClick?: (record: DynamicEntity) => void;
  onRowAction?: (action: ActionDefinition, record: DynamicEntity) => void;
  getLocalizedText: (text: LocalizedText, fallback?: string) => string;
}

/**
 * Props for action region renderer
 */
export interface ActionRendererProps {
  region: RegionDefinition;
  title: LocalizedText;
  onAction?: (action: ActionDefinition) => void;
  getLocalizedText: (text: LocalizedText, fallback?: string) => string;
}

/**
 * Props for pagination component
 */
export interface PaginationProps {
  pagination: PaginationState;
  onPageChange: (page: number) => void;
}
