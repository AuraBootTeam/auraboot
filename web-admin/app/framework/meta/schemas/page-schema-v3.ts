/**
 * PageSchema DSL (v3) — framework-level contract.
 *
 * Physically owned by the framework so framework code never imports plugin
 * internals for the page-schema contract (2026-11-14 boundary cleanup).
 * The core-designer plugin re-exports these for its internal callers.
 */

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
