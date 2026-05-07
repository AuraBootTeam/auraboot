/**
 * Named Query Service
 * Handles all named-query-related API calls
 */

import { get, post, put, del } from './http-client';

const BASE_URL = '/api/meta/named-queries';

// ============================================================================
// Types
// ============================================================================

/** SQL WHERE condition structure - dynamic query conditions from backend */
export type WhereCondition = Record<string, unknown>;

/** SQL ORDER BY condition structure - dynamic ordering from backend */
export type OrderCondition = Record<string, unknown>;

/** Lifecycle status for named queries */
export type NamedQueryStatusType = 'draft' | 'testing' | 'published' | 'deprecated' | 'archived';

export interface NamedQueryDTO {
  pid: string;
  code: string;
  title: string;
  description?: string;
  fromSql: string;
  baseWhere?: WhereCondition;
  defaultOrder?: OrderCondition;
  status: NamedQueryStatusType;
  publishedAt?: string;
  publishedBy?: number;
  deprecatedAt?: string;
  currentVersion?: number;
  policy?: NamedQueryPolicyDTO;
  executable?: boolean;
  editable?: boolean;
  frozen?: boolean;
  fieldCount?: number;
  createdAt: string;
  updatedAt: string;
}

export interface NamedQueryPolicyDTO {
  maxRows?: number;
  timeoutMs?: number;
  rateLimitPerMinute?: number;
  cacheTtlSeconds?: number;
  exportMaxRows?: number;
  sandboxMaxRows?: number;
}

export type UiComponentType =
  | 'text'
  | 'number'
  | 'numberRange'
  | 'select'
  | 'dateRange'
  | 'date'
  | 'userPicker'
  | 'cascader'
  | 'search'
  | 'switch';

export interface NamedQueryFieldDTO {
  fieldCode: string;
  columnExpr: string;
  dataType: string;
  operators?: string[];
  sortable?: boolean;
  searchable?: boolean;
  uiComponent?: UiComponentType;
  placeholder?: string;
  defaultValue?: string;
  linkedField?: string;
  required?: boolean;
  displayName?: string;
  sortOrder?: number;
  fieldGroup?: string;
  uiConfig?: Record<string, unknown>;
}

export interface NamedQueryCreateRequest {
  code: string;
  title: string;
  description?: string;
  fromSql: string;
  baseWhere?: WhereCondition;
  defaultOrder?: OrderCondition;
}

export interface NamedQueryUpdateRequest {
  title?: string;
  description?: string;
  fromSql?: string;
  baseWhere?: WhereCondition;
  defaultOrder?: OrderCondition;
  policy?: NamedQueryPolicyDTO;
}

export interface NamedQueryQueryRequest {
  pageNum?: number;
  pageSize?: number;
  keyword?: string;
  status?: string;
}

export interface NamedQueryFieldRequest {
  fieldCode: string;
  columnExpr: string;
  dataType: string;
  operators?: string[];
  sortable?: boolean;
  searchable?: boolean;
  uiComponent?: UiComponentType;
  placeholder?: string;
  defaultValue?: string;
  linkedField?: string;
  required?: boolean;
  displayName?: string;
  sortOrder?: number;
  fieldGroup?: string;
  uiConfig?: Record<string, unknown>;
}

export interface NamedQueryTestRequest {
  where?: WhereCondition;
  orderBy?: OrderCondition;
  pageNum?: number;
  pageSize?: number;
  params?: Record<string, unknown>;
}

export interface NamedQueryTestResult {
  success?: boolean;
  message?: string;
  resultCount?: number;
  sampleData?: Record<string, unknown>[];
  executedSql?: string;
  executionTimeMs?: number;
  warnings?: string[];
  errorMessage?: string;
  executionStats?: {
    rowsScanned?: number;
    rowsReturned?: number;
    bytesProcessed?: number;
  };
}

export interface NamedQueryValidationRequest {
  fromSql: string;
  baseWhere?: WhereCondition;
}

export interface NamedQueryValidationResult {
  valid: boolean;
  message?: string;
  errors?: string[];
}

export interface NamedQueryDataExportRequest {
  format?: 'excel' | 'csv' | 'json';
  fields?: string[];
  whereConditions?: WhereCondition;
  orderConditions?: OrderCondition;
  limit?: number;
  includeHeader?: boolean;
  fileName?: string;
}

export interface ExportTaskDTO {
  pid: string;
  queryCode: string;
  status: 'pending' | 'running' | 'completed' | 'failed' | 'expired';
  progress: number;
  totalRows?: number;
  processedRows?: number;
  fileSize?: number;
  format?: string;
  errorMessage?: string;
  downloadUrl?: string;
  createdAt: string;
  completedAt?: string;
  expiresAt?: string;
}

export interface NamedQueryExportResponse {
  success: boolean;
  downloadUrl: string;
  recordCount: number;
  fileSize: number;
  format: string;
}

export interface NamedQueryVersionDTO {
  pid: string;
  queryCode: string;
  versionNo: number;
  fromSql: string;
  baseWhere?: WhereCondition;
  defaultOrder?: OrderCondition;
  fieldsSnapshot?: Record<string, unknown>[];
  policy?: NamedQueryPolicyDTO;
  description?: string;
  status: string;
  publishedAt?: string;
  publishedBy?: number;
  createdAt: string;
}

export interface PageResult<T> {
  records: T[];
  total: number;
  size: number;
  current: number;
  pages: number;
}

type NamedQueryPagePayload<T> =
  | PageResult<T>
  | {
      records?: T[];
      total?: number;
      size?: number;
      current?: number;
      pages?: number;
      pageSize?: number;
      page?: number;
      totalPages?: number;
    };

// ============================================================================
// Service
// ============================================================================

/**
 * Handle API response
 */
function handleResponse<T>(
  result: { success?: boolean; message?: string; data: T | null },
  errorMessage: string,
): T {
  if (!result.success) {
    throw new Error(result.message || errorMessage);
  }
  return result.data as T;
}

function normalizePageResult<T>(payload: NamedQueryPagePayload<T>): PageResult<T> {
  const p = payload as PageResult<T> & {
    pageSize?: number;
    page?: number;
    totalPages?: number;
  };
  return {
    records: p.records ?? [],
    total: p.total ?? 0,
    size: p.size ?? p.pageSize ?? 20,
    current: p.current ?? p.page ?? 1,
    pages: p.pages ?? p.totalPages ?? 0,
  };
}

export const namedQueryService = {
  /**
   * Create a new named query
   */
  async create(request: NamedQueryCreateRequest, httpRequest?: Request): Promise<NamedQueryDTO> {
    const result = await post<NamedQueryDTO>(BASE_URL, request, undefined, httpRequest);
    return handleResponse(result, 'Failed to create named query');
  },

  /**
   * Update named query
   */
  async update(
    pid: string,
    request: NamedQueryUpdateRequest,
    httpRequest?: Request,
  ): Promise<NamedQueryDTO> {
    const result = await put<NamedQueryDTO>(`${BASE_URL}/${pid}`, request, undefined, httpRequest);
    return handleResponse(result, 'Failed to update named query');
  },

  /**
   * Delete named query
   */
  async delete(pid: string, httpRequest?: Request): Promise<void> {
    const result = await del<void>(`${BASE_URL}/${pid}`, undefined, undefined, httpRequest);
    handleResponse(result, 'Failed to delete named query');
  },

  /**
   * Get named query by PID
   */
  async findByPid(pid: string, httpRequest?: Request): Promise<NamedQueryDTO> {
    const result = await get<NamedQueryDTO>(
      `${BASE_URL}/${pid}`,
      undefined,
      undefined,
      httpRequest,
    );
    return handleResponse(result, 'Failed to fetch named query');
  },

  /**
   * Query named queries with pagination
   */
  async query(
    request: NamedQueryQueryRequest,
    httpRequest?: Request,
  ): Promise<PageResult<NamedQueryDTO>> {
    const params = new URLSearchParams();
    if (request.pageNum) params.append('pageNum', request.pageNum.toString());
    if (request.pageSize) params.append('pageSize', request.pageSize.toString());
    if (request.keyword) params.append('keyword', request.keyword);
    if (request.status) params.append('status', request.status);

    const result = await get<PageResult<NamedQueryDTO>>(
      `${BASE_URL}?${params.toString()}`,
      undefined,
      undefined,
      httpRequest,
    );
    return normalizePageResult(handleResponse(result, 'Failed to query named queries'));
  },

  /**
   * Update named query status
   */
  async updateStatus(pid: string, status: string, httpRequest?: Request): Promise<NamedQueryDTO> {
    const params = new URLSearchParams({ status });
    const result = await put<NamedQueryDTO>(
      `${BASE_URL}/${pid}/status?${params.toString()}`,
      undefined,
      undefined,
      httpRequest,
    );
    return handleResponse(result, 'Failed to update named query status');
  },

  /**
   * Batch update named query status
   */
  async batchUpdateStatus(
    pids: string[],
    status: string,
    httpRequest?: Request,
  ): Promise<{ updated: number }> {
    const result = await post<{ updated: number }>(
      `${BASE_URL}/batch-status`,
      { pids, status },
      undefined,
      httpRequest,
    );
    return handleResponse(result, 'Failed to batch update status');
  },

  /**
   * Get fields for a named query
   */
  async getFields(code: string, httpRequest?: Request): Promise<NamedQueryFieldDTO[]> {
    const result = await get<NamedQueryFieldDTO[]>(
      `${BASE_URL}/${code}/fields`,
      undefined,
      undefined,
      httpRequest,
    );
    return handleResponse(result, 'Failed to fetch named query fields');
  },

  /**
   * Add field to a named query
   */
  async addField(
    code: string,
    field: NamedQueryFieldRequest,
    httpRequest?: Request,
  ): Promise<NamedQueryFieldDTO> {
    const result = await post<NamedQueryFieldDTO>(
      `${BASE_URL}/${code}/fields`,
      field,
      undefined,
      httpRequest,
    );
    return handleResponse(result, 'Failed to add field');
  },

  /**
   * Update field of a named query
   */
  async updateField(
    code: string,
    fieldCode: string,
    field: NamedQueryFieldRequest,
    httpRequest?: Request,
  ): Promise<NamedQueryFieldDTO> {
    const result = await put<NamedQueryFieldDTO>(
      `${BASE_URL}/${code}/fields/${fieldCode}`,
      field,
      undefined,
      httpRequest,
    );
    return handleResponse(result, 'Failed to update field');
  },

  /**
   * Batch save fields for a named query
   */
  async batchSaveFields(
    code: string,
    fields: NamedQueryFieldRequest[],
    httpRequest?: Request,
  ): Promise<NamedQueryFieldDTO[]> {
    const result = await post<NamedQueryFieldDTO[]>(
      `${BASE_URL}/${code}/fields/batch`,
      fields,
      undefined,
      httpRequest,
    );
    return handleResponse(result, 'Failed to batch save fields');
  },

  /**
   * Delete field from a named query
   */
  async deleteField(code: string, fieldCode: string, httpRequest?: Request): Promise<void> {
    const result = await del<void>(
      `${BASE_URL}/${code}/fields/${fieldCode}`,
      undefined,
      undefined,
      httpRequest,
    );
    handleResponse(result, 'Failed to delete field');
  },

  /**
   * Get version history for a named query
   */
  async getVersions(code: string, httpRequest?: Request): Promise<NamedQueryVersionDTO[]> {
    const result = await get<NamedQueryVersionDTO[]>(
      `${BASE_URL}/${code}/versions`,
      undefined,
      undefined,
      httpRequest,
    );
    return handleResponse(result, 'Failed to fetch versions');
  },

  /**
   * Get a specific version
   */
  async getVersion(
    code: string,
    versionNo: number,
    httpRequest?: Request,
  ): Promise<NamedQueryVersionDTO> {
    const result = await get<NamedQueryVersionDTO>(
      `${BASE_URL}/${code}/versions/${versionNo}`,
      undefined,
      undefined,
      httpRequest,
    );
    return handleResponse(result, 'Failed to fetch version');
  },

  /**
   * Get param schema (searchable fields with UI hints, sorted by sort_order)
   */
  async getParamSchema(code: string, httpRequest?: Request): Promise<NamedQueryFieldDTO[]> {
    const result = await get<NamedQueryFieldDTO[]>(
      `${BASE_URL}/${code}/param-schema`,
      undefined,
      undefined,
      httpRequest,
    );
    return handleResponse(result, 'Failed to fetch param schema');
  },

  /**
   * Test named query execution
   */
  async testQuery(
    pid: string,
    request: NamedQueryTestRequest,
    httpRequest?: Request,
  ): Promise<NamedQueryTestResult> {
    const result = await post<NamedQueryTestResult>(
      `${BASE_URL}/${pid}/test`,
      request,
      undefined,
      httpRequest,
    );
    return handleResponse(result, 'Failed to test named query');
  },

  /**
   * Validate named query
   */
  async validate(
    request: NamedQueryValidationRequest,
    httpRequest?: Request,
  ): Promise<NamedQueryValidationResult> {
    const result = await post<NamedQueryValidationResult>(
      `${BASE_URL}/validate`,
      request,
      undefined,
      httpRequest,
    );
    return handleResponse(result, 'Failed to validate named query');
  },

  /**
   * Export named query result data
   */
  async exportData(
    code: string,
    request: NamedQueryDataExportRequest,
    httpRequest?: Request,
  ): Promise<NamedQueryExportResponse> {
    const result = await post<NamedQueryExportResponse>(
      `${BASE_URL}/${code}/export-data`,
      request,
      undefined,
      httpRequest,
    );
    return handleResponse(result, 'Failed to export named query data');
  },

  /**
   * Submit async export task
   */
  async submitAsyncExport(
    code: string,
    request: NamedQueryDataExportRequest,
    httpRequest?: Request,
  ): Promise<ExportTaskDTO> {
    const result = await post<ExportTaskDTO>(
      `${BASE_URL}/${code}/export-async`,
      request,
      undefined,
      httpRequest,
    );
    return handleResponse(result, 'Failed to submit async export');
  },

  /**
   * Get export task status
   */
  async getExportTaskStatus(taskPid: string, httpRequest?: Request): Promise<ExportTaskDTO> {
    const result = await get<ExportTaskDTO>(
      `${BASE_URL}/export-tasks/${taskPid}`,
      undefined,
      undefined,
      httpRequest,
    );
    return handleResponse(result, 'Failed to get export task status');
  },

  /**
   * Trigger file download for an export
   */
  downloadExport(downloadUrl: string): void {
    window.open(downloadUrl, '_blank');
  },
};
