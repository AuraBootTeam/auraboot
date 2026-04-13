/**
 * 表单引用管理器
 *
 * 负责表单的加载、缓存、验证和提交
 */

import { EventEmitter } from 'events';
import type {
  FormRefManager,
  FormSchema,
  FormRefMode,
  FormRefConfig,
  FormRefEvents,
  FormValidationRule,
  FormDataSource,
  FormSubmitConfig,
} from '~/plugins/core-designer/components/studio/workbench/components/FormRef/types';

/**
 * 表单缓存项
 */
interface FormCacheItem {
  schema: FormSchema;
  timestamp: Date;
  ttl: number;
}

/**
 * 表单引用管理器实现
 */
export class FormRefManagerImpl extends EventEmitter implements FormRefManager {
  private cache = new Map<string, FormCacheItem>();
  private config: FormRefConfig;
  private loadingPromises = new Map<string, Promise<FormSchema>>();
  private cacheCleanupTimer: ReturnType<typeof setInterval> | null = null;

  constructor(config: Partial<FormRefConfig> = DEFAULT_FORM_REF_CONFIG) {
    super();
    this.config = {
      ...DEFAULT_FORM_REF_CONFIG,
      ...config,
      api: { ...DEFAULT_FORM_REF_CONFIG.api, ...(config.api ?? {}) },
      cache: { ...DEFAULT_FORM_REF_CONFIG.cache, ...(config.cache ?? {}) },
      validation: { ...DEFAULT_FORM_REF_CONFIG.validation, ...(config.validation ?? {}) },
      rendering: { ...DEFAULT_FORM_REF_CONFIG.rendering, ...(config.rendering ?? {}) },
    };

    if (this.config.cache.enabled) {
      this.cacheCleanupTimer = setInterval(() => {
        this.cleanExpiredCache();
      }, 60000);
    }
  }

  dispose(): void {
    if (this.cacheCleanupTimer) {
      clearInterval(this.cacheCleanupTimer);
      this.cacheCleanupTimer = null;
    }
    this.cache.clear();
    this.loadingPromises.clear();
    this.removeAllListeners();
  }

  /**
   * 加载表单
   */
  async loadForm(formId: string, mode: FormRefMode): Promise<FormSchema> {
    // 检查缓存
    if (this.config.cache.enabled && mode === 'pointer') {
      const cached = this.getCachedForm(formId);
      if (cached) {
        this.emit('form:load', { formId, schema: cached });
        return cached;
      }
    }

    // 检查是否正在加载
    if (this.loadingPromises.has(formId)) {
      return this.loadingPromises.get(formId)!;
    }

    // 开始加载
    const loadPromise = this.fetchFormSchema(formId, mode);
    this.loadingPromises.set(formId, loadPromise);

    try {
      const schema = await loadPromise;

      // 缓存结果
      if (this.config.cache.enabled && mode === 'pointer') {
        this.setCachedForm(formId, schema);
      }

      this.emit('form:load', { formId, schema });
      return schema;
    } catch (error) {
      this.emit('form:error', { formId, error: error as Error });
      throw error;
    } finally {
      this.loadingPromises.delete(formId);
    }
  }

  /**
   * 获取缓存的表单
   */
  getCachedForm(formId: string): FormSchema | null {
    if (!this.config.cache.enabled) {
      return null;
    }

    const item = this.cache.get(formId);
    if (!item) {
      return null;
    }

    // 检查是否过期
    const now = new Date();
    const expireTime = new Date(item.timestamp.getTime() + item.ttl);
    if (now > expireTime) {
      this.cache.delete(formId);
      return null;
    }

    return item.schema;
  }

  /**
   * 设置缓存的表单
   */
  setCachedForm(formId: string, schema: FormSchema): void {
    if (!this.config.cache.enabled) {
      return;
    }

    // 检查缓存大小限制
    if (this.cache.size >= this.config.cache.maxSize) {
      // 删除最旧的缓存项
      const oldestKey = this.cache.keys().next().value;
      if (oldestKey) {
        this.cache.delete(oldestKey);
      }
    }

    this.cache.set(formId, {
      schema,
      timestamp: new Date(),
      ttl: this.config.cache.ttl,
    });
  }

  /**
   * 清理缓存
   */
  clearCache(formId?: string): void {
    if (formId) {
      this.cache.delete(formId);
    } else {
      this.cache.clear();
    }
  }

  /**
   * 验证表单
   */
  validateForm(
    schema: FormSchema,
    data: Record<string, any>,
  ): {
    isValid: boolean;
    errors: Record<string, string[]>;
  } {
    const errors: Record<string, string[]> = {};

    // 验证每个字段
    schema.fields.forEach((field) => {
      const fieldErrors = this.validateField(field, data[field.name], data);
      if (fieldErrors.length > 0) {
        errors[field.name] = fieldErrors;
      }
    });

    // 执行全局验证规则
    if (schema.validation) {
      schema.validation.forEach((rule) => {
        const fieldErrors = this.validateFieldRule(rule, data);
        if (fieldErrors.length > 0) {
          if (!errors[rule.field]) {
            errors[rule.field] = [];
          }
          errors[rule.field].push(...fieldErrors);
        }
      });
    }

    const isValid = Object.keys(errors).length === 0;
    this.emit('form:validate', { formId: schema.id, isValid, errors });

    return { isValid, errors };
  }

  /**
   * 提交表单
   */
  async submitForm(schema: FormSchema, data: Record<string, any>): Promise<any> {
    // 验证表单
    const validation = this.validateForm(schema, data);
    if (!validation.isValid) {
      throw new Error('表单验证失败');
    }

    // 转换数据
    const transformedData = this.transformData(schema, data);

    // 提交数据
    if (schema.submitConfig) {
      const result = await this.submitToEndpoint(schema.submitConfig, transformedData);
      this.emit('form:submit', { formId: schema.id, data: transformedData });
      return result;
    }

    this.emit('form:submit', { formId: schema.id, data: transformedData });
    return transformedData;
  }

  /**
   * 转换数据
   */
  transformData(schema: FormSchema, data: Record<string, any>): Record<string, any> {
    const transformed = { ...data };

    // 应用字段级转换
    schema.fields.forEach((field) => {
      const value = transformed[field.name];
      if (value !== undefined) {
        // 根据字段类型进行转换
        switch (field.type) {
          case 'number':
            transformed[field.name] = Number(value);
            break;
          case 'boolean':
            transformed[field.name] = Boolean(value);
            break;
          case 'date':
            if (typeof value === 'string') {
              transformed[field.name] = new Date(value);
            }
            break;
          case 'array':
            if (typeof value === 'string') {
              try {
                transformed[field.name] = JSON.parse(value);
              } catch {
                transformed[field.name] = value.split(',').map((v) => v.trim());
              }
            }
            break;
        }
      }
    });

    // 应用全局转换
    if (schema.submitConfig?.transform) {
      return schema.submitConfig.transform(transformed);
    }

    return transformed;
  }

  /**
   * 获取表单数据
   */
  async getFormData(dataSource: FormDataSource, context?: any): Promise<any> {
    switch (dataSource.type) {
      case 'api':
        return this.fetchDataFromAPI(dataSource.config);
      case 'static':
        return dataSource.config.data || {};
      case 'context':
        return this.getDataFromContext(dataSource.config.contextPath!, context);
      default:
        return {};
    }
  }

  /**
   * 重置表单数据
   */
  resetFormData(schema: FormSchema): Record<string, any> {
    const data: Record<string, any> = {};

    schema.fields.forEach((field) => {
      if (field.defaultValue !== undefined) {
        data[field.name] = field.defaultValue;
      } else {
        // 根据字段类型设置默认值
        switch (field.type) {
          case 'string':
          case 'text':
          case 'email':
          case 'password':
          case 'url':
            data[field.name] = '';
            break;
          case 'number':
            data[field.name] = 0;
            break;
          case 'boolean':
            data[field.name] = false;
            break;
          case 'array':
            data[field.name] = [];
            break;
          case 'object':
            data[field.name] = {};
            break;
          default:
            data[field.name] = null;
        }
      }
    });

    return data;
  }

  // 私有方法

  /**
   * 从 API 获取表单 Schema
   */
  private async fetchFormSchema(formId: string, mode: FormRefMode): Promise<FormSchema> {
    const url = `${this.config.api.baseUrl}/forms/${formId}`;
    const params = new URLSearchParams({ mode });

    const response = await fetch(`${url}?${params}`, {
      method: 'get',
      headers: {
        'Content-Type': 'application/json',
        ...this.config.api.headers,
      },
      signal: AbortSignal.timeout(this.config.api.timeout),
    });

    if (!response.ok) {
      throw new Error(`加载表单失败: ${response.statusText}`);
    }

    const schema = await response.json();
    return this.normalizeSchema(schema);
  }

  /**
   * 标准化表单 Schema
   */
  private normalizeSchema(schema: any): FormSchema {
    // 确保必需字段存在
    return {
      id: schema.id || '',
      name: schema.name || '',
      title: schema.title || '',
      description: schema.description || '',
      version: schema.version || '1.0.0',
      fields: schema.fields || [],
      layout: {
        type: 'vertical',
        columns: 1,
        spacing: 'medium',
        labelPosition: 'top',
        ...schema.layout,
      },
      dataSource: schema.dataSource,
      submitConfig: schema.submitConfig,
      validation: schema.validation || [],
      styles: schema.styles || {},
      metadata: {
        createdAt: schema.metadata?.createdAt ? new Date(schema.metadata.createdAt) : new Date(),
        updatedAt: schema.metadata?.updatedAt ? new Date(schema.metadata.updatedAt) : new Date(),
        ...schema.metadata,
      },
    };
  }

  /**
   * 验证单个字段
   */
  private validateField(field: any, value: any, allData: Record<string, any>): string[] {
    const errors: string[] = [];

    // 必填验证
    if (field.required && (value === undefined || value === null || value === '')) {
      errors.push(`${field.label || field.name} 是必填字段`);
      return errors; // 必填验证失败时，不再进行其他验证
    }

    // 如果值为空且非必填，跳过其他验证
    if (value === undefined || value === null || value === '') {
      return errors;
    }

    // 字段级验证规则
    if (field.validation) {
      field.validation.forEach((rule: any) => {
        const error = this.validateFieldWithRule(value, rule, allData);
        if (error) {
          errors.push(error);
        }
      });
    }

    // 类型验证
    const typeError = this.validateFieldType(field.type, value);
    if (typeError) {
      errors.push(typeError);
    }

    return errors;
  }

  /**
   * 使用规则验证字段
   */
  private validateFieldWithRule(
    value: any,
    rule: any,
    allData: Record<string, any>,
  ): string | null {
    switch (rule.type) {
      case 'required':
        return value === undefined || value === null || value === '' ? rule.message : null;

      case 'email':
        const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
        return !emailRegex.test(value) ? rule.message : null;

      case 'phone':
        const phoneRegex = /^1[3-9]\d{9}$/;
        return !phoneRegex.test(value) ? rule.message : null;

      case 'url':
        try {
          new URL(value);
          return null;
        } catch {
          return rule.message;
        }

      case 'number':
        return isNaN(Number(value)) ? rule.message : null;

      case 'custom':
        return rule.validator && !rule.validator(value, allData) ? rule.message : null;

      default:
        return null;
    }
  }

  /**
   * 验证字段类型
   */
  private validateFieldType(type: string, value: any): string | null {
    switch (type) {
      case 'number':
        return isNaN(Number(value)) ? '请输入有效的数字' : null;
      case 'email':
        const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
        return !emailRegex.test(value) ? '请输入有效的邮箱地址' : null;
      case 'url':
        try {
          new URL(value);
          return null;
        } catch {
          return '请输入有效的URL地址';
        }
      default:
        return null;
    }
  }

  /**
   * 验证表单级规则
   */
  private validateFieldRule(rule: FormValidationRule, data: Record<string, any>): string[] {
    const errors: string[] = [];
    const value = data[rule.field];

    rule.rules.forEach((ruleItem) => {
      const error = this.validateFieldWithRule(value, ruleItem, data);
      if (error) {
        errors.push(error);
      }
    });

    return errors;
  }

  /**
   * 提交到端点
   */
  private async submitToEndpoint(config: FormSubmitConfig, data: any): Promise<any> {
    if (!config.endpoint) {
      throw new Error('未配置提交端点');
    }

    const response = await fetch(config.endpoint, {
      method: config.method || 'post',
      headers: {
        'Content-Type': 'application/json',
        ...config.headers,
      },
      body: JSON.stringify(data),
      signal: AbortSignal.timeout(this.config.api.timeout),
    });

    if (!response.ok) {
      const error = new Error(`提交失败: ${response.statusText}`);
      config.onError?.(error);
      throw error;
    }

    const result = await response.json();
    config.onSuccess?.(result);
    return result;
  }

  /**
   * 从 API 获取数据
   */
  private async fetchDataFromAPI(config: any): Promise<any> {
    const url = config.endpoint;
    const method = config.method || 'get';

    const options: RequestInit = {
      method,
      headers: {
        'Content-Type': 'application/json',
        ...config.headers,
      },
      signal: AbortSignal.timeout(this.config.api.timeout),
    };

    if (method !== 'get' && config.params) {
      options.body = JSON.stringify(config.params);
    }

    const response = await fetch(url, options);

    if (!response.ok) {
      throw new Error(`获取数据失败: ${response.statusText}`);
    }

    return response.json();
  }

  /**
   * 从上下文获取数据
   */
  private getDataFromContext(path: string, context: any): any {
    if (!context) {
      return {};
    }

    return path.split('.').reduce((current, key) => current?.[key], context) || {};
  }

  /**
   * 清理过期缓存
   */
  private cleanExpiredCache(): void {
    const now = new Date();

    for (const [key, item] of this.cache.entries()) {
      const expireTime = new Date(item.timestamp.getTime() + item.ttl);
      if (now > expireTime) {
        this.cache.delete(key);
      }
    }
  }
}

/**
 * 默认表单引用配置
 */
export const DEFAULT_FORM_REF_CONFIG: FormRefConfig = {
  api: {
    baseUrl: '/api',
    timeout: 10000,
  },
  cache: {
    enabled: true,
    ttl: 5 * 60 * 1000, // 5分钟
    maxSize: 100,
  },
  validation: {
    validateOnChange: true,
    validateOnBlur: true,
    showErrorsImmediately: false,
  },
  rendering: {
    fieldRenderers: {},
    defaultFieldRenderer: {
      type: 'text',
      component: 'input' as any,
    },
  },
};
