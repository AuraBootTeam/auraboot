/**
 * Kernel Plugin Manager — view all plugins registered with the in-process
 * PluginLoader (the M2 kernel runtime). Distinct from the unified
 * /plugins page which manages backend PF4J plugins (JAR uploads).
 *
 * Read-only first cut; enable/disable actions follow once PluginLoader
 * exposes a disable() method (currently only install/enable/activate).
 */
import { useEffect, useMemo, useState } from 'react'
import { useI18n } from '~/contexts/I18nContext'
import { getKernel } from '~/framework/bootstrap'
import type { PluginRecord } from '~/framework/plugins/loader'
import type { PluginKind, PluginState } from '@auraboot/plugin-sdk'

const STATE_COLORS: Record<PluginState, string> = {
  discovered: 'bg-gray-100 text-gray-700 border-gray-300',
  installed: 'bg-blue-100 text-blue-700 border-blue-300',
  enabled: 'bg-amber-100 text-amber-700 border-amber-300',
  licensed: 'bg-purple-100 text-purple-700 border-purple-300',
  active: 'bg-green-100 text-green-700 border-green-300',
}

const KIND_BADGES: Record<PluginKind, string> = {
  core: 'bg-slate-200 text-slate-700',
  oss: 'bg-emerald-100 text-emerald-700',
  enterprise: 'bg-amber-100 text-amber-800',
  solution: 'bg-violet-100 text-violet-700',
}

function StateBadge({ state }: { state: PluginState }) {
  const { t } = useI18n()
  const cls = STATE_COLORS[state] ?? 'bg-gray-100 text-gray-700'
  return (
    <span className={`inline-flex items-center px-2 py-0.5 text-xs font-medium border rounded-full ${cls}`}>
      {t(`kernelPlugins.state.${state}`, undefined, state)}
    </span>
  )
}

function KindBadge({ kind }: { kind: PluginKind }) {
  const { t } = useI18n()
  return (
    <span className={`inline-flex px-1.5 py-0.5 text-xs font-mono rounded ${KIND_BADGES[kind]}`}>
      {t(`kernelPlugins.kind.${kind}`, undefined, kind)}
    </span>
  )
}

function summarize(records: readonly PluginRecord[]) {
  const byState: Record<string, number> = {}
  const byKind: Record<string, number> = {}
  for (const r of records) {
    byState[r.state] = (byState[r.state] ?? 0) + 1
    const k = r.definition.manifest.kind
    byKind[k] = (byKind[k] ?? 0) + 1
  }
  return { byState, byKind, total: records.length }
}

function inactiveReason(reason: string, t: (key: string, params?: Record<string, string>, fallback?: string) => string) {
  const exact: Record<string, string> = { 'application module unavailable': 'moduleUnavailable', 'circular dependency': 'circularDependency' }
  if (exact[reason]) return t(`kernelPlugins.reason.${exact[reason]}`, undefined, reason)
  const reasons: [string, string][] = [
    ['dependency inactive: ', 'dependencyInactive'], ['application module unavailable: ', 'moduleUnavailableDetail'],
    ['missing feature: ', 'missingFeature'], ['setup failed: ', 'setupFailed'],
    ['missing dependency: ', 'missingDependency'], ['dependency not enabled: ', 'dependencyNotEnabled'],
    ['dependency failed: ', 'dependencyFailed'],
  ]
  for (const [prefix, key] of reasons) {
    if (reason.startsWith(prefix)) return t(`kernelPlugins.reason.${key}`, { detail: reason.slice(prefix.length) }, reason)
  }
  const phase = reason.match(/^dependency '(.+)' is in later activation phase '(.+)'$/)
  if (phase) return t('kernelPlugins.reason.laterPhase', { dependency: phase[1], phase: phase[2] }, reason)
  return reason
}

export default function KernelPluginsPage() {
  const { t } = useI18n()
  const [tick, setTick] = useState(0)
  const records = useMemo(() => getKernel().pluginLoader.list(), [tick])
  const summary = useMemo(() => summarize(records), [records])

  // Refresh when boot-plugins finishes the activation pass after first render.
  useEffect(() => {
    const t = setTimeout(() => setTick(n => n + 1), 200)
    return () => clearTimeout(t)
  }, [])

  return (
    <div className="p-6 max-w-7xl mx-auto">
      <header className="mb-6">
        <h1 className="text-2xl font-semibold text-gray-900">{t('kernelPlugins.title', undefined, 'Kernel Plugin Manager')}</h1>
        <p className="mt-1 text-sm text-gray-600">
          {t('kernelPlugins.description', undefined, 'View registered runtime plugins and their activation status. This inventory is read-only.')} {' '}
          <a href="/plugins" className="text-blue-600 hover:underline">{t('kernelPlugins.backendManager', undefined, 'Backend plugin management')}</a>
        </p>
      </header>

      <section className="grid grid-cols-2 md:grid-cols-5 gap-3 mb-6">
        <SummaryCard label={t('kernelPlugins.summary.total', undefined, 'Total')} value={summary.total} accent="bg-gray-50" />
        <SummaryCard label={t('kernelPlugins.summary.active', undefined, 'Active')} value={summary.byState.active ?? 0} accent="bg-green-50" />
        <SummaryCard label={t('kernelPlugins.summary.licensed', undefined, 'Licensed')} value={summary.byState.licensed ?? 0} accent="bg-purple-50" />
        <SummaryCard label={t('kernelPlugins.summary.enabled', undefined, 'Enabled')} value={summary.byState.enabled ?? 0} accent="bg-amber-50" />
        <SummaryCard label={t('kernelPlugins.summary.installed', undefined, 'Installed')} value={summary.byState.installed ?? 0} accent="bg-blue-50" />
      </section>

      <section className="overflow-x-auto bg-white rounded-lg border border-gray-200">
        <table className="min-w-full text-sm">
          <thead className="bg-gray-50 text-gray-600 text-xs uppercase">
            <tr>
              <th className="px-4 py-3 text-left font-semibold">{t('kernelPlugins.column.plugin', undefined, 'Plugin')}</th>
              <th className="px-3 py-3 text-left font-semibold">{t('kernelPlugins.column.kind', undefined, 'Kind')}</th>
              <th className="px-3 py-3 text-left font-semibold">{t('kernelPlugins.column.version', undefined, 'Version')}</th>
              <th className="px-3 py-3 text-left font-semibold">{t('kernelPlugins.column.state', undefined, 'State')}</th>
              <th className="px-3 py-3 text-left font-semibold">{t('kernelPlugins.column.features', undefined, 'Features')}</th>
              <th className="px-3 py-3 text-left font-semibold">{t('kernelPlugins.column.dependencies', undefined, 'Dependencies')}</th>
              <th className="px-3 py-3 text-left font-semibold">{t('kernelPlugins.column.inactiveReason', undefined, 'Inactive Reason')}</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {records.length === 0 && (
              <tr>
                <td colSpan={7} className="px-4 py-12 text-center text-gray-500">
                  {t('kernelPlugins.empty', undefined, 'No plugins registered yet. Startup may still be in progress.')}
                </td>
              </tr>
            )}
            {records.map(r => {
              const m = r.definition.manifest
              const featureKeys = m.license?.featureKeys ?? []
              const deps = m.dependencies?.plugins ?? []
              return (
                <tr key={m.code} className="hover:bg-gray-50">
                  <td className="px-4 py-3 align-top">
                    <div className="font-medium text-gray-900">{t(`plugin.${m.code}.name`, undefined, m.name)}</div>
                    <div className="text-xs text-gray-500 font-mono">{m.code}</div>
                    {m.description && (
                      <div className="text-xs text-gray-500 mt-1 max-w-md">{t(`plugin.${m.code}.description`, undefined, m.description)}</div>
                    )}
                  </td>
                  <td className="px-3 py-3 align-top"><KindBadge kind={m.kind} /></td>
                  <td className="px-3 py-3 align-top text-xs font-mono text-gray-600">{m.version}</td>
                  <td className="px-3 py-3 align-top"><StateBadge state={r.state} /></td>
                  <td className="px-3 py-3 align-top">
                    {featureKeys.length === 0 ? (
                      <span className="text-xs text-gray-400">—</span>
                    ) : (
                      <div className="flex flex-wrap gap-1">
                        {featureKeys.map(f => (
                          <span key={f} className="px-1.5 py-0.5 text-xs bg-purple-50 text-purple-700 rounded font-mono">
                            {f}
                          </span>
                        ))}
                      </div>
                    )}
                  </td>
                  <td className="px-3 py-3 align-top">
                    {deps.length === 0 ? (
                      <span className="text-xs text-gray-400">—</span>
                    ) : (
                      <div className="flex flex-wrap gap-1">
                        {deps.map(d => (
                          <span key={d} className="px-1.5 py-0.5 text-xs bg-gray-100 text-gray-700 rounded font-mono">
                            {d}
                          </span>
                        ))}
                      </div>
                    )}
                  </td>
                  <td className="px-3 py-3 align-top text-xs text-red-600">
                    {r.inactiveReason ? inactiveReason(r.inactiveReason, t) : <span className="text-gray-400">—</span>}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </section>

      <footer className="mt-4 flex items-center justify-between text-xs text-gray-500">
        <div>
          {t('kernelPlugins.registryHelp', undefined, 'Refresh reads the current runtime registry without changing plugin state.')}
        </div>
        <button
          type="button"
          onClick={() => setTick(n => n + 1)}
          className="px-3 py-1 border border-gray-300 rounded hover:bg-gray-50"
        >
          {t('kernelPlugins.refresh', undefined, 'Refresh')}
        </button>
      </footer>
    </div>
  )
}

function SummaryCard({ label, value, accent }: { label: string; value: number; accent: string }) {
  return (
    <div className={`${accent} rounded-lg border border-gray-200 px-4 py-3`}>
      <div className="text-xs text-gray-600">{label}</div>
      <div className="text-2xl font-semibold text-gray-900 mt-1">{value}</div>
    </div>
  )
}
