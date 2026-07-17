/**
 * Global front-end error reporter.
 *
 * Captures uncaught JS errors (window 'error') and unhandled promise rejections
 * and best-effort POSTs them to /api/client-errors, so front-end failures show up
 * in the in-app troubleshooting center (/ops/errors) instead of vanishing.
 *
 * Design constraints:
 * - Fire-and-forget: never throws, never blocks the app, swallows its own failures.
 * - Loop-safe: errors thrown by the reporter's own fetch are ignored (marked).
 * - Throttled + de-duplicated so a tight error loop can't spam the endpoint.
 * - Browser-only; a no-op on the server.
 */

const ENDPOINT = '/api/client-errors';
const MAX_REPORTS_PER_SESSION = 50;
const DEDUP_WINDOW_MS = 10_000;

let installed = false;
let reportCount = 0;
const recentSignatures = new Map<string, number>();

function sessionId(): string {
  try {
    const KEY = 'ab_client_session_id';
    let sid = sessionStorage.getItem(KEY);
    if (!sid) {
      sid = `${Date.now().toString(36)}-${crypto.getRandomValues(new Uint32Array(1))[0].toString(36)}`;
      sessionStorage.setItem(KEY, sid);
    }
    return sid;
  } catch {
    return 'unknown';
  }
}

function shouldReport(signature: string, now: number): boolean {
  if (reportCount >= MAX_REPORTS_PER_SESSION) return false;
  const last = recentSignatures.get(signature);
  if (last != null && now - last < DEDUP_WINDOW_MS) return false;
  recentSignatures.set(signature, now);
  return true;
}

function send(payload: Record<string, unknown>): void {
  reportCount += 1;
  try {
    // Raw fetch (not the app http-client) to stay decoupled and avoid interceptors.
    // keepalive lets the report survive a page unload triggered by the error.
    void fetch(ENDPOINT, {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', 'X-Client-Error-Report': '1' },
      body: JSON.stringify(payload),
      keepalive: true,
    }).catch(() => {
      /* fire-and-forget: reporting must never surface its own failure */
    });
  } catch {
    /* ignore */
  }
}

export function installClientErrorReporter(): void {
  if (installed || typeof window === 'undefined') return;
  installed = true;

  window.addEventListener('error', (event: ErrorEvent) => {
    const message = event.message || event.error?.message || 'Unknown error';
    // Ignore ResourceLoadingError-style events with no useful message (e.g. <img> 404s).
    if (!event.error && !event.message) return;
    const stack = event.error?.stack;
    const now = Date.now();
    const signature = `error:${message}:${event.filename}:${event.lineno}`;
    if (!shouldReport(signature, now)) return;
    send({
      errorType: 'error',
      message,
      stack,
      pageUrl: window.location?.href,
      userAgent: navigator.userAgent,
      sessionId: sessionId(),
      clientTimestamp: new Date(now).toISOString(),
    });
  });

  window.addEventListener('unhandledrejection', (event: PromiseRejectionEvent) => {
    const reason = event.reason;
    const message =
      reason instanceof Error ? reason.message : typeof reason === 'string' ? reason : 'Unhandled rejection';
    const stack = reason instanceof Error ? reason.stack : undefined;
    const now = Date.now();
    const signature = `rejection:${message}`;
    if (!shouldReport(signature, now)) return;
    send({
      errorType: 'unhandledrejection',
      message,
      stack,
      pageUrl: window.location?.href,
      userAgent: navigator.userAgent,
      sessionId: sessionId(),
      clientTimestamp: new Date(now).toISOString(),
    });
  });
}
