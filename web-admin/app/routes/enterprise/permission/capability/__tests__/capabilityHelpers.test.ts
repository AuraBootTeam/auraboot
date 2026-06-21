import { describe, it, expect } from 'vitest';
import type { CapabilityGroup } from '../types';
import {
  grantedCapabilityCodes,
  toggleCapability,
  groupSummary,
  isDirty,
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
});
