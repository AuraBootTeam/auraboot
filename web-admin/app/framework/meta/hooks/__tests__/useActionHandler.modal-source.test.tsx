import { act, renderHook } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { useActionHandler } from '../useActionHandler';
import type { ButtonConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
const open = vi.hoisted(() => vi.fn());
vi.mock('~/framework/meta/rendering/pages/detail/DetailModalProvider', () => ({
  useDetailModal: () => open,
}));
vi.mock('~/shared/services/http-client', () => ({ fetchResult: vi.fn() }));
function runtime(): SchemaRuntime {
  return {
    getContext: () => ({}),
    getShowToast: () => undefined,
    getSchema: () => ({ id: 'cases', dataSources: { records: {} } }),
    getEvaluator: () => ({
      evaluateCondition: () => true,
      evaluateTemplate: (text: string) => text,
    }),
    getFlowRunner: () => null,
  } as unknown as SchemaRuntime;
}
const button = {
  code: 'detail',
  action: {
    type: 'navigate',
    to: '/p/case/view/{pid}',
    presentation: { mode: 'modal', width: 900 },
  },
} as ButtonConfig;
describe('native modal source callback completion', () => {
  it('returns the actual pending DMS read promise instead of resolving the close callback early', async () => {
    open.mockClear();
    let finish!: () => void;
    const read = new Promise<void>((resolve) => {
      finish = resolve;
    });
    const reload = vi.fn(() => read),
      dataSourceManager = { has: () => true, reload } as any;
    const { result } = renderHook(() =>
      useActionHandler({
        runtime: runtime(),
        navigate: vi.fn(),
        tableName: 'case',
        dataSourceManager,
        locale: 'en',
        t: (key) => key,
      }),
    );
    await act(async () => {
      await result.current.handleAction(button, { pid: 'A' });
    });
    const callback = open.mock.calls[0][1] as () => Promise<void>;
    const pending = callback();
    expect(pending).toBeInstanceOf(Promise);
    expect(reload).toHaveBeenCalledExactlyOnceWith(['records']);
    let settled = false;
    void pending.then(() => {
      settled = true;
    });
    await Promise.resolve();
    expect(settled).toBe(false);
    finish();
    await pending;
    expect(settled).toBe(true);
  });
  it('keeps native failure feedback and rejects completion so the provider can use its block fallback', async () => {
    open.mockClear();
    const failure = new Error('Source read failed'),
      toast = vi.fn();
    const dataSourceManager = {
      has: () => true,
      reload: vi.fn().mockRejectedValue(failure),
    } as any;
    const { result } = renderHook(() =>
      useActionHandler({
        runtime: runtime(),
        navigate: vi.fn(),
        tableName: 'case',
        dataSourceManager,
        locale: 'en',
        t: (key) => key,
        showToast: toast,
      }),
    );
    await act(async () => {
      await result.current.handleAction(button, { pid: 'A' });
    });
    await expect(open.mock.calls[0][1]()).rejects.toBe(failure);
    expect(toast).toHaveBeenCalledOnce();
  });
});

it('treats an error in real DMS state as a failed read even when reload resolves', async () => {
  open.mockClear();
  const failure = new Error('DMS read failed'),
    toast = vi.fn();
  const dataSourceManager = {
    has: () => true,
    reload: vi.fn().mockResolvedValue(undefined),
    getState: () => ({ error: failure }),
  } as any;
  const { result } = renderHook(() =>
    useActionHandler({
      runtime: runtime(),
      navigate: vi.fn(),
      tableName: 'case',
      dataSourceManager,
      locale: 'en',
      t: (key) => key,
      showToast: toast,
    }),
  );
  await act(async () => {
    await result.current.handleAction(button, { pid: 'A' });
  });
  await expect(open.mock.calls[0][1]()).rejects.toBe(failure);
  expect(toast).toHaveBeenCalledOnce();
});
