import { useState, useCallback, useEffect, useRef } from 'react';

/**
 * Parameters passed to the fetchData function
 */
export interface DataTableParams {
  page: number;
  pageSize: number;
  search?: string;
  sortField?: string;
  sortOrder?: 'asc' | 'desc';
}

/**
 * Configuration options for useDataTable hook
 */
export interface UseDataTableOptions<T> {
  /** Async function to fetch paginated data */
  fetchData: (params: DataTableParams) => Promise<{ data: T[]; total: number }>;
  /** Default page size (default: 20) */
  defaultPageSize?: number;
  /** Default sort field */
  defaultSortField?: string;
  /** Default sort order */
  defaultSortOrder?: 'asc' | 'desc';
  /** Whether to fetch on mount (default: true) */
  autoFetch?: boolean;
}

/**
 * Return type of useDataTable hook
 */
export interface UseDataTableReturn<T> {
  data: T[];
  total: number;
  loading: boolean;
  error: string | null;

  // Pagination
  page: number;
  pageSize: number;
  setPage: (page: number) => void;
  setPageSize: (size: number) => void;

  // Search
  search: string;
  setSearch: (search: string) => void;

  // Sort
  sortField: string | undefined;
  sortOrder: 'asc' | 'desc' | undefined;
  setSort: (field: string, order: 'asc' | 'desc') => void;

  // Actions
  refresh: () => void;
  reset: () => void;
}

const SEARCH_DEBOUNCE_MS = 300;

/**
 * useDataTable - Generic hook for table data management
 *
 * Encapsulates pagination, search (with debounce), sorting, and API fetching
 * with AbortController support for request cancellation.
 *
 * @example
 * ```ts
 * const table = useDataTable({
 *   fetchData: async (params) => {
 *     const res = await apiService.get('/api/users', { params });
 *     return { data: res.data.records, total: res.data.total };
 *   },
 *   defaultPageSize: 20,
 *   defaultSortField: 'createdAt',
 *   defaultSortOrder: 'desc',
 * });
 * ```
 */
export function useDataTable<T>(options: UseDataTableOptions<T>): UseDataTableReturn<T> {
  const {
    fetchData,
    defaultPageSize = 20,
    defaultSortField,
    defaultSortOrder,
    autoFetch = true,
  } = options;

  // ── State ──────────────────────────────────────────────────────────
  const [data, setData] = useState<T[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(defaultPageSize);
  const [search, setSearchRaw] = useState('');
  const [debouncedSearch, setDebouncedSearch] = useState('');
  const [sortField, setSortField] = useState<string | undefined>(defaultSortField);
  const [sortOrder, setSortOrder] = useState<'asc' | 'desc' | undefined>(defaultSortOrder);

  // ── Refs ───────────────────────────────────────────────────────────
  const abortControllerRef = useRef<AbortController | null>(null);
  const debounceTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const isMountedRef = useRef(true);
  const fetchIdRef = useRef(0);

  // Track mounted state to avoid setState on unmounted component
  useEffect(() => {
    isMountedRef.current = true;
    return () => {
      isMountedRef.current = false;
    };
  }, []);

  // ── Search debounce ────────────────────────────────────────────────
  useEffect(() => {
    if (debounceTimerRef.current !== null) {
      clearTimeout(debounceTimerRef.current);
    }

    debounceTimerRef.current = setTimeout(() => {
      setDebouncedSearch(search);
    }, SEARCH_DEBOUNCE_MS);

    return () => {
      if (debounceTimerRef.current !== null) {
        clearTimeout(debounceTimerRef.current);
      }
    };
  }, [search]);

  // Reset to page 1 when search changes
  useEffect(() => {
    setPage(1);
  }, [debouncedSearch]);

  // ── Core fetch logic ───────────────────────────────────────────────
  const executeFetch = useCallback(async () => {
    // Cancel any in-flight request
    if (abortControllerRef.current) {
      abortControllerRef.current.abort();
    }

    const controller = new AbortController();
    abortControllerRef.current = controller;

    const currentFetchId = ++fetchIdRef.current;

    setLoading(true);
    setError(null);

    try {
      const params: DataTableParams = {
        page,
        pageSize,
        search: debouncedSearch || undefined,
        sortField,
        sortOrder,
      };

      const result = await fetchData(params);

      // Only apply result if this is still the latest request and component is mounted
      if (currentFetchId === fetchIdRef.current && isMountedRef.current) {
        setData(result.data);
        setTotal(result.total);
      }
    } catch (err) {
      // Ignore abort errors
      if (err instanceof DOMException && err.name === 'AbortError') {
        return;
      }
      if (currentFetchId === fetchIdRef.current && isMountedRef.current) {
        const message = err instanceof Error ? err.message : 'Failed to fetch data';
        setError(message);
      }
    } finally {
      if (currentFetchId === fetchIdRef.current && isMountedRef.current) {
        setLoading(false);
      }
    }
  }, [fetchData, page, pageSize, debouncedSearch, sortField, sortOrder]);

  // ── Auto-fetch on param changes ────────────────────────────────────
  const isInitialMount = useRef(true);

  useEffect(() => {
    if (isInitialMount.current) {
      isInitialMount.current = false;
      if (autoFetch) {
        executeFetch();
      }
      return;
    }
    // Always fetch on subsequent param changes
    executeFetch();
  }, [executeFetch, autoFetch]);

  // ── Cleanup on unmount ─────────────────────────────────────────────
  useEffect(() => {
    return () => {
      if (abortControllerRef.current) {
        abortControllerRef.current.abort();
      }
      if (debounceTimerRef.current !== null) {
        clearTimeout(debounceTimerRef.current);
      }
    };
  }, []);

  // ── Public API ─────────────────────────────────────────────────────

  const setSearch = useCallback((value: string) => {
    setSearchRaw(value);
  }, []);

  const setSort = useCallback((field: string, order: 'asc' | 'desc') => {
    setSortField(field);
    setSortOrder(order);
  }, []);

  const refresh = useCallback(() => {
    executeFetch();
  }, [executeFetch]);

  const reset = useCallback(() => {
    setPage(1);
    setPageSize(defaultPageSize);
    setSearchRaw('');
    setDebouncedSearch('');
    setSortField(defaultSortField);
    setSortOrder(defaultSortOrder);
    // Clear debounce timer so reset takes effect immediately
    if (debounceTimerRef.current !== null) {
      clearTimeout(debounceTimerRef.current);
      debounceTimerRef.current = null;
    }
  }, [defaultPageSize, defaultSortField, defaultSortOrder]);

  return {
    data,
    total,
    loading,
    error,

    // Pagination
    page,
    pageSize,
    setPage,
    setPageSize,

    // Search
    search,
    setSearch,

    // Sort
    sortField,
    sortOrder,
    setSort,

    // Actions
    refresh,
    reset,
  };
}
