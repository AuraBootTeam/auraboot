/**
 * useActionHandler.pin-model.test.ts
 *
 * Regression test for the pin-GET record snapshot on custom pages. The command
 * branch fetches a fresh record snapshot (`GET /api/dynamic/{model}/{pid}`)
 * before opening command inputs. Page-level callers hold the raw route segment
 * as tableName, but the backend authorizes `model.{pageKey}.read` against the
 * raw pageKey before resolving the model — so on custom pages whose pageKey
 * differs from the model (e.g. wg_my_wage) the pin-GET 403'd and the button
 * died. Those callers now pass `recordReadModel` (schema.modelCode, mirroring
 * resolveEditRecordEndpoint) while the navigate fallback keeps the route
 * segment; block renderers that already resolve their row model into tableName
 * (resolveRowActionModel) keep the default.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';

const fetchResultMock = vi.fn();
vi.mock('~/shared/services/http-client', () => ({
  fetchResult: (...args: unknown[]) => fetchResultMock(...args),
}));

import { useActionHandler } from '~/framework/meta/hooks/useActionHandler';
import * as actionRegistry from '~/framework/meta/runtime/actions/ActionRegistry';
import type { ButtonConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';

function makeRuntime(contextOverrides: Record<string, unknown> = {}): SchemaRuntime {
  const context: Record<string, unknown> = {
    locale: 'zh-CN',
    t: (k: string) => k,
    form: {},
    global: {},
    state: {},
    ...contextOverrides,
  };
  return {
    executeHandler: vi.fn(),
    getContext: () => context,
    getShowToast: () => undefined,
    getEvaluator: () => ({ evaluateCondition: () => true, evaluateTemplate: (t: string) => t }),
    getSchema: () => ({ id: 'wg_my_wage', modelCode: 'wg_wage_monthly' }),
    getDataSourceManager: () => ({ getData: () => [], has: () => false, register: vi.fn() }),
    getFlowRunner: () => null,
  } as unknown as SchemaRuntime;
}

describe('useActionHandler - pin-GET record read model', () => {
  beforeEach(() => {
    fetchResultMock.mockReset();
  });

  it('pins the snapshot through recordReadModel when the pageKey differs (custom page, no 403)', async () => {
    const freshRecord = { pid: 'WAGE-1', row_version: 4, state: 'pending' };
    fetchResultMock
      .mockResolvedValueOnce({ code: '0', data: freshRecord })
      .mockResolvedValueOnce({
        code: '0',
        data: { commandCode: 'wg:approve_wage', phaseReached: 'completed' },
      });
    const prompt = vi
      .spyOn(actionRegistry, 'promptInputForm')
      .mockResolvedValue({ note: 'ok' });
    try {
      const navigate = vi.fn();
      const { result } = renderHook(() =>
        useActionHandler({
          runtime: makeRuntime(),
          navigate: navigate as any,
          tableName: 'wg_my_wage',
          recordReadModel: 'wg_wage_monthly',
          locale: 'zh-CN',
          t: ((k: string, _p?: any, fb?: string) => fb ?? k) as any,
        }),
      );
      await act(async () => {
        await result.current.handleAction(
          {
            code: 'approve_substitute',
            action: {
              type: 'command',
              command: 'wg:approve_wage',
              inputFields: [{ field: 'note', type: 'text' }],
            },
          } as unknown as ButtonConfig,
          { pid: 'WAGE-1', row_version: 3 },
        );
      });

      // Snapshot read authorized by the MODEL, not the raw pageKey segment.
      expect(fetchResultMock).toHaveBeenNthCalledWith(
        1,
        '/api/dynamic/wg_wage_monthly/WAGE-1',
        expect.anything(),
      );
      // The pinned fresh snapshot feeds the prompt context.
      expect(prompt.mock.calls[0][4]).toMatchObject({
        record: { pid: 'WAGE-1', row_version: 4 },
      });
      expect(fetchResultMock).toHaveBeenNthCalledWith(
        2,
        '/api/meta/commands/execute/wg:approve_wage',
        expect.anything(),
      );
      expect(result.current.error).toBeNull();
      // The navigate fallback keeps the route segment (page key), not the model.
      expect(navigate).toHaveBeenCalledWith('/p/wg_my_wage');
    } finally {
      prompt.mockRestore();
    }
  });

  it('falls back to tableName for callers that already pass a resolved model', async () => {
    fetchResultMock
      .mockResolvedValueOnce({ code: '0', data: { pid: 'CHILD-1', row_version: 2 } })
      .mockResolvedValueOnce({
        code: '0',
        data: { commandCode: 'child_model:adjust', phaseReached: 'completed' },
      });
    const prompt = vi
      .spyOn(actionRegistry, 'promptInputForm')
      .mockResolvedValue({ quantity: 5 });
    try {
      const { result } = renderHook(() =>
        useActionHandler({
          runtime: makeRuntime(),
          navigate: vi.fn() as any,
          // TableBlockRenderer passes resolveRowActionModel(block) here.
          tableName: 'child_model',
          locale: 'zh-CN',
          t: ((k: string, _p?: any, fb?: string) => fb ?? k) as any,
        }),
      );
      await act(async () => {
        await result.current.handleAction(
          {
            code: 'adjust',
            action: {
              type: 'command',
              command: 'child_model:adjust',
              inputFields: [{ field: 'quantity', type: 'number' }],
            },
          } as unknown as ButtonConfig,
          { pid: 'CHILD-1', row_version: 1 },
        );
      });
      expect(fetchResultMock).toHaveBeenNthCalledWith(
        1,
        '/api/dynamic/child_model/CHILD-1',
        expect.anything(),
      );
      expect(result.current.error).toBeNull();
    } finally {
      prompt.mockRestore();
    }
  });
});
