import { renderHook, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

vi.mock('~/shared/services/chartDataService', () => ({
  chartDataService: {
    fetchChartData: vi.fn(),
  },
}));

import { useChartData } from '../useChartData';

describe('useChartData', () => {
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
});
