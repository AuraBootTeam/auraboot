export type ComparisonOp = '===' | '!==' | '>' | '>=' | '<' | '<=' | 'includes' | '!includes';

export interface ConditionRow {
  id: string;
  field: string;
  operator: ComparisonOp;
  value: string;
}

export interface ConditionGroup {
  operator: 'and' | 'or';
  conditions: ConditionRow[];
}

export type FieldCategory = 'string' | 'number' | 'boolean' | 'array';

export interface FieldOption {
  code: string;
  name: string;
  category: FieldCategory;
  group?: string;
}

export interface OperatorOption {
  value: ComparisonOp;
  label: string;
}

export const OPERATORS_BY_CATEGORY: Record<FieldCategory, OperatorOption[]> = {
  string: [
    { value: '===', label: '等于' },
    { value: '!==', label: '不等于' },
    { value: 'includes', label: '包含' },
    { value: '!includes', label: '不包含' },
  ],
  number: [
    { value: '===', label: '等于' },
    { value: '!==', label: '不等于' },
    { value: '>', label: '大于' },
    { value: '>=', label: '大于等于' },
    { value: '<', label: '小于' },
    { value: '<=', label: '小于等于' },
  ],
  boolean: [
    { value: '===', label: '等于' },
    { value: '!==', label: '不等于' },
  ],
  array: [
    { value: 'includes', label: '包含' },
    { value: '!includes', label: '不包含' },
  ],
};
