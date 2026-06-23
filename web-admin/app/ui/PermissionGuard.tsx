/**
 * Permission guard component.
 *
 * Controls whether UI is shown based on the current user's permissions.
 */

import React from 'react';
import { usePermissions } from '~/contexts/AuthContext';
import { useSmartText } from '~/utils/i18n';

interface PermissionGuardProps {
  children: React.ReactNode;
  /** Required permission code */
  permission?: string;
  /** Required permission codes (any of) */
  anyPermission?: string[];
  /** Required permission codes (all of) */
  allPermissions?: string[];
  /** Required role code */
  role?: string;
  /** Content shown when the user lacks permission */
  fallback?: React.ReactNode;
}

/**
 * Permission guard component.
 *
 * @example
 * ```tsx
 * // Single permission
 * <PermissionGuard permission="user:create">
 *   <button>Create user</button>
 * </PermissionGuard>
 *
 * // Any permission
 * <PermissionGuard anyPermission={["user:create", "user:edit"]}>
 *   <button>Edit user</button>
 * </PermissionGuard>
 *
 * // All permissions
 * <PermissionGuard allPermissions={["user:create", "user:delete"]}>
 *   <button>Bulk operation</button>
 * </PermissionGuard>
 *
 * // Role check
 * <PermissionGuard role="admin">
 *   <button>Admin features</button>
 * </PermissionGuard>
 *
 * // With fallback
 * <PermissionGuard
 *   permission="user:view"
 *   fallback={<div>No permission to view</div>}
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

  // Check single permission
  if (permission && !hasPermission(permission)) {
    hasAccess = false;
  }

  // Check any permission
  if (anyPermission && !hasAnyPermission(anyPermission)) {
    hasAccess = false;
  }

  // Check all permissions
  if (allPermissions && !hasAllPermissions(allPermissions)) {
    hasAccess = false;
  }

  // Check role
  if (role && !hasRole(role)) {
    hasAccess = false;
  }

  if (!hasAccess) {
    return <>{fallback}</>;
  }

  return <>{children}</>;
}

/**
 * Permission button component.
 * Disables the button automatically based on permissions.
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
  const st = useSmartText();

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
      title={!hasAccess ? st('$i18n:permission_guard.no_access', 'No permission') : props.title}
    >
      {children}
    </button>
  );
}
