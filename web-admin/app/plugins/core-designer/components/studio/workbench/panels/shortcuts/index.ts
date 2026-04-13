/**
 * Shortcuts Module
 *
 * Keyboard shortcuts help panel and utilities.
 *
 * @since 3.2.0
 */

// Types
export type {
  ShortcutCategory,
  ShortcutDefinition,
  ShortcutKey,
  CategoryInfo,
  ShortcutPanelState,
} from './types';

// Data
export {
  CATEGORIES,
  SHORTCUTS,
  getShortcutsByCategory,
  searchShortcuts,
  formatKeyCombo,
} from './shortcuts';

// Components
export { ShortcutHelpPanel, default } from './ShortcutHelpPanel';

// Hooks
export { useShortcutHelp } from './useShortcutHelp';
