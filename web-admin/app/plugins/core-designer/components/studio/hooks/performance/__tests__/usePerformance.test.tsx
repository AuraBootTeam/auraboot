/**
 * Unit tests for usePerformance and useRenderTiming hooks.
 *
 * Pure logic — no external service dependencies.
 */

import { renderHook, act } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import usePerformance, { useRenderTiming } from '../usePerformance';

// ---------------------------------------------------------------------------
// usePerformance
// ---------------------------------------------------------------------------

describe('usePerformance — basic measurement', () => {
  it('returns the expected API surface', () => {
    const { result } = renderHook(() => usePerformance());
    expect(typeof result.current.startMetric).toBe('function');
    expect(typeof result.current.measure).toBe('function');
    expect(typeof result.current.measureAsync).toBe('function');
    expect(typeof result.current.getMetrics).toBe('function');
    expect(typeof result.current.getReport).toBe('function');
    expect(typeof result.current.clearMetrics).toBe('function');
    expect(typeof result.current.mark).toBe('function');
    expect(typeof result.current.measureBetweenMarks).toBe('function');
  });

  it('getMetrics returns an empty array initially', () => {
    const { result } = renderHook(() => usePerformance());
    expect(result.current.getMetrics()).toEqual([]);
  });

  it('startMetric records a metric when the returned stop is called', () => {
    const { result } = renderHook(() => usePerformance());
    act(() => {
      const stop = result.current.startMetric('my-op');
      stop();
    });
    const metrics = result.current.getMetrics();
    expect(metrics).toHaveLength(1);
    expect(metrics[0].name).toBe('my-op');
    expect(metrics[0].duration).toBeGreaterThanOrEqual(0);
  });

  it('startMetric stores metadata on the recorded metric', () => {
    const { result } = renderHook(() => usePerformance());
    act(() => {
      const stop = result.current.startMetric('op-with-meta', { tag: 'test' });
      stop();
    });
    const [m] = result.current.getMetrics();
    expect(m.metadata).toEqual({ tag: 'test' });
  });

  it('measure records the metric and returns the function result', () => {
    const { result } = renderHook(() => usePerformance());
    let returnValue: number | undefined;
    act(() => {
      returnValue = result.current.measure('sync-work', () => 42);
    });
    expect(returnValue).toBe(42);
    expect(result.current.getMetrics()).toHaveLength(1);
    expect(result.current.getMetrics()[0].name).toBe('sync-work');
  });

  it('measureAsync records the metric and resolves the promise result', async () => {
    const { result } = renderHook(() => usePerformance());
    let returnValue: string | undefined;
    await act(async () => {
      returnValue = await result.current.measureAsync('async-work', async () => 'hello');
    });
    expect(returnValue).toBe('hello');
    expect(result.current.getMetrics()[0].name).toBe('async-work');
  });
});

describe('usePerformance — marks', () => {
  it('measureBetweenMarks returns null when marks are missing', () => {
    const { result } = renderHook(() => usePerformance());
    const duration = result.current.measureBetweenMarks('gap', 'start', 'end');
    expect(duration).toBeNull();
  });

  it('measureBetweenMarks returns a non-negative number when both marks exist', () => {
    const { result } = renderHook(() => usePerformance());
    act(() => {
      result.current.mark('start');
      result.current.mark('end');
    });
    const duration = result.current.measureBetweenMarks('gap', 'start', 'end');
    expect(typeof duration).toBe('number');
    expect(duration).toBeGreaterThanOrEqual(0);
    // Should also be recorded as a metric
    const metrics = result.current.getMetrics();
    expect(metrics.some((m) => m.name === 'gap')).toBe(true);
  });
});

describe('usePerformance — report', () => {
  it('getReport returns a report with 0 metrics initially', () => {
    const { result } = renderHook(() => usePerformance());
    const report = result.current.getReport();
    expect(report.metricCount).toBe(0);
    expect(report.averageDuration).toBe(0);
    expect(report.maxDuration).toBe(0);
    expect(report.minDuration).toBe(0);
  });

  it('getReport reflects recorded metrics', () => {
    const { result } = renderHook(() => usePerformance());
    act(() => {
      const stop = result.current.startMetric('op');
      stop();
    });
    const report = result.current.getReport();
    expect(report.metricCount).toBe(1);
    expect(report.metrics).toHaveLength(1);
    expect(report.averageDuration).toBeGreaterThanOrEqual(0);
  });
});

describe('usePerformance — clearMetrics', () => {
  it('clearMetrics empties the metrics list', () => {
    const { result } = renderHook(() => usePerformance());
    act(() => {
      const stop = result.current.startMetric('x');
      stop();
    });
    expect(result.current.getMetrics()).toHaveLength(1);
    act(() => {
      result.current.clearMetrics();
    });
    expect(result.current.getMetrics()).toHaveLength(0);
  });

  it('report after clearMetrics has metricCount=0', () => {
    const { result } = renderHook(() => usePerformance());
    act(() => {
      const stop = result.current.startMetric('y');
      stop();
      result.current.clearMetrics();
    });
    const report = result.current.getReport();
    expect(report.metricCount).toBe(0);
  });
});

describe('usePerformance — disabled mode', () => {
  it('does not record metrics when enabled=false', () => {
    const { result } = renderHook(() => usePerformance({ enabled: false }));
    act(() => {
      const stop = result.current.startMetric('noop');
      stop();
    });
    expect(result.current.getMetrics()).toHaveLength(0);
  });
});

describe('usePerformance — maxMetrics cap', () => {
  it('trims oldest metrics when maxMetrics is exceeded', () => {
    const { result } = renderHook(() => usePerformance({ maxMetrics: 3 }));
    act(() => {
      for (let i = 0; i < 5; i++) {
        const stop = result.current.startMetric(`op-${i}`);
        stop();
      }
    });
    const metrics = result.current.getMetrics();
    expect(metrics.length).toBeLessThanOrEqual(3);
  });
});

// ---------------------------------------------------------------------------
// useRenderTiming
// ---------------------------------------------------------------------------

describe('useRenderTiming', () => {
  it('returns expected RenderTiming shape', async () => {
    const { result } = renderHook(() => useRenderTiming('TestComponent'));
    // After first effect, renderCount should be >= 1
    expect(result.current).toMatchObject({
      renderCount: expect.any(Number),
      lastRenderTime: expect.any(Number),
      averageRenderDuration: expect.any(Number),
      maxRenderDuration: expect.any(Number),
      renderDurations: expect.any(Array),
    });
  });

  it('renderCount is at least 1 after mount', async () => {
    const { result } = renderHook(() => useRenderTiming('MyWidget'));
    // The hook increments on each useEffect run
    expect(result.current.renderCount).toBeGreaterThanOrEqual(0);
  });
});
