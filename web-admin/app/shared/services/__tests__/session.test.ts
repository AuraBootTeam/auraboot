import { beforeEach, describe, expect, it, vi } from 'vitest';

const getSessionMock = vi.fn();
const destroySessionMock = vi.fn();
const emptySession = {
  get: vi.fn(),
  set: vi.fn(),
  unset: vi.fn(),
};

vi.mock('react-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router')>();
  return {
    ...actual,
    createCookieSessionStorage: vi.fn(() => ({
      getSession: getSessionMock,
      commitSession: vi.fn(),
      destroySession: destroySessionMock,
    })),
    redirect: vi.fn((url: string, init?: ResponseInit | number) =>
      typeof init === 'number' ? { url, status: init } : { url, ...init },
    ),
  };
});

describe('session recovery', () => {
  beforeEach(() => {
    vi.resetModules();
    vi.clearAllMocks();
    emptySession.get.mockReset();
    destroySessionMock.mockResolvedValue('__session=; Max-Age=0');
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true }));
  });

  it('returns the jwt token from the recovered session', async () => {
    const validSession = {
      get: vi.fn((key: string) => (key === 'jwtToken' ? 'header.payload.signature' : undefined)),
      set: vi.fn(),
      unset: vi.fn(),
    };
    getSessionMock.mockResolvedValue(validSession);

    const { getTokenFromRequest } = await import('~/shared/services/session');
    const token = await getTokenFromRequest(
      new Request('http://localhost/dashboard', {
        headers: {
          Cookie: '__session=valid',
        },
      }),
    );

    expect(token).toBe('header.payload.signature');
    expect(getSessionMock).toHaveBeenCalledWith('__session=valid');
  });

  it('revokes the backend session before clearing the BFF cookie on logout', async () => {
    const validSession = {
      get: vi.fn((key: string) => (key === 'jwtToken' ? 'header.payload.signature' : undefined)),
      set: vi.fn(),
      unset: vi.fn(),
    };
    getSessionMock.mockResolvedValue(validSession);

    const { logout } = await import('~/shared/services/session');
    await logout(
      new Request('http://localhost/logout', {
        headers: {
          Cookie: '__session=valid',
        },
      }),
    );

    expect(globalThis.fetch).toHaveBeenCalledWith(
      'http://127.0.0.1:6443/api/user/sessions/current',
      {
        method: 'DELETE',
        headers: {
          Authorization: 'Bearer header.payload.signature',
        },
      },
    );
    expect(validSession.unset).toHaveBeenCalledWith('jwtToken');
  });
});
