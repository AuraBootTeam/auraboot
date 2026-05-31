import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderHook, act, waitFor } from '@testing-library/react';
import type { UnifiedSchema } from '~/framework/meta/schemas/types';

const fetchResultMock = vi.fn();

vi.mock('~/shared/services/http-client', () => ({
  fetchResult: (...args: unknown[]) => fetchResultMock(...args),
}));

import { useListData } from '../useListData';

const schema = { kind: 'list', blocks: [] } as unknown as UnifiedSchema;

function lastCallParams(): Record<string, any> {
  const call = fetchResultMock.mock.calls.at(-1);
  return (call?.[1] as { params?: Record<string, any> })?.params ?? {};
}

function parseFilters(params: Record<string, any>): Array<{ fieldName: string; operator: string; value: string }> {
  return params.filters ? JSON.parse(params.filters) : [];
}

afterEach(() => {
  fetchResultMock.mockReset();
});

describe('useListData fixedFilters', () => {
  it('AND-merges fixedFilters into the standard dynamic-table request', async () => {
    fetchResultMock.mockResolvedValue({ code: 0, data: { records: [], total: 0, page: 1 } });

    const { result } = renderHook(() =>
      useListData({ schema, tableName: 'bom_standard_item', fixedFilters: { bom_std_task_id: 'T-1' } }),
    );

    await act(async () => {
      await result.current.loadData({ filters: { bom_std_category: 'cap' } });
    });

    await waitFor(() => expect(fetchResultMock).toHaveBeenCalled());
    const conditions = parseFilters(lastCallParams());
    expect(conditions).toContainEqual({ fieldName: 'bom_std_task_id', operator: 'EQ', value: 'T-1' });
    expect(conditions).toContainEqual({ fieldName: 'bom_std_category', operator: 'EQ', value: 'cap' });
  });

  it('fixedFilters win over a user filter of the same field (scope cannot be widened)', async () => {
    fetchResultMock.mockResolvedValue({ code: 0, data: { records: [], total: 0, page: 1 } });

    const { result } = renderHook(() =>
      useListData({ schema, tableName: 'bom_standard_item', fixedFilters: { bom_std_task_id: 'T-1' } }),
    );

    await act(async () => {
      await result.current.loadData({ filters: { bom_std_task_id: 'OTHER' } });
    });

    await waitFor(() => expect(fetchResultMock).toHaveBeenCalled());
    const conditions = parseFilters(lastCallParams());
    const taskConditions = conditions.filter((c) => c.fieldName === 'bom_std_task_id');
    expect(taskConditions).toEqual([{ fieldName: 'bom_std_task_id', operator: 'EQ', value: 'T-1' }]);
  });

  it('maps chip filters (LIKE wildcard) and passes keyword + sort params', async () => {
    fetchResultMock.mockResolvedValue({ code: 0, data: { records: [], total: 0, page: 1 } });

    const { result } = renderHook(() => useListData({ schema, tableName: 'bom_standard_item' }));

    await act(async () => {
      await result.current.loadData({
        chipFilters: [{ fieldCode: 'bom_std_material_code', operator: 'like', value: 'R10' }],
        keyword: '  cap  ',
        sorts: [{ fieldCode: 'bom_std_row_no', direction: 'desc' }],
      });
    });

    await waitFor(() => expect(fetchResultMock).toHaveBeenCalled());
    const params = lastCallParams();
    expect(parseFilters(params)).toContainEqual({
      fieldName: 'bom_std_material_code',
      operator: 'LIKE',
      value: '%R10%',
    });
    expect(params.keyword).toBe('cap');
    expect(params.sortField).toBe('bom_std_row_no');
    expect(params.sortOrder).toBe('desc');
  });

  it('omits filters param entirely when no fixed or user filters are present', async () => {
    fetchResultMock.mockResolvedValue({ code: 0, data: { records: [], total: 0, page: 1 } });

    const { result } = renderHook(() => useListData({ schema, tableName: 'bom_standard_item' }));

    await act(async () => {
      await result.current.loadData();
    });

    await waitFor(() => expect(fetchResultMock).toHaveBeenCalled());
    expect(lastCallParams().filters).toBeUndefined();
  });
});
