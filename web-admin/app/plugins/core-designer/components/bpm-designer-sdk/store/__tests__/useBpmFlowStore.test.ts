/**
 * B2c Phase 2 — `useBpmFlowStore` adapter unit tests.
 *
 * Verifies the adapter preserves the legacy `useBPMNStore` selector shape,
 * routes shared state through the SDK `useFlowStore`, and provides the
 * `.getState() / .setState() / .subscribe()` shims consumers need.
 *
 * Coverage matrix (≥ 15 cases):
 *
 *   Public surface (3)
 *     1. adapter exposes all selector keys from useBPMNStore
 *     2. nodes/edges/selection/dirty come from useFlowStore (delegation read)
 *     3. setProcessDefinition seeds both stores + clears isDirty
 *
 *   Mutations through SDK (5)
 *     4. addNode → useFlowStore.nodes contains it + history snapshot pushed
 *     5. updateNode merges data + pushes snapshot
 *     6. deleteNode delegates to SDK + cascades to edges
 *     7. addEdge / updateEdge / deleteEdge round-trip through SDK
 *     8. setNodes / setEdges bypass history snapshot (per-frame RF callback)
 *
 *   BPMN-only state (3)
 *     9. setPreviewMode / setSaving / setDirty round-trip
 *    10. setViewMode('design') clears instanceStatus + monitorInstanceId
 *    11. clearInstanceStatus clears SDK monitorData
 *
 *   BPMN-only actions (3)
 *    12. validate() returns start_event_required when nodes empty
 *    13. validate() returns exclusive_gateway_edge_missing_condition
 *    14. loadVersionData + backToCurrent round-trip _savedCurrentState
 *
 *   importFromJSON + reset (2)
 *    15. importFromJSON populates SDK + processDefinition (when id/name)
 *    16. reset clears both sidecar and SDK
 *
 *   Store-like shims (3)
 *    17. .getState() returns merged snapshot with both BPMN + SDK keys
 *    18. .setState() routes partial per-key (BPMN vs SDK)
 *    19. .subscribe() fires on either store mutation
 */

import { describe, it, expect, beforeEach, vi } from 'vitest';
import { useBpmFlowStore } from '../useBpmFlowStore';
import { useFlowStore } from '~/plugins/core-designer/components/flow-designer-sdk/store/useFlowStore';
import { BPMNNodeType, type BPMNNode, type BPMNEdge } from '~/plugins/core-designer/components/bpmn-designer/types';

vi.mock('~/plugins/core-designer/components/bpmn-designer/services/bpmnService', () => ({
  deployProcessDefinition: vi.fn(async () => ({ data: { status: 'published' } })),
  getProcessInstanceStatus: vi.fn(async () => ({
    data: {
      processDefinitionId: 'pd-1',
      currentNodes: [{ nodeId: 'n2' }],
      completedNodes: [{ nodeId: 'n1' }],
    },
  })),
  normalizeDesignerJsonPayload: (json: any) => ({
    nodes: json.nodes || [],
    edges: json.edges || [],
    aura: json.aura,
  }),
}));

function makeNode(id: string, type = BPMNNodeType.USER_TASK, x = 0, y = 0): BPMNNode {
  return {
    id,
    type,
    position: { x, y },
    data: { type, label: id, config: {} },
  } as unknown as BPMNNode;
}

function makeEdge(id: string, source: string, target: string, condition?: string): BPMNEdge {
  return {
    id,
    source,
    target,
    data: condition
      ? { label: '', condition: { type: 'expression', content: condition } }
      : { label: '' },
  } as unknown as BPMNEdge;
}

beforeEach(() => {
  // Reset adapter (and underlying stores) to a known baseline.
  useBpmFlowStore.getState().reset();
});

describe('useBpmFlowStore — adapter shape', () => {
  it('exposes all selector keys from the legacy useBPMNStore', () => {
    const s = useBpmFlowStore.getState();
    const expectedStateKeys = [
      'processDefinition',
      'nodes',
      'edges',
      'selectedNodeId',
      'selectedEdgeId',
      'isPreviewMode',
      'isDirty',
      'isSaving',
      'isDeploying',
      'history',
      'historyIndex',
      'viewMode',
      'instanceStatus',
      'monitorInstanceId',
      'viewingVersionId',
      'validationResult',
    ];
    for (const k of expectedStateKeys) {
      expect(s).toHaveProperty(k);
    }
    const expectedActionKeys = [
      'setProcessDefinition',
      'setNodes',
      'setEdges',
      'addNode',
      'updateNode',
      'deleteNode',
      'addEdge',
      'updateEdge',
      'deleteEdge',
      'setSelectedNode',
      'setSelectedEdge',
      'setPreviewMode',
      'setDirty',
      'setSaving',
      'validate',
      'reset',
      'loadVersionData',
      'backToCurrent',
      'deployProcess',
      'setViewMode',
      'setMonitorInstanceId',
      'fetchInstanceStatus',
      'clearInstanceStatus',
      'undo',
      'redo',
      'canUndo',
      'canRedo',
      'importFromJSON',
      'getNodeById',
      'getEdgeById',
      'getConnectedEdges',
    ];
    for (const k of expectedActionKeys) {
      expect(typeof (s as any)[k]).toBe('function');
    }
  });

  it('reads nodes/edges/selection from SDK useFlowStore', () => {
    const a = makeNode('n1');
    useFlowStore.setState({
      nodes: [a as any],
      selectedNodeId: 'n1',
      isDirty: true,
    });
    const s = useBpmFlowStore.getState();
    expect(s.nodes).toHaveLength(1);
    expect(s.nodes[0].id).toBe('n1');
    expect(s.selectedNodeId).toBe('n1');
    expect(s.isDirty).toBe(true);
  });

  it('setProcessDefinition seeds both stores + clears isDirty', () => {
    const def = {
      id: 'pd-1',
      name: 'p',
      key: 'p',
      nodes: [makeNode('n1', BPMNNodeType.START_EVENT)],
      edges: [],
    } as any;
    useBpmFlowStore.getState().setProcessDefinition(def);
    const s = useBpmFlowStore.getState();
    expect(s.processDefinition?.id).toBe('pd-1');
    expect(s.nodes).toHaveLength(1);
    expect(s.isDirty).toBe(false);
  });
});

describe('useBpmFlowStore — mutations through SDK', () => {
  it('addNode appends to SDK nodes + pushes history snapshot', () => {
    const before = useFlowStore.getState().historyIndex;
    useBpmFlowStore.getState().addNode(makeNode('n1'));
    const after = useFlowStore.getState();
    expect(after.nodes).toHaveLength(1);
    expect(after.nodes[0].id).toBe('n1');
    expect(after.historyIndex).toBe(before + 1);
    expect(after.isDirty).toBe(true);
  });

  it('updateNode merges data + pushes snapshot', () => {
    useBpmFlowStore.getState().addNode(makeNode('n1'));
    const before = useFlowStore.getState().historyIndex;
    useBpmFlowStore.getState().updateNode('n1', { label: 'updated' });
    const after = useFlowStore.getState();
    expect(after.nodes[0].data.label).toBe('updated');
    expect(after.historyIndex).toBe(before + 1);
  });

  it('deleteNode cascades to connected edges via SDK', () => {
    useBpmFlowStore.getState().addNode(makeNode('n1'));
    useBpmFlowStore.getState().addNode(makeNode('n2'));
    useBpmFlowStore.getState().addEdge(makeEdge('e1', 'n1', 'n2'));
    expect(useFlowStore.getState().edges).toHaveLength(1);
    useBpmFlowStore.getState().deleteNode('n1');
    expect(useFlowStore.getState().nodes).toHaveLength(1);
    expect(useFlowStore.getState().edges).toHaveLength(0);
  });

  it('addEdge / updateEdge / deleteEdge round-trip through SDK', () => {
    useBpmFlowStore.getState().addEdge(makeEdge('e1', 'a', 'b'));
    expect(useFlowStore.getState().edges).toHaveLength(1);
    useBpmFlowStore.getState().updateEdge('e1', { label: 'cond' });
    expect((useFlowStore.getState().edges[0] as any).label).toBe('cond');
    useBpmFlowStore.getState().deleteEdge('e1');
    expect(useFlowStore.getState().edges).toHaveLength(0);
  });

  it('setNodes / setEdges bypass history snapshot (per-frame React Flow)', () => {
    useBpmFlowStore.getState().addNode(makeNode('n1'));
    const baselineHistoryIdx = useFlowStore.getState().historyIndex;
    useBpmFlowStore.getState().setNodes([makeNode('n1'), makeNode('n2')]);
    useBpmFlowStore.getState().setEdges([makeEdge('e1', 'n1', 'n2')]);
    expect(useFlowStore.getState().nodes).toHaveLength(2);
    expect(useFlowStore.getState().edges).toHaveLength(1);
    // No snapshot pushed: historyIndex unchanged.
    expect(useFlowStore.getState().historyIndex).toBe(baselineHistoryIdx);
  });
});

describe('useBpmFlowStore — BPMN-only state', () => {
  it('setPreviewMode / setSaving / setDirty round-trip', () => {
    useBpmFlowStore.getState().setPreviewMode(true);
    useBpmFlowStore.getState().setSaving(true);
    useBpmFlowStore.getState().setDirty(true);
    const s = useBpmFlowStore.getState();
    expect(s.isPreviewMode).toBe(true);
    expect(s.isSaving).toBe(true);
    expect(s.isDirty).toBe(true);
  });

  it('setViewMode(design) clears instanceStatus + monitorInstanceId', () => {
    useBpmFlowStore.getState().setViewMode('monitor');
    useBpmFlowStore.getState().setMonitorInstanceId('inst-1');
    // Inject some instanceStatus via setState shim
    useBpmFlowStore.setState({ instanceStatus: { processDefinitionId: 'pd' } as any });
    expect(useBpmFlowStore.getState().instanceStatus).not.toBeNull();
    useBpmFlowStore.getState().setViewMode('design');
    const s = useBpmFlowStore.getState();
    expect(s.viewMode).toBe('design');
    expect(s.instanceStatus).toBeNull();
    expect(s.monitorInstanceId).toBe('');
    expect(useFlowStore.getState().monitorMode).toBe(false);
  });

  it('clearInstanceStatus also clears SDK monitorData', () => {
    useFlowStore.getState().setMonitorData({ n1: { status: 'running' } });
    useBpmFlowStore.setState({ instanceStatus: { id: 'x' } as any, monitorInstanceId: 'inst-1' });
    useBpmFlowStore.getState().clearInstanceStatus();
    expect(useBpmFlowStore.getState().instanceStatus).toBeNull();
    expect(useBpmFlowStore.getState().monitorInstanceId).toBe('');
    expect(useFlowStore.getState().monitorData).toEqual({});
  });
});

describe('useBpmFlowStore — BPMN validation rules', () => {
  it('validate() emits start_event_required when nodes empty', () => {
    const result = useBpmFlowStore.getState().validate();
    expect(result.valid).toBe(false);
    expect(result.errors.some((e) => e.message === 'bpmn.validate.start_event_required')).toBe(
      true,
    );
  });

  it('validate() emits exclusive_gateway_edge_missing_condition when outgoing has no condition', () => {
    const gw = makeNode('gw', BPMNNodeType.EXCLUSIVE_GATEWAY);
    const a = makeNode('a', BPMNNodeType.START_EVENT);
    const b = makeNode('b', BPMNNodeType.END_EVENT);
    const c = makeNode('c', BPMNNodeType.END_EVENT);
    useFlowStore.setState({
      nodes: [a, gw, b, c] as any,
      edges: [
        makeEdge('e1', 'a', 'gw'),
        makeEdge('e2', 'gw', 'b'), // no condition
        makeEdge('e3', 'gw', 'c'), // no condition
      ] as any,
    });
    const result = useBpmFlowStore.getState().validate();
    expect(
      result.errors.some(
        (e) => e.message === 'bpmn.validate.exclusive_gateway_edge_missing_condition',
      ),
    ).toBe(true);
  });
});

describe('useBpmFlowStore — version preview round-trip', () => {
  it('loadVersionData saves current state then backToCurrent restores it', () => {
    const def = {
      id: 'pd-1',
      name: 'p',
      key: 'p',
      nodes: [makeNode('current-1', BPMNNodeType.START_EVENT)],
      edges: [],
    } as any;
    useBpmFlowStore.getState().setProcessDefinition(def);
    expect(useBpmFlowStore.getState().nodes[0].id).toBe('current-1');

    // Switch to version view
    const versionNodes = [makeNode('v-1')];
    useBpmFlowStore
      .getState()
      .loadVersionData('version-pid-1', versionNodes, []);
    expect(useBpmFlowStore.getState().viewingVersionId).toBe('version-pid-1');
    expect(useBpmFlowStore.getState().nodes[0].id).toBe('v-1');

    // Back to current
    useBpmFlowStore.getState().backToCurrent();
    expect(useBpmFlowStore.getState().viewingVersionId).toBeNull();
    expect(useBpmFlowStore.getState().nodes[0].id).toBe('current-1');
  });
});

describe('useBpmFlowStore — importFromJSON + reset', () => {
  it('importFromJSON populates SDK + processDefinition when id/name present', () => {
    useBpmFlowStore.getState().importFromJSON({
      id: 'pd-import',
      name: 'imported',
      key: 'k',
      nodes: [makeNode('n1')],
      edges: [makeEdge('e1', 'n1', 'n1')],
    });
    const s = useBpmFlowStore.getState();
    expect(s.nodes).toHaveLength(1);
    expect(s.processDefinition?.name).toBe('imported');
    expect(s.isDirty).toBe(true);
  });

  it('reset clears both sidecar BPMN state and SDK store', () => {
    useBpmFlowStore.getState().setProcessDefinition({
      id: 'pd',
      name: 'p',
      key: 'p',
      nodes: [makeNode('n1')],
      edges: [],
    } as any);
    useBpmFlowStore.getState().setSaving(true);
    useBpmFlowStore.getState().reset();
    const s = useBpmFlowStore.getState();
    expect(s.processDefinition).toBeNull();
    expect(s.isSaving).toBe(false);
    expect(s.nodes).toHaveLength(0);
  });
});

describe('useBpmFlowStore — store-like shims', () => {
  it('.getState() returns merged snapshot with both BPMN-only + SDK keys', () => {
    useBpmFlowStore.getState().setSaving(true);
    useBpmFlowStore.getState().addNode(makeNode('n1'));
    const snap = useBpmFlowStore.getState();
    // BPMN-only key
    expect(snap.isSaving).toBe(true);
    // SDK key
    expect(snap.nodes).toHaveLength(1);
  });

  it('.setState() routes partial per-key to BPMN vs SDK store', () => {
    useBpmFlowStore.setState({
      isSaving: true, // BPMN-only
      isDirty: true, // SDK
    });
    expect(useBpmFlowStore.getState().isSaving).toBe(true);
    expect(useFlowStore.getState().isDirty).toBe(true);
  });

  it('.subscribe() fires on either store mutation', async () => {
    const listener = vi.fn();
    const unsub = useBpmFlowStore.subscribe(listener);
    // SDK mutation
    useBpmFlowStore.getState().addNode(makeNode('n1'));
    await useBpmFlowStore.__flush();
    // BPMN mutation
    useBpmFlowStore.getState().setSaving(true);
    await useBpmFlowStore.__flush();
    expect(listener.mock.calls.length).toBeGreaterThanOrEqual(2);
    unsub();
  });
});
