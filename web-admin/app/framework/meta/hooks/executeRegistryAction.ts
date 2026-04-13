/**
 * executeRegistryAction - ActionRegistry Action Execution
 *
 * 专门处理 ActionRegistry.execute() 调用
 *
 * 职责:
 * - 构建 ActionContext
 * - 执行 ActionRegistry actions (atomic operations)
 * - 错误传播给调用者
 *
 * 变更记录:
 * - 2025-12-03: 创建 (修复 P1-1 - 拆分 useActionHandler)
 */

import type { NavigateFunction } from 'react-router';
import type { ButtonConfig } from '~/framework/meta/schemas/types';
import type { DataSourceManager } from '~/framework/meta/runtime/data-pipeline/DataSourceManager';
import { actionRegistry } from '~/framework/meta/runtime/actions/ActionRegistry';
import { fetchResult } from '~/services/http-client';
import { buildApiEndpoint } from '~/routes/_shared/dynamic-route-utils';
import { confirmDialog } from '~/utils/confirmDialog';

export interface ExecuteRegistryActionOptions {
  button: ButtonConfig;
  record?: Record<string, any>;
  navigate: NavigateFunction;
  tableName: string;
  context: {
    filters?: Record<string, any>;
    setFilters?: (filters: Record<string, any>) => void;
    pagination?: {
      current: number;
      pageSize: number;
      total: number;
    };
    setPagination?: (pagination: any) => void;
    data?: Record<string, any>;
    setData?: (data: Record<string, any>) => void;
    record?: Record<string, any>;
    loadData?: (...args: any[]) => void | Promise<void>;
    [key: string]: any;
  };
  dataSourceManager: DataSourceManager;
  locale: string;
  t: (key: string) => string;
  token?: string;
  showToast?: (message: string, type: 'success' | 'error' | 'warning' | 'info') => void;
}

/**
 * 执行 ActionRegistry action
 *
 * @param options - 执行选项
 * @returns Promise that resolves when action completes
 * @throws Error if action is not registered or execution fails
 */
export async function executeRegistryAction(options: ExecuteRegistryActionOptions): Promise<void> {
  const {
    button,
    record,
    navigate,
    tableName,
    context,
    dataSourceManager,
    locale,
    t,
    token,
    showToast,
  } = options;

  const code = button.code;
  const handlerArgs = button.events?.onClick?.args || {};

  // 映射 code 到 actionType
  const actionType = code === 'create' ? 'new' : code === 'submit' ? 'save' : code;

  if (!actionRegistry.has(actionType)) {
    throw new Error(`[executeRegistryAction] Action not registered: ${actionType}`);
  }

  // 构建 ActionContext
  const actionContext: any = {
    button,
    args: handlerArgs,
    navigate,
    tableName,
    record: record || context.record,
    dataSourceManager,
    locale,
    t,
    token,
    fetchResult,
    buildApiEndpoint,
    confirm: confirmDialog,
    showToast,
    // 传递所有上下文字段
    ...context,
  };

  // 执行 ActionRegistry action
  await actionRegistry.execute(actionType, actionContext);
}
