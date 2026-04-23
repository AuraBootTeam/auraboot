/**
 * BpmDiagramSection.test.tsx
 *
 * Unit tests for the Task 12 diagram section:
 *   - null instance → component renders nothing,
 *   - present instance → `getProcessDefinitionByKey` is called with the
 *     SmartEngine processKey carried in `instance.processDefinitionId`,
 *     and the ReactFlow container is mounted with current/completed
 *     highlights derived from the instance,
 *   - definition lookup failure (rejected promise) → error testid surfaces
 *     the message without fabricating UI.
 *
 * `@xyflow/react` relies on browser-only APIs (ResizeObserver, DOMMatrix,
 * canvas sizing) that jsdom does not ship. We mock the package to a minimal
 * pass-through component that surfaces the `nodes` prop through a testid so
 * we can assert the highlight annotations without instantiating the real
 * canvas engine.
 */

import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';

// -- Mocks must be declared before importing the component under test. -------

const getProcessDefinitionByKeyMock = vi.fn();
const fitViewMock = vi.fn();
const observedElements: Element[] = [];
let resizeObserverCallback: ResizeObserverCallback | null = null;

vi.mock('~/plugins/core-designer/components/bpmn-designer/services/bpmnService', () => ({
  getProcessDefinitionByKey: (...args: unknown[]) => getProcessDefinitionByKeyMock(...args),
}));

// xyflow's stylesheet import has no effect in jsdom; silence it.
vi.mock('@xyflow/react/dist/style.css', () => ({}));

// Minimal xyflow stand-in: renders each node with its `highlight` data so the
// test can inspect the wiring. `Controls` / `Background` are irrelevant to
// assertions and stubbed to empty elements.
vi.mock('@xyflow/react', () => ({
  ReactFlow: ({
    nodes,
    children,
  }: {
    nodes: Array<{ id: string; data: { highlight?: string } }>;
    children?: React.ReactNode;
  }) => (
    <div data-testid="xyflow-stub">
      {nodes.map((n) => (
        <span
          key={n.id}
          data-testid={`xyflow-node-${n.id}`}
          data-highlight={n.data.highlight ?? 'none'}
        />
      ))}
      {children}
    </div>
  ),
  Controls: () => <div data-testid="xyflow-controls" />,
  Background: () => <div data-testid="xyflow-background" />,
  useNodesInitialized: () => true,
  useReactFlow: () => ({ fitView: fitViewMock }),
  Handle: () => null,
  Position: { Left: 'left', Right: 'right', Top: 'top', Bottom: 'bottom' },
}));

import { BpmDiagramSection } from '../BpmDiagramSection';
import type { BpmInstanceForRecord } from '~/plugins/core-bpm/services/bpmWorkbenchService';

// Identity translator: returns the fallback (so Chinese copy surfaces) or
// the key itself. Matches the pattern used by BpmStatusSection.test.tsx.
const t = (_key: string, _params?: Record<string, unknown>, fallback?: string): string =>
  fallback ?? _key;

function buildInstance(overrides: Partial<BpmInstanceForRecord> = {}): BpmInstanceForRecord {
  return {
    instanceId: 'pi-42',
    processDefinitionId: 'leave_request', // SmartEngine processKey
    status: 'running',
    currentNodes: [],
    completedNodes: [],
    variables: {},
    ...overrides,
  };
}

function buildDefinition(
  nodes: Array<{ id: string; type?: string; label?: string }>,
): {
  code: string;
  data: {
    id: string;
    name: string;
    key: string;
    status: string;
    nodes: Array<Record<string, unknown>>;
    edges: Array<Record<string, unknown>>;
  };
} {
  return {
    code: '0',
    data: {
      id: 'pd-42',
      name: 'Leave Request',
      key: 'leave_request',
      status: 'published',
      nodes: nodes.map((n) => ({
        id: n.id,
        type: n.type ?? 'userTask',
        position: { x: 0, y: 0 },
        data: { type: n.type ?? 'userTask', label: n.label ?? n.id },
      })),
      edges: [],
    },
  };
}

beforeEach(() => {
  getProcessDefinitionByKeyMock.mockReset();
  fitViewMock.mockReset();
  observedElements.length = 0;
  resizeObserverCallback = null;
  class ResizeObserverMock {
    constructor(callback: ResizeObserverCallback) {
      resizeObserverCallback = callback;
    }

    observe(target: Element) {
      observedElements.push(target);
    }

    unobserve() {}

    disconnect() {}
  }
  vi.stubGlobal('ResizeObserver', ResizeObserverMock);
});

afterEach(() => {
  document.body.innerHTML = '';
  vi.unstubAllGlobals();
});

describe('BpmDiagramSection', () => {
  it('renders nothing when instance is null', () => {
    const { container } = render(<BpmDiagramSection instance={null} t={t} />);

    expect(container.firstChild).toBeNull();
    // Definition lookup must not fire without an instance.
    expect(getProcessDefinitionByKeyMock).not.toHaveBeenCalled();
  });

  it('fetches the definition by processKey and annotates nodes with current/completed/idle', async () => {
    getProcessDefinitionByKeyMock.mockResolvedValue(
      buildDefinition([
        { id: 'start-1', type: 'startEvent', label: 'Start' },
        { id: 'approve-1', type: 'userTask', label: 'Approve' },
        { id: 'end-1', type: 'endEvent', label: 'End' },
      ]),
    );

    const instance = buildInstance({
      processDefinitionId: 'leave_request',
      currentNodes: [
        {
          nodeId: 'approve-1',
          type: 'userTask',
          name: 'Approve',
          status: 'running',
          assignee: 'manager',
          completedAt: null,
          completedBy: null,
        },
      ],
      completedNodes: [
        {
          nodeId: 'start-1',
          type: 'startEvent',
          name: 'Start',
          status: 'completed',
          assignee: null,
          completedAt: '2026-04-17T00:00:00Z',
          completedBy: 'applicant',
        },
      ],
    });

    render(<BpmDiagramSection instance={instance} t={t} />);

    await waitFor(() => {
      expect(screen.getByTestId('bpm-diagram-container')).toBeInTheDocument();
    });

    // Called exactly once, with the instance's processDefinitionId as the key.
    expect(getProcessDefinitionByKeyMock).toHaveBeenCalledTimes(1);
    expect(getProcessDefinitionByKeyMock).toHaveBeenCalledWith('leave_request');

    // Highlight wiring: current node blue, completed node green, untouched idle.
    expect(screen.getByTestId('xyflow-node-approve-1')).toHaveAttribute(
      'data-highlight',
      'current',
    );
    expect(screen.getByTestId('xyflow-node-start-1')).toHaveAttribute(
      'data-highlight',
      'completed',
    );
    expect(screen.getByTestId('xyflow-node-end-1')).toHaveAttribute('data-highlight', 'idle');
  });

  it('renders the error state when the definition fetch rejects', async () => {
    getProcessDefinitionByKeyMock.mockRejectedValue(new Error('network down'));

    render(<BpmDiagramSection instance={buildInstance()} t={t} />);

    await waitFor(() => {
      expect(screen.getByTestId('bpm-diagram-error')).toBeInTheDocument();
    });
    expect(screen.getByTestId('bpm-diagram-error')).toHaveTextContent('network down');
    expect(screen.queryByTestId('bpm-diagram-container')).toBeNull();
  });

  it('re-fits the viewport when the diagram container resizes after initial mount', async () => {
    getProcessDefinitionByKeyMock.mockResolvedValue(
      buildDefinition([{ id: 'approve-1', type: 'userTask', label: 'Approve' }]),
    );

    render(<BpmDiagramSection instance={buildInstance()} t={t} />);

    await waitFor(() => {
      expect(screen.getByTestId('bpm-diagram-container')).toBeInTheDocument();
    });

    expect(observedElements).toHaveLength(1);
    fitViewMock.mockClear();

    resizeObserverCallback?.(
      [
        {
          target: observedElements[0],
          contentRect: {
            width: 820,
            height: 320,
            top: 0,
            left: 0,
            right: 820,
            bottom: 320,
            x: 0,
            y: 0,
            toJSON: () => ({}),
          },
        } as ResizeObserverEntry,
      ],
      {} as ResizeObserver,
    );

    await waitFor(() => {
      expect(fitViewMock).toHaveBeenCalled();
    });
  });
});
