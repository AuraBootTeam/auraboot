/**
 * useListData — data loading + pagination + sorting for list pages
 *
 * Handles:
 * - Standard dynamic table API (/api/dynamic/{slug}/list)
 * - API datasource (schema.dataSource.type === 'api')
 * - NamedQuery datasource
 * - Tab filter integration
 * - Flat array client-side pagination
 */

import { useState, useCallback, useEffect, useMemo } from 'react';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { buildApiEndpoint } from '~/routes/_shared/dynamic-route-utils';
import type { UnifiedSchema } from '~/framework/meta/schemas/types';
import type { ViewFilterConfig, SortConfig } from '~/framework/smart/types/savedView';

interface DynamicEntity {
  [key: string]: any;
  id?: string;
  pid?: string;
}

interface PaginationState {
  current: number;
  pageSize: number;
  total: number;
}

interface PaginationResult<T> {
  records: T[];
  total: number;
  page: number;
  pageSize: number;
  totalPages: number;
}

export interface TabFilter {
  fieldName: string;
  operator: string;
  value: string;
}

export interface UseListDataOptions {
  schema: UnifiedSchema | null;
  tableName: string;
  token?: string;
  /** Initial page size (can be overridden by DSL) */
  initialPageSize?: number;
  /**
   * Parent-scope filters AND-merged into every request and not user-clearable
   * (e.g. `{ bom_std_task_id: <currentTaskId> }` for an embedded list bound to
   * the surrounding detail record). These always win over user/tab filters of
   * the same field so the embedded scope can never be widened from the toolbar.
   */
  fixedFilters?: Record<string, any>;
}

export interface UseListDataResult {
  data: DynamicEntity[];
  loading: boolean;
  error: string | null;
  filters: Record<string, any>;
  setFilters: (filters: Record<string, any>) => void;
  pagination: PaginationState;
  setPagination: (fn: (prev: PaginationState) => PaginationState) => void;
  loadData: (params?: {
    page?: number;
    size?: number;
    filters?: Record<string, any>;
    tabFilter?: TabFilter | null;
    chipFilters?: ViewFilterConfig[];
    keyword?: string;
    sorts?: SortConfig[];
  }) => Promise<void>;
  handlePageChange: (page: number) => void;
}

export function useListData({
  schema,
  tableName,
  token,
  initialPageSize = 20,
  fixedFilters,
}: UseListDataOptions): UseListDataResult {
  const [data, setData] = useState<DynamicEntity[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [pageState, setPageState] = useState({
    filters: {} as Record<string, any>,
    pagination: {
      current: 1,
      pageSize: initialPageSize,
      total: 0,
    } as PaginationState,
  });

  const { filters, pagination } = pageState;

  const setFilters = useCallback((filters: Record<string, any>) => {
    setPageState((prev) => ({ ...prev, filters }));
  }, []);

  const setPagination = useCallback((fn: (prev: PaginationState) => PaginationState) => {
    setPageState((prev) => ({
      ...prev,
      pagination: fn(prev.pagination),
    }));
  }, []);

  // Detect namedQuery
  const namedQueryCode = useMemo(() => {
    if (schema?.dataSource?.type === 'namedQuery' && schema.dataSource.queryCode) {
      return schema.dataSource.queryCode;
    }
    return null;
  }, [schema]);

  // Build filters JSON array
  const buildFiltersParam = useCallback(
    (
      tabCondition: TabFilter | null,
      userFilters?: Record<string, any>,
      chipFilters?: ViewFilterConfig[],
    ) => {
      const conditions: Array<{ fieldName: string; operator: string; value: string }> = [];
      if (tabCondition) conditions.push(tabCondition);
      if (userFilters) {
        for (const [key, value] of Object.entries(userFilters)) {
          if (value == null || value === '') continue;
          if (typeof value === 'object' && ('start' in value || 'end' in value)) {
            if (value.start)
              conditions.push({ fieldName: key, operator: 'gte', value: String(value.start) });
            if (value.end)
              conditions.push({ fieldName: key, operator: 'lte', value: String(value.end) });
          } else {
            conditions.push({ fieldName: key, operator: 'EQ', value: String(value) });
          }
        }
      }
      // Chip filters carry an explicit operator (eq/like/gt/.../isNull). LIKE
      // wraps the value with % wildcards; unary null operators need no value.
      if (chipFilters) {
        for (const cf of chipFilters) {
          if (!cf.fieldCode) continue;
          const op = (cf.operator || 'eq').toUpperCase();
          if (op === 'ISNULL' || op === 'ISNOTNULL') {
            conditions.push({ fieldName: cf.fieldCode, operator: op, value: '' });
            continue;
          }
          if (cf.value == null || cf.value === '') continue;
          const val = String(cf.value);
          conditions.push({
            fieldName: cf.fieldCode,
            operator: op,
            value: op === 'LIKE' ? `%${val}%` : val,
          });
        }
      }
      return conditions.length > 0 ? JSON.stringify(conditions) : undefined;
    },
    [],
  );

  const loadData = useCallback(
    async (params?: {
      page?: number;
      size?: number;
      filters?: Record<string, any>;
      tabFilter?: TabFilter | null;
      chipFilters?: ViewFilterConfig[];
      keyword?: string;
      sorts?: SortConfig[];
    }) => {
      if (!schema) return;

      try {
        setLoading(true);
        setError(null);

        const isApiDatasource = schema.dataSource?.type === 'api' && schema.dataSource.endpoint;
        const endpoint = isApiDatasource
          ? schema.dataSource!.endpoint!
          : `${buildApiEndpoint(tableName)}/list`;
        const method = isApiDatasource
          ? (schema.dataSource!.method as 'get' | 'post') || 'get'
          : 'get';

        const requestedPageNum = (params?.page ?? pagination.current - 1) + 1;
        const requestedPageSize = params?.size ?? pagination.pageSize;
        const requestedPageZeroBased = Math.max(requestedPageNum - 1, 0);
        const queryParams: Record<string, any> = {};

        if (isApiDatasource) {
          queryParams.page = requestedPageZeroBased;
          queryParams.size = requestedPageSize;
        } else {
          queryParams.pageNum = requestedPageNum;
          queryParams.pageSize = requestedPageSize;
        }

        // Parent-scope filters always win over user/tab filters of the same field.
        const effectiveFilters = { ...(params?.filters ?? filters), ...(fixedFilters ?? {}) };

        if (isApiDatasource) {
          // API datasource: pass filter params individually (not JSON array)
          const userFilters = effectiveFilters;
          for (const [key, value] of Object.entries(userFilters)) {
            if (value == null || value === '') continue;
            if (typeof value === 'object' && ('start' in value || 'end' in value)) {
              if (value.start) queryParams[`${key}_start`] = String(value.start);
              if (value.end) queryParams[`${key}_end`] = String(value.end);
            } else {
              queryParams[key] = String(value);
            }
          }
        } else {
          // Standard dynamic table: pass filters as JSON array
          const filtersParam = buildFiltersParam(
            params?.tabFilter ?? null,
            effectiveFilters,
            params?.chipFilters,
          );
          if (filtersParam) queryParams.filters = filtersParam;

          // Free-text search + sort use the dynamic list contract param names
          // (keyword / sortField / sortOrder — see DynamicController).
          const keyword = params?.keyword?.trim();
          if (keyword) queryParams.keyword = keyword;
          const primarySort = (params?.sorts ?? [])
            .slice()
            .sort((a, b) => (a.priority ?? 0) - (b.priority ?? 0))[0];
          if (primarySort?.fieldCode) {
            queryParams.sortField = primarySort.fieldCode;
            queryParams.sortOrder = primarySort.direction;
          }
        }

        if (namedQueryCode) queryParams.queryCode = namedQueryCode;

        const result = await fetchResult<PaginationResult<DynamicEntity>>(endpoint, {
          method,
          params: queryParams,
          token,
        });

        if (ResultHelper.isSuccess(result) && result.data) {
          const responseData = result.data;
          if (Array.isArray(responseData)) {
            // Flat array response — apply client-side pagination
            const start = requestedPageZeroBased * requestedPageSize;
            const sliced = (responseData as any[]).slice(start, start + requestedPageSize);
            setData(sliced as DynamicEntity[]);
            setPageState((prev) => ({
              ...prev,
              pagination: {
                ...prev.pagination,
                total: (responseData as any[]).length,
                current: requestedPageNum,
              },
            }));
          } else {
            // Standard paginated response
            const records = responseData.records ?? [];
            const currentPage = Number(responseData.page ?? requestedPageNum) || requestedPageNum;
            const total = Number(responseData.total ?? 0);
            setData(records);
            setPageState((prev) => ({
              ...prev,
              pagination: { ...prev.pagination, total, current: currentPage },
            }));
          }
        } else {
          setError((result as any).desc || 'Failed to load data');
        }
      } catch (err) {
        setError(err instanceof Error ? err.message : 'Failed to load data');
      } finally {
        setLoading(false);
      }
    },
    [
      schema,
      tableName,
      token,
      pagination.current,
      pagination.pageSize,
      filters,
      fixedFilters,
      buildFiltersParam,
      namedQueryCode,
    ],
  );

  // Apply DSL pagination.pageSize when schema loads
  useEffect(() => {
    if (!schema?.blocks) return;
    const tableBlock = schema.blocks.find((block) => block.blockType === 'table');
    const dslPageSize = tableBlock?.table?.pagination?.pageSize;
    if (dslPageSize && dslPageSize > 0) {
      setPagination((prev) => ({ ...prev, pageSize: dslPageSize }));
    }
  }, [schema, setPagination]);

  const handlePageChange = useCallback(
    (page: number) => {
      setPagination((prev) => ({ ...prev, current: page }));
      loadData({ page: page - 1, filters });
    },
    [filters, loadData, setPagination],
  );

  return {
    data,
    loading,
    error,
    filters,
    setFilters,
    pagination,
    setPagination,
    loadData,
    handlePageChange,
  };
}
