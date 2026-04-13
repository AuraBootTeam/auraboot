/**
 * Page Schema API Client
 *
 * API client for page schema CRUD operations.
 * Uses the unified HTTP client infrastructure.
 *
 * @since 3.2.0
 */

import { get, post, put, del } from '~/services/http-client';
import type { Result } from '~/services/http-client';
import type {
  PageSchemaDTO,
  PageSchemaCreateRequest,
  PageSchemaUpdateRequest,
  PaginationResult,
  PageSchemaVersionDTO,
  PageSchemaVersionComparisonDTO,
} from './api-types';

const API_BASE = '/api/pages';

/**
 * List pages with filters and pagination
 */
export async function listPages(params?: {
  kind?: string;
  isTemplate?: boolean;
  isPublished?: boolean;
  keyword?: string;
  page?: number;
  pageSize?: number;
  sortField?: string;
  sortDirection?: 'asc' | 'desc';
}): Promise<Result<PaginationResult<PageSchemaDTO>>> {
  return get<PaginationResult<PageSchemaDTO>>(API_BASE, params);
}

/**
 * Get page by PID
 */
export async function getPageByPid(pid: string): Promise<Result<PageSchemaDTO>> {
  return get<PageSchemaDTO>(`${API_BASE}/{pid}`, { pid });
}

/**
 * Get page by name
 */
export async function getPageByName(name: string): Promise<Result<PageSchemaDTO>> {
  return get<PageSchemaDTO>(`${API_BASE}/by-name`, { name });
}

/**
 * Get pages by type
 */
export async function getPagesByType(type: string): Promise<Result<PageSchemaDTO[]>> {
  return get<PageSchemaDTO[]>(`${API_BASE}/by-type/{type}`, { type });
}

/**
 * Get published pages
 */
export async function getPublishedPages(): Promise<Result<PageSchemaDTO[]>> {
  return get<PageSchemaDTO[]>(`${API_BASE}/published`);
}

/**
 * Get template pages
 */
export async function getTemplates(templateCategory?: string): Promise<Result<PageSchemaDTO[]>> {
  return get<PageSchemaDTO[]>(`${API_BASE}/templates`, { templateCategory });
}

/**
 * Create new page
 */
export async function createPage(request: PageSchemaCreateRequest): Promise<Result<PageSchemaDTO>> {
  return post<PageSchemaDTO>(API_BASE, request);
}

/**
 * Update page
 */
export async function updatePage(
  pid: string,
  request: PageSchemaUpdateRequest,
): Promise<Result<PageSchemaDTO>> {
  return put<PageSchemaDTO>(`${API_BASE}/{pid}`, { pid, ...request });
}

/**
 * Delete page (soft delete)
 */
export async function deletePage(pid: string): Promise<Result<void>> {
  return del<void>(`${API_BASE}/{pid}`, { pid });
}

/**
 * Publish page
 */
export async function publishPage(pid: string): Promise<Result<PageSchemaDTO>> {
  return post<PageSchemaDTO>(`${API_BASE}/{pid}/publish`, { pid });
}

/**
 * Unpublish page
 */
export async function unpublishPage(pid: string): Promise<Result<PageSchemaDTO>> {
  return post<PageSchemaDTO>(`${API_BASE}/{pid}/unpublish`, { pid });
}

/**
 * Get version history
 */
export async function getVersionHistory(pid: string): Promise<Result<PageSchemaVersionDTO[]>> {
  return get<PageSchemaVersionDTO[]>(`${API_BASE}/{pid}/versions`, { pid });
}

/**
 * Get latest version
 */
export async function getLatestVersion(pagePid: string): Promise<Result<PageSchemaVersionDTO>> {
  return get<PageSchemaVersionDTO>(`${API_BASE}/latest/{pagePid}`, { pagePid });
}

/**
 * Rollback to version
 * Note: reason is passed as query param per backend @RequestParam
 */
export async function rollbackToVersion(
  pid: string,
  historyId: number,
  reason: string,
): Promise<Result<PageSchemaVersionDTO>> {
  return post<PageSchemaVersionDTO>(
    `${API_BASE}/{pid}/rollback/{historyId}?reason=${encodeURIComponent(reason)}`,
    { pid, historyId },
  );
}

/**
 * Compare versions
 */
export async function compareVersions(
  pid: string,
  fromHistoryId: number,
  toHistoryId: number,
): Promise<Result<PageSchemaVersionComparisonDTO>> {
  return get<PageSchemaVersionComparisonDTO>(
    `${API_BASE}/{pid}/versions/{fromHistoryId}/compare/{toHistoryId}`,
    { pid, fromHistoryId, toHistoryId },
  );
}

/**
 * Validate name uniqueness
 */
export async function validateNameUnique(
  name: string,
  excludePid?: string,
): Promise<Result<boolean>> {
  return get<boolean>(`${API_BASE}/validate/name-unique`, { name, excludePid });
}

/**
 * Get page count statistics
 */
export async function getPageStats(): Promise<{
  total: Result<number>;
  published: Result<number>;
  templates: Result<number>;
}> {
  const [total, published, templates] = await Promise.all([
    get<number>(`${API_BASE}/count/total`),
    get<number>(`${API_BASE}/count/published`),
    get<number>(`${API_BASE}/count/templates`),
  ]);
  return { total, published, templates };
}

/**
 * Get page by entity code
 */
export async function getPageByEntityCode(
  entityCode: string,
  schemaType: string,
): Promise<Result<PageSchemaDTO>> {
  return get<PageSchemaDTO>(`${API_BASE}/entity/{entityCode}`, { entityCode, schemaType });
}

/**
 * Get page by page key
 */
export async function getPageByPageKey(pageKey: string): Promise<Result<PageSchemaDTO>> {
  return get<PageSchemaDTO>(`${API_BASE}/page-key/{pageKey}`, { pageKey });
}

/**
 * List template pages (convenience wrapper around listPages)
 */
export async function listTemplates(): Promise<Result<PaginationResult<PageSchemaDTO>>> {
  return listPages({ isTemplate: true, pageSize: 100 });
}
