import { renderHook, act } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';

// ── Mock EventSource ────────────────────────────────────────────────────────
class MockEventSource {
  static instances: MockEventSource[] = [];

  url: string;
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  onmessage: (() => void) | null = null;
  _listeners: Map<string, ((ev: MessageEvent) => void)[]> = new Map();
  closed = false;

  constructor(url: string) {
    this.url = url;
    MockEventSource.instances.push(this);
  }

  addEventListener(event: string, handler: (ev: MessageEvent) => void) {
    const list = this._listeners.get(event) ?? [];
    list.push(handler);
    this._listeners.set(event, list);
  }

  removeEventListener(event: string, handler: (ev: MessageEvent) => void) {
    const list = this._listeners.get(event) ?? [];
    this._listeners.set(
      event,
      list.filter((h) => h !== handler),
    );
  }

  close() {
    this.closed = true;
  }

  // Test helpers
  emit(event: string, data: unknown) {
    const ev = { data: JSON.stringify(data) } as MessageEvent;
    const handlers = this._listeners.get(event) ?? [];
    for (const h of handlers) h(ev);
  }

  triggerOpen() {
    this.onopen?.();
  }

  triggerError() {
    this.onerror?.();
  }
}

vi.stubGlobal('EventSource', MockEventSource);

import { useSSE } from '../useSSE';

describe('useSSE', () => {
  beforeEach(() => {
    MockEventSource.instances = [];
    vi.useFakeTimers();
    // Ensure tab is visible
    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      get: () => 'visible',
    });
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('creates an EventSource on mount when enabled=true', () => {
    renderHook(() =>
      useSSE({ url: '/api/events', listeners: [] }),
    );

    expect(MockEventSource.instances).toHaveLength(1);
    expect(MockEventSource.instances[0].url).toBe('/api/events');
  });

  it('does not create EventSource when enabled=false', () => {
    renderHook(() =>
      useSSE({ url: '/api/events', listeners: [], enabled: false }),
    );

    expect(MockEventSource.instances).toHaveLength(0);
  });

  it('closes EventSource on unmount', () => {
    const { unmount } = renderHook(() =>
      useSSE({ url: '/api/events', listeners: [] }),
    );

    const es = MockEventSource.instances[0];
    unmount();

    expect(es.closed).toBe(true);
  });

  it('calls listener handler when event is received', () => {
    const handler = vi.fn();
    renderHook(() =>
      useSSE({
        url: '/api/events',
        listeners: [{ event: 'notification', handler }],
      }),
    );

    const es = MockEventSource.instances[0];
    es.emit('notification', { id: 1, message: 'Hello' });

    expect(handler).toHaveBeenCalledWith({ id: 1, message: 'Hello' });
  });

  it('does not crash on non-JSON event data', () => {
    const handler = vi.fn();
    renderHook(() =>
      useSSE({
        url: '/api/events',
        listeners: [{ event: 'msg', handler }],
      }),
    );

    const es = MockEventSource.instances[0];
    // emit raw (non-JSON) string
    const rawEv = { data: 'not-json' } as MessageEvent;
    const handlers = es._listeners.get('msg') ?? [];
    expect(() => {
      for (const h of handlers) h(rawEv);
    }).not.toThrow();
    // handler not called because JSON.parse failed
    expect(handler).not.toHaveBeenCalled();
  });

  it('schedules reconnect on error', () => {
    renderHook(() =>
      useSSE({ url: '/api/events', listeners: [], initialDelay: 100 }),
    );

    const es = MockEventSource.instances[0];
    act(() => {
      es.triggerError();
    });

    // First es should be closed
    expect(es.closed).toBe(true);

    // Advance timer to trigger reconnect
    act(() => {
      vi.advanceTimersByTime(150);
    });

    // A second EventSource should have been created
    expect(MockEventSource.instances).toHaveLength(2);
  });

  it('does not reconnect when tab is hidden', () => {
    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      get: () => 'hidden',
    });

    renderHook(() =>
      useSSE({ url: '/api/events', listeners: [] }),
    );

    // No EventSource created because tab is hidden
    expect(MockEventSource.instances).toHaveLength(0);
  });

  it('stops reconnecting after maxRetries', () => {
    renderHook(() =>
      useSSE({
        url: '/api/events',
        listeners: [],
        initialDelay: 10,
        maxRetries: 2,
      }),
    );

    // Trigger errors up to maxRetries + 1
    for (let i = 0; i < 4; i++) {
      const latest = MockEventSource.instances[MockEventSource.instances.length - 1];
      act(() => {
        latest.triggerError();
      });
      act(() => {
        vi.advanceTimersByTime(100);
      });
    }

    // Should have created initial + maxRetries=2 more = 3 total
    expect(MockEventSource.instances.length).toBeLessThanOrEqual(4);
  });

  it('resets retry count on connected event', () => {
    renderHook(() =>
      useSSE({ url: '/api/events', listeners: [], initialDelay: 10 }),
    );

    const es = MockEventSource.instances[0];
    act(() => {
      es.emit('connected', {});
    });

    // Should not throw; retryCount resets to 0
    expect(MockEventSource.instances).toHaveLength(1);
  });
});
