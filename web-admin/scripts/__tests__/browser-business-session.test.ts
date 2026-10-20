import { describe, expect, it, vi } from 'vitest';
import type { APIRequestContext } from '@playwright/test';
import { establishBrowserBusinessSession } from '../../tests/helpers/browser-business-session';

const baseURL = 'https://owned-runtime.example';
const credentials = { identifier: 'admin@example.test', password: 'fixture-password' };
const business = { spaceType: 'business', tenantId: '2', tenantName: 'AuraBoot Demo' };
const response = (data: unknown = null, status = 200, cookie = false) => ({
  status: () => status, ok: () => status >= 200 && status < 300,
  headers: () => cookie ? { 'set-cookie': '__session=server-signed-fixture; HttpOnly' } : {},
  json: async () => ({ code: '0', data }),
});
function fixture(spaces: unknown = [business], selectedTenant = '2', loginCookie = true, selectionCookie = true) {
  const post = vi.fn().mockResolvedValueOnce(response(null, 302, loginCookie))
    .mockResolvedValueOnce(response(null, 302, selectionCookie));
  const get = vi.fn().mockResolvedValueOnce(response(spaces))
    .mockResolvedValueOnce(response({ user: { tenantId: selectedTenant } }));
  return { request: { post, get } as unknown as APIRequestContext, post, get };
}

describe('browser business session fixture', () => {
  it('uses production BFF login and switch routes, then verifies the selected identity', async () => {
    const { request, post, get } = fixture();
    await establishBrowserBusinessSession(request, baseURL, credentials);
    expect(post.mock.calls).toEqual([
      [`${baseURL}/login`, { form: { ...credentials, remember: 'on', redirectTo: '/' }, maxRedirects: 0 }],
      [`${baseURL}/_action/switch-space`, { form: { tenantId: '2', redirectTo: '/' }, maxRedirects: 0 }],
    ]);
    expect(get).toHaveBeenLastCalledWith(`${baseURL}/api/auth/me`);
  });
  it('refuses login redirects without a server-issued session cookie', async () => {
    const { request, post, get } = fixture([business], '2', false);
    await expect(establishBrowserBusinessSession(request, baseURL, credentials)).rejects.toThrow('BFF login');
    expect(get).not.toHaveBeenCalled(); expect(post).toHaveBeenCalledTimes(1);
  });
  it('refuses a switch failure that merely redirects home without a new cookie', async () => {
    const { request } = fixture([business], '2', true, false);
    await expect(establishBrowserBusinessSession(request, baseURL, credentials)).rejects.toThrow('BFF space selection');
  });
  it('rejects a valid session for the wrong tenant', async () => {
    const { request } = fixture([business], '3');
    await expect(establishBrowserBusinessSession(request, baseURL, credentials)).rejects.toThrow('does not match');
  });
  it.each([null, [], [{ ...business, tenantName: 'A' }, { ...business, tenantId: '3', tenantName: 'B' }]].map(spaces => [spaces]))(
    'refuses unavailable or ambiguous business spaces (%j)', async spaces => {
      const { request, post } = fixture(spaces);
      await expect(establishBrowserBusinessSession(request, baseURL, credentials)).rejects.toThrow(/inventory|Exactly one/);
      expect(post).toHaveBeenCalledTimes(1);
    },
  );
  it('honors an explicit tenant instead of the canonical demo preference', async () => {
    const { request, post } = fixture([business, { ...business, tenantId: '3', tenantName: 'Other' }], '3');
    await establishBrowserBusinessSession(request, baseURL, { ...credentials, tenantId: '3' });
    expect(post.mock.calls[1][1].form.tenantId).toBe('3');
  });
});
