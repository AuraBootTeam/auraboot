import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';

const { fetchResultMock } = vi.hoisted(() => ({
  fetchResultMock: vi.fn(),
}));

vi.mock('~/shared/services/http-client', () => ({
  fetchResult: fetchResultMock,
}));

import { executeSimpleWorkbenchAction, readPath, writeRuntimeState } from '../workbenchBlockUtils';

function makeRuntime(overrides: Partial<any> = {}): SchemaRuntime {
  const context: Record<string, any> = {
    locale: 'en-US',
    t: (k: string) => k,
    form: {},
    global: {},
    state: {
      selectedLine: { pid: 'LINE-1' },
      selectedCandidate: { materialCode: 'MAT-001' },
    },
  };
  const updateState = vi.fn((scopeId: string, key: string, value: any) => {
    context.state[key] = value;
  });
  const reload = vi.fn().mockResolvedValue(undefined);
  const notifyStateChanged = vi.fn().mockResolvedValue(undefined);
  const stub = {
    getContext: () => context,
    getEvaluator: () => ({
      evaluateCondition: () => true,
      evaluateTemplate: (tpl: string) => tpl,
      evaluateObject: (obj: any) => obj,
    }),
    getDataSourceManager: () => ({
      reload,
      notifyStateChanged,
    }),
    getStateManager: () => ({
      updateState,
      getContext: () => context,
    }),
    getScopeId: () => 'scope-1',
    __reload: reload,
    __updateState: updateState,
    __notifyStateChanged: notifyStateChanged,
    ...overrides,
  };
  return stub as unknown as SchemaRuntime;
}

describe('workbenchBlockUtils action runner', () => {
  beforeEach(() => {
    fetchResultMock.mockReset();
    fetchResultMock.mockResolvedValue({ code: '0', data: {} });
    vi.unstubAllGlobals();
  });

  it('reloads one or more data sources', async () => {
    const runtime = makeRuntime() as any;

    await executeSimpleWorkbenchAction(runtime, {
      action: 'dataSource.reload',
      args: { ids: ['summary', 'lines'] },
    });

    expect(runtime.__reload).toHaveBeenCalledWith(['summary', 'lines']);
  });

  it('notifies data source dependents after writing runtime state', () => {
    const runtime = makeRuntime() as any;

    writeRuntimeState(runtime, 'selectedLine', { pid: 'LINE-2' });

    expect(runtime.__updateState).toHaveBeenCalledWith('scope-1', 'selectedLine', {
      pid: 'LINE-2',
    });
    expect(runtime.__notifyStateChanged).toHaveBeenCalledWith('selectedLine');
  });

  it('executes a command with resolved state references and reloads dependencies', async () => {
    const runtime = makeRuntime() as any;

    await executeSimpleWorkbenchAction(runtime, {
      action: 'command.execute',
      args: {
        command: 'bom:confirm_candidate',
        targetRecordId: '${state.selectedLine.pid}',
        operationType: 'update',
        payload: {
          materialCode: '${state.selectedCandidate.materialCode}',
        },
        reload: ['summary', 'lines'],
      },
    });

    expect(fetchResultMock).toHaveBeenCalledWith('/api/meta/commands/execute/bom:confirm_candidate', {
      method: 'post',
      params: {
        targetRecordId: 'LINE-1',
        operationType: 'UPDATE',
        payload: {
          materialCode: 'MAT-001',
        },
      },
    });
    expect(runtime.__reload).toHaveBeenCalledWith(['summary', 'lines']);
  });

  it('does not reload data sources when command execution fails', async () => {
    const runtime = makeRuntime() as any;
    fetchResultMock.mockResolvedValue({
      code: '500',
      message: 'candidate is no longer available',
    });

    await expect(
      executeSimpleWorkbenchAction(runtime, {
        action: 'command.execute',
        args: {
          command: 'bom:confirm_candidate',
          targetRecordId: '${state.selectedLine.pid}',
          operationType: 'update',
          payload: {
            materialCode: '${state.selectedCandidate.materialCode}',
          },
          reload: ['summary', 'lines'],
        },
      }),
    ).rejects.toThrow('candidate is no longer available');

    expect(runtime.__reload).not.toHaveBeenCalled();
  });

  it('opens the returned file download after executing a command', async () => {
    const runtime = makeRuntime() as any;
    const assign = vi.fn();
    fetchResultMock.mockResolvedValue({
      code: '0',
      data: {
        fileId: 'export-file-1',
      },
    });
    vi.stubGlobal('window', {
      location: {
        assign,
      },
    });

    await executeSimpleWorkbenchAction(runtime, {
      action: 'command.execute',
      args: {
        command: 'bom:regenerate_export',
        targetRecordId: 'TASK-1',
        reload: ['summary'],
        download: {
          fileIdField: 'fileId',
        },
      },
    });

    expect(fetchResultMock).toHaveBeenCalledWith('/api/meta/commands/execute/bom:regenerate_export', {
      method: 'post',
      params: {
        targetRecordId: 'TASK-1',
        payload: {},
      },
    });
    expect(assign).toHaveBeenCalledWith('/api/file/download/export-file-1');
    expect(runtime.__reload).toHaveBeenCalledWith(['summary']);
  });
});

describe('workbenchBlockUtils readPath', () => {
  it('reads nested fields from JSON string values', () => {
    expect(
      readPath(
        {
          reasonBreakdown: '{"match_multi_candidate":48,"unrecognized_category":1}',
        },
        'reasonBreakdown.match_multi_candidate',
      ),
    ).toBe(48);
  });
});
