/**
 * API Client for E2E tests
 * Encapsulates all API calls for model system testing
 */

import type { APIRequestContext, Page } from '@playwright/test';
import { ErrorCodes } from '~/shared/services/http-client/types';
import type {
  ModelTestData,
  FieldTestData,
  DictTestData,
  FieldBindingTestData,
  VirtualFieldTestData,
} from './test-data';

/**
 * API Response wrapper - supports both formats
 * Backend can return either:
 * 1. { code: "0", desc: "success", data: T } - for most APIs
 * 2. { success: true, message: "...", data: T } - for some APIs (like dict)
 */
export interface ApiResponse<T> {
  code?: string;
  desc?: string;
  data: T | null;
  success?: boolean;
  message?: string;
}

/**
 * Model API Response
 */
export interface ModelResponse {
  id: number;
  pid: string;
  code: string;
  displayName: string;
  modelType: string;
  status: string;
  version: number;
  isCurrent: boolean;
  namespace: string;
  env: string;
  tenantId: number;
  extension?: Record<string, any>;
  createdAt: string;
  updatedAt: string;
}

/**
 * Field API Response
 */
export interface FieldResponse {
  id: number;
  pid: string;
  code: string;
  dataType: string;
  version: number;
  isCurrent: boolean;
  status: string;
  tenantId: number;
  namespace: string;
  env: string;
  extension?: Record<string, any>;
  feature?: Record<string, any>;
  uiSchema?: Record<string, any>;
  createdAt: string;
  updatedAt: string;
}

/**
 * Dictionary API Response
 */
export interface DictResponse {
  id: number;
  pid: string;
  code: string;
  name: string;
  dictType: string;
  status: string;
  version: number;
  isCurrent: boolean;
  items?: DictItemResponse[];
  createdAt: string;
  updatedAt: string;
}

/**
 * Dictionary Item Response
 */
export interface DictItemResponse {
  id: number;
  value: string;
  label: string;
  sortOrder?: number;
  parentValue?: string;
  disabled?: boolean;
}

/**
 * API Client class for E2E tests
 * Uses page.request to inherit cookies from the logged-in page context
 */
export class ApiClient {
  private request: APIRequestContext;

  /**
   * Create an API client
   * @param pageOrRequest - Page object (recommended, inherits cookies) or APIRequestContext
   */
  constructor(pageOrRequest: Page | APIRequestContext) {
    // Check if it's a Page object by looking for page-specific methods
    if ('goto' in pageOrRequest && 'locator' in pageOrRequest) {
      // This is a Page object - access its request context
      this.request = (pageOrRequest as Page).request;
    } else {
      // This is an APIRequestContext
      this.request = pageOrRequest as APIRequestContext;
    }
  }

  // ============================================================================
  // Model APIs
  // ============================================================================

  /**
   * Create a model
   */
  async createModel(data: ModelTestData): Promise<ApiResponse<ModelResponse>> {
    const response = await this.request.post(`/api/meta/models`, {
      data,
    });
    return response.json();
  }

  /**
   * Get model by PID
   */
  async getModelByPid(pid: string): Promise<ApiResponse<ModelResponse>> {
    const response = await this.request.get(`/api/meta/models/${pid}`);
    return response.json();
  }

  /**
   * Get model by code
   */
  async getModelByCode(code: string): Promise<ApiResponse<ModelResponse>> {
    const response = await this.request.get(`/api/meta/models/code/${code}`);
    return response.json();
  }

  /**
   * Update model
   */
  async updateModel(
    pid: string,
    data: Partial<ModelTestData>,
  ): Promise<ApiResponse<ModelResponse>> {
    const response = await this.request.put(`/api/meta/models/${pid}`, {
      data,
    });
    return response.json();
  }

  /**
   * Delete model
   */
  async deleteModel(pid: string): Promise<ApiResponse<void>> {
    const response = await this.request.delete(`/api/meta/models/${pid}`);
    return response.json();
  }

  /**
   * Publish model
   */
  async publishModel(pid: string, versionNote?: string): Promise<ApiResponse<ModelResponse>> {
    const params = versionNote ? `?versionNote=${encodeURIComponent(versionNote)}` : '';
    const response = await this.request.post(`/api/meta/models/${pid}/publish${params}`);
    return response.json();
  }

  /**
   * Get model version history
   */
  async getModelVersionHistory(code: string): Promise<ApiResponse<any[]>> {
    const response = await this.request.get(`/api/meta/models/code/${code}/versions`);
    return response.json();
  }

  /**
   * Rollback model to version
   */
  async rollbackModel(code: string, version: number): Promise<ApiResponse<ModelResponse>> {
    const response = await this.request.post(`/api/meta/models/code/${code}/rollback`, {
      data: { version },
    });
    return response.json();
  }

  /**
   * Check model code uniqueness
   */
  async checkModelCodeUnique(code: string, excludePid?: string): Promise<ApiResponse<boolean>> {
    const params = excludePid ? `?excludePid=${excludePid}` : '';
    const response = await this.request.get(`/api/meta/models/code/${code}/unique${params}`);
    return response.json();
  }

  /**
   * Get model fields
   */
  async getModelFields(pid: string): Promise<ApiResponse<any[]>> {
    const response = await this.request.get(`/api/meta/models/${pid}/fields`);
    return response.json();
  }

  // ============================================================================
  // Field APIs
  // ============================================================================

  /**
   * Create a field
   */
  async createField(
    data: FieldTestData | VirtualFieldTestData,
  ): Promise<ApiResponse<FieldResponse>> {
    const response = await this.request.post(`/api/meta/fields`, {
      data,
    });
    return response.json();
  }

  /**
   * Get field by PID
   */
  async getFieldByPid(pid: string): Promise<ApiResponse<FieldResponse>> {
    const response = await this.request.get(`/api/meta/fields/${pid}`);
    return response.json();
  }

  /**
   * Get field by code
   */
  async getFieldByCode(code: string): Promise<ApiResponse<FieldResponse>> {
    const response = await this.request.get(`/api/meta/fields/key/${code}`);
    return response.json();
  }

  /**
   * Update field
   */
  async updateField(
    pid: string,
    data: Partial<FieldTestData>,
  ): Promise<ApiResponse<FieldResponse>> {
    const response = await this.request.put(`/api/meta/fields/${pid}`, {
      data,
    });
    return response.json();
  }

  /**
   * Delete field
   */
  async deleteField(pid: string): Promise<ApiResponse<void>> {
    const response = await this.request.delete(`/api/meta/fields/${pid}`);
    return response.json();
  }

  /**
   * Bind field to model
   */
  async bindFieldToModel(
    modelPid: string,
    binding: FieldBindingTestData,
  ): Promise<ApiResponse<any>> {
    const response = await this.request.post(`/api/meta/models/${modelPid}/fields/bind`, {
      data: binding,
    });
    return response.json();
  }

  /**
   * Batch bind fields to model
   */
  async batchBindFieldsToModel(
    modelPid: string,
    fieldPids: string[],
    commonConfig?: Partial<FieldBindingTestData>,
  ): Promise<ApiResponse<any[]>> {
    const response = await this.request.post(`/api/meta/models/${modelPid}/fields/bind-batch`, {
      data: {
        fieldPids,
        ...commonConfig,
      },
    });
    return response.json();
  }

  /**
   * Unbind field from model
   */
  async unbindFieldFromModel(modelPid: string, fieldCode: string): Promise<ApiResponse<void>> {
    const response = await this.request.delete(
      `/api/meta/models/${modelPid}/fields/${fieldCode}/unbind`,
    );
    return response.json();
  }

  // ============================================================================
  // Dictionary APIs
  // ============================================================================

  /**
   * Create a dictionary
   */
  async createDict(data: DictTestData): Promise<ApiResponse<DictResponse>> {
    const response = await this.request.post(`/api/meta/dict`, {
      data,
    });
    return response.json();
  }

  /**
   * Get dictionary by PID
   */
  async getDictByPid(pid: string): Promise<ApiResponse<DictResponse>> {
    const response = await this.request.get(`/api/meta/dict/${pid}`);
    return response.json();
  }

  /**
   * Get dictionary by code
   */
  async getDictByCode(code: string): Promise<ApiResponse<DictResponse>> {
    const response = await this.request.get(`/api/meta/dict/by-code/${code}`);
    return response.json();
  }

  /**
   * Update dictionary
   */
  async updateDict(pid: string, data: Partial<DictTestData>): Promise<ApiResponse<DictResponse>> {
    const response = await this.request.put(`/api/meta/dict/${pid}`, {
      data,
    });
    return response.json();
  }

  /**
   * Delete dictionary
   */
  async deleteDict(pid: string): Promise<ApiResponse<void>> {
    const response = await this.request.delete(`/api/meta/dict/${pid}`);
    return response.json();
  }

  /**
   * Publish dictionary
   */
  async publishDict(pid: string, versionNote?: string): Promise<ApiResponse<DictResponse>> {
    const params = versionNote ? `?versionNote=${encodeURIComponent(versionNote)}` : '';
    const response = await this.request.post(`/api/meta/dict/${pid}/publish${params}`);
    return response.json();
  }

  /**
   * Get dictionary version history
   */
  async getDictVersionHistory(code: string): Promise<ApiResponse<DictResponse[]>> {
    const response = await this.request.get(`/api/meta/dict/${code}/versions`);
    return response.json();
  }

  /**
   * Unpublish dictionary (published -> deprecated)
   */
  async unpublishDict(pid: string): Promise<ApiResponse<DictResponse>> {
    const response = await this.request.post(`/api/meta/dict/${pid}/unpublish`);
    return response.json();
  }

  /**
   * Create a new version of dictionary (creates draft copy)
   */
  async createDictVersion(pid: string, versionNote?: string): Promise<ApiResponse<DictResponse>> {
    const params = versionNote ? `?versionNote=${encodeURIComponent(versionNote)}` : '';
    const response = await this.request.post(`/api/meta/dict/${pid}/version${params}`);
    return response.json();
  }

  /**
   * Get dictionary cascade tree
   */
  async getDictCascadeTree(pid: string): Promise<ApiResponse<any>> {
    const response = await this.request.get(`/api/meta/dict/${pid}/cascade/tree`);
    return response.json();
  }

  /**
   * Bind dictionary to field
   */
  async bindDictToField(fieldPid: string, dictCode: string): Promise<ApiResponse<void>> {
    const response = await this.request.post(`/api/meta/fields/${fieldPid}/bind-dict`, {
      data: { dictCode },
    });
    return response.json();
  }

  /**
   * Unbind dictionary from field
   */
  async unbindDictFromField(fieldPid: string): Promise<ApiResponse<void>> {
    const response = await this.request.delete(`/api/meta/fields/${fieldPid}/unbind-dict`);
    return response.json();
  }

  // ============================================================================
  // Utility Methods
  // ============================================================================

  /**
   * Check if response is successful
   * Handles both response formats:
   * - { code: "0", ... } - standard format
   * - { success: true, ... } - alternative format
   */
  isSuccess<T>(response: ApiResponse<T>): boolean {
    // Check standard format first
    if (response.code !== undefined) {
      return response.code === ErrorCodes.SUCCESS;
    }
    // Check alternative format
    if (response.success !== undefined) {
      return response.success === true;
    }
    // If neither, check if data exists
    return response.data !== null && response.data !== undefined;
  }

  /**
   * Get data from response or throw error
   */
  getData<T>(response: ApiResponse<T>, errorMessage: string): T {
    if (this.isSuccess(response) && response.data !== null) {
      return response.data;
    }
    throw new Error(response.desc || response.message || errorMessage);
  }
}
