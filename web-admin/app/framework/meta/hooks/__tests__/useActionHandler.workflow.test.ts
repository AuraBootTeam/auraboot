/**
 * useActionHandler.workflow.test.ts
 *
 * Verifies that the generic workflow action resolves its record-derived
 * request and delegates it to the installed workflow provider.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';

import { useActionHandler } from '~/framework/meta/hooks/useActionHandler';
import type { ButtonConfig } from '~/framework/meta/schemas/types';

const { invokeMock } = vi.hoisted(() => ({
  invokeMock: vi.fn(),
}));

vi.mock('~/framework/bootstrap', () => ({
  getKernel: () => ({ pluginLoader: { invoke: invokeMock } }),
}));

function makeButton(action: ButtonConfig['action']): ButtonConfig {
  return {
    code: 'start-workflow',
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

describe('useActionHandler - action.type=workflow', () => {
  beforeEach(() => {
    invokeMock.mockReset();
  });

  it('calls startProcessFromAction with resolved businessKey + JSONPath variables', async () => {
    invokeMock.mockResolvedValue({
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
          type: 'workflow',
          workflowKey: 'leave_request',
          businessKeyField: 'id',
          variables: { days: '$.days', actorName: '$.actor.name' },
        }),
        { id: 'rec-1', days: 3, actor: { name: 'Alice' } },
      );
    });

    expect(invokeMock).toHaveBeenCalledTimes(1);
    expect(invokeMock).toHaveBeenCalledWith('workflow.start', {
      workflowKey: 'leave_request',
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
          type: 'workflow',
          workflowKey: 'leave_request',
          businessKeyField: 'id',
        }),
        { id: '   ' },
      );
    });

    expect(invokeMock).not.toHaveBeenCalled();
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
          type: 'workflow',
          workflowKey: 'leave_request',
          businessKeyField: 'id',
          variables: { firstItem: '$.items[0]' },
        }),
        { id: 'rec-1', items: ['a', 'b'] },
      );
    });

    expect(invokeMock).not.toHaveBeenCalled();
    expect(onError).toHaveBeenCalledTimes(1);
    const err = onError.mock.calls[0][0] as Error;
    expect(err.message).toMatch(/bracket/i);
  });

  it('surfaces a distinct toast message when backend reports deduped=true', async () => {
    invokeMock.mockResolvedValue({
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
          type: 'workflow',
          workflowKey: 'leave_request',
          businessKeyField: 'id',
        }),
        { id: 'rec-1' },
      );
    });

    expect(invokeMock).toHaveBeenCalledTimes(1);
    expect(showToast).toHaveBeenCalledWith(
      expect.stringContaining('已有工作流'),
      'success',
    );
  });
});
