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
