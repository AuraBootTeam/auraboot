/**
 * useChartData Hook
 *
 * React hook for fetching and managing chart data from the aggregate query API.
 * Supports automatic refresh, drill-down filters, and linkage filters.
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import { chartDataService } from '~/services/chartDataService';
import type {
  ChartDataSource,
  AggregateQueryResponse,
  AggregateQueryRequest,
  FilterConfig,
} from '~/framework/smart/types/chart';

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

  /**
   * Check if data source configuration is complete enough to fetch data
   */
  const isDataSourceComplete = useCallback(() => {
    if (!dataSource) return false;

    switch (dataSource.type) {
      case 'aggregate':
        // Need modelCode and at least one metric for aggregate queries
        return !!(dataSource.modelCode && dataSource.metrics?.length);
      case 'namedQuery':
        // Need queryCode for named queries
        return !!dataSource.queryCode;
      case 'static':
        // Static data is always "complete" (handled separately)
        return true;
      default:
        return false;
    }
  }, [dataSource]);

  /**
   * Fetch data from the API
   */
  const fetchData = useCallback(async () => {
    // Skip fetch for static data sources or when disabled
    if (!enabled || dataSource.type === 'static') {
      if (dataSource.type === 'static' && dataSource.staticData) {
        setData({
          rows: dataSource.staticData,
          summary: {},
          meta: {
            dimensions: dataSource.dimensions || [],
            metrics: dataSource.metrics?.map((m) => m.alias || m.field) || [],
          },
        });
      }
      return;
    }

    // Skip fetch if data source configuration is incomplete
    if (!isDataSourceComplete()) {
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
      // Build the request from data source configuration
      const request: AggregateQueryRequest = {
        type: dataSource.type,
        modelCode: dataSource.modelCode,
        queryCode: dataSource.queryCode,
        dimensions: dataSource.dimensions,
        metrics: dataSource.metrics,
        filters: [...(dataSource.filters || []), ...(linkageFilters || [])],
        parameters: dataSource.parameters,
        limit: dataSource.limit,
        drillFilters,
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
  }, [dataSource, drillFilters, linkageFilters, enabled, isDataSourceComplete]);

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

  return {
    data,
    loading,
    error,
    refetch: fetchData,
  };
}

export default useChartData;
