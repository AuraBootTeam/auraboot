import { beforeEach, describe, expect, it, vi } from 'vitest';

const getSessionMock = vi.fn();
const emptySession = {
  get: vi.fn(),
  set: vi.fn(),
  unset: vi.fn(),
};

vi.mock('react-router', () => ({
  createCookieSessionStorage: vi.fn(() => ({
    getSession: getSessionMock,
    commitSession: vi.fn(),
    destroySession: vi.fn(),
  })),
  redirect: vi.fn((url: string, init?: ResponseInit) => ({ url, ...init })),
}));

describe('session recovery', () => {
  beforeEach(() => {
    vi.resetModules();
    vi.clearAllMocks();
    emptySession.get.mockReset();
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
});
