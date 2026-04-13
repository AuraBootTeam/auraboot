/**
 * Kernel Plugin Manager — view all plugins registered with the in-process
 * PluginLoader (the M2 kernel runtime). Distinct from the legacy
 * /system/plugins page which manages backend PF4J plugins (JAR uploads).
 *
 * Read-only first cut; enable/disable actions follow once PluginLoader
 * exposes a disable() method (currently only install/enable/activate).
 */
import { useEffect, useMemo, useState } from 'react'
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
  const cls = STATE_COLORS[state] ?? 'bg-gray-100 text-gray-700'
  return (
    <span className={`inline-flex items-center px-2 py-0.5 text-xs font-medium border rounded-full ${cls}`}>
      {state}
    </span>
  )
}

function KindBadge({ kind }: { kind: PluginKind }) {
  return (
    <span className={`inline-flex px-1.5 py-0.5 text-xs font-mono rounded ${KIND_BADGES[kind]}`}>
      {kind}
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

export default function KernelPluginsPage() {
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
        <h1 className="text-2xl font-semibold text-gray-900">Kernel Plugin Manager</h1>
        <p className="mt-1 text-sm text-gray-600">
          Plugins registered with the in-process PluginLoader. For backend (PF4J) plugin
          management, see <a href="/system/plugins" className="text-blue-600 hover:underline">/system/plugins</a>.
        </p>
      </header>

      <section className="grid grid-cols-2 md:grid-cols-5 gap-3 mb-6">
        <SummaryCard label="Total" value={summary.total} accent="bg-gray-50" />
        <SummaryCard label="Active" value={summary.byState.active ?? 0} accent="bg-green-50" />
        <SummaryCard label="Licensed" value={summary.byState.licensed ?? 0} accent="bg-purple-50" />
        <SummaryCard label="Enabled" value={summary.byState.enabled ?? 0} accent="bg-amber-50" />
        <SummaryCard label="Installed" value={summary.byState.installed ?? 0} accent="bg-blue-50" />
      </section>

      <section className="overflow-x-auto bg-white rounded-lg border border-gray-200">
        <table className="min-w-full text-sm">
          <thead className="bg-gray-50 text-gray-600 text-xs uppercase">
            <tr>
              <th className="px-4 py-3 text-left font-semibold">Plugin</th>
              <th className="px-3 py-3 text-left font-semibold">Kind</th>
              <th className="px-3 py-3 text-left font-semibold">Version</th>
              <th className="px-3 py-3 text-left font-semibold">State</th>
              <th className="px-3 py-3 text-left font-semibold">Features</th>
              <th className="px-3 py-3 text-left font-semibold">Dependencies</th>
              <th className="px-3 py-3 text-left font-semibold">Inactive Reason</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {records.length === 0 && (
              <tr>
                <td colSpan={7} className="px-4 py-12 text-center text-gray-500">
                  No plugins installed yet. Boot may still be in progress.
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
                    <div className="font-medium text-gray-900">{m.name}</div>
                    <div className="text-xs text-gray-500 font-mono">{m.code}</div>
                    {m.description && (
                      <div className="text-xs text-gray-500 mt-1 max-w-md">{m.description}</div>
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
                    {r.inactiveReason ?? <span className="text-gray-400">—</span>}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </section>

      <footer className="mt-4 flex items-center justify-between text-xs text-gray-500">
        <div>
          Kernel state lives in-memory; refresh re-reads the registry.
        </div>
        <button
          type="button"
          onClick={() => setTick(n => n + 1)}
          className="px-3 py-1 border border-gray-300 rounded hover:bg-gray-50"
        >
          Refresh
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
