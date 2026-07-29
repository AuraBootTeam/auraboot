package com.auraboot.framework.permission.service;

import java.util.List;
import java.util.Set;

/**
 * User Permission Service Interface
 * 
 * Provides user permission caching and cache eviction management.
 * 
 * <p>Key Features:
 * <ul>
 *   <li>Permission snapshots (TTL: 5 minutes with event-driven eviction)</li>
 *   <li>Cache eviction on role binding changes</li>
 *   <li>Batch eviction for role users</li>
 * </ul>
 * 
 * <p>Cache Strategy:
 * <pre>
 * Cache Key: tenant + user + effective date
 * Cache TTL: 5 minutes
 * Eviction Triggers:
 *   - Role-Permission binding changed
 *   - User-Role binding changed
 *   - Release rollback
 * </pre>
 * 
 * @author AuraBoot Platform
 * @since V4
 */
public interface UserPermissionService {
    
    /**
     * Get user's permission IDs (with cache)
     * 
     * <p>This method queries all roles assigned to the user,
     * then retrieves all permissions bound to those roles.
     * 
     * <p>Cache Strategy:
     * <ul>
     *   <li>Cache Name: user-permissions</li>
     *   <li>Cache Key: tenant + user + effective date</li>
     *   <li>TTL: 5 minutes</li>
     * </ul>
     * 
     * @param userId User ID
     * @return Set of permission IDs
     */
    Set<Long> getUserPermissionIds(Long userId);

    /**
     * Get user's permission codes through the service layer.
     *
     * <p>Controllers and other entry points must not resolve permission IDs
     * through mapper access directly. This method keeps the permission lookup
     * path behind the service boundary while reusing the cached ID resolution.
     *
     * @param userId User ID
     * @return Set of permission codes
     */
    Set<String> getUserPermissionCodes(Long userId);
    
    /**
     * Evict user's permission cache
     * 
     * <p>Triggers:
     * <ul>
     *   <li>User-Role binding changed</li>
     *   <li>User deleted</li>
     * </ul>
     * 
     * @param userId User ID
     */
    void evictUserPermissions(Long userId);

    /** Evict one user's role and effective-permission snapshots for an explicit tenant. */
    void evictUserPermissions(Long tenantId, Long userId);
    
    /**
     * Evict all users' permission cache for a specific role
     * 
     * <p>Triggers:
     * <ul>
     *   <li>Role-Permission binding changed</li>
     *   <li>Role deleted</li>
     * </ul>
     * 
     * <p>Implementation:
     * <ol>
     *   <li>Query all users assigned to the role</li>
     *   <li>Evict each user's permission cache</li>
     * </ol>
     * 
     * @param roleId Role ID
     */
    void evictRoleUsers(Long roleId);

    /** Evict one role's grant snapshot and all derived effective-user snapshots. */
    void evictRoleUsers(Long tenantId, Long roleId);

    /** Evict the tenant permission-definition catalog, including cached misses. */
    void evictPermissionDefinitions(Long tenantId);
    
    /**
     * Batch get users' permission IDs
     * 
     * <p>Optimization for batch operations.
     * 
     * @param userIds List of user IDs
     * @return Map of userId -> Set of permission IDs
     */
    java.util.Map<Long, Set<Long>> batchGetUserPermissionIds(List<Long> userIds);
    
    /**
     * Check if user has specific permission by permission code
     * 
     * <p>This method is used by {@link com.auraboot.framework.permission.interceptor.PermissionInterceptor}
     * to check if the current user has the required permission.
     * 
     * <p>Implementation:
     * <ol>
     *   <li>Query permission by code</li>
     *   <li>Check if user has the permission ID</li>
     * </ol>
     * 
     * @param userId User ID
     * @param permissionCode Permission code (e.g., "model.model.manage")
     * @return true if user has the permission
     */
    boolean hasPermission(Long userId, String permissionCode);
    
    /**
     * Check if user has specific permission
     * 
     * <p>Convenience method for single permission check.
     * 
     * @param userId User ID
     * @param permissionId Permission ID
     * @return true if user has the permission
     */
    boolean hasPermission(Long userId, Long permissionId);
    
    /**
     * Check if user has all specified permissions (AND logic)
     * 
     * @param userId User ID
     * @param permissionIds List of permission IDs
     * @return true if user has all permissions
     */
    boolean hasAllPermissions(Long userId, List<Long> permissionIds);
    
    /**
     * Check if user has any of specified permissions (OR logic)
     * 
     * @param userId User ID
     * @param permissionIds List of permission IDs
     * @return true if user has at least one permission
     */
    boolean hasAnyPermission(Long userId, List<Long> permissionIds);
}
