/**
 * Filter Preset Types
 *
 * Defines filter conditions and preset structures for saved filters.
 *
 * @since 3.4.0
 */

export type FilterOperator =
  | 'eq'
  | 'neq'
  | 'gt'
  | 'gte'
  | 'lt'
  | 'lte'
  | 'contains'
  | 'startsWith'
  | 'endsWith'
  | 'in'
  | 'notIn'
  | 'between'
  | 'isNull'
  | 'isNotNull';

export interface FilterCondition {
  id: string;
  fieldCode: string;
  operator: FilterOperator;
  value: any;
  valueType: 'static' | 'expression' | 'currentUser' | 'currentDate';
}

export interface FilterPreset {
  id?: number;
  name: string;
  conditions: FilterCondition[];
  logic: 'and' | 'OR';
  isDefault: boolean;
  scope: 'global' | 'personal';
  pageCode?: string;
  modelCode?: string;
  createdAt?: string;
}

export interface FilterPresetCreateRequest {
  pageCode: string;
  modelCode: string;
  name: string;
  conditions: string; // JSON stringified FilterCondition[]
  logic: string;
  isDefault: boolean;
  scope: string;
}

/**
 * Operator display info.
 */
export const OPERATOR_INFO: Record<FilterOperator, { label: string; valueCount: 0 | 1 | 2 }> = {
  eq: { label: '等于', valueCount: 1 },
  neq: { label: '不等于', valueCount: 1 },
  gt: { label: '大于', valueCount: 1 },
  gte: { label: '大于等于', valueCount: 1 },
  lt: { label: '小于', valueCount: 1 },
  lte: { label: '小于等于', valueCount: 1 },
  contains: { label: '包含', valueCount: 1 },
  startsWith: { label: '开头为', valueCount: 1 },
  endsWith: { label: '结尾为', valueCount: 1 },
  in: { label: '在列表中', valueCount: 1 },
  notIn: { label: '不在列表中', valueCount: 1 },
  between: { label: '介于', valueCount: 2 },
  isNull: { label: '为空', valueCount: 0 },
  isNotNull: { label: '不为空', valueCount: 0 },
};

/**
 * Create a default filter condition.
 */
export function createFilterCondition(): FilterCondition {
  return {
    id: `cond-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 5)}`,
    fieldCode: '',
    operator: 'eq',
    value: '',
    valueType: 'static',
  };
}
