package com.auraboot.framework.permission.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.cache.MetaCacheKeyGenerator;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.rbac.entity.UserRole;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.auraboot.framework.rbac.mapper.UserRoleMapper;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * User Permission Service Implementation
 * 
 * <p>Implements L1 cache for user permissions with automatic eviction.
 * 
 * <p>Cache Hierarchy:
 * <pre>
 * L1: user-permissions:{userId} (TTL: 30min)
 *     ↓
 * L2: subject-evaluation:{subjectType}:{subjectId}:{userId} (TTL: 15min)
 *     ↓
 * L3: subject-declarations:{subjectType}:{subjectId} (TTL: 60min)
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
    
    private final RolePermissionMapper rolePermissionMapper;
    private final UserRoleMapper userRoleMapper;
    private final CacheManager cacheManager;
    private final com.auraboot.framework.permission.mapper.PermissionMapper permissionMapper;
    
    private static final String CACHE_NAME = "user-permissions";

    /**
     * Local cache for permissionCode → permissionId mapping.
     * Permission definitions rarely change, so a simple ConcurrentHashMap suffices.
     * Cleared when permissions are modified (e.g., plugin import, permission CRUD).
     */
    private final ConcurrentHashMap<String, Long> permissionCodeCache = new ConcurrentHashMap<>();
    
    /**
     * Get user's permission IDs (with cache)
     * 
     * <p>Cache Strategy:
     * <ul>
     *   <li>Cache Name: user-permissions</li>
     *   <li>Cache Key: userId</li>
     *   <li>TTL: 30 minutes (configured in application.yml)</li>
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
    @Cacheable(value = CACHE_NAME, key = "T(com.auraboot.framework.meta.cache.MetaCacheKeyGenerator).getTenantContextSuffix() + ':' + #userId")
    public Set<Long> getUserPermissionIds(Long userId) {
        log.debug("Loading user permissions from database: userId={}", userId);

        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null) {
            log.warn("Tenant context missing when loading user permissions: userId={}", userId);
            return Collections.emptySet();
        }

        // Phase 2: ab_user_role uses member_id. Get memberId from MetaContext.
        Long memberId = MetaContext.getCurrentMemberId();
        if (memberId == null) {
            log.warn("MemberId not available in MetaContext for userId={}, cannot resolve permissions", userId);
            return Collections.emptySet();
        }

        // 1. Query roles assigned to the member in current tenant
        List<UserRole> userRoles = userRoleMapper.findByMemberIdAndTenantId(memberId, tenantId);
        List<Long> roleIds = userRoles.stream()
            .map(UserRole::getRoleId)
            .filter(Objects::nonNull)
            .distinct()
            .collect(Collectors.toList());

        if (roleIds.isEmpty()) {
            log.debug("Member has no roles: memberId={}, userId={}", memberId, userId);
            return Collections.emptySet();
        }

        log.debug("Member has {} roles: memberId={}, userId={}, roleIds={}",
            roleIds.size(), memberId, userId, roleIds);

        // 2. Query all permissions bound to those roles
        Set<Long> permissionIds = rolePermissionMapper.findPermissionIdsByRoles(roleIds);

        log.debug("Member has {} permissions: memberId={}, userId={}, count={}",
            memberId, userId, permissionIds.size());

        return permissionIds;
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
        Cache cache = cacheManager.getCache(CACHE_NAME);
        if (cache != null) {
            String cacheKey = MetaCacheKeyGenerator.getTenantContextSuffix() + ":" + userId;
            cache.evict(cacheKey);
            log.info("Evicted user permissions cache: userId={}, key={}", userId, cacheKey);
        } else {
            log.warn("Cache not found: {}", CACHE_NAME);
        }
        // Also clear permissionCode→ID cache since permission data may have changed
        clearPermissionCodeCache();
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
     * <p>Implementation:
     * <ol>
     *   <li>Query all users assigned to the role</li>
     *   <li>Evict each user's permission cache</li>
     * </ol>
     * 
     * @param roleId Role ID
     */
    @Override
    public void evictRoleUsers(Long roleId) {
        log.info("Evicting permissions cache for all users of role: roleId={}", roleId);
        
        // 1. Query all members assigned to the role
        List<Long> memberIds = userRoleMapper.findMemberIdsByRoleId(roleId);

        if (memberIds.isEmpty()) {
            log.debug("Role has no members: roleId={}", roleId);
            return;
        }

        log.info("Role has {} members, evicting their caches: roleId={}, memberCount={}",
            memberIds.size(), roleId, memberIds.size());

        // 2. Evict each member's permission cache
        // Note: cache key still uses userId for backward compatibility with hasPermission(userId, ...)
        // The cache eviction here is best-effort; a full cache clear may be needed
        Cache cache = cacheManager.getCache(CACHE_NAME);
        if (cache != null) {
            // Clear all entries since we can't easily map memberId back to userId for cache keys
            cache.clear();
            log.info("Cleared all permission caches for role change: roleId={}", roleId);
        } else {
            log.warn("Cache not found: {}", CACHE_NAME);
        }
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

        // 1. Resolve permissionCode → permissionId (cached)
        String permissionCacheKey = buildPermissionCodeCacheKey(permissionCode);
        Long permissionId = permissionCodeCache.get(permissionCacheKey);
        if (permissionId == null) {
            com.auraboot.framework.permission.entity.Permission permission =
                permissionMapper.findByCode(permissionCode);
            if (permission == null) {
                log.warn("Permission not found: permissionCode={}", permissionCode);
                return false;
            }
            permissionId = permission.getId();
            permissionCodeCache.put(permissionCacheKey, permissionId);
            log.debug("Cached permission mapping: {}={}", permissionCacheKey, permissionId);
        }

        // 2. Check if user has the permission ID
        boolean hasPermission = hasPermission(userId, permissionId);

        log.debug("Permission check result: userId={}, permissionCode={}, hasPermission={}",
            userId, permissionCode, hasPermission);

        return hasPermission;
    }

    /**
     * Clear the permissionCode → permissionId cache.
     * Should be called when permission definitions change (plugin import, permission CRUD).
     */
    public void clearPermissionCodeCache() {
        int size = permissionCodeCache.size();
        permissionCodeCache.clear();
        log.info("Cleared permission code cache: {} entries removed", size);
    }

    private String buildPermissionCodeCacheKey(String permissionCode) {
        return MetaCacheKeyGenerator.getTenantContextSuffix() + ":" + permissionCode;
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
