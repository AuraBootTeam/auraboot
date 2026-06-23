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
  SavedViewAuditEvent,
  SavedViewCapabilityCheckRequest,
  SavedViewCapabilityCheckResponse,
  SavedViewCreateRequest,
  SavedViewCopyToPersonalRequest,
  SavedViewTeamOption,
  SavedViewUserOption,
  SavedViewUpdateRequest,
  SavedViewQueryParams,
  ViewConfig,
} from '~/framework/smart/types/savedView';

const BASE_URL = '/api/views';

interface TenantMemberUserRecord {
  pid?: string;
  username?: string;
  email?: string;
  realName?: string;
  avatar?: string;
}

interface TenantMemberSearchRecord {
  user?: TenantMemberUserRecord;
  displayName?: string;
  email?: string;
  avatarUrl?: string;
  departmentName?: string;
}

type TenantMemberSearchResult =
  | TenantMemberSearchRecord[]
  | {
      records?: TenantMemberSearchRecord[];
      content?: TenantMemberSearchRecord[];
    };

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
   * Get teams the current user can use when creating TEAM scoped views.
   */
  async getMyTeams(request?: Request): Promise<SavedViewTeamOption[]> {
    const result = await get<Array<SavedViewTeamOption & {
      teamPid?: string;
      teamName?: string;
      teamCode?: string;
    }>>(
      `${BASE_URL}/my-teams`,
      undefined,
      undefined,
      request,
    );
    const teams = handleResponse(result, 'Failed to fetch saved view teams');
    return teams
      .map((team) => ({
        pid: team.pid ?? team.teamPid ?? '',
        name: team.name ?? team.teamName ?? team.teamCode ?? team.pid ?? team.teamPid ?? '',
        role: team.role,
        memberCount: team.memberCount,
      }))
      .filter((team) => team.pid);
  }

  /**
   * Search tenant users for SavedView collaborator assignment.
   */
  async searchUsers(
    keyword: string,
    size = 10,
    request?: Request,
  ): Promise<SavedViewUserOption[]> {
    const result = await post<TenantMemberSearchResult>(
      '/api/tenant/members/search',
      {
        pageNum: 1,
        pageSize: size,
        status: 'active',
        ...(keyword.trim() ? { keyword: keyword.trim() } : {}),
      },
      undefined,
      request,
    );
    const data = handleResponse(result, 'Failed to search users');
    const records = Array.isArray(data) ? data : data.records ?? data.content ?? [];
    return records.flatMap((record): SavedViewUserOption[] => {
      const pid = record.user?.pid;
      if (!pid) {
        return [];
      }
      return [
        {
          pid,
          displayName:
            record.displayName ||
            record.user?.realName ||
            record.user?.username ||
            record.user?.email ||
            pid,
          email: record.email || record.user?.email,
          avatarUrl: record.avatarUrl || record.user?.avatar,
          departmentName: record.departmentName,
        },
      ];
    });
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
   * Copy any accessible view into the current user's personal scope.
   */
  async copyToPersonal(
    pid: string,
    data: SavedViewCopyToPersonalRequest = {},
    request?: Request,
  ): Promise<SavedView> {
    const result = await post<SavedView>(
      `${BASE_URL}/${pid}/copy-to-personal`,
      data,
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to copy view to personal scope');
  }

  /**
   * Get audit events for a visible shared/global view.
   */
  async getAuditEvents(pid: string, request?: Request): Promise<SavedViewAuditEvent[]> {
    const result = await get<SavedViewAuditEvent[]>(
      `${BASE_URL}/${pid}/audit-events`,
      undefined,
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to fetch saved view audit events');
  }

  /**
   * Check whether a view type has the required backend field mapping before saving.
   */
  async checkCapability(
    data: SavedViewCapabilityCheckRequest,
    request?: Request,
  ): Promise<SavedViewCapabilityCheckResponse> {
    const result = await post<SavedViewCapabilityCheckResponse>(
      `${BASE_URL}/capability-check`,
      data,
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to check saved view capability');
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
