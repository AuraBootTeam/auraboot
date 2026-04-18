import { renderHook, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { useModelCapabilities } from '~/shared/hooks/useModelCapabilities';

// Mock the http-client module so we don't rely on globalThis.fetch or auth logic
vi.mock('~/shared/services/http-client', () => ({
  get: vi.fn(),
}));

import { get } from '~/shared/services/http-client';
const mockGet = vi.mocked(get);

describe('useModelCapabilities', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('loads capabilities for a given model code', async () => {
    mockGet.mockResolvedValue({
      code: '0',
      desc: '',
      data: {
        list: true,
        create: false,
        sortableFields: ['name'],
        filterableFields: [],
        detail: true,
        update: false,
        delete: false,
        bulkDelete: false,
        export: true,
        sort: true,
        filter: true,
        paginate: true,
      },
    });

    const { result } = renderHook(() => useModelCapabilities('test_model'));

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.data?.list).toBe(true);
    expect(result.current.data?.create).toBe(false);
    expect(result.current.data?.sortableFields).toEqual(['name']);
    expect(result.current.error).toBeUndefined();
    expect(mockGet).toHaveBeenCalledWith('/api/meta/models/test_model/capabilities');
  });

  it('returns error on non-OK response', async () => {
    mockGet.mockResolvedValue({
      code: '404',
      desc: 'Not Found',
      data: null,
    });

    const { result } = renderHook(() => useModelCapabilities('nonexistent'));

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.error).toBeDefined();
    expect(result.current.data).toBeUndefined();
  });

  it('returns nothing for undefined code', () => {
    const { result } = renderHook(() => useModelCapabilities(undefined));
    expect(result.current.data).toBeUndefined();
    expect(result.current.loading).toBe(false);
    expect(mockGet).not.toHaveBeenCalled();
  });
});
