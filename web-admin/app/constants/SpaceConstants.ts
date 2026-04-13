/**
 * Space constants for Platform / Business space separation.
 *
 * Platform space (/platform/*) — system-level management (License, Marketplace, Tenants, etc.)
 * Business space (everything else) — business tenant functionality (CRM, PM, ERP, etc.)
 */

/** Tenant ID reserved for the system (control plane) tenant. */
export const SYSTEM_TENANT_ID = 1;

/** Route prefix for the platform (control plane) space. */
export const PLATFORM_ROUTE_PREFIX = '/platform';

/** Default landing page within the platform space. */
export const PLATFORM_HOME = '/platform/marketplace';

/** Default landing page within the business space. */
export const BUSINESS_HOME = '/';

/** Space identifiers used in the SpaceSwitcher and route guards. */
export type SpaceType = 'platform' | 'business';

/**
 * Determine the current space based on the pathname.
 */
export function resolveSpace(pathname: string): SpaceType {
  return pathname.startsWith(PLATFORM_ROUTE_PREFIX) ? 'platform' : 'business';
}

/**
 * Check whether the given tenantId belongs to the system tenant.
 * Handles both number and string tenantId (backend serializes Long as string).
 */
export function isSystemTenant(tenantId: number | string | undefined | null): boolean {
  if (tenantId == null) return false;
  return String(tenantId) === String(SYSTEM_TENANT_ID);
}
