/**
 * FormRef 组件类型定义
 *
 * 定义表单引用的核心类型和接口
 */

import type { ComponentState } from '~/studio/services/state/PageStateManager';
import type { Action, ActionContext } from '~/studio/services/runtime/execution/types';

/**
 * 表单引用模式
 */
export type FormRefMode = 'pointer' | 'snapshot';

/**
 * 表单引用状态
 */
export type FormRefStatus = 'loading' | 'loaded' | 'error' | 'not-found';

/**
 * 表单数据源
 */
export interface FormDataSource {
  type: 'api' | 'static' | 'context';
  config: {
    // API 数据源配置
    endpoint?: string;
    method?: 'get' | 'post' | 'put' | 'delete';
    headers?: Record<string, string>;
    params?: Record<string, any>;

    // 静态数据源配置
    data?: any;

    // 上下文数据源配置
    contextPath?: string;
  };
}

/**
 * 表单提交配置
 */
export interface FormSubmitConfig {
  endpoint?: string;
  method?: 'post' | 'put' | 'patch';
  headers?: Record<string, string>;
  transform?: (data: any) => any;
  onSuccess?: (response: any) => void;
  onError?: (error: any) => void;
}

/**
 * 表单验证规则
 */
export interface FormValidationRule {
  field: string;
  rules: Array<{
    type:
      | 'required'
      | 'email'
      | 'phone'
      | 'url'
      | 'number'
      | 'custom'
      | 'minLength'
      | 'maxLength'
      | 'pattern'
      | 'min'
      | 'max';
    message: string;
    validator?: (value: any) => boolean;
    params?: any;
  }>;
}

/**
 * 表单字段配置
 */
export interface FormFieldConfig {
  name: string;
  label: string;
  type: string;
  required?: boolean;
  disabled?: boolean;
  readonly?: boolean;
  placeholder?: string;
  defaultValue?: any;
  options?: Array<{ label: string; value: any }>;
  validation?: FormValidationRule['rules'];
  dependencies?: string[]; // 依赖的其他字段
  conditional?: {
    field: string;
    operator: 'eq' | 'ne' | 'gt' | 'lt' | 'in' | 'nin';
    value: any;
  };

  // 动作系统集成
  actions?: {
    onChange?: Action[]; // 字段值变化时触发的动作
    onFocus?: Action[]; // 字段获得焦点时触发的动作
    onBlur?: Action[]; // 字段失去焦点时触发的动作
    onValidate?: Action[]; // 字段验证时触发的动作
  };
}

/**
 * 表单布局配置
 */
export interface FormLayoutConfig {
  type: 'vertical' | 'horizontal' | 'inline' | 'grid';
  columns?: number;
  spacing?: 'small' | 'medium' | 'large';
  labelPosition?: 'top' | 'left' | 'right';
  labelWidth?: string | number;
}

/**
 * 表单 Schema 定义
 */
export interface FormSchema {
  id: string;
  name: string;
  title: string;
  description?: string;
  version: string;

  // 表单字段
  fields: FormFieldConfig[];

  // 布局配置
  layout: FormLayoutConfig;

  // 数据源配置
  dataSource?: FormDataSource;

  // 提交配置
  submitConfig?: FormSubmitConfig;

  // 验证规则
  validation?: FormValidationRule[];

  // 样式配置
  styles?: {
    container?: React.CSSProperties;
    field?: React.CSSProperties;
    label?: React.CSSProperties;
    input?: React.CSSProperties;
    button?: React.CSSProperties;
  };

  // 动作系统集成
  actions?: {
    onLoad?: Action[]; // 表单加载时触发的动作
    onSubmit?: Action[]; // 表单提交时触发的动作
    onReset?: Action[]; // 表单重置时触发的动作
    onValidate?: Action[]; // 表单验证时触发的动作
    onError?: Action[]; // 表单出错时触发的动作
  };

  // 元数据
  metadata?: {
    createdAt: Date;
    updatedAt: Date;
    createdBy?: string;
    updatedBy?: string;
    tags?: string[];
  };
}

/**
 * FormRef 组件属性
 */
export interface FormRefProps {
  // 基本属性
  id: string;
  className?: string;
  style?: React.CSSProperties;

  // 表单引用配置
  formId: string;
  mode: FormRefMode;

  // 快照模式下的表单 Schema
  snapshot?: FormSchema;

  // 数据绑定
  value?: Record<string, any>;
  onChange?: (data: Record<string, any>) => void;
  onSubmit?: (data: Record<string, any>) => void;

  // 状态控制
  disabled?: boolean;
  readonly?: boolean;
  loading?: boolean;

  // 样式覆盖
  styleOverrides?: {
    container?: React.CSSProperties;
    field?: React.CSSProperties;
    label?: React.CSSProperties;
    input?: React.CSSProperties;
    button?: React.CSSProperties;
  };

  // 字段覆盖
  fieldOverrides?: Record<string, Partial<FormFieldConfig>>;

  // 布局覆盖
  layoutOverride?: Partial<FormLayoutConfig>;

  // 验证覆盖
  validationOverride?: FormValidationRule[];

  // 事件处理
  onFieldChange?: (fieldName: string, value: any) => void;
  onValidationChange?: (isValid: boolean, errors: Record<string, string[]>) => void;
  onLoad?: (schema: FormSchema) => void;
  onError?: (error: Error) => void;

  // 动作系统集成
  actionContext?: ActionContext; // 动作执行上下文
  onActionExecute?: (action: Action, result: any) => void; // 动作执行回调
}

/**
 * FormRef 组件状态
 */
export interface FormRefState extends ComponentState {
  // 表单引用特有状态
  formRef: {
    formId: string;
    mode: FormRefMode;
    status: FormRefStatus;
    schema: FormSchema | null;
    data: Record<string, any>;
    errors: Record<string, string[]>;
    isValid: boolean;
    isDirty: boolean;
    isSubmitting: boolean;
    lastLoadTime?: Date;
    lastSubmitTime?: Date;
  };
}

/**
 * 表单引用管理器接口
 */
export interface FormRefManager {
  // 表单加载
  loadForm(formId: string, mode: FormRefMode): Promise<FormSchema>;

  // 表单缓存
  getCachedForm(formId: string): FormSchema | null;
  setCachedForm(formId: string, schema: FormSchema): void;
  clearCache(formId?: string): void;

  // 表单验证
  validateForm(
    schema: FormSchema,
    data: Record<string, any>,
  ): {
    isValid: boolean;
    errors: Record<string, string[]>;
  };

  // 表单提交
  submitForm(schema: FormSchema, data: Record<string, any>): Promise<any>;

  // 数据转换
  transformData(schema: FormSchema, data: Record<string, any>): Record<string, any>;

  // 事件监听
  on(event: string, callback: Function): void;
  off(event: string, callback: Function): void;
  emit(event: string, ...args: any[]): void;
}

/**
 * 表单引用上下文
 */
export interface FormRefContext {
  manager: FormRefManager;

  // 全局配置
  config: {
    apiBaseUrl: string;
    defaultTimeout: number;
    cacheEnabled: boolean;
    cacheTTL: number;
  };

  // 当前表单状态
  currentForm: FormSchema | null;
  formData: Record<string, any>;

  // 操作方法
  loadForm: (formId: string, mode: FormRefMode) => Promise<void>;
  updateFormData: (data: Record<string, any>) => void;
  submitForm: () => Promise<void>;
  resetForm: () => void;
  validateForm: () => { isValid: boolean; errors: Record<string, string[]> };
}

/**
 * 表单字段渲染器接口
 */
export interface FormFieldRenderer {
  type: string;
  component: React.ComponentType<any>;
  defaultProps?: Record<string, any>;
  validator?: (value: any, config: FormFieldConfig) => string | null;
}

/**
 * 表单事件类型
 */
export interface FormRefEvents {
  'form:load': { formId: string; schema: FormSchema };
  'form:error': { formId: string; error: Error };
  'form:change': { formId: string; data: Record<string, any> };
  'form:submit': { formId: string; data: Record<string, any> };
  'form:validate': { formId: string; isValid: boolean; errors: Record<string, string[]> };
  'form:reset': { formId: string };
}

/**
 * 表单引用配置
 */
export interface FormRefConfig {
  // API 配置
  api: {
    baseUrl: string;
    timeout: number;
    headers?: Record<string, string>;
  };

  // 缓存配置
  cache: {
    enabled: boolean;
    ttl: number;
    maxSize: number;
  };

  // 验证配置
  validation: {
    validateOnChange: boolean;
    validateOnBlur: boolean;
    showErrorsImmediately: boolean;
  };

  // 渲染配置
  rendering: {
    fieldRenderers: Record<string, FormFieldRenderer>;
    defaultFieldRenderer: FormFieldRenderer;
    loadingComponent?: React.ComponentType;
    errorComponent?: React.ComponentType<{ error: Error }>;
  };
}
