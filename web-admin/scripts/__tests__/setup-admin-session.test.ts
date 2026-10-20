import { afterEach, describe, expect, it, vi } from 'vitest';
import type { APIRequestContext } from '@playwright/test';
import { loginAdmin } from '../../tests/api/setup/_helpers';

const response = (data: unknown, code = '0', status = 200) => ({
  ok: () => status >= 200 && status < 300,
  status: () => status,
  json: async () => ({ code, data }),
  text: async () => 'fixture response',
});

function fixture(spaces: unknown, selected: unknown = { jwt: 'business-session', tenantId: '2' }, status = 200) {
  const post = vi.fn().mockResolvedValueOnce(response({ jwt: 'onboarding-session' }))
    .mockResolvedValueOnce(response(selected, '0', status));
  const get = vi.fn().mockResolvedValue(response(spaces));
  return { request: { post, get } as unknown as APIRequestContext, post, get };
}

describe('setup administrator session', () => {
  afterEach(() => vi.unstubAllEnvs());

  it('selects the business membership before returning a token for fixture writes', async () => {
    vi.stubEnv('AURA_ADMIN_TENANT_ID', '');
    const { request, post, get } = fixture([
      { tenantId: '1', spaceType: 'platform' }, { tenantId: '2', spaceType: 'business' },
    ]);
    expect(await loginAdmin(request)).toBe('business-session');
    expect(get).toHaveBeenCalledWith('/api/tenant-selection/my-spaces', {
      headers: { Authorization: 'Bearer onboarding-session', 'Content-Type': 'application/json' },
    });
    expect(post).toHaveBeenNthCalledWith(2, '/api/tenant-selection/process', {
      headers: { Authorization: 'Bearer onboarding-session', 'Content-Type': 'application/json' },
      data: { action: 'select', tenantId: '2' },
    });
  });

  it.each([null, [], [{ tenantId: '1', spaceType: 'platform' }],
    [{ tenantId: '2', spaceType: 'business' }, { tenantId: '3', spaceType: 'business' }]].map(spaces => [spaces]))(
    'refuses missing or ambiguous business membership before any fixture write (%j)', async spaces => {
      vi.stubEnv('AURA_ADMIN_TENANT_ID', '');
      const { request, post } = fixture(spaces);
      await expect(loginAdmin(request)).rejects.toThrow(/inventory|exactly one business space/);
      expect(post).toHaveBeenCalledTimes(1);
    },
  );

  it('honors an explicitly pinned tenant rather than choosing the first membership', async () => {
    vi.stubEnv('AURA_ADMIN_TENANT_ID', '2');
    const { request, post } = fixture([
      { tenantId: '3', spaceType: 'business' }, { tenantId: '2', spaceType: 'business' },
    ]);
    expect(await loginAdmin(request)).toBe('business-session');
    expect(post.mock.calls[1][1].data.tenantId).toBe('2');
  });

  it.each([{ jwt: 'wrong-session', tenantId: '3' }, { tenantId: '2' }])(
    'never returns an onboarding token after an invalid selection (%j)', async selected => {
      vi.stubEnv('AURA_ADMIN_TENANT_ID', '');
      const { request } = fixture([{ tenantId: '2', spaceType: 'business' }], selected);
      await expect(loginAdmin(request)).rejects.toThrow('no matching session');
    },
  );

  it('propagates a rejected selection instead of falling back to the login token', async () => {
    vi.stubEnv('AURA_ADMIN_TENANT_ID', '');
    const { request } = fixture([{ tenantId: '2', spaceType: 'business' }], null, 401);
    await expect(loginAdmin(request)).rejects.toThrow('selection failed (401)');
  });
});
