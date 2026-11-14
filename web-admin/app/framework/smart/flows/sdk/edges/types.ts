// web-admin/app/flow-designer-sdk/edges/types.ts
import type { I18nText } from '~/ui/field-adapter';
import type { PropertySchema } from '../nodes/types';
import type { FlowEdge } from '../store/types';

/**
 * Props passed to a bespoke edge editor (G1 injection point). Lets domains
 * (e.g. BPM) plug a rich condition editor without the SDK core knowing about it.
 */
export interface EdgePropertyEditorProps {
  edgeId: string;
  data: NonNullable<FlowEdge['data']>;
  /** Merge a partial patch into the edge's data. */
  onChange: (patch: Partial<NonNullable<FlowEdge['data']>>) => void;
  readOnly?: boolean;
}

/**
 * Edge type definition. Mirrors FlowNodeDefinition for edges: a custom @xyflow
 * edge component (e.g. a conditional edge with a label badge) plus an optional
 * bespoke editor. Omit both to use the SDK's default edge + built-in condition
 * editor.
 */
export interface FlowEdgeDefinition {
  type: string;
  label?: I18nText;
  /** Custom @xyflow edge renderer for this type. */
  component?: React.ComponentType<any>;
  /** Generic field schema for the edge (used by the fallback editor). */
  configSchema?: PropertySchema[];
  /** Bespoke edge editor (e.g. BPM ConditionExpressionEditor). */
  editor?: React.ComponentType<EdgePropertyEditorProps>;
}
