import { renderHook, act, waitFor } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { useDataTable, type DataTableParams } from '../useDataTable';

type TableRow = { id: number };
type FetchData = (params: DataTableParams) => Promise<{ data: TableRow[]; total: number }>;

describe('useDataTable', () => {
  let fetchData: ReturnType<typeof vi.fn<FetchData>>;

  beforeEach(() => {
    fetchData = vi.fn<FetchData>().mockResolvedValue({ data: [{ id: 1 }, { id: 2 }], total: 2 });
  });

  it('starts with loading true when autoFetch=true and fetches data on mount', async () => {
    const { result } = renderHook(() => useDataTable({ fetchData }));

    await waitFor(() => {
      expect(result.current.loading).toBe(false);
    });

    expect(fetchData).toHaveBeenCalledOnce();
    expect(result.current.data).toEqual([{ id: 1 }, { id: 2 }]);
    expect(result.current.total).toBe(2);
    expect(result.current.error).toBeNull();
  });

  it('does not auto-fetch when autoFetch=false', async () => {
    const { result } = renderHook(() => useDataTable({ fetchData, autoFetch: false }));

    await Promise.resolve();

    expect(fetchData).not.toHaveBeenCalled();
    expect(result.current.data).toEqual([]);
    expect(result.current.loading).toBe(false);
  });

  it('uses defaultPageSize option', () => {
    const { result } = renderHook(() =>
      useDataTable({ fetchData, autoFetch: false, defaultPageSize: 50 }),
    );
    expect(result.current.pageSize).toBe(50);
  });

  it('applies default sort field and order', () => {
    const { result } = renderHook(() =>
      useDataTable({
        fetchData,
        autoFetch: false,
        defaultSortField: 'createdAt',
        defaultSortOrder: 'desc',
      }),
    );
    expect(result.current.sortField).toBe('createdAt');
    expect(result.current.sortOrder).toBe('desc');
  });

  it('setPage changes page and triggers fetch', async () => {
    const { result } = renderHook(() => useDataTable({ fetchData }));
    await waitFor(() => expect(result.current.loading).toBe(false));

    fetchData.mockResolvedValue({ data: [{ id: 3 }], total: 1 });

    act(() => {
      result.current.setPage(2);
    });

    await waitFor(() => {
      expect(result.current.loading).toBe(false);
    });

    expect(result.current.page).toBe(2);
    expect(fetchData).toHaveBeenCalledTimes(2);
    expect(fetchData.mock.calls[1][0]).toMatchObject({ page: 2 });
  });

  it('setPageSize changes pageSize and triggers fetch', async () => {
    const { result } = renderHook(() => useDataTable({ fetchData }));
    await waitFor(() => expect(result.current.loading).toBe(false));

    fetchData.mockResolvedValue({ data: [], total: 0 });
    act(() => {
      result.current.setPageSize(10);
    });

    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(result.current.pageSize).toBe(10);
    expect(fetchData.mock.calls[1][0]).toMatchObject({ pageSize: 10 });
  });

  it('setSort updates sortField and sortOrder', async () => {
    const { result } = renderHook(() => useDataTable({ fetchData }));
    await waitFor(() => expect(result.current.loading).toBe(false));

    fetchData.mockResolvedValue({ data: [], total: 0 });
    act(() => {
      result.current.setSort('name', 'asc');
    });

    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(result.current.sortField).toBe('name');
    expect(result.current.sortOrder).toBe('asc');
  });

  it('refresh triggers a new fetch', async () => {
    const { result } = renderHook(() => useDataTable({ fetchData }));
    await waitFor(() => expect(result.current.loading).toBe(false));

    fetchData.mockResolvedValue({ data: [{ id: 99 }], total: 1 });
    act(() => {
      result.current.refresh();
    });

    await waitFor(() => expect(result.current.data).toEqual([{ id: 99 }]));

    expect(fetchData).toHaveBeenCalledTimes(2);
  });

  it('reset restores initial state values', async () => {
    const { result } = renderHook(() =>
      useDataTable({
        fetchData,
        autoFetch: false,
        defaultPageSize: 25,
        defaultSortField: 'name',
        defaultSortOrder: 'asc',
      }),
    );

    act(() => {
      result.current.setPage(3);
      result.current.setSearch('hello');
      result.current.setSort('other', 'desc');
    });

    act(() => {
      result.current.reset();
    });

    expect(result.current.page).toBe(1);
    expect(result.current.pageSize).toBe(25);
    expect(result.current.search).toBe('');
    expect(result.current.sortField).toBe('name');
    expect(result.current.sortOrder).toBe('asc');
  });

  it('sets error state when fetchData throws', async () => {
    fetchData.mockRejectedValue(new Error('network down'));

    const { result } = renderHook(() => useDataTable({ fetchData }));

    await waitFor(() => {
      expect(result.current.loading).toBe(false);
    });

    expect(result.current.error).toBe('network down');
  });

  it('updates search state immediately when setSearch called', () => {
    const { result } = renderHook(() => useDataTable({ fetchData, autoFetch: false }));

    act(() => {
      result.current.setSearch('hello');
    });

    expect(result.current.search).toBe('hello');
  });
});
