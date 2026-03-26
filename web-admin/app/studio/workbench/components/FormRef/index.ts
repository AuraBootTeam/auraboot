/**
 * FormRef 契约系统入口文件
 *
 * 提供独立表单的引用和嵌入功能
 */

import type {
  FormSchema,
  FormFieldConfig,
  FormRefConfig,
  FormRefManager,
} from '~/studio/workbench/components/FormRef/types';
import { FormRefManagerImpl } from '~/studio/workbench/components/FormRef/FormRefManager';

// 核心类型
export type {
  FormRefMode,
  FormRefStatus,
  FormDataSource,
  FormSubmitConfig,
  FormValidationRule,
  FormFieldConfig,
  FormLayoutConfig,
  FormSchema,
  FormRefProps,
  FormRefState,
  FormRefManager,
  FormRefContext,
  FormFieldRenderer as FormFieldRendererType,
  FormRefEvents,
  FormRefConfig,
} from '~/studio/workbench/components/FormRef/types';

// 核心管理器
export {
  FormRefManagerImpl,
  DEFAULT_FORM_REF_CONFIG,
} from '~/studio/workbench/components/FormRef/FormRefManager';

// React 组件
export { FormRef } from '~/studio/workbench/components/FormRef/FormRef';
export { FormFieldRenderer } from '~/studio/workbench/components/FormRef/FormFieldRenderer';

// 工具函数和常量
export const FORM_REF_CONSTANTS = {
  // 表单引用模式
  MODES: {
    POINTER: 'pointer' as const,
    SNAPSHOT: 'snapshot' as const,
  },

  // 表单状态
  STATUS: {
    IDLE: 'idle' as const,
    LOADING: 'loading' as const,
    READY: 'ready' as const,
    ERROR: 'error' as const,
    SUBMITTING: 'submitting' as const,
  },

  // 数据源类型
  DATA_SOURCES: {
    API: 'api' as const,
    STATIC: 'static' as const,
    CONTEXT: 'context' as const,
  },

  // 字段类型
  FIELD_TYPES: {
    TEXT: 'text' as const,
    EMAIL: 'email' as const,
    PASSWORD: 'password' as const,
    URL: 'url' as const,
    NUMBER: 'number' as const,
    TEXTAREA: 'textarea' as const,
    SELECT: 'select' as const,
    CHECKBOX: 'checkbox' as const,
    RADIO: 'radio' as const,
    CHECKBOXES: 'checkboxes' as const,
    DATE: 'date' as const,
    TIME: 'time' as const,
    FILE: 'file' as const,
    FILES: 'files' as const,
    hidden: 'hidden' as const,
  },

  // 验证规则类型
  VALIDATION_TYPES: {
    REQUIRED: 'required' as const,
    MIN_LENGTH: 'minLength' as const,
    MAX_LENGTH: 'maxLength' as const,
    PATTERN: 'pattern' as const,
    EMAIL: 'email' as const,
    URL: 'url' as const,
    NUMBER: 'number' as const,
    MIN: 'min' as const,
    MAX: 'max' as const,
    CUSTOM: 'custom' as const,
  },

  // 布局类型
  LAYOUT_TYPES: {
    VERTICAL: 'vertical' as const,
    HORIZONTAL: 'horizontal' as const,
    INLINE: 'inline' as const,
    GRID: 'grid' as const,
  },

  // 事件类型
  EVENTS: {
    FORM_LOADED: 'formLoaded' as const,
    FORM_CHANGED: 'formChanged' as const,
    FORM_SUBMITTED: 'formSubmitted' as const,
    FORM_RESET: 'formReset' as const,
    FIELD_CHANGED: 'fieldChanged' as const,
    VALIDATION_CHANGED: 'validationChanged' as const,
    STATUS_CHANGED: 'statusChanged' as const,
  },

  // 默认配置
  DEFAULTS: {
    CACHE_TTL: 5 * 60 * 1000, // 5分钟
    DEBOUNCE_DELAY: 300, // 300ms
    MAX_CACHE_SIZE: 100,
    VALIDATION_DEBOUNCE: 500, // 500ms
    SUBMIT_TIMEOUT: 30000, // 30秒
  },
};

/**
 * FormRef 工具函数
 */
export const FormRefUtils = {
  /**
   * 创建默认的表单配置
   */
  createDefaultFormSchema: (id: string, title: string): FormSchema => ({
    id,
    name: title,
    title,
    description: '',
    version: '1.0.0',
    fields: [],
    layout: {
      type: 'vertical',
      columns: 1,
      spacing: 'medium',
      labelPosition: 'left',
    },
    validation: [],
    submitConfig: {
      endpoint: '',
      method: 'post',
    },
  }),

  /**
   * 创建默认的字段配置
   */
  createDefaultFieldConfig: (name: string, type: string = 'text'): FormFieldConfig => ({
    name,
    type: type as any,
    label: name,
    placeholder: `请输入${name}`,
    required: false,
    disabled: false,
    readonly: false,
    validation: [],
  }),

  /**
   * 验证表单数据
   */
  validateFormData: (data: Record<string, any>, schema: FormSchema): Record<string, string[]> => {
    const errors: Record<string, string[]> = {};

    schema.fields.forEach((field) => {
      const value = data[field.name];
      const fieldErrors: string[] = [];

      // 检查必填字段
      if (field.required && (value === undefined || value === null || value === '')) {
        fieldErrors.push(`${field.label || field.name}是必填项`);
      }

      // 执行验证规则
      if (value !== undefined && value !== null && value !== '') {
        field.validation?.forEach((rule) => {
          const ruleValue = (rule as any).value ?? (rule as any).params;
          switch (rule.type) {
            case 'minLength':
              if (typeof value === 'string' && value.length < (ruleValue as number)) {
                fieldErrors.push(
                  rule.message || `${field.label || field.name}长度不能少于${ruleValue}个字符`,
                );
              }
              break;
            case 'maxLength':
              if (typeof value === 'string' && value.length > (ruleValue as number)) {
                fieldErrors.push(
                  rule.message || `${field.label || field.name}长度不能超过${ruleValue}个字符`,
                );
              }
              break;
            case 'pattern':
              if (typeof value === 'string' && !(ruleValue as RegExp).test(value)) {
                fieldErrors.push(rule.message || `${field.label || field.name}格式不正确`);
              }
              break;
            case 'email':
              if (typeof value === 'string' && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)) {
                fieldErrors.push(rule.message || `${field.label || field.name}邮箱格式不正确`);
              }
              break;
            case 'url':
              if (typeof value === 'string') {
                try {
                  new URL(value);
                } catch {
                  fieldErrors.push(rule.message || `${field.label || field.name}URL格式不正确`);
                }
              }
              break;
            case 'number':
              if (isNaN(Number(value))) {
                fieldErrors.push(rule.message || `${field.label || field.name}必须是数字`);
              }
              break;
            case 'min':
              if (Number(value) < (ruleValue as number)) {
                fieldErrors.push(
                  rule.message || `${field.label || field.name}不能小于${ruleValue}`,
                );
              }
              break;
            case 'max':
              if (Number(value) > (ruleValue as number)) {
                fieldErrors.push(
                  rule.message || `${field.label || field.name}不能大于${ruleValue}`,
                );
              }
              break;
            case 'custom':
              if (rule.validator && !rule.validator(value)) {
                fieldErrors.push(rule.message || `${field.label || field.name}验证失败`);
              }
              break;
          }
        });
      }

      if (fieldErrors.length > 0) {
        errors[field.name] = fieldErrors;
      }
    });

    return errors;
  },

  /**
   * 转换表单数据
   */
  transformFormData: (data: Record<string, any>, schema: FormSchema): Record<string, any> => {
    const transformed: Record<string, any> = {};

    schema.fields.forEach((field) => {
      const value = data[field.name];

      if (value !== undefined) {
        switch (field.type) {
          case 'number':
            transformed[field.name] = value === '' ? null : Number(value);
            break;
          case 'checkbox':
            transformed[field.name] = Boolean(value);
            break;
          case 'checkboxes':
            transformed[field.name] = Array.isArray(value) ? value : [];
            break;
          case 'date':
            transformed[field.name] = value ? new Date(value) : null;
            break;
          default:
            transformed[field.name] = value;
        }
      }
    });

    return transformed;
  },

  /**
   * 克隆表单配置
   */
  cloneFormSchema: (schema: FormSchema): FormSchema => {
    return JSON.parse(JSON.stringify(schema));
  },

  /**
   * 合并表单配置
   */
  mergeFormSchema: (base: FormSchema, override: Partial<FormSchema>): FormSchema => {
    return {
      ...base,
      ...override,
      fields: override.fields || base.fields,
      layout: override.layout ? { ...base.layout, ...override.layout } : base.layout,
      validation: override.validation ?? base.validation,
      submitConfig: override.submitConfig
        ? { ...base.submitConfig, ...override.submitConfig }
        : base.submitConfig,
    };
  },

  /**
   * 生成表单快照
   */
  createFormSnapshot: (schema: FormSchema, data: Record<string, any>): string => {
    return JSON.stringify({
      schema,
      data,
      timestamp: Date.now(),
    });
  },

  /**
   * 恢复表单快照
   */
  restoreFormSnapshot: (
    snapshot: string,
  ): { schema: FormSchema; data: Record<string, any>; timestamp: number } | null => {
    try {
      return JSON.parse(snapshot);
    } catch {
      return null;
    }
  },
};

/**
 * 创建 FormRef 管理器实例
 */
export const createFormRefManager = (config?: Partial<FormRefConfig>): FormRefManager => {
  return new FormRefManagerImpl(config);
};

/**
 * FormRef 契约系统版本
 */
export const FORM_REF_VERSION = '1.0.0';
