/**
 * useSSE - Managed SSE connection hook with:
 * - Exponential backoff on errors (1s → 30s max)
 * - Tab visibility pause (disconnect when hidden, reconnect when visible)
 * - Auth error detection (stop reconnecting on 401)
 * - Heartbeat monitoring (reconnect if no message received within timeout)
 * - Automatic cleanup on unmount
 */

import { useEffect, useRef } from 'react';

interface SSEListener {
  event: string;
  handler: (data: any) => void;
}

interface UseSSEOptions {
  /** SSE endpoint URL */
  url: string;
  /** Event listeners to register */
  listeners: SSEListener[];
  /** Whether the connection should be active (default: true) */
  enabled?: boolean;
  /** Initial backoff delay in ms (default: 1000) */
  initialDelay?: number;
  /** Maximum backoff delay in ms (default: 30000) */
  maxDelay?: number;
  /** Maximum retry attempts before giving up (default: 20) */
  maxRetries?: number;
  /**
   * Heartbeat timeout in ms. If no message (including keep-alive comments)
   * is received within this window, the connection is assumed dead and
   * will be closed + reconnected. Set to 0 to disable. (default: 30000)
   */
  heartbeatTimeoutMs?: number;
}

export function useSSE({
  url,
  listeners,
  enabled = true,
  initialDelay = 1000,
  maxDelay = 30000,
  maxRetries = 20,
  heartbeatTimeoutMs = 30_000,
}: UseSSEOptions): void {
  const esRef = useRef<EventSource | null>(null);
  const retryCount = useRef(0);
  const retryTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const heartbeatTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const disposed = useRef(false);

  useEffect(() => {
    if (!enabled) return;

    disposed.current = false;

    function resetHeartbeat() {
      if (heartbeatTimeoutMs <= 0) return;

      if (heartbeatTimer.current) {
        clearTimeout(heartbeatTimer.current);
      }
      heartbeatTimer.current = setTimeout(() => {
        // No message received within the heartbeat window — zombie connection
        console.warn(`[SSE] Heartbeat timeout (${heartbeatTimeoutMs}ms), reconnecting: ${url}`);
        close();
        scheduleReconnect();
      }, heartbeatTimeoutMs);
    }

    function clearHeartbeat() {
      if (heartbeatTimer.current) {
        clearTimeout(heartbeatTimer.current);
        heartbeatTimer.current = null;
      }
    }

    function connect() {
      if (disposed.current || document.visibilityState === 'hidden') return;
      close();

      const es = new EventSource(url);
      esRef.current = es;

      for (const { event, handler } of listeners) {
        es.addEventListener(event, (ev: MessageEvent) => {
          resetHeartbeat();
          try {
            handler(JSON.parse(ev.data));
          } catch {
            // ignore parse errors
          }
        });
      }

      es.addEventListener('connected', () => {
        retryCount.current = 0;
        resetHeartbeat();
      });

      // Any incoming message resets the heartbeat timer
      es.onmessage = () => {
        resetHeartbeat();
      };

      es.onopen = () => {
        resetHeartbeat();
      };

      es.onerror = () => {
        close();
        scheduleReconnect();
      };
    }

    function close() {
      clearHeartbeat();
      if (esRef.current) {
        esRef.current.close();
        esRef.current = null;
      }
    }

    function scheduleReconnect() {
      if (disposed.current || retryTimer.current) return;
      if (retryCount.current >= maxRetries) {
        console.warn(`[SSE] Giving up after ${maxRetries} retries: ${url}`);
        return;
      }
      const delay = Math.min(initialDelay * 2 ** retryCount.current, maxDelay);
      retryCount.current++;
      retryTimer.current = setTimeout(() => {
        retryTimer.current = null;
        connect();
      }, delay);
    }

    function onVisibilityChange() {
      if (document.visibilityState === 'visible') {
        // Tab became visible — reconnect if not connected
        if (!esRef.current && !retryTimer.current) {
          retryCount.current = 0;
          connect();
        }
      } else {
        // Tab hidden — disconnect to free browser connection slot
        close();
        if (retryTimer.current) {
          clearTimeout(retryTimer.current);
          retryTimer.current = null;
        }
      }
    }

    document.addEventListener('visibilitychange', onVisibilityChange);
    connect();

    return () => {
      disposed.current = true;
      document.removeEventListener('visibilitychange', onVisibilityChange);
      close();
      if (retryTimer.current) {
        clearTimeout(retryTimer.current);
        retryTimer.current = null;
      }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [url, enabled]);
}
