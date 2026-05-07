import type { NavigationResource } from './resource.js'
import type { BreadcrumbTrail } from './derived.js'

export interface RouteNode {
  resource: NavigationResource
  children: RouteNode[]
}

export type RouteTree = RouteNode[]

/**
 * RouteRegistry — kernel-side contract. The actual implementation lives in
 * `framework/routing/registry.ts` inside web-admin; this package declares the
 * interface so plugins (and tools like the Designer) can program against it.
 *
 * Note: sidebar menu construction is NOT a registry responsibility — the
 * sidebar comes from the backend (`/api/menu/user`). Permission gating
 * happens server-side via `@RequirePermission` on Controllers.
 */
export interface RouteRegistry {
  /** Register a single resource. Throws on duplicate `key` or `path` conflict. */
  register(resource: NavigationResource): void

  /** Bulk register. Atomic — either all succeed or none. */
  registerBatch(resources: NavigationResource[]): void

  /** Look up by key. */
  findByKey(key: string): NavigationResource | undefined

  /** Look up by exact path (no param matching). */
  findByPath(path: string): NavigationResource | undefined

  /** All registered resources, flat. */
  getAll(): readonly NavigationResource[]

  /** Tree form — children grouped under parents via parentKey. */
  buildRouteTree(): RouteTree

  /** Breadcrumb trail for a given path. */
  buildBreadcrumb(path: string): BreadcrumbTrail
}
