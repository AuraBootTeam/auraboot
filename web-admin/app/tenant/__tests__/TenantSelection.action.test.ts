import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { action } from '../TenantSelection';
import { getTokenFromRequest } from '~/shared/services/session';
import { CLOSED_ACCESS_POLICY, fetchAccessPolicyResult } from '~/services/accessPolicy';

vi.mock('~/shared/services/session', () => ({
  getTokenFromRequest: vi.fn(), createUserSession: vi.fn(),
}));
vi.mock('~/services/accessPolicy', async () => ({
  ...await vi.importActual<typeof import('~/services/accessPolicy')>('~/services/accessPolicy'),
  fetchAccessPolicyResult: vi.fn(),
}));

const selfService = {
  ...CLOSED_ACCESS_POLICY,
  deploymentMode: 'multi' as const,
  tenantProvisioningPolicy: 'self_service' as const,
};

async function submit() {
  return action({ request: new Request('http://localhost/tenant-selection', {
    method: 'POST', body: new URLSearchParams({ action: 'create', tenantName: 'sample-team' }),
  }), params: {}, context: {} } as any);
}

describe('tenant selection action error contracts (hermetic)', () => {
  const backend = vi.fn();
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(getTokenFromRequest).mockResolvedValue('fixture-token');
    vi.mocked(fetchAccessPolicyResult).mockResolvedValue({ policy: selfService, status: 'loaded' });
    vi.stubGlobal('fetch', backend);
  });
  afterEach(() => vi.unstubAllGlobals());

  it('rejects an unauthenticated request before policy or backend access', async () => {
    vi.mocked(getTokenFromRequest).mockResolvedValue(null);
    expect(await submit()).toMatchObject({ success: false, errorKey: 'tenant.select.error.authRequired' });
    expect(fetchAccessPolicyResult).not.toHaveBeenCalled();
    expect(backend).not.toHaveBeenCalled();
  });

  it('distinguishes policy unavailability without issuing a mutation', async () => {
    vi.mocked(fetchAccessPolicyResult).mockResolvedValue({ policy: CLOSED_ACCESS_POLICY, status: 'unavailable' });
    expect(await submit()).toMatchObject({ success: false, errorKey: 'tenant.select.policy.unavailable' });
    expect(backend).not.toHaveBeenCalled();
  });

  it('rejects administrator-managed provisioning without issuing a mutation', async () => {
    vi.mocked(fetchAccessPolicyResult).mockResolvedValue({ policy: CLOSED_ACCESS_POLICY, status: 'loaded' });
    expect(await submit()).toMatchObject({ success: false, errorKey: 'tenant.select.policy.managed' });
    expect(backend).not.toHaveBeenCalled();
  });

  it('retains the HTTP status as a localized message parameter', async () => {
    backend.mockResolvedValue(new Response('', { status: 503 }));
    expect(await submit()).toMatchObject({ success: false, errorKey: 'tenant.select.error.requestFailed', errorParams: { status: 503 } });
    expect(backend).toHaveBeenCalledTimes(1);
  });

  it('retains network diagnostics in the response while choosing localized UI feedback', async () => {
    backend.mockRejectedValue(new Error('fixture transport detail'));
    expect(await submit()).toMatchObject({ success: false, error: 'fixture transport detail', errorKey: 'tenant.select.error.networkFailed' });
  });

  it('localizes an empty backend failure but preserves a supplied business message', async () => {
    backend.mockResolvedValueOnce(Response.json({ code: '1' }))
      .mockResolvedValueOnce(Response.json({ code: '1', message: 'Business validation rejected this name' }));
    expect(await submit()).toMatchObject({ success: false, errorKey: 'tenant.select.error.operationFailed' });
    const supplied = await submit();
    expect(supplied).toMatchObject({ success: false, error: 'Business validation rejected this name' });
    expect(supplied).not.toHaveProperty('errorKey');
  });

  it('preserves pending approval feedback from the backend', async () => {
    backend.mockResolvedValue(Response.json({ code: '0', data: { status: 'pending', message: 'Pending approval' } }));
    expect(await submit()).toEqual({ success: true, pending: true, message: 'Pending approval' });
  });
});
