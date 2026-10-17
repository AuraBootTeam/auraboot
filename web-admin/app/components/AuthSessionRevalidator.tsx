import { useEffect, useRef } from 'react';
import { useRevalidator } from 'react-router';

const DEFAULT_REVALIDATE_INTERVAL_MS = 60_000;
const DEFAULT_MIN_REVALIDATE_INTERVAL_MS = 15_000;

type RevalidatorState = 'idle' | 'loading' | 'submitting';

interface RevalidateDecision {
  enabled: boolean;
  isAuthenticated: boolean;
  revalidatorState: RevalidatorState;
  now: number;
  lastRevalidatedAt: number;
  minIntervalMs: number;
}

export function shouldRevalidateAuthSession({
  enabled,
  isAuthenticated,
  revalidatorState,
  now,
  lastRevalidatedAt,
  minIntervalMs,
}: RevalidateDecision): boolean {
  if (!enabled || !isAuthenticated) {
    return false;
  }
  if (revalidatorState !== 'idle') {
    return false;
  }
  return now - lastRevalidatedAt >= minIntervalMs;
}

interface AuthSessionRevalidatorProps {
  enabled: boolean;
  isAuthenticated: boolean;
  intervalMs?: number;
  minIntervalMs?: number;
  now?: () => number;
}

const defaultNow = () => Date.now();

/**
 * Keeps long-lived admin sessions aligned with backend permission changes.
 * Root loader revalidation refreshes `/api/auth/me` and menu data without adding UI.
 */
export function AuthSessionRevalidator({
  enabled,
  isAuthenticated,
  intervalMs = DEFAULT_REVALIDATE_INTERVAL_MS,
  minIntervalMs = DEFAULT_MIN_REVALIDATE_INTERVAL_MS,
  now = defaultNow,
}: AuthSessionRevalidatorProps) {
  const revalidator = useRevalidator();
  const lastRevalidatedAt = useRef(now());

  useEffect(() => {
    if (!enabled || !isAuthenticated || typeof window === 'undefined') {
      return undefined;
    }

    let disposed = false;
    let pendingProbe: AbortController | undefined;

    const attemptRevalidate = async (trigger: 'interval' | 'focus' | 'online' | 'visible') => {
      // Offline root-loader failures would dispose page-level reconnect subscriptions.
      if (window.navigator.onLine === false || pendingProbe) return;
      const currentTime = now();
      if (
        !shouldRevalidateAuthSession({
          enabled,
          isAuthenticated,
          revalidatorState: revalidator.state as RevalidatorState,
          now: currentTime,
          lastRevalidatedAt: lastRevalidatedAt.current,
          minIntervalMs,
        })
      ) {
        return;
      }
      lastRevalidatedAt.current = currentTime;
      // Browser connectivity does not imply the backend is reachable. Probe the
      // same authenticated BFF before background root refreshes: a 5xx would
      // otherwise replace a healthy live page with the root error boundary.
      // Authoritative auth rejection still goes through normal loader handling.
      const probe = new AbortController();
      pendingProbe = probe;
      const requestId = crypto.randomUUID();
      const started = performance.now();
      const logDeferred = (reason: string, status?: number) => {
        console.warn('[auth-refresh]', {
          requestId,
          trigger,
          reason,
          status,
          durationMs: Math.round(performance.now() - started),
          action: 'deferred',
        });
      };
      const deadline = window.setTimeout(() => probe.abort(), 10_000);
      try {
        const response = await fetch('/api/auth/me', {
          credentials: 'same-origin',
          headers: { 'X-Request-Id': requestId },
          signal: probe.signal,
        });
        if (!disposed && !probe.signal.aborted && response.status < 500) {
          console.debug('[auth-refresh]', {
            trigger,
            requestId,
            status: response.status,
            durationMs: Math.round(performance.now() - started),
            action: 'revalidate',
          });
          revalidator.revalidate();
        } else if (!disposed) {
          logDeferred('probe-unavailable', response.status);
        }
      } catch (error) {
        // Only transport failures defer this background refresh. Navigation,
        // configuration errors and non-network exceptions retain fail-fast behavior.
        if (!(error instanceof TypeError) && !probe.signal.aborted) throw error;
        if (!disposed) logDeferred(probe.signal.aborted ? 'probe-timeout' : 'probe-transport');
      } finally {
        window.clearTimeout(deadline);
        if (pendingProbe === probe) pendingProbe = undefined;
      }
    };

    const handleVisibilityChange = () => {
      if (document.visibilityState === 'visible') {
        void attemptRevalidate('visible');
      }
    };

    const onFocus = () => {
      void attemptRevalidate('focus');
    };
    const onOnline = () => {
      void attemptRevalidate('online');
    };
    window.addEventListener('focus', onFocus);
    window.addEventListener('online', onOnline);
    document.addEventListener('visibilitychange', handleVisibilityChange);
    const intervalId =
      intervalMs > 0
        ? window.setInterval(() => {
            void attemptRevalidate('interval');
          }, intervalMs)
        : undefined;

    return () => {
      disposed = true;
      pendingProbe?.abort();
      window.removeEventListener('focus', onFocus);
      window.removeEventListener('online', onOnline);
      document.removeEventListener('visibilitychange', handleVisibilityChange);
      if (intervalId !== undefined) {
        window.clearInterval(intervalId);
      }
    };
  }, [enabled, isAuthenticated, intervalMs, minIntervalMs, now, revalidator]);

  return null;
}
