import React, { useEffect, useMemo, useState } from 'react'
import type { BlockConfig } from '~/framework/meta/schemas/types'
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime'
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils'
import { readPath, resolveRuntimeValue, useRuntimeStateSubscription } from './workbenchBlockUtils'
import { fetchResult } from '~/shared/services/http-client'
import { ResultHelper } from '~/utils/type'
import { cn } from '~/utils/cn'

export interface DagCanvasBlockRendererProps {
  block: BlockConfig
  runtime: SchemaRuntime
}

export type DeliveryNodeStatus = 'pending' | 'running' | 'completed' | 'failed' | 'skipped'

type Localized = string | Record<string, string>

type NodeConfig = {
  key: string
  label: string | Record<string, string>
  /** Conditional gate: with whenValues the node requires the field to match one of
   * them; without them the field merely has to be non-empty. Otherwise 'skipped'. */
  whenField?: string
  whenValues?: string[]
  /** Field-based completion: the node is complete when this record field equals doneValue. */
  doneField?: string
  doneValue?: string
  /** Record status values that mark this node complete. */
  doneStatuses?: string[]
  /** Record status values that mark this node actively running. */
  runningStatuses?: string[]
  /** Record status values that mark this node failed. */
  failedStatuses?: string[]
  /** The node is complete unconditionally (a fact completed before the run started). */
  alwaysComplete?: boolean
  // ---- Node detail panel (contract facts; static per node key) ----
  /** What kind of task this node represents. */
  taskType?: Localized
  /** What the node may touch (影响范围 row). */
  effect?: Localized
  /** Record field paths resolved as the node's 输入 row. */
  inputFields?: string[]
  /** Record field paths resolved as the node's 输出 row. */
  outputFields?: string[]
  /** Who may retry this node (重试负责人 row). */
  retryOwner?: Localized
  /** The retry boundary contract (重试边界 row). */
  retryBoundary?: Localized
  /** Persistent note under the facts (e.g. an approval wait occupies no worker). */
  notice?: Localized
}

type TerminalConfig = {
  field: string
  value: string
  label: string | Record<string, string>
}

type DagCanvasConfig = {
  title?: string | Record<string, string>
  statusField?: string
  nodes?: NodeConfig[]
  terminal?: TerminalConfig[]
  /** Record field holding worker-attested per-phase timings; entries
   * are matched to nodes by their phase/name/key field and shown as the 耗时 row. */
  phasesField?: string
  /** Shown under the steps while none is selected. */
  hint?: Localized
}

const STATUS_WORD: Record<DeliveryNodeStatus, Localized> = {
  pending: { 'zh-CN': '未开始', en: 'Not started' },
  running: { 'zh-CN': '运行中', en: 'Running' },
  completed: { 'zh-CN': '已完成', en: 'Completed' },
  failed: { 'zh-CN': '失败', en: 'Failed' },
  skipped: { 'zh-CN': '跳过', en: 'Skipped' },
}

const STATUS_PILL: Record<DeliveryNodeStatus, string> = {
  pending: 'border-border bg-panel text-text-2',
  running: 'border-status-blue bg-status-blue-bg text-status-blue',
  completed: 'border-status-green bg-status-green-bg text-status-green',
  failed: 'border-status-red bg-status-red-bg text-status-red',
  skipped: 'border-border bg-panel text-text-2',
}

/** The round marker of a step. `symbol` is the glyph inside it; `dashed` marks a step that was not taken. */
const STATUS_MARKER: Record<DeliveryNodeStatus, { symbol: string; className: string; dashed?: boolean }> = {
  completed: { symbol: '✓', className: 'border-status-green bg-status-green text-white' },
  running: { symbol: '●', className: 'border-status-blue bg-status-blue text-white' },
  failed: { symbol: '✕', className: 'border-status-red bg-status-red text-white' },
  skipped: { symbol: '–', className: 'border-border border-dashed bg-panel text-text-2', dashed: true },
  pending: { symbol: '', className: 'border-border bg-panel text-text-2' },
}

const ROW_LABELS = {
  taskType: { 'zh-CN': '任务类型', en: 'Task type' },
  effect: { 'zh-CN': '影响范围', en: 'Effect' },
  inputs: { 'zh-CN': '输入', en: 'Inputs' },
  outputs: { 'zh-CN': '输出', en: 'Outputs' },
  retryOwner: { 'zh-CN': '重试负责人', en: 'Retry owner' },
  retryBoundary: { 'zh-CN': '重试边界', en: 'Retry boundary' },
  duration: { 'zh-CN': '耗时', en: 'Duration' },
} satisfies Record<string, Localized>

const DEFAULT_HINT: Localized = {
  'zh-CN': '点击任一步骤，查看它的输入、输出与重试方式',
  en: 'Select a step to see its inputs, outputs and how it may be retried',
}

/**
 * Derives per-node runtime statuses from the record, honoring conditional
 * gates before the status ladder. Vocabulary matches the flow-designer-sdk
 * runtime overlay: 'pending' | 'running' | 'completed' | 'failed' | 'skipped'.
 */
export function deriveDeliveryNodeStatuses(
  nodes: NodeConfig[],
  statusField: string,
  record: Record<string, unknown>,
): Record<string, DeliveryNodeStatus> {
  const status = String(readPath(record, statusField) ?? '')
  const map: Record<string, DeliveryNodeStatus> = {}
  for (const node of nodes) {
    if (node.whenField) {
      const value = String(readPath(record, node.whenField) ?? '')
      const passes = node.whenValues
        ? node.whenValues.includes(value)
        : value !== '' && value !== 'null'
      if (!passes) {
        map[node.key] = 'skipped'
        continue
      }
    }
    if ((node.failedStatuses ?? []).includes(status)) {
      map[node.key] = 'failed'
      continue
    }
    if (
      node.doneField &&
      String(readPath(record, node.doneField) ?? '') === node.doneValue
    ) {
      map[node.key] = 'completed'
      continue
    }
    if (node.alwaysComplete || (node.doneStatuses ?? []).includes(status)) {
      map[node.key] = 'completed'
      continue
    }
    if ((node.runningStatuses ?? []).includes(status)) {
      map[node.key] = 'running'
      continue
    }
    map[node.key] = 'pending'
  }
  return map
}

/** Parses the worker-attested phases list (JSON string or already-decoded array). */
export function parsePhasesList(value: unknown): Record<string, unknown>[] {
  let parsed = value
  if (typeof parsed === 'string') {
    try {
      parsed = JSON.parse(parsed)
    } catch {
      return []
    }
  }
  return Array.isArray(parsed) ? (parsed as Record<string, unknown>[]) : []
}

/** Seconds attested by a phase entry; worker entries carry `seconds`, ms fields are normalized. */
export function phaseSeconds(entry: Record<string, unknown> | null): number | null {
  if (!entry) return null
  const direct = entry.seconds ?? entry.elapsedSeconds
  if (direct !== undefined && direct !== null && direct !== '') {
    const n = Number(direct)
    if (!Number.isNaN(n)) return n
  }
  const ms = entry.elapsedMs ?? entry.durationMs
  if (ms !== undefined && ms !== null && ms !== '') {
    const n = Number(ms)
    if (!Number.isNaN(n)) return Math.round(n / 1000)
  }
  return null
}

function phaseForEntry(
  phases: Record<string, unknown>[],
  key: string,
): Record<string, unknown> | null {
  return (
    phases.find((entry) => String(entry.phase ?? entry.name ?? entry.key ?? '') === key) || null
  )
}

// ---- field labels: the model's own display names, fetched once per model -----------------------------
// Without them the panel can only print the field code (`orb_drun_release`), which is the model's
// internal name and means nothing to the person reading the run.
const fieldLabelCache = new Map<string, Record<string, string>>()
/** Test-only: forget the fetched labels so one test's model cannot leak into the next. */
export function __resetFieldLabelCache(): void {
  fieldLabelCache.clear()
}

interface FieldMetaRow {
  code?: string
  displayName?: unknown
  extension?: { displayName?: unknown }
}

/** `field code -> display name` for one model; empty until the lookup lands, and on any failure. */
export function useModelFieldLabels(modelCode: string | undefined): Record<string, string> {
  const [labels, setLabels] = useState<Record<string, string>>({})
  useEffect(() => {
    if (!modelCode) {
      setLabels({})
      return
    }
    const cached = fieldLabelCache.get(modelCode)
    if (cached) {
      setLabels(cached)
      return
    }
    let cancelled = false
    setLabels({})
    fetchResult<FieldMetaRow[]>(`/api/dynamic/${modelCode}/field-meta`, { method: 'get' })
      .then((result) => {
        if (cancelled) return
        const map: Record<string, string> = {}
        if (ResultHelper.isSuccess(result) && Array.isArray(result.data)) {
          for (const field of result.data) {
            if (!field?.code) continue
            const name = [field.displayName, field.extension?.displayName].find(
              (candidate) => typeof candidate === 'string' && candidate.trim(),
            )
            if (typeof name === 'string') map[field.code] = name.trim()
          }
        }
        fieldLabelCache.set(modelCode, map)
        setLabels(map)
      })
      .catch(() => {
        if (!cancelled) setLabels({})
      })
    return () => {
      cancelled = true
    }
  }, [modelCode])
  return labels
}

/**
 * The model of the page's record. The page schema names it (`modelCode`, e.g. `orb_deployment_run`);
 * a page that does not is assumed to follow the naming rule `<modelCode>_<kind>`.
 */
export function pageModelCode(runtime: SchemaRuntime): string | undefined {
  let schema: { modelCode?: unknown; pageKey?: unknown } | undefined
  try {
    schema = runtime.getSchema() as { modelCode?: unknown; pageKey?: unknown } | undefined
  } catch {
    schema = undefined
  }
  if (typeof schema?.modelCode === 'string' && schema.modelCode) return schema.modelCode
  const key = typeof schema?.pageKey === 'string' ? schema.pageKey : ''
  return key.replace(/_(detail|list|form|edit|new|view)$/, '') || undefined
}

const LONG_VALUE = 40
const HEAD = 24
const TAIL = 8
/** Shared "nothing" objects, so memoised derivations do not see a fresh empty input every render. */
const EMPTY_RECORD: Record<string, unknown> = {}
const EMPTY_NODES: NodeConfig[] = []

/**
 * What the panel prints for one record field: the referenced record's name when the record carries
 * it (`<field>_display`), else the value itself. Digests and keys stay recognisable by their head and
 * tail; the whole value is handed back for the tooltip instead of being cut silently.
 */
export function fieldDisplayValue(
  record: Record<string, unknown>,
  field: string,
): { text: string; full?: string } | null {
  const raw = readPath(record, field)
  if (raw === null || raw === undefined || raw === '') return null
  const display = readPath(record, `${field}_display`)
  const full = String(display ?? raw)
  if (full.length <= LONG_VALUE) return { text: full }
  return { text: `${full.slice(0, HEAD)}…${full.slice(-TAIL)}`, full }
}

/** The last legible fallback for a field name: the code without its model namespace prefix. */
function fallbackFieldLabel(field: string): string {
  return field.replace(/^[a-z]+_[a-z]+_/, '')
}

/** Side panel: contract facts per step + the step's values resolved from the run record. */
function NodeDetailPanel({
  node,
  status,
  phases,
  record,
  locale,
  t,
  fieldLabels,
  onClose,
}: {
  node: NodeConfig
  status: DeliveryNodeStatus
  phases: Record<string, unknown>[]
  record: Record<string, unknown>
  locale: string
  t: (key: string) => string
  fieldLabels: Record<string, string>
  onClose: () => void
}) {
  const localizedLabel = (label: Localized | undefined) =>
    label === undefined ? '—' : getLocalizedText(label, locale, t)
  const fieldItems = (fields: string[] | undefined) =>
    (fields ?? [])
      .map((field) => ({ field, value: fieldDisplayValue(record, field) }))
      .filter((item): item is { field: string; value: { text: string; full?: string } } => item.value !== null)
      .map((item) => ({
        field: item.field,
        label: fieldLabels[item.field] ?? fallbackFieldLabel(item.field),
        text: item.value.text,
        full: item.value.full,
      }))
  const seconds = phaseSeconds(phaseForEntry(phases, node.key))
  const facts: Array<{ label: Localized; value: string }> = [
    { label: ROW_LABELS.taskType, value: localizedLabel(node.taskType) },
    { label: ROW_LABELS.effect, value: localizedLabel(node.effect) },
    { label: ROW_LABELS.retryOwner, value: localizedLabel(node.retryOwner) },
    { label: ROW_LABELS.retryBoundary, value: localizedLabel(node.retryBoundary) },
    { label: ROW_LABELS.duration, value: seconds === null ? '—' : `${seconds}s` },
  ]
  const fieldRows: Array<{ label: Localized; items: ReturnType<typeof fieldItems> }> = [
    { label: ROW_LABELS.inputs, items: fieldItems(node.inputFields) },
    { label: ROW_LABELS.outputs, items: fieldItems(node.outputFields) },
  ]
  return (
    <aside
      className="rounded-card border-border bg-panel mt-4 border p-4"
      data-testid="dag-node-panel"
      data-status={status}
    >
      <div className="flex items-center justify-between gap-2">
        <h4 className="text-text text-sm font-semibold">{getLocalizedText(node.label, locale, t)}</h4>
        <div className="flex items-center gap-2">
          <span
            data-testid="dag-node-panel-status"
            className={cn(
              'rounded-pill inline-flex items-center gap-1.5 border px-2.5 py-0.5 text-xs font-semibold',
              STATUS_PILL[status],
            )}
          >
            {getLocalizedText(STATUS_WORD[status], locale, t)}
          </span>
          <button
            type="button"
            onClick={onClose}
            aria-label={locale === 'zh-CN' ? '收起说明' : 'Close details'}
            className="text-text-2 hover:text-text rounded-control hover:bg-subtle px-2 py-0.5 text-xs"
            data-testid="dag-node-panel-close"
          >
            {locale === 'zh-CN' ? '收起' : 'Close'}
          </button>
        </div>
      </div>
      <dl className="mt-3 grid gap-x-8 gap-y-3 sm:grid-cols-2">
        {facts.map((row) => (
          <div key={String(getLocalizedText(row.label, locale, t))} className="min-w-0">
            <dt className="text-text-2 text-xs font-medium">{getLocalizedText(row.label, locale, t)}</dt>
            <dd className="text-text mt-0.5 text-sm break-words">{row.value}</dd>
          </div>
        ))}
        {fieldRows.map((row) => (
          <div key={String(getLocalizedText(row.label, locale, t))} className="min-w-0">
            <dt className="text-text-2 text-xs font-medium">{getLocalizedText(row.label, locale, t)}</dt>
            <dd className="text-text mt-0.5 text-sm break-words" data-testid={`dag-node-panel-${getLocalizedText(row.label, locale, t)}`}>
              {row.items.length === 0
                ? '—'
                : (
                  <ul className="space-y-0.5">
                    {row.items.map((item) => (
                      <li key={item.field} title={item.full ?? undefined}>
                        <span className="font-medium">{item.label}</span>
                        <span className="text-text-2"> {item.text}</span>
                      </li>
                    ))}
                  </ul>
                )}
            </dd>
          </div>
        ))}
      </dl>
      {node.notice ? (
        <p className="text-text-2 border-border mt-3 border-t pt-3 text-xs leading-5">
          {getLocalizedText(node.notice, locale, t)}
        </p>
      ) : null}
    </aside>
  )
}

/**
 * A read-only stage rail for an ordered process: one row of steps in the order they run, each with
 * its state, and — on click — what that step is allowed to touch, what goes in and out, and how a
 * failure may be retried. The steps are the page's own configuration; nothing is laid out by hand.
 */
export const DagCanvasBlockRenderer: React.FC<DagCanvasBlockRendererProps> = ({ block, runtime }) => {
  useRuntimeStateSubscription(runtime)
  const [selectedKey, setSelectedKey] = useState<string | null>(null)
  const context = runtime.getContext() as Record<string, unknown>
  const locale = (context.locale as string) || 'zh-CN'
  const t = (context.t as ((key: string) => string) | undefined) || ((key: string) => key)
  const config = (block as any) as DagCanvasConfig
  // Stable references for the two inputs of the derivations below: the block's step list and the
  // page's record. `contextRecord` is read on every render, so a refreshed record is picked up.
  const contextRecord = context.record
  const record = useMemo<Record<string, unknown>>(() => {
    const own = (block as any).record
    if (own !== undefined && own !== null) return own
    const scoped = (block as any).context
    const value = scoped !== undefined ? resolveRuntimeValue(runtime, scoped) : contextRecord
    return value ?? EMPTY_RECORD
  }, [runtime, block, contextRecord])
  const configuredNodes = config.nodes
  const nodes = useMemo<NodeConfig[]>(
    () => (Array.isArray(configuredNodes) ? configuredNodes : EMPTY_NODES),
    [configuredNodes],
  )
  const statusField = String(config.statusField || '')
  const phasesField = config.phasesField
  const fieldLabels = useModelFieldLabels(pageModelCode(runtime))

  const statusMap = useMemo(
    () => (nodes.length ? deriveDeliveryNodeStatuses(nodes, statusField, record) : {}),
    [nodes, statusField, record],
  )
  const phases = useMemo(
    () => (phasesField ? parsePhasesList(readPath(record, phasesField)) : []),
    [phasesField, record],
  )
  const activeTerminal = (config.terminal ?? []).find(
    (terminal) => String(readPath(record, terminal.field) ?? '') === terminal.value,
  )
  const selectedNode = nodes.find((node) => node.key === selectedKey) || null

  if (nodes.length === 0) return null

  return (
    <section
      className="rounded-card border-border bg-panel border px-4 py-4 shadow-sm sm:px-5"
      data-testid={`dag-canvas-${block.id || 'block'}`}
    >
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <h3 className="text-text text-sm font-semibold">
          {getLocalizedText(config.title || { 'zh-CN': '交付进度', en: 'Delivery progress' }, locale, t)}
        </h3>
        {activeTerminal ? (
          <span
            data-testid="dag-canvas-terminal"
            className="rounded-pill bg-status-red-bg text-status-red ring-status-red/20 px-3 py-1 text-xs font-medium ring-2"
          >
            {getLocalizedText(activeTerminal.label, locale, t)}
          </span>
        ) : null}
      </div>

      <ol className="flex flex-wrap items-center gap-y-2" aria-label={locale === 'zh-CN' ? '交付步骤' : 'Delivery steps'}>
        {nodes.map((node, index) => {
          const status = statusMap[node.key] || 'pending'
          const marker = STATUS_MARKER[status]
          const seconds = phaseSeconds(phaseForEntry(phases, node.key))
          const selected = node.key === selectedKey
          const word = getLocalizedText(STATUS_WORD[status], locale, t)
          return (
            <li key={node.key} className="flex items-center">
              <button
                type="button"
                data-testid={`dag-step-${node.key}`}
                data-status={status}
                aria-pressed={selected}
                aria-current={status === 'running' ? 'step' : undefined}
                onClick={() => setSelectedKey(selected ? null : node.key)}
                className={cn(
                  'rounded-control border-border bg-panel hover:bg-subtle flex min-w-0 items-center gap-2 border px-2.5 py-1.5 text-left transition-colors',
                  selected && 'border-text bg-subtle',
                  status === 'skipped' && 'opacity-80',
                )}
              >
                <span
                  aria-hidden="true"
                  className={cn(
                    'flex h-6 w-6 shrink-0 items-center justify-center rounded-full border text-xs font-semibold',
                    marker.className,
                  )}
                >
                  {marker.symbol || index + 1}
                </span>
                <span className="min-w-0">
                  <span className="text-text block truncate text-sm font-medium">
                    {getLocalizedText(node.label, locale, t)}
                  </span>
                  <span className={cn('text-text-2 block text-xs', status === 'failed' && 'text-status-red font-medium')}>
                    {word}
                    {seconds !== null ? ` · ${seconds}s` : ''}
                  </span>
                </span>
              </button>
              {index < nodes.length - 1 ? (
                <span
                  aria-hidden="true"
                  className={cn(
                    'mx-2 h-px w-5 shrink-0 sm:w-7',
                    status === 'completed' && 'bg-status-green',
                    status === 'skipped' && 'border-border border-t border-dashed bg-transparent',
                    status !== 'completed' && status !== 'skipped' && 'bg-border',
                  )}
                />
              ) : null}
            </li>
          )
        })}
      </ol>

      {selectedNode ? (
        <NodeDetailPanel
          node={selectedNode}
          status={statusMap[selectedNode.key] || 'pending'}
          phases={phases}
          record={record}
          locale={locale}
          t={t}
          fieldLabels={fieldLabels}
          onClose={() => setSelectedKey(null)}
        />
      ) : (
        <p
          className="text-text-2 mt-3 text-xs leading-5"
          data-testid="dag-node-panel-empty"
        >
          {getLocalizedText(config.hint || DEFAULT_HINT, locale, t)}
        </p>
      )}
    </section>
  )
}

export default DagCanvasBlockRenderer
