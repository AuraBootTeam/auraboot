import { beforeEach, describe, expect, it, vi } from 'vitest';

const getSessionWithRecoveryMock = vi.fn();
const destroySessionMock = vi.fn();

vi.mock('react-router', () => ({
  redirect: vi.fn((url: string, init?: ResponseInit) => ({ url, ...init })),
}));

vi.mock('~/services/session.js', () => ({
  getSessionWithRecovery: getSessionWithRecoveryMock,
  sessionStorage: {
    destroySession: destroySessionMock,
  },
}));

describe('createSessionMiddleware', () => {
  beforeEach(() => {
    vi.resetModules();
    vi.clearAllMocks();
  });

  it('clears invalid cookies and continues on public routes', async () => {
    getSessionWithRecoveryMock.mockResolvedValue({
      session: { get: vi.fn() },
      hasInvalidCookie: true,
    });
    destroySessionMock.mockResolvedValue('mock-cleared-cookie');

    const { createSessionMiddleware } = await import('~/middleware/sessionMiddlewareFactory');
    const middleware = createSessionMiddleware();
    const next = async () => new Response('ok', { status: 200 });

    const response = (await middleware(
      {
        request: new Request('http://localhost/login', {
          headers: {
            Cookie: '__session=broken',
          },
        }),
      } as any,
      next,
    )) as Response;

    expect(response.status).toBe(200);
    expect(response.headers.get('Set-Cookie')).toBe('mock-cleared-cookie');
    expect(await response.text()).toBe('ok');
  });

  it('redirects protected routes to login when cookie payload is malformed', async () => {
    getSessionWithRecoveryMock.mockResolvedValue({
      session: { get: vi.fn() },
      hasInvalidCookie: true,
    });
    destroySessionMock.mockResolvedValue('mock-cleared-cookie');

    const { createSessionMiddleware } = await import('~/middleware/sessionMiddlewareFactory');
    const middleware = createSessionMiddleware();

    await expect(
      middleware(
        {
          request: new Request('http://localhost/dashboard', {
            headers: {
              Cookie: '__session=broken',
            },
          }),
        } as any,
        async () => new Response('should not reach next', { status: 200 }),
      ),
    ).rejects.toMatchObject({
      url: '/login?redirectTo=%2Fdashboard',
      status: 302,
      headers: {
        'Set-Cookie': 'mock-cleared-cookie',
      },
    });
  });
});
