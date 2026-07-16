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

  it('navigates workbench actions to an explicit path or custom page key', async () => {
    const navigateTo = vi.fn();
    const runtime = makeRuntime({ navigateTo }) as any;

    await executeSimpleWorkbenchAction(runtime, {
      action: 'navigate',
      args: { path: '/p/c/iot_alarm_console_workbench' },
    });

    await executeSimpleWorkbenchAction(runtime, {
      action: 'navigate',
      args: { to: 'iot_remote_diagnosis_workbench' },
    });

    expect(navigateTo).toHaveBeenNthCalledWith(1, '/p/c/iot_alarm_console_workbench');
    expect(navigateTo).toHaveBeenNthCalledWith(2, '/p/c/iot_remote_diagnosis_workbench');
  });

  it('encodes resolved navigate context as routeContext query state', async () => {
    const navigateTo = vi.fn();
    const runtime = makeRuntime({ navigateTo }) as any;

    await executeSimpleWorkbenchAction(runtime, {
      action: 'navigate',
      args: {
        to: 'iot_remote_diagnosis_workbench',
        context: {
          source: 'FLEET',
          deviceCode: '${state.selectedLine.pid}',
          emptyValue: '',
        },
      },
    });

    const target = new URL(navigateTo.mock.calls[0][0], 'http://auraboot.local');
    expect(target.pathname).toBe('/p/c/iot_remote_diagnosis_workbench');
    expect(JSON.parse(target.searchParams.get('routeContext') || '{}')).toEqual({
      source: 'FLEET',
      deviceCode: 'LINE-1',
    });
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
        targetRecordPid: '${state.selectedLine.pid}',
        operationType: 'update',
        payload: {
          materialCode: '${state.selectedCandidate.materialCode}',
        },
        reload: ['summary', 'lines'],
      },
    });

    expect(fetchResultMock).toHaveBeenCalledWith(
      '/api/meta/commands/execute/bom:confirm_candidate',
      {
        method: 'post',
        params: {
          targetRecordPid: 'LINE-1',
          operationType: 'UPDATE',
          payload: {
            materialCode: 'MAT-001',
          },
        },
      },
    );
    expect(runtime.__reload).toHaveBeenCalledWith(['summary', 'lines']);
  });

  it('downloads Base64 command artifacts from the workbench action path', async () => {
    fetchResultMock.mockResolvedValueOnce({
      code: '0',
      data: {
        success: true,
        data: {
          fileName: 'audit.json',
          contentType: 'application/json',
          contentBase64: btoa('{"schema":"v1"}'),
        },
      },
    });
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:workbench-artifact');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);

    await executeSimpleWorkbenchAction(makeRuntime() as any, {
      action: 'command.execute',
      args: { command: 'iot_dps_batch_onboarding_job:export_audit', targetRecordPid: 'B1' },
    });

    expect(click).toHaveBeenCalledOnce();
  });

  it('accepts targetRecordId alias for command actions', async () => {
    const runtime = makeRuntime({
      getContext: () => ({
        locale: 'zh-CN',
        t: (k: string) => k,
        form: { pid: 'QUOTE-1' },
        global: {},
        state: {},
      }),
    }) as any;

    await executeSimpleWorkbenchAction(runtime, {
      action: 'command.execute',
      args: {
        command: 'qo_quote_common:deepseek_price_suggestions',
        targetRecordId: '${form.pid}',
        operationType: 'update',
      },
    });

    expect(fetchResultMock).toHaveBeenCalledWith(
      '/api/meta/commands/execute/qo_quote_common:deepseek_price_suggestions',
      {
        method: 'post',
        params: {
          targetRecordPid: 'QUOTE-1',
          operationType: 'UPDATE',
          payload: {},
        },
      },
    );
  });

  it('polls async command tasks and throttles dependency reloads while the task is running', async () => {
    const runtime = makeRuntime() as any;
    fetchResultMock
      .mockResolvedValueOnce({
        code: '0',
        data: {
          commandCode: 'qo_quote_common:batch_source_prices',
          data: { async: true, taskCode: 'TASK-PRICE-1', taskType: 'command-handler' },
        },
      })
      .mockResolvedValueOnce({
        code: '0',
        data: { status: 'running', progress: 35, progressMessage: 'Processed 2/7 quote lines' },
      })
      .mockResolvedValueOnce({
        code: '0',
        data: { status: 'running', progress: 70, progressMessage: 'Processed 5/7 quote lines' },
      })
      .mockResolvedValueOnce({
        code: '0',
        data: { status: 'completed', resultData: { processedCount: 7, sourcedCount: 5 } },
      });

    await executeSimpleWorkbenchAction(runtime, {
      action: 'command.execute',
      args: {
        command: 'qo_quote_common:batch_source_prices',
        targetRecordPid: 'QUOTE-1',
        operationType: 'update',
        reload: ['bomPriceMetrics', 'bomPriceWaterfall', 'evidence', 'lines'],
        asyncPollIntervalMs: 0,
        asyncReloadIntervalMs: 3000,
      },
    });

    const urls = fetchResultMock.mock.calls.map((call) => String(call[0]));
    expect(urls).toContain('/api/meta/commands/execute/qo_quote_common:batch_source_prices');
    expect(urls).toContain('/api/async-tasks/TASK-PRICE-1');
    expect(runtime.__reload).toHaveBeenCalledTimes(2);
    expect(runtime.__reload).toHaveBeenLastCalledWith([
      'bomPriceMetrics',
      'bomPriceWaterfall',
      'evidence',
      'lines',
    ]);
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
          targetRecordPid: '${state.selectedLine.pid}',
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

  it('B-002: surfaces localized context.detail over the generic message envelope on command reject', async () => {
    // A business-rejected command puts the real reason in context.detail; message/desc
    // are the generic "Business error" envelope. The workbench path (executeSimpleWorkbenchAction)
    // is the third command path (after useActionHandler / FormPageContent, OSS #1212) and
    // must extract the same way. Pre-fix it threw message ('Business error'); this asserts detail.
    const runtime = makeRuntime() as any;
    fetchResultMock.mockResolvedValue({
      code: '500',
      message: 'Business error',
      desc: 'Business error',
      context: { detail: '库存不足,无法确认入库' },
    });

    await expect(
      executeSimpleWorkbenchAction(runtime, {
        action: 'command.execute',
        args: {
          command: 'tinv:confirm_stock_in',
          targetRecordPid: 'SI-1',
          operationType: 'update',
        },
      }),
    ).rejects.toThrow('库存不足,无法确认入库');

    expect(runtime.__reload).not.toHaveBeenCalled();
  });

  it('downloads the returned file with browser auth after executing a command', async () => {
    const runtime = makeRuntime() as any;
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      blob: vi.fn().mockResolvedValue(new Blob(['xlsx'])),
      headers: {
        get: vi.fn().mockReturnValue('attachment; filename="standard-bom.xlsx"'),
      },
    });
    const objectUrl = 'blob:http://localhost/export';
    const createObjectURL = vi.fn().mockReturnValue(objectUrl);
    const revokeObjectURL = vi.fn();
    const click = vi.fn();
    const appendChild = vi.spyOn(document.body, 'appendChild');
    const createElement = vi.spyOn(document, 'createElement');
    fetchResultMock.mockResolvedValue({
      code: '0',
      data: {
        fileId: 'export-file-1',
      },
    });
    vi.stubGlobal('fetch', fetchMock);
    vi.stubGlobal('URL', { createObjectURL, revokeObjectURL });
    window.localStorage.setItem('jwtToken', 'token-1');
    createElement.mockImplementation((tagName: string) => {
      const element = document.createElementNS(
        'http://www.w3.org/1999/xhtml',
        tagName,
      ) as HTMLAnchorElement;
      if (tagName === 'a') {
        element.click = click;
      }
      return element;
    });

    await executeSimpleWorkbenchAction(runtime, {
      action: 'command.execute',
      args: {
        command: 'bom:regenerate_export',
        targetRecordPid: 'TASK-1',
        reload: ['summary'],
        download: {
          fileIdField: 'fileId',
        },
      },
    });

    expect(fetchResultMock).toHaveBeenCalledWith(
      '/api/meta/commands/execute/bom:regenerate_export',
      {
        method: 'post',
        params: {
          targetRecordPid: 'TASK-1',
          payload: {},
        },
      },
    );
    expect(fetchMock).toHaveBeenCalledWith('/api/file/download/export-file-1', {
      method: 'GET',
      headers: {
        Authorization: 'Bearer token-1',
      },
      credentials: 'include',
    });
    expect(createObjectURL).toHaveBeenCalled();
    expect(appendChild).toHaveBeenCalled();
    expect(click).toHaveBeenCalled();
    expect(runtime.__reload).toHaveBeenCalledWith(['summary']);
  });
});

describe('workbenchBlockUtils unknown-action backstop', () => {
  it('throws and logs for an invented string verb instead of silently no-op-ing', async () => {
    const runtime = makeRuntime() as any;
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined);

    await expect(
      executeSimpleWorkbenchAction(runtime, {
        action: 'api',
        args: { command: 'cs:go_online', endpoint: '/api/x' },
      }),
    ).rejects.toThrow(/unknown action/);
    expect(errorSpy).toHaveBeenCalled();
    // The fatal footgun was a dead button with no side effect — assert nothing ran.
    expect(runtime.__reload).not.toHaveBeenCalled();

    errorSpy.mockRestore();
  });

  it('throws for an object-shaped action (the ActionRegistry/rowActions dialect)', async () => {
    const runtime = makeRuntime() as any;
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined);

    await expect(
      executeSimpleWorkbenchAction(runtime, {
        action: { type: 'api', endpoint: '/api/x', body: {} },
      }),
    ).rejects.toThrow(/unknown action an object/);

    errorSpy.mockRestore();
  });

  it('still runs the four legal verbs without throwing', async () => {
    const runtime = makeRuntime() as any;
    await expect(
      executeSimpleWorkbenchAction(runtime, { action: 'state.set', args: { statusFilter: [] } }),
    ).resolves.toBeUndefined();
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
