import React, { useEffect } from 'react'
import type { BlockConfig } from '~/framework/meta/schemas/types'
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime'
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils'
import { readPath, resolveRuntimeValue, useRuntimeStateSubscription } from './workbenchBlockUtils'
import {
  FlowCanvas,
  NodeRuntimeStatusProvider,
  useFlowStore,
  type NodeStatusMap,
} from '~/plugins/core-designer/components/flow-designer-sdk'

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
  /** What kind of task this node represents (TaskType row). */
  taskType?: Localized
  /** What the node may touch (副作用 row). */
  effect?: Localized
  /** Record field paths resolved as the node's 输入 row. */
  inputFields?: string[]
  /** Record field paths resolved as the node's 输出 row. */
  outputFields?: string[]
  /** Who may retry this node (重试 owner row). */
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
  /** Record field holding worker-attested per-phase timings (orb_drun_phases); entries
   * are matched to nodes by their phase/name/key field and shown as the 耗时 row. */
  phasesField?: string
  /** Shown in the side-panel area while no node is selected. */
  hint?: Localized
}

const STATUS_WORD: Record<DeliveryNodeStatus, Localized> = {
  pending: { 'zh-CN': '待触发', en: 'Pending' },
  running: { 'zh-CN': '运行中', en: 'Running' },
  completed: { 'zh-CN': '已完成', en: 'Completed' },
  failed: { 'zh-CN': '失败', en: 'Failed' },
  skipped: { 'zh-CN': '跳过', en: 'Skipped' },
}

const NODE_STATUS_PILL: Record<DeliveryNodeStatus, string> = {
  pending: 'border-border bg-panel text-text-2',
  running: 'border-status-blue bg-status-blue-bg text-status-blue',
  completed: 'border-status-green bg-status-green-bg text-status-green',
  failed: 'border-status-red bg-status-red-bg text-status-red',
  skipped: 'border-border bg-panel text-text-2',
}

const ROW_LABELS = {
  taskType: { 'zh-CN': 'TaskType', en: 'TaskType' },
  effect: { 'zh-CN': '副作用', en: 'Effect' },
  inputs: { 'zh-CN': '输入', en: 'Inputs' },
  outputs: { 'zh-CN': '输出', en: 'Outputs' },
  retryOwner: { 'zh-CN': '重试 owner', en: 'Retry owner' },
  retryBoundary: { 'zh-CN': '重试边界', en: 'Retry boundary' },
  duration: { 'zh-CN': '耗时', en: 'Duration' },
} satisfies Record<string, Localized>

/**
 * Derives per-node runtime statuses from the record, honoring conditional
 * gates before the status ladder. Vocabulary matches the flow-designer-sdk
 * runtime overlay: 'pending' | 'running' | 'completed' | 'failed' | 'skipped'.
 */
export function deriveDeliveryNodeStatuses(
  nodes: NodeConfig[],
  statusField: string,
  record: Record<string, unknown>,
): NodeStatusMap {
  const status = String(readPath(record, statusField) ?? '')
  const map: NodeStatusMap = {}
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

/** The card seeds the shared designer store, then renders the readonly canvas. */
function FlowCanvasCard({
  nodes,
  edges,
  statusMap,
  className,
}: {
  nodes: unknown[]
  edges: unknown[]
  statusMap: NodeStatusMap
  className?: string
}) {
  const importData = useFlowStore((store) => store.importData)
  useEffect(() => {
    importData({ nodes, edges } as never)
  }, [importData, nodes, edges])
  return (
    <NodeRuntimeStatusProvider statuses={statusMap}>
      <div className={`flex flex-col ${className || ''}`}>
        <FlowCanvas readOnly showMinimap={false} showControls={false} fitExistingGraph className="flex-1 min-h-0" />
      </div>
    </NodeRuntimeStatusProvider>
  )
}

/** Side panel: contract facts per node + live values resolved from the run record. */
function NodeDetailPanel({
  node,
  status,
  phases,
  record,
  locale,
  t,
}: {
  node: NodeConfig
  status: DeliveryNodeStatus
  phases: Record<string, unknown>[]
  record: Record<string, unknown>
  locale: string
  t: (key: string) => string
}) {
  const localizedLabel = (label: Localized | undefined) =>
    label === undefined ? '-' : getLocalizedText(label, locale, t)
  const fieldList = (fields: string[] | undefined) => {
    if (!fields || fields.length === 0) return '-'
    const parts = fields
      .map((field) => {
        const value = readPath(record, field)
        return value === null || value === undefined || value === '' ? null : `${field}=${String(value)}`
      })
      .filter(Boolean)
    return parts.length > 0 ? parts.join('，') : '-'
  }
  const seconds = phaseSeconds(phaseForEntry(phases, node.key))
  const rows: Array<{ label: Localized; value: string }> = [
    { label: ROW_LABELS.taskType, value: node.taskType ? localizedLabel(node.taskType) : '-' },
    { label: ROW_LABELS.effect, value: node.effect ? localizedLabel(node.effect) : '-' },
    { label: ROW_LABELS.inputs, value: fieldList(node.inputFields) },
    { label: ROW_LABELS.outputs, value: fieldList(node.outputFields) },
    {
      label: ROW_LABELS.retryOwner,
      value: node.retryOwner ? localizedLabel(node.retryOwner) : '-',
    },
    {
      label: ROW_LABELS.retryBoundary,
      value: node.retryBoundary ? localizedLabel(node.retryBoundary) : '-',
    },
    { label: ROW_LABELS.duration, value: seconds === null ? '—' : `${seconds}s` },
  ]
  return (
    <aside
      className="rounded-card border-border bg-panel border lg:w-80 lg:shrink-0"
      data-testid="dag-node-panel"
    >
      <div className="flex items-center justify-between gap-2 border-b border-border px-4 py-3">
        <h4 className="text-text text-sm font-semibold">{getLocalizedText(node.label, locale, t)}</h4>
        <span
          className={`rounded-pill inline-flex items-center border px-2.5 py-0.5 text-xs font-semibold ${NODE_STATUS_PILL[status]}`}
        >
          {getLocalizedText(STATUS_WORD[status] ?? status, locale, t)}
        </span>
      </div>
      <dl className="space-y-2.5 p-3">
        {rows.map((row) => (
          <div key={String(row.label)}>
            <dt className="text-text-2 text-xs font-medium">{getLocalizedText(row.label, locale, t)}</dt>
            <dd className="text-text mt-0.5 text-sm break-words">{row.value}</dd>
          </div>
        ))}
      </dl>
      {node.notice ? (
        <p className="text-text-2 border-t border-border px-4 py-3 text-xs leading-5">
          {getLocalizedText(node.notice, locale, t)}
        </p>
      ) : null}
    </aside>
  )
}

export const DagCanvasBlockRenderer: React.FC<DagCanvasBlockRendererProps> = ({ block, runtime }) => {
  useRuntimeStateSubscription(runtime)
  // Selection is self-contained: the readonly canvas does not route clicks through the
  // shared designer store (elementsSelectable is off there), so this card delegates
  // clicks on the canvas and reads the react-flow node id (= node key) itself. A click
  // that misses any node clears the selection. Hooks stay above the early return.
  const [selectedNodeId, setSelectedNodeId] = React.useState<string | null>(null)
  const onCanvasClick = React.useCallback((event: React.MouseEvent<HTMLDivElement>) => {
    const nodeEl = (event.target as HTMLElement).closest('.react-flow__node')
    setSelectedNodeId(nodeEl ? nodeEl.getAttribute('data-id') : null)
  }, [])
  const context = runtime.getContext()
  const locale = context.locale || 'zh-CN'
  const t = context.t || ((key: string) => key)
  const record =
    (block as any).record ??
    ((block as any).context !== undefined
      ? resolveRuntimeValue(runtime, (block as any).context)
      : context.record) ??
    {}
  const config = (block as any) as DagCanvasConfig
  const nodes = Array.isArray(config.nodes) ? config.nodes : []
  const statusField = String(config.statusField || '')

  if (nodes.length === 0) return null

  const statusMap = deriveDeliveryNodeStatuses(nodes, statusField, record)
  const localizedLabel = (label: NodeConfig['label']) =>
    getLocalizedText(label, locale, t)
  const flowNodes = nodes.map((node, index) => ({
    id: node.key,
    type: 'default',
    position: { x: index * 230, y: 60 },
    data: { label: localizedLabel(node.label), config: {} },
  }))
  const flowEdges = nodes.slice(0, -1).map((node, index) => ({
    id: `${node.key}->${nodes[index + 1].key}`,
    source: node.key,
    target: nodes[index + 1].key,
  }))
  const activeTerminal = (config.terminal ?? []).find(
    (terminal) => String(readPath(record, terminal.field) ?? '') === terminal.value,
  )
  const selectedNode = nodes.find((node) => node.key === selectedNodeId) || null
  const phases = config.phasesField ? parsePhasesList(readPath(record, config.phasesField)) : []

  return (
    <section
      className="rounded-card border-border bg-panel border px-4 py-4 shadow-sm sm:px-5"
      data-testid={`dag-canvas-${block.id || 'block'}`}
    >
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <h3 className="text-text text-sm font-semibold">
          {getLocalizedText(config.title || { 'zh-CN': '交付 DAG', en: 'Delivery DAG' }, locale, t)}
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
      <div className="flex flex-col gap-3 lg:flex-row lg:items-stretch">
        <div className="min-w-0 lg:flex-1" onClick={onCanvasClick}>
          <FlowCanvasCard
            nodes={flowNodes}
            edges={flowEdges}
            statusMap={statusMap}
            className="h-44 rounded-control border-border border"
          />
        </div>
        {selectedNode ? (
          <NodeDetailPanel
            key={selectedNode.key}
            node={selectedNode}
            status={statusMap[selectedNode.key] || 'pending'}
            phases={phases}
            record={record}
            locale={locale}
            t={t}
          />
        ) : (
          <aside
            className="rounded-card border-border bg-panel text-text-2 flex items-center border border-dashed p-4 text-xs leading-5 lg:w-80 lg:shrink-0"
            data-testid="dag-node-panel-empty"
          >
            {getLocalizedText(
              config.hint || {
                'zh-CN': '点击节点查看输入、合同与结果',
                en: 'Select a node to inspect inputs, contract and result',
              },
              locale,
              t,
            )}
          </aside>
        )}
      </div>
    </section>
  )
}

export default DagCanvasBlockRenderer
