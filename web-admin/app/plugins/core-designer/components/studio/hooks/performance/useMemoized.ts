/**
 * useMemoized Hooks
 *
 * Advanced memoization utilities for performance optimization.
 *
 * @since 3.2.0
 */

import { useRef, useCallback, useState, useEffect } from 'react';

/**
 * Deep comparison memoization hook
 */
export function useDeepMemo<T>(factory: () => T, deps: unknown[]): T {
  const ref = useRef<{ deps: unknown[]; value: T } | undefined>(undefined);

  if (!ref.current || !deepEqual(deps, ref.current.deps)) {
    ref.current = {
      deps,
      value: factory(),
    };
  }

  return ref.current.value;
}

/**
 * Deep comparison callback hook
 */
export function useDeepCallback<T extends (...args: unknown[]) => unknown>(
  callback: T,
  deps: unknown[],
): T {
  const ref = useRef<{ deps: unknown[]; callback: T } | undefined>(undefined);

  if (!ref.current || !deepEqual(deps, ref.current.deps)) {
    ref.current = {
      deps,
      callback,
    };
  }

  return ref.current.callback;
}

/**
 * Memoized selector hook (like Redux's reselect)
 */
export function useSelector<S, R>(
  state: S,
  selector: (state: S) => R,
  equalityFn: (a: R, b: R) => boolean = Object.is,
): R {
  const resultRef = useRef<R | undefined>(undefined);
  const selectorRef = useRef(selector);
  selectorRef.current = selector;

  const newResult = selectorRef.current(state);

  if (resultRef.current === undefined || !equalityFn(resultRef.current, newResult)) {
    resultRef.current = newResult;
  }

  return resultRef.current as R;
}

/**
 * Previous value hook
 */
export function usePrevious<T>(value: T): T | undefined {
  const ref = useRef<T | undefined>(undefined);

  useEffect(() => {
    ref.current = value;
  }, [value]);

  return ref.current;
}

/**
 * Stable callback that doesn't change reference
 */
export function useStableCallback<T extends (...args: unknown[]) => unknown>(callback: T): T {
  const callbackRef = useRef(callback);

  useEffect(() => {
    callbackRef.current = callback;
  }, [callback]);

  return useCallback(((...args) => callbackRef.current(...args)) as T, []);
}

/**
 * Throttled callback hook
 */
export function useThrottledCallback<T extends (...args: unknown[]) => unknown>(
  callback: T,
  delay: number,
): T {
  const lastRun = useRef(0);
  const timeoutRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  const throttledCallback = useCallback(
    ((...args) => {
      const now = Date.now();
      const remaining = delay - (now - lastRun.current);

      if (remaining <= 0) {
        lastRun.current = now;
        callback(...args);
      } else {
        if (timeoutRef.current) {
          clearTimeout(timeoutRef.current);
        }
        timeoutRef.current = setTimeout(() => {
          lastRun.current = Date.now();
          callback(...args);
        }, remaining);
      }
    }) as T,
    [callback, delay],
  );

  useEffect(() => {
    return () => {
      if (timeoutRef.current) {
        clearTimeout(timeoutRef.current);
      }
    };
  }, []);

  return throttledCallback;
}

/**
 * Debounced callback hook
 */
export function useDebouncedCallback<T extends (...args: unknown[]) => unknown>(
  callback: T,
  delay: number,
): T {
  const timeoutRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  const debouncedCallback = useCallback(
    ((...args) => {
      if (timeoutRef.current) {
        clearTimeout(timeoutRef.current);
      }
      timeoutRef.current = setTimeout(() => {
        callback(...args);
      }, delay);
    }) as T,
    [callback, delay],
  );

  useEffect(() => {
    return () => {
      if (timeoutRef.current) {
        clearTimeout(timeoutRef.current);
      }
    };
  }, []);

  return debouncedCallback;
}

/**
 * Debounced value hook
 */
export function useDebouncedValue<T>(value: T, delay: number): T {
  const [debouncedValue, setDebouncedValue] = useState(value);

  useEffect(() => {
    const timer = setTimeout(() => {
      setDebouncedValue(value);
    }, delay);

    return () => {
      clearTimeout(timer);
    };
  }, [value, delay]);

  return debouncedValue;
}

/**
 * Memoized object that only changes when deep equality fails
 */
export function useDeepMemoObject<T extends Record<string, unknown>>(object: T): T {
  const ref = useRef<T>(object);

  if (!deepEqual(ref.current, object)) {
    ref.current = object;
  }

  return ref.current;
}

/**
 * Deep equality comparison
 */
function deepEqual(a: unknown, b: unknown): boolean {
  if (a === b) return true;

  if (typeof a !== 'object' || typeof b !== 'object' || a === null || b === null) {
    return false;
  }

  const keysA = Object.keys(a as Record<string, unknown>);
  const keysB = Object.keys(b as Record<string, unknown>);

  if (keysA.length !== keysB.length) return false;

  for (const key of keysA) {
    if (
      !keysB.includes(key) ||
      !deepEqual((a as Record<string, unknown>)[key], (b as Record<string, unknown>)[key])
    ) {
      return false;
    }
  }

  return true;
}

/**
 * Cache hook for expensive computations
 */
export function useCache<K, V>(
  maxSize = 100,
): {
  get: (key: K) => V | undefined;
  set: (key: K, value: V) => void;
  has: (key: K) => boolean;
  clear: () => void;
  size: number;
} {
  const cacheRef = useRef(new Map<K, V>());

  const get = useCallback((key: K): V | undefined => {
    return cacheRef.current.get(key);
  }, []);

  const set = useCallback(
    (key: K, value: V): void => {
      if (cacheRef.current.size >= maxSize) {
        // Remove oldest entry (first key)
        const firstKey = cacheRef.current.keys().next().value;
        if (firstKey !== undefined) {
          cacheRef.current.delete(firstKey);
        }
      }
      cacheRef.current.set(key, value);
    },
    [maxSize],
  );

  const has = useCallback((key: K): boolean => {
    return cacheRef.current.has(key);
  }, []);

  const clear = useCallback((): void => {
    cacheRef.current.clear();
  }, []);

  return {
    get,
    set,
    has,
    clear,
    get size() {
      return cacheRef.current.size;
    },
  };
}

export default useDeepMemo;
