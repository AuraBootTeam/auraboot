import { renderHook, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { useModelCapabilities } from '~/shared/hooks/useModelCapabilities';

describe('useModelCapabilities', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('loads capabilities for a given model code', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({
        code: '0',
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
      }),
    } as unknown as Response);

    const { result } = renderHook(() => useModelCapabilities('test_model'));

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.data?.list).toBe(true);
    expect(result.current.data?.create).toBe(false);
    expect(result.current.data?.sortableFields).toEqual(['name']);
    expect(result.current.error).toBeUndefined();
  });

  it('returns error on non-OK response', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 404,
      json: async () => ({}),
    } as unknown as Response);

    const { result } = renderHook(() => useModelCapabilities('nonexistent'));

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.error).toBeDefined();
    expect(result.current.data).toBeUndefined();
  });

  it('returns nothing for undefined code', () => {
    globalThis.fetch = vi.fn();
    const { result } = renderHook(() => useModelCapabilities(undefined));
    expect(result.current.data).toBeUndefined();
    expect(result.current.loading).toBe(false);
    expect(fetch).not.toHaveBeenCalled();
  });
});
