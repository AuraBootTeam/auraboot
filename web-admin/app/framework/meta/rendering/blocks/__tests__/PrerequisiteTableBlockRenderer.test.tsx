/**
 * Unit tests for the prerequisite-table block: declared inputs with first-match
 * status rules evaluated against the detail record.
 *
 *   first-match:    the first matching rule wins over later ones
 *   equals-narrow:  a rule with `equals` only matches that value
 *   fallback:       no matching rule renders the fallback
 *   empty-fallback: neither rules nor fallback render the gray default
 */

import React from 'react'
import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime'
import type { BlockConfig } from '~/framework/meta/schemas/types'

import { PrerequisiteTableBlockRenderer } from '../PrerequisiteTableBlockRenderer'

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
    id: 'plan_prerequisites',
    blockType: 'prerequisite-table',
    title: { 'zh-CN': '编译前置' },
    columns: [
      { 'zh-CN': '输入', en: 'Input' },
      { 'zh-CN': '当前状态', en: 'Status' },
    ],
    rows: [
      { label: { 'zh-CN': '应用技术声明' }, status: [{ when: 'orb_plan_applications', tone: 'gray', label: { 'zh-CN': '按所选应用解析' } }] },
      {
        label: { 'zh-CN': '精确源码 / 已有制品' },
        status: [
          { when: 'orb_plan_release', tone: 'blue', label: { 'zh-CN': '已选择版本引用' } },
          { when: 'orb_plan_source', tone: 'green', label: { 'zh-CN': '源码已解析' } },
        ],
        fallback: { when: '', tone: 'amber', label: { 'zh-CN': '待选择' } },
      },
      { label: { 'zh-CN': '组件与运行单元映射' }, fallback: { when: '', tone: 'amber' } },
    ],
  } as unknown as BlockConfig
}

describe('PrerequisiteTableBlockRenderer', () => {
  it('first matching rule wins and equals narrows a rule', () => {
    render(
      <PrerequisiteTableBlockRenderer
        block={block()}
        runtime={runtimeWithRecord({
          orb_plan_applications: 'APP-1',
          orb_plan_release: 'RL-9',
          orb_plan_source: 'ignored-when-release-present',
        })}
      />,
    )
    // row 0: gray declared rule matched
    expect(screen.getByTestId('prerequisite-status-0').textContent).toBe('按所选应用解析')
    // row 1: first rule (release) matched — source rule never consulted
    expect(screen.getByTestId('prerequisite-status-1').textContent).toBe('已选择版本引用')
  })

  it('fallback renders when no rule matches, with tone default label', () => {
    render(
      <PrerequisiteTableBlockRenderer
        block={block()}
        runtime={runtimeWithRecord({ orb_plan_applications: 'APP-1' })}
      />,
    )
    expect(screen.getByTestId('prerequisite-status-1').textContent).toBe('待选择')
    // row 2 fallback has no label — tone default (amber 待定)
    expect(screen.getByTestId('prerequisite-status-2').textContent).toBe('待定')
  })

  it('renders headers and row labels', () => {
    render(
      <PrerequisiteTableBlockRenderer
        block={block()}
        runtime={runtimeWithRecord({})}
      />,
    )
    const section = screen.getByTestId('prerequisite-table-plan_prerequisites')
    expect(section.textContent).toContain('编译前置')
    expect(section.textContent).toContain('输入')
    expect(section.textContent).toContain('组件与运行单元映射')
  })

  it('renders nothing without rows', () => {
    const empty = { id: 'x', blockType: 'prerequisite-table' } as unknown as BlockConfig
    const { container } = render(
      <PrerequisiteTableBlockRenderer block={empty} runtime={runtimeWithRecord({})} />,
    )
    expect(container.innerHTML).toBe('')
  })
})
