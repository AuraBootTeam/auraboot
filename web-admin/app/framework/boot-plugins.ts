/**
 * boot-plugins — runs the kernel + activates all bundled OSS plugins.
 *
 * Called once on app startup (from root.tsx) so the kernel's RouteRegistry,
 * WidgetRegistry, etc. are populated before any plugin-registered surfaces
 * are queried (menu rendering, breadcrumb, widget resolution).
 *
 * Plugin discovery is currently static — the imports below are the boot
 * manifest. M4 may move this to a generated file derived from plugin
 * directory scanning.
 */

import { getKernel, resetKernel } from './bootstrap.js'

import coreDemoPlugin from '~/plugins/core-demo'
import coreBpmPlugin from '~/plugins/core-bpm'
import coreDesignerPlugin from '~/plugins/core-designer'
import coreAutomationPlugin from '~/plugins/core-automation'
import coreOrganizationPlugin from '~/plugins/core-organization'
import coreMetaPlugin from '~/plugins/core-meta'
import coreAurabotPlugin from '~/plugins/core-aurabot'
import coreAiColleaguesPlugin from '~/plugins/core-ai-colleagues'
import corePersonalPlugin from '~/plugins/core-personal'
import coreSettingsPlugin from '~/plugins/core-settings'
import corePlatformPlugin from '~/plugins/core-platform'
import coreAdminPlugin from '~/plugins/core-admin'
import coreOpsPlugin from '~/plugins/core-ops'
import coreDashboardPlugin from '~/plugins/core-dashboard'

// Enterprise plugins — empty in OSS, populated by enterprise overlay
// (see auraboot-enterprise/web-admin-ext/.../boot-plugins-ent.ts).
import { ENT_PLUGINS } from './boot-plugins-ent.js'

const CORE_PLUGINS = [
  coreDemoPlugin,
  coreBpmPlugin,
  coreDesignerPlugin,
  coreAutomationPlugin,
  coreOrganizationPlugin,
  coreMetaPlugin,
  coreAurabotPlugin,
  coreAiColleaguesPlugin,
  corePersonalPlugin,
  coreSettingsPlugin,
  corePlatformPlugin,
  coreAdminPlugin,
  coreOpsPlugin,
  coreDashboardPlugin,
]

let bootedOnce = false

/**
 * Install + enable + activate all bundled core plugins.
 *
 * Idempotent: subsequent calls are no-ops unless `force=true` (which resets
 * the kernel singleton). Safe to call multiple times during HMR.
 *
 * @param hasFeature optional entitlement check — defaults to "all features
 *                   enabled" if omitted (suitable for OSS dev). The App
 *                   shell should pass an EntitlementContext-backed check.
 */
export async function bootCorePlugins(opts: { hasFeature?: (key: string) => boolean; force?: boolean } = {}): Promise<readonly string[]> {
  if (bootedOnce && !opts.force) {
    return getKernel().pluginLoader.list().filter(r => r.state === 'active').map(r => r.definition.manifest.code)
  }
  if (opts.force) resetKernel({ hasFeature: opts.hasFeature })

  const kernel = getKernel()
  const ALL_PLUGINS = [...CORE_PLUGINS, ...ENT_PLUGINS]
  for (const plugin of ALL_PLUGINS) {
    try {
      kernel.pluginLoader.install(plugin)
      kernel.pluginLoader.enable(plugin.manifest.code)
    } catch (err) {
      // eslint-disable-next-line no-console
      console.warn(`[boot-plugins] install failed for ${plugin.manifest.code}:`, err)
    }
  }

  const activated = await kernel.pluginLoader.activateAll()
  bootedOnce = true
  // eslint-disable-next-line no-console
  console.info(`[boot-plugins] activated ${activated.length}/${ALL_PLUGINS.length} plugins (${CORE_PLUGINS.length} core + ${ENT_PLUGINS.length} ent):`, activated.join(', '))
  return activated
}

/** Test-only: reset bootedOnce so a fresh boot can run. */
export function _resetBootState(): void {
  bootedOnce = false
}
