import { useCallback, useEffect, useMemo, useState } from 'react';
import { useSavedViews } from '~/framework/smart/hooks/useSavedViews';
import {
  type SavedView,
  type ViewConfig,
  type ViewFilterConfig,
  type ViewType,
} from '~/framework/smart/types/savedView';
import {
  canCopySavedView,
  getSavedViewPersistenceMode,
  isSavedViewLockedPreset,
  mergeViewConfigPatch,
  summarizeViewConfigPatch,
} from '~/framework/smart/utils/savedViewPersistence';
import { savedViewService, type ChipPin } from '~/shared/services/savedViewService';
import type { User } from '~/utils/type';
import { resolveSavedViewFilterExpressions } from './listFilterConditions';

interface ListSavedViewStateInput {
  modelCode: any;
  pageKey: string;
  schema: any;
  hideSavedViews: boolean;
  skipListData: boolean;
  hideQuickFilters: boolean;
  hasPermission: (permissionCode: string) => boolean;
  user: User | null;
  activeViewType: ViewType;
  activeTab: string;
  filters: Record<string, any>;
  chipFilters: ViewFilterConfig[];
  t: (key: string) => string;
  showSuccessToast: (message: string, duration?: number) => void;
  showErrorToast: (message: string, duration?: number) => void;
}

/** Load saved views and derive the effective configuration and overlay permissions. */
export function useListSavedViewState({
  modelCode,
  pageKey,
  schema,
  hideSavedViews,
  skipListData,
  hideQuickFilters,
  hasPermission,
  user,
  activeViewType,
  activeTab,
  filters,
  chipFilters,
  t,
  showSuccessToast,
  showErrorToast,
}: ListSavedViewStateInput) {
  const {
    views: savedViews,
    accessibleViews,
    currentView,
    selectView,
    selectDefaultView,
    upsertView,
    createView,
    updateView,
    deleteView: deleteSavedView,
    setDefaultView,
    duplicateView,
    copyToPersonal,
    reload: reloadViews,
    loading: viewsLoading,
  } = useSavedViews({
    modelCode,
    pageKey,
    scopeFilter: 'all',
    autoLoad: !!schema && !hideSavedViews && !skipListData,
  });

  const availableViewTypes = useMemo<ViewType[]>(() => {
    const supported = new Set<ViewType>(['table']);
    for (const view of accessibleViews) {
      if (view.viewType === 'kanban') supported.add('kanban');
    }
    return ['table', 'kanban'].filter((type) => supported.has(type as ViewType)) as ViewType[];
  }, [accessibleViews]);

  // Quick-filter chip pins (Half B): the current user's pinned views for this
  // model/page, merged into the chip row alongside built-in presets + global pins.
  const [chipPins, setChipPins] = useState<ChipPin[]>([]);
  const loadChipPins = useCallback(async () => {
    if (!schema || hideQuickFilters || skipListData) {
      setChipPins([]);
      return;
    }
    try {
      setChipPins(await savedViewService.getChipPins({ modelCode, pageKey }));
    } catch {
      setChipPins([]);
    }
  }, [schema, hideQuickFilters, skipListData, modelCode, pageKey]);
  useEffect(() => {
    void loadChipPins();
  }, [loadChipPins]);

  // Team-scoped views the user may pin for their team. They are already visible
  // in the all-scope selector; this separate fetch is only for team pin authoring
  // and remains permission-gated.
  const canManageTeamPins =
    hasPermission('dashboard.saved_view.team.update') ||
    hasPermission('dashboard.saved_view.update');
  const [teamViews, setTeamViews] = useState<SavedView[]>([]);
  const loadTeamViews = useCallback(async () => {
    if (!schema || !canManageTeamPins || skipListData) {
      setTeamViews([]);
      return;
    }
    try {
      setTeamViews(await savedViewService.getTeamViews({ modelCode, pageKey }));
    } catch {
      setTeamViews([]);
    }
  }, [schema, canManageTeamPins, skipListData, modelCode, pageKey]);
  useEffect(() => {
    void loadTeamViews();
  }, [loadTeamViews]);

  const [pendingViewConfig, setPendingViewConfig] = useState<Partial<ViewConfig> | null>(null);
  const [savingViewDraft, setSavingViewDraft] = useState(false);
  const [copyingViewDraft, setCopyingViewDraft] = useState(false);
  const [repairingViewOverlay, setRepairingViewOverlay] = useState(false);
  const savedViewPersistenceMode = getSavedViewPersistenceMode(currentView);
  const isCurrentViewLockedPreset = isSavedViewLockedPreset(currentView);
  const canCopyCurrentView = canCopySavedView(currentView);
  const canSaveSharedView = useMemo(() => {
    if (!currentView || savedViewPersistenceMode !== 'shared-draft') {
      return false;
    }
    if (isCurrentViewLockedPreset) {
      return false;
    }
    if (Array.isArray(currentView.actions)) {
      return currentView.actions.includes('save');
    }
    if (currentView.createdBy && user?.pid && currentView.createdBy === user.pid) {
      return true;
    }
    if (currentView.scope === 'team') {
      return (
        hasPermission('dashboard.saved_view.team.update') ||
        hasPermission('dashboard.saved_view.update')
      );
    }
    if (currentView.scope === 'global') {
      return hasPermission('dashboard.saved_view.update');
    }
    return false;
  }, [currentView, hasPermission, isCurrentViewLockedPreset, savedViewPersistenceMode, user?.pid]);
  const hasPendingSharedViewConfig =
    savedViewPersistenceMode === 'shared-draft' && Object.keys(pendingViewConfig ?? {}).length > 0;
  const hasPendingPersonalViewConfig =
    savedViewPersistenceMode === 'personal-persist' &&
    Object.keys(pendingViewConfig ?? {}).length > 0;
  const hasPendingViewConfig = hasPendingPersonalViewConfig || hasPendingSharedViewConfig;
  const effectiveViewConfig = useMemo(
    () =>
      currentView
        ? mergeViewConfigPatch(currentView.viewConfig, pendingViewConfig ?? {})
        : undefined,
    [currentView, pendingViewConfig],
  );
  const activeViewTemplate = useMemo(
    () =>
      accessibleViews.find((view) => view.viewType === activeViewType && view.isDefault) ??
      accessibleViews.find((view) => view.viewType === activeViewType) ??
      null,
    [accessibleViews, activeViewType],
  );
  const activeTabViewFilter = useMemo<ViewFilterConfig | null>(() => {
    const tabsBlock = schema?.blocks?.find((block: any) => block.blockType === 'tabs');
    const tab = (tabsBlock?.tabs as any[] | undefined)?.find(
      (candidate) => candidate.key === activeTab,
    );
    if (!tab?.filter) return null;
    const fieldCode = tab.filter.fieldName || tab.filter.field;
    if (!fieldCode) return null;
    return {
      fieldCode,
      operator: String(tab.filter.operator || 'EQ').toLowerCase() as ViewFilterConfig['operator'],
      value: tab.filter.value,
    };
  }, [activeTab, schema?.blocks]);
  const resolvedEffectiveViewFilters = useMemo(
    () =>
      resolveSavedViewFilterExpressions(effectiveViewConfig?.filters, {
        currentUserPid: user?.pid,
      }),
    [effectiveViewConfig?.filters, user?.pid],
  );
  const activeRuntimeViewFilters = useMemo<ViewFilterConfig[]>(() => {
    const merged = [
      ...resolvedEffectiveViewFilters,
      ...Object.entries(filters)
        .filter(([, value]) => value != null && value !== '')
        .map(([fieldCode, value]) => ({
          fieldCode,
          operator: 'eq' as const,
          value,
        })),
      ...chipFilters,
      ...(activeTabViewFilter ? [activeTabViewFilter] : []),
    ];
    const unique = new Map<string, ViewFilterConfig>();
    for (const filter of merged) {
      unique.set(`${filter.fieldCode}:${filter.operator}:${JSON.stringify(filter.value)}`, filter);
    }
    return Array.from(unique.values());
  }, [activeTabViewFilter, chipFilters, filters, resolvedEffectiveViewFilters]);
  const effectiveNonNullViewConfig = useMemo<Partial<ViewConfig>>(
    () =>
      Object.fromEntries(
        Object.entries(effectiveViewConfig ?? {}).filter(
          ([, value]) => value !== null && value !== undefined,
        ),
      ),
    [effectiveViewConfig],
  );
  const activeViewConfig = useMemo<ViewConfig>(
    () => ({
      ...(activeViewTemplate?.viewConfig ?? {}),
      ...effectiveNonNullViewConfig,
      filters: activeRuntimeViewFilters,
    }),
    [activeRuntimeViewFilters, activeViewTemplate?.viewConfig, effectiveNonNullViewConfig],
  );
  const pendingViewSummary = useMemo(
    () => summarizeViewConfigPatch(pendingViewConfig),
    [pendingViewConfig],
  );
  const translateCommon = useCallback(
    (key: string, fallback: string) => {
      const value = t(key);
      return value && value !== key ? value : fallback;
    },
    [t],
  );
  const overlayMeta = currentView?.viewConfig?.meta;
  const overlayCanWrite =
    Boolean(currentView) &&
    (savedViewPersistenceMode !== 'shared-draft' || canSaveSharedView) &&
    !isCurrentViewLockedPreset;
  const canRepairViewOverlay = overlayCanWrite && !hasPendingViewConfig;
  const repairViewOverlayUnavailableReason = hasPendingViewConfig
    ? translateCommon(
        'common.saved_view_overlay_pending_draft',
        '请先保存或放弃当前视图的本地变更。',
      )
    : undefined;

  const handleRepairViewOverlay = useCallback(async () => {
    if (!currentView?.viewConfig || !canRepairViewOverlay) return;

    setRepairingViewOverlay(true);
    try {
      await updateView({ viewConfig: currentView.viewConfig });
      showSuccessToast(
        translateCommon(
          'common.saved_view_overlay_repair_success',
          '失效设置已清理，个人视图已适配当前页面。',
        ),
      );
    } catch (err) {
      showErrorToast(
        err instanceof Error
          ? err.message
          : translateCommon('common.saved_view_overlay_repair_failed', '个人视图修复失败'),
      );
    } finally {
      setRepairingViewOverlay(false);
    }
  }, [
    canRepairViewOverlay,
    currentView,
    showErrorToast,
    showSuccessToast,
    translateCommon,
    updateView,
  ]);
  useEffect(() => {
    setPendingViewConfig(null);
  }, [currentView?.pid]);

  return {
    savedViews,
    accessibleViews,
    currentView,
    selectView,
    selectDefaultView,
    upsertView,
    createView,
    updateView,
    deleteSavedView,
    setDefaultView,
    duplicateView,
    copyToPersonal,
    reloadViews,
    viewsLoading,
    availableViewTypes,
    chipPins,
    loadChipPins,
    canManageTeamPins,
    teamViews,
    pendingViewConfig,
    setPendingViewConfig,
    savingViewDraft,
    setSavingViewDraft,
    copyingViewDraft,
    setCopyingViewDraft,
    repairingViewOverlay,
    isCurrentViewLockedPreset,
    canCopyCurrentView,
    canSaveSharedView,
    hasPendingSharedViewConfig,
    hasPendingPersonalViewConfig,
    hasPendingViewConfig,
    effectiveViewConfig,
    activeViewTemplate,
    activeRuntimeViewFilters,
    activeViewConfig,
    pendingViewSummary,
    translateCommon,
    overlayMeta,
    canRepairViewOverlay,
    repairViewOverlayUnavailableReason,
    handleRepairViewOverlay,
  };
}
