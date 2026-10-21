import { beforeEach, describe, expect, it, vi } from 'vitest';

const getSessionMock = vi.fn();
const destroySessionMock = vi.fn();
const commitSessionMock = vi.fn();
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
      commitSession: commitSessionMock,
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
    commitSessionMock.mockResolvedValue('__session=updated');
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ code: '0', data: null }), {
          headers: { 'Content-Type': 'application/json' },
        }),
      ),
    );
  });

  it('preserves and restores the operator session around impersonation', async () => {
    const expires = Math.floor(Date.now() / 1000) + 3600;
    const encode = (value: object) => Buffer.from(JSON.stringify(value)).toString('base64url');
    const operatorToken = `${encode({ alg: 'none' })}.${encode({ exp: expires })}.sig`;
    const customerToken = `${encode({ alg: 'none' })}.${encode({ exp: expires - 1800 })}.sig`;
    const values = new Map<string, unknown>([
      ['jwtToken', operatorToken],
      ['tokenExpiry', String(expires)],
      ['remember', '1'],
    ]);
    const session = {
      get: vi.fn((key: string) => values.get(key)),
      set: vi.fn((key: string, value: unknown) => values.set(key, value)),
      unset: vi.fn((key: string) => values.delete(key)),
    };
    getSessionMock.mockResolvedValue(session);

    const { commitImpersonationSession, getImpersonationFromRequest, restoreOperatorSession } =
      await import('~/shared/services/session');
    const request = new Request('http://localhost/organization/members/customer', {
      headers: { Cookie: '__session=valid' },
    });
    const impersonation = {
      sessionPid: '01K5SESSION0000000000000000',
      targetUserPid: '01K5USER000000000000000000',
      targetMemberPid: '01K5MEMBER00000000000000',
      targetDisplayName: 'Customer Zhang',
      operatorDisplayName: 'Administrator Li',
      expiresAt: new Date((expires - 1800) * 1000).toISOString(),
      clientType: 'web',
    };

    await commitImpersonationSession(request, customerToken, impersonation);
    expect(values.get('jwtToken')).toBe(customerToken);
    expect(values.get('operatorJwtToken')).toBe(operatorToken);
    expect(await getImpersonationFromRequest(request)).toEqual(impersonation);

    await restoreOperatorSession(request);
    expect(values.get('jwtToken')).toBe(operatorToken);
    expect(values.get('remember')).toBe('1');
    expect(values.has('impersonation')).toBe(false);
    expect(values.has('operatorJwtToken')).toBe(false);
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
      expect.objectContaining({
        method: 'DELETE',
        signal: expect.any(AbortSignal),
        headers: expect.objectContaining({
          Authorization: 'Bearer header.payload.signature',
        }),
      }),
    );
    expect(validSession.unset).toHaveBeenCalledWith('jwtToken');
  });
  for (const failure of ['refused', 'network']) {
    it(`still clears the local session when exact backend revocation is ${failure}`, async () => {
      const session = {
        get: vi.fn((key: string) => (key === 'jwtToken' ? 'header.payload.signature' : undefined)),
        set: vi.fn(),
        unset: vi.fn(),
      };
      getSessionMock.mockResolvedValue(session);
      const fetchMock = vi.mocked(globalThis.fetch);
      if (failure === 'network') fetchMock.mockRejectedValueOnce(new Error('offline'));
      else
        fetchMock.mockResolvedValueOnce(
          new Response(JSON.stringify({ code: '401', data: null }), {
            status: 401,
            headers: { 'Content-Type': 'application/json' },
          }),
        );
      const { logout } = await import('~/shared/services/session');
      await logout(
        new Request('http://localhost/logout', { headers: { Cookie: '__session=valid' } }),
      );
      expect(session.unset).toHaveBeenCalledWith('jwtToken');
      expect(destroySessionMock).toHaveBeenCalled();
      expect(fetchMock).toHaveBeenCalledTimes(1);
      expect(fetchMock.mock.calls[0][0]).toBe('http://127.0.0.1:6443/api/user/sessions/current');
    });
  }
  it('keeps the explicit native backend and the three-second logout deadline', async () => {
    const session = {
      get: vi.fn((key: string) => (key === 'jwtToken' ? 'header.payload.signature' : undefined)),
      set: vi.fn(),
      unset: vi.fn(),
    };
    getSessionMock.mockResolvedValue(session);
    vi.stubEnv('SPRING_BOOT_URL', 'http://127.0.0.1:9999');
    const timeout = vi.spyOn(AbortSignal, 'timeout');
    try {
      const { logout } = await import('~/shared/services/session');
      await logout(
        new Request('http://admin.example.test/logout', { headers: { Cookie: '__session=valid' } }),
      );
      expect(vi.mocked(globalThis.fetch).mock.calls[0][0]).toBe(
        'http://127.0.0.1:9999/api/user/sessions/current',
      );
      expect(timeout).toHaveBeenCalledWith(3000);
    } finally {
      vi.unstubAllEnvs();
      timeout.mockRestore();
    }
  });
});
