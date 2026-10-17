import { afterEach, describe, expect, it, vi } from 'vitest';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  vi.resetModules();
});
describe('route request diagnostics', () => {
  it('persists the exact failed route request in the searchable browser error report', async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(
        new Response('failed', {
          status: 500,
          headers: {
            'X-Trace-Id': '0123456789abcdef0123456789abcdef',
          },
        }),
      )
      .mockResolvedValue(new Response('{}'));
    vi.stubGlobal('fetch', fetch);
    const { installRouteRequestDiagnostics } = await import('../routeRequestDiagnostics');
    installRouteRequestDiagnostics();
    await window.fetch('/xy/display.data');
    const requestId = (fetch.mock.calls[0][1].headers as Headers).get('X-Request-Id');
    expect(requestId).toMatch(/^[a-f0-9-]{36}$/);
    const { reportClientError } = await import('../clientErrorReporter');
    reportClientError({
      errorType: 'boundary',
      errorId: 'ERR-TEST1234',
      kind: 'unknown',
      message: 'Unexpected Server Error',
      pageUrl: new URL('/xy/display', location.href).href,
    });
    const payload = JSON.parse(fetch.mock.calls[1][1].body);
    expect(payload.traceId).toBe('0123456789abcdef0123456789abcdef');
    expect(payload.message).toContain(`requestId=${requestId} status=500`);
  });
  it('does not forward diagnostic headers to other origins or associate aborted navigation', async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(new Response('{}'))
      .mockRejectedValueOnce(new DOMException('navigation aborted', 'AbortError'));
    vi.stubGlobal('fetch', fetch);
    const { installRouteRequestDiagnostics, recentRouteFailure } =
      await import('../routeRequestDiagnostics');
    installRouteRequestDiagnostics();
    await window.fetch('https://external.invalid/private.data');
    expect(fetch.mock.calls[0][1]).toBeUndefined();
    await expect(window.fetch('/xy/display.data')).rejects.toThrow('navigation aborted');
    expect(recentRouteFailure(new URL('/xy/display', location.href).href)).toBeUndefined();
  });
});
