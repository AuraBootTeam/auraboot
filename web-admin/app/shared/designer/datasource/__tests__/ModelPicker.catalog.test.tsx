import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { useMetaModels } from '../useMetaModels';

const model = (code: string) => ({ pid: `pid-${code}`, code, displayName: `Label ${code}` });
const response = (data: unknown) => ({ ok: true, status: 200, json: async () => ({ code: '0', data }) });
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

describe('model picker complete catalog', () => {
  it('includes a model on a later real catalog page', async () => {
    const fetcher = vi.fn(async (input: string) => {
      const page = new URL(input, 'http://fixture.test').searchParams.get('page') ?? '1';
      return response({ records: [model(page === '1' ? 'early' : 'e2et_order')],
        total: 2, current: Number(page), size: 1, pages: 2 });
    });
    vi.stubGlobal('fetch', fetcher);
    const { result } = renderHook(() => useMetaModels());
    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.models.map((row: { code: string }) => row.code)).toEqual(['early', 'e2et_order']);
    expect(fetcher).toHaveBeenCalledTimes(2);
  });

  it('does not present the first page as complete when a later page is denied', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const fetcher = vi.fn().mockResolvedValueOnce(response({ records: [model('early')],
      total: 2, current: 1, size: 1, pages: 2 }))
      .mockResolvedValueOnce({ ok: false, status: 403, json: async () => ({ code: '403' }) });
    vi.stubGlobal('fetch', fetcher);
    const { result } = renderHook(() => useMetaModels());
    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(fetcher).toHaveBeenCalledTimes(2);
    expect(result.current.models).toEqual([]);
  });

  it('retains the existing non-paginated array response contract', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response([model('legacy')])));
    const { result } = renderHook(() => useMetaModels());
    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.models).toEqual([{ pid: 'pid-legacy', code: 'legacy', name: 'Label legacy' }]);
  });

  it('accepts a genuinely empty catalog without extra requests', async () => {
    const fetcher = vi.fn().mockResolvedValue(response({ records: [], total: 0 }));
    vi.stubGlobal('fetch', fetcher);
    const { result } = renderHook(() => useMetaModels());
    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.models).toEqual([]);
    expect(fetcher).toHaveBeenCalledOnce();
  });
});
