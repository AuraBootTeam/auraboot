/**
 * Search Service Types
 *
 * Types for the unified search functionality.
 *
 * @since 3.2.0
 */

/**
 * Search scope
 */
export type SearchScope = 'all' | 'viewmodel' | 'components' | 'fields' | 'bindings' | 'actions';

/**
 * Search result type
 */
export type SearchResultType =
  | 'field'
  | 'component'
  | 'binding'
  | 'action'
  | 'datasource'
  | 'computed';

/**
 * Base search result
 */
export interface SearchResult {
  /** Unique ID */
  id: string;
  /** Result type */
  type: SearchResultType;
  /** Display title */
  title: string;
  /** Subtitle/description */
  subtitle?: string;
  /** Path (for tree items) */
  path?: string;
  /** Match highlights */
  highlights?: SearchHighlight[];
  /** Result metadata */
  metadata?: Record<string, unknown>;
  /** Score for ranking */
  score: number;
}

/**
 * Search highlight
 */
export interface SearchHighlight {
  /** Field that was matched */
  field: string;
  /** Start index of match */
  start: number;
  /** End index of match */
  end: number;
  /** Matched text */
  text: string;
}

/**
 * Field search result
 */
export interface FieldSearchResult extends SearchResult {
  type: 'field';
  metadata: {
    fieldType: string;
    required: boolean;
    path: string;
  };
}

/**
 * Component search result
 */
export interface ComponentSearchResult extends SearchResult {
  type: 'component';
  metadata: {
    componentType: string;
    parentId?: string;
    hasBindings: boolean;
  };
}

/**
 * Binding search result
 */
export interface BindingSearchResult extends SearchResult {
  type: 'binding';
  metadata: {
    fieldPath: string;
    componentId: string;
    mode: 'one-way' | 'two-way';
  };
}

/**
 * Action search result
 */
export interface ActionSearchResult extends SearchResult {
  type: 'action';
  metadata: {
    trigger: string;
    targetComponent?: string;
  };
}

/**
 * Search options
 */
export interface SearchOptions {
  /** Search query */
  query: string;
  /** Search scope */
  scope?: SearchScope;
  /** Maximum results */
  limit?: number;
  /** Case sensitive */
  caseSensitive?: boolean;
  /** Fuzzy matching */
  fuzzy?: boolean;
  /** Include metadata in search */
  searchMetadata?: boolean;
}

/**
 * Search state
 */
export interface SearchState {
  /** Current query */
  query: string;
  /** Search results */
  results: SearchResult[];
  /** Loading state */
  loading: boolean;
  /** Error message */
  error: string | null;
  /** Selected result ID */
  selectedId: string | null;
  /** Search history */
  history: string[];
}

/**
 * Search index entry
 */
export interface SearchIndexEntry {
  /** Entry ID */
  id: string;
  /** Entry type */
  type: SearchResultType;
  /** Searchable text fields */
  text: {
    title: string;
    subtitle?: string;
    path?: string;
    keywords?: string[];
  };
  /** Entry data */
  data: SearchResult;
}
