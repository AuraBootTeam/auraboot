---
type: system-reference
status: active
created: 2026-11-24
---

# Platform console navigation ownership

Community does not seed the legacy System-space Overview, Tenants, Commerce, Platform or System directory. Primary Platform Management navigation still links to the supported read-only kernel inventory and Open Platform. Removing the legacy directory removes navigation metadata, not tenants, audit records, licenses, cloud settings or plugin installations.

Distributions that supply the corresponding console pages declare their bootstrap menu codes in `META-INF/auraboot/platform-console-menus.json` (`schemaVersion: 1`, `menuCodes: string[]`). The Enterprise core module supplies this resource. Community supplies no declaration. The fixed bootstrap code catalog rejects unknown/duplicate codes and children without declared parents. Marketplace's obsolete standalone route is retired in every distribution; the existing unified plugin manager remains available.

`PlatformConsoleMenuBootstrap` reconciles first-install/repair and existing System tenants on application readiness. Reconciliation is transactional and enters the trusted System tenant context. It physically deletes exact unsupported bootstrap leaves and their role-navigation/customization records, then deletes empty legacy groups. It never substitutes a `visible=false` override or a logical deletion for removal. Supported distribution menus are seeded idempotently with parents before children.

Retirement is limited to System tenant 1, known bootstrap code/path pairs with no plugin ownership, page binding or authored-menu marker, and no remaining children. A customization retargeted to another destination is preserved. Other tenants, plugin-owned menus, custom pages, unrelated menu codes and nonempty groups remain intact. Navigation audit history and all business data are retained. The legacy-code integrity allowlist is not a menu seed and does not recreate removed rows.

The resource controls navigation ownership only. It grants no application, platform or business permission and does not replace route registration or server authorization. Reinstalling Community cannot resurrect the retired directory; enabling Enterprise console declarations can restore its supported navigation without restoring Marketplace.
