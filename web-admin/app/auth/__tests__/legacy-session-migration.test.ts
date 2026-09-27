import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  clearLegacyBrowserAuthStorage,
  migrateLegacyBrowserSession,
} from '../legacy-session-migration';

describe('legacy browser JWT migration', () => {
  beforeEach(() => {
    window.localStorage.clear();
    window.sessionStorage.clear();
  });

  it('exchanges a sessionStorage token once and removes every legacy auth key', async () => {
    window.sessionStorage.setItem('jwtToken', 'legacy-session-token');
    window.sessionStorage.setItem('refreshToken', 'legacy-refresh');
    window.localStorage.setItem('tokenExpiry', '123');
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));

    await expect(migrateLegacyBrowserSession(window, fetchMock)).resolves.toBe(true);

    expect(fetchMock).toHaveBeenCalledWith('/api/auth/session-migrate', {
      method: 'POST',
      credentials: 'same-origin',
      headers: {
        Authorization: 'Bearer legacy-session-token',
        'X-Aura-Remember': 'false',
      },
    });
    expect(window.sessionStorage.getItem('jwtToken')).toBeNull();
    expect(window.sessionStorage.getItem('refreshToken')).toBeNull();
    expect(window.localStorage.getItem('tokenExpiry')).toBeNull();
  });

  it('preserves a token on a transient failure but clears a rejected token', async () => {
    window.localStorage.setItem('jwtToken', 'legacy-local-token');
    const unavailable = vi.fn().mockRejectedValue(new TypeError('offline'));
    await expect(migrateLegacyBrowserSession(window, unavailable)).resolves.toBe(false);
    expect(window.localStorage.getItem('jwtToken')).toBe('legacy-local-token');

    const rejected = vi.fn().mockResolvedValue(new Response(null, { status: 401 }));
    await expect(migrateLegacyBrowserSession(window, rejected)).resolves.toBe(false);
    expect(window.localStorage.getItem('jwtToken')).toBeNull();
  });

  it('migrates and clears the older jwt alias used by direct-fetch call sites', async () => {
    window.localStorage.setItem('jwt', 'oldest-local-token');
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));

    await expect(migrateLegacyBrowserSession(window, fetchMock)).resolves.toBe(true);

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/auth/session-migrate',
      expect.objectContaining({
        headers: expect.objectContaining({ Authorization: 'Bearer oldest-local-token' }),
      }),
    );
    expect(window.localStorage.getItem('jwt')).toBeNull();
  });

  it('clears both legacy stores without touching unrelated preferences', () => {
    window.sessionStorage.setItem('jwtToken', 'token');
    window.localStorage.setItem('jwtToken', 'token');
    window.localStorage.setItem('jwt', 'old-token');
    window.localStorage.setItem('auth.remember', 'true');

    clearLegacyBrowserAuthStorage(window);

    expect(window.sessionStorage.getItem('jwtToken')).toBeNull();
    expect(window.localStorage.getItem('jwtToken')).toBeNull();
    expect(window.localStorage.getItem('jwt')).toBeNull();
    expect(window.localStorage.getItem('auth.remember')).toBe('true');
  });
});
