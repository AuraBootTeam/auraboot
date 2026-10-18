import { useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { createPortal } from 'react-dom';
import type { RootLoaderData } from '~/root-data';
import { rootT, resolveErrorLocale } from '~/error/root-error-view';
import {
  authorizationReduced,
  SESSION_CONTEXT_UNAVAILABLE,
  validateSessionContext,
  type SessionContextSnapshot,
} from '~/shared/services/session-context';
import { createClientRequestId } from '~/shared/observability/routeRequestDiagnostics';
import { reportClientError } from '~/shared/observability/clientErrorReporter';

interface Props {
  enabled: boolean;
  source: RootLoaderData;
  snapshot: RootLoaderData;
  onRefresh: (snapshot: SessionContextSnapshot) => void;
  onRejected: (status: 401 | 403) => void;
  onError: (error: Error) => void;
  intervalMs?: number;
  minIntervalMs?: number;
}
const fullscreenElement = () =>
  typeof document === 'undefined' ? null : document.fullscreenElement;
const subscribeFullscreen = (notify: () => void) => {
  document.addEventListener('fullscreenchange', notify);
  return () => document.removeEventListener('fullscreenchange', notify);
};

/** Session-only polling: never revalidate routes, replay commands or replace business data. */
export function SessionMaintenance({
  enabled,
  source,
  snapshot,
  onRefresh,
  onRejected,
  onError,
  intervalMs = 60_000,
  minIntervalMs = 15_000,
}: Props) {
  const latest = useRef({ source, snapshot, onRefresh, onRejected, onError });
  latest.current = { source, snapshot, onRefresh, onRejected, onError };
  const [unavailable, setUnavailable] = useState(false);
  const portalTarget = useSyncExternalStore(subscribeFullscreen, fullscreenElement, () => null);
  useEffect(() => {
    if (!enabled || !source.user || source.impersonation) {
      setUnavailable(false);
      return;
    }
    let disposed = false,
      terminal = false,
      wasUnavailable = false;
    let lastAttempt = Date.now();
    let pending: AbortController | undefined;
    const defer = (requestId?: string, traceId?: string, stage?: string) => {
      if (!wasUnavailable && requestId)
        reportClientError({
          errorType: 'session-maintenance',
          kind: 'network',
          traceId,
          message: `[requestId=${requestId} stage=${stage || 'transport'}] Session maintenance unavailable`,
          pageUrl: window.location.href,
        });
      wasUnavailable = true;
      setUnavailable(true);
    };
    const attempt = async (reconnect = false) => {
      if (disposed || terminal || pending || document.visibilityState === 'hidden') return;
      if (navigator.onLine === false) {
        defer();
        return;
      }
      if (!(reconnect && wasUnavailable) && Date.now() - lastAttempt < minIntervalMs) return;
      lastAttempt = Date.now();
      const controller = new AbortController();
      pending = controller;
      const requestId = createClientRequestId();
      const deadline = window.setTimeout(() => controller.abort(), 10_000);
      try {
        const response = await fetch('/api/runtime/session-context', {
          credentials: 'same-origin',
          headers: { 'X-Request-Id': requestId },
          signal: controller.signal,
        });
        if (disposed || latest.current.source !== source || controller.signal.aborted) return;
        if (response.status === 401 || response.status === 403) {
          terminal = true;
          latest.current.onRejected(response.status);
          return;
        }
        const body = await response.json();
        if (disposed || latest.current.source !== source || controller.signal.aborted) return;
        if (response.status === 503 && body?.code === SESSION_CONTEXT_UNAVAILABLE) {
          defer(requestId, response.headers.get('x-trace-id') || undefined, body.stage);
          return;
        }
        if (!response.ok || String(body?.code) !== '0')
          throw new Error('Session context returned an unexpected response');
        const fresh = validateSessionContext(body.data);
        if (authorizationReduced(latest.current.snapshot, fresh)) {
          terminal = true;
          latest.current.onRejected(403);
          return;
        }
        latest.current.onRefresh(fresh);
        wasUnavailable = false;
        setUnavailable(false);
      } catch (error) {
        if (disposed || latest.current.source !== source) return;
        if (
          controller.signal.aborted ||
          (error instanceof TypeError && /fetch|network|load failed/i.test(error.message))
        )
          defer(requestId);
        else {
          terminal = true;
          latest.current.onError(
            error instanceof Error ? error : new Error('Session maintenance failed'),
          );
        }
      } finally {
        window.clearTimeout(deadline);
        if (pending === controller) pending = undefined;
      }
    };
    const onFocus = () => {
      void attempt();
    };
    const onOnline = () => {
      void attempt(true);
    };
    const onOffline = () => {
      defer();
    };
    const onVisible = () => {
      if (document.visibilityState === 'visible') void attempt(true);
    };
    window.addEventListener('focus', onFocus);
    window.addEventListener('online', onOnline);
    window.addEventListener('offline', onOffline);
    document.addEventListener('visibilitychange', onVisible);
    const interval = intervalMs > 0 ? window.setInterval(onFocus, intervalMs) : undefined;
    return () => {
      disposed = true;
      pending?.abort();
      if (interval !== undefined) window.clearInterval(interval);
      window.removeEventListener('focus', onFocus);
      window.removeEventListener('online', onOnline);
      window.removeEventListener('offline', onOffline);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [enabled, source, intervalMs, minIntervalMs]);
  if (!enabled || !snapshot.user || !unavailable || typeof document === 'undefined') return null;
  return createPortal(
    <div
      role="status"
      data-testid="background-refresh-unavailable"
      className="pointer-events-none fixed right-4 bottom-4 z-50 max-w-sm rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-900 shadow-sm"
    >
      {rootT('backgroundRefreshUnavailable', resolveErrorLocale(snapshot.locale))}
    </div>,
    portalTarget || document.body,
  );
}
