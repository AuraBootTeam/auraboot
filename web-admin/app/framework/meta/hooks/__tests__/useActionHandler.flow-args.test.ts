/**
 * useActionHandler.flow-args.test.ts
 *
 * Regression test for the Task 9a refactor that migrated block renderers to
 * useActionHandler. Before the refactor, FormButtonsBlockRenderer invoked
 * `runtime.executeHandler(handler, button.events.onClick.args || {})` directly,
 * so any args the DSL attached to the legacy `events.onClick` payload rode
 * through to the handler. After the refactor, args went through
 * `normalizeAction` → `executeSchemaHandler` and were silently dropped because
 * `normalizeAction` only lifted the `handler` string (not its sibling `args`).
 *
 * The fix splices the original button's `events.onClick.args` back into the
 * synthesised button that executeSchemaHandler consumes. These tests pin the
 * contract: args must reach the runtime handler regardless of whether they
 * lived on the original button or on the normalised ActionDef shape.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';

import { useActionHandler } from '~/framework/meta/hooks/useActionHandler';
import type { ButtonConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { ActionCancelledError } from '~/framework/meta/runtime/actions/ActionCancelledError';

function makeRuntime(executeHandler: ReturnType<typeof vi.fn>): SchemaRuntime {
  const context: Record<string, unknown> = {
    locale: 'zh-CN',
    t: (k: string) => k,
    form: {},
    global: {},
    state: {},
  };
  const stub = {
    executeHandler,
    getContext: () => context,
    getEvaluator: () => ({
      evaluateCondition: () => true,
      evaluateTemplate: (tpl: string) => tpl,
    }),
    getSchema: () => ({ id: 'test', modelCode: 'test_model' }),
    getDataSourceManager: () => ({
      getData: () => [],
      has: () => false,
      register: vi.fn(),
    }),
    getFlowRunner: () => null,
  };
  return stub as unknown as SchemaRuntime;
}

function baseOptions(
  runtime: SchemaRuntime,
  overrides: Partial<Parameters<typeof useActionHandler>[0]> = {},
) {
  return {
    runtime,
    navigate: vi.fn() as any,
    tableName: 'demo',
    locale: 'zh-CN',
    t: vi.fn(
      (_key: string, _params?: Record<string, any>, fallback?: string) =>
        fallback ?? _key,
    ),
    ...overrides,
  };
}

describe('useActionHandler - action.type=flow handler preserves args', () => {
  let executeHandlerMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    executeHandlerMock = vi.fn().mockResolvedValue(undefined);
  });

  it('forwards button.events.onClick.args to runtime.executeHandler when normalizeAction lifts a bare handler', async () => {
    const runtime = makeRuntime(executeHandlerMock);
    const { result } = renderHook(() =>
      useActionHandler(baseOptions(runtime)),
    );

    // Legacy shape: only handler + args on events.onClick; no explicit action.
    // normalizeAction promotes this to `{ type: 'flow', handler }` and drops
    // args — the flow-branch must re-attach them.
    const button = {
      code: 'submitOrder',
      label: 'Submit',
      events: {
        onClick: {
          handler: 'submitOrder',
          args: { reason: 'expedited', priority: 9 },
        },
      },
    } as unknown as ButtonConfig;

    await act(async () => {
      await result.current.handleAction(button, { id: 'rec-42' });
    });

    expect(executeHandlerMock).toHaveBeenCalledTimes(1);
    const [handlerName, contextArgs] = executeHandlerMock.mock.calls[0];
    expect(handlerName).toBe('submitOrder');
    // args spread into the handler context (executeSchemaHandler merges args
    // first, then overlays record/row/id/filters/reload, then re-spreads
    // context). The original args must survive.
    expect(contextArgs).toMatchObject({
      reason: 'expedited',
      priority: 9,
    });
    // Sanity: the record still comes through alongside the args.
    expect(contextArgs.record).toEqual({ id: 'rec-42' });
    expect(contextArgs.id).toBe('rec-42');
  });

  it('still works when events.onClick has a handler but no args (no-args path)', async () => {
    const runtime = makeRuntime(executeHandlerMock);
    const { result } = renderHook(() =>
      useActionHandler(baseOptions(runtime)),
    );

    const button = {
      code: 'refresh',
      label: 'Refresh',
      events: { onClick: { handler: 'refreshList' } },
    } as unknown as ButtonConfig;

    await act(async () => {
      await result.current.handleAction(button);
    });

    expect(executeHandlerMock).toHaveBeenCalledTimes(1);
    const [handlerName, contextArgs] = executeHandlerMock.mock.calls[0];
    expect(handlerName).toBe('refreshList');
    // With no args defined, contextArgs still carries the standard fields;
    // no spurious keys should be injected.
    expect(contextArgs.record).toBeUndefined();
  });
});


describe('useActionHandler flow refresh scope', () => {
  it('stops a cancelled flow without reporting an error or refreshing data', async () => {
    const runtime = makeRuntime(vi.fn());
    runtime.getFlowRunner = () => ({ run: vi.fn().mockRejectedValue(new ActionCancelledError()) }) as any;
    const loadData = vi.fn();
    const onError = vi.fn();
    const { result } = renderHook(() => useActionHandler(baseOptions(runtime, { context: { loadData }, onError })));
    await act(async () => {
      await result.current.handleAction({ code: 'confirmPurchase', action: { type: 'flow', steps: [
        { action: 'dialog.confirm' }, { action: 'command.execute' },
      ] } } as ButtonConfig);
    });
    expect(result.current.error).toBeNull();
    expect(result.current.loading).toBe(false);
    expect(onError).not.toHaveBeenCalled();
    expect(loadData).not.toHaveBeenCalled();
  });

  it('still reports an ordinary error whose message happens to mention cancellation', async () => {
    const runtime = makeRuntime(vi.fn());
    runtime.getFlowRunner = () => ({ run: vi.fn().mockRejectedValue(new Error('User cancelled')) }) as any;
    const onError = vi.fn();
    const { result } = renderHook(() => useActionHandler(baseOptions(runtime, { onError })));
    await act(async () => {
      await result.current.handleAction({ code: 'confirmPurchase', action: { type: 'flow', steps: [
        { action: 'dialog.confirm' },
      ] } } as ButtonConfig);
    });
    expect(onError).toHaveBeenCalledOnce();
    expect(result.current.error).toBeTruthy();
    expect(result.current.loading).toBe(false);
  });

  it('retains the selected task filter after a successful runtime flow', async () => {
    const executeHandler = vi.fn();
    const runtime = makeRuntime(executeHandler);
    const run = vi.fn().mockResolvedValue(undefined);
    runtime.getFlowRunner = () => ({ run }) as any;
    const loadData = vi.fn().mockResolvedValue(undefined);
    const filters = { qo_pr_source_task_pid: 'TASK-1', state: 'active' };
    const { result } = renderHook(() => useActionHandler(baseOptions(runtime, { context: { loadData, filters } })));
    await act(async () => {
      await result.current.handleAction({ code: 'save', action: { type: 'flow', steps: [
        { action: 'command.execute', args: { command: 'test:update' } },
      ] } } as ButtonConfig, { pid: 'SOURCE-1' });
    });
    expect(run).toHaveBeenCalledOnce();
    expect(loadData).toHaveBeenCalledWith({ filters });
  });

  it('retains filters after a fallback flow without a runtime runner', async () => {
    const loadData = vi.fn().mockResolvedValue(undefined);
    const filters = { parent_pid: 'PARENT-1' };
    const { result } = renderHook(() => useActionHandler(baseOptions(makeRuntime(vi.fn()), { context: { loadData, filters } })));
    await act(async () => { await result.current.handleAction({ code: 'flow', action: { type: 'flow', steps: [] } } as any, { pid: 'ROW-1' }); });
    expect(loadData).toHaveBeenCalledWith({ filters });
  });
});
