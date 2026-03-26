/**
 * Expression Context - 表达式执行上下文
 * 提供表达式求值所需的所有变量和内置函数
 */

import dayjs from 'dayjs';
import { businessFunctions, type BusinessFunctions } from './business-functions';

export interface GlobalState {
  user?: {
    id: string;
    name: string;
    email: string;
    roles: string[];
    permissions: string[];
    [key: string]: any;
  };
  tenant?: {
    id: string;
    name: string;
    [key: string]: any;
  };
  locale: string;
  theme: string;
  t?: (key: string, vars?: Record<string, any>) => string; // i18n translation function
  [key: string]: any;
}

export interface PageState {
  filters?: Record<string, any>;
  selectedIds?: any[];
  form?: Record<string, any>;
  [key: string]: any;
}

export interface FormData {
  [key: string]: any;
}

export interface RowData {
  [key: string]: any;
}

export interface DictStore {
  [key: string]: any;
}

/**
 * 表达式上下文接口
 */
export interface ExpressionContext {
  // 全局状态
  global: GlobalState;

  // 页面状态
  state: PageState;

  // 表单数据
  form?: FormData;

  // 当前行数据（表格场景）
  row?: RowData;

  // 参数传递
  args?: Record<string, any>;

  // 字典数据
  dict?: DictStore;

  // i18n 数据
  i18n?: Record<string, Record<string, string>>;

  // 当前语言
  locale: string;

  // Built-in functions
  hasPermission: (permission: string) => boolean;
  formatDate: (date: Date | string | number, format?: string) => string;
  formatCurrency: (value: number, currency?: string) => string;
  t: (key: string, vars?: Record<string, any>) => string;

  // Business functions namespace: ${fn.IF(...)}, ${fn.DATEADD(...)}
  fn: BusinessFunctions;

  // Extension fields
  [key: string]: any;
}

/**
 * 创建默认的表达式上下文
 */
export function createExpressionContext(overrides?: Partial<ExpressionContext>): ExpressionContext {
  const defaultContext: ExpressionContext = {
    global: {
      user: undefined,
      tenant: undefined,
      locale: 'zh-CN',
      theme: 'light',
    },
    state: {},
    form: undefined,
    row: undefined,
    args: undefined,
    dict: {},
    i18n: {},
    locale: 'zh-CN',

    // 内置函数实现
    hasPermission: (permission: string) => {
      const permissions = defaultContext.global.user?.permissions || [];
      return permissions.includes(permission);
    },

    formatDate: (date: Date | string | number, format = 'YYYY-MM-DD HH:mm:ss') => {
      const d = dayjs(date);
      if (!d.isValid()) return String(date);
      return d.format(format);
    },

    formatCurrency: (value: number, currency = 'cny') => {
      return new Intl.NumberFormat('zh-CN', {
        style: 'currency',
        currency,
      }).format(value);
    },

    t: (key: string, vars?: Record<string, any>) => {
      const messages = defaultContext.i18n?.[defaultContext.locale] || {};
      let message = messages[key] || key;

      // 简单变量替换
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

  // 合并覆盖
  const safeOverrides = overrides || {};
  const { global: overrideGlobal, state: overrideState, ...rest } = safeOverrides;

  // 先合并基础数据
  const mergedGlobal = {
    ...defaultContext.global,
    ...(overrideGlobal || {}),
  };
  const mergedState = {
    ...defaultContext.state,
    ...(overrideState || {}),
  };

  // 创建最终上下文，确保内置函数使用合并后的数据
  const mergedContext: ExpressionContext = {
    ...defaultContext,
    ...rest,
    global: mergedGlobal,
    state: mergedState,
    // 重新定义 hasPermission，使用合并后的 global.user.permissions
    hasPermission: (permission: string) => {
      const permissions = mergedGlobal.user?.permissions || [];
      return permissions.includes(permission);
    },
    // 重新定义 t 函数，使用合并后的 i18n 和 locale
    t:
      rest.t ||
      ((key: string, vars?: Record<string, any>) => {
        const locale = rest.locale || defaultContext.locale;
        const i18n = rest.i18n || defaultContext.i18n;
        const messages = i18n?.[locale] || {};
        let message = messages[key] || key;

        if (vars) {
          Object.entries(vars).forEach(([k, v]) => {
            message = message.replace(new RegExp(`{${k}}`, 'g'), String(v));
          });
        }

        return message;
      }),
  };

  return mergedContext;
}

/**
 * 合并多个上下文
 */
export function mergeContexts(...contexts: Partial<ExpressionContext>[]): ExpressionContext {
  const base = createExpressionContext();
  return contexts.reduce<ExpressionContext>((merged, ctx) => {
    const { global: ctxGlobal, state: ctxState, ...rest } = ctx || {};
    const mergedRest = { ...merged, ...rest };
    return {
      ...mergedRest,
      global: { ...merged.global, ...(ctxGlobal || {}) },
      state: { ...merged.state, ...(ctxState || {}) },
      locale: rest.locale ?? merged.locale,
      t: rest.t ?? merged.t,
      hasPermission: rest.hasPermission ?? merged.hasPermission,
      formatDate: rest.formatDate ?? merged.formatDate,
      formatCurrency: rest.formatCurrency ?? merged.formatCurrency,
    };
  }, base);
}
