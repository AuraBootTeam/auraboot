/**
 * useActionHandler.bpm.test.ts
 *
 * Verifies the `type: 'bpm'` branch of useActionHandler. Mocks
 * bpmWorkbenchService.startProcessFromAction so we can assert the resolved
 * payload (businessKey, JSONPath-extracted variables) + toast branching on
 * the `deduped` flag without touching the network.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';

import { useActionHandler } from '~/framework/meta/hooks/useActionHandler';
import type { ButtonConfig } from '~/framework/meta/schemas/types';

const { startProcessFromActionMock } = vi.hoisted(() => ({
  startProcessFromActionMock: vi.fn(),
}));

vi.mock('~/plugins/core-bpm/services/bpmWorkbenchService', () => ({
  startProcessFromAction: startProcessFromActionMock,
}));

function makeButton(action: ButtonConfig['action']): ButtonConfig {
  return {
    code: 'start-bpm',
    action,
  } as ButtonConfig;
}

function baseOptions(overrides: Partial<Parameters<typeof useActionHandler>[0]> = {}) {
  return {
    navigate: vi.fn() as any,
    tableName: 'demo',
    locale: 'zh-CN',
    t: vi.fn((_key: string, _params?: Record<string, any>, fallback?: string) =>
      fallback ?? _key,
    ),
    ...overrides,
  };
}

describe('useActionHandler - action.type=bpm', () => {
  beforeEach(() => {
    startProcessFromActionMock.mockReset();
  });

  it('calls startProcessFromAction with resolved businessKey + JSONPath variables', async () => {
    startProcessFromActionMock.mockResolvedValue({
      processInstanceId: 'pi-1',
      deduped: false,
    });
    const loadData = vi.fn();
    const showToast = vi.fn();
    const { result } = renderHook(() =>
      useActionHandler(
        baseOptions({
          context: { loadData },
          showToast,
        }),
      ),
    );

    await act(async () => {
      await result.current.handleAction(
        makeButton({
          type: 'bpm',
          processDefinitionKey: 'leave_request',
          businessKeyField: 'id',
          variables: { days: '$.days', actorName: '$.actor.name' },
        }),
        { id: 'rec-1', days: 3, actor: { name: 'Alice' } },
      );
    });

    expect(startProcessFromActionMock).toHaveBeenCalledTimes(1);
    expect(startProcessFromActionMock).toHaveBeenCalledWith({
      processDefinitionKey: 'leave_request',
      businessKey: 'rec-1',
      variables: { days: 3, actorName: 'Alice' },
    });
    expect(showToast).toHaveBeenCalledWith(expect.stringContaining('启动'), 'success');
    expect(loadData).toHaveBeenCalledTimes(1);
  });

  it('rejects when businessKey resolves to blank', async () => {
    const onError = vi.fn();
    const { result } = renderHook(() =>
      useActionHandler(baseOptions({ onError })),
    );

    await act(async () => {
      await result.current.handleAction(
        makeButton({
          type: 'bpm',
          processDefinitionKey: 'leave_request',
          businessKeyField: 'id',
        }),
        { id: '   ' },
      );
    });

    expect(startProcessFromActionMock).not.toHaveBeenCalled();
    expect(onError).toHaveBeenCalledTimes(1);
    const err = onError.mock.calls[0][0] as Error;
    expect(err.message).toMatch(/blank businessKeyField/i);
  });

  it('rejects when a variable expression uses bracket JSONPath', async () => {
    const onError = vi.fn();
    const { result } = renderHook(() =>
      useActionHandler(baseOptions({ onError })),
    );

    await act(async () => {
      await result.current.handleAction(
        makeButton({
          type: 'bpm',
          processDefinitionKey: 'leave_request',
          businessKeyField: 'id',
          variables: { firstItem: '$.items[0]' },
        }),
        { id: 'rec-1', items: ['a', 'b'] },
      );
    });

    expect(startProcessFromActionMock).not.toHaveBeenCalled();
    expect(onError).toHaveBeenCalledTimes(1);
    const err = onError.mock.calls[0][0] as Error;
    expect(err.message).toMatch(/bracket/i);
  });

  it('surfaces a distinct toast message when backend reports deduped=true', async () => {
    startProcessFromActionMock.mockResolvedValue({
      processInstanceId: 'pi-existing',
      deduped: true,
    });
    const showToast = vi.fn();
    const { result } = renderHook(() =>
      useActionHandler(baseOptions({ showToast })),
    );

    await act(async () => {
      await result.current.handleAction(
        makeButton({
          type: 'bpm',
          processDefinitionKey: 'leave_request',
          businessKeyField: 'id',
        }),
        { id: 'rec-1' },
      );
    });

    expect(startProcessFromActionMock).toHaveBeenCalledTimes(1);
    expect(showToast).toHaveBeenCalledWith(
      expect.stringContaining('已有审批流程'),
      'success',
    );
  });
});
