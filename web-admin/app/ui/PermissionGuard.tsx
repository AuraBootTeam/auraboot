/**
 * 权限守卫组件
 *
 * 用于在UI中根据权限控制组件的显示
 */

import React from 'react';
import { usePermissions } from '~/contexts/AuthContext';

interface PermissionGuardProps {
  children: React.ReactNode;
  /** 需要的权限代码 */
  permission?: string;
  /** 需要的权限代码列表（任一） */
  anyPermission?: string[];
  /** 需要的权限代码列表（全部） */
  allPermissions?: string[];
  /** 需要的角色代码 */
  role?: string;
  /** 无权限时显示的内容 */
  fallback?: React.ReactNode;
}

/**
 * 权限守卫组件
 *
 * @example
 * ```tsx
 * // 单个权限
 * <PermissionGuard permission="user:create">
 *   <button>创建用户</button>
 * </PermissionGuard>
 *
 * // 任一权限
 * <PermissionGuard anyPermission={["user:create", "user:edit"]}>
 *   <button>编辑用户</button>
 * </PermissionGuard>
 *
 * // 所有权限
 * <PermissionGuard allPermissions={["user:create", "user:delete"]}>
 *   <button>批量操作</button>
 * </PermissionGuard>
 *
 * // 角色检查
 * <PermissionGuard role="admin">
 *   <button>管理员功能</button>
 * </PermissionGuard>
 *
 * // 带fallback
 * <PermissionGuard
 *   permission="user:view"
 *   fallback={<div>无权限查看</div>}
 * >
 *   <UserList />
 * </PermissionGuard>
 * ```
 */
export function PermissionGuard({
  children,
  permission,
  anyPermission,
  allPermissions,
  role,
  fallback = null,
}: PermissionGuardProps) {
  const { hasPermission, hasRole, hasAnyPermission, hasAllPermissions } = usePermissions();

  let hasAccess = true;

  // 检查单个权限
  if (permission && !hasPermission(permission)) {
    hasAccess = false;
  }

  // 检查任一权限
  if (anyPermission && !hasAnyPermission(anyPermission)) {
    hasAccess = false;
  }

  // 检查所有权限
  if (allPermissions && !hasAllPermissions(allPermissions)) {
    hasAccess = false;
  }

  // 检查角色
  if (role && !hasRole(role)) {
    hasAccess = false;
  }

  if (!hasAccess) {
    return <>{fallback}</>;
  }

  return <>{children}</>;
}

/**
 * 权限按钮组件
 * 自动根据权限禁用按钮
 */
interface PermissionButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  permission?: string;
  anyPermission?: string[];
  allPermissions?: string[];
  role?: string;
  children: React.ReactNode;
}

export function PermissionButton({
  permission,
  anyPermission,
  allPermissions,
  role,
  children,
  disabled,
  ...props
}: PermissionButtonProps) {
  const { hasPermission, hasRole, hasAnyPermission, hasAllPermissions } = usePermissions();

  let hasAccess = true;

  if (permission && !hasPermission(permission)) {
    hasAccess = false;
  }

  if (anyPermission && !hasAnyPermission(anyPermission)) {
    hasAccess = false;
  }

  if (allPermissions && !hasAllPermissions(allPermissions)) {
    hasAccess = false;
  }

  if (role && !hasRole(role)) {
    hasAccess = false;
  }

  return (
    <button
      {...props}
      disabled={disabled || !hasAccess}
      title={!hasAccess ? '无权限' : props.title}
    >
      {children}
    </button>
  );
}
