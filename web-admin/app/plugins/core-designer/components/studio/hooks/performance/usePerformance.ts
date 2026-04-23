/**
 * usePerformance Hook
 *
 * Performance monitoring and profiling utilities.
 *
 * @since 3.2.0
 */

import { useRef, useCallback, useEffect, useState } from 'react';

/**
 * Performance metric
 */
export interface PerformanceMetric {
  /** Metric name */
  name: string;
  /** Start time */
  startTime: number;
  /** End time */
  endTime?: number;
  /** Duration in ms */
  duration?: number;
  /** Metadata */
  metadata?: Record<string, unknown>;
}

/**
 * Performance report
 */
export interface PerformanceReport {
  /** Report ID */
  id: string;
  /** Start time */
  startTime: number;
  /** End time */
  endTime?: number;
  /** Total duration */
  totalDuration?: number;
  /** Metrics */
  metrics: PerformanceMetric[];
  /** Average metric duration */
  averageDuration: number;
  /** Max metric duration */
  maxDuration: number;
  /** Min metric duration */
  minDuration: number;
  /** Metric count */
  metricCount: number;
}

/**
 * Render timing info
 */
export interface RenderTiming {
  /** Render count */
  renderCount: number;
  /** Last render time */
  lastRenderTime: number;
  /** Average render duration */
  averageRenderDuration: number;
  /** Max render duration */
  maxRenderDuration: number;
  /** Render durations history */
  renderDurations: number[];
}

/**
 * usePerformance hook options
 */
interface UsePerformanceOptions {
  /** Enable performance monitoring */
  enabled?: boolean;
  /** Max metrics to keep */
  maxMetrics?: number;
  /** Log to console */
  logToConsole?: boolean;
  /** Slow threshold in ms */
  slowThreshold?: number;
}

/**
 * usePerformance hook return type
 */
interface UsePerformanceReturn {
  /** Start a metric measurement */
  startMetric: (name: string, metadata?: Record<string, unknown>) => () => void;
  /** Measure a function execution time */
  measure: <T>(name: string, fn: () => T) => T;
  /** Measure an async function execution time */
  measureAsync: <T>(name: string, fn: () => Promise<T>) => Promise<T>;
  /** Get current metrics */
  getMetrics: () => PerformanceMetric[];
  /** Get performance report */
  getReport: () => PerformanceReport;
  /** Clear metrics */
  clearMetrics: () => void;
  /** Mark a performance point */
  mark: (name: string) => void;
  /** Measure between marks */
  measureBetweenMarks: (name: string, startMark: string, endMark: string) => number | null;
}

/**
 * usePerformance hook
 *
 * Performance monitoring and profiling.
 */
export function usePerformance(options: UsePerformanceOptions = {}): UsePerformanceReturn {
  const { enabled = true, maxMetrics = 100, logToConsole = false, slowThreshold = 100 } = options;

  const metricsRef = useRef<PerformanceMetric[]>([]);
  const marksRef = useRef<Map<string, number>>(new Map());
  const startTimeRef = useRef(Date.now());
  const reportIdRef = useRef(`perf-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`);

  /**
   * Add a metric
   */
  const addMetric = useCallback(
    (metric: PerformanceMetric) => {
      if (!enabled) return;

      metricsRef.current.push(metric);

      // Trim if exceeded max
      if (metricsRef.current.length > maxMetrics) {
        metricsRef.current = metricsRef.current.slice(-maxMetrics);
      }

      // Log slow metrics
      if (logToConsole && metric.duration && metric.duration > slowThreshold) {
        console.warn(
          `[Performance] Slow metric "${metric.name}": ${metric.duration.toFixed(2)}ms`,
          metric.metadata,
        );
      }
    },
    [enabled, maxMetrics, logToConsole, slowThreshold],
  );

  /**
   * Start a metric measurement
   */
  const startMetric = useCallback(
    (name: string, metadata?: Record<string, unknown>) => {
      const startTime = performance.now();

      return () => {
        const endTime = performance.now();
        const duration = endTime - startTime;

        addMetric({
          name,
          startTime,
          endTime,
          duration,
          metadata,
        });
      };
    },
    [addMetric],
  );

  /**
   * Measure a function execution time
   */
  const measure = useCallback(
    <T>(name: string, fn: () => T): T => {
      const endMetric = startMetric(name);
      try {
        return fn();
      } finally {
        endMetric();
      }
    },
    [startMetric],
  );

  /**
   * Measure an async function execution time
   */
  const measureAsync = useCallback(
    async <T>(name: string, fn: () => Promise<T>): Promise<T> => {
      const endMetric = startMetric(name);
      try {
        return await fn();
      } finally {
        endMetric();
      }
    },
    [startMetric],
  );

  /**
   * Get current metrics
   */
  const getMetrics = useCallback(() => {
    return [...metricsRef.current];
  }, []);

  /**
   * Get performance report
   */
  const getReport = useCallback((): PerformanceReport => {
    const metrics = metricsRef.current;
    const durations = metrics.filter((m) => m.duration !== undefined).map((m) => m.duration!);

    return {
      id: reportIdRef.current,
      startTime: startTimeRef.current,
      endTime: Date.now(),
      totalDuration: Date.now() - startTimeRef.current,
      metrics: [...metrics],
      averageDuration:
        durations.length > 0 ? durations.reduce((a, b) => a + b, 0) / durations.length : 0,
      maxDuration: durations.length > 0 ? Math.max(...durations) : 0,
      minDuration: durations.length > 0 ? Math.min(...durations) : 0,
      metricCount: metrics.length,
    };
  }, []);

  /**
   * Clear metrics
   */
  const clearMetrics = useCallback(() => {
    metricsRef.current = [];
    marksRef.current.clear();
    startTimeRef.current = Date.now();
    reportIdRef.current = `perf-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`;
  }, []);

  /**
   * Mark a performance point
   */
  const mark = useCallback((name: string) => {
    marksRef.current.set(name, performance.now());
  }, []);

  /**
   * Measure between marks
   */
  const measureBetweenMarks = useCallback(
    (name: string, startMark: string, endMark: string): number | null => {
      const startTime = marksRef.current.get(startMark);
      const endTime = marksRef.current.get(endMark);

      if (startTime === undefined || endTime === undefined) {
        return null;
      }

      const duration = endTime - startTime;

      addMetric({
        name,
        startTime,
        endTime,
        duration,
        metadata: { startMark, endMark },
      });

      return duration;
    },
    [addMetric],
  );

  return {
    startMetric,
    measure,
    measureAsync,
    getMetrics,
    getReport,
    clearMetrics,
    mark,
    measureBetweenMarks,
  };
}

/**
 * useRenderTiming hook
 *
 * Track component render timing.
 */
export function useRenderTiming(_componentName: string): RenderTiming {
  const renderCountRef = useRef(0);
  const renderStartRef = useRef(0);
  const durationsRef = useRef<number[]>([]);
  const [timing, setTiming] = useState<RenderTiming>({
    renderCount: 0,
    lastRenderTime: 0,
    averageRenderDuration: 0,
    maxRenderDuration: 0,
    renderDurations: [],
  });

  // Mark render start
  renderStartRef.current = performance.now();

  // Track render completion
  useEffect(() => {
    const duration = performance.now() - renderStartRef.current;
    renderCountRef.current++;

    durationsRef.current.push(duration);
    if (durationsRef.current.length > 50) {
      durationsRef.current.shift();
    }

    const durations = durationsRef.current;
    const average = durations.reduce((a, b) => a + b, 0) / durations.length;
    const max = Math.max(...durations);

    setTiming({
      renderCount: renderCountRef.current,
      lastRenderTime: duration,
      averageRenderDuration: average,
      maxRenderDuration: max,
      renderDurations: [...durations],
    });
  }, []);

  return timing;
}

/**
 * useMemoryUsage hook
 *
 * Track memory usage (if available).
 */
export function useMemoryUsage(intervalMs = 5000): {
  usedJSHeapSize: number | null;
  totalJSHeapSize: number | null;
  jsHeapSizeLimit: number | null;
} {
  const [memory, setMemory] = useState<{
    usedJSHeapSize: number | null;
    totalJSHeapSize: number | null;
    jsHeapSizeLimit: number | null;
  }>({
    usedJSHeapSize: null,
    totalJSHeapSize: null,
    jsHeapSizeLimit: null,
  });

  useEffect(() => {
    const perf = performance as Performance & {
      memory?: {
        usedJSHeapSize: number;
        totalJSHeapSize: number;
        jsHeapSizeLimit: number;
      };
    };

    if (!perf.memory) {
      return;
    }

    const updateMemory = () => {
      if (perf.memory) {
        setMemory({
          usedJSHeapSize: perf.memory.usedJSHeapSize,
          totalJSHeapSize: perf.memory.totalJSHeapSize,
          jsHeapSizeLimit: perf.memory.jsHeapSizeLimit,
        });
      }
    };

    updateMemory();
    const interval = setInterval(updateMemory, intervalMs);

    return () => clearInterval(interval);
  }, [intervalMs]);

  return memory;
}

/**
 * useFPS hook
 *
 * Track frames per second.
 */
export function useFPS(): number {
  const [fps, setFps] = useState(0);
  const framesRef = useRef(0);
  const lastTimeRef = useRef(performance.now());

  useEffect(() => {
    let animationId: number;

    const tick = () => {
      framesRef.current++;
      const now = performance.now();

      if (now - lastTimeRef.current >= 1000) {
        setFps(framesRef.current);
        framesRef.current = 0;
        lastTimeRef.current = now;
      }

      animationId = requestAnimationFrame(tick);
    };

    animationId = requestAnimationFrame(tick);

    return () => cancelAnimationFrame(animationId);
  }, []);

  return fps;
}

/**
 * useNetworkTiming hook
 *
 * Track network request timing.
 */
export function useNetworkTiming(): {
  entries: PerformanceResourceTiming[];
  getEntriesByType: (type: string) => PerformanceResourceTiming[];
  clearEntries: () => void;
} {
  const [entries, setEntries] = useState<PerformanceResourceTiming[]>([]);

  useEffect(() => {
    const observer = new PerformanceObserver((list) => {
      const newEntries = list.getEntries() as PerformanceResourceTiming[];
      setEntries((prev) => [...prev, ...newEntries].slice(-100));
    });

    observer.observe({ entryTypes: ['resource'] });

    return () => observer.disconnect();
  }, []);

  const getEntriesByType = useCallback(
    (type: string) => {
      return entries.filter((e) => e.initiatorType === type);
    },
    [entries],
  );

  const clearEntries = useCallback(() => {
    setEntries([]);
  }, []);

  return { entries, getEntriesByType, clearEntries };
}

export default usePerformance;
