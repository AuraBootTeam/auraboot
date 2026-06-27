/**
 * Permission v2 capability view types (mirror of the backend CapabilityGroup / Capability served
 * by GET /api/permission/capabilities).
 */
export interface Capability {
  code: string;
  group: string;
  label: string;
  sensitive: boolean;
  /** Preset tier (viewer/editor/admin); null for convention-derived capabilities. */
  tier?: string | null;
  displayGroupOrder?: number | null;
  displayOrder?: number | null;
  includes: string[];
  granted: boolean;
  conventionDerived: boolean;
  /** Menus this capability unlocks (derived server-side from menu.permissionCode ∈ includes). */
  unlockedMenus?: string[] | null;
}

export interface CapabilityGroup {
  group: string;
  capabilities: Capability[];
}
