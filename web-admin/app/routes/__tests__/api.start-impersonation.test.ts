import { beforeEach, describe, expect, it, vi } from 'vitest';
import { action } from '../api.start-impersonation';
import { commitImpersonationSession, getTokenFromRequest } from '~/shared/services/session';

vi.mock('~/shared/services/session', () => ({
  commitImpersonationSession: vi.fn(),
  getTokenFromRequest: vi.fn(),
}));

const mockedGetToken = vi.mocked(getTokenFromRequest);
const mockedCommit = vi.mocked(commitImpersonationSession);

function requestWithForm(values: Record<string, string>) {
  return new Request('http://localhost/_action/start-impersonation', {
    method: 'POST',
    headers: { 'User-Agent': 'vitest' },
    body: new URLSearchParams(values),
  });
}

describe('start impersonation resource action', () => {
  beforeEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
    mockedGetToken.mockResolvedValue('operator-jwt');
    mockedCommit.mockResolvedValue('__session=impersonation');
  });

  it('records authorization context and installs the customer JWT', async () => {
    const data = {
      jwt: 'customer-jwt',
      sessionPid: 'session-pid',
      targetUserPid: 'user-pid',
      targetMemberPid: 'member-pid',
      targetDisplayName: 'Customer Zhang',
      operatorDisplayName: 'Administrator Li',
      expiresAt: '2026-09-24T10:30:00Z',
      clientType: 'web',
    };
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ code: '0', data }),
    });
    vi.stubGlobal('fetch', fetchMock);
    const request = requestWithForm({
      targetMemberPid: 'member-pid',
      authorizationMethod: 'offline',
      reason: 'Assist with order review',
      reference: 'Store visit 2026-09-24',
    });

    const response = await action({ request, params: {}, context: {} } as any);

    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringMatching(/\/api\/impersonation-sessions$/),
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({ Authorization: 'Bearer operator-jwt' }),
        body: JSON.stringify({
          targetMemberPid: 'member-pid',
          authorizationMethod: 'offline',
          reason: 'Assist with order review',
          reference: 'Store visit 2026-09-24',
          clientType: 'web',
        }),
      }),
    );
    expect(mockedCommit).toHaveBeenCalledWith(request, 'customer-jwt', {
      sessionPid: 'session-pid',
      targetUserPid: 'user-pid',
      targetMemberPid: 'member-pid',
      targetDisplayName: 'Customer Zhang',
      operatorDisplayName: 'Administrator Li',
      expiresAt: '2026-09-24T10:30:00Z',
      clientType: 'web',
    });
    expect(response.headers.get('Set-Cookie')).toBe('__session=impersonation');
    expect(await response.json()).toEqual(expect.objectContaining({ ok: true }));
  });

  it('rejects missing audit context without calling the backend', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    const response = await action({
      request: requestWithForm({ targetMemberPid: 'member-pid', authorizationMethod: 'offline' }),
      params: {},
      context: {},
    } as any);

    expect(response.status).toBe(400);
    expect(fetchMock).not.toHaveBeenCalled();
    expect(mockedCommit).not.toHaveBeenCalled();
  });
});
