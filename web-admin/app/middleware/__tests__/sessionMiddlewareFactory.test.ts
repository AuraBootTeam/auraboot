import { beforeEach, describe, expect, it, vi } from 'vitest';

const getSessionMock = vi.fn();
const destroySessionMock = vi.fn();

vi.mock('react-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router')>();
  return {
    ...actual,
    redirect: vi.fn((url: string, init?: ResponseInit | number) =>
      typeof init === 'number' ? { url, status: init } : { url, ...init },
    ),
  };
});

vi.mock('~/shared/services/session.js', () => ({
  sessionStorage: {
    getSession: getSessionMock,
    destroySession: destroySessionMock,
  },
}));

describe('createSessionMiddleware', () => {
  beforeEach(() => {
    vi.resetModules();
    vi.clearAllMocks();
  });

  it('clears invalid cookies and continues on public routes', async () => {
    getSessionMock.mockResolvedValue({ get: vi.fn() });

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
    expect(response.headers.get('Set-Cookie')).toBeNull();
    expect(await response.text()).toBe('ok');
  });

  it('redirects protected routes to login when cookie payload is malformed', async () => {
    getSessionMock.mockResolvedValue({ get: vi.fn() });

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
    });
  });
});
