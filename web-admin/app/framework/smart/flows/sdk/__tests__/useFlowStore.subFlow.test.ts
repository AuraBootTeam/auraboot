// B2c: sub-flow drilldown stack + currentMonitorNodeIds selector tests.
import { describe, it, expect, beforeEach } from 'vitest';
import { useFlowStore } from '../store/useFlowStore';
import type { FlowNode, FlowEdge } from '../store/types';

const outerNode = (id: string): FlowNode => ({
  id,
  type: 'sub-process',
  position: { x: 0, y: 0 },
  data: { label: id, config: {} },
});

const innerNode = (id: string): FlowNode => ({
  id,
  type: 'task',
  position: { x: 10, y: 10 },
  data: { label: id, config: {} },
});

const edge = (id: string, source: string, target: string): FlowEdge => ({
  id,
  source,
  target,
});

describe('useFlowStore — sub-flow drilldown', () => {
  beforeEach(() => {
    useFlowStore.getState().reset();
  });

  it('initial state has empty subFlowStack', () => {
    expect(useFlowStore.getState().subFlowStack).toEqual([]);
    expect(useFlowStore.getState().getSubFlowPath()).toEqual([]);
  });

  it('pushSubFlow swaps visible nodes/edges and stacks the outer frame', () => {
    const s0 = useFlowStore.getState();
    s0.importData({
      nodes: [outerNode('outer1'), outerNode('outer2')],
      edges: [edge('e_outer', 'outer1', 'outer2')],
    });
    s0.selectNode('outer1');

    const inner = [innerNode('inner1'), innerNode('inner2')];
    const innerEdges = [edge('e_inner', 'inner1', 'inner2')];
    useFlowStore.getState().pushSubFlow('outer1', inner, innerEdges, 'Sub A');

    const s1 = useFlowStore.getState();
    expect(s1.nodes.map((n) => n.id)).toEqual(['inner1', 'inner2']);
    expect(s1.edges.map((e) => e.id)).toEqual(['e_inner']);
    expect(s1.selectedNodeId).toBeNull();
    expect(s1.subFlowStack).toHaveLength(1);
    expect(s1.subFlowStack[0].parentNodeId).toBe('outer1');
    expect(s1.subFlowStack[0].label).toBe('Sub A');
    expect(s1.subFlowStack[0].outerNodes.map((n) => n.id)).toEqual([
      'outer1',
      'outer2',
    ]);
    expect(s1.subFlowStack[0].outerSelectedNodeId).toBe('outer1');
    expect(s1.getSubFlowPath()).toEqual([
      { parentNodeId: 'outer1', label: 'Sub A' },
    ]);
  });

  it('popSubFlow restores outer nodes/edges and selection', () => {
    const s = useFlowStore.getState();
    s.importData({
      nodes: [outerNode('o1')],
      edges: [],
    });
    s.selectNode('o1');
    useFlowStore.getState().pushSubFlow('o1', [innerNode('i1')], []);
    // Mutate inner; should not bleed into outer
    useFlowStore.getState().updateNode('i1', { position: { x: 999, y: 999 } });

    useFlowStore.getState().popSubFlow();
    const restored = useFlowStore.getState();
    expect(restored.nodes.map((n) => n.id)).toEqual(['o1']);
    expect(restored.selectedNodeId).toBe('o1');
    expect(restored.subFlowStack).toEqual([]);
  });

  it('supports multi-level nesting (push twice, pop twice)', () => {
    const s = useFlowStore.getState();
    s.importData({ nodes: [outerNode('L0')], edges: [] });

    useFlowStore.getState().pushSubFlow('L0', [innerNode('L1')], [], 'level1');
    useFlowStore.getState().pushSubFlow('L1', [innerNode('L2')], [], 'level2');

    let st = useFlowStore.getState();
    expect(st.nodes[0].id).toBe('L2');
    expect(st.subFlowStack).toHaveLength(2);
    expect(st.getSubFlowPath()).toEqual([
      { parentNodeId: 'L0', label: 'level1' },
      { parentNodeId: 'L1', label: 'level2' },
    ]);

    useFlowStore.getState().popSubFlow();
    st = useFlowStore.getState();
    expect(st.nodes[0].id).toBe('L1');
    expect(st.subFlowStack).toHaveLength(1);

    useFlowStore.getState().popSubFlow();
    st = useFlowStore.getState();
    expect(st.nodes[0].id).toBe('L0');
    expect(st.subFlowStack).toEqual([]);
  });

  it('popSubFlow on empty stack is a no-op', () => {
    const s = useFlowStore.getState();
    s.importData({ nodes: [outerNode('o1')], edges: [] });
    useFlowStore.getState().popSubFlow();
    expect(useFlowStore.getState().nodes[0].id).toBe('o1');
    expect(useFlowStore.getState().subFlowStack).toEqual([]);
  });

  it('inner-frame undo does not pop the drilldown', () => {
    const s = useFlowStore.getState();
    s.importData({ nodes: [outerNode('o1')], edges: [] });
    useFlowStore.getState().pushSubFlow('o1', [innerNode('i1')], []);

    // Mutate to push a snapshot, then undo
    useFlowStore.getState().updateNode('i1', { position: { x: 5, y: 5 } });
    useFlowStore.getState().undo();

    const st = useFlowStore.getState();
    // still inside the sub-flow
    expect(st.subFlowStack).toHaveLength(1);
    expect(st.nodes[0].id).toBe('i1');
    // position was reverted
    expect(st.nodes[0].position).toEqual({ x: 10, y: 10 });
  });

  it('resetSubFlowStack clears stack without touching visible nodes', () => {
    const s = useFlowStore.getState();
    s.importData({ nodes: [outerNode('o1')], edges: [] });
    useFlowStore.getState().pushSubFlow('o1', [innerNode('i1')], []);
    useFlowStore.getState().resetSubFlowStack();
    const st = useFlowStore.getState();
    expect(st.subFlowStack).toEqual([]);
    // visible nodes are NOT reverted (caller is responsible)
    expect(st.nodes[0].id).toBe('i1');
  });

  it('reset() clears subFlowStack', () => {
    const s = useFlowStore.getState();
    s.importData({ nodes: [outerNode('o1')], edges: [] });
    useFlowStore.getState().pushSubFlow('o1', [innerNode('i1')], []);
    useFlowStore.getState().reset();
    expect(useFlowStore.getState().subFlowStack).toEqual([]);
  });
});

describe('useFlowStore — getCurrentMonitorNodeIds selector', () => {
  beforeEach(() => {
    useFlowStore.getState().reset();
  });

  it('returns empty Set when monitorMode is off', () => {
    useFlowStore.getState().setMonitorData({
      n1: { status: 'running' },
    });
    expect(useFlowStore.getState().getCurrentMonitorNodeIds().size).toBe(0);
  });

  it('returns empty Set when monitorData is empty', () => {
    useFlowStore.getState().setMonitorMode(true);
    expect(useFlowStore.getState().getCurrentMonitorNodeIds().size).toBe(0);
  });

  it('returns nodeIds whose status is running (filters out other statuses)', () => {
    useFlowStore.getState().setMonitorMode(true);
    useFlowStore.getState().setMonitorData({
      n1: { status: 'running' },
      n2: { status: 'running' },
      n3: { status: 'completed' },
      n4: { status: 'failed' },
      n5: { status: 'idle' },
    });
    const ids = useFlowStore.getState().getCurrentMonitorNodeIds();
    expect(ids.size).toBe(2);
    expect(ids.has('n1')).toBe(true);
    expect(ids.has('n2')).toBe(true);
  });
});
