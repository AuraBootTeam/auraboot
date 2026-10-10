import { act, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  AuthSessionRevalidator,
  shouldRevalidateAuthSession,
} from '~/components/AuthSessionRevalidator';

const mocks = vi.hoisted(() => ({
  revalidate: vi.fn(),
  state: 'idle',
  fetch: vi.fn(),
}));

vi.mock('react-router', () => ({
  useRevalidator: () => ({
    state: mocks.state,
    revalidate: mocks.revalidate,
  }),
}));

describe('AuthSessionRevalidator', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-07-05T12:00:00.000Z'));
    mocks.state = 'idle';
    mocks.revalidate.mockReset();
    mocks.fetch.mockReset().mockResolvedValue({ status: 200 });
    vi.stubGlobal('fetch', mocks.fetch);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it('keeps the decision helper strict for auth and revalidator state', () => {
    expect(
      shouldRevalidateAuthSession({
        enabled: true,
        isAuthenticated: true,
        revalidatorState: 'idle',
        now: 2_000,
        lastRevalidatedAt: 1_000,
        minIntervalMs: 500,
      }),
    ).toBe(true);
    expect(
      shouldRevalidateAuthSession({
        enabled: true,
        isAuthenticated: false,
        revalidatorState: 'idle',
        now: 2_000,
        lastRevalidatedAt: 1_000,
        minIntervalMs: 500,
      }),
    ).toBe(false);
    expect(
      shouldRevalidateAuthSession({
        enabled: true,
        isAuthenticated: true,
        revalidatorState: 'loading',
        now: 2_000,
        lastRevalidatedAt: 1_000,
        minIntervalMs: 500,
      }),
    ).toBe(false);
  });

  it('does not revalidate anonymous runtime sessions', async () => {
    render(
      <AuthSessionRevalidator
        enabled
        isAuthenticated={false}
        intervalMs={1_000}
        minIntervalMs={0}
      />,
    );

    await act(async () => {
      vi.advanceTimersByTime(2_000);
      window.dispatchEvent(new Event('focus'));
    });

    expect(mocks.revalidate).not.toHaveBeenCalled();
  });

  it('throttles focus-triggered revalidation', async () => {
    render(
      <AuthSessionRevalidator
        enabled
        isAuthenticated
        intervalMs={0}
        minIntervalMs={1_000}
      />,
    );

    await act(async () => {
      window.dispatchEvent(new Event('focus'));
    });
    expect(mocks.revalidate).not.toHaveBeenCalled();

    await act(async () => {
      vi.advanceTimersByTime(1_001);
      window.dispatchEvent(new Event('focus'));
    });

    expect(mocks.revalidate).toHaveBeenCalledTimes(1);
  });

  it('periodically revalidates authenticated admin sessions', async () => {
    render(
      <AuthSessionRevalidator
        enabled
        isAuthenticated
        intervalMs={1_000}
        minIntervalMs={0}
      />,
    );

    await act(async () => {
      vi.advanceTimersByTime(1_000);
    });

    expect(mocks.revalidate).toHaveBeenCalledTimes(1);
  });

  it('does not start another revalidation while React Router is already loading', async () => {
    mocks.state = 'loading';
    render(
      <AuthSessionRevalidator
        enabled
        isAuthenticated
        intervalMs={1_000}
        minIntervalMs={0}
      />,
    );

    await act(async () => {
      vi.advanceTimersByTime(1_000);
      window.dispatchEvent(new Event('focus'));
    });

    expect(mocks.revalidate).not.toHaveBeenCalled();
  });

  it('keeps the live page during offline polling and revalidates on recovery', async () => {
    const online = vi.spyOn(window.navigator, 'onLine', 'get').mockReturnValue(false);
    const view = render(
      <AuthSessionRevalidator enabled isAuthenticated intervalMs={1_000} minIntervalMs={0} />,
    );
    await act(async () => {
      vi.advanceTimersByTime(3_000);
      window.dispatchEvent(new Event('focus'));
      document.dispatchEvent(new Event('visibilitychange'));
    });
    expect(mocks.revalidate).not.toHaveBeenCalled();
    online.mockReturnValue(true);
    await act(async () => { window.dispatchEvent(new Event('online')); });
    expect(mocks.revalidate).toHaveBeenCalledTimes(1);
    view.unmount();
    await act(async () => { window.dispatchEvent(new Event('online')); });
    expect(mocks.revalidate).toHaveBeenCalledTimes(1);
  });

  it.each([500, 502, 503])('keeps the page alive on backend HTTP %s and refreshes after recovery', async (status) => {
    mocks.fetch.mockResolvedValueOnce({ status });
    render(<AuthSessionRevalidator enabled isAuthenticated intervalMs={1_000} minIntervalMs={0} />);
    await act(async () => { vi.advanceTimersByTime(1_000); });
    expect(mocks.fetch).toHaveBeenCalledWith('/api/auth/me', expect.objectContaining({ credentials: 'same-origin', signal: expect.any(AbortSignal) }));
    expect(mocks.revalidate).not.toHaveBeenCalled();
    await act(async () => { vi.advanceTimersByTime(1_000); });
    expect(mocks.revalidate).toHaveBeenCalledTimes(1);
  });

  it('keeps the page alive on transport failure without hiding a subsequent auth rejection', async () => {
    mocks.fetch.mockRejectedValueOnce(new TypeError('Failed to fetch'));
    mocks.fetch.mockResolvedValueOnce({ status: 401 });
    render(<AuthSessionRevalidator enabled isAuthenticated intervalMs={1_000} minIntervalMs={0} />);
    await act(async () => { vi.advanceTimersByTime(1_000); });
    expect(mocks.revalidate).not.toHaveBeenCalled();
    await act(async () => { vi.advanceTimersByTime(1_000); });
    expect(mocks.revalidate).toHaveBeenCalledTimes(1);
  });

  it('aborts a pending probe on unmount and never refreshes a disposed identity', async () => {
    let finish!: (value: { status: number }) => void;
    mocks.fetch.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
    const view = render(<AuthSessionRevalidator enabled isAuthenticated intervalMs={1_000} minIntervalMs={0} />);
    await act(async () => { vi.advanceTimersByTime(1_000); });
    await act(async () => { window.dispatchEvent(new Event('focus')); });
    expect(mocks.fetch).toHaveBeenCalledTimes(1);
    const signal = mocks.fetch.mock.calls[0][1].signal;
    view.unmount();
    expect(signal.aborted).toBe(true);
    await act(async () => { finish({ status: 200 }); });
    expect(mocks.revalidate).not.toHaveBeenCalled();
  });

});
