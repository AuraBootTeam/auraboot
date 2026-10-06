import { beforeEach, describe, expect, it, vi } from 'vitest';
import { action } from '../api.end-impersonation';
import {
  getImpersonationFromRequest,
  getTokenFromRequest,
  restoreOperatorSession,
} from '~/shared/services/session';

vi.mock('~/shared/services/session', () => ({
  getImpersonationFromRequest: vi.fn(),
  getTokenFromRequest: vi.fn(),
  restoreOperatorSession: vi.fn(),
}));

const mockedGetImpersonation = vi.mocked(getImpersonationFromRequest);
const mockedGetToken = vi.mocked(getTokenFromRequest);
const mockedRestore = vi.mocked(restoreOperatorSession);

describe('end impersonation resource action', () => {
  beforeEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
    mockedGetToken.mockResolvedValue('customer-jwt');
    mockedGetImpersonation.mockResolvedValue({
      sessionPid: 'session-pid',
      targetUserPid: 'user-pid',
      targetMemberPid: 'member-pid',
      targetDisplayName: 'Customer Zhang',
      operatorDisplayName: 'Administrator Li',
      expiresAt: '2026-09-24T10:30:00Z',
      clientType: 'web',
    });
    mockedRestore.mockResolvedValue('__session=operator');
  });

  it('revokes the delegated session before restoring the operator cookie', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200 });
    vi.stubGlobal('fetch', fetchMock);
    const request = new Request('http://localhost/_action/end-impersonation', { method: 'POST' });

    const response = await action({ request, params: {}, context: {} } as any);

    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringMatching(/\/api\/impersonation-sessions\/current\/end$/),
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({ Authorization: 'Bearer customer-jwt' }),
      }),
    );
    expect(mockedRestore).toHaveBeenCalledWith(request);
    const redirected = response as unknown as {
      url: string;
      headers: Record<string, string>;
    };
    expect(redirected.headers['Set-Cookie']).toBe('__session=operator');
    expect(redirected.url).toBe('/');
  });

  it('keeps impersonation active when server-side revocation fails', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        status: 503,
        json: async () => ({ message: 'temporarily unavailable' }),
      }),
    );
    const request = new Request('http://localhost/_action/end-impersonation', { method: 'POST' });

    const response = await action({ request, params: {}, context: {} } as any);

    expect(response.status).toBe(503);
    expect(mockedRestore).not.toHaveBeenCalled();
  });
});
