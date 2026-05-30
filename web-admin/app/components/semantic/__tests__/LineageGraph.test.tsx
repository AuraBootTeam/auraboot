/**
 * LineageGraph.test.tsx
 *
 * Unit tests (vitest + jsdom) for the LineageGraph component.
 *
 * Coverage:
 *   1. Empty state (no nodePid)  — renders empty-state testid
 *   2. Loading state             — shows loading testid while fetch is in-flight
 *   3. Graph rendered            — mock response with 2 incoming + 2 outgoing edges
 *                                  → container testid present, nodes rendered in xyflow stub
 *   4. Node type colour coding   — each node carries correct `data-node-type` attribute
 *   5. Error state               — rejected fetch → error testid surfaced
 *
 * @xyflow/react is mocked (browser-only canvas APIs not available in jsdom).
 */

import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';

// ---------------------------------------------------------------------------
// Mocks — must be hoisted before component import
// ---------------------------------------------------------------------------

vi.mock('@xyflow/react/dist/style.css', () => ({}));

const fitViewMock = vi.fn();

vi.mock('@xyflow/react', () => ({
  ReactFlow: ({
    nodes,
    children,
  }: {
    nodes: Array<{ id: string; data: Record<string, unknown> }>;
    children?: React.ReactNode;
  }) => (
    <div data-testid="xyflow-stub">
      {nodes.map((n) => (
        <span
          key={n.id}
          data-testid={`lineage-node-${n.id}`}
          data-node-type={String(n.data['nodeType'] ?? '')}
          data-is-focal={String(Boolean(n.data['isFocal']))}
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

const fetchLineageMock = vi.fn();

vi.mock('~/plugins/core-semantic/api/semanticApi', () => ({
  fetchLineage: (...args: unknown[]) => fetchLineageMock(...args),
}));

// ---------------------------------------------------------------------------
// Import component under test (after mocks)
// ---------------------------------------------------------------------------

import { LineageGraph } from '../LineageGraph';
import type { LineageResponse } from '~/plugins/core-semantic/api/semanticApi';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

const t = (_key: string, _params?: Record<string, unknown>, fallback?: string): string =>
  fallback ?? _key;

function buildResponse(overrides: Partial<LineageResponse> = {}): LineageResponse {
  return {
    nodePid: 'model-pid-001',
    nodeType: 'MODEL',
    incoming: [
      { srcPid: 'dim-pid-001', srcType: 'DIMENSION', dstPid: 'model-pid-001', dstType: 'MODEL', refType: 'MODEL_USES_DIMENSION' },
      { srcPid: 'dim-pid-002', srcType: 'DIMENSION', dstPid: 'model-pid-001', dstType: 'MODEL', refType: 'MODEL_USES_DIMENSION' },
    ],
    outgoing: [
      { srcPid: 'model-pid-001', srcType: 'MODEL', dstPid: 'metric-pid-001', dstType: 'METRIC', refType: 'MODEL_DEFINES_METRIC' },
      { srcPid: 'model-pid-001', srcType: 'MODEL', dstPid: 'metric-pid-002', dstType: 'METRIC', refType: 'MODEL_DEFINES_METRIC' },
    ],
    ...overrides,
  };
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe('LineageGraph', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('1. renders empty state when nodePid is empty string', () => {
    render(<LineageGraph nodePid="" nodeType="" t={t} />);
    expect(screen.getByTestId('lineage-graph-empty')).toBeInTheDocument();
    expect(screen.queryByTestId('lineage-graph-loading')).not.toBeInTheDocument();
    expect(screen.queryByTestId('xyflow-stub')).not.toBeInTheDocument();
    expect(fetchLineageMock).not.toHaveBeenCalled();
  });

  it('2. shows loading state while fetch is in-flight', async () => {
    // Keep the promise pending so the loading state stays visible.
    fetchLineageMock.mockReturnValue(new Promise(() => {}));

    render(<LineageGraph nodePid="model-pid-001" nodeType="MODEL" t={t} />);

    expect(screen.getByTestId('lineage-graph-loading')).toBeInTheDocument();
    expect(screen.queryByTestId('xyflow-stub')).not.toBeInTheDocument();
  });

  it('3. renders graph with nodes after successful fetch (2 incoming + 2 outgoing edges)', async () => {
    fetchLineageMock.mockResolvedValue(buildResponse());

    render(<LineageGraph nodePid="model-pid-001" nodeType="MODEL" t={t} />);

    await waitFor(() => {
      expect(screen.getByTestId('lineage-graph-container')).toBeInTheDocument();
    });

    // Focal node + 2 incoming + 2 outgoing = 5 nodes total
    expect(screen.getByTestId('lineage-node-model-pid-001')).toBeInTheDocument();
    expect(screen.getByTestId('lineage-node-dim-pid-001')).toBeInTheDocument();
    expect(screen.getByTestId('lineage-node-dim-pid-002')).toBeInTheDocument();
    expect(screen.getByTestId('lineage-node-metric-pid-001')).toBeInTheDocument();
    expect(screen.getByTestId('lineage-node-metric-pid-002')).toBeInTheDocument();
  });

  it('4. node type colour coding — correct data-node-type on each rendered node', async () => {
    fetchLineageMock.mockResolvedValue(buildResponse());

    render(<LineageGraph nodePid="model-pid-001" nodeType="MODEL" t={t} />);

    await waitFor(() => {
      expect(screen.getByTestId('lineage-node-model-pid-001')).toBeInTheDocument();
    });

    expect(screen.getByTestId('lineage-node-model-pid-001')).toHaveAttribute('data-node-type', 'MODEL');
    expect(screen.getByTestId('lineage-node-dim-pid-001')).toHaveAttribute('data-node-type', 'DIMENSION');
    expect(screen.getByTestId('lineage-node-dim-pid-002')).toHaveAttribute('data-node-type', 'DIMENSION');
    expect(screen.getByTestId('lineage-node-metric-pid-001')).toHaveAttribute('data-node-type', 'METRIC');
    expect(screen.getByTestId('lineage-node-metric-pid-002')).toHaveAttribute('data-node-type', 'METRIC');
  });

  it('5. renders error state on fetch rejection', async () => {
    fetchLineageMock.mockRejectedValue(new Error('network error'));

    render(<LineageGraph nodePid="model-pid-001" nodeType="MODEL" t={t} />);

    await waitFor(() => {
      expect(screen.getByTestId('lineage-graph-error')).toBeInTheDocument();
    });

    expect(screen.getByTestId('lineage-graph-error')).toHaveTextContent('network error');
    expect(screen.queryByTestId('xyflow-stub')).not.toBeInTheDocument();
  });

  it('6. focal node has isFocal flag set to true', async () => {
    fetchLineageMock.mockResolvedValue(buildResponse());

    render(<LineageGraph nodePid="model-pid-001" nodeType="MODEL" t={t} />);

    await waitFor(() => {
      expect(screen.getByTestId('lineage-node-model-pid-001')).toBeInTheDocument();
    });

    const focalNode = screen.getByTestId('lineage-node-model-pid-001');
    expect(focalNode).toHaveAttribute('data-is-focal', 'true');

    // Non-focal nodes should NOT have isFocal=true
    expect(screen.getByTestId('lineage-node-dim-pid-001')).toHaveAttribute('data-is-focal', 'false');
  });
});
