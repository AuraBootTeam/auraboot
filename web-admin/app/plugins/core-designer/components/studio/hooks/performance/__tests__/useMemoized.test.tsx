/**
 * Unit tests for useMemoized hooks
 *
 * Covers: useDeepMemo, useDeepCallback, useSelector, usePrevious,
 *         useStableCallback, useThrottledCallback, useDebouncedCallback,
 *         useDebouncedValue, useDeepMemoObject, useCache
 */

import { renderHook, act } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

import {
  useDeepMemo,
  useDeepCallback,
  useSelector,
  usePrevious,
  useStableCallback,
  useThrottledCallback,
  useDebouncedCallback,
  useDebouncedValue,
  useDeepMemoObject,
  useCache,
} from '../useMemoized';

// ---------------------------------------------------------------------------
// useDeepMemo
// ---------------------------------------------------------------------------
describe('useDeepMemo', () => {
  it('returns initial factory result', () => {
    const factory = vi.fn(() => ({ x: 1 }));
    const { result } = renderHook(() => useDeepMemo(factory, [1]));
    expect(result.current).toEqual({ x: 1 });
    expect(factory).toHaveBeenCalledTimes(1);
  });

  it('does not re-run factory when deps are deeply equal', () => {
    const factory = vi.fn(() => ({ x: 1 }));
    let deps = [{ a: 1 }];
    const { result, rerender } = renderHook(() => useDeepMemo(factory, deps));
    const first = result.current;

    // New array object but deep-equal — must NOT re-run factory
    deps = [{ a: 1 }];
    rerender();
    expect(factory).toHaveBeenCalledTimes(1);
    expect(result.current).toBe(first);
  });

  it('re-runs factory when deps change', () => {
    const factory = vi.fn((n: number) => ({ n }));
    let n = 0;
    const { result, rerender } = renderHook(() => useDeepMemo(() => factory(n), [n]));
    expect(result.current).toEqual({ n: 0 });

    n = 1;
    rerender();
    expect(factory).toHaveBeenCalledTimes(2);
    expect(result.current).toEqual({ n: 1 });
  });
});

// ---------------------------------------------------------------------------
// useDeepCallback
// ---------------------------------------------------------------------------
describe('useDeepCallback', () => {
  it('returns same callback reference when deps are deeply equal', () => {
    const cb = vi.fn();
    let deps = [{ key: 'a' }];
    const { result, rerender } = renderHook(() => useDeepCallback(cb, deps));
    const first = result.current;

    deps = [{ key: 'a' }]; // new object, same deep value
    rerender();
    expect(result.current).toBe(first);
  });

  it('returns new callback reference when deps change', () => {
    let deps = [{ key: 'a' }];
    const cb1 = vi.fn();
    const cb2 = vi.fn();
    let cb = cb1;
    const { result, rerender } = renderHook(() => useDeepCallback(cb, deps));
    const first = result.current;

    deps = [{ key: 'b' }];
    cb = cb2;
    rerender();
    expect(result.current).not.toBe(first);
  });
});

// ---------------------------------------------------------------------------
// useSelector
// ---------------------------------------------------------------------------
describe('useSelector', () => {
  it('returns selected value', () => {
    const state = { count: 42, name: 'test' };
    const { result } = renderHook(() => useSelector(state, (s) => s.count));
    expect(result.current).toBe(42);
  });

  it('returns same reference when selector result is referentially equal', () => {
    const state = { items: [1, 2, 3] };
    const selector = (s: typeof state) => s.items;
    const { result, rerender } = renderHook(() => useSelector(state, selector));
    const first = result.current;
    rerender();
    expect(result.current).toBe(first);
  });

  it('uses custom equality function', () => {
    let state = { value: { x: 1 } };
    const selector = (s: typeof state) => s.value;
    const equalityFn = (a: { x: number }, b: { x: number }) => a.x === b.x;

    const { result, rerender } = renderHook(() => useSelector(state, selector, equalityFn));
    const first = result.current;

    // Different object but same value — should keep same reference via custom eq
    state = { value: { x: 1 } };
    rerender();
    expect(result.current).toBe(first);
  });
});

// ---------------------------------------------------------------------------
// usePrevious
// ---------------------------------------------------------------------------
describe('usePrevious', () => {
  it('returns undefined on first render', () => {
    const { result } = renderHook(() => usePrevious(10));
    expect(result.current).toBeUndefined();
  });

  it('returns previous value after rerender', () => {
    let value = 1;
    const { result, rerender } = renderHook(() => usePrevious(value));
    expect(result.current).toBeUndefined();

    act(() => {
      value = 2;
      rerender();
    });
    expect(result.current).toBe(1);

    act(() => {
      value = 3;
      rerender();
    });
    expect(result.current).toBe(2);
  });
});

// ---------------------------------------------------------------------------
// useStableCallback
// ---------------------------------------------------------------------------
describe('useStableCallback', () => {
  it('returns stable function reference across rerenders', () => {
    let cb = vi.fn();
    const { result, rerender } = renderHook(() => useStableCallback(cb));
    const first = result.current;

    cb = vi.fn();
    rerender();
    // Reference must be the same
    expect(result.current).toBe(first);
  });

  it('delegates to the latest callback when invoked', () => {
    const cb1 = vi.fn();
    const cb2 = vi.fn();
    let cb: (...args: unknown[]) => unknown = cb1;

    const { result, rerender } = renderHook(() => useStableCallback(cb));
    result.current('initial');
    expect(cb1).toHaveBeenCalledWith('initial');

    // Update underlying callback
    cb = cb2;
    rerender();
    result.current('updated');
    expect(cb2).toHaveBeenCalledWith('updated');
    expect(cb1).toHaveBeenCalledTimes(1);
  });
});

// ---------------------------------------------------------------------------
// useThrottledCallback
// ---------------------------------------------------------------------------
describe('useThrottledCallback', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('invokes callback immediately on first call', () => {
    const cb = vi.fn();
    const { result } = renderHook(() => useThrottledCallback(cb, 200));
    result.current('first');
    expect(cb).toHaveBeenCalledTimes(1);
    expect(cb).toHaveBeenCalledWith('first');
  });

  it('throttles subsequent calls within delay', () => {
    const cb = vi.fn();
    const { result } = renderHook(() => useThrottledCallback(cb, 200));

    result.current('first');
    result.current('second'); // within throttle window — should not call immediately
    expect(cb).toHaveBeenCalledTimes(1);

    // Advance past throttle delay — deferred call fires
    vi.advanceTimersByTime(200);
    expect(cb).toHaveBeenCalledTimes(2);
  });

  it('cleans up pending timeout on unmount', () => {
    const cb = vi.fn();
    const { result, unmount } = renderHook(() => useThrottledCallback(cb, 200));
    result.current('first');
    result.current('second');
    unmount();
    vi.advanceTimersByTime(300);
    expect(cb).toHaveBeenCalledTimes(1); // deferred call was cleared
  });
});

// ---------------------------------------------------------------------------
// useDebouncedCallback
// ---------------------------------------------------------------------------
describe('useDebouncedCallback', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('does not call callback immediately', () => {
    const cb = vi.fn();
    const { result } = renderHook(() => useDebouncedCallback(cb, 200));
    result.current('hello');
    expect(cb).not.toHaveBeenCalled();
  });

  it('calls callback after delay', () => {
    const cb = vi.fn();
    const { result } = renderHook(() => useDebouncedCallback(cb, 200));
    result.current('hello');
    vi.advanceTimersByTime(200);
    expect(cb).toHaveBeenCalledTimes(1);
    expect(cb).toHaveBeenCalledWith('hello');
  });

  it('resets timer on repeated calls (debounce behaviour)', () => {
    const cb = vi.fn();
    const { result } = renderHook(() => useDebouncedCallback(cb, 200));
    result.current('first');
    vi.advanceTimersByTime(100);
    result.current('second');
    vi.advanceTimersByTime(100);
    expect(cb).not.toHaveBeenCalled();
    vi.advanceTimersByTime(100);
    expect(cb).toHaveBeenCalledTimes(1);
    expect(cb).toHaveBeenCalledWith('second');
  });

  it('clears pending timeout on unmount', () => {
    const cb = vi.fn();
    const { result, unmount } = renderHook(() => useDebouncedCallback(cb, 200));
    result.current('hello');
    unmount();
    vi.advanceTimersByTime(300);
    expect(cb).not.toHaveBeenCalled();
  });
});

// ---------------------------------------------------------------------------
// useDebouncedValue
// ---------------------------------------------------------------------------
describe('useDebouncedValue', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('returns initial value immediately', () => {
    const { result } = renderHook(() => useDebouncedValue('initial', 200));
    expect(result.current).toBe('initial');
  });

  it('updates value after delay', () => {
    let val = 'initial';
    const { result, rerender } = renderHook(() => useDebouncedValue(val, 200));
    val = 'updated';
    rerender();
    expect(result.current).toBe('initial');
    act(() => {
      vi.advanceTimersByTime(200);
    });
    expect(result.current).toBe('updated');
  });

  it('resets debounce on rapid changes', () => {
    let val = 'a';
    const { result, rerender } = renderHook(() => useDebouncedValue(val, 200));

    act(() => {
      val = 'b';
      rerender();
    });
    act(() => {
      vi.advanceTimersByTime(100);
    });
    act(() => {
      val = 'c';
      rerender();
    });
    expect(result.current).toBe('a');
    act(() => {
      vi.advanceTimersByTime(200);
    });
    expect(result.current).toBe('c');
  });
});

// ---------------------------------------------------------------------------
// useDeepMemoObject
// ---------------------------------------------------------------------------
describe('useDeepMemoObject', () => {
  it('returns same reference when object is deeply equal', () => {
    let obj = { a: 1, b: 2 };
    const { result, rerender } = renderHook(() => useDeepMemoObject(obj));
    const first = result.current;

    obj = { a: 1, b: 2 }; // new reference, same deep value
    rerender();
    expect(result.current).toBe(first);
  });

  it('returns new reference when object changes', () => {
    let obj = { a: 1 };
    const { result, rerender } = renderHook(() => useDeepMemoObject(obj));
    const first = result.current;

    obj = { a: 2 };
    rerender();
    expect(result.current).not.toBe(first);
    expect(result.current).toEqual({ a: 2 });
  });
});

// ---------------------------------------------------------------------------
// useCache
// ---------------------------------------------------------------------------
describe('useCache', () => {
  it('stores and retrieves values', () => {
    const { result } = renderHook(() => useCache<string, number>(50));
    act(() => {
      result.current.set('key1', 100);
    });
    expect(result.current.get('key1')).toBe(100);
  });

  it('returns undefined for missing keys', () => {
    const { result } = renderHook(() => useCache<string, number>());
    expect(result.current.get('missing')).toBeUndefined();
  });

  it('has() returns correct boolean', () => {
    const { result } = renderHook(() => useCache<string, string>());
    act(() => {
      result.current.set('k', 'v');
    });
    expect(result.current.has('k')).toBe(true);
    expect(result.current.has('nope')).toBe(false);
  });

  it('clear() empties the cache', () => {
    const { result } = renderHook(() => useCache<string, number>());
    act(() => {
      result.current.set('a', 1);
      result.current.set('b', 2);
    });
    expect(result.current.size).toBe(2);
    act(() => {
      result.current.clear();
    });
    expect(result.current.size).toBe(0);
  });

  it('evicts oldest entry when maxSize is reached', () => {
    const { result } = renderHook(() => useCache<string, number>(3));
    act(() => {
      result.current.set('a', 1);
      result.current.set('b', 2);
      result.current.set('c', 3);
      // Adding 'd' should evict 'a' (oldest)
      result.current.set('d', 4);
    });
    expect(result.current.has('a')).toBe(false);
    expect(result.current.has('b')).toBe(true);
    expect(result.current.has('d')).toBe(true);
    expect(result.current.size).toBe(3);
  });
});
