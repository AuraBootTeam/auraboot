/**
 * Shared types for data source components used across designers.
 */

export interface ModelOption {
  pid: string;
  code: string;
  name: string;
}

export interface FieldOption {
  code: string;
  name: string;
  fieldType: string;
}

export interface NamedQueryOption {
  pid: string;
  code: string;
  title: string;
}

/**
 * A governed metric declared in a semantic model (PRD 16). Unlike a raw
 * aggregate ({field, aggregation}), a semantic metric is a single named code
 * whose aggregation formula lives in the {@code *.semantic.yml} definition.
 */
export interface SemanticMetricOption {
  code: string;
  name: string;
  /** simple | derived | cumulative — informational only for the picker */
  type?: string;
  description?: string;
}

/**
 * A dimension declared in a semantic model. Time dimensions expose
 * {@code timeGrains} (e.g. day/month/year) that map to the {@code code__grain}
 * suffix the semantic compiler understands.
 */
export interface SemanticDimensionOption {
  code: string;
  name: string;
  type?: string;
  timeGrains?: string[];
  primaryTime?: boolean;
}

export interface FilterCondition {
  field: string;
  operator: string;
  value: string;
}

export interface SortCondition {
  field: string;
  order: 'asc' | 'desc';
}

export const FILTER_OPERATORS = [
  { value: 'eq', label: 'Equals' },
  { value: 'ne', label: 'Not equals' },
  { value: 'gt', label: 'Greater than' },
  { value: 'gte', label: 'Greater or equal' },
  { value: 'lt', label: 'Less than' },
  { value: 'lte', label: 'Less or equal' },
  { value: 'like', label: 'Contains' },
  { value: 'in', label: 'In list' },
  { value: 'isNull', label: 'Is empty' },
  { value: 'isNotNull', label: 'Is not empty' },
] as const;

export const AGGREGATION_FUNCTIONS = [
  { value: 'count', label: 'Count (COUNT)' },
  { value: 'count_distinct', label: 'Distinct Count (COUNT DISTINCT)' },
  { value: 'sum', label: 'Sum (SUM)' },
  { value: 'avg', label: 'Average (AVG)' },
  { value: 'max', label: 'Max (MAX)' },
  { value: 'min', label: 'Min (MIN)' },
] as const;
