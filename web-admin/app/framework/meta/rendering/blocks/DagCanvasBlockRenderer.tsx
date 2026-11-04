import React from 'react'
import type { BlockConfig } from '~/framework/meta/schemas/types'
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime'
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils'
import { readPath, resolveRuntimeValue, useRuntimeStateSubscription } from './workbenchBlockUtils'

export interface DagCanvasBlockRendererProps {
  block: BlockConfig
  runtime: SchemaRuntime
}

type NodeConfig = {
  key: string
  label: string | Record<string, string>
  /** Conditional gate: the node renders only when the record field matches one of values. */
  when?: { field: string; values: string[] }
  /** The node is complete when the record field equals this value (defaults to stage progression). */
  doneField?: string
  doneValue?: string
  /** The node is actively running when the record field equals this value. */
  activeField?: string
  activeValue?: string
}

type TerminalConfig = {
  field: string
  value: string
  label: string | Record<string, string>
}

const stateClass = {
  complete: {
    dot: 'border-accent bg-accent text-white',
    line: 'bg-accent',
    text: 'text-text',
  },
  current: {
    dot: 'border-accent bg-panel text-accent ring-4 ring-accent/15',
    line: 'bg-border-strong',
    text: 'text-accent',
  },
  upcoming: {
    dot: 'border-border-strong bg-panel text-text-3',
    line: 'bg-border-strong',
    text: 'text-text-3',
  },
  skipped: {
    dot: 'border-border-strong bg-panel text-text-3 border-dashed',
    line: 'bg-border-strong',
    text: 'text-text-3',
  },
} as const

function readRecord(block: DagCanvasBlockRendererProps['block'], runtime: SchemaRuntime) {
  return (
    (block as any).record ??
    ((block as any).context !== undefined
      ? resolveRuntimeValue(runtime, (block as any).context)
      : runtime.getContext().record) ??
    {}
  )
}

export const DagCanvasBlockRenderer: React.FC<DagCanvasBlockRendererProps> = ({
  block,
  runtime,
}) => {
  useRuntimeStateSubscription(runtime)
  const context = runtime.getContext()
  const locale = context.locale || 'zh-CN'
  const t = context.t || ((key: string) => key)
  const record = readRecord(block, runtime)
  const stageField = String((block as any).stageField || '')
  const stageOrder = (Array.isArray((block as any).stageOrder) ? (block as any).stageOrder : []) as string[]
  const nodes = (Array.isArray((block as any).nodes) ? (block as any).nodes : []) as NodeConfig[]
  const terminals = (Array.isArray((block as any).terminal)
    ? (block as any).terminal
    : []) as TerminalConfig[]
  const title = getLocalizedText(block.title || { 'zh-CN': '交付 DAG', en: 'Delivery DAG' }, locale, t)

  if (nodes.length === 0) return null

  const stageRank = (value: unknown) => {
    const text = String(value ?? '')
    const index = stageOrder.indexOf(text)
    return index === -1 ? -1 : index
  }
  const currentRank = stageRank(readPath(record, stageField))

  const rendered = nodes
    .map((node) => {
      const gate = node.when
      if (gate) {
        const value = String(readPath(record, gate.field) ?? '')
        if (!gate.values.includes(value)) {
          return { node, state: 'skipped' as const, rendered: true }
        }
      }
      let state: 'complete' | 'current' | 'upcoming' = 'upcoming'
      if (node.doneField && node.doneValue) {
        if (String(readPath(record, node.doneField) ?? '') === node.doneValue) state = 'complete'
        else if (node.activeField && node.activeValue && String(readPath(record, node.activeField) ?? '') === node.activeValue) state = 'current'
      } else {
        const rank = stageRank(node.key)
        if (rank === -1) state = currentRank >= stageOrder.length ? 'complete' : 'upcoming'
        else if (currentRank > rank) state = 'complete'
        else if (currentRank === rank) state = 'current'
      }
      return { node, state, rendered: true }
    })
    .filter((item) => item.rendered)

  const activeTerminal = terminals.find(
    (terminal) => String(readPath(record, terminal.field) ?? '') === terminal.value,
  )
  const currentNode = rendered.find((item) => item.state === 'current')

  return (
    <section
      className="rounded-card border-border bg-panel border px-4 py-4 shadow-sm sm:px-5"
      data-testid={`dag-canvas-${block.id || 'block'}`}
    >
      <div className="mb-4 flex flex-wrap items-center justify-between gap-2">
        <h3 className="text-text text-sm font-semibold">{title}</h3>
        {activeTerminal ? (
          <span
            data-testid="dag-canvas-terminal"
            className="rounded-pill bg-status-red-bg text-status-red ring-status-red/20 px-3 py-1 text-xs font-medium ring-2"
          >
            {getLocalizedText(activeTerminal.label, locale, t)}
          </span>
        ) : currentNode ? (
          <span className="rounded-pill bg-accent-weak text-accent px-2.5 py-1 text-xs font-medium">
            {getLocalizedText(currentNode.node.label, locale, t)}
          </span>
        ) : null}
      </div>
      <div className="overflow-x-auto pb-1">
        <ol
          className="flex min-w-[680px] items-start"
          aria-label={title}
          data-testid="dag-canvas-nodes"
        >
          {rendered.map(({ node, state }, index) => {
            const classes = stateClass[state]
            return (
              <li
                key={node.key}
                className="relative flex min-w-0 flex-1 flex-col items-center px-1 text-center"
                data-testid={`dag-canvas-node-${node.key}`}
                data-node-state={state}
                aria-current={state === 'current' ? 'step' : undefined}
              >
                {index > 0 ? (
                  <span
                    aria-hidden="true"
                    className={`absolute top-3 right-1/2 h-0.5 w-full ${
                      rendered[index - 1].state === 'complete' && state !== 'upcoming'
                        ? stateClass.complete.line
                        : classes.line
                    }`}
                  />
                ) : null}
                <span
                  className={`relative z-10 flex h-6 w-6 items-center justify-center rounded-full border-2 text-[11px] font-bold ${classes.dot}`}
                >
                  {state === 'complete' ? '✓' : state === 'skipped' ? '–' : index + 1}
                </span>
                <span className={`mt-2 text-xs font-medium whitespace-nowrap ${classes.text}`}>
                  {getLocalizedText(node.label, locale, t)}
                </span>
              </li>
            )
          })}
        </ol>
      </div>
    </section>
  )
}

export default DagCanvasBlockRenderer
