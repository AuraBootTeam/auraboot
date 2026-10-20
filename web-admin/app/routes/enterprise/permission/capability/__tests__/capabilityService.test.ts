import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Capability, CapabilitySelectionPreview } from '../types';
const { transport } = vi.hoisted(() => ({ transport: vi.fn() }));
vi.mock('~/shared/services/http-client/HttpClient', () => ({ fetchResult: transport }));
import { capabilityService } from '../capabilityService';
const capability = {
  code: 'org.cap.read',
  group: null,
  label: 'Read organization',
  localizedLabels: {},
  description: null,
  localizedDescriptions: {},
  sensitive: false,
  tier: null,
  displayGroupOrder: 10000,
  displayOrder: 100,
  includes: ['org.hr.read'],
  granted: false,
  authorizationState: 'none',
  missingCodes: ['org.hr.read'],
  conventionDerived: false,
  unlockedMenus: null,
} satisfies Capability;
const groups = [{ group: null, capabilities: [capability] }];
const plan = {
  grantedCodes: ['org.hr.read'],
  revokedCodes: [],
  preservedCodes: ['existing.read'],
  resultingCapabilities: [
    { ...capability, granted: true, authorizationState: 'full' as const, missingCodes: [] },
  ],
  relatedMenus: [],
} satisfies CapabilitySelectionPreview;
const ok = (data: unknown) => ({ code: '0', success: true, data });

describe('generated capability operations', () => {
  beforeEach(() => transport.mockReset());
  it('encodes the public role query, forwards SSR context and preserves null display metadata', async () => {
    const request = new Request('https://web.example/roles');
    transport.mockResolvedValue(ok(groups));
    expect(await capabilityService.getForRole('role /?字', request)).toEqual(groups);
    expect(transport).toHaveBeenCalledExactlyOnceWith(
      '/api/permission/capabilities?rolePid=role+%2F%3F%E5%AD%97',
      expect.objectContaining({ method: 'get', body: undefined }),
      request,
    );
  });
  it('previews with a true selection array and repeated explicit partial revocations', async () => {
    transport.mockResolvedValue(ok(plan));
    expect(
      await capabilityService.previewSelection(
        'role/pid',
        ['org.cap.read'],
        ['cap.edit', 'cap.export'],
      ),
    ).toEqual(plan);
    expect(transport).toHaveBeenCalledExactlyOnceWith(
      '/api/permission/capabilities/preview?rolePid=role%2Fpid&revokePartial=cap.edit&revokePartial=cap.export',
      expect.objectContaining({ method: 'post', body: ['org.cap.read'] }),
      undefined,
    );
  });
  it('applies both selected and empty selections as JSON arrays, retaining SSR context', async () => {
    const request = new Request('https://web.example/roles');
    transport.mockResolvedValue(ok(groups));
    expect(
      await capabilityService.applySelection('role-pid', ['org.cap.read'], request, ['cap.edit']),
    ).toEqual(groups);
    expect(transport).toHaveBeenLastCalledWith(
      '/api/permission/capabilities?rolePid=role-pid&revokePartial=cap.edit',
      expect.objectContaining({ method: 'put', body: ['org.cap.read'] }),
      request,
    );
    transport.mockResolvedValue(ok([]));
    expect(await capabilityService.applySelection('role-pid', [])).toEqual([]);
    expect(transport).toHaveBeenLastCalledWith(
      '/api/permission/capabilities?rolePid=role-pid',
      expect.objectContaining({ method: 'put', body: [] }),
      undefined,
    );
  });
  it.each(
    [
      null,
      {},
      [{ group: 'g', capabilities: null }],
      [{ group: null, capabilities: [{ ...capability, localizedLabels: null }] }],
      [{ group: null, capabilities: [{ ...capability, authorizationState: 'maybe' }] }],
      [{ group: null, capabilities: [capability, capability] }],
    ].map((data) => ({ data })),
  )('rejects incomplete successful group payloads $data', async ({ data }) => {
    transport.mockResolvedValue(ok(data));
    await expect(capabilityService.getForRole('role-pid')).rejects.toThrow();
  });
  it.each(
    [
      null,
      {},
      { ...plan, grantedCodes: null },
      { ...plan, resultingCapabilities: null },
      { ...plan, relatedMenus: [123] },
    ].map((data) => ({ data })),
  )('rejects invalid preview payloads $data', async ({ data }) => {
    transport.mockResolvedValue(ok(data));
    await expect(capabilityService.previewSelection('role-pid', [])).rejects.toThrow();
  });
  it.each([
    { name: 'read', action: () => capabilityService.getForRole('role-pid') },
    { name: 'preview', action: () => capabilityService.previewSelection('role-pid', []) },
    { name: 'apply', action: () => capabilityService.applySelection('role-pid', []) },
  ])('retains authorization and transport errors for $name', async ({ action }) => {
    transport.mockResolvedValueOnce({ code: '403', desc: 'Forbidden', data: null });
    await expect(action()).rejects.toThrow('Forbidden');
    transport.mockRejectedValueOnce(new Error('Network failed'));
    await expect(action()).rejects.toThrow('Network failed');
  });
  it('refuses missing public role identity before transport', async () => {
    await expect(capabilityService.getForRole('')).rejects.toThrow('public role PID');
    expect(transport).not.toHaveBeenCalled();
  });
});
