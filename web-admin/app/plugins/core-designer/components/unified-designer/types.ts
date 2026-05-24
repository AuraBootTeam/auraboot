import type { PropertySchema } from '~/shared/designer';

export type LocalizedText = string | Record<string, string>;

export type PageSchemaV3Kind = 'list' | 'detail' | 'form' | 'dashboard' | 'composite';

export interface LayoutConfigV3 {
  type?: string;
  cols?: number;
  columns?: number;
  rowHeight?: number;
  gap?: number;
  span?: number;
  x?: number;
  y?: number;
  w?: number;
  h?: number;
  width?: number;
  [key: string]: unknown;
}

export interface PageSchemaV3 {
  schemaVersion: 3;
  kind: PageSchemaV3Kind;
  id: string;
  pageKey?: string;
  modelCode?: string;
  title?: LocalizedText;
  layout?: LayoutConfigV3;
  blocks: DslBlockV3[];
  extension?: Record<string, unknown>;
}

export interface DslBlockV3 {
  id: string;
  blockType: string;
  region?: string;
  title?: LocalizedText;
  field?: string;
  widgetType?: string;
  actionType?: string;
  dataSource?: Record<string, unknown>;
  layout?: LayoutConfigV3;
  props?: Record<string, unknown>;
  blocks?: DslBlockV3[];
  extension?: Record<string, unknown>;
}

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
  columns?: LegacyColumnRefV2[];
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
