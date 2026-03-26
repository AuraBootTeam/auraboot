/**
 * Field Library Panel types.
 *
 * @since 3.1.0
 */

export interface MetaFieldDTO {
  pid: string;
  code: string;
  dataType: string;
  displayName?: string;
  description?: string;
  semanticType?: string;
  virtualType?: string; // COMPUTED_READONLY | MATERIALIZED | TRANSIENT
  computeExpression?: string;
  required?: boolean;
  visible?: boolean;
  editable?: boolean;
  version?: number;
  isCurrent?: boolean;
  status?: string;
  uiSchema?: Record<string, any>;
  ruleSchema?: Record<string, any>;
  feature?: Record<string, any>;
}

export interface FieldSearchRequest {
  keyword?: string;
  baseType?: string;
  semanticType?: string;
  page?: number;
  pageSize?: number;
}

export interface FieldRecommendation {
  field: MetaFieldDTO;
  score: number;
  reason: string;
}

export interface FieldCategoryInfo {
  id: string;
  name: string;
  icon: string;
  count: number;
}

export type VirtualType = 'computed_readonly' | 'materialized' | 'transient';

export interface VirtualBadgeConfig {
  label: string;
  color: string;
  icon: string;
  tooltip: string;
}

export const VIRTUAL_BADGES: Record<VirtualType, VirtualBadgeConfig> = {
  computed_readonly: { label: '计算', color: 'blue', icon: 'fx', tooltip: '只读计算字段' },
  materialized: { label: '物化', color: 'green', icon: 'db', tooltip: '物化计算字段' },
  transient: { label: '临时', color: 'amber', icon: 'zap', tooltip: '临时字段，不持久化' },
};

/**
 * Maps data types to Smart Component types.
 */
export const DATA_TYPE_COMPONENT_MAP: Record<
  string,
  { type: string; defaultProps?: Record<string, any> }
> = {
  STRING: { type: 'input', defaultProps: {} },
  TEXT: { type: 'textarea', defaultProps: {} },
  INTEGER: { type: 'input', defaultProps: { inputType: 'number' } },
  DECIMAL: { type: 'input', defaultProps: { inputType: 'number' } },
  BOOLEAN: { type: 'checkbox', defaultProps: {} },
  DATE: { type: 'date-picker', defaultProps: {} },
  DATETIME: { type: 'date-picker', defaultProps: { showTime: true } },
  ENUM: { type: 'select', defaultProps: {} },
  REFERENCE: { type: 'select', defaultProps: {} },
  JSON: { type: 'textarea', defaultProps: { rows: 6 } },
  EMAIL: { type: 'input', defaultProps: { inputType: 'email' } },
  PHONE: { type: 'input', defaultProps: { inputType: 'tel' } },
  URL: { type: 'input', defaultProps: { inputType: 'url' } },
};

/**
 * Semantic type display names and icons.
 */
export const SEMANTIC_TYPE_INFO: Record<string, { name: string; icon: string }> = {
  identity: { name: '身份信息', icon: '🆔' },
  contact: { name: '联系方式', icon: '📞' },
  address: { name: '地址信息', icon: '📍' },
  financial: { name: '财务信息', icon: '💰' },
  temporal: { name: '时间信息', icon: '🕐' },
  status: { name: '状态信息', icon: '🔄' },
  measurement: { name: '度量信息', icon: '📏' },
  description: { name: '描述信息', icon: '📝' },
  system: { name: '系统字段', icon: '⚙️' },
  other: { name: '其他', icon: '📋' },
};
