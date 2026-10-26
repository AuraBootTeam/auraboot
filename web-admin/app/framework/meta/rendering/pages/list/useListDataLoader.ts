import { completeListReadRequest } from './listReadRequest';
import { resolveListReadErrorMessage } from '~/framework/meta/utils/listReadErrors';
import React, { useCallback, useEffect } from 'react';
import type { SetURLSearchParams } from 'react-router';
import {
  type SavedView,
  type SortConfig,
  type ViewConfig,
  type ViewFilterConfig,
  type ViewType,
} from '~/framework/smart/types/savedView';
import { isImplicitSavedView } from '~/framework/smart/utils/savedViewPersistence';
import { buildApiEndpoint } from '~/routes/_shared/dynamic-route-utils';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { type ListQueryFilterCondition } from './listFilterConditions';
import {
  type ListApiReadRequest,
  type useListApiReadRetry,
  type ListLoadDataParams,
  type PaginationResult,
  clearTransientViewSearchParams,
} from './listPageStateUtils';

import type { DynamicEntity } from './listPageTypes';
import { type ListSortFilterQuery } from './listViewConfig';

interface ListDataLoaderInput {
  locale: string;
  rememberSubmittedFilters: (filters: Record<string, any>) => void;
  getSubmittedFilters: () => Record<string, any>;
  readApiRequest: ReturnType<typeof useListApiReadRetry>['read'];
  clearApiReadFailure: ReturnType<typeof useListApiReadRetry>['clearFailure'];
  loadDataSeqRef: React.RefObject<number>;
  schema: any;
  skipListData: boolean;
  setData: React.Dispatch<React.SetStateAction<DynamicEntity[]>>;
  setError: React.Dispatch<React.SetStateAction<string | null>>;
  setLoading: React.Dispatch<React.SetStateAction<boolean>>;
  setPageState: React.Dispatch<
    React.SetStateAction<{
      filters: Record<string, any>;
      pagination: { current: number; pageSize: number; total: number };
    }>
  >;
  tableName: string;
  pagination: { current: number; pageSize: number; total: number };
  activeSorts: SortConfig[];
  lastScheduledSortFilterValuesRef: React.RefObject<ListSortFilterQuery | undefined>;
  chipFiltersRef: React.RefObject<ViewFilterConfig[]>;
  auditUserDisplayFields: string | undefined;
  tableBlock: any;
  getTabFilter: () => ListQueryFilterCondition | null;
  buildFiltersParam: (
    tabCondition: ListQueryFilterCondition | null,
    userFilters?: Record<string, any>,
    chipFiltersList?: ViewFilterConfig[],
    filterFieldMap?: Map<string, any>,
  ) => string | undefined;
  modelFieldMapRef: React.RefObject<Map<string, any>>;
  keywordRef: React.RefObject<string>;
  namedQueryCode: any;
  token: string | null | undefined;
  setDataReady: React.Dispatch<React.SetStateAction<boolean>>;
  t: (key: string) => string;
  loadDataRef: React.RefObject<((params?: ListLoadDataParams) => Promise<void>) | null>;
  clearSortOverrides: () => void;
  clearFilterOverrides: () => void;
  savedViews: SavedView[];
  applyViewConfigToListState: (viewConfig: ViewConfig | undefined) => Record<string, any>;
  setPendingViewConfig: React.Dispatch<React.SetStateAction<Partial<ViewConfig> | null>>;
  selectDefaultView: () => void;
  setActiveQuickFilter: React.Dispatch<React.SetStateAction<string | null>>;
  clearKeyword: () => void;
  setActiveViewType: React.Dispatch<React.SetStateAction<ViewType>>;
  setSearchParams: SetURLSearchParams;
  listSchemaFieldNameSet: Set<string>;
  pendingSavedViewFiltersRef: React.RefObject<Record<string, any> | null>;
}

/** Load list records and coordinate the latest query and default-view restoration. */
export function useListDataLoader({
  locale,
  rememberSubmittedFilters,
  getSubmittedFilters,
  readApiRequest,
  clearApiReadFailure,
  loadDataSeqRef,
  schema,
  skipListData,
  setData,
  setError,
  setLoading,
  setPageState,
  tableName,
  pagination,
  activeSorts,
  lastScheduledSortFilterValuesRef,
  chipFiltersRef,
  auditUserDisplayFields,
  tableBlock,
  getTabFilter,
  buildFiltersParam,
  modelFieldMapRef,
  keywordRef,
  namedQueryCode,
  token,
  setDataReady,
  t,
  loadDataRef,
  clearSortOverrides,
  clearFilterOverrides,
  savedViews,
  applyViewConfigToListState,
  setPendingViewConfig,
  selectDefaultView,
  setActiveQuickFilter,
  clearKeyword,
  setActiveViewType,
  setSearchParams,
  listSchemaFieldNameSet,
  pendingSavedViewFiltersRef,
}: ListDataLoaderInput) {
  // Load data from API - P2-1 fix: use destructured pagination
  const loadData = useCallback(
    async (params?: ListLoadDataParams) => {
      // Claim the newest slot before doing anything; an older invocation that
      // resolves later must not touch table state (see loadDataSeqRef).
      const requestId = ++loadDataSeqRef.current;
      // Sibling flows below share the stale-response guard name against the
      // canonical loadData sequence (PD-012).
      const isLatestRequest = () => requestId === loadDataSeqRef.current;
      if (!schema || skipListData) {
        setData([]);
        setError(null);
        setLoading(false);
        setPageState((prev) => ({
          ...prev,
          pagination: {
            ...prev.pagination,
            total: 0,
          },
        }));
        return;
      }

      clearApiReadFailure();
      try {
        setLoading(true);
        setError(null);

        // Resolve endpoint: use API datasource endpoint if configured, else default dynamic table
        let endpoint: string;
        let method: 'get' | 'post' | 'put' | 'delete' | 'patch' | 'options' = 'get';
        const isApiDatasource = schema.dataSource?.type === 'api' && schema.dataSource.endpoint;
        const retryRequest = isApiDatasource ? params?.retryRequest : undefined;
        if (isApiDatasource) {
          endpoint = retryRequest?.endpoint ?? schema.dataSource!.endpoint!;
          method = retryRequest?.method ?? ((schema.dataSource!.method as typeof method) || 'get');
        } else {
          // Use schema.modelCode for API endpoint when available (supports NQ-backed pages
          // where URL tableName differs from actual model, e.g., /p/crm-my-tasks → crm_activity_common)
          const apiTableName = schema.modelCode ? schema.modelCode : tableName;
          endpoint = `${buildApiEndpoint(apiTableName)}/list`;
        }

        // Capture only explicitly submitted filters before issuing the request.
        // Pending filter-form edits do not participate in keyword-only searches.
        if (params?.filters !== undefined) rememberSubmittedFilters(params.filters);
        // Mutation refreshes keep the applied query; pending form edits remain unsubmitted.
        const submittedFilters = params?.filters ?? getSubmittedFilters();

        const requestedPageNum = retryRequest
          ? Number(retryRequest.queryParams.page) + 1
          : (params?.page ?? pagination.current - 1) + 1;
        const requestedPageSize = retryRequest
          ? Number(retryRequest.queryParams.size)
          : params?.size ?? pagination.pageSize;
        const requestedPageZeroBased = Math.max(requestedPageNum - 1, 0);
        const requestedSorts = params?.sorts ?? activeSorts;
        if (!retryRequest) {
          lastScheduledSortFilterValuesRef.current = {
            activeSorts: requestedSorts,
            chipFilters: params?.chipFilters ?? chipFiltersRef.current,
          };
        }
        const queryParams: Record<string, any> = retryRequest
          ? structuredClone(retryRequest.queryParams) : {};

        if (!retryRequest) {
          if (isApiDatasource) {
            queryParams.page = requestedPageZeroBased;
            queryParams.size = requestedPageSize;
          } else {
            queryParams.pageNum = requestedPageNum;
            queryParams.pageSize = requestedPageSize;
            if (auditUserDisplayFields) {
              queryParams.auditUserDisplayFields = auditUserDisplayFields;
            }
          }

          if (isApiDatasource) {
            // For API datasources, pass filter values as individual query params
            if (submittedFilters) {
              for (const [key, value] of Object.entries(submittedFilters)) {
                if (value == null || value === '') continue;
                // Handle date range objects { start, end }
                if (typeof value === 'object' && ('start' in value || 'end' in value)) {
                  if (value.start) queryParams[`${key}_start`] = String(value.start);
                  if (value.end) queryParams[`${key}_end`] = String(value.end);
                } else {
                  queryParams[key] = String(value);
                }
              }
            }
            if (requestedSorts.length === 1) {
              queryParams.sortField = requestedSorts[0].fieldCode;
              queryParams.sortOrder = requestedSorts[0].direction;
            } else if (tableBlock?.defaultSort?.field) {
              queryParams.sortField = tableBlock.defaultSort.field;
              queryParams.sortOrder = String(tableBlock.defaultSort.order || 'desc').toLowerCase();
            }
          } else {
            // For standard dynamic tables, use JSON filters array
            const tabCondition = getTabFilter();
            const filtersParam = buildFiltersParam(
              tabCondition,
              submittedFilters,
              params?.chipFilters ?? chipFiltersRef.current,
              modelFieldMapRef.current,
            );
            if (filtersParam) {
              queryParams.filters = filtersParam;
            }
            // Use active sorts (user-driven) > SavedView sorts > DSL defaultSort
            // Multi-field sort: use sortFields param (field:direction pairs) when >1 sort
            if (requestedSorts.length > 1) {
              queryParams.sortFields = requestedSorts
                .map((s) => `${s.fieldCode}:${s.direction}`)
                .join(',');
            } else if (requestedSorts.length === 1) {
              queryParams.sortField = requestedSorts[0].fieldCode;
              queryParams.sortOrder = requestedSorts[0].direction;
            } else if (tableBlock?.defaultSort?.field) {
              queryParams.sortField = tableBlock.defaultSort.field;
              queryParams.sortOrder = String(tableBlock.defaultSort.order || 'desc').toLowerCase();
            }
          }
        }

        const fetchCurrentActorRequest = (request: ListApiReadRequest) => (
          fetchResult<PaginationResult<DynamicEntity>>(request.endpoint, {
            method: request.method,
            params: request.queryParams,
            token: token || undefined,
          })
        );
        const request = completeListReadRequest({ endpoint, method, queryParams }, {
          keyword: keywordRef.current,
          queryCode: namedQueryCode,
          apiDatasource: Boolean(isApiDatasource),
          retry: Boolean(retryRequest),
        });
        const result = isApiDatasource
          ? await readApiRequest(request, isLatestRequest, fetchCurrentActorRequest)
          : await fetchCurrentActorRequest(request);

        // A newer loadData was issued while this request was in flight — this
        // response (and its pagination) is stale; drop it instead of letting
        // it overwrite the newer render.
        if (requestId !== loadDataSeqRef.current) {
          return;
        }

        if (ResultHelper.isSuccess(result) && result.data) {
          // Handle both paginated ({ records, total, current }) and flat array responses
          const responseData = result.data;
          if (Array.isArray(responseData)) {
            // API returned flat array — client-side pagination
            const start = requestedPageZeroBased * requestedPageSize;
            const sliced = responseData.slice(start, start + requestedPageSize);
            setData(sliced as DynamicEntity[]);
            setDataReady(true);
            setPageState((prev) => ({
              ...prev,
              pagination: {
                ...prev.pagination,
                total: responseData.length,
                current: requestedPageNum,
              },
            }));
          } else {
            const records = responseData.records ?? [];
            const currentPage = Number(responseData.page ?? requestedPageNum) || requestedPageNum;
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
        // A stale request's error must not clobber the newer request's state.
        if (requestId !== loadDataSeqRef.current) {
          return;
        }
        setError(resolveListReadErrorMessage(err, locale, t));
      } finally {
        // Only the newest invocation owns the loading flag; an older one that
        // resolves late must not clear the newer request's spinner.
        if (requestId === loadDataSeqRef.current) {
          setLoading(false);
        }
      }
    },
    [
      schema,
      tableName,
      token,
      rememberSubmittedFilters,
      getSubmittedFilters,
      readApiRequest,
      clearApiReadFailure,
      pagination.current,
      pagination.pageSize,
      getTabFilter,
      buildFiltersParam,
      namedQueryCode,
      tableBlock,
      auditUserDisplayFields,
      activeSorts,
      skipListData,
    ],
  );

  useEffect(() => {
    loadDataRef.current = loadData;
  }, [loadData]);

  const handleSelectDefaultView = useCallback(() => {
    clearSortOverrides();
    clearFilterOverrides();
    const implicitDefaultView =
      savedViews.find((view) => view.scope === 'personal' && isImplicitSavedView(view)) ?? null;
    const implicitViewConfig = implicitDefaultView?.viewConfig;
    const restoredFilters = applyViewConfigToListState(implicitViewConfig);
    const restoredSorts = implicitViewConfig?.sorts ?? [];
    setPendingViewConfig(null);
    selectDefaultView();
    setActiveQuickFilter(null);
    clearKeyword();
    setActiveViewType((implicitDefaultView?.viewType as ViewType) || 'table');
    setSearchParams(
      (prev) => {
        const p = new URLSearchParams(prev);
        p.delete('view');
        clearTransientViewSearchParams(p, listSchemaFieldNameSet);
        return p;
      },
      { replace: true },
    );
    loadData({
      page: 0,
      size: pagination.pageSize,
      filters: restoredFilters,
      sorts: restoredSorts,
    });
  }, [
    applyViewConfigToListState,
    clearSortOverrides,
    clearFilterOverrides,
    clearKeyword,
    loadData,
    pagination.pageSize,
    savedViews,
    selectDefaultView,
    setSearchParams,
  ]);

  useEffect(() => {
    const restoredFilters = pendingSavedViewFiltersRef.current;
    if (!restoredFilters) return;
    pendingSavedViewFiltersRef.current = null;
    // Hydration can finish after the user changes pages. Reload the current
    // page with the restored filters rather than superseding that newer request
    // with page one. The latest loadData closure retains effective URL sorts.
    loadDataRef.current?.({
      page: pagination.current - 1,
      size: pagination.pageSize,
      filters: restoredFilters,
    });
  }, [loadData, pagination.current, pagination.pageSize]);

  return { loadData, handleSelectDefaultView };
}
