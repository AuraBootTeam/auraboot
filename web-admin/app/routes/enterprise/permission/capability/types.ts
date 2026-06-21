/**
 * Permission v2 capability view types (mirror of the backend CapabilityGroup / Capability served
 * by GET /api/permission/capabilities).
 */
export interface Capability {
  code: string;
  group: string;
  label: string;
  sensitive: boolean;
  includes: string[];
  granted: boolean;
  conventionDerived: boolean;
}

export interface CapabilityGroup {
  group: string;
  capabilities: Capability[];
}
