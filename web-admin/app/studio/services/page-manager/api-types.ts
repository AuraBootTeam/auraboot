/**
 * Page Manager API Types
 *
 * Types matching the backend PageSchema API DTOs.
 * These types represent the wire format for API communication.
 *
 * @since 3.2.0
 */

/**
 * Backend page type enum
 * Maps to: FORM, LIST, DETAIL, DASHBOARD, CUSTOM
 */
export type ApiPageType = 'form' | 'list' | 'detail' | 'dashboard' | 'custom';

/**
 * Backend status enum
 * Maps to: draft, published, archived
 */
export type ApiPageStatus = 'draft' | 'published' | 'archived';

/**
 * Page Schema DTO from backend
 * Matches: PageSchemaDTO.java
 */
export interface PageSchemaDTO {
  pid: string;
  name: string;
  title: string;
  description?: string;
  pageType: ApiPageType;
  dslSchema?: Record<string, unknown>;
  metaInfo?: Record<string, unknown>;
  isTemplate?: boolean;
  templateCategory?: string;
  sortWeight?: number;
  /** @deprecated Use status field instead */
  isPublished?: boolean;
  publishedAt?: string;
  tags?: Record<string, unknown>;
  version?: number;
  semver?: string;
  rowVersion?: number;
  isCurrent?: boolean;
  status?: ApiPageStatus;
  extension?: Record<string, unknown>;
  createdAt?: string;
  updatedAt?: string;
}

/**
 * Create page request
 * Matches: PageSchemaCreateRequest.java
 */
export interface PageSchemaCreateRequest {
  name: string;
  pageKey: string; // Required by backend
  title: string;
  description?: string;
  pageType: ApiPageType;
  dslSchema: Record<string, unknown>;
  metaInfo?: Record<string, unknown>;
  isTemplate?: boolean;
  templateCategory?: string;
  sortWeight?: number;
  tags?: Record<string, unknown>;
  semver?: string;
  extension?: Record<string, unknown>;
}

/**
 * Update page request
 * Matches: PageSchemaUpdateRequest.java
 */
export interface PageSchemaUpdateRequest {
  name?: string;
  title?: string;
  description?: string;
  pageType?: ApiPageType;
  dslSchema?: Record<string, unknown>;
  metaInfo?: Record<string, unknown>;
  isTemplate?: boolean;
  templateCategory?: string;
  sortWeight?: number;
  /** @deprecated Use status field instead */
  isPublished?: boolean;
  tags?: Record<string, unknown>;
  semver?: string;
  extension?: Record<string, unknown>;
}

/**
 * Pagination request
 */
export interface PaginationRequest {
  page?: number;
  pageSize?: number;
  sortField?: string;
  sortDirection?: 'asc' | 'desc';
}

/**
 * Pagination result wrapper (canonical shape)
 */
export interface PaginationResult<T> {
  records: T[];
  total: number;
  page: number;
  pageSize: number;
  totalPages: number;
}

/**
 * Version history DTO
 * Matches: PageSchemaVersionDTO.java
 */
export interface PageSchemaVersionDTO {
  id: number;
  pagePid: string;
  version: number;
  semver?: string;
  snapshot: Record<string, unknown>;
  /** Operation type: CREATE, UPDATE, PUBLISH, ARCHIVE, DELETE, RESTORE */
  operation: 'create' | 'update' | 'publish' | 'archive' | 'delete' | 'restore';
  /** Operator PID */
  operatorPid?: string;
  /** Operation time */
  operationTime?: string;
  /** Version description */
  description?: string;
  /** Is current version */
  isCurrent?: boolean;
  /** Is published */
  /** @deprecated Use status field instead */
  isPublished?: boolean;
}

/**
 * Version comparison DTO
 * Matches: PageSchemaVersionComparisonDTO.java
 */
export interface PageSchemaVersionComparisonDTO {
  sourceVersion: VersionInfo;
  targetVersion: VersionInfo;
  differences: FieldDifference[];
  summary?: ComparisonSummary;
}

/**
 * Version info for comparison
 */
export interface VersionInfo {
  historyId: number;
  pagePid: string;
  version: number;
  semver?: string;
  operation: string;
  operationTime?: string;
  operatorPid?: string;
}

/**
 * Field difference detail
 * Matches: PageSchemaVersionComparisonDTO.FieldDifference
 */
export interface FieldDifference {
  fieldPath: string;
  type: 'added' | 'removed' | 'modified';
  sourceValue?: unknown;
  targetValue?: unknown;
  description?: string;
}

/**
 * Comparison summary
 */
export interface ComparisonSummary {
  totalDifferences: number;
  addedFields: number;
  removedFields: number;
  modifiedFields: number;
  hasMajorChanges?: boolean;
  changesByCategory?: Record<string, number>;
}

/**
 * API Response wrapper
 * Matches: ApiResponse.java
 */
export interface ApiResponse<T> {
  code: string;
  desc: string;
  data: T | null;
}
