export type {
  PluginManifest,
  PluginKind,
  PluginDependencies,
  PluginLicenseRequirement,
  PluginCompatibility,
} from './manifest.js'

export type {
  PluginDefinition,
  PluginSetupFn,
} from './definition.js'

export type {
  PluginContext,
  WidgetRegistration,
  ColumnRendererRegistration,
  ActionRegistration,
  SlotRegistration,
  FeatureRegistration,
  PermissionGroupRegistration,
  DataSourceProviderRegistration,
} from './context.js'

export type { PluginState } from './lifecycle.js'

export { definePlugin } from './definition.js'
