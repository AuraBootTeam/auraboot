/**
 * Search Service Module
 *
 * Unified search functionality for the page designer.
 *
 * @since 3.2.0
 */

// Types
export type {
  SearchScope,
  SearchResultType,
  SearchResult,
  SearchHighlight,
  FieldSearchResult,
  ComponentSearchResult,
  BindingSearchResult,
  ActionSearchResult,
  SearchOptions,
  SearchState,
  SearchIndexEntry,
} from './types';

// Service
export { SearchService, searchService } from './SearchService';

// Components
export { SearchPanel, default } from './SearchPanel';

// Hooks
export {
  useSearch,
  useSearchIndexer,
  useFieldSearchIndexer,
  useComponentSearchIndexer,
} from './useSearch';
