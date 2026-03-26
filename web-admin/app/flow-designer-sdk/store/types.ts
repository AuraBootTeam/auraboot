// web-admin/app/flow-designer-sdk/store/types.ts

export interface FlowNode {
  id: string;
  type: string;
  position: { x: number; y: number };
  data: {
    label: string;
    config: Record<string, unknown>;
    /** Optional node sub-type for rendering (e.g. 'trigger', 'action') */
    type?: string;
  };
}

export interface FlowEdge {
  id: string;
  source: string;
  target: string;
  sourceHandle?: string;
  targetHandle?: string;
  /** Edge rendering type (e.g. 'smoothstep') */
  type?: string;
  data?: {
    label?: string;
    condition?: string;
  };
}

export interface ValidationError {
  nodeId?: string;
  edgeId?: string;
  fieldKey?: string;
  message: string;
  type: 'error' | 'warning';
}

export interface ValidationResult {
  valid: boolean;
  errors: ValidationError[];
}

export interface FlowData {
  nodes: FlowNode[];
  edges: FlowEdge[];
}
