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

// Adapters
export { useFlowFieldAdapter } from './adapters/FlowFieldAdapter';

// Types
export type {
  FlowNode,
  FlowEdge,
  FlowData,
  ValidationResult,
  ValidationError,
} from './store/types';

export type {
  FlowNodeDefinition,
  PropertySchema,
  PropertyType,
  NodeValidation,
} from './nodes/types';
