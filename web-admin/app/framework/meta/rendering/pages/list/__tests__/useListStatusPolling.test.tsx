import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { listStatusPoll, useListStatusPolling } from '../useListStatusPolling';

const schema = {
  dataSource: { type: 'api', endpoint: '/api/progress' },
  extension: {
    poll: {
      intervalMs: 3000,
      statusField: 'status',
      enabledWhenStatuses: ['pending', 'sending'],
    },
  },
};
const input = (load = vi.fn(async () => {})) => ({
  schema,
  data: [{ status: 'pending' }],
  dataReady: true,
  loading: false,
  error: null as string | null,
  skipListData: false,
  loadDataRef: { current: load },
});
describe('opt-in list status polling', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    Object.defineProperty(document, 'hidden', { configurable: true, value: false });
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('keeps ordinary lists unchanged and rejects invalid opt-in declarations', () => {
    expect(listStatusPoll({})).toBeUndefined();
    for (const poll of [
      { ...schema.extension.poll, intervalMs: 0 },
      { ...schema.extension.poll, statusField: 'status || true' },
      { ...schema.extension.poll, enabledWhenStatuses: [] },
      { ...schema.extension.poll, enabledWhenStatuses: ['pending', 'pending'] },
      { ...schema.extension.poll, other: true },
    ]) {
      expect(() => listStatusPoll({ ...schema, extension: { poll } })).toThrow(
        'Invalid list status',
      );
    }
    expect(() => listStatusPoll({ ...schema, dataSource: { type: 'model' } })).toThrow();
  });
  it('refreshes through the current loader without submitting filter drafts and stops on terminal data', async () => {
    const props = input();
    const { rerender, unmount } = renderHook(useListStatusPolling, { initialProps: props });
    await act(() => vi.advanceTimersByTimeAsync(3000));
    expect(props.loadDataRef.current).toHaveBeenCalledExactlyOnceWith({ background: true });
    const latest = vi.fn(async () => {});
    props.loadDataRef.current = latest;
    await act(() => vi.advanceTimersByTimeAsync(3000));
    expect(latest).toHaveBeenCalledExactlyOnceWith({ background: true });
    rerender({ ...props, data: [{ status: 'succeeded' }] });
    await act(() => vi.advanceTimersByTimeAsync(9000));
    expect(latest).toHaveBeenCalledTimes(1);
    unmount();
    expect(vi.getTimerCount()).toBe(0);
  });
  it('does not overlap slow reads or reschedule after unmount', async () => {
    let finish!: () => void;
    const load = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          finish = resolve;
        }),
    );
    const { unmount } = renderHook(useListStatusPolling, { initialProps: input(load) });
    await act(() => vi.advanceTimersByTimeAsync(30000));
    expect(load).toHaveBeenCalledTimes(1);
    unmount();
    await act(async () => finish());
    await act(() => vi.advanceTimersByTimeAsync(30000));
    expect(load).toHaveBeenCalledTimes(1);
  });
  it('pauses hidden pages and active user reads, then resumes without a new business request', async () => {
    const props = input();
    const { rerender } = renderHook(useListStatusPolling, { initialProps: props });
    Object.defineProperty(document, 'hidden', { configurable: true, value: true });
    await act(() => vi.advanceTimersByTimeAsync(6000));
    expect(props.loadDataRef.current).not.toHaveBeenCalled();
    Object.defineProperty(document, 'hidden', { configurable: true, value: false });
    rerender({ ...props, loading: true });
    await act(() => vi.advanceTimersByTimeAsync(3000));
    expect(props.loadDataRef.current).not.toHaveBeenCalled();
    rerender(props);
    await act(() => vi.advanceTimersByTimeAsync(3000));
    expect(props.loadDataRef.current).toHaveBeenCalledTimes(1);
  });
  it('does not start before initial data, with a read error, or for skipped lists', async () => {
    for (const patch of [
      { dataReady: false },
      { error: 'Read denied' },
      { skipListData: true },
      { data: [] },
    ]) {
      const props = { ...input(), ...patch };
      const { unmount } = renderHook(useListStatusPolling, { initialProps: props });
      await act(() => vi.advanceTimersByTimeAsync(6000));
      expect(props.loadDataRef.current).not.toHaveBeenCalled();
      unmount();
    }
  });
});
