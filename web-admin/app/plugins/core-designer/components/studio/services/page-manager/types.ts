/**
 * Page Manager Types
 *
 * Types for page management functionality.
 *
 * @since 3.2.0
 */

/**
 * Page status
 */
export type PageStatus = 'draft' | 'published' | 'modified' | 'archived';

/**
 * Page mode/type
 */
export type PageMode = 'grid' | 'floor' | 'form' | 'composite';

/**
 * Page metadata
 */
export interface PageMeta {
  /** Page unique ID */
  id: string;
  /** Page title */
  title: string;
  /** Page description */
  description?: string;
  /** Page mode */
  mode: PageMode;
  /** Associated ViewModel code */
  viewModelCode?: string;
  /** Page status */
  status: PageStatus;
  /** Current version */
  version: string;
  /** Published version (if different from current) */
  publishedVersion?: string;
  /** Created timestamp */
  createdAt: string;
  /** Updated timestamp */
  updatedAt: string;
  /** Created by user */
  createdBy?: string;
  /** Updated by user */
  updatedBy?: string;
  /** Tags for categorization */
  tags?: string[];
  /** Thumbnail URL */
  thumbnail?: string;
  /** Component count */
  componentCount?: number;
  /** DSL Schema (page structure and layout) */
  dslSchema?: Record<string, unknown>;
  /** Extension metadata persisted by backend */
  extension?: Record<string, unknown>;
}

/**
 * Page list filter options
 */
export interface PageListFilter {
  /** Search query */
  query?: string;
  /** Filter by status */
  status?: PageStatus | 'all';
  /** Filter by mode */
  mode?: PageMode | 'all';
  /** Filter by ViewModel */
  viewModelCode?: string;
  /** Filter by tags */
  tags?: string[];
}

/**
 * Page list sort options
 */
export interface PageListSort {
  /** Sort field */
  field: 'title' | 'createdAt' | 'updatedAt' | 'status';
  /** Sort direction */
  direction: 'asc' | 'desc';
}

/**
 * Page list pagination
 */
export interface PageListPagination {
  /** Current page (1-based) */
  page: number;
  /** Items per page */
  pageSize: number;
  /** Total items */
  total: number;
  /** Total pages */
  totalPages: number;
}

/**
 * Page list result
 */
export interface PageListResult {
  /** Page items */
  items: PageMeta[];
  /** Pagination info */
  pagination: PageListPagination;
}

/**
 * Create page request
 */
export interface CreatePageRequest {
  /** Page title */
  title: string;
  /** Page key (optional, auto-generated if not provided) */
  pageKey?: string;
  /** Page description */
  description?: string;
  /** Page mode */
  mode: PageMode;
  /** Associated ViewModel code */
  viewModelCode?: string;
  /** Template ID to use */
  templateId?: string;
  /** Initial tags */
  tags?: string[];
  /** Layout preset (for form mode) */
  layoutPreset?: 'cols-2' | 'cols-3' | 'cols-4';
}

/**
 * Update page request
 */
export interface UpdatePageRequest {
  /** Page title */
  title?: string;
  /** Page description */
  description?: string;
  /** Tags */
  tags?: string[];
}

/**
 * Page template
 */
export interface PageTemplate {
  /** Template ID */
  id: string;
  /** Template name */
  name: string;
  /** Template description */
  description?: string;
  /** Template mode */
  mode: PageMode;
  /** Template thumbnail */
  thumbnail?: string;
  /** Template category */
  category: string;
  /** Is built-in template */
  isBuiltIn: boolean;
}

/**
 * Mode display info
 */
export const PAGE_MODE_INFO: Record<
  PageMode,
  { label: string; icon: string; description: string }
> = {
  grid: {
    label: '网格模式',
    icon: 'M4 5a1 1 0 011-1h14a1 1 0 011 1v2a1 1 0 01-1 1H5a1 1 0 01-1-1V5zM4 13a1 1 0 011-1h6a1 1 0 011 1v6a1 1 0 01-1 1H5a1 1 0 01-1-1v-6zM16 13a1 1 0 011-1h2a1 1 0 011 1v6a1 1 0 01-1 1h-2a1 1 0 01-1-1v-6z',
    description: '12列自由布局，适合仪表盘',
  },
  floor: {
    label: '楼层模式',
    icon: 'M19 21V5a2 2 0 00-2-2H7a2 2 0 00-2 2v16m14 0h2m-2 0h-5m-9 0H3m2 0h5M9 7h1m-1 4h1m4-4h1m-1 4h1m-5 10v-5a1 1 0 011-1h2a1 1 0 011 1v5m-4 0h4',
    description: 'Tab → 楼层 → 区块，适合复杂表单',
  },
  form: {
    label: '表单模式',
    icon: 'M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z',
    description: '标准表单，支持2/3/4列布局',
  },
  composite: {
    label: '组合模式',
    icon: 'M7 21h10a2 2 0 002-2V9.414a1 1 0 00-.293-.707l-5.414-5.414A1 1 0 0010.586 3H7a2 2 0 00-2 2v14a2 2 0 002 2z',
    description: '组合多个区块，灵活布局',
  },
};

/**
 * Status display info
 */
export const PAGE_STATUS_INFO: Record<
  PageStatus,
  { label: string; color: string; bgColor: string }
> = {
  draft: {
    label: '草稿',
    color: 'text-gray-600',
    bgColor: 'bg-gray-100',
  },
  published: {
    label: '已发布',
    color: 'text-green-600',
    bgColor: 'bg-green-100',
  },
  modified: {
    label: '有更改',
    color: 'text-yellow-600',
    bgColor: 'bg-yellow-100',
  },
  archived: {
    label: '已归档',
    color: 'text-red-600',
    bgColor: 'bg-red-100',
  },
};
