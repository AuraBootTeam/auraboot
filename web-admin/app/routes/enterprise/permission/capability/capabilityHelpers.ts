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

/**
 * Menu-view: map each business-function capability group to the top-level sidebar menu section it
 * belongs to, so the permission editor mirrors the real navigation tree (owner-approved mockup
 * docs/mockups/rbac-menu-view) instead of dozens of abstract groups. Capabilities whose group is not
 * in this map (CRM lead/opportunity/campaign… that have no focused menu, plus generated model/system
 * codes) fold into the advanced escape hatch. The map IS the declaration of "which menu section owns
 * this capability"; keeping it here (a focused-deployment UI concern) avoids multi-repo
 * capabilities.json churn and a fragile permissionCode derivation (most actions don't gate a menu).
 */
const GROUP_TO_MENU_SECTION: Record<string, string> = {
  客户管理: '客户',
  项目管理: '项目',
  转换作业: 'BOM 转化工具',
  物料库: 'BOM 转化工具',
  规则配置: 'BOM 转化工具',
  报价单: '报价工具',
  报价管理: '报价工具',
  来料处理: '报价工具',
  寻源与定价: '报价工具',
  敏感信息: '报价工具',
  组织与权限管理: '组织管理',
  权限管理: '组织管理',
  团队与用户: '组织管理',
  系统管理: '系统管理',
};

/** Section render order = sidebar menu tree order (客户 → 项目 → BOM → 报价 → 组织 → 系统). */
const MENU_SECTION_ORDER: Record<string, number> = {
  客户: 10,
  项目: 20,
  'BOM 转化工具': 30,
  报价工具: 40,
  组织管理: 50,
  系统管理: 60,
};

/** The top-level menu section a capability belongs to, or null if it has no focused menu. */
export function menuSectionForCapability(capability: Capability): string | null {
  if (capability.conventionDerived) return null;
  return GROUP_TO_MENU_SECTION[capability.group] ?? null;
}

function isAdvancedCapability(capability: Capability): boolean {
  return menuSectionForCapability(capability) === null;
}

function countGranted(groups: CapabilityGroup[]): number {
  return allCapabilities(groups).filter((capability) => capability.granted).length;
}

/**
 * Menu-view split: primary capabilities are RE-GROUPED by their top-level menu section (客户 / 项目 /
 * BOM 转化工具 / 报价工具 / 组织管理), so the editor mirrors the sidebar tree. Capabilities with no
 * focused menu section (CRM lead/opportunity/campaign…, generated model/platform codes) stay in the
 * advanced escape hatch, keeping their original business group for auditability.
 */
export function splitCapabilityGroupsForPrimaryView(
  groups: CapabilityGroup[],
): CapabilityPrimaryViewSplit {
  // Re-group primary capabilities by menu section (not by the backend's business-function group).
  const bySection = new Map<string, Capability[]>();
  const advancedGroups: CapabilityGroup[] = [];

  for (const group of groups) {
    const advancedCapabilities = group.capabilities.filter(isAdvancedCapability);
    if (advancedCapabilities.length > 0) {
      advancedGroups.push({ ...group, capabilities: advancedCapabilities });
    }
    for (const capability of group.capabilities) {
      const section = menuSectionForCapability(capability);
      if (section === null) continue;
      const bucket = bySection.get(section);
      if (bucket) bucket.push(capability);
      else bySection.set(section, [capability]);
    }
  }

  const primaryGroups: CapabilityGroup[] = [...bySection.entries()]
    .map(([group, capabilities]) => ({ group, capabilities: [...capabilities].sort(compareCapabilities) }))
    .sort((a, b) => menuSectionOrder(a.group) - menuSectionOrder(b.group));

  return {
    primaryGroups,
    advancedGroups: sortCapabilityGroups(advancedGroups),
    primaryGranted: countGranted(primaryGroups),
    primaryTotal: allCapabilities(primaryGroups).length,
    advancedGranted: countGranted(advancedGroups),
    advancedTotal: allCapabilities(advancedGroups).length,
  };
}

function menuSectionOrder(section: string): number {
  return MENU_SECTION_ORDER[section] ?? 9999;
}

function sortCapabilityGroups(groups: CapabilityGroup[]): CapabilityGroup[] {
  return groups
    .map((group, index) => ({
      group: {
        ...group,
        capabilities: [...group.capabilities].sort(compareCapabilities),
      },
      index,
    }))
    .sort((a, b) => {
      const groupOrderA = firstDisplayGroupOrder(a.group);
      const groupOrderB = firstDisplayGroupOrder(b.group);
      if (groupOrderA !== groupOrderB) return groupOrderA - groupOrderB;
      return a.index - b.index;
    })
    .map(({ group }) => group);
}

function firstDisplayGroupOrder(group: CapabilityGroup): number {
  return Math.min(...group.capabilities.map((capability) => numberOrMax(capability.displayGroupOrder)));
}

function compareCapabilities(a: Capability, b: Capability): number {
  const groupOrderDelta = numberOrMax(a.displayGroupOrder) - numberOrMax(b.displayGroupOrder);
  if (groupOrderDelta !== 0) return groupOrderDelta;
  const orderDelta = numberOrMax(a.displayOrder) - numberOrMax(b.displayOrder);
  if (orderDelta !== 0) return orderDelta;
  return a.code.localeCompare(b.code);
}

function numberOrMax(value: number | null | undefined): number {
  return typeof value === 'number' ? value : Number.MAX_SAFE_INTEGER;
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
