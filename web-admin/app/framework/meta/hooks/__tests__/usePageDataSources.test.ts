import { renderHook, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { usePageDataSources } from '../usePageDataSources';
import { createExpressionContext } from '~/framework/meta/runtime/expression/context';

const { fetchResultMock } = vi.hoisted(() => ({
  fetchResultMock: vi.fn(),
}));

vi.mock('~/shared/services/http-client', () => ({
  fetchResult: fetchResultMock,
}));

function expectFilterParam(
  call: unknown[],
  fieldName: string,
  value: unknown,
): void {
  const params = (call[1] as any)?.params ?? {};
  const filters = JSON.parse(String(params.filters ?? '[]'));
  expect(filters).toContainEqual({
    fieldName,
    operator: 'EQ',
    value,
  });
}

describe('usePageDataSources', () => {
  it('rerenders when an auto-fetched schema data source returns data', async () => {
    fetchResultMock.mockResolvedValue({
      code: '0',
      data: {
        records: [{ pid: 'line-1', name: 'Line 1' }],
        total: 1,
        current: 1,
        pageSize: 10,
      },
    });

    const schema = {
      id: 'bom_workbench',
      modelCode: 'bom_conversion_task_pcba',
      dataSources: {
        lines: {
          type: 'api',
          method: 'get',
          endpoint: '/api/dynamic/bom_standard_line_pcba/list',
          adaptor: 'table',
          params: {
            bom_std_task_id: '${form.pid}',
          },
        },
      },
    } as any;

    const { result } = renderHook(() =>
      usePageDataSources({
        context: createExpressionContext({
          form: { pid: 'task-1' },
        }),
        schema,
      }),
    );

    await waitFor(() => {
      expect(result.current.getData('lines')?.records?.[0]?.pid).toBe('line-1');
    });

    expect(fetchResultMock).toHaveBeenCalledWith('/api/dynamic/bom_standard_line_pcba/list', {
      method: 'get',
      params: {
        filters: expect.any(String),
      },
    });
    expectFilterParam(fetchResultMock.mock.calls[0], 'bom_std_task_id', 'task-1');
  });

  it('reloads auto-fetched schema data sources when page context changes', async () => {
    fetchResultMock
      .mockResolvedValueOnce({
        code: '0',
        data: {
          records: [{ pid: 'line-1', name: 'Line 1' }],
          total: 1,
          current: 1,
          pageSize: 10,
        },
      })
      .mockResolvedValueOnce({
        code: '0',
        data: {
          records: [{ pid: 'line-2', name: 'Line 2' }],
          total: 1,
          current: 1,
          pageSize: 10,
        },
      });

    const schema = {
      id: 'bom_workbench',
      modelCode: 'bom_conversion_task_pcba',
      dataSources: {
        lines: {
          type: 'api',
          method: 'get',
          endpoint: '/api/dynamic/bom_standard_line_pcba/list',
          adaptor: 'table',
          params: {
            bom_std_task_id: '${form.pid}',
          },
        },
      },
    } as any;

    const { rerender, result } = renderHook(
      ({ pid }: { pid: string }) =>
        usePageDataSources({
          context: createExpressionContext({
            form: { pid },
          }),
          schema,
        }),
      {
        initialProps: { pid: 'task-1' },
      },
    );

    await waitFor(() => {
      expect(result.current.getData('lines')?.records?.[0]?.pid).toBe('line-1');
    });

    rerender({ pid: 'task-2' });

    await waitFor(() => {
      expect(result.current.getData('lines')?.records?.[0]?.pid).toBe('line-2');
    });

    expect(fetchResultMock).toHaveBeenLastCalledWith('/api/dynamic/bom_standard_line_pcba/list', {
      method: 'get',
      params: {
        filters: expect.any(String),
      },
    });
    expectFilterParam(
      fetchResultMock.mock.calls[fetchResultMock.mock.calls.length - 1],
      'bom_std_task_id',
      'task-2',
    );
  });
});
