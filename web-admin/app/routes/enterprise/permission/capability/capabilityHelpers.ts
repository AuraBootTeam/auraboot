import type { Capability, CapabilityGroup } from './types';

/**
 * Pure helpers for the permission v2 capability checklist UI. Kept framework-free so they are unit
 * testable without rendering. The checklist seeds its selection from the granted capabilities, lets
 * the user toggle capabilities, and saves the selection back via PUT /api/permission/capabilities.
 */

/** All capabilities across groups, flattened. */
export function allCapabilities(groups: CapabilityGroup[]): Capability[] {
  return groups.flatMap((g) => g.capabilities);
}

export interface CapabilityPrimaryViewSplit {
  primaryGroups: CapabilityGroup[];
  advancedGroups: CapabilityGroup[];
  primaryGranted: number;
  primaryTotal: number;
  advancedGranted: number;
  advancedTotal: number;
}

const ADVANCED_GROUPS = new Set([
  'model',
  'sys',
  'system',
  'meta',
  'data',
  'dashboard',
  'bpm',
  'decision',
  'audit',
  'plugin',
  'acp',
  'page',
  'notification',
  'report',
]);

const FOCUSED_PRIMARY_GROUPS = new Set([
  '报价单',
  '项目管理',
  '来料处理',
  '转换作业',
  '寻源与定价',
  '物料库',
  '敏感信息',
  '规则配置',
  '权限管理',
  '团队与用户',
]);

function isAdvancedCapability(capability: Capability): boolean {
  return (
    capability.conventionDerived ||
    ADVANCED_GROUPS.has(capability.group) ||
    !FOCUSED_PRIMARY_GROUPS.has(capability.group)
  );
}

function countGranted(groups: CapabilityGroup[]): number {
  return allCapabilities(groups).filter((capability) => capability.granted).length;
}

/**
 * Default permission editing should show hand-authored business capabilities only. Generated
 * model/platform capabilities stay available as an advanced escape hatch so BOM/quote users do not
 * have to scan hundreds of low-level codes for normal role setup.
 */
export function splitCapabilityGroupsForPrimaryView(
  groups: CapabilityGroup[],
): CapabilityPrimaryViewSplit {
  const primaryGroups: CapabilityGroup[] = [];
  const advancedGroups: CapabilityGroup[] = [];

  for (const group of groups) {
    const primaryCapabilities = group.capabilities.filter((capability) => !isAdvancedCapability(capability));
    const advancedCapabilities = group.capabilities.filter(isAdvancedCapability);

    if (primaryCapabilities.length > 0) {
      primaryGroups.push({ ...group, capabilities: primaryCapabilities });
    }
    if (advancedCapabilities.length > 0) {
      advancedGroups.push({ ...group, capabilities: advancedCapabilities });
    }
  }

  return {
    primaryGroups,
    advancedGroups,
    primaryGranted: countGranted(primaryGroups),
    primaryTotal: allCapabilities(primaryGroups).length,
    advancedGranted: countGranted(advancedGroups),
    advancedTotal: allCapabilities(advancedGroups).length,
  };
}

/** Initial selection = codes of capabilities the role currently has fully granted. */
export function grantedCapabilityCodes(groups: CapabilityGroup[]): string[] {
  return allCapabilities(groups)
    .filter((c) => c.granted)
    .map((c) => c.code);
}

/** Toggle a capability code in the selection, returning a new array (immutable). */
export function toggleCapability(selected: string[], code: string): string[] {
  return selected.includes(code)
    ? selected.filter((c) => c !== code)
    : [...selected, code];
}

/** Per-group granted/total counts for the group header summary. */
export function groupSummary(group: CapabilityGroup): { granted: number; total: number } {
  return {
    granted: group.capabilities.filter((c) => c.granted).length,
    total: group.capabilities.length,
  };
}

/** Preset tier ordering: a tier preset includes every tiered capability at or below it. */
const TIER_ORDER: Record<string, number> = { viewer: 0, editor: 1, admin: 2 };

/** Capability codes for a preset tier = all tiered capabilities at or below the given tier. */
export function capabilityCodesForTier(groups: CapabilityGroup[], tier: string): string[] {
  const max = TIER_ORDER[tier];
  if (max === undefined) return [];
  return allCapabilities(groups)
    .filter((c) => c.tier != null && TIER_ORDER[c.tier] !== undefined && TIER_ORDER[c.tier] <= max)
    .map((c) => c.code);
}

/** True when the current selection differs from the role's granted baseline (enables Save). */
export function isDirty(groups: CapabilityGroup[], selected: string[]): boolean {
  const baseline = new Set(grantedCapabilityCodes(groups));
  const current = new Set(selected);
  if (baseline.size !== current.size) return true;
  for (const code of current) {
    if (!baseline.has(code)) return true;
  }
  return false;
}
