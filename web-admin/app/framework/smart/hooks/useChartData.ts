/**
 * useChartData Hook
 *
 * React hook for fetching and managing chart data from the aggregate query API.
 * Supports automatic refresh, drill-down filters, and linkage filters.
 */

import { useState, useEffect, useCallback, useRef, useMemo } from 'react';
import { chartDataService } from '~/shared/services/chartDataService';
import type {
  ChartDataSource,
  AggregateQueryResponse,
  AggregateQueryRequest,
  FilterConfig,
} from '~/framework/smart/types/chart';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useDimensionLabels } from './useDimensionLabels';

type ApiDataPayload =
  | { records?: Record<string, unknown>[]; rows?: Record<string, unknown>[] }
  | Record<string, unknown>[]
  | Record<string, unknown>
  | null
  | undefined;

const apiInFlight = new Map<string, Promise<AggregateQueryResponse>>();
const apiResolvedCache = new Map<string, { data: AggregateQueryResponse; expiresAt: number }>();
const API_RESOLVED_CACHE_MS = 1000;

function stableStringify(value: unknown): string {
  if (value === null || value === undefined) return '';
  if (Array.isArray(value)) {
    return `[${value.map((item) => stableStringify(item)).join(',')}]`;
  }
  if (typeof value === 'object') {
    const record = value as Record<string, unknown>;
    return `{${Object.keys(record)
      .sort()
      .map((key) => `${JSON.stringify(key)}:${stableStringify(record[key])}`)
      .join(',')}}`;
  }
  return JSON.stringify(value);
}

function serializeApiParams(params?: Record<string, unknown>): string {
  const normalized = Object.fromEntries(
    Object.entries(params ?? {})
      .sort(([left], [right]) => left.localeCompare(right))
      .map(([key, value]) => [key, String(value)]),
  );
  return JSON.stringify(normalized);
}

function parseApiParams(paramsKey: string): Record<string, string> {
  return paramsKey ? (JSON.parse(paramsKey) as Record<string, string>) : {};
}

function apiRequestKey(url: string, paramsKey: string): string {
  return `${url}?${paramsKey}`;
}

function normalizeApiRows(payload: ApiDataPayload): Record<string, unknown>[] {
  if (Array.isArray(payload)) return payload;
  if (!payload || typeof payload !== 'object') return [];
  if (Array.isArray(payload.records)) return payload.records;
  if (Array.isArray(payload.rows)) return payload.rows;
  return [payload as Record<string, unknown>];
}

function inferApiMeta(rows: Record<string, unknown>[]): AggregateQueryResponse['meta'] {
  return {
    dimensions: [],
    metrics: rows[0] ? Object.keys(rows[0]) : [],
  };
}

async function fetchApiChartData(url: string, paramsKey: string): Promise<AggregateQueryResponse> {
  const key = apiRequestKey(url, paramsKey);
  const cached = apiResolvedCache.get(key);
  if (cached && cached.expiresAt > Date.now()) {
    return cached.data;
  }
  apiResolvedCache.delete(key);

  const existing = apiInFlight.get(key);
  if (existing) return existing;

  const promise = fetchResult<ApiDataPayload>(url, {
    method: 'get',
    params: parseApiParams(paramsKey),
  })
    .then((result) => {
      if (!ResultHelper.isSuccess(result)) {
        throw new Error(result.message || result.desc || `Failed to load API data from ${url}`);
      }
      const rows = normalizeApiRows(result.data);
      return {
        rows,
        summary: {},
        meta: inferApiMeta(rows),
      };
    })
    .then((response) => {
      apiResolvedCache.set(key, {
        data: response,
        expiresAt: Date.now() + API_RESOLVED_CACHE_MS,
      });
      return response;
    })
    .finally(() => {
      apiInFlight.delete(key);
    });

  apiInFlight.set(key, promise);
  return promise;
}

function isDataSourceComplete(dataSource: ChartDataSource | undefined): boolean {
  if (!dataSource) return false;

  switch (dataSource.type) {
    case 'aggregate':
      return !!(dataSource.modelCode && dataSource.metrics?.length);
    case 'namedQuery':
      return !!dataSource.queryCode;
    case 'api':
      return !!dataSource.url;
    case 'static':
      return true;
    default:
      return false;
  }
}

/**
 * Options for the useChartData hook
 */
export interface UseChartDataOptions {
  /** Data source configuration */
  dataSource: ChartDataSource;
  /** Drill-down filter conditions */
  drillFilters?: FilterConfig[];
  /** Linkage filter conditions from other charts */
  linkageFilters?: FilterConfig[];
  /** Auto-refresh interval in milliseconds (0 or undefined to disable) */
  refreshInterval?: number;
  /** Whether to enable data fetching (default: true) */
  enabled?: boolean;
}

/**
 * Return type for the useChartData hook
 */
export interface UseChartDataResult {
  /** Fetched data or null if not yet loaded */
  data: AggregateQueryResponse | null;
  /** Loading state */
  loading: boolean;
  /** Error object if request failed */
  error: Error | null;
  /** Function to manually trigger data refresh */
  refetch: () => Promise<void>;
}

/**
 * Hook for fetching chart data with support for filtering and auto-refresh
 *
 * @param options - Hook configuration options
 * @returns Object containing data, loading state, error, and refetch function
 *
 * @example
 * // Basic usage
 * const { data, loading, error } = useChartData({
 *   dataSource: {
 *     type: 'aggregate',
 *     modelCode: 'order',
 *     dimensions: ['status'],
 *     metrics: [{ field: 'id', aggregation: 'count', alias: 'count' }],
 *   },
 * });
 *
 * @example
 * // With auto-refresh and linkage filters
 * const { data, loading, refetch } = useChartData({
 *   dataSource: {
 *     type: 'aggregate',
 *     modelCode: 'order',
 *     dimensions: ['created_date'],
 *     metrics: [{ field: 'amount', aggregation: 'sum', alias: 'total' }],
 *   },
 *   linkageFilters: [{ field: 'status', operator: 'eq', value: 'completed' }],
 *   refreshInterval: 30000, // 30 seconds
 * });
 */
export function useChartData(options: UseChartDataOptions): UseChartDataResult {
  const { dataSource, drillFilters, linkageFilters, refreshInterval, enabled = true } = options;

  const [data, setData] = useState<AggregateQueryResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const abortControllerRef = useRef<AbortController | null>(null);
  const mountedRef = useRef(true);
  const dataSourceRef = useRef(dataSource);
  const drillFiltersRef = useRef(drillFilters);
  const linkageFiltersRef = useRef(linkageFilters);
  const dataSourceKey = stableStringify(dataSource);
  const drillFiltersKey = stableStringify(drillFilters);
  const linkageFiltersKey = stableStringify(linkageFilters);
  const apiParamsKey = useMemo(
    () => (dataSource?.type === 'api' ? serializeApiParams(dataSource.params) : ''),
    [dataSource?.type, dataSource?.params],
  );

  useEffect(() => {
    dataSourceRef.current = dataSource;
    drillFiltersRef.current = drillFilters;
    linkageFiltersRef.current = linkageFilters;
  }, [dataSource, drillFilters, linkageFilters]);

  // Static sources resolve SYNCHRONOUSLY (no fetch) — return the data on the very first
  // render so ECharts mounts with data and never caches an empty scale. Without this the
  // async setData path left charts mis-scaled in animated / small containers (Slice D:
  // ad-hoc chat-bi charts rendered tiny). Fetch sources (aggregate / namedQuery / api)
  // are unchanged and stay async.
  const isStatic = dataSource?.type === 'static';
  const staticData = useMemo<AggregateQueryResponse | null>(() => {
    if (!isStatic || !enabled) return null;
    return {
      rows: dataSource.staticData || [],
      summary: {},
      meta: {
        dimensions: dataSource.dimensions || [],
        metrics: dataSource.metrics?.map((m) => m.alias || m.field) || [],
      },
    } as AggregateQueryResponse;
  }, [isStatic, enabled, dataSource]);

  /**
   * Fetch data from the API
   */
  const fetchData = useCallback(async () => {
    const currentDataSource = dataSourceRef.current;
    const currentDrillFilters = drillFiltersRef.current;
    const currentLinkageFilters = linkageFiltersRef.current;

    if (!enabled) {
      return;
    }

    if (!currentDataSource) {
      setData(null);
      setLoading(false);
      return;
    }

    if (currentDataSource.type === 'static') {
      // Handled synchronously by the staticData useMemo + the return below. No async fetch.
      return;
    }

    // Skip fetch if data source configuration is incomplete
    if (!isDataSourceComplete(currentDataSource)) {
      setData(null);
      setLoading(false);
      return;
    }

    // Abort any pending request
    if (abortControllerRef.current) {
      abortControllerRef.current.abort();
    }
    abortControllerRef.current = new AbortController();

    setLoading(true);
    setError(null);

    try {
      if (currentDataSource.type === 'api') {
        const response = await fetchApiChartData(currentDataSource.url!, apiParamsKey);
        if (mountedRef.current) {
          setData(response);
          setError(null);
        }
        return;
      }

      // Build the request from data source configuration
      const request: AggregateQueryRequest = {
        type: currentDataSource.type === 'namedQuery' ? 'namedQuery' : 'aggregate',
        modelCode: currentDataSource.modelCode,
        queryCode: currentDataSource.queryCode,
        dimensions: currentDataSource.dimensions,
        metrics: currentDataSource.metrics,
        filters: [...(currentDataSource.filters || []), ...(currentLinkageFilters || [])],
        parameters: currentDataSource.parameters,
        orderBy: currentDataSource.orderBy,
        limit: currentDataSource.limit,
        drillFilters: currentDrillFilters,
        // When a semantic model is configured, pass it through so the backend
        // delegates to SemanticQueryService instead of the raw SQL path.
        ...(currentDataSource.semanticModelCode
          ? { semanticModelCode: currentDataSource.semanticModelCode }
          : {}),
      };

      const response = await chartDataService.fetchChartData(request);

      // Only update state if component is still mounted
      if (mountedRef.current) {
        setData(response);
        setError(null);
      }
    } catch (err) {
      // Ignore abort errors
      if (err instanceof Error && err.name === 'AbortError') {
        return;
      }

      if (mountedRef.current) {
        setError(err instanceof Error ? err : new Error('Unknown error'));
        setData(null);
      }
    } finally {
      if (mountedRef.current) {
        setLoading(false);
      }
    }
  }, [apiParamsKey, dataSourceKey, drillFiltersKey, enabled, linkageFiltersKey]);

  /**
   * Effect for initial fetch and dependency changes
   */
  useEffect(() => {
    fetchData();
  }, [fetchData]);

  /**
   * Effect for auto-refresh
   */
  useEffect(() => {
    if (!refreshInterval || refreshInterval <= 0 || !enabled) {
      return;
    }

    const timer = setInterval(fetchData, refreshInterval);

    return () => {
      clearInterval(timer);
    };
  }, [fetchData, refreshInterval, enabled]);

  /**
   * Cleanup effect
   */
  useEffect(() => {
    mountedRef.current = true;

    return () => {
      mountedRef.current = false;
      if (abortControllerRef.current) {
        abortControllerRef.current.abort();
      }
    };
  }, []);

  const resolved = isStatic ? staticData : data;

  // Dict labels for dict-coded dimension values. Resolved here rather than in each
  // chart so every component gets them for free — the aggregate response only ever
  // carries raw codes, and a dozen components were each rendering them verbatim.
  const dimensionLabels = useDimensionLabels(dataSource, resolved?.meta?.dimensions);

  const dataWithLabels = useMemo<AggregateQueryResponse | null>(() => {
    if (!resolved) return null;
    if (Object.keys(dimensionLabels).length === 0) return resolved;
    return { ...resolved, meta: { ...resolved.meta, dimensionLabels } };
  }, [resolved, dimensionLabels]);

  return {
    data: dataWithLabels,
    loading: isStatic ? false : loading,
    error: isStatic ? null : error,
    refetch: fetchData,
  };
}

export default useChartData;
