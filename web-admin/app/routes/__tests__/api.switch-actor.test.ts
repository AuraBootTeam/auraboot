import { beforeEach, describe, expect, it, vi } from 'vitest';
import { action } from '../api.switch-actor';
import { createUserSession, getTokenFromRequest } from '~/shared/services/session';

vi.mock('~/shared/services/session', () => ({
  createUserSession: vi.fn(),
  getTokenFromRequest: vi.fn(),
}));

const mockedGetToken = vi.mocked(getTokenFromRequest);
const mockedCreateSession = vi.mocked(createUserSession);

function requestWithForm(values: Record<string, string>) {
  return new Request('http://localhost/_action/switch-actor', {
    method: 'POST',
    headers: { 'User-Agent': 'vitest' },
    body: new URLSearchParams(values),
  });
}

describe('actor switch resource action', () => {
  beforeEach(() => {
    vi.unstubAllGlobals();
    mockedGetToken.mockReset();
    mockedCreateSession.mockReset();
    mockedGetToken.mockResolvedValue('current-jwt');
    mockedCreateSession.mockImplementation(async ({ redirectTo }) =>
      new Response(null, { status: 302, headers: { Location: redirectTo } }),
    );
  });

  it('forwards the selected Party as a precision-safe string and installs replacement JWT', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ data: { jwt: 'replacement-jwt' } }),
    });
    vi.stubGlobal('fetch', fetchMock);
    const request = requestWithForm({
      partyId: '9007199254740993',
      redirectTo: '/orders',
    });

    const response = await action({ request, params: {}, context: {} } as any);

    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringMatching(/\/api\/actors\/switch$/),
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({ Authorization: 'Bearer current-jwt' }),
        body: JSON.stringify({ partyId: '9007199254740993' }),
      }),
    );
    expect(mockedCreateSession).toHaveBeenCalledWith({
      request,
      token: 'replacement-jwt',
      remember: false,
      redirectTo: '/orders',
    });
    expect(response.headers.get('Location')).toBe('/orders');
  });

  it('rejects malformed Party IDs without calling the backend', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    const response = await action({
      request: requestWithForm({ partyId: '1 OR 1=1', redirectTo: '/orders' }),
      params: {},
      context: {},
    } as any);

    expect(fetchMock).not.toHaveBeenCalled();
    expect(mockedCreateSession).not.toHaveBeenCalled();
    expect(response.url).toBe('/orders');
  });

  it('fails closed to local redirects and requires an authenticated session', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ data: { jwt: 'replacement-jwt' } }),
    });
    vi.stubGlobal('fetch', fetchMock);

    await action({
      request: requestWithForm({ partyId: '42', redirectTo: 'https://evil.example' }),
      params: {},
      context: {},
    } as any);
    expect(mockedCreateSession).toHaveBeenCalledWith(
      expect.objectContaining({ redirectTo: '/' }),
    );

    mockedGetToken.mockReset();
    mockedGetToken.mockResolvedValue(null);
    const response = await action({
      request: requestWithForm({ partyId: '42' }),
      params: {},
      context: {},
    } as any);
    expect(response.url).toBe('/login');
  });
});
