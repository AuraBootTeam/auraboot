import { renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const { fetchChartDataMock, fetchResultMock } = vi.hoisted(() => ({
  fetchChartDataMock: vi.fn(),
  fetchResultMock: vi.fn(),
}));

vi.mock('~/shared/services/chartDataService', () => ({
  chartDataService: {
    fetchChartData: fetchChartDataMock,
  },
}));

vi.mock('~/shared/services/http-client', () => ({
  fetchResult: fetchResultMock,
}));

import { useChartData } from '../useChartData';

describe('useChartData', () => {
  beforeEach(() => {
    fetchChartDataMock.mockReset();
    fetchResultMock.mockReset();
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
