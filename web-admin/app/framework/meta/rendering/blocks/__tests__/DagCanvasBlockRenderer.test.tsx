/**
 * Unit tests for the dag-canvas delivery DAG derivation + card.
 *
 * Test plan (deriveDeliveryNodeStatuses — pure):
 *   ladder-complete:  doneStatuses mark nodes complete
 *   ladder-running:   runningStatuses mark nodes running
 *   ladder-failed:    failedStatuses win over doneStatuses
 *   gate-skip:        an unmatched whenField marks the node skipped
 *   gate-pass:        a matched whenField node follows its own ladder
 *   always-complete:  unconditional fact nodes are complete
 *
 * Card smoke (React Flow mocked):
 *   renders the canvas card and passes the derived status map down
 */

import React from 'react'
import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime'
import type { BlockConfig } from '~/framework/meta/schemas/types'

const statusMapSpy = vi.fn()

vi.mock('~/plugins/core-designer/components/flow-designer-sdk', () => ({
  FlowCanvas: ({ className }: { className?: string }) => (
    <div data-testid="flow-canvas" className={className} />
  ),
  NodeRuntimeStatusProvider: ({
    statuses,
    children,
  }: {
    statuses: Record<string, string>
    children: React.ReactNode
  }) => {
    statusMapSpy(statuses)
    return <div data-testid="status-provider">{children}</div>
  },
  useFlowStore: () => (data: unknown) => {
    void data
  },
}))

import { DagCanvasBlockRenderer, deriveDeliveryNodeStatuses } from '../DagCanvasBlockRenderer'

const nodes = [
  { key: 'register', label: { 'zh-CN': '登记' }, alwaysComplete: true },
  {
    key: 'approval',
    label: { 'zh-CN': '审批门' },
    whenField: 'approval',
    whenValues: ['pending', 'approved'],
    doneField: 'approval',
    doneValue: 'approved',
  },
  {
    key: 'migration',
    label: { 'zh-CN': '迁移' },
    whenField: 'migration_summary',
    whenValues: ['present'],
    failedStatuses: ['failed'],
    runningStatuses: ['applying'],
    doneStatuses: ['verifying', 'succeeded'],
  },
  {
    key: 'deploy',
    label: { 'zh-CN': '部署' },
    failedStatuses: ['failed'],
    runningStatuses: ['applying'],
    doneStatuses: ['verifying', 'succeeded'],
  },
]

function runtimeWithRecord(record: Record<string, unknown>): SchemaRuntime {
  const context = {
    locale: 'zh-CN',
    t: (key: string) => key,
    record,
  }
  return {
    getContext: () => context,
    subscribeContext: () => () => undefined,
    getStateManager: () => ({
      getStore: () => ({ subscribe: () => () => undefined }),
    }),
    getScopeId: () => 'scope-1',
  } as unknown as SchemaRuntime
}

function block(): BlockConfig {
  return {
    id: 'delivery',
    blockType: 'dag-canvas',
    title: { 'zh-CN': '交付 DAG' },
    statusField: 'status',
    nodes: nodes as never,
    terminal: [{ field: 'status', value: 'failed', label: { 'zh-CN': '失败' } }],
  } as unknown as BlockConfig
}

describe('deriveDeliveryNodeStatuses', () => {
  it('ladder-complete: doneStatuses mark nodes complete', () => {
    const map = deriveDeliveryNodeStatuses(nodes as never, 'status', {
      status: 'succeeded',
      approval: 'approved',
      migration_summary: 'present',
    })
    expect(map.register).toBe('completed')
    expect(map.approval).toBe('completed')
    expect(map.migration).toBe('completed')
    expect(map.deploy).toBe('completed')
  })

  it('ladder-running: runningStatuses mark nodes running', () => {
    const map = deriveDeliveryNodeStatuses(nodes as never, 'status', {
      status: 'applying',
      approval: 'approved',
      migration_summary: 'present',
    })
    expect(map.migration).toBe('running')
    expect(map.deploy).toBe('running')
  })

  it('ladder-failed: failedStatuses win over doneStatuses', () => {
    const map = deriveDeliveryNodeStatuses(nodes as never, 'status', {
      status: 'failed',
      approval: 'approved',
      migration_summary: 'present',
    })
    expect(map.migration).toBe('failed')
    expect(map.deploy).toBe('failed')
  })

  it('gate-skip: an unmatched whenField marks the node skipped', () => {
    const map = deriveDeliveryNodeStatuses(nodes as never, 'status', {
      status: 'succeeded',
      approval: '',
      migration_summary: '',
    })
    expect(map.approval).toBe('skipped')
    expect(map.migration).toBe('skipped')
  })

  it('gate-pass: a matched whenField node follows its own ladder', () => {
    const map = deriveDeliveryNodeStatuses(nodes as never, 'status', {
      status: 'verifying',
      approval: 'pending',
      migration_summary: 'present',
    })
    // 'verifying' is not in approval's doneStatuses; approval falls to pending
    expect(map.approval).toBe('pending')
    expect(map.migration).toBe('completed')
  })

  it('gate-non-empty: whenValues omitted requires a non-empty field', () => {
    const map = deriveDeliveryNodeStatuses(
      [
        { key: 'migration', label: {}, whenField: 'migration_summary' },
      ] as never,
      'status',
      { status: 'succeeded', migration_summary: 'applied=x' },
    )
    expect(map.migration).toBe('pending')
    const empty = deriveDeliveryNodeStatuses(
      [
        { key: 'migration', label: {}, whenField: 'migration_summary' },
      ] as never,
      'status',
      { status: 'succeeded', migration_summary: '' },
    )
    expect(empty.migration).toBe('skipped')
  })

  it('always-complete: unconditional fact nodes are complete', () => {
    const map = deriveDeliveryNodeStatuses(nodes as never, 'status', { status: 'planned' })
    expect(map.register).toBe('completed')
  })
})

describe('DagCanvasBlockRenderer card', () => {
  it('renders the canvas card and passes the derived status map down', () => {
    statusMapSpy.mockClear()
    render(
      <DagCanvasBlockRenderer
        block={block()}
        runtime={runtimeWithRecord({
          status: 'succeeded',
          approval: 'approved',
          migration_summary: 'present',
        })}
      />,
    )
    expect(screen.getByTestId('flow-canvas')).toBeDefined()
    expect(screen.queryByTestId('dag-canvas-terminal')).toBeNull()
    const last = statusMapSpy.mock.calls.at(-1)?.[0] as Record<string, string>
    expect(last.register).toBe('completed')
    expect(last.approval).toBe('completed')
    expect(last.migration).toBe('completed')
  })

  it('renders the terminal pill on failed runs', () => {
    render(
      <DagCanvasBlockRenderer
        block={block()}
        runtime={runtimeWithRecord({
          status: 'failed',
          approval: 'approved',
          migration_summary: 'present',
        })}
      />,
    )
    expect(screen.getByTestId('dag-canvas-terminal').textContent).toBe('失败')
  })
})
