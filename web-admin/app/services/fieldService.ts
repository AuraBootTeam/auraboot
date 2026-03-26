/**
 * Field Service
 * Handles all field-related API operations
 */

import { get, post, put, del } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';
import type {
  MetaFieldDTO,
  MetaFieldCreateRequest,
  MetaFieldUpdateRequest,
  PageResult,
} from '~/types/model';

/**
 * Helper function to handle API responses
 */
function handleResponse<T>(
  result: { code: string; desc: string; data: T | null },
  errorMsg: string,
): T {
  if (ResultHelper.isSuccess(result) && result.data !== null) {
    return result.data;
  }
  throw new Error(result.desc || errorMsg);
}

/**
 * Create a new field
 */
export async function createField(
  request: MetaFieldCreateRequest,
  httpRequest?: Request,
): Promise<MetaFieldDTO> {
  const result = await post<MetaFieldDTO>('/api/meta/fields', request, undefined, httpRequest);
  return handleResponse(result, 'Failed to create field');
}

/**
 * Get field by PID
 */
export async function getFieldByPid(pid: string, httpRequest?: Request): Promise<MetaFieldDTO> {
  const result = await get<MetaFieldDTO>(
    `/api/meta/fields/${pid}`,
    undefined,
    undefined,
    httpRequest,
  );
  return handleResponse(result, 'Failed to get field');
}

/**
 * Update field
 */
export async function updateField(
  pid: string,
  request: MetaFieldUpdateRequest,
  httpRequest?: Request,
): Promise<MetaFieldDTO> {
  const result = await put<MetaFieldDTO>(
    `/api/meta/fields/${pid}`,
    request,
    undefined,
    httpRequest,
  );
  return handleResponse(result, 'Failed to update field');
}

/**
 * Delete field
 */
export async function deleteField(pid: string, httpRequest?: Request): Promise<void> {
  const result = await del<void>(`/api/meta/fields/${pid}`, undefined, undefined, httpRequest);
  if (!ResultHelper.isSuccess(result)) {
    throw new Error(result.desc || 'Failed to delete field');
  }
}

/**
 * List fields with pagination
 */
export async function listFields(
  params: {
    page?: number;
    size?: number;
    code?: string;
    dataType?: string;
    status?: string;
    currentOnly?: boolean;
  },
  httpRequest?: Request,
): Promise<PageResult<MetaFieldDTO>> {
  const result = await get<PageResult<MetaFieldDTO>>(
    '/api/meta/fields',
    params,
    undefined,
    httpRequest,
  );
  return handleResponse(result, 'Failed to list fields');
}

/**
 * Get field by code (current version)
 */
export async function getFieldByCode(code: string, httpRequest?: Request): Promise<MetaFieldDTO> {
  const result = await get<MetaFieldDTO>(
    `/api/meta/fields/key/${code}`,
    undefined,
    undefined,
    httpRequest,
  );
  return handleResponse(result, 'Failed to get field by code');
}

/**
 * Check if field code is unique
 */
export async function checkCodeUnique(
  code: string,
  excludePid?: string,
  httpRequest?: Request,
): Promise<boolean> {
  const params = excludePid ? { excludePid } : {};
  const result = await get<boolean>(
    `/api/meta/fields/key/${code}/unique`,
    params,
    undefined,
    httpRequest,
  );
  return handleResponse(result, 'Failed to check code uniqueness');
}

export const fieldService = {
  createField,
  getFieldByPid,
  updateField,
  deleteField,
  listFields,
  getFieldByCode,
  checkCodeUnique,
};
