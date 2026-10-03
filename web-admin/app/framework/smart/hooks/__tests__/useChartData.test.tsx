import type { ReactNode } from 'react';
import { DashboardQueryContext } from '../DashboardQueryContext';
import { act, renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const { fetchChartDataMock, fetchDashboardWidgetMock, fetchResultMock } = vi.hoisted(() => ({
  fetchChartDataMock: vi.fn(),
  fetchDashboardWidgetMock: vi.fn(),
  fetchResultMock: vi.fn(),
}));

vi.mock('~/shared/services/chartDataService', () => ({
  chartDataService: {
    fetchChartData: fetchChartDataMock,
    fetchDashboardWidget: fetchDashboardWidgetMock,
  },
}));

vi.mock('~/shared/services/http-client', () => ({
  fetchResult: fetchResultMock,
}));

import { useChartData } from '../useChartData';
import type { ChartDataSource } from '../../types/chart';

describe('useChartData', () => {
  beforeEach(() => {
    fetchChartDataMock.mockReset();
    fetchDashboardWidgetMock.mockReset();
    fetchResultMock.mockReset();
  });

  it('loads the bound saved widget without sending a replacement query', async () => {
    fetchDashboardWidgetMock.mockResolvedValue({
      rows: [{ count: 2 }],
      meta: { dimensions: [], metrics: ['count'] },
    });
    const binding = { dashboardPid: 'saved', widgetId: 'widget', usageId: 'visit' };
    const wrapper = ({ children }: { children: ReactNode }) => (
      <DashboardQueryContext.Provider value={binding}>{children}</DashboardQueryContext.Provider>
    );
    const { result, rerender } = renderHook(
      () =>
        useChartData({
          dataSource: {
            type: 'aggregate',
            modelCode: 'orders',
            metrics: [{ field: 'pid', aggregation: 'count', alias: 'count' }],
          },
          linkageFilters: [{ field: 'region', operator: 'eq', value: 'East' }],
        }),
      { wrapper },
    );
    await waitFor(() => expect(result.current.data?.rows).toEqual([{ count: 2 }]));
    expect(fetchDashboardWidgetMock).toHaveBeenCalledWith('saved', 'widget', {
      usageId: 'visit',
      linkageFilters: [{ field: 'region', operator: 'eq', value: 'East' }],
      drillFilters: undefined,
    });
    rerender();
    expect(fetchDashboardWidgetMock).toHaveBeenCalledTimes(1);
    expect(fetchChartDataMock).not.toHaveBeenCalled();
  });

  it('exposes a saved-query failure without falling back to a client query', async () => {
    fetchDashboardWidgetMock.mockRejectedValue(new Error('Access denied'));
    const wrapper = ({ children }: { children: ReactNode }) => (
      <DashboardQueryContext.Provider
        value={{ dashboardPid: 'saved', widgetId: 'widget', usageId: 'visit' }}
      >
        {children}
      </DashboardQueryContext.Provider>
    );
    const { result } = renderHook(
      () =>
        useChartData({
          dataSource: {
            type: 'aggregate',
            modelCode: 'orders',
            metrics: [{ field: 'pid', aggregation: 'count', alias: 'count' }],
          },
        }),
      { wrapper },
    );
    await waitFor(() => expect(result.current.error?.message).toBe('Access denied'));
    expect(result.current.data).toBeNull();
    expect(fetchChartDataMock).not.toHaveBeenCalled();
  });

  it('does not update state for static data when disabled', async () => {
    const { result } = renderHook(() =>
      useChartData({
        enabled: false,
        dataSource: {
          type: 'static',
          staticData: [{ name: 'Alpha' }],
          dimensions: ['name'],
        },
      }),
    );

    await Promise.resolve();

    expect(result.current.loading).toBe(false);
    expect(result.current.error).toBeNull();
    expect(result.current.data).toBeNull();
  });

  it('normalizes static data when enabled', async () => {
    const { result } = renderHook(() =>
      useChartData({
        dataSource: {
          type: 'static',
          staticData: [{ name: 'Alpha' }],
          dimensions: ['name'],
        },
      }),
    );

    await waitFor(() => {
      expect(result.current.data?.rows).toEqual([{ name: 'Alpha' }]);
    });
    expect(result.current.data?.meta.dimensions).toEqual(['name']);
  });

  it('normalizes api records into chart data rows', async () => {
    fetchResultMock.mockResolvedValue({
      code: '0',
      data: { records: [{ oeePct: 49.6 }] },
    });

    const { result } = renderHook(() =>
      useChartData({
        dataSource: {
          type: 'api',
          url: '/api/manufacturing/oee/fleet/summary',
          params: { end: '2026-06-05T00:00:00', start: '2026-06-01T00:00:00' },
        },
      }),
    );

    await waitFor(() => {
      expect(result.current.data?.rows).toEqual([{ oeePct: 49.6 }]);
    });
    expect(result.current.data?.meta.metrics).toEqual(['oeePct']);
    expect(fetchResultMock).toHaveBeenCalledWith('/api/manufacturing/oee/fleet/summary', {
      method: 'get',
      params: { end: '2026-06-05T00:00:00', start: '2026-06-01T00:00:00' },
    });
  });

  it('deduplicates concurrent api requests with equivalent params', async () => {
    let resolveFetch: (value: unknown) => void = () => {};
    fetchResultMock.mockReturnValue(
      new Promise((resolve) => {
        resolveFetch = resolve;
      }),
    );

    const first = renderHook(() =>
      useChartData({
        dataSource: {
          type: 'api',
          url: '/api/manufacturing/oee/fleet/summary',
          params: { start: '2026-06-01T00:00:00', end: '2026-06-06T00:00:00' },
        },
      }),
    );
    const second = renderHook(() =>
      useChartData({
        dataSource: {
          type: 'api',
          url: '/api/manufacturing/oee/fleet/summary',
          params: { end: '2026-06-06T00:00:00', start: '2026-06-01T00:00:00' },
        },
      }),
    );

    await waitFor(() => expect(fetchResultMock).toHaveBeenCalledTimes(1));

    resolveFetch({
      code: '0',
      data: { records: [{ equipmentWithDataCount: 2 }] },
    });

    await waitFor(() => {
      expect(first.result.current.data?.rows).toEqual([{ equipmentWithDataCount: 2 }]);
      expect(second.result.current.data?.rows).toEqual([{ equipmentWithDataCount: 2 }]);
    });
  });

  it('reuses a just-resolved api response for equivalent remounts', async () => {
    fetchResultMock.mockResolvedValue({
      code: '0',
      data: { records: [{ oeePct: 49.6 }] },
    });

    const source = {
      type: 'api' as const,
      url: '/api/manufacturing/oee/fleet/summary',
      params: { start: '2026-06-01T00:00:00', end: '2026-06-07T00:00:00' },
    };

    const first = renderHook(() => useChartData({ dataSource: source }));
    await waitFor(() => {
      expect(first.result.current.data?.rows).toEqual([{ oeePct: 49.6 }]);
    });

    const second = renderHook(() =>
      useChartData({
        dataSource: {
          ...source,
          params: { end: '2026-06-07T00:00:00', start: '2026-06-01T00:00:00' },
        },
      }),
    );

    await waitFor(() => {
      expect(second.result.current.data?.rows).toEqual([{ oeePct: 49.6 }]);
    });
    expect(fetchResultMock).toHaveBeenCalledTimes(1);
  });
});

describe('aggregate model routing', () => {
  beforeEach(() => {
    fetchChartDataMock.mockReset();
    fetchDashboardWidgetMock.mockReset();
    fetchResultMock.mockReset();
  });
  for (const legacyModel of [undefined, 'stale_raw_model']) {
    it(`queries a semantic model without forwarding raw identity: ${legacyModel}`, async () => {
      fetchChartDataMock.mockResolvedValue({ rows: [{ order_count: 12 }], meta: { dimensions: [], metrics: ['order_count'] } });
      const { result } = renderHook(() => useChartData({ dataSource: {
        type: 'aggregate', semanticModelCode: 'governed_orders', modelCode: legacyModel,
        metrics: [{ field: 'order_count', aggregation: 'none' }], dimensions: [],
      } }));
      await waitFor(() => expect(result.current.data?.rows).toEqual([{ order_count: 12 }]));
      const payload = fetchChartDataMock.mock.calls[0][0];
      expect(payload.semanticModelCode).toBe('governed_orders');
      expect(payload).not.toHaveProperty('modelCode');
      expect(payload.metrics).toEqual([{ field: 'order_count', aggregation: 'none' }]);
      expect(fetchDashboardWidgetMock).not.toHaveBeenCalled();
    });
  }
  for (const code of ['', '   ']) {
    it(`does not query or fall back from incomplete semantic identity: ${JSON.stringify(code)}`, () => {
      const { result } = renderHook(() => useChartData({ dataSource: {
        type: 'aggregate', semanticModelCode: code, modelCode: 'stale_raw_model',
        metrics: [{ field: 'order_count', aggregation: 'none' }],
      } }));
      expect(fetchChartDataMock).not.toHaveBeenCalled();
      expect(fetchDashboardWidgetMock).not.toHaveBeenCalled();
      expect(result.current.data).toBeNull();
      expect(result.current.loading).toBe(false);
    });
  }
  it('continues querying raw aggregates without semantic identity', async () => {
    fetchChartDataMock.mockResolvedValue({ rows: [{ count: 12 }], meta: { dimensions: [], metrics: ['count'] } });
    const { result } = renderHook(() => useChartData({ dataSource: {
      type: 'aggregate', modelCode: 'orders', metrics: [{ field: 'pid', aggregation: 'count', alias: 'count' }],
    } }));
    await waitFor(() => expect(result.current.data?.rows).toEqual([{ count: 12 }]));
    const payload = fetchChartDataMock.mock.calls[0][0];
    expect(payload.modelCode).toBe('orders');
    expect(payload).not.toHaveProperty('semanticModelCode');
  });
  it('keeps a selected semantic model with no metrics incomplete', () => {
    const { result } = renderHook(() => useChartData({ dataSource: {
      type: 'aggregate', semanticModelCode: 'governed_orders', metrics: [],
    } }));
    expect(fetchChartDataMock).not.toHaveBeenCalled();
    expect(result.current.data).toBeNull();
  });
});


describe('chart request ownership', () => {
  beforeEach(() => {
    fetchChartDataMock.mockReset();
    fetchDashboardWidgetMock.mockReset();
    fetchResultMock.mockReset();
  });

  function pending() {
    let resolve!: (value: { rows: { count: number }[]; meta: { dimensions: string[]; metrics: string[] } }) => void;
    let reject!: (error: Error) => void;
    const promise = new Promise<Parameters<typeof resolve>[0]>((done, fail) => { resolve = done; reject = fail; });
    return { promise, resolve, reject };
  }
  const response = (count: number) => ({ rows: [{ count }], meta: { dimensions: [], metrics: ['count'] } });
  const source = (code: string): ChartDataSource => ({
    type: 'aggregate', semanticModelCode: code, metrics: [{ field: 'count', aggregation: 'none' }],
  });

  for (const outcome of ['success', 'error'] as const) {
    it(`ignores an obsolete ${outcome} after the new model resolves`, async () => {
      const old = pending();
      fetchChartDataMock.mockReturnValueOnce(old.promise).mockResolvedValueOnce(response(22));
      const { result, rerender } = renderHook(({ dataSource }: { dataSource: ChartDataSource }) => useChartData({ dataSource }), {
        initialProps: { dataSource: source('old') },
      });
      await waitFor(() => expect(fetchChartDataMock).toHaveBeenCalledTimes(1));
      rerender({ dataSource: source('new') });
      await waitFor(() => expect(result.current.data?.rows).toEqual([{ count: 22 }]));
      await act(async () => { if (outcome === 'success') old.resolve(response(11)); else old.reject(new Error('Old model denied')); });
      expect(result.current.data?.rows).toEqual([{ count: 22 }]);
      expect(result.current.error).toBeNull();
      expect(result.current.loading).toBe(false);
    });
  }

  for (const code of ['', '   ']) {
    it(`keeps a cleared model empty after its previous request resolves: ${String(code)}`, async () => {
      const old = pending();
      fetchChartDataMock.mockReturnValueOnce(old.promise);
      const { result, rerender } = renderHook(({ dataSource }: { dataSource: ChartDataSource }) => useChartData({ dataSource }), {
        initialProps: { dataSource: source('old') },
      });
      await waitFor(() => expect(result.current.loading).toBe(true));
      rerender({ dataSource: source(code) });
      expect(result.current.data).toBeNull();
      expect(result.current.loading).toBe(false);
      await act(async () => { old.resolve(response(11)); });
      expect(result.current.data).toBeNull();
      expect(result.current.error).toBeNull();
      expect(fetchChartDataMock).toHaveBeenCalledTimes(1);
    });
  }

  it('does not end the new request loading state when the old request settles', async () => {
    const old = pending(); const current = pending();
    fetchChartDataMock.mockReturnValueOnce(old.promise).mockReturnValueOnce(current.promise);
    const { result, rerender } = renderHook(({ dataSource }: { dataSource: ChartDataSource }) => useChartData({ dataSource }), {
      initialProps: { dataSource: source('old') },
    });
    rerender({ dataSource: source('new') });
    await waitFor(() => expect(fetchChartDataMock).toHaveBeenCalledTimes(2));
    await act(async () => { old.resolve(response(11)); });
    expect(result.current.loading).toBe(true);
    expect(result.current.data).toBeNull();
    await act(async () => { current.resolve(response(22)); });
    expect(result.current.loading).toBe(false);
    expect(result.current.data?.rows).toEqual([{ count: 22 }]);
  });

  it('invalidates an outstanding request when disabled', async () => {
    const old = pending();
    fetchChartDataMock.mockReturnValueOnce(old.promise);
    const { result, rerender } = renderHook(({ enabled }: { enabled: boolean }) => useChartData({ enabled, dataSource: source('old') }), {
      initialProps: { enabled: true },
    });
    await waitFor(() => expect(result.current.loading).toBe(true));
    rerender({ enabled: false });
    expect(result.current.loading).toBe(false);
    await act(async () => { old.resolve(response(11)); });
    expect(result.current.data).toBeNull();
    expect(result.current.error).toBeNull();
    expect(fetchChartDataMock).toHaveBeenCalledTimes(1);
  });
});
