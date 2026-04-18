/**
 * RouteRegistryImpl — concrete implementation of the @auraboot/nav-model
 * RouteRegistry contract.
 *
 * Single source of truth for navigation. Kernel boots, plugins call
 * `register()` during their setup(), then the App shell calls
 * `buildRouteTree()` to feed React Router and `buildMenuTree(user)` to
 * render the sidebar.
 *
 * Ordering: registration order is preserved within a parent. Sort within
 * menu output is by `menu.order` ascending (resources without an explicit
 * order come last in registration order).
 *
 * Conflicts: duplicate `key` or `path` throws. Re-registration after init
 * is allowed in dev (HMR) but emits a warning.
 */

import type {
  NavigationResource,
  RouteRegistry,
  RouteTree,
  RouteNode,
  MenuTree,
  MenuNode,
  BreadcrumbTrail,
  BreadcrumbItem,
  RegistryUser,
} from '@auraboot/nav-model'
import { evaluateAccess, type AccessUser } from '../access/evaluator.js'

function asAccessUser(u: RegistryUser): AccessUser {
  return {
    permissions: u.permissions instanceof Set ? u.permissions : new Set(u.permissions),
    features: u.features instanceof Set ? u.features : new Set(u.features),
  }
}

function menuConfig(r: NavigationResource): { enabled: boolean; order: number; group?: string } {
  if (r.menu === undefined || r.menu === false) return { enabled: false, order: 0 }
  if (r.menu === true) return { enabled: true, order: Number.POSITIVE_INFINITY }
  return {
    enabled: !r.menu.hidden,
    order: r.menu.order ?? Number.POSITIVE_INFINITY,
    group: r.menu.group,
  }
}

export class RouteRegistryImpl implements RouteRegistry {
  /** Insertion-ordered map keyed by NavigationResource.key. */
  private readonly resources = new Map<string, NavigationResource>()
  /** Reverse path → key index for fast findByPath. */
  private readonly pathIndex = new Map<string, string>()

  register(resource: NavigationResource): void {
    if (this.resources.has(resource.key)) {
      const previous = this.resources.get(resource.key)
      if (previous && previous.path !== resource.path) {
        this.pathIndex.delete(previous.path)
      }
      // Allow re-registration in HMR scenarios but warn.
      // eslint-disable-next-line no-console
      console.warn(`[RouteRegistry] re-registering key=${resource.key} (HMR?)`)
    } else if (this.pathIndex.has(resource.path)) {
      const existing = this.pathIndex.get(resource.path)
      throw new Error(
        `[RouteRegistry] path conflict: '${resource.path}' already owned by key='${existing}', refused new key='${resource.key}'`,
      )
    }
    this.resources.set(resource.key, resource)
    this.pathIndex.set(resource.path, resource.key)

    // Recursively register inline children.
    if (resource.children) {
      for (const child of resource.children) {
        const childWithParent: NavigationResource =
          child.parentKey ? child : { ...child, parentKey: resource.key }
        this.register(childWithParent)
      }
    }
  }

  registerBatch(resources: NavigationResource[]): void {
    // Atomic: snapshot, attempt, rollback on failure.
    const snapshotResources = new Map(this.resources)
    const snapshotPaths = new Map(this.pathIndex)
    try {
      for (const r of resources) this.register(r)
    } catch (err) {
      this.resources.clear()
      for (const [k, v] of snapshotResources) this.resources.set(k, v)
      this.pathIndex.clear()
      for (const [k, v] of snapshotPaths) this.pathIndex.set(k, v)
      throw err
    }
  }

  findByKey(key: string): NavigationResource | undefined {
    return this.resources.get(key)
  }

  findByPath(path: string): NavigationResource | undefined {
    const key = this.pathIndex.get(path)
    return key ? this.resources.get(key) : undefined
  }

  getAll(): readonly NavigationResource[] {
    return Array.from(this.resources.values())
  }

  buildRouteTree(): RouteTree {
    const childrenByParent = new Map<string | undefined, NavigationResource[]>()
    for (const r of this.resources.values()) {
      const arr = childrenByParent.get(r.parentKey) ?? []
      arr.push(r)
      childrenByParent.set(r.parentKey, arr)
    }

    const buildNode = (resource: NavigationResource): RouteNode => ({
      resource,
      children: (childrenByParent.get(resource.key) ?? []).map(buildNode),
    })

    return (childrenByParent.get(undefined) ?? []).map(buildNode)
  }

  buildMenuTree(user: RegistryUser): MenuTree {
    const access = asAccessUser(user)

    const childrenByParent = new Map<string | undefined, NavigationResource[]>()
    for (const r of this.resources.values()) {
      const arr = childrenByParent.get(r.parentKey) ?? []
      arr.push(r)
      childrenByParent.set(r.parentKey, arr)
    }

    const buildMenuNode = (resource: NavigationResource): MenuNode | null => {
      const cfg = menuConfig(resource)
      if (!cfg.enabled) return null

      const decision = evaluateAccess(
        {
          permission: resource.permission as string | string[] | undefined,
          featureKey: resource.featureKey as string | string[] | undefined,
          hidden: resource.hidden,
        },
        access,
      )
      if (!decision.visible) return null

      const children = (childrenByParent.get(resource.key) ?? [])
        .map(buildMenuNode)
        .filter((n): n is MenuNode => n !== null)
        .sort((a, b) => a.order - b.order)

      return {
        key: resource.key,
        path: resource.path,
        title: resource.title,
        icon: resource.icon,
        order: cfg.order,
        group: cfg.group,
        children,
      }
    }

    return (childrenByParent.get(undefined) ?? [])
      .map(buildMenuNode)
      .filter((n): n is MenuNode => n !== null)
      .sort((a, b) => a.order - b.order)
  }

  buildBreadcrumb(path: string): BreadcrumbTrail {
    const target = this.findByPath(path)
    if (!target) return []

    const trail: BreadcrumbItem[] = []
    let cur: NavigationResource | undefined = target
    const visited = new Set<string>()

    while (cur) {
      if (visited.has(cur.key)) break // cycle guard
      visited.add(cur.key)

      const breadcrumbEnabled = cur.breadcrumb !== false
      if (breadcrumbEnabled) {
        trail.unshift({ key: cur.key, path: cur.path, title: cur.title })
      }

      cur = cur.parentKey ? this.findByKey(cur.parentKey) : undefined
    }
    return trail
  }
}
