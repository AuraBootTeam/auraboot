import { get, post, put, del, fetchResult } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';
import type { Permission, PermissionReference, IPermissionService } from '~/types/model';
import type {
  PermissionMatrixDTO,
  PermissionGrantRequest,
  RoleMemberDTO,
  PaginationResult,
} from '~/routes/enterprise/permission/types';

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
 * Permission服务类
 * 封装所有权限点管理相关的API调用
 */
export class PermissionService implements IPermissionService {
  private baseUrl = '/api/permissions';

  /**
   * 获取Model的权限点列表
   */
  async getModelPermissions(modelCode: string, request?: Request): Promise<Permission[]> {
    const result = await get<Permission[]>(
      `${this.baseUrl}/model/${modelCode}`,
      undefined,
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to fetch model permissions');
  }

  /**
   * 获取所有权限点(按资源类型分组)
   */
  async getAllPermissions(request?: Request): Promise<Record<string, Permission[]>> {
    const result = await get<Record<string, Permission[]>>(
      this.baseUrl,
      undefined,
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to fetch all permissions');
  }

  /**
   * 获取角色的权限点绑定
   */
  async getRolePermissions(roleId: string, request?: Request): Promise<Permission[]> {
    const result = await get<Permission[]>(
      `${this.baseUrl}/role/${roleId}`,
      undefined,
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to fetch role permissions');
  }

  /**
   * 为角色添加权限点
   */
  async bindPermissionToRole(
    roleId: string,
    permissionId: string,
    request?: Request,
  ): Promise<void> {
    const result = await post<void>(
      `${this.baseUrl}/role/${roleId}/bind`,
      { permissionId },
      undefined,
      request,
    );
    if (!ResultHelper.isSuccess(result)) {
      throw new Error(result.desc || 'Failed to bind permission to role');
    }
  }

  /**
   * 为角色移除权限点
   */
  async unbindPermissionFromRole(
    roleId: string,
    permissionId: string,
    request?: Request,
  ): Promise<void> {
    const result = await post<void>(
      `${this.baseUrl}/role/${roleId}/unbind`,
      { permissionId },
      undefined,
      request,
    );
    if (!ResultHelper.isSuccess(result)) {
      throw new Error(result.desc || 'Failed to unbind permission from role');
    }
  }

  /**
   * 获取权限点的引用情况
   */
  async getPermissionReferences(
    permissionId: string,
    request?: Request,
  ): Promise<PermissionReference[]> {
    const result = await get<PermissionReference[]>(
      `${this.baseUrl}/${permissionId}/references`,
      undefined,
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to fetch permission references');
  }

  // ---------------------------------------------------------------------------
  // Matrix API
  // ---------------------------------------------------------------------------

  /**
   * Get permission matrix for a specific role
   */
  async getMatrixForRole(rolePid: string, request?: Request): Promise<PermissionMatrixDTO> {
    const result = await get<PermissionMatrixDTO>(
      `${this.baseUrl}/matrix/${rolePid}`,
      undefined,
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to fetch permission matrix');
  }

  /**
   * Batch update role permissions (grant/revoke)
   */
  async batchUpdateRolePermissions(
    rolePid: string,
    grants: PermissionGrantRequest[],
    request?: Request,
  ): Promise<void> {
    // Use fetchResult directly because `put()` expects Record<string, any>
    // but the API expects a JSON array body
    const result = await fetchResult<void>(
      `${this.baseUrl}/matrix/${rolePid}/batch`,
      {
        method: 'put',
        params: grants as unknown as Record<string, any>,
      },
      request,
    );
    if (!ResultHelper.isSuccess(result)) {
      throw new Error(result.desc || 'Failed to update role permissions');
    }
  }

  /**
   * Update data scope for a specific role + resource + action combination
   */
  async updateScope(
    rolePid: string,
    data: { resourceCode: string; actionCode: string; scopeType: string; mergeStrategy?: string },
    request?: Request,
  ): Promise<void> {
    const result = await put<void>(
      `${this.baseUrl}/matrix/${rolePid}/scope`,
      data as unknown as Record<string, any>,
      undefined,
      request,
    );
    if (!ResultHelper.isSuccess(result)) {
      throw new Error((result as any).desc || 'Failed to update permission scope');
    }
  }

  // ---------------------------------------------------------------------------
  // Policy API
  // ---------------------------------------------------------------------------

  /**
   * Get policy values for a specific role + permission combination
   */
  async getPolicy(
    rolePid: string,
    permissionPid: string,
    request?: Request,
  ): Promise<Record<string, any>> {
    const result = await get<Record<string, any>>(
      `${this.baseUrl}/matrix/${rolePid}/policy/${permissionPid}`,
      undefined,
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to fetch policy values');
  }

  /**
   * Save policy values for a specific role + permission combination
   */
  async setPolicy(
    rolePid: string,
    permissionPid: string,
    values: Record<string, any>,
    request?: Request,
  ): Promise<void> {
    const result = await put<void>(
      `${this.baseUrl}/matrix/${rolePid}/policy/${permissionPid}`,
      values as unknown as Record<string, any>,
      undefined,
      request,
    );
    if (!ResultHelper.isSuccess(result)) {
      throw new Error((result as any).desc || 'Failed to save policy values');
    }
  }

  // ---------------------------------------------------------------------------
  // Role Member API
  // ---------------------------------------------------------------------------

  /**
   * Get paginated members for a role
   */
  async getRoleMembers(
    rolePid: string,
    params: { pageNum: number; pageSize: number },
    request?: Request,
  ): Promise<PaginationResult<RoleMemberDTO>> {
    const result = await get<PaginationResult<RoleMemberDTO>>(
      `/api/roles/${rolePid}/members`,
      params,
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to fetch role members');
  }

  /**
   * Add members to a role
   */
  async addRoleMembers(rolePid: string, memberPids: string[], request?: Request): Promise<void> {
    const result = await fetchResult<void>(`/api/roles/${rolePid}/members`, {
      method: 'post',
      params: memberPids as unknown as Record<string, any>,
    }, request);
    if (!ResultHelper.isSuccess(result)) {
      throw new Error(result.desc || 'Failed to add role members');
    }
  }

  /**
   * Remove members from a role
   */
  async removeRoleMembers(rolePid: string, memberPids: string[], request?: Request): Promise<void> {
    const result = await fetchResult<void>(`/api/roles/${rolePid}/members/remove`, {
      method: 'post',
      params: memberPids as unknown as Record<string, any>,
    }, request);
    if (!ResultHelper.isSuccess(result)) {
      throw new Error(result.desc || 'Failed to remove role members');
    }
  }

  /**
   * Get candidate members that can be added to a role
   */
  async getRoleMemberCandidates(
    rolePid: string,
    keyword?: string,
    request?: Request,
  ): Promise<RoleMemberDTO[]> {
    const params = keyword ? { keyword } : {};
    const result = await get<RoleMemberDTO[]>(
      `/api/roles/${rolePid}/members/candidates`,
      params,
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to fetch role member candidates');
  }
}

/**
 * Permission服务单例实例
 */
export const permissionService = new PermissionService();
