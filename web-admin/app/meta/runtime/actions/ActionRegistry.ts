/**
 * ActionRegistry - 动作注册中心
 *
 * 提供可扩展的动作注册和执行机制，替代硬编码的 switch-case
 *
 * 设计原则：
 * 1. 开闭原则 - 新增动作只需注册，无需修改现有代码
 * 2. 单一职责 - 每个动作处理器只负责一个具体操作
 * 3. 依赖注入 - 通过 ActionContext 注入所有依赖
 *
 * 与 SchemaRuntime 的关系：
 * - ActionRegistry 提供原子操作（navigate, delete, reload）
 * - SchemaRuntime 提供流程编排（executeFlow, executeHandler）
 * - SchemaRuntime.executeAction 可以调用 ActionRegistry.execute
 */

import type { NavigateFunction } from 'react-router';
import type { DataSourceManager } from '~/meta/runtime/data-pipeline/DataSourceManager';
import { confirmDialog, type ConfirmOptions } from '~/utils/confirmDialog';
import { ResultHelper } from '~/utils/type';

/**
 * 动作执行上下文
 * 包含所有动作执行所需的依赖和状态
 *
 * P0-2 更新: 支持 SchemaRuntime 纯委托模式
 * - 添加 Step 级别参数 (stepEndpoint, stepBody, etc.)
 * - 添加 Schema 数据和工具函数
 * - 添加表达式求值器
 */
export interface ActionContext {
  // 动作参数
  args?: Record<string, any>;

  // 路由相关 (可选 - SchemaRuntime 调用时可能没有)
  navigate?: NavigateFunction | ((path: string) => void);
  tableName?: string;

  // 数据加载 (可选 - 列表页使用)
  loadData?: (params?: any) => Promise<void>;

  // 筛选器状态 (可选 - 列表页使用)
  filters?: Record<string, any>;
  setFilters?: (
    filters: Record<string, any> | ((prev: Record<string, any>) => Record<string, any>),
  ) => void;

  // 分页状态 (可选 - 列表页使用)
  pagination?: {
    current: number;
    pageSize: number;
    total: number;
  };
  setPagination?: (update: (prev: any) => any) => void;

  // 数据源管理 (可选 - 如果有 DataSourceManager)
  dataSourceManager?: DataSourceManager;

  // 记录数据（用于行级操作）
  record?: any;

  // 按钮配置（用于行级操作，如 commandCode）
  button?: any;

  // 国际化 (可选)
  locale?: string;
  t?: (key: string) => string;

  // 认证 (可选)
  token?: string;

  // API 工具（可选，用于自定义请求）
  fetchResult?: (endpoint: string, options?: any) => Promise<any>;
  buildApiEndpoint?: (tableName: string) => string;

  // Confirm dialog (可选 - 命令式 confirmDialog API 用作 fallback)
  confirm?: (opts: ConfirmOptions) => Promise<boolean>;

  // Toast 提示 (可选 - SchemaRuntime 可能提供)
  showToast?: (message: string, level?: 'success' | 'error' | 'info' | 'warning') => void;

  // State Manager (可选 - SchemaRuntime 提供)
  stateManager?: any;
  scopeId?: string;

  // P0-2: Step 级别参数 (SchemaRuntime 传递的原始参数)
  stepEndpoint?: string;
  stepMethod?: string;
  stepBody?: any;
  stepParams?: any;
  stepTarget?: string;

  // P0-2: Schema 数据和工具函数 (用于需要访问 Schema 的 action)
  schema?: any;
  getAllFormFields?: () => any[];

  // P0-2: 表达式求值器 (让 Action 自行求值表达式)
  expressionEvaluator?: any;
  expressionContext?: any;
}

/**
 * 动作处理器类型
 */
export type ActionHandler = (context: ActionContext) => Promise<void> | void;

/**
 * 动作注册表
 */
class ActionRegistry {
  private handlers = new Map<string, ActionHandler>();

  /**
   * 注册动作处理器
   * @param type 动作类型（如 'navigate', 'delete', 'search'）
   * @param handler 处理器函数
   */
  register(type: string, handler: ActionHandler): void {
    if (this.handlers.has(type)) {
      console.warn(`[ActionRegistry] Overwriting existing handler: ${type}`);
    }
    this.handlers.set(type, handler);
  }

  /**
   * 批量注册动作处理器
   */
  registerBatch(handlers: Record<string, ActionHandler>): void {
    Object.entries(handlers).forEach(([type, handler]) => {
      this.register(type, handler);
    });
  }

  /**
   * 执行动作
   * @param type 动作类型
   * @param context 执行上下文
   */
  async execute(type: string, context: ActionContext): Promise<void> {
    const handler = this.handlers.get(type);

    if (!handler) {
      console.warn(
        `[ActionRegistry] Handler not found: ${type}. Available handlers:`,
        Array.from(this.handlers.keys()),
      );
      return;
    }

    try {
      await handler(context);
    } catch (error) {
      console.error(`[ActionRegistry] Error executing action ${type}:`, error);
      throw error;
    }
  }

  /**
   * 检查动作是否已注册
   */
  has(type: string): boolean {
    return this.handlers.has(type);
  }

  /**
   * 获取所有已注册的动作类型
   */
  getRegisteredTypes(): string[] {
    return Array.from(this.handlers.keys());
  }

  /**
   * 取消注册动作
   */
  unregister(type: string): void {
    this.handlers.delete(type);
  }

  /**
   * 清空所有动作
   */
  clear(): void {
    this.handlers.clear();
  }
}

// 导出单例
export const actionRegistry = new ActionRegistry();

// ============================================
// 注册内置动作处理器
// ============================================

/**
 * 导航动作 - 跳转到指定路径
 */
actionRegistry.register('navigate', ({ args, navigate }) => {
  if (!navigate) {
    console.error('[ActionRegistry] navigate: navigate function not provided');
    return;
  }
  const path = args?.path;
  if (!path) {
    console.error('[ActionRegistry] navigate: missing path argument');
    return;
  }
  navigate(path);
});

/**
 * 新建记录 - 跳转到新建页面
 */
actionRegistry.register('new', ({ navigate, tableName }) => {
  if (!navigate || !tableName) {
    console.error('[ActionRegistry] new: missing navigate or tableName');
    return;
  }
  navigate(`/p/${tableName}/new`);
});

/**
 * 编辑记录 - 跳转到编辑页面
 */
actionRegistry.register('edit', ({ navigate, tableName, record }) => {
  if (!navigate || !tableName) {
    console.error('[ActionRegistry] edit: missing navigate or tableName');
    return;
  }
  if (!record) {
    console.error('[ActionRegistry] edit: missing record');
    return;
  }
  const id = record.id || record.pid;
  navigate(`/p/${tableName}/${id}/edit`);
});

/**
 * 查看记录 - 跳转到详情页面
 */
actionRegistry.register('view', ({ navigate, tableName, record }) => {
  if (!navigate || !tableName) {
    console.error('[ActionRegistry] view: missing navigate or tableName');
    return;
  }
  if (!record) {
    console.error('[ActionRegistry] view: missing record');
    return;
  }
  const id = record.id || record.pid;
  navigate(`/p/${tableName}/view/${id}`);
});

/**
 * 删除记录 - 删除指定记录并刷新列表
 */
actionRegistry.register(
  'delete',
  async ({
    record,
    button,
    loadData,
    filters,
    tableName,
    token,
    fetchResult,
    buildApiEndpoint,
    t,
    confirm: ctxConfirm,
    showToast,
  }) => {
    if (!record) {
      console.error('[ActionRegistry] delete: missing record');
      return;
    }

    if (!tableName) {
      console.error('[ActionRegistry] delete: missing tableName');
      return;
    }

    const confirmMessage =
      t?.('common.confirm.delete') || 'Are you sure you want to delete this record?';
    const doConfirm = ctxConfirm ?? confirmDialog;
    const confirmed = await doConfirm({ content: confirmMessage, variant: 'danger' });
    if (!confirmed) {
      return;
    }

    if (!fetchResult || !buildApiEndpoint || !loadData) {
      console.error('[ActionRegistry] delete: missing fetchResult, buildApiEndpoint or loadData');
      return;
    }

    try {
      const id = record.id || record.pid;
      if (!id) {
        console.error('[ActionRegistry] delete: missing record id/pid');
        return;
      }
      const commandCode = typeof button?.commandCode === 'string' ? button.commandCode : undefined;
      const result = commandCode
        ? await fetchResult(`/api/meta/commands/execute/${commandCode}`, {
            method: 'post',
            params: {
              targetRecordId: id,
              payload: record,
              operationType: 'delete',
            },
            token: token || undefined,
          })
        : await fetchResult(`${buildApiEndpoint(tableName)}/${id}`, {
            method: 'delete',
            token: token || undefined,
          });

      if (ResultHelper.isSuccess(result)) {
        const successMessage = t?.('common.success.delete') || 'Deleted successfully';
        showToast?.(successMessage, 'success');
        await loadData({ filters });
      } else {
        const errorMessage = result.desc || t?.('common.error.delete') || 'Delete failed';
        showToast?.(errorMessage, 'error');
      }
    } catch (error) {
      console.error('[ActionRegistry] delete error:', error);
      const errorMessage = t?.('common.error.delete') || 'Delete failed';
      showToast?.(errorMessage, 'error');
    }
  },
);

/**
 * 搜索动作 - 使用当前筛选器重新加载数据
 */
actionRegistry.register('search', ({ loadData, filters, setPagination }) => {
  if (!loadData || !setPagination) {
    console.error('[ActionRegistry] search: missing loadData or setPagination');
    return;
  }
  setPagination((prev) => ({ ...prev, current: 1 }));
  loadData({ page: 0, filters });
});

/**
 * 重置筛选器 - 清空筛选条件并重新加载数据
 */
actionRegistry.register('reset', ({ setFilters, loadData, setPagination }) => {
  if (!setFilters || !loadData || !setPagination) {
    console.error('[ActionRegistry] reset: missing setFilters, loadData or setPagination');
    return;
  }
  setFilters({});
  setPagination((prev) => ({ ...prev, current: 1 }));
  loadData({ page: 0, filters: {} });
});

/**
 * 刷新数据 - 使用当前条件重新加载数据
 */
actionRegistry.register('refresh', ({ loadData, filters }) => {
  if (!loadData) {
    console.error('[ActionRegistry] refresh: missing loadData');
    return;
  }
  loadData({ filters });
});

/**
 * 导出数据 - 导出当前筛选条件的数据
 */
actionRegistry.register(
  'export',
  async ({ tableName, filters, token, fetchResult, buildApiEndpoint, t }) => {
    if (!tableName) {
      console.error('[ActionRegistry] export: missing tableName');
      return;
    }

    if (!fetchResult || !buildApiEndpoint) {
      console.error('[ActionRegistry] export: missing fetchResult or buildApiEndpoint');
      return;
    }

    try {
      const endpoint = `${buildApiEndpoint(tableName)}/export`;
      const result = await fetchResult(endpoint, {
        method: 'post',
        params: { filters },
        token: token || undefined,
      });

      if (ResultHelper.isSuccess(result) && result.data?.downloadUrl) {
        // 使用 fetch 下载二进制文件
        const downloadUrl = result.data.downloadUrl;
        const response = await fetch(downloadUrl, {
          method: 'get',
          headers: {
            Authorization: token ? `Bearer ${token}` : '',
          },
        });

        if (!response.ok) {
          throw new Error(`Download failed: ${response.status}`);
        }

        // 获取文件名
        const contentDisposition = response.headers.get('Content-Disposition');
        let fileName = `${tableName}_export.xlsx`;
        if (contentDisposition) {
          const match = contentDisposition.match(/filename[^;=\n]*=((['"]).*?\2|[^;\n]*)/);
          if (match && match[1]) {
            fileName = match[1].replace(/['"]/g, '');
          }
        }

        // 创建 Blob 并下载
        const blob = await response.blob();
        const url = window.URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.download = fileName;
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
        window.URL.revokeObjectURL(url);

        // export success
      } else {
      }
    } catch (error) {
      console.error('[ActionRegistry] export error:', error);
    }
  },
);

/**
 * 重新加载数据源 - 刷新指定的数据源
 */
actionRegistry.register('reloadDataSource', async ({ dataSourceManager, args }) => {
  const dataSourceId = args?.dataSourceId;
  if (!dataSourceId) {
    console.error('[ActionRegistry] reloadDataSource: missing dataSourceId');
    return;
  }

  if (!dataSourceManager) {
    console.error('[ActionRegistry] reloadDataSource: missing dataSourceManager');
    return;
  }

  await dataSourceManager.reload(dataSourceId);
});

/**
 * 设置状态 - 更新筛选器状态
 */
actionRegistry.register('setState', ({ setFilters, args }) => {
  if (!setFilters) {
    console.error('[ActionRegistry] setState: missing setFilters');
    return;
  }
  if (!args) {
    console.error('[ActionRegistry] setState: missing args');
    return;
  }
  setFilters((prev) => ({ ...prev, ...args }));
});

// ============================================
// 注册 SchemaRuntime 兼容动作 (为了委托)
// ============================================

/**
 * router.push - 路由跳转
 */
actionRegistry.register('router.push', ({ args, navigate }) => {
  if (!navigate) {
    console.error('[ActionRegistry] router.push: missing navigate');
    return;
  }
  const path = args?.path;
  if (!path) {
    console.error('[ActionRegistry] router.push: missing path argument');
    return;
  }
  navigate(path);
});

/**
 * router.back - 返回上一页
 */
actionRegistry.register('router.back', ({ navigate }) => {
  if (!navigate) {
    console.error('[ActionRegistry] router.back: missing navigate');
    return;
  }
  navigate(-1 as any);
});

/**
 * dataSource.fetch - 获取数据源
 */
actionRegistry.register('dataSource.fetch', async ({ dataSourceManager, args }) => {
  if (!dataSourceManager) {
    console.error('[ActionRegistry] dataSource.fetch: missing dataSourceManager');
    return;
  }
  const target = args?.target;
  if (!target) {
    console.error('[ActionRegistry] dataSource.fetch: missing target');
    return;
  }
  await dataSourceManager.fetch(target, args);
});

/**
 * dataSource.reload - 重新加载数据源
 */
actionRegistry.register('dataSource.reload', async ({ dataSourceManager, args }) => {
  if (!dataSourceManager) {
    console.error('[ActionRegistry] dataSource.reload: missing dataSourceManager');
    return;
  }
  const targets = args?.targets || args?.target;
  if (!targets) {
    console.error('[ActionRegistry] dataSource.reload: missing targets');
    return;
  }
  const targetArray = Array.isArray(targets) ? targets : [targets];
  await dataSourceManager.reload(targetArray);
});

/**
 * toast.show - 显示提示消息
 */
actionRegistry.register('toast.show', ({ args, showToast }) => {
  let message: any = args?.message || args?.content;
  if (message && typeof message === 'object') {
    message = (message as any)['zh-CN'] || (message as any)['en-US'] || JSON.stringify(message);
  }
  const level = args?.level || 'info';
  showToast?.(message as string, level);
});

/**
 * toast.success - 成功提示
 */
actionRegistry.register('toast.success', ({ args, showToast }) => {
  let message: any = args?.message || args?.content || 'Operation successful';
  if (message && typeof message === 'object') {
    message = (message as any)['zh-CN'] || (message as any)['en-US'] || 'Operation successful';
  }
  showToast?.(message as string, 'success');
});

/**
 * toast.error - 错误提示
 */
actionRegistry.register('toast.error', ({ args, showToast }) => {
  let message: any = args?.message || args?.content || 'Operation failed';
  if (message && typeof message === 'object') {
    message = (message as any)['zh-CN'] || (message as any)['en-US'] || 'Operation failed';
  }
  showToast?.(message as string, 'error');
});

/**
 * dialog.confirm - 确认对话框
 */
actionRegistry.register('dialog.confirm', async ({ args, confirm: ctxConfirm }) => {
  const message = args?.message || args?.content || 'Are you sure you want to proceed?';
  const doConfirm = ctxConfirm ?? confirmDialog;
  const confirmed = await doConfirm({ content: message });
  if (!confirmed) {
    throw new Error('User cancelled');
  }
});

/**
 * dialog.form - Display a dynamic form dialog
 *
 * Renders a modal form with configurable fields. Pre-fetches options
 * for select fields with API datasources. On submit, stores collected
 * form values in stateManager under form.{fieldName} keys for
 * subsequent handler steps.
 *
 * DSL format:
 * {
 *   "action": "dialog.form",
 *   "args": {
 *     "title": "Mount to Menu",
 *     "fields": [
 *       { "field": "parentMenu", "label": "Parent Menu", "type": "select",
 *         "required": true, "dataSource": { "type": "api", "endpoint": "/api/..." } },
 *       { "field": "orderNo", "label": "Sort Order", "type": "number", "defaultValue": 10 }
 *     ]
 *   }
 * }
 */
actionRegistry.register('dialog.form', async ({ args, stateManager, scopeId, fetchResult }) => {
  if (!args?.fields) {
    console.error('[ActionRegistry] dialog.form: missing fields in args');
    return;
  }

  // Pre-fetch options for select fields with API datasources
  const fieldOptions: Record<string, Array<{ label: string; value: string }>> = {};
  for (const field of args.fields) {
    if (field.dataSource?.type === 'api' && field.dataSource.endpoint && fetchResult) {
      try {
        const result = await fetchResult(field.dataSource.endpoint, { method: 'get' });
        fieldOptions[field.field] = result.data || [];
      } catch (e) {
        console.error(`[dialog.form] Failed to fetch options for ${field.field}:`, e);
        fieldOptions[field.field] = [];
      }
    } else if (field.dataSource?.type === 'static') {
      fieldOptions[field.field] = field.dataSource.data || [];
    }
  }

  // Build default values from field configs
  const defaults: Record<string, any> = {};
  for (const field of args.fields) {
    if (field.defaultValue !== undefined) {
      defaults[field.field] = field.defaultValue;
    }
  }

  return new Promise<void>((resolve, reject) => {
    const event = new CustomEvent('dialog:form', {
      detail: {
        title: args.title,
        fields: args.fields,
        fieldOptions,
        defaults,
        onSubmit: (formData: Record<string, any>) => {
          // Store form values in stateManager under form.{fieldName}
          if (stateManager && scopeId) {
            Object.entries(formData).forEach(([key, value]) => {
              stateManager.updateForm(scopeId, key, value);
            });
          }
          resolve();
        },
        onCancel: () => {
          reject(new Error('User cancelled'));
        },
      },
    });
    window.dispatchEvent(event);
  });
});

/**
 * event.emit - Dispatch a custom DOM event
 *
 * Fires a CustomEvent on window, allowing cross-component communication.
 * Used for decoupled side-effects like refreshing the sidebar menu
 * after a dashboard is mounted/unmounted.
 *
 * DSL format:
 * { "action": "event.emit", "args": { "event": "menu:refresh" } }
 */
actionRegistry.register('event.emit', ({ args }) => {
  const eventName = args?.event;
  if (!eventName) {
    console.error('[ActionRegistry] event.emit: missing event name in args');
    return;
  }
  window.dispatchEvent(new CustomEvent(eventName, { detail: args }));
});

/**
 * noop - 空操作
 */
actionRegistry.register('noop', () => {
  // 什么也不做
});

// ============================================
// 注册表单和状态管理动作
// ============================================

/**
 * form.validate - 表单验证
 * 验证所有表单字段，如果有错误则抛出异常
 *
 * P0-2 更新: 从 getAllFormFields() 获取字段信息
 */
actionRegistry.register(
  'form.validate',
  async ({ stateManager, scopeId, args, showToast, getAllFormFields, t }) => {
    if (!stateManager || !scopeId) {
      console.error('[ActionRegistry] form.validate: missing stateManager or scopeId');
      return;
    }

    const context = stateManager.getContext(scopeId);
    const errors: Record<string, string> = {};

    // 获取表单数据
    const formData = context.form || {};

    // P0-2: 优先使用 getAllFormFields()，fallback 到 args.fields
    const fields = getAllFormFields ? getAllFormFields() : args?.fields || [];

    // 验证每个字段
    for (const field of fields) {
      if (!field.validation || field.validation.length === 0) continue;

      const value = formData[field.field];

      for (const rule of field.validation) {
        const error = validateField(value, rule, field, context);
        if (error) {
          errors[field.field] = error;
          break; // 只显示第一个错误
        }
      }
    }

    // 如果有错误，抛出异常停止流程
    if (Object.keys(errors).length > 0) {
      const errorMessages = Object.entries(errors)
        .map(([field, message]) => `${field}: ${message}`)
        .join('\n');

      // 显示错误提示
      const validationFailedMsg = t?.('common.validation.failed') || 'Form validation failed';
      showToast?.(`${validationFailedMsg}:\n${errorMessages}`, 'error');

      throw new Error('Form validation failed');
    }
  },
);

/**
 * form.reset - 重置表单
 * 清空表单数据，恢复到初始状态
 */
actionRegistry.register('form.reset', ({ stateManager, scopeId }) => {
  if (!stateManager || !scopeId) {
    console.error('[ActionRegistry] form.reset: missing stateManager or scopeId');
    return;
  }

  stateManager.resetForm(scopeId);
});

/**
 * state.set - 设置状态
 * 更新指定作用域的状态值
 */
actionRegistry.register('state.set', ({ stateManager, scopeId, args }) => {
  if (!stateManager || !scopeId) {
    console.error('[ActionRegistry] state.set: missing stateManager or scopeId');
    return;
  }

  if (!args) {
    console.error('[ActionRegistry] state.set: missing args');
    return;
  }

  // 批量更新状态
  Object.entries(args).forEach(([key, value]) => {
    stateManager.updateState(scopeId, key, value);
  });
});

/**
 * api.request - API 请求
 * 执行 HTTP 请求并返回结果
 *
 * P0-2 更新: 支持 Step 级别参数和表达式求值
 * - 优先使用 stepEndpoint, stepMethod, stepBody, stepParams
 * - 如果提供了 expressionEvaluator，先求值表达式
 * - Fallback 到 args 中的参数
 */
actionRegistry.register(
  'api.request',
  async ({
    args,
    fetchResult,
    stateManager,
    scopeId,
    stepEndpoint,
    stepMethod,
    stepBody,
    stepParams,
    stepTarget,
    expressionEvaluator,
    expressionContext,
  }) => {
    if (!fetchResult) {
      console.error('[ActionRegistry] api.request: fetchResult not provided');
      return;
    }

    // P0-2: 构建请求参数，优先使用 step 级别参数
    let endpoint = stepEndpoint || args?.endpoint;
    let method = stepMethod || args?.method || 'get';
    let body = stepBody || args?.body;
    let params = stepParams || args?.params;
    const target = stepTarget || args?.target;

    // P0-2: 如果提供了表达式求值器，求值表达式
    if (expressionEvaluator && expressionContext) {
      if (endpoint) {
        endpoint = expressionEvaluator.evaluateTemplate(endpoint, expressionContext);
      }
      // 处理 body: 支持字符串模板 {{state.form}} 和对象
      if (body) {
        if (typeof body === 'string') {
          // 字符串模板如 "{{state.form}}" 需要使用 bind 来获取值
          const trimmed = body.trim();
          if (trimmed.startsWith('{{') && trimmed.endsWith('}}')) {
            const bindResult = expressionEvaluator.bind(body, expressionContext);
            // bind 返回 { path, value }，取 value；如果 value 是 undefined，尝试直接从 context 获取
            if (bindResult?.value !== undefined) {
              body = bindResult.value;
            } else if (bindResult?.path) {
              // 尝试从 context 中直接获取路径值
              const path = bindResult.path;
              const parts = path.split('.');
              let value: any = expressionContext;
              for (const part of parts) {
                value = value?.[part];
              }
              body = value;
            }
          } else if (trimmed.includes('${')) {
            // ${} 格式的表达式
            body = expressionEvaluator.evaluate(body, expressionContext);
          }
        } else if (typeof body === 'object') {
          body = expressionEvaluator.evaluateObject(body, expressionContext);
        }
      }
      if (params && typeof params === 'object') {
        params = expressionEvaluator.evaluateObject(params, expressionContext);
      }
    }

    if (!endpoint) {
      console.error('[ActionRegistry] api.request: missing endpoint');
      return;
    }

    try {
      // FetchOptions 使用 params 作为请求数据：
      // - GET 请求：params 作为 query string
      // - POST/PUT/PATCH 请求：params 作为 request body
      // 合并 body 和 params，body 优先（POST 请求的主要数据）
      const requestParams = body ?? params;

      const result = await fetchResult(endpoint, {
        method,
        params: requestParams,
      });

      // 如果指定了 target，将结果保存到状态中
      if (target && stateManager && scopeId) {
        stateManager.updateState(scopeId, target, result.data);
      }

      return result.data;
    } catch (error) {
      console.error('[ActionRegistry] api.request error:', error);
      throw error;
    }
  },
);

// ============================================
// 辅助函数
// ============================================

/**
 * 验证单个字段
 */
function validateField(value: any, rule: any, field: any, context: any): string | null {
  const t = context.t || ((key: string) => key);

  switch (rule.type) {
    case 'required':
      if (value === undefined || value === null || value === '') {
        return typeof rule.message === 'string' && rule.message.startsWith('$i18n:')
          ? t(rule.message.substring(7))
          : rule.message || `${field.label || field.field} is required`;
      }
      break;

    case 'email':
      if (value && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)) {
        return typeof rule.message === 'string' && rule.message.startsWith('$i18n:')
          ? t(rule.message.substring(7))
          : rule.message || 'Invalid email format';
      }
      break;

    case 'pattern':
      if (value && rule.pattern) {
        try {
          if (!new RegExp(rule.pattern).test(value)) {
            return typeof rule.message === 'string' && rule.message.startsWith('$i18n:')
              ? t(rule.message.substring(7))
              : rule.message || 'Invalid format';
          }
        } catch (e) {
          console.error(`[validateField] Invalid regex pattern: ${rule.pattern}`, e);
          return 'Invalid validation pattern';
        }
      }
      break;

    case 'min':
      if (value !== undefined && value !== null) {
        const numValue = typeof value === 'number' ? value : parseFloat(value);
        if (!isNaN(numValue) && numValue < rule.min) {
          return typeof rule.message === 'string' && rule.message.startsWith('$i18n:')
            ? t(rule.message.substring(7))
            : rule.message || `Value must be at least ${rule.min}`;
        }
      }
      break;

    case 'max':
      if (value !== undefined && value !== null) {
        const numValue = typeof value === 'number' ? value : parseFloat(value);
        if (!isNaN(numValue) && numValue > rule.max) {
          return typeof rule.message === 'string' && rule.message.startsWith('$i18n:')
            ? t(rule.message.substring(7))
            : rule.message || `Value must be at most ${rule.max}`;
        }
      }
      break;

    case 'minLength':
      if (value && value.length < rule.minLength) {
        return typeof rule.message === 'string' && rule.message.startsWith('$i18n:')
          ? t(rule.message.substring(7))
          : rule.message || `Minimum length is ${rule.minLength}`;
      }
      break;

    case 'maxLength':
      if (value && value.length > rule.maxLength) {
        return typeof rule.message === 'string' && rule.message.startsWith('$i18n:')
          ? t(rule.message.substring(7))
          : rule.message || `Maximum length is ${rule.maxLength}`;
      }
      break;

    default:
      console.warn(`Unknown validation rule type: ${rule.type}`);
  }

  return null;
}

// ============================================
// UI Actions
// ============================================

/**
 * ui.openContainer - 打开容器（抽屉、对话框等）
 *
 * 临时实现：当 target 为 drawer.form 时，跳转到对应的新建/编辑页面
 * 未来应该实现真正的抽屉 UI
 *
 * DSL 格式: {"action": "ui.openContainer", "target": "drawer.form", "args": {"mode": "create"}}
 * - target: step 级别属性，通过 stepTarget 传入
 * - args: step.args，包含 mode, id 等
 */
actionRegistry.register('ui.openContainer', ({ args, stepTarget, navigate, schema }) => {
  // target 可能在 args 中或者在 stepTarget 中
  const target = stepTarget || args?.target;
  const mode = args?.mode;
  const id = args?.id;

  if (!navigate) {
    console.error('[ActionRegistry] ui.openContainer requires navigate function');
    return;
  }

  // 从 schema 中获取 modelCode，或从 schema.id 解析（格式: "list.modelCode" 或 "form.modelCode"）
  let modelCode = schema?.modelCode;
  if (!modelCode && schema?.id) {
    // schema.id 格式通常是 "list.test2" 或 "form.store"
    const parts = schema.id.split('.');
    if (parts.length >= 2) {
      modelCode = parts[1];
    }
  }

  if (!modelCode) {
    console.error(
      '[ActionRegistry] ui.openContainer requires schema.modelCode or parseable schema.id',
    );
    return;
  }

  // 根据 target 和 mode 决定跳转路径
  if (target === 'drawer.form' || target === 'form') {
    if (mode === 'create') {
      navigate(`/p/${modelCode}/new`);
    } else if (mode === 'edit' && id) {
      navigate(`/p/${modelCode}/${id}/edit`);
    } else {
      console.warn('[ActionRegistry] ui.openContainer: unknown mode for form target:', mode);
    }
  } else if (target === 'drawer.detail' || target === 'detail') {
    if (id) {
      navigate(`/p/${modelCode}/view/${id}`);
    } else {
      console.warn('[ActionRegistry] ui.openContainer: detail target requires id');
    }
  } else {
    console.warn('[ActionRegistry] ui.openContainer: unknown target:', target);
  }
});

/**
 * ui.closeContainer - 关闭容器
 */
actionRegistry.register('ui.closeContainer', ({ navigate }) => {
  // 临时实现：返回上一页
  if (navigate) {
    navigate(-1 as any);
  }
});

// ============================================
// 通知动作
// ============================================

/**
 * notify - 发送通知到指定 channel
 *
 * DSL 格式:
 * {
 *   "action": "notify",
 *   "channel": "dataSource",
 *   "payload": { "id": "ds_modelCodeList", "event": "reload" }
 * }
 *
 * 支持的 channel:
 * - dataSource: 通知数据源执行操作（reload, clear 等）
 */
actionRegistry.register('notify', async ({ args, dataSourceManager }) => {
  const channel = args?.channel;
  const payload = args?.payload;

  if (!channel) {
    console.warn('[ActionRegistry] notify: missing channel');
    return;
  }

  switch (channel) {
    case 'dataSource':
      if (!dataSourceManager) {
        console.warn('[ActionRegistry] notify: dataSourceManager not available');
        return;
      }

      const { id, event } = payload || {};
      if (!id) {
        console.warn('[ActionRegistry] notify: missing payload.id for dataSource channel');
        return;
      }

      if (event === 'reload') {
        await dataSourceManager.reload(id);
      } else {
        console.warn('[ActionRegistry] notify: unknown event for dataSource channel:', event);
      }
      break;

    default:
      console.warn('[ActionRegistry] notify: unknown channel:', channel);
  }
});
