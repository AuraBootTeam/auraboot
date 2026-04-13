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
import { fetchResult } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';
import { buildApiEndpoint } from '~/routes/_shared/dynamic-route-utils';
import type { UnifiedSchema } from '~/meta/schemas/types';

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
  }) => Promise<void>;
  handlePageChange: (page: number) => void;
}

export function useListData({
  schema,
  tableName,
  token,
  initialPageSize = 20,
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
    (tabCondition: TabFilter | null, userFilters?: Record<string, any>) => {
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

        if (isApiDatasource) {
          // API datasource: pass filter params individually (not JSON array)
          const userFilters = params?.filters ?? filters;
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
            params?.filters ?? filters,
          );
          if (filtersParam) queryParams.filters = filtersParam;
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
