// web-admin/app/flow-designer-sdk/store/useFlowStore.ts
import { create } from 'zustand';
import type { FlowNode, FlowEdge, ValidationResult, FlowData } from './types';

const MAX_HISTORY = 50;

interface FlowSnapshot {
  nodes: FlowNode[];
  edges: FlowEdge[];
}

interface FlowStoreState {
  // Data
  nodes: FlowNode[];
  edges: FlowEdge[];
  selectedNodeId: string | null;

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

const generateId = () => `node_${Date.now()}_${Math.random().toString(36).slice(2, 9)}`;

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
    isDirty: false,
    validationResult: null,
    registryVersion: 0,
    bumpRegistryVersion: () => set((s) => ({ registryVersion: s.registryVersion + 1 })),
    history: [],
    historyIndex: -1,

    addNode: (node) => {
      const id = generateId();
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
          isDirty: true,
          ...snapshot,
        };
      });
    },

    selectNode: (id) => {
      set({ selectedNodeId: id });
    },

    addEdge: (edge) => {
      const id = `edge_${Date.now()}`;
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
        return { edges: newEdges, isDirty: true, ...snapshot };
      });
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
        isDirty: false,
        validationResult: null,
        history: [],
        historyIndex: -1,
      });
    },

    setDirty: (dirty) => {
      set({ isDirty: dirty });
    },
  };
});
