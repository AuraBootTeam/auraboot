import { describe, expect, it, vi } from 'vitest';
import { waitFor } from '@testing-library/react';
import { fetchResult } from '~/shared/services/http-client';
import { DataSourceManager } from '~/framework/meta/runtime/data-pipeline/DataSourceManager';
import { createExpressionContext } from '~/framework/meta/runtime/expression/context';
import { ScopedStateManager } from '~/framework/meta/runtime/state/scoped-state';

vi.mock('~/shared/services/http-client', () => ({
  fetchResult: vi.fn(),
}));

const mockedFetchResult = vi.mocked(fetchResult);

describe('DataSourceManager', () => {
  it('evaluates object params against runtime record and state context', async () => {
    mockedFetchResult.mockResolvedValueOnce({
      code: '0',
      data: { records: [], total: 0, current: 1, pageSize: 100 },
    } as any);

    const manager = new DataSourceManager(
      createExpressionContext({
        record: { pid: 'task-1' },
        state: { selectedLine: { pid: 'line-1' } },
      } as any),
    );
    manager.register('canonicalLines', {
      type: 'api',
      endpoint: '/api/dynamic/bom_canonical_line/list',
      method: 'get',
      adaptor: 'table',
      autoFetch: false,
      params: {
        pageNum: 1,
        pageSize: 100,
        bom_cl_task_id: '${record.pid}',
        selectedLineId: '${state.selectedLine.pid}',
      },
    });

    await manager.fetch('canonicalLines');

    expect(mockedFetchResult).toHaveBeenCalledWith(
      '/api/dynamic/bom_canonical_line/list',
      expect.objectContaining({
        method: 'get',
        params: expect.objectContaining({
          pageNum: 1,
          pageSize: 100,
          selectedLineId: 'line-1',
        }),
      }),
    );
    const params = mockedFetchResult.mock.calls[0][1]?.params as Record<string, any>;
    expect(JSON.parse(params.filters)).toEqual([
      { fieldName: 'bom_cl_task_id', operator: 'EQ', value: 'task-1' },
    ]);
  });

  it('keeps updated page context available after binding a schema state manager', async () => {
    mockedFetchResult.mockResolvedValueOnce({
      code: '0',
      data: { records: [], total: 0, current: 1, pageSize: 100 },
    } as any);

    const manager = new DataSourceManager(
      createExpressionContext({
        form: {},
        record: {},
      } as any),
    );
    const stateManager = new ScopedStateManager({
      locale: 'zh-CN',
      theme: 'light',
      user: undefined,
      tenant: undefined,
      t: (key: string) => key,
    } as any);
    stateManager.createScope('scope-1', { state: {} });
    manager.bindStateManager(stateManager, 'scope-1');
    manager.updateContext(
      createExpressionContext({
        form: { pid: 'task-2' },
        record: { pid: 'task-2' },
      } as any),
    );
    manager.register('taskSummary', {
      type: 'api',
      endpoint: '/api/dynamic/bom_convert_task/list',
      method: 'get',
      adaptor: 'table',
      autoFetch: false,
      params: {
        pidFromForm: '${form.pid}',
        pidFromRecord: '${record.pid}',
      },
    });

    await manager.fetch('taskSummary');

    expect(mockedFetchResult).toHaveBeenCalledWith('/api/dynamic/bom_convert_task/list', {
      method: 'get',
      params: {
        pidFromForm: 'task-2',
        pidFromRecord: 'task-2',
      },
    });
  });

  it('rebuilds dependOn subscriptions when a state manager is bound after registration', async () => {
    mockedFetchResult
      .mockResolvedValueOnce({
        code: '0',
        data: { records: [], total: 0, current: 1, pageSize: 50 },
      } as any)
      .mockResolvedValueOnce({
        code: '0',
        data: { records: [{ pid: 'me-1' }], total: 1, current: 1, pageSize: 50 },
      } as any);

    const manager = new DataSourceManager(
      createExpressionContext({
        state: {},
      } as any),
    );
    manager.register('matchEvidence', {
      type: 'api',
      endpoint: '/api/dynamic/bom_match_evidence/list',
      method: 'get',
      adaptor: 'table',
      params: {
        bom_me_canonical_line_id: '${state.selectedBomLine.pid}',
      },
      dependOn: ['state.selectedBomLine.pid'],
    });

    const stateManager = new ScopedStateManager({
      locale: 'zh-CN',
      theme: 'light',
      user: undefined,
      tenant: undefined,
      t: (key: string) => key,
    } as any);
    stateManager.createScope('scope-1', { state: {} });
    manager.bindStateManager(stateManager, 'scope-1');

    stateManager.updateState('scope-1', 'selectedBomLine', { pid: 'std-1' });

    await waitFor(() => {
      expect(mockedFetchResult).toHaveBeenCalledWith(
        '/api/dynamic/bom_match_evidence/list',
        expect.objectContaining({ method: 'get' }),
      );
    });
    const params = mockedFetchResult.mock.calls.at(-1)?.[1]?.params as Record<string, any>;
    expect(JSON.parse(params.filters)).toEqual([
      { fieldName: 'bom_me_canonical_line_id', operator: 'EQ', value: 'std-1' },
    ]);
  });

  it('reloads nested state dependents when a root state key is notified', async () => {
    mockedFetchResult.mockResolvedValue({
      code: '0',
      data: { records: [], total: 0, current: 1, pageSize: 50 },
    } as any);

    const manager = new DataSourceManager(
      createExpressionContext({
        state: {},
      } as any),
    );
    manager.register('matchEvidence', {
      type: 'api',
      endpoint: '/api/dynamic/bom_match_evidence/list',
      method: 'get',
      adaptor: 'table',
      params: {
        bom_me_canonical_line_id: '${state.selectedBomLine.pid}',
      },
      autoFetch: false,
      dependOn: ['state.selectedBomLine.pid'],
    });

    manager.updateContext(
      createExpressionContext({
        state: {
          selectedBomLine: { pid: 'std-1' },
        },
      } as any),
    );

    await manager.notifyStateChanged('selectedBomLine');

    expect(mockedFetchResult).toHaveBeenCalledWith(
      '/api/dynamic/bom_match_evidence/list',
      expect.objectContaining({ method: 'get' }),
    );
    const params = mockedFetchResult.mock.calls.at(-1)?.[1]?.params as Record<string, any>;
    expect(JSON.parse(params.filters)).toEqual([
      { fieldName: 'bom_me_canonical_line_id', operator: 'EQ', value: 'std-1' },
    ]);
  });

  it('converts dynamic list field params into filters and skips blank optional values', async () => {
    mockedFetchResult.mockResolvedValueOnce({
      code: '0',
      data: { records: [], total: 0, current: 1, pageSize: 500 },
    } as any);

    const manager = new DataSourceManager(
      createExpressionContext({
        form: { pid: 'task-1' },
        state: { reasonFilter: '' },
      } as any),
    );
    manager.register('standardLines', {
      type: 'api',
      endpoint: '/api/dynamic/bom_standard_item/list',
      method: 'get',
      adaptor: 'table',
      autoFetch: false,
      params: {
        pageNum: 1,
        pageSize: 500,
        bom_std_task_id: '${form.pid}',
        bom_std_reason_code: '${state.reasonFilter}',
        sortField: 'bom_std_row_no',
        sortOrder: 'asc',
      },
    });

    await manager.fetch('standardLines');

    const params = mockedFetchResult.mock.calls.at(-1)?.[1]?.params as Record<string, any>;
    expect(params).toMatchObject({
      pageNum: 1,
      pageSize: 500,
      sortField: 'bom_std_row_no',
      sortOrder: 'asc',
    });
    expect(JSON.parse(params.filters)).toEqual([
      { fieldName: 'bom_std_task_id', operator: 'EQ', value: 'task-1' },
    ]);
    expect(params).not.toHaveProperty('bom_std_task_id');
    expect(params).not.toHaveProperty('bom_std_reason_code');
  });
});
