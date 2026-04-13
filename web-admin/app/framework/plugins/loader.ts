/**
 * PluginLoader — discovers, validates, and activates plugins.
 *
 * Lifecycle (mirrors @auraboot/plugin-sdk PluginState):
 *   discovered → installed → enabled → licensed → active
 *
 * In M2 web-admin, plugins are statically discovered (imported from the
 * generated plugin manifest). Future iterations may add dynamic discovery
 * (Module Federation, Vite virtual modules).
 */

import type {
  PluginDefinition,
  PluginContext,
  PluginState,
} from '@auraboot/plugin-sdk'
import type { NavigationResource, RouteRegistry } from '@auraboot/nav-model'
import type { DataSourceRef } from '@auraboot/dsl-types'

import type { SlotRegistry } from '../extensions/slot-registry.js'
import type { WidgetRegistry, ColumnRendererRegistry } from '../widgets/widget-registry.js'
import type { DataSourceRegistry } from '../data-source/registry.js'

export interface LoaderOptions {
  routeRegistry: RouteRegistry
  slotRegistry: SlotRegistry
  widgetRegistry: WidgetRegistry
  columnRegistry: ColumnRendererRegistry
  dataSourceRegistry: DataSourceRegistry
  /** Returns true if a feature key is entitled for the current tenant/user. */
  hasFeature: (key: string) => boolean
}

export interface PluginRecord {
  definition: PluginDefinition
  state: PluginState
  /** Reason the plugin is not active, when state !== 'active'. */
  inactiveReason?: string
}

type ActionHandler = (...args: unknown[]) => unknown | Promise<unknown>

export class PluginLoader {
  private readonly records = new Map<string, PluginRecord>()
  private readonly actions = new Map<string, ActionHandler>()

  constructor(private readonly opts: LoaderOptions) {}

  /** Register a plugin definition (transition discovered → installed). */
  install(definition: PluginDefinition): void {
    const code = definition.manifest.code
    if (this.records.has(code)) {
      throw new Error(`[PluginLoader] plugin '${code}' already installed`)
    }
    this.records.set(code, { definition, state: 'installed' })
  }

  /** Mark a plugin enabled (admin opted in). */
  enable(code: string): void {
    const record = this.requireRecord(code)
    if (record.state !== 'installed' && record.state !== 'enabled') {
      throw new Error(`[PluginLoader] cannot enable from state='${record.state}': ${code}`)
    }
    record.state = 'enabled'
  }

  /**
   * Activate all enabled plugins. Resolves dependencies, checks features,
   * and invokes each plugin's setup(ctx).
   *
   * Returns the list of plugins that successfully activated.
   */
  async activateAll(): Promise<readonly string[]> {
    const order = this.computeActivationOrder()
    const activated: string[] = []

    for (const code of order) {
      const record = this.records.get(code)!
      if (record.state !== 'enabled') continue

      // License gate
      const featureKeys = record.definition.manifest.license?.featureKeys ?? []
      const missingFeature = featureKeys.find(k => !this.opts.hasFeature(k))
      if (missingFeature) {
        record.state = 'enabled'
        record.inactiveReason = `missing feature: ${missingFeature}`
        continue
      }
      record.state = 'licensed'

      try {
        await record.definition.setup(this.makeContext(code))
        record.state = 'active'
        activated.push(code)
      } catch (err) {
        record.state = 'licensed'
        record.inactiveReason = `setup failed: ${err instanceof Error ? err.message : String(err)}`
        // eslint-disable-next-line no-console
        console.error(`[PluginLoader] activation failed for ${code}:`, err)
      }
    }

    return activated
  }

  /** All known plugins with their current state. Useful for admin UIs. */
  list(): readonly PluginRecord[] {
    return Array.from(this.records.values())
  }

  /** Invoke a registered action by code. */
  async invoke(code: string, ...args: unknown[]): Promise<unknown> {
    const handler = this.actions.get(code)
    if (!handler) throw new Error(`[PluginLoader] no action registered for code='${code}'`)
    return handler(...args)
  }

  private requireRecord(code: string): PluginRecord {
    const record = this.records.get(code)
    if (!record) throw new Error(`[PluginLoader] plugin '${code}' not installed`)
    return record
  }

  /**
   * Topological sort by `dependencies.plugins`. Plugins with unresolved
   * deps remain in 'enabled' state and will not be activated.
   */
  private computeActivationOrder(): string[] {
    const order: string[] = []
    const visited = new Set<string>()
    const visiting = new Set<string>()

    const visit = (code: string): boolean => {
      if (visited.has(code)) return true
      if (visiting.has(code)) {
        const record = this.records.get(code)
        if (record) record.inactiveReason = 'circular dependency'
        return false
      }
      const record = this.records.get(code)
      if (!record) return false
      if (record.state !== 'enabled') return true

      visiting.add(code)
      const deps = record.definition.manifest.dependencies?.plugins ?? []
      for (const dep of deps) {
        if (!this.records.has(dep)) {
          record.inactiveReason = `missing dependency: ${dep}`
          visiting.delete(code)
          return false
        }
        if (!visit(dep)) {
          record.inactiveReason = `dependency failed: ${dep}`
          visiting.delete(code)
          return false
        }
      }
      visiting.delete(code)
      visited.add(code)
      order.push(code)
      return true
    }

    for (const code of this.records.keys()) visit(code)
    return order
  }

  private makeContext(pluginCode: string): PluginContext {
    const { routeRegistry, slotRegistry, widgetRegistry, columnRegistry, dataSourceRegistry, hasFeature } = this.opts

    const log = (level: 'debug' | 'info' | 'warn' | 'error', msg: string, meta?: Record<string, unknown>) => {
      // eslint-disable-next-line no-console
      console[level === 'debug' ? 'log' : level](`[plugin:${pluginCode}] ${msg}`, meta ?? '')
    }

    return {
      registerNavigationResource: (resource: NavigationResource) => {
        routeRegistry.register({ ...resource, plugin: resource.plugin ?? pluginCode })
      },
      registerNavigationResources: (resources: NavigationResource[]) => {
        routeRegistry.registerBatch(resources.map(r => ({ ...r, plugin: r.plugin ?? pluginCode })))
      },
      registerWidget: (reg) => {
        widgetRegistry.register({ ...reg, plugin: pluginCode })
      },
      registerColumnRenderer: (reg) => {
        columnRegistry.register({ ...reg, plugin: pluginCode })
      },
      registerAction: (reg) => {
        if (this.actions.has(reg.code)) {
          throw new Error(`[plugin:${pluginCode}] action '${reg.code}' already registered`)
        }
        this.actions.set(reg.code, reg.handler)
      },
      registerSlot: (reg) => {
        slotRegistry.register({ ...reg, plugin: pluginCode })
      },
      registerFeature: (_reg) => {
        // Feature declarations are kernel-level metadata; runtime gating uses
        // the entitlement context. We accept the call to keep plugin code
        // declarative — actual gating happens via hasFeature().
      },
      registerPermissionGroup: (_reg) => {
        // Same: permissions are declared in plugin manifests + permissions.json
        // files at build time. Runtime registration is a no-op for now.
      },
      registerDataSourceProvider: (reg) => {
        dataSourceRegistry.register({ ...reg, plugin: pluginCode })
      },
      log: {
        debug: (msg, meta) => log('debug', msg, meta),
        info: (msg, meta) => log('info', msg, meta),
        warn: (msg, meta) => log('warn', msg, meta),
        error: (msg, meta) => log('error', msg, meta),
      },
      invoke: (code, ...args) => this.invoke(code, ...args),
      hasFeature,
    }
  }
}

export type { DataSourceRef }
