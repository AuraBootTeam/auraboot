import type { PluginManifest } from './manifest.js'
import type { PluginContext } from './context.js'

export type PluginSetupFn = (ctx: PluginContext) => void | Promise<void>

export interface PluginDefinition {
  manifest: PluginManifest
  setup: PluginSetupFn
}

/**
 * Type-safe plugin entrypoint. Returns the definition unchanged at runtime;
 * provides full inference and ensures the manifest matches the contract.
 *
 * @example
 * ```ts
 * export default definePlugin({
 *   manifest: { code: 'my.plugin', name: 'My Plugin', version: '0.1.0', kind: 'oss' },
 *   setup(ctx) { ctx.registerNavigationResource({ ... }) }
 * })
 * ```
 */
export function definePlugin(def: PluginDefinition): PluginDefinition {
  return def
}
