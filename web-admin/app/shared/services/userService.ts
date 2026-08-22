/**
 * 用户信息服务
 *
 * 设计原则（企业级安全）：
 * 1. ❌ 不在前端解析JWT（安全风险）
 * 2. ✅ 所有用户信息从后端API获取（零信任原则）
 * 3. ✅ Token只用于身份验证，不用于获取信息
 * 4. ✅ 权限信息实时从API获取（避免过期）
 */

import { ErrorCodes } from '~/shared/services/http-client/types';
import { ResultHelper, type User, type UserPermissions, type Preferences } from '~/utils/type';
import { getTokenFromRequest } from '~/shared/services/session';
import { fetchResult } from '~/shared/services/http-client';

export class UserInfoUnavailableError extends Error {
  readonly code: string;

  constructor(code: string, message: string) {
    super(message);
    this.name = 'UserInfoUnavailableError';
    this.code = code;
  }
}

/**
 * 从后端API获取用户完整信息（包括权限）
 *
 * 安全原则：
 * - 不在前端解析JWT
 * - 所有信息从后端权威来源获取
 * - Token只用于身份验证
 */
export async function fetchUserInfo(request: Request): Promise<{
  user: User;
  permissions: UserPermissions;
  preferences: Preferences | null;
} | null> {
  const token = await getTokenFromRequest(request);

  if (!token) {
    return null;
  }

  try {
    const result = await fetchResult<{
      user: User;
      permissions?: UserPermissions;
      preferences?: Preferences | null;
    }>('/api/auth/me', { token }, request);

    // 后端返回格式: { code: "0", data: { user: {...}, permissions: {...} } }
    if (!ResultHelper.isSuccess(result) || !result.data) {
      console.error('Invalid response from /api/auth/me:', result);
      if (result.httpStatus === 401 || result.code === ErrorCodes.UNAUTHORIZED) {
        return null;
      }
      throw new UserInfoUnavailableError(
        result.code,
        result.message || result.desc || 'Unable to resolve the authenticated user',
      );
    }

    return {
      user: result.data.user,
      permissions: result.data.permissions || {
        roles: [],
        permissions: [],
      },
      preferences: result.data.preferences || null,
    };
  } catch (error) {
    if (error instanceof UserInfoUnavailableError) {
      throw error;
    }
    console.error('Error fetching user info:', error);
    throw new UserInfoUnavailableError(
      ErrorCodes.NETWORK_ERROR,
      error instanceof Error ? error.message : 'Unable to resolve the authenticated user',
    );
  }
}

/**
 * 获取用户信息
 *
 * 策略：
 * - 始终从后端API获取（安全第一）
 * - 不解析JWT（零信任原则）
 */
export async function getUserInfo(request: Request): Promise<{
  user: User | null;
  permissions: UserPermissions | null;
  preferences: Preferences | null;
}> {
  const fullInfo = await fetchUserInfo(request);

  if (!fullInfo) {
    return { user: null, permissions: null, preferences: null };
  }

  return {
    user: fullInfo.user,
    permissions: fullInfo.permissions,
    preferences: fullInfo.preferences,
  };
}

/**
 * 检查用户是否有指定权限
 */
export function hasPermission(
  permissions: UserPermissions | undefined,
  permissionCode: string,
): boolean {
  if (!permissions) {
    return false;
  }

  if (permissions.permissionCodes?.includes(permissionCode)) {
    return true;
  }

  return permissions.permissions?.some((p) => p.code === permissionCode) ?? false;
}

/**
 * 检查用户是否有指定角色
 */
export function hasRole(permissions: UserPermissions | undefined, roleCode: string): boolean {
  if (!permissions) {
    return false;
  }

  return permissions.roles.some((r) => r.code === roleCode);
}

/**
 * 检查用户是否有任一权限
 */
export function hasAnyPermission(
  permissions: UserPermissions | undefined,
  permissionCodes: string[],
): boolean {
  return permissionCodes.some((code) => hasPermission(permissions, code));
}

/**
 * 检查用户是否有所有权限
 */
export function hasAllPermissions(
  permissions: UserPermissions | undefined,
  permissionCodes: string[],
): boolean {
  return permissionCodes.every((code) => hasPermission(permissions, code));
}
