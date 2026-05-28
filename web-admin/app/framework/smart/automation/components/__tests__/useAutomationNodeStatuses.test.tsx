// G5 — useAutomationNodeStatuses fetch hook tests.

import React from 'react';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';

import { useAutomationNodeStatuses } from '../useAutomationNodeStatuses';

const originalFetch = global.fetch;

afterEach(() => {
  global.fetch = originalFetch;
  vi.clearAllMocks();
});

describe('useAutomationNodeStatuses (G5)', () => {
  beforeEach(() => {
    global.fetch = vi.fn();
  });

  it('returns null statuses + no fetch when logId is undefined', () => {
    const { result } = renderHook(() => useAutomationNodeStatuses(undefined));
    expect(result.current.statuses).toBeNull();
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it('flattens the wire array into a Record<nodeId, status>', async () => {
    (global.fetch as any).mockResolvedValueOnce({
      ok: true,
      json: async () => ({
        data: [
          { nodeId: 'a', status: 'completed' },
          { nodeId: 'b', status: 'failed', errorMessage: 'boom' },
        ],
      }),
    });

    const { result } = renderHook(() => useAutomationNodeStatuses(42));

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.statuses).toEqual({ a: 'completed', b: 'failed' });
    expect(result.current.error).toBeNull();
    expect(global.fetch).toHaveBeenCalledWith(
      '/api/automation/executions/by-log/42/node-statuses',
    );
  });

  it('exposes a non-OK fetch error via the `error` field and clears statuses', async () => {
    (global.fetch as any).mockResolvedValueOnce({
      ok: false,
      status: 500,
      json: async () => ({ desc: 'server exploded' }),
    });

    const { result } = renderHook(() => useAutomationNodeStatuses(7));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.error).toBe('server exploded');
    expect(result.current.statuses).toBeNull();
  });

  it('returns null statuses when the response array is empty (no overlay rendered)', async () => {
    (global.fetch as any).mockResolvedValueOnce({
      ok: true,
      json: async () => ({ data: [] }),
    });
    const { result } = renderHook(() => useAutomationNodeStatuses(9));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.statuses).toBeNull();
  });
});
