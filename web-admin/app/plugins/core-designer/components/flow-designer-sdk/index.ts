// web-admin/app/flow-designer-sdk/index.ts
/**
 * Flow Designer SDK
 *
 * A reusable flow/workflow designer SDK based on @xyflow/react.
 * Can be used for Automation, BPMN, and other flow-based editors.
 */

// Core components
export { FlowDesigner } from './core/FlowDesigner';
export type { FlowDesignerProps, FlowDesignerConfig } from './core/FlowDesigner';

export { FlowCanvas } from './core/FlowCanvas';
export { FlowPalette } from './core/FlowPalette';
export { FlowPropertyPanel } from './core/FlowPropertyPanel';
export { FlowToolbar } from './core/FlowToolbar';
export { DefaultFlowNode } from './core/DefaultFlowNode';

// Store
export { useFlowStore } from './store/useFlowStore';

// Node registry
export { NodeRegistry, nodeRegistry } from './nodes/NodeRegistry';

// Edge registry (G1)
export { EdgeRegistry, edgeRegistry } from './edges/EdgeRegistry';

// Adapters
export { useFlowFieldAdapter } from './adapters/FlowFieldAdapter';

// Validation (G4): pure validator + imperative hook shared by every save path.
export { validateFlow } from './validation/validateFlow';
export type { ValidateFlowOptions } from './validation/validateFlow';
export { useFlowValidation } from './validation/useFlowValidation';

// A1: unified GraphDocument grammar — JSON Schema, structural validator,
// and divergence diff utility
// (spec: docs/backlog/2026-05-23-unified-graph-grammar-spec.md).
export {
  graphDocumentSchema,
  GRAPH_DOCUMENT_SCHEMA_VERSION,
} from './validation/graphDocumentSchema';
export { validateGraphDocument } from './validation/validateGraphDocument';
export type {
  GraphDocumentValidationError,
  GraphDocumentValidationResult,
} from './validation/validateGraphDocument';
export { diffGraphDocuments } from './validation/diffGraphDocuments';
export type {
  GrammarDivergence,
  DiffReport,
} from './validation/diffGraphDocuments';

// Hooks (G7 + G8) — neighbor traversal and monitor-mode status surface.
export { useNodeNeighbors } from './hooks/useNodeNeighbors';
export type { NodeNeighbors } from './hooks/useNodeNeighbors';
export { useNodeMonitorStatus } from './hooks/useNodeMonitorStatus';
export type {
  FlowMonitorStatus,
  NodeMonitorStatus,
  FlowMonitorData,
} from './store/monitorTypes';

// Types
export type {
  FlowNode,
  FlowEdge,
  FlowData,
  ConditionExpression,
  ValidationResult,
  ValidationError,
} from './store/types';

export type {
  FlowNodeDefinition,
  NodePropertyEditorProps,
  PropertySchema,
  PropertyType,
  NodeValidation,
} from './nodes/types';

export type { FlowEdgeDefinition, EdgePropertyEditorProps } from './edges/types';

// Runtime status overlay (G5)
export {
  NodeRuntimeStatusProvider,
  useNodeRuntimeStatus,
  useNodeRuntimeStatusMap,
} from './runtime/NodeRuntimeStatusContext';
export type {
  NodeRuntimeStatus,
  NodeStatusMap,
} from './runtime/NodeRuntimeStatusContext';
