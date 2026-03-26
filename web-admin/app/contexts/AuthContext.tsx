import React, { createContext, useContext, useMemo } from 'react';
import { useLoaderData } from 'react-router';
import type { User, UserPermissions } from '~/utils/type';

/**
 * Permission item from legacy format
 */
interface PermissionItem {
  code: string;
  name?: string;
}

/**
 * Role item from permissions data
 */
interface RoleItem {
  code: string;
  name?: string;
}

/**
 * Loader data structure for authenticated routes
 */
interface AuthLoaderData {
  user: User | null;
  permissions: {
    permissionCodes?: string[];
    permissions?: PermissionItem[];
    roles?: RoleItem[];
  } | null;
}

interface AuthContextType {
  user: User | null;
  permissions: UserPermissions | null;
  token: string | null;
  isAuthenticated: boolean;
  hasPermission: (permissionCode: string) => boolean;
  hasRole: (roleCode: string) => boolean;
  hasAnyPermission: (permissionCodes: string[]) => boolean;
  hasAllPermissions: (permissionCodes: string[]) => boolean;
}

const AuthContext = createContext<AuthContextType>({
  user: null,
  permissions: null,
  token: null,
  isAuthenticated: false,
  hasPermission: () => false,
  hasRole: () => false,
  hasAnyPermission: () => false,
  hasAllPermissions: () => false,
});

/**
 * AuthProvider - SSR-safe authentication context
 *
 * Design principles:
 * 1. Does not store token (token belongs to session, stored in Cookie)
 * 2. Uses initialData to ensure SSR/CSR consistency
 * 3. Permission check functions use useMemo for performance optimization
 */
export function AuthProvider({ children }: { children: React.ReactNode }) {
  // SSR-safe: get initial data from loader
  const data = useLoaderData() as AuthLoaderData | undefined;

  // Permission check functions
  // Supports two formats:
  // 1. permissionCodes: ["code1", "code2"] - string array (current backend format)
  // 2. permissions: [{ code: "code1" }] - object array (legacy format)
  const hasPermission = useMemo(() => {
    return (permissionCode: string): boolean => {
      if (!data?.permissions) return false;
      // Prefer permissionCodes (string array)
      const permissionCodes = data.permissions.permissionCodes;
      if (Array.isArray(permissionCodes)) {
        return permissionCodes.includes(permissionCode);
      }
      // Support legacy format: permissions object array
      const permissions = data.permissions.permissions;
      if (Array.isArray(permissions)) {
        return permissions.some((p) => p.code === permissionCode);
      }
      return false;
    };
  }, [data?.permissions]);

  const hasRole = useMemo(() => {
    return (roleCode: string): boolean => {
      if (!data?.permissions?.roles) return false;
      return data.permissions.roles.some((r) => r.code === roleCode);
    };
  }, [data?.permissions]);

  const hasAnyPermission = useMemo(() => {
    return (permissionCodes: string[]): boolean => {
      return permissionCodes.some((code) => hasPermission(code));
    };
  }, [hasPermission]);

  const hasAllPermissions = useMemo(() => {
    return (permissionCodes: string[]): boolean => {
      return permissionCodes.every((code) => hasPermission(code));
    };
  }, [hasPermission]);

  const value = useMemo(
    () => ({
      user: data?.user || null,
      permissions: data?.permissions
        ? {
            permissionCodes: data.permissions.permissionCodes,
            permissions: data.permissions.permissions?.map((permission, index) => ({
              id: (permission as { id?: number }).id ?? index + 1,
              code: permission.code,
              name: permission.name ?? permission.code,
              type: (permission as { type?: string }).type ?? 'custom',
            })),
            roles: (data.permissions.roles || []).map((role, index) => ({
              id: (role as { id?: number }).id ?? index + 1,
              code: role.code,
              name: role.name ?? role.code,
              type: (role as { type?: string }).type ?? 'custom',
            })),
          }
        : null,
      token: data?.user?.jwt ?? null,
      isAuthenticated: !!data?.user,
      hasPermission,
      hasRole,
      hasAnyPermission,
      hasAllPermissions,
    }),
    [data?.user, data?.permissions, hasPermission, hasRole, hasAnyPermission, hasAllPermissions],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
}

/**
 * Get token hook
 *
 * Note: Token is not stored in Context, but in Cookie
 * If you need to get token on client, read from Cookie
 *
 * @deprecated Not recommended to use token directly on client
 * When calling API, the browser will automatically include Cookie
 */
export function useToken() {
  console.warn('useToken is deprecated. Token should be read from Cookie, not Context.');
  return null;
}

/**
 * Hook for getting user information
 */
export function useUser() {
  const { user, isAuthenticated } = useAuth();
  return { user, isAuthenticated };
}

/**
 * Hook for permission checking
 */
export function usePermissions() {
  const { permissions, hasPermission, hasRole, hasAnyPermission, hasAllPermissions } = useAuth();
  return { permissions, hasPermission, hasRole, hasAnyPermission, hasAllPermissions };
}

/**
 * Single permission check hook (more elegant way)
 *
 * @example
 * ```tsx
 * const canCreate = usePermission('user:create');
 *
 * {canCreate && <button>Create User</button>}
 * ```
 */
export function usePermission(permissionCode: string): boolean {
  const { hasPermission } = useAuth();
  return hasPermission(permissionCode);
}

/**
 * Role check hook
 *
 * @example
 * ```tsx
 * const isAdmin = useRole('admin');
 *
 * {isAdmin && <AdminPanel />}
 * ```
 */
export function useRole(roleCode: string): boolean {
  const { hasRole } = useAuth();
  return hasRole(roleCode);
}
