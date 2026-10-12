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
import coreEmailPlugin from '~/plugins/core-email'
import coreDecisionOpsPlugin from '~/plugins/core-decisionops'

// Build-time composition manifest. OSS resolves the virtual module to an
// empty list; Enterprise points AURA_WEB_CONTRIBUTION_MANIFEST at its private
// generated manifest. Browser boot consumes this runtime manifest; the route
// manifest separately feeds the shared SSR/client React Router build graph.
import { ENTERPRISE_PLUGINS } from 'virtual:auraboot-web-contributions'

const CORE_PLUGINS = [
  coreDemoPlugin,
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
  coreEmailPlugin,
  coreDecisionOpsPlugin,
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
  const existingKernel = getKernel()
  if (opts.hasFeature && !opts.force) {
    existingKernel.pluginLoader.setFeatureGate(opts.hasFeature)
  }
  if (bootedOnce && !opts.force) {
    return existingKernel.pluginLoader.list().filter(r => r.state === 'active').map(r => r.definition.manifest.code)
  }
  if (opts.force) resetKernel({ hasFeature: opts.hasFeature })

  const kernel = getKernel()
  const ALL_PLUGINS = [...CORE_PLUGINS, ...ENTERPRISE_PLUGINS]
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
  registerBuiltInRuntimeActions(kernel)
  bootedOnce = true
  // eslint-disable-next-line no-console
  console.info(`[boot-plugins] activated ${activated.length}/${ALL_PLUGINS.length} plugins (${CORE_PLUGINS.length} core + ${ENTERPRISE_PLUGINS.length} ent):`, activated.join(', '))
  return activated
}

/** Test-only: reset bootedOnce so a fresh boot can run. */
export function _resetBootState(): void {
  bootedOnce = false
}

/**
 * Host-level runtime action defaults for codes the OSS web bundle ships no
 * plugin implementation for. The workflow.start invocation (useActionHandler
 * + unified runtime execution) otherwise throws "no action registered" before
 * any request fires. A BPM web package can still claim the code first — this
 * runs after activation and never overrides a plugin handler.
 */
type WorkflowStartPayload = {
  workflowKey: string
  businessKey: string
  variables?: Record<string, unknown>
}

async function defaultWorkflowStart(payload: WorkflowStartPayload): Promise<unknown> {
  const { fetchResult } = await import('~/shared/services/http-client')
  const result = await fetchResult('/api/bpm/process-instances', {
    method: 'post',
    params: {
      processDefinitionId: payload.workflowKey,
      businessKey: payload.businessKey,
      variables: payload.variables ?? {},
    },
  })
  const code = String((result as { code?: unknown })?.code ?? '')
  if (code === '0') {
    const data = (result as { data?: { instanceId?: unknown; processInstanceId?: unknown } })?.data
    const processInstanceId = String(
      data?.instanceId ?? data?.processInstanceId ?? '',
    )
    return { deduped: false, processInstanceId }
  }
  const scoped = result as { desc?: unknown; message?: unknown }
  const desc = String(scoped.desc ?? scoped.message ?? '')
  // A running instance for the same business key is a benign duplicate start.
  if (/already|running|conflict|409/i.test(`${code} ${desc}`)) return { deduped: true }
  throw new Error(`workflow.start failed: ${code} ${desc}`.trim())
}

function registerBuiltInRuntimeActions(kernel: ReturnType<typeof getKernel>): void {
  if (kernel.pluginLoader.hasAction('workflow.start')) return
  kernel.pluginLoader.registerHostAction('workflow.start', (payload) =>
    defaultWorkflowStart(payload as WorkflowStartPayload))
}
