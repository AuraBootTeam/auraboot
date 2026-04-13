/**
 * Field Library Service
 * Handles field library, usage tracking, and impact analysis API operations
 */

import { get, post, put, del } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import type {
  FieldSearchRequest,
  FieldSearchResult,
  FieldRecommendation,
  FieldUsageInfo,
  FieldImpactAnalysis,
  BindingConfiguration,
  MetaFieldDTO,
  PageResult,
} from '~/types/fieldLibrary';

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

// ============================================================================
// Field Library APIs
// ============================================================================

/**
 * Search fields in library with advanced filters
 */
export async function searchFields(
  request: FieldSearchRequest,
  httpRequest?: Request,
): Promise<FieldSearchResult> {
  const result = await post<FieldSearchResult>(
    '/api/meta/field-library/search',
    request,
    undefined,
    httpRequest,
  );
  return handleResponse(result, 'Failed to search fields');
}

/**
 * Get field recommendations for a model
 */
export async function getFieldRecommendations(
  modelPid: string,
  httpRequest?: Request,
): Promise<FieldRecommendation[]> {
  const result = await get<FieldRecommendation[]>(
    `/api/meta/field-library/recommendations`,
    { modelPid },
    undefined,
    httpRequest,
  );
  return handleResponse(result, 'Failed to get field recommendations');
}

/**
 * Get unused fields
 */
export async function getUnusedFields(httpRequest?: Request): Promise<MetaFieldDTO[]> {
  const result = await get<MetaFieldDTO[]>(
    '/api/meta/field-library/unused',
    undefined,
    undefined,
    httpRequest,
  );
  return handleResponse(result, 'Failed to get unused fields');
}

/**
 * Get system fields
 */
export async function getSystemFields(httpRequest?: Request): Promise<MetaFieldDTO[]> {
  const result = await get<MetaFieldDTO[]>(
    '/api/meta/field-library/system',
    undefined,
    undefined,
    httpRequest,
  );
  return handleResponse(result, 'Failed to get system fields');
}

// ============================================================================
// Field Usage APIs
// ============================================================================

/**
 * Get field usage information
 */
export async function getFieldUsage(
  fieldPid: string,
  httpRequest?: Request,
): Promise<FieldUsageInfo> {
  const result = await get<FieldUsageInfo>(
    `/api/meta/fields/${fieldPid}/usage`,
    undefined,
    undefined,
    httpRequest,
  );
  return handleResponse(result, 'Failed to get field usage');
}

/**
 * Get binding configurations for a field
 */
export async function getBindingConfigurations(
  fieldPid: string,
  httpRequest?: Request,
): Promise<BindingConfiguration[]> {
  const result = await get<BindingConfiguration[]>(
    `/api/meta/fields/${fieldPid}/usage/bindings`,
    undefined,
    undefined,
    httpRequest,
  );
  return handleResponse(result, 'Failed to get binding configurations');
}

/**
 * Refresh field usage cache
 */
export async function refreshFieldUsageCache(
  fieldPid: string,
  httpRequest?: Request,
): Promise<void> {
  const result = await post<void>(
    `/api/meta/fields/${fieldPid}/usage/refresh`,
    {},
    undefined,
    httpRequest,
  );
  if (!ResultHelper.isSuccess(result)) {
    throw new Error(result.desc || 'Failed to refresh field usage cache');
  }
}

// ============================================================================
// Field Impact Analysis APIs
// ============================================================================

/**
 * Analyze field impact
 */
export async function analyzeFieldImpact(
  fieldPid: string,
  httpRequest?: Request,
): Promise<FieldImpactAnalysis> {
  const result = await get<FieldImpactAnalysis>(
    `/api/meta/fields/${fieldPid}/impact`,
    undefined,
    undefined,
    httpRequest,
  );
  return handleResponse(result, 'Failed to analyze field impact');
}

/**
 * Validate field modification
 */
export async function validateFieldModification(
  fieldPid: string,
  modifications: Record<string, any>,
  httpRequest?: Request,
): Promise<{ valid: boolean; issues: string[] }> {
  const result = await post<{ valid: boolean; issues: string[] }>(
    `/api/meta/fields/${fieldPid}/impact/validate`,
    modifications,
    undefined,
    httpRequest,
  );
  return handleResponse(result, 'Failed to validate field modification');
}

/**
 * Validate field deletion
 */
export async function validateFieldDeletion(
  fieldPid: string,
  httpRequest?: Request,
): Promise<{ canDelete: boolean; blockingReasons: string[] }> {
  const result = await get<{ canDelete: boolean; blockingReasons: string[] }>(
    `/api/meta/fields/${fieldPid}/impact/validate-deletion`,
    undefined,
    undefined,
    httpRequest,
  );
  return handleResponse(result, 'Failed to validate field deletion');
}

export const fieldLibraryService = {
  searchFields,
  getFieldRecommendations,
  getUnusedFields,
  getSystemFields,
  getFieldUsage,
  getBindingConfigurations,
  refreshFieldUsageCache,
  analyzeFieldImpact,
  validateFieldModification,
  validateFieldDeletion,
  createField,
  checkFieldCodeUnique,
};

/**
 * Create a new field
 */
export async function createField(
  request: {
    code: string;
    dataType: string;
    feature?: {
      required?: boolean;
      unique?: boolean;
      indexed?: boolean;
    };
    status?: string;
    autoPublish?: boolean;
    modelPid?: string;
  },
  httpRequest?: Request,
): Promise<MetaFieldDTO> {
  const result = await post<MetaFieldDTO>('/api/meta/fields', request, undefined, httpRequest);
  return handleResponse(result, 'Failed to create field');
}

/**
 * Check if field code is unique
 */
export async function checkFieldCodeUnique(
  code: string,
  excludePid?: string,
  httpRequest?: Request,
): Promise<boolean> {
  const params = excludePid ? `?excludePid=${excludePid}` : '';
  const result = await get<boolean>(
    `/api/meta/fields/key/${code}/unique${params}`,
    undefined,
    undefined,
    httpRequest,
  );
  return handleResponse(result, 'Failed to check field code uniqueness');
}
