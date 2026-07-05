import { act, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  AuthSessionRevalidator,
  shouldRevalidateAuthSession,
} from '~/components/AuthSessionRevalidator';

const mocks = vi.hoisted(() => ({
  revalidate: vi.fn(),
  state: 'idle',
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
  });

  afterEach(() => {
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

  it('does not revalidate anonymous runtime sessions', () => {
    render(
      <AuthSessionRevalidator
        enabled
        isAuthenticated={false}
        intervalMs={1_000}
        minIntervalMs={0}
      />,
    );

    act(() => {
      vi.advanceTimersByTime(2_000);
      window.dispatchEvent(new Event('focus'));
    });

    expect(mocks.revalidate).not.toHaveBeenCalled();
  });

  it('throttles focus-triggered revalidation', () => {
    render(
      <AuthSessionRevalidator
        enabled
        isAuthenticated
        intervalMs={0}
        minIntervalMs={1_000}
      />,
    );

    act(() => {
      window.dispatchEvent(new Event('focus'));
    });
    expect(mocks.revalidate).not.toHaveBeenCalled();

    act(() => {
      vi.advanceTimersByTime(1_001);
      window.dispatchEvent(new Event('focus'));
    });

    expect(mocks.revalidate).toHaveBeenCalledTimes(1);
  });

  it('periodically revalidates authenticated admin sessions', () => {
    render(
      <AuthSessionRevalidator
        enabled
        isAuthenticated
        intervalMs={1_000}
        minIntervalMs={0}
      />,
    );

    act(() => {
      vi.advanceTimersByTime(1_000);
    });

    expect(mocks.revalidate).toHaveBeenCalledTimes(1);
  });

  it('does not start another revalidation while React Router is already loading', () => {
    mocks.state = 'loading';
    render(
      <AuthSessionRevalidator
        enabled
        isAuthenticated
        intervalMs={1_000}
        minIntervalMs={0}
      />,
    );

    act(() => {
      vi.advanceTimersByTime(1_000);
      window.dispatchEvent(new Event('focus'));
    });

    expect(mocks.revalidate).not.toHaveBeenCalled();
  });
});
