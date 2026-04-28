/**
 * Kernel bootstrap — instantiates the framework singletons and wires them
 * together. Importing this module gives consumers (App shell, plugin
 * discoverer, tests) a single ready-to-use kernel surface.
 *
 * Singleton lifetime: per-module-graph. In SSR, each request gets a fresh
 * kernel via createKernel(); in the browser, the singleton lasts for the
 * tab's lifetime (recreated on full reload, preserved across HMR).
 */

import type { PluginContext } from '@auraboot/plugin-sdk'
import { RouteRegistryImpl } from './routing/registry.js'
import { PluginLoader, type LoaderOptions } from './plugins/loader.js'
import { SlotRegistry } from './extensions/slot-registry.js'
import { WidgetRegistry, ColumnRendererRegistry } from './widgets/widget-registry.js'
import { DataSourceRegistry } from './data-source/registry.js'
import { initBlockRegistry } from '~/ui/schema-renderer/BlockRegistry'
import { initViewRegistry } from '~/ui/schema-renderer/ViewRegistry'

export interface Kernel {
  routeRegistry: RouteRegistryImpl
  pluginLoader: PluginLoader
  slotRegistry: SlotRegistry
  widgetRegistry: WidgetRegistry
  columnRegistry: ColumnRendererRegistry
  dataSourceRegistry: DataSourceRegistry
}

export interface KernelOptions {
  /**
   * Feature-key entitlement check. Defaults to `() => true` (all features
   * unlocked) — production should pass an EntitlementContext-backed check.
   */
  hasFeature?: LoaderOptions['hasFeature']
}

/**
 * Build a fresh kernel. Use this in SSR and tests; the browser app should
 * use the `kernel` singleton exported below.
 */
export function createKernel(opts: KernelOptions = {}): Kernel {
  // Eager registry init — lazy registration silently breaks schema-driven
  // panels (memory: feedback_g1_init_registry_bootstrap). Both calls are
  // idempotent.
  initBlockRegistry()
  initViewRegistry()

  const routeRegistry = new RouteRegistryImpl()
  const slotRegistry = new SlotRegistry()
  const widgetRegistry = new WidgetRegistry()
  const columnRegistry = new ColumnRendererRegistry()
  const dataSourceRegistry = new DataSourceRegistry()

  const pluginLoader = new PluginLoader({
    routeRegistry,
    slotRegistry,
    widgetRegistry,
    columnRegistry,
    dataSourceRegistry,
    hasFeature: opts.hasFeature ?? (() => true),
  })

  return {
    routeRegistry,
    pluginLoader,
    slotRegistry,
    widgetRegistry,
    columnRegistry,
    dataSourceRegistry,
  }
}

/**
 * Browser singleton kernel. The first import builds it; subsequent
 * imports get the same instance. Prefer createKernel() in SSR.
 *
 * NOTE: the browser singleton is created with hasFeature=() => true.
 * The App shell should re-create or augment via EntitlementContext on
 * mount before activating plugins.
 */
let _kernel: Kernel | null = null
export function getKernel(): Kernel {
  if (!_kernel) _kernel = createKernel()
  return _kernel
}

/**
 * Convenience: rebuild the kernel singleton with new options. Useful when
 * the EntitlementContext loads asynchronously and the app needs to swap
 * in a real hasFeature predicate before activating plugins.
 */
export function resetKernel(opts: KernelOptions = {}): Kernel {
  _kernel = createKernel(opts)
  return _kernel
}

export type { PluginContext }
