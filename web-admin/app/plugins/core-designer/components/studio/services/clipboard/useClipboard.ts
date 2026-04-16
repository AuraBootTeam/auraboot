/**
 * useClipboard Hook
 *
 * React hook for clipboard operations with keyboard shortcuts.
 *
 * @since 3.2.0
 */

import { useCallback, useEffect, useState } from 'react';
import { clipboardManager } from './ClipboardManager';
import { useCanvasEditorState } from '~/plugins/core-designer/components/studio/hooks/store/useCanvasEditorState';
import type { Component, Position } from '~/plugins/core-designer/components/studio/domain/schema/types';
import type { PasteOptions } from './types';

interface UseClipboardOptions {
  /** Page ID for tracking source */
  pageId?: string;
  /** Enable keyboard shortcuts */
  enableShortcuts?: boolean;
  /** Components dictionary — derived from schema.components by the caller */
  components?: Record<string, Component>;
  /** Called when new components should be added to the schema */
  onAddComponents?: (components: Component[]) => void;
  /** Called when a component should be removed from the schema */
  onRemoveComponent?: (id: string) => void;
  /** Callback when components are pasted */
  onPaste?: (components: Component[]) => void;
  /** Callback when components are cut (for removal) */
  onCut?: (componentIds: string[]) => void;
}

interface UseClipboardResult {
  /** Copy selected components */
  copy: () => void;
  /** Cut selected components */
  cut: () => void;
  /** Paste from clipboard */
  paste: (options?: PasteOptions) => void;
  /** Duplicate selected components */
  duplicate: () => void;
  /** Whether clipboard has content */
  hasContent: boolean;
  /** Number of items in clipboard */
  contentCount: number;
  /** Check if component is cut */
  isCut: (componentId: string) => boolean;
}

/**
 * Clipboard operations hook
 */
export function useClipboard(options: UseClipboardOptions = {}): UseClipboardResult {
  const {
    pageId,
    enableShortcuts = true,
    components = {},
    onAddComponents,
    onRemoveComponent,
    onPaste,
    onCut,
  } = options;

  const [hasContent, setHasContent] = useState(clipboardManager.hasContent());
  const [contentCount, setContentCount] = useState(clipboardManager.getContentCount());

  const { selectedComponentId, selectComponent } = useCanvasEditorState();

  // Get selected components (single selection for now)
  const getSelectedComponents = useCallback((): Component[] => {
    const ids = selectedComponentId ? [selectedComponentId] : [];
    return ids.map((id: string) => components[id]).filter(Boolean) as Component[];
  }, [selectedComponentId, components]);

  // Copy
  const copy = useCallback(() => {
    const selected = getSelectedComponents();
    if (selected.length === 0) return;

    clipboardManager.copy(selected, pageId);
    setHasContent(true);
    setContentCount(selected.length);
  }, [getSelectedComponents, pageId]);

  // Cut
  const cut = useCallback(() => {
    const selected = getSelectedComponents();
    if (selected.length === 0) return;

    clipboardManager.cut(selected, pageId);
    setHasContent(true);
    setContentCount(selected.length);

    // Notify about cut components (for visual indication)
    onCut?.(selected.map((c) => c.id));
  }, [getSelectedComponents, pageId, onCut]);

  // Paste
  const paste = useCallback(
    (pasteOptions?: PasteOptions) => {
      const result = clipboardManager.paste(pasteOptions);

      if (result.success && result.components) {
        // Add pasted components
        onAddComponents?.(result.components);

        // Select pasted components (select first one for now - multi-select not supported yet)
        if (result.components.length >= 1) {
          selectComponent(result.components[0].id);
        }

        // Remove cut components if this was a cut operation
        const cutIds = clipboardManager.getCutComponentIds();
        if (cutIds.length > 0) {
          cutIds.forEach((id) => onRemoveComponent?.(id));
          clipboardManager.clearCutState();
        }

        onPaste?.(result.components);
      }
    },
    [onAddComponents, selectComponent, onRemoveComponent, onPaste],
  );

  // Duplicate
  const duplicate = useCallback(() => {
    const selected = getSelectedComponents();
    if (selected.length === 0) return;

    const result = clipboardManager.duplicate(selected, pageId);

    if (result.success && result.components) {
      onAddComponents?.(result.components);

      // Select duplicated components
      if (result.components.length === 1) {
        selectComponent(result.components[0].id);
      }
    }
  }, [getSelectedComponents, pageId, onAddComponents, selectComponent]);

  // Check if cut
  const isCut = useCallback((componentId: string): boolean => {
    return clipboardManager.isCut(componentId);
  }, []);

  // Keyboard shortcuts
  useEffect(() => {
    if (!enableShortcuts) return;

    const handleKeyDown = (e: KeyboardEvent) => {
      // Skip if in input element
      const target = e.target as HTMLElement;
      if (isInputElement(target)) return;

      const isCtrl = e.ctrlKey || e.metaKey;

      if (isCtrl && e.key === 'c') {
        e.preventDefault();
        copy();
      } else if (isCtrl && e.key === 'x') {
        e.preventDefault();
        cut();
      } else if (isCtrl && e.key === 'v') {
        e.preventDefault();
        paste();
      } else if (isCtrl && e.key === 'd') {
        e.preventDefault();
        duplicate();
      }
    };

    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [enableShortcuts, copy, cut, paste, duplicate]);

  return {
    copy,
    cut,
    paste,
    duplicate,
    hasContent,
    contentCount,
    isCut,
  };
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

export default useClipboard;
