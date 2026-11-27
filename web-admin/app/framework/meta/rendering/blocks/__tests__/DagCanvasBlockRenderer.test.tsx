/**
 * Unit tests for the dag-canvas delivery stage rail.
 *
 * Test plan (deriveDeliveryNodeStatuses — pure):
 *   ladder-complete:  doneStatuses mark nodes complete
 *   ladder-running:   runningStatuses mark nodes running
 *   ladder-failed:    failedStatuses win over doneStatuses
 *   gate-skip:        an unmatched whenField marks the node skipped
 *   gate-pass:        a matched whenField node follows its own ladder
 *   gate-non-empty:   whenValues omitted requires a non-empty field
 *   always-complete:  unconditional fact nodes are complete
 *
 * Card (one row of steps, no graph canvas):
 *   each step carries the status derived from the record
 *   the terminal pill on failed runs
 *   a step that was not taken is marked as skipped; the active step is the current one
 *   nothing of the graph library remains on the page
 *
 * Step details:
 *   the hint while nothing is selected
 *   facts and the step's values on click
 *   field names come from the model's display names; referenced records by name, not id
 *   long digests keep head and tail and carry the whole value in the tooltip
 *   durations join by step key; unparsable timings degrade to a dash
 *   selecting the step again (or closing) hides the details
 */

import React from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime'
import type { BlockConfig } from '~/framework/meta/schemas/types'

const fetchResultMock = vi.fn()
vi.mock('~/shared/services/http-client', () => ({
  fetchResult: (...args: unknown[]) => fetchResultMock(...args),
}))

import {
  DagCanvasBlockRenderer,
  __resetFieldLabelCache,
  deriveDeliveryNodeStatuses,
  fieldDisplayValue,
  pageModelCode,
} from '../DagCanvasBlockRenderer'

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

function runtimeWithRecord(record: Record<string, unknown>, schema: Record<string, unknown> = {}): SchemaRuntime {
  const full = {
    locale: 'zh-CN',
    t: (key: string) => key,
    record,
  }
  return {
    getContext: () => full,
    // the page schema is where a block reads the page's model from
    getSchema: () => schema,
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
    title: { 'zh-CN': '交付进度' },
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

describe('fieldDisplayValue', () => {
  it('prints the referenced record by name when the record carries the display companion', () => {
    expect(
      fieldDisplayValue({ orb_drun_release: '01M867H3RTAGZF0DYACE0CKMD3', orb_drun_release_display: 'chain A v1' }, 'orb_drun_release'),
    ).toEqual({ text: 'chain A v1' })
  })

  it('prints the value itself when there is no companion', () => {
    expect(fieldDisplayValue({ worker: 'wc-op-base1' }, 'worker')).toEqual({ text: 'wc-op-base1' })
  })

  it('keeps head and tail of a long digest and hands back the whole value for the tooltip', () => {
    const digest = `sha256:${'a1b2c3d4'.repeat(8)}`
    const shown = fieldDisplayValue({ digest }, 'digest')
    expect(shown?.text).toBe(`${digest.slice(0, 24)}…${digest.slice(-8)}`)
    expect(shown?.full).toBe(digest)
  })

  it('treats a missing or empty value as nothing to show', () => {
    expect(fieldDisplayValue({}, 'x')).toBeNull()
    expect(fieldDisplayValue({ x: '' }, 'x')).toBeNull()
    expect(fieldDisplayValue({ x: null }, 'x')).toBeNull()
  })
})

describe('pageModelCode', () => {
  it('reads the model the page schema names', () => {
    expect(pageModelCode(runtimeWithRecord({}, { modelCode: 'orb_deployment_run', pageKey: 'orb_deployment_run_detail' }))).toBe('orb_deployment_run')
  })

  it("falls back to the page key without its kind suffix — only the last one, the model code may contain underscores", () => {
    expect(pageModelCode(runtimeWithRecord({}, { pageKey: 'orb_release_batch_item_detail' }))).toBe('orb_release_batch_item')
    expect(pageModelCode(runtimeWithRecord({}, { pageKey: 'orb_application_list' }))).toBe('orb_application')
  })

  it('is undefined when nothing names the model', () => {
    expect(pageModelCode(runtimeWithRecord({}))).toBeUndefined()
  })
})

describe('DagCanvasBlockRenderer card', () => {
  it('shows one row of steps, each with the status derived from the record', () => {
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
    const step = (key: string) => screen.getByTestId(`dag-step-${key}`)
    expect(step('register').dataset.status).toBe('completed')
    expect(step('approval').dataset.status).toBe('completed')
    expect(step('migration').dataset.status).toBe('completed')
    expect(step('deploy').dataset.status).toBe('completed')
    expect(step('register').textContent).toContain('已完成')
    // steps are listed in the configured order
    const keys = screen.getAllByTestId(/^dag-step-/).map((el: HTMLElement) => el.getAttribute('data-testid'))
    expect(keys).toEqual(['dag-step-register', 'dag-step-approval', 'dag-step-migration', 'dag-step-deploy'])
    expect(screen.queryByTestId('dag-canvas-terminal')).toBeNull()
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

  it('marks a step that was not taken as skipped and names the active step as the current one', () => {
    render(
      <DagCanvasBlockRenderer
        block={block()}
        runtime={runtimeWithRecord({ status: 'applying', approval: '', migration_summary: '' })}
      />,
    )
    expect(screen.getByTestId('dag-step-approval').dataset.status).toBe('skipped')
    expect(screen.getByTestId('dag-step-approval').textContent).toContain('跳过')
    expect(screen.getByTestId('dag-step-deploy').dataset.status).toBe('running')
    expect(screen.getByTestId('dag-step-deploy')).toHaveAttribute('aria-current', 'step')
    expect(screen.getByTestId('dag-step-migration').dataset.status).toBe('skipped')
  })

  it('nothing of the graph library remains on the page', () => {
    const { container } = render(
      <DagCanvasBlockRenderer block={block()} runtime={runtimeWithRecord({ status: 'planned' })} />,
    )
    expect(container.querySelectorAll('.react-flow__node, .react-flow')).toHaveLength(0)
    expect(container.textContent).not.toContain('React Flow')
  })

  it('renders nothing when the page configures no steps', () => {
    const { container } = render(
      <DagCanvasBlockRenderer block={{ ...(block() as object), nodes: [] } as unknown as BlockConfig} runtime={runtimeWithRecord({})} />,
    )
    expect(container.textContent).toBe('')
  })
})

describe('DagCanvasBlockRenderer step details', () => {
  const panelNodes = [
    {
      key: 'register',
      label: { 'zh-CN': '登记' },
      alwaysComplete: true,
      taskType: 'register_run',
      effect: { 'zh-CN': '控制面记录', en: 'Control-plane record' },
      inputFields: ['release_pid', 'orb_drun_plan_digest', 'missing_field'],
      outputFields: ['pid'],
      retryOwner: { 'zh-CN': '交付执行服务', en: 'Delivery execution service' },
      retryBoundary: { 'zh-CN': '同 operation 键重试幂等', en: 'Idempotent under the same operation key' },
    },
    {
      key: 'deploy',
      label: { 'zh-CN': '部署' },
      failedStatuses: ['failed'],
      runningStatuses: ['applying'],
      doneStatuses: ['verifying', 'succeeded'],
      retryBoundary: { 'zh-CN': '失败不重投', en: 'No re-delivery on failure' },
    },
  ]

  const panelBlock = (): BlockConfig =>
    ({
      id: 'delivery',
      blockType: 'dag-canvas',
      title: { 'zh-CN': '交付进度' },
      statusField: 'status',
      nodes: panelNodes as never,
      phasesField: 'orb_drun_phases',
    }) as unknown as BlockConfig

  const digest = `sha256:${'0f1e2d3c'.repeat(8)}`
  const record = {
    status: 'succeeded',
    release_pid: '01M867H3RTAGZF0DYACE0CKMD3',
    release_pid_display: 'chain A v1',
    orb_drun_plan_digest: digest,
    pid: 'RUN-777',
  }

  beforeEach(() => {
    __resetFieldLabelCache()
    fetchResultMock.mockReset()
  })

  it('the hint shows while no step is selected', () => {
    render(<DagCanvasBlockRenderer block={panelBlock()} runtime={runtimeWithRecord({ status: 'succeeded' })} />)
    expect(screen.getByTestId('dag-node-panel-empty').textContent).toContain('点击任一步骤')
    expect(screen.queryByTestId('dag-node-panel')).toBeNull()
  })

  it('clicking a step shows the facts and the step\'s values, named by the model\'s field names', async () => {
    fetchResultMock.mockResolvedValue({
      code: '0',
      data: [
        { code: 'release_pid', extension: { displayName: '发布版本' } },
        { code: 'orb_drun_plan_digest', extension: { displayName: '部署计划摘要' } },
        { code: 'pid', extension: { displayName: '运行编号' } },
      ],
    })
    render(<DagCanvasBlockRenderer block={panelBlock()} runtime={runtimeWithRecord(record, { modelCode: 'orb_deployment_run' })} />)
    expect(fetchResultMock.mock.calls[0][0]).toBe('/api/dynamic/orb_deployment_run/field-meta')
    const user = userEvent.setup()
    await user.click(screen.getByTestId('dag-step-register'))
    const panel = screen.getByTestId('dag-node-panel')
    expect(panel.textContent).toContain('登记')
    expect(panel.querySelector('[data-testid="dag-node-panel-status"]')?.textContent).toBe('已完成')
    // facts
    expect(panel.textContent).toContain('任务类型')
    expect(panel.textContent).toContain('register_run')
    expect(panel.textContent).toContain('影响范围')
    expect(panel.textContent).toContain('控制面记录')
    expect(panel.textContent).toContain('重试负责人')
    expect(panel.textContent).toContain('交付执行服务')
    expect(panel.textContent).toContain('重试边界')
    // values: field display names, referenced record by name, a missing field is simply not listed
    expect(panel.textContent).toContain('发布版本')
    expect(panel.textContent).toContain('chain A v1')
    expect(panel.textContent).not.toContain('01M867H3RTAGZF0DYACE0CKMD3')
    expect(panel.textContent).not.toContain('orb_drun_release')
    expect(panel.textContent).not.toContain('missing_field')
    expect(panel.querySelector('[data-testid="dag-node-panel-输出"]')?.textContent).toContain('RUN-777')
    // register has no attested phase entry — duration stays honest (—)
    expect(panel.textContent).toContain('—')
  })

  it('a long digest is shortened and the whole value is kept in the tooltip', async () => {
    render(<DagCanvasBlockRenderer block={panelBlock()} runtime={runtimeWithRecord(record)} />)
    const user = userEvent.setup()
    await user.click(screen.getByTestId('dag-step-register'))
    const panel = screen.getByTestId('dag-node-panel')
    const shown = `${digest.slice(0, 24)}…${digest.slice(-8)}`
    expect(panel.textContent).toContain(shown)
    expect(panel.querySelector(`[title="${digest}"]`)).not.toBeNull()
    // without the model's names the code is printed minus its namespace prefix
    expect(panel.textContent).toContain('plan_digest')
  })

  it('phase durations join by step key and the details reflect the derived status', async () => {
    render(
      <DagCanvasBlockRenderer
        block={panelBlock()}
        runtime={runtimeWithRecord({
          status: 'succeeded',
          orb_drun_phases: JSON.stringify([
            { phase: 'deploy', seconds: 41 },
            { phase: 'verify', seconds: 5 },
          ]),
        })}
      />,
    )
    const user = userEvent.setup()
    await user.click(screen.getByTestId('dag-step-deploy'))
    const panel = screen.getByTestId('dag-node-panel')
    expect(panel.textContent).toContain('41s')
    expect(panel.textContent).not.toContain('5s')
    expect(panel.textContent).toContain('已完成')
    expect(panel.textContent).toContain('失败不重投')
    // the duration is also shown under the step itself
    expect(screen.getByTestId('dag-step-deploy').textContent).toContain('41s')
  })

  it('a duration is taken from the entry of that step, not from the first entry in the list', async () => {
    render(
      <DagCanvasBlockRenderer
        block={panelBlock()}
        runtime={runtimeWithRecord({
          status: 'succeeded',
          orb_drun_phases: JSON.stringify([
            { phase: 'verify', seconds: 5 },
            { phase: 'deploy', seconds: 41 },
          ]),
        })}
      />,
    )
    const user = userEvent.setup()
    await user.click(screen.getByTestId('dag-step-deploy'))
    const panel = screen.getByTestId('dag-node-panel')
    expect(panel.textContent).toContain('41s')
    expect(panel.textContent).not.toContain('5s')
  })

  it('an unparsable phases field degrades to a dash instead of crashing', async () => {
    render(
      <DagCanvasBlockRenderer
        block={panelBlock()}
        runtime={runtimeWithRecord({ status: 'succeeded', orb_drun_phases: 'not-json' })}
      />,
    )
    const user = userEvent.setup()
    await user.click(screen.getByTestId('dag-step-deploy'))
    expect(screen.getByTestId('dag-node-panel').textContent).toContain('—')
  })

  it('selecting the step again, or closing the details, hides them', async () => {
    render(<DagCanvasBlockRenderer block={panelBlock()} runtime={runtimeWithRecord({ status: 'succeeded' })} />)
    const user = userEvent.setup()
    await user.click(screen.getByTestId('dag-step-register'))
    expect(screen.getByTestId('dag-node-panel')).toBeDefined()
    await user.click(screen.getByTestId('dag-step-register'))
    expect(screen.queryByTestId('dag-node-panel')).toBeNull()
    await user.click(screen.getByTestId('dag-step-deploy'))
    await user.click(screen.getByTestId('dag-node-panel-close'))
    expect(screen.queryByTestId('dag-node-panel')).toBeNull()
  })

  it('a failed field lookup leaves the values readable under their codes', async () => {
    fetchResultMock.mockRejectedValue(new Error('field metadata unavailable'))
    render(<DagCanvasBlockRenderer block={panelBlock()} runtime={runtimeWithRecord(record, { modelCode: 'orb_deployment_run' })} />)
    const user = userEvent.setup()
    await user.click(screen.getByTestId('dag-step-register'))
    const panel = await screen.findByTestId('dag-node-panel')
    expect(panel.textContent).toContain('chain A v1')
    expect(panel.textContent).toContain('release_pid')
  })
})
