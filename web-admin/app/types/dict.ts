/**
 * Dictionary Type Definitions
 */

import type { VersionStatus } from './status';

/**
 * Backend Page Result (MyBatis Plus format)
 */
export interface DictPageResult<T> {
  records: T[];
  total: number;
  size: number;
  current: number;
  pages: number;
}

/**
 * Dictionary DTO
 */
export interface DictDTO {
  id: number;
  pid: string;
  code: string;
  name: string;
  dictType: 'simple' | 'tree' | 'cascade'; // 简化为三种类型
  description?: string;
  remark?: string;
  status: VersionStatus;
  version: number;
  isCurrent: boolean;
  tenantId: number;
  namespace?: string;
  env?: string;
  items?: DictItemData[];
  createdAt: string;
  updatedAt: string;
  createdBy?: string;
  updatedBy?: string;
}

/**
 * Dictionary Item Data
 */
export interface DictItemData {
  value: string;
  label: string;
  order?: number;
  sortOrder?: number; // Alias for order
  parentValue?: string;
  disabled?: boolean;
  extension?: Record<string, any>;
  children?: DictItemData[];
}

/**
 * Dictionary Create Request
 */
export interface DictCreateRequest {
  code: string;
  name: string;
  dictType: 'simple' | 'tree' | 'cascade'; // 简化为三种类型
  sourceType: string; // Required: STATIC, API, SQL, etc.
  description?: string;
  remark?: string;
  items?: Array<{
    value: string;
    label: string;
    sortOrder?: number;
    parentValue?: string; // 用于 TREE 类型
    disabled?: boolean;
    extension?: Record<string, any>;
  }>;
  sourceConfig?: Record<string, any>;
  cascadeConfig?: Record<string, any>;
  cacheConfig?: Record<string, any>;
  extendedProps?: Record<string, any>;
  versionStrategy?: string;
  pinnedVersion?: string;
  sortWeight?: number;
  tags?: string;
  enabled?: boolean;
  isSystem?: boolean;
  versionNote?: string;
  createdBy?: string;
}

/**
 * Dictionary Update Request
 */
export interface DictUpdateRequest {
  name?: string;
  description?: string;
  items?: DictItemData[];
}

/**
 * Dictionary Query Request
 */
export interface DictQueryRequest {
  pageNum?: number;
  pageSize?: number;
  code?: string;
  name?: string;
  dictType?: string;
  status?: string;
}

/**
 * Dictionary Data Result
 */
export interface DictDataResult {
  dictCode: string;
  dictName: string;
  dictType: string;
  version: number;
  items: DictItemData[];
  loadedAt: string;
}

/**
 * Dictionary Statistics
 */
export interface DictStatistics {
  totalCount: number;
  publishedCount: number;
  draftCount: number;
  archivedCount: number;
  simpleCount: number;
  treeCount: number;
  cascadeCount: number;
}

/**
 * Dictionary Validation Result
 */
export interface DictValidationResult {
  valid: boolean;
  errors: string[];
  warnings: string[];
}

/**
 * Dictionary Tree Node
 */
export interface DictTreeNode {
  value: string;
  label: string;
  children?: DictTreeNode[];
  disabled?: boolean;
}
