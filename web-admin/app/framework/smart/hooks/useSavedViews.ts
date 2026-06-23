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
  SavedViewCopyToPersonalRequest,
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
  /** Optional scope filter for release-scoped user experiences */
  scopeFilter?: ViewScope | 'all';
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
  /** Select the implicit default baseline, or clear selection if it does not exist */
  selectDefaultView: () => void;
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
  /** Copy an accessible shared/global view into personal scope */
  copyToPersonal: (pid: string, request?: SavedViewCopyToPersonalRequest) => Promise<SavedView>;
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
  const { modelCode, pageKey, scopeFilter = 'all', autoLoad = true } = options;

  const [views, setViews] = useState<SavedView[]>([]);
  const [currentView, setCurrentView] = useState<SavedView | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const mountedRef = useRef(true);
  const selectedViewPidRef = useRef<string | null>(null);

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

      const scopedViews =
        scopeFilter === 'all'
          ? accessibleViews
          : accessibleViews.filter((view) => view.scope === scopeFilter);
      const scopedDefaultView =
        scopeFilter === 'all' || defaultView?.scope === scopeFilter ? defaultView : null;

      setViews(scopedViews);

      const preservedView = selectedViewPidRef.current
        ? scopedViews.find((view) => view.pid === selectedViewPidRef.current)
        : undefined;
      const nextView = preservedView ?? scopedDefaultView ?? scopedViews[0] ?? null;
      selectedViewPidRef.current = nextView?.pid ?? null;
      setCurrentView(nextView);
    } catch (err) {
      if (mountedRef.current) {
        setError(err instanceof Error ? err : new Error('Failed to load views'));
      }
    } finally {
      if (mountedRef.current) {
        setLoading(false);
      }
    }
  }, [modelCode, pageKey, scopeFilter]);

  /**
   * Select a view by PID
   */
  const selectView = useCallback(
    (pid: string) => {
      const view = views.find((v) => v.pid === pid);
      if (view) {
        selectedViewPidRef.current = view.pid;
        setCurrentView(view);
      }
    },
    [views],
  );

  const selectDefaultView = useCallback(() => {
    const implicitDefaultView = views.find((view) => view.isImplicit === true) ?? null;
    selectedViewPidRef.current = implicitDefaultView?.pid ?? null;
    setCurrentView(implicitDefaultView);
  }, [views]);

  /**
   * Create a new view
   */
  const createView = useCallback(async (request: SavedViewCreateRequest): Promise<SavedView> => {
    const newView = await savedViewService.createView(request);

    if (mountedRef.current) {
      setViews((prev) => [...prev, newView]);
      selectedViewPidRef.current = newView.pid;
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
        selectedViewPidRef.current = updatedView.pid;
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
            const nextView = remaining.length > 0 ? remaining[0] : null;
            selectedViewPidRef.current = nextView?.pid ?? null;
            setCurrentView(nextView);
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
   * Copy an accessible view into current user's personal scope and select it.
   */
  const copyToPersonal = useCallback(
    async (pid: string, request: SavedViewCopyToPersonalRequest = {}): Promise<SavedView> => {
      const copiedView = await savedViewService.copyToPersonal(pid, request);

      if (mountedRef.current) {
        setViews((prev) => {
          const withoutExisting = prev.filter((view) => view.pid !== copiedView.pid);
          return [...withoutExisting, copiedView];
        });
        selectedViewPidRef.current = copiedView.pid;
        setCurrentView(copiedView);
      }

      return copiedView;
    },
    [],
  );

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
    selectDefaultView,
    createView,
    updateView,
    updateViewConfig,
    deleteView,
    setDefaultView,
    duplicateView,
    copyToPersonal,
    reload: loadViews,
    groupedViews,
  };
}

export default useSavedViews;
