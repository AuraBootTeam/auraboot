import { act, renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ButtonConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';

const mocks = vi.hoisted(() => ({ fetch: vi.fn(), preview: vi.fn(), available: true }));
vi.mock('~/shared/services/http-client', () => ({
  fetchResult: (...args: unknown[]) => mocks.fetch(...args),
}));
vi.mock('~/framework/meta/rendering/components/ProtectedFilePreviewProvider', () => ({
  useProtectedFilePreview: () => (mocks.available ? mocks.preview : null),
  ProtectedFilePreviewProvider: ({ children }: any) => children,
}));
import { useActionHandler } from '../useActionHandler';

const fileId = '01ARZ3NDEKTSV4RRFFQ69G5FAW';
const command = 'example:current_document';
const record = { pid: 'order-a', row_version: 3, privateNote: 'must not enter the command' };
const action = {
  code: 'document',
  action: {
    type: 'command',
    command,
    operationType: 'UPDATE',
    includeRecordPayload: false,
    payload: {},
    preview: { fileIdField: 'fileId', contentType: 'application/pdf' },
  },
} as ButtonConfig;
const completed = () => ({ code: '0', data: { phaseReached: 'completed', data: { fileId } } });
function setup(refresh?: () => Promise<void>) {
  const navigate = vi.fn();
  const runtime = {
    getContext: () => ({ record, form: record, state: {}, global: {} }),
    getSchema: () => ({
      modelCode: 'example',
      extension: {
        commandRequestByCommand: {
          [command]: {
            idempotent: true,
            payloadFields: [],
            versionField: 'row_version',
            newIntentAfterSuccess: true,
          },
        },
      },
    }),
    getFlowRunner: () => null,
    getDataSourceManager: () => undefined,
    getEvaluator: () => ({
      evaluateCondition: () => true,
      evaluateTemplate: (value: string) => value,
    }),
  } as unknown as SchemaRuntime;
  return {
    navigate,
    ...renderHook(() =>
      useActionHandler({
        runtime,
        navigate,
        tableName: 'example',
        context: { record, ...(refresh ? { loadData: refresh } : {}) },
        locale: 'zh-CN',
        t: ((key: string) => key) as any,
      }),
    ),
  };
}
beforeEach(() => {
  mocks.available = true;
  mocks.preview.mockReset();
  mocks.preview.mockResolvedValue(undefined);
  mocks.fetch.mockReset();
  mocks.fetch.mockResolvedValue(completed());
});

describe('DSL command preview composition', () => {
  it('sends only the reviewed target/version and empty payload, refreshes, then opens the protected file', async () => {
    const order: string[] = [];
    const refresh = vi.fn(async () => {
      order.push('refresh');
    });
    mocks.preview.mockImplementation(async () => {
      order.push('preview');
    });
    const view = setup(refresh);
    await act(async () => view.result.current.handleAction(action, record));
    expect(mocks.fetch).toHaveBeenCalledTimes(1);
    const [path, options] = mocks.fetch.mock.calls[0];
    expect(path).toBe('/api/meta/commands/execute/' + command);
    expect(options.params).toEqual({
      targetRecordPid: 'order-a',
      operationType: 'UPDATE',
      expectedVersion: 3,
      clientRequestId: expect.any(String),
      payload: {},
    });
    expect(order).toEqual(['refresh', 'preview']);
    expect(mocks.preview).toHaveBeenCalledWith(
      expect.objectContaining({
        url: '/api/file/download/' + fileId,
        contentType: 'application/pdf',
      }),
    );
    expect(view.navigate).not.toHaveBeenCalled();
  });

  it('an action without a reload owner keeps its page open for preview', async () => {
    const view = setup();
    await act(async () => view.result.current.handleAction(action, record));
    expect(mocks.preview).toHaveBeenCalledTimes(1);
    expect(view.navigate).not.toHaveBeenCalled();
  });

  it.each([
    'missing-host',
    'ambiguous-modes',
    'unsupported-type',
    'nonstring-field',
    'invalid-field',
  ])('rejects %s before any command write', async (defect) => {
    if (defect === 'missing-host') mocks.available = false;
    const changed = structuredClone(action) as any;
    if (defect === 'ambiguous-modes') changed.action.download = { fileIdField: 'fileId' };
    if (defect === 'unsupported-type') changed.action.preview.contentType = 'text/html';
    if (defect === 'nonstring-field') changed.action.preview.fileIdField = 123;
    if (defect === 'invalid-field') changed.action.preview.fileIdField = 'fileId[0]';
    const view = setup();
    await act(async () => view.result.current.handleAction(changed, record));
    expect(mocks.fetch).not.toHaveBeenCalled();
    expect(mocks.preview).not.toHaveBeenCalled();
    expect(view.result.current.error).toBeTruthy();
  });

  it('a failed command or missing file never opens a misleading preview', async () => {
    const view = setup();
    mocks.fetch.mockResolvedValueOnce({ code: '403', message: 'Not available' });
    await act(async () => view.result.current.handleAction(action, record));
    mocks.fetch.mockResolvedValueOnce({ code: '0', data: { phaseReached: 'completed', data: {} } });
    await act(async () => view.result.current.handleAction(action, record));
    expect(mocks.preview).not.toHaveBeenCalled();
    expect(view.result.current.error).toBeTruthy();
  });

  it('uncertain command retry retains the original key and reviewed version', async () => {
    const view = setup();
    mocks.fetch.mockResolvedValueOnce({ code: 'NETWORK_ERROR', message: 'Reply lost' });
    await act(async () => view.result.current.handleAction(action, record));
    await act(async () => view.result.current.handleAction(action, record));
    expect(mocks.fetch).toHaveBeenCalledTimes(2);
    const first = mocks.fetch.mock.calls[0][1].params,
      second = mocks.fetch.mock.calls[1][1].params;
    expect(second).toEqual(first);
    expect(first.expectedVersion).toBe(3);
    expect(mocks.preview).toHaveBeenCalledTimes(1);
  });
});
