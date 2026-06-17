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
import * as promptUpload from '~/framework/meta/utils/promptUpload';
import type { ButtonConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';

function makeRuntime(overrides: Record<string, unknown> = {}): SchemaRuntime {
  const context: Record<string, unknown> = {
    locale: 'zh-CN',
    t: (k: string) => k,
    form: {},
    global: {},
    state: {},
    ...overrides,
  };
  return {
    executeHandler: vi.fn(),
    getContext: () => context,
    getShowToast: () => undefined,
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

  it('refreshes detail data sources when an async task fails so status banners can surface the failure', async () => {
    fetchResultMock
      .mockResolvedValueOnce({
        code: '0',
        data: {
          commandCode: 'c',
          phaseReached: 'completed',
          data: { async: true, taskCode: 'T-FAILED' },
        },
      })
      .mockResolvedValueOnce({
        code: '0',
        data: {
          status: 'failed',
          errorMessage: 'Corrected BOM missing required header row',
        },
      });

    const loadData = vi.fn().mockResolvedValue(undefined);
    const reload = vi.fn().mockResolvedValue(undefined);
    const runtime = makeRuntime({ form: { pid: 'QUOTE-123' } });
    const { result } = renderHook(() =>
      useActionHandler({
        runtime,
        navigate: vi.fn() as any,
        tableName: 'qo_quote_common',
        locale: 'zh-CN',
        t: ((k: string, _p?: any, fb?: string) => fb ?? k) as any,
        dataSourceManager: { reload } as any,
        context: { loadData } as any,
      }),
    );

    const button = {
      code: 'upload_corrected_bom',
      label: 'Upload Corrected BOM',
      action: {
        type: 'command',
        command: 'qo_quote_common:import_corrected_bom',
        targetRecordId: '${form.pid}',
        refresh: ['quoteRecomputeStatus', 'lines', 'quoteBomImports'],
      },
    } as unknown as ButtonConfig;

    await act(async () => {
      await result.current.handleAction(button);
    });

    expect(result.current.activeTask?.status).toBe('failed');
    expect(loadData).toHaveBeenCalled();
    expect(reload).toHaveBeenCalledWith(['quoteRecomputeStatus', 'lines', 'quoteBomImports']);
  });

  it('prefers command context.detail over generic HTTP error text', async () => {
    fetchResultMock.mockResolvedValueOnce({
      code: '35000',
      desc: 'Bad parameter',
      message: 'Bad parameter',
      data: null,
      context: {
        detail:
          'Plugin handler execution failed: Quote readiness gate failed: BOM_PRICE_MISSING: E2E-MISSING-PRICE',
      },
    });

    const loadData = vi.fn();
    const showToast = vi.fn();
    const onError = vi.fn();
    const { result } = renderHook(() =>
      useActionHandler({
        runtime: makeRuntime(),
        navigate: vi.fn() as any,
        tableName: 'qo_quote_common',
        locale: 'zh-CN',
        t: ((k: string, _p?: any, fb?: string) => fb ?? k) as any,
        showToast,
        onError,
        context: { loadData } as any,
      }),
    );

    const button = {
      code: 'submit_approval',
      label: 'Submit Approval',
      action: { type: 'command', command: 'qo_quote_common:submit_approval' },
    } as unknown as ButtonConfig;

    await act(async () => {
      await result.current.handleAction(button, {
        pid: '01KTYHZX80DQ0HWWDXTY5JTZEB',
      });
    });

    expect(loadData).not.toHaveBeenCalled();
    expect(result.current.error).toContain('BOM_PRICE_MISSING');
    expect(result.current.error).not.toBe('Bad parameter');
    expect(showToast).toHaveBeenCalledWith(expect.stringContaining('BOM_PRICE_MISSING'), 'error');
    expect(onError).toHaveBeenCalledTimes(1);
    expect((onError.mock.calls[0][0] as Error).message).toContain('BOM_PRICE_MISSING');
  });

  it('uses an explicit command targetRecordId template from runtime context for nested toolbar actions', async () => {
    fetchResultMock.mockResolvedValueOnce({ code: '0', data: { updated: 1 } });

    const loadData = vi.fn().mockResolvedValue(undefined);
    const runtime = makeRuntime({ form: { pid: 'QUOTE-123' } });
    const { result } = renderHook(() =>
      useActionHandler({
        runtime,
        navigate: vi.fn() as any,
        tableName: 'qo_quote_common',
        locale: 'zh-CN',
        t: ((k: string, _p?: any, fb?: string) => fb ?? k) as any,
        context: { loadData } as any,
      }),
    );

    const button = {
      code: 'compute_process_fee',
      label: 'Compute Process Fee',
      action: {
        type: 'command',
        command: 'qo_quote_common:compute_process_fee',
        targetRecordId: '${form.pid}',
      },
    } as unknown as ButtonConfig;

    await act(async () => {
      await result.current.handleAction(button);
    });

    expect(fetchResultMock).toHaveBeenCalledWith(
      '/api/meta/commands/execute/qo_quote_common:compute_process_fee',
      expect.objectContaining({
        method: 'post',
        params: expect.objectContaining({
          targetRecordId: 'QUOTE-123',
          operationType: 'UPDATE',
        }),
      }),
    );
    expect(loadData).toHaveBeenCalled();
  });

  it('reloads explicit data sources for nested toolbar commands instead of navigating away', async () => {
    fetchResultMock.mockResolvedValueOnce({ code: '0', data: { updated: 1 } });

    const navigate = vi.fn();
    const reload = vi.fn().mockResolvedValue(undefined);
    const runtime = makeRuntime({ form: { pid: 'QUOTE-123' } });
    const { result } = renderHook(() =>
      useActionHandler({
        runtime,
        navigate: navigate as any,
        tableName: 'qo_quote_common',
        locale: 'zh-CN',
        t: ((k: string, _p?: any, fb?: string) => fb ?? k) as any,
        dataSourceManager: { reload } as any,
        context: {} as any,
      }),
    );

    const button = {
      code: 'compute_process_fee',
      label: 'Compute Process Fee',
      action: {
        type: 'command',
        command: 'qo_quote_common:compute_process_fee',
        targetRecordId: '${form.pid}',
        refresh: ['processCostItems', 'lines', 'quoteSummary'],
      },
    } as unknown as ButtonConfig;

    await act(async () => {
      await result.current.handleAction(button);
    });

    expect(fetchResultMock).toHaveBeenCalledWith(
      '/api/meta/commands/execute/qo_quote_common:compute_process_fee',
      expect.objectContaining({
        method: 'post',
        params: expect.objectContaining({
          targetRecordId: 'QUOTE-123',
          operationType: 'UPDATE',
        }),
      }),
    );
    expect(reload).toHaveBeenCalledWith(['processCostItems', 'lines', 'quoteSummary']);
    expect(navigate).not.toHaveBeenCalled();
  });

  it('uses a hard browser navigation when navigate actions opt into hardReload', async () => {
    const navigate = vi.fn();
    const openSpy = vi.spyOn(window, 'open').mockImplementation(() => null);
    const { result } = renderHook(() =>
      useActionHandler({
        runtime: makeRuntime(),
        navigate: navigate as any,
        tableName: 'qo_quote_common',
        locale: 'zh-CN',
        t: ((k: string, _p?: any, fb?: string) => fb ?? k) as any,
        context: {} as any,
      }),
    );

    const button = {
      code: 'open_bom_workbench',
      label: 'Open BOM Workbench',
      action: {
        type: 'navigate',
        to: '/p/bom_conversion_task_pcba_workbench/view/{pid}',
        hardReload: true,
      },
    } as unknown as ButtonConfig;

    await act(async () => {
      await result.current.handleAction(button, { pid: 'TASK-123' });
    });

    expect(openSpy).toHaveBeenCalledWith('/p/bom_conversion_task_pcba_workbench/view/TASK-123', '_self');
    expect(navigate).not.toHaveBeenCalled();
    openSpy.mockRestore();
  });

  it('injects the original filename alongside promptUpload file ids', async () => {
    fetchResultMock.mockResolvedValueOnce({ code: '0', data: { importId: 'BOM-IMPORT-1' } });
    const pickFileSpy = vi.spyOn(promptUpload, 'pickFile').mockResolvedValueOnce(
      new File(['mpn,qty\nRC0603FR-0710KL,2'], 'corrected-bom-ui-upload.xlsx'),
    );
    vi.spyOn(promptUpload, 'uploadCommandFile').mockResolvedValueOnce('FILE-123');

    const reload = vi.fn().mockResolvedValue(undefined);
    const runtime = makeRuntime({ form: { pid: 'QUOTE-123' } });
    const { result } = renderHook(() =>
      useActionHandler({
        runtime,
        navigate: vi.fn() as any,
        tableName: 'qo_quote_common',
        locale: 'zh-CN',
        t: ((k: string, _p?: any, fb?: string) => fb ?? k) as any,
        dataSourceManager: { reload } as any,
        context: {} as any,
      }),
    );

    const button = {
      code: 'import_corrected_bom',
      label: 'Upload Corrected BOM',
      promptUpload: { key: 'corrected_bom_file_id', accept: '.xlsx,.xls,.csv' },
      action: {
        type: 'command',
        command: 'qo_quote_common:import_corrected_bom',
        targetRecordId: '${form.pid}',
        refresh: ['lines', 'quoteSummary'],
      },
    } as unknown as ButtonConfig;

    await act(async () => {
      await result.current.handleAction(button);
    });

    expect(fetchResultMock).toHaveBeenCalledWith(
      '/api/meta/commands/execute/qo_quote_common:import_corrected_bom',
      expect.objectContaining({
        method: 'post',
        params: expect.objectContaining({
          targetRecordId: 'QUOTE-123',
          operationType: 'UPDATE',
          payload: expect.objectContaining({
            corrected_bom_file_id: 'FILE-123',
            corrected_bom_filename: 'corrected-bom-ui-upload.xlsx',
          }),
        }),
      }),
    );
    expect(pickFileSpy).toHaveBeenCalledWith('.xlsx,.xls,.csv');
    expect(reload).toHaveBeenCalledWith(['lines', 'quoteSummary']);
  });

  it('refreshes detail data sources and shows completion feedback after promptUpload commands', async () => {
    fetchResultMock.mockResolvedValueOnce({ code: '0', data: { importId: 'BOM-IMPORT-1' } });
    vi.spyOn(promptUpload, 'pickFile').mockResolvedValueOnce(
      new File(['mpn,qty\nRC0603FR-0710KL,2'], 'corrected-bom-ui-upload.xlsx'),
    );
    vi.spyOn(promptUpload, 'uploadCommandFile').mockResolvedValueOnce('FILE-123');

    const loadData = vi.fn().mockResolvedValue(undefined);
    const reload = vi.fn().mockResolvedValue(undefined);
    const showToast = vi.fn();
    const runtime = makeRuntime({ form: { pid: 'QUOTE-123' } });
    const { result } = renderHook(() =>
      useActionHandler({
        runtime,
        navigate: vi.fn() as any,
        tableName: 'qo_quote_common',
        locale: 'zh-CN',
        t: ((k: string, _p?: any, fb?: string) => fb ?? k) as any,
        dataSourceManager: { reload } as any,
        context: { loadData } as any,
        showToast,
      }),
    );

    const button = {
      code: 'upload_corrected_bom',
      label: { 'zh-CN': '上传修正BOM', 'en-US': 'Upload Corrected BOM' },
      promptUpload: { key: 'corrected_bom_file_id', accept: '.xlsx,.xls,.csv' },
      action: {
        type: 'command',
        command: 'qo_quote_common:import_corrected_bom',
        targetRecordId: '${form.pid}',
        refresh: ['lines', 'quoteBomImports', 'quoteSummary'],
      },
    } as unknown as ButtonConfig;

    await act(async () => {
      await result.current.handleAction(button);
    });

    expect(loadData).toHaveBeenCalled();
    expect(reload).toHaveBeenCalledWith(['lines', 'quoteBomImports', 'quoteSummary']);
    expect(showToast).toHaveBeenCalledWith(
      expect.stringContaining('已选择 corrected-bom-ui-upload.xlsx'),
      'info',
    );
    expect(showToast).toHaveBeenCalledWith(
      expect.stringContaining('corrected-bom-ui-upload.xlsx 已上传'),
      'info',
    );
    expect(showToast).toHaveBeenCalledWith(
      expect.stringContaining('corrected-bom-ui-upload.xlsx 已处理完成'),
      'success',
    );
  });

  it('uses the runtime toast bridge for promptUpload feedback when block renderers do not pass showToast', async () => {
    fetchResultMock.mockResolvedValueOnce({
      code: '0',
      data: {
        commandCode: 'qo_quote_common:import_process_fee_rules',
        phaseReached: 'completed',
        data: {
          ruleSetId: 'RULE-SET-1',
          ruleVersion: 'PFR-20260615113614',
          importedLines: 10,
          status: 'draft',
        },
      },
    });
    vi.spyOn(promptUpload, 'pickFile').mockResolvedValueOnce(
      new File(['stage,points\nSMT,48'], 'process-fee-rules.xlsx'),
    );
    vi.spyOn(promptUpload, 'uploadCommandFile').mockResolvedValueOnce('FILE-RULES-1');

    const runtimeToast = vi.fn();
    const reload = vi.fn().mockResolvedValue(undefined);
    const runtime = {
      ...makeRuntime({ form: { pid: 'QUOTE-123' } }),
      getShowToast: () => runtimeToast,
    } as unknown as SchemaRuntime;

    const { result } = renderHook(() =>
      useActionHandler({
        runtime,
        navigate: vi.fn() as any,
        tableName: 'qo_quote_common',
        locale: 'zh-CN',
        t: ((k: string, _p?: any, fb?: string) => fb ?? k) as any,
        dataSourceManager: { reload } as any,
        context: {} as any,
      }),
    );

    const button = {
      code: 'reimport_process_fee_rules',
      label: { 'zh-CN': '重新导入规则 Excel', en: 'Re-import Rule Excel' },
      promptUpload: 'process_rule_file_id',
      action: {
        type: 'command',
        command: 'qo_quote_common:import_process_fee_rules',
        targetRecordId: '${form.pid}',
        refresh: ['processFeeMetrics', 'processFeeRuleHits', 'quoteSummary'],
      },
    } as unknown as ButtonConfig;

    await act(async () => {
      await result.current.handleAction(button);
    });

    expect(reload).toHaveBeenCalledWith([
      'processFeeMetrics',
      'processFeeRuleHits',
      'quoteSummary',
    ]);
    expect(runtimeToast).toHaveBeenCalledWith(
      expect.stringContaining('已选择 process-fee-rules.xlsx'),
      'info',
    );
    expect(runtimeToast).toHaveBeenCalledWith(
      expect.stringContaining('process-fee-rules.xlsx 已上传'),
      'info',
    );
    expect(runtimeToast).toHaveBeenCalledWith(
      expect.stringContaining('process-fee-rules.xlsx 已导入为草稿 PFR-20260615113614，共 10 行；发布后生效'),
      'success',
    );
  });

  it('falls back to global toast events for promptUpload feedback when no toast bridge is provided', async () => {
    fetchResultMock.mockResolvedValueOnce({
      code: '0',
      data: {
        commandCode: 'qo_quote_common:import_process_fee_rules',
        phaseReached: 'completed',
        data: {
          ruleVersion: 'PFR-20260615120000',
          importedLines: 10,
          status: 'draft',
        },
      },
    });
    vi.spyOn(promptUpload, 'pickFile').mockResolvedValueOnce(
      new File(['stage,points\nSMT,48'], 'process-fee-rules.xlsx'),
    );
    vi.spyOn(promptUpload, 'uploadCommandFile').mockResolvedValueOnce('FILE-RULES-2');

    const toastEvents: CustomEvent[] = [];
    const onToast = (event: Event) => toastEvents.push(event as CustomEvent);
    window.addEventListener('aura:toast', onToast);

    try {
      const reload = vi.fn().mockResolvedValue(undefined);
      const runtime = makeRuntime({ form: { pid: 'QUOTE-123' } });
      const { result } = renderHook(() =>
        useActionHandler({
          runtime,
          navigate: vi.fn() as any,
          tableName: 'qo_quote_common',
          locale: 'zh-CN',
          t: ((k: string, _p?: any, fb?: string) => fb ?? k) as any,
          dataSourceManager: { reload } as any,
          context: {} as any,
        }),
      );

      const button = {
        code: 'reimport_process_fee_rules',
        label: { 'zh-CN': '重新导入规则 Excel', en: 'Re-import Rule Excel' },
        promptUpload: 'process_rule_file_id',
        action: {
          type: 'command',
          command: 'qo_quote_common:import_process_fee_rules',
          targetRecordId: '${form.pid}',
          refresh: ['processFeeMetrics', 'processFeeRuleHits', 'quoteSummary'],
        },
      } as unknown as ButtonConfig;

      await act(async () => {
        await result.current.handleAction(button);
      });

      expect(reload).toHaveBeenCalledWith([
        'processFeeMetrics',
        'processFeeRuleHits',
        'quoteSummary',
      ]);
      expect(toastEvents.map((event) => event.detail)).toEqual(
        expect.arrayContaining([
          expect.objectContaining({
            message: expect.stringContaining('已选择 process-fee-rules.xlsx'),
            variant: 'info',
          }),
          expect.objectContaining({
            message: expect.stringContaining('process-fee-rules.xlsx 已上传'),
            variant: 'info',
          }),
          expect.objectContaining({
            message: expect.stringContaining(
              'process-fee-rules.xlsx 已导入为草稿 PFR-20260615120000，共 10 行；发布后生效',
            ),
            variant: 'success',
          }),
        ]),
      );
    } finally {
      window.removeEventListener('aura:toast', onToast);
    }
  });

  it('merges action payload with promptUpload file ids for command buttons', async () => {
    fetchResultMock.mockResolvedValueOnce({ code: '0', data: { attachmentId: 'ATT-1' } });
    vi.spyOn(promptUpload, 'pickFile').mockResolvedValueOnce(
      new File(['refdes,mpn\nR1,RC0603'], 'raw-bom.xlsx'),
    );
    vi.spyOn(promptUpload, 'uploadCommandFile').mockResolvedValueOnce('FILE-RFQ-1');

    const reload = vi.fn().mockResolvedValue(undefined);
    const runtime = makeRuntime({ form: { pid: 'RFQ-123' } });
    const { result } = renderHook(() =>
      useActionHandler({
        runtime,
        navigate: vi.fn() as any,
        tableName: 'crm_customer_request_pcba_rfq',
        locale: 'zh-CN',
        t: ((k: string, _p?: any, fb?: string) => fb ?? k) as any,
        dataSourceManager: { reload } as any,
        context: {} as any,
      }),
    );

    const button = {
      code: 'upload_raw_bom',
      label: 'Upload Raw BOM',
      promptUpload: { key: 'source_file_id', accept: '.xlsx,.xls,.csv' },
      action: {
        type: 'command',
        command: 'crm_customer_request_pcba_rfq:upload_source_attachment',
        targetRecordId: '${form.pid}',
        payload: {
          attachment_type: 'raw_bom',
        },
        refresh: ['rfqSourceAttachments'],
      },
    } as unknown as ButtonConfig;

    await act(async () => {
      await result.current.handleAction(button);
    });

    expect(fetchResultMock).toHaveBeenCalledWith(
      '/api/meta/commands/execute/crm_customer_request_pcba_rfq:upload_source_attachment',
      expect.objectContaining({
        method: 'post',
        params: expect.objectContaining({
          targetRecordId: 'RFQ-123',
          operationType: 'UPDATE',
          payload: expect.objectContaining({
            attachment_type: 'raw_bom',
            source_file_id: 'FILE-RFQ-1',
            source_filename: 'raw-bom.xlsx',
          }),
        }),
      }),
    );
    expect(reload).toHaveBeenCalledWith(['rfqSourceAttachments']);
  });
});
