import { describe, it, expect } from 'vitest';
import type { CapabilityGroup } from '../types';
import {
  grantedCapabilityCodes,
  toggleCapability,
  groupSummary,
  isDirty,
  capabilityCodesForTier,
} from '../capabilityHelpers';

function cap(code: string, granted: boolean, sensitive = false) {
  return { code, group: '客户管理', label: code, sensitive, includes: [code + '.read'], granted, conventionDerived: false };
}

const groups: CapabilityGroup[] = [
  { group: '客户管理', capabilities: [cap('crm.cap.account', true), cap('crm.cap.account_contact_full', false, true)] },
  { group: '线索', capabilities: [cap('crm.cap.lead', false)] },
];

describe('capabilityHelpers', () => {
  it('grantedCapabilityCodes returns only fully-granted capability codes', () => {
    expect(grantedCapabilityCodes(groups)).toEqual(['crm.cap.account']);
  });

  it('toggleCapability adds when absent and removes when present (immutable)', () => {
    const added = toggleCapability(['crm.cap.account'], 'crm.cap.lead');
    expect(added).toEqual(['crm.cap.account', 'crm.cap.lead']);
    const removed = toggleCapability(added, 'crm.cap.account');
    expect(removed).toEqual(['crm.cap.lead']);
    // original not mutated
    expect(added).toEqual(['crm.cap.account', 'crm.cap.lead']);
  });

  it('groupSummary counts granted vs total', () => {
    expect(groupSummary(groups[0])).toEqual({ granted: 1, total: 2 });
    expect(groupSummary(groups[1])).toEqual({ granted: 0, total: 1 });
  });

  it('isDirty is false for the granted baseline and true after a change', () => {
    expect(isDirty(groups, ['crm.cap.account'])).toBe(false);
    expect(isDirty(groups, ['crm.cap.account', 'crm.cap.account_contact_full'])).toBe(true);
    expect(isDirty(groups, [])).toBe(true);
  });

  it('capabilityCodesForTier selects tiered capabilities at or below the tier (skips untiered)', () => {
    const tiered: CapabilityGroup[] = [
      {
        group: 'g',
        capabilities: [
          { code: 'a', group: 'g', label: 'a', sensitive: false, tier: 'viewer', includes: [], granted: false, conventionDerived: false },
          { code: 'b', group: 'g', label: 'b', sensitive: false, tier: 'editor', includes: [], granted: false, conventionDerived: false },
          { code: 'c', group: 'g', label: 'c', sensitive: false, tier: 'admin', includes: [], granted: false, conventionDerived: false },
          { code: 'd', group: 'g', label: 'd', sensitive: false, includes: [], granted: false, conventionDerived: false },
        ],
      },
    ];
    expect(capabilityCodesForTier(tiered, 'viewer')).toEqual(['a']);
    expect(capabilityCodesForTier(tiered, 'editor').sort()).toEqual(['a', 'b']);
    expect(capabilityCodesForTier(tiered, 'admin').sort()).toEqual(['a', 'b', 'c']);
    expect(capabilityCodesForTier(tiered, 'nope')).toEqual([]);
  });
});
