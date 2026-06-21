import { describe, it, expect } from 'vitest';
import type { CapabilityGroup } from '../types';
import { deriveCodeSources, sourceFor } from '../coverageHelpers';

function declared(code: string, label: string, includes: string[]): any {
  return { code, group: 'crm', label, sensitive: false, includes, granted: false, conventionDerived: false };
}
function derived(code: string, includes: string[]): any {
  return { code, group: 'billing', label: code, sensitive: false, includes, granted: false, conventionDerived: true };
}

const groups: CapabilityGroup[] = [
  {
    group: '客户管理',
    capabilities: [
      declared('crm.cap.account', '维护客户资料', ['crm.account.read', 'crm.account.manage']),
      declared('crm.cap.account_list', '查看客户列表', ['crm.account.read']),
    ],
  },
  {
    group: 'billing',
    // convention-derived fallback — does NOT count as business coverage
    capabilities: [derived('billing.license', ['billing.license.read', 'billing.license.export'])],
  },
];

describe('coverageHelpers.deriveCodeSources', () => {
  it('marks a code included by a declared capability as covered, with that capability label', () => {
    const map = deriveCodeSources(groups);
    expect(map['crm.account.manage']).toEqual({
      covered: true,
      capabilityLabel: '维护客户资料',
      capabilityCode: 'crm.cap.account',
    });
  });

  it('first declared capability wins when a code is in several (stable order)', () => {
    const map = deriveCodeSources(groups);
    // crm.account.read is in both crm.cap.account and crm.cap.account_list — first listed wins
    expect(map['crm.account.read'].capabilityCode).toBe('crm.cap.account');
  });

  it('treats a code reachable only via a convention-derived capability as uncovered (exception)', () => {
    const map = deriveCodeSources(groups);
    expect(map['billing.license.export']).toBeUndefined();
    expect(sourceFor(map, 'billing.license.export')).toEqual({ covered: false });
  });

  it('treats a code in no capability as uncovered', () => {
    const map = deriveCodeSources(groups);
    expect(sourceFor(map, 'sys.audit.read')).toEqual({ covered: false });
  });
});
