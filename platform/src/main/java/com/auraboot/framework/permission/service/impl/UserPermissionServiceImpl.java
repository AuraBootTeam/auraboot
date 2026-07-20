package com.auraboot.framework.permission.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.permission.service.UserPermissionService;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * User Permission Service Implementation
 * 
 * <p>Resolves user permissions through {@link PermissionSnapshotCache}.
 * 
 * <p>Cache Hierarchy:
 * <pre>
 * permission-catalog (tenant)
 * user-role-snapshots (tenant + user)
 * role-permission-snapshots (tenant + role + date)
 * user-permissions (tenant + user + date)
 * </pre>
 * 
 * <p>Eviction Strategy:
 * <ul>
 *   <li>Role-Permission changed → Evict all users of that role</li>
 *   <li>User-Role changed → Evict that user</li>
 *   <li>Release rollback → Clear all caches</li>
 * </ul>
 * 
 * @author AuraBoot Platform
 * @since V4
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserPermissionServiceImpl implements UserPermissionService {
    private final PermissionSnapshotCache permissionSnapshotCache;
    
    /**
     * Get user's permission IDs (with cache)
     * 
     * <p>Cache Strategy:
     * <ul>
     *   <li>Cache Name: user-permissions</li>
     *   <li>Cache Key: tenant + user + effective date</li>
     *   <li>TTL: 5 minutes in the dedicated security cache manager</li>
     * </ul>
     * 
     * <p>Query Logic:
     * <ol>
     *   <li>Query all roles assigned to the user</li>
     *   <li>Query all permissions bound to those roles</li>
     *   <li>Filter by GRANT type and effective date</li>
     *   <li>Return distinct permission IDs</li>
     * </ol>
     * 
     * @param userId User ID
     * @return Set of permission IDs
     */
    @Override
    public Set<Long> getUserPermissionIds(Long userId) {
        if (userId == null || !MetaContext.exists()) {
            return Collections.emptySet();
        }
        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null) {
            log.warn("Tenant context missing when loading user permissions: userId={}", userId);
            return Collections.emptySet();
        }
        Long memberId = MetaContext.getCurrentMemberId();
        if (memberId == null) {
            log.warn("MemberId not available in MetaContext for userId={}, cannot resolve permissions", userId);
            return Collections.emptySet();
        }
        return permissionSnapshotCache.getEffectivePermissionIds(tenantId, userId, memberId);
    }

    @Override
    public Set<String> getUserPermissionCodes(Long userId) {
        if (userId == null) {
            return Collections.emptySet();
        }
        if (!MetaContext.exists()) {
            return Collections.emptySet();
        }
        return permissionSnapshotCache.resolvePermissionCodes(
                MetaContext.getCurrentTenantId(), getUserPermissionIds(userId));
    }
    
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
    @Override
    public void evictUserPermissions(Long userId) {
        Long tenantId = MetaContext.exists() ? MetaContext.getCurrentTenantId() : null;
        evictUserPermissions(tenantId, userId);
    }

    @Override
    public void evictUserPermissions(Long tenantId, Long userId) {
        permissionSnapshotCache.evictUser(tenantId, userId);
        log.info("Evicted user permission snapshots: tenantId={}, userId={}", tenantId, userId);
    }
    
    /**
     * Evict all users' permission cache for a specific role
     * 
     * <p>Triggers:
     * <ul>
     *   <li>Role-Permission binding changed</li>
     *   <li>Role deleted</li>
     * </ul>
     * 
     * <p>The role snapshot is evicted directly. Derived effective-user snapshots are cleared because
     * the implicit baseline role has no membership rows and cannot be safely fanned out by query.
     * 
     * @param roleId Role ID
     */
    @Override
    public void evictRoleUsers(Long roleId) {
        Long tenantId = MetaContext.exists() ? MetaContext.getCurrentTenantId() : null;
        evictRoleUsers(tenantId, roleId);
    }

    @Override
    public void evictRoleUsers(Long tenantId, Long roleId) {
        permissionSnapshotCache.evictRole(tenantId, roleId);
        log.info("Evicted role and effective permission snapshots: tenantId={}, roleId={}",
                tenantId, roleId);
    }
    
    /**
     * Batch get users' permission IDs
     * 
     * <p>Optimization for batch operations.
     * 
     * <p>Implementation:
     * <ul>
     *   <li>Check cache first for each user</li>
     *   <li>Load from database for cache misses</li>
     *   <li>Update cache for loaded users</li>
     * </ul>
     * 
     * @param userIds List of user IDs
     * @return Map of userId -> Set of permission IDs
     */
    @Override
    public Map<Long, Set<Long>> batchGetUserPermissionIds(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        
        log.debug("Batch loading user permissions: userCount={}", userIds.size());
        
        Map<Long, Set<Long>> result = new HashMap<>();
        
        // Load permissions for each user (cache will be used automatically)
        for (Long userId : userIds) {
            Set<Long> permissionIds = getUserPermissionIds(userId);
            result.put(userId, permissionIds);
        }
        
        log.debug("Batch loaded {} users' permissions", result.size());
        
        return result;
    }
    
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
    @Override
    @Observed(name = "permission.check", contextualName = "permission-check")
    public boolean hasPermission(Long userId, String permissionCode) {
        if (userId == null || permissionCode == null || permissionCode.isEmpty()) {
            log.warn("Invalid parameters: userId={}, permissionCode={}", userId, permissionCode);
            return false;
        }

        log.debug("Checking permission by code: userId={}, permissionCode={}",
            userId, permissionCode);

        if (!MetaContext.exists()) {
            return false;
        }
        Long permissionId = permissionSnapshotCache.resolvePermissionId(
                MetaContext.getCurrentTenantId(), permissionCode);
        if (permissionId == null) {
            log.debug("Permission check result: userId={}, permissionCode={}, hasPermission=false (unregistered code)",
                userId, permissionCode);
            return false;
        }

        boolean granted = hasPermission(userId, permissionId);
        log.debug("Permission check result: userId={}, permissionCode={}, hasPermission={}",
            userId, permissionCode, granted);
        return granted;
    }

    /**
     * Clear the permissionCode → permissionId cache.
     * Should be called when permission definitions change (plugin import, permission CRUD).
     */
    public void clearPermissionCodeCache() {
        permissionSnapshotCache.clearPermissionCatalogs();
        log.info("Cleared permission catalogs");
    }

    @Override
    public void evictPermissionDefinitions(Long tenantId) {
        permissionSnapshotCache.evictPermissionCatalog(tenantId);
        log.info("Evicted permission catalog: tenantId={}", tenantId);
    }
    
    /**
     * Check if user has specific permission
     * 
     * <p>Convenience method for single permission check.
     * 
     * @param userId User ID
     * @param permissionId Permission ID
     * @return true if user has the permission
     */
    @Override
    public boolean hasPermission(Long userId, Long permissionId) {
        if (userId == null || permissionId == null) {
            return false;
        }
        
        Set<Long> userPermissions = getUserPermissionIds(userId);
        return userPermissions.contains(permissionId);
    }
    
    /**
     * Check if user has all specified permissions (AND logic)
     * 
     * @param userId User ID
     * @param permissionIds List of permission IDs
     * @return true if user has all permissions
     */
    @Override
    public boolean hasAllPermissions(Long userId, List<Long> permissionIds) {
        if (userId == null || permissionIds == null || permissionIds.isEmpty()) {
            return false;
        }
        
        Set<Long> userPermissions = getUserPermissionIds(userId);
        return userPermissions.containsAll(permissionIds);
    }
    
    /**
     * Check if user has any of specified permissions (OR logic)
     * 
     * @param userId User ID
     * @param permissionIds List of permission IDs
     * @return true if user has at least one permission
     */
    @Override
    public boolean hasAnyPermission(Long userId, List<Long> permissionIds) {
        if (userId == null || permissionIds == null || permissionIds.isEmpty()) {
            return false;
        }
        
        Set<Long> userPermissions = getUserPermissionIds(userId);
        
        for (Long permissionId : permissionIds) {
            if (userPermissions.contains(permissionId)) {
                return true;
            }
        }
        
        return false;
    }
}
