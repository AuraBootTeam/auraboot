/**
 * Permission v2 capability view types (mirror of the backend CapabilityGroup / Capability served
 * by GET /api/permission/capabilities).
 */
export interface Capability {
  code: string;
  group: string;
  label: string;
  /** Declaration labels by locale; omitted by older servers and legacy capabilities. */
  localizedLabels?: Record<string, string> | null;
  description?: string | null;
  localizedDescriptions?: Record<string, string> | null;
  sensitive: boolean;
  /** Preset tier (viewer/editor/admin); null for convention-derived capabilities. */
  tier?: string | null;
  displayGroupOrder?: number | null;
  displayOrder?: number | null;
  includes: string[];
  granted: boolean;
  authorizationState?: 'none' | 'partial' | 'full';
  missingCodes?: string[];
  conventionDerived: boolean;
  /** Menus this capability unlocks (derived server-side from menu.permissionCode ∈ includes). */
  unlockedMenus?: string[] | null;
}

export interface CapabilityGroup {
  group: string;
  capabilities: Capability[];
}

export interface CapabilitySelectionPreview {
  grantedCodes: string[];
  revokedCodes: string[];
  preservedCodes: string[];
  resultingCapabilities: Capability[];
  relatedMenus: string[];
}
