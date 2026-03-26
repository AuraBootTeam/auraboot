/**
 * Schema types for the designer core
 *
 * Defines the core types used by the schema management system
 */

import type { TabContainerConfig } from './layout-hierarchy';
import type { LinkageRule } from '~/studio/workbench/panels/linkage/types';

// Schema compatibility alias
export type FormSchema = PageSchema;

export interface Position {
  row: number;
  column: number;
  x?: number;
  y?: number;
}

export interface Component {
  id: string;
  type: string;
  name?: string;
  props: Record<string, any>;
  children?: Component[];
  position?: Position;
  size?: { width: number; height: number; span: number };
  span?: number;
}

export type ComponentSchema = Component;

/**
 * Block interface - represents a container with layout permissions
 */
export interface Block {
  id: string;
  type: string;
  name?: string;
  props: Record<string, any>;
  position?: Position;
  children?: Block[];
  components?: Component[];
  layout?: {
    type: 'flex' | 'grid' | 'absolute' | 'flow';
    direction?: 'row' | 'column' | 'row-reverse' | 'column-reverse';
    justify?:
      | 'flex-start'
      | 'flex-end'
      | 'center'
      | 'space-between'
      | 'space-around'
      | 'space-evenly';
    align?: 'flex-start' | 'flex-end' | 'center' | 'stretch' | 'baseline';
    wrap?: 'nowrap' | 'wrap' | 'wrap-reverse';
    gap?: number;
    padding?: number;
  };
  styles?: Record<string, any>;
  visible?: boolean;
  locked?: boolean;
}

/**
 * Page Schema interface - represents the complete page structure
 */
export interface PageSchema {
  id: string;
  kind: 'home' | 'list' | 'form';
  name?: string;
  title: string;
  description?: string;
  version: string;
  components: Block[];
  layout: LayoutConfig;
  hierarchy?: TabContainerConfig; // Four-level layout (takes priority over layout when present)
  theme?: ThemeConfig;
  actions?: any[]; // To support compatibility check in PageSchemaVersionManager
  styles?: any[]; // To support extractDependencies in PageSchemaVersionManager
  metadata: {
    createdAt: string;
    updatedAt: string;
    createdBy: string;
    tags?: string[];
  };
  meta?: {
    title?: string;
    description?: string;
    tags?: string[];
    viewModelCode?: string;
    [key: string]: any;
  };
  linkageRules?: LinkageRule[];
}

/**
 * Layout configuration interface
 */
export interface LayoutBreakpoint {
  columns: number;
  gap: number;
  minWidth?: number;
}

export interface LayoutConfig {
  type: 'vertical' | 'horizontal' | 'grid';
  columns: number;
  spacing: number;
  padding: number;
  gap?: number;
  rows?: number | 'auto';
  mode?: 'auto' | 'fixed' | 'responsive';
  responsive?: boolean;
  breakpoints?: Record<string, LayoutBreakpoint>;
}

/**
 * Theme configuration interface
 */
export interface ThemeConfig {
  primaryColor: string;
  backgroundColor: string;
  textColor: string;
  borderRadius: number;
  colors?: {
    primary: string;
    secondary: string;
    success: string;
    warning: string;
    error: string;
    background: string;
    surface: string;
    text: string;
    textSecondary: string;
    border: string;
  };
  fonts?: {
    primary: string;
    mono: string;
  };
  spacing?: {
    xs: number;
    sm: number;
    md: number;
    lg: number;
    xl: number;
    xxl: number;
  };
  radiusScale?: {
    none: number;
    sm: number;
    md: number;
    lg: number;
    xl: number;
    full: number;
  };
}

/**
 * Schema metadata interface
 */
export interface SchemaMetadata {
  createdAt: string;
  updatedAt: string;
  createdBy: string;
  tags: string[];
}
