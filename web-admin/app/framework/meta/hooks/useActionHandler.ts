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
import type { ButtonConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import type { DataSourceManager } from '~/framework/meta/runtime/data-pipeline/DataSourceManager';
import { executeSchemaHandler } from '~/framework/meta/hooks/executeSchemaHandler';
import { executeRegistryAction } from '~/framework/meta/hooks/executeRegistryAction';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { resolveConfirmDialog } from '~/framework/meta/utils/i18nResolver';
import { getLocalizedText } from '~/framework/meta/runtime/expression/i18n-renderer';
import { confirmDialog } from '~/utils/confirmDialog';
import { normalizeAction, normalizeButtonProps } from '~/framework/meta/utils/normalizeAction';
import {
  pickFile,
  uploadCommandFile,
  resolvePromptUploadAccept,
  resolvePromptUploadFeedbackMode,
  resolvePromptUploadKey,
  resolvePromptUploadFilenameKey,
} from '~/framework/meta/utils/promptUpload';
import { promptInputForm } from '~/framework/meta/runtime/actions/ActionRegistry';
import type { AsyncTask } from '~/framework/meta/rendering/components/AsyncTaskProgressModal';
import { useAsyncTaskModalSink } from '~/framework/meta/rendering/components/AsyncTaskModalContext';
import {
  buildCommandTargetParams,
  getLegacyCompatibleRecordPid,
} from '~/framework/meta/utils/publicRecordId';

// Navigate function type (compatible with react-router v7)
import type { NavigateFunction as RouterNavigateFunction } from 'react-router';
type NavigateFunction = RouterNavigateFunction;

function firstNonBlankString(...values: unknown[]): string | undefined {
  for (const value of values) {
    if (typeof value !== 'string') continue;
    const trimmed = value.trim();
    if (trimmed.length > 0) return trimmed;
  }
  return undefined;
}

function toNonBlankString(value: unknown): string | undefined {
  if (value === undefined || value === null) return undefined;
  const text = String(value).trim();
  return text.length > 0 ? text : undefined;
}

function readPath(source: unknown, path: string): unknown {
  if (!source || typeof source !== 'object') return undefined;
  return path.split('.').reduce<unknown>((current, segment) => {
    if (!current || typeof current !== 'object') return undefined;
    return (current as Record<string, unknown>)[segment];
  }, source);
}

function resolveRuntimeTemplate(value: unknown, runtimeContext: Record<string, unknown>): unknown {
  if (typeof value !== 'string') return value;
  const exact = value.trim().match(/^\$\{(.+)\}$/);
  if (exact) return readPath(runtimeContext, exact[1]);
  return value.replace(/\$\{([^}]+)\}/g, (_match, path) =>
    String(readPath(runtimeContext, path) ?? ''),
  );
}

function resolveCommandTargetRecordId(
  actionDef: Record<string, unknown>,
  runtimeContext: Record<string, unknown>,
  record: Record<string, any> | undefined,
  context: Record<string, any>,
): string | undefined {
  const explicitTarget = resolveRuntimeTemplate(
    actionDef.targetRecordPid ?? actionDef.targetRecordPid,
    runtimeContext,
  );
  return (
    toNonBlankString(explicitTarget) ||
    getLegacyCompatibleRecordPid(record) ||
    getLegacyCompatibleRecordPid(context.data)
  );
}

function resolveCommandRefreshIds(
  actionDef: Record<string, unknown>,
  button: Record<string, unknown>,
): string[] | undefined {
  const rawRefresh = actionDef.refresh ?? actionDef.reload ?? button.refresh ?? button.reload;
  if (Array.isArray(rawRefresh)) {
    const ids = rawRefresh.map((item) => toNonBlankString(item)).filter(Boolean) as string[];
    return ids.length > 0 ? ids : undefined;
  }
  const singleId = toNonBlankString(rawRefresh);
  return singleId ? [singleId] : undefined;
}

function resolveCommandPayload(
  actionDef: Record<string, unknown>,
  runtimeContext: Record<string, unknown>,
): Record<string, any> {
  const configured = actionDef.payload;
  if (!configured || typeof configured !== 'object' || Array.isArray(configured)) {
    return {};
  }
  const out: Record<string, any> = {};
  for (const [key, value] of Object.entries(configured as Record<string, unknown>)) {
    out[key] = resolveRuntimeTemplate(value, runtimeContext);
  }
  return out;
}

function formatMessage(params: Record<string, any>, fallback: string): string {
  return Object.entries(params).reduce(
    (text, [paramKey, paramValue]) => text.split(`{${paramKey}}`).join(String(paramValue)),
    fallback,
  );
}

function uploadMessageFallback(
  locale: string,
  phase: 'selected' | 'uploaded' | 'completed',
): string {
  const isChinese = locale.toLowerCase().startsWith('zh');
  if (isChinese) {
    if (phase === 'selected') return '已选择 {filename}，正在上传文件...';
    if (phase === 'uploaded') return '{filename} 已上传，正在处理...';
    return '{filename} 已处理完成';
  }
  if (phase === 'selected') return '{filename} selected; uploading file...';
  if (phase === 'uploaded') return '{filename} uploaded; running action...';
  return '{filename} processed successfully';
}

function unwrapCommandResultData(commandResult: unknown): Record<string, any> {
  if (!commandResult || typeof commandResult !== 'object') return {};
  const envelope = commandResult as Record<string, any>;
  const data = envelope.data;
  return data && typeof data === 'object' ? data : envelope;
}

function buildPromptUploadCompletedMessage(
  locale: string,
  fileName: string,
  commandResult: unknown,
): string {
  const data = unwrapCommandResultData(commandResult);
  const ruleVersion = toNonBlankString(data.ruleVersion);
  const importedLines = data.importedLines;
  const status = toNonBlankString(data.status)?.toLowerCase();
  const isChinese = locale.toLowerCase().startsWith('zh');

  if (ruleVersion && importedLines != null) {
    if (isChinese) {
      const statusText = status === 'draft' ? '草稿' : '规则';
      const effectiveNote = status === 'draft' ? '；发布后生效' : '';
      return `${fileName} 已导入为${statusText} ${ruleVersion}，共 ${importedLines} 行${effectiveNote}`;
    }
    const statusText = status === 'draft' ? 'draft' : 'rule set';
    const effectiveNote = status === 'draft' ? '; publish it to take effect' : '';
    return `${fileName} imported as ${statusText} ${ruleVersion}, ${importedLines} lines${effectiveNote}`;
  }

  return formatMessage(
    { filename: fileName || 'file' },
    uploadMessageFallback(locale, 'completed'),
  );
}

type ActionToastType = 'success' | 'error' | 'warning' | 'info';
interface ExecuteCommandOptions {
  suppressAsyncSubmitToast?: boolean;
  asyncTaskLabel?: string;
}

function notifyActionToast(
  showToast: ((message: string, type: ActionToastType) => void) | undefined,
  message: string,
  type: ActionToastType = 'info',
): void {
  if (showToast) {
    showToast(message, type);
    return;
  }

  if (
    typeof window !== 'undefined' &&
    typeof window.dispatchEvent === 'function' &&
    typeof CustomEvent !== 'undefined'
  ) {
    window.dispatchEvent(
      new CustomEvent('aura:toast', {
        detail: { message, variant: type },
      }),
    );
  }
}

/**
 * Pull the user-facing reason out of a failed command response. The backend puts it in
 * `context.detail` (localized); `message` / `desc` are the generic envelope text
 * ("Business error"), so they must stay last. Shared with the DSL form page so both
 * command execution paths surface the same reason.
 */
export function resolveCommandErrorMessage(result: unknown, commandCode: string): string {
  const body = (result || {}) as Record<string, any>;
  return (
    firstNonBlankString(
      body.context?.detail,
      body.context?.error,
      body.context?.exception,
      body.data?.context?.detail,
      body.data?.context?.error,
      body.data?.detail,
      body.data?.error,
      body.data?.message,
      body.message,
      body.desc,
    ) || `Command ${commandCode} failed`
  );
}

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
  /**
   * Translator. Accepts optional fallback string returned when the key is
   * unresolved (matches `I18nContextType.t`). Callers that pass a
   * single-argument `(key) => string` are forward-compatible — the extra
   * parameter is simply ignored.
   */
  t: (key: string, params?: Record<string, any>, fallback?: string) => string;

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
  /**
   * The live async task driving the progress modal. Non-null from the moment an
   * async command is dispatched until the host dismisses the modal. The host
   * (e.g. ListPageContent) renders `<AsyncTaskProgressModal task={activeTask}/>`
   * and disables the triggering button while this is non-null & non-terminal.
   */
  activeTask: AsyncTask | null;
  /** Dismiss the progress modal (clears `activeTask`). */
  clearActiveTask: () => void;
  /**
   * Execute a command directly by code, without going through the action button
   * pipeline. Exposed so callers (e.g. ReferenceCreateDialog) can fire commands
   * without needing a ButtonConfig.
   */
  executeCommand: (
    commandCode: string,
    targetRecordPid?: string,
    payload?: Record<string, any>,
    operationType?: string,
    options?: ExecuteCommandOptions,
  ) => Promise<any>;
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
  const effectiveShowToast = showToast ?? runtime?.getShowToast?.();
  const notifyToast = useCallback(
    (message: string, type: ActionToastType = 'info') => {
      notifyActionToast(effectiveShowToast, message, type);
    },
    [effectiveShowToast],
  );

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Live async task backing the progress modal (running → terminal). Kept after
  // terminal so the modal can show the final summary until the host dismisses.
  // Shared via context when a provider is present (so a command dispatched from
  // a ToolbarBlockRenderer's own hook instance surfaces in the page's single
  // modal); falls back to local state otherwise.
  const modalSink = useAsyncTaskModalSink();
  const [localTask, setLocalTask] = useState<AsyncTask | null>(null);
  const activeTask = modalSink ? modalSink.activeTask : localTask;
  const setActiveTask = modalSink ? modalSink.setActiveTask : setLocalTask;
  const clearActiveTask = useCallback(() => setActiveTask(null), [setActiveTask]);

  /**
   * Poll an async task to completion. A command declaring handlerParams.async
   * returns immediately with { async:true, taskCode }; we then poll the task
   * status endpoint (each poll is a fast GET, so no single long request trips
   * the BFF proxy timeout). Resolves with the task result data on success.
   */
  const pollAsyncTask = useCallback(
    async (taskCode: string, taskLabel?: string): Promise<any> => {
      const TERMINAL = new Set(['completed', 'failed', 'cancelled']);
      // ~15 min cap at 1.5s intervals — generous for bulk imports.
      for (let attempt = 0; attempt < 600; attempt++) {
        const res = await fetchResult(`/api/async-tasks/${encodeURIComponent(taskCode)}`, {
          method: 'get',
          token,
        });
        if (!ResultHelper.isSuccess(res)) {
          throw new Error(
            (res as any).desc || (res as any).message || 'Async task status unavailable',
          );
        }
        const task = (res as any).data || {};
        const status = String(task.status || '').toLowerCase();
        const presentation = task.inputParams?.handlerParams?.taskPresentation;
        // Feed the live task into the progress modal each tick. Toast is kept as
        // a fallback for hosts that don't render the modal.
        setActiveTask({
          status,
          taskCode: task.taskCode || taskCode,
          taskType: task.taskType,
          taskName: task.taskName,
          taskLabel: taskLabel || task.taskName,
          locale,
          progress: typeof task.progress === 'number' ? task.progress : undefined,
          progressMessage: task.progressMessage,
          resultData: task.resultData,
          errorMessage: task.errorMessage,
          presentation: presentation && typeof presentation === 'object' ? presentation : undefined,
        });
        // No per-tick toast — the progress modal (or background chip) is the live
        // UI. A toast here would spam raw progress text (incl. the JSON message).
        if (TERMINAL.has(status)) {
          if (status === 'completed') return task.resultData ?? {};
          if (status === 'cancelled') throw new Error('Task cancelled');
          // Failed: the modal is already showing the failed state (set above);
          // return a sentinel instead of throwing so the error surfaces in the
          // modal rather than the page-level error boundary.
          return { __asyncFailed: true, errorMessage: task.errorMessage };
        }
        await new Promise((resolve) => setTimeout(resolve, 1500));
      }
      throw new Error('Async task timed out');
    },
    [token, setActiveTask, locale],
  );

  /**
   * Execute a command via the Command Engine API
   */
  const executeCommand = useCallback(
    async (
      commandCode: string,
      targetRecordPid?: string,
      payload?: Record<string, any>,
      operationType?: string,
      commandOptions: ExecuteCommandOptions = {},
    ) => {
      const normalizedOp = operationType?.toUpperCase();
      if ((normalizedOp === 'update' || normalizedOp === 'delete') && !targetRecordPid) {
        throw new Error(
          `Command ${commandCode} requires targetRecordPid when operationType=${normalizedOp}`,
        );
      }
      if (normalizedOp === 'create' && targetRecordPid) {
        throw new Error(
          `Command ${commandCode} should not carry targetRecordPid when operationType=CREATE`,
        );
      }

      const body: Record<string, any> = {
        ...buildCommandTargetParams(targetRecordPid),
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
        throw new Error(resolveCommandErrorMessage(result, commandCode));
      }

      // Async dispatch: handlerParams.async commands return immediately with a
      // taskCode; poll the task to completion so the UI reflects the real result
      // without a single long request (which would hit the BFF 30s timeout).
      // The command engine wraps the handler result one level deep:
      // result.data = { commandCode, phaseReached, data: { async, taskCode } },
      // so the async marker lives on result.data.data (fall back to result.data).
      const envelope = result.data as any;
      const dispatch =
        envelope?.data && typeof envelope.data === 'object' ? envelope.data : envelope;
      if (dispatch && dispatch.async === true && dispatch.taskCode) {
        if (!commandOptions.suppressAsyncSubmitToast) {
          notifyToast('已提交,后台处理中…', 'info');
        }
        // Open the progress modal immediately in a running state; pollAsyncTask
        // then refreshes it each tick until terminal.
        setActiveTask({
          status: 'running',
          taskCode: dispatch.taskCode,
          taskType: dispatch.taskType,
          taskLabel: commandOptions.asyncTaskLabel,
          locale,
          progress: 0,
        });
        return await pollAsyncTask(dispatch.taskCode, commandOptions.asyncTaskLabel);
      }

      return result.data;
    },
    [token, notifyToast, pollAsyncTask, setActiveTask, locale],
  );

  /**
   * Resolve navigateTo pageKey to a route path
   *
   * Supported formats:
   * - Cross-designer prefixes:
   *   - "dashboard:{code}" -> /dashboards/view/{code}
   *   - "bpmn-status:{processKey}" -> /bpm/process-status?processKey={processKey}&businessKey={recordPid}
   *   - "automation:{pid}" -> /automation/{pid}
   * - Legacy "{modelCode}_{pageType}" e.g. "qo_daily_report_form"
   */
  const resolveNavigateTo = useCallback(
    (pageKey: string | undefined, record?: Record<string, any>) => {
      if (!pageKey) {
        console.error('[useActionHandler] navigate action is missing both "to" and "url" fields');
        return '';
      }
      const recordPid = getLegacyCompatibleRecordPid(record);

      // Absolute path with template variables — OCP compliant
      // DSL can write navigateTo: "/dashboard-designer/{pid}" or "/bpmn-designer?pid={pid}"
      if (pageKey.startsWith('/')) {
        return pageKey.replace(/\{(\w+)\}/g, (_, key) => {
          if (record && key in record) return encodeURIComponent(String(record[key] ?? ''));
          if (key === 'pid' || key === 'id') return encodeURIComponent(String(recordPid ?? ''));
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
        if (recordPid) {
          params.set('businessKey', String(recordPid));
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
          // Route pattern: /p/:pageKey/edit/:recordPid (see routes.ts)
          return recordPid ? `/p/${modelCodePart}/edit/${recordPid}` : `/p/${modelCodePart}/new`;
        case 'detail':
        case 'view':
          return `/p/${modelCodePart}/view/${recordPid}`;
        case 'list':
          return `/p/${modelCodePart}`;
        default:
          // Fallback: treat as list page
          return `/p/${modelCodePart}`;
      }
    },
    [],
  );

  /**
   * Show confirmation dialog and return user's choice
   */
  const showConfirmDialog = useCallback(
    async (messageKey: string | Record<string, string>): Promise<boolean> => {
      const { title, content } = resolveConfirmDialog(messageKey, t);
      return confirmDialog({ title, content, variant: 'danger' });
    },
    [t],
  );

  const surfaceTemporaryPassword = useCallback(
    (commandResult: unknown) => {
      const data =
        commandResult && typeof commandResult === 'object' && 'data' in commandResult
          ? (commandResult as Record<string, unknown>).data
          : commandResult;
      if (!data || typeof data !== 'object') return;
      const tempPassword = (data as Record<string, unknown>).tempPassword;
      if (typeof tempPassword !== 'string' || tempPassword.length === 0) return;

      void navigator.clipboard?.writeText(tempPassword).catch(() => undefined);
      void confirmDialog({
        title: '临时密码已生成',
        content: `请立即保存并交付给用户，临时密码只显示一次：${tempPassword}`,
        confirmText: '我已保存',
        cancelText: '关闭',
      });
      notifyToast(`临时密码已生成并尝试复制: ${tempPassword}`, 'success');
    },
    [notifyToast],
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
            const runtimeContext = (runtime?.getContext?.() ?? {}) as Record<string, unknown>;
            const targetRecordPid = resolveCommandTargetRecordId(
              actionDef as unknown as Record<string, unknown>,
              runtimeContext,
              record,
              context,
            );
            let payload = {
              ...(record || context.data || {}),
              ...resolveCommandPayload(
                actionDef as unknown as Record<string, unknown>,
                runtimeContext,
              ),
            };
            const inputFields = Array.isArray((actionDef as any).inputFields)
              ? (actionDef as any).inputFields
              : Array.isArray((normalizedButton as any).inputFields)
                ? (normalizedButton as any).inputFields
                : [];
            if (inputFields.length > 0) {
              let collectedInputs: Record<string, any>;
              try {
                collectedInputs = await promptInputForm(
                  inputFields,
                  (actionDef as any).inputFieldsTitle ?? (normalizedButton as any).inputFieldsTitle,
                  fetchResult,
                );
              } catch {
                return;
              }
              payload = {
                ...payload,
                ...collectedInputs,
              };
            }
            // `promptUpload`: collect a file from the user, upload it, and inject the
            // resulting file id into the payload before the command runs. Strictly
            // guarded by the flag, so non-upload buttons are unaffected.
            const promptUpload = (normalizedButton as any).promptUpload;
            const promptUploadUsesPanelFeedback =
              resolvePromptUploadFeedbackMode(promptUpload) === 'panel';
            if (promptUpload) {
              // Don't keep the button disabled while the OS file picker is open:
              // some browsers don't fire a 'cancel' event, so awaiting pickFile()
              // would otherwise hang the loading state and leave the button stuck.
              setLoading(false);
              const file = await pickFile(resolvePromptUploadAccept(promptUpload));
              if (!file) return; // user dismissed the picker — nothing to do
              setLoading(true);
              if (!promptUploadUsesPanelFeedback) {
                notifyToast(
                  formatMessage({ filename: file.name }, uploadMessageFallback(locale, 'selected')),
                  'info',
                );
              }
              const fileId = await uploadCommandFile(file, token);
              if (!promptUploadUsesPanelFeedback) {
                notifyToast(
                  formatMessage({ filename: file.name }, uploadMessageFallback(locale, 'uploaded')),
                  'info',
                );
              }
              payload = {
                ...payload,
                [resolvePromptUploadKey(promptUpload)]: fileId,
                [resolvePromptUploadFilenameKey(promptUpload)]: file.name,
              };
            }
            const btnLabel = normalizedButton.label;
            const btnCode = normalizedButton.code;
            const explicitCommand =
              typeof actionDef.command === 'string' && actionDef.command ? actionDef.command : '';
            const semanticActionText =
              `${btnLabel ?? ''} ${btnCode ?? ''} ${explicitCommand}`.toLowerCase();
            const explicitOperationType = toNonBlankString((actionDef as any).operationType);
            const operationType =
              explicitOperationType ||
              (semanticActionText.includes('delete')
                ? 'delete'
                : semanticActionText.includes('create')
                  ? 'create'
                  : semanticActionText.includes('update')
                    ? 'update'
                    : targetRecordPid
                      ? 'update'
                      : undefined);
            const refreshIds = resolveCommandRefreshIds(
              actionDef as unknown as Record<string, unknown>,
              normalizedButton as unknown as Record<string, unknown>,
            );
            // Convention over configuration: a command-type button with no
            // explicit command (e.g. a standard row delete) routes through the
            // model's CRUD command the server resolved onto schema.commands,
            // keyed by the derived operationType (create/update/delete). Explicit
            // command still wins.
            const effectiveCommand =
              explicitCommand ||
              (operationType ? runtime?.getSchema?.()?.commands?.[operationType] : undefined) ||
              undefined;
            if (!effectiveCommand) {
              throw new Error(
                `No command resolved for button "${normalizedButton.code}": ` +
                  `no explicit command and no convention "${operationType}" command on the model`,
              );
            }
            const commandResult = await executeCommand(
              effectiveCommand,
              targetRecordPid,
              payload,
              operationType,
              {
                suppressAsyncSubmitToast: promptUploadUsesPanelFeedback,
                asyncTaskLabel: getLocalizedText(
                  normalizedButton.label || normalizedButton.content || normalizedButton.code,
                  locale,
                  t,
                ),
              },
            );
            surfaceTemporaryPassword(commandResult);
            if ((commandResult as any)?.__asyncFailed) {
              if (context.loadData) {
                await context.loadData();
              }
              if (refreshIds && dataSourceManager?.reload) {
                await dataSourceManager.reload(refreshIds);
              }
              return;
            }
            if (context.loadData) {
              await context.loadData();
            }
            if (refreshIds && dataSourceManager?.reload) {
              await dataSourceManager.reload(refreshIds);
            }
            if (!context.loadData && !(refreshIds && dataSourceManager?.reload)) {
              navigate(`/p/${tableName}`);
            }
            if (promptUpload && !promptUploadUsesPanelFeedback) {
              const fileName = toNonBlankString(
                payload[resolvePromptUploadFilenameKey(promptUpload)],
              );
              notifyToast(
                buildPromptUploadCompletedMessage(locale, fileName || 'file', commandResult),
                'success',
              );
            }
            return;
          }

          case 'navigate': {
            const path = resolveNavigateTo(actionDef.to, record);
            // Absolute backend/external URLs (e.g. a file-download endpoint) are
            // real browser navigations, not client-side routes — open them so the
            // browser handles the Content-Disposition download.
            if (actionDef.hardReload === true) {
              window.open(path, '_self');
              return;
            }
            if (/^(https?:)?\/\//.test(path) || path.startsWith('/api/')) {
              window.open(path, '_blank', 'noopener');
              return;
            }
            if (actionDef.command) {
              const isEditAction =
                normalizedButton.label === 'edit' ||
                normalizedButton.label === 'update' ||
                normalizedButton.code === 'edit' ||
                normalizedButton.code === 'update';
              const sep = path.includes('?') ? '&' : '?';
              const params = [`commandCode=${encodeURIComponent(actionDef.command)}`];
              const sourceRecordPid = record?.pid;
              if (!isEditAction && sourceRecordPid) {
                params.push(`sourceRecordPid=${encodeURIComponent(sourceRecordPid)}`);
              }
              navigate(`${path}${sep}${params.join('&')}`);
            } else {
              navigate(path);
            }
            return;
          }

          case 'state_transition': {
            // state_transition commands update the target record's status field.
            // They are DSL commands executed via the command engine, same as type=command,
            // but always require a targetRecordPid and always use operationType=update.
            if (confirmKey) {
              const confirmed = await showConfirmDialog(confirmKey);
              if (!confirmed) return;
            }
            const targetRecordPid = record?.pid || (context.data?.pid as string | undefined);
            if (!targetRecordPid) {
              throw new Error(
                `state_transition command ${actionDef.command} requires a target record`,
              );
            }
            await executeCommand(actionDef.command, targetRecordPid, {}, 'update');
            if (context.loadData) {
              await context.loadData();
            } else {
              navigate(`/p/${tableName}`);
            }
            return;
          }

          case 'builtin': {
            if (actionDef.name === 'back') {
              navigate(-1 as any);
              return;
            }
            if (dataSourceManager) {
              const { actionRegistry } =
                await import('~/framework/meta/runtime/actions/ActionRegistry');
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
                  showToast: notifyToast,
                });
                return;
              }
            }
            console.warn(`[useActionHandler] Unknown builtin action: ${actionDef.name}`);
            return;
          }

          case 'bpm': {
            if (confirmKey) {
              const confirmed = await showConfirmDialog(confirmKey);
              if (!confirmed) return;
            }
            const { processDefinitionKey, businessKeyField, variables: varMap } = actionDef;
            const src: Record<string, any> = record || context.data || {};
            const businessKeyRaw = src[businessKeyField];
            if (
              businessKeyRaw === undefined ||
              businessKeyRaw === null ||
              String(businessKeyRaw).trim() === ''
            ) {
              throw new Error(
                `action.type=bpm: record missing or blank businessKeyField "${businessKeyField}"`,
              );
            }
            const resolvedVars: Record<string, unknown> = {};
            if (varMap) {
              for (const [k, expr] of Object.entries(varMap)) {
                if (typeof expr !== 'string') continue;
                if (!expr.startsWith('$')) {
                  resolvedVars[k] = expr; // literal
                  continue;
                }
                if (expr.includes('[')) {
                  throw new Error(
                    `action.type=bpm: JSONPath bracket syntax not supported: "${expr}"`,
                  );
                }
                const stripped = expr.startsWith('$.') ? expr.slice(2) : expr.slice(1);
                if (stripped === '') continue;
                let cursor: unknown = src;
                let resolved = true;
                for (const part of stripped.split('.')) {
                  if (cursor && typeof cursor === 'object' && part in (cursor as object)) {
                    cursor = (cursor as Record<string, unknown>)[part];
                  } else {
                    resolved = false;
                    break;
                  }
                }
                if (resolved) resolvedVars[k] = cursor;
              }
            }
            const { startProcessFromAction } =
              await import('~/plugins/core-bpm/services/bpmWorkbenchService');
            const result = await startProcessFromAction({
              processDefinitionKey,
              businessKey: String(businessKeyRaw),
              variables: Object.keys(resolvedVars).length > 0 ? resolvedVars : undefined,
            });
            // i18n keys `bpm.action.start.success` / `bpm.action.start.deduped` are
            // pending registration in the central i18n dictionary; t() accepts a
            // fallback string that surfaces when the key is missing so the UX
            // never shows the raw key.
            const toastMessage = result.deduped
              ? t('bpm.action.start.deduped', undefined, '该记录已有审批流程在运行')
              : t('bpm.action.start.success', undefined, '审批流程已启动');
            notifyToast(toastMessage, 'success');
            if (context.loadData) await context.loadData();
            return;
          }

          case 'flow': {
            if (confirmKey) {
              const confirmed = await showConfirmDialog(confirmKey);
              if (!confirmed) return;
            }

            if ('handler' in actionDef && actionDef.handler) {
              if (runtime) {
                // Task 9a refactor regression fix: normalizeAction only lifts
                // `handler` from the legacy `events.onClick.handler` shape but
                // drops the sibling `args` map. Downstream `executeSchemaHandler`
                // reads `button.events.onClick.args`, so we splice the original
                // args back in (if any) to preserve pre-refactor behaviour.
                const legacyArgs = normalizedButton.events?.onClick?.args;
                await executeSchemaHandler({
                  runtime,
                  button: {
                    ...normalizedButton,
                    events: {
                      onClick: { handler: actionDef.handler, args: legacyArgs },
                    },
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
                  const { fetchResult: fr } = await import('~/shared/services/http-client');
                  (freshContext as any).fetchResult = fr;
                  const contextWithRecord = { ...freshContext, record, row: record };
                  await flowRunner.run(actionDef.steps, contextWithRecord as any);
                  if (context.loadData) await context.loadData();
                  return;
                }
              }
              // Fallback: execute steps via ActionRegistry directly
              const { actionRegistry } =
                await import('~/framework/meta/runtime/actions/ActionRegistry');
              const { fetchResult: fr } = await import('~/shared/services/http-client');
              for (const step of actionDef.steps) {
                if (step.action && actionRegistry.has(step.action)) {
                  await actionRegistry.execute(step.action, {
                    args: step.args,
                    navigate,
                    showToast: notifyToast as any,
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
        const errorObject = err instanceof Error ? err : new Error(errorMessage);
        console.error(`[useActionHandler] Action execution failed (${button.code}):`, err);
        setError(errorMessage);
        notifyToast(errorMessage, 'error');
        if (onError) onError(errorObject);
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
      notifyToast,
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
    activeTask,
    clearActiveTask,
    executeCommand,
  };
}
