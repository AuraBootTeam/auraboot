/**
 * BPMN Designer state management
 *
 * Validation messages use i18n keys (e.g. "bpmn.validate.start_event_required")
 * that the UI layer translates via t(). Messages containing a "{label}" placeholder
 * are resolved by calling t(key, { label }) at render time.
 */

import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { subscribeWithSelector } from 'zustand/middleware';
import {
  BPMNNodeType,
  type BPMNNode,
  type BPMNEdge,
  type BPMNEdgeData,
  type BPMNProcessDefinition,
  type ValidationResult,
} from '~/bpmn-designer/types';
import { DEFAULT_NODE_CONFIGS } from '~/bpmn-designer/constants';
import {
  deployProcessDefinition,
  getProcessInstanceStatus,
  type ProcessInstanceNodeStatus,
} from '~/bpmn-designer/services/bpmnService';

const BPMN_MAX_HISTORY = 50;

interface BPMNSnapshot {
  nodes: BPMNNode[];
  edges: BPMNEdge[];
}

interface BPMNStore {
  // Process definition
  processDefinition: BPMNProcessDefinition | null;

  // Nodes and edges
  nodes: BPMNNode[];
  edges: BPMNEdge[];

  // Selection state
  selectedNodeId: string | null;
  selectedEdgeId: string | null;

  // UI state
  isPreviewMode: boolean;
  isDirty: boolean;
  isSaving: boolean;
  isDeploying: boolean;

  // Undo/Redo history
  history: BPMNSnapshot[];
  historyIndex: number;

  // Monitor mode
  viewMode: 'design' | 'monitor';
  instanceStatus: ProcessInstanceNodeStatus | null;
  monitorInstanceId: string;

  // Version viewing (panel state managed by shared useVersioning hook)
  viewingVersionId: string | null; // null = editing current version

  // Internal: saved state when viewing an old version
  _savedCurrentState?: {
    nodes: BPMNNode[];
    edges: BPMNEdge[];
    processDefinition: BPMNProcessDefinition;
    isDirty: boolean;
  };

  // Validation result
  validationResult: ValidationResult | null;

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

  // Version viewing actions (panel state managed by shared useVersioning hook)
  loadVersionData: (versionPid: string, nodes: BPMNNode[], edges: BPMNEdge[]) => void;
  backToCurrent: () => void;

  // Deploy action
  deployProcess: () => Promise<void>;

  // Monitor mode actions
  setViewMode: (mode: 'design' | 'monitor') => void;
  setMonitorInstanceId: (instanceId: string) => void;
  fetchInstanceStatus: (instanceId: string) => Promise<void>;
  clearInstanceStatus: () => void;

  // Undo/Redo
  undo: () => void;
  redo: () => void;
  canUndo: () => boolean;
  canRedo: () => boolean;

  // Import/Export
  importFromJSON: (json: any) => void;

  // Utility methods
  getNodeById: (nodeId: string) => BPMNNode | undefined;
  getEdgeById: (edgeId: string) => BPMNEdge | undefined;
  getConnectedEdges: (nodeId: string) => BPMNEdge[];
}

const initialState = {
  processDefinition: null,
  nodes: [],
  edges: [],
  selectedNodeId: null,
  selectedEdgeId: null,
  isPreviewMode: false,
  isDirty: false,
  isSaving: false,
  isDeploying: false,
  history: [] as BPMNSnapshot[],
  historyIndex: -1,
  viewMode: 'design' as const,
  instanceStatus: null as ProcessInstanceNodeStatus | null,
  monitorInstanceId: '',
  viewingVersionId: null as string | null,
  validationResult: null,
};

export const useBPMNStore = create<BPMNStore>()(
  subscribeWithSelector(
    immer((set, get) => {
      function pushSnapshot(draft: {
        nodes: BPMNNode[];
        edges: BPMNEdge[];
        history: BPMNSnapshot[];
        historyIndex: number;
      }) {
        const snapshot: BPMNSnapshot = structuredClone({ nodes: draft.nodes, edges: draft.edges });
        draft.history = draft.history.slice(0, draft.historyIndex + 1);
        draft.history.push(snapshot);
        if (draft.history.length > BPMN_MAX_HISTORY) {
          draft.history.shift();
        }
        draft.historyIndex = draft.history.length - 1;
      }

      return {
        ...initialState,

        setProcessDefinition: (definition) => {
          set((state) => {
            state.processDefinition = definition;
            state.nodes = definition.nodes || [];
            state.edges = definition.edges || [];
            state.isDirty = false;
            const snapshot: BPMNSnapshot = structuredClone({
              nodes: state.nodes,
              edges: state.edges,
            });
            state.history = [snapshot];
            state.historyIndex = 0;
          });
        },

        setNodes: (nodes) => {
          set((state) => {
            state.nodes = nodes;
            state.isDirty = true;
            // Note: no pushSnapshot here — React Flow calls setNodes per-frame during drag.
            // Snapshots are only pushed by explicit user actions (addNode, updateNode, deleteNode, etc.)
          });
        },

        setEdges: (edges) => {
          set((state) => {
            // Keep edge label in sync with data.label
            state.edges = edges.map((edge: any) => ({
              ...edge,
              label: edge.data?.label || edge.label || '',
            }));
            state.isDirty = true;
            // Note: no pushSnapshot here — React Flow calls setEdges per-frame during drag.
            // Snapshots are only pushed by explicit user actions (addEdge, updateEdge, deleteEdge, etc.)
          });
        },

        addNode: (node) => {
          set((state) => {
            state.nodes.push(node);
            state.isDirty = true;
            pushSnapshot(state);
          });
        },

        updateNode: (nodeId, data) => {
          set((state) => {
            const node = state.nodes.find((n) => n.id === nodeId);
            if (node) {
              node.data = { ...node.data, ...data };
              state.isDirty = true;
              pushSnapshot(state);
            }
          });
        },

        deleteNode: (nodeId) => {
          set((state) => {
            state.nodes = state.nodes.filter((n) => n.id !== nodeId);
            // Delete connected edges
            state.edges = state.edges.filter((e) => e.source !== nodeId && e.target !== nodeId);
            if (state.selectedNodeId === nodeId) {
              state.selectedNodeId = null;
            }
            state.isDirty = true;
            pushSnapshot(state);
          });
        },

        addEdge: (edge) => {
          set((state) => {
            state.edges.push(edge);
            state.isDirty = true;
            pushSnapshot(state);
          });
        },

        updateEdge: (edgeId, data) => {
          set((state) => {
            const edge = state.edges.find((e) => e.id === edgeId);
            if (!edge) {
              return;
            }
            const nextData = { ...(edge.data ?? {}), ...data };
            edge.data = nextData;
            if (data.label !== undefined) {
              edge.label = data.label;
            } else if (nextData.label !== undefined) {
              edge.label = String(nextData.label);
            }
            state.isDirty = true;
            pushSnapshot(state);
          });
        },

        deleteEdge: (edgeId) => {
          set((state) => {
            state.edges = state.edges.filter((e) => e.id !== edgeId);
            if (state.selectedEdgeId === edgeId) {
              state.selectedEdgeId = null;
            }
            state.isDirty = true;
            pushSnapshot(state);
          });
        },

        setSelectedNode: (nodeId) => {
          set((state) => {
            state.selectedNodeId = nodeId;
            state.selectedEdgeId = null;
          });
        },

        setSelectedEdge: (edgeId) => {
          set((state) => {
            state.selectedEdgeId = edgeId;
            state.selectedNodeId = null;
          });
        },

        setPreviewMode: (isPreview) => {
          set((state) => {
            state.isPreviewMode = isPreview;
          });
        },

        setDirty: (isDirty) => {
          set((state) => {
            state.isDirty = isDirty;
          });
        },

        setSaving: (isSaving) => {
          set((state) => {
            state.isSaving = isSaving;
          });
        },

        validate: () => {
          const state = get();
          const errors: ValidationResult['errors'] = [];

          // Check for start event nodes
          const startNodes = state.nodes.filter((n) => n.data.type === BPMNNodeType.START_EVENT);
          if (startNodes.length === 0) {
            errors.push({
              message: 'bpmn.validate.start_event_required',
              type: 'error',
            });
          } else if (startNodes.length > 1) {
            errors.push({
              message: 'bpmn.validate.start_event_single',
              type: 'error',
            });
          }

          // Check for end event nodes
          const endNodes = state.nodes.filter((n) => n.data.type === BPMNNodeType.END_EVENT);
          if (endNodes.length === 0) {
            errors.push({
              message: 'bpmn.validate.end_event_required',
              type: 'error',
            });
          }

          // Check connectivity for each node
          state.nodes.forEach((node) => {
            const outgoingEdges = state.edges.filter((e) => e.source === node.id);
            const incomingEdges = state.edges.filter((e) => e.target === node.id);

            // Start event must not have incoming edges
            if (node.data.type === BPMNNodeType.START_EVENT && incomingEdges.length > 0) {
              errors.push({
                nodeId: node.id,
                message: 'bpmn.validate.start_no_incoming',
                messageParams: { label: node.data.label },
                type: 'error',
              });
            }

            // Start event must have outgoing edges
            if (node.data.type === BPMNNodeType.START_EVENT && outgoingEdges.length === 0) {
              errors.push({
                nodeId: node.id,
                message: 'bpmn.validate.start_no_outgoing',
                messageParams: { label: node.data.label },
                type: 'error',
              });
            }

            // End event must not have outgoing edges
            if (node.data.type === BPMNNodeType.END_EVENT && outgoingEdges.length > 0) {
              errors.push({
                nodeId: node.id,
                message: 'bpmn.validate.end_no_outgoing',
                messageParams: { label: node.data.label },
                type: 'error',
              });
            }

            // End event must have incoming edges
            if (node.data.type === BPMNNodeType.END_EVENT && incomingEdges.length === 0) {
              errors.push({
                nodeId: node.id,
                message: 'bpmn.validate.end_no_incoming',
                messageParams: { label: node.data.label },
                type: 'error',
              });
            }

            // Task node checks
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

            // Gateway checks
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
            }
          });

          const result: ValidationResult = {
            valid: errors.filter((e) => e.type === 'error').length === 0,
            errors,
          };

          set((state) => {
            state.validationResult = result;
          });

          return result;
        },

        loadVersionData: (versionPid, nodes, edges) => {
          set((draft) => {
            // Store current editing state before switching if not already viewing a version
            if (!draft.viewingVersionId && draft.processDefinition) {
              draft._savedCurrentState = {
                nodes: draft.nodes,
                edges: draft.edges,
                processDefinition: draft.processDefinition,
                isDirty: draft.isDirty,
              };
            }

            draft.viewingVersionId = versionPid;
            draft.nodes = nodes;
            draft.edges = edges;
            draft.selectedNodeId = null;
            draft.selectedEdgeId = null;
          });
        },

        backToCurrent: () => {
          set((state) => {
            if (state._savedCurrentState) {
              state.nodes = state._savedCurrentState.nodes;
              state.edges = state._savedCurrentState.edges;
              state.processDefinition = state._savedCurrentState.processDefinition;
              state.isDirty = state._savedCurrentState.isDirty;
              state._savedCurrentState = undefined;
            }
            state.viewingVersionId = null;
            state.selectedNodeId = null;
            state.selectedEdgeId = null;
          });
        },

        deployProcess: async () => {
          const state = get();
          if (!state.processDefinition?.id) {
            throw new Error('Process must be saved before deploying');
          }
          if (state.isDirty) {
            throw new Error('Please save changes before deploying');
          }

          set((draft) => {
            draft.isDeploying = true;
          });

          try {
            const result = await deployProcessDefinition(state.processDefinition.id);
            if (result.data) {
              set((draft) => {
                if (draft.processDefinition) {
                  draft.processDefinition.status = result.data!.status;
                }
                draft.isDeploying = false;
              });
            } else {
              throw new Error('Deploy failed: no data returned');
            }
          } catch (error) {
            set((draft) => {
              draft.isDeploying = false;
            });
            throw error;
          }
        },

        setViewMode: (mode) => {
          set((state) => {
            state.viewMode = mode;
            if (mode === 'design') {
              state.instanceStatus = null;
              state.monitorInstanceId = '';
            }
          });
        },

        setMonitorInstanceId: (instanceId) => {
          set((state) => {
            state.monitorInstanceId = instanceId;
          });
        },

        fetchInstanceStatus: async (instanceId) => {
          try {
            const result = await getProcessInstanceStatus(instanceId);
            if (result.data) {
              set((state) => {
                state.instanceStatus = result.data;
              });
            }
          } catch (error) {
            console.error('Failed to fetch instance status:', error);
          }
        },

        clearInstanceStatus: () => {
          set((state) => {
            state.instanceStatus = null;
            state.monitorInstanceId = '';
          });
        },

        reset: () => {
          set(initialState);
        },

        undo: () => {
          set((state) => {
            if (state.historyIndex <= 0) return;
            state.historyIndex -= 1;
            const snapshot = state.history[state.historyIndex];
            state.nodes = structuredClone(snapshot.nodes);
            state.edges = structuredClone(snapshot.edges);
            state.isDirty = true;
          });
        },

        redo: () => {
          set((state) => {
            if (state.historyIndex >= state.history.length - 1) return;
            state.historyIndex += 1;
            const snapshot = state.history[state.historyIndex];
            state.nodes = structuredClone(snapshot.nodes);
            state.edges = structuredClone(snapshot.edges);
            state.isDirty = true;
          });
        },

        canUndo: () => {
          return get().historyIndex > 0;
        },

        canRedo: () => {
          const state = get();
          return state.historyIndex < state.history.length - 1;
        },

        importFromJSON: (json) => {
          set((state) => {
            // Clean and normalize node data
            const cleanNodes: BPMNNode[] = (json.nodes || []).map((node: any) => {
              const {
                measured: _measured,
                selected: _selected,
                dragging: _dragging,
                ...cleanNode
              } = node;
              const nodeType = (cleanNode.type ||
                cleanNode.data?.type ||
                BPMNNodeType.USER_TASK) as BPMNNodeType;
              const defaultConfig = DEFAULT_NODE_CONFIGS[nodeType] || {};

              return {
                id: cleanNode.id,
                type: nodeType,
                position: cleanNode.position || { x: 0, y: 0 },
                data: {
                  type: nodeType,
                  label: cleanNode.data?.label || 'Unnamed',
                  config: cleanNode.data?.config || defaultConfig,
                },
              };
            });

            // Clean and normalize edge data
            const cleanEdges: BPMNEdge[] = (json.edges || []).map((edge: any) => {
              // Remove React Flow internal fields
              const { selected: _selected, ...cleanEdge } = edge;

              return {
                id: cleanEdge.id,
                source: cleanEdge.source,
                target: cleanEdge.target,
                type: cleanEdge.type || 'smoothstep',
                animated: cleanEdge.animated || false,
                label: cleanEdge.label || cleanEdge.data?.label || '',
                labelStyle: cleanEdge.labelStyle || {
                  fill: '#374151',
                  fontSize: 12,
                  fontWeight: 500,
                },
                labelBgStyle: cleanEdge.labelBgStyle || {
                  fill: '#ffffff',
                  fillOpacity: 0.9,
                },
                labelBgPadding: cleanEdge.labelBgPadding || [8, 4],
                labelBgBorderRadius: cleanEdge.labelBgBorderRadius || 4,
                style: cleanEdge.style || { stroke: '#94a3b8', strokeWidth: 2 },
                data: {
                  label: cleanEdge.label || cleanEdge.data?.label || '',
                  condition: cleanEdge.data?.condition,
                },
              } as BPMNEdge;
            });

            state.nodes = cleanNodes;
            state.edges = cleanEdges;

            // Import process definition info (if present)
            if (json.id || json.name || json.key) {
              state.processDefinition = {
                id: json.id,
                name: json.name || 'Imported Process',
                key: json.key || 'imported_' + Date.now(),
                version: json.version || 1,
                status: json.status || 'draft',
                nodes: cleanNodes as BPMNNode[],
                edges: cleanEdges as BPMNEdge[],
                createdAt: json.createdAt,
                updatedAt: json.updatedAt,
              };
            }

            // Clear selection
            state.selectedNodeId = null;
            state.selectedEdgeId = null;
            state.isDirty = true;
          });
        },

        getNodeById: (nodeId) => {
          return get().nodes.find((n) => n.id === nodeId);
        },

        getEdgeById: (edgeId) => {
          return get().edges.find((e) => e.id === edgeId);
        },

        getConnectedEdges: (nodeId) => {
          const state = get();
          return state.edges.filter((e) => e.source === nodeId || e.target === nodeId);
        },
      };
    }),
  ),
);
