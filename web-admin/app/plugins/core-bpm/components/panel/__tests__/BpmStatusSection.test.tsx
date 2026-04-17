/**
 * BpmStatusSection.test.tsx
 *
 * Unit tests for the Task 11 status section:
 *   - empty state (no process instance),
 *   - running instance with a current node + assignee,
 *   - approved instance with no current nodes,
 *   - unknown status gracefully degrades without hardcoding a translation.
 *
 * The component is pure (takes an `instance` prop and a `t` translator), so
 * these tests render it directly without any i18n provider.
 */

import React from 'react';
import { describe, it, expect, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';

import { BpmStatusSection } from '../BpmStatusSection';
import type { BpmInstanceForRecord } from '~/plugins/core-bpm/services/bpmWorkbenchService';

// The repo's vitest config uses `isolate: false` + `singleThread: true`, so
// the jsdom instance is shared across tests in the same file. Testing Library
// would normally auto-cleanup when `globals: true`, but we reset the document
// body explicitly to guarantee no cross-test DOM leak during `getByTestId`
// lookups. We avoid importing `cleanup` from '@testing-library/react' because
// that named export is not surfaced in the installed @types package.
afterEach(() => {
  document.body.innerHTML = '';
});

// Identity translator: returns the caller-supplied fallback or the key itself.
// This mirrors the real `t` contract and lets us assert on the Chinese default
// copy rather than raw i18n keys bleeding into the DOM.
const t = (_key: string, _params?: Record<string, unknown>, fallback?: string): string =>
  fallback ?? _key;

function buildInstance(overrides: Partial<BpmInstanceForRecord> = {}): BpmInstanceForRecord {
  return {
    instanceId: 'pi-001',
    processDefinitionId: 'pd-alpha',
    status: 'running',
    currentNodes: [],
    completedNodes: [],
    variables: {},
    ...overrides,
  };
}

describe('BpmStatusSection', () => {
  it('renders the empty state with guidance copy when instance is null', () => {
    render(<BpmStatusSection instance={null} t={t} />);

    const empty = screen.getByTestId('bpm-status-empty');
    expect(empty).toBeInTheDocument();
    expect(empty).toHaveTextContent('暂无审批流程');
    expect(empty).toHaveTextContent('点击下方');
    // The empty state must not render a CTA button - Operations section owns it.
    expect(empty.querySelector('button')).toBeNull();
    expect(screen.queryByTestId('bpm-status-card')).toBeNull();
  });

  it('renders a running badge and the current node with assignee', () => {
    const instance = buildInstance({
      status: 'running',
      currentNodes: [
        {
          nodeId: 'approve-node',
          type: 'userTask',
          name: '部门审批',
          status: 'running',
          assignee: 'manager@example.com',
          completedAt: null,
          completedBy: null,
        },
      ],
    });

    render(<BpmStatusSection instance={instance} t={t} />);

    const card = screen.getByTestId('bpm-status-card');
    expect(card).toBeInTheDocument();

    const badge = screen.getByTestId('bpm-status-badge');
    expect(badge).toHaveAttribute('data-status', 'running');
    expect(badge).toHaveTextContent('运行中');
    expect(badge.className).toContain('bg-blue-100');
    expect(badge.className).toContain('text-blue-800');

    const nodesBlock = screen.getByTestId('bpm-status-current-nodes');
    expect(nodesBlock).toHaveTextContent('当前节点');
    const nodeItem = screen.getByTestId('bpm-status-current-node-approve-node');
    expect(nodeItem).toHaveTextContent('部门审批');
    expect(nodeItem).toHaveTextContent('manager@example.com');

    expect(screen.getByTestId('bpm-status-instance-id')).toHaveTextContent('pi-001');
  });

  it('renders approved badge without the current-node block when currentNodes is empty', () => {
    const instance = buildInstance({ status: 'approved', currentNodes: [] });

    render(<BpmStatusSection instance={instance} t={t} />);

    const badge = screen.getByTestId('bpm-status-badge');
    expect(badge).toHaveAttribute('data-status', 'approved');
    expect(badge).toHaveTextContent('已通过');
    expect(badge.className).toContain('bg-green-100');
    expect(badge.className).toContain('text-green-800');

    expect(screen.queryByTestId('bpm-status-current-nodes')).toBeNull();
  });

  it('falls back to raw status label + neutral badge for an unknown status value', () => {
    const instance = buildInstance({ status: 'weird_unknown' });

    render(<BpmStatusSection instance={instance} t={t} />);

    const badge = screen.getByTestId('bpm-status-badge');
    expect(badge).toHaveAttribute('data-status', 'weird_unknown');
    // Raw backend string surfaces verbatim - no silent translation,
    // no remapping to `running`.
    expect(badge).toHaveTextContent('weird_unknown');
    expect(badge.className).toContain('bg-gray-100');
    expect(badge.className).toContain('text-gray-600');
    // None of the known-status colour classes leak through.
    expect(badge.className).not.toContain('bg-blue-100');
    expect(badge.className).not.toContain('bg-green-100');
  });
});
