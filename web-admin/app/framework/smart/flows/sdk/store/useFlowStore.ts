// web-admin/app/flow-designer-sdk/store/useFlowStore.ts
import { create } from 'zustand';
import type { FlowNode, FlowEdge, ValidationResult, FlowData } from './types';
import type { FlowMonitorData } from './monitorTypes';

const MAX_HISTORY = 50;

interface FlowSnapshot {
  nodes: FlowNode[];
  edges: FlowEdge[];
}

/**
 * B2c: Sub-flow drilldown frame.
 *
 * When a host (BPMN sub-process, CallActivity) navigates into a child
 * graph, it pushes the OUTER (nodes, edges, selectedNodeId,
 * selectedEdgeId) onto subFlowStack so the inner graph can use the
 * primary `nodes`/`edges` slots. `popSubFlow` restores the outer frame.
 *
 * Frames also carry an opaque `parentNodeId` so callers can render
 * breadcrumbs / annotate provenance.
 */
export interface SubFlowFrame {
  parentNodeId: string;
  /** Free-form label for breadcrumbs (e.g. sub-process name). */
  label?: string;
  outerNodes: FlowNode[];
  outerEdges: FlowEdge[];
  outerSelectedNodeId: string | null;
  outerSelectedEdgeId: string | null;
}

interface FlowStoreState {
  // Data
  nodes: FlowNode[];
  edges: FlowEdge[];
  selectedNodeId: string | null;
  selectedEdgeId: string | null;

  // Status
  isDirty: boolean;
  validationResult: ValidationResult | null;

  // Registry version — incremented after nodeRegistry.registerAll() so
  // components that read from the registry (FlowPalette, FlowCanvas) re-render.
  registryVersion: number;
  bumpRegistryVersion: () => void;

  // Undo/Redo history
  history: FlowSnapshot[];
  historyIndex: number;

  // Monitor mode (G8) — when monitorMode=true, monitorData maps nodeId →
  // NodeMonitorStatus so node renderers and editors can adapt their UI.
  monitorMode: boolean;
  monitorData: FlowMonitorData;
  setMonitorMode: (enabled: boolean) => void;
  setMonitorData: (data: FlowMonitorData) => void;
  /**
   * B2c: derive the set of nodeIds currently in `running` status from
   * monitorData. Used by BPMN UserTask monitor panel + canvas overlays
   * to highlight active wait-states. Returns empty Set when monitorMode
   * is off OR monitorData is empty.
   */
  getCurrentMonitorNodeIds: () => Set<string>;

  // B2c: Sub-flow drilldown stack (BPMN sub-process / CallActivity).
  // When non-empty, the visible (nodes, edges) belong to the innermost
  // child frame; outer frames are stored top-to-bottom in subFlowStack.
  subFlowStack: SubFlowFrame[];
  pushSubFlow: (
    parentNodeId: string,
    innerNodes: FlowNode[],
    innerEdges: FlowEdge[],
    label?: string,
  ) => void;
  popSubFlow: () => void;
  resetSubFlowStack: () => void;
  /** Convenience selector — breadcrumb labels (oldest → newest). */
  getSubFlowPath: () => Array<{ parentNodeId: string; label?: string }>;

  // Node operations
  addNode: (node: Omit<FlowNode, 'id'>) => string;
  updateNode: (id: string, updates: Partial<FlowNode>) => void;
  updateNodeConfig: (id: string, config: Record<string, unknown>) => void;
  deleteNode: (id: string) => void;
  selectNode: (id: string | null) => void;

  // Edge operations
  addEdge: (edge: Omit<FlowEdge, 'id'>) => string;
  updateEdge: (id: string, updates: Partial<FlowEdge>) => void;
  deleteEdge: (id: string) => void;
  selectEdge: (id: string | null) => void;

  // Validation
  setValidationResult: (result: ValidationResult | null) => void;

  // Undo/Redo
  undo: () => void;
  redo: () => void;
  canUndo: () => boolean;
  canRedo: () => boolean;

  // Import/Export
  importData: (data: FlowData) => void;
  exportData: () => FlowData;
  reset: () => void;
  setDirty: (dirty: boolean) => void;
}

const generateNodeId = () => `node_${Date.now()}_${Math.random().toString(36).slice(2, 9)}`;
const generateEdgeId = () => `edge_${Date.now()}_${Math.random().toString(36).slice(2, 9)}`;

function cloneSnapshot(nodes: FlowNode[], edges: FlowEdge[]): FlowSnapshot {
  return JSON.parse(JSON.stringify({ nodes, edges }));
}

export const useFlowStore = create<FlowStoreState>((set, get) => {
  function pushSnapshot(nodes: FlowNode[], edges: FlowEdge[]) {
    const { history, historyIndex } = get();
    const truncated = history.slice(0, historyIndex + 1);
    truncated.push(cloneSnapshot(nodes, edges));
    if (truncated.length > MAX_HISTORY) {
      truncated.shift();
    }
    return { history: truncated, historyIndex: truncated.length - 1 };
  }

  return {
    nodes: [],
    edges: [],
    selectedNodeId: null,
    selectedEdgeId: null,
    isDirty: false,
    validationResult: null,
    registryVersion: 0,
    bumpRegistryVersion: () => set((s) => ({ registryVersion: s.registryVersion + 1 })),
    history: [],
    historyIndex: -1,
    monitorMode: false,
    monitorData: {},
    setMonitorMode: (enabled) => set({ monitorMode: enabled }),
    setMonitorData: (data) => set({ monitorData: data }),
    getCurrentMonitorNodeIds: () => {
      const { monitorMode, monitorData } = get();
      if (!monitorMode) return new Set<string>();
      const ids = new Set<string>();
      for (const [nodeId, st] of Object.entries(monitorData)) {
        if (st && st.status === 'running') ids.add(nodeId);
      }
      return ids;
    },

    subFlowStack: [],
    pushSubFlow: (parentNodeId, innerNodes, innerEdges, label) => {
      set((state) => {
        const frame: SubFlowFrame = {
          parentNodeId,
          label,
          outerNodes: state.nodes,
          outerEdges: state.edges,
          outerSelectedNodeId: state.selectedNodeId,
          outerSelectedEdgeId: state.selectedEdgeId,
        };
        // Drilldown intentionally does NOT push a history snapshot — undo
        // inside the inner graph should not pop the drilldown. Callers
        // that want to track navigation separately can subscribe to
        // subFlowStack.length.
        return {
          subFlowStack: [...state.subFlowStack, frame],
          nodes: innerNodes,
          edges: innerEdges,
          selectedNodeId: null,
          selectedEdgeId: null,
          // history is per-frame: reset for the inner frame so undo
          // stays scoped. We seed a single snapshot of the inner state
          // so the first edit pushes onto a non-empty history.
          history: [cloneSnapshot(innerNodes, innerEdges)],
          historyIndex: 0,
        };
      });
    },
    popSubFlow: () => {
      set((state) => {
        if (state.subFlowStack.length === 0) return state;
        const next = [...state.subFlowStack];
        const frame = next.pop()!;
        return {
          subFlowStack: next,
          nodes: frame.outerNodes,
          edges: frame.outerEdges,
          selectedNodeId: frame.outerSelectedNodeId,
          selectedEdgeId: frame.outerSelectedEdgeId,
          // Restore a fresh history for the outer frame. Outer-frame undo
          // history is intentionally NOT preserved across drilldowns — the
          // outer graph is treated as committed at drilldown time. Callers
          // needing cross-frame undo should layer their own history above
          // the SDK store.
          history: [cloneSnapshot(frame.outerNodes, frame.outerEdges)],
          historyIndex: 0,
        };
      });
    },
    resetSubFlowStack: () => {
      set({ subFlowStack: [] });
    },
    getSubFlowPath: () => {
      return get().subFlowStack.map((f) => ({
        parentNodeId: f.parentNodeId,
        label: f.label,
      }));
    },

    addNode: (node) => {
      const id = generateNodeId();
      set((state) => {
        const newNodes = [...state.nodes, { ...node, id }];
        const snapshot = pushSnapshot(newNodes, state.edges);
        return { nodes: newNodes, isDirty: true, ...snapshot };
      });
      return id;
    },

    updateNode: (id, updates) => {
      set((state) => {
        const newNodes = state.nodes.map((n) => (n.id === id ? { ...n, ...updates } : n));
        const snapshot = pushSnapshot(newNodes, state.edges);
        return { nodes: newNodes, isDirty: true, ...snapshot };
      });
    },

    updateNodeConfig: (id, config) => {
      set((state) => {
        const newNodes = state.nodes.map((n) =>
          n.id === id ? { ...n, data: { ...n.data, config: { ...n.data.config, ...config } } } : n,
        );
        const snapshot = pushSnapshot(newNodes, state.edges);
        return { nodes: newNodes, isDirty: true, ...snapshot };
      });
    },

    deleteNode: (id) => {
      set((state) => {
        const newNodes = state.nodes.filter((n) => n.id !== id);
        const newEdges = state.edges.filter((e) => e.source !== id && e.target !== id);
        const snapshot = pushSnapshot(newNodes, newEdges);
        return {
          nodes: newNodes,
          edges: newEdges,
          selectedNodeId: state.selectedNodeId === id ? null : state.selectedNodeId,
          selectedEdgeId:
            state.selectedEdgeId && !newEdges.some((e) => e.id === state.selectedEdgeId)
              ? null
              : state.selectedEdgeId,
          isDirty: true,
          ...snapshot,
        };
      });
    },

    selectNode: (id) => {
      set({ selectedNodeId: id, selectedEdgeId: null });
    },

    addEdge: (edge) => {
      const id = generateEdgeId();
      set((state) => {
        const newEdges = [...state.edges, { ...edge, id }];
        const snapshot = pushSnapshot(state.nodes, newEdges);
        return { edges: newEdges, isDirty: true, ...snapshot };
      });
      return id;
    },

    updateEdge: (id, updates) => {
      set((state) => {
        const newEdges = state.edges.map((e) => (e.id === id ? { ...e, ...updates } : e));
        const snapshot = pushSnapshot(state.nodes, newEdges);
        return { edges: newEdges, isDirty: true, ...snapshot };
      });
    },

    deleteEdge: (id) => {
      set((state) => {
        const newEdges = state.edges.filter((e) => e.id !== id);
        const snapshot = pushSnapshot(state.nodes, newEdges);
        return {
          edges: newEdges,
          selectedEdgeId: state.selectedEdgeId === id ? null : state.selectedEdgeId,
          isDirty: true,
          ...snapshot,
        };
      });
    },

    selectEdge: (id) => {
      set({ selectedEdgeId: id, selectedNodeId: null });
    },

    setValidationResult: (result) => {
      set({ validationResult: result });
    },

    undo: () => {
      set((state) => {
        if (state.historyIndex <= 0) return state;
        const newIndex = state.historyIndex - 1;
        const snapshot = state.history[newIndex];
        return {
          nodes: JSON.parse(JSON.stringify(snapshot.nodes)),
          edges: JSON.parse(JSON.stringify(snapshot.edges)),
          historyIndex: newIndex,
          isDirty: true,
        };
      });
    },

    redo: () => {
      set((state) => {
        if (state.historyIndex >= state.history.length - 1) return state;
        const newIndex = state.historyIndex + 1;
        const snapshot = state.history[newIndex];
        return {
          nodes: JSON.parse(JSON.stringify(snapshot.nodes)),
          edges: JSON.parse(JSON.stringify(snapshot.edges)),
          historyIndex: newIndex,
          isDirty: true,
        };
      });
    },

    canUndo: () => {
      return get().historyIndex > 0;
    },

    canRedo: () => {
      const state = get();
      return state.historyIndex < state.history.length - 1;
    },

    importData: (data) => {
      const nodes = data?.nodes || [];
      const edges = data?.edges || [];
      const initialSnapshot = cloneSnapshot(nodes, edges);
      set({
        nodes,
        edges,
        selectedNodeId: null,
        selectedEdgeId: null,
        isDirty: false,
        validationResult: null,
        history: [initialSnapshot],
        historyIndex: 0,
      });
    },

    exportData: () => {
      const { nodes, edges } = get();
      return { nodes, edges };
    },

    reset: () => {
      set({
        nodes: [],
        edges: [],
        selectedNodeId: null,
        selectedEdgeId: null,
        isDirty: false,
        validationResult: null,
        history: [],
        historyIndex: -1,
        monitorMode: false,
        monitorData: {},
        subFlowStack: [],
      });
    },

    setDirty: (dirty) => {
      set({ isDirty: dirty });
    },
  };
});
