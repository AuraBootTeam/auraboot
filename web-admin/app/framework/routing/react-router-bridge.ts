/**
 * React Router 7 bridge.
 *
 * Plugins declare their pages in two complementary places:
 *
 *   1. **Static routes** — exported from the plugin (`export const xxxRoutes`),
 *      consumed by `route-manifest.ts` to feed React Router 7's typegen.
 *      These are the bare `route(path, file)` entries the framework needs
 *      to compile route types and code-split bundles.
 *
 *   2. **NavigationResource** — registered via `PluginContext.registerNavigationResource`
 *      at runtime, used by the kernel to derive menus, breadcrumbs,
 *      permission/feature gates, and tab strips.
 *
 * The two share the same `path` per resource — this duplication is the cost
 * of React Router 7's static typegen requirement. A future build-time codegen
 * step can derive (1) from a plugin manifest's resources to eliminate it.
 *
 * This module provides a tiny helper for plugins that want to author once and
 * derive the static routes from their NavigationResource declarations.
 */

import { route, type RouteConfigEntry } from '@react-router/dev/routes'
import type { NavigationResource } from '@auraboot/nav-model'

/**
 * Convert a flat list of NavigationResources into React Router 7 RouteConfigEntry[].
 *
 * Each resource must have a `meta.file` string (relative to `app/`, the React
 * Router convention) — the kernel uses `loader` for runtime lazy-loading, but
 * typegen needs the source path statically.
 *
 * Resources without `meta.file` are skipped (e.g. menu-only entries that
 * resolve to a different runtime route).
 */
export function routesFromResources(resources: readonly NavigationResource[]): RouteConfigEntry[] {
  const out: RouteConfigEntry[] = []
  for (const r of resources) {
    const file = (r.meta?.file as string | undefined) ?? undefined
    if (!file) continue
    out.push(route(r.path, file))
  }
  return out
}

/**
 * Marker type — plugin manifests that opt into static route generation
 * should expose `routes()` returning RouteConfigEntry[]. The route-manifest
 * picks these up alongside legacy `route(...)` calls.
 */
export interface PluginRoutesProvider {
  routes(): RouteConfigEntry[]
}

/**
 * Compose multiple plugin route providers into a flat array.
 */
export function collectPluginRoutes(providers: readonly PluginRoutesProvider[]): RouteConfigEntry[] {
  return providers.flatMap(p => p.routes())
}
