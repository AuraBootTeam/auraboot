/**
 * useAutoSaveView — Debounced auto-save of user view preferences.
 *
 * Creates an implicit SavedView for users who haven't explicitly created one,
 * then auto-saves sort/filter/column changes with a 2-second debounce.
 */
import { useCallback, useRef } from 'react';
import type { ViewConfig, SavedView } from '~/framework/smart/types/savedView';

interface UseAutoSaveViewOptions {
  currentView: SavedView | null;
  updateViewConfig: (config: Partial<ViewConfig>) => Promise<SavedView>;
}

interface UseAutoSaveViewResult {
  /** Queue a partial ViewConfig update — debounced 2s, merged with pending changes */
  autoSave: (config: Partial<ViewConfig>) => void;
  /** Whether there are pending unsaved changes */
  hasPendingChanges: boolean;
}

export function useAutoSaveView({
  currentView,
  updateViewConfig,
}: UseAutoSaveViewOptions): UseAutoSaveViewResult {
  const pendingRef = useRef<Partial<ViewConfig> | null>(null);
  const timerRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  const autoSave = useCallback(
    (config: Partial<ViewConfig>) => {
      // Merge with any pending changes
      pendingRef.current = { ...pendingRef.current, ...config };

      // Clear previous timer
      if (timerRef.current) clearTimeout(timerRef.current);

      // Debounce 2 seconds
      timerRef.current = setTimeout(async () => {
        const pending = pendingRef.current;
        if (!pending) return;
        pendingRef.current = null;

        try {
          await updateViewConfig(pending);
        } catch (err) {
          console.error('[useAutoSaveView] Failed to auto-save:', err);
          // Re-queue the failed changes
          pendingRef.current = { ...(pendingRef.current || {}), ...pending };
        }
      }, 2000);
    },
    [currentView, updateViewConfig],
  );

  return {
    autoSave,
    hasPendingChanges: !!pendingRef.current,
  };
}
