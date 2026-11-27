import type { PropertySchema } from '~/shared/designer';
// The v3 page-schema contract lives in the framework now; import locally for
// the definitions below and re-export for existing plugin-internal callers.
import type {
  LocalizedText,
  PageSchemaV3Kind,
  PageSchemaV3,
  LayoutConfigV3,
  DslBlockV3,
} from '~/framework/meta/schemas/page-schema-v3';

export type { LocalizedText, PageSchemaV3Kind, PageSchemaV3, LayoutConfigV3, DslBlockV3 };


export interface ModelFieldDefinition {
  modelCode: string;
  code: string;
  label: LocalizedText;
  type?: string;
  component?: string;
  dictCode?: string;
  required?: boolean;
  refTarget?: ModelFieldRefTarget;
  /** Virtual / computed field backed by an expression or external source. */
  virtual?: boolean;
  /** Optional backend-provided semantic grouping hint. */
  semanticType?: string;
  /**
   * Raw backend field `extension` bag (renderComponent, currencySymbol, precision,
   * levels, allowClear, ...). Threaded to the designer preview so the WYSIWYG field
   * renderer can reproduce the exact live control instead of a generic input.
   */
  extensionProps?: Record<string, unknown>;
}

export interface ModelFieldRefTarget {
  modelCode?: string;
  valueField?: string;
  displayField?: string;
}

export type ModelFieldsByModel = Record<string, ModelFieldDefinition[]>;

export type DesignerMode = 'edit' | 'layout';
export type WorkbenchMode = DesignerMode | 'preview';

export interface DesignerSelection {
  blockId: string | null;
}

export interface BlockPathItem {
  id: string;
  block: DslBlockV3;
  index: number;
  parentId?: string;
}

export interface FindBlockResult {
  block: DslBlockV3;
  path: BlockPathItem[];
}

export interface InspectorGroupV3 {
  key: string;
  label: LocalizedText;
  fields: PropertySchema[];
}

export interface InspectorTabV3 {
  key: string;
  label: LocalizedText;
  groups: InspectorGroupV3[];
}

export interface InspectorSchemaV3 {
  tabs: InspectorTabV3[];
}

export type LayoutCapabilityV3 = 'none' | 'span' | 'grid-item' | 'dashboard-widget';

export interface BlockDefinitionV3 {
  blockType: string;
  label: LocalizedText;
  icon: string;
  category: string;
  allowedChildren?: string[];
  allowedRegions?: string[];
  inspector?: InspectorSchemaV3;
  layoutCapability?: LayoutCapabilityV3;
}

export interface LegacyPageSchemaV2 {
  schemaVersion?: number;
  kind: 'list' | 'detail' | 'form' | string;
  id: string;
  pageKey?: string;
  modelCode?: string;
  title?: LocalizedText;
  layout?: Record<string, unknown>;
  blocks?: LegacyDslBlockV2[];
  extension?: Record<string, unknown>;
}

export type LegacyFieldRefV2 = string | Record<string, unknown>;
export type LegacyColumnRefV2 = string | Record<string, unknown>;

export interface LegacyDslBlockV2 {
  id?: string;
  blockType: string;
  region?: string;
  title?: LocalizedText;
  fields?: LegacyFieldRefV2[];
  /** Column refs on `table` blocks; `form-section` / `detail-section` carry a numeric column count. */
  columns?: number | LegacyColumnRefV2[];
  buttons?: Array<string | Record<string, unknown>>;
  actions?: Array<string | Record<string, unknown>>;
  span?: number;
  props?: Record<string, unknown>;
  dataSource?: unknown;
  selection?: Record<string, unknown>;
  blocks?: LegacyDslBlockV2[];
  [key: string]: unknown;
}

export interface LegacyDashboardResource {
  id?: string | number;
  pid?: string;
  code?: string;
  title?: LocalizedText;
  layoutConfig?: {
    columns?: number;
    rowHeight?: number;
    gap?: number;
    [key: string]: unknown;
  };
  widgets?: LegacyDashboardWidget[];
  extension?: Record<string, unknown>;
}

export interface LegacyDashboardWidget {
  id?: string | number;
  type: string;
  x?: number;
  y?: number;
  w?: number;
  h?: number;
  config?: Record<string, unknown>;
  props?: Record<string, unknown>;
  [key: string]: unknown;
}
