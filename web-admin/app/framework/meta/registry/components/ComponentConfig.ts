import type { LocalizedText } from '~/framework/meta/schemas/types';
import { componentText } from './componentText';

export type ComponentText = string | LocalizedText;

/**
 * 组件配置接口定义
 * 用于统一管理Smart组件的配置信息
 */

export interface PropertySchema {
  key: string;
  label: ComponentText;
  type:
    | 'string'
    | 'number'
    | 'boolean'
    | 'select'
    | 'array'
    | 'object'
    | 'icon'
    | 'color'
    | 'date'
    | 'formref-select'
    | 'component-select'
    | 'datasource-select'
    | 'model-select'
    | 'field-select';
  defaultValue?: any;
  options?: Array<{ label: ComponentText; value: any }>;
  required?: boolean;
  description?: ComponentText;
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
  message: ComponentText;
}

export interface ComponentConfig {
  type: string;
  name: ComponentText;
  category: 'form' | 'display' | 'interaction' | 'layout' | 'datetime' | 'chart';
  icon: string;
  description: ComponentText;
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
  name: ComponentText;
  icon: string;
  description: ComponentText;
  order: number;
}

export const COMPONENT_CATEGORIES: ComponentCategory[] = [
  {
    id: 'form',
    name: componentText('category.form'),
    icon: '📝',
    description: componentText('category.formDescription'),
    order: 1,
  },
  {
    id: 'display',
    name: componentText('category.display'),
    icon: '📊',
    description: componentText('category.displayDescription'),
    order: 2,
  },
  {
    id: 'interaction',
    name: componentText('category.interaction'),
    icon: '🎯',
    description: componentText('category.interactionDescription'),
    order: 3,
  },
  {
    id: 'layout',
    name: componentText('category.layout'),
    icon: '📐',
    description: componentText('category.layoutDescription'),
    order: 4,
  },
  {
    id: 'datetime',
    name: componentText('category.datetime'),
    icon: '📅',
    description: componentText('category.datetimeDescription'),
    order: 5,
  },
  {
    id: 'chart',
    name: componentText('category.chart'),
    icon: '📈',
    description: componentText('category.chartDescription'),
    order: 6,
  },
];

export interface ComponentRuntimeConfig {
  modulePath: string;
  exportName?: string;
  componentName?: string;
  aliases?: string[];
}
