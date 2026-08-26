import { beforeEach, describe, expect, it, vi } from 'vitest';
import { post } from '~/shared/services/http-client';

const getSessionMock = vi.fn();
const commitSessionMock = vi.fn();

vi.mock('react-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router')>();
  return {
    ...actual,
    createCookieSessionStorage: vi.fn(() => ({
      getSession: getSessionMock,
      commitSession: commitSessionMock,
      destroySession: vi.fn(),
    })),
    redirect: vi.fn((url: string, init?: ResponseInit | number) =>
      typeof init === 'number' ? { url, status: init } : { url, ...init },
    ),
  };
});

vi.mock('~/shared/services/http-client', () => ({
  post: vi.fn(),
}));

function makeJwt(exp: number): string {
  const enc = (value: object) =>
    Buffer.from(JSON.stringify(value)).toString('base64url');
  return `${enc({ alg: 'HS256' })}.${enc({ exp })}.signature`;
}

function fakeSession(overrides: Record<string, string>) {
  return {
    get: vi.fn((key: string) => overrides[key]),
    set: vi.fn(),
    unset: vi.fn(),
  };
}

describe('session sliding renewal', () => {
  beforeEach(() => {
    vi.resetModules();
    vi.clearAllMocks();
    getSessionMock.mockReset();
    commitSessionMock.mockReset();
    vi.mocked(post).mockReset();
  });

  it('readJwtExp extracts the exp claim from a JWT payload', async () => {
    const { readJwtExp } = await import('~/shared/services/session');
    const exp = Math.floor(Date.now() / 1000) + 3600;
    expect(readJwtExp(makeJwt(exp))).toBe(exp);
    expect(readJwtExp('not-a-jwt')).toBeNull();
    expect(readJwtExp('header.payload')).toBeNull();
  });

  it('shouldAttemptRenewal only fires inside the threshold', async () => {
    const { shouldAttemptRenewal } = await import('~/shared/services/session');
    const now = 1_000_000;
    expect(shouldAttemptRenewal(now / 1000 + 3600, now, 12 * 3600 * 1000)).toBe(true);
    expect(shouldAttemptRenewal(now / 1000 + 13 * 3600, now, 12 * 3600 * 1000)).toBe(false);
    expect(shouldAttemptRenewal(null, now, 12 * 3600 * 1000)).toBe(false);
    expect(shouldAttemptRenewal(Number.NaN, now, 12 * 3600 * 1000)).toBe(false);
  });

  it('skips renewal when the session has no token', async () => {
    const session = fakeSession({});
    getSessionMock.mockResolvedValue(session);
    const { maybeRenewSession } = await import('~/shared/services/session');

    await expect(maybeRenewSession(new Request('http://localhost/'))).resolves.toEqual({
      renewed: false,
    });
    expect(post).not.toHaveBeenCalled();
  });

  it('skips renewal when the token is not inside the window', async () => {
    const exp = Math.floor(Date.now() / 1000) + 20 * 3600;
    const session = fakeSession({ jwtToken: makeJwt(exp), tokenExpiry: String(exp) });
    getSessionMock.mockResolvedValue(session);
    const { maybeRenewSession } = await import('~/shared/services/session');

    await expect(maybeRenewSession(new Request('http://localhost/'))).resolves.toEqual({
      renewed: false,
    });
    expect(post).not.toHaveBeenCalled();
  });

  it('renews inside the window and returns the updated cookie', async () => {
    const exp = Math.floor(Date.now() / 1000) + 3600;
    const oldJwt = makeJwt(exp);
    const newJwt = makeJwt(exp + 86400);
    const session = fakeSession({ jwtToken: oldJwt, tokenExpiry: String(exp), remember: '1' });
    getSessionMock.mockResolvedValue(session);
    commitSessionMock.mockResolvedValue('__session=renewed.cookie');
    vi.mocked(post).mockResolvedValue({
      code: '0',
      success: true,
      message: '',
      desc: '',
      data: { jwt: newJwt },
      context: null,
      httpStatus: 200,
    });
    const { maybeRenewSession } = await import('~/shared/services/session');

    const result = await maybeRenewSession(new Request('http://localhost/'));

    expect(result.renewed).toBe(true);
    expect(result.setCookie).toBe('__session=renewed.cookie');
    expect(post).toHaveBeenCalledWith(
      '/api/auth/renew',
      {},
      expect.objectContaining({ token: oldJwt, timeout: 10_000 }),
      expect.any(Request),
    );
    expect(session.set).toHaveBeenCalledWith('jwtToken', newJwt);
    expect(commitSessionMock).toHaveBeenCalledWith(session, { maxAge: 7 * 24 * 3600 });
  });

  it('keeps the current token when the renew call fails', async () => {
    const exp = Math.floor(Date.now() / 1000) + 3600;
    const oldJwt = makeJwt(exp);
    const session = fakeSession({ jwtToken: oldJwt, tokenExpiry: String(exp), remember: '1' });
    getSessionMock.mockResolvedValue(session);
    vi.mocked(post).mockResolvedValue({
      code: '401',
      success: false,
      message: 'rejected',
      desc: 'rejected',
      data: null,
      context: null,
      httpStatus: 401,
    });
    const { maybeRenewSession } = await import('~/shared/services/session');

    await expect(maybeRenewSession(new Request('http://localhost/'))).resolves.toEqual({
      renewed: false,
    });
    expect(session.set).not.toHaveBeenCalledWith('jwtToken', expect.anything());
  });
});
