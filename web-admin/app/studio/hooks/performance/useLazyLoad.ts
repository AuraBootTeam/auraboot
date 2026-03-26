/**
 * useLazyLoad Hook
 *
 * Provides lazy loading functionality for components and data.
 *
 * @since 3.2.0
 */

import { useState, useEffect, useRef, useCallback } from 'react';

export interface LazyLoadOptions {
  /** Root element for intersection observer */
  root?: Element | null;
  /** Root margin */
  rootMargin?: string;
  /** Threshold for visibility */
  threshold?: number | number[];
  /** Trigger only once */
  triggerOnce?: boolean;
}

export interface LazyLoadResult {
  /** Ref to attach to the element */
  ref: React.RefObject<HTMLElement | null>;
  /** Whether the element is in view */
  inView: boolean;
  /** Whether the element has been viewed */
  hasBeenViewed: boolean;
}

/**
 * Lazy load hook using Intersection Observer
 */
export function useLazyLoad(options: LazyLoadOptions = {}): LazyLoadResult {
  const { root = null, rootMargin = '100px', threshold = 0, triggerOnce = true } = options;

  const [inView, setInView] = useState(false);
  const [hasBeenViewed, setHasBeenViewed] = useState(false);
  const ref = useRef<HTMLElement>(null);

  useEffect(() => {
    const element = ref.current;
    if (!element) return;

    // Skip if already triggered and triggerOnce is true
    if (triggerOnce && hasBeenViewed) return;

    const observer = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          const isIntersecting = entry.isIntersecting;
          setInView(isIntersecting);

          if (isIntersecting && !hasBeenViewed) {
            setHasBeenViewed(true);

            if (triggerOnce) {
              observer.unobserve(element);
            }
          }
        });
      },
      { root, rootMargin, threshold },
    );

    observer.observe(element);

    return () => {
      observer.unobserve(element);
    };
  }, [root, rootMargin, threshold, triggerOnce, hasBeenViewed]);

  return { ref, inView, hasBeenViewed };
}

/**
 * Lazy load data hook
 */
export interface LazyDataOptions<T> {
  /** Data fetcher function */
  fetcher: () => Promise<T>;
  /** Dependencies that trigger refetch */
  deps?: unknown[];
  /** Whether to fetch immediately or lazily */
  immediate?: boolean;
}

export interface LazyDataResult<T> {
  /** Loaded data */
  data: T | null;
  /** Loading state */
  loading: boolean;
  /** Error state */
  error: Error | null;
  /** Trigger load manually */
  load: () => Promise<void>;
  /** Reset state */
  reset: () => void;
}

export function useLazyData<T>(options: LazyDataOptions<T>): LazyDataResult<T> {
  const { fetcher, deps = [], immediate = false } = options;

  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const loadedRef = useRef(false);

  const load = useCallback(async () => {
    if (loadedRef.current && !deps.length) return;

    setLoading(true);
    setError(null);

    try {
      const result = await fetcher();
      setData(result);
      loadedRef.current = true;
    } catch (err) {
      setError(err instanceof Error ? err : new Error(String(err)));
    } finally {
      setLoading(false);
    }
  }, [fetcher, ...deps]);

  const reset = useCallback(() => {
    setData(null);
    setError(null);
    setLoading(false);
    loadedRef.current = false;
  }, []);

  useEffect(() => {
    if (immediate) {
      load();
    }
  }, [immediate, load]);

  return { data, loading, error, load, reset };
}

/**
 * Lazy component wrapper hook
 */
export interface LazyComponentOptions {
  /** Delay before showing loading state (ms) */
  loadingDelay?: number;
  /** Minimum time to show loading state (ms) */
  minLoadingTime?: number;
}

export function useLazyComponent<T>(
  importFn: () => Promise<{ default: T }>,
  options: LazyComponentOptions = {},
): {
  Component: T | null;
  loading: boolean;
  error: Error | null;
} {
  const { loadingDelay = 200, minLoadingTime = 0 } = options;

  const [Component, setComponent] = useState<T | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<Error | null>(null);

  useEffect(() => {
    let mounted = true;
    let loadingTimeout: ReturnType<typeof setTimeout>;
    let startTime = Date.now();

    loadingTimeout = setTimeout(() => {
      if (mounted) setLoading(true);
    }, loadingDelay);

    importFn()
      .then((module) => {
        if (!mounted) return;

        const elapsed = Date.now() - startTime;
        const remaining = Math.max(0, minLoadingTime - elapsed);

        setTimeout(() => {
          if (mounted) {
            clearTimeout(loadingTimeout);
            setComponent(() => module.default);
            setLoading(false);
          }
        }, remaining);
      })
      .catch((err) => {
        if (mounted) {
          clearTimeout(loadingTimeout);
          setError(err);
          setLoading(false);
        }
      });

    return () => {
      mounted = false;
      clearTimeout(loadingTimeout);
    };
  }, [importFn, loadingDelay, minLoadingTime]);

  return { Component, loading, error };
}

export default useLazyLoad;
