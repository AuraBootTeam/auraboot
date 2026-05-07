import type { LocalizedText } from '@auraboot/dsl-types'

/**
 * Async component loader. Returns a default export. Compatible with
 * `() => import('./Page')` and React.lazy patterns.
 */
export type ComponentLoader = () => Promise<{ default: unknown }>

export type LayoutKind = 'admin' | 'auth' | 'blank' | (string & {})

export type ResourceSource = 'core' | 'plugin' | 'runtime'

export interface MenuConfig {
  /** Menu sort order within its group/parent. Lower = earlier. */
  order?: number
  /** Menu group key — for sectioned sidebars. */
  group?: string
  /** Hide from menu but keep route registered. Useful for detail/edit pages. */
  hidden?: boolean
}

/**
 * NavigationResource — single declaration for a navigable page.
 *
 * The kernel's RouteRegistry derives:
 *
 *   - React Router tree     (from path + component + children)
 *   - Breadcrumb trail      (from parentKey chain + breadcrumb flag)
 *   - Tab strip             (from tab flag)
 *
 * Sidebar menu rendering is owned by the backend (`/api/menu/user`,
 * sourced from `ab_menu` populated by plugin `menus.json`); permission
 * gating happens server-side via `@RequirePermission` on the
 * Controllers. This interface intentionally does NOT carry `permission`
 * or `featureKey` fields — front-end client-side menu gating was
 * removed in favor of the single backend source of truth (see
 * auraboot-enterprise/docs/standards/meta/permission-code-naming.md
 * §5 Q3).
 */
export interface NavigationResource {
  /** Globally unique key (across all plugins). Convention: `<plugin>.<page>`. */
  key: string

  /** URL path. Supports React Router 7 syntax including params (`:id`). */
  path: string

  /** Display title for menu, breadcrumb, document title. */
  title: LocalizedText

  /** Icon code (resolved by the icon registry). */
  icon?: string

  /** Component bound to this route. Either eager component or lazy loader. */
  component?: unknown
  loader?: ComponentLoader

  /** Layout shell to wrap the route. Defaults to `admin`. */
  layout?: LayoutKind

  /** Parent key for nested routes / breadcrumb chain. */
  parentKey?: string

  /** Nested resources under this one. Alternative to parentKey. */
  children?: NavigationResource[]

  /** Whether this resource appears in the sidebar menu. Boolean is shorthand for `{ }`. */
  menu?: boolean | MenuConfig

  /** Whether this resource contributes to breadcrumb. Default: true. */
  breadcrumb?: boolean

  /** Whether this resource is shown in the top tab strip when navigated. Default: true. */
  tab?: boolean

  /** Hide from menu but keep route accessible. */
  hidden?: boolean

  /** Origin tracking — useful for debugging and admin UI. */
  source: ResourceSource
  plugin?: string

  /** Free-form metadata (route guards, telemetry tags, etc). */
  meta?: Record<string, unknown>
}
