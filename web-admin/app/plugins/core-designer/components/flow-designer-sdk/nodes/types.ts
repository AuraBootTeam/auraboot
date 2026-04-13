// web-admin/app/flow-designer-sdk/nodes/types.ts
import type { I18nText } from '~/components/field-adapter';
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
}
