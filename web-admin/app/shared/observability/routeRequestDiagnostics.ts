/** Correlate failed loader requests even when no telemetry exporter is configured. */
type RouteFailure = {
  requestId: string;
  traceId?: string;
  status?: number;
  path: string;
  at: number;
};
let installed = false;
const failures: RouteFailure[] = [];

export function recentRouteFailure(pageUrl: string): RouteFailure | undefined {
  const path = new URL(pageUrl).pathname;
  for (let index = failures.length - 1; index >= 0; index--) {
    const failure = failures[index];
    if (failure.path === path && Date.now() - failure.at < 30_000) return failure;
  }
  return undefined;
}

export function installRouteRequestDiagnostics(): void {
  if (installed || typeof window === 'undefined') return;
  installed = true;
  const originalFetch = window.fetch.bind(window);
  window.fetch = async (input, init) => {
    const url = new URL(input instanceof Request ? input.url : String(input), window.location.href);
    if (url.origin !== window.location.origin || !url.pathname.endsWith('.data'))
      return originalFetch(input, init);
    const requestId = crypto.randomUUID();
    const headers = new Headers(
      init?.headers || (input instanceof Request ? input.headers : undefined),
    );
    headers.set('X-Request-Id', requestId);
    const remember = (status?: number, traceId?: string) => {
      failures.push({
        requestId,
        traceId,
        status,
        path: url.pathname.replace(/\.data$/, ''),
        at: Date.now(),
      });
      if (failures.length > 10) failures.shift();
    };
    try {
      const response = await originalFetch(input, { ...init, headers });
      if (response.status >= 400) {
        const traceId = response.headers.get('X-Trace-Id') || '';
        remember(
          response.status,
          /^[a-f0-9]{32}$/.test(traceId) && !/^0+$/.test(traceId) ? traceId : undefined,
        );
      }
      return response;
    } catch (error) {
      // Aborted navigation is expected and must not be associated with a later boundary.
      if (
        !((error instanceof Error || error instanceof DOMException) && error.name === 'AbortError')
      )
        remember();
      throw error;
    }
  };
}
