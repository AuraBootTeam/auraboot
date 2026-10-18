import { describe, it, expect, vi } from 'vitest';
import type { APIRequestContext } from '@playwright/test';
import { loginAdmin } from '../../../../tests/api/setup/_helpers';

const response = (data: unknown, code = '0', ok = true) => ({
  ok: () => ok,
  json: async () => ({ code, data }),
});

describe('setup administrator business session', () => {
  it('keeps an already tenant-scoped login without selecting another space', async () => {
    const post = vi.fn().mockResolvedValue(response({ jwt: 'existing-session', tenantId: '42' }));
    const get = vi.fn().mockResolvedValue(response([{ tenantId: '42', spaceType: 'business' }]));
    expect(await loginAdmin({ post, get } as unknown as APIRequestContext)).toBe(
      'existing-session',
    );
    expect(post).toHaveBeenCalledTimes(1);
    expect(get).toHaveBeenCalledTimes(1);
  });

  it('selects the sole business membership and returns the replacement session', async () => {
    const post = vi
      .fn()
      .mockResolvedValueOnce(
        response({ jwt: 'onboarding-session', tenantId: null, nextAction: 'SELECT_SCHOOL' }),
      )
      .mockResolvedValueOnce(response({ jwt: 'business-session' }));
    const get = vi.fn().mockResolvedValue(
      response([
        { tenantId: '1', spaceType: 'platform' },
        { tenantId: '42', spaceType: 'business' },
      ]),
    );
    expect(
      await loginAdmin({ post, get } as unknown as APIRequestContext, 'http://backend.invalid'),
    ).toBe('business-session');
    expect(get).toHaveBeenCalledWith('http://backend.invalid/api/tenant-selection/my-spaces', {
      headers: { Authorization: 'Bearer onboarding-session', 'Content-Type': 'application/json' },
    });
    expect(post).toHaveBeenLastCalledWith('http://backend.invalid/api/tenant-selection/process', {
      headers: { Authorization: 'Bearer onboarding-session', 'Content-Type': 'application/json' },
      data: { action: 'select', tenantId: '42' },
    });
  });

  it.each([
    [],
    [{ tenantId: '1', spaceType: 'platform' }],
    [
      { tenantId: '42', spaceType: 'business' },
      { tenantId: '43', spaceType: 'business' },
    ],
  ])('refuses absent or ambiguous business memberships: %j', async (...spaces) => {
    const post = vi
      .fn()
      .mockResolvedValue(
        response({ jwt: 'onboarding-session', tenantId: null, nextAction: 'SELECT_SCHOOL' }),
      );
    const get = vi.fn().mockResolvedValue(response(spaces));
    await expect(loginAdmin({ post, get } as unknown as APIRequestContext)).rejects.toThrow(
      'exactly one existing business space',
    );
    expect(post).toHaveBeenCalledTimes(1);
  });

  it('switches an existing platform session to the sole business membership', async () => {
    const post = vi
      .fn()
      .mockResolvedValueOnce(response({ jwt: 'platform-session', tenantId: '1' }))
      .mockResolvedValueOnce(response({ jwt: 'business-session' }));
    const get = vi.fn().mockResolvedValue(
      response([
        { tenantId: '1', spaceType: 'platform' },
        { tenantId: '42', spaceType: 'business' },
      ]),
    );
    expect(await loginAdmin({ post, get } as unknown as APIRequestContext)).toBe(
      'business-session',
    );
    expect(post).toHaveBeenLastCalledWith(
      '/api/tenant-selection/process',
      expect.objectContaining({
        data: { action: 'select', tenantId: '42' },
      }),
    );
  });

  it('rejects selection failure instead of returning the onboarding session', async () => {
    const post = vi
      .fn()
      .mockResolvedValueOnce(
        response({ jwt: 'onboarding-session', tenantId: null, nextAction: 'SELECT_SCHOOL' }),
      )
      .mockResolvedValueOnce(response(null, '403', false));
    const get = vi.fn().mockResolvedValue(response([{ tenantId: '42', spaceType: 'business' }]));
    await expect(loginAdmin({ post, get } as unknown as APIRequestContext)).rejects.toThrow(
      'selection returned no session',
    );
  });
});
