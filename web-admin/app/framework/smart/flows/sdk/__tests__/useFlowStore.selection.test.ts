import { describe, it, expect, beforeEach } from 'vitest';
import { useFlowStore } from '../store/useFlowStore';

describe('useFlowStore edge selection + editing (G1)', () => {
  beforeEach(() => {
    useFlowStore.getState().reset();
  });

  function seed() {
    const s = useFlowStore.getState();
    const n1 = s.addNode({ type: 'a', position: { x: 0, y: 0 }, data: { label: 'A', config: {} } });
    const n2 = s.addNode({ type: 'b', position: { x: 100, y: 0 }, data: { label: 'B', config: {} } });
    const e1 = useFlowStore.getState().addEdge({ source: n1, target: n2 });
    return { n1, n2, e1 };
  }

  it('selectEdge sets selectedEdgeId and clears node selection', () => {
    const { n1, e1 } = seed();
    useFlowStore.getState().selectNode(n1);
    useFlowStore.getState().selectEdge(e1);
    const s = useFlowStore.getState();
    expect(s.selectedEdgeId).toBe(e1);
    expect(s.selectedNodeId).toBeNull();
  });

  it('selectNode clears edge selection (mutual exclusion)', () => {
    const { n1, e1 } = seed();
    useFlowStore.getState().selectEdge(e1);
    useFlowStore.getState().selectNode(n1);
    const s = useFlowStore.getState();
    expect(s.selectedNodeId).toBe(n1);
    expect(s.selectedEdgeId).toBeNull();
  });

  it('updateEdge merges a structured condition into edge data', () => {
    const { e1 } = seed();
    useFlowStore.getState().updateEdge(e1, {
      data: { condition: { type: 'expression', content: 'x > 1' } },
    });
    const edge = useFlowStore.getState().edges.find((e) => e.id === e1)!;
    expect(edge.data?.condition?.content).toBe('x > 1');
  });

  it('deleteEdge clears selectedEdgeId', () => {
    const { e1 } = seed();
    useFlowStore.getState().selectEdge(e1);
    useFlowStore.getState().deleteEdge(e1);
    expect(useFlowStore.getState().selectedEdgeId).toBeNull();
  });

  it('deleteNode clears the selected edge when that edge is removed with the node', () => {
    const { n1, e1 } = seed();
    useFlowStore.getState().selectEdge(e1);
    useFlowStore.getState().deleteNode(n1); // removes the connected edge e1
    const s = useFlowStore.getState();
    expect(s.edges.find((e) => e.id === e1)).toBeUndefined();
    expect(s.selectedEdgeId).toBeNull();
  });

  it('importData / reset clear edge selection', () => {
    const { e1 } = seed();
    useFlowStore.getState().selectEdge(e1);
    useFlowStore.getState().reset();
    expect(useFlowStore.getState().selectedEdgeId).toBeNull();
  });
});
