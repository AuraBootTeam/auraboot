import { describe, it, expect, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { Activity, createElement, type ReactNode } from 'react';
import { useSchemaRuntime } from '~/framework/meta/hooks/useSchemaRuntime';
import { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { DataSourceManager } from '~/framework/meta/runtime/data-pipeline/DataSourceManager';
import { createExpressionContext, type GlobalState } from '~/framework/meta/runtime/expression/context';
import type { UnifiedSchema } from '~/framework/meta/schemas/types';
import { actionRegistry } from '~/framework/meta/runtime/actions/ActionRegistry';
import { fetchResult } from '~/shared/services/http-client';
import formDsl from '~/plugins/core-designer/components/studio/test/final.v1.0/form.json';

vi.mock('~/shared/services/http-client', () => ({
  fetchResult: vi.fn().mockResolvedValue({ code: '0', data: { records: [], total: 0 } }),
}));

const mockedFetchResult = vi.mocked(fetchResult);

const createGlobalState = (): GlobalState => ({
  locale: 'zh-CN',
  theme: 'light',
  t: (key: string) => key,
});

const createManager = () => new DataSourceManager(createExpressionContext());

const minimalSchema: UnifiedSchema = {
  kind: 'form',
  version: '1.0.0',
  id: 'schema.runtime.test',
  title: 'Test',
  layout: {
    type: 'grid',
    cols: 1,
    rowGap: 8,
    colGap: 8,
  },
  blocks: [],
};

describe('SchemaRuntime', () => {
  it('registers missing data sources and cleans up only those it owns', () => {
    const manager = createManager();

    manager.register('shared', {
      endpoint: '/api/shared',
      autoFetch: false,
    });

    const schema: UnifiedSchema = {
      ...minimalSchema,
      dataSources: {
        shared: {
          endpoint: '/api/shared',
        },
        runtimeOnly: {
          endpoint: '/api/runtime',
        },
      },
    };

    const unregisterSpy = vi.spyOn(manager, 'unregister');

    const runtime = new SchemaRuntime({
      schema,
      globalState: createGlobalState(),
      dataSourceManager: manager,
      disableAutoFetch: true,
    });

    expect(manager.getConfig('runtimeOnly')).toBeDefined();

    runtime.destroy();

    expect(unregisterSpy).toHaveBeenCalledWith('runtimeOnly');
    expect(unregisterSpy).not.toHaveBeenCalledWith('shared');
  });

  it('can leave schema data source registration to the page-level manager', () => {
    const manager = createManager();
    const schema: UnifiedSchema = {
      ...minimalSchema,
      dataSources: {
        runtimeOnly: {
          endpoint: '/api/runtime',
        },
      },
    };

    const runtime = new SchemaRuntime({
      schema,
      globalState: createGlobalState(),
      dataSourceManager: manager,
      disableAutoFetch: true,
      skipDataSourceRegistration: true,
    });

    expect(manager.getConfig('runtimeOnly')).toBeUndefined();

    runtime.destroy();
  });

  it('exposes and refreshes page-owned context for block actions', () => {
    const manager = createManager();
    const runtime = new SchemaRuntime({
      schema: {
        ...minimalSchema,
        state: { source: 'schema', keep: true },
      } as UnifiedSchema,
      globalState: createGlobalState(),
      dataSourceManager: manager,
      disableAutoFetch: true,
      initialContext: {
        form: { pid: 'quote-1', status: 'draft' },
        row: { pid: 'quote-1' },
        record: { pid: 'quote-1', status: 'draft' },
        state: { selectedTab: 'bom' },
        $page: { pageKey: 'quote_detail', recordPid: 'quote-1' },
      },
    });

    let context = runtime.getContext() as any;
    expect(context.form.pid).toBe('quote-1');
    expect(context.row.pid).toBe('quote-1');
    expect(context.record.pid).toBe('quote-1');
    expect(context.$page.recordPid).toBe('quote-1');
    expect(context.state).toMatchObject({ source: 'schema', keep: true, selectedTab: 'bom' });

    runtime.syncContext({
      form: { pid: 'quote-2', status: 'review' },
      row: { pid: 'quote-2' },
      record: { pid: 'quote-2', status: 'review' },
      state: { selectedTab: 'excel' },
      $page: { pageKey: 'quote_detail', recordPid: 'quote-2' },
    });

    context = runtime.getContext() as any;
    expect(context.form.pid).toBe('quote-2');
    expect(context.row.pid).toBe('quote-2');
    expect(context.record.status).toBe('review');
    expect(context.$page.recordPid).toBe('quote-2');
    expect(context.state).toMatchObject({ source: 'schema', keep: true, selectedTab: 'excel' });

    runtime.destroy();
  });

  it('restores bindState filter fields from carried page state', () => {
    const manager = createManager();
    const runtime = new SchemaRuntime({
      schema: {
        ...minimalSchema,
        blocks: [
          {
            id: 'opportunity_filters',
            blockType: 'filters',
            fields: [
              {
                field: 'crm_opp_name',
                component: 'SmartInput',
                bindState: 'searchKeyword',
              },
            ],
          },
        ],
      } as UnifiedSchema,
      globalState: createGlobalState(),
      dataSourceManager: manager,
      disableAutoFetch: true,
      initialContext: {
        state: { searchKeyword: '华东智造云' },
      },
    });

    expect(runtime.getContext().state.searchKeyword).toBe('华东智造云');
    expect(runtime.getContext().form?.crm_opp_name).toBe('华东智造云');

    runtime.syncContext({ state: { searchKeyword: '重新筛选' } });
    expect(runtime.getContext().form?.crm_opp_name).toBe('重新筛选');

    runtime.destroy();
  });

  it('delegates flow actions to the ActionRegistry', async () => {
    const manager = createManager();
    const actionName = '__test.action__';
    const handler = vi.fn();

    actionRegistry.register(actionName, handler);

    const schema: UnifiedSchema = {
      ...minimalSchema,
      handlers: {
        testFlow: {
          type: 'flow',
          steps: [
            {
              id: 'start',
              action: actionName,
              args: { foo: 'bar' },
            },
          ],
        },
      },
    };

    const runtime = new SchemaRuntime({
      schema,
      globalState: createGlobalState(),
      dataSourceManager: manager,
      disableAutoFetch: true,
    });

    await runtime.executeHandler('testFlow');

    expect(handler).toHaveBeenCalledTimes(1);
    expect(handler.mock.calls[0][0].args).toEqual({ foo: 'bar' });

    actionRegistry.unregister(actionName);
  });

  it('refreshes scoped form state between flow steps', async () => {
    const manager = createManager();
    const writeAction = '__test.write.form__';
    const readAction = '__test.read.form__';
    const readHandler = vi.fn();

    actionRegistry.register(writeAction, async ({ stateManager, scopeId }) => {
      stateManager.updateForm(scopeId, 'reason', 'operator reason');
    });
    actionRegistry.register(readAction, readHandler);

    const schema: UnifiedSchema = {
      ...minimalSchema,
      handlers: {
        testFlow: {
          type: 'flow',
          steps: [
            { id: 'write', action: writeAction },
            { id: 'read', action: readAction },
          ],
        },
      },
    };

    const runtime = new SchemaRuntime({
      schema,
      globalState: createGlobalState(),
      dataSourceManager: manager,
      disableAutoFetch: true,
    });

    await runtime.executeHandler('testFlow');

    expect(readHandler).toHaveBeenCalledTimes(1);
    expect(readHandler.mock.calls[0][0].expressionContext.form.reason).toBe('operator reason');

    actionRegistry.unregister(writeAction);
    actionRegistry.unregister(readAction);
  });

  it('initializes successfully with the final.v1.0 DSL sample', () => {
    const manager = createManager();
    const runtime = new SchemaRuntime({
      schema: formDsl as unknown as UnifiedSchema,
      globalState: createGlobalState(),
      dataSourceManager: manager,
      disableAutoFetch: true,
    });

    expect(runtime.getSchema().id).toBe('form.store');
  });

  // Regression: workbench detail pages mount with disableAutoFetch=true. Dependency-less
  // KPI named queries (metric-strip) and filter-bound lists whose filter state is simply
  // empty must still fetch on mount — otherwise they render "-" / "暂无数据". Only sources
  // whose dependency parent is genuinely unresolved (e.g. a detail bound to an unselected
  // row) should defer until their dependency changes.
  it('fetches dependency-less and dependency-ready data sources on mount under disableAutoFetch, but defers unresolved-parent sources', async () => {
    mockedFetchResult.mockClear();
    const manager = createManager();
    const schema: UnifiedSchema = {
      ...minimalSchema,
      dataSources: {
        // dependency-less aggregate KPI feeding a metric-strip
        kpi: {
          type: 'namedQuery',
          queryCode: 'demo_kpi',
          format: 'records',
          adaptor: 'table',
          params: {},
        },
        // filter-bound list — parent `state` exists, filter value just unset → ready
        filteredList: {
          type: 'api',
          endpoint: '/api/dynamic/demo_list/list',
          method: 'get',
          adaptor: 'table',
          params: { keyword: '${state.keyword}' },
          dependOn: ['state.keyword'],
        },
        // detail bound to an unselected row — parent `state.selected` missing → defer
        rowDetail: {
          type: 'api',
          endpoint: '/api/dynamic/demo_detail/list',
          method: 'get',
          adaptor: 'table',
          params: { pid: '${state.selected.pid}' },
          dependOn: ['state.selected.pid'],
        },
      },
    };

    new SchemaRuntime({
      schema,
      globalState: createGlobalState(),
      dataSourceManager: manager,
      disableAutoFetch: true,
    });

    await waitFor(() => expect(mockedFetchResult).toHaveBeenCalled());
    const endpoints = mockedFetchResult.mock.calls.map((call) => call[0]);
    expect(endpoints).toContain('/api/datasource/list'); // kpi (namedQuery, no deps)
    expect(endpoints).toContain('/api/dynamic/demo_list/list'); // filteredList (deps ready)
    expect(endpoints).not.toContain('/api/dynamic/demo_detail/list'); // rowDetail deferred
  });
});


vi.mock('~/contexts/ToastContext', () => ({
  useToastContext: () => ({ showSuccessToast: vi.fn(), showErrorToast: vi.fn(), showWarningToast: vi.fn(), showInfoToast: vi.fn() }),
}));

describe('page runtime manager lifecycle', () => {
  it('never synchronizes a destroyed runtime when preserved effects reconnect', async () => {
    const consoleError = vi.spyOn(console, 'error');
    const manager = createManager();
    let mode: 'visible' | 'hidden' = 'visible';
    try {
      const { result, rerender, unmount } = renderHook(({ status }: { status: string }) => useSchemaRuntime({
        schema: minimalSchema, dataSourceManager: manager, navigate: vi.fn(), locale: 'en', t: key => key,
        disableAutoFetch: true, skipDataSourceRegistration: true,
        initialContext: { record: { inv_fgpt_status: status } },
      }), { initialProps: { status: 'pending_pack' }, wrapper: ({ children }: { children: ReactNode }) => createElement(Activity, { mode, children }) });
      await waitFor(() => expect(result.current?.getContext()).toMatchObject({ record: { inv_fgpt_status: 'pending_pack' } }));
      mode = 'hidden';
      rerender({ status: 'pending_pack' });
      mode = 'visible';
      rerender({ status: 'received' });
      await waitFor(() => expect(result.current?.getContext()).toMatchObject({ record: { inv_fgpt_status: 'received' } }));
      unmount();
      expect(consoleError).not.toHaveBeenCalled();
    } finally {
      consoleError.mockRestore();
    }
  });

  it('rebinds the same schema to a new data manager instead of retaining a destroyed scope', async () => {
    const first = createManager();
    const second = createManager();
    second.register('pageOwned', { endpoint: '/api/page-owned', autoFetch: false });
    const { result, rerender, unmount } = renderHook(({ manager }: { manager: DataSourceManager }) => useSchemaRuntime({
      schema: minimalSchema, dataSourceManager: manager, navigate: vi.fn(), locale: 'en', t: key => key,
      disableAutoFetch: true, skipDataSourceRegistration: true,
      initialContext: { record: { pid: 'fixture-task', inv_fgpt_status: 'pending_pack' } },
    }), { initialProps: { manager: first } });
    await waitFor(() => expect(result.current).not.toBeNull());
    const previous = result.current!;
    rerender({ manager: second });
    await waitFor(() => expect(result.current).not.toBe(previous));
    const active = result.current!;
    expect(active.getDataSourceManager()).toBe(second);
    expect(active.getContext()).toMatchObject({ record: { pid: 'fixture-task', inv_fgpt_status: 'pending_pack' } });
    expect(previous.getStateManager().getScope(previous.getScopeId())).toBeUndefined();
    unmount();
    expect(active.getStateManager().getScope(active.getScopeId())).toBeUndefined();
    expect(second.getConfig('pageOwned')).toBeDefined();
  });

  it('synchronizes later record values without recreating a stable manager or losing action state', async () => {
    const manager = createManager();
    const { result, rerender, unmount } = renderHook(({ status }: { status: string }) => useSchemaRuntime({
      schema: minimalSchema, dataSourceManager: manager, navigate: vi.fn(), locale: 'en', t: key => key,
      disableAutoFetch: true, skipDataSourceRegistration: true,
      initialContext: { record: { inv_fgpt_status: status } },
    }), { initialProps: { status: 'pending_pack' } });
    await waitFor(() => expect(result.current).not.toBeNull());
    const active = result.current!;
    active.getStateManager().updateState(active.getScopeId(), 'selectedPackage', 'fixture-package');
    rerender({ status: 'received' });
    await waitFor(() => expect(result.current!.getContext()).toMatchObject({ record: { inv_fgpt_status: 'received' } }));
    expect(result.current).toBe(active);
    expect(active.getContext().state).toMatchObject({ selectedPackage: 'fixture-package' });
    unmount();
  });
});
