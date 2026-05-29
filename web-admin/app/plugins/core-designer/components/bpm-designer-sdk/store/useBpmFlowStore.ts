/**
 * B2c Phase 2 adapter — `useBpmFlowStore`.
 *
 * Bridges the legacy BPMN designer `useBPMNStore` shape onto the
 * flow-designer-sdk `useFlowStore`. Goal: consumers of useBPMNStore can
 * migrate to useBpmFlowStore with an import-path rename (selector shape is
 * preserved); BPMN-only logic (processDefinition, validate, deploy, version
 * preview) lives in a sidecar store; nodes/edges/selection/history/dirty/
 * monitor/sub-flow stack are delegated to the SDK store.
 *
 * Design choice locked in `docs/backlog/2026-05-28-B2c-followup-adapter-migration-report.md`
 * §0 — hybrid "Option A surface + Option B internals" (hook composition with
 * store-like static shims `.getState/.setState/.subscribe`).
 *
 * Double-write contract: this adapter coexists with the legacy `useBPMNStore`
 * during B2c-followup → B2d. No consumer is required to migrate as part of
 * landing this file.
 */

import { useMemo, useSyncExternalStore } from 'react';
import { create } from 'zustand';
import { useFlowStore } from '~/plugins/core-designer/components/flow-designer-sdk/store/useFlowStore';
import type {
  FlowNode,
  FlowEdge,
  ValidationResult as SdkValidationResult,
} from '~/plugins/core-designer/components/flow-designer-sdk/store/types';
import {
  BPMNNodeType,
  type BPMNNode,
  type BPMNEdge,
  type BPMNEdgeData,
  type BPMNProcessDefinition,
  type ValidationResult,
} from '~/plugins/core-designer/components/bpmn-designer/types';
import {
  deployProcessDefinition,
  getProcessInstanceStatus,
  normalizeDesignerJsonPayload,
  type ProcessInstanceNodeStatus,
} from '~/plugins/core-designer/components/bpmn-designer/services/bpmnService';

// ─── Type bridging helpers ──────────────────────────────────────────────────
//
// BPMNNode = Node<BPMNNodeData> from @xyflow/react. FlowNode is a structurally
// narrower SDK-internal type (data: { label; config; type? }). BPMN data is
// always a superset (BPMNNodeData extends Record<string, unknown> with
// type:BPMNNodeType + label + config). The cast is safe at runtime because
// xyflow never introspects `data` — it is application-owned.
//
// We localize the cast here so the rest of the codebase does not need to know.

function toFlowNode(node: BPMNNode): FlowNode {
  return node as unknown as FlowNode;
}

function toFlowNodes(nodes: BPMNNode[]): FlowNode[] {
  return nodes as unknown as FlowNode[];
}

function toFlowEdge(edge: BPMNEdge): FlowEdge {
  return edge as unknown as FlowEdge;
}

function toFlowEdges(edges: BPMNEdge[]): FlowEdge[] {
  return edges as unknown as FlowEdge[];
}

function fromFlowNodes(nodes: FlowNode[]): BPMNNode[] {
  return nodes as unknown as BPMNNode[];
}

function fromFlowEdges(edges: FlowEdge[]): BPMNEdge[] {
  return edges as unknown as BPMNEdge[];
}

// ─── BPMN-only sidecar store ────────────────────────────────────────────────
//
// Holds the 70% of useBPMNStore that is BPMN-specific (validate rules,
// deploy lifecycle, version preview, monitor instance metadata). Plain
// zustand — no immer needed because the state is shallow and the BPMN
// rule mutations are also shallow.

interface BpmOnlyState {
  processDefinition: BPMNProcessDefinition | null;
  isPreviewMode: boolean;
  isSaving: boolean;
  isDeploying: boolean;
  viewMode: 'design' | 'monitor';
  instanceStatus: ProcessInstanceNodeStatus | null;
  monitorInstanceId: string;
  viewingVersionId: string | null;
  _savedCurrentState?: {
    nodes: BPMNNode[];
    edges: BPMNEdge[];
    processDefinition: BPMNProcessDefinition;
    isDirty: boolean;
  };
  validationResult: ValidationResult | null;
}

const initialBpmOnlyState: BpmOnlyState = {
  processDefinition: null,
  isPreviewMode: false,
  isSaving: false,
  isDeploying: false,
  viewMode: 'design',
  instanceStatus: null,
  monitorInstanceId: '',
  viewingVersionId: null,
  validationResult: null,
};

const useBpmOnlyStore = create<BpmOnlyState>(() => ({ ...initialBpmOnlyState }));

// ─── BPMN validation rules (ported verbatim from useBPMNStore.validate) ─────

function runBpmnValidation(nodes: BPMNNode[], edges: BPMNEdge[]): ValidationResult {
  const errors: ValidationResult['errors'] = [];

  const startNodes = nodes.filter((n) => n.data.type === BPMNNodeType.START_EVENT);
  if (startNodes.length === 0) {
    errors.push({ message: 'bpmn.validate.start_event_required', type: 'error' });
  } else if (startNodes.length > 1) {
    errors.push({ message: 'bpmn.validate.start_event_single', type: 'error' });
  }

  const endNodes = nodes.filter((n) => n.data.type === BPMNNodeType.END_EVENT);
  if (endNodes.length === 0) {
    errors.push({ message: 'bpmn.validate.end_event_required', type: 'error' });
  }

  nodes.forEach((node) => {
    const outgoingEdges = edges.filter((e) => e.source === node.id);
    const incomingEdges = edges.filter((e) => e.target === node.id);

    if (node.data.type === BPMNNodeType.START_EVENT && incomingEdges.length > 0) {
      errors.push({
        nodeId: node.id,
        message: 'bpmn.validate.start_no_incoming',
        messageParams: { label: node.data.label },
        type: 'error',
      });
    }
    if (node.data.type === BPMNNodeType.START_EVENT && outgoingEdges.length === 0) {
      errors.push({
        nodeId: node.id,
        message: 'bpmn.validate.start_no_outgoing',
        messageParams: { label: node.data.label },
        type: 'error',
      });
    }
    if (node.data.type === BPMNNodeType.END_EVENT && outgoingEdges.length > 0) {
      errors.push({
        nodeId: node.id,
        message: 'bpmn.validate.end_no_outgoing',
        messageParams: { label: node.data.label },
        type: 'error',
      });
    }
    if (node.data.type === BPMNNodeType.END_EVENT && incomingEdges.length === 0) {
      errors.push({
        nodeId: node.id,
        message: 'bpmn.validate.end_no_incoming',
        messageParams: { label: node.data.label },
        type: 'error',
      });
    }

    if (
      [
        BPMNNodeType.USER_TASK,
        BPMNNodeType.SERVICE_TASK,
        BPMNNodeType.RECEIVE_TASK,
      ].includes(node.data.type)
    ) {
      if (incomingEdges.length === 0) {
        errors.push({
          nodeId: node.id,
          message: 'bpmn.validate.task_no_incoming',
          messageParams: { label: node.data.label },
          type: 'warning',
        });
      }
      if (outgoingEdges.length === 0) {
        errors.push({
          nodeId: node.id,
          message: 'bpmn.validate.task_no_outgoing',
          messageParams: { label: node.data.label },
          type: 'warning',
        });
      }
    }

    if (node.data.type === BPMNNodeType.EXCLUSIVE_GATEWAY) {
      if (incomingEdges.length === 0) {
        errors.push({
          nodeId: node.id,
          message: 'bpmn.validate.gateway_no_incoming',
          messageParams: { label: node.data.label },
          type: 'error',
        });
      }
      if (outgoingEdges.length < 2) {
        errors.push({
          nodeId: node.id,
          message: 'bpmn.validate.exclusive_gateway_min_outgoing',
          messageParams: { label: node.data.label },
          type: 'error',
        });
      }
      const defaultEdges = outgoingEdges.filter((e) => e.data?.isDefault === true);
      if (defaultEdges.length > 1) {
        errors.push({
          nodeId: node.id,
          message: 'bpmn.validate.exclusive_gateway_multiple_defaults',
          messageParams: { label: node.data.label, count: String(defaultEdges.length) },
          type: 'error',
        });
      }
      outgoingEdges.forEach((edge) => {
        const content = edge.data?.condition?.content?.trim();
        if (!content) {
          errors.push({
            nodeId: node.id,
            edgeId: edge.id,
            message: 'bpmn.validate.exclusive_gateway_edge_missing_condition',
            messageParams: {
              label: node.data.label,
              edgeLabel: edge.data?.label || edge.id,
            },
            type: 'error',
          });
        }
      });
    }
  });

  return {
    valid: errors.filter((e) => e.type === 'error').length === 0,
    errors,
  };
}

// ─── Public merged shape ────────────────────────────────────────────────────

export interface BpmFlowState {
  // BPMN-only state
  processDefinition: BPMNProcessDefinition | null;
  isPreviewMode: boolean;
  isSaving: boolean;
  isDeploying: boolean;
  viewMode: 'design' | 'monitor';
  instanceStatus: ProcessInstanceNodeStatus | null;
  monitorInstanceId: string;
  viewingVersionId: string | null;
  _savedCurrentState?: BpmOnlyState['_savedCurrentState'];
  validationResult: ValidationResult | null;

  // Delegated to SDK useFlowStore
  nodes: BPMNNode[];
  edges: BPMNEdge[];
  selectedNodeId: string | null;
  selectedEdgeId: string | null;
  isDirty: boolean;
  history: Array<{ nodes: BPMNNode[]; edges: BPMNEdge[] }>;
  historyIndex: number;

  // Actions
  setProcessDefinition: (definition: BPMNProcessDefinition) => void;
  setNodes: (nodes: BPMNNode[]) => void;
  setEdges: (edges: BPMNEdge[]) => void;
  addNode: (node: BPMNNode) => void;
  updateNode: (nodeId: string, data: Partial<BPMNNode['data']>) => void;
  deleteNode: (nodeId: string) => void;
  addEdge: (edge: BPMNEdge) => void;
  updateEdge: (edgeId: string, data: Partial<BPMNEdgeData>) => void;
  deleteEdge: (edgeId: string) => void;
  setSelectedNode: (nodeId: string | null) => void;
  setSelectedEdge: (edgeId: string | null) => void;
  setPreviewMode: (isPreview: boolean) => void;
  setDirty: (isDirty: boolean) => void;
  setSaving: (isSaving: boolean) => void;
  validate: () => ValidationResult;
  reset: () => void;
  loadVersionData: (versionPid: string, nodes: BPMNNode[], edges: BPMNEdge[]) => void;
  backToCurrent: () => void;
  deployProcess: () => Promise<void>;
  setViewMode: (mode: 'design' | 'monitor') => void;
  setMonitorInstanceId: (instanceId: string) => void;
  fetchInstanceStatus: (instanceId: string) => Promise<void>;
  clearInstanceStatus: () => void;
  undo: () => void;
  redo: () => void;
  canUndo: () => boolean;
  canRedo: () => boolean;
  importFromJSON: (json: any) => void;
  getNodeById: (nodeId: string) => BPMNNode | undefined;
  getEdgeById: (edgeId: string) => BPMNEdge | undefined;
  getConnectedEdges: (nodeId: string) => BPMNEdge[];
}

// ─── Actions (operate on both stores transactionally) ───────────────────────
//
// Actions are stable references (don't recreate per render). They are owned
// here, not on either underlying store, so subscribers do not re-render when
// no relevant state changes.

const actions = {
  setProcessDefinition(definition: BPMNProcessDefinition): void {
    // Sanitize React Flow internal fields out (BPMN parity behavior).
    const cleanNodes = sanitizeNodesForClone(definition.nodes || []);
    const cleanEdges = sanitizeEdgesForClone(definition.edges || []);
    useBpmOnlyStore.setState({
      processDefinition: { ...definition, nodes: cleanNodes, edges: cleanEdges },
    });
    // Reset SDK state with the new graph (single-snapshot history seed).
    const sdk = useFlowStore.getState();
    sdk.importData({ nodes: toFlowNodes(cleanNodes), edges: toFlowEdges(cleanEdges) });
    sdk.setDirty(false);
  },

  setNodes(nodes: BPMNNode[]): void {
    // React Flow per-frame callback: replace nodes without pushing snapshot.
    // SDK has no public "replace-without-history" — write directly via setState.
    useFlowStore.setState({ nodes: toFlowNodes(nodes), isDirty: true });
  },

  setEdges(edges: BPMNEdge[]): void {
    const normalized = edges.map((edge: any) => ({
      ...edge,
      label: edge.data?.label || edge.label || '',
    }));
    useFlowStore.setState({ edges: toFlowEdges(normalized as BPMNEdge[]), isDirty: true });
  },

  addNode(node: BPMNNode): void {
    // SDK addNode auto-generates id from Omit<FlowNode, 'id'>. BPMN callers
    // pass a pre-assigned id (they construct full Node objects). Inject the
    // node directly + push history snapshot via the SDK's `updateNode` path
    // on a placeholder. Easier: write directly through setState and push
    // through pushSnapshot-equivalent (call importData? — no, that resets
    // history). Cleanest is to call SDK addNode then patch the id back.
    //
    // BPMN parity matters for test seed paths (BPMNDesigner.tsx __bpmDesigner
    // adapter uses preassigned ids). We preserve the caller's id.
    const sdk = useFlowStore.getState();
    // Direct insert + manual history snapshot (mirrors SDK addNode internals).
    const newNodes = [...sdk.nodes, toFlowNode(node)];
    const truncated = sdk.history.slice(0, sdk.historyIndex + 1);
    truncated.push(cloneFlowSnapshot(newNodes, sdk.edges));
    if (truncated.length > 50) truncated.shift();
    useFlowStore.setState({
      nodes: newNodes,
      isDirty: true,
      history: truncated,
      historyIndex: truncated.length - 1,
    });
  },

  updateNode(nodeId: string, data: Partial<BPMNNode['data']>): void {
    const sdk = useFlowStore.getState();
    const target = sdk.nodes.find((n) => n.id === nodeId);
    if (!target) return;
    const merged = { ...target, data: { ...target.data, ...data } } as FlowNode;
    const newNodes = sdk.nodes.map((n) => (n.id === nodeId ? merged : n));
    const truncated = sdk.history.slice(0, sdk.historyIndex + 1);
    truncated.push(cloneFlowSnapshot(newNodes, sdk.edges));
    if (truncated.length > 50) truncated.shift();
    useFlowStore.setState({
      nodes: newNodes,
      isDirty: true,
      history: truncated,
      historyIndex: truncated.length - 1,
    });
  },

  deleteNode(nodeId: string): void {
    const sdk = useFlowStore.getState();
    sdk.deleteNode(nodeId);
  },

  addEdge(edge: BPMNEdge): void {
    const sdk = useFlowStore.getState();
    const newEdges = [...sdk.edges, toFlowEdge(edge)];
    const truncated = sdk.history.slice(0, sdk.historyIndex + 1);
    truncated.push(cloneFlowSnapshot(sdk.nodes, newEdges));
    if (truncated.length > 50) truncated.shift();
    useFlowStore.setState({
      edges: newEdges,
      isDirty: true,
      history: truncated,
      historyIndex: truncated.length - 1,
    });
  },

  updateEdge(edgeId: string, data: Partial<BPMNEdgeData>): void {
    const sdk = useFlowStore.getState();
    const target = sdk.edges.find((e) => e.id === edgeId);
    if (!target) return;
    const nextData = { ...(target.data ?? {}), ...data };
    const merged: any = { ...target, data: nextData };
    if (data.label !== undefined) merged.label = data.label;
    else if (nextData.label !== undefined) merged.label = String(nextData.label);
    const newEdges = sdk.edges.map((e) => (e.id === edgeId ? merged : e));
    const truncated = sdk.history.slice(0, sdk.historyIndex + 1);
    truncated.push(cloneFlowSnapshot(sdk.nodes, newEdges));
    if (truncated.length > 50) truncated.shift();
    useFlowStore.setState({
      edges: newEdges,
      isDirty: true,
      history: truncated,
      historyIndex: truncated.length - 1,
    });
  },

  deleteEdge(edgeId: string): void {
    const sdk = useFlowStore.getState();
    sdk.deleteEdge(edgeId);
  },

  setSelectedNode(nodeId: string | null): void {
    useFlowStore.setState({ selectedNodeId: nodeId, selectedEdgeId: null });
  },

  setSelectedEdge(edgeId: string | null): void {
    useFlowStore.setState({ selectedEdgeId: edgeId, selectedNodeId: null });
  },

  setPreviewMode(isPreview: boolean): void {
    useBpmOnlyStore.setState({ isPreviewMode: isPreview });
  },

  setDirty(isDirty: boolean): void {
    useFlowStore.getState().setDirty(isDirty);
  },

  setSaving(isSaving: boolean): void {
    useBpmOnlyStore.setState({ isSaving });
  },

  validate(): ValidationResult {
    const sdk = useFlowStore.getState();
    const result = runBpmnValidation(fromFlowNodes(sdk.nodes), fromFlowEdges(sdk.edges));
    useBpmOnlyStore.setState({ validationResult: result });
    // Mirror onto SDK validation surface for consumers using useFlowValidation.
    useFlowStore.getState().setValidationResult(result as SdkValidationResult);
    return result;
  },

  reset(): void {
    useBpmOnlyStore.setState({ ...initialBpmOnlyState });
    useFlowStore.getState().reset();
  },

  loadVersionData(versionPid: string, nodes: BPMNNode[], edges: BPMNEdge[]): void {
    const bpm = useBpmOnlyStore.getState();
    const sdk = useFlowStore.getState();
    if (!bpm.viewingVersionId && bpm.processDefinition) {
      useBpmOnlyStore.setState({
        _savedCurrentState: {
          nodes: fromFlowNodes(sdk.nodes),
          edges: fromFlowEdges(sdk.edges),
          processDefinition: bpm.processDefinition,
          isDirty: sdk.isDirty,
        },
        viewingVersionId: versionPid,
      });
    } else {
      useBpmOnlyStore.setState({ viewingVersionId: versionPid });
    }
    useFlowStore.setState({
      nodes: toFlowNodes(nodes),
      edges: toFlowEdges(edges),
      selectedNodeId: null,
      selectedEdgeId: null,
    });
  },

  backToCurrent(): void {
    const bpm = useBpmOnlyStore.getState();
    if (bpm._savedCurrentState) {
      useFlowStore.setState({
        nodes: toFlowNodes(bpm._savedCurrentState.nodes),
        edges: toFlowEdges(bpm._savedCurrentState.edges),
        selectedNodeId: null,
        selectedEdgeId: null,
        isDirty: bpm._savedCurrentState.isDirty,
      });
      useBpmOnlyStore.setState({
        processDefinition: bpm._savedCurrentState.processDefinition,
        _savedCurrentState: undefined,
        viewingVersionId: null,
      });
    } else {
      useBpmOnlyStore.setState({ viewingVersionId: null });
      useFlowStore.setState({ selectedNodeId: null, selectedEdgeId: null });
    }
  },

  async deployProcess(): Promise<void> {
    const bpm = useBpmOnlyStore.getState();
    const sdk = useFlowStore.getState();
    if (!bpm.processDefinition?.id) {
      throw new Error('Process must be saved before deploying');
    }
    if (sdk.isDirty) {
      throw new Error('Please save changes before deploying');
    }
    useBpmOnlyStore.setState({ isDeploying: true });
    try {
      const result = await deployProcessDefinition(bpm.processDefinition.id);
      if (!result.data) {
        throw new Error('Deploy failed: no data returned');
      }
      const current = useBpmOnlyStore.getState().processDefinition;
      useBpmOnlyStore.setState({
        processDefinition: current ? { ...current, status: result.data.status } : current,
        isDeploying: false,
      });
    } catch (error) {
      useBpmOnlyStore.setState({ isDeploying: false });
      throw error;
    }
  },

  setViewMode(mode: 'design' | 'monitor'): void {
    useBpmOnlyStore.setState((s) => ({
      ...s,
      viewMode: mode,
      instanceStatus: mode === 'design' ? null : s.instanceStatus,
      monitorInstanceId: mode === 'design' ? '' : s.monitorInstanceId,
    }));
    // Bridge to SDK monitor surface.
    useFlowStore.getState().setMonitorMode(mode === 'monitor');
    if (mode === 'design') {
      useFlowStore.getState().setMonitorData({});
    }
  },

  setMonitorInstanceId(instanceId: string): void {
    useBpmOnlyStore.setState({ monitorInstanceId: instanceId });
  },

  async fetchInstanceStatus(instanceId: string): Promise<void> {
    try {
      const result = await getProcessInstanceStatus(instanceId);
      if (result.data) {
        useBpmOnlyStore.setState({ instanceStatus: result.data });
        // Project to SDK monitor data shape (BPMN currentNodes → running,
        // completedNodes → completed).
        const data = result.data;
        const projected: Record<string, { status: 'running' | 'completed' }> = {};
        const currents = (data as any).currentNodes as Array<{ nodeId: string }> | undefined;
        const completed = (data as any).completedNodes as Array<{ nodeId: string }> | undefined;
        currents?.forEach((n) => {
          projected[n.nodeId] = { status: 'running' };
        });
        completed?.forEach((n) => {
          if (!projected[n.nodeId]) projected[n.nodeId] = { status: 'completed' };
        });
        useFlowStore.getState().setMonitorData(projected);
      }
    } catch (error) {
      console.error('Failed to fetch instance status:', error);
    }
  },

  clearInstanceStatus(): void {
    useBpmOnlyStore.setState({ instanceStatus: null, monitorInstanceId: '' });
    useFlowStore.getState().setMonitorData({});
  },

  undo(): void {
    useFlowStore.getState().undo();
  },

  redo(): void {
    useFlowStore.getState().redo();
  },

  canUndo(): boolean {
    return useFlowStore.getState().canUndo();
  },

  canRedo(): boolean {
    return useFlowStore.getState().canRedo();
  },

  importFromJSON(json: any): void {
    const { nodes: cleanNodes, edges: cleanEdges, aura } = normalizeDesignerJsonPayload(json);
    useFlowStore.getState().importData({
      nodes: toFlowNodes(cleanNodes as BPMNNode[]),
      edges: toFlowEdges(cleanEdges as BPMNEdge[]),
    });
    useFlowStore.setState({ isDirty: true, selectedNodeId: null, selectedEdgeId: null });
    if (json.id || json.name || json.key) {
      useBpmOnlyStore.setState({
        processDefinition: {
          id: json.id,
          name: json.name || 'Imported Process',
          key: json.key || 'imported_' + Date.now(),
          version: json.version || 1,
          status: json.status || 'draft',
          nodes: cleanNodes as BPMNNode[],
          edges: cleanEdges as BPMNEdge[],
          aura,
          createdAt: json.createdAt,
          updatedAt: json.updatedAt,
        },
      });
    }
  },

  getNodeById(nodeId: string): BPMNNode | undefined {
    const found = useFlowStore.getState().nodes.find((n) => n.id === nodeId);
    return found ? (found as unknown as BPMNNode) : undefined;
  },

  getEdgeById(edgeId: string): BPMNEdge | undefined {
    const found = useFlowStore.getState().edges.find((e) => e.id === edgeId);
    return found ? (found as unknown as BPMNEdge) : undefined;
  },

  getConnectedEdges(nodeId: string): BPMNEdge[] {
    const edges = useFlowStore.getState().edges;
    return fromFlowEdges(edges.filter((e) => e.source === nodeId || e.target === nodeId));
  },
};

// ─── Snapshot helpers (used by addNode/addEdge/updateNode/updateEdge) ──────

function cloneFlowSnapshot(nodes: FlowNode[], edges: FlowEdge[]) {
  return JSON.parse(JSON.stringify({ nodes, edges }));
}

function sanitizeNodesForClone(nodes: readonly BPMNNode[]): BPMNNode[] {
  return nodes.map((node) => {
    const {
      measured: _measured,
      selected: _selected,
      dragging: _dragging,
      resizing: _resizing,
      handleBounds: _handleBounds,
      positionAbsolute: _positionAbsolute,
      computed: _computed,
      internals: _internals,
      ...rest
    } = node as BPMNNode & Record<string, unknown>;
    return rest as BPMNNode;
  });
}

function sanitizeEdgesForClone(edges: readonly BPMNEdge[]): BPMNEdge[] {
  return edges.map((edge) => {
    const {
      selected: _selected,
      interactionWidth: _interactionWidth,
      ...rest
    } = edge as BPMNEdge & Record<string, unknown>;
    return rest as BPMNEdge;
  });
}

// ─── Merged-snapshot builder ────────────────────────────────────────────────

function buildMergedSnapshot(): BpmFlowState {
  const bpm = useBpmOnlyStore.getState();
  const sdk = useFlowStore.getState();
  return {
    // BPMN-only
    processDefinition: bpm.processDefinition,
    isPreviewMode: bpm.isPreviewMode,
    isSaving: bpm.isSaving,
    isDeploying: bpm.isDeploying,
    viewMode: bpm.viewMode,
    instanceStatus: bpm.instanceStatus,
    monitorInstanceId: bpm.monitorInstanceId,
    viewingVersionId: bpm.viewingVersionId,
    _savedCurrentState: bpm._savedCurrentState,
    validationResult: bpm.validationResult,
    // Delegated
    nodes: fromFlowNodes(sdk.nodes),
    edges: fromFlowEdges(sdk.edges),
    selectedNodeId: sdk.selectedNodeId,
    selectedEdgeId: sdk.selectedEdgeId,
    isDirty: sdk.isDirty,
    history: sdk.history as unknown as BpmFlowState['history'],
    historyIndex: sdk.historyIndex,
    // Actions
    ...actions,
  };
}

// ─── Hook + store-like statics ──────────────────────────────────────────────

function useBpmFlowStoreHook<T>(selector?: (s: BpmFlowState) => T): T {
  // Subscribe to both underlying stores via useSyncExternalStore so React
  // re-renders on either mutation. The snapshot returned is the merged shape
  // — selectors receive the full BpmFlowState.
  const snapshot = useSyncExternalStore(
    subscribeBoth,
    getMergedSnapshotCached,
    getMergedSnapshotCached,
  );
  return useMemo(
    () => (selector ? selector(snapshot) : (snapshot as unknown as T)),
    [snapshot, selector],
  );
}

// Subscribe shim: fan out to both stores, dedupe listener invocation per tick
// using a microtask scheduler.
function subscribeBoth(listener: () => void): () => void {
  let scheduled = false;
  const wrapped = () => {
    if (scheduled) return;
    scheduled = true;
    queueMicrotask(() => {
      scheduled = false;
      // Invalidate the cached snapshot so the next read re-builds.
      cachedSnapshot = null;
      listener();
    });
  };
  const unsub1 = useFlowStore.subscribe(wrapped);
  const unsub2 = useBpmOnlyStore.subscribe(wrapped);
  return () => {
    unsub1();
    unsub2();
  };
}

// Snapshot cache so useSyncExternalStore receives a referentially-stable
// object between renders when neither store has changed. Otherwise React 18
// throws "getSnapshot should be cached".
let cachedSnapshot: BpmFlowState | null = null;
function getMergedSnapshotCached(): BpmFlowState {
  if (!cachedSnapshot) {
    cachedSnapshot = buildMergedSnapshot();
  }
  return cachedSnapshot;
}

// Static shims that mirror zustand store API (getState / setState / subscribe).

function getState(): BpmFlowState {
  // Always fresh — bypass cache; callers using .getState() generally expect
  // the latest value at call time.
  return buildMergedSnapshot();
}

type SetStatePartial =
  | Partial<BpmFlowState>
  | ((s: BpmFlowState) => Partial<BpmFlowState>);

function setState(partial: SetStatePartial): void {
  const next = typeof partial === 'function' ? partial(getState()) : partial;
  const bpmKeys: Array<keyof BpmOnlyState> = [
    'processDefinition',
    'isPreviewMode',
    'isSaving',
    'isDeploying',
    'viewMode',
    'instanceStatus',
    'monitorInstanceId',
    'viewingVersionId',
    '_savedCurrentState',
    'validationResult',
  ];
  const bpmPatch: Partial<BpmOnlyState> = {};
  const sdkPatch: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(next)) {
    if ((bpmKeys as string[]).includes(k)) {
      (bpmPatch as any)[k] = v;
    } else if (
      k === 'nodes' ||
      k === 'edges' ||
      k === 'selectedNodeId' ||
      k === 'selectedEdgeId' ||
      k === 'isDirty' ||
      k === 'history' ||
      k === 'historyIndex'
    ) {
      sdkPatch[k] = v;
    }
    // Action keys are ignored (cannot be overridden via setState).
  }
  if (Object.keys(bpmPatch).length > 0) {
    useBpmOnlyStore.setState(bpmPatch as Partial<BpmOnlyState>);
  }
  if (Object.keys(sdkPatch).length > 0) {
    useFlowStore.setState(sdkPatch as any);
  }
  // Invalidate cache so next read sees the new values.
  cachedSnapshot = null;
}

function subscribe(listener: (s: BpmFlowState, prev: BpmFlowState) => void): () => void {
  let prev = buildMergedSnapshot();
  let scheduled = false;
  const wrapped = () => {
    if (scheduled) return;
    scheduled = true;
    queueMicrotask(() => {
      scheduled = false;
      const next = buildMergedSnapshot();
      const prior = prev;
      prev = next;
      listener(next, prior);
    });
  };
  const unsub1 = useFlowStore.subscribe(wrapped);
  const unsub2 = useBpmOnlyStore.subscribe(wrapped);
  return () => {
    unsub1();
    unsub2();
  };
}

// Tiny test-only helper to flush queueMicrotask in unit tests. Not exported
// from index.ts (kept on the function for adapter-internal tests).
async function __flush(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
}

// ─── Final export — function + statics ──────────────────────────────────────

interface UseBpmFlowStore {
  // No-arg call returns the full merged state (legacy useBPMNStore() shape).
  // Declared FIRST so TS overload resolution picks this signature when the
  // caller destructures without a selector — B2c phase3 batch2 consumers
  // (BPMNDesigner / BPMNCanvas / BPMNPropertyPanel / BPMNToolbar) all rely
  // on this shape. With the selector-first overload, TS infers `unknown`
  // for the destructure and downstream property accesses fail TS2339.
  (): BpmFlowState;
  <T>(selector: (s: BpmFlowState) => T): T;
  getState: typeof getState;
  setState: typeof setState;
  subscribe: typeof subscribe;
  /** Adapter-internal: flush microtask queue for tests. */
  __flush: typeof __flush;
}

export const useBpmFlowStore = useBpmFlowStoreHook as UseBpmFlowStore;
useBpmFlowStore.getState = getState;
useBpmFlowStore.setState = setState;
useBpmFlowStore.subscribe = subscribe;
useBpmFlowStore.__flush = __flush;
