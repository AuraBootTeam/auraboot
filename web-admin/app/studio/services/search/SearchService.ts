/**
 * SearchService
 *
 * Unified search service for ViewModel, components, bindings, and actions.
 *
 * @since 3.2.0
 */

import type {
  SearchScope,
  SearchResult,
  SearchOptions,
  SearchState,
  SearchIndexEntry,
  SearchHighlight,
} from './types';

/**
 * Search indexers
 */
type SearchIndexer = () => SearchIndexEntry[];

/**
 * SearchService class
 */
export class SearchService {
  private static instance: SearchService;
  private indexers: Map<SearchScope, SearchIndexer[]> = new Map();
  private cache: Map<string, SearchResult[]> = new Map();
  private cacheTimeout = 5000; // 5 seconds
  private maxHistory = 20;
  private state: SearchState = {
    query: '',
    results: [],
    loading: false,
    error: null,
    selectedId: null,
    history: [],
  };
  private listeners: Set<(state: SearchState) => void> = new Set();

  private constructor() {
    // Initialize default indexers for each scope
    this.indexers.set('all', []);
    this.indexers.set('viewmodel', []);
    this.indexers.set('components', []);
    this.indexers.set('fields', []);
    this.indexers.set('bindings', []);
    this.indexers.set('actions', []);
  }

  /**
   * Get singleton instance
   */
  public static getInstance(): SearchService {
    if (!SearchService.instance) {
      SearchService.instance = new SearchService();
    }
    return SearchService.instance;
  }

  /**
   * Subscribe to state changes
   */
  public subscribe(listener: (state: SearchState) => void): () => void {
    this.listeners.add(listener);
    listener(this.state);
    return () => this.listeners.delete(listener);
  }

  /**
   * Update state and notify listeners
   */
  private updateState(partial: Partial<SearchState>): void {
    this.state = { ...this.state, ...partial };
    this.listeners.forEach((listener) => listener(this.state));
  }

  /**
   * Register a search indexer
   */
  public registerIndexer(scope: SearchScope, indexer: SearchIndexer): void {
    const indexers = this.indexers.get(scope) || [];
    indexers.push(indexer);
    this.indexers.set(scope, indexers);

    // Also add to 'all' scope
    if (scope !== 'all') {
      const allIndexers = this.indexers.get('all') || [];
      allIndexers.push(indexer);
      this.indexers.set('all', allIndexers);
    }

    // Clear cache
    this.clearCache();
  }

  /**
   * Unregister an indexer
   */
  public unregisterIndexer(scope: SearchScope, indexer: SearchIndexer): void {
    const indexers = this.indexers.get(scope);
    if (indexers) {
      const index = indexers.indexOf(indexer);
      if (index !== -1) {
        indexers.splice(index, 1);
      }
    }

    // Also remove from 'all' scope
    if (scope !== 'all') {
      const allIndexers = this.indexers.get('all');
      if (allIndexers) {
        const index = allIndexers.indexOf(indexer);
        if (index !== -1) {
          allIndexers.splice(index, 1);
        }
      }
    }

    this.clearCache();
  }

  /**
   * Clear search cache
   */
  public clearCache(): void {
    this.cache.clear();
  }

  /**
   * Build search index for a scope
   */
  private buildIndex(scope: SearchScope): SearchIndexEntry[] {
    const indexers = this.indexers.get(scope) || [];
    const entries: SearchIndexEntry[] = [];

    for (const indexer of indexers) {
      try {
        const indexerEntries = indexer();
        entries.push(...indexerEntries);
      } catch (error) {
        console.error('Search indexer error:', error);
      }
    }

    return entries;
  }

  /**
   * Calculate match score
   */
  private calculateScore(
    entry: SearchIndexEntry,
    query: string,
    options: SearchOptions,
  ): { score: number; highlights: SearchHighlight[] } {
    const normalizedQuery = options.caseSensitive ? query : query.toLowerCase();
    const highlights: SearchHighlight[] = [];
    let score = 0;

    // Check each text field
    const fields = ['title', 'subtitle', 'path'] as const;

    for (const field of fields) {
      const value = entry.text[field];
      if (!value) continue;

      const normalizedValue = options.caseSensitive ? value : value.toLowerCase();
      const index = normalizedValue.indexOf(normalizedQuery);

      if (index !== -1) {
        // Exact match in this field
        const fieldWeight = field === 'title' ? 100 : field === 'subtitle' ? 50 : 25;
        score += fieldWeight;

        // Boost for match at start
        if (index === 0) {
          score += 50;
        }

        // Add highlight
        highlights.push({
          field,
          start: index,
          end: index + normalizedQuery.length,
          text: value.slice(index, index + normalizedQuery.length),
        });
      } else if (options.fuzzy) {
        // Fuzzy matching
        const fuzzyScore = this.fuzzyMatch(normalizedValue, normalizedQuery);
        if (fuzzyScore > 0.5) {
          score += fuzzyScore * 30;
        }
      }
    }

    // Check keywords
    if (entry.text.keywords) {
      for (const keyword of entry.text.keywords) {
        const normalizedKeyword = options.caseSensitive ? keyword : keyword.toLowerCase();
        if (normalizedKeyword.includes(normalizedQuery)) {
          score += 20;
        }
      }
    }

    return { score, highlights };
  }

  /**
   * Simple fuzzy matching (Jaro-Winkler inspired)
   */
  private fuzzyMatch(str1: string, str2: string): number {
    if (str1 === str2) return 1;
    if (str1.length === 0 || str2.length === 0) return 0;

    const maxDist = Math.floor(Math.max(str1.length, str2.length) / 2) - 1;
    const s1Matches: boolean[] = new Array(str1.length).fill(false);
    const s2Matches: boolean[] = new Array(str2.length).fill(false);

    let matches = 0;
    let transpositions = 0;

    // Find matches
    for (let i = 0; i < str1.length; i++) {
      const start = Math.max(0, i - maxDist);
      const end = Math.min(i + maxDist + 1, str2.length);

      for (let j = start; j < end; j++) {
        if (s2Matches[j] || str1[i] !== str2[j]) continue;
        s1Matches[i] = true;
        s2Matches[j] = true;
        matches++;
        break;
      }
    }

    if (matches === 0) return 0;

    // Count transpositions
    let k = 0;
    for (let i = 0; i < str1.length; i++) {
      if (!s1Matches[i]) continue;
      while (!s2Matches[k]) k++;
      if (str1[i] !== str2[k]) transpositions++;
      k++;
    }

    return (
      (matches / str1.length + matches / str2.length + (matches - transpositions / 2) / matches) / 3
    );
  }

  /**
   * Perform search
   */
  public async search(options: SearchOptions): Promise<SearchResult[]> {
    const {
      query,
      scope = 'all',
      limit = 50,
      caseSensitive = false,
      fuzzy = true,
      searchMetadata = false,
    } = options;

    // Empty query
    if (!query.trim()) {
      this.updateState({ query: '', results: [], loading: false });
      return [];
    }

    // Check cache
    const cacheKey = JSON.stringify({ query, scope, caseSensitive, fuzzy });
    const cached = this.cache.get(cacheKey);
    if (cached) {
      this.updateState({ query, results: cached, loading: false });
      return cached;
    }

    // Start search
    this.updateState({ query, loading: true, error: null });

    try {
      // Build index
      const index = this.buildIndex(scope);

      // Score and filter entries
      const scoredResults: Array<{ result: SearchResult; score: number }> = [];

      for (const entry of index) {
        const { score, highlights } = this.calculateScore(entry, query, {
          ...options,
          caseSensitive,
          fuzzy,
        });

        if (score > 0) {
          scoredResults.push({
            result: {
              ...entry.data,
              score,
              highlights,
            },
            score,
          });
        }
      }

      // Sort by score and limit
      scoredResults.sort((a, b) => b.score - a.score);
      const results = scoredResults.slice(0, limit).map((r) => r.result);

      // Update cache
      this.cache.set(cacheKey, results);
      setTimeout(() => this.cache.delete(cacheKey), this.cacheTimeout);

      // Update state
      this.updateState({ results, loading: false });

      // Add to history
      this.addToHistory(query);

      return results;
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : '搜索失败';
      this.updateState({ error: errorMessage, loading: false });
      return [];
    }
  }

  /**
   * Add query to history
   */
  private addToHistory(query: string): void {
    const history = [query, ...this.state.history.filter((h) => h !== query)];
    if (history.length > this.maxHistory) {
      history.pop();
    }
    this.updateState({ history });
  }

  /**
   * Clear search history
   */
  public clearHistory(): void {
    this.updateState({ history: [] });
  }

  /**
   * Select a result
   */
  public selectResult(id: string | null): void {
    this.updateState({ selectedId: id });
  }

  /**
   * Get current state
   */
  public getState(): SearchState {
    return this.state;
  }

  /**
   * Reset search state
   */
  public reset(): void {
    this.updateState({
      query: '',
      results: [],
      loading: false,
      error: null,
      selectedId: null,
    });
  }
}

/**
 * Singleton instance
 */
export const searchService = SearchService.getInstance();

export default SearchService;
