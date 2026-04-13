/**
 * useKanbanData Hook
 *
 * React hook for fetching and managing Kanban board data.
 * Groups data by a specified field to create columns and supports
 * optimistic updates for card movements.
 */

import { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import { chartDataService } from '~/services/chartDataService';
import { fetchResult } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';
import type {
  KanbanDataSource,
  KanbanColumn,
  KanbanCard,
  KanbanAggregation,
} from '~/smart/types/kanban';
import type { AggregateQueryRequest, FilterConfig } from '~/smart/types/chart';

/**
 * Options for the useKanbanData hook
 */
export interface UseKanbanDataOptions {
  /** Kanban data source configuration */
  dataSource: KanbanDataSource;
  /** Linkage filter conditions from other components */
  linkageFilters?: FilterConfig[];
  /** Whether to enable data fetching (default: true) */
  enabled?: boolean;
}

/**
 * Return type for the useKanbanData hook
 */
export interface UseKanbanDataResult {
  /** Kanban columns with grouped cards */
  columns: KanbanColumn[];
  /** Loading state */
  loading: boolean;
  /** Error object if request failed */
  error: Error | null;
  /** Function to manually trigger data refresh */
  refetch: () => Promise<void>;
  /** Function to optimistically move a card between columns */
  moveCard: (
    cardId: string,
    sourceColumnId: string,
    targetColumnId: string,
    targetIndex: number,
  ) => void;
}

/**
 * Calculate aggregation value for a set of cards
 */
function calculateAggregation(cards: KanbanCard[], aggregation: KanbanAggregation): number {
  const { field, function: aggFunc } = aggregation;

  if (aggFunc === 'count') {
    return cards.length;
  }

  const values = cards.map((card) => card[field]).filter((v): v is number => typeof v === 'number');

  if (values.length === 0) {
    return 0;
  }

  switch (aggFunc) {
    case 'sum':
      return values.reduce((sum, v) => sum + v, 0);
    case 'avg':
      return values.reduce((sum, v) => sum + v, 0) / values.length;
    case 'min':
      return Math.min(...values);
    case 'max':
      return Math.max(...values);
    default:
      return 0;
  }
}

/**
 * Hook for fetching and managing Kanban board data
 *
 * @param options - Hook configuration options
 * @returns Object containing columns, loading state, error, refetch, and moveCard functions
 *
 * @example
 * // Basic usage
 * const { columns, loading, error, moveCard } = useKanbanData({
 *   dataSource: {
 *     type: 'aggregate',
 *     modelCode: 'task',
 *     groupByField: 'status',
 *     titleField: 'title',
 *   },
 * });
 *
 * @example
 * // With linkage filters
 * const { columns, refetch } = useKanbanData({
 *   dataSource: {
 *     type: 'aggregate',
 *     modelCode: 'task',
 *     groupByField: 'status',
 *     titleField: 'title',
 *     aggregations: [{ field: 'storyPoints', function: 'sum', label: 'Points' }],
 *   },
 *   linkageFilters: [{ field: 'projectId', operator: 'eq', value: '123' }],
 * });
 */
export function useKanbanData(options: UseKanbanDataOptions): UseKanbanDataResult {
  const { dataSource, linkageFilters, enabled = true } = options;

  const [rawData, setRawData] = useState<Record<string, unknown>[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const abortControllerRef = useRef<AbortController | null>(null);
  const mountedRef = useRef(true);

  const { groupByField, idField = 'id', aggregations } = dataSource;

  /**
   * Fetch data from the API
   */
  const fetchData = useCallback(async () => {
    // Skip fetch when disabled
    if (!enabled) {
      return;
    }

    // Handle static data source
    if (dataSource.type === 'static' && dataSource.staticData) {
      setRawData(dataSource.staticData);
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
      let rows: Record<string, unknown>[] = [];

      if (dataSource.type === 'aggregate' && dataSource.modelCode) {
        // For aggregate type with a modelCode, fetch individual records
        // from the dynamic list API (not the chart-data aggregate endpoint)
        const slug = dataSource.modelCode;
        const params: Record<string, string> = {
          pageSize: String(dataSource.limit || 500),
          pageNum: '1',
        };

        // Build filters JSON
        const allFilters = [...(dataSource.filters || []), ...(linkageFilters || [])];
        if (allFilters.length > 0) {
          params.filters = JSON.stringify(
            allFilters.map((f) => ({
              fieldName: f.field,
              operator: f.operator?.toUpperCase() || 'EQ',
              value: f.value,
            })),
          );
        }

        const result = await fetchResult<any>(`/api/dynamic/${slug}/list`, {
          method: 'get',
          params,
        });
        if (ResultHelper.isSuccess(result) && result.data?.records) {
          rows = result.data.records;
        }
      } else {
        // For namedQuery or other types, use the chart-data aggregate endpoint
        const dimensions = [
          groupByField,
          ...(dataSource.dimensions || []).filter((d) => d !== groupByField),
        ];

        const request: AggregateQueryRequest = {
          type: dataSource.type as 'aggregate' | 'namedQuery',
          modelCode: dataSource.modelCode,
          queryCode: dataSource.queryCode,
          dimensions,
          metrics: dataSource.metrics,
          filters: [...(dataSource.filters || []), ...(linkageFilters || [])],
          parameters: dataSource.parameters,
          limit: dataSource.limit,
        };

        const response = await chartDataService.fetchChartData(request);
        rows = response.rows;
      }

      // Only update state if component is still mounted
      if (mountedRef.current) {
        setRawData(rows);
        setError(null);
      }
    } catch (err) {
      // Ignore abort errors
      if (err instanceof Error && err.name === 'AbortError') {
        return;
      }

      if (mountedRef.current) {
        setError(err instanceof Error ? err : new Error('Unknown error'));
        setRawData([]);
      }
    } finally {
      if (mountedRef.current) {
        setLoading(false);
      }
    }
  }, [dataSource, groupByField, linkageFilters, enabled]);

  /**
   * Effect for initial fetch and dependency changes
   */
  useEffect(() => {
    fetchData();
  }, [fetchData]);

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

  /**
   * Group raw data into columns
   */
  const columns = useMemo<KanbanColumn[]>(() => {
    // Enhanced groupKey resolver: BOOLEAN → Yes/No, DATE → YYYY-MM, null → (Empty)
    function resolveGroup(value: unknown): { key: string; title: string } {
      if (value === null || value === undefined || value === '') {
        return { key: '', title: '(Empty)' };
      }
      if (typeof value === 'boolean' || value === 'true' || value === 'false') {
        const b = String(value);
        return { key: b, title: b === 'true' ? 'Yes' : 'No' };
      }
      const s = String(value);
      // DATE/DATETIME ISO string → group by month
      if (/^\d{4}-\d{2}-\d{2}/.test(s)) {
        const month = s.substring(0, 7);
        return { key: month, title: month };
      }
      return { key: s, title: s };
    }

    // Group cards by the groupByField value
    const groupedData = new Map<string, { title: string; cards: KanbanCard[] }>();

    for (const row of rawData) {
      const groupValue = row[groupByField];
      const { key: groupKey, title: groupTitle } = resolveGroup(groupValue);

      const card: KanbanCard = {
        id: String(row[idField] ?? ''),
        ...row,
      };

      if (!groupedData.has(groupKey)) {
        groupedData.set(groupKey, { title: groupTitle, cards: [] });
      }
      groupedData.get(groupKey)!.cards.push(card);
    }

    // Convert grouped data to columns
    const result: KanbanColumn[] = [];

    for (const [groupKey, { title, cards }] of groupedData) {
      const columnAggregations: Record<string, number> = {};
      if (aggregations) {
        for (const agg of aggregations) {
          const key = agg.label || agg.field;
          columnAggregations[key] = calculateAggregation(cards, agg);
        }
      }

      // For REFERENCE fields, try _display suffix for readable column title
      const displayKey = `${groupByField}_display`;
      const refTitle = cards[0]?.[displayKey];

      result.push({
        id: groupKey,
        title: typeof refTitle === 'string' && refTitle ? refTitle : title,
        value: cards[0]?.[groupByField] ?? groupKey,
        cards,
        count: cards.length,
        aggregations: Object.keys(columnAggregations).length > 0 ? columnAggregations : undefined,
      });
    }

    return result;
  }, [rawData, groupByField, idField, aggregations]);

  /**
   * Move a card between columns (optimistic update)
   *
   * Updates the local rawData to reflect the card movement.
   * Does not persist the change - external handler should call API.
   */
  const moveCard = useCallback(
    (cardId: string, _sourceColumnId: string, targetColumnId: string, _targetIndex: number) => {
      setRawData((prevData) => {
        return prevData.map((row) => {
          const rowId = String(row[idField] ?? '');
          if (rowId === cardId) {
            // Update the groupByField value to move the card to the target column
            return {
              ...row,
              [groupByField]: targetColumnId,
            };
          }
          return row;
        });
      });
    },
    [idField, groupByField],
  );

  return {
    columns,
    loading,
    error,
    refetch: fetchData,
    moveCard,
  };
}

export default useKanbanData;
