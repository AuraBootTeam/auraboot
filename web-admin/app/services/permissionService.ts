import { get, post } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';
import type { Permission, PermissionReference, IPermissionService } from '~/types/model';

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
}

/**
 * Permission服务单例实例
 */
export const permissionService = new PermissionService();
