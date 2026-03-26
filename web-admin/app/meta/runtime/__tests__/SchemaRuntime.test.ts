import { describe, it, expect, vi } from 'vitest';
import { SchemaRuntime } from '~/meta/runtime/schema-runtime';
import { DataSourceManager } from '~/meta/runtime/data-pipeline/DataSourceManager';
import { createExpressionContext, type GlobalState } from '~/meta/runtime/expression/context';
import type { UnifiedSchema } from '~/meta/schemas/types';
import { actionRegistry } from '~/meta/runtime/actions/ActionRegistry';
import formDsl from '~/designer/test/final.v1.0/form.json';

const createGlobalState = (): GlobalState => ({
  locale: 'zh-CN',
  theme: 'light',
  t: (key: string) => key,
});

const createManager = () => new DataSourceManager(createExpressionContext());

const minimalSchema: UnifiedSchema = {
  kind: 'Form',
  version: '1.0.0',
  id: 'schema.runtime.test',
  title: 'Test',
  layout: {
    areas: ['main'],
    areasConfig: {
      main: {
        type: 'grid',
        cols: 1,
        rowGap: 8,
        colGap: 8,
      },
    },
  },
  areas: {
    main: {
      blocks: [],
    },
  },
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

  it('initializes successfully with the final.v1.0 DSL sample', () => {
    const manager = createManager();
    const runtime = new SchemaRuntime({
      schema: formDsl as UnifiedSchema,
      globalState: createGlobalState(),
      dataSourceManager: manager,
      disableAutoFetch: true,
    });

    expect(runtime.getSchema().id).toBe('form.store');
  });
});
