import { renderHook, waitFor, act } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach } from 'vitest';

vi.mock('~/shared/services/chartDataService', () => ({
  chartDataService: {
    fetchChartData: vi.fn(),
  },
}));

import { useGroupedTableData } from '../useGroupedTableData';
import { chartDataService } from '~/shared/services/chartDataService';

const mockFetch = vi.mocked(chartDataService.fetchChartData);

const sampleRows = [
  { id: '1', status: 'active', amount: 100 },
  { id: '2', status: 'active', amount: 200 },
  { id: '3', status: 'closed', amount: 50 },
];

describe('useGroupedTableData', () => {
  beforeEach(() => {
    mockFetch.mockReset();
  });

  it('does not fetch when disabled', () => {
    const { result } = renderHook(() =>
      useGroupedTableData({ modelCode: 'order', enabled: false }),
    );
    expect(mockFetch).not.toHaveBeenCalled();
    expect(result.current.loading).toBe(false);
  });

  it('fetches data and populates rows', async () => {
    mockFetch.mockResolvedValue({ rows: sampleRows } as any);
    const { result } = renderHook(() =>
      useGroupedTableData({ modelCode: 'order', fields: ['id', 'status', 'amount'] }),
    );
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.rows).toHaveLength(3);
    expect(result.current.totalCount).toBe(3);
    expect(result.current.error).toBeNull();
  });

  it('sets error state on fetch failure', async () => {
    mockFetch.mockRejectedValue(new Error('Network error'));
    const { result } = renderHook(() =>
      useGroupedTableData({ modelCode: 'order' }),
    );
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.error).not.toBeNull();
    expect(result.current.error?.message).toBe('Network error');
    expect(result.current.rows).toEqual([]);
  });

  it('toggleGroup collapses and expands a group', async () => {
    mockFetch.mockResolvedValue({ rows: sampleRows } as any);
    const groupByConfig = [{ fieldCode: 'status', aggregations: [] }];
    const { result } = renderHook(() =>
      useGroupedTableData({ modelCode: 'order', groupBy: groupByConfig }),
    );
    await waitFor(() => expect(result.current.loading).toBe(false));

    act(() => result.current.toggleGroup('active'));
    const collapsed = result.current.groups.find((g: { groupKey: string; collapsed: boolean }) => g.groupKey === 'active');
    expect(collapsed!.collapsed).toBe(true);

    act(() => result.current.toggleGroup('active'));
    const expanded = result.current.groups.find((g: { groupKey: string; collapsed: boolean }) => g.groupKey === 'active');
    expect(expanded!.collapsed).toBe(false);
  });

  it('refetch re-runs the data fetch', async () => {
    mockFetch.mockResolvedValue({ rows: sampleRows } as any);
    const { result } = renderHook(() =>
      useGroupedTableData({ modelCode: 'order' }),
    );
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(mockFetch).toHaveBeenCalledTimes(1);

    await act(async () => {
      await result.current.refetch();
    });
    expect(mockFetch).toHaveBeenCalledTimes(2);
  });

  it('groups rows and calculates sum aggregation', async () => {
    mockFetch.mockResolvedValue({ rows: sampleRows } as any);
    const aggConfig = { fieldCode: 'amount', label: 'Total Amount' } as any;
    aggConfig['function'] = 'sum';
    const groupByConfig = [{ fieldCode: 'status', aggregations: [aggConfig] }];
    const { result } = renderHook(() =>
      useGroupedTableData({ modelCode: 'order', groupBy: groupByConfig }),
    );
    await waitFor(() => expect(result.current.loading).toBe(false));
    const groups = result.current.groups;
    expect(groups).toHaveLength(2);
    const activeGroup = groups.find((g: { groupKey: string; rows: unknown[]; aggregations: Record<string, number> }) => g.groupKey === 'active');
    expect(activeGroup).toBeDefined();
    expect(activeGroup!.rows).toHaveLength(2);
    expect(activeGroup!.aggregations['Total Amount']).toBe(300);
  });

  it('calculates global totals', async () => {
    mockFetch.mockResolvedValue({ rows: sampleRows } as any);
    const aggConfig = { fieldCode: 'amount', label: 'Total' } as any;
    aggConfig['function'] = 'sum';
    const groupByConfig = [{ fieldCode: 'status', aggregations: [aggConfig] }];
    const { result } = renderHook(() =>
      useGroupedTableData({ modelCode: 'order', groupBy: groupByConfig }),
    );
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.totals['Total']).toBe(350);
  });
});
