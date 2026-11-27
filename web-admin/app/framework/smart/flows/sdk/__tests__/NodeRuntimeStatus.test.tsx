// G5 — runtime status overlay tests.
//
// Covers the FlowDesigner `nodeStatuses` prop contract end-to-end at the unit
// level: provider hook semantics + DefaultFlowNode renders the right badge /
// data attribute + back-compat (omitted prop = no overlay = no DOM change).

import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, renderHook, screen } from '@testing-library/react';

vi.mock('~/utils/i18n', () => ({
  useSmartText: () => (k: unknown) => (typeof k === 'string' ? k : ''),
}));
// xyflow Handle / Position try to read SVG context — stub the bits we exercise.
vi.mock('@xyflow/react', () => ({
  Handle: ({ id }: { id?: string }) => <span data-testid={`handle-${id || 'default'}`} />,
  Position: { Top: 'top', Bottom: 'bottom', Left: 'left', Right: 'right' },
}));

import { nodeRegistry } from '../nodes/NodeRegistry';
import { DefaultFlowNode } from '../core/DefaultFlowNode';
import {
  NodeRuntimeStatusProvider,
  useNodeRuntimeStatus,
  useNodeRuntimeStatusMap,
  type NodeStatusMap,
} from '../runtime/NodeRuntimeStatusContext';

function renderNode(id: string, statuses?: NodeStatusMap | null) {
  return render(
    <NodeRuntimeStatusProvider statuses={statuses}>
      <DefaultFlowNode
        id={id}
        type="action-test"
        data={{ label: 'Test Action', config: {} } as any}
        selected={false}
        // The other NodeProps fields are not read by DefaultFlowNode.
        {...({} as any)}
      />
    </NodeRuntimeStatusProvider>,
  );
}

describe('G5 — NodeRuntimeStatusContext + DefaultFlowNode overlay', () => {
  beforeEach(() => {
    nodeRegistry.clear();
    nodeRegistry.register({
      type: 'action-test',
      label: 'Test Action',
      icon: 'icon-action-test',
      category: 'action',
      defaultConfig: {},
    });
  });

  it('hook returns undefined when no provider is active (overlay disabled by default)', () => {
    const { result } = renderHook(() => useNodeRuntimeStatus('n1'));
    expect(result.current).toBeUndefined();
  });

  it('hook returns undefined when statuses map is empty or null', () => {
    const wrapperEmpty = ({ children }: { children: React.ReactNode }) => (
      <NodeRuntimeStatusProvider statuses={{}}>{children}</NodeRuntimeStatusProvider>
    );
    const wrapperNull = ({ children }: { children: React.ReactNode }) => (
      <NodeRuntimeStatusProvider statuses={null}>{children}</NodeRuntimeStatusProvider>
    );
    expect(
      renderHook(() => useNodeRuntimeStatusMap(), { wrapper: wrapperEmpty }).result.current,
    ).toBeNull();
    expect(
      renderHook(() => useNodeRuntimeStatusMap(), { wrapper: wrapperNull }).result.current,
    ).toBeNull();
  });

  it('hook reads the status for a specific node id from the provider', () => {
    const wrapper = ({ children }: { children: React.ReactNode }) => (
      <NodeRuntimeStatusProvider statuses={{ a: 'running', b: 'failed' }}>
        {children}
      </NodeRuntimeStatusProvider>
    );
    expect(renderHook(() => useNodeRuntimeStatus('a'), { wrapper }).result.current).toBe('running');
    expect(renderHook(() => useNodeRuntimeStatus('b'), { wrapper }).result.current).toBe('failed');
    expect(renderHook(() => useNodeRuntimeStatus('missing'), { wrapper }).result.current).toBeUndefined();
  });

  it('renders no status badge when nodeStatuses prop is omitted (back-compat)', () => {
    renderNode('n1');
    expect(screen.queryByTestId('flow-node-n1-status-badge')).toBeNull();
    expect(screen.getByTestId('flow-node-n1').getAttribute('data-runtime-status')).toBeNull();
  });

  it('renders a badge with status=running and the data attribute', () => {
    renderNode('n1', { n1: 'running' });
    const badge = screen.getByTestId('flow-node-n1-status-badge');
    expect(badge).toBeInTheDocument();
    expect(badge.getAttribute('aria-label')).toBe('runtime status: running');
    expect(screen.getByTestId('flow-node-n1').getAttribute('data-runtime-status')).toBe('running');
  });

  it('renders a badge with status=completed', () => {
    renderNode('n1', { n1: 'completed' });
    expect(screen.getByTestId('flow-node-n1-status-badge').getAttribute('aria-label'))
      .toBe('runtime status: completed');
    expect(screen.getByTestId('flow-node-n1').getAttribute('data-runtime-status')).toBe('completed');
  });

  it('renders a badge with status=failed', () => {
    renderNode('n1', { n1: 'failed' });
    expect(screen.getByTestId('flow-node-n1').getAttribute('data-runtime-status')).toBe('failed');
  });

  it('renders a badge with status=pending', () => {
    renderNode('n1', { n1: 'pending' });
    expect(screen.getByTestId('flow-node-n1').getAttribute('data-runtime-status')).toBe('pending');
  });

  it('renders a badge with status=skipped', () => {
    renderNode('n1', { n1: 'skipped' });
    expect(screen.getByTestId('flow-node-n1').getAttribute('data-runtime-status')).toBe('skipped');
  });

  it('does not render the badge for nodes absent from the status map', () => {
    renderNode('other', { someOtherNode: 'running' });
    expect(screen.queryByTestId('flow-node-other-status-badge')).toBeNull();
    expect(screen.getByTestId('flow-node-other').getAttribute('data-runtime-status')).toBeNull();
  });

  it('renders string lucide icon names as icons rather than leaking icon codes', () => {
    nodeRegistry.register({
      type: 'action-send-webhook',
      label: '发送 Webhook',
      icon: 'Send',
      category: 'action',
      defaultConfig: {},
    });

    render(
      <NodeRuntimeStatusProvider statuses={null}>
        <DefaultFlowNode
          id="webhook"
          type="action-send-webhook"
          data={{ label: '发送 Webhook', config: {} } as any}
          selected={false}
          {...({} as any)}
        />
      </NodeRuntimeStatusProvider>,
    );

    const node = screen.getByTestId('flow-node-webhook');
    expect(node).not.toHaveTextContent('Send');
    expect(node.querySelector('svg')).not.toBeNull();
  });
});
