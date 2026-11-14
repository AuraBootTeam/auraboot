// web-admin/app/flow-designer-sdk/__tests__/useNodeNeighbors.test.tsx
import { describe, it, expect, beforeEach } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { useFlowStore } from '../store/useFlowStore';
import { useNodeNeighbors } from '../hooks/useNodeNeighbors';
import type { FlowNode, FlowEdge } from '../store/types';

/**
 * Mini fixture: start → service-task with two outgoing edges (G7 main use
 * case: BPMN ExclusiveGatewayEditor needs to enumerate outgoing flows).
 */
function seedTwoOutgoing() {
  const s = useFlowStore.getState();
  const start = s.addNode({
    type: 'startEvent',
    position: { x: 0, y: 0 },
    data: { label: 'Start', config: {} },
  });
  const service = s.addNode({
    type: 'serviceTask',
    position: { x: 200, y: 0 },
    data: { label: 'Svc', config: {} },
  });
  const branchA = useFlowStore.getState().addNode({
    type: 'endEvent',
    position: { x: 400, y: -80 },
    data: { label: 'EndA', config: {} },
  });
  const branchB = useFlowStore.getState().addNode({
    type: 'endEvent',
    position: { x: 400, y: 80 },
    data: { label: 'EndB', config: {} },
  });
  const eIn = useFlowStore.getState().addEdge({ source: start, target: service });
  const eOutA = useFlowStore
    .getState()
    .addEdge({ source: service, target: branchA, data: { label: 'A' } });
  const eOutB = useFlowStore
    .getState()
    .addEdge({ source: service, target: branchB, data: { label: 'B' } });
  return { start, service, branchA, branchB, eIn, eOutA, eOutB };
}

describe('useNodeNeighbors (G7)', () => {
  beforeEach(() => {
    useFlowStore.getState().reset();
  });

  it('returns empty arrays for a node with no incoming and no outgoing edges', () => {
    const isolated = useFlowStore
      .getState()
      .addNode({ type: 'x', position: { x: 0, y: 0 }, data: { label: 'X', config: {} } });
    const { result } = renderHook(() => useNodeNeighbors(isolated));
    expect(result.current.incoming).toEqual([]);
    expect(result.current.outgoing).toEqual([]);
    expect(result.current.sourceNodes).toEqual([]);
    expect(result.current.targetNodes).toEqual([]);
  });

  it('returns only incoming for a sink node (no outgoing)', () => {
    const { branchA, eOutA } = seedTwoOutgoing();
    const { result } = renderHook(() => useNodeNeighbors(branchA));
    expect(result.current.incoming).toHaveLength(1);
    expect(result.current.incoming[0].id).toBe(eOutA);
    expect(result.current.outgoing).toHaveLength(0);
    expect(result.current.sourceNodes.map((n: FlowNode) => n.data.label)).toEqual(['Svc']);
  });

  it('returns a single outgoing edge for a source node', () => {
    const { start, service, eIn } = seedTwoOutgoing();
    const { result } = renderHook(() => useNodeNeighbors(start));
    expect(result.current.outgoing).toHaveLength(1);
    expect(result.current.outgoing[0].id).toBe(eIn);
    expect(result.current.targetNodes[0].id).toBe(service);
    expect(result.current.incoming).toHaveLength(0);
  });

  it('returns all outgoing edges and target nodes for a fan-out node (default-flow editor use case)', () => {
    const { service, branchA, branchB, eOutA, eOutB, eIn } = seedTwoOutgoing();
    const { result } = renderHook(() => useNodeNeighbors(service));
    expect(result.current.incoming).toHaveLength(1);
    expect(result.current.incoming[0].id).toBe(eIn);
    const outgoingIds = result.current.outgoing.map((e: FlowEdge) => e.id).sort();
    expect(outgoingIds).toEqual([eOutA, eOutB].sort());
    const targetIds = result.current.targetNodes.map((n: FlowNode) => n.id).sort();
    expect(targetIds).toEqual([branchA, branchB].sort());
  });

  it('reacts when an outgoing edge is re-targeted to a different node', () => {
    // Minimal fixture (single outgoing edge) to avoid the pre-existing
    // edge-id collision when multiple edges are added in the same ms.
    const s = useFlowStore.getState();
    const src = s.addNode({
      type: 'serviceTask',
      position: { x: 0, y: 0 },
      data: { label: 'Svc', config: {} },
    });
    const oldTarget = useFlowStore
      .getState()
      .addNode({ type: 'endEvent', position: { x: 100, y: 0 }, data: { label: 'Old', config: {} } });
    const newTarget = useFlowStore
      .getState()
      .addNode({ type: 'endEvent', position: { x: 200, y: 0 }, data: { label: 'New', config: {} } });
    const eId = useFlowStore.getState().addEdge({ source: src, target: oldTarget });

    const { result, rerender } = renderHook(() => useNodeNeighbors(src));
    expect(result.current.targetNodes.map((n: FlowNode) => n.id)).toEqual([oldTarget]);

    act(() => {
      useFlowStore.getState().updateEdge(eId, { target: newTarget });
    });
    rerender();
    expect(result.current.targetNodes.map((n: FlowNode) => n.id)).toEqual([newTarget]);
    expect(result.current.outgoing[0].target).toBe(newTarget);
  });

  it('returns frozen empty result for a null/undefined nodeId', () => {
    seedTwoOutgoing();
    const { result } = renderHook(() => useNodeNeighbors(null));
    expect(result.current.incoming).toEqual([]);
    expect(result.current.outgoing).toEqual([]);
  });
});
