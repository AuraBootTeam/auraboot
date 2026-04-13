/**
 * Dashboard Grid Layout Types
 *
 * Enhanced type definitions for dashboard grid layouts,
 * compatible with react-grid-layout.
 */

/**
 * Layout type compatible with react-grid-layout
 * Defined locally to avoid namespace import issues
 */
export interface Layout {
  /** A string corresponding to the component key */
  i: string;
  /** X position in grid units */
  x: number;
  /** Y position in grid units */
  y: number;
  /** Width in grid units */
  w: number;
  /** Height in grid units */
  h: number;
  /** Minimum width in grid units */
  minW?: number;
  /** Maximum width in grid units */
  maxW?: number;
  /** Minimum height in grid units */
  minH?: number;
  /** Maximum height in grid units */
  maxH?: number;
  /** If true, equal to isDraggable: false and isResizable: false */
  static?: boolean;
  /** If false, will not be draggable. Overrides static */
  isDraggable?: boolean;
  /** If false, will not be resizable. Overrides static */
  isResizable?: boolean;
}

/**
 * Responsive breakpoint configuration
 */
export interface ResponsiveConfig {
  /** Number of columns at this breakpoint */
  columns: number;
  /** Row height in pixels at this breakpoint */
  rowHeight: number;
}

/**
 * Enhanced grid container configuration
 */
export interface EnhancedGridConfig {
  /** Layout type identifier */
  type: 'grid';
  /** Unique grid identifier */
  id: string;
  /** Number of columns (12 or 24 grid system) */
  columns: 12 | 24;
  /** Height of a single row in pixels */
  rowHeight: number;
  /** Gap between grid items in pixels */
  gap: number;
  /** Compaction type for auto-arrangement */
  compactType?: 'vertical' | 'horizontal' | null;
  /** Responsive breakpoint configurations */
  responsive?: {
    lg?: ResponsiveConfig;
    md?: ResponsiveConfig;
    sm?: ResponsiveConfig;
  };
  /** Grid cell configurations */
  cells: EnhancedGridCellConfig[];
}

/**
 * Enhanced grid cell configuration
 *
 * Compatible with react-grid-layout's Layout interface,
 * with additional component configuration.
 */
export interface EnhancedGridCellConfig {
  /** Unique cell identifier */
  id: string;
  /** Component type to render in this cell */
  componentType: string;

  // Position (react-grid-layout compatible)
  /** X position in grid units */
  x: number;
  /** Y position in grid units */
  y: number;
  /** Width in grid units */
  w: number;
  /** Height in grid units */
  h: number;

  // Size constraints
  /** Minimum width in grid units */
  minW?: number;
  /** Maximum width in grid units */
  maxW?: number;
  /** Minimum height in grid units */
  minH?: number;
  /** Maximum height in grid units */
  maxH?: number;

  // Interaction
  /** If true, equal to isDraggable: false and isResizable: false */
  static?: boolean;
  /** If false, will not be draggable. Overrides static */
  isDraggable?: boolean;
  /** If false, will not be resizable. Overrides static */
  isResizable?: boolean;

  // Component config
  /** Props to pass to the rendered component */
  props: Record<string, unknown>;
}

/**
 * Convert an EnhancedGridCellConfig to a react-grid-layout Layout object
 *
 * @param cell - The cell configuration to convert
 * @returns A Layout object compatible with react-grid-layout
 */
export function cellToLayout(cell: EnhancedGridCellConfig): Layout {
  return {
    i: cell.id,
    x: cell.x,
    y: cell.y,
    w: cell.w,
    h: cell.h,
    minW: cell.minW,
    maxW: cell.maxW,
    minH: cell.minH,
    maxH: cell.maxH,
    static: cell.static,
    isDraggable: cell.isDraggable,
    isResizable: cell.isResizable,
  };
}

/**
 * Update an EnhancedGridCellConfig with position data from a Layout object
 *
 * @param layout - The Layout object from react-grid-layout
 * @param existingCell - The existing cell configuration to update
 * @returns Updated cell configuration with new position data
 */
export function layoutToCell(
  layout: Layout,
  existingCell: EnhancedGridCellConfig,
): EnhancedGridCellConfig {
  return {
    ...existingCell,
    x: layout.x,
    y: layout.y,
    w: layout.w,
    h: layout.h,
  };
}

/**
 * Convert an array of EnhancedGridCellConfig to react-grid-layout layouts
 *
 * @param cells - Array of cell configurations
 * @returns Array of Layout objects
 */
export function cellsToLayouts(cells: EnhancedGridCellConfig[]): Layout[] {
  return cells.map(cellToLayout);
}

/**
 * Update multiple cells with new layout positions
 *
 * @param layouts - Array of Layout objects from react-grid-layout
 * @param cells - Array of existing cell configurations
 * @returns Updated array of cell configurations
 */
export function layoutsToCells(
  layouts: Layout[],
  cells: EnhancedGridCellConfig[],
): EnhancedGridCellConfig[] {
  const cellMap = new Map(cells.map((cell) => [cell.id, cell]));

  return layouts.map((layout) => {
    const existingCell = cellMap.get(layout.i);
    if (!existingCell) {
      throw new Error(`Cell with id "${layout.i}" not found`);
    }
    return layoutToCell(layout, existingCell);
  });
}
