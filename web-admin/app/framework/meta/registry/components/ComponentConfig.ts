/**
 * 组件配置接口定义
 * 用于统一管理Smart组件的配置信息
 */

export interface PropertySchema {
  key: string;
  label: string;
  type:
    | 'string'
    | 'number'
    | 'boolean'
    | 'select'
    | 'array'
    | 'object'
    | 'color'
    | 'date'
    | 'formref-select'
    | 'component-select'
    | 'datasource-select'
    | 'model-select'
    | 'field-select';
  defaultValue?: any;
  options?: Array<{ label: string; value: any }>;
  required?: boolean;
  description?: string;
  group?:
    | 'basic'
    | 'validation'
    | 'appearance'
    | 'behavior'
    | 'advanced'
    | 'size'
    | 'spacing'
    | 'layout'
    | 'dataSource';
  min?: number;
  max?: number;
  pattern?: string;
  validation?: ValidationRule[];
}

export interface ValidationRule {
  type: 'required' | 'minLength' | 'maxLength' | 'pattern' | 'custom' | 'min' | 'max';
  value?: any;
  message: string;
}

export interface ComponentConfig {
  type: string;
  name: string;
  category: 'form' | 'display' | 'interaction' | 'layout' | 'datetime' | 'chart';
  icon: string;
  description: string;
  defaultProps: Record<string, any>;
  propertySchema: PropertySchema[];
  validation?: ValidationRule[];
  dependencies?: string[];
  tags?: string[];
  /** When set, restricts this component to the listed profiles only. Empty/undefined = available in all profiles. */
  profiles?: string[];
  version?: string;
  runtime?: ComponentRuntimeConfig;
}

export interface ComponentCategory {
  id: string;
  name: string;
  icon: string;
  description: string;
  order: number;
}

export const COMPONENT_CATEGORIES: ComponentCategory[] = [
  {
    id: 'form',
    name: '表单组件',
    icon: '📝',
    description: '用于数据输入和表单构建的组件',
    order: 1,
  },
  {
    id: 'display',
    name: '展示组件',
    icon: '📊',
    description: '用于数据展示和信息呈现的组件',
    order: 2,
  },
  {
    id: 'interaction',
    name: '交互组件',
    icon: '🎯',
    description: '用于用户交互和操作的组件',
    order: 3,
  },
  {
    id: 'layout',
    name: '布局组件',
    icon: '📐',
    description: '用于页面布局和结构组织的组件',
    order: 4,
  },
  {
    id: 'datetime',
    name: '日期时间',
    icon: '📅',
    description: '用于日期时间选择和显示的组件',
    order: 5,
  },
  {
    id: 'chart',
    name: '图表组件',
    icon: '📈',
    description: '用于数据可视化和图表展示的组件',
    order: 6,
  },
];

export interface ComponentRuntimeConfig {
  modulePath: string;
  exportName?: string;
  componentName?: string;
  aliases?: string[];
}
