/**
 * DSL Profile Types — defines the contract for a page rendering style
 */

import type { ComponentType } from 'react';

/**
 * Props passed to block renderers
 */
export interface BlockRendererProps {
  block: any; // BlockConfig — using any to avoid circular import
  runtime: any; // SchemaRuntime
  areaId?: string;
}

/**
 * Props passed to page content components
 */
export interface PageContentProps {
  schema: any; // UnifiedSchema
  tableName: string;
  recordId?: string;
  token?: string | null;
  listExtensions?: {
    onDataChange?: (records: Record<string, any>[]) => void;
    disableRowClick?: boolean;
    disableRowSelection?: boolean;
    hideBuiltInImport?: boolean;
    hideBuiltInExport?: boolean;
    hideBuiltInPrint?: boolean;
    hideSavedViews?: boolean;
    reloadEventName?: string;
  };
  // --- L1 SDK extensions ---
  initialValues?: Record<string, any>;
  fieldPermissions?: Record<string, 'editable' | 'readonly' | 'hidden'>;
  onSubmitOverride?: (data: Record<string, any>) => Promise<void>;
}

/**
 * Component manifest entry (for designer toolbox)
 */
export interface ComponentEntry {
  name: string;
  category: string;
  icon?: string;
  defaultProps?: Record<string, unknown>;
}

/**
 * Layout configuration preset
 */
export interface LayoutConfig {
  type?: 'grid' | 'stack';
  cols?: number;
  colGap?: number;
  rowGap?: number;
  gap?: number;
}

/**
 * DSL Profile — defines a complete page rendering style
 *
 * Each profile declares:
 * - What block types it supports
 * - How to render each block type
 * - What page kinds it supports
 * - How to render each page kind
 * - Optional skeleton components for loading states
 */
export interface RenderProfile {
  /** Profile identifier, e.g. "admin" | "storefront" | "portal" */
  name: string;

  /** Block types this profile supports */
  blockTypes: string[];

  /** blockType → React renderer component mapping */
  blockRenderers: Map<string, ComponentType<BlockRendererProps>>;

  /** Page kinds this profile supports */
  kinds: string[];

  /** kind → page content component mapping */
  pageRenderers: Map<string, ComponentType<PageContentProps>>;

  /** Smart component manifest (for designer) */
  componentManifest?: ComponentEntry[];

  /** Preset layout templates */
  layoutPresets?: Record<string, LayoutConfig>;

  /** Skeleton components: kind → Skeleton */
  skeletons?: Map<string, ComponentType>;
}
