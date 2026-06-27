import { describe, it, expect } from 'vitest';
import type { CapabilityGroup } from '../types';
import {
  grantedCapabilityCodes,
  toggleCapability,
  groupSummary,
  isDirty,
  capabilityCodesForTier,
  splitCapabilityGroupsForPrimaryView,
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

  it('splitCapabilityGroupsForPrimaryView keeps business capabilities primary and folds generated capabilities into advanced', () => {
    const mixed: CapabilityGroup[] = [
      {
        group: '报价单',
        capabilities: [
          { code: 'qo.cap.quote_view', group: '报价单', label: '查看报价', sensitive: false, tier: 'viewer', includes: ['qo.quote.read'], granted: true, conventionDerived: false },
        ],
      },
      {
        group: 'model',
        capabilities: [
          { code: 'model.qo_quote_common', group: 'model', label: 'Qo_quote_common Read', sensitive: false, tier: null, includes: ['model.qo_quote_common.read'], granted: true, conventionDerived: true },
          { code: 'model.crm_account_common', group: 'model', label: 'Crm_account_common Read', sensitive: false, tier: null, includes: ['model.crm_account_common.read'], granted: false, conventionDerived: true },
        ],
      },
      {
        group: '客户管理',
        capabilities: [
          { code: 'crm.cap.account', group: '客户管理', label: '维护客户资料', sensitive: false, tier: 'editor', includes: ['crm.account.manage'], granted: true, conventionDerived: false },
        ],
      },
      {
        group: 'meta',
        capabilities: [
          { code: 'meta.model', group: 'meta', label: 'Meta model read', sensitive: false, tier: null, includes: ['meta.model.read'], granted: true, conventionDerived: true },
        ],
      },
    ];

    const split = splitCapabilityGroupsForPrimaryView(mixed);

    // Menu-view: business groups regroup into menu sections (报价单→报价工具, 客户管理→客户);
    // generated model/meta capabilities fold into advanced.
    expect(split.primaryGroups.map((group) => group.group)).toEqual(['客户', '报价工具']);
    expect(split.primaryGranted).toBe(2); // quote_view + crm.cap.account both granted
    expect(split.primaryTotal).toBe(2);
    expect(split.advancedGroups.map((group) => group.group)).toEqual(['model', 'meta']);
    expect(split.advancedGranted).toBe(2);
    expect(split.advancedTotal).toBe(3);
  });

  it('sorts primary capability groups and capabilities by display metadata', () => {
    const mixed: CapabilityGroup[] = [
      {
        group: '规则配置',
        capabilities: [
          { code: 'bom.rule.manage', group: '规则配置', label: '编辑 BOM 规则', sensitive: false, includes: [], granted: false, conventionDerived: false, displayGroupOrder: 80, displayOrder: 20 },
        ],
      },
      {
        group: '报价管理',
        capabilities: [
          { code: 'qo.cap.quote_edit', group: '报价管理', label: '编辑报价', sensitive: false, includes: [], granted: false, conventionDerived: false, displayGroupOrder: 10, displayOrder: 30 },
          { code: 'qo.cap.quote_view', group: '报价管理', label: '查看报价', sensitive: false, includes: [], granted: true, conventionDerived: false, displayGroupOrder: 10, displayOrder: 10 },
        ],
      },
    ];

    const split = splitCapabilityGroupsForPrimaryView(mixed);

    // 规则配置→BOM 转化工具, 报价管理→报价工具; sections ordered by menu tree (BOM 30 < 报价 40);
    // capabilities within a section sorted by display metadata.
    expect(split.primaryGroups.map((group) => group.group)).toEqual(['BOM 转化工具', '报价工具']);
    expect(split.primaryGroups[1].capabilities.map((capability) => capability.code)).toEqual([
      'qo.cap.quote_view',
      'qo.cap.quote_edit',
    ]);
  });

  it('menu-view: 组织与权限管理 capabilities land in the 组织管理 section (R5/menu-view)', () => {
    const groups: CapabilityGroup[] = [
      {
        group: '组织与权限管理',
        capabilities: [
          { code: 'org.cap.role', group: '组织与权限管理', label: '管理角色与授权', sensitive: false, includes: ['org.role.read'], granted: false, conventionDerived: false, displayGroupOrder: 90, displayOrder: 40 },
        ],
      },
    ];

    const split = splitCapabilityGroupsForPrimaryView(groups);

    expect(split.primaryGroups.map((group) => group.group)).toEqual(['组织管理']);
    expect(split.advancedGroups).toEqual([]);
  });

  it('menu-view: a CRM group with no focused menu (线索与商机) folds into advanced', () => {
    const groups: CapabilityGroup[] = [
      {
        group: '线索与商机',
        capabilities: [
          { code: 'crm.cap.lead', group: '线索与商机', label: '维护线索', sensitive: false, includes: ['crm.lead.read'], granted: false, conventionDerived: false },
        ],
      },
    ];

    const split = splitCapabilityGroupsForPrimaryView(groups);

    expect(split.primaryGroups).toEqual([]);
    expect(split.advancedGroups.map((g) => g.group)).toEqual(['线索与商机']);
  });
});
