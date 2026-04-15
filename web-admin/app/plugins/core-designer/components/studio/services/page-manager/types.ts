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
 * Page metadata
 */
export interface PageMeta {
  /** Page unique ID */
  id: string;
  /** Page key */
  pageKey?: string;
  /** Page title */
  title: string;
  /** Page description */
  description?: string;
  /** Page kind (V2: list | form | detail) */
  kind: 'list' | 'form' | 'detail';
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
  /** Filter by kind */
  kind?: 'list' | 'form' | 'detail' | 'all';
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
  /** Page kind (V2: list | form | detail) */
  kind: 'list' | 'form' | 'detail';
  /** Associated ViewModel code */
  viewModelCode?: string;
  /** Template ID to use */
  templateId?: string;
  /** Initial tags */
  tags?: string[];
  /** Layout preset (for form kind) */
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
  /** Template kind (V2: list | form | detail) */
  kind: 'list' | 'form' | 'detail';
  /** Template thumbnail */
  thumbnail?: string;
  /** Template category */
  category: string;
  /** Is built-in template */
  isBuiltIn: boolean;
}

/**
 * Kind display info
 */
export const PAGE_KIND_INFO: Record<
  'list' | 'form' | 'detail',
  { label: string; icon: string; description: string }
> = {
  list: {
    label: '列表页',
    icon: 'M4 5a1 1 0 011-1h14a1 1 0 011 1v2a1 1 0 01-1 1H5a1 1 0 01-1-1V5zM4 13a1 1 0 011-1h6a1 1 0 011 1v6a1 1 0 01-1 1H5a1 1 0 01-1-1v-6zM16 13a1 1 0 011-1h2a1 1 0 011 1v6a1 1 0 01-1 1h-2a1 1 0 01-1-1v-6z',
    description: '表格+筛选+工具栏，标准列表视图',
  },
  form: {
    label: '表单页',
    icon: 'M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z',
    description: '标准表单，支持2/3/4列布局',
  },
  detail: {
    label: '详情页',
    icon: 'M19 21V5a2 2 0 00-2-2H7a2 2 0 00-2 2v16m14 0h2m-2 0h-5m-9 0H3m2 0h5M9 7h1m-1 4h1m4-4h1m-1 4h1m-5 10v-5a1 1 0 011-1h2a1 1 0 011 1v5m-4 0h4',
    description: '只读详情展示，支持分组区块',
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
