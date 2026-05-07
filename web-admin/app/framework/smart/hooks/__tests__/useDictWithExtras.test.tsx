/**
 * useDictWithExtras.test.tsx
 *
 * Phase 1 Task 3: pin contract that useDictWithExtras flattens dict items'
 * `extension.color` and `extension.terminal` to top-level fields, narrows
 * terminal to 'won' | 'lost', and skips fetch when dictCode is missing.
 *
 * Backend returns DictItemData with `extension: Object` (see
 * platform/.../DictDataResult.java line 85). Plugin import wires
 * `extension.color` / `extension.terminal` into that field.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';

// Mock http-client BEFORE importing the hook so the hook picks up the mock
vi.mock('~/shared/services/http-client', () => ({
  fetchResult: vi.fn(),
}));

import { fetchResult } from '~/shared/services/http-client';
import { useDictWithExtras } from '~/framework/smart/hooks/useDictWithExtras';

const mockFetchResult = vi.mocked(fetchResult);

describe('useDictWithExtras', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('flattens extension.color and extension.terminal onto each item', async () => {
    mockFetchResult.mockResolvedValue({
      code: '0',
      desc: '',
      message: '',
      data: {
        items: [
          {
            value: 'new',
            label: 'New',
            extension: { color: '#3B82F6', terminal: null },
          },
          {
            value: 'won',
            label: 'Won',
            extension: { color: '#10B981', terminal: 'won' },
          },
          {
            value: 'lost',
            label: 'Lost',
            extension: { color: '#6B7280', terminal: 'lost' },
          },
        ],
      },
    } as any);

    const { result } = renderHook(() => useDictWithExtras('crm_opportunity_stage'));

    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(mockFetchResult).toHaveBeenCalledTimes(1);
    const [path, options] = mockFetchResult.mock.calls[0];
    expect(path).toBe('/api/meta/dict/by-code/crm_opportunity_stage/data');
    expect((options as any).method).toBe('get');

    expect(result.current.items).toEqual([
      { value: 'new', label: 'New', color: '#3B82F6', terminal: undefined },
      { value: 'won', label: 'Won', color: '#10B981', terminal: 'won' },
      { value: 'lost', label: 'Lost', color: '#6B7280', terminal: 'lost' },
    ]);
  });

  it('returns terminal undefined when extension.terminal is absent or invalid', async () => {
    mockFetchResult.mockResolvedValue({
      code: '0',
      desc: '',
      message: '',
      data: {
        items: [
          { value: 'a', label: 'A', extension: { color: '#FF0000' } },
          { value: 'b', label: 'B', extension: { color: '#00FF00', terminal: 'pending' } },
        ],
      },
    } as any);

    const { result } = renderHook(() => useDictWithExtras('some_dict'));

    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(result.current.items).toEqual([
      { value: 'a', label: 'A', color: '#FF0000', terminal: undefined },
      { value: 'b', label: 'B', color: '#00FF00', terminal: undefined },
    ]);
  });

  it('returns color/terminal undefined when extension is missing', async () => {
    mockFetchResult.mockResolvedValue({
      code: '0',
      desc: '',
      message: '',
      data: {
        items: [
          { value: 'x', label: 'X' },
          { value: 'y', label: 'Y', extension: null },
        ],
      },
    } as any);

    const { result } = renderHook(() => useDictWithExtras('plain_dict'));

    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(result.current.items).toEqual([
      { value: 'x', label: 'X', color: undefined, terminal: undefined },
      { value: 'y', label: 'Y', color: undefined, terminal: undefined },
    ]);
  });

  it('does not fetch when dictCode is undefined', async () => {
    const { result } = renderHook(() => useDictWithExtras(undefined));

    // Microtask flush
    await Promise.resolve();

    expect(mockFetchResult).not.toHaveBeenCalled();
    expect(result.current.items).toEqual([]);
    expect(result.current.loading).toBe(false);
  });
});
