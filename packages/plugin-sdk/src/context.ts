import type {
  ActionSchema,
  ColumnType,
  DataSourceRef,
  DataSourceType,
  LocalizedText,
  WidgetType,
} from '@auraboot/dsl-types'
import type { NavigationResource } from '@auraboot/nav-model'

/**
 * JSON Schema (subset) used to declare widget/renderer/datasource prop shapes.
 * Validated by the kernel at registration time; surfaced to the Designer to
 * auto-render configuration panels via `PropertyFieldRenderer`.
 */
export type PropsSchema = Record<string, unknown>

export interface WidgetRegistration {
  /** Widget type code (matches `FieldSchema.widget`). */
  type: WidgetType
  /** React component (kept opaque here — declared as `unknown` to avoid React peer dep). */
  component: unknown
  /** Field types this widget can render. Used for default selection. */
  appliesTo?: string[]
  /** JSON Schema describing accepted `widgetProps`. */
  propsSchema?: PropsSchema
  /** Whether this widget overrides an existing registration. Default false (throws on conflict). */
  override?: boolean
}

export interface ColumnRendererRegistration {
  type: ColumnType
  component: unknown
  /** Column types this renderer applies to. */
  appliesTo?: string[]
  propsSchema?: PropsSchema
  override?: boolean
}

export interface SlotRegistration {
  /** Slot key (e.g. `chat.group`, `org.tree-picker`). Defined by core. */
  slot: string
  component: unknown
  /** Slot-level priority when multiple plugins target the same slot. */
  priority?: number
}

export interface ActionRegistration {
  /** Action code referenced by `ActionSchema.command` or registered toolbars. */
  code: string
  /** Async handler. Receives action context (record, params). */
  handler: (...args: unknown[]) => unknown | Promise<unknown>
  /** Optional schema for action params. */
  paramsSchema?: PropsSchema
}

export interface FeatureRegistration {
  key: string
  name: LocalizedText
  description?: LocalizedText
  /** When true, this feature represents a paid capability gate. */
  paid?: boolean
}

export interface PermissionGroupRegistration {
  code: string
  name: LocalizedText
  permissions: Array<{
    code: string
    name: LocalizedText
    description?: LocalizedText
  }>
}

export interface DataSourceProviderRegistration {
  /** Data source type this provider handles. */
  type: DataSourceType
  /** Resolver function — receives `DataSourceRef.source` and `params`, returns data. */
  resolve: (ref: DataSourceRef) => Promise<unknown>
}

/**
 * PluginContext — the only API surface plugins are allowed to use during
 * setup(). Plugins MUST NOT reach into kernel internals; everything they
 * contribute goes through one of these `register*` methods.
 */
export interface PluginContext {
  /** Register a navigable page (route + menu + permission + feature in one). */
  registerNavigationResource(resource: NavigationResource): void
  registerNavigationResources(resources: NavigationResource[]): void

  /** Register a form widget (e.g. money-input, user-picker). */
  registerWidget(reg: WidgetRegistration): void

  /** Register a table column renderer (e.g. status-badge, user-cell). */
  registerColumnRenderer(reg: ColumnRendererRegistration): void

  /** Register an action handler (referenced by ActionSchema.command). */
  registerAction(reg: ActionRegistration): void

  /** Register a UI slot override (e.g. fill in core's chat.group slot). */
  registerSlot(reg: SlotRegistration): void

  /** Declare a feature key this plugin contributes. */
  registerFeature(reg: FeatureRegistration): void

  /** Register a group of related permissions. */
  registerPermissionGroup(reg: PermissionGroupRegistration): void

  /** Register a data source provider for a given DataSourceRef.type. */
  registerDataSourceProvider(reg: DataSourceProviderRegistration): void

  /** Convenience: emit a structured log entry attributed to this plugin. */
  log: {
    debug(msg: string, meta?: Record<string, unknown>): void
    info(msg: string, meta?: Record<string, unknown>): void
    warn(msg: string, meta?: Record<string, unknown>): void
    error(msg: string, meta?: Record<string, unknown>): void
  }

  /** Plugin-scoped action helpers. */
  invoke(actionCode: string, ...args: unknown[]): Promise<unknown>

  /**
   * Read an entitlement at runtime. Prefer declarative `featureKey` on the
   * navigation resource over imperative checks.
   */
  hasFeature(key: string): boolean
}
