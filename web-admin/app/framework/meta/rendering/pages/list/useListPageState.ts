import { type PageContentProps } from '@auraboot/runtime-kernel';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useLocation, useNavigationType, useSearchParams } from 'react-router';
import { useAuth } from '~/contexts/AuthContext';
import { useToastContext } from '~/contexts/ToastContext';
import type { ButtonConfig, TableConfig } from '~/framework/meta/schemas/types';
import { getPublicRecordKey } from '~/framework/meta/utils/publicRecordId';
import { type SortConfig, type ViewFilterConfig } from '~/framework/smart/types/savedView';
import { useDebouncedCallback } from '~/hooks/useDebouncedValue';
import { resolveAuditUserDisplayFields } from './auditUserDisplayFields';
import { type BuiltInBulkCapabilitiesConfig } from './bulkCapabilities';
import { collectListBareParamFilters, collectListSchemaFieldNames } from './listFilterConditions';
import {
  type BulkFieldCommandState,
  type ListLoadDataParams,
  type QuickFilterKey,
  applyLocalSortUpdate,
  useSerializedSearchParamsUpdater,
  useListApiReadRetry,
  useListKeywordSearch,
} from './listPageStateUtils';

import type { DynamicEntity } from './listPageTypes';
import {
  collectListMiscBlocks,
  resolveListMiscBlocksPosition,
  shouldSkipListData,
  shouldSkipModelFieldMeta,
} from './listSchemaPolicy';
import { resolveUrlStateSyncAction } from './listViewConfig';
import { isQuickFilterPresetKey } from './quickFilterPresets';
import {
  createSelectionModel,
  getExcludedIds as selectionGetExcludedIds,
  getExplicitIds as selectionGetExplicitIds,
  isAllMatching as selectionIsAllMatching,
  isPageFullySelected as selectionIsPageFullySelected,
  isSelected as selectionIsSelected,
  selectedCount as selectionSelectedCount,
  type SelectionState,
} from './selectionModel';
import {
  areFiltersEqual,
  areSortsEqual,
  decodeFilters,
  decodeSorts,
  encodeFilters,
} from './useListUrlState';

/** Own URL, query, pagination, selection and grouping state. */
export function useListPageState(
  props: PageContentProps,
  late: {
    loadData: (params?: ListLoadDataParams) => Promise<void>;
  },
) {
  const { schema, tableName, token, listExtensions } = props;
  const { user, hasPermission } = useAuth();
  const { showSuccessToast, showErrorToast, showWarningToast, showInfoToast } = useToastContext();
  const showToast = useCallback(
    (message: string, type: 'success' | 'error' | 'warning' | 'info') => {
      switch (type) {
        case 'success':
          showSuccessToast(message);
          break;
        case 'error':
          showErrorToast(message);
          break;
        case 'warning':
          showWarningToast(message);
          break;
        case 'info':
          showInfoToast(message);
          break;
      }
    },
    [showSuccessToast, showErrorToast, showWarningToast, showInfoToast],
  );

  // Parse filter_* params from URL for drill-down navigation
  const [searchParams, routerSetSearchParams] = useSearchParams();
  const listLocation = useLocation();
  const listNavigationType = useNavigationType();
  const listUrlWritesEnabledRef = useRef(true);
  const setSearchParams = useSerializedSearchParamsUpdater(
    searchParams,
    routerSetSearchParams,
    listUrlWritesEnabledRef,
    { key: listLocation.key, action: listNavigationType },
  );
  const listSchemaFieldNames = useMemo(() => collectListSchemaFieldNames(schema), [schema]);
  const listSchemaFieldNameSet = useMemo(
    () => new Set(listSchemaFieldNames),
    [listSchemaFieldNames],
  );
  const urlFilters = useMemo(() => {
    const filters: Record<string, any> = {};
    searchParams.forEach((value, key) => {
      if (key.startsWith('filter_')) {
        const fieldName = key.replace('filter_', '');
        filters[fieldName] = value;
      }
    });
    // Drill-down bare params (e.g. fin_cfw_week_start written by a dashboard
    // drillDown paramMapping) seed row filters too; filter_* keeps precedence.
    return {
      ...collectListBareParamFilters(searchParams, listSchemaFieldNames),
      ...filters,
    };
  }, [searchParams, listSchemaFieldNames]);
  const urlPageNum = useMemo(() => {
    const raw = searchParams.get('pageNum');
    if (!raw) return null;
    const parsed = Number(raw);
    return Number.isFinite(parsed) && parsed >= 1 ? Math.floor(parsed) : null;
  }, [searchParams]);
  const urlPageSize = useMemo(() => {
    const raw = searchParams.get('pageSize');
    if (!raw) return null;
    const parsed = Number(raw);
    return Number.isFinite(parsed) && parsed >= 1 ? Math.floor(parsed) : null;
  }, [searchParams]);

  // State management - P2-1 fix: merged into single state
  const [data, setData] = useState<DynamicEntity[]>([]);
  const [dataReady, setDataReady] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // T9 — cross-page selection model. `selectionState` is the single source of
  // truth (explicit picked ids, or all-matching mode with an exclusion set);
  // the page-scoped `selectedIds` Set + counts below are derived from it.
  const [selectionState, setSelectionState] = useState<SelectionState>(() =>
    createSelectionModel(),
  );
  const [bulkEditOpen, setBulkEditOpen] = useState(false);
  const [bulkFieldCommand, setBulkFieldCommand] = useState<BulkFieldCommandState | null>(null);
  const [analysisOpen, setAnalysisOpen] = useState(false);
  const [pageState, setPageState] = useState(() => ({
    filters: { ...urlFilters } as Record<string, any>,
    pagination: {
      current: urlPageNum ?? 1,
      pageSize: urlPageSize ?? 20,
      total: 0,
    },
  }));

  // P2-1 fix: destructure state for convenience
  const { filters, pagination } = pageState;
  const schemaExtension = (schema as any)?.extension ?? {};
  const isApiDatasourcePage = Boolean(
    schema?.dataSource?.type === 'api' && schema.dataSource.endpoint,
  );
  const skipListData = shouldSkipListData(schema);
  const skipModelFieldMeta = shouldSkipModelFieldMeta(schema, skipListData);
  const miscBlocksPosition = resolveListMiscBlocksPosition(schema);

  // Read initial sorts, keyword, and view from URL search params
  // Keyword/pagination updates must not reset a saved view's sorts or filters.
  const urlSortEncoding = searchParams.get('sort');
  const urlFilterEncoding = searchParams.get('filters');
  const urlSorts = useMemo(() => decodeSorts(urlSortEncoding), [urlSortEncoding]);
  const urlChipFilters = useMemo(() => decodeFilters(urlFilterEncoding), [urlFilterEncoding]);
  const urlKeyword = useMemo(() => searchParams.get('keyword') || '', [searchParams]);
  const urlViewPid = useMemo(() => searchParams.get('view') || null, [searchParams]);
  // Active preset view (?preset=created_today) — persists across reload.
  const urlPreset = useMemo<QuickFilterKey | null>(() => {
    const raw = searchParams.get('preset');
    return isQuickFilterPresetKey(raw) ? raw : null;
  }, [searchParams]);

  // Search keyword for toolbar search input
  const [keyword, _setKeyword] = useState(urlKeyword);
  const keywordRef = useRef(keyword);
  const listRequestSeqRef = useRef(0);

  // Debounced URL sync for keyword (300ms) — keeps input responsive while
  // reducing URL/history updates during rapid typing
  const syncKeywordToUrl = useDebouncedCallback((value: string) => {
    setSearchParams(
      (prev) => {
        const p = new URLSearchParams(prev);
        if (value.trim()) {
          p.set('keyword', value.trim());
        } else {
          p.delete('keyword');
        }
        return p;
      },
      { replace: true },
    );
  }, 300);

  // Debounced auto-search: triggers loadData 300ms after the user stops typing,
  // so results update without requiring Enter. Reduces API calls by ~60-80%
  // compared to firing on every keystroke.
  const { debouncedSearch, commitSearch, rememberSubmittedFilters, getSubmittedFilters } = useListKeywordSearch(
    (params) => { void late.loadData(params); },
    Boolean(schema),
    pagination.pageSize,
    urlFilters,
  );
  const {
    read: readApiRequest,
    getFailedRequest: getFailedApiRequest,
    clearFailure: clearApiReadFailure,
  } = useListApiReadRetry();

  // Synchronous ref update ensures loadData always reads the latest keyword
  // even when called in the same event cycle as a state update (e.g., Playwright fill + Enter)
  const setKeyword = useCallback(
    (value: string) => {
      keywordRef.current = value;
      _setKeyword(value);
      // Sync keyword to URL (debounced 300ms)
      syncKeywordToUrl(value);
      // Auto-search after user stops typing (debounced 300ms)
      debouncedSearch();
    },
    [syncKeywordToUrl, debouncedSearch],
  );

  // Active sort state — initialized from URL > SavedView > DSL defaultSort
  const [activeSorts, setActiveSorts] = useState<SortConfig[]>(() => urlSorts);
  const initialUrlSortsRef = useRef(urlSorts);
  const hasLocalSortChangeRef = useRef(false);
  const pendingSortUrlSyncRef = useRef<string | null | undefined>(undefined);
  const setLocalActiveSorts = useCallback(
    (update: SortConfig[] | ((previous: SortConfig[]) => SortConfig[])) => {
      hasLocalSortChangeRef.current = true;
      setActiveSorts((previous) => applyLocalSortUpdate(previous, update, pendingSortUrlSyncRef));
    },
    [],
  );
  const clearSortOverrides = useCallback(() => {
    initialUrlSortsRef.current = [];
    hasLocalSortChangeRef.current = false;
    pendingSortUrlSyncRef.current = undefined;
  }, []);
  // Active filter chips — user-added filters via chip bar (separate from filters)
  const [chipFilters, setChipFilters] = useState<ViewFilterConfig[]>(() => urlChipFilters);
  const initialUrlFiltersRef = useRef(urlChipFilters);
  const hasLocalFilterChangeRef = useRef(false);
  const pendingChipFilterUrlSyncRef = useRef<string | null | undefined>(undefined);
  const clearFilterOverrides = useCallback(() => {
    initialUrlFiltersRef.current = [];
    hasLocalFilterChangeRef.current = false;
    pendingChipFilterUrlSyncRef.current = undefined;
  }, []);
  const setLocalChipFilters = useCallback(
    (update: ViewFilterConfig[] | ((previous: ViewFilterConfig[]) => ViewFilterConfig[])) => {
      hasLocalFilterChangeRef.current = true;
      setChipFilters((previous) => {
        const next = typeof update === 'function' ? update(previous) : update;
        pendingChipFilterUrlSyncRef.current = encodeFilters(next);
        return next;
      });
    },
    [],
  );
  // FilterFieldPicker state
  const [fieldPickerOpen, setFieldPickerOpen] = useState(false);
  const [fieldPickerAnchor, setFieldPickerAnchor] = useState<
    { x: number; y: number } | undefined
  >();
  // FilterValuePopover state — for editing a chip's operator + value
  const [editingChipIdx, setEditingChipIdx] = useState<number | null>(null);
  const [valuePopoverAnchor, setValuePopoverAnchor] = useState<
    { x: number; y: number } | undefined
  >();
  const chipFiltersRef = useRef<ViewFilterConfig[]>(chipFilters);

  useEffect(() => {
    chipFiltersRef.current = chipFilters;
  }, [chipFilters]);

  // Client-side grouping state
  const [groupByField, setGroupByField] = useState<string | null>(null);
  const [collapsedGroups, setCollapsedGroups] = useState<Set<string>>(new Set());

  const groupedData = useMemo(() => {
    if (!groupByField || data.length === 0) return null;
    const groups = new Map<string, DynamicEntity[]>();
    for (const row of data) {
      const key = String(row[groupByField] ?? '(empty)');
      if (!groups.has(key)) groups.set(key, []);
      groups.get(key)!.push(row);
    }
    return Array.from(groups.entries()).map(([key, rows]) => ({ key, rows, count: rows.length }));
  }, [data, groupByField]);

  const toggleGroupCollapse = useCallback((key: string) => {
    setCollapsedGroups((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  }, []);

  // P2-1 fix: helper functions for updating filters and pagination
  const setFilters = useCallback(
    (filters: Record<string, any> | ((prev: Record<string, any>) => Record<string, any>)) => {
      setPageState((prev) => ({
        ...prev,
        filters: typeof filters === 'function' ? filters(prev.filters) : filters,
      }));
    },
    [],
  );

  const setPagination = useCallback((pagination: any) => {
    setPageState((prev) => ({
      ...prev,
      pagination: typeof pagination === 'function' ? pagination(prev.pagination) : pagination,
    }));
  }, []);

  const shouldPersistPaginationToUrlRef = useRef(
    urlPageNum != null ||
      urlPageSize != null ||
      urlSorts.length > 0 ||
      urlChipFilters.length > 0 ||
      Boolean(urlKeyword),
  );
  const tableBlock = useMemo(() => {
    if (!schema?.blocks) return null;
    return schema.blocks.find((block: any) => block.blockType === 'table') || null;
  }, [schema]);
  const auditUserDisplayFields = useMemo(
    () => resolveAuditUserDisplayFields(tableBlock),
    [tableBlock],
  );
  const tableBulkActions = useMemo<ButtonConfig[]>(() => {
    const configured =
      (tableBlock as any)?.table?.bulkActions ?? (tableBlock as any)?.bulkActions ?? [];
    return Array.isArray(configured) ? configured : [];
  }, [tableBlock]);
  const tableBulkCapabilities = useMemo<BuiltInBulkCapabilitiesConfig | undefined>(() => {
    const configured =
      (tableBlock as any)?.table?.bulkCapabilities ?? (tableBlock as any)?.bulkCapabilities;
    return configured && typeof configured === 'object' ? configured : undefined;
  }, [tableBlock]);
  // T9 — ids of the rows currently loaded on this page (cross-page selection
  // accumulates across these as the user pages).
  const pageRowIds = useMemo(
    () => data.map((row) => getPublicRecordKey(row) || '').filter(Boolean) as string[],
    [data],
  );
  const allMatchingSelected = selectionIsAllMatching(selectionState);
  // Page-scoped Set of selected ids — what the table's checkboxes render from
  // (the table compares against the visible page's row ids).
  const selectedIds = useMemo(
    () => new Set(pageRowIds.filter((id) => selectionIsSelected(selectionState, id))),
    [pageRowIds, selectionState],
  );
  // Whether every loaded row on this page is selected (drives the header
  // checkbox + the "select all N matching" banner offer).
  const pageFullySelected = useMemo(
    () => selectionIsPageFullySelected(selectionState, pageRowIds),
    [selectionState, pageRowIds],
  );
  // Effective selected count across all pages (page picks, or total-minus-
  // exclusions in all-matching mode).
  const effectiveSelectedCount = selectionSelectedCount(selectionState, pagination.total);
  // Explicit ids for bulk ops/export. In all-matching mode there is no finite
  // client-side id list — callers branch on `allMatchingSelected` and export by
  // the current filter instead.
  const explicitSelectedIds = useMemo(
    () => selectionGetExplicitIds(selectionState),
    [selectionState],
  );
  // The concrete ids a bulk op (delete/edit/custom action) should target. In
  // explicit mode this is the full cross-page pick; in all-matching mode we
  // fall back to the currently-selected rows on this page (a finite, safe set)
  // — export is the cross-page path and uses the filter directly.
  const selectedIdList = allMatchingSelected ? [] : explicitSelectedIds;
  const allMatchingExcludedIds = useMemo(
    () => selectionGetExcludedIds(selectionState),
    [selectionState],
  );
  // Whether row selection (checkbox column + bulk bar + select-all banner) is
  // active for this page — DSL opt-in via the table block, minus any host-level
  // override.
  const selectionEnabled =
    !!((tableBlock as any)?.table?.selection || (tableBlock as any)?.selection) &&
    !listExtensions?.disableRowSelection;

  // T10 — column aggregation summary footer. DSL opt-in via the table block
  // (`table.showSummaryRow`); `undefined` lets ListTable auto-show the footer
  // whenever any column declares an `aggregate`. Aggregates cover the current
  // page only — a cross-page grand total would require a backend sum endpoint.
  const summaryRowEnabled = ((tableBlock as any)?.table?.showSummaryRow ??
    (tableBlock as any)?.showSummaryRow) as boolean | undefined;

  // T10 — expandable tree rows. DSL opt-in via the table block
  // (`table.treeConfig: { parentField }`). When present, self-referencing rows
  // render as an indented tree with expand/collapse chevrons; when absent the
  // table stays flat (byte-identical to today). Aggregation/sorting unaffected.
  const treeConfig = ((tableBlock as any)?.table?.treeConfig ?? (tableBlock as any)?.treeConfig) as
    | TableConfig['treeConfig']
    | undefined;

  // G7 dispatch — list pages hardcode table/filters/toolbar/tabs/form-buttons
  // in the layout above, but any additional block types in the schema
  // (chart / description / rich-text / divider / stat-card / etc.) were
  // previously dropped silently. Collect them here and render via BlockRenderer
  // at the end of the list page so they appear in the UI.
  const miscListBlocks = useMemo(
    () => collectListMiscBlocks(schema, skipListData),
    [schema, skipListData],
  );

  // Sync URL pagination params -> local state (supports refresh and browser back/forward).
  useEffect(() => {
    if (urlPageNum == null && urlPageSize == null) return;
    shouldPersistPaginationToUrlRef.current = true;
    setPageState((prev) => {
      const nextCurrent = urlPageNum ?? prev.pagination.current;
      const nextPageSize = urlPageSize ?? prev.pagination.pageSize;
      if (nextCurrent === prev.pagination.current && nextPageSize === prev.pagination.pageSize) {
        return prev;
      }
      return {
        ...prev,
        pagination: {
          ...prev.pagination,
          current: nextCurrent,
          pageSize: nextPageSize,
        },
      };
    });
  }, [urlPageNum, urlPageSize]);

  // Sync URL chip filters -> local state (supports refresh and browser back/forward).
  useEffect(() => {
    const currentUrlEncoding = urlFilterEncoding;
    const syncAction = resolveUrlStateSyncAction(
      pendingChipFilterUrlSyncRef.current,
      currentUrlEncoding,
    );
    if (syncAction === 'wait-for-local') return;
    if (syncAction === 'ack-local') {
      pendingChipFilterUrlSyncRef.current = undefined;
      return;
    }
    setChipFilters((prev) => (areFiltersEqual(prev, urlChipFilters) ? prev : urlChipFilters));
  }, [urlFilterEncoding, urlChipFilters]);

  // Sync URL sorts -> local state (supports refresh and browser back/forward).
  useEffect(() => {
    const currentUrlEncoding = urlSortEncoding;
    const syncAction = resolveUrlStateSyncAction(pendingSortUrlSyncRef.current, currentUrlEncoding);
    if (syncAction === 'wait-for-local') return;
    if (syncAction === 'ack-local') {
      pendingSortUrlSyncRef.current = undefined;
      return;
    }
    setActiveSorts((prev) => (areSortsEqual(prev, urlSorts) ? prev : urlSorts));
  }, [urlSortEncoding, urlSorts]);

  // Sync local pagination state -> URL query params (preserve existing filter_* params).
  useEffect(() => {
    if (!shouldPersistPaginationToUrlRef.current) return;
    const currentUrlPageNum = Number(searchParams.get('pageNum'));
    const currentUrlPageSize = Number(searchParams.get('pageSize'));
    const needsUpdate =
      currentUrlPageNum !== pagination.current || currentUrlPageSize !== pagination.pageSize;
    if (!needsUpdate) return;
    setSearchParams((previous) => {
      const nextParams = new URLSearchParams(previous);
      nextParams.set('pageNum', String(pagination.current));
      nextParams.set('pageSize', String(pagination.pageSize));
      return nextParams;
    }, { replace: true });
  }, [pagination.current, pagination.pageSize, searchParams, setSearchParams]);

  return {
    schema,
    tableName,
    token,
    listExtensions,
    user,
    hasPermission,
    showSuccessToast,
    showErrorToast,
    showToast,
    searchParams,
    listUrlWritesEnabledRef,
    setSearchParams,
    listSchemaFieldNameSet,
    urlFilters,
    data,
    setData,
    dataReady,
    setDataReady,
    loading,
    setLoading,
    error,
    setError,
    setSelectionState,
    bulkEditOpen,
    setBulkEditOpen,
    bulkFieldCommand,
    setBulkFieldCommand,
    analysisOpen,
    setAnalysisOpen,
    setPageState,
    filters,
    pagination,
    schemaExtension,
    isApiDatasourcePage,
    skipListData,
    skipModelFieldMeta,
    miscBlocksPosition,
    urlViewPid,
    urlPreset,
    keyword,
    _setKeyword,
    keywordRef,
    listRequestSeqRef,
    syncKeywordToUrl,
    debouncedSearch,
    commitSearch,
    rememberSubmittedFilters,
    getSubmittedFilters,
    readApiRequest,
    getFailedApiRequest,
    clearApiReadFailure,
    setKeyword,
    activeSorts,
    setActiveSorts,
    initialUrlSortsRef,
    hasLocalSortChangeRef,
    pendingSortUrlSyncRef,
    setLocalActiveSorts,
    clearSortOverrides,
    chipFilters,
    setChipFilters,
    initialUrlFiltersRef,
    hasLocalFilterChangeRef,
    pendingChipFilterUrlSyncRef,
    clearFilterOverrides,
    setLocalChipFilters,
    fieldPickerOpen,
    setFieldPickerOpen,
    fieldPickerAnchor,
    setFieldPickerAnchor,
    editingChipIdx,
    setEditingChipIdx,
    valuePopoverAnchor,
    setValuePopoverAnchor,
    chipFiltersRef,
    groupByField,
    setGroupByField,
    collapsedGroups,
    groupedData,
    toggleGroupCollapse,
    setFilters,
    setPagination,
    shouldPersistPaginationToUrlRef,
    tableBlock,
    auditUserDisplayFields,
    tableBulkActions,
    tableBulkCapabilities,
    pageRowIds,
    allMatchingSelected,
    selectionState,
    selectedIds,
    pageFullySelected,
    effectiveSelectedCount,
    explicitSelectedIds,
    selectedIdList,
    allMatchingExcludedIds,
    selectionEnabled,
    summaryRowEnabled,
    treeConfig,
    miscListBlocks,
  };
}
