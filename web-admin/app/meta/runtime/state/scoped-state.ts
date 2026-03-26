/**
 * Scoped State Manager - 作用域状态管理器
 * 用于解决同一页面多个表单/组件的状态隔离问题
 */

import { create } from 'zustand';
import { subscribeWithSelector } from 'zustand/middleware';
import type { ExpressionContext, GlobalState, PageState } from '~/meta/runtime/expression/context';
import { businessFunctions } from '~/meta/runtime/expression/business-functions';

/**
 * Field metadata for linkage-driven overrides (visibility, disabled, required, options)
 */
export interface FieldMeta {
  hidden?: boolean;
  disabled?: boolean;
  required?: boolean;
  options?: Array<{ label: string; value: any }>;
  validation?: Array<{ type: string; value?: string | number; message: string }>;
}

/**
 * 作用域状态接口
 */
export interface ScopeState {
  form?: Record<string, any>;
  state?: Record<string, any>;
  i18n?: Record<string, Record<string, string>>;
  fieldMeta?: Record<string, FieldMeta>;
  [key: string]: any;
}

/**
 * 作用域状态管理器
 */
export class ScopedStateManager {
  private scopes = new Map<string, ScopeState>();
  private stores = new Map<string, any>();
  private globalState: GlobalState;

  constructor(globalState: GlobalState) {
    this.globalState = globalState;
  }

  /**
   * 创建新的作用域
   */
  createScope(scopeId: string, initialState?: Partial<ScopeState>): void {
    if (this.scopes.has(scopeId)) {
      console.warn(`Scope ${scopeId} already exists, overwriting...`);
    }

    const scopeState: ScopeState = {
      form: {},
      state: {},
      i18n: {},
      fieldMeta: {},
      ...initialState,
    };

    this.scopes.set(scopeId, scopeState);

    // 为每个 scope 创建独立的 Zustand store
    const store = create(subscribeWithSelector(() => scopeState));

    this.stores.set(scopeId, store);
  }

  /**
   * 获取作用域状态
   */
  getScope(scopeId: string): ScopeState | undefined {
    return this.scopes.get(scopeId);
  }

  /**
   * 获取作用域的 Zustand store
   */
  getStore(scopeId: string): any {
    return this.stores.get(scopeId);
  }

  /**
   * 更新作用域状态
   */
  updateScope(
    scopeId: string,
    updater: Partial<ScopeState> | ((prev: ScopeState) => Partial<ScopeState>),
  ): void {
    const scope = this.scopes.get(scopeId);
    if (!scope) {
      console.error(`Scope ${scopeId} does not exist`);
      return;
    }

    const updates = typeof updater === 'function' ? updater(scope) : updater;

    const newScope = {
      ...scope,
      ...updates,
    };

    this.scopes.set(scopeId, newScope);

    // 更新 store
    const store = this.stores.get(scopeId);
    if (store) {
      store.setState(newScope);
    }
  }

  /**
   * 获取字段值
   */
  getFieldValue(scopeId: string, field: string): any {
    const scope = this.scopes.get(scopeId);
    return scope?.form?.[field];
  }

  /**
   * 更新字段值 (alias for updateForm)
   */
  updateField(scopeId: string, field: string, value: any): void {
    this.updateForm(scopeId, field, value);
  }

  /**
   * 更新作用域中的表单数据
   */
  updateForm(scopeId: string, field: string, value: any): void {
    this.updateScope(scopeId, (prev) => ({
      form: {
        ...(prev.form || {}),
        [field]: value,
      },
    }));
  }

  /**
   * 重置作用域中的表单数据
   */
  resetForm(scopeId: string): void {
    this.updateScope(scopeId, { form: {} });
  }

  /**
   * 更新作用域中的页面状态
   */
  updateState(scopeId: string, key: string, value: any): void {
    this.updateScope(scopeId, (prev) => ({
      state: {
        ...(prev.state || {}),
        [key]: value,
      },
    }));
  }

  /**
   * 获取作用域的表达式上下文
   */
  getContext(scopeId: string): ExpressionContext {
    const scope = this.scopes.get(scopeId) || {};

    return {
      global: this.globalState,
      state: (scope.state as PageState) || {},
      form: scope.form,
      i18n: scope.i18n,
      locale: this.globalState.locale,
      dict: {},
      row: undefined,
      args: undefined,

      // 内置函数
      hasPermission: (permission: string) => {
        const permissions = this.globalState.user?.permissions || [];
        return permissions.includes(permission);
      },

      formatDate: (date: Date | string | number, format = 'YYYY-MM-DD HH:mm:ss') => {
        const d = new Date(date);
        if (isNaN(d.getTime())) return String(date);

        const year = d.getFullYear();
        const month = String(d.getMonth() + 1).padStart(2, '0');
        const day = String(d.getDate()).padStart(2, '0');
        const hours = String(d.getHours()).padStart(2, '0');
        const minutes = String(d.getMinutes()).padStart(2, '0');
        const seconds = String(d.getSeconds()).padStart(2, '0');

        return format
          .replace('yyyy', String(year))
          .replace('MM', month)
          .replace('DD', day)
          .replace('HH', hours)
          .replace('mm', minutes)
          .replace('ss', seconds);
      },

      formatCurrency: (value: number, currency = 'cny') => {
        return new Intl.NumberFormat('zh-CN', {
          style: 'currency',
          currency,
        }).format(value);
      },

      t: (key: string, vars?: Record<string, any>) => {
        // 优先使用 globalState 中的 t 函数（来自 I18nContext）
        if (this.globalState.t && typeof this.globalState.t === 'function') {
          return this.globalState.t(key, vars);
        }

        // 回退到 scope.i18n
        const messages = scope.i18n?.[this.globalState.locale] || {};
        let message = messages[key] || key;

        if (vars) {
          Object.entries(vars).forEach(([k, v]) => {
            message = message.replace(new RegExp(`{${k}}`, 'g'), String(v));
          });
        }

        return message;
      },

      // Business functions namespace
      fn: businessFunctions,
    };
  }

  /**
   * Get field metadata for a specific field
   */
  getFieldMeta(scopeId: string, fieldCode: string): FieldMeta | undefined {
    const scope = this.scopes.get(scopeId);
    return scope?.fieldMeta?.[fieldCode];
  }

  /**
   * Update field metadata for a specific field
   */
  updateFieldMeta(scopeId: string, fieldCode: string, meta: Partial<FieldMeta>): void {
    this.updateScope(scopeId, (prev) => ({
      fieldMeta: {
        ...(prev.fieldMeta || {}),
        [fieldCode]: {
          ...(prev.fieldMeta?.[fieldCode] || {}),
          ...meta,
        },
      },
    }));
  }

  /**
   * Batch update field metadata for multiple fields
   */
  batchUpdateFieldMeta(scopeId: string, updates: Record<string, Partial<FieldMeta>>): void {
    this.updateScope(scopeId, (prev) => {
      const newFieldMeta = { ...(prev.fieldMeta || {}) };
      for (const [fieldCode, meta] of Object.entries(updates)) {
        newFieldMeta[fieldCode] = {
          ...(newFieldMeta[fieldCode] || {}),
          ...meta,
        };
      }
      return { fieldMeta: newFieldMeta };
    });
  }

  /**
   * 删除作用域
   */
  deleteScope(scopeId: string): void {
    this.scopes.delete(scopeId);
    this.stores.delete(scopeId);
  }

  /**
   * 订阅作用域状态变化
   */
  subscribe(
    scopeId: string,
    selector: (state: ScopeState) => any,
    callback: (value: any) => void,
  ): () => void {
    const store = this.stores.get(scopeId);
    if (!store) {
      console.error(`Scope ${scopeId} does not exist`);
      return () => {};
    }

    return store.subscribe(selector, callback);
  }

  /**
   * 初始化作用域状态绑定
   * 从 schema.stateBinding 配置初始化
   */
  initFromBinding(scopeId: string, stateBinding?: Record<string, string>): void {
    if (!stateBinding) return;

    const initialState: Partial<ScopeState> = {
      state: {},
    };

    // 解析 stateBinding 并初始化对应的状态
    Object.keys(stateBinding).forEach((key) => {
      if (initialState.state) {
        initialState.state[key] = undefined;
      }
    });

    if (!this.scopes.has(scopeId)) {
      this.createScope(scopeId, initialState);
    } else {
      this.updateScope(scopeId, initialState);
    }
  }
}

/**
 * 全局作用域管理器实例
 */
let globalScopedStateManager: ScopedStateManager | null = null;

/**
 * 获取全局作用域管理器
 */
export function getScopedStateManager(globalState?: GlobalState): ScopedStateManager {
  if (!globalScopedStateManager && globalState) {
    globalScopedStateManager = new ScopedStateManager(globalState);
  }

  if (!globalScopedStateManager) {
    throw new Error('ScopedStateManager not initialized. Provide globalState first.');
  }

  return globalScopedStateManager;
}

/**
 * 重置全局作用域管理器（用于测试）
 */
export function resetScopedStateManager(): void {
  globalScopedStateManager = null;
}
