// web-admin/app/flow-designer-sdk/nodes/types.ts
import type { I18nText } from '~/ui/field-adapter';
import type {
  PropertyType as SharedPropertyType,
  PropertySchema as SharedPropertySchema,
} from '~/shared/designer';

export type PropertyType = SharedPropertyType;

/**
 * Flow SDK uses I18nText for labels to support internationalization.
 */
export type PropertySchema = SharedPropertySchema<I18nText>;

export interface NodeValidation {
  minInputs?: number;
  maxInputs?: number;
  minOutputs?: number;
  maxOutputs?: number;
  custom?: (node: any, context: any) => string[];
}

/**
 * Props passed to a node's bespoke property editor (G2 injection point).
 * Lets domains (e.g. BPM) plug rich editors — assignee picker, condition
 * editor, form binding — without the SDK core knowing about them.
 */
export interface NodePropertyEditorProps {
  /** The selected node's id. */
  nodeId: string;
  /** The node's current config payload (i.e. node.data.config). */
  config: Record<string, unknown>;
  /** Merge a partial patch into the node's config. */
  onChange: (patch: Record<string, unknown>) => void;
  readOnly?: boolean;
}

export interface FlowNodeDefinition {
  type: string;
  label: I18nText;
  icon: string | React.ReactNode;
  category: string;
  description?: I18nText;
  configSchema?: PropertySchema[];
  defaultConfig?: Record<string, unknown>;
  component?: React.ComponentType<any>;
  validation?: NodeValidation;
  /**
   * Optional bespoke property editor for this node type (G2). When present the
   * property panel renders this instead of the generic `configSchema` fields.
   * Omit it to keep the declarative configSchema path (automation default).
   */
  propertyEditor?: React.ComponentType<NodePropertyEditorProps>;
}
