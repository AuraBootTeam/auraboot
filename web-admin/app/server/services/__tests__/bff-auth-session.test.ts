import { describe, it, expect, vi } from 'vitest';
import type { APIRequestContext } from '@playwright/test';
import { authenticateBffSession } from '../../../../tests/helpers/bff-auth-session';

function fixture(
  options: {
    loginStatus?: number;
    cookie?: boolean;
    spaces?: object[];
    selectStatus?: number;
    identityStatus?: number;
  } = {},
) {
  const response = (status: number, data?: unknown, cookie = false) => ({
    status: () => status,
    headers: () =>
      cookie ? { 'set-cookie': '__session=opaque-server-cookie; HttpOnly; Path=/' } : {},
    json: async () => ({ code: '0', data }),
  });
  const post = vi
    .fn()
    .mockResolvedValueOnce(
      response(options.loginStatus ?? 302, undefined, options.cookie !== false),
    )
    .mockResolvedValueOnce(response(options.selectStatus ?? 302, undefined, true));
  const get = vi
    .fn()
    .mockResolvedValueOnce(
      response(200, options.spaces ?? [{ tenantId: '370251641229807616', spaceType: 'business' }]),
    )
    .mockResolvedValueOnce(
      response(options.identityStatus ?? 200, {
        user: { pid: 'owned-user', tenantId: '370251641229807616' },
      }),
    );
  return { post, get, request: { post, get } as unknown as APIRequestContext };
}

describe('server-issued BFF auth setup', () => {
  it('uses login and the real selection action, preserving the full tenant identity', async () => {
    const { request, post, get } = fixture();
    expect(
      await authenticateBffSession(
        request,
        'http://bff.invalid:5381',
        'owned-user',
        'fixture-password',
      ),
    ).toEqual({
      tenantId: '370251641229807616',
      userPid: 'owned-user',
    });
    expect(post).toHaveBeenNthCalledWith(1, 'http://bff.invalid:5381/login', {
      form: {
        identifier: 'owned-user',
        password: 'fixture-password',
        channelCode: 'email_password',
        remember: 'on',
        redirectTo: '/',
      },
      headers: { Origin: 'http://bff.invalid:5381' },
      maxRedirects: 0,
    });
    expect(post).toHaveBeenNthCalledWith(2, 'http://bff.invalid:5381/tenant-selection', {
      form: { action: 'select', tenantId: '370251641229807616' },
      headers: { Origin: 'http://bff.invalid:5381' },
      maxRedirects: 0,
    });
    expect(get).toHaveBeenLastCalledWith('http://bff.invalid:5381/api/auth/me');
    expect(post.mock.calls.every(([url]) => !url.includes('/api/auth/login'))).toBe(true);
  });

  it('rejects missing server cookies before any authenticated request', async () => {
    const { request, get } = fixture({ cookie: false });
    await expect(
      authenticateBffSession(request, 'http://bff.invalid', 'owned-user', 'fixture-password'),
    ).rejects.toThrow();
    expect(get).not.toHaveBeenCalled();
  });

  it('rejects a credential failure redirect instead of saving empty state', async () => {
    const { request, get } = fixture({ loginStatus: 303 });
    await expect(
      authenticateBffSession(request, 'http://bff.invalid', 'owned-user', 'fixture-password'),
    ).rejects.toThrow();
    expect(get).not.toHaveBeenCalled();
  });

  it.each([
    { label: 'none', spaces: [] },
    {
      label: 'ambiguous',
      spaces: [
        { tenantId: '42', spaceType: 'business' },
        { tenantId: '43', spaceType: 'business' },
      ],
    },
  ])('refuses $label business fixtures', async ({ spaces }) => {
    const { request, post } = fixture({ spaces });
    await expect(
      authenticateBffSession(request, 'http://bff.invalid', 'owned-user', 'fixture-password'),
    ).rejects.toThrow();
    expect(post).toHaveBeenCalledTimes(1);
  });

  it('rejects a failed selection action and an unauthenticated identity probe', async () => {
    for (const options of [{ selectStatus: 200 }, { identityStatus: 401 }]) {
      const { request } = fixture(options);
      await expect(
        authenticateBffSession(request, 'http://bff.invalid', 'owned-user', 'fixture-password'),
      ).rejects.toThrow();
    }
  });
});
