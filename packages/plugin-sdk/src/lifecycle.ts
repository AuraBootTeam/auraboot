/**
 * Plugin lifecycle states.
 *
 * A plugin transitions through these states in order. `active` requires all
 * prior conditions to be satisfied:
 *
 *   active = installed && enabled && compatibleVersion && depsOk && licenseOk
 *
 * Used by the kernel's plugin manager and surfaced to admin UIs.
 */
export type PluginState =
  /** Discovered in marketplace/registry, not yet installed. */
  | 'discovered'
  /** Installed locally, not yet enabled by tenant admin. */
  | 'installed'
  /** Enabled by tenant admin. Compatibility and deps may still fail. */
  | 'enabled'
  /** License/entitlement check passed (no-op for OSS plugins). */
  | 'licensed'
  /** All gates passed. Plugin is registered and contributions are live. */
  | 'active'
