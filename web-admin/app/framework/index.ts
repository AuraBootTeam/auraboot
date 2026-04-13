/**
 * AuraBoot web-admin framework — public API.
 *
 * Consumers (plugins, app shell) import from `~/framework` only. Internal
 * modules (`./internal/*` once introduced) are off-limits.
 */

// Routing
export { RouteRegistryImpl } from './routing/registry.js'
export { createRouteRegistry } from './routing/factory.js'
export type { RouteRegistryOptions } from './routing/factory.js'

// Plugins
export { PluginLoader } from './plugins/loader.js'
export type { LoaderOptions, PluginRecord } from './plugins/loader.js'

// Extensions / Slots
export { SlotRegistry } from './extensions/slot-registry.js'
export type { SlotRecord } from './extensions/slot-registry.js'

// Widgets
export { WidgetRegistry } from './widgets/widget-registry.js'
export { ColumnRendererRegistry } from './widgets/column-registry.js'
export type {
  WidgetRecord,
  ColumnRendererRecord,
} from './widgets/widget-registry.js'

// Access control
export { evaluateAccess } from './access/evaluator.js'
export type {
  AccessUser,
  AccessRequirement,
  AccessDecision,
} from './access/evaluator.js'

// Data sources
export { DataSourceRegistry } from './data-source/registry.js'
export type { DataSourceProviderRecord } from './data-source/registry.js'
