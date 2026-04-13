/**
 * Shortcut Panel Types
 *
 * Types for keyboard shortcut help panel.
 *
 * @since 3.2.0
 */

/**
 * Shortcut category
 */
export type ShortcutCategory =
  | 'general'
  | 'edit'
  | 'canvas'
  | 'selection'
  | 'layout'
  | 'navigation';

/**
 * Shortcut definition
 */
export interface ShortcutDefinition {
  /** Unique ID */
  id: string;
  /** Display label */
  label: string;
  /** Description */
  description?: string;
  /** Key combination */
  keys: ShortcutKey[];
  /** Category */
  category: ShortcutCategory;
  /** Whether shortcut is enabled */
  enabled?: boolean;
  /** Platform specific (mac/windows) */
  platform?: 'mac' | 'windows' | 'all';
  /** Tags for search */
  tags?: string[];
}

/**
 * Single key in combination
 */
export interface ShortcutKey {
  /** Key code or name */
  key: string;
  /** Whether Ctrl/Cmd is required */
  ctrl?: boolean;
  /** Whether Shift is required */
  shift?: boolean;
  /** Whether Alt/Option is required */
  alt?: boolean;
  /** Whether Meta/Win is required */
  meta?: boolean;
}

/**
 * Category info
 */
export interface CategoryInfo {
  /** Category ID */
  id: ShortcutCategory;
  /** Display name */
  name: string;
  /** Icon path */
  icon: string;
  /** Order for display */
  order: number;
}

/**
 * Shortcut panel state
 */
export interface ShortcutPanelState {
  /** Whether panel is open */
  isOpen: boolean;
  /** Search query */
  searchQuery: string;
  /** Selected category filter */
  selectedCategory: ShortcutCategory | 'all';
}
