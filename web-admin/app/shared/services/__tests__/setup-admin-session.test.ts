import { afterEach, beforeEach, describe, it, expect, vi } from 'vitest';
import type { APIRequestContext } from '@playwright/test';
const companyName = 'Declared business session fixture';
const backendUrl = 'http://127.0.0.1:65530';
let loginAdmin: typeof import('../../../../tests/api/setup/_helpers').loginAdmin;

async function loadHelper() {
  vi.resetModules();
  ({ loginAdmin } = await import('../../../../tests/api/setup/_helpers'));
}

const response = (data: unknown, code = '0', ok = true) => ({
  ok: () => ok,
  json: async () => ({ code, data }),
});

const space = (tenantId: string, spaceType = 'business') => ({ tenantId, spaceType, tenantName: companyName });

afterEach(() => {
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
});

describe('setup administrator business session', () => {
  beforeEach(async () => {
    // Empty string behaves as unset in the helper's narrowing logic, so the
    // suite cannot be polluted by a worker-wide AURA_ADMIN_TENANT_ID.
    vi.stubEnv('AURA_ADMIN_TENANT_ID', '');
    vi.stubEnv('AURA_BOOTSTRAP_COMPANY', companyName);
    vi.stubEnv('BACKEND_URL', backendUrl);
    await loadHelper();
  });

  it('selects the sole business membership and returns the selected session', async () => {
    const post = vi
      .fn()
      .mockResolvedValueOnce(response({ jwt: 'onboarding-session', tenantId: '1' }))
      .mockResolvedValueOnce(response({ jwt: 'business-session', tenantId: '42' }));
    const get = vi.fn().mockResolvedValue(
      response([space('1', 'platform'), space('42')]),
    );
    expect(await loginAdmin({ post, get } as unknown as APIRequestContext)).toBe(
      'business-session',
    );
    expect(get).toHaveBeenCalledWith(`${backendUrl}/api/tenant-selection/my-spaces`, {
      headers: { Authorization: 'Bearer onboarding-session', 'Content-Type': 'application/json' },
    });
    expect(post).toHaveBeenLastCalledWith(`${backendUrl}/api/tenant-selection/process`, {
      headers: { Authorization: 'Bearer onboarding-session', 'Content-Type': 'application/json' },
      data: { action: 'select', tenantId: '42' },
    });
  });

  it('honors AURA_ADMIN_TENANT_ID when narrowing business memberships', async () => {
    vi.stubEnv('AURA_ADMIN_TENANT_ID', '43');
    await loadHelper();
    const post = vi
      .fn()
      .mockResolvedValueOnce(response({ jwt: 'onboarding-session', tenantId: null, nextAction: 'SELECT_SCHOOL' }))
      .mockResolvedValueOnce(response({ jwt: 'business-43-session', tenantId: '43' }));
    const get = vi.fn().mockResolvedValue(response([space('42'), space('43')]));
    expect(await loginAdmin({ post, get } as unknown as APIRequestContext)).toBe(
      'business-43-session',
    );
    expect(post).toHaveBeenLastCalledWith(`${backendUrl}/api/tenant-selection/process`, {
      headers: { Authorization: 'Bearer onboarding-session', 'Content-Type': 'application/json' },
      data: { action: 'select', tenantId: '43' },
    });
  });

  it.each([
    ['absent', []],
    ['platform-only', [space('1', 'platform')]],
    ['ambiguous', [space('42'), space('43')]],
  ])('refuses %j business memberships', async (_name, spaces) => {
    const post = vi.fn().mockResolvedValue(
      response({ jwt: 'onboarding-session', tenantId: null, nextAction: 'SELECT_SCHOOL' }),
    );
    const get = vi.fn().mockResolvedValue(response(spaces as unknown));
    await expect(loginAdmin({ post, get } as unknown as APIRequestContext)).rejects.toThrow(
      'expected exactly one business space',
    );
    expect(post).toHaveBeenCalledTimes(1);
  });

  it('refuses a malformed spaces inventory', async () => {
    const post = vi.fn().mockResolvedValue(
      response({ jwt: 'onboarding-session', tenantId: null }),
    );
    const get = vi.fn().mockResolvedValue(response([], '1'));
    await expect(loginAdmin({ post, get } as unknown as APIRequestContext)).rejects.toThrow(
      'no inventory',
    );
  });

  it('refuses a selection whose tenant does not match the requested space', async () => {
    const post = vi
      .fn()
      .mockResolvedValueOnce(response({ jwt: 'onboarding-session', tenantId: null }))
      .mockResolvedValueOnce(response({ jwt: 'mismatched-session', tenantId: '999' }));
    const get = vi.fn().mockResolvedValue(response([space('42')]));
    await expect(loginAdmin({ post, get } as unknown as APIRequestContext)).rejects.toThrow(
      'no matching session',
    );
  });
});
