import { describe, expect, it, vi } from 'vitest';
const log = vi.hoisted(() => ({ error: vi.fn(), debug: vi.fn() }));
vi.mock('~/server/utils/logger', () => ({ default: log }));
import {
  createLoaderDiagnostics,
  requestCorrelation,
  safeLoaderError,
  isRootDependencyUnavailable,
  RootDependencyUnavailableError,
} from '../loaderDiagnostics.server';

describe('loader diagnostics', () => {
  it('correlates the failing stage and rethrows the original error', async () => {
    const request = new Request('http://local/xy/display?token=private', {
      headers: {
        'x-request-id': 'route-123',
        traceparent: '00-0123456789abcdef0123456789abcdef-0123456789abcdef-01',
      },
    });
    const error = new TypeError('fetch failed', {
      cause: Object.assign(new Error('connection reset'), { code: 'ECONNRESET' }),
    });
    await expect(
      createLoaderDiagnostics(request).stage('branding', async () => {
        throw error;
      }),
    ).rejects.toBe(error);
    expect(log.error).toHaveBeenLastCalledWith(
      expect.objectContaining({
        requestId: 'route-123',
        traceId: '0123456789abcdef0123456789abcdef',
        path: '/xy/display',
        stage: 'branding',
        outcome: 'failure',
        exception: expect.objectContaining({
          name: 'TypeError',
          cause: expect.objectContaining({ code: 'ECONNRESET' }),
        }),
      }),
      'Root loader stage failed',
    );
  });
  it('does not turn redirects into server failures', async () => {
    log.error.mockClear();
    const response = new Response(null, { status: 302, headers: { Location: '/login' } });
    await expect(
      createLoaderDiagnostics(new Request('http://local/xy/display')).stage('auth', async () => {
        throw response;
      }),
    ).rejects.toBe(response);
    expect(log.error).not.toHaveBeenCalled();
  });
  it('excludes credentials, URL queries, request config and response bodies', () => {
    const error = Object.assign(
      new Error('Bearer private-token token=private https://local/api?password=private'),
      {
        config: { password: 'private' },
        response: { data: 'private' },
      },
    );
    expect(JSON.stringify(safeLoaderError(error))).not.toContain('private');
    expect(
      requestCorrelation(
        new Request('http://local/xy/display', { headers: { 'x-request-id': 'bad id' } }),
      ).requestId,
    ).toMatch(/^[a-f0-9-]{36}$/);
  });
});

describe('root dependency classification', () => {
  it('identifies timeouts and transport failures without masking invalid configuration', () => {
    expect(isRootDependencyUnavailable(new DOMException('timed out', 'TimeoutError'))).toBe(true);
    expect(isRootDependencyUnavailable(new TypeError('fetch failed'))).toBe(true);
    expect(isRootDependencyUnavailable(new RootDependencyUnavailableError('branding', 503))).toBe(
      true,
    );
    expect(
      isRootDependencyUnavailable(
        Object.assign(new Error('unavailable'), {
          name: 'UserInfoUnavailableError',
          code: 'network_error',
        }),
      ),
    ).toBe(true);
    expect(isRootDependencyUnavailable(new Error('Branding contract missing'))).toBe(false);
    expect(isRootDependencyUnavailable(new TypeError('Cannot read properties of undefined'))).toBe(
      false,
    );
  });
});
