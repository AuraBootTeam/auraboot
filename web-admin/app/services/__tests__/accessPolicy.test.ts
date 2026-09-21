import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  CLOSED_ACCESS_POLICY,
  canSelfProvisionTenant,
  fetchAccessPolicy,
  fetchAccessPolicyResult,
} from '../accessPolicy';

describe('access policy loading', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('keeps an explicit self-service policy distinct from fallback state', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({
          data: {
            deploymentMode: 'multi',
            userRegistrationPolicy: 'closed',
            tenantProvisioningPolicy: 'self_service',
            partyCreationPolicy: 'approval_required',
            partyInvitationEnabled: true,
            actorSwitchEnabled: true,
          },
        }),
      }),
    );

    const result = await fetchAccessPolicyResult();

    expect(result.status).toBe('loaded');
    expect(canSelfProvisionTenant(result.policy)).toBe(true);
    expect(await fetchAccessPolicy()).toEqual(result.policy);
  });

  it('reports an unavailable policy instead of presenting it as an explicit denial', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('network unavailable')));

    await expect(fetchAccessPolicyResult()).resolves.toEqual({
      policy: CLOSED_ACCESS_POLICY,
      status: 'unavailable',
    });
  });
});
