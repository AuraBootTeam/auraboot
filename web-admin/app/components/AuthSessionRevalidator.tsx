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

    const attemptRevalidate = () => {
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
      revalidator.revalidate();
    };

    const handleVisibilityChange = () => {
      if (document.visibilityState === 'visible') {
        attemptRevalidate();
      }
    };

    window.addEventListener('focus', attemptRevalidate);
    document.addEventListener('visibilitychange', handleVisibilityChange);
    const intervalId =
      intervalMs > 0 ? window.setInterval(attemptRevalidate, intervalMs) : undefined;

    return () => {
      window.removeEventListener('focus', attemptRevalidate);
      document.removeEventListener('visibilitychange', handleVisibilityChange);
      if (intervalId !== undefined) {
        window.clearInterval(intervalId);
      }
    };
  }, [enabled, isAuthenticated, intervalMs, minIntervalMs, now, revalidator]);

  return null;
}
