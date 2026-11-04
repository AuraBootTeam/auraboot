import { findListFilterTabsBlock } from './listSchemaPolicy';
import { hasButtonPermissions } from '~/framework/meta/utils/buttonPermissions';
import { resolveListReadErrorMessage } from '~/framework/meta/utils/listReadErrors';
import React, { useCallback, useEffect, useMemo } from 'react';
import { type SetURLSearchParams, type NavigateFunction } from 'react-router';

import { useActionHandler } from '~/framework/meta/hooks/useActionHandler';
import { actionRegistry } from '~/framework/meta/runtime/actions/ActionRegistry';
import type { DataSourceManager } from '~/framework/meta/runtime/data-pipeline/DataSourceManager';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import type { ButtonConfig } from '~/framework/meta/schemas/types';
import {
  type SortConfig,
  type ViewConfig,
  type ViewFilterConfig,
} from '~/framework/smart/types/savedView';
import { useDebouncedValue } from '~/hooks/useDebouncedValue';
import { buildApiEndpoint, getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { fetchResult } from '~/shared/services/http-client';
import { type User, ResultHelper } from '~/utils/type';

import { evaluateVisibleWhen as evaluateVisibleWhenExpression } from '../utils/visibleWhen';
import { tabFilterToQueryCondition, type ListQueryFilterCondition } from './listFilterConditions';
import {
  type ListLoadDataParams,
  type PaginationResult,
  beginLatestListRequest,
} from './listPageStateUtils';

import type { DynamicEntity } from './listPageTypes';
import {
  areListSortFilterQueriesEqual,
  isListQuerySettled,
  type ListSortFilterQuery,
} from './listViewConfig';
import { buildQuickFilterPreset } from './quickFilterPresets';
import { areSortsEqual, encodeFilters, encodeSorts, resolveListSortState } from './useListUrlState';

interface ListQueryActionsInput {
  runtime: SchemaRuntime | null;
  navigateAwayFromList: NavigateFunction;
  tableName: string;
  loadData: (params?: ListLoadDataParams) => Promise<void>;
  filters: Record<string, any>;
  setFilters: (
    filters: Record<string, any> | ((prev: Record<string, any>) => Record<string, any>),
  ) => void;
  pagination: { current: number; pageSize: number; total: number };
  setPagination: (pagination: any) => void;
  dataSourceManager: DataSourceManager;
  locale: string;
  t: (key: string) => string;
  token: string | null | undefined;
  showToast: (message: string, type: 'success' | 'error' | 'warning' | 'info') => void;
  schema: any;
  skipListData: boolean;
  urlViewPid: string | null;
  urlPreset: string | null;
  user: User | null;
  setActiveQuickFilter: React.Dispatch<React.SetStateAction<string | null>>;
  skipFirstSortFilterEffectRef: React.RefObject<boolean>;
  loadDataRef: React.RefObject<((params?: ListLoadDataParams) => Promise<void>) | null>;
  urlFilters: { [x: string]: any };
  fieldMetaReadyRef: React.RefObject<Promise<void>>;
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
  data: DynamicEntity[];
  setActiveTab: React.Dispatch<React.SetStateAction<string>>;
  listRequestSeqRef: React.RefObject<number>;
  setLoading: React.Dispatch<React.SetStateAction<boolean>>;
  setError: React.Dispatch<React.SetStateAction<string | null>>;
  auditUserDisplayFields: string | undefined;
  activeSorts: SortConfig[];
  tableBlock: any;
  buildFiltersParam: (
    tabCondition: ListQueryFilterCondition | null,
    userFilters?: Record<string, any>,
    chipFiltersList?: ViewFilterConfig[],
    filterFieldMap?: Map<string, any>,
  ) => string | undefined;
  chipFilters: ViewFilterConfig[];
  modelFieldMapRef: React.RefObject<Map<string, any>>;
  keywordRef: React.RefObject<string>;
  setData: React.Dispatch<React.SetStateAction<DynamicEntity[]>>;
  setDataReady: React.Dispatch<React.SetStateAction<boolean>>;
  setPageState: React.Dispatch<
    React.SetStateAction<{
      filters: Record<string, any>;
      pagination: { current: number; pageSize: number; total: number };
    }>
  >;
  hasPermission: (permissionCode: string) => boolean;
  navigate: NavigateFunction;
  shouldPersistPaginationToUrlRef: React.RefObject<boolean>;
  setLocalActiveSorts: (update: SortConfig[] | ((previous: SortConfig[]) => SortConfig[])) => void;
  lastScheduledSortFilterValuesRef: React.RefObject<ListSortFilterQuery | undefined>;
  searchParams: URLSearchParams;
  initialUrlSortsRef: React.RefObject<SortConfig[]>;
  hasLocalSortChangeRef: React.RefObject<boolean>;
  pendingSortUrlSyncRef: React.RefObject<string | null | undefined>;
  setSearchParams: SetURLSearchParams;
  viewsLoading: boolean;
  effectiveViewConfig: ViewConfig | undefined;
  pendingChipFilterUrlSyncRef: React.RefObject<string | null | undefined>;
}

/** Bind actions, tab/search/pagination handlers and URL query synchronization. */
export function useListQueryActions(
  {
    runtime,
    navigateAwayFromList,
    tableName,
    loadData,
    filters,
    setFilters,
    pagination,
    setPagination,
    dataSourceManager,
    locale,
    t,
    token,
    showToast,
    schema,
    skipListData,
    urlViewPid,
    urlPreset,
    user,
    setActiveQuickFilter,
    skipFirstSortFilterEffectRef,
    loadDataRef,
    urlFilters,
    fieldMetaReadyRef,
    listExtensions,
    data,
    setActiveTab,
    listRequestSeqRef,
    setLoading,
    setError,
    auditUserDisplayFields,
    activeSorts,
    tableBlock,
    buildFiltersParam,
    chipFilters,
    modelFieldMapRef,
    keywordRef,
    setData,
    setDataReady,
    setPageState,
    hasPermission,
    navigate,
    shouldPersistPaginationToUrlRef,
    setLocalActiveSorts,
    lastScheduledSortFilterValuesRef,
    searchParams,
    initialUrlSortsRef,
    hasLocalSortChangeRef,
    pendingSortUrlSyncRef,
    setSearchParams,
    viewsLoading,
    effectiveViewConfig,
    pendingChipFilterUrlSyncRef,
  }: ListQueryActionsInput,
  late: {
    autoSave: (config: Partial<ViewConfig>) => void;
  },
) {
  // Use unified action handler hook
  // IMPORTANT: Must be declared before any useEffect that references handleAction
  // to avoid temporal dead zone ("Cannot access 'handleAction' before initialization").
  const {
    handleAction,
    error: actionError,
    setError: setActionError,
  } = useActionHandler({
    runtime,
    navigate: navigateAwayFromList,
    tableName,
    // Route segments are page keys on custom pages; record reads authorize by
    // model, so pin the command snapshot against the schema's model.
    recordReadModel: schema?.modelCode,
    context: {
      loadData,
      filters,
      setFilters,
      pagination,
      setPagination,
    },
    dataSourceManager,
    locale,
    t,
    token: token || undefined,
    showToast,
    // Action failures retain the table and render separately from data/schema load errors.
    onError: (err) => {
      if (import.meta.env?.DEV) console.warn('[ListPageContent] action error:', err.message);
    },
  });

  // Listen for cell-button-click events from Button field renderer (GAP-131)
  useEffect(() => {
    const handler = (e: Event) => {
      const detail = (e as CustomEvent).detail;
      if (detail?.commandCode && detail?.record) {
        handleAction({ code: detail.commandCode, action: 'executeCommand' } as any, detail.record);
      }
    };
    window.addEventListener('cell-button-click', handler);
    return () => window.removeEventListener('cell-button-click', handler);
  }, [handleAction]);

  // Initial data load - only execute once when schema is loaded
  // Pass current filters (which may include URL filter_* params) for the first load
  useEffect(() => {
    if (schema && !skipListData) {
      const runInitialLoad = () => {
        // Restore an active preset view from ?preset= so it survives reload —
        // a SavedView (?view=) takes precedence and carries its own filters.
        const initialPreset = urlViewPid ? null : urlPreset;
        if (initialPreset) {
          const presetFilters =
            buildQuickFilterPreset(initialPreset, { userId: user?.id, now: new Date() }) ?? {};
          setActiveQuickFilter(initialPreset);
          setFilters(presetFilters);
          // The debounced sort/filter effect would otherwise re-fetch once on
          // mount with the still-empty `filters` state and clobber this preset
          // load — skip exactly that first run so the preset filter wins.
          skipFirstSortFilterEffectRef.current = true;
          loadDataRef.current?.({
            page: pagination.current - 1,
            size: pagination.pageSize,
            filters: presetFilters,
          });
        } else {
          loadDataRef.current?.({
            page: pagination.current - 1,
            size: pagination.pageSize,
            filters,
          });
        }
      };
      // URL-sourced filters (filter_* / drill-down bare params) are typed by the
      // model field metadata; awaiting it here prevents the first request from
      // going out with the un-typed legacy operator (e.g. LIKE on a date).
      if (Object.keys(urlFilters).length > 0) {
        let cancelled = false;
        void fieldMetaReadyRef.current.then(() => {
          if (!cancelled) runInitialLoad();
        });
        return () => {
          cancelled = true;
        };
      }
      runInitialLoad();
    }
    // Intentionally only react to schema changes.
    // Pagination or filter updates are handled by explicit user actions.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [schema, skipListData]);

  useEffect(() => {
    listExtensions?.onDataChange?.(data);
  }, [data, listExtensions]);

  useEffect(() => {
    const eventName = listExtensions?.reloadEventName;
    if (!eventName) {
      return;
    }

    const handleReload = () => {
      loadDataRef.current?.({ page: pagination.current - 1, size: pagination.pageSize, filters });
    };

    window.addEventListener(eventName, handleReload);
    return () => window.removeEventListener(eventName, handleReload);
  }, [filters, pagination.current, pagination.pageSize, listExtensions?.reloadEventName]);

  // Handle tab change - reload data with tab filter
  const handleTabChange = useCallback(
    (tabKey: string) => {
      if (skipListData) return;
      setActiveTab(tabKey);
      const isLatestRequest = beginLatestListRequest(listRequestSeqRef);
      // Compute tab filter directly (don't rely on getTabFilter since activeTab is stale in closure)
      let tabCondition: ListQueryFilterCondition | null = null;
      if (schema?.blocks) {
        const tabsBlock = findListFilterTabsBlock(schema.blocks);
        const tabDef = (tabsBlock?.tabs as any[])?.find((t: any) => t.key === tabKey);
        if (tabDef?.filter) {
          tabCondition = tabFilterToQueryCondition(tabDef.filter, user?.pid);
        }
      }

      // Call loadData with pre-built params (bypass getTabFilter to avoid stale closure)
      (async () => {
        try {
          setLoading(true);
          setError(null);

          // Resolve endpoint: API datasource vs standard dynamic table
          let endpoint: string;
          let method: 'get' | 'post' = 'get';
          const isApiDatasource = schema?.dataSource?.type === 'api' && schema.dataSource.endpoint;
          if (isApiDatasource) {
            endpoint = schema!.dataSource!.endpoint!;
            method = (schema!.dataSource!.method as 'get' | 'post') || 'get';
          } else {
            const apiTableName = schema?.modelCode ? schema.modelCode : tableName;
            endpoint = `${buildApiEndpoint(apiTableName)}/list`;
          }

          // Build query params
          const queryParams: Record<string, any> = {};
          if (isApiDatasource) {
            queryParams.page = 0;
            queryParams.size = pagination.pageSize;
          } else {
            queryParams.pageNum = 1;
            queryParams.pageSize = pagination.pageSize;
            if (auditUserDisplayFields) {
              queryParams.auditUserDisplayFields = auditUserDisplayFields;
            }
          }
          if (isApiDatasource) {
            // API datasource: tab filter as individual query param
            if (tabCondition) {
              queryParams[tabCondition.fieldName] = tabCondition.values ?? tabCondition.value;
            }
            if (filters) {
              for (const [key, value] of Object.entries(filters)) {
                if (value == null || value === '') continue;
                if (typeof value === 'object' && ('start' in value || 'end' in value)) {
                  if (value.start) queryParams[`${key}_start`] = String(value.start);
                  if (value.end) queryParams[`${key}_end`] = String(value.end);
                } else {
                  queryParams[key] = String(value);
                }
              }
            }
            if (activeSorts.length === 1) {
              queryParams.sortField = activeSorts[0].fieldCode;
              queryParams.sortOrder = activeSorts[0].direction;
            } else if (tableBlock?.defaultSort?.field) {
              queryParams.sortField = tableBlock.defaultSort.field;
              queryParams.sortOrder = String(tableBlock.defaultSort.order || 'desc').toLowerCase();
            }
          } else {
            const filtersParam = buildFiltersParam(
              tabCondition,
              filters,
              chipFilters,
              modelFieldMapRef.current,
            );
            if (filtersParam) {
              queryParams.filters = filtersParam;
            }
          }
          if (!isApiDatasource) {
            if (activeSorts.length > 1)
              queryParams.sortFields = activeSorts
                .map((sort) => `${sort.fieldCode}:${sort.direction}`)
                .join(',');
            else if (activeSorts.length === 1) {
              queryParams.sortField = activeSorts[0].fieldCode;
              queryParams.sortOrder = activeSorts[0].direction;
            } else if (tableBlock?.defaultSort?.field) {
              queryParams.sortField = tableBlock.defaultSort.field;
              queryParams.sortOrder = tableBlock.defaultSort.order || 'desc';
            }
          }
          if (keywordRef.current.trim()) queryParams.keyword = keywordRef.current.trim();

          const result = await fetchResult<PaginationResult<DynamicEntity>>(endpoint, {
            method,
            params: queryParams,
            token: token || undefined,
          });
          if (!isLatestRequest()) return;
          if (ResultHelper.isSuccess(result) && result.data) {
            const responseData = result.data;
            if (Array.isArray(responseData)) {
              // API returned flat array — client-side pagination
              const sliced = (responseData as any[]).slice(0, pagination.pageSize);
              setData(sliced as DynamicEntity[]);
              setDataReady(true);
              setPageState((prev) => ({
                ...prev,
                pagination: {
                  ...prev.pagination,
                  total: (responseData as any[]).length,
                  current: 1,
                },
              }));
            } else {
              const records = responseData.records ?? [];
              const currentPage = Number(responseData.page ?? 1) || 1;
              const total = Number(responseData.total ?? 0);
              setData(records);
              setDataReady(true);
              setPageState((prev) => ({
                ...prev,
                pagination: {
                  ...prev.pagination,
                  total,
                  current: currentPage,
                },
              }));
            }
          } else {
            setError(resolveListReadErrorMessage(result, locale, t));
          }
        } catch (err) {
          if (!isLatestRequest()) return;
          setError(resolveListReadErrorMessage(err, locale, t));
        } finally {
          if (isLatestRequest()) {
            setLoading(false);
          }
        }
      })();
    },
    [
      schema,
      buildFiltersParam,
      chipFilters,
      filters,
      pagination.pageSize,
      tableName,
      token,
      t,
      activeSorts,
      tableBlock,
      auditUserDisplayFields,
      skipListData,
    ],
  );

  // Evaluate visibleWhen expression against a row record
  const evaluateVisibleWhen = useCallback(
    (visibleWhen: string | undefined, record?: Record<string, any>): boolean => {
      return evaluateVisibleWhenExpression(visibleWhen, {
        record: record || {},
        row: record || {},
        form: record || {},
      });
    },
    [],
  );

  const canUseButton = useCallback(
    (button: ButtonConfig): boolean => {
      return hasButtonPermissions(button, hasPermission);
    },
    [hasPermission],
  );

  // Resolve button display label
  const resolveButtonLabel = useCallback(
    (button: ButtonConfig): string => {
      if (button.content) {
        return getLocalizedText(button.content, locale, t);
      }
      if (button.label) {
        const localized = getLocalizedText(button.label, locale, t);
        // If the DSL label is a bare lowercase identifier (e.g. "create",
        // "edit", "detail", "submit", "cancel"), getLocalizedText returns it
        // unchanged. Treat it as an i18n key candidate and probe well-known
        // namespaces before leaking the raw token to the UI.
        const isBareIdentifier =
          typeof button.label === 'string' &&
          /^[a-z][a-z0-9_]*$/.test(button.label) &&
          localized === button.label;
        if (isBareIdentifier) {
          const commonKey = `common.${button.label}`;
          const commonResolved = t(commonKey);
          if (commonResolved && commonResolved !== commonKey) return commonResolved;
          const actionKey = `action.${button.label}`;
          const actionResolved = t(actionKey);
          if (actionResolved && actionResolved !== actionKey) return actionResolved;
        }
        return localized;
      }
      // Try plugin-namespaced key from commandCode (e.g., "crm:contact_lead" → "crm.action.contact_lead")
      if (button.commandCode && button.commandCode.includes(':')) {
        const [ns, actionCode] = button.commandCode.split(':');
        const nsKey = `${ns}.action.${actionCode}`;
        const nsResolved = t(nsKey);
        if (nsResolved && nsResolved !== nsKey) return nsResolved;
      }
      if (button.label) {
        const labelStr = typeof button.label === 'string' ? button.label : undefined;
        if (labelStr) {
          const labelResolved = t(labelStr);
          if (labelResolved && labelResolved !== labelStr) return labelResolved;
          return labelStr;
        }
      }
      if (button.action && typeof button.action === 'string') {
        // Map action values to i18n keys
        const actionKeyMap: Record<string, string> = {
          edit: 'update',
          navigate: 'view',
          detail: 'view',
        };
        const i18nAction = actionKeyMap[button.action] || button.action;
        const i18nKey = `action.${i18nAction}`;
        const translated = t(i18nKey);
        if (translated && translated !== i18nKey) {
          return translated;
        }
      }
      // Try button code as i18n key
      const codeKey = `action.${button.code}`;
      const codeResolved = t(codeKey);
      if (codeResolved && codeResolved !== codeKey) return codeResolved;
      return button.code;
    },
    [locale, t],
  );

  // Handle search - using ActionRegistry
  const handleSearch = useCallback(() => {
    actionRegistry.execute('search', {
      args: {},
      navigate,
      tableName,
      loadData,
      filters,
      setFilters,
      pagination,
      setPagination,
      dataSourceManager,
      locale,
      t,
      token: token || undefined,
      fetchResult,
      buildApiEndpoint,
    });
  }, [filters, loadData, navigate, tableName, dataSourceManager, locale, t, token, pagination]);

  // Handle reset - using ActionRegistry
  const handleReset = useCallback(() => {
    actionRegistry.execute('reset', {
      args: {},
      navigate,
      tableName,
      loadData,
      filters,
      setFilters,
      pagination,
      setPagination,
      dataSourceManager,
      locale,
      t,
      token: token || undefined,
      fetchResult,
      buildApiEndpoint,
    });
  }, [loadData, navigate, tableName, dataSourceManager, locale, t, token, filters, pagination]);

  // Handle pagination - P2-1 fix: use destructured pagination
  const handlePageChange = useCallback(
    (page: number) => {
      shouldPersistPaginationToUrlRef.current = true;
      setPagination((prev: typeof pagination) => ({ ...prev, current: page }));
      loadData({ page: page - 1, filters });
    },
    [filters, loadData, setPagination],
  );

  const handlePageSizeChange = useCallback(
    (pageSize: number) => {
      shouldPersistPaginationToUrlRef.current = true;
      setPagination((prev: typeof pagination) => ({ ...prev, current: 1, pageSize }));
      loadData({ page: 0, size: pageSize, filters });
    },
    [filters, loadData, setPagination],
  );

  // Column header sort toggle: none → asc → desc → none
  // Shift+click appends to multi-sort, regular click replaces
  const toggleSort = useCallback(
    (fieldCode: string, multiSort = false) => {
      setLocalActiveSorts((prev) => {
        const existing = prev.find((s) => s.fieldCode === fieldCode);
        let next: SortConfig[];
        if (!existing) {
          // Add new sort
          const newSort: SortConfig = { fieldCode, direction: 'asc', priority: prev.length };
          next = multiSort ? [...prev, newSort] : [newSort];
        } else if (existing.direction === 'asc') {
          // asc → desc
          next = multiSort
            ? prev.map((s) =>
                s.fieldCode === fieldCode ? { ...s, direction: 'desc' as const } : s,
              )
            : [{ fieldCode, direction: 'desc', priority: 0 }];
        } else {
          // desc → clear
          next = multiSort ? prev.filter((s) => s.fieldCode !== fieldCode) : [];
        }
        return next;
      });
    },
    [setLocalActiveSorts],
  );

  // Debounced re-fetch when sorts or chip filters change (150ms).
  // Prevents multiple rapid API calls when users adjust multiple filters
  // or sort columns in quick succession.
  const debouncedSortFilterValues = useDebouncedValue(
    useMemo(() => ({ activeSorts, chipFilters }), [activeSorts, chipFilters]),
    150,
  );

  useEffect(() => {
    if (!schema || skipListData) return;
    // Preset restore (?preset=) already issued the filtered load on mount; skip
    // this first debounced run so it doesn't clobber it with empty filters.
    if (skipFirstSortFilterEffectRef.current) {
      skipFirstSortFilterEffectRef.current = false;
      return;
    }
    // Explicit loads (initialization or view selection) already scheduled the
    // same query. Do not start a second request after the debounce catches up.
    if (
      areListSortFilterQueriesEqual(
        debouncedSortFilterValues,
        lastScheduledSortFilterValuesRef.current,
      )
    )
      return;
    loadData({
      page: 0,
      size: pagination.pageSize,
      filters,
      sorts: debouncedSortFilterValues.activeSorts,
      chipFilters: debouncedSortFilterValues.chipFilters,
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [debouncedSortFilterValues, skipListData]);

  const listQuerySettled =
    skipListData ||
    isListQuerySettled(
      { activeSorts, chipFilters },
      debouncedSortFilterValues,
      lastScheduledSortFilterValuesRef.current,
    );

  // Auto-save sorts to SavedView (debounced) + sync to URL
  useEffect(() => {
    if (!schema || skipListData) return;
    const encoded = encodeSorts(activeSorts);
    const currentEncoded = searchParams.get('sort');
    const sortOwnership = resolveListSortState({
      initialUrlSorts: initialUrlSortsRef.current,
      hasLocalSortChange: hasLocalSortChangeRef.current,
    });
    if ((encoded ?? null) !== (currentEncoded ?? null) && sortOwnership.syncUrlSorts) {
      pendingSortUrlSyncRef.current = encoded;
      setSearchParams(
        (prev) => {
          const p = new URLSearchParams(prev);
          if (encoded) {
            p.set('sort', encoded);
          } else {
            p.delete('sort');
          }
          return p;
        },
        { replace: true },
      );
    }
    if (viewsLoading) return;
    if (!sortOwnership.autoSaveSorts) {
      return;
    }
    if (areSortsEqual(effectiveViewConfig?.sorts ?? [], activeSorts)) {
      return;
    }
    late.autoSave({ sorts: activeSorts });
    // autoSave is declared later in this component; evaluating it in the
    // dependency array would hit the temporal dead zone during render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    activeSorts,
    schema,
    viewsLoading,
    effectiveViewConfig?.sorts,
    searchParams,
    setSearchParams,
    skipListData,
  ]);

  // Sync chip filters to URL so detail navigation and browser back/forward keep the list state.
  useEffect(() => {
    if (!schema || skipListData) return;
    const encoded = encodeFilters(chipFilters);
    const currentEncoded = searchParams.get('filters');
    if ((encoded ?? null) === (currentEncoded ?? null)) return;
    pendingChipFilterUrlSyncRef.current = encoded;
    shouldPersistPaginationToUrlRef.current = true;
    setSearchParams(
      (prev) => {
        const p = new URLSearchParams(prev);
        if (encoded) {
          p.set('filters', encoded);
        } else {
          p.delete('filters');
        }
        p.set('pageNum', '1');
        return p;
      },
      { replace: true },
    );
  }, [chipFilters, schema, searchParams, setSearchParams, skipListData]);

  return {
    handleAction,
    actionError,
    setActionError,
    handleTabChange,
    evaluateVisibleWhen,
    canUseButton,
    resolveButtonLabel,
    handleSearch,
    handleReset,
    handlePageChange,
    handlePageSizeChange,
    toggleSort,
    listQuerySettled,
  };
}
