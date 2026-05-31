/**
 * useActionHandler.async.test.ts
 *
 * Pins the handlerParams.async contract on the client: a command that returns
 * { async:true, taskCode } must be polled to completion via the async-task
 * status endpoint (no single long request), and the list reloaded on success.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';

const fetchResultMock = vi.fn();
vi.mock('~/shared/services/http-client', () => ({
  fetchResult: (...args: unknown[]) => fetchResultMock(...args),
}));

import { useActionHandler } from '~/framework/meta/hooks/useActionHandler';
import type { ButtonConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';

function makeRuntime(): SchemaRuntime {
  const context: Record<string, unknown> = { locale: 'zh-CN', t: (k: string) => k, form: {}, global: {}, state: {} };
  return {
    executeHandler: vi.fn(),
    getContext: () => context,
    getEvaluator: () => ({ evaluateCondition: () => true, evaluateTemplate: (t: string) => t }),
    getSchema: () => ({ id: 'test', modelCode: 'bom_material_master' }),
    getDataSourceManager: () => ({ getData: () => [], has: () => false, register: vi.fn() }),
    getFlowRunner: () => null,
  } as unknown as SchemaRuntime;
}

describe('useActionHandler - handlerParams.async polling', () => {
  beforeEach(() => fetchResultMock.mockReset());

  it('polls the async task to completion and reloads, instead of treating the immediate ack as the result', async () => {
    // 1) command execute → immediate async ack; 2) task poll → completed.
    fetchResultMock
      // Command engine wraps the handler ack one level deep (result.data.data).
      .mockResolvedValueOnce({ code: '0', data: { commandCode: 'c', phaseReached: 'completed', data: { async: true, taskCode: 'T1', taskType: 'command-handler' } } })
      .mockResolvedValueOnce({ code: '0', data: { status: 'completed', resultData: { importedRows: 35924 } } });

    const loadData = vi.fn().mockResolvedValue(undefined);
    const showToast = vi.fn();
    const { result } = renderHook(() =>
      useActionHandler({
        runtime: makeRuntime(),
        navigate: vi.fn() as any,
        tableName: 'bom_material_master',
        locale: 'zh-CN',
        t: ((k: string, _p?: any, fb?: string) => fb ?? k) as any,
        showToast,
        context: { loadData } as any,
      }),
    );

    const button = {
      code: 'import_current_library',
      label: 'Import',
      action: { type: 'command', command: 'bom:import_material_library' },
    } as unknown as ButtonConfig;

    await act(async () => {
      await result.current.handleAction(button);
    });

    // Polled the async-task status endpoint (not just the execute call).
    const urls = fetchResultMock.mock.calls.map((c) => String(c[0]));
    expect(urls.some((u) => u.includes('/api/meta/commands/execute/bom:import_material_library'))).toBe(true);
    expect(urls.some((u) => u.includes('/api/async-tasks/T1'))).toBe(true);
    // Success path reloaded the list.
    expect(loadData).toHaveBeenCalled();
    expect(showToast).toHaveBeenCalledWith(expect.stringContaining('后台处理'), 'info');
    // Modal state was populated and polled through to the terminal task, so the
    // host can render <AsyncTaskProgressModal task={activeTask}/> with the final
    // summary (not just a fire-and-forget toast).
    expect(result.current.activeTask).not.toBeNull();
    expect(result.current.activeTask?.status).toBe('completed');
    expect(result.current.activeTask?.resultData).toEqual({ importedRows: 35924 });
    // Dismissing clears the modal.
    act(() => result.current.clearActiveTask());
    expect(result.current.activeTask).toBeNull();
  });

  it('surfaces a failed async task in the modal instead of throwing to the page', async () => {
    fetchResultMock
      .mockResolvedValueOnce({ code: '0', data: { commandCode: 'c', phaseReached: 'completed', data: { async: true, taskCode: 'T2' } } })
      .mockResolvedValueOnce({ code: '0', data: { status: 'failed', errorMessage: 'source_file_id is required' } });

    const loadData = vi.fn();
    const onError = vi.fn();
    const { result } = renderHook(() =>
      useActionHandler({
        runtime: makeRuntime(),
        navigate: vi.fn() as any,
        tableName: 'bom_material_master',
        locale: 'zh-CN',
        t: ((k: string, _p?: any, fb?: string) => fb ?? k) as any,
        onError,
        context: { loadData } as any,
      }),
    );

    const button = {
      code: 'import_current_library',
      label: 'Import',
      action: { type: 'command', command: 'bom:import_material_library' },
    } as unknown as ButtonConfig;

    await act(async () => {
      await result.current.handleAction(button);
    });

    // Failed task → modal shows the failed state; NOT thrown to onError/page
    // error boundary (which would replace the page with a generic error).
    expect(onError).not.toHaveBeenCalled();
    expect(result.current.activeTask?.status).toBe('failed');
    expect(result.current.activeTask?.errorMessage).toContain('source_file_id is required');
  });
});
