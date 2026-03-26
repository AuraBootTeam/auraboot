/**
 * useActionHandler - 统一的 Action 处理 Hook (重构版本)
 *
 * 用于列表页、新建页、编辑页等所有动态页面的按钮操作处理
 *
 * 功能：
 * 1. 支持 ActionRegistry.execute() (Priority 1 - for built-in actions like search, reset)
 * 2. 支持 SchemaRuntime.executeHandler() (Priority 2 - for custom ActionFlow)
 * 3. 自动错误处理和 loading 状态管理
 * 4. 灵活的上下文传递
 *
 * 优先级逻辑 (2025-12-03 更新):
 * - Priority 1: 检查 ActionRegistry 是否有 built-in action (handler 或 code)
 * - Priority 2: 如果有 handler 定义，使用 SchemaRuntime 执行 ActionFlow
 * - Priority 3: 未找到处理器，输出警告
 *
 * 重构历史:
 * - 2025-12-03 (P1-1): 提取 executeSchemaHandler 和 executeRegistryAction 为独立模块
 * - 2025-12-03: 调整优先级，built-in actions 优先于 SchemaRuntime
 * - 简化 useActionHandler 为协调层，委托给专门的执行器
 * - 提高代码可测试性和可维护性
 *
 * 虽然不需要修改，但建议遵循以下命名约定：
 * Built-in actions（使用 ActionRegistry）：
 *     直接使用 action 名称："handler": "search", "handler": "reset"
 *     或省略 handler，只用 code："code": "search"
 * Custom ActionFlow（使用 SchemaRuntime）：
 *    使用描述性名称："handler": "openCreateForm", "handler": "deleteSingleStore"
 *   确保在 DSL 的 handlers 中定义对应的流程
 *
 * 使用示例：
 * ```typescript
 * const { handleAction, loading, error } = useActionHandler({
 *   runtime,
 *   navigate,
 *   tableName,
 *   context: {
 *     loadData,
 *     filters,
 *     setFilters,
 *     pagination,
 *     setPagination,
 *   },
 *   dataSourceManager,
 *   locale,
 *   t,
 *   token,
 * });
 * ```
 */

import { useState, useCallback } from 'react';
import type { ButtonConfig } from '~/meta/schemas/types';
import type { SchemaRuntime } from '~/meta/runtime/schema-runtime';
import type { DataSourceManager } from '~/meta/runtime/data-pipeline/DataSourceManager';
import { executeSchemaHandler } from '~/meta/hooks/executeSchemaHandler';
import { executeRegistryAction } from '~/meta/hooks/executeRegistryAction';
import { fetchResult } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';
import { resolveConfirmDialog } from '~/meta/utils/i18nResolver';
import { buildApiEndpoint } from '~/routes/_shared/dynamic-route-utils';
import { confirmDialog } from '~/utils/confirmDialog';
import { getLocalizedText } from '~/meta/runtime/expression/i18n-renderer';
import { normalizeAction, normalizeButtonProps } from '~/meta/utils/normalizeAction';

// Navigate function type (compatible with react-router v7)
import type { NavigateFunction as RouterNavigateFunction } from 'react-router';
type NavigateFunction = RouterNavigateFunction;

export interface UseActionHandlerOptions {
  // SchemaRuntime (可选 - 用于 ActionFlow 支持)
  runtime?: SchemaRuntime | null;

  // 路由相关
  navigate: NavigateFunction;
  tableName: string;

  // 业务上下文 (可选 - 根据页面类型提供不同的上下文)
  context?: {
    // 列表页上下文
    loadData?: (...args: any[]) => void | Promise<void>;
    filters?: Record<string, any>;
    setFilters?: (filters: Record<string, any>) => void;
    pagination?: {
      current: number;
      pageSize: number;
      total: number;
    };
    setPagination?: (pagination: any) => void;

    // 表单页上下文
    data?: Record<string, any>;
    setData?: (data: Record<string, any>) => void;

    // 行数据 (列表操作时)
    record?: Record<string, any>;

    // 其他自定义上下文
    [key: string]: any;
  };

  // DataSource 管理器
  dataSourceManager?: DataSourceManager | null;

  // i18n
  locale: string;
  t: (key: string) => string;

  // 认证 token
  token?: string;

  // Toast (可选)
  showToast?: (message: string, type: 'success' | 'error' | 'warning' | 'info') => void;

  // 错误回调 (可选)
  onError?: (error: Error) => void;
}

export interface UseActionHandlerResult {
  handleAction: (button: ButtonConfig, record?: Record<string, any>) => Promise<void>;
  loading: boolean;
  error: string | null;
  setError: (error: string | null) => void;
}

/**
 * 统一的 Action 处理 Hook
 */
export function useActionHandler(options: UseActionHandlerOptions): UseActionHandlerResult {
  const {
    runtime,
    navigate,
    tableName,
    context = {},
    dataSourceManager,
    locale,
    t,
    token,
    showToast,
    onError,
  } = options;

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  /**
   * Execute a command via the Command Engine API
   */
  const executeCommand = useCallback(
    async (
      commandCode: string,
      targetRecordId?: string,
      payload?: Record<string, any>,
      operationType?: string,
    ) => {
      const normalizedOp = operationType?.toUpperCase();
      if ((normalizedOp === 'update' || normalizedOp === 'delete') && !targetRecordId) {
        throw new Error(
          `Command ${commandCode} requires targetRecordId when operationType=${normalizedOp}`,
        );
      }
      if (normalizedOp === 'create' && targetRecordId) {
        throw new Error(
          `Command ${commandCode} should not carry targetRecordId when operationType=CREATE`,
        );
      }

      const body: Record<string, any> = {
        targetRecordId,
        payload: payload || {},
      };
      if (normalizedOp) {
        body.operationType = normalizedOp;
      }
      const result = await fetchResult(`/api/meta/commands/execute/${commandCode}`, {
        method: 'post',
        params: body,
        token,
      });

      if (!ResultHelper.isSuccess(result)) {
        throw new Error(result.desc || result.message || `Command ${commandCode} failed`);
      }

      return result.data;
    },
    [token],
  );

  /**
   * Resolve navigateTo pageKey to a route path
   *
   * Supported formats:
   * - Cross-designer prefixes:
   *   - "dashboard:{code}" -> /dashboards/view/{code}
   *   - "bpmn-status:{processKey}" -> /bpm/process-status?processKey={processKey}&businessKey={recordId}
   *   - "automation:{pid}" -> /automation/{pid}
   * - Legacy "{modelCode}_{pageType}" e.g. "qo_daily_report_form"
   */
  const resolveNavigateTo = useCallback((pageKey: string, record?: Record<string, any>) => {
    const recordId = record?.pid || record?.id;

    // Absolute path with template variables — OCP compliant
    // DSL can write navigateTo: "/dashboard-designer/{pid}" or "/bpmn-designer?pid={pid}"
    if (pageKey.startsWith('/')) {
      return pageKey.replace(/\{(\w+)\}/g, (_, key) => {
        if (record && key in record) return encodeURIComponent(String(record[key] ?? ''));
        if (key === 'pid' || key === 'id') return encodeURIComponent(String(recordId ?? ''));
        return '';
      });
    }

    // Cross-designer navigation: dashboard:{code}
    if (pageKey.startsWith('dashboard:')) {
      const code = pageKey.substring('dashboard:'.length);
      return `/dashboards/view/${code}`;
    }

    // Cross-designer navigation: bpmn-status:{processKey}
    if (pageKey.startsWith('bpmn-status:')) {
      const processKey = pageKey.substring('bpmn-status:'.length);
      const params = new URLSearchParams({ processKey });
      if (recordId) {
        params.set('businessKey', String(recordId));
      }
      return `/bpm/process-status?${params.toString()}`;
    }

    // Cross-designer navigation: automation:{pid}
    if (pageKey.startsWith('automation:')) {
      const pid = pageKey.substring('automation:'.length);
      return `/automation/${pid}`;
    }

    // Cross-designer navigation: bpmn-designer:{pid}
    if (pageKey.startsWith('bpmn-designer:')) {
      const pid = pageKey.substring('bpmn-designer:'.length);
      return pid ? `/bpmn-designer?pid=${pid}` : '/bpmn-designer';
    }

    // Legacy format: "{modelCode}_{pageType}"
    // Parse pageKey: last segment is the page type (list/form/detail)
    const lastUnderscoreIdx = pageKey.lastIndexOf('_');
    const suffix = pageKey.substring(lastUnderscoreIdx + 1);
    const modelCodePart = pageKey.substring(0, lastUnderscoreIdx);

    // Keep model code as-is (underscores) to match page schema keys

    switch (suffix) {
      case 'form':
        // Route pattern: /dynamic/:tableName/:recordId/edit (see routes.ts)
        return recordId
          ? `/dynamic/${modelCodePart}/${recordId}/edit`
          : `/dynamic/${modelCodePart}/new`;
      case 'detail':
      case 'view':
        return `/dynamic/${modelCodePart}/view/${recordId}`;
      case 'list':
        return `/dynamic/${modelCodePart}`;
      default:
        // Fallback: treat as list page
        return `/dynamic/${modelCodePart}`;
    }
  }, []);

  /**
   * Show confirmation dialog and return user's choice
   */
  const showConfirmDialog = useCallback(
    async (messageKey: string): Promise<boolean> => {
      const { title, content } = resolveConfirmDialog(messageKey, t);
      return confirmDialog({ title, content, variant: 'danger' });
    },
    [t],
  );

  const handleAction = useCallback(
    async (button: ButtonConfig, record?: Record<string, any>) => {
      const normalizedButton = normalizeButtonProps(button);
      const actionDef = normalizeAction(normalizedButton);
      const confirmKey = (normalizedButton as any).confirm || normalizedButton.confirmMessageKey;

      try {
        setLoading(true);
        setError(null);

        switch (actionDef.type) {
          case 'command': {
            if (confirmKey) {
              const confirmed = await showConfirmDialog(confirmKey);
              if (!confirmed) return;
            }
            const targetRecordId = record?.pid || (context.data?.pid as string | undefined);
            const payload = record || context.data || {};
            const btnLabel = normalizedButton.label;
            const btnCode = normalizedButton.code;
            const operationType =
              btnLabel === 'delete' || btnCode === 'delete'
                ? 'delete'
                : btnLabel === 'create' || btnCode === 'create'
                  ? 'create'
                  : btnLabel === 'update' || btnCode === 'update'
                    ? 'update'
                    : targetRecordId
                      ? 'update'
                      : undefined;
            await executeCommand(actionDef.command, targetRecordId, payload, operationType);
            if (context.loadData) {
              await context.loadData();
            } else {
              navigate(`/dynamic/${tableName}`);
            }
            return;
          }

          case 'navigate': {
            const path = resolveNavigateTo(actionDef.to, record);
            if (actionDef.command) {
              const isEditAction =
                normalizedButton.label === 'edit' ||
                normalizedButton.label === 'update' ||
                normalizedButton.code === 'edit' ||
                normalizedButton.code === 'update';
              const sep = path.includes('?') ? '&' : '?';
              const params = [`commandCode=${encodeURIComponent(actionDef.command)}`];
              const sourceRecordId = record?.pid;
              if (!isEditAction && sourceRecordId) {
                params.push(`sourceRecordId=${encodeURIComponent(sourceRecordId)}`);
              }
              navigate(`${path}${sep}${params.join('&')}`);
            } else {
              navigate(path);
            }
            return;
          }

          case 'builtin': {
            if (actionDef.name === 'back') {
              navigate(-1 as any);
              return;
            }
            if (dataSourceManager) {
              const { actionRegistry } = await import('~/meta/runtime/actions/ActionRegistry');
              if (actionRegistry.has(actionDef.name)) {
                await executeRegistryAction({
                  button: normalizedButton,
                  record,
                  navigate,
                  tableName,
                  context,
                  dataSourceManager,
                  locale,
                  t,
                  token,
                  showToast,
                });
                return;
              }
            }
            console.warn(`[useActionHandler] Unknown builtin action: ${actionDef.name}`);
            return;
          }

          case 'flow': {
            if (confirmKey) {
              const confirmed = await showConfirmDialog(confirmKey);
              if (!confirmed) return;
            }

            if ('handler' in actionDef && actionDef.handler) {
              if (runtime) {
                await executeSchemaHandler({
                  runtime,
                  button: {
                    ...normalizedButton,
                    events: { onClick: { handler: actionDef.handler } },
                  },
                  record,
                  context,
                });
                return;
              }
              console.warn(
                `[useActionHandler] Flow handler "${actionDef.handler}" requires SchemaRuntime`,
              );
            } else if ('steps' in actionDef && actionDef.steps) {
              if (runtime) {
                const flowRunner = runtime.getFlowRunner();
                if (flowRunner) {
                  const freshContext = runtime.getContext();
                  const { fetchResult: fr } = await import('~/services/http-client');
                  (freshContext as any).fetchResult = fr;
                  const contextWithRecord = { ...freshContext, record, row: record };
                  await flowRunner.run(actionDef.steps, contextWithRecord as any);
                  if (context.loadData) await context.loadData();
                  return;
                }
              }
              // Fallback: execute steps via ActionRegistry directly
              const { actionRegistry } = await import('~/meta/runtime/actions/ActionRegistry');
              const { fetchResult: fr } = await import('~/services/http-client');
              for (const step of actionDef.steps) {
                if (step.action && actionRegistry.has(step.action)) {
                  await actionRegistry.execute(step.action, {
                    args: step.args,
                    navigate,
                    showToast: showToast as any,
                    token,
                    fetchResult: fr,
                    stepEndpoint: step.endpoint?.replace(/\{(\w+)\}/g, (_, key) => {
                      if (record && key in record)
                        return encodeURIComponent(String(record[key] ?? ''));
                      return '';
                    }),
                    stepMethod: step.method,
                    stepBody: step.body,
                    stepTarget: step.target,
                  });
                }
              }
              if (context.loadData) await context.loadData();
            }
            return;
          }
        }
      } catch (err) {
        const errorMessage = err instanceof Error ? err.message : 'Action execution failed';
        console.error(`[useActionHandler] Action execution failed (${button.code}):`, err);
        setError(errorMessage);
        if (onError) onError(err as Error);
      } finally {
        setLoading(false);
      }
    },
    [
      runtime,
      navigate,
      tableName,
      context,
      dataSourceManager,
      locale,
      t,
      token,
      showToast,
      onError,
      executeCommand,
      resolveNavigateTo,
      showConfirmDialog,
    ],
  );

  return {
    handleAction,
    loading,
    error,
    setError,
  };
}
