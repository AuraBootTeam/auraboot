import {
  type SavedViewUpdateRequest,
  type SavedViewCopyToPersonalRequest,
  type SavedView,
  type SavedViewCreateRequest,
  type ViewConfig,
  type ViewFilterConfig,
  type ViewType,
} from '~/framework/smart/types/savedView';
import React, { useCallback, useEffect, useRef } from 'react';
import { type SetURLSearchParams, type NavigateFunction } from 'react-router';

import type { ColumnConfig } from '~/framework/meta/schemas/types';
import {
  buildColumnSettingsRows,
  serializeColumnSettings,
  type ColumnSettingsSavePayload,
} from '~/framework/smart/components/view/ColumnSettingsPanel';
import { useAutoSaveView } from '~/framework/smart/hooks/useAutoSaveView';

import {
  buildConditionalStyle,
  evaluateConditionalFormats,
} from '~/framework/smart/utils/conditionalFormatEvaluator';
import {
  buildPersonalCopyName,
  getSavedViewPersistenceMode,
  mergeViewConfigPatch,
} from '~/framework/smart/utils/savedViewPersistence';
import { useTransientFlag } from '~/hooks/useTransientFlag';
import { savedViewService } from '~/shared/services/savedViewService';
import { confirmDialog } from '~/utils/confirmDialog';
import { useListFieldOptions } from './listColumnModel';
import { type ListLoadDataParams, clearTransientViewSearchParams } from './listPageStateUtils';

import { pruneNoopViewConfigPatch } from './listViewConfig';
import { resolveListRowClickMode } from './rowClickNavigation';

interface ListViewPersistenceInput {
  effectiveViewConfig: ViewConfig | undefined;
  listExtensions:
    | {
        onDataChange?: (records: Record<string, any>[]) => void;
        disableRowClick?: boolean;
        disableRowSelection?: boolean;
        hideBuiltInImport?: boolean;
        hideBuiltInExport?: boolean;
        hideBuiltInPrint?: boolean;
        hideSavedViews?: boolean;
        hideQuickFilters?: boolean;
        hideSort?: boolean;
        hideColumnSettings?: boolean;
        hideRowHeight?: boolean;
        hideFilterChips?: boolean;
        reloadEventName?: string;
      }
    | undefined;
  schema: any;
  tableBlock: any;
  navigateAwayFromList: NavigateFunction;
  appendListSearch: (path: string) => string;
  tableName: string;
  setPreviewRecordId: React.Dispatch<React.SetStateAction<string | null>>;
  tableColumns: ColumnConfig[];
  modelFieldMap: Map<string, any>;
  SYSTEM_FIELD_DEFS: ColumnConfig[];
  resolveColumnLabel: (column: ColumnConfig) => string;
  chipFilters: ViewFilterConfig[];
  chipFiltersRef: React.RefObject<ViewFilterConfig[]>;
  setLocalChipFilters: (
    update: ViewFilterConfig[] | ((previous: ViewFilterConfig[]) => ViewFilterConfig[]),
  ) => void;
  loadData: (params?: ListLoadDataParams) => Promise<void>;
  pagination: { current: number; pageSize: number; total: number };
  filters: Record<string, any>;
  setAnalysisOpen: React.Dispatch<React.SetStateAction<boolean>>;
  currentView: SavedView | null;
  setPendingViewConfig: React.Dispatch<React.SetStateAction<Partial<ViewConfig> | null>>;
  modelCode: any;
  pageKey: string;
  upsertView: (view: SavedView) => void;
  hasPendingViewConfig: boolean;
  pendingViewConfig: Partial<ViewConfig> | null;
  setSavingViewDraft: React.Dispatch<React.SetStateAction<boolean>>;
  updateView: (request: SavedViewUpdateRequest) => Promise<SavedView>;
  showSuccessToast: (message: string, duration?: number) => void;
  translateCommon: (key: string, fallback: string) => string;
  showErrorToast: (message: string, duration?: number) => void;
  applyViewConfigToListState: (viewConfig: ViewConfig | undefined) => Record<string, any>;
  setActiveQuickFilter: React.Dispatch<React.SetStateAction<string | null>>;
  clearKeyword: () => void;
  setSearchParams: SetURLSearchParams;
  listSchemaFieldNameSet: Set<string>;
  canCopyCurrentView: boolean;
  setCopyingViewDraft: React.Dispatch<React.SetStateAction<boolean>>;
  createView: (request: SavedViewCreateRequest) => Promise<SavedView>;
  activeViewType: ViewType;
  copyToPersonal: (pid: string, request?: SavedViewCopyToPersonalRequest) => Promise<SavedView>;
  hasPendingSharedViewConfig: boolean;
  pendingViewSummary: string[];
  setColumnOrder: React.Dispatch<React.SetStateAction<string[]>>;
}

/** Manage row navigation, view drafts, auto-save and column updates. */
export function useListViewPersistence({
  effectiveViewConfig,
  listExtensions,
  schema,
  tableBlock,
  navigateAwayFromList,
  appendListSearch,
  tableName,
  setPreviewRecordId,
  tableColumns,
  modelFieldMap,
  SYSTEM_FIELD_DEFS,
  resolveColumnLabel,
  chipFilters,
  chipFiltersRef,
  setLocalChipFilters,
  loadData,
  pagination,
  filters,
  setAnalysisOpen,
  currentView,
  setPendingViewConfig,
  modelCode,
  pageKey,
  upsertView,
  hasPendingViewConfig,
  pendingViewConfig,
  setSavingViewDraft,
  updateView,
  showSuccessToast,
  translateCommon,
  showErrorToast,
  applyViewConfigToListState,
  setActiveQuickFilter,
  clearKeyword,
  setSearchParams,
  listSchemaFieldNameSet,
  canCopyCurrentView,
  setCopyingViewDraft,
  createView,
  activeViewType,
  copyToPersonal,
  hasPendingSharedViewConfig,
  pendingViewSummary,
  setColumnOrder,
}: ListViewPersistenceInput) {
  // Row style from conditional formats
  const getRowStyle = useCallback(
    (record: Record<string, any>): React.CSSProperties | undefined => {
      const cfStyle = evaluateConditionalFormats(effectiveViewConfig?.conditionalFormats, record);
      return buildConditionalStyle(cfStyle);
    },
    [effectiveViewConfig?.conditionalFormats],
  );

  // Row click → navigate to detail page or open preview drawer
  const handleRowClick = useCallback(
    (record: Record<string, unknown>) => {
      if (listExtensions?.disableRowClick) {
        return;
      }

      const rowClickMode = resolveListRowClickMode({
        schemaDetailNavigation: (schema as any)?.options?.detailNavigation,
        tableOnRowClick: (tableBlock as any)?.onRowClick,
        tableRowClickAction: (tableBlock as any)?.props?.rowClickAction,
      });

      if (rowClickMode === 'none') {
        return;
      }

      // A configured detailUrl template ({field} placeholders) is resolved BEFORE the pid guard
      // so it works for records that have no pid (e.g. external-REST / aggregate list rows keyed
      // by a numeric id). Pages without a detailUrl keep requiring a pid (unchanged behavior).
      const detailUrl = (schema as any)?.options?.detailUrl || (tableBlock as any)?.detailUrl;
      if (rowClickMode === 'detail' && detailUrl) {
        const resolved = detailUrl.replace(/\{(\w+)\}/g, (_: string, key: string) =>
          String(record[key] ?? ''),
        );
        navigateAwayFromList(appendListSearch(resolved));
        return;
      }

      const pid = record.pid as string | undefined;
      if (!pid) return;

      if (rowClickMode === 'detail') {
        // Resolve detail page key: check extension.relatedPages.detail first, then options.detailPageKey,
        // then fall back to the {tableName}/view/{pid} convention (which derives {tableName}_detail).
        const relatedDetailPageKey = (schema as any)?.extension?.relatedPages?.detail;
        const optionDetailPageKey = (schema as any)?.options?.detailPageKey;
        const resolvedDetailPageKey = relatedDetailPageKey || optionDetailPageKey;
        if (resolvedDetailPageKey) {
          navigateAwayFromList(appendListSearch(`/p/${resolvedDetailPageKey}/view/${pid}`));
        } else {
          navigateAwayFromList(appendListSearch(`/p/${tableName}/view/${pid}`));
        }
        return;
      }

      setPreviewRecordId(pid);
    },
    [
      appendListSearch,
      schema,
      tableBlock,
      tableName,
      navigateAwayFromList,
      listExtensions?.disableRowClick,
    ],
  );

  const { allColumnDefs, viewManageFields, filterFieldMetadata } = useListFieldOptions({
    tableBlock,
    tableColumns,
    modelFieldMap,
    SYSTEM_FIELD_DEFS,
    resolveColumnLabel,
  });

  const handleAnalysisDrillDown = useCallback(
    (drillFilters: import('~/framework/smart/types/chart').FilterConfig[]) => {
      const next = drillFilters.map<ViewFilterConfig>((filter) => ({
        fieldCode: filter.field,
        operator: 'eq',
        value: filter.value,
      }));
      const fields = new Set(next.map((filter) => filter.fieldCode));
      const nextChipFilters = [
        ...chipFilters.filter((filter) => !fields.has(filter.fieldCode)),
        ...next,
      ];
      chipFiltersRef.current = nextChipFilters;
      setLocalChipFilters(nextChipFilters);
      void loadData({
        page: 0,
        size: pagination.pageSize,
        filters,
        chipFilters: nextChipFilters,
      });
      setAnalysisOpen(false);
    },
    [chipFilters, filters, loadData, pagination.pageSize, setLocalChipFilters],
  );

  // Personal-only baseline: changes to an explicit personal view are staged as
  // a visible local draft. The user chooses save-current, save-as-new, or discard.
  const [viewSavedHintOn, flashViewSavedHint] = useTransientFlag(2200);

  const ensureViewAndUpdateConfig = useCallback(
    async (
      config: Partial<import('~/framework/smart/types/savedView').ViewConfig>,
      options?: { isStale?: () => boolean; rethrow?: boolean },
    ) => {
      try {
        const persistenceMode = getSavedViewPersistenceMode(currentView);
        if (persistenceMode === 'personal-persist' || persistenceMode === 'shared-draft') {
          setPendingViewConfig((prev) =>
            pruneNoopViewConfigPatch(currentView?.viewConfig, mergeViewConfigPatch(prev, config)),
          );
        } else {
          // No explicit view — use backend auto-save (atomic upsert of implicit view)
          const view = await savedViewService.autoSave({
            modelCode,
            pageKey,
            viewConfig: config,
          });
          // Apply the returned implicit view immediately; reloading should not be required.
          if (view) {
            upsertView(view);
          }
        }
        if (!options?.isStale?.() && persistenceMode === 'implicit-autosave') {
          flashViewSavedHint();
        }
      } catch (err) {
        if (options?.isStale?.()) {
          return;
        }
        console.error('[ListPageContent] Failed to save view config:', err);
        if (options?.rethrow) {
          throw err;
        }
      }
    },
    [currentView, modelCode, pageKey, upsertView, flashViewSavedHint],
  );

  const handleSaveCurrentViewDraft = useCallback(async () => {
    if (!currentView || !hasPendingViewConfig || !pendingViewConfig) return;

    setSavingViewDraft(true);
    try {
      await updateView({
        viewConfig: mergeViewConfigPatch(currentView.viewConfig, pendingViewConfig),
      });
      setPendingViewConfig(null);
      flashViewSavedHint();
      showSuccessToast(translateCommon('common.saved_view_current_saved', 'Current view saved'));
    } catch (err) {
      showErrorToast(
        err instanceof Error
          ? err.message
          : translateCommon('common.saved_view_shared_save_failed', 'Failed to save view'),
      );
    } finally {
      setSavingViewDraft(false);
    }
  }, [
    currentView,
    flashViewSavedHint,
    hasPendingViewConfig,
    pendingViewConfig,
    showErrorToast,
    showSuccessToast,
    translateCommon,
    updateView,
  ]);

  const handleDiscardViewDraft = useCallback(() => {
    if (!currentView) {
      setPendingViewConfig(null);
      return;
    }

    const restoredFilters = applyViewConfigToListState(currentView.viewConfig);
    const restoredSorts = currentView.viewConfig?.sorts ?? [];
    setPendingViewConfig(null);
    setActiveQuickFilter(null);
    clearKeyword();
    setSearchParams(
      (prev) => {
        const p = new URLSearchParams(prev);
        p.set('view', currentView.pid);
        clearTransientViewSearchParams(p, listSchemaFieldNameSet);
        return p;
      },
      { replace: true },
    );
    loadData({
      page: 0,
      size: currentView.viewConfig?.pagination?.pageSize ?? pagination.pageSize,
      filters: restoredFilters,
      sorts: restoredSorts,
    });
  }, [
    applyViewConfigToListState,
    clearKeyword,
    currentView,
    loadData,
    pagination.pageSize,
    setSearchParams,
  ]);

  const handleSaveDraftAsPersonalView = useCallback(async () => {
    if (!currentView || !hasPendingViewConfig || !pendingViewConfig) return;
    if (!canCopyCurrentView) {
      showErrorToast(translateCommon('common.saved_view_copy_disabled_reason', '当前视图不能复制'));
      return;
    }
    setCopyingViewDraft(true);
    try {
      const mergedConfig = mergeViewConfigPatch(currentView.viewConfig, pendingViewConfig);
      const copiedView =
        currentView.scope === 'personal'
          ? await createView({
              name: buildPersonalCopyName(currentView.name),
              modelCode,
              pageKey,
              scope: 'personal',
              viewType: currentView.viewType ?? activeViewType ?? 'table',
              viewConfig: mergedConfig,
            })
          : await copyToPersonal(currentView.pid, {
              name: buildPersonalCopyName(currentView.name),
              viewConfig: mergedConfig,
            });
      setPendingViewConfig(null);
      setSearchParams(
        (prev) => {
          const p = new URLSearchParams(prev);
          p.set('view', copiedView.pid);
          p.delete('sort');
          p.delete('keyword');
          p.delete('filters');
          return p;
        },
        { replace: true },
      );
      showSuccessToast(translateCommon('common.saved_view_copied_to_personal', '已另存为个人视图'));
    } catch (err) {
      showErrorToast(
        err instanceof Error
          ? err.message
          : translateCommon('common.saved_view_copy_failed', '复制视图失败'),
      );
    } finally {
      setCopyingViewDraft(false);
    }
  }, [
    activeViewType,
    canCopyCurrentView,
    copyToPersonal,
    createView,
    currentView,
    hasPendingViewConfig,
    modelCode,
    pageKey,
    pendingViewConfig,
    setSearchParams,
    showErrorToast,
    showSuccessToast,
    translateCommon,
  ]);

  const handleSaveSharedDraft = useCallback(async () => {
    if (!currentView || !hasPendingSharedViewConfig || !pendingViewConfig) return;

    const targetName =
      currentView.scope === 'team'
        ? currentView.teamName || translateCommon('common.saved_view_scope_team', 'Team')
        : translateCommon('common.saved_view_scope_global', 'Global');
    const summaryText =
      pendingViewSummary.length > 0
        ? pendingViewSummary.join(', ')
        : translateCommon('common.saved_view_shared_changes', 'view configuration');
    const confirmed = await confirmDialog({
      title: translateCommon('common.saved_view_save_shared_confirm_title', '保存到共享视图？'),
      content: translateCommon(
        'common.saved_view_save_shared_confirm_content',
        `This will update the shared view for ${targetName}. Changes: ${summaryText}.`,
      )
        .replace('{target}', targetName)
        .replace('{changes}', summaryText),
      confirmText: translateCommon('common.saved_view_save_shared', 'Save Shared View'),
      cancelText: translateCommon('common.cancel', '取消'),
    });
    if (!confirmed) return;

    setSavingViewDraft(true);
    try {
      await updateView({
        viewConfig: mergeViewConfigPatch(currentView.viewConfig, pendingViewConfig),
      });
      setPendingViewConfig(null);
      flashViewSavedHint();
      showSuccessToast(translateCommon('common.saved_view_shared_saved', 'Shared view updated'));
    } catch (err) {
      showErrorToast(
        err instanceof Error
          ? err.message
          : translateCommon('common.saved_view_shared_save_failed', 'Failed to save shared view'),
      );
    } finally {
      setSavingViewDraft(false);
    }
  }, [
    currentView,
    flashViewSavedHint,
    hasPendingSharedViewConfig,
    pendingViewConfig,
    pendingViewSummary,
    showErrorToast,
    showSuccessToast,
    translateCommon,
    updateView,
  ]);

  const autoSaveMountedRef = useRef(true);
  useEffect(() => {
    autoSaveMountedRef.current = true;
    return () => {
      autoSaveMountedRef.current = false;
    };
  }, []);

  const { autoSave } = useAutoSaveView({
    currentView,
    updateViewConfig: async (config) => {
      await ensureViewAndUpdateConfig(config, {
        isStale: () => !autoSaveMountedRef.current,
        rethrow: true,
      });
      return currentView!;
    },
  });

  const buildCurrentColumnSettings = useCallback(
    () =>
      serializeColumnSettings(buildColumnSettingsRows(allColumnDefs, effectiveViewConfig?.columns)),
    [allColumnDefs, effectiveViewConfig?.columns],
  );

  // Handle column reorder via drag-and-drop
  const handleColumnReorder = useCallback(
    (newOrder: string[]) => {
      setColumnOrder(newOrder);
      const existing = buildCurrentColumnSettings();
      const byField = new Map(existing.map((column) => [column.fieldCode, column]));
      const reordered = newOrder.map((fieldCode, order) => ({
        ...(byField.get(fieldCode) || { fieldCode }),
        fieldCode,
        order,
      }));
      const trailing = existing
        .filter((column) => !newOrder.includes(column.fieldCode))
        .map((column, index) => ({ ...column, order: newOrder.length + index }));
      autoSave({ columns: [...reordered, ...trailing] });
    },
    [autoSave, buildCurrentColumnSettings],
  );

  // Handle column resize
  const handleColumnResize = useCallback(
    (field: string, width: number) => {
      const cols = buildCurrentColumnSettings();
      const idx = cols.findIndex((c) => c.fieldCode === field);
      if (idx >= 0) {
        cols[idx] = { ...cols[idx], width };
      } else {
        cols.push({ fieldCode: field, width });
      }
      autoSave({ columns: cols });
    },
    [autoSave, buildCurrentColumnSettings],
  );

  // Handle column settings save -> update SavedView
  const handleColumnSettingsSave = useCallback(
    async ({ columns, rowHeight }: ColumnSettingsSavePayload) => {
      await ensureViewAndUpdateConfig({ columns, rowHeight }, { rethrow: true });
    },
    [ensureViewAndUpdateConfig],
  );

  // Handle toolbar action config change -> update SavedView
  const handleToolbarConfigChange = useCallback(
    (config: import('~/framework/smart/types/savedView').ToolbarActionConfig[]) => {
      autoSave({ toolbarActions: config });
    },
    [autoSave],
  );

  return {
    getRowStyle,
    handleRowClick,
    allColumnDefs,
    viewManageFields,
    filterFieldMetadata,
    handleAnalysisDrillDown,
    viewSavedHintOn,
    ensureViewAndUpdateConfig,
    handleSaveCurrentViewDraft,
    handleDiscardViewDraft,
    handleSaveDraftAsPersonalView,
    handleSaveSharedDraft,
    autoSave,
    buildCurrentColumnSettings,
    handleColumnReorder,
    handleColumnResize,
    handleColumnSettingsSave,
    handleToolbarConfigChange,
  };
}
