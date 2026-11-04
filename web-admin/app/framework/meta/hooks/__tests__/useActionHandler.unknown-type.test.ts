/**
 * useActionHandler.unknown-type.test.ts
 *
 * Regression test for the silent no-op on unknown action.type (plugins#771).
 * The action-dispatch switch only handles command/navigate/state_transition/
 * builtin/workflow/flow; a page author writing e.g. "command.execute" (which is
 * only valid as an ActionRegistry flow-step action) used to fall through the
 * switch without any diagnostic, leaving toolbar buttons dead. The default
 * branch now logs the misconfiguration with the page identifier and throws in
 * development so the error surfaces instead of being swallowed.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';

import { useActionHandler } from '~/framework/meta/hooks/useActionHandler';
import type { ButtonConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';

function makeRuntime(): SchemaRuntime {
  const context: Record<string, unknown> = {
    locale: 'zh-CN',
    t: (k: string) => k,
    form: {},
    global: {},
    state: {},
  };
  const stub = {
    executeHandler: vi.fn().mockResolvedValue(undefined),
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
    tableName: 'mes_wages_detail',
    locale: 'zh-CN',
    t: vi.fn(
      (_key: string, _params?: Record<string, any>, fallback?: string) => fallback ?? _key,
    ),
    ...overrides,
  };
}

let consoleError: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  // One shared spy instance per file (repeat spyOn calls return the same
  // mock), so clear recorded calls before every test.
  consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
  consoleError.mockClear();
});

afterEach(() => {
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
});

describe('useActionHandler - unknown action.type', () => {
  it('logs the unknown type with the page identifier and surfaces an error in development', async () => {
    vi.stubEnv('NODE_ENV', 'development');
    const onError = vi.fn();
    const runtime = makeRuntime();
    const { result } = renderHook(() =>
      useActionHandler(baseOptions(runtime, { onError })),
    );

    await act(async () => {
      await result.current.handleAction({
        code: 'approve_substitute',
        label: 'Approve',
        action: { type: 'command.execute' },
      } as unknown as ButtonConfig);
    });

    // Explicit console.error carrying the action type, button and page.
    expect(consoleError).toHaveBeenCalledWith(expect.stringContaining('command.execute'));
    expect(consoleError).toHaveBeenCalledWith(expect.stringContaining('approve_substitute'));
    expect(consoleError).toHaveBeenCalledWith(expect.stringContaining('mes_wages_detail'));

    // In development the thrown error reaches the standard error surface.
    expect(onError).toHaveBeenCalledOnce();
    expect(result.current.error).toContain('command.execute');
    expect(result.current.error).toContain('mes_wages_detail');
    expect(result.current.loading).toBe(false);
  });

  it('logs but keeps the legacy silent no-op outside development (no error surface)', async () => {
    vi.stubEnv('NODE_ENV', 'production');
    const onError = vi.fn();
    const runtime = makeRuntime();
    const { result } = renderHook(() =>
      useActionHandler(baseOptions(runtime, { onError })),
    );

    await act(async () => {
      await result.current.handleAction({
        code: 'approve_substitute',
        action: { type: 'command.execute' },
      } as unknown as ButtonConfig);
    });

    expect(consoleError).toHaveBeenCalledWith(expect.stringContaining('command.execute'));
    expect(onError).not.toHaveBeenCalled();
    expect(result.current.error).toBeNull();
    expect(result.current.loading).toBe(false);
  });

  it('does not flag supported action types (flow handler still dispatches)', async () => {
    vi.stubEnv('NODE_ENV', 'development');
    const runtime = makeRuntime();
    const { result } = renderHook(() => useActionHandler(baseOptions(runtime)));

    await act(async () => {
      await result.current.handleAction({
        code: 'submitOrder',
        events: { onClick: { handler: 'submitOrder' } },
      } as unknown as ButtonConfig);
    });

    expect(runtime.executeHandler).toHaveBeenCalledTimes(1);
    expect(consoleError).not.toHaveBeenCalledWith(
      expect.stringContaining('Unknown action.type'),
    );
    expect(result.current.error).toBeNull();
  });
});
