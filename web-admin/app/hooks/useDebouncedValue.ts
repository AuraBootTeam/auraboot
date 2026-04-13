/**
 * useDebouncedValue — Returns a debounced version of the provided value.
 *
 * The debounced value only updates after the specified delay has elapsed
 * since the last change. Useful for reducing API calls triggered by
 * rapid user input (search fields, filter changes, etc.).
 *
 * @param value - The value to debounce
 * @param delayMs - Debounce delay in milliseconds
 * @returns The debounced value
 */
import { useState, useEffect, useRef } from 'react';

export function useDebouncedValue<T>(value: T, delayMs: number): T {
  const [debouncedValue, setDebouncedValue] = useState(value);
  const timerRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  useEffect(() => {
    // Clear previous timer on every value change
    if (timerRef.current) {
      clearTimeout(timerRef.current);
    }

    timerRef.current = setTimeout(() => {
      setDebouncedValue(value);
    }, delayMs);

    return () => {
      if (timerRef.current) {
        clearTimeout(timerRef.current);
      }
    };
  }, [value, delayMs]);

  return debouncedValue;
}

/**
 * useDebouncedCallback — Returns a debounced version of a callback function.
 *
 * The callback is only invoked after the specified delay has elapsed
 * since the last call. The latest arguments are always used.
 *
 * @param callback - The function to debounce
 * @param delayMs - Debounce delay in milliseconds
 * @returns A debounced version of the callback, plus a flush() method
 */
export function useDebouncedCallback<Args extends unknown[]>(
  callback: (...args: Args) => void,
  delayMs: number,
): { (...args: Args): void; flush: () => void; cancel: () => void } {
  const callbackRef = useRef(callback);
  const timerRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const lastArgsRef = useRef<Args | null>(null);

  // Always use the latest callback without re-creating the debounced function
  callbackRef.current = callback;

  const cancel = useRef(() => {
    if (timerRef.current) {
      clearTimeout(timerRef.current);
      timerRef.current = undefined;
    }
    lastArgsRef.current = null;
  }).current;

  const flush = useRef(() => {
    if (timerRef.current && lastArgsRef.current) {
      clearTimeout(timerRef.current);
      timerRef.current = undefined;
      callbackRef.current(...lastArgsRef.current);
      lastArgsRef.current = null;
    }
  }).current;

  // Cleanup on unmount
  useEffect(() => cancel, [cancel]);

  const debounced = useRef((...args: Args) => {
    lastArgsRef.current = args;

    if (timerRef.current) {
      clearTimeout(timerRef.current);
    }

    timerRef.current = setTimeout(() => {
      timerRef.current = undefined;
      if (lastArgsRef.current) {
        callbackRef.current(...lastArgsRef.current);
        lastArgsRef.current = null;
      }
    }, delayMs);
  }).current;

  // Attach flush and cancel as properties
  (debounced as any).flush = flush;
  (debounced as any).cancel = cancel;

  return debounced as any;
}
