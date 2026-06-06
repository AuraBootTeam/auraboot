import { useEffect, useState } from 'react';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { fetchResult } from '~/shared/services/http-client';

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

    return store.subscribe(() => {
      forceUpdate((version) => version + 1);
    });
  }, [runtime]);
}

export function readPath(source: any, path?: string): any {
  if (!path) return undefined;
  return path.split('.').reduce((current, part) => current?.[part], source);
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
  return result?.data?.data ?? result?.data ?? result ?? {};
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

function openDownloadUrl(url: string): void {
  if (typeof window === 'undefined') return;
  if (typeof window.location?.assign === 'function') {
    window.location.assign(url);
    return;
  }
  window.location.href = url;
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

    const params: Record<string, any> = {
      targetRecordId: args.targetRecordId ?? args.targetRecordPid,
      operationType: args.operationType ? String(args.operationType).toUpperCase() : undefined,
      payload: args.payload || {},
    };
    if (args.targetRecordPid) {
      params.targetRecordPid = args.targetRecordPid;
    }
    Object.keys(params).forEach((key) => {
      if (params[key] === undefined) delete params[key];
    });

    const result = await fetchResult(`/api/meta/commands/execute/${command}`, {
      method: 'post',
      params,
    });
    if (result && typeof result === 'object' && 'code' in result && result.code !== '0') {
      throw new Error((result as any).message || (result as any).desc || `${command} failed`);
    }

    await reloadDataSources(runtime, resolveReloadIds(args.reload));
    const downloadUrl = resolveDownloadUrl(result, args.download);
    if (downloadUrl) {
      openDownloadUrl(downloadUrl);
    }
  }
}
