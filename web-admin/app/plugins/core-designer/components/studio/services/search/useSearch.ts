/**
 * useSearch Hook
 *
 * React hook for integrating with the search service.
 *
 * @since 3.2.0
 */

import { useState, useEffect, useCallback, useMemo } from 'react';
import { searchService } from './SearchService';
import type {
  SearchScope,
  SearchResult,
  SearchState,
  SearchOptions,
  SearchIndexEntry,
} from './types';

/**
 * useSearch hook options
 */
interface UseSearchOptions {
  /** Default scope */
  scope?: SearchScope;
  /** Debounce delay in ms */
  debounce?: number;
  /** Initial query */
  initialQuery?: string;
}

/**
 * useSearch hook return type
 */
interface UseSearchReturn {
  /** Current query */
  query: string;
  /** Set query */
  setQuery: (query: string) => void;
  /** Search results */
  results: SearchResult[];
  /** Loading state */
  loading: boolean;
  /** Error message */
  error: string | null;
  /** Selected result ID */
  selectedId: string | null;
  /** Select a result */
  selectResult: (id: string | null) => void;
  /** Clear search */
  clear: () => void;
  /** Search function */
  search: (options?: Partial<SearchOptions>) => Promise<SearchResult[]>;
  /** Search history */
  history: string[];
  /** Clear history */
  clearHistory: () => void;
}

/**
 * useSearch hook
 */
export function useSearch(options: UseSearchOptions = {}): UseSearchReturn {
  const { scope = 'all', debounce = 200, initialQuery = '' } = options;

  const [query, setQuery] = useState(initialQuery);
  const [state, setState] = useState<SearchState>(searchService.getState());

  // Subscribe to search service
  useEffect(() => {
    return searchService.subscribe(setState);
  }, []);

  // Debounced search effect
  useEffect(() => {
    if (!query.trim()) {
      searchService.reset();
      return;
    }

    const timer = setTimeout(() => {
      searchService.search({ query, scope });
    }, debounce);

    return () => clearTimeout(timer);
  }, [query, scope, debounce]);

  // Search function
  const search = useCallback(
    async (opts?: Partial<SearchOptions>) => {
      return searchService.search({
        query,
        scope,
        ...opts,
      });
    },
    [query, scope],
  );

  // Select result
  const selectResult = useCallback((id: string | null) => {
    searchService.selectResult(id);
  }, []);

  // Clear search
  const clear = useCallback(() => {
    setQuery('');
    searchService.reset();
  }, []);

  // Clear history
  const clearHistory = useCallback(() => {
    searchService.clearHistory();
  }, []);

  return {
    query,
    setQuery,
    results: state.results,
    loading: state.loading,
    error: state.error,
    selectedId: state.selectedId,
    selectResult,
    clear,
    search,
    history: state.history,
    clearHistory,
  };
}

/**
 * useSearchIndexer hook
 *
 * Register a search indexer with the search service.
 */
export function useSearchIndexer(
  scope: SearchScope,
  indexer: () => SearchIndexEntry[],
  deps: unknown[] = [],
): void {
  useEffect(() => {
    searchService.registerIndexer(scope, indexer);
    return () => searchService.unregisterIndexer(scope, indexer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
}

/**
 * useFieldSearchIndexer hook
 *
 * Helper hook to index fields for search.
 */
export function useFieldSearchIndexer(
  fields: Array<{
    path: string;
    label: string;
    type: string;
    required?: boolean;
  }>,
): void {
  const indexer = useCallback((): SearchIndexEntry[] => {
    return fields.map((field) => ({
      id: `field:${field.path}`,
      type: 'field',
      text: {
        title: field.label,
        subtitle: field.type,
        path: field.path,
        keywords: [field.type, field.required ? '必填' : '可选'],
      },
      data: {
        id: `field:${field.path}`,
        type: 'field',
        title: field.label,
        subtitle: field.type,
        path: field.path,
        score: 0,
        metadata: {
          fieldType: field.type,
          required: field.required ?? false,
          path: field.path,
        },
      },
    }));
  }, [fields]);

  useSearchIndexer('fields', indexer, [fields]);
}

/**
 * useComponentSearchIndexer hook
 *
 * Helper hook to index components for search.
 */
export function useComponentSearchIndexer(
  components: Array<{
    id: string;
    name: string;
    type: string;
    parentId?: string;
    hasBindings?: boolean;
  }>,
): void {
  const indexer = useCallback((): SearchIndexEntry[] => {
    return components.map((comp) => ({
      id: `component:${comp.id}`,
      type: 'component',
      text: {
        title: comp.name,
        subtitle: comp.type,
        keywords: [comp.type],
      },
      data: {
        id: `component:${comp.id}`,
        type: 'component',
        title: comp.name,
        subtitle: comp.type,
        score: 0,
        metadata: {
          componentType: comp.type,
          parentId: comp.parentId,
          hasBindings: comp.hasBindings ?? false,
        },
      },
    }));
  }, [components]);

  useSearchIndexer('components', indexer, [components]);
}

export default useSearch;
