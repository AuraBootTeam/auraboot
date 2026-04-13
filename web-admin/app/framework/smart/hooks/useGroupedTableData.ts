/**
 * useGroupedTableData Hook
 *
 * React hook for fetching and managing grouped table data with aggregation support.
 * Supports data grouping, aggregation calculations, and group collapse/expand.
 */

import { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import { chartDataService } from '~/shared/services/chartDataService';
import type { FilterConfig, AggregateQueryRequest } from '~/framework/smart/types/chart';
import type { GroupByConfig, AggregationConfig } from '~/framework/smart/types/savedView';

/**
 * Options for the useGroupedTableData hook
 */
export interface UseGroupedTableDataOptions {
  /** Model code for the data source */
  modelCode: string;
  /** Fields to query */
  fields?: string[];
  /** Filter conditions */
  filters?: FilterConfig[];
  /** Group by configuration */
  groupBy?: GroupByConfig[];
  /** Whether to enable data fetching (default: true) */
  enabled?: boolean;
}

/**
 * Grouped row data structure
 */
export interface GroupedRow {
  /** Unique key for the group */
  groupKey: string;
  /** Group field value */
  groupValue: unknown;
  /** Rows in this group */
  rows: Record<string, unknown>[];
  /** Aggregation results for this group */
  aggregations: Record<string, number>;
  /** Whether the group is collapsed */
  collapsed: boolean;
}

/**
 * Return type for the useGroupedTableData hook
 */
export interface UseGroupedTableDataResult {
  /** Raw row data */
  rows: Record<string, unknown>[];
  /** Grouped row data */
  groups: GroupedRow[];
  /** Loading state */
  loading: boolean;
  /** Error object if request failed */
  error: Error | null;
  /** Function to toggle group collapse state */
  toggleGroup: (groupKey: string) => void;
  /** Function to manually trigger data refresh */
  refetch: () => Promise<void>;
  /** Total count of rows */
  totalCount: number;
  /** Global aggregation results */
  totals: Record<string, number>;
}

/**
 * Calculate aggregation for a set of rows
 *
 * @param rows - Data rows to aggregate
 * @param config - Aggregation configuration
 * @returns Calculated aggregation value
 */
function calculateAggregation(rows: Record<string, unknown>[], config: AggregationConfig): number {
  const values = rows.map((r) => Number(r[config.fieldCode]) || 0);

  switch (config.function) {
    case 'count':
      return rows.length;
    case 'sum':
      return values.reduce((a, b) => a + b, 0);
    case 'avg':
      return values.length > 0 ? values.reduce((a, b) => a + b, 0) / values.length : 0;
    case 'min':
      return values.length > 0 ? Math.min(...values) : 0;
    case 'max':
      return values.length > 0 ? Math.max(...values) : 0;
    default:
      return 0;
  }
}

/**
 * Generate aggregation key for storage
 *
 * @param config - Aggregation configuration
 * @returns Unique key for the aggregation
 */
function getAggregationKey(config: AggregationConfig): string {
  return config.label || `${config.function}_${config.fieldCode}`;
}

/**
 * Hook for fetching grouped table data with aggregation support
 *
 * @param options - Hook configuration options
 * @returns Object containing grouped data, loading state, error, and control functions
 *
 * @example
 * // Basic usage with grouping
 * const { rows, groups, loading } = useGroupedTableData({
 *   modelCode: 'order',
 *   fields: ['id', 'status', 'amount'],
 *   groupBy: [{
 *     fieldCode: 'status',
 *     aggregations: [
 *       { fieldCode: 'amount', function: 'sum', label: 'Total Amount' },
 *       { fieldCode: 'id', function: 'count', label: 'Order Count' },
 *     ],
 *   }],
 * });
 *
 * @example
 * // With filters and collapse control
 * const { groups, toggleGroup, totals } = useGroupedTableData({
 *   modelCode: 'transaction',
 *   filters: [{ field: 'type', operator: 'eq', value: 'sale' }],
 *   groupBy: [{
 *     fieldCode: 'category',
 *     collapsed: true,
 *     aggregations: [{ fieldCode: 'amount', function: 'sum' }],
 *   }],
 * });
 */
export function useGroupedTableData(
  options: UseGroupedTableDataOptions,
): UseGroupedTableDataResult {
  const { modelCode, fields, filters, groupBy, enabled = true } = options;

  const [rows, setRows] = useState<Record<string, unknown>[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const [collapsedGroups, setCollapsedGroups] = useState<Set<string>>(new Set());
  const mountedRef = useRef(true);

  /**
   * Initialize collapsed groups from groupBy config
   */
  useEffect(() => {
    if (groupBy && groupBy.length > 0) {
      const initialCollapsed = new Set<string>();
      // Note: Initial collapsed state will be set when data arrives
      // based on the collapsed flag in groupBy config
      setCollapsedGroups(initialCollapsed);
    }
  }, [groupBy]);

  /**
   * Fetch data from the API
   */
  const fetchData = useCallback(async () => {
    if (!enabled || !modelCode) {
      return;
    }

    setLoading(true);
    setError(null);

    try {
      // Build request - fetch raw data without server-side grouping
      // Grouping is done client-side for flexibility
      const request: AggregateQueryRequest = {
        type: 'aggregate',
        modelCode,
        dimensions: fields || [],
        metrics: [],
        filters: filters || [],
      };

      const response = await chartDataService.fetchChartData(request);

      if (mountedRef.current) {
        setRows(response.rows);

        // Initialize collapsed state based on groupBy config
        if (groupBy && groupBy.length > 0 && groupBy[0].collapsed) {
          const groupFieldCode = groupBy[0].fieldCode;
          const uniqueValues = new Set<string>(
            response.rows.map((row) => String(row[groupFieldCode] ?? 'null')),
          );
          setCollapsedGroups(uniqueValues);
        }

        setError(null);
      }
    } catch (err) {
      if (mountedRef.current) {
        setError(err instanceof Error ? err : new Error('Unknown error'));
        setRows([]);
      }
    } finally {
      if (mountedRef.current) {
        setLoading(false);
      }
    }
  }, [modelCode, fields, filters, enabled, groupBy]);

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
    };
  }, []);

  /**
   * Toggle group collapse state
   */
  const toggleGroup = useCallback((groupKey: string) => {
    setCollapsedGroups((prev) => {
      const next = new Set(prev);
      if (next.has(groupKey)) {
        next.delete(groupKey);
      } else {
        next.add(groupKey);
      }
      return next;
    });
  }, []);

  /**
   * Calculate grouped data with aggregations
   */
  const groups = useMemo<GroupedRow[]>(() => {
    if (!groupBy || groupBy.length === 0 || rows.length === 0) {
      return [];
    }

    // Support single-level grouping (groupBy[0])
    const groupConfig = groupBy[0];
    const groupFieldCode = groupConfig.fieldCode;
    const aggregationConfigs = groupConfig.aggregations || [];

    // Group rows by field value
    const groupMap = new Map<string, Record<string, unknown>[]>();

    for (const row of rows) {
      const groupValue = row[groupFieldCode];
      const groupKey = String(groupValue ?? 'null');

      if (!groupMap.has(groupKey)) {
        groupMap.set(groupKey, []);
      }
      groupMap.get(groupKey)!.push(row);
    }

    // Build grouped rows with aggregations
    const result: GroupedRow[] = [];

    for (const entry of Array.from(groupMap.entries())) {
      const [groupKey, groupRows] = entry;
      const aggregations: Record<string, number> = {};

      for (const aggConfig of aggregationConfigs) {
        const key = getAggregationKey(aggConfig);
        aggregations[key] = calculateAggregation(groupRows, aggConfig);
      }

      result.push({
        groupKey,
        groupValue: groupRows[0]?.[groupFieldCode] ?? null,
        rows: groupRows,
        aggregations,
        collapsed: collapsedGroups.has(groupKey),
      });
    }

    return result;
  }, [rows, groupBy, collapsedGroups]);

  /**
   * Calculate global aggregations (totals)
   */
  const totals = useMemo<Record<string, number>>(() => {
    if (!groupBy || groupBy.length === 0 || rows.length === 0) {
      return {};
    }

    const groupConfig = groupBy[0];
    const aggregationConfigs = groupConfig.aggregations || [];
    const result: Record<string, number> = {};

    for (const aggConfig of aggregationConfigs) {
      const key = getAggregationKey(aggConfig);
      result[key] = calculateAggregation(rows, aggConfig);
    }

    return result;
  }, [rows, groupBy]);

  return {
    rows,
    groups,
    loading,
    error,
    toggleGroup,
    refetch: fetchData,
    totalCount: rows.length,
    totals,
  };
}

export default useGroupedTableData;
