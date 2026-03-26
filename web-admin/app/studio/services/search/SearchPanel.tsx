/**
 * SearchPanel Component
 *
 * Unified search panel for the page designer.
 *
 * @since 3.2.0
 */

import React, { useState, useCallback, useEffect, useRef, useMemo } from 'react';
import { searchService } from './SearchService';
import type { SearchScope, SearchResult, SearchState } from './types';

interface SearchPanelProps {
  /** Default scope */
  defaultScope?: SearchScope;
  /** On result select */
  onSelect?: (result: SearchResult) => void;
  /** Placeholder */
  placeholder?: string;
  /** Auto focus */
  autoFocus?: boolean;
  /** Show scope tabs */
  showScopeTabs?: boolean;
  /** Compact mode */
  compact?: boolean;
  /** Class name */
  className?: string;
}

/**
 * Scope options
 */
const SCOPE_OPTIONS: Array<{ value: SearchScope; label: string }> = [
  { value: 'all', label: '全部' },
  { value: 'fields', label: '字段' },
  { value: 'components', label: '组件' },
  { value: 'bindings', label: '绑定' },
  { value: 'actions', label: '动作' },
];

/**
 * Result type icons
 */
const TYPE_ICONS: Record<string, string> = {
  field: 'M4 6h16M4 12h16m-7 6h7',
  component:
    'M4 5a1 1 0 011-1h14a1 1 0 011 1v2a1 1 0 01-1 1H5a1 1 0 01-1-1V5zM4 13a1 1 0 011-1h6a1 1 0 011 1v6a1 1 0 01-1 1H5a1 1 0 01-1-1v-6zM16 13a1 1 0 011-1h2a1 1 0 011 1v6a1 1 0 01-1 1h-2a1 1 0 01-1-1v-6z',
  binding:
    'M13.828 10.172a4 4 0 00-5.656 0l-4 4a4 4 0 105.656 5.656l1.102-1.101m-.758-4.899a4 4 0 005.656 0l4-4a4 4 0 00-5.656-5.656l-1.1 1.1',
  action: 'M13 10V3L4 14h7v7l9-11h-7z',
  datasource:
    'M4 7v10c0 2.21 3.582 4 8 4s8-1.79 8-4V7M4 7c0 2.21 3.582 4 8 4s8-1.79 8-4M4 7c0-2.21 3.582-4 8-4s8 1.79 8 4',
  computed:
    'M9 7h6m0 10v-3m-3 3h.01M9 17h.01M9 14h.01M12 14h.01M15 11h.01M12 11h.01M9 11h.01M7 21h10a2 2 0 002-2V5a2 2 0 00-2-2H7a2 2 0 00-2 2v14a2 2 0 002 2z',
};

/**
 * Result type colors
 */
const TYPE_COLORS: Record<string, string> = {
  field: 'text-blue-500 bg-blue-50',
  component: 'text-purple-500 bg-purple-50',
  binding: 'text-green-500 bg-green-50',
  action: 'text-orange-500 bg-orange-50',
  datasource: 'text-cyan-500 bg-cyan-50',
  computed: 'text-pink-500 bg-pink-50',
};

/**
 * SearchPanel Component
 */
export const SearchPanel: React.FC<SearchPanelProps> = ({
  defaultScope = 'all',
  onSelect,
  placeholder = '搜索字段、组件、绑定...',
  autoFocus = false,
  showScopeTabs = true,
  compact = false,
  className = '',
}) => {
  const [scope, setScope] = useState<SearchScope>(defaultScope);
  const [query, setQuery] = useState('');
  const [state, setState] = useState<SearchState>(searchService.getState());
  const [selectedIndex, setSelectedIndex] = useState(0);
  const [showHistory, setShowHistory] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  const resultsRef = useRef<HTMLDivElement>(null);

  // Subscribe to search service
  useEffect(() => {
    return searchService.subscribe(setState);
  }, []);

  // Auto focus
  useEffect(() => {
    if (autoFocus && inputRef.current) {
      inputRef.current.focus();
    }
  }, [autoFocus]);

  // Reset selection when results change
  useEffect(() => {
    setSelectedIndex(0);
  }, [state.results]);

  // Debounced search
  useEffect(() => {
    const timer = setTimeout(() => {
      if (query.trim()) {
        searchService.search({ query, scope });
      } else {
        searchService.reset();
      }
    }, 200);

    return () => clearTimeout(timer);
  }, [query, scope]);

  // Handle input change
  const handleInputChange = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    setQuery(e.target.value);
    setShowHistory(false);
  }, []);

  // Handle input focus
  const handleInputFocus = useCallback(() => {
    if (!query && state.history.length > 0) {
      setShowHistory(true);
    }
  }, [query, state.history]);

  // Handle input blur
  const handleInputBlur = useCallback(() => {
    // Delay to allow clicking on history items
    setTimeout(() => setShowHistory(false), 200);
  }, []);

  // Handle key down
  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      const results = state.results;

      switch (e.key) {
        case 'ArrowDown':
          e.preventDefault();
          setSelectedIndex((i) => Math.min(i + 1, results.length - 1));
          break;
        case 'ArrowUp':
          e.preventDefault();
          setSelectedIndex((i) => Math.max(i - 1, 0));
          break;
        case 'Enter':
          e.preventDefault();
          if (results[selectedIndex]) {
            handleResultClick(results[selectedIndex]);
          }
          break;
        case 'Escape':
          e.preventDefault();
          setQuery('');
          searchService.reset();
          break;
      }
    },
    [state.results, selectedIndex],
  );

  // Handle result click
  const handleResultClick = useCallback(
    (result: SearchResult) => {
      searchService.selectResult(result.id);
      onSelect?.(result);
    },
    [onSelect],
  );

  // Handle history item click
  const handleHistoryClick = useCallback((historyQuery: string) => {
    setQuery(historyQuery);
    setShowHistory(false);
  }, []);

  // Handle clear
  const handleClear = useCallback(() => {
    setQuery('');
    searchService.reset();
    inputRef.current?.focus();
  }, []);

  // Scroll selected item into view
  useEffect(() => {
    if (resultsRef.current && state.results.length > 0) {
      const selectedElement = resultsRef.current.querySelector(`[data-index="${selectedIndex}"]`);
      selectedElement?.scrollIntoView({ block: 'nearest' });
    }
  }, [selectedIndex, state.results]);

  // Highlighted text
  const highlightText = useCallback((text: string, result: SearchResult, field: string) => {
    const highlight = result.highlights?.find((h) => h.field === field);
    if (!highlight) return text;

    const before = text.slice(0, highlight.start);
    const match = text.slice(highlight.start, highlight.end);
    const after = text.slice(highlight.end);

    return (
      <>
        {before}
        <mark className="rounded bg-yellow-200 px-0.5">{match}</mark>
        {after}
      </>
    );
  }, []);

  return (
    <div className={`flex flex-col ${className}`}>
      {/* Scope tabs */}
      {showScopeTabs && (
        <div className="mb-2 flex gap-1 overflow-x-auto">
          {SCOPE_OPTIONS.map((option) => (
            <button
              key={option.value}
              type="button"
              onClick={() => setScope(option.value)}
              className={`rounded px-2 py-1 text-xs whitespace-nowrap transition-colors ${
                scope === option.value
                  ? 'bg-blue-100 text-blue-700'
                  : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
              } `}
            >
              {option.label}
            </button>
          ))}
        </div>
      )}

      {/* Search input */}
      <div className="relative">
        <div className="relative">
          <svg
            className="absolute top-1/2 left-3 h-4 w-4 -translate-y-1/2 text-gray-400"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"
            />
          </svg>
          <input
            ref={inputRef}
            type="text"
            value={query}
            onChange={handleInputChange}
            onFocus={handleInputFocus}
            onBlur={handleInputBlur}
            onKeyDown={handleKeyDown}
            placeholder={placeholder}
            className={`w-full rounded-md border border-gray-200 pr-8 pl-9 focus:border-transparent focus:ring-2 focus:ring-blue-500 focus:outline-none ${compact ? 'py-1.5 text-sm' : 'py-2'} `}
          />
          {query && (
            <button
              type="button"
              onClick={handleClear}
              className="absolute top-1/2 right-2 -translate-y-1/2 p-1 text-gray-400 hover:text-gray-600"
            >
              <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M6 18L18 6M6 6l12 12"
                />
              </svg>
            </button>
          )}
        </div>

        {/* History dropdown */}
        {showHistory && state.history.length > 0 && (
          <div className="absolute z-50 mt-1 w-full rounded-md border border-gray-200 bg-white shadow-lg">
            <div className="border-b border-gray-100 p-2 text-xs text-gray-400">搜索历史</div>
            {state.history.slice(0, 5).map((historyQuery, index) => (
              <button
                key={index}
                type="button"
                onClick={() => handleHistoryClick(historyQuery)}
                className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm hover:bg-gray-50"
              >
                <svg
                  className="h-4 w-4 text-gray-400"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                >
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z"
                  />
                </svg>
                {historyQuery}
              </button>
            ))}
            <button
              type="button"
              onClick={() => searchService.clearHistory()}
              className="w-full border-t border-gray-100 px-3 py-1.5 text-xs text-gray-400 hover:text-red-500"
            >
              清除历史
            </button>
          </div>
        )}
      </div>

      {/* Loading */}
      {state.loading && (
        <div className="flex items-center justify-center py-4">
          <div className="h-5 w-5 animate-spin rounded-full border-2 border-blue-500 border-t-transparent" />
        </div>
      )}

      {/* Error */}
      {state.error && (
        <div className="mt-2 rounded bg-red-50 p-2 text-sm text-red-600">{state.error}</div>
      )}

      {/* Results */}
      {!state.loading && query && state.results.length > 0 && (
        <div
          ref={resultsRef}
          className="mt-2 max-h-80 overflow-auto rounded-md border border-gray-200"
        >
          {state.results.map((result, index) => (
            <button
              key={result.id}
              type="button"
              data-index={index}
              onClick={() => handleResultClick(result)}
              className={`flex w-full items-start gap-3 border-b border-gray-100 px-3 py-2 text-left transition-colors last:border-b-0 ${index === selectedIndex ? 'bg-blue-50' : 'hover:bg-gray-50'} `}
            >
              {/* Icon */}
              <div
                className={`flex h-7 w-7 flex-shrink-0 items-center justify-center rounded ${
                  TYPE_COLORS[result.type] || 'bg-gray-50 text-gray-500'
                }`}
              >
                <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d={TYPE_ICONS[result.type] || TYPE_ICONS.field}
                  />
                </svg>
              </div>

              {/* Content */}
              <div className="min-w-0 flex-1">
                <div className="truncate text-sm font-medium text-gray-900">
                  {highlightText(result.title, result, 'title')}
                </div>
                {result.subtitle && (
                  <div className="truncate text-xs text-gray-500">
                    {highlightText(result.subtitle, result, 'subtitle')}
                  </div>
                )}
                {result.path && (
                  <div className="truncate text-xs text-gray-400">
                    {highlightText(result.path, result, 'path')}
                  </div>
                )}
              </div>

              {/* Score badge (debug) */}
              {process.env.NODE_ENV === 'development' && (
                <span className="text-[10px] text-gray-400">{result.score.toFixed(0)}</span>
              )}
            </button>
          ))}
        </div>
      )}

      {/* No results */}
      {!state.loading && query && state.results.length === 0 && (
        <div className="mt-2 py-4 text-center text-sm text-gray-500">
          没有找到匹配 "{query}" 的结果
        </div>
      )}
    </div>
  );
};

export default SearchPanel;
