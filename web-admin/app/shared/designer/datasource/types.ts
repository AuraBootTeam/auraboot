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
