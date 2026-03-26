/**
 * Page Mode Types
 *
 * Type definitions for three-mode page design system.
 *
 * @since 3.2.0
 */

/**
 * Page mode type
 */
export type PageMode = 'floor' | 'form' | 'grid';

/**
 * Mode structure definition
 */
export interface ModeStructure {
  /** Structure hierarchy levels */
  levels: string[];
  /** Example: floor: ['tab', 'floor', 'block', 'field'] */
}

/**
 * Mode capabilities
 */
export interface ModeCapabilities {
  /** Supports tab navigation */
  supportsTabs: boolean;
  /** Supports collapsible sections */
  supportsCollapse: boolean;
  /** Uses grid layout */
  supportsGrid: boolean;
  /** Supports free positioning */
  supportsFreePosition: boolean;
  /** Supports multiple columns */
  supportsMultiColumn: boolean;
  /** Maximum columns supported */
  maxColumns?: number;
}

/**
 * Page mode configuration
 */
export interface PageModeConfig {
  /** Mode identifier */
  mode: PageMode;
  /** Display name */
  name: string;
  /** Icon (emoji or component) */
  icon: string;
  /** Description */
  description: string;
  /** Structure definition */
  structure: ModeStructure;
  /** Mode capabilities */
  capabilities: ModeCapabilities;
  /** Default layout config */
  defaultLayout: ModeLayoutConfig;
}

/**
 * Mode layout configuration
 */
export interface ModeLayoutConfig {
  /** Layout type */
  type: 'vertical' | 'horizontal' | 'grid';
  /** Number of columns */
  columns: number;
  /** Gutter/gap between elements */
  gutter: number;
  /** Padding */
  padding: number;
}

/**
 * Form layout configuration (for form mode)
 */
export interface FormLayoutConfig {
  /** Number of columns (2, 3, or 4) */
  columns: 2 | 3 | 4;
  /** Column gap */
  gutter: number;
  /** Label position */
  labelPosition: 'top' | 'left' | 'inline';
  /** Label width (for left position) */
  labelWidth?: number;
}

/**
 * Mode switch event
 */
export interface ModeSwitchEvent {
  /** Previous mode */
  fromMode: PageMode;
  /** New mode */
  toMode: PageMode;
  /** Timestamp */
  timestamp: number;
  /** Whether components were migrated */
  componentsMigrated: boolean;
}

/**
 * Drag item for unified drop handling
 */
export interface DragItem {
  /** Drag item type */
  type: 'field' | 'component' | 'block' | 'section';
  /** Source item ID */
  id?: string;
  /** Field path (for field drag) */
  fieldPath?: string;
  /** Component type (for component library drag) */
  componentType?: string;
  /** Original component data */
  data?: Record<string, unknown>;
}

/**
 * Drop target
 */
export interface DropTarget {
  /** Target type */
  type: 'canvas' | 'block' | 'section' | 'cell' | 'tab' | 'floor';
  /** Target ID */
  id?: string;
  /** Drop position */
  position?: {
    x: number;
    y: number;
  };
  /** Grid position (for grid mode) */
  gridPosition?: {
    row: number;
    column: number;
  };
  /** Insert index (for list-based layouts) */
  insertIndex?: number;
}

/**
 * Migration result when switching modes
 */
export interface MigrationResult {
  /** Whether migration was successful */
  success: boolean;
  /** Number of components migrated */
  migratedCount: number;
  /** Components that couldn't be migrated */
  failedComponents: string[];
  /** Warnings during migration */
  warnings: string[];
}
