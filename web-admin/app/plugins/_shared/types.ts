/**
 * Shared types for OSS core-* plugins.
 *
 * `PluginResource` extends NavigationResource with an optional `file` field
 * carrying the route component path (relative to app/, the React Router 7
 * convention). Plugins author a single `resources.ts` exporting
 * `RESOURCES: PluginResource[]` — `index.ts` and `routes.ts` then derive
 * from it, eliminating path duplication.
 *
 * Resources without `file` are nav-only (e.g. menu groupings) or share a
 * route with another resource via `parentKey`.
 */

import type { NavigationResource } from '@auraboot/nav-model'

export interface PluginResource extends Omit<NavigationResource, 'source'> {
  /**
   * React Router 7 file path (relative to app/). Required for any resource
   * the router should serve. Omit for menu-only entries.
   */
  file?: string
}

/** Convenience: the `source: 'plugin'` injected by `toNavigationResources()`. */
export const PLUGIN_SOURCE = 'plugin' as const

/**
 * Convert PluginResource[] → NavigationResource[] (drops `file`, adds source).
 * Used by plugin index.ts files when calling `ctx.registerNavigationResources()`.
 */
export function toNavigationResources(resources: readonly PluginResource[]): NavigationResource[] {
  return resources.map(({ file: _file, ...rest }) => ({
    ...rest,
    source: PLUGIN_SOURCE,
  }))
}

/**
 * Convert PluginResource[] → React Router 7 route() entries. Skips resources
 * without `file`. Used by plugin routes.ts files.
 */
export function toRouteEntries<T>(
  resources: readonly PluginResource[],
  routeFn: (path: string, file: string) => T,
): T[] {
  return resources
    .filter((r): r is PluginResource & { file: string } => Boolean(r.file))
    .map(r => routeFn(r.path, r.file))
}
