/**
 * Named Query constants — aligned with backend NamedQueryFieldRequest.java and NamedQueryServiceImpl.java
 */

/** Valid data types (matches backend @Pattern validation regex) */
export const DATA_TYPES = ['string', 'number', 'date', 'boolean', 'json', 'array'] as const;
export type DataType = (typeof DATA_TYPES)[number];

/** All 16 operators supported by backend buildWhereClause() switch-case */
export const ALL_OPERATORS = [
  'eq',
  'ne',
  'gt',
  'gte',
  'lt',
  'lte',
  'like',
  'ilike',
  'contains',
  'starts_with',
  'ends_with',
  'in',
  'not_in',
  'is_null',
  'is_not_null',
  'between',
] as const;
export type Operator = (typeof ALL_OPERATORS)[number];

/** Chinese labels for operators */
export const OPERATOR_LABELS: Record<string, string> = {
  eq: '等于',
  ne: '不等于',
  gt: '大于',
  gte: '大于等于',
  lt: '小于',
  lte: '小于等于',
  like: '包含',
  ilike: '包含(忽略大小写)',
  contains: '包含',
  starts_with: '开头是',
  ends_with: '结尾是',
  in: '在列表中',
  not_in: '不在列表中',
  is_null: '为空',
  is_not_null: '不为空',
  between: '在范围内',
};

/** Default operators per data type */
export const OPERATORS_BY_TYPE: Record<string, string[]> = {
  STRING: [
    'eq',
    'ne',
    'like',
    'ilike',
    'starts_with',
    'ends_with',
    'in',
    'not_in',
    'is_null',
    'is_not_null',
  ],
  NUMBER: [
    'eq',
    'ne',
    'gt',
    'gte',
    'lt',
    'lte',
    'in',
    'not_in',
    'between',
    'is_null',
    'is_not_null',
  ],
  DATE: ['eq', 'ne', 'gt', 'gte', 'lt', 'lte', 'between', 'is_null', 'is_not_null'],
  BOOLEAN: ['eq', 'ne', 'is_null', 'is_not_null'],
  JSON: ['is_null', 'is_not_null'],
  ARRAY: ['is_null', 'is_not_null'],
};

/** UI component types matching backend uiComponent validation */
export const UI_COMPONENTS = [
  'text',
  'number',
  'numberRange',
  'select',
  'dateRange',
  'date',
  'userPicker',
  'cascader',
  'search',
  'switch',
] as const;
export type UiComponent = (typeof UI_COMPONENTS)[number];
