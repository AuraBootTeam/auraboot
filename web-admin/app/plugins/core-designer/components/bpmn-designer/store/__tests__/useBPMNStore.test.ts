/**
 * Unit tests for useBPMNStore.
 *
 * Primary regression: `setProcessDefinition` must not throw when called with
 * nodes/edges that carry React Flow internal fields (measured, handleBounds,
 * selected, dragging, etc.). These fields appear on the live canvas state
 * after React Flow enriches them and break `structuredClone` (DataCloneError),
 * which previously blocked the UI-first "save from scratch" path.
 */

import { describe, it, expect, beforeEach } from 'vitest';
import { useBPMNStore } from '~/plugins/core-designer/components/bpmn-designer/store/useBPMNStore';
import {
  BPMNNodeType,
  type BPMNNode,
  type BPMNEdge,
  type BPMNProcessDefinition,
} from '~/plugins/core-designer/components/bpmn-designer/types';

/**
 * Build a node object that mimics what React Flow passes back into the store
 * after it has enriched the caller-provided node with internal bookkeeping.
 *
 * `measured` here carries a fake `ResizeObserver`-backed ref that
 * `structuredClone` cannot serialize, matching the real-world failure mode.
 */
function rfEnrichedNode(id: string, type: BPMNNodeType): BPMNNode {
  const domRef = {
    observe: () => {},
    disconnect: () => {},
  };
  return {
    id,
    type,
    position: { x: 100, y: 100 },
    // React Flow internal fields (should be stripped by the store).
    measured: { width: 120, height: 60, ref: domRef },
    handleBounds: { source: [], target: [] },
    selected: false,
    dragging: false,
    data: {
      type,
      label: `Node ${id}`,
      config: { name: `Node ${id}` },
    },
  } as unknown as BPMNNode;
}

function rfEnrichedEdge(id: string, source: string, target: string): BPMNEdge {
  return {
    id,
    source,
    target,
    type: 'smoothstep',
    selected: false,
    interactionWidth: 20,
    data: { label: '' },
  } as unknown as BPMNEdge;
}

describe('useBPMNStore.setProcessDefinition', () => {
  beforeEach(() => {
    useBPMNStore.getState().reset();
  });

  it('does not throw DataCloneError when definition carries React Flow internal fields', () => {
    const definition: BPMNProcessDefinition = {
      id: 'pd-1',
      name: 'Test Process',
      key: 'test_process',
      version: 1,
      status: 'draft',
      nodes: [
        rfEnrichedNode('start', BPMNNodeType.START_EVENT),
        rfEnrichedNode('task', BPMNNodeType.USER_TASK),
        rfEnrichedNode('end', BPMNNodeType.END_EVENT),
      ],
      edges: [rfEnrichedEdge('e1', 'start', 'task'), rfEnrichedEdge('e2', 'task', 'end')],
    };

    expect(() => {
      useBPMNStore.getState().setProcessDefinition(definition);
    }).not.toThrow();

    const state = useBPMNStore.getState();
    expect(state.processDefinition?.id).toBe('pd-1');
    expect(state.nodes).toHaveLength(3);
    expect(state.edges).toHaveLength(2);
    // Internal fields must be stripped out so the store stays cloneable.
    expect((state.nodes[0] as unknown as Record<string, unknown>).measured).toBeUndefined();
    expect((state.nodes[0] as unknown as Record<string, unknown>).handleBounds).toBeUndefined();
    expect((state.edges[0] as unknown as Record<string, unknown>).interactionWidth).toBeUndefined();
    // Semantic fields preserved.
    expect(state.nodes[0].data.label).toBe('Node start');
    expect(state.isDirty).toBe(false);
    expect(state.history).toHaveLength(1);
  });

  it('setProcessDefinition then addNode: history push does not throw', () => {
    const definition: BPMNProcessDefinition = {
      name: 'Fresh',
      key: 'fresh',
      nodes: [rfEnrichedNode('start', BPMNNodeType.START_EVENT)],
      edges: [],
    };
    useBPMNStore.getState().setProcessDefinition(definition);

    expect(() => {
      useBPMNStore.getState().addNode({
        id: 'task2',
        type: BPMNNodeType.USER_TASK,
        position: { x: 200, y: 200 },
        data: { type: BPMNNodeType.USER_TASK, label: 'Task 2' },
      } as BPMNNode);
    }).not.toThrow();

    expect(useBPMNStore.getState().nodes).toHaveLength(2);
    expect(useBPMNStore.getState().history.length).toBeGreaterThan(1);
  });

  it('syncs back the id returned from save response so deploy button can unblock', () => {
    // Simulate initial UI-first state (no id yet).
    useBPMNStore.getState().setProcessDefinition({
      name: 'Draft',
      key: 'draft_proc',
      nodes: [rfEnrichedNode('start', BPMNNodeType.START_EVENT)],
      edges: [],
    });
    expect(useBPMNStore.getState().processDefinition?.id).toBeUndefined();

    // Simulate save() response from backend writing id back.
    useBPMNStore.getState().setProcessDefinition({
      id: 'pd-new-123',
      name: 'Draft',
      key: 'draft_proc',
      version: 1,
      status: 'draft',
      nodes: [rfEnrichedNode('start', BPMNNodeType.START_EVENT)],
      edges: [],
    });

    expect(useBPMNStore.getState().processDefinition?.id).toBe('pd-new-123');
    expect(useBPMNStore.getState().isDirty).toBe(false);
  });
});
