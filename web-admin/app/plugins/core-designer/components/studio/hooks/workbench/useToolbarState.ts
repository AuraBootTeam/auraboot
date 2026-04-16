/**
 * useToolbarState Hook
 *
 * Manages toolbar state and integrates with designer store.
 *
 * @since 3.2.0
 */

import { useState, useCallback, useMemo } from 'react';
import type { PageMeta } from '../../services/page-manager';

/**
 * Toolbar state
 */
export interface ToolbarState {
  /** Page metadata */
  pageMeta?: PageMeta;
  /** Whether there are unsaved changes */
  hasUnsavedChanges: boolean;
  /** Whether undo is available */
  canUndo: boolean;
  /** Whether redo is available */
  canRedo: boolean;
  /** Current zoom level */
  zoomLevel: number;
  /** Current device */
  currentDevice: string;
  /** Auto-save enabled */
  autoSaveEnabled: boolean;
  /** Last saved timestamp */
  lastSavedAt?: string;
  /** Is currently saving */
  isSaving: boolean;
  /** Is currently publishing */
  isPublishing: boolean;
  /** Show settings panel */
  showSettings: boolean;
  /** Show shortcuts help */
  showShortcuts: boolean;
  /** Show version history */
  showVersionHistory: boolean;
  /** Show preview */
  showPreview: boolean;
}

/**
 * Toolbar actions
 */
export interface ToolbarActions {
  undo: () => void;
  redo: () => void;
  clear: () => void;
  zoomIn: () => void;
  zoomOut: () => void;
  zoomReset: () => void;
  setZoomLevel: (level: number) => void;
  setDevice: (device: string) => void;
  save: () => Promise<void>;
  publish: () => Promise<void>;
  toggleSettings: () => void;
  toggleShortcuts: () => void;
  toggleVersionHistory: () => void;
  togglePreview: () => void;
  markSaved: () => void;
  markUnsaved: () => void;
}

/**
 * useToolbarState options
 */
export interface UseToolbarStateOptions {
  /** Page metadata */
  pageMeta?: PageMeta;
  /** Save callback */
  onSave?: () => Promise<void>;
  /** Publish callback */
  onPublish?: () => Promise<void>;
  /** Auto-save enabled */
  autoSaveEnabled?: boolean;
}

/**
 * useToolbarState hook
 */
export function useToolbarState(options: UseToolbarStateOptions = {}): {
  state: ToolbarState;
  actions: ToolbarActions;
} {
  const { pageMeta, onSave, onPublish, autoSaveEnabled = true } = options;

  // canUndo/canRedo: stubs defaulting to false until real undo stack (CommandManager) is wired.
  // Real undo/redo flows from usePageSchemaHistory in PageDesignerEditorImpl via onUndo/onRedo options.
  const canUndo = false;
  const canRedo = false;
  const undo = useCallback(() => {}, []);
  const redo = useCallback(() => {}, []);
  const reset = useCallback(() => {}, []);

  // Local state
  const [hasUnsavedChanges, setHasUnsavedChanges] = useState(false);
  const [zoomLevel, setZoomLevel] = useState(100);
  const [currentDevice, setCurrentDevice] = useState('desktop');
  const [lastSavedAt, setLastSavedAt] = useState<string | undefined>();
  const [isSaving, setIsSaving] = useState(false);
  const [isPublishing, setIsPublishing] = useState(false);
  const [showSettings, setShowSettings] = useState(false);
  const [showShortcuts, setShowShortcuts] = useState(false);
  const [showVersionHistory, setShowVersionHistory] = useState(false);
  const [showPreview, setShowPreview] = useState(false);

  // Zoom actions
  const handleZoomIn = useCallback(() => {
    setZoomLevel((prev) => Math.min(200, prev + 25));
  }, []);

  const handleZoomOut = useCallback(() => {
    setZoomLevel((prev) => Math.max(25, prev - 25));
  }, []);

  const handleZoomReset = useCallback(() => {
    setZoomLevel(100);
  }, []);

  // Save action
  const handleSave = useCallback(async () => {
    if (isSaving) return;

    setIsSaving(true);
    try {
      await onSave?.();
      setLastSavedAt(new Date().toISOString());
      setHasUnsavedChanges(false);
    } catch (error) {
      console.error('Save failed:', error);
      throw error;
    } finally {
      setIsSaving(false);
    }
  }, [isSaving, onSave]);

  // Publish action
  const handlePublish = useCallback(async () => {
    if (isPublishing) return;

    setIsPublishing(true);
    try {
      await onPublish?.();
      setLastSavedAt(new Date().toISOString());
      setHasUnsavedChanges(false);
    } catch (error) {
      console.error('Publish failed:', error);
      throw error;
    } finally {
      setIsPublishing(false);
    }
  }, [isPublishing, onPublish]);

  // Mark as saved/unsaved
  const markSaved = useCallback(() => {
    setHasUnsavedChanges(false);
    setLastSavedAt(new Date().toISOString());
  }, []);

  const markUnsaved = useCallback(() => {
    setHasUnsavedChanges(true);
  }, []);

  // Toggle panels
  const toggleSettings = useCallback(() => setShowSettings((prev) => !prev), []);
  const toggleShortcuts = useCallback(() => setShowShortcuts((prev) => !prev), []);
  const toggleVersionHistory = useCallback(() => setShowVersionHistory((prev) => !prev), []);
  const togglePreview = useCallback(() => setShowPreview((prev) => !prev), []);

  const state: ToolbarState = useMemo(
    () => ({
      pageMeta,
      hasUnsavedChanges,
      canUndo,
      canRedo,
      zoomLevel,
      currentDevice,
      autoSaveEnabled,
      lastSavedAt,
      isSaving,
      isPublishing,
      showSettings,
      showShortcuts,
      showVersionHistory,
      showPreview,
    }),
    [
      pageMeta,
      hasUnsavedChanges,
      canUndo,
      canRedo,
      zoomLevel,
      currentDevice,
      autoSaveEnabled,
      lastSavedAt,
      isSaving,
      isPublishing,
      showSettings,
      showShortcuts,
      showVersionHistory,
      showPreview,
    ],
  );

  const actions: ToolbarActions = useMemo(
    () => ({
      undo,
      redo,
      clear: reset,
      zoomIn: handleZoomIn,
      zoomOut: handleZoomOut,
      zoomReset: handleZoomReset,
      setZoomLevel,
      setDevice: setCurrentDevice,
      save: handleSave,
      publish: handlePublish,
      toggleSettings,
      toggleShortcuts,
      toggleVersionHistory,
      togglePreview,
      markSaved,
      markUnsaved,
    }),
    [
      undo,
      redo,
      reset,
      handleZoomIn,
      handleZoomOut,
      handleZoomReset,
      handleSave,
      handlePublish,
      toggleSettings,
      toggleShortcuts,
      toggleVersionHistory,
      togglePreview,
      markSaved,
      markUnsaved,
    ],
  );

  return { state, actions };
}

export default useToolbarState;
