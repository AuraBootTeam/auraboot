import type { NavigationResource } from './resource.js'
import type { MenuTree, BreadcrumbTrail } from './derived.js'

export interface RouteNode {
  resource: NavigationResource
  children: RouteNode[]
}

export type RouteTree = RouteNode[]

/**
 * Minimal user shape needed for permission/feature evaluation. Implementations
 * may pass richer types — these fields are required.
 */
export interface RegistryUser {
  permissions: ReadonlySet<string> | readonly string[]
  features: ReadonlySet<string> | readonly string[]
}

/**
 * RouteRegistry — kernel-side contract. The actual implementation lives in
 * `framework/routing/registry.ts` inside web-admin; this package declares the
 * interface so plugins (and tools like the Designer) can program against it.
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

  /** Menu tree filtered by user permissions and entitlements. */
  buildMenuTree(user: RegistryUser): MenuTree

  /** Breadcrumb trail for a given path. */
  buildBreadcrumb(path: string): BreadcrumbTrail
}
