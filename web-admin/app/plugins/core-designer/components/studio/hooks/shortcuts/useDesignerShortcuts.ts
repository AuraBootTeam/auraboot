/**
 * useDesignerShortcuts Hook
 *
 * Manages designer-specific keyboard shortcuts.
 *
 * @since 3.2.0
 */

import { useEffect, useCallback } from 'react';
import { useCanvasEditorState } from '~/plugins/core-designer/components/studio/hooks/store/useCanvasEditorState';
import { useClipboard } from '~/plugins/core-designer/components/studio/services/clipboard';

export interface ShortcutDefinition {
  key: string;
  ctrl?: boolean;
  shift?: boolean;
  alt?: boolean;
  description: string;
  category: 'edit' | 'view' | 'selection' | 'navigation';
}

/**
 * All available shortcuts
 */
export const SHORTCUTS: ShortcutDefinition[] = [
  // Edit
  { key: 'c', ctrl: true, description: 'Copy', category: 'edit' },
  { key: 'x', ctrl: true, description: 'Cut', category: 'edit' },
  { key: 'v', ctrl: true, description: 'Paste', category: 'edit' },
  { key: 'd', ctrl: true, description: 'Duplicate', category: 'edit' },
  { key: 'z', ctrl: true, description: 'Undo', category: 'edit' },
  { key: 'y', ctrl: true, description: 'Redo', category: 'edit' },
  { key: 'z', ctrl: true, shift: true, description: 'Redo', category: 'edit' },
  { key: 's', ctrl: true, description: 'Save', category: 'edit' },
  { key: 'Delete', description: 'Delete', category: 'edit' },
  { key: 'Backspace', description: 'Delete', category: 'edit' },

  // Selection
  { key: 'a', ctrl: true, description: 'Select All', category: 'selection' },
  { key: 'Escape', description: 'Deselect', category: 'selection' },

  // View
  { key: '=', ctrl: true, description: 'Zoom In', category: 'view' },
  { key: '-', ctrl: true, description: 'Zoom Out', category: 'view' },
  { key: '0', ctrl: true, description: 'Reset Zoom', category: 'view' },
  { key: '1', ctrl: true, description: '100% Zoom', category: 'view' },
];

export interface UseDesignerShortcutsOptions {
  /** Enable shortcuts */
  enabled?: boolean;
  /** All component IDs (for select-all). Derived from schema.components by the caller. */
  allComponentIds?: string[];
  /** Called when the selected component should be removed from the schema */
  onRemoveComponent?: (id: string) => void;
  /** Save handler */
  onSave?: () => void;
  /** Undo handler */
  onUndo?: () => void;
  /** Redo handler */
  onRedo?: () => void;
  /** Zoom handlers */
  onZoomIn?: () => void;
  onZoomOut?: () => void;
  onZoomReset?: () => void;
}

/**
 * Designer shortcuts hook
 */
export function useDesignerShortcuts(options: UseDesignerShortcutsOptions = {}): void {
  const {
    enabled = true,
    allComponentIds = [],
    onRemoveComponent,
    onSave,
    onUndo,
    onRedo,
    onZoomIn,
    onZoomOut,
    onZoomReset,
  } = options;

  const { selectedComponentId, selectComponent } = useCanvasEditorState();

  // clearSelection helper
  const clearSelection = useCallback(() => {
    selectComponent(null);
  }, [selectComponent]);

  const { copy, cut, paste, duplicate } = useClipboard({ enableShortcuts: false });

  // Delete handler
  const handleDelete = useCallback(() => {
    if (selectedComponentId) {
      onRemoveComponent?.(selectedComponentId);
      clearSelection();
    }
  }, [selectedComponentId, onRemoveComponent, clearSelection]);

  // Select all handler
  const handleSelectAll = useCallback(() => {
    if (allComponentIds.length > 0) {
      // Select first component (multi-select handled separately)
      selectComponent(allComponentIds[0]);
    }
  }, [allComponentIds, selectComponent]);

  // Main keyboard handler
  useEffect(() => {
    if (!enabled) return;

    const handleKeyDown = (e: KeyboardEvent) => {
      // Skip if in input element
      const target = e.target as HTMLElement;
      if (isInputElement(target)) return;

      const isCtrl = e.ctrlKey || e.metaKey;
      const isShift = e.shiftKey;
      const key = e.key.toLowerCase();

      // Edit shortcuts
      if (isCtrl) {
        switch (key) {
          case 's':
            e.preventDefault();
            onSave?.();
            break;
          case 'z':
            e.preventDefault();
            if (isShift) {
              onRedo?.();
            } else {
              onUndo?.();
            }
            break;
          case 'y':
            e.preventDefault();
            onRedo?.();
            break;
          case 'c':
            e.preventDefault();
            copy();
            break;
          case 'x':
            e.preventDefault();
            cut();
            break;
          case 'v':
            e.preventDefault();
            paste();
            break;
          case 'd':
            e.preventDefault();
            duplicate();
            break;
          case 'a':
            e.preventDefault();
            handleSelectAll();
            break;
          case '=':
          case '+':
            e.preventDefault();
            onZoomIn?.();
            break;
          case '-':
            e.preventDefault();
            onZoomOut?.();
            break;
          case '0':
            e.preventDefault();
            onZoomReset?.();
            break;
        }
      }

      // Non-ctrl shortcuts
      switch (e.key) {
        case 'Delete':
        case 'Backspace':
          if (!isInputElement(target)) {
            e.preventDefault();
            handleDelete();
          }
          break;
        case 'Escape':
          e.preventDefault();
          clearSelection();
          break;
      }
    };

    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [
    enabled,
    copy,
    cut,
    paste,
    duplicate,
    handleDelete,
    handleSelectAll,
    clearSelection,
    onSave,
    onUndo,
    onRedo,
    onZoomIn,
    onZoomOut,
    onZoomReset,
  ]);
}

/**
 * Check if element is an input element
 */
function isInputElement(element: HTMLElement): boolean {
  const tagName = element.tagName.toLowerCase();
  return (
    tagName === 'input' ||
    tagName === 'textarea' ||
    tagName === 'select' ||
    element.isContentEditable
  );
}

/**
 * Get shortcut display string
 */
export function getShortcutDisplay(shortcut: ShortcutDefinition): string {
  const parts: string[] = [];
  if (shortcut.ctrl) parts.push('⌘');
  if (shortcut.shift) parts.push('⇧');
  if (shortcut.alt) parts.push('⌥');
  parts.push(shortcut.key.toUpperCase());
  return parts.join('');
}

export default useDesignerShortcuts;
