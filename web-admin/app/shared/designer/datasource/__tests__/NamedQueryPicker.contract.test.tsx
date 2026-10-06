import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, renderHook, screen, waitFor } from '@testing-library/react';
import { useNamedQueries } from '../useMetaModels';
import { NamedQueryPicker } from '../NamedQueryPicker';

const query = { pid: 'nq-contract', code: 'contract_query', title: 'Contract query' };
afterEach(() => vi.unstubAllGlobals());

describe('named-query picker API contract', () => {
  it('loads query options from the canonical enabled array endpoint', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue({ ok: true, json: async () => ({ code: '0', data: [query] }) });
    vi.stubGlobal('fetch', fetchMock);
    const { result } = renderHook(() => useNamedQueries());
    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(fetchMock).toHaveBeenCalledWith('/api/meta/named-queries/enabled');
    expect(result.current.namedQueries).toEqual([query]);
  });

  it('exposes HTTP rejection instead of presenting a successful empty catalog', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 403 }));
    const { result } = renderHook(() => useNamedQueries());
    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.error?.message).toContain('403');
    expect(result.current.namedQueries).toEqual([]);
  });

  it('rejects a malformed success payload instead of silently losing query options', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue({
          ok: true,
          json: async () => ({ code: '0', data: { content: [query] } }),
        }),
    );
    const { result } = renderHook(() => useNamedQueries());
    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.error?.message).toContain('Malformed');
  });

  it('shows a visible failure and disables selection when catalog loading fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('Catalog unavailable')));
    render(<NamedQueryPicker value={undefined} onChange={vi.fn()} />);
    await expect(screen.findByRole('alert')).resolves.toBeTruthy();
    expect(screen.getByRole('combobox')).toBeDisabled();
  });
});
