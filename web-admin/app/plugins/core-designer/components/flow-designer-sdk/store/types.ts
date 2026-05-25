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

/**
 * Structured edge condition (aligns with the unified graph-grammar spec).
 * Replaces the former bare-string condition. A bare string maps to
 * `{ type: 'expression', content }`.
 */
export interface ConditionExpression {
  type: 'expression' | 'script';
  content: string;
  language?: 'mvel' | 'juel' | 'spel';
  /** Reference to a BPM rule-engine rule code (optional). */
  ruleCode?: string;
}

export interface FlowEdge {
  id: string;
  source: string;
  target: string;
  sourceHandle?: string;
  targetHandle?: string;
  /** Edge rendering type — maps to an EdgeRegistry definition / @xyflow edge type. */
  type?: string;
  data?: {
    label?: string;
    /** Gateway/branch condition (exclusive/inclusive outgoing flows). */
    condition?: ConditionExpression;
    /** Marks this edge as the gateway's default flow. */
    isDefault?: boolean;
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
