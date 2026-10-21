import React, { useCallback, useMemo } from 'react';
import type { SetURLSearchParams } from 'react-router';
import {
  type SavedView,
  type SavedViewCreateRequest,
  type ViewConfig,
  type ViewFilterConfig,
  type ViewType,
} from '~/framework/smart/types/savedView';
import { isImplicitSavedView } from '~/framework/smart/utils/savedViewPersistence';
import { type ChipPin } from '~/shared/services/savedViewService';
import type { User } from '~/utils/type';
import {
  type ListLoadDataParams,
  type QuickFilterKey,
  clearTransientViewSearchParams,
} from './listPageStateUtils';

import { findPersonalPresetSavedView } from './listViewConfig';
import { assembleQuickFilterChips, type QuickFilterChip } from './quickFilterChips';
import {
  buildQuickFilterPreset,
  buildQuickFilterPresetViewRequest,
  getQuickFilterPresetDefinition,
  getQuickFilterPresetDefinitions,
} from './quickFilterPresets';

interface ListQuickFiltersInput {
  rememberSubmittedFilters: (filters: Record<string, any>) => void;
  setSearchParams: SetURLSearchParams;
  listSchemaFieldNameSet: Set<string>;
  activeQuickFilterRef: React.RefObject<string | null>;
  setActiveQuickFilter: React.Dispatch<React.SetStateAction<string | null>>;
  setFilters: (
    filters: Record<string, any> | ((prev: Record<string, any>) => Record<string, any>),
  ) => void;
  loadData: (params?: ListLoadDataParams) => Promise<void>;
  pagination: { current: number; pageSize: number; total: number };
  currentView: SavedView | null;
  clearSortOverrides: () => void;
  clearFilterOverrides: () => void;
  selectDefaultView: () => void;
  setPendingViewConfig: React.Dispatch<React.SetStateAction<Partial<ViewConfig> | null>>;
  setActiveViewType: React.Dispatch<React.SetStateAction<ViewType>>;
  clearKeyword: () => void;
  user: User | null;
  t: (key: string) => string;
  teamViews: SavedView[];
  savedViews: SavedView[];
  chipPins: ChipPin[];
  selectView: (pid: string) => void;
  activeQuickFilter: string | null;
  showSuccessToast: (message: string, duration?: number) => void;
  translateCommon: (key: string, fallback: string) => string;
  modelCode: any;
  pageKey: string;
  createView: (request: SavedViewCreateRequest) => Promise<SavedView>;
  showErrorToast: (message: string, duration?: number) => void;
  filters: Record<string, any>;
  ensureViewAndUpdateConfig: (
    config: Partial<import('~/framework/smart/types/savedView').ViewConfig>,
    options?: { isStale?: () => boolean; rethrow?: boolean },
  ) => Promise<void>;
  chipFilters: ViewFilterConfig[];
}

/** Apply preset chips and select or create saved views. */
export function useListQuickFilters({
  rememberSubmittedFilters,
  setSearchParams,
  listSchemaFieldNameSet,
  activeQuickFilterRef,
  setActiveQuickFilter,
  setFilters,
  loadData,
  pagination,
  currentView,
  clearSortOverrides,
  clearFilterOverrides,
  selectDefaultView,
  setPendingViewConfig,
  setActiveViewType,
  clearKeyword,
  user,
  t,
  teamViews,
  savedViews,
  chipPins,
  selectView,
  activeQuickFilter,
  showSuccessToast,
  translateCommon,
  modelCode,
  pageKey,
  createView,
  showErrorToast,
  filters,
  ensureViewAndUpdateConfig,
  chipFilters,
}: ListQuickFiltersInput) {
  // Sync the active preset view to the URL (?preset=created_today) so it
  // survives reload; pass null to clear it. A quick-filter preset belongs to
  // the default list state, so selecting one clears any SavedView identity and
  // transient URL state that came from a personal view.
  const syncPresetToUrl = useCallback(
    (key: QuickFilterKey | null) => {
      setSearchParams(
        (prev) => {
          const p = new URLSearchParams(prev);
          if (key) {
            p.delete('view');
            clearTransientViewSearchParams(p, listSchemaFieldNameSet);
            p.set('preset', key);
          } else {
            p.delete('preset');
          }
          return p;
        },
        { replace: true },
      );
    },
    [setSearchParams],
  );

  // Quick filter handler — toggle a preset view on/off, apply its filter, reload.
  const handleQuickFilter = useCallback(
    (key: QuickFilterKey) => {
      if (activeQuickFilterRef.current === key) {
        // Toggle off — clear preset filter and reload
        activeQuickFilterRef.current = null;
        setActiveQuickFilter(null);
        setFilters({});
        syncPresetToUrl(null);
        loadData({ page: 0, size: pagination.pageSize, filters: {} });
        return;
      }
      selectDefaultView();
      if (currentView && !isImplicitSavedView(currentView)) {
        clearSortOverrides();
        clearFilterOverrides();
        setPendingViewConfig(null);
        setActiveViewType('table');
        clearKeyword();
      }
      const qf = buildQuickFilterPreset(key, { userId: user?.id, now: new Date() }) ?? {};
      activeQuickFilterRef.current = key;
      setActiveQuickFilter(key);
      setFilters(qf);
      syncPresetToUrl(key);
      loadData({ page: 0, size: pagination.pageSize, filters: qf });
    },
    [
      clearKeyword,
      clearSortOverrides,
      clearFilterOverrides,
      currentView,
      loadData,
      pagination.pageSize,
      selectDefaultView,
      setFilters,
      syncPresetToUrl,
      user,
    ],
  );

  // Assemble the toolbar chip row: built-in filter presets + pinned views. A
  // pin only resolves to a chip if its SavedView is in the pool, so team views
  // (fetched separately from the personal-only list) are merged in — otherwise a
  // team-pinned view could never render as a chip.
  const quickFilterChips = useMemo<QuickFilterChip[]>(
    () =>
      assembleQuickFilterChips({
        presets: getQuickFilterPresetDefinitions(),
        t,
        savedViews: teamViews.length > 0 ? [...savedViews, ...teamViews] : savedViews,
        pins: chipPins,
      }),
    [t, savedViews, teamViews, chipPins],
  );

  // Select a SavedView: clear any active preset, switch the view, sync the URL
  // (?view=), and set the non-table view type. Shared by the header selector and
  // the toolbar view chips so both take the exact same path.
  const handleSelectView = useCallback(
    (pid: string) => {
      clearSortOverrides();
      clearFilterOverrides();
      activeQuickFilterRef.current = null;
      setActiveQuickFilter(null);
      selectView(pid);
      setSearchParams(
        (prev) => {
          const p = new URLSearchParams(prev);
          p.set('view', pid);
          clearTransientViewSearchParams(p, listSchemaFieldNameSet);
          return p;
        },
        { replace: true },
      );
      const view = savedViews.find((v) => v.pid === pid);
      if (view?.viewType && view.viewType !== 'table') {
        setActiveViewType(view.viewType as ViewType);
      }
    },
    [
      clearSortOverrides,
      clearFilterOverrides,
      selectView,
      setSearchParams,
      savedViews,
      setActiveViewType,
      setActiveQuickFilter,
    ],
  );

  // Activate a chip: a filter-preset chip toggles its preset; a view chip
  // switches to that SavedView (columns / sort / viewType).
  const handleActivateChip = useCallback(
    (chip: QuickFilterChip) => {
      if (chip.kind === 'view') {
        handleSelectView(chip.viewPid);
        return;
      }
      handleQuickFilter(chip.key);
    },
    [handleSelectView, handleQuickFilter],
  );

  const handleSaveActivePreset = useCallback(async () => {
    if (!activeQuickFilter) return;
    clearSortOverrides();
    clearFilterOverrides();

    const existingPresetView = findPersonalPresetSavedView(savedViews, activeQuickFilter);
    if (existingPresetView) {
      selectView(existingPresetView.pid);
      setActiveViewType((existingPresetView.viewType as ViewType) || 'table');
      activeQuickFilterRef.current = null;
      setActiveQuickFilter(null);
      setFilters({});
      rememberSubmittedFilters({});
      syncPresetToUrl(null);
      setSearchParams(
        (prev) => {
          const p = new URLSearchParams(prev);
          p.set('view', existingPresetView.pid);
          p.delete('preset');
          p.delete('sort');
          p.delete('keyword');
          p.delete('filters');
          return p;
        },
        { replace: true },
      );
      showSuccessToast(
        translateCommon('common.saved_view_preset_saved_to_personal', 'Saved as personal view'),
      );
      return;
    }

    const presetDefinition = getQuickFilterPresetDefinition(activeQuickFilter);
    if (!presetDefinition) return;
    const presetName = translateCommon(presetDefinition.i18nKey, presetDefinition.fallbackLabel);
    const request = buildQuickFilterPresetViewRequest(
      activeQuickFilter,
      { userId: user?.id, now: new Date() },
      { modelCode, pageKey, name: presetName },
    );
    if (!request) return;

    try {
      const view = await createView(request);
      activeQuickFilterRef.current = null;
      setActiveQuickFilter(null);
      setFilters({});
      rememberSubmittedFilters({});
      syncPresetToUrl(null);
      setSearchParams(
        (prev) => {
          const p = new URLSearchParams(prev);
          p.set('view', view.pid);
          p.delete('preset');
          p.delete('sort');
          p.delete('keyword');
          p.delete('filters');
          return p;
        },
        { replace: true },
      );
      showSuccessToast(
        translateCommon('common.saved_view_preset_saved_to_personal', 'Saved as personal view'),
      );
    } catch (err) {
      showErrorToast(
        err instanceof Error
          ? err.message
          : translateCommon('common.saved_view_preset_save_failed', 'Failed to save preset'),
      );
    }
  }, [
    activeQuickFilter,
    clearSortOverrides,
    clearFilterOverrides,
    createView,
    modelCode,
    rememberSubmittedFilters,
    pageKey,
    savedViews,
    selectView,
    setSearchParams,
    setFilters,
    showErrorToast,
    showSuccessToast,
    syncPresetToUrl,
    translateCommon,
    user?.id,
  ]);

  // Save current filters to SavedView
  const handleSaveFilters = useCallback(async () => {
    const formFilters = Object.entries(filters)
      .filter(([, v]) => v != null && v !== '')
      .map(([field, value]) => ({
        fieldCode: field,
        operator: 'eq' as const,
        value: String(value),
      }));
    await ensureViewAndUpdateConfig({ filters: [...formFilters, ...chipFilters] });
  }, [filters, chipFilters, ensureViewAndUpdateConfig]);

  return {
    quickFilterChips,
    handleSelectView,
    handleActivateChip,
    handleSaveActivePreset,
    handleSaveFilters,
  };
}
