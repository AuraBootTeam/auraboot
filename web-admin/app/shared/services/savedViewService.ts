/**
 * SavedView Service
 *
 * API service for managing user saved views.
 * Provides CRUD operations and utility methods for view management.
 */

import { get, post, put, del } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import type {
  SavedView,
  SavedViewCreateRequest,
  SavedViewUpdateRequest,
  SavedViewQueryParams,
  ViewConfig,
} from '~/framework/smart/types/savedView';

const BASE_URL = '/api/views';

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
 * SavedView Service Class
 *
 * Encapsulates all SavedView-related API calls
 */
export class SavedViewService {
  /**
   * Get a single view by PID
   */
  async getView(pid: string, request?: Request): Promise<SavedView> {
    const result = await get<SavedView>(`${BASE_URL}/${pid}`, undefined, undefined, request);
    return handleResponse(result, 'Failed to fetch view');
  }

  /**
   * Get all accessible views for the current user
   * Includes personal views and global views
   */
  async getAccessibleViews(params: SavedViewQueryParams, request?: Request): Promise<SavedView[]> {
    const result = await get<SavedView[]>(`${BASE_URL}/accessible`, params, undefined, request);
    return handleResponse(result, 'Failed to fetch accessible views');
  }

  /**
   * Get personal views for the current user
   */
  async getPersonalViews(params: SavedViewQueryParams, request?: Request): Promise<SavedView[]> {
    const result = await get<SavedView[]>(`${BASE_URL}/personal`, params, undefined, request);
    return handleResponse(result, 'Failed to fetch personal views');
  }

  /**
   * Get global views
   */
  async getGlobalViews(params: SavedViewQueryParams, request?: Request): Promise<SavedView[]> {
    const result = await get<SavedView[]>(`${BASE_URL}/global`, params, undefined, request);
    return handleResponse(result, 'Failed to fetch global views');
  }

  /**
   * Get the default view for a model/page
   * Returns null if no default view exists (not an error condition)
   */
  async getDefaultView(params: SavedViewQueryParams, request?: Request): Promise<SavedView | null> {
    const result = await get<SavedView>(`${BASE_URL}/default`, params, undefined, request);
    // Return null instead of throwing error when no default view exists
    if (ResultHelper.isSuccess(result)) {
      return result.data;
    }
    // Only return null for "not found" scenarios, throw for real errors
    if (result.code === '404' || result.desc?.toLowerCase().includes('not found')) {
      return null;
    }
    throw new Error(result.desc || 'Failed to fetch default view');
  }

  /**
   * Create a new view
   */
  async createView(data: SavedViewCreateRequest, request?: Request): Promise<SavedView> {
    const result = await post<SavedView>(BASE_URL, data, undefined, request);
    return handleResponse(result, 'Failed to create view');
  }

  /**
   * Auto-save view config (atomic upsert of implicit view).
   * Backend finds or creates an implicit personal view for the current user/model/page.
   */
  async autoSave(
    data: { modelCode: string; pageKey?: string; viewConfig?: Partial<ViewConfig> },
    request?: Request,
  ): Promise<SavedView> {
    const result = await post<SavedView>(`${BASE_URL}/auto-save`, data, undefined, request);
    return handleResponse(result, 'Failed to auto-save view');
  }

  /**
   * Update an existing view
   */
  async updateView(
    pid: string,
    data: SavedViewUpdateRequest,
    request?: Request,
  ): Promise<SavedView> {
    const result = await put<SavedView>(`${BASE_URL}/${pid}`, data, undefined, request);
    return handleResponse(result, 'Failed to update view');
  }

  /**
   * Delete a view
   */
  async deleteView(pid: string, request?: Request): Promise<void> {
    const result = await del<void>(`${BASE_URL}/${pid}`, undefined, undefined, request);
    if (!ResultHelper.isSuccess(result)) {
      throw new Error(result.desc || 'Failed to delete view');
    }
  }

  /**
   * Set a view as the default for its model/page
   */
  async setDefaultView(pid: string, request?: Request): Promise<SavedView> {
    const result = await post<SavedView>(
      `${BASE_URL}/${pid}/set-default`,
      undefined,
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to set default view');
  }

  /**
   * Duplicate an existing view with a new name
   */
  async duplicateView(pid: string, newName: string, request?: Request): Promise<SavedView> {
    const result = await post<SavedView>(
      `${BASE_URL}/${pid}/duplicate`,
      { name: newName },
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to duplicate view');
  }

  /**
   * Check if a view name is unique within a model/page
   * Returns true if the name is available (unique)
   */
  async checkNameUnique(
    params: {
      modelCode: string;
      name: string;
      pageKey?: string;
      excludePid?: string;
    },
    request?: Request,
  ): Promise<boolean> {
    const result = await get<boolean>(`${BASE_URL}/check-name`, params, undefined, request);
    return handleResponse(result, 'Failed to check name uniqueness');
  }
}

// Export singleton instance
export const savedViewService = new SavedViewService();
