/**
 * Unit tests for DagCanvasBlockRenderer.
 *
 * Test plan:
 *   smoke-linear:      status progression drives complete/current/upcoming states
 *   gate-hidden:       a conditional gate node with unmatched values is skipped
 *   gate-shown-done:   a matched gate node with its doneValue renders complete
 *   gate-shown-active: a matched gate node without doneValue renders current
 *   terminal-pill:     a matching terminal record value renders the terminal pill
 *   no-nodes:          an empty node list renders nothing
 */

import React from 'react'
import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime'
import type { BlockConfig } from '~/framework/meta/schemas/types'
import { DagCanvasBlockRenderer } from '../DagCanvasBlockRenderer'

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

function block(overrides: Record<string, unknown> = {}): BlockConfig {
  return {
    id: 'delivery',
    blockType: 'dag-canvas',
    title: { 'zh-CN': '交付 DAG' },
    stageField: 'status',
    stageOrder: ['planned', 'applying', 'verifying', 'succeeded'],
    nodes: [
      { key: 'planned', label: { 'zh-CN': '已规划' } },
      { key: 'applying', label: { 'zh-CN': '执行中' } },
      { key: 'verifying', label: { 'zh-CN': '验证中' } },
      { key: 'succeeded', label: { 'zh-CN': '成功' } },
    ],
    ...overrides,
  } as unknown as BlockConfig
}

describe('DagCanvasBlockRenderer', () => {
  it('smoke-linear: status progression drives complete/current/upcoming states', () => {
    render(<DagCanvasBlockRenderer block={block()} runtime={runtimeWithRecord({ status: 'verifying' })} />)
    expect(screen.getByTestId('dag-canvas-node-planned').dataset.nodeState).toBe('complete')
    expect(screen.getByTestId('dag-canvas-node-applying').dataset.nodeState).toBe('complete')
    expect(screen.getByTestId('dag-canvas-node-verifying').dataset.nodeState).toBe('current')
    expect(screen.getByTestId('dag-canvas-node-succeeded').dataset.nodeState).toBe('upcoming')
  })

  it('gate-hidden: a conditional gate node with unmatched values is skipped', () => {
    render(
      <DagCanvasBlockRenderer
        block={block({
          nodes: [
            { key: 'planned', label: { 'zh-CN': '已规划' } },
            {
              key: 'migration',
              label: { 'zh-CN': '迁移' },
              when: { field: 'migration_summary', values: ['sha256:abc'] },
            },
            { key: 'succeeded', label: { 'zh-CN': '成功' } },
          ],
        })}
        runtime={runtimeWithRecord({ status: 'succeeded', migration_summary: '' })}
      />,
    )
    const node = screen.getByTestId('dag-canvas-node-migration')
    expect(node.dataset.nodeState).toBe('skipped')
  })

  it('gate-shown-done: a matched gate node with its doneValue renders complete', () => {
    render(
      <DagCanvasBlockRenderer
        block={block({
          nodes: [
            { key: 'planned', label: { 'zh-CN': '已规划' } },
            {
              key: 'approval',
              label: { 'zh-CN': '审批门' },
              when: { field: 'approval', values: ['pending', 'approved'] },
              doneField: 'approval',
              doneValue: 'approved',
              activeField: 'approval',
              activeValue: 'pending',
            },
            { key: 'succeeded', label: { 'zh-CN': '成功' } },
          ],
        })}
        runtime={runtimeWithRecord({ status: 'succeeded', approval: 'approved' })}
      />,
    )
    expect(screen.getByTestId('dag-canvas-node-approval').dataset.nodeState).toBe('complete')
  })

  it('gate-shown-active: a matched gate node without doneValue renders current', () => {
    render(
      <DagCanvasBlockRenderer
        block={block({
          nodes: [
            { key: 'planned', label: { 'zh-CN': '已规划' } },
            {
              key: 'approval',
              label: { 'zh-CN': '审批门' },
              when: { field: 'approval', values: ['pending', 'approved'] },
              activeField: 'approval',
              activeValue: 'pending',
            },
            { key: 'succeeded', label: { 'zh-CN': '成功' } },
          ],
        })}
        runtime={runtimeWithRecord({ status: 'succeeded', approval: 'pending' })}
      />,
    )
    expect(screen.getByTestId('dag-canvas-node-approval').dataset.nodeState).toBe('current')
  })

  it('terminal-pill: a matching terminal record value renders the terminal pill', () => {
    render(
      <DagCanvasBlockRenderer
        block={block({
          terminal: [{ field: 'status', value: 'failed', label: { 'zh-CN': '失败' } }],
        })}
        runtime={runtimeWithRecord({ status: 'failed' })}
      />,
    )
    expect(screen.getByTestId('dag-canvas-terminal').textContent).toBe('失败')
  })

  it('no-nodes: an empty node list renders nothing', () => {
    const { container } = render(
      <DagCanvasBlockRenderer block={block({ nodes: [] })} runtime={runtimeWithRecord({})} />,
    )
    expect(container.querySelector('[data-testid^="dag-canvas-"]')).toBeNull()
  })
})
