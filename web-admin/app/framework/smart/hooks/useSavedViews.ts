/**
 * useSavedViews Hook
 *
 * React hook for managing SavedViews with support for CRUD operations,
 * view selection, and grouping by scope.
 */

import { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import { savedViewService } from '~/shared/services/savedViewService';
import type {
  SavedView,
  SavedViewCreateRequest,
  SavedViewUpdateRequest,
  ViewConfig,
  ViewScope,
} from '~/framework/smart/types/savedView';

/**
 * Options for the useSavedViews hook
 */
export interface UseSavedViewsOptions {
  /** Model code to filter views (required) */
  modelCode: string;
  /** Page key to filter views (optional) */
  pageKey?: string;
  /** Whether to automatically load views on mount (default: true) */
  autoLoad?: boolean;
}

/**
 * Grouped views by scope
 */
export interface GroupedViews {
  /** Personal views owned by current user */
  personal: SavedView[];
  /** Team views shared within team */
  team: SavedView[];
  /** Global views accessible to all users */
  global: SavedView[];
}

/**
 * Return type for the useSavedViews hook
 */
export interface UseSavedViewsResult {
  /** All accessible views */
  views: SavedView[];
  /** Currently selected view */
  currentView: SavedView | null;
  /** Loading state */
  loading: boolean;
  /** Error object if operation failed */
  error: Error | null;
  /** Select a view by PID */
  selectView: (pid: string) => void;
  /** Create a new view */
  createView: (request: SavedViewCreateRequest) => Promise<SavedView>;
  /** Update the current view */
  updateView: (request: SavedViewUpdateRequest) => Promise<SavedView>;
  /** Update only the viewConfig of current view */
  updateViewConfig: (config: Partial<ViewConfig>) => Promise<SavedView>;
  /** Delete a view by PID */
  deleteView: (pid: string) => Promise<void>;
  /** Set a view as default */
  setDefaultView: (pid: string) => Promise<void>;
  /** Duplicate a view with a new name */
  duplicateView: (pid: string, newName: string) => Promise<SavedView>;
  /** Reload all views */
  reload: () => Promise<void>;
  /** Views grouped by scope */
  groupedViews: GroupedViews;
}

/**
 * Hook for managing SavedViews with full CRUD support
 *
 * @param options - Hook configuration options
 * @returns Object containing views, current view, loading state, error, and CRUD functions
 *
 * @example
 * // Basic usage
 * const { views, currentView, loading, selectView } = useSavedViews({
 *   modelCode: 'order',
 * });
 *
 * @example
 * // With page key and manual loading
 * const { views, reload, createView } = useSavedViews({
 *   modelCode: 'order',
 *   pageKey: 'order-list',
 *   autoLoad: false,
 * });
 */
export function useSavedViews(options: UseSavedViewsOptions): UseSavedViewsResult {
  const { modelCode, pageKey, autoLoad = true } = options;

  const [views, setViews] = useState<SavedView[]>([]);
  const [currentView, setCurrentView] = useState<SavedView | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const mountedRef = useRef(true);

  /**
   * Load all accessible views and default view
   */
  const loadViews = useCallback(async () => {
    setLoading(true);
    setError(null);

    try {
      const queryParams = { modelCode, pageKey };

      // Fetch accessible views and default view in parallel
      const [accessibleViews, defaultView] = await Promise.all([
        savedViewService.getAccessibleViews(queryParams),
        savedViewService.getDefaultView(queryParams),
      ]);

      if (!mountedRef.current) return;

      setViews(accessibleViews);

      // Auto-select default view or first view
      if (defaultView) {
        setCurrentView(defaultView);
      } else if (accessibleViews.length > 0) {
        setCurrentView(accessibleViews[0]);
      } else {
        setCurrentView(null);
      }
    } catch (err) {
      if (mountedRef.current) {
        setError(err instanceof Error ? err : new Error('Failed to load views'));
      }
    } finally {
      if (mountedRef.current) {
        setLoading(false);
      }
    }
  }, [modelCode, pageKey]);

  /**
   * Select a view by PID
   */
  const selectView = useCallback(
    (pid: string) => {
      const view = views.find((v) => v.pid === pid);
      if (view) {
        setCurrentView(view);
      }
    },
    [views],
  );

  /**
   * Create a new view
   */
  const createView = useCallback(async (request: SavedViewCreateRequest): Promise<SavedView> => {
    const newView = await savedViewService.createView(request);

    if (mountedRef.current) {
      setViews((prev) => [...prev, newView]);
      setCurrentView(newView);
    }

    return newView;
  }, []);

  /**
   * Update the current view
   */
  const updateView = useCallback(
    async (request: SavedViewUpdateRequest): Promise<SavedView> => {
      if (!currentView) {
        throw new Error('No view is currently selected');
      }

      const updatedView = await savedViewService.updateView(currentView.pid, request);

      if (mountedRef.current) {
        setViews((prev) => prev.map((v) => (v.pid === updatedView.pid ? updatedView : v)));
        setCurrentView(updatedView);
      }

      return updatedView;
    },
    [currentView],
  );

  /**
   * Update only the viewConfig of current view
   */
  const updateViewConfig = useCallback(
    async (config: Partial<ViewConfig>): Promise<SavedView> => {
      if (!currentView) {
        throw new Error('No view is currently selected');
      }

      // Merge new config with existing viewConfig
      const mergedConfig: ViewConfig = {
        ...currentView.viewConfig,
        ...config,
      };

      return updateView({ viewConfig: mergedConfig });
    },
    [currentView, updateView],
  );

  /**
   * Delete a view by PID
   */
  const deleteView = useCallback(
    async (pid: string): Promise<void> => {
      await savedViewService.deleteView(pid);

      if (mountedRef.current) {
        setViews((prev) => {
          const remaining = prev.filter((v) => v.pid !== pid);

          // If deleted view was current, select first remaining view
          if (currentView?.pid === pid) {
            setCurrentView(remaining.length > 0 ? remaining[0] : null);
          }

          return remaining;
        });
      }
    },
    [currentView],
  );

  /**
   * Set a view as default
   */
  const setDefaultView = useCallback(
    async (pid: string): Promise<void> => {
      await savedViewService.setDefaultView(pid);

      if (mountedRef.current) {
        // Update isDefault flag for all views
        setViews((prev) =>
          prev.map((v) => ({
            ...v,
            isDefault: v.pid === pid,
          })),
        );

        // Update currentView if it was affected
        if (currentView) {
          setCurrentView((prev) => (prev ? { ...prev, isDefault: prev.pid === pid } : null));
        }
      }
    },
    [currentView],
  );

  /**
   * Duplicate a view with a new name
   */
  const duplicateView = useCallback(async (pid: string, newName: string): Promise<SavedView> => {
    const duplicatedView = await savedViewService.duplicateView(pid, newName);

    if (mountedRef.current) {
      setViews((prev) => [...prev, duplicatedView]);
    }

    return duplicatedView;
  }, []);

  /**
   * Group views by scope using useMemo
   */
  const groupedViews = useMemo<GroupedViews>(() => {
    const scopeGroups: Record<ViewScope, SavedView[]> = {
      personal: [],
      team: [],
      global: [],
    };

    views.forEach((view) => {
      if (scopeGroups[view.scope]) {
        scopeGroups[view.scope].push(view);
      }
    });

    return {
      personal: scopeGroups.personal,
      team: scopeGroups.team,
      global: scopeGroups.global,
    };
  }, [views]);

  /**
   * Effect for initial load
   */
  useEffect(() => {
    if (autoLoad) {
      loadViews();
    }
  }, [autoLoad, loadViews]);

  /**
   * Cleanup effect
   */
  useEffect(() => {
    mountedRef.current = true;

    return () => {
      mountedRef.current = false;
    };
  }, []);

  return {
    views,
    currentView,
    loading,
    error,
    selectView,
    createView,
    updateView,
    updateViewConfig,
    deleteView,
    setDefaultView,
    duplicateView,
    reload: loadViews,
    groupedViews,
  };
}

export default useSavedViews;
