import { describe, it, expect, vi } from 'vitest';
import { waitFor } from '@testing-library/react';
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
