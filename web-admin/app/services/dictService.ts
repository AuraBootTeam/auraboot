/**
 * Dictionary Service
 * Handles all dictionary-related API calls
 */

import { get, post, put, del } from './http-client';
import type {
  DictDTO,
  DictCreateRequest,
  DictUpdateRequest,
  DictQueryRequest,
  DictDataResult,
  DictStatistics,
  DictValidationResult,
  DictTreeNode,
  DictItemData,
  DictPageResult,
} from '~/types/dict';

const BASE_URL = '/api/meta/dict';

/**
 * Handle API response
 */
function handleResponse<T>(result: any, errorMessage: string): T {
  if (!result.success) {
    throw new Error(result.message || errorMessage);
  }
  return result.data;
}

export const dictService = {
  /**
   * Create a new dictionary
   */
  async create(request: DictCreateRequest, httpRequest?: Request): Promise<DictDTO> {
    const result = await post<DictDTO>(BASE_URL, request, undefined, httpRequest);
    return handleResponse(result, 'Failed to create dictionary');
  },

  /**
   * Update dictionary
   */
  async update(pid: string, request: DictUpdateRequest, httpRequest?: Request): Promise<DictDTO> {
    const result = await put<DictDTO>(`${BASE_URL}/${pid}`, request, undefined, httpRequest);
    return handleResponse(result, 'Failed to update dictionary');
  },

  /**
   * Replace dictionary items
   */
  async replaceItems(
    pid: string,
    items: Array<{
      value: string;
      label: string;
      sortOrder?: number;
      parentValue?: string;
      disabled?: boolean;
      extension?: Record<string, any>;
    }>,
    httpRequest?: Request,
  ): Promise<DictDTO> {
    const result = await put<DictDTO>(`${BASE_URL}/${pid}/items`, items, undefined, httpRequest);
    return handleResponse(result, 'Failed to replace dictionary items');
  },

  /**
   * Delete dictionary
   */
  async delete(pid: string, httpRequest?: Request): Promise<void> {
    const result = await del<void>(`${BASE_URL}/${pid}`, undefined, undefined, httpRequest);
    handleResponse(result, 'Failed to delete dictionary');
  },

  /**
   * Get dictionary by PID
   */
  async findByPid(pid: string, httpRequest?: Request): Promise<DictDTO> {
    const result = await get<DictDTO>(`${BASE_URL}/${pid}`, undefined, undefined, httpRequest);
    return handleResponse(result, 'Failed to fetch dictionary');
  },

  /**
   * Get dictionary by code
   */
  async findByCode(code: string, httpRequest?: Request): Promise<DictDTO> {
    const result = await get<DictDTO>(
      `${BASE_URL}/by-code/${code}`,
      undefined,
      undefined,
      httpRequest,
    );
    return handleResponse(result, 'Failed to fetch dictionary');
  },

  /**
   * Query dictionaries with pagination
   */
  async query(request: DictQueryRequest, httpRequest?: Request): Promise<DictPageResult<DictDTO>> {
    const params = new URLSearchParams();
    if (request.pageNum) params.append('pageNum', request.pageNum.toString());
    if (request.pageSize) params.append('pageSize', request.pageSize.toString());
    if (request.code) params.append('code', request.code);
    if (request.name) params.append('name', request.name);
    if (request.dictType) params.append('dictType', request.dictType);
    if (request.status) params.append('status', request.status);

    const result = await get<DictPageResult<DictDTO>>(
      `${BASE_URL}?${params.toString()}`,
      undefined,
      undefined,
      httpRequest,
    );
    return handleResponse(result, 'Failed to query dictionaries');
  },

  /**
   * Load dictionary data
   */
  async loadData(
    pid: string,
    versionStrategy: string = 'latest',
    pinnedVersion?: string,
    httpRequest?: Request,
  ): Promise<DictDataResult> {
    const params = new URLSearchParams({ versionStrategy });
    if (pinnedVersion) params.append('pinnedVersion', pinnedVersion);

    const result = await get<DictDataResult>(
      `${BASE_URL}/${pid}/data?${params.toString()}`,
      undefined,
      undefined,
      httpRequest,
    );
    return handleResponse(result, 'Failed to load dictionary data');
  },

  /**
   * Publish dictionary
   */
  async publish(pid: string, versionNote?: string, httpRequest?: Request): Promise<DictDTO> {
    const params = versionNote ? new URLSearchParams({ versionNote }) : undefined;
    const url = params
      ? `${BASE_URL}/${pid}/publish?${params.toString()}`
      : `${BASE_URL}/${pid}/publish`;

    const result = await post<DictDTO>(url, undefined, undefined, httpRequest);
    return handleResponse(result, 'Failed to publish dictionary');
  },

  /**
   * Unpublish dictionary
   */
  async unpublish(pid: string, httpRequest?: Request): Promise<DictDTO> {
    const result = await post<DictDTO>(
      `${BASE_URL}/${pid}/unpublish`,
      undefined,
      undefined,
      httpRequest,
    );
    return handleResponse(result, 'Failed to unpublish dictionary');
  },

  /**
   * Get version history
   */
  async getVersionHistory(code: string, httpRequest?: Request): Promise<DictDTO[]> {
    const result = await get<DictDTO[]>(
      `${BASE_URL}/${code}/versions`,
      undefined,
      undefined,
      httpRequest,
    );
    return handleResponse(result, 'Failed to fetch version history');
  },

  /**
   * Get cascade children
   */
  async getCascadeChildren(
    pid: string,
    parentValue?: string,
    httpRequest?: Request,
  ): Promise<DictItemData[]> {
    const params = parentValue ? new URLSearchParams({ parentValue }) : undefined;
    const url = params
      ? `${BASE_URL}/${pid}/cascade/children?${params.toString()}`
      : `${BASE_URL}/${pid}/cascade/children`;

    const result = await get<DictItemData[]>(url, undefined, undefined, httpRequest);
    return handleResponse(result, 'Failed to fetch cascade children');
  },

  /**
   * Build cascade tree
   */
  async buildCascadeTree(pid: string, httpRequest?: Request): Promise<DictTreeNode> {
    const result = await get<DictTreeNode>(
      `${BASE_URL}/${pid}/cascade/tree`,
      undefined,
      undefined,
      httpRequest,
    );
    return handleResponse(result, 'Failed to build cascade tree');
  },

  /**
   * Get statistics
   */
  async getStatistics(httpRequest?: Request): Promise<DictStatistics> {
    const result = await get<DictStatistics>(
      `${BASE_URL}/statistics`,
      undefined,
      undefined,
      httpRequest,
    );
    return handleResponse(result, 'Failed to fetch statistics');
  },

  /**
   * Validate dictionary config
   */
  async validateConfig(code: string, httpRequest?: Request): Promise<DictValidationResult> {
    const result = await get<DictValidationResult>(
      `${BASE_URL}/${code}/validate`,
      undefined,
      undefined,
      httpRequest,
    );
    return handleResponse(result, 'Failed to validate dictionary');
  },

  /**
   * Check code uniqueness
   */
  async checkCodeUnique(
    code: string,
    excludePid?: string,
    httpRequest?: Request,
  ): Promise<boolean> {
    const params = excludePid ? new URLSearchParams({ excludePid }) : undefined;
    const url = params
      ? `${BASE_URL}/code/${code}/unique?${params.toString()}`
      : `${BASE_URL}/code/${code}/unique`;

    const result = await get<boolean>(url, undefined, undefined, httpRequest);
    return handleResponse(result, 'Failed to check code uniqueness');
  },

  /**
   * Batch delete dictionaries
   */
  async batchDelete(pids: string[], httpRequest?: Request): Promise<number> {
    const params = new URLSearchParams();
    pids.forEach((pid) => params.append('pids', pid));

    const result = await del<number>(
      `${BASE_URL}/batch?${params.toString()}`,
      undefined,
      undefined,
      httpRequest,
    );
    return handleResponse(result, 'Failed to batch delete dictionaries');
  },

  /**
   * Search dictionaries
   */
  async search(keyword: string, httpRequest?: Request): Promise<DictDTO[]> {
    const params = new URLSearchParams({ keyword });
    const result = await get<DictDTO[]>(
      `${BASE_URL}/search?${params.toString()}`,
      undefined,
      undefined,
      httpRequest,
    );
    return handleResponse(result, 'Failed to search dictionaries');
  },
};
