/**
 * 用户信息服务
 *
 * 设计原则（企业级安全）：
 * 1. ❌ 不在前端解析JWT（安全风险）
 * 2. ✅ 所有用户信息从后端API获取（零信任原则）
 * 3. ✅ Token只用于身份验证，不用于获取信息
 * 4. ✅ 权限信息实时从API获取（避免过期）
 */

import { ResultHelper } from '~/utils/type';
import { getTokenFromRequest } from '~/services/session';
import type { User, UserPermissions } from '~/utils/type';

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
} | null> {
  const token = await getTokenFromRequest(request);

  if (!token) {
    return null;
  }

  try {
    const apiUrl = process.env.SPRING_BOOT_URL;

    const response = await fetch(`${apiUrl}/api/auth/me`, {
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
      },
    });

    if (!response.ok) {
      console.error('Failed to fetch user info:', response.statusText);
      return null;
    }

    const result = await response.json();

    // 后端返回格式: { code: "0", data: { user: {...}, permissions: {...} } }
    if (!ResultHelper.isSuccess(result) || !result.data) {
      console.error('Invalid response from /api/auth/me:', result);
      return null;
    }

    return {
      user: result.data.user,
      permissions: result.data.permissions || {
        roles: [],
        permissions: [],
      },
    };
  } catch (error) {
    console.error('Error fetching user info:', error);
    return null;
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
}> {
  const fullInfo = await fetchUserInfo(request);

  if (!fullInfo) {
    return { user: null, permissions: null };
  }

  return {
    user: fullInfo.user,
    permissions: fullInfo.permissions,
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
