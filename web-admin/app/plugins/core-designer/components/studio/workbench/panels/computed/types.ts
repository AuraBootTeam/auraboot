/**
 * Computed Field Types
 *
 * Type definitions for computed field editing.
 *
 * @since 3.2.0
 */

/**
 * Computed field type
 */
export type ComputedFieldType = 'computed_readonly' | 'materialized' | 'transient';

/**
 * Computed field definition
 */
export interface ComputedFieldDefinition {
  /** Field code */
  code: string;
  /** Field label */
  label: string;
  /** Expression */
  expression: string;
  /** Computed type */
  virtualType: ComputedFieldType;
  /** Return type */
  returnType: string;
  /** Field dependencies (parsed from expression) */
  dependencies?: string[];
  /** Description */
  description?: string;
  /** Whether field is enabled */
  enabled?: boolean;
}

/**
 * Computed field type metadata
 */
export interface ComputedTypeInfo {
  type: ComputedFieldType;
  label: string;
  description: string;
  icon: string;
  persisted: boolean;
}

/**
 * Expression validation result
 */
export interface ExpressionValidation {
  valid: boolean;
  errors: Array<{
    line?: number;
    column?: number;
    message: string;
  }>;
  warnings: string[];
  dependencies: string[];
}

/**
 * Expression test result
 */
export interface ExpressionTestResult {
  success: boolean;
  result?: unknown;
  error?: string;
  executionTime?: number;
}

/**
 * Available return types
 */
export const RETURN_TYPES = [
  { value: 'string', label: '文本', icon: 'Aa' },
  { value: 'integer', label: '整数', icon: '#' },
  { value: 'decimal', label: '小数', icon: '.0' },
  { value: 'boolean', label: '布尔', icon: '✓' },
  { value: 'date', label: '日期', icon: '📅' },
  { value: 'datetime', label: '日期时间', icon: '🕐' },
];

/**
 * Computed type configurations
 */
export const COMPUTED_TYPES: ComputedTypeInfo[] = [
  {
    type: 'computed_readonly',
    label: '只读计算',
    description: '只读计算字段，每次访问时实时计算，不持久化到数据库',
    icon: '🔢',
    persisted: false,
  },
  {
    type: 'materialized',
    label: '物化计算',
    description: '物化计算字段，依赖变更时重新计算并持久化，适用于搜索和报表',
    icon: '💾',
    persisted: true,
  },
  {
    type: 'transient',
    label: '临时计算',
    description: '临时计算字段，仅在页面会话中有效，不持久化',
    icon: '⚡',
    persisted: false,
  },
];

/**
 * Get computed type info
 */
export function getComputedTypeInfo(type: ComputedFieldType): ComputedTypeInfo {
  return COMPUTED_TYPES.find((t) => t.type === type) || COMPUTED_TYPES[0];
}
