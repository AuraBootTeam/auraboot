/**
 * useTransientFlag — a boolean that flips true on trigger() and auto-resets after
 * a delay. Backs the quiet "已保存到当前视图" hint (standard §3) and similar
 * transient confirmations.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useTransientFlag } from '~/hooks/useTransientFlag';

describe('useTransientFlag', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('starts false, flips true on trigger, auto-resets after the delay', () => {
    const { result } = renderHook(() => useTransientFlag(2000));
    expect(result.current[0]).toBe(false);
    act(() => result.current[1]());
    expect(result.current[0]).toBe(true);
    act(() => vi.advanceTimersByTime(1999));
    expect(result.current[0]).toBe(true);
    act(() => vi.advanceTimersByTime(1));
    expect(result.current[0]).toBe(false);
  });

  it('re-triggering restarts the timer (debounced reset)', () => {
    const { result } = renderHook(() => useTransientFlag(1000));
    act(() => result.current[1]());
    act(() => vi.advanceTimersByTime(800));
    act(() => result.current[1]()); // retrigger
    act(() => vi.advanceTimersByTime(800));
    expect(result.current[0]).toBe(true); // still on — timer restarted
    act(() => vi.advanceTimersByTime(200));
    expect(result.current[0]).toBe(false);
  });
});
