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
      <FlowCanvas readOnly showMinimap={false} showControls={false} fitExistingGraph className={className} />
    </NodeRuntimeStatusProvider>
  )
}

export const DagCanvasBlockRenderer: React.FC<DagCanvasBlockRendererProps> = ({ block, runtime }) => {
  useRuntimeStateSubscription(runtime)
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
      <FlowCanvasCard
        nodes={flowNodes}
        edges={flowEdges}
        statusMap={statusMap}
        className="h-44 rounded-control border-border border"
      />
    </section>
  )
}

export default DagCanvasBlockRenderer
