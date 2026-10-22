import { useRuntimeStateSubscription } from '../../blocks/workbenchBlockUtils';
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  type SetURLSearchParams,
  type NavigateFunction,
  type NavigateOptions,
  type To,
} from 'react-router';

import { useTimezone } from '~/contexts/TimezoneContext';
import { usePageRuntime } from '~/framework/meta/rendering/pages/hooks/usePageRuntime';
import type { ColumnConfig } from '~/framework/meta/schemas/types';
import { canUseImport } from '~/framework/smart/components/data-tools/importCapability';
import { type ViewType } from '~/framework/smart/types/savedView';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { type BulkActionResult } from './BulkActionResultDialog';
import type { InviteCodeData, ListLoadDataParams, QuickFilterKey } from './listPageStateUtils';
import { resolveInitialListTabKey } from './listSchemaPolicy';
import { resolveListSavedViewPageKey, type ListSortFilterQuery } from './listViewConfig';

interface ListPageRuntimeInput {
  schema: any;
  token: string | null | undefined;
  filters: Record<string, any>;
  tableBlock: any;
  listUrlWritesEnabledRef: React.RefObject<boolean>;
  searchParams: URLSearchParams;
  tableName: string;
  urlViewPid: string | null;
  setSearchParams: SetURLSearchParams;
  schemaExtension: any;
  hasPermission: (permissionCode: string) => boolean;
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
  skipListData: boolean;
}

/** Bind the page runtime, navigation, dialog state and page capabilities. */
export function useListPageRuntime({
  schema,
  token,
  filters,
  tableBlock,
  listUrlWritesEnabledRef,
  searchParams,
  tableName,
  urlViewPid,
  setSearchParams,
  schemaExtension,
  hasPermission,
  listExtensions,
  skipListData,
}: ListPageRuntimeInput) {
  // Flow refreshes must use the current loader after list filters change.
  const loadDataRef = useRef<((params?: ListLoadDataParams) => Promise<void>) | null>(null);
  const reloadListStrict = useCallback(async (params?: ListLoadDataParams) => {
    const loader = loadDataRef.current;
    if (!loader) throw new Error('List page loader is unavailable');
    await loader(params);
  }, []);
  const listRuntimeContext = useMemo(() => ({ filters }), [filters]);
  // Use usePageRuntime instead of useDynamicPageSetup
  const { runtime, dataSourceManager, t, locale, navigate } = usePageRuntime(schema, {
    token: token || undefined,
    additionalContext: listRuntimeContext,
    loadData: reloadListStrict,
  });
  useRuntimeStateSubscription(runtime);
  const tableEmptyState = useMemo(() => {
    const configured = (tableBlock as any)?.empty ?? (tableBlock as any)?.table?.empty;
    if (!configured || typeof configured !== 'object') return {};
    return {
      title: configured.title ? getLocalizedText(configured.title, locale, t) : undefined,
      description: configured.description
        ? getLocalizedText(configured.description, locale, t)
        : undefined,
    };
  }, [locale, t, tableBlock]);
  const tableSearchPlaceholder = useMemo(() => {
    const configured =
      (tableBlock as any)?.searchPlaceholder ?? (tableBlock as any)?.table?.searchPlaceholder;
    return configured ? getLocalizedText(configured, locale, t) : undefined;
  }, [locale, t, tableBlock]);
  const navigateAwayFromList = useCallback(
    ((toOrDelta: To | number, options?: NavigateOptions) => {
      // List URL state effects (SavedView sorts, filters and pagination) can still be queued
      // when a user clicks Create/View/Edit. Once an outgoing navigation starts, those stale
      // effects must never replace the destination route with the list's query-string URL.
      listUrlWritesEnabledRef.current = false;
      return typeof toOrDelta === 'number' ? navigate(toOrDelta) : navigate(toOrDelta, options);
    }) as NavigateFunction,
    [navigate],
  );

  const appendListSearch = useCallback(
    (path: string) => {
      const query = searchParams.toString();
      if (!query) return path;
      return `${path}${path.includes('?') ? '&' : '?'}${query}`;
    },
    [searchParams],
  );

  const navigateToRecordView = useCallback(
    (recordPid: string | number | null | undefined) => {
      if (recordPid == null || recordPid === '') {
        return;
      }
      navigateAwayFromList(appendListSearch(`/p/${tableName}/view/${String(recordPid)}`));
    },
    [appendListSearch, navigateAwayFromList, tableName],
  );

  // Tab state for tabs
  const [activeTab, setActiveTab] = useState(() => resolveInitialListTabKey(schema?.blocks));
  const [importOpen, setImportOpen] = useState(false);
  const [viewManageOpen, setViewManageOpen] = useState(false);
  const [columnSettingsOpen, setColumnSettingsOpen] = useState(false);
  const [contextMenu, setContextMenu] = useState<{
    x: number;
    y: number;
    column: ColumnConfig;
  } | null>(null);
  const [activeQuickFilter, setActiveQuickFilter] = useState<QuickFilterKey | null>(null);
  const activeQuickFilterRef = useRef<QuickFilterKey | null>(null);

  useEffect(() => {
    activeQuickFilterRef.current = activeQuickFilter;
  }, [activeQuickFilter]);

  useEffect(() => {
    if (!urlViewPid || !searchParams.has('preset')) return;
    setActiveQuickFilter(null);
    setSearchParams(
      (prev) => {
        const p = new URLSearchParams(prev);
        p.delete('preset');
        return p;
      },
      { replace: true },
    );
  }, [searchParams, setSearchParams, urlViewPid]);

  const [filtersExpanded, setFiltersExpanded] = useState(false);
  const [filterFormVisible, setFilterFormVisible] = useState(false);
  const [activeViewType, setActiveViewType] = useState<ViewType>('table');
  const [startCreateViewMode, setStartCreateViewMode] = useState(false);
  const [inviteDialogOpen, setInviteDialogOpen] = useState(false);
  const inviteTriggerRef = useRef<HTMLElement | null>(null);
  const [inviteLoading, setInviteLoading] = useState(false);
  const [inviteReadLoading, setInviteReadLoading] = useState(false);
  const [inviteReadError, setInviteReadError] = useState<string | null>(null);
  const inviteReadSeqRef = useRef(0);
  const inviteContextRef = useRef({ allowed: false, open: false });
  const [inviteCodeData, setInviteCodeData] = useState<InviteCodeData | null>(null);
  const [memberImportDialogOpen, setMemberImportDialogOpen] = useState(false);
  const [bulkActionResult, setBulkActionResult] = useState<BulkActionResult | null>(null);
  const { formats: dateTimeFormats, timezone: effectiveTimezone } = useTimezone();
  const pendingSavedViewFiltersRef = useRef<Record<string, any> | null>(null);
  // When restoring a preset view from ?preset= on mount, skip the first run of
  // the debounced sort/filter effect so it doesn't re-fetch with empty filters.
  const skipFirstSortFilterEffectRef = useRef(false);
  const lastScheduledSortFilterValuesRef = useRef<ListSortFilterQuery | undefined>(undefined);
  // Monotonic sequence for loadData invocations. Concurrent triggers (debounced
  // keyword auto-search, URL-sync effect, Enter commit, pagination) can overlap;
  // responses must apply only when their request is still the newest one, or a
  // stale unfiltered response can land after a filtered one and overwrite the
  // table (PD-012).
  const loadDataSeqRef = useRef(0);

  // Record preview drawer state
  const [previewRecordId, setPreviewRecordId] = useState<string | null>(null);

  // SavedView integration
  const modelCode = schema?.modelCode || tableName;
  const importConfig = schemaExtension.import as
    | import('~/framework/smart/components/data-tools/ImportModal').ImportConfiguration
    | undefined;
  const canImport = canUseImport(importConfig, hasPermission);
  const pageKey = resolveListSavedViewPageKey(schema, tableName);
  const isTenantMemberPage = modelCode === 'tenant_member' || pageKey === 'tenant_member';
  const canManageMemberAccounts = isTenantMemberPage && hasPermission('org.role.update');
  const canInviteMembers = isTenantMemberPage && hasPermission('org.tenant.invite.manage');
  inviteContextRef.current = { allowed: canInviteMembers, open: inviteDialogOpen };
  const hideSavedViews =
    listExtensions?.hideSavedViews ?? Boolean(schemaExtension.hideSavedViews || skipListData);
  // Quick filters live only in the toolbar. They apply to default view mode and
  // are not mirrored as saved-view selector entries.
  const hideQuickFilters =
    listExtensions?.hideQuickFilters ?? Boolean(schemaExtension.hideQuickFilters);

  return {
    runtime,
    dataSourceManager,
    t,
    locale,
    navigate,
    tableEmptyState,
    tableSearchPlaceholder,
    navigateAwayFromList,
    appendListSearch,
    navigateToRecordView,
    activeTab,
    setActiveTab,
    importOpen,
    setImportOpen,
    viewManageOpen,
    setViewManageOpen,
    columnSettingsOpen,
    setColumnSettingsOpen,
    contextMenu,
    setContextMenu,
    activeQuickFilter,
    setActiveQuickFilter,
    activeQuickFilterRef,
    filtersExpanded,
    setFiltersExpanded,
    filterFormVisible,
    setFilterFormVisible,
    activeViewType,
    setActiveViewType,
    startCreateViewMode,
    setStartCreateViewMode,
    inviteDialogOpen,
    setInviteDialogOpen,
    inviteTriggerRef,
    inviteLoading,
    setInviteLoading,
    inviteReadLoading,
    setInviteReadLoading,
    inviteReadError,
    setInviteReadError,
    inviteReadSeqRef,
    inviteContextRef,
    inviteCodeData,
    setInviteCodeData,
    memberImportDialogOpen,
    setMemberImportDialogOpen,
    bulkActionResult,
    setBulkActionResult,
    dateTimeFormats,
    effectiveTimezone,
    pendingSavedViewFiltersRef,
    skipFirstSortFilterEffectRef,
    lastScheduledSortFilterValuesRef,
    loadDataRef,
    loadDataSeqRef,
    previewRecordId,
    setPreviewRecordId,
    modelCode,
    importConfig,
    canImport,
    pageKey,
    isTenantMemberPage,
    canManageMemberAccounts,
    canInviteMembers,
    hideSavedViews,
    hideQuickFilters,
  };
}
