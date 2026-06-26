import { beforeEach, describe, expect, it, vi } from 'vitest';

const getSessionMock = vi.fn();
const destroySessionMock = vi.fn();

vi.mock('~/shared/services/session', () => ({
  sessionStorage: {
    getSession: getSessionMock,
    destroySession: destroySessionMock,
  },
}));

describe('handleLogoutPost', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true }));
    destroySessionMock.mockResolvedValue('__session=; Max-Age=0; Path=/');
  });

  it('revokes the backend session and redirects with a destroyed BFF cookie', async () => {
    const session = {
      get: vi.fn((key: string) => (key === 'jwtToken' ? 'header.payload.signature' : undefined)),
      unset: vi.fn(),
    };
    getSessionMock.mockResolvedValue(session);
    const req = { headers: { cookie: '__session=valid' } } as any;
    const res = {
      setHeader: vi.fn(),
      redirect: vi.fn(),
    } as any;
    const next = vi.fn();

    const { handleLogoutPost } = await import('../logout.server');
    await handleLogoutPost('http://backend:6443')(req, res, next);

    expect(globalThis.fetch).toHaveBeenCalledWith('http://backend:6443/api/user/sessions/current', {
      method: 'DELETE',
      headers: {
        Authorization: 'Bearer header.payload.signature',
      },
    });
    expect(session.unset).toHaveBeenCalledWith('jwtToken');
    expect(res.setHeader).toHaveBeenCalledWith('Set-Cookie', '__session=; Max-Age=0; Path=/');
    expect(res.redirect).toHaveBeenCalledWith(302, '/login');
    expect(next).not.toHaveBeenCalled();
  });
});
