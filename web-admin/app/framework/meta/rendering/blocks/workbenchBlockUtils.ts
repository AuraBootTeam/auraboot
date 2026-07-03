import { useEffect, useState } from 'react';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { JWT_TOKEN_KEY } from '~/constants/AuthConstant';
import { fetchResult } from '~/shared/services/http-client';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';

export function readDataSourceRows(runtime: SchemaRuntime, dataSource?: string): any[] {
  if (!dataSource) return [];
  const data = runtime.getDataSourceManager().getData(dataSource);
  if (Array.isArray(data)) return data;
  if (Array.isArray(data?.records)) return data.records;
  if (Array.isArray(data?.list)) return data.list;
  return data ? [data] : [];
}

export function readDataSourceRecord(runtime: SchemaRuntime, dataSource?: string): Record<string, any> {
  const rows = readDataSourceRows(runtime, dataSource);
  return rows[0] ?? {};
}

export function readDataSourceState(runtime: SchemaRuntime, dataSource?: string): any | undefined {
  if (!dataSource) return undefined;
  return runtime.getDataSourceManager?.().getState?.(dataSource);
}

export function useDataSourceSubscription(runtime: SchemaRuntime, dataSource?: string): void {
  const [, forceUpdate] = useState(0);

  useEffect(() => {
    if (!dataSource) return undefined;

    const manager = runtime.getDataSourceManager?.();
    if (!manager?.subscribe) return undefined;

    return manager.subscribe(dataSource, () => {
      forceUpdate((version) => version + 1);
    });
  }, [runtime, dataSource]);
}

export function useRuntimeStateSubscription(runtime: SchemaRuntime): void {
  const [, forceUpdate] = useState(0);

  useEffect(() => {
    const stateManager = runtime.getStateManager?.();
    const scopeId = runtime.getScopeId?.();
    const store = stateManager?.getStore?.(scopeId);
    if (!store?.subscribe) return undefined;

    const unsubscribe = store.subscribe(() => {
      forceUpdate((version) => version + 1);
    });

    // A sibling block can write runtime state during the same mount effect
    // phase before this subscription is active. Refresh once after subscribing
    // so state-bound blocks render the current snapshot, not only future writes.
    forceUpdate((version) => version + 1);

    return unsubscribe;
  }, [runtime]);
}

function parseJsonLike(value: unknown): unknown {
  if (typeof value !== 'string') return value;
  const trimmed = value.trim();
  if (!trimmed || (!trimmed.startsWith('{') && !trimmed.startsWith('['))) {
    return value;
  }
  try {
    return JSON.parse(trimmed);
  } catch {
    return value;
  }
}

export function readPath(source: any, path?: string): any {
  if (!path) return undefined;
  return path.split('.').reduce((current, part) => {
    const resolved = parseJsonLike(current);
    return (resolved as any)?.[part];
  }, source);
}

export function resolveRuntimeValue(runtime: SchemaRuntime, expression: unknown): any {
  if (Array.isArray(expression)) {
    return expression.map((item) => resolveRuntimeValue(runtime, item));
  }
  if (expression && typeof expression === 'object') {
    return Object.fromEntries(
      Object.entries(expression).map(([key, value]) => [key, resolveRuntimeValue(runtime, value)]),
    );
  }
  if (typeof expression !== 'string') return expression;
  const trimmed = expression.trim();
  const match = trimmed.match(/^\$\{(.+)\}$/);
  if (!match) return expression;
  return readPath(runtime.getContext(), match[1]);
}

export function writeRuntimeState(runtime: SchemaRuntime, key: string, value: any): void {
  const stateManager = runtime.getStateManager?.();
  const scopeId = runtime.getScopeId?.();
  if (!stateManager || !scopeId) return;
  stateManager.updateState(scopeId, key, value);
  void runtime.getDataSourceManager?.().notifyStateChanged?.(key);
}

function resolveReloadIds(args: any): string | string[] | undefined {
  if (!args) return undefined;
  if (typeof args === 'string') return args;
  if (Array.isArray(args)) return args;
  return args.ids ?? args.id ?? args.dataSourceId ?? args.dataSourceIds ?? args.reload;
}

async function reloadDataSources(runtime: SchemaRuntime, ids: string | string[] | undefined) {
  if (!ids || (Array.isArray(ids) && ids.length === 0)) return;
  const manager = runtime.getDataSourceManager?.();
  if (!manager?.reload) return;
  await manager.reload(ids);
}

function unwrapCommandData(result: any): any {
  const data = result?.data?.data ?? result?.data ?? result ?? {};
  if (
    data &&
    typeof data === 'object' &&
    'data' in data &&
    ('commandCode' in data || 'phaseReached' in data || 'executionTimeMs' in data)
  ) {
    return data.data ?? {};
  }
  return data;
}

function isSuccessResult(result: any): boolean {
  return !(result && typeof result === 'object' && 'code' in result && String(result.code) !== '0');
}

function isAsyncDispatch(data: any): data is { async: true; taskCode: string } {
  return data?.async === true && typeof data.taskCode === 'string' && data.taskCode.trim() !== '';
}

function isBusinessRejected(data: any): boolean {
  return data && typeof data === 'object' && (data.success === false || data.applied === false);
}

function resolveFeedback(args: any): any {
  return args?.feedback ?? args?.resultFeedback ?? {};
}

function resolveFeedbackMessage(
  runtime: SchemaRuntime,
  feedback: any,
  key: string,
  fallback?: string,
  preferFallback = false,
): string | undefined {
  const raw = preferFallback ? fallback ?? feedback?.[key] : feedback?.[key] ?? fallback;
  if (raw === false || raw === undefined || raw === null) return undefined;
  const context = runtime.getContext?.() || {};
  const locale = context.locale || 'zh-CN';
  const t = context.t || ((value: string) => value);
  const message = getLocalizedText(raw, locale, t);
  return message && message !== key ? message : undefined;
}

function showCommandFeedback(
  runtime: SchemaRuntime,
  feedback: any,
  key: string,
  level: 'success' | 'error' | 'warning' | 'info',
  fallback?: string,
  preferFallback = false,
): void {
  const message = resolveFeedbackMessage(runtime, feedback, key, fallback, preferFallback);
  if (!message) return;
  const showToast = runtime.getShowToast?.();
  if (showToast) {
    showToast(message, level);
    return;
  }
  if (typeof window !== 'undefined') {
    window.dispatchEvent(
      new CustomEvent('aura:toast', {
        detail: {
          message,
          variant: level,
        },
      }),
    );
  }
}

type AsyncTaskStatus = {
  status?: string;
  resultData?: unknown;
  errorMessage?: string;
  message?: string;
};

function resolvePollIntervalMs(args: any): number {
  const value = Number(args?.asyncPollIntervalMs ?? args?.pollIntervalMs ?? 1500);
  return Number.isFinite(value) && value >= 0 ? value : 1500;
}

function resolvePollAttempts(args: any): number {
  const value = Number(args?.asyncMaxPollAttempts ?? args?.maxPollAttempts ?? 600);
  return Number.isFinite(value) && value > 0 ? Math.floor(value) : 600;
}

function delay(ms: number): Promise<void> {
  if (ms <= 0) return Promise.resolve();
  return new Promise((resolve) => globalThis.setTimeout(resolve, ms));
}

async function pollWorkbenchAsyncTask(
  runtime: SchemaRuntime,
  taskCode: string,
  reloadIds: string | string[] | undefined,
  args: any,
): Promise<any> {
  const terminalStatuses = new Set(['completed', 'failed', 'cancelled']);
  const intervalMs = resolvePollIntervalMs(args);
  const maxAttempts = resolvePollAttempts(args);

  for (let attempt = 0; attempt < maxAttempts; attempt += 1) {
    const result = await fetchResult<AsyncTaskStatus>(`/api/async-tasks/${encodeURIComponent(taskCode)}`, {
      method: 'get',
    });
    if (!isSuccessResult(result)) {
      throw new Error((result as any).message || (result as any).desc || 'Async task status unavailable');
    }

    const task: AsyncTaskStatus = result?.data ?? {};
    await reloadDataSources(runtime, reloadIds);

    const status = String(task.status || '').toLowerCase();
    if (terminalStatuses.has(status)) {
      if (status === 'completed') return task.resultData ?? {};
      if (status === 'cancelled') throw new Error('Task cancelled');
      throw new Error(task.errorMessage || task.message || 'Async task failed');
    }

    await delay(intervalMs);
  }

  throw new Error('Async task timed out');
}

function readFirstPath(source: any, paths: string[]): any {
  for (const path of paths) {
    const value = readPath(source, path);
    if (value !== undefined && value !== null && String(value).trim() !== '') {
      return value;
    }
  }
  return undefined;
}

function resolveDownloadUrl(commandResult: any, downloadConfig: any): string | undefined {
  if (!downloadConfig) return undefined;
  const data = unwrapCommandData(commandResult);
  const config = downloadConfig === true ? {} : downloadConfig;
  const url = readFirstPath(data, [
    config.urlField,
    config.downloadUrlField,
    'downloadUrl',
    'url',
  ].filter(Boolean));
  if (url) return String(url);

  const fileId = readFirstPath(data, [
    config.fileIdField,
    'fileId',
    'exportFileId',
    'id',
    'pid',
  ].filter(Boolean));
  if (!fileId) return undefined;
  return `/api/file/download/${encodeURIComponent(String(fileId))}`;
}

function filenameFromContentDisposition(header: string | null): string | undefined {
  if (!header) return undefined;
  const utf8Match = header.match(/filename\*=UTF-8''([^;]+)/i);
  if (utf8Match?.[1]) {
    try {
      return decodeURIComponent(utf8Match[1]);
    } catch {
      return utf8Match[1];
    }
  }
  const quotedMatch = header.match(/filename="([^"]+)"/i);
  if (quotedMatch?.[1]) return quotedMatch[1];
  const plainMatch = header.match(/filename=([^;]+)/i);
  return plainMatch?.[1]?.trim();
}

function openDownloadUrl(url: string): void {
  if (typeof window === 'undefined') return;
  if (typeof window.location?.assign === 'function') {
    window.location.assign(url);
    return;
  }
  window.location.href = url;
}

async function downloadWithAuth(url: string): Promise<void> {
  if (typeof window === 'undefined' || typeof document === 'undefined') return;

  const headers: Record<string, string> = {};
  const token = window.localStorage?.getItem(JWT_TOKEN_KEY);
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }

  const response = await fetch(url, {
    method: 'GET',
    headers,
    credentials: 'include',
  });
  if (!response.ok) {
    throw new Error(`Download failed: ${response.status} ${response.statusText}`);
  }

  const blob = await response.blob();
  const objectUrl = window.URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = objectUrl;
  anchor.download = filenameFromContentDisposition(response.headers.get('Content-Disposition')) || 'download';
  anchor.style.display = 'none';
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  window.setTimeout(() => window.URL.revokeObjectURL(objectUrl), 0);
}

export async function executeSimpleWorkbenchAction(
  runtime: SchemaRuntime,
  config: any,
): Promise<void> {
  if (!config) return;
  if (config.action === 'state.set' && config.args && typeof config.args === 'object') {
    Object.entries(resolveRuntimeValue(runtime, config.args)).forEach(([key, value]) => {
      writeRuntimeState(runtime, key, value);
    });
    return;
  }

  if (config.action === 'dataSource.reload') {
    const args = resolveRuntimeValue(runtime, config.args);
    await reloadDataSources(runtime, resolveReloadIds(args));
    return;
  }

  if (config.action === 'command.execute') {
    const args = resolveRuntimeValue(runtime, config.args || {});
    const command = args.command ?? args.commandCode;
    if (!command) {
      throw new Error('[workbench] command.execute requires args.command');
    }
    const feedback = resolveFeedback(args);

    // inputFields sugar: pop a FormDialog, collect fields, merge into the command payload.
    // Same capability as ActionRegistry's command.execute. workbench-action-bar buttons run
    // THIS path (not ActionRegistry), so the platform inputFields feature must be wired here
    // too — otherwise a detail-page action with inputFields silently skips the form and fails
    // (the cr_account "导入凭据" gate-gap, caught by the credential golden).
    let collectedInputs: Record<string, any> = {};
    if (Array.isArray(args.inputFields) && args.inputFields.length > 0) {
      const { promptInputForm } = await import('~/framework/meta/runtime/actions/ActionRegistry');
      try {
        collectedInputs = await promptInputForm(
          args.inputFields,
          args.inputFieldsTitle,
          fetchResult,
          args.inputFieldsSubmitLabel,
        );
      } catch {
        return; // user cancelled the form — abort without executing the command
      }
    }

    const targetRecordPid = args.targetRecordPid ?? args.targetRecordId;
    const params: Record<string, any> = {
      targetRecordPid,
      operationType: args.operationType ? String(args.operationType).toUpperCase() : undefined,
      payload: { ...(args.payload || {}), ...collectedInputs },
    };
    if (targetRecordPid) {
      params.targetRecordPid = targetRecordPid;
    }
    Object.keys(params).forEach((key) => {
      if (params[key] === undefined) delete params[key];
    });

    try {
      let result = await fetchResult(`/api/meta/commands/execute/${command}`, {
        method: 'post',
        params,
      });
      if (!isSuccessResult(result)) {
        throw new Error((result as any).message || (result as any).desc || `${command} failed`);
      }

      const reloadIds = resolveReloadIds(args.reload);
      const dispatch = unwrapCommandData(result);
      if (isAsyncDispatch(dispatch)) {
        result = await pollWorkbenchAsyncTask(runtime, dispatch.taskCode, reloadIds, args);
      }

      await reloadDataSources(runtime, reloadIds);
      const resultData = unwrapCommandData(result);
      if (isBusinessRejected(resultData)) {
        showCommandFeedback(
          runtime,
          feedback,
          'rejectedMessage',
          'warning',
          resultData.message || resultData.reason,
          Boolean(resultData.message),
        );
      } else {
        showCommandFeedback(runtime, feedback, 'successMessage', 'success');
      }

      const downloadUrl = resolveDownloadUrl(result, args.download);
      if (downloadUrl) {
        try {
          await downloadWithAuth(downloadUrl);
        } catch (error) {
          console.error('[workbench] authenticated download failed, falling back to direct navigation:', error);
          openDownloadUrl(downloadUrl);
        }
      }
    } catch (error) {
      showCommandFeedback(
        runtime,
        feedback,
        'errorMessage',
        'error',
        error instanceof Error ? error.message : `${command} failed`,
      );
      throw error;
    }
  }
}
